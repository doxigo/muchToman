package com.doxigo.muchtoman

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL

/**
 * One tradeable نماد — a share or an ETF. [symbol] is what she reads and says ("شتران");
 * [name] is the long company name ("پالایش نفت تهران"), kept so the picker can be searched
 * either way.
 */
@Serializable
data class Stock(val id: String, val symbol: String, val name: String)

/**
 * Everything one market-watch fetch produced: Toman per share for each id, and the catalogue
 * of what those ids are. Both come out of the same rows, so unlike the Worker's coin list
 * there is no case where prices arrive without names.
 */
@Serializable
data class TseSnapshot(
    val updatedAt: Long = 0L,
    val toman: Map<String, Double> = emptyMap(),
    val stocks: List<Stock> = emptyList(),
)

/**
 * Prefixed so a نماد can never be mistaken for anything else in the flat rates map:
 * [resolveType] falls through to CRYPTO for ids it does not know, and a bare ticker colliding
 * with a coin would price a share off a cryptocurrency.
 */
private const val TSE_PREFIX = "tse_"

/**
 * The row layout has grown a 26-column variant, but only ever by appending — everything read
 * here sits in the first 23, so both parse from the same indices.
 */
private const val TSE_MIN_COLUMNS = 23

private const val COL_INS_CODE = 0
private const val COL_SYMBOL = 2 // l18
private const val COL_NAME = 3 // l30
private const val COL_CLOSING = 6 // pc — قیمت پایانی
private const val COL_LAST = 7 // pl — آخرین معامله
private const val COL_YESTERDAY = 13 // py
private const val COL_TYPE = 22 // yval — نوع نماد

/**
 * The instrument types you can own a quantity of and be worth that quantity times its price:
 * ordinary shares on every board, صندوق‌های قابل معامله, حق تقدم, and اوراق.
 *
 * صندوق اهرمی is deliberately in — its leverage lives inside the fund, and the units
 * themselves are held spot like any other ETF (305). What is out is the derivatives the
 * market watch carries alongside everything else: آتی (304) and اختیار خرید/فروش (311, 312,
 * plus a further code for every underlying — 321 اختیار فولاد هرمزگان, 322/323 اختیار اخزا).
 * Their quoted price is a premium or a margin, not what a position is worth, so folding them
 * into a total would be wrong even before the thousands of ضخود-style rows they would add.
 *
 * An allow-list rather than a deny-list because the option codes multiply: TSETMC mints a new
 * one per underlying, so anything built on naming them goes stale the day a contract lists.
 * The cost is the other direction — a genuinely new *holdable* type is dropped until added
 * here, which is the safer way round to be wrong.
 *
 * Note that 304, 311 and 312 all call themselves «سهام عادی» in TSETMC's own table. Only the
 * code tells them apart; the label cannot.
 */
private val HOLDABLE_TYPES = setOf(
    "300", "303", "307", "309", "313", // سهام عادی — بورس، فرابورس، پایه، کوچک و متوسط
    "305", "315", // صندوق سرمایه‌گذاری قابل معامله
    // Commodity-backed funds, and the reason this list is worth getting right: every gold
    // fund lives here — طلا، عیار، کهربا، مثقال، ناب — not under 305 with the rest of the
    // ETFs. Leaving 380 out dropped exactly the instruments this app exists to price.
    "380",
    "301", "306", "308", "706", "206", "208", // اوراق مشارکت و صکوک
    "200", // سلف موازی — held to maturity, same shape as the 308 rows already here
    "400", "401", "403", "404", // حق تقدم
    "701", "201", // گواهی سپرده کالایی و گواهی ظرفیت
)

/**
 * The whole-market snapshot: every نماد, its name and its price in one request.
 *
 * The body is `@`-separated into five sections and the third holds the price rows, themselves
 * `;`-separated with `,`-separated columns. Anything that cannot be read as a priced row is
 * skipped, but a body that yields no rows at all raises rather than returning an empty market
 * — an empty snapshot would read downstream as "every share is worth nothing", which is the
 * one wrong answer worth crashing over.
 *
 * @param now stamped onto the snapshot; passed in so the parse stays pure and testable.
 */
fun parseMarketWatch(body: String, now: Long): TseSnapshot {
    // Five sections, not "at least three": a body cut off partway through the prices still
    // ends up with three, parses cleanly, and yields a market that is missing whatever came
    // after the cut — which is how a run once returned 1522 instruments with فولاد and every
    // gold fund quietly absent. Half a market is the failure this cannot afford to tolerate,
    // because it is indistinguishable downstream from those shares having no price.
    val sections = body.split('@')
    if (sections.size < 5) {
        error("truncated body: ${sections.size} sections, ${body.length} chars")
    }

    val toman = LinkedHashMap<String, Double>()
    val stocks = ArrayList<Stock>()

    for (row in sections[2].split(';')) {
        if (row.isBlank()) continue
        val cols = row.split(',')
        if (cols.size < TSE_MIN_COLUMNS) continue

        if (cols[COL_TYPE].trim() !in HOLDABLE_TYPES) continue

        val insCode = cols[COL_INS_CODE].trim()
        val symbol = faLetters(cols[COL_SYMBOL].trim())
        if (insCode.isEmpty() || symbol.isEmpty()) continue

        // قیمت پایانی is what a portfolio is marked at. It is zero for a symbol that has not
        // traded today, so the last trade and then yesterday's close stand in — a suspended
        // نماد is still worth its last price, not nothing.
        val rial = price(cols[COL_CLOSING])
            ?: price(cols[COL_LAST])
            ?: price(cols[COL_YESTERDAY])
            ?: continue

        val id = TSE_PREFIX + insCode
        toman[id] = rial / 10.0 // TSETMC quotes in Rial
        stocks += Stock(id, symbol, faLetters(cols[COL_NAME].trim()))
    }

    if (toman.isEmpty()) error("no priced rows")
    return TseSnapshot(now, toman, stocks)
}

private fun price(raw: String): Double? = raw.trim().toDoubleOrNull()?.takeIf { it > 0.0 }

private const val TSE_URL = "https://old.tsetmc.com/tsev2/data/MarketWatchInit.aspx?h=0&r=0"

/** TSETMC answers a default Java user-agent with nothing usable. */
private const val TSE_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:100.0) Gecko/20100101 Firefox/100.0"

/**
 * The one price source the phone talks to directly. Every other rate comes from the Worker,
 * but TSETMC refuses connections from outside Iran and the reachable resellers forbid being
 * called from a Cloudflare Worker — so for بورس, and only for بورس, the phone is the client.
 *
 * That means this fails for anyone abroad. It fails the way every other source does: the
 * Result is left alone, the last snapshot on disk keeps being used, and a share we have never
 * priced is reported as missing instead of counted as zero.
 */
suspend fun fetchTse(now: Long = System.currentTimeMillis()): Result<TseSnapshot> =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(TSE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                // Longer than the Worker's: this body carries an order-book section we throw
                // away, so it is an order of magnitude larger than the rates JSON.
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", TSE_UA)
                setRequestProperty("Connection", "close")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) error("HTTP $code")
                parseMarketWatch(conn.inputStream.bufferedReader().use { it.readText() }, now)
            } finally {
                conn.disconnect()
            }
        }
    }
