package app.twinkle.web

import android.content.Context
import android.util.Log
import app.twinkle.data.PickedFile
import app.twinkle.data.Settings
import app.twinkle.net.WifiDirectHost
import app.twinkle.service.TransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The three separate ways a computer reaches PC mode. Each one brings up only its own link and the server
 * answers only on that link: a session over USB never creates a Wi-Fi Direct group, and so on.
 */
enum class PcMode(internal val trustedLinks: List<String>) {
    /** The phone hosts its own Wi-Fi Direct network; the computer joins it. No router needed. */
    WIFI(HttpServer.WIFI_DIRECT_IFACES),
    /** Any device on the phone's router (Wi-Fi or Ethernet), with a PIN. */
    LAN(emptyList()),
    /** USB tethering over a USB-C cable. */
    USB(UsbTether.PREFIXES),
}

/** Wi-Fi Direct network details, for [PcMode.WIFI]. */
data class WifiGroup(val ssid: String, val pass: String, val url: String, val band: String)

/** PC mode state exposed to the UI. */
sealed class PcState {
    data object Idle : PcState()
    data class Starting(val mode: PcMode) : PcState()
    data class Ready(
        val mode: PcMode,
        val stats: HttpServer.Stats,
        val sharedCount: Int,
        val uploadedCount: Int,
        val uploadedNames: List<String> = emptyList(),
        /** [PcMode.WIFI] only. */
        val wifi: WifiGroup? = null,
        /** [PcMode.USB]: where the cable route stands, with its link once tethering is on. */
        val usb: UsbTether.Status = UsbTether.Status(),
        /** [PcMode.LAN]: the phone's address on its router's network and the PIN. */
        val lan: LanStatus = LanStatus(),
    ) : PcState()
    data class Failed(val mode: PcMode, val message: String) : PcState()
}

/** Same-network route: [ip] is the phone's address on its router's network (null when it has none). */
data class LanStatus(val ip: String? = null, val open: Boolean = false, val pin: String? = null) {
    val url: String? get() = ip?.let { "http://$it:${HttpServer.PORT}" }
    /** For the QR code: the fragment never leaves the browser, the page reads the PIN from it and signs in. */
    val qrUrl: String? get() = url?.let { u -> pin?.let { "$u/#pin=$it" } ?: u }
}

/**
 * Orchestrates PC mode: brings up the link for the chosen [PcMode], then serves the browser UI over HTTP on
 * that link only. Independent from the phone-to-phone TransferController.
 */
object PcSession {
    private const val TAG = "TwinklePc"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow<PcState>(PcState.Idle)
    val state: StateFlow<PcState> get() = _state

    private val _shared = MutableStateFlow<List<PickedFile>>(emptyList())
    /** Files currently offered to the computer. */
    val shared: StateFlow<List<PickedFile>> get() = _shared

    /** Names the computer uploaded during this session (newest last). */
    private val _uploaded = MutableStateFlow<List<String>>(emptyList())
    val uploaded: StateFlow<List<String>> get() = _uploaded

    private var mode: PcMode? = null
    private var host: WifiDirectHost? = null
    private var server: HttpServer? = null
    private var statsJob: Job? = null
    private var group: WifiDirectHost.GroupInfo? = null
    @Volatile private var usbStatus: UsbTether.Status = UsbTether.Status()
    @Volatile private var lanStatus: LanStatus = LanStatus()

    /** Starts [mode]. No-op when that mode is already starting or ready; another running mode is stopped first. */
    fun start(context: Context, mode: PcMode) {
        val app = context.applicationContext
        // PC mode must outlive the screen: a foreground service keeps the process, the CPU and the Wi-Fi radio awake
        // while the phone is locked or the app is in the background. It stops itself when PC mode ends.
        runCatching { TransferService.start(app) }
        scope.launch {
            lock.withLock {
                val cur = _state.value
                if ((cur is PcState.Starting && cur.mode == mode) || (cur is PcState.Ready && cur.mode == mode)) return@withLock
                tearDown()
                this@PcSession.mode = mode
                _state.value = PcState.Starting(mode)
                _uploaded.value = emptyList()
                try {
                    if (mode == PcMode.WIFI) {
                        val prefs = Settings.get(app).state.value
                        val h = WifiDirectHost(app)
                        host = h
                        // 2.4GHz by default: every laptop can see it; 5GHz is opt-in from the screen.
                        group = h.start(
                            band = if (prefs.pc5GHz) WifiDirectHost.Band.PREFER_5GHZ else WifiDirectHost.Band.GHZ2,
                            suffix = prefs.pcSuffix, passphrase = prefs.pcPassword,
                        )
                    }
                    val s = HttpServer(
                        context = app,
                        filesProvider = { _shared.value },
                        onUploaded = { name, _ -> _uploaded.update { it + name } },
                        phoneName = { Settings.get(app).deviceName },
                        uploadedNames = { _uploaded.value },
                        onPinExhausted = { scope.launch { lock.withLock { if (lanStatus.open) openLanLocked() } } },
                        trustedLinks = mode.trustedLinks,
                    )
                    s.start()
                    server = s
                    when (mode) {
                        // Choosing "same network" is the owner's go-ahead: open it straight away, PIN required.
                        PcMode.LAN -> { lanStatus = LanStatus(ip = HttpServer.lanAddress()); openLanLocked() }
                        PcMode.USB -> usbStatus = UsbTether.status(app)
                        PcMode.WIFI -> Unit
                    }
                    publish()
                    statsJob = scope.launch {
                        // Re-publish Ready whenever stats, shared list or uploads change.
                        // Stats change on every 64 KiB served; publishing each one made the whole screen recompose
                        // hundreds of times a second during a transfer. Coalesce to four updates a second.
                        launch { s.stats.collect { publish(); kotlinx.coroutines.delay(250) } }
                        launch { _shared.collect { publish() } }
                        launch { _uploaded.collect { publish() } }
                        if (mode != PcMode.WIFI) launch {
                            // USB: the tethering interface appears when the user switches tethering on.
                            // LAN: the address changes when the phone joins another network.
                            while (true) {
                                kotlinx.coroutines.delay(2_000)
                                if (mode == PcMode.USB) {
                                    val st = UsbTether.status(app)
                                    if (st != usbStatus) { usbStatus = st; publish() }
                                } else {
                                    val ip = HttpServer.lanAddress()
                                    if (ip != lanStatus.ip) { lanStatus = lanStatus.copy(ip = ip); publish() }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PC mode ($mode) failed to start", e)
                    tearDown()
                    _state.value = PcState.Failed(mode, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Replaces the set of files offered for download. Safe to call at any time. */
    fun setShared(files: List<PickedFile>) {
        _shared.value = files
    }

    /** Replaces the PIN; browsers signed in with the old one must enter the new one. */
    fun newLanPin() {
        scope.launch { lock.withLock { if (mode == PcMode.LAN) openLanLocked() } }
    }

    private fun openLanLocked() {
        val s = server ?: return
        val pin = HttpServer.newPin()
        s.openLan(pin)
        lanStatus = lanStatus.copy(open = true, pin = pin)
        publish()
    }

    /** Applies new Wi-Fi Direct settings: tears the group down and brings it back up. */
    fun restart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val m = lock.withLock {
                val m = mode
                tearDown()
                _state.value = PcState.Idle
                m
            } ?: return@launch
            start(app, m)
        }
    }

    /** Stops the server and whatever link the mode brought up. */
    fun stop() {
        scope.launch {
            lock.withLock {
                tearDown()
                _state.value = PcState.Idle
            }
        }
    }

    private fun publish() {
        val m = mode ?: return
        val s = server ?: return
        val g = group
        if (m == PcMode.WIFI && g == null) return
        _state.value = PcState.Ready(
            mode = m,
            stats = s.stats.value,
            sharedCount = _shared.value.size,
            uploadedCount = _uploaded.value.size,
            uploadedNames = _uploaded.value,
            wifi = g?.let { WifiGroup(it.ssid, it.passphrase, "http://${it.ownerIp}:${HttpServer.PORT}", it.band) },
            usb = usbStatus,
            lan = lanStatus,
        )
    }

    private suspend fun tearDown() {
        statsJob?.cancel(); statsJob = null
        runCatching { server?.stop() }; server = null
        runCatching { host?.stop() }; host = null
        group = null
        mode = null
        usbStatus = UsbTether.Status()
        lanStatus = LanStatus()
    }
}
