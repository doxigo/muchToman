package com.doxigo.muchtoman

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.withTransaction
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.io.encoding.Base64

private const val WALLET_REFRESH_MS = 10 * 60_000L
private const val STOCK_REFRESH_MS = 10 * 60_000L
private const val MAX_PARALLEL_WALLET_FETCHES = 4

class AppVm(app: Application) : AndroidViewModel(app) {
    // Before the first Store read, deliberately: if a staged restore is waiting, the swap and the
    // prefs rewrite happen now, so the state built two lines down — and the first frame after
    // «ببند و باز کن» — is already the backup. Milliseconds when idle (one file stat), renames
    // when not; the bytes were written at import time.
    private val restoredAtLaunch = DurableDb.completePendingRestore(app)
    private val store = Store(app)

    // Belt over the derived.db wipe the completion already did: if anything had the derived file
    // open at that instant, its marker would still match and needsDerive would wave the old rows
    // through. One forced full derive costs under a second and closes that hole for good.
    private var deriveAfterRestore = restoredAtLaunch

    /**
     * The scan in flight, if any. Only one runs at a time, and anything that wants the inbox
     * read differently — from the start, or from further back — waits for this one to finish
     * before changing what it would read. Without that, a scan can wake up holding balances
     * computed against a store that has since been wiped and write them straight back over it.
     */
    private var scanJob: Job? = null
    private var stockJob: Job? = null
    private val walletSemaphore = Semaphore(MAX_PARALLEL_WALLET_FETCHES)

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
            onboarded = store.onboarded,
            smsEnabled = store.smsEnabled,
            bankAccounts = store.bankAccounts,
            disabledBanks = store.disabledBanks,
            strangeSenders = store.strangeSenders,
            dismissedUpdate = store.dismissedUpdate,
            reportExcluded = store.reportExcluded,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var ledgerJob: Job? = null
    private var familySyncJob: Job? = null

    init {
        refresh()
        refreshStocksIfHeld()
        refreshWallets()
        scanSms()
        runLedger()
        viewModelScope.launch {
            ledgerJob?.join()
            requestFamilySync(silent = true)
        }
        scheduleDailySnapshot(app)
    }

    /**
     * Every path that changes the ledger ends here, and this is the only place that publishes it.
     *
     * It exists for the second line. A budget is a figure over these entries, so *anything* that
     * moves them can be the thing that crosses a cap — a message arriving, a receipt refiled, a
     * transfer link rejected, a budget created against a month that is already spent. Eight callers
     * each remembering to check would be eight chances to miss one, and the one it missed would be
     * the alert that never came.
     *
     * [announceBudgets] is idempotent and says nothing when there is nothing new, so calling it on
     * every publish costs a walk over a handful of budgets and buys the guarantee.
     */
    private suspend fun publishLedger(durable: DurableDb, derived: DerivedDb) {
        val app = getApplication<Application>()
        // One publisher at a time past this line. [LedgerWatchWorker] runs the same read →
        // announce → mark sequence from its own coroutine, and the marks those helpers write are
        // get-then-set on prefs — interleaved, an alert is said twice or a mark is lost.
        // [ledgerGate] is not reentrant, and nothing called under it takes it: ledgerView,
        // scheduleLedgerWatch, announceBudgets and markFilingSeen have all been checked.
        ledgerGate.withLock {
            val view = ledgerView(derived, durable)
            _state.update { it.copy(ledger = view) }
            // Watching is scheduled by whether there is anything left to watch, so deleting the last
            // budget on a phone that does not read messages stops the worker rather than leaving it to
            // wake up and find nothing four times a day.
            scheduleLedgerWatch(app, view.budgets.isNotEmpty() || store.smsEnabled)
            announceBudgets(app, store, view.budgets)
            // The other half is deliberately not announced here: this line runs with the app in front
            // of her, and the backlog it would describe is on the tab badge two inches below. Seeing it
            // is being told, so the note comes down and the mark moves past everything on screen —
            // see [markFilingSeen].
            markFilingSeen(app, store, view)
        }
    }

    /**
     * The ledger, deliberately **not** behind the SMS permission.
     *
     * Ingest needs `READ_SMS`; deriving does not, and wiring the two together broke the one
     * promise the split was built for — a parser fix rebuilding her ledger offline, from the
     * messages already stored, after she has revoked the permission. It ran inside the scan at
     * first, and revoking the permission silently stopped every rebuild.
     *
     * It also runs beside the balance fold rather than instead of it, and writes nothing the
     * fold reads, so a failure here costs a rebuild later and never a balance now.
     */
    fun runLedger() {
        if (ledgerJob?.isActive == true) return
        val app = getApplication<Application>()
        ledgerJob = viewModelScope.launch(Dispatchers.Default) {
            runCatching { runLedgerPipeline(app, extraLookup(store.extraBankNumbers)) }
                .onFailure { android.util.Log.w("muchtoman", "ledger pipeline failed: $it") }
        }
    }

    /**
     * Called every time the app comes back to the foreground. The Worker's edge cache is
     * 10 minutes, so anything younger than that cannot change; anything older gets refetched
     * without her having to find the button.
     */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - _state.value.rates.updatedAt > 10 * 60_000L) refresh()
        refreshStocksIfHeld()
        refreshWallets()
        // Coming back is also when messages that arrived while she was away get read.
        scanSms()
        runLedger()
        requestFamilySync(silent = true)
    }

    fun refreshAll() {
        refresh()
        refreshStocksIfHeld(force = true)
        refreshWallets(force = true)
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var failure: String? = null
            fetchRates(BuildConfig.RATES_URL).onSuccess { fetched ->
                // The merge reads the cached blob and the store write encodes ~80KB of JSON —
                // that ran on Main and cost a frame after every fetch. The state update stays
                // on the calling context; only the decode/merge/encode moves.
                val fresh = withContext(Dispatchers.Default) {
                    mergeRates(fetched, store.cachedRates).also { store.cachedRates = it }
                }
                _state.update { it.copy(rates = fresh) }
            }.onFailure { e ->
                // Keep showing the cached rates; an old number beats a blank screen. The
                // log line is the only place the real reason survives — the UI stays Persian
                // and calm, but `adb logcat -s muchtoman` must be able to answer "why".
                android.util.Log.w("muchtoman", "rates fetch failed: $e")
                failure = "نرخ‌ها به‌روز نشدن. اینترنتت رو چک کن."
            }

            _state.update { it.copy(loading = false, error = failure) }
            recordSnapshot()
        }
    }

    fun refreshStocksForPicker() = refreshStocks(requireHolding = false)

    private fun refreshStocksIfHeld(force: Boolean = false) =
        refreshStocks(force = force, requireHolding = true)

    private fun refreshStocks(force: Boolean = false, requireHolding: Boolean) {
        val current = _state.value
        if (requireHolding && current.holdings.none { isStockId(it.typeId) }) return
        if (!force && System.currentTimeMillis() - current.tse.updatedAt <= STOCK_REFRESH_MS) return
        if (stockJob?.isActive == true) return

        _state.update { it.copy(stocksLoading = true) }
        stockJob = viewModelScope.launch {
            var changed = false
            fetchTse().onSuccess { fresh ->
                changed = true
                // A couple of thousand نماد encode to a sizeable blob; written off Main for the
                // same reason refresh() moved its merge there.
                withContext(Dispatchers.Default) { store.cachedStocks = fresh }
                _state.update { it.copy(tse = fresh) }
                android.util.Log.i("muchtoman", "tse ok: ${fresh.stocks.size} instruments")
            }.onFailure { error ->
                android.util.Log.w("muchtoman", "tse fetch failed: $error")
            }
            _state.update { it.copy(stocksLoading = false) }
            if (changed && _state.value.holdings.any { isStockId(it.typeId) }) recordSnapshot()
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
        persist(catalogOrdered(next))
    }

    /**
     * Fixed assets keep their catalogue order; coins fall in after them in the order she
     * added them (sortedBy is stable), since there is no meaningful order for 250 coins.
     */
    private fun catalogOrdered(list: List<Holding>): List<Holding> {
        val order = STATIC_CATALOG.withIndex().associate { (i, t) -> t.id to i }
        return list.sortedBy { order[it.typeId] ?: Int.MAX_VALUE }
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

    /**
     * Puts back a holding [removeHolding] took off, exactly as it was — amount, label, wallet
     * link and the set-aside flag all ride the same object, so undo restores all of it.
     * Unreferenced for now: the UI wave wires the undo affordance to it.
     */
    fun reinstateHolding(h: Holding) {
        val list = _state.value.holdings
        if (list.any { it.key == h.key }) return // undo tapped twice; it is already back
        persist(catalogOrdered(list + h))
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
            walletSemaphore.withPermit { fetchWalletBalance(BuildConfig.RATES_URL, wallet) }
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
                async {
                    holding to walletSemaphore.withPermit {
                        fetchWalletBalance(BuildConfig.RATES_URL, holding.wallet!!)
                    }
                }
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
                "این آدرس با شبکه انتخاب‌شده جور نیست."
            "unsupported_network" -> "هنوز نمی‌شه از این شبکه استفاده کرد."
            else -> "موجودی نیومد. اینترنتت رو چک کن و دوباره امتحان کن."
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

    /**
     * A category of her own, with the mark she picked for it.
     *
     * Nothing is re-derived and nothing is re-classified: a new category is an answer that becomes
     * available, not one that changes any answer already given. It appears in the picker on the
     * next read of the ledger, which is the update below.
     */
    fun addCategory(nameFa: String, kind: String, glyph: CategoryGlyph) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            runCatching {
                durable.categories().putAll(
                    listOf(customCategory(nameFa, kind, glyph, System.currentTimeMillis()))
                )
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "addCategory failed: $it") }
        }
    }

    /**
     * Archived, never deleted — every transaction she ever filed under it still names it, and the
     * timeline reads that name off the row rather than off this table.
     */
    fun archiveCategory(category: Category) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            runCatching {
                durable.categories().putAll(
                    listOf(category.copy(archived = true, updatedAt = System.currentTimeMillis()))
                )
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "archiveCategory failed: $it") }
        }
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
        // One at a time. Two used to start on every cold open — init{} launches one and the
        // lifecycle observer replays ON_START into refreshIfStale() a moment later — and both
        // walked the whole inbox for one of them to be thrown away at the end.
        if (scanJob?.isActive == true) return
        // Dispatchers.Default: only the cursor read was ever off the UI thread. Everything
        // after it — the JSON decode of every balance and of every message already counted,
        // the parse of each message, and the encode on the way back — ran on the main thread,
        // and 1.0.2's schema bump makes the first scan after an upgrade the WHOLE inbox. On a
        // phone with years of messages that is the app frozen on first open.
        scanJob = viewModelScope.launch(Dispatchers.Default) { runScan(app) }
    }

    /**
     * ingest → derive, and then a check that the two ways of reaching a balance agree.
     *
     * Ingest starts at the first of the current Jalali month and grows forward from there. It
     * used to reach thirteen months back on the first run, which handed a new user a ledger with
     * a year of unfiled transactions in it — history she never asked for, priced as a review deck
     * she would never finish.
     *
     * The fold and the derivation both run for now, and disagreeing is a log line rather than a
     * visible change. The fold is what she sees until they have agreed on a real phone for a
     * release; then the fold goes and this becomes the only answer.
     */
    private suspend fun runLedgerPipeline(app: Application, extra: Map<String, Bank>) {
        val durable = DurableDb.get(app)
        val derived = DerivedDb.get(app)

        migrateAnchorsFromPrefs(store.bankAccounts, durable, System.currentTimeMillis())
        seedBuiltins(durable)
        loadSession(durable)?.let { refreshFamily(it) }
        val added = ingestBankSms(app, durable, extra)
        // Derive when anything new arrived, or when this build reads messages differently from
        // whatever produced the rows already there. The second case needs no inbox, no
        // permission and no network — it is why a parser fix is now a Tuesday job.
        if (added > 0 || deriveAfterRestore || needsDerive(derived)) {
            deriveAfterRestore = false
            val rows = derive(durable, derived, extra)
            android.util.Log.i("muchtoman", "ledger: +$added messages, $rows transactions")
        }

        publishLedger(durable, derived)

        // Only a disagreement about the *same* evidence is worth a line, and anchored balances
        // are the only ones that qualify: a stated مانده is an absolute figure both sides must
        // land on. The two read from different start points on purpose — the fold walks the whole
        // inbox, ingest starts at this month — so an unanchored balance is a sum of deltas over a
        // different span on each side, and comparing those two numbers only ever prints noise.
        for (balance in allBalances(derived, durable)) {
            if (!balance.anchored) continue
            val folded = store.bankAccounts.firstOrNull { it.bank == balance.accountId } ?: continue
            if (!folded.anchored) continue
            if (balance.at > folded.updatedAt) continue
            val foldedRial = Math.round(folded.balance * 10.0)
            if (foldedRial != balance.rial) {
                android.util.Log.w(
                    "muchtoman",
                    "ledger disagrees on ${balance.accountId} over the same messages: " +
                        "fold=$foldedRial derived=${balance.rial} Rial (fold at ${folded.updatedAt}, " +
                        "ledger at ${balance.at})",
                )
            }
        }
    }

    /**
     * File one transaction, and — if she says «همیشه» — teach the app to file everything like
     * it by itself, past and future both.
     *
     * There is no backfill job behind that promise. Classification is total, so one new rule
     * plus one re-derive is the whole mechanism, and it applies to every transaction she has
     * ever had rather than only the ones that arrive next.
     */
    fun categorise(entry: LedgerEntry, categoryId: String, always: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val derived = DerivedDb.get(app)
            runCatching {
                val session = loadSession(durable)
                val previous = durable.decisions().forRef(entry.txn.ref)
                    .firstOrNull { it.kind == DecisionKind.CATEGORY }
                val now = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L)
                // Filing a leg under a real category is her saying the detector was wrong, so the
                // pair dies. Filing it under انتقال is her saying it was right — rejecting then
                // would throw the other leg back into income, which is the bug this guards.
                if (entry.transfer && categoryId != CAT_TRANSFER) {
                    derived.links().touching(entry.txn.ref)
                        .filter { it.kind == LinkKind.TRANSFER && it.auto }
                        .forEach { link ->
                            durable.linkDecisions().put(
                                linkDecision(
                                    link.aRef,
                                    link.bRef,
                                    LinkKind.TRANSFER,
                                    Verdict.REJECTED,
                                    now,
                                )
                            )
                        }
                }
                durable.decisions().put(
                    TxnDecision(
                        id = uuid7(now),
                        ref = entry.txn.ref,
                        kind = DecisionKind.CATEGORY,
                        value = categoryId,
                        createdAt = now,
                        updatedAt = now,
                        memberId = session?.member.orEmpty(),
                        familyRef = entry.txn.familyRef.ifBlank {
                            session?.let { familyTxnId(it.member, entry.txn.ref) }.orEmpty()
                        },
                    )
                )
                if (always) {
                    val addrKey = durable.smsSource().addrKeyOf(entry.txn.srcHash).orEmpty()
                    durable.rules().put(ruleFrom(entry.txn, categoryId, addrKey, now))
                }
                derive(durable, derived, extraLookup(store.extraBankNumbers))
                publishLedger(durable, derived)
                requestFamilySync(silent = true)
            }.onFailure { android.util.Log.w("muchtoman", "categorise failed: $it") }
        }
    }

    /**
     * File the whole backlog at once — see [autoFilePlan].
     *
     * Every row gets an ordinary pinned decision, so any one of them can be refiled by hand
     * later exactly as if she had answered its card herself. One derive at the end applies them
     * together: fifty-three decisions and one rebuild, not fifty-three rebuilds.
     */
    fun categoriseAll(assignments: List<Pair<LedgerEntry, String>>) {
        if (assignments.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val derived = DerivedDb.get(app)
            runCatching {
                val session = loadSession(durable)
                durable.withTransaction {
                    val existing = durable.decisions().ofKind(DecisionKind.CATEGORY)
                        .associateBy { it.ref }
                    var stamp = System.currentTimeMillis()
                    val rows = assignments.map { (entry, categoryId) ->
                        val previous = existing[entry.txn.ref]
                        stamp = maxOf(stamp + 1, (previous?.updatedAt ?: 0L) + 1)
                        TxnDecision(
                            id = previous?.id ?: uuid7(stamp),
                            ref = entry.txn.ref,
                            kind = DecisionKind.CATEGORY,
                            value = categoryId,
                            createdAt = previous?.createdAt ?: stamp,
                            updatedAt = stamp,
                            memberId = session?.member.orEmpty(),
                            familyRef = entry.txn.familyRef.ifBlank {
                                session?.let { familyTxnId(it.member, entry.txn.ref) }.orEmpty()
                            },
                        )
                    }
                    durable.decisions().putAll(rows)
                }
                derive(durable, derived, extraLookup(store.extraBankNumbers))
                publishLedger(durable, derived)
                requestFamilySync(silent = true)
            }.onFailure { android.util.Log.w("muchtoman", "categoriseAll failed: $it") }
        }
    }

    /**
     * Her note on one transaction — the one field that is words rather than an answer. Blank
     * takes it back. A [DecisionKind.NOTE] decision like any other, so it survives a re-derive
     * and replays onto a rebuilt ledger the way a category does.
     */
    fun setNote(entry: LedgerEntry, text: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            runCatching {
                val clean = text.trim().take(200)
                val previous = durable.decisions().forRef(entry.txn.ref)
                    .firstOrNull { it.kind == DecisionKind.NOTE }
                if (previous == null && clean.isEmpty()) return@runCatching
                if (previous != null && previous.value.orEmpty() == clean) return@runCatching
                val now = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L)
                val session = loadSession(durable)
                durable.decisions().put(
                    TxnDecision(
                        id = previous?.id ?: uuid7(now),
                        ref = entry.txn.ref,
                        kind = DecisionKind.NOTE,
                        value = clean,
                        createdAt = previous?.createdAt ?: now,
                        updatedAt = now,
                        deleted = clean.isEmpty(),
                        memberId = session?.member.orEmpty(),
                        familyRef = entry.txn.familyRef,
                    )
                )
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "setNote failed: $it") }
        }
    }

    /**
     * A transaction she typed in herself, in the same shape the iPhone writes one.
     *
     * The row and its answers land together: the category is an ordinary pinned decision on
     * `m:<id>` — the same mechanism a filed message uses, which is what lets her refile it later
     * — and the note rides the same rail as a note on any other row.
     */
    fun addManualTxn(signedRial: Long, categoryId: String?, merchant: String, note: String, at: Long) {
        if (signedRial == 0L) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val derived = DerivedDb.get(app)
            runCatching {
                val now = System.currentTimeMillis()
                val id = uuid7(now)
                val session = loadSession(durable)
                durable.withTransaction {
                    durable.manual().put(
                        ManualTxn(
                            id = id,
                            at = at,
                            day = tehranDay(at),
                            amountRial = signedRial,
                            categoryId = categoryId,
                            merchant = merchant.trim().take(60),
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                    if (categoryId != null) {
                        durable.decisions().put(
                            TxnDecision(
                                id = uuid7(now + 1),
                                ref = manualRef(id),
                                kind = DecisionKind.CATEGORY,
                                value = categoryId,
                                createdAt = now,
                                updatedAt = now,
                                memberId = session?.member.orEmpty(),
                                familyRef = session?.let { familyTxnId(it.member, manualRef(id)) }.orEmpty(),
                            )
                        )
                    }
                    val cleanNote = note.trim().take(200)
                    if (cleanNote.isNotEmpty()) {
                        durable.decisions().put(
                            TxnDecision(
                                id = uuid7(now + 2),
                                ref = manualRef(id),
                                kind = DecisionKind.NOTE,
                                value = cleanNote,
                                createdAt = now,
                                updatedAt = now,
                                memberId = session?.member.orEmpty(),
                            )
                        )
                    }
                }
                derive(durable, derived, extraLookup(store.extraBankNumbers))
                publishLedger(durable, derived)
                requestFamilySync(silent = true)
            }.onFailure { android.util.Log.w("muchtoman", "addManualTxn failed: $it") }
        }
    }

    /**
     * Takes back a row she typed in — tombstoned, never erased, so the other phones in the
     * household learn it is gone. Only ever offered on manual rows: a message is evidence, and
     * evidence is not deletable.
     */
    fun deleteManualTxn(entry: LedgerEntry) {
        if (entry.txn.sourceKind != "manual" || !entry.txn.ref.startsWith("m:")) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val derived = DerivedDb.get(app)
            runCatching {
                val id = entry.txn.ref.removePrefix("m:")
                val row = durable.manual().all().firstOrNull { it.id == id } ?: return@runCatching
                durable.manual().put(
                    row.copy(deleted = true, updatedAt = maxOf(System.currentTimeMillis(), row.updatedAt + 1))
                )
                derive(durable, derived, extraLookup(store.extraBankNumbers))
                publishLedger(durable, derived)
                requestFamilySync(silent = true)
            }.onFailure { android.util.Log.w("muchtoman", "deleteManualTxn failed: $it") }
        }
    }

     * The tombstone [deleteManualTxn] writes, written the other way: the row comes back with a
     * newer stamp, so the other phones learn it is back the same way they learnt it was gone.
     * Unreferenced for now: the UI wave wires the undo affordance to it.
     */
    fun restoreManualTxn(ref: String) {
        if (!ref.startsWith("m:")) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val derived = DerivedDb.get(app)
            runCatching {
                // The DAO deliberately hides tombstones — manual().all() filters deleted rows,
                // and its file is owned elsewhere this wave — so the flip goes through the
                // openHelper. Safe to bypass Room's invalidation tracker: nothing observes this
                // table (every reader is a one-shot suspend query), and the derive below is the
                // re-read. MAX keeps the stamp monotonic, exactly as the delete's maxOf does.
                durable.openHelper.writableDatabase.execSQL(
                    "UPDATE manual_txn SET deleted = 0, updated_at = MAX(?, updated_at + 1) " +
                        "WHERE id = ? AND deleted = 1",
                    arrayOf<Any>(System.currentTimeMillis(), ref.removePrefix("m:")),
    /** Which categories دخل و خرج leaves out. A way of reading the report, never a fact about money. */
    fun setReportExcluded(ids: Set<String>) {
        store.reportExcluded = ids
        _state.update { it.copy(reportExcluded = ids) }
    }

    /**
     * Everything rebuildable, dropped and rebuilt: the cached rate and بورس snapshots, the coin
     * -icon cache, and `derived.db` — which is a cache by design, so «clear» for it simply means
     * the same total re-derive a parser fix runs. Nothing of hers is touched: messages,
     * decisions, holdings, history and every setting stay exactly put, and fresh fetches start
     * on the spot so the screens refill rather than sit empty.
     */
    fun clearCaches() {
        val app = getApplication<Application>()
        store.cachedRates = Rates()
        store.cachedStocks = TseSnapshot()
        _state.update { it.copy(rates = Rates(), tse = TseSnapshot()) }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                coil3.SingletonImageLoader.get(app).apply {
                    diskCache?.clear()
                    memoryCache?.clear()
                }
                val durable = DurableDb.get(app)
                val derived = DerivedDb.get(app)
                derive(durable, derived, extraLookup(store.extraBankNumbers))
                publishLedger(durable, derived)
            }.onFailure { android.util.Log.w("muchtoman", "clearCaches failed: $it") }
        }
        refreshAll()
    }

    // ——— Backup and restore: the recovery path for a phone that no longer exists ———

    private val _backup = MutableStateFlow(BackupUi())

    /** Its own flow, not [UiState]: only the two settings rows read it. */
    val backup: StateFlow<BackupUi> = _backup.asStateFlow()

    /** The decrypted backup between «رمز درسته» and the armed confirm. Never touches disk. */
    private var pendingRestore: BackupPayload? = null

    /**
     * Everything into the file she just picked. The payload is gathered under [ledgerGate] so the
     * prefs slice and the database bytes describe one moment; the slow parts — 600k rounds of the
     * KDF and the stream write — run outside it, where they block nobody.
     */
    fun exportBackup(uri: Uri, passphrase: String) {
        if (_backup.value.working) return
        _backup.update { it.copy(working = true, notice = null, failed = false) }
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val durable = DurableDb.get(app)
                val payload = ledgerGate.withLock {
                    BackupPayload(
                        prefs = exportablePrefs(app),
                        durableDbB64 = Base64.encode(backupDurableDbBytes(app, durable)),
                    )
                }
                val sealed = sealBackup(
                    payload,
                    passphrase,
                    createdAt = System.currentTimeMillis(),
                    appVersionCode = BuildConfig.VERSION_CODE,
                )
                // "wt" truncates a file she chose to overwrite; a provider that cannot do that
                // gets a plain write, and the runCatching owns whatever it thinks of it.
                val stream = runCatching { app.contentResolver.openOutputStream(uri, "wt") }
                    .getOrNull() ?: app.contentResolver.openOutputStream(uri)
                (stream ?: error("no stream for $uri")).use { it.write(sealed) }
            }.onSuccess {
                _backup.update {
                    it.copy(working = false, notice = "پشتیبان ساخته شد. فایل و رمزش رو جای امن نگه دار.")
                }
            }.onFailure { e ->
                android.util.Log.w("muchtoman", "backup export failed: $e")
                _backup.update {
                    it.copy(working = false, failed = true, notice = "پشتیبان ساخته نشد. دوباره امتحان کن.")
                }
            }
        }
    }

    /**
     * Decrypt and judge the picked file — nothing on the phone changes here. A payload that
     * passes waits in [pendingRestore] for the armed confirm; every refusal is words, and the
     * wrong-passphrase words deliberately cannot tell a bad passphrase from a damaged file,
     * because GCM cannot either.
     */
    fun readBackupFile(uri: Uri, passphrase: String) {
        if (_backup.value.working) return
        _backup.update { it.copy(working = true, notice = null, failed = false, ready = false) }
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytesLimited() }
                    ?: error("no stream for $uri")
                openBackup(bytes, passphrase)
            }.onSuccess { opened ->
                pendingRestore = opened.payload
                val day = opened.header.createdAt.takeIf { it > 0 }?.let { faDate(tehranDay(it)) }
                _backup.update {
                    it.copy(
                        working = false,
                        ready = true,
                        readyWords = if (day == null) "فایل پشتیبان" else "پشتیبانِ $day",
                    )
                }
            }.onFailure { e ->
                pendingRestore = null
                android.util.Log.w("muchtoman", "backup open failed: ${(e as? BackupException)?.fault ?: e}")
                val words = when ((e as? BackupException)?.fault) {
                    BackupFault.NOT_A_BACKUP -> "این فایل پشتیبانِ چقدر تومن نیست."
                    BackupFault.NEWER_FORMAT ->
                        "این پشتیبان با نسخهٔ جدیدتر برنامه ساخته شده. اول برنامه رو به‌روز کن."
                    BackupFault.WRONG_PASSPHRASE_OR_CORRUPT -> "رمز اشتباهه یا فایل خرابه."
                    null -> "فایل خونده نشد. دوباره امتحان کن."
                }
                _backup.update { it.copy(working = false, failed = true, notice = words) }
            }
        }
    }

    /**
     * The destructive step, behind the sheet's armed two-tap. Nothing is swapped here either:
     * the payload is staged beside the database and [DurableDb.completePendingRestore] applies
     * it at the next launch, before Room opens — see [stageRestore] for why in-place was refused.
     * Room then runs its migrations over the restored file if the backup is from an older build.
     */
    fun confirmRestore() {
        val payload = pendingRestore ?: return
        if (_backup.value.working) return
        _backup.update { it.copy(working = true) }
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                stageRestore(app, Base64.decode(payload.durableDbB64), encodeBackupPrefs(payload.prefs))
            }.onSuccess {
                pendingRestore = null
                _backup.update { BackupUi(restartNeeded = true) }
            }.onFailure { e ->
                android.util.Log.w("muchtoman", "backup staging failed: $e")
                _backup.update {
                    it.copy(working = false, failed = true, notice = "بازگردانی نشد. دوباره امتحان کن.")
                }
            }
        }
    }

    /** She closed the sheet. The decrypted payload goes with it; the file stays hers to re-pick. */
    fun dismissRestore() {
        pendingRestore = null
        _backup.update { it.copy(ready = false, readyWords = "", notice = null, failed = false) }
    }
    /** Where the household's ledger syncs. Same host as the PWA it serves, so one origin. */
    private val syncBase = BuildConfig.SYNC_URL

    private suspend fun refreshFamily(session: SyncSession?, note: String? = null, error: String? = null) {
        val durable = DurableDb.get(getApplication())
        val own = session?.let { active ->
            durable.familyMembers().get(active.member) ?: FamilyMember(
                id = active.member,
                name = store.name.trim().take(32).ifBlank { "من" },
                sharesSms = durable.meta().get(META_SYNC_SHARE_SMS).toBoolean(),
                updatedAt = System.currentTimeMillis(),
            ).also { durable.familyMembers().put(it) }
        }
        val members = if (session == null) emptyList() else durable.familyMembers().all()
        _state.update {
            it.copy(
                family = it.family.copy(
                    paired = session != null,
                    pendingPairing = if (session != null) null else it.family.pendingPairing,
                    memberId = session?.member.orEmpty(),
                    memberName = own?.name.orEmpty(),
                    members = members,
                    sharesSms = own?.sharesSms ?: false,
                    lastSync = note ?: it.family.lastSync,
                    error = error,
                    working = false,
                ),
            )
        }
    }

    /** Create a family with this phone's owner as its first member. */
    fun startFamily(name: String) {
        if (name.isBlank()) return
        val app = getApplication<Application>()
        _state.update { it.copy(family = it.family.copy(working = true, error = null)) }
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            runCatching { loadSession(durable) ?: claimHousehold(syncBase, durable, name) }
                .onSuccess {
                    refreshFamily(it, note = "خانواده ساخته شد.")
                    requestFamilySync(silent = true)
                }
                .onFailure {
                    android.util.Log.w("muchtoman", "claim failed: $it")
                    refreshFamily(null, error = "اتصال نشد. بعداً دوباره امتحان کن.")
                }
        }
    }

    /** Called from the Android deep link opened by a scanned family invitation. */
    fun acceptPairing(link: String?) {
        if (link.isNullOrBlank()) return
        if (_state.value.family.paired) {
            _state.update {
                it.copy(family = it.family.copy(error = "این گوشی از قبل عضو یک خانواده است."))
            }
            return
        }
        if (parsePairingLink(link) == null) {
            _state.update { it.copy(family = it.family.copy(error = "کد خانواده معتبر نیست.")) }
            return
        }
        _state.update {
            it.copy(
                family = it.family.copy(
                    pendingPairing = link,
                    error = null,
                    pairingUrl = null,
                )
            )
        }
    }

    fun joinFamily(name: String) {
        val link = _state.value.family.pendingPairing ?: return
        if (name.isBlank()) return
        val app = getApplication<Application>()
        _state.update { it.copy(family = it.family.copy(working = true, error = null)) }
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            loadSession(durable)?.let { existing ->
                refreshFamily(existing, error = "این گوشی از قبل عضو یک خانواده است.")
                return@launch
            }
            runCatching { joinHousehold(link, durable, name) }
                .onSuccess { session ->
                    _state.update { it.copy(family = it.family.copy(pendingPairing = null)) }
                    refreshFamily(session, note = "به خانواده پیوستی.")
                    requestFamilySync(silent = true)
                }
                .onFailure {
                    android.util.Log.w("muchtoman", "pair failed: $it")
                    _state.update {
                        it.copy(family = it.family.copy(working = false, error = "کد منقضی شده یا قبلاً استفاده شده."))
                    }
                }
        }
    }

    fun setFamilyName(name: String) {
        val clean = name.filterNot(Char::isISOControl).trim().take(32)
        if (clean.isBlank()) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val session = loadSession(durable) ?: return@launch
            val previous = durable.familyMembers().get(session.member)
            val now = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L)
            durable.familyMembers().put(
                (previous ?: FamilyMember(session.member, clean, updatedAt = now))
                    .copy(name = clean, updatedAt = now)
            )
            refreshFamily(session)
            requestFamilySync(silent = true)
        }
    }

    fun setFamilySmsSharing(enabled: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val session = loadSession(durable) ?: return@launch
            val previous = durable.familyMembers().get(session.member)
                ?: FamilyMember(session.member, "من", updatedAt = 0L)
            val now = maxOf(System.currentTimeMillis(), previous.updatedAt + 1L)
            durable.withTransaction {
                durable.meta().put(DurableMeta(META_SYNC_SHARE_SMS, enabled.toString()))
                durable.familyMembers().put(previous.copy(sharesSms = enabled, updatedAt = now))
            }
            refreshFamily(session)
            requestFamilySync(silent = false)
        }
    }

    /** A one-time code, shown as a QR. Ten minutes, one use. */
    fun inviteDevice() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val session = loadSession(durable) ?: return@launch refreshFamily(null)
            runCatching { pairingUrl(session, invite(session, durable)) }
                .onSuccess { url -> _state.update { it.copy(family = it.family.copy(paired = true, pairingUrl = url, error = null)) } }
                .onFailure {
                    android.util.Log.w("muchtoman", "invite failed: $it")
                    refreshFamily(session, error = "کد ساخته نشد. اینترنتت رو چک کن.")
                }
        }
    }

    fun syncFamily() = requestFamilySync(silent = false)

    private fun requestFamilySync(silent: Boolean) {
        if (familySyncJob?.isActive == true) return
        val app = getApplication<Application>()
        if (!silent) _state.update { it.copy(family = it.family.copy(working = true, error = null)) }
        familySyncJob = viewModelScope.launch(Dispatchers.Default) {
            ledgerJob?.join()
            val durable = DurableDb.get(app)
            val derived = DerivedDb.get(app)
            val session = loadSession(durable) ?: return@launch if (!silent) refreshFamily(null) else Unit
            runCatching { syncNow(durable, derived, session) }
                .onSuccess { result ->
                    if (result.received > 0) derive(durable, derived, extraLookup(store.extraBankNumbers))
                    publishLedger(durable, derived)
                    refreshFamily(
                        session,
                        // faNumber, not the Int: interpolated straight in, these were the only
                        // Latin digits anywhere in the app.
                        note = "${faNumber(result.sent.toDouble())} مورد فرستادیم، " +
                            "${faNumber(result.received.toDouble())} مورد گرفتیم.",
                    )
                }
                .onFailure {
                    android.util.Log.w("muchtoman", "sync failed: $it")
                    if (silent) refreshFamily(session)
                    else refreshFamily(session, error = "اتصال نشد. تغییرات روی گوشی محفوظ موند.")
                }
        }
    }

    /**
     * A cap on one category, per week, month or فصل — see `Budget.kt`.
     *
     * `startsOn` is the day she set it and is not what the figure is measured from: the window is
     * the whole week, month or فصل, because a budget that ignored the days before she decided would
     * disagree with «دسته‌ها» in دخل و خرج about what «رستوران و کافه» cost this month. What
     * `startsOn` buys is [BudgetProgress.partWindow] — the card's own sentence saying exactly that.
     *
     * `nameFa` is the category's name at this moment, snapshotted for the same reason a filed
     * transaction keeps the name it was filed under: the row must still be readable when the
     * category behind it is archived.
     */
    fun addBudget(category: Category, period: BudgetPeriod, capRial: Long) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val now = System.currentTimeMillis()
            runCatching {
                durable.goals().put(
                    Goal(
                        id = uuid7(now),
                        nameFa = category.nameFa.take(40),
                        targetRial = capRial,
                        kind = GoalKind.CAP,
                        categoryId = category.id,
                        period = period.id,
                        startsOn = tehranDay(now),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "addBudget failed: $it") }
        }
    }

    /**
     * A savings goal, with a deadline that makes it something she can pace.
     *
     * `startsOn` is the first of the current Jalali month rather than today, which is deliberate and
     * is the one thing about a goal that could look invented: what she has already kept this month
     * counts towards it. The card states the date it counts from, so the figure can be checked
     * rather than taken on faith.
     */
    fun addGoal(name: String, targetRial: Long, horizon: GoalHorizon) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val now = System.currentTimeMillis()
            val today = tehranDay(now)
            runCatching {
                durable.goals().put(
                    Goal(
                        id = uuid7(now),
                        nameFa = name.take(40),
                        targetRial = targetRial,
                        kind = GoalKind.SAVE,
                        period = GoalPeriod.ONCE,
                        startsOn = jalaliMonthStart(today),
                        endsOn = horizon.endsOn(today),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "addGoal failed: $it") }
        }
    }

    /**
     * Changes the ceiling or the rhythm of a cap she already keeps. The category stays: a cap on
     * a different category is a different budget, made by deleting this one.
     *
     * What was already said about the old cap is forgotten on purpose. Crossing 80% of a figure
     * she chose five minutes ago is news about that figure, and the high-water mark for the old
     * one would silence it — so the marks are dropped and [publishLedger]'s own announce treats
     * the edited budget exactly as it would one created at this spend: each threshold speaks
     * once, against the cap she actually keeps now.
     */
    fun editBudget(id: String, period: BudgetPeriod, capRial: Long) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val now = System.currentTimeMillis()
            runCatching {
                val goal = durable.goals().byId(id) ?: return@runCatching
                // Saved untouched is not an edit: a re-put would only churn updatedAt, and the
                // dropped marks would say the same warning a second time about nothing new.
                if (goal.targetRial == capRial && goal.period == period.id) return@runCatching
                durable.goals().put(goal.copy(targetRial = capRial, period = period.id, updatedAt = now))
                // The marks list is get-then-set on prefs and [announceBudgets] does the same
                // under [ledgerGate] — so this write takes the gate too, and releases it before
                // publishLedger takes it again: the Mutex is not reentrant.
                ledgerGate.withLock {
                    store.budgetMarks = store.budgetMarks.filterNot { it.goalId == id }
                }
                clearBudgetNote(app, id)
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "editBudget failed: $it") }
        }
    }

    /**
     * Renames, resizes or re-deadlines a savings goal, without moving the day it counts from —
     * what she has kept since she set it stays counted, which is the whole reason to edit
     * rather than start over.
     *
     * [horizon] is null when she left «تا کِی؟» alone and the deadline she already has stands.
     * When she picks one it is measured from today, exactly as on a new goal: the pill says
     * «۶ ماه», and six months from some day she cannot see would be the sheet lying.
     */
    fun editGoal(id: String, name: String, targetRial: Long, horizon: GoalHorizon?) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val now = System.currentTimeMillis()
            runCatching {
                val goal = durable.goals().byId(id) ?: return@runCatching
                val next = goal.copy(
                    nameFa = name.take(40),
                    targetRial = targetRial,
                    endsOn = if (horizon != null) horizon.endsOn(tehranDay(now)) else goal.endsOn,
                )
                if (next == goal) return@runCatching
                durable.goals().put(next.copy(updatedAt = now))
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "editGoal failed: $it") }
        }
    }

    /** Deletes either shape — a budget and a goal are one table, and this is one tombstone. */
    fun deleteGoal(id: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            runCatching {
                durable.goals().delete(id, System.currentTimeMillis())
                // Before the publish, not after: [announceBudgets] can only retract a note for a
                // budget it can still see, and this one is about to stop existing. A warning about
                // a budget she has deleted is the app talking about nothing.
                clearBudgetNote(app, id)
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "deleteGoal failed: $it") }
        }
    }

    /**
     * Which screen a tapped notification was about, held until the UI has moved there.
     *
     * The same one-shot shape [FamilyState.pendingPairing] uses: the intent's extra becomes a piece
     * of state, the composition acts on it and then clears it — so a rotation does not send her back
     * to the budget tab, and neither does the activity being recreated at sunset when the theme
     * follows the system.
     */
    fun openTab(name: String?) {
        val tab = name?.let { value -> tabs.firstOrNull { it.name == value } } ?: return
        _state.update { it.copy(openTab = tab) }
    }

    fun consumeOpenTab() = _state.update { it.copy(openTab = null) }

    /**
     * The same one-shot, for the note that asks rather than reports — see [EXTRA_OPEN_DECK].
     *
     * Guarded on the edition as [openTab] is guarded by [tabs]: the lite build has no دفتر and no
     * deck, and while nothing there can post the note, an intent can be sent to any exported
     * activity by anyone.
     */
    fun openDeck(wanted: Boolean) {
        if (!wanted || Tab.LEDGER !in tabs) return
        _state.update { it.copy(openDeck = true) }
    }

    fun consumeOpenDeck() = _state.update { it.copy(openDeck = false) }

    /**
     * Fill the dev build with a household's year, or take it back out again.
     *
     * Guarded here as well as in تنظیمات, because this is the method that writes the rows and a
     * guard on the button that calls it is a guard on one caller. `BuildConfig.DEMO` is a compile
     * -time constant, so in every other build this is `if (false)` and R8 removes the body along
     * with [demoLedger] itself.
     *
     * Deliberately not wired to family sync: nothing invented here should reach another phone,
     * and the sync that would carry it is only ever started by something she did.
     */
    fun setDemoData(on: Boolean) {
        if (!BuildConfig.DEMO) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val derived = DerivedDb.get(app)
            val now = System.currentTimeMillis()
            runCatching {
                // Cleared first either way, so «ساختن» twice is one demo ledger rather than two
                // laid on top of each other.
                durable.manual().deleteWithIdPrefix(DEMO_PREFIX)
                durable.decisions().deleteForRefPrefix(manualRef(DEMO_PREFIX))
                if (on) {
                    val ledger = demoLedger(tehranDay(now), now)
                    durable.manual().putAll(ledger.transactions)
                    durable.decisions().putAll(ledger.filings)
                }
                // The same total rebuild a parser fix runs, which is the point: the invented rows
                // go through the reading, the linking and the filing that every other row does.
                derive(durable, derived, extraLookup(store.extraBankNumbers))

                // Balances and holdings are the store's, not the ledger's, and each is marked so
                // that clearing takes back exactly what was put in. Anything she added by hand on
                // this build stays where it is.
                val banks = store.bankAccounts.filterNot { it.sender == DEMO_PREFIX } +
                    if (on) demoBankAccounts(now) else emptyList()
                val holdings = store.holdings.filterNot { it.id.startsWith(DEMO_PREFIX) } +
                    if (on) demoHoldings() else emptyList()
                store.bankAccounts = banks
                store.holdings = holdings
                _state.update { it.copy(bankAccounts = store.bankAccounts, holdings = holdings) }
                publishLedger(durable, derived)
                // A year of daily totals, ending at what the holdings just written are actually
                // worth — so گزارش دارایی has its «۶ ماه» and «۱ سال» too, and its newest point is
                // the one the app would have recorded on its own. Cleared outright when the demo
                // is: the chart is a record of days, and days it invented are not worth keeping.
                val history = if (on) demoHistory(now, _state.value.totals.toman) else emptyMap()
                store.history = history
                _state.update { it.copy(history = history) }
                recordSnapshot()
            }.onFailure { android.util.Log.w("muchtoman", "demo data failed: $it") }
        }
    }

    /** Her verdict on one purchase. No rule follows from it — it is an opinion, not a pattern. */
    fun answerWorthIt(entry: LedgerEntry, answer: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val durable = DurableDb.get(app)
            val now = System.currentTimeMillis()
            runCatching {
                durable.decisions().put(
                    TxnDecision(uuid7(now), entry.txn.ref, DecisionKind.WORTH_IT, answer, now, now)
                )
                publishLedger(durable, DerivedDb.get(app))
            }.onFailure { android.util.Log.w("muchtoman", "worthIt failed: $it") }
        }
    }

    /** The message a transaction was read from, so every figure can be traced back to its source. */
    suspend fun sourceOf(entry: LedgerEntry): String =
        runCatching {
            DurableDb.get(getApplication()).smsSource().bodyOf(entry.txn.srcHash).orEmpty()
        }.getOrDefault("")

    /** The scan itself. Separate so [restartScan] can run it in the coroutine it already owns. */
    private suspend fun runScan(app: Application) {
        val extra = extraLookup(store.extraBankNumbers)

        val messages = readSmsInbox(app, store.smsScannedTo)
        if (messages.isEmpty()) return

        // Re-keyed through senderKey on the way in: the key function has changed once
        // already (whitespace collapse), and a dismissal stored under an old key must
        // stay dismissed. senderKey is a fixpoint over its own output.
        val dismissed = store.dismissedSenders.map(::senderKey).toSet()
        var accounts = store.bankAccounts
        val seen = store.seenSms
        val fresh = mutableListOf<String>()
        val strangers = store.strangeSenders.toMutableList()
        for (m in messages) {
            val key = smsKey(m.from, m.body, m.at)
            if (key in seen || legacySmsKey(m.body, m.at) in seen || key in fresh) continue
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
            // Through the plausibility gate, not applyBankSms directly: a garbled twenty-one
            // -digit figure from a matched sender must contribute nothing — see [foldBankSms].
            accounts = foldBankSms(accounts, parsed)
            fresh += key
        }

        if (!store.smsEnabled || !canReadSms(app)) return

        store.bankAccounts = accounts
        store.seenSms = rememberSeen(seen, fresh)
        // A stranger whose number has since been added resolves and drops off the list, as
        // does one she dismissed. Newest first, so the message she is asking about — which
        // is almost always the latest — sits at the top instead of behind old promos.
        val currentDismissed = store.dismissedSenders.map(::senderKey).toSet()
        store.strangeSenders = strangers
            .filter { bankOf(it.sender, extra) == null && senderKey(it.sender) !in currentDismissed }
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

    /**
     * Forget every balance and read the inbox again from the beginning.
     *
     * Messages are read once and then skipped for ever, by a watermark and a seen-list — which
     * is right until the reading itself changes, and then every balance is frozen at what an
     * older parser made of it with no way back. An upgrade clears it automatically; this is the
     * same thing on demand, so a figure that has gone wrong is never a dead end.
     */
    fun rescanSms() = restartScan {
        store.bankAccounts = emptyList()
        store.seenSms = emptySet()
        store.smsScannedTo = 0L
        store.strangeSenders = emptyList()
        _state.update { it.copy(bankAccounts = emptyList(), strangeSenders = emptyList()) }
    }

            // Joined first, and rewound under the gate, so neither a pipeline mid-ingest nor the
            // watch worker can write its watermark over the rewind.
            ledgerJob?.join()
            runCatching { ledgerGate.withLock { rewindIngest(DurableDb.get(app)) } }
                .onFailure { android.util.Log.w("muchtoman", "rewindIngest failed: $it") }
    /**
     * Change what the next scan would read, then read it — with the scan already in flight
     * stopped and waited for first.
     *
     * cancelAndJoin, not cancel: cancellation is cooperative and the parse loop never suspends,
     * so only joining actually guarantees that [prepare] is not overwritten by a scan that was
     * already most of the way through.
     *
     * The restart takes over [scanJob] straight away, before it suspends into that join. A job
     * reads as not-active the whole time it is cancelling, so leaving the old one there meant
     * any scanSms() arriving during the join — one foreground is enough — walked past the
     * one-at-a-time guard, started a scan with the old watermark and claimed the slot. Then
     * [prepare] ran, and our own scanSms() was the one turned away: the wipe landed with no
     * rescan behind it, and every balance she had anchored by hand was gone for good.
     *
     * NonCancellable around the join for the same reason one step up: the next restart cancels
     * this coroutine, and a join that gives up early lets the old scan wake and write over the
     * wipe — exactly what the join is here to prevent.
     */
    private fun restartScan(prepare: () -> Unit) {
        val previous = scanJob
        val app = getApplication<Application>()
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            prepare()
            // Straight into the scan rather than back through scanSms(), which would only
            // find this very coroutine holding the slot and decline.
            if (store.smsEnabled && canReadSms(app)) runScan(app)
        }
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
     * this phone only, and the inbox is read again from the start so the messages it already
     * sent finally count.
     *
     * Winding the watermark back, not [rescanSms]. Confirming a sender is one tap on a
     * suggestion card, and it used to forget every balance in the app — including the ones she
     * typed in herself. Those cannot be rebuilt: a bank whose messages never state a مانده
     * comes back as the sum of its transactions, unanchored, which is exactly the figure that
     * does not count towards the total. Her net worth would drop by whatever she had anchored,
     * silently, with nothing storing the number she had entered.
     *
     * Nothing needs forgetting here anyway. A message from an unknown sender is skipped before
     * it is ever recorded as seen, so the only thing hiding these particular messages is how
     * far the inbox has been read; everything already counted is still in seenSms and stays
     * skipped.
     */
    fun addBankNumber(bank: String, sender: String) {
        if (runCatching { Bank.valueOf(bank) }.getOrNull() == null || sender.isBlank()) return
        val cur = store.extraBankNumbers
        store.extraBankNumbers = cur + (bank to ((cur[bank] ?: emptyList()) + sender).distinct())
        restartScan { store.smsScannedTo = 0L }
    }

    /** Turning it off leaves the balances where they are; it stops them moving on their own. */
    /**
     * The first-run sheet has had its turn — answered or skipped, which are the same thing to it.
     *
     * Also the moment the watch is first scheduled: she may have just granted the messages, and
     * without this the schedule would wait for the next thing that happens to call [publishLedger].
     */
    fun finishOnboarding() {
        store.onboarded = true
        _state.update { it.copy(onboarded = true) }
        scheduleLedgerWatch(getApplication(), store.smsEnabled)
        // Whatever she granted takes effect against the inbox now rather than at the next open.
        scanSms()
        runLedger()
    }

    fun setSmsEnabled(on: Boolean) {
        store.smsEnabled = on
        _state.update { it.copy(smsEnabled = on) }
        // The switch is one of the two things the watch is scheduled by, and turning it off is the
        // only path that can take the last reason to watch away without touching a budget.
        scheduleLedgerWatch(
            getApplication<Application>(),
            on || _state.value.ledger.budgets.isNotEmpty(),
        )
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
        restartScan {
            val next = anchorAccount(store.bankAccounts, key, balance, System.currentTimeMillis())
            store.bankAccounts = next
            _state.update { it.copy(bankAccounts = next) }
            recordSnapshot()
        }
    }

    /**
     * Drops an account that was read wrong. The messages behind it stay marked as seen on
     * purpose: re-reading them would rebuild the same wrong figure. It starts again, from
     * zero and unanchored, at the next message that bank sends.
     */
    fun forgetBankAccount(key: String) {
        restartScan {
            val next = store.bankAccounts.filterNot { it.key == key }
            store.bankAccounts = next
            _state.update { it.copy(bankAccounts = next) }
            recordSnapshot()
        }
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
    val stocksLoading: Boolean = false,
    val error: String? = null,
    val lockEnabled: Boolean = false,
    val widgetLock: Boolean = false,
    val locked: Boolean = false,
    val name: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val history: Map<Long, Double> = emptyMap(),
    /** False only on a phone that has never been past the first-run sheet. See [Store.onboarded]. */
    val onboarded: Boolean = true,
    val smsEnabled: Boolean = false,
    val bankAccounts: List<BankAccount> = emptyList(),
    val disabledBanks: Set<String> = emptySet(),
    val strangeSenders: List<StrangeSender> = emptyList(),
    val refreshingWallets: Set<String> = emptySet(),
    val walletErrors: Map<String, String> = emptyMap(),
    val dismissedUpdate: String = "",
    /** Category ids دخل و خرج leaves out — her reading preference, mirrored from [Store]. */
    val reportExcluded: Set<String> = emptySet(),
    val tse: TseSnapshot = TseSnapshot(),
    val ledger: LedgerView = LedgerView(),
    val family: FamilyState = FamilyState(),
    /** Where a tapped notification wants her, until the composition has taken her there. */
    val openTab: Tab? = null,
    /** Whether that notification wanted the review deck in particular. Same one-shot life. */
    val openDeck: Boolean = false,
) {
    val coins: List<Coin> get() = rates.coins

    /**
     * What the home screen says, worked out once per state rather than once per card.
     *
     * `by lazy` for the same reason `effective` is: the month walk and the ninety-day median
     * behind the runway are not free, and the summary reads them several times over.
     */
    val story: HomeStory by lazy {
        buildStory(
            ledger.entries,
            Math.round(bankToman * 10.0),
            tehranDay(System.currentTimeMillis()),
            ledger.budgets,
            // The same single gate دخل و خرج reads: the exclusion set governs, and قرض و همسر
            // are its shipped default rather than a mechanism of their own.
            countPassThrough = true,
            excluded = reportExcluded,
        )
    }
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
    /**
     * by lazy, not get(): building this copies the whole rate map three times over, and with
     * بورس loaded that map is the Worker's several hundred plus a couple of thousand نماد.
     * The screen reads it — directly, and through [totals] — the better part of ten times in
     * one composition pass, so as a get() a scrolling list rebuilt it on every frame and the
     * رمزارز rows stuttered. A UiState is immutable and copy() makes a new one, so lazy is
     * still exactly one build per state, and the value can never be stale.
     */
    val effective: Map<String, Double> by lazy { effectiveRates(rates, overrides, tse) }
    val refreshing: Boolean get() = loading || stocksLoading || refreshingWallets.isNotEmpty()

    /** The tracked balances as one figure, minus the banks she switched off. */
    val bankToman: Double get() = bankTotal(bankAccounts, disabledBanks)

    /** True while any tracked balance is a guess rather than something a bank stated. */
    val bankUnsure: Boolean
        get() = bankAccounts.any { it.bank !in disabledBanks && !it.trusted }

    /** See [com.doxigo.muchtoman.listHoldings] — shared with the widget and the daily worker. */
    val listHoldings: List<Holding> by lazy {
        listHoldings(holdings, smsEnabled, bankAccounts, disabledBanks)
    }

    /** Reads both of the above, so as a get() it paid for both of them again every time. */
    val totals: Totals by lazy { computeTotals(listHoldings, effective) }
}

/** What the backup rows in تنظیمات have to say. Everything user-visible in it is words. */
data class BackupUi(
    val working: Boolean = false,
    /** The one line under the rows — success and failure alike are said, never just implied. */
    val notice: String? = null,
    val failed: Boolean = false,
    /** A decrypted backup is in hand, waiting behind the armed confirm. */
    val ready: Boolean = false,
    /** Which backup, in words she can check against the file — «پشتیبانِ ۳ مرداد ۱۴۰۵». */
    val readyWords: String = "",
    /** Staged. Nothing changes until the app is closed and opened, and the line says so. */
    val restartNeeded: Boolean = false,
)

/** A backup is a few MB; a "backup" that will not fit in memory is an attack or a mistake. */
private const val MAX_BACKUP_FILE_BYTES = 256 * 1024 * 1024

private fun InputStream.readBytesLimited(maxBytes: Int = MAX_BACKUP_FILE_BYTES): ByteArray {
    val out = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) error("file too large")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

// FragmentActivity, not ComponentActivity: androidx.biometric's BiometricPrompt requires it.
class MainActivity : FragmentActivity() {
    private lateinit var appVm: AppVm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appVm = ViewModelProvider(this)[AppVm::class.java]
        appVm.acceptPairing(intent?.dataString)
        appVm.openTab(intent?.getStringExtra(EXTRA_OPEN_TAB))
        appVm.openDeck(intent?.getBooleanExtra(EXTRA_OPEN_DECK, false) == true)
        // A phone that granted READ_SMS before RECEIVE_SMS existed: she already said yes to the
        // messages conversation, this completes it so [SmsReceiver] can hear them land. Both
        // permissions share the SMS group, so the platform grants this without showing anything.
        // Asking "at launch" is exactly what the settings screen's rule forbids — for a *new*
        // conversation; this guard makes it unreachable except mid-upgrade, and asked at most
        // once: denied is a state the guard cannot tell from never-asked, but the system stops
        // re-showing a denied dialog on its own. The result is deliberately ignored — a denial
        // just leaves the six-hour sweep, which is what the phone was doing yesterday.
        if (Store(this).smsEnabled && canReadSms(this) && !canReceiveSms(this)) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 0)
        }
        enableEdgeToEdge()
        setContent {
            val state by appVm.state.collectAsStateWithLifecycle()

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

                    AppRoot(appVm, state, this@MainActivity)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appVm.acceptPairing(intent.dataString)
        // The usual case for either note: the app is already running, so the tap arrives here
        // rather than through onCreate. Missing this is the note that opens the app on whatever
        // tab she left it on, which reads as the notification having done nothing.
        appVm.openTab(intent.getStringExtra(EXTRA_OPEN_TAB))
        appVm.openDeck(intent.getBooleanExtra(EXTRA_OPEN_DECK, false))
    }
}
