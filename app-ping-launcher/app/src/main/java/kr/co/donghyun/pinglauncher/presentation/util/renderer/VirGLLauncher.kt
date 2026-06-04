package kr.co.donghyun.pinglauncher.presentation.util.renderer

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VirGL test server 를 백그라운드 스레드에서 띄운다.
 *
 * 동작:
 *  1) APK 의 nativeLibraryDir 안에서 libvirgl_test_server.so 를 찾는다.
 *  2) JREUtils.executeBinary 와 같은 방식으로 dlopen → main(argc, argv) 호출.
 *  3) 서버는 blocking 이므로 별도 스레드에서 무한 루프로 listen.
 *
 * Mesa virpipe driver 는 VTEST_SOCKET_NAME 환경변수에 적힌 socket 으로 connect 한다.
 * 이 클래스는 같은 경로를 server 인자에도 넘겨주기 때문에 한 쌍이 맞아야 한다.
 */
object VirGLLauncher {

    private const val TAG = "VirGLLauncher"
    private val started = AtomicBoolean(false)

    /** virpipe ↔ vtest-server 가 공유할 unix socket 경로 */
    fun socketPath(context: Context): String =
        File(context.cacheDir, ".virgl_test.socket").absolutePath

    /**
     * @return true 면 서버 시작 시도가 완료된 상태 (이미 켜져있어도 true)
     */
    fun start(context: Context): Boolean {
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG, "VirGL server 이미 시작됨 — 스킵")
            return true
        }

        val libDir = context.applicationInfo.nativeLibraryDir
        val serverSo = File(libDir, "libvirgl_test_server.so")
        if (!serverSo.exists()) {
            Log.e(TAG, "❌ libvirgl_test_server.so 없음: ${serverSo.absolutePath}")
            started.set(false)
            return false
        }

        val socket = socketPath(context)
        // 이전 실행의 잔여 socket 정리
        runCatching { File(socket).delete() }

        // VirGL 서버 인자 — PojavLauncher 와 동일 옵션
        //  --no-loop-or-fork : 단일 클라이언트만 받고 종료 안 함
        //  --use-gles        : GLES 백엔드 (안드로이드는 데스크톱 GL 없음)
        //  --socket-path     : virpipe 가 접속할 unix socket
        val args = arrayOf(
            "virgl_test_server",
            "--no-loop-or-fork",
            "--use-gles",
            "--socket-path", socket
        )

        Thread({
            try {
                Log.i(TAG, "🌀 VirGL server 시작: ${serverSo.absolutePath} socket=$socket")
                // utils.c 의 executeBinary 와 동일한 방식: dlopen → main 심볼 호출
                executeBinaryNative(serverSo.absolutePath, args)
                Log.w(TAG, "VirGL server 종료됨")
            } catch (t: Throwable) {
                Log.e(TAG, "VirGL server 비정상 종료", t)
            } finally {
                started.set(false)
            }
        }, "VirGL-Server").apply {
            isDaemon = true
            start()
        }

        // socket 파일이 만들어질 때까지 잠깐 대기 (최대 2초)
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (File(socket).exists()) {
                Log.i(TAG, "✅ VirGL socket ready: $socket")
                return true
            }
            Thread.sleep(50)
        }
        Log.w(TAG, "⚠️ 2초 안에 VirGL socket 생성 안 됨 — 그래도 진행")
        return true
    }

    /**
     * 이미 있는 JREUtils.executeBinary 의 JNI 가 cmdArgs[0] 을 .so 경로로 보고
     * dlopen 후 main() 을 호출한다. 같은 메커니즘 그대로 재사용.
     */
    @JvmStatic
    private external fun executeBinaryNative(soPath: String, args: Array<String>): Int

    init {
        // libpojavexec.so 안의 executeBinary 심볼을 그대로 쓰기 위해 사전 로드.
        // MinecraftActivity 에서 이미 로드했지만 안전망.
        runCatching { System.loadLibrary("pojavexec") }
    }
}