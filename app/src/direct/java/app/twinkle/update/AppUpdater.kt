package app.twinkle.update

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Direct-download build (APK shared outside Google Play): no Play libraries are linked, so nothing here talks
 * to Google Play. The Settings update card is hidden for this flavor ([app.twinkle.BuildConfig.PLAY_UPDATES]);
 * this stub only keeps the shared UI compiling. A newer APK is installed over this one by the user.
 */
object AppUpdater {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.NotFromPlay)
    val state: StateFlow<UpdateState> get() = _state

    fun check(ctx: Context) { _state.value = UpdateState.NotFromPlay }
    fun start(ctx: Context, launcher: ActivityResultLauncher<IntentSenderRequest>) {}
    fun install(ctx: Context) {}
    fun onFlowDeclined() {}
}
