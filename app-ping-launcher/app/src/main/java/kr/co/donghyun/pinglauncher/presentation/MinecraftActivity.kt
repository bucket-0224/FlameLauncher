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
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.components.GameControllerOverlay
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

    companion object {
        private const val EXTRA_VERSION_ID = "version_id"
        private const val EXTRA_ASSET_INDEX = "asset_index"
        private const val EXTRA_EXTRA_JARS = "extra_jars"
        private const val EXTRA_MAIN_CLASS = "main_class"
        private const val EXTRA_GAME_DIR = "game_dir"
        private const val EXTRA_INSTANCE_DIR = "instance_dir"

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

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        // 내비게이션 바만 숨기기
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        // 사용자가 화면을 스와이프했을 때만 잠깐 나타나도록 동작 설정
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE


        setContent {
            PingLauncherTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    MinecraftSurface(
                        onSurfaceCreated = { surface, _ ->
                            currentSurface = surface
                            setupAndLaunch(surface)
                        },
                        onSurfaceChanged = { w, h -> sendScreenSize(w, h) },
                        onTouch = { }
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
        // MinecraftJREPreparer 또는 setupAndLaunch에서
         val nativesDir = File(applicationContext.filesDir, "natives")

        try {
            System.loadLibrary("pingjvm")

            val jreLibDir = File(applicationContext.filesDir, "jre21_runtime/lib")
            if (jreLibDir.exists()) {
                listOf("libawt_xawt.so", "libawt_headless.so", "libpojavexec_awt.so").forEach { soName ->
                    val src = File(nativesDir, soName)
                    val dst = File(jreLibDir, soName)
                    if (src.exists() && !dst.exists()) {
                        src.copyTo(dst, overwrite = true)
                        dst.setExecutable(true, false)
                    }
                }
            }

            System.loadLibrary("vulkan")   // ← 추가: 시스템 Vulkan loader 강제 로드
            System.load(File(nativesDir, "libOSMesa.so").absolutePath)   // ← Mesa
            System.load(File(nativesDir, "libopenal.so").absolutePath)
            System.load(File(nativesDir, "libglfw.so").absolutePath)
            System.load(File(nativesDir, "libpojavexec.so").absolutePath)
            System.load(File(nativesDir, "liblwjgl.so").absolutePath)
            System.load(File(nativesDir, "liblwjgl_opengl.so").absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            Log.w("PING_LAUNCHER", "일부 .so 이미 로드됨: ${e.message}")
        }
        try {
            System.loadLibrary("pingjvm")
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

        File(base, "lwjgl3/lwjgl-glfw-classes.jar").takeIf { it.exists() }
            ?.let { jarList.add(it.absolutePath) }

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

        val libJvmPath = MinecraftJREPreparer.prepareJreAndGetPath(this)
        val jvmSettings = JvmSettingsManager.load(this)

        val launchWrapperArgs = if (mainClass.contains("launchwrapper")) {
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

        // ── Fabric 전용 JVM 인자 ──────────────────────────────
        val fabricJvmArgs = if (isFabric) {
            arrayOf(
                // -DFabricMcEmu은 metaJvmArgs에서 자동 주입 (Fabric profile arguments.jvm)
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

        // 인스턴스 메타의 추가 JVM 인자 (Fabric profile의 arguments.jvm)
        val metaJvmArgs = instanceMeta?.gameJvmArgs?.toTypedArray() ?: emptyArray()

        val jvmArgs = jvmSettings.toJvmArgArray(
            userDir = mcDir.absolutePath,
            classPath = jarList.joinToString(":"),
            libraryPath = nativesDir.absolutePath,
            mainClass = mainClass
        ) + launchWrapperArgs + fabricJvmArgs + metaJvmArgs

        Log.d("PING_LAUNCHER", "버전: $versionId, mcDir: ${mcDir.absolutePath}, isFabric=$isFabric")

        Thread {
            try {
                val session = MicrosoftAuthManager.loadSession(this)
                val validSession = if (session != null && !MicrosoftAuthManager.isSessionValid(session)) {
                    try {
                        MicrosoftAuthManager.refreshSession(session.refreshToken)
                            .also { MicrosoftAuthManager.saveSession(this, it) }
                    } catch (_: Exception) { session }
                } else session

                val baseMcArgs = if (validSession != null) {
                    arrayOf(
                        "--username", validSession.username,
                        "--version", versionId,
                        "--gameDir", mcDir.absolutePath,
                        "--assetsDir", assetsDir.absolutePath,
                        "--assetIndex", assetIndex,
                        "--uuid", validSession.uuid,
                        "--accessToken", validSession.accessToken,
                        "--userType", "msa",
                        "--versionType", if (isFabric) "Fabric" else "release"
                    )
                } else {
                    arrayOf(
                        "--username", "Player",
                        "--version", versionId,
                        "--gameDir", mcDir.absolutePath,
                        "--assetsDir", assetsDir.absolutePath,
                        "--assetIndex", assetIndex,
                        "--uuid", "00000000-0000-0000-0000-000000000000",
                        "--accessToken", "0",
                        "--userType", "mojang",
                        "--versionType", if (isFabric) "Fabric" else "release"
                    )
                }

                val metaGameArgs = instanceMeta?.gameArgs?.toTypedArray() ?: emptyArray()
                val mcArgs = baseMcArgs + metaGameArgs

                JavaNativeLauncher().bootMinecraftJVM(libJvmPath, jvmArgs, mcArgs)
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
                        CrashReportActivity.start(this, instanceBase.absolutePath)
                    }
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


    override fun onResume() {
        super.onResume()
        window.decorView.requestFocus()

        currentSurface?.let { surface ->
            if (surface.isValid) {
                try {
                    nativeSetupBridgeWindow(surface)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        currentInstance = null
        super.onDestroy()
    }
}