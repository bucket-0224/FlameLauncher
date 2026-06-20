package kr.co.donghyun.pinglauncher.presentation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager.InputDeviceListener
import android.net.Uri
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.data.auth.MicrosoftAuthManager
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.data.instance.InstanceType
import kr.co.donghyun.pinglauncher.data.jvm.JvmSettings
import kr.co.donghyun.pinglauncher.data.jvm.JvmSettingsManager
import kr.co.donghyun.pinglauncher.data.jvm.isLegacyVersion
import kr.co.donghyun.pinglauncher.data.renderer.Renderer
import kr.co.donghyun.pinglauncher.data.renderer.RendererManager
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.components.GameControllerView
import kr.co.donghyun.pinglauncher.presentation.ui.components.MinecraftBootOverlay
import kr.co.donghyun.pinglauncher.presentation.ui.components.MinecraftSurface
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PingLauncherTheme
import kr.co.donghyun.pinglauncher.presentation.util.MinecraftActivityBridge
import kr.co.donghyun.pinglauncher.presentation.util.dns.DnsHookNative
import kr.co.donghyun.pinglauncher.presentation.util.jni.JavaNativeLauncher
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.MinecraftJREPreparer
import kr.co.donghyun.pinglauncher.presentation.util.resources.ResourcePackImporter
import org.lwjgl.glfw.GLFW.GLFW_KEY_A
import org.lwjgl.glfw.GLFW.GLFW_KEY_D
import org.lwjgl.glfw.GLFW.GLFW_KEY_E
import org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
import org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH
import org.lwjgl.glfw.GLFW.GLFW_KEY_T
import org.lwjgl.glfw.GLFW.GLFW_KEY_TAB
import org.lwjgl.glfw.GLFW.GLFW_KEY_W
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


class MinecraftActivity : BaseActivity() {

    private external fun nativeIsGrabbing(): Boolean
    private external fun nativeSetupBridgeWindow(surface: Surface)
    private external fun nativeTrySetupShowingWindow(): Boolean
    private external fun nativeDumpInputState()

    private external fun nativeSendKey(key: Int, scancode: Int, action: Int, mods: Int)

    private external fun nativeSendMouseButton(button: Int, action: Int, mods: Int)
    private external fun nativeSendCursorPos(x: Float, y: Float)


    // Intent로 전달받은 버전 정보
    private lateinit var versionId: String
    private lateinit var assetIndex: String
    private lateinit var extraJars: List<String>
    private lateinit var mainClass: String
    internal var instanceDir: String? = null
    private var customGameDir: String? = null
    private var currentSurface: Surface? = null
    @Volatile var combatMode: Boolean = false

    private var forceShowController: Boolean? = null

    private val PROCESSOR_ONLY_JAR_PREFIXES = listOf(
        "ForgeAutoRenamingTool",
        "BinaryPatcher", "binarypatcher",
        "jarsplitter",
        "installertools",
        "vignette",
        "DiffPatch", "diffpatch",
        "mergetool"   // ※ 부팅에 필요한 mergetool-*-api.jar 는 보존 필요 — 아래 헬퍼에서 별도 처리
    )

    private var gameControllerView: GameControllerView? = null
    private var inputDeviceListener: InputDeviceListener? = null

    internal val isGrabbing: Boolean
        get() {
            if (!jvmStarted) return false
            return try { nativeIsGrabbing() } catch (_: Throwable) { false }
        }

    private var jvmStarted = false
    private var javaMajor: Int = 21

    // ── 부팅 로딩 오버레이 상태 ──
    // showBootOverlay 가 true 인 동안 게임 surface 위에 "부팅 중" 다이얼로그를 표시한다.
    // 첫 프레임 콜백(MinecraftActivityBridge.onFirstFrameRendered) 또는 타임아웃에 false 가 된다.
    private var showBootOverlay by mutableStateOf(true)
    private var bootModCount by mutableIntStateOf(0)
    private var bootMaxDelayMin by mutableIntStateOf(2)
    @Volatile private var bootOverlayDismissed = false


    companion object {
        private const val EXTRA_VERSION_ID = "version_id"
        private const val EXTRA_ASSET_INDEX = "asset_index"
        private const val EXTRA_EXTRA_JARS = "extra_jars"
        private const val EXTRA_MAIN_CLASS = "main_class"
        private const val EXTRA_GAME_DIR = "game_dir"
        private const val EXTRA_INSTANCE_DIR = "instance_dir"

        @JvmStatic
        var currentInstance: MinecraftActivity? = null

        fun start(
            context: Context,
            versionId: String,
            assetIndex: String,
            extraJars: List<String> = emptyList(),
            mainClass: String = "net.minecraft.client.main.Main",
            customGameDir: String? = null,
            instanceDir: String? = null
        ) {
            Log.d("PING_LAUNCHER", "MC 시작: mainClass=$mainClass, extraJars=${extraJars.size}개")
            Log.d("PING_LAUNCHER", "instanceDir 전달: $instanceDir")  // ← 추가
            Log.d("PING_LAUNCHER", "customGameDir 전달: $customGameDir")  // ← 추가

            context.startActivity(
                Intent(context, MinecraftActivity::class.java).apply {
                    instanceDir?.let { putExtra(EXTRA_INSTANCE_DIR, it) }
                    putExtra(EXTRA_VERSION_ID, versionId)
                    putExtra(EXTRA_ASSET_INDEX, assetIndex)
                    putStringArrayListExtra(EXTRA_EXTRA_JARS, ArrayList(extraJars))
                    putExtra(EXTRA_MAIN_CLASS, mainClass)
                    customGameDir?.let { putExtra(EXTRA_GAME_DIR, it) }
                }
            )
        }
    }


    // ── 게임 내 "리소스팩 폴더 열기" → SAF 로 .zip 선택해 resourcepacks/ 에 복사 ──
    //   MainActivity(stub).openLink 가 resourcepacks 경로를 감지하면 이 Activity 의
    //   openResourcePackPicker(dir) 를 호출한다. 피커 결과는 아래 런처가 받는다.
    //   (런처 등록은 필드 초기화 시점 = RESUMED 이전이라 게임 중에도 launch 가능)
    private var pendingResourcePacksDir: File? = null

    private val resourcePackPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val dir = pendingResourcePacksDir
        pendingResourcePacksDir = null
        if (uri == null || dir == null) return@registerForActivityResult
        Toast.makeText(this, "리소스팩 가져오는 중…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = ResourcePackImporter.importZip(
                context = applicationContext,
                zipUri = uri,
                resourcePacksDir = dir,
            )
            withContext(Dispatchers.Main) {
                val msg = when (result) {
                    is ResourcePackImporter.Result.Success ->
                        "‘${result.packName}’ 추가됨. 게임 리소스팩 목록에서 활성화하세요."
                    is ResourcePackImporter.Result.Failure ->
                        result.reason
                }
                Toast.makeText(this@MinecraftActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 게임 내 "리소스팩 폴더 열기"에서 호출됨(MainActivity stub 경유).
     * 폴더 선택 대신 .zip 파일 피커를 띄워, 고른 팩을 해당 resourcepacks 폴더로 복사한다.
     */
    fun openResourcePackPicker(resourcePacksDir: File) {
        pendingResourcePacksDir = resourcePacksDir.apply { mkdirs() }
        val mimeTypes = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
        )
        try {
            resourcePackPickerLauncher.launch(mimeTypes)
            Toast.makeText(this, "추가할 리소스팩 .zip 을 선택하세요", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "리소스팩 피커 실행 실패: ${e.message}", e)
            pendingResourcePacksDir = null
        }
    }

    override fun onCreated() {
        hideNavigation()
        currentInstance = this

        versionId = intent.getStringExtra(EXTRA_VERSION_ID) ?: "1.16.2"
        assetIndex = intent.getStringExtra(EXTRA_ASSET_INDEX) ?: "1.16"
        extraJars = intent.getStringArrayListExtra(EXTRA_EXTRA_JARS) ?: emptyList()
        mainClass = intent.getStringExtra(EXTRA_MAIN_CLASS) ?: "net.minecraft.client.main.Main"
        instanceDir = intent.getStringExtra(EXTRA_INSTANCE_DIR)
        Log.d("PING_LAUNCHER", "instanceDir 수신: $instanceDir")  // ← 추가
        customGameDir = intent.getStringExtra(EXTRA_GAME_DIR)
        Log.d("PING_LAUNCHER", "customGameDir 수신: $customGameDir")  // ← 추가

        // 엣지투엣지로 직접 제어 — 시스템이 IME 에 맞춰 윈도우를 리사이즈/팬 하지 않게 한다.
        // (adjustNothing 만으로는 Compose/surface 로 IME insets 가 전파되어 화면이 밀리고
        //  번쩍이므로, 아래 리스너에서 IME insets 를 소비해 하위 전파를 끊는다.)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val ime = insets.isVisible(WindowInsetsCompat.Type.ime())
            gameControllerView?.setImeVisibleExternal(ime)
            if (ime != imeVisible) {
                imeVisible = ime
                updateGameControllerVisibility()
            }
            // IME insets 를 0 으로 덮어써서 하위(Compose/Surface)로의 전파를 차단.
            //   → 게임 화면이 IME 에 밀리지 않고(다 해결), surface 재레이아웃 번쩍임(가)도 사라진다.
            //   다른 insets(상태바/제스처 등)는 그대로 유지.
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { return }
        })

        setContent {
            PingLauncherTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    MinecraftSurface(
                        onSurfaceCreated = { surface, _ ->
                            currentSurface = surface
                            if (!jvmStarted) {
                                jvmStarted = true
                                setupAndLaunch(surface)
                            } else {
                                try {
                                    System.loadLibrary("pingjvm")
                                    nativeSetupBridgeWindow(surface)
                                    Log.d("PING_LAUNCHER", "✅ Surface 재바인딩 완료 (resume 후)")
                                } catch (e: Exception) {
                                    Log.e("PING_LAUNCHER", "Surface 재바인딩 실패: ${e.message}", e)
                                }
                            }
                        },
                        onSurfaceChanged = { w, h -> sendScreenSize(w, h) },
                        onSurfaceDestroyed = {
                            Log.d("PING_LAUNCHER", "Surface destroyed — JVM 유지")
                            currentSurface = null
                        },
                    )
                    // GameControllerOverlay() ← 제거

                    // 부팅 로딩 오버레이 (첫 프레임 렌더링 전까지 표시)
                    if (showBootOverlay) {
                        MinecraftBootOverlay(
                            modCount = bootModCount,
                            maxDelayMinutes = bootMaxDelayMin,
                            onClose = { dismissBootOverlay("user-close") },
                        )
                    }
                }
            }
        }


        // GameControllerView 는 부팅 오버레이가 닫힌 뒤(첫 프레임 이후) 추가한다.
        // 부팅 중에는 오버레이가 컨트롤러 위가 아니라, 컨트롤러 자체가 아직 없도록 한다.

        // 초기 상태 반영 + 디바이스 변화 감지
        setupInputDeviceWatching()
        installPhysicalKeyboardInterceptor()

        setupBootOverlay()
    }

    /** 게임 컨트롤러 오버레이 뷰를 화면에 추가한다. 첫 프레임 이후 1회 호출. */
    private fun attachGameControllerView() {
        if (gameControllerView != null) return   // 중복 추가 방지
        gameControllerView = GameControllerView(this).also { view ->
            addContentView(
                view,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        // 현재 입력 상태 즉시 반영(컨트롤러가 늦게 붙으므로).
        // setupInputDeviceWatching() 을 다시 부르면 리스너/폴링이 중복 등록되므로,
        // 가시성 갱신만 호출한다. (리스너·폴링은 onCreate 에서 이미 살아있음)
        try { updateGameControllerVisibility() } catch (_: Throwable) {}
    }

    /**
     * 부팅 로딩 오버레이 준비:
     *  - mods 폴더의 .jar 개수를 세어 모드팩 여부/지연 안내 시간 결정
     *  - 첫 프레임 콜백 등록 (네이티브가 첫 swap 시 호출 → 오버레이 닫기)
     *  - 콜백이 오지 않을 경우를 대비한 타임아웃 안전망
     */
    private fun setupBootOverlay() {
        // 모드 개수 계산 (mcDir/mods 의 .jar). 0 이면 바닐라로 간주.
        val count = try {
            val base = instanceDir?.let { File(it) }
                ?: customGameDir?.let { File(it) }
                ?: File(getExternalFilesDir(null), "instances/vanilla_$versionId")
            File(base, "mods").listFiles()
                ?.count { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                ?: 0
        } catch (_: Exception) { 0 }

        bootModCount = count
        // 대략적 안내값: 기본 2분 + 모드 50개당 약 1분, 최대 10분으로 캡.
        bootMaxDelayMin = (2 + count / 50).coerceIn(2, 10)

        // 첫 프레임 콜백 등록 (렌더 스레드에서 불리므로 UI 스레드로 전환)
        MinecraftActivityBridge.setFirstFrameListener {
            runOnUiThread { dismissBootOverlay("first-frame") }
        }

        // 타임아웃 안전망: 콜백이 어떤 이유로 안 와도 무한 로딩이 되지 않게.
        // 모드 수에 비례해 넉넉히 잡되(분→ms), 최소 30초.
        val timeoutMs = (bootMaxDelayMin.toLong() * 60_000L).coerceAtLeast(30_000L)
        window.decorView.postDelayed({
            if (!bootOverlayDismissed) {
                Log.w("PING_LAUNCHER", "부팅 오버레이 타임아웃(${timeoutMs}ms) — 강제 닫기")
                dismissBootOverlay("timeout")
            }
        }, timeoutMs)
    }

    private fun dismissBootOverlay(reason: String) {
        if (bootOverlayDismissed) return
        bootOverlayDismissed = true
        showBootOverlay = false
        Log.d("PING_LAUNCHER", "부팅 오버레이 닫음 ($reason)")
        // 오버레이가 사라진 뒤에야 게임 컨트롤러를 화면에 올린다.
        attachGameControllerView()
    }

    private fun setupInputDeviceWatching() {
        val im = getSystemService(INPUT_SERVICE)
                as android.hardware.input.InputManager

        // 초기 상태 반영
        updateGameControllerVisibility()

        // 디바이스 add/remove/change 감지
        inputDeviceListener = object : InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                Log.d("PING_LAUNCHER", "🎮 입력 디바이스 추가: id=$deviceId")
                updateGameControllerVisibility()
            }
            override fun onInputDeviceRemoved(deviceId: Int) {
                Log.d("PING_LAUNCHER", "🎮 입력 디바이스 제거: id=$deviceId")
                updateGameControllerVisibility()
            }
            override fun onInputDeviceChanged(deviceId: Int) {
                updateGameControllerVisibility()
            }
        }
        im.registerInputDeviceListener(inputDeviceListener, null)

        // ── grab 상태 폴링(가벼움, 500ms) ──
        //   마인크래프트 onGrabStateChanged JNI 콜백은 모듈 레이어 불일치로 NoSuchMethodError 라
        //   신뢰 불가. nativeIsGrabbing()(=pojav_environ->isGrabbing, 정상 갱신)을 폴링해
        //   '첫 grab=true' 를 월드 진입으로 보고, 이후 컨트롤러를 계속 표시(A-1).
        startGrabStatePolling()
    }

    private var lastGrabState: Boolean? = null
    private var imeVisible = false
    private var hasEnteredWorld = false

    private fun startGrabStatePolling() {
        lifecycleScope.launch {
            var shownOnce = false
            while (true) {
                if (jvmStarted) {
                    // JVM 시작 직후 1회: grab 변화가 없어도(타이틀은 grab=false 유지) 컨트롤러를 표시.
                    if (!shownOnce) {
                        shownOnce = true
                        updateGameControllerVisibility()
                    }
                    val grab = isGrabbing
                    if (grab != lastGrabState) {
                        lastGrabState = grab
                        if (grab) hasEnteredWorld = true   // 첫 grab = 월드 진입
                        updateGameControllerVisibility()
                    }
                }
                kotlinx.coroutines.delay(300)
            }
        }
    }

    /** 현재 물리 키보드 또는 마우스가 연결되어 있는지 */
    private fun hasHardwareKeyboardOrMouse(): Boolean {
        val im = getSystemService(INPUT_SERVICE)
                as android.hardware.input.InputManager

        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            if (dev.isVirtual) continue

            val src = dev.sources

            // 알파벳 키보드 (소프트 IME 제외)
            if ((src and InputDevice.SOURCE_KEYBOARD) != 0
                && dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                return true
            }

            // 마우스 / 터치패드
            if ((src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (src and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                return true
            }
        }
        return false
    }


    /**
     * GameControllerView 가시성 갱신.
     * 표시 조건: 월드에서 플레이 중(마우스 grab) AND 물리 키보드/마우스 미연결.
     *
     * 판정 기준:
     *  - grab: nativeIsGrabbing() — 마인크래프트가 마우스를 잡은 상태(=월드 플레이 중)
     *  - 키보드: SOURCE_KEYBOARD 비트 + KEYBOARD_TYPE_ALPHABETIC (소프트 IME 제외)
     *  - 마우스: SOURCE_MOUSE 비트 (또는 SOURCE_MOUSE_RELATIVE)
     *  - 물리 입력이 하나라도 있으면 숨김 (물리로 조작)
     *  - grab 이 풀리면(메뉴/인벤토리/채팅) 숨김 (커서로 조작)
     */
    internal fun updateGameControllerVisibility() {
        val hasExternalInput = hasHardwareKeyboardOrMouse()
        // 전체 버튼은 "실제 플레이 중(grab) 이면서 IME(채팅)가 닫혀있을 때"만.
        //   - grab + IME 닫힘 → 전체 버튼 (WASD 등 플레이 조작)
        //   - grab + IME 열림(채팅) → ESC + 키보드 버튼만 (다른 버튼이 채팅을 가리지 않도록)
        //   - 그 외(타이틀/인벤토리/ESC메뉴) → ESC + 키보드 버튼만
        //   - 물리 키보드/마우스 → 전체 숨김
        //   ※ 최초 타이틀 화면부터 ESC/키보드가 보이도록 hasEnteredWorld 게이트는 쓰지 않는다.
        val fullControl = isGrabbing && !imeVisible
        val shouldShow = jvmStarted && !hasExternalInput
        val target = if (shouldShow) View.VISIBLE else View.INVISIBLE
        runOnUiThread {
            val view = gameControllerView ?: return@runOnUiThread
            view.setEscOnlyMode(!fullControl)   // grab/IME 아니면 ESC + 키보드만
            if (view.visibility != target) {
                view.visibility = target
            }
        }
    }

    private fun setupAndLaunch(surface: Surface) {
        val nativesDir = File(applicationContext.filesDir, "natives")

        // ★ mcVersion 기반으로 Java major 결정
        javaMajor = MinecraftJREPreparer.pickJavaMajor(versionId)
        Log.d("PING_LAUNCHER", "선택된 Java major: $javaMajor (mc=$versionId)")

        // pingjvm 은 반드시 떠야 하므로 별도 처리
        try {
            System.loadLibrary("pingjvm")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("PING_LAUNCHER", "❌ libpingjvm.so 로드 실패 — 진행 불가: ${e.message}", e)
            return
        }


        var renderer = RendererManager.load(this)

        when (renderer.id) {
            "mobileglues" -> {
                // info_getter 가 먼저! libmobileglues.so 의 의존성임
                loadSoSafely(File(nativesDir, "libmobileglues_info_getter.so"), required = false)
                if (loadSoSafely(File(nativesDir, "libmobileglues.so"), required = false)) {
                    Log.d("PING_LAUNCHER", "✅ 렌더러: MobileGlues")
                } else {
                    Log.w("PING_LAUNCHER", "⚠️ libmobileglues.so 없음 — RendererManager 선택만 됐고 .so 미배포")
                }
            }
            "zink" -> {
                try { System.loadLibrary("vulkan") } catch (_: Throwable) {}
                if (loadSoSafely(File(nativesDir, "libOSMesa.so"), required = true)) {
                    Log.d("PING_LAUNCHER", "✅ 렌더러: Zink")
                }
            }
            else -> {
                // gl4es / gl4es_desktop / holy_gl4es
                if (loadSoSafely(File(nativesDir, "libgl4es_114.so"), required = true)) {
                    Log.d("PING_LAUNCHER", "✅ 렌더러: GL4ES")
                }
            }
        }

        // 공통 .so — 하나가 실패해도 다음 것은 계속 시도
        loadSoSafely(File(nativesDir, "libopenal.so"), required = false)
        loadSoSafely(File(nativesDir, "libglfw.so"), required = true)
        loadSoSafely(File(nativesDir, "libpojavexec.so"), required = true)
        loadSoSafely(File(nativesDir, "liblwjgl.so"), required = false)
        loadSoSafely(File(nativesDir, "liblwjgl_opengl.so"), required = false)

        DnsHookNative.setup(this)

        // ── AWT stub preload (실패해도 무시 — JNI 바인딩 불일치여도 핵심 .so 는 이미 떠 있음) ──
        try {
            JavaNativeLauncher.preloadAwtStubs(applicationInfo.nativeLibraryDir)
        } catch (e: UnsatisfiedLinkError) {
            Log.w("PING_LAUNCHER", "⚠️ preloadAwtStubs 바인딩 실패 (무시 가능): ${e.message}")
        } catch (e: Throwable) {
            Log.w("PING_LAUNCHER", "⚠️ preloadAwtStubs 예외 (무시 가능): ${e.message}")
        }

        try {
            nativeSetupBridgeWindow(surface)
            Log.d("PING_LAUNCHER", "✅ setupBridgeWindow 완료")
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "setupBridgeWindow 실패: ${e.message}", e)
        }

        startCrashWatcher()
        startMinecraft()
    }

    /**
     * .so 한 개를 안전하게 로드. 이미 로드되어 있거나 파일이 없으면 false 반환.
     * required=true 인데 실패하면 ERROR 로그, 아니면 WARN 로그만 남기고 계속 진행.
     */
    private fun loadSoSafely(soFile: File, required: Boolean): Boolean {
        if (!soFile.exists()) {
            if (required) Log.e("PING_LAUNCHER", "❌ 필수 .so 파일 없음: ${soFile.name}")
            else Log.w("PING_LAUNCHER", "⚠️ .so 파일 없음 (스킵): ${soFile.name}")
            return false
        }
        return try {
            System.load(soFile.absolutePath)
            Log.d("PING_LAUNCHER", "📦 .so 로드: ${soFile.name}")
            true
        } catch (e: UnsatisfiedLinkError) {
            // 이미 로드된 경우도 여기로 옴 — 무해
            val msg = e.message ?: ""
            if (msg.contains("already loaded", ignoreCase = true)) {
                Log.d("PING_LAUNCHER", "ℹ️ 이미 로드됨: ${soFile.name}")
                true
            } else {
                if (required) Log.e("PING_LAUNCHER", "❌ ${soFile.name} 로드 실패: $msg", e)
                else Log.w("PING_LAUNCHER", "⚠️ ${soFile.name} 로드 실패 (무시): $msg")
                false
            }
        }
    }


    private fun startCrashWatcher() {
        val instanceBase = instanceDir?.let { File(it) }
            ?: customGameDir?.let { File(it) }  // instanceDir 없을 때만 fallback
            ?: File(getExternalFilesDir(null), "instances/vanilla_$versionId")

        Thread {
            val crashDir = File(instanceBase, "crash-reports")
            val existingFiles = crashDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

            // JVM이 실행되는 동안 새 크래시 파일 감시
            while (!isFinishing) {
                Thread.sleep(1000)
                val newCrash = crashDir.listFiles()
                    ?.any { it.extension == "txt" && !existingFiles.contains(it.name) } == true
                if (newCrash) {
                    Log.d("PING_LAUNCHER", "새 크래시 감지!")
                    val intent = Intent(this, CrashReportActivity::class.java).apply {
                        putExtra("instance_dir", instanceBase.absolutePath)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                    break
                }
            }
        }.start()
    }

    private fun sendScreenSize(width: Int, height: Int) {
        try {
            Class.forName("org.lwjgl.glfw.CallbackBridge")
                .getMethod("nativeSendScreenSize", Int::class.java, Int::class.java)
                .invoke(null, width, height)
        } catch (_: Exception) {}
    }

    internal var currentCursorX = 1280f  // 화면 중앙 근처
    internal var currentCursorY = 720f
    internal val MOUSE_SENSITIVITY = 1.5f

    // 마인크래프트 GUI 스케일(options.txt). 핫바 영역 계산에 사용. 게임 실행 시 갱신.
    @Volatile internal var mcGuiScale: Int = 0   // 0 = 자동(auto)

    /**
     * 핫바 슬롯 선택 (index 0~8). 화면 하단 핫바 영역 터치 시 호출.
     * 마인크래프트는 1~9 키로 슬롯을 직접 선택하므로 GLFW_KEY_1(49)+index 를 전송.
     */
    internal fun selectHotbarSlot(index: Int) {
        if (index !in 0..8) return
        val key = 49 + index   // GLFW_KEY_1 = 49
        sendKey(key, GLFW_PRESS)
        sendKey(key, GLFW_RELEASE)
    }

    /**
     * 현재 화면 크기 기준 핫바의 화면상 사각형(left,right,top,bottom px)을 계산한다.
     * ZL2 와 동일한 규칙: slotSize = guiScale*20, 핫바너비 = slotSize*9, 화면 하단 중앙.
     * guiScale 이 0(자동)이면 해상도로 계산. 핫바가 화면에 없으면 null.
     */
    internal fun computeHotbarRect(viewW: Int, viewH: Int): android.graphics.RectF? {
        if (viewW <= 0 || viewH <= 0) return null
        val auto = minOf(viewW / 320, viewH / 240).coerceAtLeast(1)
        val scale = if (mcGuiScale in 1..auto) mcGuiScale else auto
        val slot = scale * 20f
        val total = slot * 9f
        if (total <= 0f || total > viewW) return null
        val left = (viewW - total) / 2f
        val right = left + total
        // 핫바는 화면 맨 아래에서 살짝 위. MC 기본은 바닥에서 약 (slot/ ... ) 위지만,
        //   터치 편의상 바닥 ~ slotHeight*1.0 영역으로 잡는다.
        val bottom = viewH.toFloat()
        val top = bottom - slot
        return android.graphics.RectF(left, top, right, bottom)
    }


    /**
     * PojavLauncher patched lwjgl-glfw-classes.jar 에 누락된 GLFW 3.4 API 를
     * ASM 으로 노옵 스텁 주입.
     *
     * 1.21.5+ (특히 26w14a) 가 부팅 단계에서 호출하는 API:
     *   - glfwPlatformSupported(int)Z   ← 26w14a 가 NoSuchMethodError 로 죽는 지점
     *   - glfwGetPlatform()I
     *   - glfwFocusWindow / glfwHideWindow / glfwMaximizeWindow / glfwRestoreWindow (J)V
     *
     * 실제 GLFW 백엔드는 어차피 libglfw.so/libpojavexec.so 가 자체 구현이라,
     * 노옵 스텁이 있어도 MC 가 부팅 단계는 통과한다. HiDPI / IME 같은 부가 기능은
     * 동작 안 할 수 있지만 게임 자체는 켜짐.
     */
    /**
     * lwjgl-glfw-classes.jar 안에 GLFW 3.4 신규 API 스텁이 들어있는지
     * 실제 클래스 바이트를 검사해서 보장한다. 마커 파일에 의존하지 않음 —
     * 런처 업데이트로 필요한 스텁 목록이 늘어나도 자동 재패치되도록.
     */
    private fun patchLwjglGlfwIfNeeded(lwjgl3Dir: File) {
        if (!lwjgl3Dir.exists()) return
        val candidates = lwjgl3Dir.listFiles()
            ?.filter { it.name.startsWith("lwjgl-glfw-classes") && it.extension == "jar" }
            ?: return

        // 26.1.x 부팅에 필요한 GLFW 3.4 API 들
        val required = setOf(
            "glfwPlatformSupported(I)Z",
            "glfwGetPlatform()I",
            "glfwFocusWindow(J)V",
            "glfwHideWindow(J)V",
            "glfwMaximizeWindow(J)V",
            "glfwRestoreWindow(J)V",
            "glfwRequestWindowAttention(J)V",
        )

        for (jar in candidates) {
            val missing = findMissingMethods(jar, required)
            if (missing.isEmpty()) {
                Log.d("PING_LAUNCHER", "✅ GLFW 3.4 stubs 이미 있음: ${jar.name}")
                // 옛 마커 파일 청소 (있을 수도 없을 수도)
                File(jar.parent, "${jar.name}.patched_glfw34").delete()
                continue
            }
            Log.w("PING_LAUNCHER", "🩹 GLFW 패치 필요: ${jar.name} — 누락 메서드 $missing")
            try {
                patchGlfwJar(jar)
                Log.d("PING_LAUNCHER", "✅ 패치 완료: ${jar.name}")
                // 검증
                val stillMissing = findMissingMethods(jar, required)
                if (stillMissing.isNotEmpty()) {
                    Log.e("PING_LAUNCHER", "❌ 패치 후에도 여전히 누락: $stillMissing — patcher 버그 의심")
                }
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "❌ GLFW 패치 실패: ${e.message}", e)
            }
        }
    }

    /**
     * jar 안의 org/lwjgl/glfw/GLFW.class 를 열어서 [required] 중 빠진 메서드 시그니처 목록 반환.
     * jar 가 깨졌거나 GLFW.class 가 없으면 required 전체를 반환 (= 무조건 패치 시도).
     */
    private fun findMissingMethods(jar: File, required: Set<String>): Set<String> {
        return try {
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry("org/lwjgl/glfw/GLFW.class")
                    ?: return required
                val bytes = zip.getInputStream(entry).readBytes()
                val found = HashSet<String>()
                ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int, name: String, descriptor: String,
                        signature: String?, exceptions: Array<out String>?
                    ): org.objectweb.asm.MethodVisitor? {
                        found.add("$name$descriptor")
                        return null
                    }
                }, ClassReader.SKIP_CODE)
                required - found
            }
        } catch (e: Exception) {
            Log.w("PING_LAUNCHER", "jar 메서드 스캔 실패 (${jar.name}): ${e.message}")
            required
        }
    }

    private fun patchGlfwJar(jar: File) {
        val tmp = File(jar.parent, jar.name + ".tmp")
        ZipFile(jar).use { zin ->
            ZipOutputStream(tmp.outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val bytes = zin.getInputStream(entry).readBytes()
                    val finalBytes = if (entry.name == "org/lwjgl/glfw/GLFW.class") {
                        patchGlfwClass(bytes)
                    } else bytes

                    // 새 ZipEntry 로 만들어야 CRC/size 자동 계산. DEFLATED 로 통일.
                    val newEntry = ZipEntry(entry.name).apply {
                        method = ZipEntry.DEFLATED
                    }
                    zout.putNextEntry(newEntry)
                    zout.write(finalBytes)
                    zout.closeEntry()
                }
            }
        }
        if (!jar.delete()) throw IOException("기존 jar 삭제 실패: ${jar.absolutePath}")
        if (!tmp.renameTo(jar)) throw IOException("임시 jar rename 실패")
    }

    private fun patchGlfwClass(bytes: ByteArray): ByteArray {
        val ASM = Opcodes.ASM9

        // 이미 같은 시그니처 메서드가 있으면 덮어쓰지 않도록 1차 스캔
        val existing = HashSet<String>()
        ClassReader(bytes).accept(object : ClassVisitor(ASM) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor? {
                existing.add("$name$descriptor")
                return null
            }
        }, ClassReader.SKIP_CODE)

        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)

        val visitor = object : ClassVisitor(ASM, writer) {
            override fun visitEnd() {
                // GLFW_PLATFORM_X11 = 0x60004
                if ("glfwPlatformSupported(I)Z" !in existing) {
                    emitPlatformSupported(); Log.d("PING_LAUNCHER", "  + glfwPlatformSupported(I)Z")
                }
                if ("glfwGetPlatform()I" !in existing) {
                    emitGetPlatform(); Log.d("PING_LAUNCHER", "  + glfwGetPlatform()I")
                }
                listOf(
                    "glfwFocusWindow", "glfwHideWindow",
                    "glfwMaximizeWindow", "glfwRestoreWindow",
                    "glfwRequestWindowAttention"
                ).forEach { n ->
                    if ("$n(J)V" !in existing) {
                        emitNoopJV(n); Log.d("PING_LAUNCHER", "  + $n(J)V")
                    }
                }
                super.visitEnd()
            }

            private fun emitPlatformSupported() {
                val mv = cv.visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                    "glfwPlatformSupported", "(I)Z", null, null
                )
                mv.visitCode()
                mv.visitVarInsn(Opcodes.ILOAD, 0)
                mv.visitLdcInsn(0x60004)
                val notEqual = org.objectweb.asm.Label()
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, notEqual)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitLabel(notEqual)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitMaxs(2, 1)
                mv.visitEnd()
            }

            private fun emitGetPlatform() {
                val mv = cv.visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                    "glfwGetPlatform", "()I", null, null
                )
                mv.visitCode()
                mv.visitLdcInsn(0x60004)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitMaxs(1, 0)
                mv.visitEnd()
            }

            private fun emitNoopJV(name: String) {
                val mv = cv.visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                    name, "(J)V", null, null
                )
                mv.visitCode()
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(0, 2)   // long = 2 slot
                mv.visitEnd()
            }
        }
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }

    private fun patchLaunchwrapperIfNeeded(searchDirs: List<File>) {
        searchDirs.forEach { dir ->
            dir.walkTopDown()
                .filter { it.name.startsWith("launchwrapper") && it.extension == "jar" }
                .forEach { lwJar ->
                    // 이미 패치됐는지 확인 (패치 마커 파일)
                    val markerFile = File(lwJar.parent, "${lwJar.name}.patched")
                    if (markerFile.exists()) return@forEach

                    Log.d("PING_LAUNCHER", "launchwrapper 패치 중: ${lwJar.absolutePath}")
                    try {
                        patchLaunchJar(lwJar)
                        markerFile.createNewFile() // 패치 완료 마커
                        Log.d("PING_LAUNCHER", "✅ launchwrapper 패치 완료")
                    } catch (e: Exception) {
                        Log.e("PING_LAUNCHER", "launchwrapper 패치 실패: ${e.message}")
                    }
                }
        }
    }

    private fun patchLaunchJar(lwJar: File) {
        val zipIn = ZipFile(lwJar)
        val patchedJar = File(lwJar.parent, lwJar.name + ".tmp")
        val zipOut = ZipOutputStream(patchedJar.outputStream())

        zipIn.entries().asSequence().forEach { entry ->
            val bytes = zipIn.getInputStream(entry).readBytes()
            val patched = if (entry.name == "net/minecraft/launchwrapper/Launch.class") {
                patchLaunchClass(bytes)
            } else bytes
            zipOut.putNextEntry(ZipEntry(entry.name))
            zipOut.write(patched)
            zipOut.closeEntry()
        }

        zipIn.close()
        zipOut.close()
        lwJar.delete()
        patchedJar.renameTo(lwJar)
    }

    private fun patchLaunchClass(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)

        val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name == "<init>" && descriptor == "()V") {
                    return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, mv) {
                        override fun visitTypeInsn(opcode: Int, type: String) {
                            if (opcode == Opcodes.CHECKCAST && type == "java/net/URLClassLoader") {
                                visitInsn(Opcodes.POP)
                                visitLdcInsn("java.class.path")
                                visitLdcInsn("")
                                visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false)
                                visitLdcInsn(File.pathSeparator)
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", false)
                                visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/launchwrapper/Launch", "pingStringsToUrls", "([Ljava/lang/String;)[Ljava/net/URL;", false)
                                return
                            }
                            super.visitTypeInsn(opcode, type)
                        }
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            if (owner == "java/net/URLClassLoader" && name == "getURLs") return
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
                return mv
            }

            override fun visitEnd() {
                // 헬퍼 메서드 추가
                val mv = cv.visitMethod(
                    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
                    "pingStringsToUrls", "([Ljava/lang/String;)[Ljava/net/URL;", null, null
                )
                mv.visitCode()
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/net/URL")
                mv.visitVarInsn(Opcodes.ASTORE, 1)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitVarInsn(Opcodes.ISTORE, 2)
                val loopStart = org.objectweb.asm.Label()
                val loopEnd = org.objectweb.asm.Label()
                mv.visitLabel(loopStart)
                mv.visitVarInsn(Opcodes.ILOAD, 2)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
                mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd)
                val tryStart = org.objectweb.asm.Label()
                val tryEnd = org.objectweb.asm.Label()
                val catchBlock = org.objectweb.asm.Label()
                mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Exception")
                mv.visitLabel(tryStart)
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitVarInsn(Opcodes.ILOAD, 2)
                mv.visitTypeInsn(Opcodes.NEW, "java/io/File")
                mv.visitInsn(Opcodes.DUP)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.ILOAD, 2)
                mv.visitInsn(Opcodes.AALOAD)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "toURI", "()Ljava/net/URI;", false)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URI", "toURL", "()Ljava/net/URL;", false)
                mv.visitInsn(Opcodes.AASTORE)
                mv.visitLabel(tryEnd)
                mv.visitIincInsn(2, 1)
                mv.visitJumpInsn(Opcodes.GOTO, loopStart)
                mv.visitLabel(catchBlock)
                mv.visitInsn(Opcodes.POP)
                mv.visitIincInsn(2, 1)
                mv.visitJumpInsn(Opcodes.GOTO, loopStart)
                mv.visitLabel(loopEnd)
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitInsn(Opcodes.ARETURN)
                mv.visitMaxs(5, 3)
                mv.visitEnd()
                super.visitEnd()
            }
        }
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }
    internal fun sendMouseButton(button: Int, action: Int) {
        Log.d("PING_LAUNCHER", "sendMouseButton: btn=$button action=$action")
        nativeSendMouseButton(button, action, 0)
    }

    internal fun sendCursorPos(x: Float, y: Float) {
        Log.d("PING_LAUNCHER", "sendCursorPos: x=$x y=$y")
        nativeSendCursorPos(x, y)
    }

    internal fun sendKey(glfwKeyCode: Int, action: Int) {

        Log.d("PING_LAUNCHER", "sendKey: $glfwKeyCode action=$action")

        val scancode = getScancode(glfwKeyCode)

        nativeSendKey(glfwKeyCode, scancode, action, 0)

        // 채팅/명령어 키 → 소프트 키보드 자동 표시 (T=84, /=47).
        //   엔터(257)/ESC(256) → 채팅 닫힘이므로 키보드 숨김.
        if (action == GLFW_PRESS) {
            when (glfwKeyCode) {
                GLFW_KEY_T, GLFW_KEY_SLASH -> showGameSoftKeyboard()   // 채팅 / 명령어
                GLFW_KEY_ENTER, GLFW_KEY_ESCAPE -> hideGameSoftKeyboard()  // 전송/취소
            }
        }
    }

    private fun showGameSoftKeyboard() {
        // 물리 키보드가 있으면 surface 의 onCreateInputConnection 이 null 을 반환하므로
        //   소프트 키보드를 띄우지 않는다(물리 키로 입력).
        if (hasHardwareKeyboardOrMouse()) return
        runOnUiThread {
            val surface = window.decorView.findViewWithTag<View>("minecraft_surface") ?: return@runOnUiThread
            surface.isFocusableInTouchMode = true
            surface.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(surface, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideGameSoftKeyboard() {
        runOnUiThread {
            val surface = window.decorView.findViewWithTag<View>("minecraft_surface")
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(
                (surface ?: window.decorView).windowToken, 0
            )
        }
    }


    internal fun glfwKeyToChar(glfwKey: Int): Char = when (glfwKey) {
        in 65..90 -> ('a' + (glfwKey - 65))  // a-z
        in 48..57 -> ('0' + (glfwKey - 48))  // 0-9
        32 -> ' '
        else -> '\u0000'
    }

    internal fun getScancode(glfwKey: Int): Int = when (glfwKey) {
        // 알파벳 A=65 → scancode 30, B=66 → 48, C=67 → 46 ...
        65 -> 30   // A
        66 -> 48   // B
        67 -> 46   // C
        68 -> 32   // D
        69 -> 18   // E
        70 -> 33   // F
        71 -> 34   // G
        72 -> 35   // H
        73 -> 23   // I
        74 -> 36   // J
        75 -> 37   // K
        76 -> 38   // L
        77 -> 50   // M
        78 -> 49   // N
        79 -> 24   // O
        80 -> 25   // P
        81 -> 16   // Q
        82 -> 19   // R
        83 -> 31   // S
        84 -> 20   // T
        85 -> 22   // U
        86 -> 47   // V
        87 -> 17   // W
        88 -> 45   // X
        89 -> 21   // Y
        90 -> 44   // Z
        // 숫자
        48 -> 11   // 0
        49 -> 2    // 1
        50 -> 3    // 2
        51 -> 4    // 3
        52 -> 5    // 4
        53 -> 6    // 5
        54 -> 7    // 6
        55 -> 8    // 7
        56 -> 9    // 8
        57 -> 10   // 9
        // 특수키
        32 -> 57   // Space
        256 -> 1   // ESC
        257 -> 28  // Enter
        258 -> 15  // Tab
        259 -> 14  // Backspace
        261 -> 211 // Delete
        265 -> 103 // Up
        264 -> 108 // Down
        263 -> 105 // Left
        262 -> 106 // Right
        340 -> 42  // Left Shift
        341 -> 29  // Left Ctrl
        342 -> 56  // Left Alt
        344 -> 54  // Right Shift
        345 -> 97  // Right Ctrl
        346 -> 100 // Right Alt
        else -> 0
    }

    fun gaKey(jarPath: String, librariesRoot: String): String? {
        if (!jarPath.startsWith("$librariesRoot/")) return null
        val rel = jarPath.removePrefix("$librariesRoot/")
        val parts = rel.split("/")
        if (parts.size < 4) return null
        // [group..., artifact, version, filename]
        val artifactIdx = parts.size - 3
        val artifact = parts[artifactIdx]
        val group = parts.subList(0, artifactIdx).joinToString(".")
        // ⚠️ modern Forge 예외: net.minecraftforge:forge 는 같은 GA 로 -client.jar 와
        //   -universal.jar 두 개가 존재한다. 둘은 역할이 다른 별개 아티팩트로,
        //   -client.jar 에는 패치된 net/minecraft/client/Minecraft.class 가 들어있고
        //   (ForgeProdLaunchHandler.getMinecraftPaths 가 이걸 클래스패스에서 찾음)
        //   -universal.jar 에는 Forge 자체 코드가 들어있다.
        //   classifier 를 무시하고 같은 키로 묶으면 둘 중 하나(보통 universal)만 남아
        //   Minecraft.class 를 못 찾는 크래시가 난다. → classifier 를 키에 포함해 둘 다 보존.
        val fileName = parts.last()
        if (group == "net.minecraftforge" && artifact == "forge") {
            val classifier = when {
                fileName.endsWith("-client.jar")    -> "client"
                fileName.endsWith("-universal.jar") -> "universal"
                fileName.endsWith("-shim.jar")      -> "shim"
                else -> ""
            }
            if (classifier.isNotEmpty()) return "$group:$artifact:$classifier"
        }
        return "$group:$artifact"
    }

    /**
     * JLI(java 커맨드 파서)는 "--add-opens X" 같은 두-토큰 형식을 "--add-opens=X" 로
     * 합쳐 JVM에 넘기지만, JNI_CreateJavaVM 직접 호출 경로엔 JLI 가 없다.
     * 두 토큰 그대로 들어가면 JVM 은 "--add-opens" 만 보고 값을 못 찾아 그냥 무시한다
     * (ignoreUnrecognized=JNI_TRUE 라 에러도 안 남). 그래서 여기서 미리 합쳐준다.
     *
     * 추가로 "-p" 는 JLI 전용 짧은 형이라 hotspot 자체는 못 알아먹음 → "--module-path" 로 정규화.
     */
    private val JLI_TWO_TOKEN_OPTS = setOf(
        "--add-opens", "--add-exports", "--add-reads", "--add-modules",
        "--patch-module", "--module-path", "-p", "--upgrade-module-path",
        "--limit-modules", "--module", "-m"
    )

    /**
     * Android(bionic)에서 동작 불가능한 모드를 로드에서 제외한다.
     *
     * 현재 대상: controllable (controllable-forge / controllable-sdl)
     *   - SDL2 네이티브를 jar 에 번들하는데, 그게 데스크톱 glibc 빌드라
     *     "libm.so.6 not found" 로 dlopen 실패 → onClientSetup 단계에서 크래시.
     *   - ZL2 포함 어떤 PojavLauncher 계열도 못 쓰는 데스크톱 전용 네이티브라,
     *     모바일에선 영구 제외가 맞다(복원 불필요).
     *
     * Forge 의 mods 스캐너는 .jar 확장자만 로드하므로, .jar → .jar.pingdisabled
     * 로 rename 해서 제외한다. (파일 삭제 아님 — 모드팩 무결성 보존)
     *
     * 주의: "controlling"(옵션 화면 검색 모드)은 이름이 비슷하지만 전혀 다른 모드이고
     *       네이티브가 없어 정상 동작하므로 제외 대상이 아니다.
     */
    private fun disableUnsupportedMods(modsDir: File) {
        if (!modsDir.isDirectory) return
        // 로드 불가 모드의 파일명 prefix (소문자 비교). controlling 은 제외하기 위해 '-' 포함.
        val blocked = listOf("controllable-forge", "controllable-sdl")
        modsDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name
            if (!name.endsWith(".jar", ignoreCase = true)) return@forEach
            val lower = name.lowercase()
            if (blocked.any { lower.startsWith(it) }) {
                val disabled = File(f.parentFile, "$name.pingdisabled")
                try {
                    if (disabled.exists()) disabled.delete()
                    if (f.renameTo(disabled)) {
                        Log.d("PING_LAUNCHER", "🎮 모드 로드 제외 (SDL2 미지원, Android 불가): $name")
                    } else {
                        Log.w("PING_LAUNCHER", "⚠️ 모드 제외 실패 (rename): $name")
                    }
                } catch (e: Exception) {
                    Log.w("PING_LAUNCHER", "⚠️ 모드 제외 중 오류: $name — ${e.message}")
                }
            }
        }
    }

    private fun normalizeJvmArgsForJni(
        args: Array<String>,
        freetypeLibPath: String? = null,
        jnaBootPath: String? = null,
        jnaTmpDir: String? = null,
    ): Array<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val a = args[i]
            // freetype libname 은 별도로 강제하므로, 기존에 들어온 것들은 모두 제거(ZL2 purgeArg 방식).
            // version.json / 사용자 설정이 데스크톱용 경로를 넣었을 수 있어 충돌을 막는다.
            if (freetypeLibPath != null && a.startsWith("-Dorg.lwjgl.freetype.libname")) {
                i++
                continue
            }
            // jna.boot.library.path 도 우리가 강제하므로 기존 것은 제거.
            // (JNA 는 이 경로에서 libjnidispatch.so 를 직접 로드 → 추출 실패로 인한
            //  "Could not initialize class com.sun.jna.NativeLibrary" 방지)
            if (jnaBootPath != null && a.startsWith("-Djna.boot.library.path")) {
                i++
                continue
            }
            // jna.tmpdir 는 안드로이드에서 쓰기 가능한 캐시 경로로 치환 (ZL2 방식).
            // 폴백 추출이 필요할 때 읽기 전용 경로라 실패하는 것을 막는다.
            if (jnaTmpDir != null && a.startsWith("-Djna.tmpdir")) {
                out.add("-Djna.tmpdir=$jnaTmpDir")
                i++
                continue
            }
            // 이미 "--add-opens=..." 처럼 = 가 붙어있으면 그대로
            val isBare = a in JLI_TWO_TOKEN_OPTS
            if (isBare && i + 1 < args.size) {
                val v = args[i + 1]
                val canonical = if (a == "-p") "--module-path" else a
                out.add("$canonical=$v")
                i += 2
            } else {
                out.add(a)
                i++
            }
        }
        // 제거 후 우리가 계산한 freetype 경로를 단 하나만 추가
        if (freetypeLibPath != null) {
            out.add("-Dorg.lwjgl.freetype.libname=$freetypeLibPath")
        }
        // JNA 부트 경로 강제 (단 하나만)
        if (jnaBootPath != null) {
            out.add("-Djna.boot.library.path=$jnaBootPath")
            // JNA 가 시스템 경로의 jnidispatch 를 먼저 찾도록(추출 회피). 실패 시 boot path 폴백.
            out.add("-Djna.nosys=false")
        }
        return out.toTypedArray()
    }

    /**
     * Pojav 의 patched `lwjgl-glfw-classes*.jar` 는 lwjgl 의 모든 서브패키지
     * (glfw, opengl, openal, stb, system 등) 를 한 jar 에 통합한 fat jar.
     * vanilla `lwjgl-openal-*.jar`, `lwjgl-opengl-*.jar` 등이 같이 classpath 에 있으면
     * BootstrapLauncher 가 자동 모듈로 등록하다가 split package 폭발.
     * → patched fat jar 만 남기고 나머지 vanilla lwjgl-* jar 들은 classpath 에서 제거.
     */
    private fun isRedundantLwjglJar(file: File): Boolean {
        val n = file.name
        // patched fat jar (e.g. "lwjgl-glfw-classes-3.3.1.jar") 는 무조건 keep
        if (n.startsWith("lwjgl-glfw-classes", ignoreCase = true)) return false
        // 네이티브 jar 는 어차피 안드로이드에서 못 씀 → 빼는 게 안전
        if (n.contains("natives", ignoreCase = true) && n.startsWith("lwjgl", ignoreCase = true)) return true
        // 그 외 lwjgl-3.3.1.jar, lwjgl-openal-*.jar, lwjgl-opengl-*.jar, lwjgl-stb-*.jar,
        // lwjgl-tinyfd-*.jar, lwjgl-jemalloc-*.jar, lwjgl-freetype-*.jar … 전부 redundant
        return n.startsWith("lwjgl-", ignoreCase = true) || n == "lwjgl.jar"
    }

    private fun startMinecraft() {
        val base = applicationContext.filesDir
        val nativesDir = File(base, "natives")
        val jarList = mutableListOf<String>()
        val seenGA = mutableSetOf<String>()

        val instanceBase = instanceDir?.let { File(it) }
            ?: customGameDir?.let { File(it) }
            ?: File(getExternalFilesDir(null), "instances/vanilla_$versionId")

        val isLegacy = isLegacyVersion(versionId)

        val mcDir = if (isLegacy) {
            // 레거시 MC 는 user.home/.minecraft 를 강제로 사용.
            // 인스턴스 베이스 안에 .minecraft 폴더를 만들고 거기로 user.home 을 가리키게 함.
            val legacyRoot = File(instanceBase, ".minecraft")
            legacyRoot.mkdirs()
            File(legacyRoot, "logs").mkdirs()
            File(legacyRoot, "mods").mkdirs()
            legacyRoot
        } else {
            instanceBase.also {
                it.mkdirs()
                File(it, "logs").mkdirs()
                File(it, "mods").mkdirs()
            }
        }


        Log.d("PING_LAUNCHER", "instanceBase: ${instanceBase.absolutePath}")

        // 인스턴스 메타 로드 — Fabric의 gameJvmArgs/gameArgs 가져오기
        val instanceMeta = InstanceManager.loadMeta(instanceBase)
        val isFabric = mainClass.contains("knot", ignoreCase = true)
                || mainClass.contains("fabric", ignoreCase = true)
                || instanceMeta?.loaderType == "fabric"
        Log.d("PING_LAUNCHER", "isFabric=$isFabric, loaderType=${instanceMeta?.loaderType}, mainClass=$mainClass")

        // ★ 추가 — Forge/NeoForge 의 BootstrapLauncher 경유 부팅 감지
        //   libraries 워커 / versionJar 분기에서 동시에 쓰기 위해 여기서 한 번만 계산
        val isModernLoader = (instanceMeta?.loaderType == "forge"
                || instanceMeta?.loaderType == "neoforge")
                && (mainClass.startsWith("cpw.mods")
                || mainClass.contains("BootstrapLauncher", ignoreCase = true)
                || mainClass.contains("ProcessorLauncher", ignoreCase = true)
                || mainClass.contains("net.neoforged", ignoreCase = true))
        // PojavLauncher 패치 LWJGL은 모든 MC 버전에 필요 (libglfw.so가 pojavInit 라우팅을 가정함)
        copyLwjglJars(base)
        patchLwjglGlfwIfNeeded(File(base, "lwjgl3"))
        val lwjgl3Dir = File(base, "lwjgl3")
        // 수정 — patched GLFW를 무조건 0번 인덱스에
        val lwjglJars = lwjgl3Dir.listFiles()
            ?.filter { it.extension == "jar" }
            ?.toMutableList() ?: mutableListOf()

        // patched glfw를 분리해서 맨 앞으로
        val patchedGlfw = lwjglJars.find { it.name.contains("glfw-classes") }
        lwjglJars.remove(patchedGlfw)
        lwjglJars.sortBy { it.name }

        if (patchedGlfw != null) {
            jarList.add(patchedGlfw.absolutePath)  // 0번 인덱스
            Log.d("PING_LAUNCHER", "🔧 patched GLFW 우선 주입: ${patchedGlfw.name}")
        }

        lwjglJars.forEach { jar ->
            jarList.add(jar.absolutePath)
            Log.d("PING_LAUNCHER", "🔧 LWJGL jar 주입: ${jar.name}")
        }
        val cleanedExtraJars = extraJars.filter { p ->
            val f = File(p)
            when {
                isProcessorOnlyJar(f) -> { Log.d("PING_LAUNCHER", "🚫 extraJars processor-only 제거: ${f.name}"); false }
                isRedundantLwjglJar(f) -> { Log.d("PING_LAUNCHER", "🚫 extraJars vanilla lwjgl 제거: ${f.name}"); false }
                else -> true
            }
        }
        jarList.addAll(0, cleanedExtraJars)

        val searchDirs = listOfNotNull(
            instanceBase,
            getExternalFilesDir(null),
            base
        ).distinct()

        // 이미 jarList에 들어있는 extraJars(Fabric 라이브러리)의 GA를 먼저 점유
        jarList.toList().forEach { jp ->
            for (rootCandidate in searchDirs) {
                val libRoot = File(rootCandidate, "libraries").absolutePath
                val ga = gaKey(jp, libRoot)
                if (ga != null) { seenGA.add(ga); break }
            }
        }
        searchDirs.forEach { dir ->
            val librariesDir = File(dir, "libraries")
            if (librariesDir.exists()) {
                librariesDir.walkTopDown().forEach { f ->
                    if (!f.isFile || f.extension != "jar") return@forEach
                    if (f.name.contains("natives-linux")) return@forEach

                    // ZL2 방식: NeoForge 게임 jar 는 classpath 에서 제외한다.
                    //   NeoForge 는 -DlibraryDirectory + --fml.mcVersion/neoFormVersion 좌표로
                    //   net/minecraft/client/<ver>/ (srg) 와 net/neoforged/neoforge/<ver>/ (client)
                    //   게임 jar 를 찾아 transformer module 'minecraft' 로 직접 로드한다.
                    //   이걸 classpath 에 또 넣으면 자동 모듈 'client'/'minecraft' 로 중복 생성되어
                    //   "Module minecraft contains package net.minecraft.X, module client exports
                    //    package net.minecraft.X to minecraft" module resolution 충돌이 난다.
                    //   ZL2 도 version.json libraries(게임 jar 없음) + 바닐라 clientJar 만 classpath 에 넣고
                    //   게임 jar(srg/slim/neoforge-client/universal)는 넣지 않는다.
                    if (isModernLoader) {
                        val n = f.name
                        val ap = f.absolutePath
                        val isGameJar =
                            // net/minecraft/client/<ver>/ 아래 srg/slim/extra
                            (ap.contains("/net/minecraft/")
                                    && (n.contains("-srg.") || n.contains("-slim.")
                                    || n.contains("-srg-and-extra.") || n.contains("-extra.")))
                                    // net/neoforged/neoforge/<ver>/ 아래 client/universal (게임 클래스 포함)
                                    || (ap.contains("/net/neoforged/neoforge/")
                                    && (n.endsWith("-client.jar") || n.endsWith("-universal.jar")))
                        if (isGameJar) {
                            Log.d("PING_LAUNCHER", "🚫 NeoForge 게임 jar classpath 제외 (좌표로 자동 로드, ZL2 방식): ${f.name}")
                            return@forEach
                        }
                    }

                    if (isProcessorOnlyJar(f)) {
                        Log.d("PING_LAUNCHER", "🚫 processor-only jar 제외: ${f.name}")
                        return@forEach
                    }

                    if (jarList.contains(f.absolutePath)) return@forEach
                    if (isProcessorOnlyJar(f)) {
                        Log.d("PING_LAUNCHER", "🚫 processor-only jar 제외: ${f.name}")
                        return@forEach
                    }
                    if (isRedundantLwjglJar(f)) {                                  // ★ 추가
                        Log.d("PING_LAUNCHER", "🚫 vanilla lwjgl jar 제외 (patched fat 만 keep): ${f.name}")
                        return@forEach
                    }

                    // 마인크래프트 번들 LWJGL은 PojavLauncher 패치 버전과 충돌하므로 제외
                    // PojavLauncher 패치 GLFW만 제외. core/opengl/openal 등 다른 LWJGL 모듈은
                    // 1.14 번들 그대로 쓰는 게 호환성 안전.
                    val lowerName = f.name.lowercase()

                    // 변경 → glfw-classes 동명 클래스 충돌 방지를 위해 lwjgl-glfw-*만 제외
                    val lwjglGlfwPattern = Regex("^lwjgl-glfw-\\d.*\\.jar$")
                    if (lwjglGlfwPattern.matches(lowerName)) {
                        Log.d("PING_LAUNCHER", "번들 lwjgl-glfw 제외 (PojavLauncher patched 사용): ${f.name}")
                        return@forEach
                    }

                    val ga = gaKey(f.absolutePath, librariesDir.absolutePath)
                    if (ga != null && seenGA.contains(ga)) {
                        Log.d("PING_LAUNCHER", "중복 라이브러리 스킵: $ga (${f.name})")
                        return@forEach
                    }
                    if (ga != null) seenGA.add(ga)
                    jarList.add(f.absolutePath)
                }
            }

            val legacyDir = File(dir, "libraries_$versionId")
            if (legacyDir.exists()) {
                legacyDir.walkTopDown().forEach { f ->
                    if (!f.isFile || f.extension != "jar") return@forEach
                    if (f.name.contains("natives-linux")) return@forEach
                    if (jarList.contains(f.absolutePath)) return@forEach

                    val lowerName = f.name.lowercase()
                    // 변경
                    val lwjglGlfwPattern = Regex("^lwjgl-glfw-\\d.*\\.jar$")
                    if (lwjglGlfwPattern.matches(lowerName)) {
                        Log.d("PING_LAUNCHER", "번들 lwjgl-glfw 제외 (PojavLauncher patched 사용): ${f.name}")
                        return@forEach
                    }

                    val ga = gaKey(f.absolutePath, legacyDir.absolutePath)
                    if (ga != null && seenGA.contains(ga)) {
                        Log.d("PING_LAUNCHER", "중복 라이브러리 스킵: $ga (${f.name})")
                        return@forEach
                    }
                    if (ga != null) seenGA.add(ga)
                    jarList.add(f.absolutePath)
                }
            }
        }

        if (mainClass.contains("launchwrapper")) {
            patchLaunchwrapperIfNeeded(searchDirs)
        }

        val versionJar = searchDirs
            .map { File(it, "versions/$versionId/$versionId.jar") }
            .firstOrNull { it.exists() }

        versionJar?.let {
            // ZL2 방식: 바닐라 client jar 를 NeoForge 에서도 classpath 에 포함한다.
            //   ZL2 generateLaunchClassPath 는 clientJar(= inheritsFrom 의 바닐라 <ver>.jar)를
            //   항상 classpath 에 추가한다(if clientJar.exists() classpathList.add). 바닐라 jar 는
            //   난독화돼 net.minecraft.* 가 없으므로 deobf 게임 jar 와 패키지 충돌도 없다.
            if (!jarList.contains(it.absolutePath)) {
                jarList.add(it.absolutePath)
                Log.d("PING_LAUNCHER", "✅ 바닐라 client jar classpath 포함 (ZL2 방식): ${it.name}")
            }
        }

        val assetsDir = searchDirs
            .map { File(it, "assets") }
            .firstOrNull { File(it, "indexes").exists() && File(it, "indexes").listFiles()?.isNotEmpty() == true }
            ?: File(getExternalFilesDir(null) ?: base, "assets")

        val irisConfig = File(mcDir, "config/iris.properties")
        if (!irisConfig.exists()) {                       // ← 이미 존재하면 손대지 않음 (지금도 이 조건은 있음)
            irisConfig.parentFile?.mkdirs()
            irisConfig.writeText("shaders.enabled=false\n")
        }


        // ★ versionId 전달
        val libJvmPath = MinecraftJREPreparer.prepareJreAndGetPath(this, versionId)
        val jvmSettings = JvmSettingsManager.load(this)

        // 모드 개수 — 첫 실행 시 모드팩이 무거우면 렌더 설정을 강제로 낮추기 위함.
        // disableUnsupportedMods 로 .pingdisabled 처리된 것도 실제 로드되진 않지만,
        // 메모리/로딩 부담은 "설치된 모드 수" 기준으로 보는 게 안전하므로 .jar / .jar.pingdisabled 둘 다 센다.
        val installedModCount = countInstalledMods(File(mcDir, "mods"))
        syncOptionsTxt(File(mcDir, "options.txt"), jvmSettings, installedModCount)
        // Forge/NeoForge early loading window(망치/여우 로딩 화면) 설정.
        // ZL2 와 동일하게 켜는 게 기본. 단, 모드팩(특히 Sinytra Connector 포함)은 early window 의
        //   acceptGameLayer → DisplayWindow.updateModuleReads 단계에서 게임 클래스
        //   (net.minecraft.client.gui.screens.LoadingOverlay)를 읽지 못해 NoClassDefFoundError 로
        //   크래시한다. 복잡한 모듈 구성 때문이므로 모드팩 인스턴스는 early window 를 끈다.
        //   (일반 Forge/NeoForge 는 켜서 망치/여우 애니메이션 유지)
        // (앱이 자기 외부 디렉토리 파일을 쓰는 것이라 권한 문제 없음)
        val enableEarlyWindow = instanceMeta?.type != InstanceType.MODPACK
        if (!enableEarlyWindow) {
            Log.d("PING_LAUNCHER", "🪟 모드팩 인스턴스 — early window 비활성화(Connector 모듈 호환)")
        }
        syncFmlConfig(File(mcDir, "config/fml.toml"), true)

// ★ JDK 9+ 전용 플래그는 javaMajor>=9 일 때만 부착
        val isModularJre = javaMajor >= 9

// ★ JDK 8 에서는 미지원 옵션을 무시하도록 (G1NewSizePercent 등이 문제)
        val jvm8CompatArgs: Array<String> =
            if (!isModularJre) arrayOf("-XX:+IgnoreUnrecognizedVMOptions") else emptyArray()

        val launchWrapperArgs = if (isModularJre && mainClass.contains("launchwrapper")) {
            arrayOf(
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens", "java.base/java.io=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.loader=ALL-UNNAMED",
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.zip=ALL-UNNAMED",
            )
        } else emptyArray()

        val fabricJvmArgs = if (isModularJre && isFabric) {
            arrayOf(
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens", "java.base/java.io=ALL-UNNAMED",
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.zip=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.loader=ALL-UNNAMED",
            )
        } else emptyArray()



// ── ${library_directory} 등 placeholder 해석 ──
        val forgeLibrariesDir = File(instanceBase, "libraries")
        val metaJvmArgsRaw = (instanceMeta?.gameJvmArgs ?: emptyList())
            .map { raw ->
                raw
                    .replace("\${library_directory}",   forgeLibrariesDir.absolutePath)
                    .replace("\${libraries_directory}", forgeLibrariesDir.absolutePath)
                    .replace("\${classpath_separator}", File.pathSeparator)
                    .replace("\${version_name}",        versionId)
                    .replace("\${natives_directory}",   nativesDir.absolutePath)
            }

// ── BootstrapLauncher 의 -DignoreList 에 LWJGL 들 추가 ──
//   PojavLauncher patched lwjgl-glfw-classes.jar 는 다른 LWJGL 서브모듈(openal, opengl 등)
//   클래스도 통합 포함되어 있어서 자동 모듈로 잡히면 split package 충돌 발생.
//   ignoreList 에 prefix 매칭시키면 classpath unnamed module 로 남아 충돌 회피.
        val metaJvmArgs: Array<String> = metaJvmArgsRaw.map { arg ->
            if (isModernLoader && arg.startsWith("-DignoreList=")) {
                // 이미 들어있는지 확인 후 없으면 추가.
                //   ⚠️ ZL2 방식: 게임 jar(-srg/-slim/-srg-and-extra)는 ignoreList 에 넣지 않는다.
                //     게임 jar 를 classpath 에 포함시켜 NeoForge 가 minecraft 모듈로 만들어야 하는데,
                //     ignoreList 에 넣으면 module 변환에서 빠져 net.minecraft.* 가 다시 누락된다.
                //     프로세서 전용 jar(installertools/ForgeAutoRenamingTool 등)만 제외 유지.
                val needed = listOf(
                    "ForgeAutoRenamingTool", "BinaryPatcher", "binarypatcher",
                    "jarsplitter", "installertools", "vignette", "DiffPatch", "diffpatch",
                    "commons-collections4",
                    // 모드가 shade 한 라이브러리와 classpath 라이브러리의 split-package 충돌 회피:
                    //   KryptonReforged / AgeOfWeapons 등이 org.checkerframework.framework.qual 를
                    //   자기 jar 에 포함(shade)하는데, classpath 의 checker-qual.jar(=checker.qual 모듈)도
                    //   같은 패키지를 export 해 "Modules krypton and checker.qual export package
                    //   org.checkerframework.framework.qual" module resolution 충돌이 난다.
                    //   ignoreList 는 classpath jar 에만 적용되므로 checker-qual 을 넣으면 named module
                    //   이 안 되고(unnamed classpath 로 남음) 모드 shade 본만 모듈로 남아 충돌 해소.
                    "checker-qual"
                ).filterNot { arg.contains(",$it") || arg.endsWith("=$it") }

                if (needed.isNotEmpty()) {
                    val patched = arg + "," + needed.joinToString(",")
                    Log.d("PING_LAUNCHER", "🩹 ignoreList 보강: +${needed.joinToString(",")}")
                    patched
                } else arg
            } else arg
        }.toTypedArray()

// ── Modern Forge fallback: 모듈 안 로드돼도 reflection 통과시키는 ALL-UNNAMED opens ──
        val modernForgeArgs: Array<String> = if (isModularJre && isModernLoader) {
            arrayOf(
                // ── Forge early window(망치/여우 로딩 창) ──
                // 켜고 끄는 것은 syncFmlConfig() 의 ENABLE_EARLY_WINDOW 토글이 fml.toml 로 단일 제어.
                // 여기서 -Dfml.earlyWindowControl 을 강제하지 않아야 fml.toml 설정이 그대로 적용된다.
                // (과거엔 OSMesa 크래시 회피용으로 여기서 false 를 강제했으나, ZL2 와 동일하게
                //  GLFW stub 이 GL 컨텍스트 버전을 렌더러에 맞춰 고정하므로 더는 필요 없음.)
                "-Djava.awt.headless=true",
                "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.io=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-exports", "java.base/sun.security.util=ALL-UNNAMED",
            )
        } else emptyArray()

        Log.d("PING_LAUNCHER",
            "isModernForge=$isModernLoader metaJvmArgs(resolved)=${metaJvmArgs.toList()}")

        var renderer = RendererManager.load(this)

//        if (renderer.id == "zink" && !RendererProbe.nativeZinkCompatible()) {
//            Log.w("PING_LAUNCHER", "⚠️ 이 기기는 Zink 미호환 — Holy GL4ES로 자동 폴백")
//            runOnUiThread {
//                Toast.makeText(
//                    this,
//                    "이 기기의 GPU는 Zink 미지원 — GL4ES로 전환합니다",
//                    Toast.LENGTH_LONG
//                ).show()
//            }
//            renderer = Renderer.fromId("gl4es")  // 또는 "gl4es" — 둘 중 가용한 것
//        }


        val glLibName = when (renderer.id) {
            "mobileglues" -> "libmobileglues.so"
            "gl4es", "gl4es_desktop" -> "libgl4es_114.so"
            "zink" -> "libOSMesa.so"
            else   -> "libgl4es_114.so"
        }

        Log.i("PingLauncherJVM", "🎨 Selected glLibName=$glLibName (renderer=${renderer.id})")

        // ── classpath 중복 제거 ─────────────────────────────────────
        val seenAbs = HashSet<String>()
        val seenFileName = HashSet<String>()
        val originalSize = jarList.size
        var dedupedJars = jarList.filter { abs ->
            if (!seenAbs.add(abs)) {
                Log.d("PING_LAUNCHER", "🗑 절대경로 중복 jar 제거: $abs")
                return@filter false
            }
            val fname = File(abs).name
            if (!seenFileName.add(fname)) {
                Log.d("PING_LAUNCHER", "🗑 동일 파일명 jar 중복 제거: $fname")
                return@filter false
            }
            true
        }

        if (isModernLoader) {
            val moduleJarsFromMp = mutableSetOf<String>()
            var i = 0
            while (i < metaJvmArgs.size) {
                val a = metaJvmArgs[i]
                val mpValue: String? = when {
                    a.startsWith("--module-path=") -> a.removePrefix("--module-path=")
                    a == "--module-path" || a == "-p" -> metaJvmArgs.getOrNull(i + 1)
                    else -> null
                }
                if (mpValue != null) {
                    mpValue.split(File.pathSeparator)
                        .map { File(it).name }
                        .filter { it.isNotBlank() }
                        .forEach { moduleJarsFromMp.add(it) }
                }
                i++
            }
            Log.d("PING_LAUNCHER", "🎯 module-path jars (${moduleJarsFromMp.size}): $moduleJarsFromMp")

            val before = dedupedJars.size
            dedupedJars = dedupedJars.filter { abs ->
                val name = File(abs).name
                when {
                    name in moduleJarsFromMp -> {
                        Log.d("PING_LAUNCHER", "🚫 module-path 에 있어 classpath 제외: $name")
                        false
                    }
                    // processor-launcher 분기 자체를 삭제 — 이 jar 는 mainClass 일 때 classpath 필수
                    else -> true
                }
            }
            Log.d("PING_LAUNCHER", "📦 modern Forge classpath 정리: $before → ${dedupedJars.size}")
        }

        if (dedupedJars.size != originalSize) {
            Log.d("PING_LAUNCHER", "📦 classpath dedupe total: $originalSize → ${dedupedJars.size}")
        }
        val classPathStr = dedupedJars.joinToString(File.pathSeparator)


        val dnsArgs = arrayOf(
            "-Djava.net.preferIPv4Stack=true",          // ★ 추가 — IPv6 시도 자체를 막음
            "-Djava.net.preferIPv4Addresses=true",      // ★ 추가 (보강)
            // JNDI DNS 공급자 강제 설정 (SRV 조회 해결의 핵심)
            "-Djava.naming.provider.url=${DnsHookNative.getActiveDnsServers().joinToString(" ") { "dns://$it" }}",
            "-Dnetworkaddress.cache.ttl=0",
            "-Dnetworkaddress.cache.negative.ttl=0",
            "-Dsun.net.inetaddr.ttl=0",
            "-Dsun.net.inetaddr.negative.ttl=0"
        )

        // ── JNI 디버그 인자 (간헐적 Scudo 힙 손상 원인 추적용) ──
        // -Xcheck:jni 로 추적한 결과 JNI 는 무죄였고, 원인은 String Deduplication(GC)로 확인됨.
        // 추적이 끝났으므로 false. 다시 JNI 디버깅이 필요하면 true 로.
        val ENABLE_JNI_CHECK = false
        val jniDebugArgs = if (ENABLE_JNI_CHECK)
            arrayOf("-Xcheck:jni")
        else
            emptyArray()

        // ── LWJGL FreeType 네이티브 강제 지정 (ZL2 방식) ──
        // 게임이 LWJGL 3.3.6 으로 도는데 .lwjgl/3.3.6/ 추출 디렉터리에 네이티브가
        // prepopulate 되지 않으면 libfreetype.so 를 못 찾아 "Default font failed to load" 로 크래시한다.
        // 버전별 추출 디렉터리에 의존하지 않도록, 앱에 포함된 arm64 libfreetype.so 의
        // 절대경로를 LWJGL 에 직접 지정한다. (실제 인자 주입/중복 제거는 normalizeJvmArgsForJni 에서 수행)
        val freetypeSo = File(applicationInfo.nativeLibraryDir, "libfreetype.so")
        if (freetypeSo.exists()) {
            Log.d("PING_LAUNCHER", "🔤 freetype 강제 지정: ${freetypeSo.absolutePath}")
        } else {
            Log.w("PING_LAUNCHER", "⚠️ libfreetype.so 가 nativeLibraryDir 에 없음 — 폰트 로딩 실패 가능")
        }

        val jvmArgs = jvm8CompatArgs +
                jniDebugArgs +
                jvmSettings.toJvmArgArray(
                    context = this,
                    mcDir = mcDir,
                    userDir = mcDir.absolutePath,
                    classPath = classPathStr,
                    libraryPath = nativesDir.absolutePath,
                    mainClass = mainClass,
                    versionId = versionId
                ) +
                dnsArgs +
                launchWrapperArgs +
                fabricJvmArgs +
                modernForgeArgs +     // ★ 추가 — metaJvmArgs 보다 앞에 둬서 version.json 인자가 덮어쓰도록
                metaJvmArgs

        Log.d("PING_LAUNCHER", "버전: $versionId, mcDir: ${mcDir.absolutePath}, isFabric=$isFabric, javaMajor=$javaMajor")

        Log.d("PING_LAUNCHER", "═══ classpath 항목 ${dedupedJars.size}개 ═══")
        dedupedJars.forEachIndexed { i, p -> Log.d("PING_LAUNCHER", "  [$i] ${File(p).name}") }

        // SDL2 기반 모드(controllable)는 데스크톱 glibc 네이티브(libm.so.6 의존)를 번들해
        // Android(bionic)에서 로드 불가 → 부팅 직전 .jar 확장자를 바꿔 로드에서 제외한다.
        disableUnsupportedMods(File(mcDir, "mods"))

        Log.d("PING_LAUNCHER", "═══ mods/ 폴더 ═══")
        File(mcDir, "mods").listFiles()?.forEach {
            Log.d("PING_LAUNCHER", "  ${it.name} (${it.length()}B)")
        }

        Thread {
            try {
                val session = MicrosoftAuthManager.loadSession(this)
                val validSession = if (session != null && !MicrosoftAuthManager.isSessionValid(session)) {
                    try {
                        MicrosoftAuthManager.refreshSession(session.refreshToken)
                            .also { MicrosoftAuthManager.saveSession(this, it) }
                    } catch (_: Exception) { session }
                } else session

                val username    = validSession?.username    ?: "Player"
                val uuid        = validSession?.uuid        ?: "00000000-0000-0000-0000-000000000000"
                val accessToken = validSession?.accessToken ?: "0"
                val userType    = if (validSession != null) "msa" else "mojang"

                // 매니페스트가 minecraftArguments(=공백 구분 단일 문자열)를 줬다면 그게 1.12 이하 레거시 포맷이다.
                // gameArgs 안에 ${...} placeholder가 있다는 사실 자체가 그 시그널.
                val legacyArgs = instanceMeta?.gameArgs.orEmpty()
                val isLegacyArgs = legacyArgs.any { it.contains("\${") }

                val mcArgs: Array<String> = if (isLegacyArgs) {
                    // ── 1.12 이하: placeholder 치환만 해서 그대로 사용 ──
                    val placeholders = mapOf(
                        "\${auth_player_name}"  to username,
                        "\${auth_session}"      to "token:$accessToken:$uuid", // 1.5.x 시절 단일 토큰 포맷
                        "\${auth_uuid}"         to uuid,
                        "\${auth_access_token}" to accessToken,
                        "\${version_name}"      to versionId,
                        "\${game_directory}"    to mcDir.absolutePath,
                        "\${game_assets}"       to assetsDir.absolutePath,    // pre-1.6 legacy assets
                        "\${assets_root}"       to assetsDir.absolutePath,
                        "\${assets_index_name}" to assetIndex,
                        "\${user_type}"         to userType,
                        "\${version_type}"      to if (isFabric) "Fabric" else "release",
                        "\${user_properties}"   to "{}",
                        "\${profile_name}"      to username,
                        "\${launcher_name}"     to "PingLauncher",
                        "\${launcher_version}"  to "1.0"
                    )
                    val resolved = legacyArgs.map { arg ->
                        placeholders.entries.fold(arg) { acc, (k, v) -> acc.replace(k, v) }
                    }
                    Log.d("PING_LAUNCHER", "legacy mcArgs (${resolved.size}): $resolved")
                    resolved.toTypedArray()
                } else {
                    // ── 1.13+ (또는 Fabric/모드팩): 기존 하드코딩 + 메타 추가 인자 ──
                    val baseMcArgs = arrayOf(
                        "--username",   username,
                        "--version",    versionId,
                        "--gameDir",    mcDir.absolutePath,
                        "--assetsDir",  assetsDir.absolutePath,
                        "--assetIndex", assetIndex,
                        "--uuid",       uuid,
                        "--accessToken",accessToken,
                        "--userType",   userType,
                        "--versionType",if (isFabric) "Fabric" else "release"
                    )
                    val metaGameArgs = legacyArgs.toTypedArray() // placeholder 없는 추가 인자만 들어옴
                    baseMcArgs + metaGameArgs
                }

                val launcher = JavaNativeLauncher()
                val rendererEnv = renderer.buildEnv(
                    cacheDir = applicationContext.cacheDir.absolutePath,
                    nativeDir = applicationInfo.nativeLibraryDir
                ).toMutableMap().apply {
                    if (jvmSettings.unlockFps) {
                        this["FORCE_VSYNC"]       = "false"
                        this["POJAV_VSYNC"]       = "0"
                        this["LIBGL_VSYNC"]       = "0"     // GL4ES 계열
                        this["POJAV_VSYNC_IN_ZINK"] = "0"   // Zink/OSMesa 경로 (swap_interval_no_egl.c)
                    }
                }

                Log.d("PING_LAUNCHER", "🎨 적용된 렌더러: ${renderer.displayName}")
                rendererEnv.forEach { (k, v) -> Log.d("PING_LAUNCHER", "  env $k=$v") }
                launcher.applyEnv(rendererEnv)

                // JNA 네이티브(libjnidispatch.so) 강제 지정 (ZL2 방식).
                // jniLibs 에 번들된 arm64 libjnidispatch.so 가 nativeLibraryDir 에 있으므로,
                // 그 경로를 jna.boot.library.path 로 지정해 추출 없이 직접 로드하게 한다.
                val jnaDispatch = File(applicationInfo.nativeLibraryDir, "libjnidispatch.so")
                val jnaBootPath = if (jnaDispatch.exists()) {
                    Log.d("PING_LAUNCHER", "🧩 JNA 부트 경로: ${applicationInfo.nativeLibraryDir}")
                    applicationInfo.nativeLibraryDir
                } else {
                    Log.w("PING_LAUNCHER", "⚠️ libjnidispatch.so 가 nativeLibraryDir 에 없음 — JNA 모드 크래시 가능")
                    null
                }

                val normalizedJvmArgs = normalizeJvmArgsForJni(
                    jvmArgs,
                    freetypeLibPath = if (freetypeSo.exists()) freetypeSo.absolutePath else null,
                    jnaBootPath = jnaBootPath,
                    jnaTmpDir = cacheDir.absolutePath,
                )

                Log.d("PING_LAUNCHER", "정규화 후 JVM 인자 ${normalizedJvmArgs.size}개")
                normalizedJvmArgs.forEachIndexed { idx, a ->
                    if (a.startsWith("--add-") || a.startsWith("--module-path") || a.startsWith("--patch-module")) {
                        Log.d("PING_LAUNCHER", "  [$idx] $a")
                    }
                }

                launcher.bootMinecraftJVM(libJvmPath, normalizedJvmArgs, mcArgs)
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "MC 실행 예외: ${e.message}")
            } finally {
                val crashDir = File(instanceBase, "crash-reports")
                val files = crashDir.listFiles()
                val hasCrash = files
                    ?.any { it.extension == "txt" &&
                            System.currentTimeMillis() - it.lastModified() < 60_000 } == true
                if (hasCrash) {
                    runOnUiThread {
                        finish()
                        CrashReportActivity.start(this, instanceBase.absolutePath)
                    }
                }
            }
        }.start()

        Thread {
            Log.d("PING_LAUNCHER", "🔵 showingWindow 워치독 시작")
            val deadline = System.currentTimeMillis() + 120_000
            var attempts = 0
            var success = false
            // 초기에는 50ms 간격, 한 번 잡으면 5초 간격으로 재확인
            var interval = 50L

            while (System.currentTimeMillis() < deadline) {
                attempts++
                try {
                    if (nativeTrySetupShowingWindow()) {
                        if (!success) {
                            Log.d("PING_LAUNCHER", "✅ showingWindow 첫 세팅 (시도 $attempts, 경과 ${attempts * 50}ms 이내)")
                            success = true
                            interval = 5000L   // 잡힌 뒤엔 느슨하게
                        }
                    } else if (attempts % 40 == 0 && !success) {
                        Log.d("PING_LAUNCHER", "🔵 대기중... (시도 $attempts)")
                    }
                } catch (e: Throwable) {
                    Log.w("PING_LAUNCHER", "워치독 예외: ${e.message}")
                }
                Thread.sleep(interval)
            }
            Log.d("PING_LAUNCHER", "🔵 워치독 종료 (success=$success)")
        }.apply { isDaemon = true; start() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 마우스 소스면 우클릭, 아니면 ESC
            if (event.source and InputDevice.SOURCE_MOUSE != 0) {
                sendMouseButton(1, GLFW_PRESS)
            } else {
                sendKey(256, GLFW_PRESS) // ESC
            }
            return true
        }
        val glfwKey = androidKeyToGlfw(keyCode) ?: return false
        sendKey(glfwKey, GLFW_PRESS)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            sendKey(256, GLFW_RELEASE)
            return true
        }
        val glfwKey = androidKeyToGlfw(keyCode) ?: return false
        sendKey(glfwKey, GLFW_RELEASE)
        return true
    }


    private fun installPhysicalKeyboardInterceptor() {
        val originalCallback = window.callback
        window.callback = object : android.view.Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (handlePhysicalKey(event)) return true
                return originalCallback.dispatchKeyEvent(event)
            }
        }
    }

    private fun handlePhysicalKey(event: KeyEvent): Boolean {
        Log.d("PING_LAUNCHER",
            "🔑 handlePhysicalKey: keyCode=${event.keyCode} " +
                    "action=${event.action} src=0x${event.source.toString(16)}")

        // JVM 부팅 전엔 무시
        if (!jvmStarted) return false

        if (!jvmStarted) return false

        // ★ ACTION_MULTIPLE 은 IME 가 합성한 이벤트 — 절대 가로채지 말 것
        if (event.action == KeyEvent.ACTION_MULTIPLE) return false

        // ★ IME 가 처리해야 하는 키 — 한글 전환, 한자 등
        when (event.keyCode) {
            KeyEvent.KEYCODE_LANGUAGE_SWITCH,   // 한영 키
            KeyEvent.KEYCODE_KANA,
            KeyEvent.KEYCODE_HENKAN,
            KeyEvent.KEYCODE_MUHENKAN,
            KeyEvent.KEYCODE_EISU -> return false
        }

        val hasKeyboardSource =
            (event.source and InputDevice.SOURCE_KEYBOARD) ==
                    InputDevice.SOURCE_KEYBOARD

        if (!hasKeyboardSource) return false

        // 물리 키보드 / 외장 키보드만 가로채기
        // (소프트 IME 는 deviceId == -1 또는 KeyCharacterMap.VIRTUAL_KEYBOARD)
        val isPhysical =
            (event.source and InputDevice.SOURCE_KEYBOARD) != 0 &&
                    event.deviceId != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD

        if (!isPhysical) return false

        // 시스템 키는 패스 (볼륨, 전원, 홈 등)
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME -> return false
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN
        val glfwAction = if (isDown) 1 else 0

        // 1) 특수키 우선 매핑 (androidKeyToGlfw 가 못 잡는 것들)
        val specialKey = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL          -> 259  // Backspace
            KeyEvent.KEYCODE_FORWARD_DEL  -> 261  // Delete
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 257
            KeyEvent.KEYCODE_DPAD_LEFT    -> 263
            KeyEvent.KEYCODE_DPAD_RIGHT   -> 262
            KeyEvent.KEYCODE_DPAD_UP      -> 265
            KeyEvent.KEYCODE_DPAD_DOWN    -> 264
            else -> null
        }

        if (specialKey != null) {
            sendKey(specialKey, glfwAction)
            return true
        }

        // 2) 일반 키 → GLFW 매핑
        // 일반 키 → GLFW 매핑
        val glfwKey = androidKeyToGlfw(event.keyCode)
        if (glfwKey != null) {
            sendKey(glfwKey, glfwAction)

            if (isDown) {
                var unicodeChar = event.getUnicodeChar(event.metaState)

                // ★ fallback: unicodeChar 가 0 이지만 우리가 아는 키면 직접 매핑
                if (unicodeChar == 0) {
                    unicodeChar = when (event.keyCode) {
                        KeyEvent.KEYCODE_SPACE -> ' '.code
                        KeyEvent.KEYCODE_TAB   -> '\t'.code
                        else -> 0
                    }
                }

                if (unicodeChar != 0) {
                    val glfwMods = (
                            (if (event.isShiftPressed) 0x0001 else 0) or
                                    (if (event.isCtrlPressed)  0x0002 else 0) or
                                    (if (event.isAltPressed)   0x0004 else 0)
                            )
                    sendCharToMc(unicodeChar.toChar(), glfwMods)
                }
            }
            return true
        }

        // 4) GLFW 매핑은 없지만 unicodeChar 가 있으면 (예: 한글, 특수문자)
        //    채팅창용으로 문자만 송신
        if (isDown) {
            val unicodeChar = event.getUnicodeChar(event.metaState)
            if (unicodeChar != 0) {
                sendCharToMc(unicodeChar.toChar())
                return true
            }
        }

        return false
    }

    private fun sendCharToMc(c: Char, mods: Int = 0) {
        Log.d("PING_LAUNCHER", "📝 sendCharToMc: '$c' (0x${c.code.toString(16)}) mods=$mods")
        try {
            val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")

            // 1) Char 콜백 (1.12 이하 + 일부 모드용)
            cb.getMethod("nativeSendChar", Char::class.java).invoke(null, c)

            // 2) CharMods 콜백 (1.13+ MC 본체용)
            cb.getMethod("nativeSendCharMods", Char::class.java, Int::class.java)
                .invoke(null, c, mods)
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "📝 sendChar 예외", e)
        }
    }


    private fun isProcessorOnlyJar(file: File): Boolean {
        val name = file.name
        // mergetool 은 두 종류 —
        //   • distmarker(Dist/OnlyIn) 등 "게임 부팅 필수" 클래스를 담은 api jar → 보존(false)
        //   • 설치 단계에서만 쓰는 processor jar → 제외(true)
        // 파일명 규칙이 로더/버전마다 다르다:
        //   Forge:    mergetool-api-1.0.jar     (api 가 앞)
        //   NeoForge: mergetool-2.0.0-api.jar   (api 가 뒤, 버전이 중간)
        // 따라서 "mergetool" 로 시작하면서 이름에 'api' 가 들어가면 부팅 필수 jar 로 보고 보존한다.
        if (name.startsWith("mergetool", ignoreCase = true)
            && name.contains("api", ignoreCase = true)) return false
        return PROCESSOR_ONLY_JAR_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    /**
     * mods 폴더에 설치된 모드 개수.
     * Forge 스캐너가 로드하는 .jar 뿐 아니라, 우리가 호환성 때문에 비활성화한
     * .jar.pingdisabled 도 함께 센다(설치된 총량 기준으로 무게를 판단).
     * 하위 폴더(예: 1.16 이하의 일부 구조)는 보지 않는다 — 표준 mods/ 평면 구조만.
     */
    private fun countInstalledMods(modsDir: File): Int {
        if (!modsDir.isDirectory) return 0
        return modsDir.listFiles()?.count { f ->
            f.isFile && (
                    f.name.endsWith(".jar", ignoreCase = true) ||
                            f.name.endsWith(".jar.pingdisabled", ignoreCase = true)
                    )
        } ?: 0
    }

    /**
     * 모드 개수에 따른 성능 등급. 첫 실행 시 options.txt 강제 하향에 사용.
     * 임계값은 arm64 모바일(Mali + Zink/OSMesa) 기준 보수적으로 잡음.
     */
    private enum class PerfTier { VANILLA, LIGHT, MEDIUM, HEAVY, EXTREME }

    private fun computePerfTier(modCount: Int): PerfTier = when {
        modCount <= 0   -> PerfTier.VANILLA   // 바닐라/데이터팩 — 강제 하향 안 함
        modCount < 30   -> PerfTier.LIGHT     // 가벼운 모드 몇 개
        modCount < 80   -> PerfTier.MEDIUM    // 중형 모드팩
        modCount < 150  -> PerfTier.HEAVY     // 대형 모드팩
        else            -> PerfTier.EXTREME   // 초대형(150+) — 최소 옵션
    }

    /**
     * 성능 등급별 강제 옵션값.
     * 첫 실행에서만 적용하며(아래 syncOptionsTxt 참고), 이후엔 사용자가 게임 내에서
     * 올린 값을 보존한다. VANILLA 는 null 을 반환해 강제 하향을 건너뛴다.
     *
     * 키 의미:
     *  - renderDistance     : 청크 렌더 거리 (가장 무거운 항목)
     *  - simulationDistance  : 시뮬레이션 거리 (엔티티/틱 부하) — 1.18+
     *  - graphicsMode        : 0=Fast, 1=Fancy, 2=Fabulous
     *  - entityDistanceScaling: 엔티티 렌더 비율(0.5~5.0)
     *  - particles           : 0=All, 1=Decreased, 2=Minimal
     *  - ao                  : 0=Off, 1=Min, 2=Max (Ambient Occlusion)
     *  - biomeBlendRadius     : 바이옴 경계 블렌딩 반경(0=꺼짐)
     */
    private fun perfFloorOptions(tier: PerfTier): Map<String, String>? = when (tier) {
        PerfTier.VANILLA -> null
        PerfTier.LIGHT -> mapOf(
            "renderDistance" to "8",
            "simulationDistance" to "6",
            "graphicsMode" to "1",
            "entityDistanceScaling" to "0.75",
            "particles" to "1",
            "biomeBlendRadius" to "1",
        )
        PerfTier.MEDIUM -> mapOf(
            "renderDistance" to "6",
            "simulationDistance" to "5",
            "graphicsMode" to "0",
            "entityDistanceScaling" to "0.75",
            "particles" to "1",
            "ao" to "1",
            "biomeBlendRadius" to "1",
            "renderClouds" to "false",
        )
        PerfTier.HEAVY -> mapOf(
            "renderDistance" to "5",
            "simulationDistance" to "4",
            "graphicsMode" to "0",
            "entityDistanceScaling" to "0.5",
            "particles" to "2",
            "ao" to "0",
            "biomeBlendRadius" to "0",
            "renderClouds" to "false",
            "mobSpawning" to "true",
        )
        PerfTier.EXTREME -> mapOf(
            "renderDistance" to "4",
            "simulationDistance" to "4",
            "graphicsMode" to "0",
            "entityDistanceScaling" to "0.5",
            "particles" to "2",
            "ao" to "0",
            "biomeBlendRadius" to "0",
            "renderClouds" to "false",
            "fancyGraphics" to "false",   // 일부 레거시 버전은 graphicsMode 대신 이 키 사용
        )
    }

    /**
     * options.txt 동기화.
     *
     *  1) 항상 강제: maxFps / enableVsync / mipmapLevels(=0, Mali+Zink 크래시 회피).
     *  2) 첫 실행 + 모드팩이 무거우면(PerfTier ≥ LIGHT) 렌더/시뮬레이션/기타 렌더 옵션을
     *     성능 등급에 맞춰 강제로 낮춘다.
     *     - "첫 실행" 판정: options.txt 와 같은 폴더의 마커 파일(.ping_perf_applied) 부재.
     *       한 번 적용하면 마커를 남겨, 이후엔 사용자가 게임 내에서 올린 값을 덮어쓰지 않는다.
     *     - 단, 사용자가 직접 정한 기본값(settings.renderDistance/graphicsMode)이 성능 등급
     *       상한보다 낮다면 그 낮은 값을 존중한다(= min 으로 합침). 절대 사용자보다 높이지 않음.
     *  3) 그 외(키 바인딩/볼륨 등)는 사용자 변경을 보존.
     */
    private fun syncOptionsTxt(optionsFile: File, settings: JvmSettings, modCount: Int = 0) {
        val targetMaxFps   = if (settings.unlockFps) 260 else 120
        val targetVsync    = if (settings.unlockFps) "false" else "true"
        val targetRenderD  = settings.renderDistance
        val targetGfxMode  = settings.graphicsMode

        val existing: MutableList<String> = if (optionsFile.exists())
            optionsFile.readLines().toMutableList()
        else
            mutableListOf()

        fun upsert(key: String, value: String) {
            val idx = existing.indexOfFirst { it.startsWith("$key:") }
            val line = "$key:$value"
            if (idx >= 0) existing[idx] = line else existing.add(line)
        }

        fun currentInt(key: String): Int? =
            existing.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter("$key:")?.trim()?.toIntOrNull()

        upsert("maxFps",         targetMaxFps.toString())
        upsert("enableVsync",    targetVsync)
        upsert("renderDistance", targetRenderD.toString())
        upsert("graphicsMode",   targetGfxMode.toString())
        upsert("renderClouds",   "false")
        // ⚠️ mipmapLevels 는 반드시 0 으로 강제.
        //   밉맵이 1 이상이면 blocks 텍스처 아틀라스가 2048x2048x4 같은 밉맵 포함 형태로 생성되는데,
        //   Mali-G57 + Zink(OSMesa) 조합에서 이 밉맵 아틀라스 처리 중 libGLES_mali 가
        //   힙을 손상시켜 Scudo "corrupted chunk header" 로 강제 종료됨(렌더 스레드).
        //   밉맵 0 이면 아틀라스가 ...x0 으로 생성되어 크래시가 사라짐.
        upsert("mipmapLevels",   "0")

        // ── 첫 실행 + 무거운 모드팩 → 렌더 설정 강제 하향 ──
        val tier = computePerfTier(modCount)
        val marker = File(optionsFile.parentFile, ".ping_perf_applied")
        val isFirstLaunch = !marker.exists()

        if (isFirstLaunch && tier != PerfTier.VANILLA) {
            val floor = perfFloorOptions(tier)
            if (floor != null) {
                floor.forEach { (key, value) ->
                    // 정수형 렌더 옵션은 "사용자/기존 값이 더 낮으면 그 값 유지"(절대 높이지 않음).
                    val flInt = value.toIntOrNull()
                    if (flInt != null) {
                        val cur = currentInt(key)
                        val applied = if (cur != null) minOf(cur, flInt) else flInt
                        upsert(key, applied.toString())
                    } else {
                        // 불리언/문자열 옵션(renderClouds, fancyGraphics 등)은 그대로 강제.
                        upsert(key, value)
                    }
                }
                // 한 번만 적용되도록 마커 남김.
                runCatching {
                    marker.parentFile?.mkdirs()
                    marker.writeText("tier=$tier\nmodCount=$modCount\n")
                }
                Log.d("PING_LAUNCHER",
                    "⚙️ 첫 실행 성능 하향 적용: tier=$tier modCount=$modCount floor=$floor")
            }
        } else if (tier != PerfTier.VANILLA) {
            Log.d("PING_LAUNCHER",
                "⚙️ 성능 하향 건너뜀(이미 적용됨): tier=$tier modCount=$modCount")
        }

        // 핫바 영역 계산용 — options.txt 의 guiScale 을 읽어둔다(없으면 0=자동).
        mcGuiScale = existing.firstOrNull { it.startsWith("guiScale:") }
            ?.substringAfter("guiScale:")?.trim()?.toIntOrNull() ?: 0

        optionsFile.writeText(existing.joinToString("\n"))
        Log.d("PING_LAUNCHER",
            "📝 options.txt sync: maxFps=$targetMaxFps vsync=$targetVsync " +
                    "renderDist=${currentInt("renderDistance")} simDist=${currentInt("simulationDistance")} " +
                    "tier=$tier mipmap=0")
    }

    /**
     * Forge/NeoForge 의 config/fml.toml 에서 early loading window(망치/여우 애니메이션) 설정.
     *
     * ZL2 는 fml.toml 을 건드리지 않고 기본값(earlyWindowProvider=fmlearlywindow,
     * earlyWindowControl=true)으로 early window 를 켠 채 구동한다. 그 비결은 GLFW stub
     * (lwjgl-glfw-classes.jar)이 early window 가 요청하는 GL 컨텍스트 버전을 렌더러에 맞게
     * 고정(vulkan_zink→4.6, 기본→3.3)해 주는 것. 우리도 같은 PojavLauncher 계열 stub +
     * Zink(MESA_GL_VERSION_OVERRIDE=4.6) 설정이라 동일하게 켤 수 있다.
     *
     * ENABLE_EARLY_WINDOW = true  → early window 켬 (ZL2 와 동일, 망치/여우 표시)
     *                       false → 끔(provider=none). Mali+OSMesa 에서 별도 스레드 GL
     *                               컨텍스트가 SIGSEGV/Scudo 로 죽으면 이걸로 즉시 되돌린다.
     *
     * 파일이 없으면(첫 실행 등) 해당 줄만 가진 fml.toml 을 새로 만든다. Forge 가 이후
     * 나머지 기본값을 자기 형식으로 채워 넣는다. 앱이 자기 파일을 쓰는 것이라
     * adb push 때와 달리 권한(AccessDenied) 문제가 없다.
     */
    private fun syncFmlConfig(fmlToml: File, enableEarlyWindow: Boolean = true) {
        // ★ early window 토글. 호출부에서 인스턴스 종류에 따라 결정해 넘긴다.
        //   (모드팩/Connector 인스턴스는 early window 의 acceptGameLayer 단계에서
        //    게임 클래스(LoadingOverlay 등) 로드에 실패하므로 OFF 로 넘어온다)
        val ENABLE_EARLY_WINDOW = enableEarlyWindow
        try {
            fmlToml.parentFile?.mkdirs()

            val lines: MutableList<String> = if (fmlToml.exists())
                fmlToml.readLines().toMutableList()
            else
                mutableListOf()

            // TOML 은 "key = value" 형식. 기존 줄(주석/공백 들여쓰기 포함)을 키로 찾아 교체.
            fun upsertToml(key: String, valueLiteral: String) {
                val idx = lines.indexOfFirst { it.trimStart().startsWith("$key ") || it.trimStart().startsWith("$key=") }
                val newLine = "$key = $valueLiteral"
                if (idx >= 0) lines[idx] = newLine else lines.add(newLine)
            }

            if (ENABLE_EARLY_WINDOW) {
                upsertToml("earlyWindowProvider", "\"fmlearlywindow\"")
                upsertToml("earlyWindowControl", "true")
            } else {
                upsertToml("earlyWindowProvider", "\"none\"")
                upsertToml("earlyWindowControl", "false")
            }

            fmlToml.writeText(lines.joinToString("\n"))
            Log.d("PING_LAUNCHER",
                "🪟 fml.toml sync: earlyWindow=${if (ENABLE_EARLY_WINDOW) "ON(fmlearlywindow)" else "OFF(none)"} (${fmlToml.absolutePath})")
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "fml.toml 수정 실패: ${e.message}", e)
        }
    }

    fun androidKeyToGlfw(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_W -> GLFW_KEY_W
        KeyEvent.KEYCODE_A -> GLFW_KEY_A
        KeyEvent.KEYCODE_S -> GLFW_KEY_S
        KeyEvent.KEYCODE_D -> GLFW_KEY_D
        KeyEvent.KEYCODE_E -> GLFW_KEY_E
        KeyEvent.KEYCODE_SPACE -> GLFW_KEY_SPACE
        KeyEvent.KEYCODE_SHIFT_LEFT -> GLFW_KEY_LEFT_SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> GLFW_KEY_LEFT_CONTROL
        KeyEvent.KEYCODE_Q -> 81
        KeyEvent.KEYCODE_F -> 70
        KeyEvent.KEYCODE_R -> 82
        KeyEvent.KEYCODE_T -> 84
        KeyEvent.KEYCODE_ESCAPE -> GLFW_KEY_ESCAPE
        KeyEvent.KEYCODE_ENTER -> GLFW_KEY_ENTER
        KeyEvent.KEYCODE_TAB -> GLFW_KEY_TAB
        KeyEvent.KEYCODE_1 -> 49
        KeyEvent.KEYCODE_2 -> 50
        KeyEvent.KEYCODE_3 -> 51
        KeyEvent.KEYCODE_4 -> 52
        KeyEvent.KEYCODE_5 -> 53
        KeyEvent.KEYCODE_6 -> 54
        KeyEvent.KEYCODE_7 -> 55
        KeyEvent.KEYCODE_8 -> 56
        KeyEvent.KEYCODE_9 -> 57
        KeyEvent.KEYCODE_SLASH       -> 47   // /
        KeyEvent.KEYCODE_PERIOD      -> 46   // .
        KeyEvent.KEYCODE_COMMA       -> 44   // ,
        KeyEvent.KEYCODE_MINUS       -> 45   // -
        KeyEvent.KEYCODE_EQUALS      -> 61   // =
        KeyEvent.KEYCODE_SEMICOLON   -> 59   // ;
        KeyEvent.KEYCODE_APOSTROPHE  -> 39   // '
        KeyEvent.KEYCODE_LEFT_BRACKET  -> 91  // [
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 93  // ]
        KeyEvent.KEYCODE_BACKSLASH   -> 92   // \
        KeyEvent.KEYCODE_GRAVE       -> 96   // `
        else -> null
    }

    private fun copyLwjglJars(base: File) {
        val targetDir = File(base, "lwjgl3").apply { mkdirs() }
        try {
            val jarNames = assets.list("lwjgl3") ?: return
            for (jarName in jarNames) {
                if (!jarName.endsWith(".jar")) continue
                val target = File(targetDir, jarName)
                if (target.exists() && target.length() > 0) continue
                assets.open("lwjgl3/$jarName").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d("PING_LAUNCHER", "📦 LWJGL jar 추출: $jarName (${target.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "LWJGL jar 추출 실패", e)
        }
    }


    override fun onResume() {
        super.onResume()
        window.decorView.findViewWithTag<View>("minecraft_surface")
            ?.let { it.requestFocus() }
            ?: window.decorView.requestFocus()

        Log.d("PING_LAUNCHER", "onResume — surface 재바인딩 대기")
    }

    override fun onPause() {
        super.onPause()
        if (jvmStarted && isGrabbing) {
            sendKey(256, GLFW_PRESS)    // ESC 누름
            sendKey(256, GLFW_RELEASE)
        }
    }

    override fun onDestroy() {
        currentInstance = null
        // ★ 첫 프레임 리스너 해제 (Activity 누수 방지)
        MinecraftActivityBridge.setFirstFrameListener(null)
        // ★ 리스너 해제
        inputDeviceListener?.let { listener ->
            try {
                val im = getSystemService(INPUT_SERVICE)
                        as android.hardware.input.InputManager
                im.unregisterInputDeviceListener(listener)
            } catch (_: Throwable) {}
        }
        inputDeviceListener = null
        gameControllerView = null

        currentInstance = null
        super.onDestroy()
    }
}