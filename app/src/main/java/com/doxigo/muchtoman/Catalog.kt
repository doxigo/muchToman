package com.doxigo.muchtoman

enum class Kind(val fa: String) {
    CASH("تومان"),
    FIAT("ارز"),
    CRYPTO("رمزارز"),
    GOLD("طلا"),
    COIN("سکه"),
}

/** Toman held as Toman. Its rate is 1 by definition and never comes from the network. */
const val TOMAN_ID = "toman"

/**
 * @param id      key used in the rates JSON from the Worker
 * @param unitFa  what one of it is called ("دلار", "گرم", "عدد")
 * @param dec     how many decimals are meaningful when showing the held amount
 * @param en      the latin name it is also known by; searched, never shown
 * @param emoji   used when there is no real logo to show
 * @param iconUrl a real logo, for coins where an emoji would just be a wrong picture
 */
data class AssetType(
    val id: String,
    val fa: String,
    val kind: Kind,
    val unitFa: String,
    val dec: Int = 0,
    val en: String = "",
    val emoji: String? = null,
    val iconUrl: String? = null,
)

// The fixed half of the catalogue: things bonbast/tgju price. Crypto is not here — it comes
// from the Worker, so the picker offers every coin rather than the three someone guessed at.
val STATIC_CATALOG: List<AssetType> = listOf(
    // Not "پول نقد و بانک" any more, and not a 🏦 either: once bank balances arrive from her
    // messages as their own row, a row also claiming "بانک" is two names for two different
    // things. This one is the Toman she counts herself.
    AssetType(TOMAN_ID, "پول نقد تومن", Kind.CASH, "تومان", en = "Toman Cash", emoji = "💰"),

    // dec = 2: bank balances have cents, and a held 100.50 must round-trip through the
    // edit field without silently becoming 100.
    AssetType("usd", "دلار آمریکا", Kind.FIAT, "دلار", dec = 2, en = "US Dollar", emoji = "💵"),
    AssetType("eur", "یورو", Kind.FIAT, "یورو", dec = 2, en = "Euro", emoji = "💶"),
    AssetType("gbp", "پوند انگلیس", Kind.FIAT, "پوند", dec = 2, en = "British Pound", emoji = "💷"),
    AssetType("nok", "کرون نروژ", Kind.FIAT, "کرون", dec = 2, en = "Norwegian Krone", emoji = "🇳🇴"),
    AssetType("try", "لیر ترکیه", Kind.FIAT, "لیر", dec = 2, en = "Turkish Lira", emoji = "🇹🇷"),
    AssetType("aed", "درهم امارات", Kind.FIAT, "درهم", dec = 2, en = "UAE Dirham", emoji = "🇦🇪"),
    AssetType("cad", "دلار کانادا", Kind.FIAT, "دلار", dec = 2, en = "Canadian Dollar", emoji = "🇨🇦"),

    AssetType("gold18", "طلای ۱۸ عیار", Kind.GOLD, "گرم", dec = 3, en = "18k Gold", emoji = "🟡"),
    // Her generation prices gold in مثقال, not grams; bonbast quotes it directly.
    AssetType("gold_mesghal", "مثقال طلا", Kind.GOLD, "مثقال", dec = 2, en = "Mesghal Gold", emoji = "🟡"),

    AssetType("coin_emami", "سکه امامی", Kind.COIN, "عدد", en = "Emami Gold Coin", emoji = "🪙"),
    AssetType("coin_bahar", "سکه بهار آزادی", Kind.COIN, "عدد", en = "Bahar Azadi Gold Coin", emoji = "🪙"),
    AssetType("coin_nim", "نیم‌سکه", Kind.COIN, "عدد", en = "Nim Half Gold Coin", emoji = "🪙"),
    AssetType("coin_rob", "ربع‌سکه", Kind.COIN, "عدد", en = "Rob Quarter Gold Coin", emoji = "🪙"),
    AssetType("coin_gerami", "سکه گرمی", Kind.COIN, "عدد", en = "Gerami Gram Gold Coin", emoji = "🪙"),
)

/**
 * The one asset she never adds by hand: it appears the moment a bank message is read and goes
 * again when she turns that off. Kept out of [STATIC_CATALOG] on purpose, so the picker never
 * offers a row whose amount is not hers to type.
 */
val BANK_TYPE = AssetType(
    BANK_ID, "حساب‌های بانکی", Kind.CASH, "تومان", en = "Bank Accounts", emoji = "💳",
)

private val STATIC_BY_ID = (STATIC_CATALOG + BANK_TYPE).associateBy { it.id }

fun Coin.toAssetType() = AssetType(
    id = id,
    fa = name,
    kind = Kind.CRYPTO,
    unitFa = id.uppercase(),
    dec = 6,
    en = en,
    iconUrl = icon.ifBlank { null },
)

/** The full picker list: fixed assets, then every coin the Worker knows about. */
fun catalog(coins: List<Coin>): List<AssetType> = STATIC_CATALOG + coins.map { it.toAssetType() }

/**
 * Static entries win, so a coin that ever ships with a ticker like "nok" can never shadow the
 * currency. A held asset we no longer recognise still renders, rather than vanishing from her
 * list along with its value.
 */
fun resolveType(id: String, coins: List<Coin>): AssetType =
    STATIC_BY_ID[id]
        ?: coins.firstOrNull { it.id == id }?.toAssetType()
        ?: AssetType(id, id.uppercase(), Kind.CRYPTO, id.uppercase(), dec = 6)

/**
 * Normalised for comparing: ZWNJ and spaces vary by keyboard and by source ("بیت‌کوین" vs
 * "بیت کوین" vs "بیتکوین" are the same word three ways), and a latin name is typed in any case.
 */
private fun searchKey(s: String) = s.replace("\u200c", "").replace(" ", "").lowercase()

/**
 * Does this asset answer to what she typed? One coin has three names — the Persian one she
 * reads, the latin one she may know it by ("Polkadot"), and the ticker ("DOT") — and any of
 * them has to find it. Searching only the first two left every coin that a Tehran exchange
 * had named in Persian unreachable from its English name.
 */
fun matchesSearch(type: AssetType, query: String): Boolean {
    val needle = searchKey(query)
    if (needle.isEmpty()) return false
    return searchKey(type.fa).contains(needle) ||
        searchKey(type.en).contains(needle) ||
        type.id.contains(query.trim(), ignoreCase = true)
}

/**
 * Her holdings split into sections by kind — dollars are not coins are not crypto, and one
 * flat stack of cards said they were. Sections come out in the order the holdings are already
 * stored in (see AppVm.setHolding), so banding the list never moves a row; only kinds she
 * actually holds appear.
 */
fun holdingsByKind(holdings: List<Holding>, coins: List<Coin>): Map<Kind, List<Holding>> =
    holdings.groupBy { resolveType(it.typeId, coins).kind }
