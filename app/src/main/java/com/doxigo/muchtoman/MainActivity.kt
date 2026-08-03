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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

private const val WALLET_REFRESH_MS = 10 * 60_000L

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
            tse = store.cachedStocks,
            overrides = store.overrides,
            lockEnabled = store.lockEnabled,
            widgetLock = store.widgetLock,
            locked = store.lockEnabled,
            name = store.name,
            themeMode = store.themeMode,
            history = store.history,
            smsEnabled = store.smsEnabled,
            bankAccounts = store.bankAccounts,
            disabledBanks = store.disabledBanks,
            strangeSenders = store.strangeSenders,
            dismissedUpdate = store.dismissedUpdate,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        refreshWallets()
        scanSms()
        scheduleDailySnapshot(app)
    }

    /**
     * Called every time the app comes back to the foreground. The Worker's edge cache is
     * 10 minutes, so anything younger than that cannot change; anything older gets refetched
     * without her having to find the button.
     */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - _state.value.rates.updatedAt > 10 * 60_000L) refresh()
        refreshWallets()
        // Coming back is also when messages that arrived while she was away get read.
        scanSms()
    }

    fun refreshAll() {
        refresh()
        refreshWallets(force = true)
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // Two sources that stand alone: the Worker prices everything else, TSETMC prices
            // بورس and refuses connections from outside Iran. Whichever answers is applied.
            val rates = async { fetchRates(BuildConfig.RATES_URL) }
            val stocks = async { fetchTse() }

            var failure: String? = null
            rates.await().onSuccess { fetched ->
                val fresh = mergeRates(fetched, store.cachedRates)
                store.cachedRates = fresh
                _state.update { it.copy(rates = fresh) }
            }.onFailure { e ->
                // Keep showing the cached rates; an old number beats a blank screen. The
                // log line is the only place the real reason survives — the UI stays Persian
                // and calm, but `adb logcat -s muchtoman` must be able to answer "why".
                android.util.Log.w("muchtoman", "rates fetch failed: $e")
                failure = e.message ?: "خطا"
            }

            stocks.await().onSuccess { fresh ->
                store.cachedStocks = fresh
                _state.update { it.copy(tse = fresh) }
                // The count is the only outward sign that the whole market arrived rather
                // than as much of it as the socket managed before timing out.
                android.util.Log.i("muchtoman", "tse ok: ${fresh.stocks.size} instruments")
            }.onFailure { e ->
                // Deliberately not surfaced. This fails for everyone outside Iran, every
                // time, and an error banner about بورس on a screen that priced everything
                // else correctly would be noise. Shares already priced keep their cached
                // number; ones never priced are already named in the missing-rate note.
                android.util.Log.w("muchtoman", "tse fetch failed: $e")
            }

            _state.update { it.copy(loading = false, error = failure) }
            recordSnapshot()
        }
    }

    /** Waves off one release by name, so the next one asks again. */
    fun dismissUpdate(version: String) {
        store.dismissedUpdate = version
        _state.update { it.copy(dismissedUpdate = version) }
    }

    fun setHolding(key: String, typeId: String, amount: Double) {
        saveHolding(key, typeId, amount, wallet = null)
        _state.update { it.copy(walletErrors = it.walletErrors - key) }
    }

    /** Her own name for a holding, or blank to go back to the asset's own. */
    fun setLabel(key: String, label: String) = persist(
        _state.value.holdings.map {
            if (it.key == key) it.copy(label = label.trim().take(32)) else it
        },
    )

    /**
     * Writes the row [key] names, or adds one under that key if she has no such row yet — which
     * is how a second Tether beside the first one gets made: the picker hands out a fresh key
     * rather than the asset's own, so nothing here can find the holding she already had.
     */
    private fun saveHolding(key: String, typeId: String, amount: Double, wallet: WalletLink?) {
        val list = _state.value.holdings
        // Copied, not rebuilt: editing the amount must not quietly un-exclude a set-aside
        // asset, nor drop the name she gave it.
        val next = if (list.any { it.key == key }) {
            list.map { if (it.key == key) it.copy(amount = amount, wallet = wallet) else it }
        } else {
            list + Holding(typeId = typeId, amount = amount, wallet = wallet, id = key)
        }
        // Fixed assets keep their catalogue order; coins fall in after them in the order she
        // added them (sortedBy is stable), since there is no meaningful order for 250 coins.
        val order = STATIC_CATALOG.withIndex().associate { (i, t) -> t.id to i }
        persist(next.sortedBy { order[it.typeId] ?: Int.MAX_VALUE })
    }

    /** A rainy-day asset: stays on the list, drops out of the total. */
    fun setExcluded(key: String, excluded: Boolean) =
        persist(_state.value.holdings.map {
            if (it.key == key) it.copy(excluded = excluded) else it
        })

    fun removeHolding(key: String) {
        persist(_state.value.holdings.filterNot { it.key == key })
        _state.update {
            it.copy(
                refreshingWallets = it.refreshingWallets - key,
                walletErrors = it.walletErrors - key,
            )
        }
    }

    fun clearWalletError(key: String) {
        if (key !in _state.value.walletErrors) return
        _state.update { it.copy(walletErrors = it.walletErrors - key) }
    }

    fun connectWallet(
        key: String,
        typeId: String,
        option: WalletOption,
        address: String,
        onSuccess: () -> Unit,
    ) {
        if (key in _state.value.refreshingWallets) return
        val wallet = WalletLink(
            network = option.network,
            networkFa = option.networkFa,
            address = address.trim(),
            contract = option.contract,
        )
        _state.update {
            it.copy(
                refreshingWallets = it.refreshingWallets + key,
                walletErrors = it.walletErrors - key,
            )
        }
        viewModelScope.launch {
            fetchWalletBalance(BuildConfig.RATES_URL, wallet)
                .onSuccess { balance ->
                    saveHolding(
                        key,
                        typeId,
                        balance.amount,
                        wallet.copy(updatedAt = balance.updatedAt),
                    )
                    _state.update {
                        it.copy(
                            refreshingWallets = it.refreshingWallets - key,
                            walletErrors = it.walletErrors - key,
                        )
                    }
                    onSuccess()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            refreshingWallets = it.refreshingWallets - key,
                            walletErrors = it.walletErrors + (key to walletErrorMessage(error)),
                        )
                    }
                }
        }
    }

    fun refreshWallets(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val busy = _state.value.refreshingWallets
        val tracked = _state.value.holdings.filter { holding ->
            val wallet = holding.wallet ?: return@filter false
            holding.key !in busy && (force || now - wallet.updatedAt >= WALLET_REFRESH_MS)
        }
        if (tracked.isEmpty()) return

        val ids = tracked.map { it.key }.toSet()
        _state.update {
            it.copy(
                refreshingWallets = it.refreshingWallets + ids,
                walletErrors = it.walletErrors - ids,
            )
        }
        viewModelScope.launch {
            val results = tracked.map { holding ->
                async { holding to fetchWalletBalance(BuildConfig.RATES_URL, holding.wallet!!) }
            }.awaitAll().associateBy { it.first.key }

            val current = _state.value
            val errors = current.walletErrors.toMutableMap()
            var changed = false
            val next = current.holdings.map { holding ->
                val (original, result) = results[holding.key] ?: return@map holding
                val wallet = holding.wallet ?: return@map holding
                if (wallet != original.wallet) return@map holding
                result.fold(
                    onSuccess = { balance ->
                        changed = true
                        errors -= holding.key
                        holding.copy(
                            amount = balance.amount,
                            wallet = wallet.copy(updatedAt = balance.updatedAt),
                        )
                    },
                    onFailure = { error ->
                        errors[holding.key] = walletErrorMessage(error)
                        holding
                    },
                )
            }

            if (changed) store.holdings = next
            _state.update {
                it.copy(
                    holdings = next,
                    refreshingWallets = it.refreshingWallets - ids,
                    walletErrors = errors,
                )
            }
            if (changed) recordSnapshot()
        }
    }

    private fun walletErrorMessage(error: Throwable): String =
        when ((error as? WalletFetchException)?.reason) {
            "invalid_address", "invalid_contract" ->
                "آدرس با شبکه انتخاب شده همخوان نیست."
            "unsupported_network" -> "این شبکه هنوز پشتیبانی نمی‌شود."
            else -> "موجودی دریافت نشد. اتصال را بررسی کنید و دوباره تلاش کنید."
        }

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

    /** The widget's own mask, independent of the app lock. Takes effect on the spot. */
    fun setWidgetLock(on: Boolean) {
        store.widgetLock = on
        _state.update { it.copy(widgetLock = on) }
        updateTotalWidget(getApplication())
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
            // Re-keyed through senderKey on the way in: the key function has changed once
            // already (whitespace collapse), and a dismissal stored under an old key must
            // stay dismissed. senderKey is a fixpoint over its own output.
            val dismissed = store.dismissedSenders.map(::senderKey).toSet()
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
                    if (
                        from.isNotEmpty() &&
                        senderKey(from) !in dismissed &&
                        !isIgnoredBankSms(from, m.body) &&
                        looksLikeBankSms(m.body)
                    ) {
                        guessBank(m.body)?.let { g ->
                            // Replace rather than skip. Messages arrive oldest first, so
                            // keeping the first sighting pinned every active sender to its
                            // earliest date — and the sort below then buried the sender she is
                            // actually asking about underneath a dozen one-off promos.
                            strangers.removeAll { senderKey(it.sender) == senderKey(from) }
                            strangers += StrangeSender(
                                sender = from,
                                bank = g.name,
                                snippet = snippetOf(m.body),
                                at = m.at,
                            )
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
     * Remembers today's total for the report chart, via the same [snapshotHistory] the daily
     * worker uses — and since this runs on every path that touches money, it is also the
     * moment the home-screen widget learns the new total.
     */
    private fun recordSnapshot() {
        val s = _state.value
        // listHoldings, not holdings: someone whose only money is bank balances read from her
        // messages has an empty holdings list and a perfectly good total, and guarding on the
        // raw list left her report saying "هنوز نموداری نیست" for ever.
        snapshotHistory(
            s.history, s.listHoldings, s.effective, s.rates.updatedAt,
            System.currentTimeMillis(),
        )?.let { next ->
            store.history = next
            _state.update { it.copy(history = next) }
        }
        updateTotalWidget(getApplication())
    }
}

data class UiState(
    val holdings: List<Holding> = emptyList(),
    val rates: Rates = Rates(),
    val overrides: Map<String, Double> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val lockEnabled: Boolean = false,
    val widgetLock: Boolean = false,
    val locked: Boolean = false,
    val name: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val history: Map<Long, Double> = emptyMap(),
    val smsEnabled: Boolean = false,
    val bankAccounts: List<BankAccount> = emptyList(),
    val disabledBanks: Set<String> = emptySet(),
    val strangeSenders: List<StrangeSender> = emptyList(),
    val refreshingWallets: Set<String> = emptySet(),
    val walletErrors: Map<String, String> = emptyMap(),
    val dismissedUpdate: String = "",
    val tse: TseSnapshot = TseSnapshot(),
) {
    val coins: List<Coin> get() = rates.coins
    val stocks: List<Stock> get() = tse.stocks

    /**
     * The release worth a line on the list: newer than this build, and not one she has already
     * waved off. Debug builds are left out because they carry a placeholder version name, so
     * every locally-built install would claim to be out of date.
     */
    val update: Release?
        get() = rates.latest?.takeIf {
            !BuildConfig.DEBUG &&
                it.url.isNotBlank() &&
                it.name != dismissedUpdate &&
                isNewerVersion(it.name, BuildConfig.VERSION_NAME)
        }
    val effective: Map<String, Double> get() = effectiveRates(rates, overrides, tse)
    val refreshing: Boolean get() = loading || refreshingWallets.isNotEmpty()

    /** The tracked balances as one figure, minus the banks she switched off. */
    val bankToman: Double get() = bankTotal(bankAccounts, disabledBanks)

    /** True while any tracked balance is a guess rather than something a bank stated. */
    val bankUnsure: Boolean
        get() = bankAccounts.any { it.bank !in disabledBanks && !it.trusted }

    /** See [com.doxigo.muchtoman.listHoldings] — shared with the widget and the daily worker. */
    val listHoldings: List<Holding>
        get() = listHoldings(holdings, smsEnabled, bankAccounts, disabledBanks)

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
