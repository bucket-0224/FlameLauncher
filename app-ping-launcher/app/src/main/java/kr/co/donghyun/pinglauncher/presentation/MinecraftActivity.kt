package kr.co.donghyun.pinglauncher.presentation

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kr.co.donghyun.pinglauncher.data.auth.MicrosoftAuthManager
import kr.co.donghyun.pinglauncher.data.jvm.JvmSettingsManager
import kr.co.donghyun.pinglauncher.data.jvm.isLegacyVersion
import kr.co.donghyun.pinglauncher.data.renderer.Renderer
import kr.co.donghyun.pinglauncher.data.renderer.RendererManager
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.components.GameControllerView
import kr.co.donghyun.pinglauncher.presentation.ui.components.MinecraftSurface
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PingLauncherTheme
import kr.co.donghyun.pinglauncher.presentation.util.MinecraftActivityBridge
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.MinecraftJREPreparer
import kr.co.donghyun.pinglauncher.presentation.util.jni.JavaNativeLauncher
import org.lwjgl.glfw.GLFW.*
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

        try {
            System.loadLibrary("pingjvm")

            // 기존 jre lib 복사 블록은 그대로 두기
            val jreLibDir = MinecraftJREPreparer.findJreLibDir(this, versionId)
            if (jreLibDir != null && jreLibDir.exists()) {
                listOf("libawt_xawt.so", "libawt_headless.so", "libpojavexec_awt.so").forEach { soName ->
                    val src = File(nativesDir, soName)
                    val dst = File(jreLibDir, soName)
                    if (src.exists() && !dst.exists()) {
                        src.copyTo(dst, overwrite = true)
                        dst.setExecutable(true, false)
                    }
                }
            }

            // ── 렌더러별 .so 우선 로딩 ──────────────────────────
            val renderer = RendererManager.load(this)
            when (renderer.id) {
                "mobileglues" -> {
                    // info_getter 가 먼저! libmobileglues.so 의 의존성임
                    val ig = File(nativesDir, "libmobileglues_info_getter.so")
                    val mg = File(nativesDir, "libmobileglues.so")
                    if (ig.exists()) System.load(ig.absolutePath)
                    if (mg.exists()) {
                        System.load(mg.absolutePath)
                        Log.d("PING_LAUNCHER", "✅ 렌더러: MobileGlues")
                    } else {
                        Log.w("PING_LAUNCHER", "⚠️ libmobileglues.so 없음 — RendererManager 선택만 됐고 .so 미배포")
                    }
                }
                "zink" -> {
                    try { System.loadLibrary("vulkan") } catch (_: Throwable) {}
                    System.load(File(nativesDir, "libOSMesa.so").absolutePath)
                    Log.d("PING_LAUNCHER", "✅ 렌더러: Zink")
                }
                else -> {
                    // gl4es / gl4es_desktop
                    System.load(File(nativesDir, "libgl4es_114.so").absolutePath)
                    Log.d("PING_LAUNCHER", "✅ 렌더러: GL4ES")
                }
            }

            // 공통 .so
            System.load(File(nativesDir, "libopenal.so").absolutePath)
            System.load(File(nativesDir, "libglfw.so").absolutePath)
            System.load(File(nativesDir, "libpojavexec.so").absolutePath)
            System.load(File(nativesDir, "liblwjgl.so").absolutePath)
            System.load(File(nativesDir, "liblwjgl_opengl.so").absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            Log.w("PING_LAUNCHER", "일부 .so 이미 로드됨: ${e.message}")
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
                                visitLdcInsn(java.io.File.pathSeparator)
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


    private fun startMinecraft() {
        val base = applicationContext.filesDir
        val nativesDir = File(base, "natives")
        val jarList = mutableListOf<String>()
        val seenGA = mutableSetOf<String>()

        val instanceBase = instanceDir?.let { File(it) }
            ?: customGameDir?.let { File(it) }
            ?: File(getExternalFilesDir(null), "instances/vanilla_$versionId")

        val mcDir = instanceBase.also {
            it.mkdirs()
            File(it, "logs").mkdirs()
            File(it, "mods").mkdirs()
        }

        Log.d("PING_LAUNCHER", "instanceBase: ${instanceBase.absolutePath}")

        // 인스턴스 메타 로드 — Fabric의 gameJvmArgs/gameArgs 가져오기
        val instanceMeta = kr.co.donghyun.pinglauncher.data.instance.InstanceManager.loadMeta(instanceBase)
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
        jarList.addAll(0, extraJars)

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
        if (!irisConfig.exists()) {
            irisConfig.parentFile?.mkdirs()
            irisConfig.writeText("shaders.enabled=false\n")
        }

        val optionsFile = File(mcDir, "options.txt")
        if (!optionsFile.exists()) {
            val jvmSettings = JvmSettingsManager.load(this)
            optionsFile.writeText("""
            renderClouds:false
            renderDistance:${jvmSettings.renderDistance}
            graphicsMode:${jvmSettings.graphicsMode}
        """.trimIndent())
        }

        // ★ versionId 전달
        val libJvmPath = MinecraftJREPreparer.prepareJreAndGetPath(this, versionId)
        val jvmSettings = JvmSettingsManager.load(this)

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

        val metaJvmArgs = instanceMeta?.gameJvmArgs?.toTypedArray() ?: emptyArray()

        val rendererPreference = RendererManager.load(this@MinecraftActivity)
        val isLegacy = isLegacyVersion(versionId)

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
            else -> "libgl4es_114.so"
        }

        Log.i("PingLauncherJVM", "🎨 Selected glLibName=$glLibName (renderer=${renderer.id})")

        val jvmArgs = jvm8CompatArgs + jvmSettings.toJvmArgArray(
            context = this,
            userDir = mcDir.absolutePath,
            classPath = jarList.joinToString(":"),
            libraryPath = nativesDir.absolutePath,
            mainClass = mainClass,
            versionId = versionId
        ) + launchWrapperArgs + fabricJvmArgs + metaJvmArgs

        Log.d("PING_LAUNCHER", "버전: $versionId, mcDir: ${mcDir.absolutePath}, isFabric=$isFabric, javaMajor=$javaMajor")

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
                )
                Log.d("PING_LAUNCHER", "🎨 적용된 렌더러: ${renderer.displayName}")
                rendererEnv.forEach { (k, v) -> Log.d("PING_LAUNCHER", "  env $k=$v") }
                launcher.applyEnv(rendererEnv)

                launcher.bootMinecraftJVM(libJvmPath, jvmArgs, mcArgs)
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
                val target = java.io.File(targetDir, jarName)
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
        window.decorView.requestFocus()
    }

    override fun onDestroy() {
        currentInstance = null
        super.onDestroy()
    }
}