package com.doxigo.muchtoman

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class WalletOption(
    val network: String,
    val networkFa: String,
    val contract: String = "",
)

@Serializable
data class WalletLink(
    val network: String,
    val networkFa: String,
    val address: String,
    val contract: String = "",
    val updatedAt: Long = 0L,
)

/**
 * [excluded] keeps a rainy-day asset on the list but out of the total. [wallet] is absent on
 * every holding saved before automatic tracking existed, so old data remains manual.
 */
@Serializable
data class Holding(
    val typeId: String,
    val amount: Double,
    val excluded: Boolean = false,
    val wallet: WalletLink? = null,
    /**
     * Her own name for this one, where the asset's name is not enough to tell it apart —
     * "تتر شخصی" beside "تتر مشترک". Blank means the asset's own name, which is what every
     * holding saved before this field existed decodes to.
     */
    val label: String = "",
    /**
     * What tells two holdings of the same asset apart — two Tether accounts, one tracked from a
     * wallet and one typed in. Blank on everything saved before that was allowed, where the
     * asset id was the identity; [key], not this, is what anything else should compare.
     */
    val id: String = "",
) {
    /** What to print for this holding. Never blank: the asset's own name is the fallback. */
    fun nameOr(default: String): String = label.ifBlank { default }

    /** This one holding, for as long as it exists. Unique across the list. */
    val key: String get() = id.ifBlank { typeId }
}

/** A fresh [Holding.id]. Only ever called when she adds one, so uniqueness is all it owes. */
fun newHoldingId(): String = java.util.UUID.randomUUID().toString()

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
    val wallets: List<WalletOption> = emptyList(),
)

/**
 * The newest tagged release the Worker could see. The app is sideloaded, so nothing updates it
 * on its own — this is the whole of how a new build gets mentioned to someone running an old one.
 *
 * [apk] is the Worker's own proxied copy of the file and is what the note opens; [url] is the
 * GitHub release page, and is the fallback for the run where the Worker found no APK to serve.
 * GitHub is routinely unreachable from Iran, so the page is the worse of the two links.
 */
@Serializable
data class Release(
    val name: String = "",
    val url: String = "",
    val apk: String = "",
    /**
     * What changed, in her own language, one short line at a time — written on the tag itself
     * and carried here by the Worker. Empty is an ordinary case, not a failure: a release cut
     * without a note still announces itself, and the sheet simply has nothing to list.
     */
    val notes: List<String> = emptyList(),
) {
    /** Whichever of the two she has the better chance of actually opening. */
    val downloadUrl: String get() = apk.ifBlank { url }
}

/** Everything the Worker sends: Toman per one unit of each asset id, plus the coin catalogue. */
@Serializable
data class Rates(
    val updatedAt: Long = 0L,
    val toman: Map<String, Double> = emptyMap(),
    val coins: List<Coin> = emptyList(),
    val latest: Release? = null,
)

@Serializable
private data class WalletBalanceRequest(
    val network: String,
    val address: String,
    val contract: String = "",
)

@Serializable
data class WalletBalance(val amount: Double, val updatedAt: Long)

@Serializable
private data class WalletError(val code: String = "unavailable")

class WalletFetchException(val reason: String) : Exception(reason)

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
        val value = rate?.let { h.amount * it }
        val next = value?.let { sum + it }
        if (
            !h.amount.isFinite() ||
            rate == null ||
            !rate.isFinite() ||
            rate <= 0.0 ||
            value == null ||
            !value.isFinite() ||
            next == null ||
            !next.isFinite()
        ) {
            missing += h.typeId
        } else {
            sum = next
        }
    }
    return Totals(sum, missing)
}

/**
 * True when [latest] names a higher version than [current]. Numeric runs only, compared
 * component by component: "1.10" beats "1.9", which a string compare gets backwards, and a
 * missing component reads as zero so "1.2" and "1.2.0" are the same build. A suffix like
 * "-beta" is ignored rather than guessed at — a pre-release of what she already runs is not
 * an update worth a banner.
 */
fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(v: String) = v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
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

/**
 * What the list actually shows: her own holdings, plus — once there is anything to show —
 * one row standing for every bank account, dropped in right after her cash so the تومان
 * section reads cash first, then bank. It is not persisted with the holdings: its amount
 * is not hers to type, and it must vanish the moment she stops reading messages.
 *
 * Pure and shared: the screen, the widget and the daily worker must all agree on what
 * "everything" is, and they can only do that by asking the same function.
 */
fun listHoldings(
    holdings: List<Holding>,
    smsEnabled: Boolean,
    bankAccounts: List<BankAccount>,
    disabledBanks: Set<String>,
): List<Holding> {
    if (!smsEnabled || bankAccounts.isEmpty()) return holdings
    val bank = Holding(BANK_ID, bankTotal(bankAccounts, disabledBanks))
    val cash = holdings.indexOfFirst { it.typeId == TOMAN_ID }
    return if (cash < 0) listOf(bank) + holdings
    else holdings.take(cash + 1) + bank + holdings.drop(cash + 1)
}

/**
 * Today's history entry, but only a total worth remembering: something on the list, every
 * holding priced, rates younger than a day. A partial or stale total drawn into the chart
 * would look like a crash that never happened. Null means leave the history as it is.
 */
fun snapshotHistory(
    history: Map<Long, Double>,
    list: List<Holding>,
    rates: Map<String, Double>,
    ratesUpdatedAt: Long,
    now: Long,
): Map<Long, Double>? {
    if (list.isEmpty()) return null
    if (now - ratesUpdatedAt > 24 * 60 * 60_000L) return null
    val totals = computeTotals(list, rates)
    if (totals.missing.isNotEmpty()) return null
    return recordDay(history, now / DAY_MS, totals.toman)
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
private const val MAX_RATES_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_WALLET_RESPONSE_BYTES = 64 * 1024
private const val MAX_RATE_ENTRIES = 10_000
private const val MAX_COIN_ENTRIES = 500
private const val MAX_WALLET_OPTIONS = 16
private const val MAX_ASSET_ID_LENGTH = 64
private const val MAX_COIN_NAME_LENGTH = 100
private const val MAX_NETWORK_NAME_LENGTH = 40
private const val MAX_CONTRACT_LENGTH = 128
private const val MAX_RELEASE_NOTES = 6
private const val MAX_RELEASE_NOTE_LENGTH = 120
private const val MAX_FUTURE_CLOCK_SKEW_MS = 5 * 60_000L
private const val OFFICIAL_RATES_ORIGIN = "https://rates.muchtoman.com"

private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
private val BASE58_KEY = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
private val TRON_ADDRESS = Regex("^T[1-9A-HJ-NP-Za-km-z]{33}$")
private val BITCOIN_ADDRESS =
    Regex("^(?:[13][a-km-zA-HJ-NP-Z1-9]{25,34}|bc1[ac-hj-np-z02-9]{11,71})$", RegexOption.IGNORE_CASE)
private val EVM_NETWORKS = setOf("ethereum", "bsc", "arbitrum", "polygon", "optimism", "avalanche")
private val WALLET_NETWORKS = EVM_NETWORKS + setOf("bitcoin", "solana", "tron")

fun isWalletAddressFormatValid(network: String, value: String): Boolean {
    val address = value.trim()
    if (address.isEmpty() || address.length > 128) return false
    return when (network) {
        "bitcoin" -> BITCOIN_ADDRESS.matches(address)
        "solana" -> BASE58_KEY.matches(address)
        "tron" -> TRON_ADDRESS.matches(address)
        in EVM_NETWORKS -> EVM_ADDRESS.matches(address)
        else -> false
    }
}

fun isWalletContractFormatValid(network: String, value: String): Boolean {
    val contract = value.trim()
    if (contract.isEmpty()) return true
    if (contract.length > MAX_CONTRACT_LENGTH) return false
    return when (network) {
        "tron" -> TRON_ADDRESS.matches(contract)
        in EVM_NETWORKS -> EVM_ADDRESS.matches(contract)
        else -> false
    }
}

internal fun isWalletBalanceValid(
    balance: WalletBalance,
    now: Long = System.currentTimeMillis(),
): Boolean =
    balance.amount.isFinite() &&
        balance.amount >= 0.0 &&
        balance.updatedAt in 1..(now + MAX_FUTURE_CLOCK_SKEW_MS)

private data class UrlOrigin(val scheme: String, val host: String, val port: Int)

private fun URL.origin(): UrlOrigin = UrlOrigin(
    protocol.lowercase(),
    host.lowercase(),
    if (port >= 0) port else defaultPort,
)

private fun trustedCoinIcon(value: String, ratesUrl: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val icon = URL(value)
        val configured = URL(ratesUrl)
        val official = URL(OFFICIAL_RATES_ORIGIN)
        val trustedOrigin = icon.origin() == configured.origin() || icon.origin() == official.origin()
        if (
            !trustedOrigin ||
            icon.path != "/coin-icon" ||
            icon.userInfo != null ||
            icon.ref != null ||
            icon.query.isNullOrBlank()
        ) "" else icon.toExternalForm()
    }.getOrDefault("")
}

/**
 * The download link is the one thing in the payload that sends her somewhere, so both halves of
 * it are checked against a fixed origin: GitHub for the release page, and the Worker she is
 * already talking to for the proxied APK. An [apk] that fails is dropped on its own rather than
 * taking the whole note with it — [Release.downloadUrl] then falls back to the page.
 *
 * [Release.notes] is free text that ends up on screen, so it is capped in both directions here
 * rather than trusted to be short: six lines of 120 characters is a changelog, and anything
 * longer is either a mistake upstream or someone using the update card as a billboard.
 */
private fun trustedRelease(value: Release?, ratesUrl: String): Release? {
    value ?: return null
    val name = value.name.trim().take(32)
    if (name.isEmpty()) return null
    val url = runCatching { URL(value.url) }.getOrNull() ?: return null
    val releasePath = url.path.lowercase()
    return value.copy(
        name = name,
        url = url.toExternalForm(),
        apk = trustedApk(value.apk, ratesUrl),
        notes = value.notes.asSequence()
            .map { safeText(it, MAX_RELEASE_NOTE_LENGTH, "") }
            .filter { it.isNotEmpty() }
            .take(MAX_RELEASE_NOTES)
            .toList(),
    ).takeIf {
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("github.com", ignoreCase = true) &&
            (url.port == -1 || url.port == 443) &&
            url.userInfo == null &&
            releasePath.startsWith("/doxigo/muchtoman/releases/")
    }
}

/** Same rule as [trustedCoinIcon]: this origin or the official one, and only that one path. */
private fun trustedApk(value: String, ratesUrl: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        val apk = URL(value)
        val trustedOrigin = apk.origin() == URL(ratesUrl).origin() ||
            apk.origin() == URL(OFFICIAL_RATES_ORIGIN).origin()
        if (
            !trustedOrigin ||
            !apk.protocol.equals("https", ignoreCase = true) ||
            apk.path != "/download" ||
            apk.userInfo != null ||
            apk.ref != null ||
            !apk.query.isNullOrBlank()
        ) "" else apk.toExternalForm()
    }.getOrDefault("")
}

private fun safeText(value: String, maxLength: Int, fallback: String): String =
    value.filterNot { it.isISOControl() }.trim().take(maxLength).ifBlank { fallback }

internal fun sanitizeRates(
    raw: Rates,
    ratesUrl: String,
    now: Long = System.currentTimeMillis(),
): Rates {
    val toman = LinkedHashMap<String, Double>()
    for ((id, value) in raw.toman) {
        if (toman.size >= MAX_RATE_ENTRIES) break
        if (
            id.isNotBlank() &&
            id.length <= MAX_ASSET_ID_LENGTH &&
            id.none { it.isISOControl() || it.isWhitespace() } &&
            value.isFinite() &&
            value > 0.0
        ) toman[id] = value
    }

    val seen = HashSet<String>()
    val coins = ArrayList<Coin>()
    for (coin in raw.coins) {
        if (coins.size >= MAX_COIN_ENTRIES) break
        val id = coin.id.trim()
        if (
            id.isEmpty() ||
            id.length > MAX_ASSET_ID_LENGTH ||
            id.any { it.isISOControl() || it.isWhitespace() } ||
            !seen.add(id)
        ) continue
        val wallets = coin.wallets.asSequence()
            .filter {
                it.network in WALLET_NETWORKS &&
                    it.contract.length <= MAX_CONTRACT_LENGTH &&
                    it.contract.none(Char::isISOControl) &&
                    isWalletContractFormatValid(it.network, it.contract)
            }
            .distinctBy { it.network }
            .take(MAX_WALLET_OPTIONS)
            .map {
                it.copy(
                    networkFa = safeText(it.networkFa, MAX_NETWORK_NAME_LENGTH, it.network),
                    contract = it.contract.trim(),
                )
            }
            .toList()
        coins += coin.copy(
            id = id,
            name = safeText(coin.name, MAX_COIN_NAME_LENGTH, id.uppercase()),
            en = safeText(coin.en, MAX_COIN_NAME_LENGTH, ""),
            icon = trustedCoinIcon(coin.icon, ratesUrl),
            wallets = wallets,
        )
    }

    return raw.copy(
        updatedAt = raw.updatedAt.takeIf { it in 1..(now + MAX_FUTURE_CLOCK_SKEW_MS) } ?: 0L,
        toman = toman,
        coins = coins,
        latest = trustedRelease(raw.latest, ratesUrl),
    )
}

internal fun InputStream.readUtf8Limited(maxBytes: Int): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) error("response too large")
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

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
// 11: the actual 90000258 thread proves it belongs to Blu, not Khavarmianeh. Rebuild every
// balance because messages from that sender were previously skipped or assigned to the wrong bank.
private const val SMS_SCHEMA = 11

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
        get() = sanitizeRates(read("rates", Rates()), BuildConfig.RATES_URL)
        set(v) = write("rates", v)

    /**
     * Last successful TSETMC snapshot. Kept apart from [cachedRates] because that is the
     * Worker's payload and this is the one thing the phone fetches for itself — and because
     * bourse prices go stale on their own schedule: outside Iran they never refresh at all,
     * and this is what keeps the shares she already priced showing a number.
     */
    var cachedStocks: TseSnapshot
        get() = read("stocks", TseSnapshot())
        set(v) = write("stocks", v)

    /** The release she has already waved off, so the note does not come back every time. */
    var dismissedUpdate: String
        get() = prefs.getString("dismissedUpdate", "").orEmpty()
        set(v) { prefs.edit().putString("dismissedUpdate", v).apply() }

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

    /**
     * Whether the home-screen widget masks the total with «٭٭٭». Its own switch, not the app
     * lock's: she may want the app guarded but the number glanceable, or the other way round.
     */
    var widgetLock: Boolean
        get() = prefs.getBoolean("widgetLock", false)
        set(v) { prefs.edit().putBoolean("widgetLock", v).apply() }

    /** Rates the user typed in by hand; these win over whatever the Worker says. */
    var overrides: Map<String, Double>
        get() = read("overrides", emptyMap())
        set(v) = write("overrides", v)

    /** One total per day, for the report chart. Written by AppVm, pruned by [recordDay]. */
    var history: Map<Long, Double>
        get() = read("history", emptyMap())
        set(v) = write("history", v)

    /**
     * Whether the first-run sheet has had its one turn.
     *
     * Written when she answers it *or* skips it, because the sheet's promise is that it appears
     * once. Everything it asks for stays reachable afterwards from تنظیمات, which is where a mind
     * changed a week later goes — a sheet that came back until it got the answer it wanted would
     * be a nag with a permission dialog attached.
     */
    var onboarded: Boolean
        // The second half is what keeps the sheet away from a phone that has been running this app
        // for a year. The flag did not exist before the sheet did, so every install that predates
        // it reads false — and any of these three keys being present means she has already made
        // the choices the sheet asks about, in the rooms the app used to ask them in. Deliberately
        // not cachedRates or history, which the app writes for itself on first launch and which
        // would mark a genuinely new phone as done before the sheet ever drew.
        get() = prefs.getBoolean("onboarded", false) ||
            prefs.contains("smsEnabled") || prefs.contains("name") || prefs.contains("holdings")
        set(v) { prefs.edit().putBoolean("onboarded", v).apply() }

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

    /**
     * What this phone has already told her about each budget, and for which window.
     *
     * Here rather than in `durable.db` on purpose: it is neither a message nor a decision of hers,
     * it is a note about a notification *this device* posted. Her husband's phone keeps its own and
     * neither owes the other one. Pruned on every write by [budgetNews], which only ever returns a
     * mark for a live budget in its current window — so a deleted budget and a finished month both
     * fall off without anything having to remember to sweep them.
     */
    var budgetMarks: List<BudgetMark>
        get() = read("budgetMarks", emptyList())
        set(v) = write("budgetMarks", v)

    /**
     * How far this phone has accounted for the review backlog: the newest transaction it has either
     * shown her or told her about. Beside [budgetMarks] and for the same reason — a note about what
     * *this* device has said, which is neither a message nor a decision of hers.
     *
     * Zero means it has never looked, and [filingNews] reads that as «seed, do not speak»: the first
     * pass after an install or an upgrade learns where the ledger is instead of announcing a backlog
     * she has been living with.
     */
    var filingMark: Long
        get() = prefs.getLong("filingMark", 0L)
        set(v) { prefs.edit().putLong("filingMark", v).apply() }

    /** How far the inbox has been read, so each scan only looks at what arrived since. */
    var smsScannedTo: Long
        get() = prefs.getLong("smsScannedTo", 0L)
        set(v) { prefs.edit().putLong("smsScannedTo", v).apply() }

    /**
     * Category ids she has told دخل و خرج to leave out. A way of reading the report and not a
     * fact about the ledger, which is why it lives here beside [budgetMarks] rather than in
     * either database — nothing about her money changes when this does.
     *
     * The default is [PASS_THROUGH_CATEGORIES]: a قرض out and back is one movement told in two
     * halves months apart, and counting it printed «۱۹۰٪ بیشتر از درآمدت خرج کردی» about money
     * that netted nothing. That used to be a separate switch on the month card; it is a seed
     * here instead, so there is one mechanism and it is hers.
     */
    var reportExcluded: Set<String>
        get() = read("reportExcluded", PASS_THROUGH_CATEGORIES.keys)
        set(v) = write("reportExcluded", v)

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
 * Toman by the time [parseBankSms] is done with them, and for the assets she prices herself.
 */
fun effectiveRates(
    fetched: Rates,
    overrides: Map<String, Double>,
    tse: TseSnapshot = TseSnapshot(),
): Map<String, Double> =
    fetched.toman + tse.toman + overrides + TOMAN_BY_DEFINITION

/**
 * Fresh prices, but the old catalogue if the new one is empty. When the Worker's name-and-logo
 * source is the thing that is down it sends prices with no coin list at all, and taking that
 * list at face value emptied the picker and turned every held coin into a bare ticker. Names
 * and logos do not go stale the way a price does, so the last ones we saw are still right.
 */
fun mergeRates(fresh: Rates, cached: Rates): Rates = fresh.copy(
    coins = fresh.coins.ifEmpty { cached.coins },
    // GitHub is a source like any other and fails on its own. One fetch that could not reach it
    // must not retract an update note already on screen.
    latest = fresh.latest ?: cached.latest,
)

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
            val body = conn.inputStream.use { it.readUtf8Limited(MAX_RATES_RESPONSE_BYTES) }
            val parsed = sanitizeRates(JSON.decodeFromString<Rates>(body), url)
            if (parsed.toman.isEmpty()) error("empty rates")
            if (parsed.updatedAt <= 0L) error("invalid rates timestamp")
            // updatedAt is when the Worker last pulled real prices, not when we asked — that
            // is the number worth showing, since a cached response is still old prices.
            parsed
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Wallet addresses go in a POST body so they do not land in URLs, edge-cache keys, or routine
 * access logs. The endpoint is deliberately derived from the configured rates origin: debug,
 * self-hosted, and release builds always ask the same Worker they already trust for prices.
 */
suspend fun fetchWalletBalance(ratesUrl: String, wallet: WalletLink): Result<WalletBalance> =
    withContext(Dispatchers.IO) {
        runCatching {
            if (!isWalletAddressFormatValid(wallet.network, wallet.address)) {
                throw WalletFetchException("invalid_address")
            }
            if (!isWalletContractFormatValid(wallet.network, wallet.contract)) {
                throw WalletFetchException("invalid_contract")
            }
            val rates = URL(ratesUrl)
            val endpoint = URL(rates.protocol, rates.host, rates.port, "/wallet-balance")
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Connection", "close")
            }
            try {
                val request = WalletBalanceRequest(
                    network = wallet.network,
                    address = wallet.address.trim(),
                    contract = wallet.contract,
                )
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(JSON.encodeToString(request))
                }

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readUtf8Limited(MAX_WALLET_RESPONSE_BYTES) }.orEmpty()
                if (status !in 200..299) {
                    val reason = runCatching { JSON.decodeFromString<WalletError>(body).code }
                        .getOrDefault("unavailable")
                    throw WalletFetchException(reason)
                }

                val balance = JSON.decodeFromString<WalletBalance>(body)
                if (!isWalletBalanceValid(balance)) {
                    throw WalletFetchException("invalid_response")
                }
                balance
            } finally {
                conn.disconnect()
            }
        }
    }
