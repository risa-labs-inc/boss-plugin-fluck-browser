package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Month
import java.time.YearMonth

/**
 * The slice of JxBrowser native autofill needs, reached by reflection.
 *
 * The plugin cannot link `com.teamdev.*` (the plugin classloader does not share it, and a bundled
 * copy would be a different class identity), so every type is resolved through the live browser's
 * own classloader and every method is looked up on the public API interface, never an impl class.
 * [resolve] probes the whole surface once; any miss means native autofill stays off.
 */
internal class Jx private constructor(
    private val loader: ClassLoader,
) {
    private fun type(name: String): Class<*> = Class.forName(name, false, loader)

    private fun Class<*>.m(
        name: String,
        vararg params: Class<*>,
    ): Method = getMethod(name, *params)

    private val str = String::class.java
    private val clsCls = Class::class.java

    private val browserT = type("$P.browser.Browser")
    private val callbackT = type("$P.callback.Callback")
    private val observerT = type("$P.event.Observer")
    private val engineT = type("$P.engine.Engine")
    private val engineOptionsT = type("$P.engine.EngineOptions")
    private val profileT = type("$P.profile.Profile")
    private val prefsT = type("$P.profile.ProfilePreferences")
    private val passwordStoreT = type("$P.password.PasswordStore")
    private val recordT = type("$P.password.PasswordRecord")
    private val recordBuilderT = type("$P.password.PasswordRecord\$Builder")
    private val cardsT = type("$P.card.CreditCards")
    private val cardT = type("$P.card.CreditCard")
    private val cardBuilderT = type("$P.card.CreditCard\$Builder")
    private val profilesT = type("$P.user.UserDataProfiles")
    private val udpT = type("$P.user.UserDataProfile")
    private val udpBuilderT = type("$P.user.UserDataProfile\$Builder")
    private val addressT = type("$P.user.Address")
    private val addressBuilderT = type("$P.user.Address\$Builder")
    private val navigationT = type("$P.navigation.Navigation")
    private val frameT = type("$P.frame.Frame")
    private val suggestionT = type("$P.internal.rpc.Suggestion")

    val savePasswordT = type("$CB.SavePasswordCallback")
    val updatePasswordT = type("$CB.UpdatePasswordCallback")
    val saveCardT = type("$CB.SaveCreditCardCallback")
    val saveProfileT = type("$CB.SaveUserDataProfileCallback")
    val showSuggestionsT = type("$CB.internal.ShowSuggestionsCallback")
    val suggestionsUpdatedT = type("$P.browser.event.internal.SuggestionsUpdated")
    val suggestionsHiddenT = type("$P.browser.event.internal.SuggestionsHidden")
    val navigationStartedT = type("$P.navigation.event.NavigationStarted")
    val browserClosingT = type("$P.browser.event.BrowserClosing")
    private val pressKeyT = type("$CB.input.PressKeyCallback")
    private val pressKeyParamsEvent = type("${pressKeyT.name}\$Params").m("event")
    private val keyEventT = type("$P.ui.event.KeyEvent")
    private val keyCode = keyEventT.m("keyCode")
    private val keyModifiers = keyEventT.m("keyModifiers")
    private val modifiersT = type("$P.ui.KeyModifiers")
    private val modifierFlags = listOf("isAltDown", "isControlDown", "isMetaDown", "isShiftDown").map { modifiersT.m(it) }
    private val pressKeyResponseT = type("${pressKeyT.name}\$Response")
    private val suppressResponse = pressKeyResponseT.m("suppress")
    private val proceedResponse = pressKeyResponseT.m("proceed")
    private val pressKeyOn = pressKeyT.methods.first { it.name == "on" && it.parameterCount == 1 }

    private val browserProfile = browserT.m("profile")
    private val browserEngine = browserT.m("engine")
    private val browserUrl = browserT.m("url")
    private val browserNavigation = browserT.m("navigation")
    private val browserMainFrame = browserT.m("mainFrame")
    private val browserIsClosed = browserT.m("isClosed")
    private val advisableSet = browserT.m("set", clsCls, callbackT)
    private val advisableGet = browserT.m("get", clsCls)
    private val advisableRemove = browserT.m("remove", clsCls)
    private val observableOn = browserT.m("on", clsCls, observerT)
    private val navigationOn = navigationT.m("on", clsCls, observerT)
    private val frameExecute = frameT.m("executeJavaScript", str)
    private val engineIsClosed = engineT.m("isClosed")
    private val engineOptions = engineT.m("options")
    private val renderingMode = engineOptionsT.m("renderingMode")

    private val profileIsDefault = profileT.m("isDefault")
    private val profileIsIncognito = profileT.m("isIncognito")
    private val profilePath = profileT.m("path")
    private val profilePasswordStore = profileT.m("passwordStore")
    private val profileCards = profileT.m("creditCards")
    private val profileUdps = profileT.m("userDataProfiles")
    private val profilePrefs = profileT.m("preferences")
    private val prefsAutofillEnabled = prefsT.m("isAutofillEnabled")
    private val prefsEnableAutofill = prefsT.m("enableAutofill")

    private val storeAllSaved = passwordStoreT.m("allSaved")
    private val storeAdd = passwordStoreT.m("add", recordT)
    private val storeRemove = passwordStoreT.m("remove", recordT)
    private val recordNew = recordT.m("newBuilder", str, str)
    private val recordUrl = recordT.m("url")
    private val recordLogin = recordT.m("login")
    private val recordBuilderLogin = recordBuilderT.m("login", str)
    private val recordBuild = recordBuilderT.m("build")

    private val cardsAll = cardsT.m("all")
    private val cardsAdd = cardsT.m("add", cardT)
    private val cardsRemove = cardsT.m("remove", cardT)
    private val cardNew = cardT.m("newBuilder", str, YearMonth::class.java)
    private val cardNumber = cardT.m("number")
    private val cardMonth = cardT.m("expirationMonth")
    private val cardYear = cardT.m("expirationYear")
    private val cardHolder = cardT.m("cardholder")
    private val cardBuilderHolder = cardBuilderT.m("cardholder", str)
    private val cardBuild = cardBuilderT.m("build")

    private val udpsAll = profilesT.m("all")
    private val udpsAdd = profilesT.m("add", udpT)
    private val udpsRemove = profilesT.m("remove", udpT)
    private val udpNew = udpT.m("newBuilder", addressT)
    private val udpGetters = listOf("fullName", "email", "companyName", "phoneNumber").associateWith { udpT.m(it) }
    private val udpAddress = udpT.m("address")
    private val udpBuilderSetters = listOf("fullName", "email", "phoneNumber", "company").associateWith { udpBuilderT.m(it, str) }
    private val udpBuild = udpBuilderT.m("build")
    private val addressNew = addressT.m("newBuilder", str)
    private val addressGetters = listOf("streetAddress", "city", "state", "zip", "countryCode").associateWith { addressT.m(it) }
    private val addressSetters = listOf("streetAddress", "city", "state", "zipCode").associateWith { addressBuilderT.m(it, str) }
    private val addressBuild = addressBuilderT.m("build")

    private val savePasswordParams = params(savePasswordT, "url", "login")
    private val updatePasswordParams = params(updatePasswordT, "url", "login")
    private val saveCardParams = params(saveCardT, "creditCard")
    private val saveProfileParams = params(saveProfileT, "profile")
    private val showParams = params(showSuggestionsT, "suggestions", "location", "popupType")
    private val updatedSuggestions = suggestionsUpdatedT.m("suggestions")

    private val savePasswordAction = actions(savePasswordT, "save", "neverSave", "ignore")
    private val updatePasswordAction = actions(updatePasswordT, "update", "ignore")
    private val saveCardAction = actions(saveCardT, "save", "decline")
    private val saveProfileAction = actions(saveProfileT, "save", "decline")
    private val showAction = actions(showSuggestionsT, "cancel")
    private val showAccept = type("${showSuggestionsT.name}\$Action").m("accept", suggestionT)

    private val suggestionGetters =
        listOf("getValue", "hasHiddenPassword", "getHiddenPassword", "hasCreditCardInfo", "getCreditCardInfo")
            .plus(listOf("hasAddressOrFullname", "getAddressOrFullname"))
            .associateWith { suggestionT.m(it) }

    private fun params(
        callback: Class<*>,
        vararg names: String,
    ): Map<String, Method> = type("${callback.name}\$Params").let { p -> names.associateWith { p.m(it) } }

    private fun actions(
        callback: Class<*>,
        vararg names: String,
    ): Map<String, Method> = type("${callback.name}\$Action").let { a -> names.associateWith { a.m(it) } }

    // ---- browser / profile ----

    fun profile(browser: Any): Any = browserProfile.invoke(browser)

    fun engine(browser: Any): Any = browserEngine.invoke(browser)

    fun url(browser: Any): String = browserUrl.invoke(browser) as? String ?: ""

    fun isClosed(browser: Any): Boolean = browserIsClosed.invoke(browser) as Boolean

    fun engineClosed(engine: Any): Boolean = engineIsClosed.invoke(engine) as Boolean

    fun hardwareAccelerated(browser: Any): Boolean =
        (renderingMode.invoke(engineOptions.invoke(engine(browser))) as? Enum<*>)?.name == "HARDWARE_ACCELERATED"

    fun isUserProfile(profile: Any): Boolean =
        profileIsDefault.invoke(profile) as Boolean && !(profileIsIncognito.invoke(profile) as Boolean)

    fun profilePath(profile: Any): String = profilePath.invoke(profile) as String

    fun ensureAutofill(profile: Any) {
        val prefs = profilePrefs.invoke(profile)
        if (prefsAutofillEnabled.invoke(prefs) != true) prefsEnableAutofill.invoke(prefs)
    }

    /** Runs [script] in the main frame, if there is one. */
    fun runInMainFrame(
        browser: Any,
        script: String,
    ) {
        val frame = (browserMainFrame.invoke(browser) as? java.util.Optional<*>)?.orElse(null) ?: return
        frameExecute.invoke(frame, script)
    }

    // ---- stores ----

    class Login(
        val url: String,
        val login: String,
        val raw: Any,
    )

    class Card(
        val last4: String,
        val expiry: YearMonth,
        val raw: Any,
    )

    class Udp(
        val fields: AddressFields,
        val raw: Any,
    )

    fun logins(profile: Any): List<Login> =
        (storeAllSaved.invoke(profilePasswordStore.invoke(profile)) as List<*>).filterNotNull().map {
            Login(recordUrl.invoke(it) as String, recordLogin.invoke(it) as String, it)
        }

    fun addLogin(
        profile: Any,
        url: String,
        login: String,
        password: String,
    ) {
        val builder = recordBuilderLogin.invoke(recordNew.invoke(null, url, password), login)
        storeAdd.invoke(profilePasswordStore.invoke(profile), recordBuild.invoke(builder))
    }

    fun removeLogin(
        profile: Any,
        record: Login,
    ) {
        storeRemove.invoke(profilePasswordStore.invoke(profile), record.raw)
    }

    fun cards(profile: Any): List<Card> =
        (cardsAll.invoke(profileCards.invoke(profile)) as List<*>).filterNotNull().mapNotNull {
            runCatching { Card(cardNumberOf(it).takeLast(4), cardExpiryOf(it), it) }.getOrNull()
        }

    fun cardNumberOf(card: Any): String = cardNumber.invoke(card) as? String ?: ""

    fun cardExpiryOf(card: Any): YearMonth = YearMonth.of(cardYear.invoke(card) as Int, cardMonth.invoke(card) as Month)

    fun cardHolderOf(card: Any): String = cardHolder.invoke(card) as? String ?: ""

    fun addCard(
        profile: Any,
        number: String,
        expiry: YearMonth,
        holder: String,
    ) {
        var builder = cardNew.invoke(null, number, expiry)
        if (holder.isNotBlank()) builder = cardBuilderHolder.invoke(builder, holder)
        cardsAdd.invoke(profileCards.invoke(profile), cardBuild.invoke(builder))
    }

    fun removeCard(
        profile: Any,
        card: Card,
    ) {
        cardsRemove.invoke(profileCards.invoke(profile), card.raw)
    }

    fun udps(profile: Any): List<Udp> =
        (udpsAll.invoke(profileUdps.invoke(profile)) as List<*>).filterNotNull().map { Udp(fieldsOf(it), it) }

    fun fieldsOf(udp: Any): AddressFields {
        fun g(m: Method?, on: Any?) = on?.let { m?.invoke(it) as? String }.orEmpty()
        val address = udpAddress.invoke(udp)
        return AddressFields(
            name = g(udpGetters["fullName"], udp),
            email = g(udpGetters["email"], udp),
            company = g(udpGetters["companyName"], udp),
            phone = g(udpGetters["phoneNumber"], udp),
            street = g(addressGetters["streetAddress"], address),
            city = g(addressGetters["city"], address),
            state = g(addressGetters["state"], address),
            zip = g(addressGetters["zip"], address),
            country = g(addressGetters["countryCode"], address),
        )
    }

    fun addUdp(
        profile: Any,
        f: AddressFields,
    ) {
        var ab = addressNew.invoke(null, f.country.ifBlank { "US" })
        ab = addressSetters.getValue("streetAddress").invoke(ab, f.street)
        ab = addressSetters.getValue("city").invoke(ab, f.city)
        ab = addressSetters.getValue("state").invoke(ab, f.state)
        ab = addressSetters.getValue("zipCode").invoke(ab, f.zip)
        var ub = udpNew.invoke(null, addressBuild.invoke(ab))
        ub = udpBuilderSetters.getValue("fullName").invoke(ub, f.name)
        ub = udpBuilderSetters.getValue("email").invoke(ub, f.email)
        ub = udpBuilderSetters.getValue("phoneNumber").invoke(ub, f.phone)
        ub = udpBuilderSetters.getValue("company").invoke(ub, f.company)
        udpsAdd.invoke(profileUdps.invoke(profile), udpBuild.invoke(ub))
    }

    fun removeUdp(
        profile: Any,
        udp: Udp,
    ) {
        udpsRemove.invoke(profileUdps.invoke(profile), udp.raw)
    }

    // ---- callbacks and events ----

    /** Installs [on] as the browser's [callbackType] via a Proxy; returns false if JxBrowser refused. */
    /** One callback we installed, and whatever it replaced, so [restoreCallback] can put that back. */
    class Installed(
        val type: Class<*>,
        val proxy: Any,
        val previous: Any?,
    )

    fun setCallback(
        browser: Any,
        callbackType: Class<*>,
        on: (params: Any, action: Any) -> Unit,
    ): Installed {
        val previous = (advisableGet.invoke(browser, callbackType) as? java.util.Optional<*>)?.orElse(null)
        val proxy = proxy(callbackType) { args -> if (args.size == 2) on(args[0]!!, args[1]!!) }
        advisableSet.invoke(browser, callbackType, proxy)
        return Installed(callbackType, proxy, previous)
    }

    /** Puts back what [installed] replaced, but only if ours is still the one installed. */
    fun restoreCallback(
        browser: Any,
        installed: Installed,
    ) {
        val current = (advisableGet.invoke(browser, installed.type) as? java.util.Optional<*>)?.orElse(null)
        if (current !== installed.proxy) return
        if (installed.previous != null) {
            advisableSet.invoke(browser, installed.type, installed.previous)
        } else {
            advisableRemove.invoke(browser, installed.type)
        }
    }

    fun removeCallback(
        browser: Any,
        callbackType: Class<*>,
    ) {
        advisableRemove.invoke(browser, callbackType)
    }

    /** What [interceptKeys] installed, so it can be put back. */
    class KeyHook(
        val proxy: Any,
        val previous: Any?,
    )

    /**
     * Puts [consume] in front of the browser's existing PressKeyCallback (the host's): real key presses
     * only, never anything a page can synthesise. [consume] gets the key name (`KEY_CODE_DOWN`...) for
     * unmodified keys and returns true to suppress it; everything else goes to the host's callback.
     */
    fun interceptKeys(
        browser: Any,
        consume: (String) -> Boolean,
    ): KeyHook {
        val previous = (advisableGet.invoke(browser, pressKeyT) as? java.util.Optional<*>)?.orElse(null)
        val proxy =
            Proxy.newProxyInstance(
                loader,
                arrayOf(pressKeyT),
                InvocationHandler { self, method, args ->
                    when (method.name) {
                        "on" -> {
                            val params = args?.firstOrNull()
                            val taken =
                                params != null &&
                                    runCatching {
                                        val event = pressKeyParamsEvent.invoke(params)
                                        val mods = keyModifiers.invoke(event)
                                        val plain = modifierFlags.none { it.invoke(mods) == true }
                                        plain && consume((keyCode.invoke(event) as Enum<*>).name)
                                    }.getOrDefault(false)
                            when {
                                taken -> suppressResponse.invoke(null)
                                previous != null -> pressKeyOn.invoke(previous, params)
                                else -> proceedResponse.invoke(null)
                            }
                        }
                        "toString" -> "BossNativeAutofill(PressKeyCallback)"
                        "hashCode" -> System.identityHashCode(self)
                        "equals" -> self === args?.firstOrNull()
                        else -> null
                    }
                },
            )
        advisableSet.invoke(browser, pressKeyT, proxy)
        return KeyHook(proxy, previous)
    }

    /** Puts the host's PressKeyCallback back, but only if ours is still the one installed. */
    fun restoreKeys(
        browser: Any,
        hook: KeyHook,
    ) {
        val current = (advisableGet.invoke(browser, pressKeyT) as? java.util.Optional<*>)?.orElse(null)
        if (current !== hook.proxy) return
        if (hook.previous != null) advisableSet.invoke(browser, pressKeyT, hook.previous) else advisableRemove.invoke(browser, pressKeyT)
    }

    /** Cancels a subscription returned by [onEvent] / [onNavigation]. */
    fun unsubscribe(subscription: Any) {
        val m =
            subscription.javaClass.interfaces.firstOrNull { it.name == "$P.event.Subscription" }?.getMethod("unsubscribe")
                ?: subscription.javaClass.getMethod("unsubscribe")
        m.invoke(subscription)
    }

    fun onEvent(
        browser: Any,
        eventType: Class<*>,
        on: (Any) -> Unit,
    ): Any = observableOn.invoke(browser, eventType, proxy(observerT) { args -> args.firstOrNull()?.let(on) })

    fun onNavigation(
        browser: Any,
        eventType: Class<*>,
        on: (Any) -> Unit,
    ): Any = navigationOn.invoke(browserNavigation.invoke(browser), eventType, proxy(observerT) { args -> args.firstOrNull()?.let(on) })

    private fun proxy(
        iface: Class<*>,
        on: (Array<out Any?>) -> Unit,
    ): Any =
        Proxy.newProxyInstance(
            loader,
            arrayOf(iface),
            InvocationHandler { self, method, args ->
                when (method.name) {
                    "on" -> {
                        runCatching { on(args ?: emptyArray()) }.onFailure {
                            println("[FluckBrowser] native autofill: ${iface.simpleName} handler failed (${it::class.simpleName})")
                        }
                        null
                    }
                    "toString" -> "BossNativeAutofill(${iface.simpleName})"
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.firstOrNull()
                    else -> null
                }
            },
        )

    // ---- params / actions ----

    fun savePasswordParams(p: Any): Pair<String, String> = savePasswordParams.str(p, "url") to savePasswordParams.str(p, "login")

    fun updatePasswordParams(p: Any): Pair<String, String> = updatePasswordParams.str(p, "url") to updatePasswordParams.str(p, "login")

    fun saveCardParams(p: Any): Any = saveCardParams.getValue("creditCard").invoke(p)

    fun saveProfileParams(p: Any): Any = saveProfileParams.getValue("profile").invoke(p)

    class Shown(
        val suggestions: List<Any>,
        val x: Int,
        val y: Int,
        val type: String,
    )

    fun showParams(p: Any): Shown {
        val loc = showParams.getValue("location").invoke(p)
        fun coord(name: String): Int = (loc.javaClass.getMethod(name).invoke(loc) as Number).toInt()
        return Shown(
            (showParams.getValue("suggestions").invoke(p) as List<*>).filterNotNull(),
            coord("getX"),
            coord("getY"),
            (showParams.getValue("popupType").invoke(p) as? Enum<*>)?.name.orEmpty(),
        )
    }

    fun updatedSuggestions(event: Any): List<Any> = (updatedSuggestions.invoke(event) as List<*>).filterNotNull()

    class SuggestionText(
        val value: String,
        val hiddenPassword: String?,
        val cardInfo: String?,
        val address: String?,
    )

    fun suggestionText(s: Any): SuggestionText {
        fun get(name: String) = suggestionGetters.getValue(name).invoke(s)
        fun opt(has: String, value: String) = if (get(has) == true) get(value) as? String else null
        return SuggestionText(
            get("getValue") as? String ?: "",
            opt("hasHiddenPassword", "getHiddenPassword"),
            opt("hasCreditCardInfo", "getCreditCardInfo"),
            opt("hasAddressOrFullname", "getAddressOrFullname"),
        )
    }

    fun savePassword(
        action: Any,
        verb: String,
    ) {
        savePasswordAction.getValue(verb).invoke(action)
    }

    fun updatePassword(
        action: Any,
        verb: String,
    ) {
        updatePasswordAction.getValue(verb).invoke(action)
    }

    fun saveCard(
        action: Any,
        save: Boolean,
    ) {
        saveCardAction.getValue(if (save) "save" else "decline").invoke(action)
    }

    fun saveProfile(
        action: Any,
        save: Boolean,
    ) {
        saveProfileAction.getValue(if (save) "save" else "decline").invoke(action)
    }

    fun acceptSuggestion(
        action: Any,
        suggestion: Any,
    ) {
        showAccept.invoke(action, suggestion)
    }

    fun cancelSuggestions(action: Any) {
        showAction.getValue("cancel").invoke(action)
    }

    private fun Map<String, Method>.str(
        on: Any,
        name: String,
    ): String = getValue(name).invoke(on) as? String ?: ""

    companion object {
        private const val P = "com.teamdev.jxbrowser"
        private const val CB = "$P.browser.callback"

        private val cache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ClassLoader, Result<Jx>>())

        /** The surface for [browser]'s classloader, or a failure naming what is missing. Probed once per loader. */
        fun resolve(browser: Any): Result<Jx> {
            val loader = browser.javaClass.classLoader ?: return Result.failure(IllegalStateException("no classloader"))
            return cache.getOrPut(loader) { runCatching { Jx(loader) } }
        }

        internal fun resolveFor(loader: ClassLoader): Result<Jx> = runCatching { Jx(loader) }
    }
}
