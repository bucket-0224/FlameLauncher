package kr.co.donghyun.flamelauncher.presentation.util.terracota

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kr.co.donghyun.terracota.TerracottaAndroidAPI
import java.io.File

private const val TAG = "TerracottaController"

/**
 * 최소 동작 컨트롤러.
 *
 * 사용 순서:
 *   1) val ctrl = TerracottaController(activity, scope)
 *   2) activity 에서 VPN 동의 런처를 만들고 ctrl.onVpnConsentResult 로 결과를 넘김
 *      (registerForActivityResult(StartActivityForResult))
 *   3) ctrl.start()  → Terracotta 초기화 + waiting
 *   4) 호스트: ctrl.hostRoom(userName)  → state 가 HostOK 되면 .code 가 룸 코드
 *      게스트: ctrl.joinRoom(code, userName)
 */
class TerracottaController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val nodes: List<String> = listOf(),
) {
    //TerracottaNodes.PUBLIC_NODES
    /** VPN 동의 다이얼로그를 띄우는 람다. activity 의 ActivityResultLauncher.launch 를 연결한다. */
    var launchVpnConsent: ((Intent) -> Unit)? = null

    val state get() = Terracotta.state
    val stateChanges get() = Terracotta.stateChanges

    fun start() {
        if (Terracotta.initialized) {
            Terracotta.setWaiting()
            return
        }
        val root = File(activity.filesDir, "terracotta")
        val logFile = File(root, "terracotta.log")

        Terracotta.initialize(
            rootDir = root,
            logFile = logFile,
            scope = scope
        ) {
            // native 가 VpnService 를 요구 → 메인 스레드에서 동의 흐름
            activity.runOnUiThread { requestVpn() }
        }
        Terracotta.setWaiting()
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(activity)
        if (intent != null) {
            // 아직 동의 전 → 시스템 다이얼로그
            val launcher = launchVpnConsent
            if (launcher != null) {
                launcher(intent)
            } else {
                Log.e(TAG, "launchVpnConsent 가 설정되지 않음 — VPN 동의를 띄울 수 없음")
                runCatching { TerracottaAndroidAPI.getPendingVpnServiceRequest().reject() }
                Terracotta.setWaiting()
            }
        } else {
            // 이미 동의됨 → 바로 서비스 시작
            startVpnService()
        }
    }

    /** activity 의 ActivityResult 콜백에서 호출 */
    fun onVpnConsentResult(granted: Boolean) {
        if (granted) {
            startVpnService()
        } else {
            runCatching { TerracottaAndroidAPI.getPendingVpnServiceRequest().reject() }
            Terracotta.setWaiting()
            Log.w(TAG, "VPN 권한이 거부됨")
        }
    }

    private fun startVpnService() {
        val vpnIntent = Intent(activity, TerracottaVPNService::class.java)
            .setAction(TerracottaVPNService.ACTION_START)
        ContextCompat.startForegroundService(activity, vpnIntent)
    }

    /** 호스트: 새 룸을 열고 LAN 을 광고. 진행은 state(HostScanning→HostStarting→HostOK)로 드러남. */
    fun hostRoom(userName: String?) {
        runCatching {
            Terracotta.setScanning(room = null, player = userName, extraNodes = nodes)
        }.onFailure { Log.w(TAG, "hostRoom 실패: ${it.message}") }
    }

    /** 게스트: 룸 코드로 합류. 코드가 유효하지 않으면 false. */
    fun joinRoom(code: String, userName: String?): Boolean {
        return runCatching {
            Terracotta.setGuesting(room = code, player = userName, extraNodes = nodes)
        }.getOrElse {
            Log.w(TAG, "joinRoom 실패: ${it.message}")
            false
        }
    }

    /** 룸 코드 형식 검증 (UI 입력 즉시 체크용) */
    fun isValidRoomCode(code: String): Boolean =
        Terracotta.parseRoomCode(code) != null

    /** 대기 상태로 복귀 (룸 나가기) */
    fun reset() {
        Terracotta.setWaiting()
    }

    /** VPN 서비스 중지 */
    fun stopVpn(context: Context) {
        if (TerracottaVPNService.isRunning()) {
            val vpnIntent = Intent(context, TerracottaVPNService::class.java)
                .setAction(TerracottaVPNService.ACTION_STOP)
            ContextCompat.startForegroundService(context, vpnIntent)
        }
    }
}