package kr.co.donghyun.pinglauncher.presentation

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Surface
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kr.co.donghyun.pinglauncher.data.auth.MicrosoftAuthManager
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.data.jvm.JvmSettings
import kr.co.donghyun.pinglauncher.data.jvm.JvmSettingsManager
import kr.co.donghyun.pinglauncher.data.jvm.isLegacyVersion
import kr.co.donghyun.pinglauncher.data.renderer.Renderer
import kr.co.donghyun.pinglauncher.data.renderer.RendererManager
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.components.GameControllerView
import kr.co.donghyun.pinglauncher.presentation.ui.components.MinecraftSurface
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PingLauncherTheme
import kr.co.donghyun.pinglauncher.presentation.util.jni.JavaNativeLauncher
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.MinecraftJREPreparer
import kr.co.donghyun.pinglauncher.presentation.util.renderer.VirGLLauncher
import org.lwjgl.glfw.GLFW.GLFW_KEY_A
import org.lwjgl.glfw.GLFW.GLFW_KEY_D
import org.lwjgl.glfw.GLFW.GLFW_KEY_E
import org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
import org.lwjgl.glfw.GLFW.GLFW_KEY_TAB
import org.lwjgl.glfw.GLFW.GLFW_KEY_W
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import java.io.File


class MinecraftActivity : BaseActivity() {

    private external fun nativeIsGrabbing(): Boolean
    private external fun nativeSetupBridgeWindow(surface: Surface)

    // Intent로 전달받은 버전 정보
    private lateinit var versionId: String
    private lateinit var assetIndex: String
    private lateinit var extraJars: List<String>
    private lateinit var mainClass: String
    internal var instanceDir: String? = null
    private var customGameDir: String? = null
    private var currentSurface: Surface? = null
    @Volatile var combatMode: Boolean = false

    private val PROCESSOR_ONLY_JAR_PREFIXES = listOf(
        "ForgeAutoRenamingTool",
        "BinaryPatcher", "binarypatcher",
        "jarsplitter",
        "installertools",
        "vignette",
        "DiffPatch", "diffpatch",
        "mergetool"   // ※ 부팅에 필요한 mergetool-*-api.jar 는 보존 필요 — 아래 헬퍼에서 별도 처리
    )

    internal val isGrabbing: Boolean
        get() = try { nativeIsGrabbing() } catch (_: Exception) { false }

    private var jvmStarted = false
    private var javaMajor: Int = 21


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


    override fun onCreated() {
        currentInstance = this

        versionId = intent.getStringExtra(EXTRA_VERSION_ID) ?: "1.16.2"
        assetIndex = intent.getStringExtra(EXTRA_ASSET_INDEX) ?: "1.16"
        extraJars = intent.getStringArrayListExtra(EXTRA_EXTRA_JARS) ?: emptyList()
        mainClass = intent.getStringExtra(EXTRA_MAIN_CLASS) ?: "net.minecraft.client.main.Main"
        instanceDir = intent.getStringExtra(EXTRA_INSTANCE_DIR)
        Log.d("PING_LAUNCHER", "instanceDir 수신: $instanceDir")  // ← 추가
        customGameDir = intent.getStringExtra(EXTRA_GAME_DIR)
        Log.d("PING_LAUNCHER", "customGameDir 수신: $customGameDir")  // ← 추가

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
                }
            }
        }

        val controllerView = GameControllerView(this)
        addContentView(
            controllerView,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
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

        // ── 렌더러별 .so 먼저 로드 (preloadAwtStubs 이전에) ───────────────
        // preloadAwtStubs 같은 JNI 바인딩 함수에서 실패가 나더라도,
        // 핵심 .so 들은 이미 메모리에 올라와 있어야 JVM 부팅이 가능하다.
        val renderer = RendererManager.load(this)
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
            "ltw" -> {
                try {
                    System.loadLibrary("GLESv2")
                    Log.d("PING_LAUNCHER", "✅ libGLESv2.so preloaded for LTW")
                } catch (e: Throwable) {
                    Log.w("PING_LAUNCHER", "⚠️ libGLESv2.so preload 실패: ${e.message}")
                }
                Log.d("PING_LAUNCHER", "✅ 렌더러: LTW (LWJGL 측에서 자체 로드)")
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

    private fun resolveForgePlaceholders(
        raw: String,
        librariesDir: File,
        nativesDir: File,
        versionId: String
    ): String = raw
        .replace("\${library_directory}",   librariesDir.absolutePath)
        .replace("\${libraries_directory}", librariesDir.absolutePath)   // 표기 둘 다 본 적 있음
        .replace("\${classpath_separator}", File.pathSeparator)
        .replace("\${version_name}",        versionId)
        .replace("\${natives_directory}",   nativesDir.absolutePath)


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
        val zipIn = java.util.zip.ZipFile(lwJar)
        val patchedJar = File(lwJar.parent, lwJar.name + ".tmp")
        val zipOut = java.util.zip.ZipOutputStream(patchedJar.outputStream())

        zipIn.entries().asSequence().forEach { entry ->
            val bytes = zipIn.getInputStream(entry).readBytes()
            val patched = if (entry.name == "net/minecraft/launchwrapper/Launch.class") {
                patchLaunchClass(bytes)
            } else bytes
            zipOut.putNextEntry(java.util.zip.ZipEntry(entry.name))
            zipOut.write(patched)
            zipOut.closeEntry()
        }

        zipIn.close()
        zipOut.close()
        lwJar.delete()
        patchedJar.renameTo(lwJar)
    }

    private fun patchLaunchClass(bytes: ByteArray): ByteArray {
        val reader = org.objectweb.asm.ClassReader(bytes)
        val writer = org.objectweb.asm.ClassWriter(reader, org.objectweb.asm.ClassWriter.COMPUTE_FRAMES)

        val visitor = object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name == "<init>" && descriptor == "()V") {
                    return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, mv) {
                        override fun visitTypeInsn(opcode: Int, type: String) {
                            if (opcode == org.objectweb.asm.Opcodes.CHECKCAST && type == "java/net/URLClassLoader") {
                                visitInsn(org.objectweb.asm.Opcodes.POP)
                                visitLdcInsn("java.class.path")
                                visitLdcInsn("")
                                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false)
                                visitLdcInsn(File.pathSeparator)
                                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", false)
                                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "net/minecraft/launchwrapper/Launch", "pingStringsToUrls", "([Ljava/lang/String;)[Ljava/net/URL;", false)
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
                    org.objectweb.asm.Opcodes.ACC_PRIVATE or org.objectweb.asm.Opcodes.ACC_STATIC,
                    "pingStringsToUrls", "([Ljava/lang/String;)[Ljava/net/URL;", null, null
                )
                mv.visitCode()
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                mv.visitInsn(org.objectweb.asm.Opcodes.ARRAYLENGTH)
                mv.visitTypeInsn(org.objectweb.asm.Opcodes.ANEWARRAY, "java/net/URL")
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ASTORE, 1)
                mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_0)
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ISTORE, 2)
                val loopStart = org.objectweb.asm.Label()
                val loopEnd = org.objectweb.asm.Label()
                mv.visitLabel(loopStart)
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 2)
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                mv.visitInsn(org.objectweb.asm.Opcodes.ARRAYLENGTH)
                mv.visitJumpInsn(org.objectweb.asm.Opcodes.IF_ICMPGE, loopEnd)
                val tryStart = org.objectweb.asm.Label()
                val tryEnd = org.objectweb.asm.Label()
                val catchBlock = org.objectweb.asm.Label()
                mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Exception")
                mv.visitLabel(tryStart)
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 2)
                mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "java/io/File")
                mv.visitInsn(org.objectweb.asm.Opcodes.DUP)
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 2)
                mv.visitInsn(org.objectweb.asm.Opcodes.AALOAD)
                mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)
                mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/io/File", "toURI", "()Ljava/net/URI;", false)
                mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/net/URI", "toURL", "()Ljava/net/URL;", false)
                mv.visitInsn(org.objectweb.asm.Opcodes.AASTORE)
                mv.visitLabel(tryEnd)
                mv.visitIincInsn(2, 1)
                mv.visitJumpInsn(org.objectweb.asm.Opcodes.GOTO, loopStart)
                mv.visitLabel(catchBlock)
                mv.visitInsn(org.objectweb.asm.Opcodes.POP)
                mv.visitIincInsn(2, 1)
                mv.visitJumpInsn(org.objectweb.asm.Opcodes.GOTO, loopStart)
                mv.visitLabel(loopEnd)
                mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
                mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN)
                mv.visitMaxs(5, 3)
                mv.visitEnd()
                super.visitEnd()
            }
        }
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }
    internal fun sendMouseButton(button: Int, action: Int) {
        try {
            Class.forName("org.lwjgl.glfw.CallbackBridge")
                .getMethod("nativeSendMouseButton", Int::class.java, Int::class.java, Int::class.java)
                .invoke(null, button, action, 0)
        } catch (_: Exception) {}
    }

    internal fun sendKey(glfwKeyCode: Int, action: Int) {
        Log.d("PING_LAUNCHER", "sendKey 호출: $glfwKeyCode, action=$action")
        try {
            val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
            val scancode = getScancode(glfwKeyCode)
            cb.getMethod("nativeSendKey", Int::class.java, Int::class.java, Int::class.java, Int::class.java)
                .invoke(null, glfwKeyCode, scancode, action, 0)
            if (action == 1) {
                val keyChar = glfwKeyToChar(glfwKeyCode)
                if (keyChar != '\u0000') {
                    cb.getMethod("nativeSendChar", Char::class.java).invoke(null, keyChar)
                }
            }
        } catch (_: Exception) {}
    }

    private fun glfwToAndroidKey(glfwKey: Int): Int? = when (glfwKey) {
        65 -> android.view.KeyEvent.KEYCODE_A
        66 -> android.view.KeyEvent.KEYCODE_B
        67 -> android.view.KeyEvent.KEYCODE_C
        68 -> android.view.KeyEvent.KEYCODE_D
        69 -> android.view.KeyEvent.KEYCODE_E
        70 -> android.view.KeyEvent.KEYCODE_F
        71 -> android.view.KeyEvent.KEYCODE_G
        72 -> android.view.KeyEvent.KEYCODE_H
        73 -> android.view.KeyEvent.KEYCODE_I
        74 -> android.view.KeyEvent.KEYCODE_J
        75 -> android.view.KeyEvent.KEYCODE_K
        76 -> android.view.KeyEvent.KEYCODE_L
        77 -> android.view.KeyEvent.KEYCODE_M
        78 -> android.view.KeyEvent.KEYCODE_N
        79 -> android.view.KeyEvent.KEYCODE_O
        80 -> android.view.KeyEvent.KEYCODE_P
        81 -> android.view.KeyEvent.KEYCODE_Q
        82 -> android.view.KeyEvent.KEYCODE_R
        83 -> android.view.KeyEvent.KEYCODE_S
        84 -> android.view.KeyEvent.KEYCODE_T
        85 -> android.view.KeyEvent.KEYCODE_U
        86 -> android.view.KeyEvent.KEYCODE_V
        87 -> android.view.KeyEvent.KEYCODE_W
        88 -> android.view.KeyEvent.KEYCODE_X
        89 -> android.view.KeyEvent.KEYCODE_Y
        90 -> android.view.KeyEvent.KEYCODE_Z
        32 -> android.view.KeyEvent.KEYCODE_SPACE
        256 -> android.view.KeyEvent.KEYCODE_ESCAPE
        257 -> android.view.KeyEvent.KEYCODE_ENTER
        258 -> android.view.KeyEvent.KEYCODE_TAB
        else -> null
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

    private fun normalizeJvmArgsForJni(args: Array<String>): Array<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val a = args[i]
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

        // PojavLauncher 패치 LWJGL은 모든 MC 버전에 필요 (libglfw.so가 pojavInit 라우팅을 가정함)
        copyLwjglJars(base)
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
            if (!jarList.contains(it.absolutePath)) jarList.add(it.absolutePath)
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

        syncOptionsTxt(File(mcDir, "options.txt"), jvmSettings)

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


// ── Modern Forge / NeoForge 감지 (1.17+) ──
        val isModernForge = (instanceMeta?.loaderType == "forge" || instanceMeta?.loaderType == "neoforge")
                && (mainClass.startsWith("cpw.mods")
                || mainClass.contains("BootstrapLauncher", ignoreCase = true)
                || mainClass.contains("ProcessorLauncher", ignoreCase = true))   // ← 추가

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
            if (isModernForge && arg.startsWith("-DignoreList=")) {
                // 이미 들어있는지 확인 후 없으면 추가. lwjgl 한 prefix 로 lwjgl-glfw-classes,
                // lwjgl-openal, lwjgl-opengl, lwjgl-stb 등 한 번에 커버.
                val needed = listOf(
                    "ForgeAutoRenamingTool", "BinaryPatcher", "binarypatcher",
                    "jarsplitter", "installertools", "vignette", "DiffPatch", "diffpatch"
                ).filterNot { arg.contains(",$it") || arg.endsWith("=$it") }

                if (needed.isNotEmpty()) {
                    val patched = arg + "," + needed.joinToString(",")
                    Log.d("PING_LAUNCHER", "🩹 ignoreList 보강: +${needed.joinToString(",")}")
                    patched
                } else arg
            } else arg
        }.toTypedArray()

// ── Modern Forge fallback: 모듈 안 로드돼도 reflection 통과시키는 ALL-UNNAMED opens ──
        val modernForgeArgs: Array<String> = if (isModularJre && isModernForge) {
            arrayOf(
                "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.io=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-exports", "java.base/sun.security.util=ALL-UNNAMED",
                "--add-exports", "jdk.naming.dns/com.sun.jndi.dns=java.naming",
            )
        } else emptyArray()

        Log.d("PING_LAUNCHER",
            "isModernForge=$isModernForge metaJvmArgs(resolved)=${metaJvmArgs.toList()}")

        val rendererPreference = RendererManager.load(this@MinecraftActivity)
        // MinecraftActivity.startMinecraft 안에서

        Log.d("PING_LAUNCHER", "isLegacy=$isLegacy, mcDir=${mcDir.absolutePath}")

        // Legacy MC 는 fixed-function GL 필요 — MobileGlues 로 불가
        val renderer = if (isLegacy && rendererPreference.id == "mobileglues") {
            Log.w("PingLauncherJVM", "Legacy $versionId — forcing GL4ES instead of MobileGlues")
            Renderer.fromId("holy_gl4es")  // 또는 "gl4es" / "gl4es_desktop" 중 enum 에 있는 것
        } else {
            rendererPreference
        }

        val glLibName = when (renderer.id) {
            "mobileglues" -> "libmobileglues.so"
            "gl4es", "gl4es_desktop", "holy_gl4es" -> "libgl4es_114.so"
            "zink" -> "libOSMesa.so"
            "ltw"  -> "libltw.so"     // ★ 추가
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

        if (isModernForge) {
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

        val jvmArgs = jvm8CompatArgs +
                jvmSettings.toJvmArgArray(
                    context = this,
                    mcDir = mcDir,
                    userDir = mcDir.absolutePath,
                    classPath = classPathStr,
                    libraryPath = nativesDir.absolutePath,
                    mainClass = mainClass,
                    versionId = versionId
                ) +
                launchWrapperArgs +
                fabricJvmArgs +
                modernForgeArgs +     // ★ 추가 — metaJvmArgs 보다 앞에 둬서 version.json 인자가 덮어쓰도록
                metaJvmArgs

        Log.d("PING_LAUNCHER", "버전: $versionId, mcDir: ${mcDir.absolutePath}, isFabric=$isFabric, javaMajor=$javaMajor")

        Log.d("PING_LAUNCHER", "═══ classpath 항목 ${dedupedJars.size}개 ═══")
        dedupedJars.forEachIndexed { i, p -> Log.d("PING_LAUNCHER", "  [$i] ${File(p).name}") }
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
                val renderer = RendererManager.load(this@MinecraftActivity)
                val rendererEnv = renderer.buildEnv(
                    cacheDir = applicationContext.cacheDir.absolutePath,
                    nativeDir = applicationInfo.nativeLibraryDir
                ).toMutableMap().apply {
                    if (renderer.id == "virgl") {
                        this["VTEST_SOCKET_NAME"] =
                            VirGLLauncher.socketPath(this@MinecraftActivity)
                    }

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

                val normalizedJvmArgs = normalizeJvmArgsForJni(jvmArgs)

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
                    runOnUiThread { CrashReportActivity.start(this, instanceBase.absolutePath) }
                }
            }
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            // 마우스 소스면 우클릭, 아니면 ESC
            if (event.source and android.view.InputDevice.SOURCE_MOUSE != 0) {
                sendMouseButton(1, GLFW_PRESS)
            } else {
                sendKey(256, GLFW_PRESS) // ESC
            }
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_SPACE) {
            sendKey(GLFW_KEY_SPACE, GLFW_PRESS)
            return true
        }
        val glfwKey = androidKeyToGlfw(keyCode) ?: return false
        sendKey(glfwKey, GLFW_PRESS)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            sendKey(256, GLFW_RELEASE)
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_SPACE) {
            sendKey(GLFW_KEY_SPACE, GLFW_RELEASE)
            return true
        }
        val glfwKey = androidKeyToGlfw(keyCode) ?: return false
        sendKey(glfwKey, GLFW_RELEASE)
        return true
    }


    private fun isProcessorOnlyJar(file: File): Boolean {
        val name = file.name
        // mergetool 은 두 종류 — "*-api.jar" 는 게임 부팅에도 필요하니 보존
        if (name.startsWith("mergetool", ignoreCase = true) && name.endsWith("-api.jar")) return false
        return PROCESSOR_ONLY_JAR_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    /**
     * options.txt 의 maxFps / enableVsync 만 강제로 우리 설정에 맞춰 덮어쓴다.
     * 나머지 옵션(키 바인딩, 볼륨 등) 은 사용자 변경을 보존.
     */
    private fun syncOptionsTxt(optionsFile: File, settings: JvmSettings) {
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

        upsert("maxFps",         targetMaxFps.toString())
        upsert("enableVsync",    targetVsync)
        upsert("renderDistance", targetRenderD.toString())
        upsert("graphicsMode",   targetGfxMode.toString())
        upsert("renderClouds",   "false")

        optionsFile.writeText(existing.joinToString("\n"))
        Log.d("PING_LAUNCHER",
            "📝 options.txt sync: maxFps=$targetMaxFps vsync=$targetVsync renderDist=$targetRenderD")
    }

    fun androidKeyToGlfw(keyCode: Int): Int? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_W -> GLFW_KEY_W
        android.view.KeyEvent.KEYCODE_A -> GLFW_KEY_A
        android.view.KeyEvent.KEYCODE_S -> GLFW_KEY_S
        android.view.KeyEvent.KEYCODE_D -> GLFW_KEY_D
        android.view.KeyEvent.KEYCODE_E -> GLFW_KEY_E
        android.view.KeyEvent.KEYCODE_SPACE -> GLFW_KEY_SPACE
        android.view.KeyEvent.KEYCODE_SHIFT_LEFT -> GLFW_KEY_LEFT_SHIFT
        android.view.KeyEvent.KEYCODE_CTRL_LEFT -> GLFW_KEY_LEFT_CONTROL
        android.view.KeyEvent.KEYCODE_Q -> 81
        android.view.KeyEvent.KEYCODE_F -> 70
        android.view.KeyEvent.KEYCODE_R -> 82
        android.view.KeyEvent.KEYCODE_T -> 84
        android.view.KeyEvent.KEYCODE_ESCAPE -> GLFW_KEY_ESCAPE
        android.view.KeyEvent.KEYCODE_ENTER -> GLFW_KEY_ENTER
        android.view.KeyEvent.KEYCODE_TAB -> GLFW_KEY_TAB
        android.view.KeyEvent.KEYCODE_1 -> 49
        android.view.KeyEvent.KEYCODE_2 -> 50
        android.view.KeyEvent.KEYCODE_3 -> 51
        android.view.KeyEvent.KEYCODE_4 -> 52
        android.view.KeyEvent.KEYCODE_5 -> 53
        android.view.KeyEvent.KEYCODE_6 -> 54
        android.view.KeyEvent.KEYCODE_7 -> 55
        android.view.KeyEvent.KEYCODE_8 -> 56
        android.view.KeyEvent.KEYCODE_9 -> 57
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
        window.decorView.findViewWithTag<android.view.View>("minecraft_surface")
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
        super.onDestroy()
    }
}