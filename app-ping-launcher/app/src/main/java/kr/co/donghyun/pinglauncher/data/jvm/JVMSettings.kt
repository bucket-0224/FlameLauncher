package kr.co.donghyun.pinglauncher.data.jvm

import android.content.Context
import com.google.gson.Gson
import kr.co.donghyun.pinglauncher.data.renderer.RendererManager
import java.io.File

data class JvmSettings(
    val maxHeapMb: Int = 2048,
    val minHeapMb: Int = 512,
    val useG1GC: Boolean = true,
    val gcPauseMillis: Int = 100,
    val parallelRefProc: Boolean = true,
    val heapRegionSizeMb: Int = 32,
    val disableClouds: Boolean = true,
    val extraJvmArgs: String = "",   // 줄바꿈 구분 커스텀 인자
    val mouseSensitivity: Float = 1.5f,
    val renderDistance: Int = 8,
    val graphicsMode: Int = 0,       // 0=fast, 1=fancy, 2=fabulous
    val cacheDirPath: String = ""
) {

    fun toJvmArgArray(
        context: Context,
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

        val renderer = RendererManager.load(context)

        val glLibName = when (renderer.id) {
            "mobileglues" -> "libmobileglues.so"
            "gl4es", "gl4es_desktop" -> "libgl4es_114.so"
            "zink" -> "libOSMesa.so"
            else -> "libgl4es_114.so"
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
            "-Djava.io.tmpdir=${cacheDirPath}",
        )

        val isLegacy = isLegacyVersion(versionId)  // 1.12.2 이하 = legacy

        if (isLegacy) {
            // Cacio bootclasspath
            val cacioDir = "${context.filesDir}/caciocavallo"
            val cacioJars = listOf(
                "$cacioDir/ResConfHack.jar",
                "$cacioDir/cacio-androidnw-1.10-SNAPSHOT.jar",
                "$cacioDir/cacio-shared-1.10-SNAPSHOT.jar"
            ).joinToString(":")

            args += "-Xbootclasspath/p:$cacioJars"
            args += "-Djava.awt.headless=false"
            args += "-Dcacio.managed.screensize=854x480"  // 또는 본인 윈도우 사이즈
            args += "-Dcacio.font.fontmanager=sun.awt.X11FontManager"
            args += "-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler"
            args += "-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel"
            args += "-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit"
            args += "-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment"
        } else {
            // 모던: AWT 안 씀
            args += "-Djava.awt.headless=true"
        }

    // MobileGlues 사용 시 Sodium 자체 검증 우회 (1.21+ 셰이더 호환)
        if (renderer.id == "mobileglues") {
            args += listOf(
                "-Dnet.caffeinemc.sodium.checks.skip=true",
                "-Dsodium.checks.issue2561=false"
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