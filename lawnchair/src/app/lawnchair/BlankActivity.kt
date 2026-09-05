package app.lawnchair

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.bundleOf
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.unsafeLazy
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class BlankActivity : ComponentActivity() {

    private val resultReceiver by unsafeLazy { intent.getParcelableExtra<ResultReceiver>("callback")!! }
    private var resultSent = false
    private var firstResume = true
    private var targetStarted = false

    /**
     * Launcher for the target activity's result. Registered UNCONDITIONALLY in onCreate — that is the
     * only legal place, and it is also what makes the result survive a configuration-change recreate:
     * the Activity Result API re-delivers a pending result to a launcher registered under the same key
     * during onCreate, so a recreated instance still receives the REAL result code rather than having
     * to guess one.
     */
    private lateinit var targetResultLauncher: ActivityResultLauncher<Intent>

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Anchor: survive a CONFIGURATION-CHANGE RECREATE without re-launching the target.
        //
        // This activity declares no configChanges, so anything that rotates the display while the
        // target is up destroys and recreates it. Without this flag onCreate would call
        // startTargetActivity() a SECOND time, stacking a duplicate of the target activity on top of
        // the one already running. That is exactly what happened with the system live-wallpaper
        // confirm screen (com.android.wallpaper.livepicker), which is hardcoded portrait: opening it
        // from landscape forces a rotation, recreates this activity, and the user got a second confirm
        // prompt — the second one showing the PREVIOUS wallpaper, because the first had already
        // applied the new one.
        targetStarted = savedInstanceState?.getBoolean(KEY_TARGET_STARTED) == true
        // firstResume must survive the recreate too. It is what makes this activity finish when the
        // TARGET returns (the first resume is its own; a later one means the target is gone). Letting
        // it reset to true on a recreate would swallow that signal and leave this activity sitting
        // in front of the caller forever.
        if (savedInstanceState != null) firstResume = false
        // Registered here rather than inside startTargetActivity(): onCreate is the only legal point
        // to register, and registering on EVERY create (including a recreate) is what lets the Activity
        // Result API hand this instance the target's real result. Without it a recreated instance is
        // deaf to the outcome and would have to infer one — and inferring OK would turn a cancelled
        // confirm dialog into an applied wallpaper, which is exactly the bug the staged-write flow in
        // AnchorWallpaperPicker exists to prevent.
        targetResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // Forward the FULL result Intent (not just extras) so the data URI and clipData
                // survive — e.g. the photo picker returns its image URI in Intent.data, which would
                // otherwise be lost.
                val b = Bundle()
                b.putParcelable(KEY_RESULT_INTENT, it.data)
                resultReceiver.send(it.resultCode, b)
                resultSent = true
                finish()
            }
        if (!intent.hasExtra("dialogTitle")) {
            if (!targetStarted) startTargetActivity()
            return
        }
        setContent {
            LawnchairTheme {
                EdgeToEdge()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BottomSheetDefaults.ScrimColor,
                ) {
                    AlertDialog(
                        onDismissRequest = { if (!targetStarted) finish() },
                        confirmButton = {
                            Button(
                                onClick = { startTargetActivity() },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(text = intent.getStringExtra("positiveButton")!!)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = { finish() },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(text = stringResource(id = android.R.string.cancel))
                            }
                        },
                        title = {
                            Text(text = intent.getStringExtra("dialogTitle")!!)
                        },
                        text = {
                            Text(text = intent.getStringExtra("dialogMessage")!!)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (firstResume) {
            firstResume = false
            return
        }
        finish()
    }

    private fun startTargetActivity() {
        when {
            intent.hasExtra("intent") -> {
                if (intent.hasExtra("dialogTitle")) {
                    startActivity(intent.getParcelableExtra("intent"))
                } else {
                    targetResultLauncher.launch(requireNotNull(intent.getParcelableExtra("intent")))
                }
            }

            else -> {
                finish()
                return
            }
        }
        targetStarted = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_TARGET_STARTED, targetStarted)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Anchor: a recreate (see onCreate) destroys THIS instance while the target activity is still
        // running and the caller is still waiting. Sending RESULT_CANCELED here would resolve the
        // caller's suspended await with a spurious cancel, so the real result — delivered later to the
        // recreated instance — arrives with nobody listening. Only report a cancel when the target was
        // never started, or when this is a real finish rather than a configuration-change recreate.
        if (targetStarted && isChangingConfigurations) return
        if (!resultSent && intent.hasExtra("callback")) {
            resultSent = true
            // Reaching here means no real result ever arrived through targetResultLauncher. Report a
            // CANCEL and nothing else — never infer an OK. The two outcomes are not symmetric: a
            // spurious cancel costs the user one repeated pick, whereas a spurious OK makes the caller
            // commit a change the user may have explicitly declined (AnchorWallpaperPicker promotes the
            // staged wallpaper and flips wallpaperSource purely on this code).
            resultReceiver.send(RESULT_CANCELED, null)
        }
    }

    companion object {

        private const val KEY_RESULT_INTENT = "blank_activity_result_intent"
        private const val KEY_TARGET_STARTED = "blank_activity_target_started"

        suspend fun startBlankActivityDialog(
            activity: Activity,
            targetIntent: Intent,
            dialogTitle: String,
            dialogMessage: String,
            positiveButton: String,
        ) {
            start(
                activity,
                targetIntent,
                bundleOf(
                    "intent" to targetIntent,
                    "dialogTitle" to dialogTitle,
                    "dialogMessage" to dialogMessage,
                    "positiveButton" to positiveButton,
                ),
            )
        }

        suspend fun startBlankActivityForResult(activity: Activity, targetIntent: Intent): ActivityResult {
            return start(activity, targetIntent, Bundle.EMPTY)
        }

        private suspend fun start(activity: Activity, targetIntent: Intent, extras: Bundle): ActivityResult {
            return suspendCancellableCoroutine { continuation ->
                val intent = Intent(activity, BlankActivity::class.java)
                    .putExtras(extras)
                    .putExtra("intent", targetIntent)
                val resultReceiver = createResultReceiver {
                    if (continuation.isActive) {
                        continuation.resume(it)
                    }
                }
                activity.startActivity(intent.putExtra("callback", resultReceiver))
            }
        }

        private fun createResultReceiver(callback: (ActivityResult) -> Unit): ResultReceiver {
            return object : ResultReceiver(Handler(Looper.myLooper()!!)) {

                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    // Prefer the full forwarded Intent (carries data URI + extras); fall back to the
                    // legacy extras-only bundle for any path that still sends one.
                    val data = resultData?.getParcelable<Intent>(KEY_RESULT_INTENT)
                        ?: Intent().also { if (resultData != null) it.putExtras(resultData) }
                    callback(ActivityResult(resultCode, data))
                }
            }
        }
    }
}
