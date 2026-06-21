package kr.co.donghyun.pinglauncher.data.renderer

import android.content.Context

enum class Renderer(
    val id: String,
    val displayName: String,
    val description: String,
    val pojavRenderer: String,
    val libglName: String,
    val libglString: String,
    val libglEs: String,
    val emoji: String,
    val extraEnv: Map<String, String> = emptyMap()
) {
    ZINK(
        id = "zink",
        displayName = "Zink (Vulkan)",
        description = "Vulkan을 OpenGL로 변환. 모던 GPU에서 가장 호환성 좋음. 1.17+ 추천.",
        pojavRenderer = "vulkan_zink",
        libglName = "libOSMesa.so",
        libglString = "VulkanGL",
        libglEs = "3",
        emoji = "🌋",
        extraEnv = mapOf(
            "force_glsl_extensions_warn" to "true",
            "allow_higher_compat_version" to "true",
            "allow_glsl_extension_directive_midshader" to "true",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "GALLIUM_DRIVER" to "zink",
//            "POJAV_LOAD_TURNIP" to "1"  // Adreno면 Turnip 시도
            "MESA_VK_WSI_PRESENT_MODE" to "mailbox",
            "VK_ICD_FILENAMES" to "",         // 시스템 ICD 무시
            "ZINK_DEBUG" to "noreorder",      // zink 의 일부 reorder 최적화 비활성 (안정성)
            "LIBGL_KOPPER_DRI2" to "1",       // kopper(=zink WSI) 가 DRI2 없이 동작
            "LIBGL_DRI3_DISABLE" to "1",      // DRI3 도 끔
            "GALLIUM_HUD" to "",
        )
    ),

    /**
     * GL4ES — OpenGL(데스크톱) → OpenGL ES 2.0 변환 레이어.
     * Zink 가 지원하지 못하는 pre-1.13(특히 LWJGL2 시절의 immediate-mode GL)을 돌리기 위한 경로.
     * PojavLauncher 계열이 구버전 구동에 쓰는 기본 방식이며, libgl4es_114.so 로 동작한다.
     * (egl_bridge.c 의 GL4ES 폴백 경로와 동일하게 POJAV_RENDERER=opengles2 / LIBGL_ES=2 사용)
     */
    GL4ES(
        id = "gl4es",
        displayName = "GL4ES",
        description = "OpenGL을 GLES2로 변환. 구버전(1.12 이하) 및 Zink 미지원 기기용.",
        pojavRenderer = "opengles2",
        libglName = "libgl4es_114.so",
        libglString = "GL4ES wrapper",
        libglEs = "2",
        emoji = "🕹️",
        extraEnv = mapOf(
            // pre-1.13 안정화용 GL4ES 플래그 (PojavLauncher 권장값 기반)
            "LIBGL_ES" to "2",                 // GLES 2.0 백엔드
            "LIBGL_GL" to "21",                // 데스크톱 GL 2.1 에뮬레이션 (구버전 호환)
            "LIBGL_MIPMAP" to "3",             // 밉맵 자동 생성
            "LIBGL_NORMALIZE" to "1",          // 법선 정규화 (구버전 라이팅)
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_NOERROR" to "1",            // glGetError 비용 제거 (성능/안정)
            "LIBGL_USEVBO" to "1",             // ★ pre-1.13 의 VBO 미사용 랜덤 크래시 회피 (검색으로 확인된 핵심)
            "LIBGL_SHADERCONVERTER" to "1",
            "LIBGL_FB" to "2",                 // FBO 기반 백버퍼
            "LIBGL_FBONOALPHA" to "1",
        )
    );

    fun buildEnv(cacheDir: String, nativeDir: String): Map<String, String> {
        val base = mutableMapOf(
            "POJAV_RENDERER" to pojavRenderer,
            "LIBGL_NAME" to libglName,
            "LIBGL_STRING" to libglString,
            "LIBGL_ES" to libglEs,
            "DLOPEN" to libglName,
            "MESA_GLSL_CACHE_DIR" to cacheDir,
            "TMPDIR" to cacheDir,
            "POJAV_NATIVEDIR" to nativeDir,
            "FORCE_VSYNC" to "false",
            "POJAV_VSYNC" to "1"
        )
        base.putAll(extraEnv)

        if (id == "mobileglues") {
            // MobileGlues 전용 디렉토리 — config.json과 latest.log, GLSL 캐시가 여기 저장됨
            val mgDir = "$cacheDir/MobileGlues"
            java.io.File(mgDir).mkdirs()
            base["MG_DIR_PATH"] = mgDir

            // config.json 자동 생성 (없을 때만)
            val configFile = java.io.File(mgDir, "config.json")
            if (!configFile.exists()) {
                configFile.writeText("""
                {
                  "enableANGLE": 0,
                  "enableNoError": 1,
                  "enableExtTimerQuery": 0,
                  "enableExtComputeShader": 1,
                  "enableExtDirectStateAccess": 0,
                  "maxGlslCacheSize": 256,
                  "multidrawMode": 0,
                  "angleDepthClearFixMode": 0,
                  "customGLVersion": 0,
                  "fsr1Setting": 0
                }
            """.trimIndent())
            }
        }

        return base
    }

    companion object {
        fun fromId(id: String?): Renderer = entries.firstOrNull { it.id == id } ?: ZINK
    }
}

object RendererManager {
    private const val PREFS = "renderer_prefs"
    private const val KEY_SELECTED = "selected_renderer"

    fun load(context: Context): Renderer {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)
        return Renderer.fromId(id)
    }

    fun save(context: Context, renderer: Renderer) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, renderer.id)
            .apply()
    }
}