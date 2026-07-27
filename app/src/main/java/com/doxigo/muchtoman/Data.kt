package com.doxigo.muchtoman

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * [excluded] keeps a rainy-day asset on the list but out of the total — the default keeps
 * every holding saved before the flag existed counting, exactly as it did.
 */
@Serializable
data class Holding(val typeId: String, val amount: Double, val excluded: Boolean = false)

/**
 * A coin the Worker knows how to price, with its real name and logo. [name] is what she
 * reads (Persian where a Tehran exchange has one); [en] is the latin name it is also known
 * by, so the picker can be searched in either language. Empty on rates cached by an older
 * build — search just falls back to the Persian name and the ticker until the next fetch.
 */
@Serializable
data class Coin(
    val id: String,
    val name: String,
    val en: String = "",
    val icon: String = "",
)

/** Everything the Worker sends: Toman per one unit of each asset id, plus the coin catalogue. */
@Serializable
data class Rates(
    val updatedAt: Long = 0L,
    val toman: Map<String, Double> = emptyMap(),
    val coins: List<Coin> = emptyList(),
)

data class Totals(val toman: Double, val missing: List<String>)

/**
 * Pure so it can be tested without a device. Anything we have no rate for is left OUT of
 * the total and reported in [Totals.missing] — quietly counting it as zero would show her a
 * total that is wrong in the reassuring direction, which is the worst possible failure here.
 */
fun computeTotals(holdings: List<Holding>, rates: Map<String, Double>): Totals {
    var sum = 0.0
    val missing = mutableListOf<String>()
    for (h in holdings) {
        if (h.excluded) continue // set aside on purpose — not summed, and not "missing" either
        val rate = rates[h.typeId]
        if (rate == null || rate <= 0.0) missing += h.typeId else sum += h.amount * rate
    }
    return Totals(sum, missing)
}

const val DAY_MS = 86_400_000L

/** How many daily entries the history keeps — a year of chart plus margin. */
const val HISTORY_KEEP_DAYS = 400

/**
 * One remembered total per day, keyed by epoch day. Overwrites the same day, so the entry
 * converges to the day's last good total; prunes from the old end so the map never outgrows
 * a year of chart.
 */
fun recordDay(history: Map<Long, Double>, epochDay: Long, total: Double): Map<Long, Double> {
    val next = history + (epochDay to total)
    if (next.size <= HISTORY_KEEP_DAYS) return next
    return next.entries.sortedBy { it.key }.takeLast(HISTORY_KEEP_DAYS)
        .associate { it.key to it.value }
}

data class Change(val delta: Double, val percent: Double?, val sinceDay: Long)

/**
 * The total's change against the newest snapshot at least [windowDays] old. A few days of
 * grace just inside the window cover the days the app was not opened; a history shorter than
 * that returns null rather than passing a two-week change off as a monthly one.
 */
fun changeOver(history: Map<Long, Double>, today: Long, windowDays: Int, current: Double): Change? {
    val target = today - windowDays
    val day = history.keys.filter { it <= target }.maxOrNull()
        ?: history.keys.filter { it in (target + 1)..(target + 3) }.minOrNull()
        ?: return null
    val base = history.getValue(day)
    return Change(current - base, if (base > 0) (current - base) / base * 100 else null, day)
}

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * Bumped whenever the way a bank message is read changes enough that the balances already on
 * disk were produced by code now known to be wrong.
 *
 * Version 2 dropped everything built before the Rial-beside-Toman tenfold error, the account
 * number read as a balance, the مانده بدهی counted as cash, and one account stored several
 * times over and summed. Version 3 follows the minus-sign fix: خاورمیانه writes its
 * withdrawals as a bare "-5,025,000" and the amount was being skipped for the reference number
 * underneath it.
 *
 * Figures built by any of that cannot be repaired — but they never had to be, because they are
 * not the record. Her inbox is. Clearing them makes the next scan read it from the start and
 * rebuild every balance with the parser as it now stands. **Bump this whenever parsing changes,
 * or the fix ships to a phone that will never re-read the messages it applies to.**
 */
// 6: 20004861 turned out to be خاورمیانه, not بلو — every balance either bank holds was built
// from messages sorted into the wrong one, so the inbox is re-read from the start.
private const val SMS_SCHEMA = 6

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("muchtoman", Context.MODE_PRIVATE)

    init {
        // extraBankNumbers survives on purpose: those are her own statements about her banks,
        // not something a parser computed.
        if (prefs.getInt("smsSchema", 0) != SMS_SCHEMA) {
            prefs.edit()
                .remove("bankAccounts")
                .remove("seenSms")
                .remove("smsScannedTo")
                .remove("unknownSenders")
                .remove("strangers")
                .putInt("smsSchema", SMS_SCHEMA)
                .apply()
        }
    }

    var holdings: List<Holding>
        get() = read("holdings", emptyList())
        set(v) = write("holdings", v)

    /** Last successful fetch, so the app still shows something offline. */
    var cachedRates: Rates
        get() = read("rates", Rates())
        set(v) = write("rates", v)

    /** Who the app greets. Empty means greet nobody. */
    var name: String
        get() = prefs.getString("name", "").orEmpty()
        set(v) { prefs.edit().putString("name", v.trim()).apply() }

    /** SYSTEM / LIGHT / DARK, stored by name so an unknown value falls back safely. */
    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", "")!!) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(v) { prefs.edit().putString("themeMode", v.name).apply() }

    /** Whether the app hides itself behind fingerprint / device PIN. */
    var lockEnabled: Boolean
        get() = prefs.getBoolean("lockEnabled", false)
        set(v) { prefs.edit().putBoolean("lockEnabled", v).apply() }

    /** Rates the user typed in by hand; these win over whatever the Worker says. */
    var overrides: Map<String, Double>
        get() = read("overrides", emptyMap())
        set(v) = write("overrides", v)

    /** One total per day, for the report chart. Written by AppVm, pruned by [recordDay]. */
    var history: Map<Long, Double>
        get() = read("history", emptyMap())
        set(v) = write("history", v)

    /** Whether she has asked the app to read her bank messages at all. */
    var smsEnabled: Boolean
        get() = prefs.getBoolean("smsEnabled", false)
        set(v) { prefs.edit().putBoolean("smsEnabled", v).apply() }

    /**
     * One balance per bank, built from her messages. Collapsed on the way in as well as on the
     * way out, so a phone carrying the rows an older build wrote — one per printed identifier,
     * thirty-eight of them on a real device — is put right the first time it is read, without
     * waiting for a new message to arrive.
     */
    var bankAccounts: List<BankAccount>
        get() = collapseAccounts(read("bankAccounts", emptyList()))
        set(v) = write("bankAccounts", collapseAccounts(v))

    /** Banks she switched off: still tracked and still listed, just not in the total. */
    var disabledBanks: Set<String>
        get() = read("disabledBanks", emptySet())
        set(v) = write("disabledBanks", v)

    /**
     * Messages already folded in. Counting one twice invents money, so this is checked before
     * anything is applied — and it is keyed by content, not by the inbox row id, which a
     * restore or a different SMS app will happily change.
     */
    var seenSms: Set<String>
        get() = read("seenSms", emptySet())
        set(v) = write("seenSms", v)

    /**
     * Sender numbers she confirmed herself, per bank — a bank that starts sending from a new
     * shortcode is a tap here, not a new build. Kept across the schema wipe on purpose.
     */
    var extraBankNumbers: Map<String, List<String>>
        get() = read("extraBankNumbers", emptyMap())
        set(v) = write("extraBankNumbers", v)

    /** Messages that named one of her banks but arrived from a number the list lacks. */
    var strangeSenders: List<StrangeSender>
        get() = read("strangers", emptyList())
        set(v) = write("strangers", v)

    /**
     * Suggestions she waved away, by sender key. Like the numbers she confirmed, these are her
     * own statements and survive the schema wipe — a dismissed card must not come back just
     * because the parser learned something new.
     */
    var dismissedSenders: Set<String>
        get() = read("dismissedSenders", emptySet())
        set(v) = write("dismissedSenders", v)

    /** How far the inbox has been read, so each scan only looks at what arrived since. */
    var smsScannedTo: Long
        get() = prefs.getLong("smsScannedTo", 0L)
        set(v) { prefs.edit().putLong("smsScannedTo", v).apply() }

    private inline fun <reified T> read(key: String, fallback: T): T =
        prefs.getString(key, null)?.let {
            runCatching { JSON.decodeFromString<T>(it) }.getOrNull()
        } ?: fallback

    private inline fun <reified T> write(key: String, value: T) {
        prefs.edit().putString(key, JSON.encodeToString(value)).apply()
    }
}

/**
 * Manual overrides layered on top of fetched rates, with Toman pinned at 1 on top of both.
 * Toman-in-the-bank must still count when the network is down and must not be "correctable"
 * to anything other than itself — and the same goes for the bank balances, which are already
 * Toman by the time [parseBankSms] is done with them.
 */
fun effectiveRates(fetched: Rates, overrides: Map<String, Double>): Map<String, Double> =
    fetched.toman + overrides + mapOf(TOMAN_ID to 1.0, BANK_ID to 1.0)

/**
 * Fresh prices, but the old catalogue if the new one is empty. When the Worker's name-and-logo
 * source is the thing that is down it sends prices with no coin list at all, and taking that
 * list at face value emptied the picker and turned every held coin into a bare ticker. Names
 * and logos do not go stale the way a price does, so the last ones we saw are still right.
 */
fun mergeRates(fresh: Rates, cached: Rates): Rates =
    if (fresh.coins.isEmpty()) fresh.copy(coins = cached.coins) else fresh

suspend fun fetchRates(url: String): Result<Rates> = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            // Fetches are ten minutes apart, so a kept-alive socket is only ever a stale
            // one — reusing it is where "unexpected end of stream" came from.
            setRequestProperty("Connection", "close")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = JSON.decodeFromString<Rates>(body)
            if (parsed.toman.isEmpty()) error("empty rates")
            // updatedAt is when the Worker last pulled real prices, not when we asked — that
            // is the number worth showing, since a cached response is still old prices.
            parsed
        } finally {
            conn.disconnect()
        }
    }
}
