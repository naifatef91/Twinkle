package app.twinkle.update

/** What the Settings "app update" section shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** A newer version is on Google Play. [staleDays] is how long it has been available (null when unknown). */
    data class Available(val versionCode: Int, val staleDays: Int?) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    /** Downloaded in the background; installing restarts the app. */
    data object Downloaded : UpdateState
    /** Not installed from Google Play (APK from Drive, USB, a test build): Play cannot update it. */
    data object NotFromPlay : UpdateState
    data object Failed : UpdateState
}
