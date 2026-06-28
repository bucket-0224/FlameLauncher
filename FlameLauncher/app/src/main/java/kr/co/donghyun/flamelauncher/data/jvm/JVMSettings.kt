package kr.co.donghyun.flamelauncher.data.jvm

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import kr.co.donghyun.flamelauncher.data.renderer.RendererManager
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

    // ── 디스플레이 ────────────────────────────────────────────────
    // 전체 화면: 시스템 바(상태/내비)를 숨기고 화면을 꽉 채운다. 기본 켜짐, 끌 수 있음.
    //            실제 적용은 게임 Activity 에서 한다(아래 applyFullscreen 참고).
    val fullscreen: Boolean = true,
    // 렌더 해상도 배율(%). 100 = 네이티브(화면 그대로). 낮출수록 프레임버퍼가 작아져
    //            FPS 가 오르고 화면은 약간 흐려진다. (ZalithLauncher2 의 Resolution 과 동일 개념)
    val resolutionScalePercent: Int = 100,
) {

    /** 0.25 ~ 1.00 범위로 보정된 실제 배율. 잘못된 값이 들어와도 범위를 벗어나지 않게 한다. */
    val resolutionScale: Float
        get() = resolutionScalePercent
            .coerceIn(RES_SCALE_MIN_PERCENT, RES_SCALE_MAX_PERCENT) / 100f

    /**
     * 화면 픽셀(width × height)에 배율을 적용한 실제 렌더 해상도.
     * GL/영상 백엔드 안정성을 위해 짝수로 맞추고 최소 2px 을 보장한다.
     * 모던(1.13+) 버전에서 surface/브리지 윈도우 크기를 정할 때 사용한다.
     */
    fun scaledResolution(width: Int, height: Int): Pair<Int, Int> {
        val s = resolutionScale
        val w = ((width * s).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((height * s).toInt() and 1.inv()).coerceAtLeast(2)
        return w to h
    }

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
        // ── Cacio (AWT 가상 백엔드) ──────────────────────────────────────────
        // FancyMenu, Dice, JourneyMap 등 일부 모드가 java.awt.* 를 호출하는데,
        // JRE 에 libawt_xawt.so(headful AWT)가 없어서 headless=true 여도 Toolkit.loadLibraries
        // 가 libawt_xawt.so 로드를 시도하다 UnsatisfiedLinkError 로 죽는다.
        // cacio 가 toolkit 을 가로채면 libawt_xawt.so 자체가 불필요해진다.
        //
        // ⚠️ JRE 버전별로 cacio 주입 방식이 완전히 다르다 (둘을 섞으면 안 됨):
        //   - JRE8 : cacio-androidnw-1.10 + -Xbootclasspath/p:(prepend) + net.java.openjdk.cacio.ctc.*
        //   - JRE9+: cacio-tta/shared-1.18 + -Xbootclasspath/a:(append) + CTCPreloadClassLoader
        //            + com.github.caciocavallosilano.cacio.ctc.* + --add-exports/--add-opens 풀세트.
        //            (JRE9+ 에 -Xbootclasspath/p: 를 주면 HotSpot 이 JNI_CreateJavaVM 단계에서
        //             -6(JNI_EINVAL) 으로 JVM 생성을 거부한다. prepend 옵션이 제거됐기 때문.)
        val javaMajor = resolveJavaMajor(versionId)

        if (javaMajor <= 8) {
            // ── JRE8: 기존 검증된 방식 그대로 ──
            val cacioDir = "${context.filesDir}/caciocavallo"
            val cacioJars = listOf(
                "$cacioDir/ResConfHack.jar",
                "$cacioDir/cacio-androidnw-1.10-SNAPSHOT.jar",
                "$cacioDir/cacio-shared-1.10-SNAPSHOT.jar"
            ).joinToString(":")

            // 레거시: cacio 가상 화면 = 렌더 해상도. resolutionScale 적용해 FPS 조절.
            val dm = context.resources.displayMetrics
            val (cacioW, cacioH) = scaledResolution(dm.widthPixels, dm.heightPixels)

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
            // ── JRE9+ (17/21/25): cacio17 정식 구성 ──
            // PojavLauncher/FCL 이 검증한 방식. -Xbootclasspath/p:(prepend) 대신
            //   1) -Xbootclasspath/a:(append) — JRE9+ 에서 살아있는 옵션이라 JNI -6 이 안 난다.
            //   2) -Djava.system.class.loader=...CTCPreloadClassLoader — agent 없이도 toolkit 을
            //      게임 클래스보다 먼저 가로채게 하는 핵심. (FancyMenu/Dice 가 java.awt.Color 를 불러도
            //      cacio 가 이미 toolkit 을 점유 → libawt_xawt.so 로드 시도 자체가 사라진다.)
            //   3) 신버전 클래스명 com.github.caciocavallosilano.cacio.ctc.*
            //   4) java.desktop 내부 패키지 개방용 --add-exports / --add-opens 풀세트.
            val cacio17Dir = "${context.filesDir}/caciocavallo17"
            val cacio17Jars = listOf(
                "$cacio17Dir/cacio-shared-1.18-SNAPSHOT.jar",
                "$cacio17Dir/cacio-tta-1.18-SNAPSHOT.jar"
            ).joinToString(":")

            // 모던: 렌더링은 GL 이 담당. cacio 가상 화면은 AWT 호환용이라 실제 픽셀이면 충분.
            val dm = context.resources.displayMetrics

            args += "-Djava.awt.headless=false"
            args += "-Dcacio.managed.screensize=${dm.widthPixels}x${dm.heightPixels}"
            args += "-Dcacio.font.fontmanager=sun.awt.X11FontManager"
            args += "-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler"
            args += "-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel"
            args += "-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit"
            args += "-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment"
            args += "-Djava.system.class.loader=com.github.caciocavallosilano.cacio.ctc.CTCPreloadClassLoader"
            args += "-Xbootclasspath/a:$cacio17Jars"

            // java.desktop / java.base 내부 패키지 개방 (JRE17 모듈 캡슐화 우회). PojavLauncher 와 동일 세트.
            args += "--add-exports=java.desktop/java.awt=ALL-UNNAMED"
            args += "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED"
            args += "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.awt=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.font=ALL-UNNAMED"
            args += "--add-exports=java.base/sun.security.action=ALL-UNNAMED"
            args += "--add-opens=java.base/java.util=ALL-UNNAMED"
            args += "--add-opens=java.desktop/java.awt=ALL-UNNAMED"
            args += "--add-opens=java.desktop/sun.font=ALL-UNNAMED"
            args += "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED"
            args += "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
            args += "--add-opens=java.base/java.net=ALL-UNNAMED"
        }
        // ─────────────────────────────────────────────────────────────────────

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

    companion object {
        /** 해상도 배율 하한 (%) */
        const val RES_SCALE_MIN_PERCENT = 25
        /**
         * 해상도 배율 상한 (%). 100 = 네이티브.
         * 100 초과로 올리고 싶으면(슈퍼샘플링 = 화질↑·FPS↓) 이 값만 키우면 된다.
         */
        const val RES_SCALE_MAX_PERCENT = 100
    }
}

object JvmSettingsManager {
    private const val FILE_NAME = "jvm_settings.json"
    private val gson = Gson()

    fun load(context: Context): JvmSettings {
        val fallback = JvmSettings(cacheDirPath = context.cacheDir.absolutePath)
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return fallback

            val text = file.readText()
            var settings = gson.fromJson(text, JvmSettings::class.java) ?: return fallback

            // ⚠️ Gson 은 Kotlin 생성자를 거치지 않아서, JSON 에 없는 필드는 data class 의
            //    기본값이 아니라 0/false 로 채워진다. 구버전 jvm_settings.json 에는
            //    fullscreen / resolutionScalePercent 가 없으므로, 키가 빠진 경우 기본값으로 보정한다.
            //    (이게 없으면 기존 사용자는 전체화면 off, 해상도 0% 로 깨진다.)
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (obj?.has("fullscreen") != true) settings = settings.copy(fullscreen = true)
            if (obj?.has("resolutionScalePercent") != true)
                settings = settings.copy(resolutionScalePercent = 100)

            // 범위 보정 (잘못된 값/0 방지)
            settings = settings.copy(
                resolutionScalePercent = settings.resolutionScalePercent
                    .coerceIn(JvmSettings.RES_SCALE_MIN_PERCENT, JvmSettings.RES_SCALE_MAX_PERCENT)
            )

            // cacheDirPath가 비어있으면 채워주기
            if (settings.cacheDirPath.isEmpty())
                settings.copy(cacheDirPath = context.cacheDir.absolutePath)
            else settings
        } catch (_: Exception) {
            fallback
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

/**
 * 버전에 따라 사용할 Java major 를 추정한다.
 *
 * ⚠️ cacio 주입 방식 분기에만 쓰는 보수적 추정치다.
 *    실제 JRE 선택은 MinecraftJREPreparer 가 하므로, 여기 값과 미세하게 다를 수 있어도
 *    "8 인가 / 9+ 인가" 경계만 정확하면 -Xbootclasspath/p: 사고를 막는 목적은 달성된다.
 *
 *   - 1.12.2 이하         → 8  (legacy, cacio-androidnw-1.10 + bootclasspath/p)
 *   - 그 외(1.13+/스냅샷)  → 17 (modern, bootclasspath/p 금지)
 *
 * 추후 1.20.5+ → 21, 26w+ 스냅샷 → 25 처럼 세분화해도 cacio 분기에는 영향 없다
 * (모두 9+ 이므로 동일하게 bootclasspath/p 를 안 쓰는 쪽으로 간다).
 */
fun resolveJavaMajor(versionId: String): Int {
    if (isLegacyVersion(versionId)) return 8
    return 17
}