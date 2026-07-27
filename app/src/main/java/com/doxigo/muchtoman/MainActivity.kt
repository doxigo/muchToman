package com.doxigo.muchtoman

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppVm(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    /**
     * Bumped by every scan. Reading the inbox suspends, and in that gap a rescan can wipe
     * everything and start again — confirming a sender does exactly that. A scan that wakes to
     * find itself superseded must throw its work away rather than write balances computed
     * against a store that no longer exists.
     */
    private var scanGeneration = 0

    private val _state = MutableStateFlow(
        UiState(
            holdings = store.holdings,
            rates = store.cachedRates,
            overrides = store.overrides,
            lockEnabled = store.lockEnabled,
            locked = store.lockEnabled,
            name = store.name,
            themeMode = store.themeMode,
            history = store.history,
            smsEnabled = store.smsEnabled,
            bankAccounts = store.bankAccounts,
            disabledBanks = store.disabledBanks,
            strangeSenders = store.strangeSenders,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        scanSms()
    }

    /**
     * Called every time the app comes back to the foreground. The Worker's edge cache is
     * 10 minutes, so anything younger than that cannot change; anything older gets refetched
     * without her having to find the button.
     */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - _state.value.rates.updatedAt > 10 * 60_000L) refresh()
        // Coming back is also when messages that arrived while she was away get read.
        scanSms()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = fetchRates(BuildConfig.RATES_URL)
            result.onSuccess { fetched ->
                val fresh = mergeRates(fetched, store.cachedRates)
                store.cachedRates = fresh
                _state.update { it.copy(loading = false, rates = fresh, error = null) }
                recordSnapshot()
            }.onFailure { e ->
                // Keep showing the cached rates; an old number beats a blank screen. The
                // log line is the only place the real reason survives — the UI stays Persian
                // and calm, but `adb logcat -s muchtoman` must be able to answer "why".
                android.util.Log.w("muchtoman", "rates fetch failed: $e")
                _state.update { it.copy(loading = false, error = e.message ?: "خطا") }
            }
        }
    }

    fun setHolding(typeId: String, amount: Double) {
        // Editing the amount must not quietly un-exclude a set-aside asset.
        val prev = _state.value.holdings.firstOrNull { it.typeId == typeId }
        val next = _state.value.holdings.filterNot { it.typeId == typeId } +
            Holding(typeId, amount, excluded = prev?.excluded ?: false)
        // Fixed assets keep their catalogue order; coins fall in after them in the order she
        // added them (sortedBy is stable), since there is no meaningful order for 250 coins.
        val order = STATIC_CATALOG.withIndex().associate { (i, t) -> t.id to i }
        persist(next.sortedBy { order[it.typeId] ?: Int.MAX_VALUE })
    }

    /** A rainy-day asset: stays on the list, drops out of the total. */
    fun setExcluded(typeId: String, excluded: Boolean) =
        persist(_state.value.holdings.map {
            if (it.typeId == typeId) it.copy(excluded = excluded) else it
        })

    fun removeHolding(typeId: String) =
        persist(_state.value.holdings.filterNot { it.typeId == typeId })

    fun setName(name: String) {
        store.name = name
        _state.update { it.copy(name = name.trim()) }
    }

    fun setThemeMode(mode: ThemeMode) {
        store.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    /** Locked state is session-only; the *preference* is what persists. */
    fun setLockEnabled(on: Boolean) {
        store.lockEnabled = on
        _state.update { it.copy(lockEnabled = on, locked = false) }
    }

    /** Called when the app leaves the foreground, so returning to it asks again. */
    fun relock() {
        if (store.lockEnabled) _state.update { it.copy(locked = true) }
    }

    fun unlock() = _state.update { it.copy(locked = false) }

    /**
     * Reads whatever has landed in the inbox since the last look and folds it into the
     * balances. Cheap enough to run on every foreground: the query is bounded by the last scan
     * and a message already counted is skipped by content key, never re-applied.
     */
    fun scanSms() {
        val app = getApplication<Application>()
        if (!store.smsEnabled) return
        // She can take the permission away in Android's own settings, and then these balances
        // are frozen at whatever they last read while her real accounts move on. Switching the
        // feature off is what keeps them out of the total — they stay listed, and turning it
        // back on re-reads everything since.
        if (!canReadSms(app)) {
            setSmsEnabled(false)
            return
        }
        val generation = ++scanGeneration
        viewModelScope.launch {
            val messages = readSmsInbox(app, store.smsScannedTo)
            if (generation != scanGeneration) return@launch // superseded mid-read; discard
            if (messages.isEmpty()) return@launch

            val extra = extraLookup(store.extraBankNumbers)
            val dismissed = store.dismissedSenders
            var accounts = store.bankAccounts
            val seen = store.seenSms
            val fresh = mutableListOf<String>()
            val strangers = store.strangeSenders.toMutableList()
            for (m in messages) {
                val key = smsKey(m.body, m.at)
                if (key in seen || key in fresh) continue
                val parsed = parseBankSms(m.from, m.body, m.at, extra)
                if (parsed == null) {
                    // Skipped, and normally that is the end of it. The one silence worth
                    // breaking: a message that names one of HER banks from a number the list
                    // lacks — that is how a bank that adds a shortcode "freezes". It becomes a
                    // one-tap suggestion in the sheet, and her tap is what adds the number.
                    val from = m.from.trim()
                    if (from.isNotEmpty() && senderKey(from) !in dismissed && looksLikeBankSms(m.body)) {
                        guessBank(m.body)?.let { g ->
                            // Replace rather than skip. Messages arrive oldest first, so
                            // keeping the first sighting pinned every active sender to its
                            // earliest date — and the sort below then buried the sender she is
                            // actually asking about underneath a dozen one-off promos.
                            strangers.removeAll { senderKey(it.sender) == senderKey(from) }
                            strangers += StrangeSender(from, g.name, snippetOf(m.body), m.at)
                        }
                    }
                    continue
                }
                accounts = applyBankSms(accounts, parsed)
                fresh += key
            }

            store.bankAccounts = accounts
            store.seenSms = rememberSeen(seen, fresh)
            // A stranger whose number has since been added resolves and drops off the list, as
            // does one she dismissed. Newest first, so the message she is asking about — which
            // is almost always the latest — sits at the top instead of behind old promos.
            store.strangeSenders = strangers
                .filter { bankOf(it.sender, extra) == null && senderKey(it.sender) !in dismissed }
                .sortedByDescending { it.at }
                .take(12)
            _state.update {
                it.copy(bankAccounts = accounts, strangeSenders = store.strangeSenders)
            }
            // Advanced past everything we looked at, parsed or not — an advert from a bank is
            // not worth re-reading on every launch. Never past now, though: one inbox row
            // stamped in 2030 by a restored backup or a skewed carrier clock would otherwise
            // put the watermark there and silently freeze every balance for ever after.
            store.smsScannedTo = minOf(messages.maxOf { it.at }, System.currentTimeMillis())
            _state.update { it.copy(bankAccounts = accounts) }
            recordSnapshot()
        }
    }

    /**
     * Forget every balance and read the inbox again from the beginning.
     *
     * Messages are read once and then skipped for ever, by a watermark and a seen-list — which
     * is right until the reading itself changes, and then every balance is frozen at what an
     * older parser made of it with no way back. An upgrade clears it automatically; this is the
     * same thing on demand, so a figure that has gone wrong is never a dead end.
     */
    fun rescanSms() {
        store.bankAccounts = emptyList()
        store.seenSms = emptySet()
        store.smsScannedTo = 0L
        store.strangeSenders = emptyList()
        _state.update { it.copy(bankAccounts = emptyList(), strangeSenders = emptyList()) }
        scanSms()
    }

    /** She waved a suggestion away. It stays away — across rescans and upgrades too. */
    fun dismissSender(sender: String) {
        store.dismissedSenders = store.dismissedSenders + senderKey(sender)
        store.strangeSenders =
            store.strangeSenders.filterNot { senderKey(it.sender) == senderKey(sender) }
        _state.update { it.copy(strangeSenders = store.strangeSenders) }
    }

    /**
     * She confirmed a stranger: this number is one of her banks. It joins that bank's list on
     * this phone only, and everything is re-read so the messages it already sent finally count.
     */
    fun addBankNumber(bank: String, sender: String) {
        if (runCatching { Bank.valueOf(bank) }.getOrNull() == null || sender.isBlank()) return
        val cur = store.extraBankNumbers
        store.extraBankNumbers = cur + (bank to ((cur[bank] ?: emptyList()) + sender).distinct())
        rescanSms()
    }

    /** Turning it off leaves the balances where they are; it stops them moving on their own. */
    fun setSmsEnabled(on: Boolean) {
        store.smsEnabled = on
        _state.update { it.copy(smsEnabled = on) }
        if (on) scanSms()
    }

    /** A bank she switched off stays tracked and stays listed — it just leaves the total. */
    fun setBankEnabled(bank: String, on: Boolean) {
        val next = if (on) store.disabledBanks - bank else store.disabledBanks + bank
        store.disabledBanks = next
        _state.update { it.copy(disabledBanks = next) }
        recordSnapshot()
    }

    /** She tells us what an account really holds; everything read after it builds on that. */
    fun setBankBalance(key: String, balance: Double) {
        val next = anchorAccount(store.bankAccounts, key, balance, System.currentTimeMillis())
        store.bankAccounts = next
        _state.update { it.copy(bankAccounts = next) }
        recordSnapshot()
    }

    /**
     * Drops an account that was read wrong. The messages behind it stay marked as seen on
     * purpose: re-reading them would rebuild the same wrong figure. It starts again, from
     * zero and unanchored, at the next message that bank sends.
     */
    fun forgetBankAccount(key: String) {
        val next = store.bankAccounts.filterNot { it.key == key }
        store.bankAccounts = next
        _state.update { it.copy(bankAccounts = next) }
        recordSnapshot()
    }

    fun setOverride(typeId: String, rate: Double?) {
        val next = _state.value.overrides.toMutableMap()
        if (rate == null) next -= typeId else next[typeId] = rate
        store.overrides = next
        _state.update { it.copy(overrides = next) }
        recordSnapshot()
    }

    private fun persist(list: List<Holding>) {
        store.holdings = list
        _state.update { it.copy(holdings = list) }
        recordSnapshot()
    }

    /**
     * Remembers today's total for the report chart — but only a total worth remembering:
     * every holding priced, rates younger than a day. A partial or stale total drawn into
     * the chart would look like a crash that never happened.
     */
    private fun recordSnapshot() {
        val s = _state.value
        // listHoldings, not holdings: someone whose only money is bank balances read from her
        // messages has an empty holdings list and a perfectly good total, and guarding on the
        // raw list left her report saying "هنوز نموداری نیست" for ever.
        if (s.listHoldings.isEmpty()) return
        if (System.currentTimeMillis() - s.rates.updatedAt > 24 * 60 * 60_000L) return
        val totals = s.totals
        if (totals.missing.isNotEmpty()) return
        val next = recordDay(s.history, System.currentTimeMillis() / DAY_MS, totals.toman)
        store.history = next
        _state.update { it.copy(history = next) }
    }
}

data class UiState(
    val holdings: List<Holding> = emptyList(),
    val rates: Rates = Rates(),
    val overrides: Map<String, Double> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val lockEnabled: Boolean = false,
    val locked: Boolean = false,
    val name: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val history: Map<Long, Double> = emptyMap(),
    val smsEnabled: Boolean = false,
    val bankAccounts: List<BankAccount> = emptyList(),
    val disabledBanks: Set<String> = emptySet(),
    val strangeSenders: List<StrangeSender> = emptyList(),
) {
    val coins: List<Coin> get() = rates.coins
    val effective: Map<String, Double> get() = effectiveRates(rates, overrides)

    /** The tracked balances as one figure, minus the banks she switched off. */
    val bankToman: Double get() = bankTotal(bankAccounts, disabledBanks)

    /** True while any tracked balance is a guess rather than something a bank stated. */
    val bankUnsure: Boolean
        get() = bankAccounts.any { it.bank !in disabledBanks && !it.trusted }

    /**
     * What the list actually shows: her own holdings, plus — once there is anything to show —
     * one row standing for every bank account, dropped in right after her cash so the تومان
     * section reads cash first, then bank. It is not persisted with the holdings: its amount
     * is not hers to type, and it must vanish the moment she stops reading messages.
     */
    val listHoldings: List<Holding>
        get() {
            if (!smsEnabled || bankAccounts.isEmpty()) return holdings
            val bank = Holding(BANK_ID, bankToman)
            val cash = holdings.indexOfFirst { it.typeId == TOMAN_ID }
            return if (cash < 0) listOf(bank) + holdings
            else holdings.take(cash + 1) + bank + holdings.drop(cash + 1)
        }

    val totals: Totals get() = computeTotals(listHoldings, effective)
}

// FragmentActivity, not ComponentActivity: androidx.biometric's BiometricPrompt requires it.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The view model is resolved before the theme, because the theme depends on a
            // preference it owns.
            val vm: AppVm = viewModel()
            val state by vm.state.collectAsState()

            MuchTomanTheme(mode = state.themeMode) {
                // The whole app is Persian, so force RTL regardless of the phone's locale.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {

                    // With the lock on, keep the total out of the app-switcher thumbnail too —
                    // otherwise the number is readable without ever unlocking anything.
                    //
                    // That is all it was ever meant to do. FLAG_SECURE does it by blocking
                    // every form of screen capture, which also stops her sending a screenshot
                    // to whoever helps her with the phone — a real cost for a lock that only
                    // guards a number. Where the platform offers the narrow tool, use it.
                    LaunchedEffect(state.lockEnabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            setRecentsScreenshotEnabled(!state.lockEnabled)
                        } else if (state.lockEnabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }

                    AppRoot(vm, this@MainActivity)
                }
            }
        }
    }
}
