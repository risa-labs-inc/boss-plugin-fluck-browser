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
import java.util.Collections
import java.util.WeakHashMap
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
        System.getProperty("boss.fluck.nativeAutofill") != "false" &&
            System.getenv("BOSS_NATIVE_AUTOFILL")?.trim()?.lowercase() !in setOf("false", "0", "no", "off")

    /** True once a browser has passed the probe; read from composition, so it is snapshot state. */
    var active: Boolean by mutableStateOf(false)
        private set

    @Volatile private var probeFailed = false

    private var scope = newScope()
    private val mutex = Mutex()
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val captured = CapturedLogins()
    private val fingerprints = ConcurrentHashMap<String, String>()
    private val tabs = Collections.synchronizedMap(WeakHashMap<Any, Tab>())

    @Volatile private var context: PluginContext? = null

    @Volatile private var lastRows: List<SecretEntryWithSharingData> = emptyList()

    @Volatile private var userId: String? = null

    @Volatile private var syncedUserId: String? = null

    @Volatile private var mirrorProfile: Pair<Jx, Any>? = null

    private var shutdownHook: Thread? = null

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun log(msg: String) = println("$TAG $msg")

    /** One browser tab's native-autofill state. */
    private val anchors = Collections.synchronizedMap(WeakHashMap<Any, () -> AutofillAnchor?>())

    private class Tab(
        val browser: Any,
        val jx: Jx,
        val anchors: Map<Any, () -> AutofillAnchor?>,
        @Volatile var windowId: String? = null,
    ) {
        fun anchor(): AutofillAnchor? = anchors[browser]?.invoke()

        @Volatile var dropdown: OpenDropdown? = null

        @Volatile var prompt: SavePrompt? = null
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
            Thread({ clearBlocking("exit", keepIndex = true) }, "fluck-native-autofill-exit").also {
                runCatching { Runtime.getRuntime().addShutdownHook(it) }
            }
    }

    @Synchronized
    fun stop() {
        clearBlocking("plugin disposed")
        shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
        shutdownHook = null
        tabs.values.forEach { t ->
            onEdt {
                t.dropdown?.window?.dispose()
                t.prompt?.dismiss()
            }
        }
        tabs.clear()
        scope.cancel()
        context = null
        mirrorProfile = null
        active = false
    }

    fun requestSync() {
        if (enabled) syncRequests.trySend(Unit)
    }

    // ------------------------------------------------------------------ tabs

    /** Wire native autofill into [handle]'s browser. False when it is unavailable (the JS path stays). */
    @Suppress("ReturnCount")
    fun attach(
        handle: BrowserHandle,
        windowId: String?,
    ): Boolean {
        if (!enabled || probeFailed) return false
        val browser = browserOf(handle) ?: return false
        tabs[browser]?.let { return true }
        val jx =
            Jx.resolve(browser).getOrElse {
                probeFailed = true
                log("WARN JxBrowser surface does not match (${it::class.simpleName}: ${it.message}); using JS autofill")
                return false
            }
        return runCatching {
            val profile = jx.profile(browser)
            if (!jx.isUserProfile(profile)) return false
            val tab = Tab(browser, jx, anchors, windowId = windowId)
            installCallbacks(tab)
            val hardware = jx.hardwareAccelerated(browser)
            if (hardware) installDropdown(tab)
            log("wired into a tab (own dropdown=$hardware)")
            tabs[browser] = tab
            if (mirrorProfile == null) {
                mirrorProfile = jx to profile
                requestSync()
            }
            active = true
            true
        }.getOrElse {
            probeFailed = true
            log("WARN could not wire native autofill (${it::class.simpleName}); using JS autofill")
            false
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
        val tab = tabs.remove(browser) ?: return
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

    /** Arrow / Enter / Escape from the page while our dropdown is open. */
    fun onNavKey(
        handle: BrowserHandle,
        key: String,
    ) {
        val tab = browserOf(handle)?.let { tabs[it] } ?: return
        onEdt {
            val open = tab.dropdown
            when {
                open != null && key == "down" -> open.window.moveSelection(1)
                open != null && key == "up" -> open.window.moveSelection(-1)
                open != null && key == "enter" -> open.window.acceptSelected()
                open != null && key == "escape" -> open.window.cancel()
                open == null && key == "escape" -> tab.prompt?.dismiss()
            }
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
            val plan = MirrorDiff.plan(desired, stores.presentKeys(), owned, fingerprints)
            for (key in plan.remove) {
                runCatching { stores.remove(key) }
                index.remove(path, key)
                fingerprints.remove(key)
            }
            var written = 0
            for (item in plan.write) {
                if (runCatching { stores.write(item, replaceOwned = item.key in owned) }.isSuccess) {
                    index.add(path, item.key)
                    fingerprints[item.key] = item.fingerprint
                    written++
                }
            }
            runCatching { index.save() }
            log(
                "synced rows=${rows.size} mirrored=${desired.size} written=$written " +
                    "removed=${plan.remove.size} skippedForeign=${plan.skippedForeign.size}",
            )
        }

    private suspend fun fetchAll(provider: ai.rever.boss.plugin.api.SecretDataProvider): List<SecretEntryWithSharingData>? {
        val out = mutableListOf<SecretEntryWithSharingData>()
        var offset = 0
        while (offset < MAX_ROWS) {
            val page = provider.getUserSecretsWithSharingInfo(PAGE_SIZE, offset).getOrNull() ?: return null
            out += page.data
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
        }
        return out
    }

    /** The default profile of a live engine, from any attached tab. */
    private fun liveProfile(): Pair<Jx, Any>? {
        val tab = tabs.values.firstOrNull { runCatching { !it.jx.isClosed(it.browser) }.getOrDefault(false) } ?: return null
        return (tab.jx to tab.jx.profile(tab.browser)).also { mirrorProfile = it }
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
    ) = mutex.withLock { clearLocked(reason, keepIndex) }

    /**
     * Removes what the mirror added. The index keeps any key still present afterwards, and on exit
     * keeps them all: removal is not guaranteed to reach disk before the engine stops, and the next
     * start reconciles from the index.
     */
    private fun clearLocked(
        reason: String,
        keepIndex: Boolean = false,
    ) {
        captured.clear()
        lastRows = emptyList()
        syncedUserId = null
        fingerprints.clear()
        val (jx, profile) = mirrorProfile ?: return
        val index = indexFor(jx, profile)
        val path = runCatching { jx.profilePath(profile) }.getOrNull() ?: return
        val keys = index.keys(path)
        if (keys.isEmpty()) return
        val removed =
            runCatching {
                val stores = Stores(jx, profile)
                keys.forEach { key -> runCatching { stores.remove(key) } }
                if (!keepIndex) {
                    val still = runCatching { stores.presentKeys() }.getOrDefault(keys) intersect keys
                    index.clear(path)
                    still.forEach { index.add(path, it) }
                    index.save()
                }
                keys.size
            }.getOrElse { 0 }
        log("cleared reason=$reason removed=$removed")
    }

    private fun clearBlocking(
        reason: String,
        keepIndex: Boolean = false,
    ) {
        runCatching { runBlocking { withTimeoutOrNull(3_000) { clear(reason, keepIndex) } } }
    }

    // ------------------------------------------------------------------ save path

    private fun installCallbacks(tab: Tab) {
        val jx = tab.jx
        jx.setCallback(tab.browser, jx.savePasswordT) { p, a ->
            val (url, login) = jx.savePasswordParams(p)
            onPasswordSave(tab, url, login, update = false, action = a)
        }
        jx.setCallback(tab.browser, jx.updatePasswordT) { p, a ->
            val (url, login) = jx.updatePasswordParams(p)
            onPasswordSave(tab, url, login, update = true, action = a)
        }
        jx.setCallback(tab.browser, jx.saveCardT) { p, a -> onCardSave(tab, jx.saveCardParams(p), a) }
        jx.setCallback(tab.browser, jx.saveProfileT) { p, a -> onAddressSave(tab, jx.fieldsOf(jx.saveProfileParams(p)), a) }
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
                val known = AutofillMapping.map(lastRows).containsKey(key)
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

    private fun installDropdown(tab: Tab) {
        val jx = tab.jx
        jx.setCallback(tab.browser, jx.showSuggestionsT) { p, a ->
            val shown = runCatching { jx.showParams(p) }.getOrElse {
                log("suggestions unreadable (${it::class.simpleName}); cancelled")
                runCatching { jx.cancelSuggestions(a) }
                return@setCallback
            }
            onEdt { showDropdown(tab, shown, a) }
        }
        jx.onEvent(tab.browser, jx.suggestionsUpdatedT) { e ->
            val list = jx.updatedSuggestions(e)
            onEdt {
                tab.dropdown?.let { open ->
                    open.suggestions = list
                    open.window.update(list.map { rowOf(jx, it, open.type, open.site) })
                }
            }
        }
        jx.onEvent(tab.browser, jx.suggestionsHiddenT) {
            onEdt {
                tab.dropdown?.window?.dispose()
                tab.dropdown = null
                navFlag(tab, 0)
            }
        }
        jx.onNavigation(tab.browser, jx.navigationStartedT) { onEdt { tab.dropdown?.window?.cancel() } }
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
            if (anchor == null) log("dropdown cancelled: browser view not on screen (anchor=${anchors.containsKey(tab.browser)})")
            runCatching { jx.cancelSuggestions(action) }
            return
        }
        val site = runCatching { java.net.URI(jx.url(tab.browser)).host.orEmpty().removePrefix("www.") }.getOrDefault("")
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
                    navFlag(tab, 0)
                    val picked = open.suggestions.getOrNull(i)
                    if (picked != null) runCatching { jx.acceptSuggestion(action, picked) } else runCatching { jx.cancelSuggestions(action) }
                },
                onCancel = {
                    if (tab.dropdown === open) tab.dropdown = null
                    navFlag(tab, 0)
                    runCatching { jx.cancelSuggestions(action) }
                },
                onSelection = { selected -> navFlag(tab, if (selected) 2 else 1) },
            )
        open = OpenDropdown(window, shown.suggestions, shown.type, site, action)
        tab.dropdown = open
        val ok = window.show(shown.suggestions.map { rowOf(jx, it, shown.type, site) }, Point(shown.x, shown.y))
        if (ok) navFlag(tab, 1)
        log(if (ok) "dropdown shown type=${shown.type} count=${shown.suggestions.size}" else "dropdown withheld: window not active")
    }

    /** Tells the page script whether arrows/Enter/Escape belong to our dropdown (0 closed, 1 open, 2 row selected). */
    private fun navFlag(
        tab: Tab,
        state: Int,
    ) {
        scope.launch { runCatching { tab.jx.runInMainFrame(tab.browser, AutofillKeys.setState(state)) } }
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

        fun presentKeys(): Set<String> {
            val out = mutableSetOf<String>()
            runCatching { jx.logins(profile).forEach { out += keyOf(it) } }
            runCatching { jx.cards(profile).forEach { out += cardKey(it.last4, it.expiry) } }
            runCatching { jx.udps(profile).forEach { out += addressKey(it.fields) } }
            return out
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
            replaceOwned: Boolean,
        ) {
            if (replaceOwned) remove(item.key)
            when (item) {
                is MirrorItem.Login -> jx.addLogin(profile, item.origin + "/", item.username, item.password)
                is MirrorItem.Card -> jx.addCard(profile, item.number, item.expiry, item.holder)
                is MirrorItem.Address -> jx.addUdp(profile, item.fields)
            }
        }
    }
}
