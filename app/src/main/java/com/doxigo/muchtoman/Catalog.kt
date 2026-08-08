package com.doxigo.muchtoman

enum class Kind(val fa: String) {
    CASH("تومان"),
    FIAT("ارز"),
    CRYPTO("رمزارز"),
    GOLD("طلا"),
    SILVER("نقره"),
    COIN("سکه"),
    STOCK("بورس"),
    PROPERTY("املاک و خودرو"),
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
    val wallets: List<WalletOption> = emptyList(),
)

private fun Int.faDigits() = toString().map { "۰۱۲۳۴۵۶۷۸۹"[it - '0'] }.joinToString("")

/**
 * The fifteen sizes tgju quotes, each still priced by the Worker — but no longer offered.
 *
 * Fifteen rows of one coin buried every other سکه in the picker, so the list now carries a
 * single سکه پارسیان counted in سوت. These stay resolvable because holdings already saved
 * against them must keep their name and their own price: each size is quoted individually,
 * which is *more* accurate than the per-سوت row, since the اجرت is close to fixed per coin
 * and so falls hardest on the smallest — a ۱۰۰ سوت piece goes for roughly a quarter more
 * than the gold in it, a ۱۵۰۰ سوت one for a few per cent.
 */
private val LEGACY_PARSIAN: List<AssetType> = (1..15).map { step ->
    val soot = step * 100
    AssetType(
        id = "parsian_$soot",
        fa = "سکه پارسیان ${soot.faDigits()} سوت",
        kind = Kind.COIN,
        unitFa = "عدد",
        // Also written by its weight everywhere they are sold, so "0.400" has to find it.
        en = "Parsian Gold Coin ${soot / 1000}.${(soot % 1000).toString().padStart(3, '0')}g",
        emoji = "🪙",
    )
}

// The fixed half of the catalogue: things bonbast/tgju price. Crypto is not here — it comes
// from the Worker, so the picker offers every coin rather than the three someone guessed at.
val STATIC_CATALOG: List<AssetType> = listOf(
    // Not "پول نقد و بانک" any more, and not a 🏦 either: once bank balances arrive from her
    // messages as their own row, a row also claiming "بانک" is two names for two different
    // things. This one is the Toman she counts herself.
    AssetType(TOMAN_ID, "پول نقد", Kind.CASH, "تومان", en = "Toman Cash", emoji = "💰"),

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

    // Both purities the Tehran market quotes, by the gram. Only tgju prices silver at all,
    // so these are the two rows with a single source behind them — which costs nothing that
    // is not already handled: no rate means left out of the total and named, never zero.
    AssetType("silver_999", "نقره ۹۹۹", Kind.SILVER, "گرم", dec = 3, en = "999 Silver", emoji = "⚪"),
    AssetType("silver_925", "نقره ۹۲۵", Kind.SILVER, "گرم", dec = 3, en = "925 Sterling Silver", emoji = "⚪"),

    AssetType("coin_emami", "سکه امامی", Kind.COIN, "عدد", en = "Emami Gold Coin", emoji = "🪙"),
    AssetType("coin_bahar", "سکه بهار آزادی", Kind.COIN, "عدد", en = "Bahar Azadi Gold Coin", emoji = "🪙"),
    AssetType("coin_nim", "نیم‌سکه", Kind.COIN, "عدد", en = "Nim Half Gold Coin", emoji = "🪙"),
    AssetType("coin_rob", "ربع‌سکه", Kind.COIN, "عدد", en = "Rob Quarter Gold Coin", emoji = "🪙"),
    AssetType("coin_gerami", "سکه گرمی", Kind.COIN, "عدد", en = "Gerami Gram Gold Coin", emoji = "🪙"),

    // Counted in سوت — a thousand to the gram — rather than as fifteen separate coins, which
    // is what it used to be and what made the سکه section unreadable. She adds up the سوت she
    // holds and types one number, whatever mix of sizes it came in.
    //
    // The cost is the اجرت: it is close to fixed per coin, so the smallest sizes really go
    // for more per سوت than this row prices them at. The rate is the ۱ گرم coin's, the size
    // the market quotes as a reference; a shelf of ۱۰۰ سوت pieces is worth somewhat more than
    // this says, and the manual rate override is the way to say so.
    AssetType("parsian", "سکه پارسیان", Kind.COIN, "سوت", en = "Parsian Gold Coin", emoji = "🪙"),

    // Nobody quotes *her* car or *her* land, so these are the assets with no rate to fetch: the
    // figure she types is the value in Toman, and the unit says so. Counted in تومان rather than
    // in "عدد" at a hand-typed نرخ, because a house is worth what she says it is worth and asking
    // for that in two fields is asking the same question twice. A second car or a second ملک is
    // another holding of the same row with a name of her own on it, the way a second تتر already is.
    AssetType("car", "خودرو", Kind.PROPERTY, "تومان", en = "Car Vehicle", emoji = "🚗"),
    AssetType("house", "خانه و ویلا", Kind.PROPERTY, "تومان", en = "House Villa Home", emoji = "🏡"),
    AssetType("land", "زمین", Kind.PROPERTY, "تومان", en = "Land Plot", emoji = "🏞️"),
)

/**
 * Rate 1 by definition, and not correctable to anything else: Toman itself, the bank balances
 * [parseBankSms] has already turned into Toman, and everything she values herself.
 */
val TOMAN_BY_DEFINITION: Map<String, Double> =
    (STATIC_CATALOG.filter { it.kind == Kind.PROPERTY }.map { it.id } + TOMAN_ID + BANK_ID)
        .associateWith { 1.0 }

/**
 * Does she type this one's value straight in Toman? Then there is no rate to show her, none to
 * correct, and no "یعنی … تومان" to print under a figure that is already in Toman.
 */
val AssetType.valuedInToman: Boolean
    get() = kind == Kind.CASH || kind == Kind.PROPERTY

/**
 * The one asset she never adds by hand: it appears the moment a bank message is read and goes
 * again when she turns that off. Kept out of [STATIC_CATALOG] on purpose, so the picker never
 * offers a row whose amount is not hers to type.
 */
val BANK_TYPE = AssetType(
    BANK_ID, "حساب‌های بانکی", Kind.CASH, "تومان", en = "Bank Accounts", emoji = "💳",
)

private val STATIC_BY_ID = (STATIC_CATALOG + BANK_TYPE + LEGACY_PARSIAN).associateBy { it.id }

fun Coin.toAssetType() = AssetType(
    id = id,
    fa = name,
    kind = Kind.CRYPTO,
    unitFa = id.uppercase(),
    dec = 6,
    en = en,
    iconUrl = icon.ifBlank { null },
    wallets = wallets,
)

/**
 * A نماد is the name she says, so it is the one shown; the company behind it goes in [en].
 * That field is documented as the latin name, and for a share it is Persian — but its whole
 * job is "a second name this asset answers to in search", and giving one kind its own parallel
 * search path to hold one more Persian string would be the worse trade.
 *
 * dec = 0: shares come in whole numbers.
 */
fun Stock.toAssetType() = AssetType(
    id = id,
    fa = symbol,
    kind = Kind.STOCK,
    unitFa = "سهم",
    dec = 0,
    en = name,
    emoji = "📈",
)

/** The full picker list: fixed assets, then everything the network knows how to price. */
fun catalog(coins: List<Coin>, stocks: List<Stock> = emptyList()): List<AssetType> =
    STATIC_CATALOG + coins.map { it.toAssetType() } + stocks.map { it.toAssetType() }

/**
 * Static entries win, so a coin that ever ships with a ticker like "nok" can never shadow the
 * currency. A held asset we no longer recognise still renders, rather than vanishing from her
 * list along with its value.
 */
fun resolveType(id: String, coins: List<Coin>, stocks: List<Stock> = emptyList()): AssetType =
    STATIC_BY_ID[id]
        ?: coins.firstOrNull { it.id == id }?.toAssetType()
        ?: stocks.firstOrNull { it.id == id }?.toAssetType()
        ?: AssetType(id, id.uppercase(), Kind.CRYPTO, id.uppercase(), dec = 6)

fun resolveType(id: String, dynamic: Map<String, AssetType>): AssetType =
    STATIC_BY_ID[id]
        ?: dynamic[id]
        ?: AssetType(id, id.uppercase(), Kind.CRYPTO, id.uppercase(), dec = 6)

/**
 * Normalised for comparing: ZWNJ and spaces vary by keyboard and by source ("بیت‌کوین" vs
 * "بیت کوین" vs "بیتکوین" are the same word three ways), and a latin name is typed in any case.
 */
/**
 * Arabic letters standing in for Persian ones. TSETMC spells "\u067e\u0627\u0644\u0627\u064a\u0634" and "\u0634\u0643\u0631" with \u064a and \u0643,
 * which are different codepoints from the \u06cc and \u06a9 her keyboard produces \u2014 unfolded, a \u0646\u0645\u0627\u062f is
 * unfindable by anyone who types its name correctly.
 */
fun faLetters(s: String) = s.replace('\u064a', '\u06cc').replace('\u0643', '\u06a9')

private fun searchKey(s: String) =
    faLetters(s).replace("\u200c", "").replace(" ", "").lowercase()

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
fun holdingsByKind(
    holdings: List<Holding>,
    coins: List<Coin>,
    stocks: List<Stock> = emptyList(),
): Map<Kind, List<Holding>> =
    holdings.groupBy { resolveType(it.typeId, coins, stocks).kind }

fun holdingsByKind(
    holdings: List<Holding>,
    dynamic: Map<String, AssetType>,
): Map<Kind, List<Holding>> =
    holdings.groupBy { resolveType(it.typeId, dynamic).kind }

/**
 * What the total is made of, per kind, in the order the list already shows. The groups are
 * exactly the home screen's sections — inventing different ones for the report would mean
 * two vocabularies for the same money. Kinds worth nothing right now are left out.
 */
fun compositionByKind(
    holdings: List<Holding>,
    coins: List<Coin>,
    rates: Map<String, Double>,
    stocks: List<Stock> = emptyList(),
): List<Pair<Kind, Double>> =
    holdingsByKind(holdings, coins, stocks)
        .map { (kind, held) -> kind to computeTotals(held, rates).toman }
        .filter { it.second > 0.0 }
