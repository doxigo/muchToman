package com.doxigo.muchtoman

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

// FragmentActivity, not ComponentActivity: androidx.biometric's BiometricPrompt requires it.
class MainActivity : FragmentActivity() {
    private lateinit var appVm: AppVm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appVm = ViewModelProvider(this)[AppVm::class.java]
        // Only a fresh launch carries news. A restore after process death, or a relaunch from
        // recents, hands back the intent that first opened the task — the budget note she tapped
        // last week, or a pairing link already spent («این گوشی از قبل عضو یک خانواده است»).
        val fresh = savedInstanceState == null &&
            intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        if (fresh) {
            appVm.acceptPairing(intent?.dataString)
            appVm.openTab(intent?.getStringExtra(EXTRA_OPEN_TAB))
            appVm.openDeck(intent?.getBooleanExtra(EXTRA_OPEN_DECK, false) == true)
            // A launcher shortcut lands here even when the app is already open: the platform starts
            // every static shortcut with NEW_TASK | CLEAR_TASK, so the running task is cleared and
            // this activity opened fresh — a new AppVm, and with it the lock, if she has one on.
            appVm.openAddTxn(intent?.getBooleanExtra(EXTRA_ADD_TXN, false) == true)
        }
        // A phone that granted READ_SMS before RECEIVE_SMS existed: she already said yes to the
        // messages conversation, this completes it so [SmsReceiver] can hear them land. Both
        // permissions share the SMS group, so the platform grants this without showing anything.
        // Asking at launch, unexplained, is exactly what [OnboardingScreen] forbids — for a *new*
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

            MuchTomanTheme(mode = state.themeMode, textSize = state.textSize) {
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
        appVm.openAddTxn(intent.getBooleanExtra(EXTRA_ADD_TXN, false))
    }
}
