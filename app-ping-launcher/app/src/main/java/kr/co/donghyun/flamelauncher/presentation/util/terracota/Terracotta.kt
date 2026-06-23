/*
 * Terracotta wrapper — ported & simplified for FlameLauncher.
 *
 * Original: ZalithLauncher2 (GPL-3.0), referencing FoldCraftLauncher / HMCL.
 *   https://github.com/FCL-Team/FoldCraftLauncher/blob/52f0542/FCL/src/main/java/com/tungsten/fcl/terracotta/Terracotta.java
 *
 * Simplifications for the minimal port:
 *  - EventViewModel(event bus)  -> direct VpnRequestCallback
 *  - MutableTransitionStateFlow -> StateFlow + previous-state tracking
 *  - PathManager / Logger       -> context.filesDir + android.util.Log
 */

package kr.co.donghyun.flamelauncher.presentation.util.terracota

import android.util.Log
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.flamelauncher.presentation.util.terracota.TerracottaState
import kr.co.donghyun.terracota.TerracottaAndroidAPI
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Terracotta"

/**
 * Terracotta 통합 진입점(싱글톤).
 *
 * 상태 폴링 루프가 핵심: 1ms 간격으로 native getState() 를 읽어 index 가 증가하면
 * 새 상태로 간주하고 StateFlow 에 publish. 호스트/게스트 진행이 이 흐름으로 드러난다.
 */
object Terracotta {

    enum class Mode { Host, Guest }

    /**
     * native 가 VpnService 를 요구할 때(EasyTier 가 tun 을 획득할 때) 호출되는 콜백.
     * UI/Activity 레이어에서 VpnService.prepare() → 동의 → TerracottaVPNService 시작을 처리해야 한다.
     */
    fun interface VpnRequestCallback {
        fun onRequestVpn()
    }

    var initialized = false
        private set

    private var vpnCallback: VpnRequestCallback? = null
    private var metadata: TerracottaAndroidAPI.Metadata? = null

    var mode: Mode? = null
        private set

    // 현재 상태
    private val _state = MutableStateFlow<TerracottaState.Ready?>(null)
    val state = _state.asStateFlow()

    // (이전, 새) 상태 전이 — ZL2 의 MutableTransitionStateFlow.changes 대체
    private val _stateChanges = MutableSharedFlow<Pair<TerracottaState.Ready?, TerracottaState.Ready>>(
        extraBufferCapacity = 64
    )
    val stateChanges = _stateChanges.asSharedFlow()

    private val stateRef = AtomicReference<TerracottaState.Ready?>(null)

    /**
     * @param rootDir     Terracotta 작업 디렉터리 (예: context.filesDir/terracotta)
     * @param logFile     로그 파일 경로
     * @param scope       폴링 루프를 돌릴 코루틴 스코프
     * @param vpnCallback native 의 VpnService 요청을 받을 콜백
     */
    fun initialize(
        rootDir: File,
        logFile: File,
        scope: CoroutineScope,
        vpnCallback: VpnRequestCallback
    ) {
        if (initialized) return
        this.vpnCallback = vpnCallback

        rootDir.mkdirs()
        logFile.parentFile?.mkdirs()

        metadata = TerracottaAndroidAPI.initialize(
            rootDir.absolutePath,
            logFile.absolutePath
        ) {
            // native VpnService 요청 → 콜백 위임
            this.vpnCallback?.onRequestVpn()
        }

        scope.launch(Dispatchers.Default) {
            while (true) {
                val current = stateRef.get()
                val index = current?.index ?: -1

                val stateJson = TerracottaAndroidAPI.getState()
                val obj = TerracottaState.TerracottaStateGson.fromJson<TerracottaState.Ready>(
                    stateJson,
                    object : TypeToken<TerracottaState.Ready>() {}.type
                ) ?: throw JsonParseException("Json object cannot be null.")

                if (obj.index > index) {
                    if (stateRef.compareAndSet(current, obj)) {
                        _state.value = obj
                        _stateChanges.tryEmit(current to obj)
                    }
                }

                delay(1L)
            }
        }

        initialized = true
    }

    fun setWaiting() {
        if (!initialized) return
        TerracottaAndroidAPI.setWaiting()
    }

    /** 호스트 모드: LAN 을 스캔해 EasyTier 가상망에 광고. room=null 이면 새 룸 코드 발급. */
    fun setScanning(room: String?, player: String?, extraNodes: List<String>?) {
        checkInitialized()
        if (_state.value !is TerracottaState.Waiting)
            throw IllegalStateException("reset state to waiting first!")
        mode = Mode.Host
        TerracottaAndroidAPI.setScanning(room, player, extraNodes)
    }

    /** 게스트 모드: 룸 코드로 가상망 합류. 코드가 유효하지 않으면 false. */
    fun setGuesting(room: String, player: String?, extraNodes: List<String>?): Boolean {
        checkInitialized()
        if (_state.value !is TerracottaState.Waiting)
            throw IllegalStateException("reset state to waiting first!")
        mode = Mode.Guest
        return TerracottaAndroidAPI.setGuesting(room, player, extraNodes)
    }

    fun parseRoomCode(room: String?): TerracottaAndroidAPI.RoomType? {
        if (!initialized || room == null) return null
        return TerracottaAndroidAPI.parseRoomCode(room)
    }

    fun getMetadata(): TerracottaAndroidAPI.Metadata =
        metadata ?: TerracottaAndroidAPI.Metadata("unknown", 0, "unknown")

    fun collectLogs(): String? {
        if (!initialized) return null
        return try {
            TerracottaAndroidAPI.collectLogs().use { reader ->
                val writer = StringWriter()
                val buf = CharArray(4096)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) writer.write(buf, 0, n)
                writer.toString()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to collect logs: ${e.message}")
            "Failed to collect logs: ${e.message}"
        }
    }

    private fun checkInitialized() {
        if (!initialized) throw IllegalStateException("initialize Terracotta first!")
    }
}