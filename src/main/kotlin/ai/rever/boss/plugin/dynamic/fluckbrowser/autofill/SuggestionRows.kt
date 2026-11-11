package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

/** Display rows for Chromium suggestions. */
internal object SuggestionRows {
    @Suppress("LongParameterList")
    internal fun rowOf(
        primary: String,
        hiddenPassword: String?,
        cardInfo: String?,
        address: String?,
        type: String,
        site: String,
    ): SuggestionRow =
        when {
            type == "PASSWORDS" || hiddenPassword != null -> {
                SuggestionRow(
                    primary,
                    listOfNotNull(
                        hiddenPassword?.ifBlank {
                            null
                        } ?: "••••••••",
                        site.ifBlank { null },
                    ).joinToString("  ·  "),
                    SuggestionKind.PASSWORD,
                )
            }

            type == "CREDIT_CARDS" || cardInfo != null -> {
                cardRow(primary, cardInfo.orEmpty())
            }

            type == "ADDRESSES" || address != null -> {
                SuggestionRow(primary, address.orEmpty(), SuggestionKind.ADDRESS)
            }

            else -> {
                SuggestionRow(primary, "", SuggestionKind.OTHER)
            }
        }

    /** Chromium's card label varies ("Visa  ••4242, •, 12/30"); show network, last four and expiry only. */
    internal fun cardRow(
        value: String,
        info: String,
    ): SuggestionRow {
        val all = "$value $info"
        val last4 = Regex("(\\d{4})(?!.*\\d{4})").find(all)?.groupValues?.get(1)
        val exp =
            Regex("\\b(\\d{1,2})/(\\d{2,4})\\b").find(all)?.let {
                "Expires ${it.groupValues[1].padStart(2, '0')}/${it.groupValues[2].takeLast(2)}"
            }
        val network = Regex("^[A-Za-z][A-Za-z ]*[A-Za-z]").find(value.trim())?.value?.trim()
        if (last4 == null) return SuggestionRow(value, info, SuggestionKind.CARD)
        val holder = info.takeIf { i -> i.isNotBlank() && i.none { it.isDigit() } && '•' !in i }
        return SuggestionRow(
            listOfNotNull(network, "•••• $last4").joinToString(" "),
            listOfNotNull(exp, holder).joinToString("  ·  "),
            SuggestionKind.CARD,
        )
    }
}
