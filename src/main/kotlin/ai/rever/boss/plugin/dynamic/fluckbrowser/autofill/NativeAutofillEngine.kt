package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import ai.rever.boss.plugin.browser.BrowserHandle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Point
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Native Chromium autofill backed by BOSS Secret Manager, driven from the plugin.
 *
 * Secret Manager is the source of truth; the default profile's password, card and address stores
 * are a mirror the plugin keeps in step. The mirror only removes records it added (an on-disk
 * index of keys, no values) and removes all of them on sign-out, plugin dispose and JVM exit.
 * JxBrowser is reached only through [Jx]; if that surface does not match, this stays off and the
 * plugin's own JS autofill keeps working. Values are never logged.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal object NativeAutofillEngine {
    private const val TAG = "[FluckBrowser] native autofill:"
    private const val PERIODIC_SYNC_MS = 2 * 60_000L
    private const val PAGE_SIZE = 200
    private const val MAX_ROWS = 5_000
    private val SECRET_MANAGER_PANEL = PanelId("secret-manager", 2, "ai.rever.boss.plugin.dynamic.secretmanager")

    /** Kill switch: `-Dboss.fluck.nativeAutofill=false` or `BOSS_NATIVE_AUTOFILL=false`. */
    private val enabled: Boolean =
        !isOff(System.getProperty("boss.fluck.nativeAutofill")) && !isOff(System.getenv("BOSS_NATIVE_AUTOFILL"))

    internal fun isOff(value: String?): Boolean = value?.trim()?.lowercase() in setOf("false", "0", "no", "off")

    @Volatile private var started = false

    @Volatile private var probeFailed = false

    private var scope = newScope()
    private val mutex = Mutex()
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val captured = CapturedLogins()
    private val fingerprints = ConcurrentHashMap<String, String>()
    private val tabs = TabRegistry<Any, Tab>()

    @Volatile private var context: PluginContext? = null

    @Volatile private var lastRows: List<SecretEntryWithSharingData> = emptyList()

    @Volatile private var userId: String? = null

    @Volatile private var syncedUserId: String? = null

    /** Keys of the SM rows seen at the last successful sync; sign-out and quit remove matches too. */
    @Volatile private var lastSyncedKeys: Set<String> = emptySet()

    @Volatile private var mirrorProfile: Pair<Jx, Any>? = null

    private var shutdownHook: Thread? = null

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun log(msg: String) = println("$TAG $msg")

    /** Where each browser's view sits on screen; set and removed by NativeAutofillAnchor. */
    private val anchors = ConcurrentHashMap<Any, () -> AutofillAnchor?>()

    /** One browser tab's native-autofill state. Holds its browser weakly: the browser holds this via its callbacks. */
    private class Tab(
        browser: Any,
        val jx: Jx,
        val anchors: Map<Any, () -> AutofillAnchor?>,
        @Volatile var windowId: String? = null,
    ) {
        private val ref = java.lang.ref.WeakReference(browser)

        val browser: Any? get() = ref.get()

        fun anchor(): AutofillAnchor? = browser?.let { anchors[it]?.invoke() }

        @Volatile var dropdown: OpenDropdown? = null

        @Volatile var prompt: SavePrompt? = null

        /** Everything installed on the browser, so [uninstall] can take it all back. */
        val subscriptions: MutableList<Any> = java.util.concurrent.CopyOnWriteArrayList()

        val callbacks: MutableList<Jx.Installed> = java.util.concurrent.CopyOnWriteArrayList()

        @Volatile var keyHook: Jx.KeyHook? = null
    }

    /** Removes every callback, observer and key hook this plugin put on [tab]'s browser. */
    private fun uninstall(tab: Tab) {
        val browser = tab.browser ?: return
        val jx = tab.jx
        tab.callbacks.forEach { runCatching { jx.restoreCallback(browser, it) } }
        tab.callbacks.clear()
        tab.subscriptions.forEach { runCatching { jx.unsubscribe(it) } }
        tab.subscriptions.clear()
        tab.keyHook?.let { hook -> runCatching { jx.restoreKeys(browser, hook) } }
        tab.keyHook = null
    }

    private class OpenDropdown(
        val window: SuggestionsWindow,
        @Volatile var suggestions: List<Any>,
        val type: String,
        val site: String,
        val action: Any,
    )

    // ------------------------------------------------------------------ lifecycle

    @Synchronized
    fun start(ctx: PluginContext) {
        if (!enabled) return log("disabled by switch")
        if (started) return
        started = true
        context = ctx
        scope = newScope()
        scope.launch {
            for (ignored in syncRequests) {
                runCatching { syncNow() }.onFailure { log("sync failed (${it::class.simpleName})") }
            }
        }
        scope.launch {
            val auth = runCatching { ctx.authDataProvider }.getOrNull() ?: return@launch log("no auth provider")
            auth.currentUser.map { it?.id }.distinctUntilChanged().collect { id ->
                userId = id
                if (id == null) clear("signed out") else requestSync()
            }
        }
        scope.launch {
            while (true) {
                delay(PERIODIC_SYNC_MS)
                requestSync()
            }
        }
        shutdownHook =
            Thread({ clearBlocking("exit", keepIndex = true, timeoutMs = 1_000) }, "fluck-native-autofill-exit").also {
                runCatching { Runtime.getRuntime().addShutdownHook(it) }
            }
    }

    /** Plugin dispose. Blocks the caller at most 1 s: the clear runs on its own thread. */
    @Synchronized
    fun stop() {
        shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
        shutdownHook = null
        val profile = mirrorProfile
        val clearing = Thread({ clearBlocking("plugin disposed", profile = profile) }, "fluck-native-autofill-stop").apply { isDaemon = true }
        clearing.start()
        tabs.live().forEach { t ->
            uninstall(t)
            onEdt {
                t.dropdown?.window?.dispose()
                t.prompt?.dismiss()
            }
        }
        tabs.clear()
        anchors.clear()
        scope.cancel()
        // Bounded, so dispose stays prompt; the clear has read what it needs by then.
        runCatching { clearing.join(1_000) }
        context = null
        mirrorProfile = null
        started = false
    }

    fun requestSync() {
        if (enabled) syncRequests.trySend(Unit)
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Wire native autofill into [handle]'s browser. True when this tab is now native (the caller's JS
     * autofill stands down for this tab only); false when it stays on the JS path. Safe to race
     * [detach]: a detach during wiring makes this undo itself and return false.
     */
    @Suppress("ReturnCount")
    fun attach(
        handle: BrowserHandle,
        windowId: String?,
    ): Boolean {
        if (!enabled || probeFailed) return false
        val browser = browserOf(handle) ?: return false
        if (tabs[browser] != null) return true
        if (!tabs.reserve(browser)) return false
        val jx =
            Jx.resolve(browser).getOrElse {
                tabs.abandon(browser)
                probeFailed = true
                log("WARN JxBrowser surface does not match (${it::class.simpleName}: ${it.message}); using JS autofill")
                return false
            }
        val tab = Tab(browser, jx, anchors, windowId = windowId)
        val wired =
            runCatching {
                val profile = jx.profile(browser)
                if (!jx.isUserProfile(profile)) return@runCatching null
                installCallbacks(tab, browser)
                tab.subscriptions += jx.onEvent(browser, jx.browserClosingT) { onBrowserClosing(browser) }
                val hardware = jx.hardwareAccelerated(browser)
                if (hardware) installDropdown(tab, browser)
                profile to hardware
            }.getOrElse {
                // Per tab (a browser closing while we wire it, say); the surface itself passed the probe.
                uninstall(tab)
                tabs.abandon(browser)
                log("could not wire this tab (${it::class.simpleName}); it keeps JS autofill")
                return false
            }
        if (wired == null) {
            tabs.abandon(browser)
            return false
        }
        if (!tabs.publish(browser, tab)) {
            uninstall(tab)
            return false
        }
        log("wired into a tab (own dropdown=${wired.second})")
        if (mirrorProfile == null) {
            mirrorProfile = jx to wired.first
            requestSync()
        }
        return true
    }

    /** The last tab closing takes the mirror with it (async: never blocks a JxBrowser thread). */
    private fun onBrowserClosing(browser: Any) {
        if (tabs.liveCountExcept(browser) > 0) return
        scope.launch {
            clear("last tab closing", keepIndex = true)
            mirrorProfile = null
        }
    }

    fun setAnchor(
        handle: BrowserHandle,
        anchor: (() -> AutofillAnchor?)?,
    ) {
        val browser = browserOf(handle) ?: return
        if (anchor == null) anchors.remove(browser) else anchors[browser] = anchor
    }

    fun detach(handle: BrowserHandle) {
        val browser = browserOf(handle) ?: return
        val tab = tabs.detach(browser) ?: return
        uninstall(tab)
        onEdt {
            tab.dropdown?.window?.cancel()
            tab.prompt?.dismiss()
        }
    }

    /** A login the page submitted (from the plugin's page-event capture). [pageUrl] is the host's reading. */
    fun recordCapture(
        pageUrl: String,
        username: String,
        password: String,
    ) {
        captured.record(pageUrl, username, password)
    }

    /**
     * A real key press on [tab]'s browser (host PressKeyCallback, so never page-synthesised). True when
     * our dropdown or save card took it. Enter only accepts a row the user highlighted.
     */
    private fun onKey(
        tab: Tab,
        key: String,
    ): Boolean {
        val open = tab.dropdown
        if (open == null) {
            if (key != "KEY_CODE_ESCAPE" || tab.prompt == null) return false
            onEdt { tab.prompt?.dismiss() }
            return true
        }
        return when (key) {
            "KEY_CODE_DOWN" -> true.also { onEdt { open.window.moveSelection(1) } }
            "KEY_CODE_UP" -> true.also { onEdt { open.window.moveSelection(-1) } }
            "KEY_CODE_ESCAPE" -> true.also { onEdt { open.window.cancel() } }
            "KEY_CODE_RETURN" -> open.window.hasSelection.also { if (it) onEdt { open.window.acceptSelected() } }
            else -> false
        }
    }

    private val browserFields = ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()

    /** The JxBrowser Browser behind a host BrowserHandle (its `browser` field). */
    internal fun browserOf(handle: BrowserHandle): Any? =
        runCatching {
            val field =
                browserFields.getOrPut(handle.javaClass) {
                    generateSequence<Class<*>>(handle.javaClass) { it.superclass }
                        .firstNotNullOf { c -> runCatching { c.getDeclaredField("browser") }.getOrNull() }
                        .apply { isAccessible = true }
                }
            field.get(handle)
        }.getOrNull()

    // ------------------------------------------------------------------ mirror

    private suspend fun syncNow() =
        mutex.withLock {
            val ctx = context ?: return@withLock
            if (userId == null) return@withLock clearLocked("no user")
            if (syncedUserId != null && syncedUserId != userId) clearLocked("user changed")
            val (jx, profile) = liveProfile() ?: return@withLock
            // Another user's mirror goes first, from the index alone: it must not wait on a Secret
            // Manager read that may fail.
            val previous = indexFor(jx, profile).owner(jx.profilePath(profile))
            if (previous != null && previous != userId) clearLocked("previous user")
            val provider = ctx.secretDataProvider ?: return@withLock
            val rows = fetchAll(provider) ?: return@withLock log("sync skipped: Secret Manager read failed")
            lastRows = rows
            syncedUserId = userId
            val desired = AutofillMapping.map(rows)
            jx.ensureAutofill(profile)
            val index = indexFor(jx, profile)
            val path = jx.profilePath(profile)
            val owned = index.keys(path)
            val stores = Stores(jx, profile)
            val present = stores.presentKeys() ?: return@withLock log("sync skipped: Chromium stores could not be listed")
            val plan = MirrorDiff.plan(desired, present, owned, fingerprints, index.written(path))
            plan.adopt.forEach { index.add(path, it) }
            lastSyncedKeys = desired.keys
            for (key in plan.remove) {
                runCatching { stores.remove(key) }
                    .onSuccess { index.remove(path, key) }
                    .onFailure { log("remove failed (${key.substringBefore('|')}): ${rootCause(it)}") }
                fingerprints.remove(key)
            }
            var written = 0
            // Indexed before the adds: a crash after this leaves indexed keys, never untracked records.
            plan.write.forEach { index.add(path, it.key) }
            val indexed = runCatching { index.save() }.onFailure { log("index save failed (${rootCause(it)}); no adds this sync") }.isSuccess
            for (item in if (indexed) plan.write else emptyList()) {
                runCatching { stores.write(item, replace = item.key in present) }
                    .onSuccess {
                        fingerprints[item.key] = item.fingerprint
                        written++
                    }.onFailure { log("write failed (${item.key.substringBefore('|')}): ${rootCause(it)}") }
            }
            userId?.let { index.setOwner(path, it) }
            runCatching { index.save() }
            log(
                "synced rows=${rows.size} mirrored=${desired.size} written=$written " +
                    "removed=${plan.remove.size} adopted=${plan.adopt.size}",
            )
        }

    private suspend fun fetchAll(provider: ai.rever.boss.plugin.api.SecretDataProvider): List<SecretEntryWithSharingData>? {
        val out = mutableListOf<SecretEntryWithSharingData>()
        var offset = 0
        while (offset < MAX_ROWS) {
            val page = provider.getUserSecretsWithSharingInfo(PAGE_SIZE, offset).getOrNull() ?: return null
            out += page.data
            if (!page.hasMore || page.data.isEmpty()) return out
            offset += page.data.size
        }
        // Over the cap: a truncated list would remove owned keys past it, so treat it as a failed read.
        log("sync skipped: more than $MAX_ROWS Secret Manager rows")
        return null
    }

    /** The default profile of a live engine, from any attached tab. */
    private fun liveProfile(): Pair<Jx, Any>? {
        for (tab in tabs.live()) {
            val browser = tab.browser ?: continue
            if (runCatching { tab.jx.isClosed(browser) }.getOrDefault(true)) continue
            return (tab.jx to tab.jx.profile(browser)).also { mirrorProfile = it }
        }
        return null
    }

    private val indexes = ConcurrentHashMap<String, MirrorIndex>()

    private fun indexFor(
        jx: Jx,
        profile: Any,
    ): MirrorIndex {
        val dir = File(jx.profilePath(profile)).let { it.parentFile ?: it }
        return indexes.getOrPut(dir.path) { MirrorIndex(File(dir, "boss-native-autofill-index.json")) }
    }

    private suspend fun clear(
        reason: String,
        keepIndex: Boolean = false,
        profile: Pair<Jx, Any>? = null,
    ) = mutex.withLock { clearLocked(reason, keepIndex, profile) }

    /**
     * Removes what the mirror added. The index keeps any key still present afterwards, and on exit
     * keeps them all: removal is not guaranteed to reach disk before the engine stops, and the next
     * start reconciles from the index.
     */
    private fun clearLocked(
        reason: String,
        keepIndex: Boolean = false,
        profileOverride: Pair<Jx, Any>? = null,
    ) {
        captured.clear()
        lastRows = emptyList()
        syncedUserId = null
        fingerprints.clear()
        val synced = lastSyncedKeys
        lastSyncedKeys = emptySet()
        val (jx, profile) = profileOverride ?: mirrorProfile ?: return
        val index = indexFor(jx, profile)
        val path = runCatching { jx.profilePath(profile) }.getOrNull() ?: return
        val stores = Stores(jx, profile)
        val owned = index.keys(path)
        // No listing first: on quit the engine is closing underneath us, so every round trip counts.
        val targets = MirrorDiff.clearSet(owned, synced, owned + synced, index.written(path))
        if (targets.isEmpty()) return
        val failed =
            runCatching { stores.removeAll(targets) }.getOrElse {
                log("remove failed: ${rootCause(it)}")
                targets.size
            }
        // Reading back orders after the removals; null when the store cannot be listed.
        val after = stores.presentKeys()
        val keep = if (keepIndex) targets else MirrorDiff.keepAfterClear(targets, failed, after)
        index.clear(path)
        keep.forEach { index.add(path, it) }
        runCatching { index.save() }
        log("cleared reason=$reason targets=${targets.size} failed=$failed kept=${keep.size}")
    }

    private fun rootCause(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        return root::class.simpleName.orEmpty()
    }

    private fun clearBlocking(
        reason: String,
        keepIndex: Boolean = false,
        timeoutMs: Long = 3_000,
        profile: Pair<Jx, Any>? = null,
    ) {
        val done = runCatching { runBlocking { withTimeoutOrNull(timeoutMs) { clear(reason, keepIndex, profile) } } }.getOrNull()
        if (done == null) log("clear ($reason) did not finish in $timeoutMs ms; the index keeps its keys for the next start")
    }

    // ------------------------------------------------------------------ save path

    private fun installCallbacks(
        tab: Tab,
        browser: Any,
    ) {
        val jx = tab.jx
        // Every path answers Chromium: a handler that throws still ignores / declines.
        fun answered(
            negative: (Any) -> Unit,
            body: (Any, Any) -> Unit,
        ): (Any, Any) -> Unit =
            { p, a ->
                runCatching { body(p, a) }.onFailure {
                    log("save callback failed (${rootCause(it)}); answered negatively")
                    runCatching { negative(a) }
                }
            }
        tab.callbacks +=
            jx.setCallback(
                browser,
                jx.savePasswordT,
                answered({ jx.savePassword(it, "ignore") }) { p, a ->
                    val (url, login) = jx.savePasswordParams(p)
                    onPasswordSave(tab, url, login, update = false, action = a)
                },
            )
        tab.callbacks +=
            jx.setCallback(
                browser,
                jx.updatePasswordT,
                answered({ jx.updatePassword(it, "ignore") }) { p, a ->
                    val (url, login) = jx.updatePasswordParams(p)
                    onPasswordSave(tab, url, login, update = true, action = a)
                },
            )
        tab.callbacks +=
            jx.setCallback(browser, jx.saveCardT, answered({ jx.saveCard(it, false) }) { p, a -> onCardSave(tab, jx.saveCardParams(p), a) })
        tab.callbacks +=
            jx.setCallback(
                browser,
                jx.saveProfileT,
                answered({ jx.saveProfile(it, false) }) { p, a -> onAddressSave(tab, jx.fieldsOf(jx.saveProfileParams(p)), a) },
            )
    }

    private fun canSave(): Boolean = System.getProperty("boss.fluck.offerToSavePasswords") != "false" && userId != null

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun onPasswordSave(
        tab: Tab,
        url: String,
        login: String,
        update: Boolean,
        action: Any,
    ) {
        val jx = tab.jx
        val commit = { runCatching { if (update) jx.updatePassword(action, "update") else jx.savePassword(action, "save") } }
        val ignore = { runCatching { if (update) jx.updatePassword(action, "ignore") else jx.savePassword(action, "ignore") } }
        val origin = AutofillMapping.normalizeOrigin(url)
        if (!canSave() || origin == null) return run { ignore() }
        val capture = captured.take(url, login)
        if (capture == null) {
            log("password save skipped: no captured value for $origin")
            return run { ignore() }
        }
        val key = loginKey(origin, login)
        val existing = findLoginRow(origin, login)
        if (existing != null && existing.password == capture.password) {
            ownKey(key)
            return run { commit() }
        }
        val host = origin.substringAfter("://")
        val updating = update || existing != null
        val request =
            SaveRequest(
                kind = SaveKind.PASSWORD,
                title = if (updating) "Update password?" else "Save password for $host?",
                saveLabel = if (updating) "Update" else "Save",
                allowNever = !update,
                username = login,
                password = capture.password,
            )
        prompt(tab, request) { choice, editedUser ->
            when (choice) {
                SaveChoice.SAVE ->
                    scope.launch {
                        val user = editedUser.ifBlank { login }
                        val edited = !CapturedLogins.sameLogin(user, login)
                        val target = if (edited) findLoginRow(origin, user) else existing
                        if (writeLogin(origin, user, capture.password, target)) {
                            // An edited username is not what Chromium would store; the mirror adds it instead.
                            if (edited) {
                                ignore()
                            } else {
                                ownKey(key)
                                commit()
                            }
                            log("password saved to Secret Manager for $origin (updated=${target != null})")
                        } else {
                            ignore()
                        }
                        requestSync()
                    }
                SaveChoice.NEVER -> runCatching { jx.savePassword(action, "neverSave") }
                SaveChoice.NOT_NOW -> ignore()
            }
        }
    }

    private fun ownKey(key: String) {
        val (jx, profile) = mirrorProfile ?: return
        runCatching {
            val index = indexFor(jx, profile)
            index.add(jx.profilePath(profile), key)
            index.save()
        }
    }

    private fun findLoginRow(
        origin: String,
        login: String,
    ): SecretEntryWithSharingData? =
        lastRows
            .filter { it.isOwner }
            .firstOrNull { row ->
                val item = AutofillMapping.map(row.website, row.username, row.password, row.notes, row.tags)
                item is MirrorItem.Login && item.origin == origin && CapturedLogins.sameLogin(item.username, login)
            }

    private suspend fun writeLogin(
        origin: String,
        login: String,
        password: String,
        existing: SecretEntryWithSharingData?,
    ): Boolean {
        val provider = context?.secretDataProvider ?: return false
        val result =
            if (existing != null) {
                provider.updateSecret(
                    UpdateSecretRequestData(
                        secretId = existing.id,
                        website = existing.website,
                        username = existing.username,
                        password = password,
                        notes = existing.notes,
                        expirationDate = existing.expirationDate,
                        tags = existing.tags,
                        twofaEnabled = existing.metadata?.twofaEnabled ?: false,
                        twofaType = existing.metadata?.twofaType,
                        recoveryCodes = existing.metadata?.recoveryCodes ?: emptyList(),
                    ),
                )
            } else {
                provider.createSecret(AutofillMapping.loginRequest(origin, login, password))
            }
        return result.isSuccess.also { if (!it) log("password save to Secret Manager failed for $origin") }
    }

    @Suppress("ReturnCount")
    private fun onCardSave(
        tab: Tab,
        card: Any,
        action: Any,
    ) {
        val jx = tab.jx
        val decline = { runCatching { jx.saveCard(action, false) } }
        val number = runCatching { jx.cardNumberOf(card) }.getOrDefault("")
        if (!canSave() || number.any { !it.isDigit() } || number.length !in 12..19) return run { decline() }
        val expiry = runCatching { jx.cardExpiryOf(card) }.getOrNull() ?: return run { decline() }
        val holder = runCatching { jx.cardHolderOf(card) }.getOrDefault("")
        val key = cardKey(number.takeLast(4), expiry)
        val brand = AutofillMapping.brandOf(number)
        val request =
            SaveRequest(
                kind = SaveKind.CARD,
                title = "Save card?",
                saveLabel = "Save",
                allowNever = false,
                lines =
                    listOfNotNull(
                        "$brand •••• ${number.takeLast(4)}",
                        "Expires %02d/%02d".format(expiry.monthValue, expiry.year % 100),
                        holder.ifBlank { null },
                    ),
            )
        prompt(tab, request) { choice, _ ->
            if (choice != SaveChoice.SAVE) return@prompt run { decline() }
            scope.launch {
                // Same last four and expiry is not enough: only the same number counts as already saved.
                val known = AutofillMapping.map(lastRows).values.any { it is MirrorItem.Card && it.number == number }
                val provider = context?.secretDataProvider
                val ok = known || provider?.createSecret(AutofillMapping.cardRequest(number, expiry, holder))?.isSuccess == true
                if (ok) {
                    ownKey(key)
                    runCatching { jx.saveCard(action, true) }
                } else {
                    log("card save to Secret Manager failed")
                    decline()
                }
                requestSync()
            }
        }
    }

    private fun onAddressSave(
        tab: Tab,
        fields: AddressFields,
        action: Any,
    ) {
        val jx = tab.jx
        val decline = { runCatching { jx.saveProfile(action, false) } }
        if (!canSave() || fields.isEmpty()) return run { decline() }
        val key = addressKey(fields)
        val request =
            SaveRequest(
                kind = SaveKind.ADDRESS,
                title = "Save address?",
                saveLabel = "Save",
                allowNever = false,
                lines =
                    listOf(
                        fields.name,
                        fields.street,
                        listOf(fields.city, fields.state, fields.zip).filter { it.isNotBlank() }.joinToString(", "),
                    ).filter { it.isNotBlank() },
            )
        prompt(tab, request) { choice, _ ->
            if (choice != SaveChoice.SAVE) return@prompt run { decline() }
            scope.launch {
                val known = AutofillMapping.map(lastRows).containsKey(key)
                val taken = lastRows.mapTo(mutableSetOf()) { it.website }
                val provider = context?.secretDataProvider
                val ok = known || provider?.createSecret(AutofillMapping.addressRequest(fields, taken))?.isSuccess == true
                if (ok) {
                    ownKey(key)
                    runCatching { jx.saveProfile(action, true) }
                } else {
                    log("address save to Secret Manager failed")
                    decline()
                }
                requestSync()
            }
        }
    }

    private fun prompt(
        tab: Tab,
        request: SaveRequest,
        onChoice: (SaveChoice, String) -> Unit,
    ) = onEdt {
        val anchor = tab.anchor()
        if (anchor == null) {
            onChoice(SaveChoice.NOT_NOW, request.username)
            return@onEdt
        }
        tab.prompt?.dismiss()
        lateinit var prompt: SavePrompt
        prompt =
            SavePrompt(anchor, { tab.anchor() }, request) { choice, user ->
                if (tab.prompt === prompt) tab.prompt = null
                log("save prompt answered kind=${request.kind} choice=$choice")
                onChoice(choice, user)
            }
        tab.prompt = prompt
        prompt.show()
        log("save prompt shown kind=${request.kind}")
    }

    // ------------------------------------------------------------------ dropdown

    private fun installDropdown(
        tab: Tab,
        browser: Any,
    ) {
        val jx = tab.jx
        // Keys for the dropdown come from the host's PressKeyCallback (real input only), never the page.
        tab.keyHook = jx.interceptKeys(browser) { key -> onKey(tab, key) }
        tab.callbacks += jx.setCallback(browser, jx.showSuggestionsT) { p, a ->
            val shown = runCatching { jx.showParams(p) }.getOrElse {
                log("suggestions unreadable (${it::class.simpleName}); cancelled")
                runCatching { jx.cancelSuggestions(a) }
                return@setCallback
            }
            onEdt { showDropdown(tab, shown, a) }
        }
        tab.subscriptions += jx.onEvent(browser, jx.suggestionsUpdatedT) { e ->
            val list = jx.updatedSuggestions(e)
            onEdt {
                tab.dropdown?.let { open ->
                    open.suggestions = list
                    open.window.update(list.map { rowOf(jx, it, open.type, open.site) })
                }
            }
        }
        tab.subscriptions += jx.onEvent(browser, jx.suggestionsHiddenT) {
            onEdt {
                tab.dropdown?.window?.dispose()
                tab.dropdown = null
            }
        }
        tab.subscriptions += jx.onNavigation(browser, jx.navigationStartedT) { onEdt { tab.dropdown?.window?.cancel() } }
    }

    private fun showDropdown(
        tab: Tab,
        shown: Jx.Shown,
        action: Any,
    ) {
        val jx = tab.jx
        tab.dropdown?.window?.dispose()
        tab.dropdown = null
        val anchor = tab.anchor()
        if (anchor == null || shown.suggestions.isEmpty()) {
            if (anchor == null) log("dropdown cancelled: browser view not on screen")
            runCatching { jx.cancelSuggestions(action) }
            return
        }
        val site = runCatching { java.net.URI(tab.browser?.let { jx.url(it) }.orEmpty()).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        lateinit var open: OpenDropdown
        val window =
            SuggestionsWindow(
                anchor,
                anchorNow = { tab.anchor() },
                manageLabel =
                    when (shown.type) {
                        "PASSWORDS" -> "Manage passwords"
                        "CREDIT_CARDS" -> "Manage cards"
                        "ADDRESSES" -> "Manage addresses"
                        else -> null
                    },
                onManage = { openManager(tab.windowId) },
                onAccept = { i ->
                    if (tab.dropdown === open) tab.dropdown = null
                    val picked = open.suggestions.getOrNull(i)
                    if (picked != null) runCatching { jx.acceptSuggestion(action, picked) } else runCatching { jx.cancelSuggestions(action) }
                },
                onCancel = {
                    if (tab.dropdown === open) tab.dropdown = null
                    runCatching { jx.cancelSuggestions(action) }
                },
            )
        open = OpenDropdown(window, shown.suggestions, shown.type, site, action)
        tab.dropdown = open
        val ok = window.show(shown.suggestions.map { rowOf(jx, it, shown.type, site) }, Point(shown.x, shown.y))
        log(if (ok) "dropdown shown type=${shown.type} count=${shown.suggestions.size}" else "dropdown withheld: window not active")
    }

    private fun openManager(windowId: String?) {
        val ctx = context ?: return
        val id = windowId ?: runCatching { ctx.windowId }.getOrNull() ?: return
        scope.launch { runCatching { ctx.panelEventProvider?.openPanel(SECRET_MANAGER_PANEL, id) } }
    }

    private fun rowOf(
        jx: Jx,
        s: Any,
        type: String,
        site: String,
    ): SuggestionRow {
        val t = runCatching { jx.suggestionText(s) }.getOrNull() ?: return SuggestionRow("", "", SuggestionKind.OTHER)
        return SuggestionRows.rowOf(t.value, t.hiddenPassword, t.cardInfo, t.address, type, site)
    }

    // ------------------------------------------------------------------ stores

    private class Stores(
        private val jx: Jx,
        private val profile: Any,
    ) {
        private fun keyOf(r: Jx.Login): String = loginKey(AutofillMapping.normalizeOrigin(r.url) ?: r.url, r.login)

        /** Every key Chromium holds, or null when any store cannot be listed. */
        fun presentKeys(): Set<String>? =
            runCatching {
                buildSet {
                    jx.logins(profile).forEach { add(keyOf(it)) }
                    jx.cards(profile).forEach { add(cardKey(it.last4, it.expiry)) }
                    jx.udps(profile).forEach { add(addressKey(it.fields)) }
                }
            }.getOrNull()

        /** Removes every record whose key is in [keys], passwords first; returns how many removals failed. */
        fun removeAll(keys: Set<String>): Int {
            var failed = 0
            fun attempt(block: () -> Unit) {
                runCatching(block).onFailure { failed++ }
            }
            jx.logins(profile).filter { keyOf(it) in keys }.forEach { attempt { jx.removeLogin(profile, it) } }
            jx.cards(profile).filter { cardKey(it.last4, it.expiry) in keys }.forEach { attempt { jx.removeCard(profile, it) } }
            jx.udps(profile).filter { addressKey(it.fields) in keys }.forEach { attempt { jx.removeUdp(profile, it) } }
            return failed
        }

        fun remove(key: String) {
            when {
                key.startsWith("pw|") -> jx.logins(profile).filter { keyOf(it) == key }.forEach { jx.removeLogin(profile, it) }
                key.startsWith("card|") -> jx.cards(profile).filter { cardKey(it.last4, it.expiry) == key }.forEach { jx.removeCard(profile, it) }
                key.startsWith("addr|") -> jx.udps(profile).filter { addressKey(it.fields) == key }.forEach { jx.removeUdp(profile, it) }
            }
        }

        fun write(
            item: MirrorItem,
            replace: Boolean,
        ) {
            if (replace) remove(item.key)
            when (item) {
                is MirrorItem.Login -> jx.addLogin(profile, item.origin + "/", item.username, item.password)
                is MirrorItem.Card -> jx.addCard(profile, item.number, item.expiry, item.holder)
                is MirrorItem.Address -> jx.addUdp(profile, item.fields)
            }
        }
    }
}
