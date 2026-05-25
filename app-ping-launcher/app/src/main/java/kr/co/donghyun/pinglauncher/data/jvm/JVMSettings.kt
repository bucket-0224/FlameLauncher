package kr.co.donghyun.pinglauncher.data.jvm

import android.content.Context
import com.google.gson.Gson
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
        userDir: String,
        classPath: String,
        libraryPath: String,
        mainClass: String
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
        args += listOf(
            "-Duser.dir=$userDir",
            "-Djava.class.path=$classPath",
            "-Djava.library.path=$libraryPath",
            "-Dorg.lwjgl.librarypath=$libraryPath",
            "-Dorg.lwjgl.opengl.libname=libng_gl4es.so",
            "-Dorg.lwjgl.opengles.libname=libng_gl4es.so",
            "-Dping.main.class=$mainClass",
            "-Dorg.lwjgl.system.SharedLibraryExtractPath=$libraryPath",
            "-Dorg.lwjgl.system.SharedLibraryExtractDirectory=$libraryPath",
            "-Dorg.lwjgl.util.NoChecks=true",
            "-Dorg.lwjgl.util.Debug=false",
            "-Djava.awt.headless=true",
            "-Dfml.earlyprogresswindow=false",
            "-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true",
            "-Dorg.lwjgl.glfw.libname=libpojavexec.so",
            "-Djava.io.tmpdir=${cacheDirPath}",
        )
        if (disableClouds) args += "-Dminecraft.graphics.disableClouds=true"

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