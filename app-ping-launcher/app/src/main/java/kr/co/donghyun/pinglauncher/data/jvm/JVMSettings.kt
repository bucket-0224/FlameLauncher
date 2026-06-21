package kr.co.donghyun.pinglauncher.data.jvm

import android.content.Context
import com.google.gson.Gson
import kr.co.donghyun.pinglauncher.data.renderer.RendererManager
import java.io.File

data class JvmSettings(
    val maxHeapMb: Int = 4096,
    val minHeapMb: Int = 512,
    val useG1GC: Boolean = true,
    val gcPauseMillis: Int = 100,
    val parallelRefProc: Boolean = true,
    val heapRegionSizeMb: Int = 32,
    val disableClouds: Boolean = true,
    val extraJvmArgs: String = "",   // 줄바꿈 구분 커스텀 인자
    val mouseSensitivity: Float = 1.5f,
    val renderDistance: Int = 4,
    val graphicsMode: Int = 0,       // 0=fast, 1=fancy, 2=fabulous
    val cacheDirPath: String = "",
    val unlockFps: Boolean = true,
) {

    fun toJvmArgArray(
        context: Context,
        mcDir : File,
        userDir: String,
        classPath: String,
        libraryPath: String,
        mainClass: String,
        versionId : String
    ): Array<String> {
        val args = mutableListOf(
            "-Xmx${maxHeapMb}M",
            "-Xms${minHeapMb}M",
            "-XX:+UnlockExperimentalVMOptions",
            "-Djna.nosys=true",
            "-Doshi.os=android",
            "-Dio.netty.native.workdir=${cacheDirPath}",
            "-Djna.tmpdir=${cacheDirPath}",
        )
        if (useG1GC) {
            args += listOf(
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=$gcPauseMillis",
                if (parallelRefProc) "-XX:+ParallelRefProcEnabled" else "-XX:-ParallelRefProcEnabled",
                "-XX:G1NewSizePercent=20",
                "-XX:G1ReservePercent=20",
                "-XX:G1HeapRegionSize=${heapRegionSizeMb}m",
            )
        }

        // ── 프레임 일정성 개선용 JVM 인자 ───────────────────────
        // FPS cap 해제 자체는 options.txt 가 하지만, 이 옵션들은 GC pause 가
        // 프레임 사이에 끼어드는 걸 줄여서 unlocked FPS 가 실제로 매끄럽게 나오게 해줌.
        if (unlockFps) {
            args += listOf(
                "-XX:+DisableExplicitGC",            // 모드가 System.gc() 호출해도 무시
                "-XX:+AlwaysPreTouch",               // heap 페이지 미리 다 터치 → 런타임 page fault 제거
                "-XX:+ParallelRefProcEnabled",
                "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=15",
                "-XX:G1RSetUpdatingPauseTimePercent=5",
                "-XX:SurvivorRatio=32",
                "-XX:+PerfDisableSharedMem",         // /tmp/hsperfdata 접근으로 stall 방지
                "-XX:MaxTenuringThreshold=1",
            )
        }

        val renderer = RendererManager.load(context)

        // pre-1.13(1.12.x 이하) 레거시는 Zink(OSMesa)로 못 돌리므로 GL4ES 로 강제.
        //   ⚠️ 여기서 RendererManager.load 가 사용자 설정(예: zink)을 그대로 읽으면
        //   -Dorg.lwjgl.opengl.libname 이 libOSMesa.so 로 박혀 LWJGL 이 OSMesa 를 로드하고,
        //   GL4ES env(LIBGL_NAME 등)가 무색해져 검은 화면이 된다.
        //   MinecraftActivity.resolveRendererForVersion() 과 동일 기준으로 여기서도 오버라이드.
        val legacyForceGl4es = isLegacyVersion(versionId)
        val effectiveRendererId = if (legacyForceGl4es && renderer.id != "gl4es" && renderer.id != "gl4es_desktop")
            "gl4es" else renderer.id

        val glLibName = when (effectiveRendererId) {
            "mobileglues" -> "libmobileglues.so"
            "gl4es", "gl4es_desktop", "holy_gl4es" -> "libgl4es_114.so"
            "zink" -> "libOSMesa.so"
            else   -> "libgl4es_114.so"
        }

        args += listOf(
            "-Duser.dir=$userDir",
            "-Djava.class.path=$classPath",
            "-Djava.library.path=$libraryPath",
            "-Dorg.lwjgl.opengl.libname=${glLibName}",
            // MobileGlues 는 opengles 쪽도 같은 .so 로 묶어줘야 함
            "-Dorg.lwjgl.opengles.libname=${glLibName}",
            "-Dorg.lwjgl.librarypath=$libraryPath",
            "-Dping.main.class=$mainClass",
            "-Dorg.lwjgl.system.SharedLibraryExtractPath=$libraryPath",
            "-Dorg.lwjgl.system.SharedLibraryExtractDirectory=$libraryPath",
            "-Dorg.lwjgl.util.NoChecks=true",
            "-Dorg.lwjgl.util.Debug=true",
            "-Dorg.lwjgl.util.DebugLoader=true",
            "-Dfml.earlyprogresswindow=false",
            "-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true",
            "-Djava.io.tmpdir=${cacheDirPath}"
        )



        if (renderer.id == "mobileglues") {
            args += listOf(
                "-Dnet.caffeinemc.sodium.checks.skip=true",
                "-Dsodium.checks.issue2561=false"
            )
        }

        val isLegacy = isLegacyVersion(versionId)  // 1.12.2 이하 = legacy

        if (isLegacy) {
            // Cacio bootclasspath
            val cacioDir = "${context.filesDir}/caciocavallo"
            val cacioJars = listOf(
                "$cacioDir/ResConfHack.jar",
                "$cacioDir/cacio-androidnw-1.10-SNAPSHOT.jar",
                "$cacioDir/cacio-shared-1.10-SNAPSHOT.jar"
            ).joinToString(":")

            val dm = context.resources.displayMetrics
            val cacioW = dm.widthPixels
            val cacioH = dm.heightPixels
            args += "-Dcacio.managed.screensize=${cacioW}x${cacioH}"

            args += "-Xbootclasspath/p:$cacioJars"
            args += "-Djava.awt.headless=false"
            args += "-Dcacio.font.fontmanager=sun.awt.X11FontManager"
            args += "-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler"
            args += "-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel"
            args += "-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit"
            args += "-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment"
            args += "-Duser.home=${mcDir.parentFile.absolutePath}"
        } else {
            // 모던: AWT 안 씀
            args += "-Djava.awt.headless=true"
        }

        // MobileGlues 사용 시 Sodium 자체 검증 우회 (1.21+ 셰이더 호환)
        if (renderer.id == "mobileglues") {
            args += listOf(
                "-Dnet.caffeinemc.sodium.checks.skip=true",
                "-Dsodium.checks.issue2561=false",
                "-Dorg.lwjgl.opengl.maxVersion=4.6",
                "-Diris.force.support=true"
            )
        }

        // 커스텀 인자
        extraJvmArgs.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { args += it }

        return args.toTypedArray()
    }
}

object JvmSettingsManager {
    private const val FILE_NAME = "jvm_settings.json"
    private val gson = Gson()

    fun load(context: Context): JvmSettings {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return JvmSettings(cacheDirPath = context.cacheDir.absolutePath)
            val settings = gson.fromJson(file.readText(), JvmSettings::class.java)
                ?: JvmSettings(cacheDirPath = context.cacheDir.absolutePath)
            // cacheDirPath가 비어있으면 채워주기
            if (settings.cacheDirPath.isEmpty())
                settings.copy(cacheDirPath = context.cacheDir.absolutePath)
            else settings
        } catch (_: Exception) {
            JvmSettings(cacheDirPath = context.cacheDir.absolutePath)
        }
    }

    fun save(context: Context, settings: JvmSettings) {
        try {
            File(context.filesDir, FILE_NAME).writeText(gson.toJson(settings))
        } catch (_: Exception) {}
    }

    fun reset(context: Context): JvmSettings {
        val default = JvmSettings(cacheDirPath = context.cacheDir.absolutePath)
        save(context, default)
        return default
    }
}

fun isLegacyVersion(versionId: String): Boolean {
    // 1.12.2 이하: legacy (AWT 필요)
    // 1.13+: modern (LWJGL3, AWT 불필요)
    val parts = versionId.removePrefix("1.").split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
    return major <= 12
}