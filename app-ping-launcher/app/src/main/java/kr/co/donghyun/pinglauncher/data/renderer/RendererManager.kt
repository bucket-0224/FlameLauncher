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
    MOBILEGLUES(
        id = "mobileglues",
        displayName = "MobileGlues (GL 4.x)",
        description = "GLES 3.x로 OpenGL 4.x를 흉내내는 모던 변환기. 1.17+ 모드팩에 추천. 일부 에뮬레이터에서 불안정.",
        pojavRenderer = "opengles3",
        libglName = "libmobileglues.so",
        libglString = "MobileGlues",
        libglEs = "3",
        emoji = "📱",
        extraEnv = mapOf(
            "FORCE_VSYNC" to "true",
            "LIBGL_VSYNC" to "1"
        )
    ),
    ZINK(
        id = "zink",
        displayName = "Zink (Vulkan)",
        description = "Vulkan을 OpenGL로 변환. 모던 GPU에서 가장 호환성 좋음. 1.17+ 추천.",
        pojavRenderer = "vulkan_zink",
        libglName = "libltw.so",
        libglString = "VulkanGL",
        libglEs = "3",
        emoji = "🌋",
        extraEnv = mapOf(
            "MESA_GL_VERSION_OVERRIDE" to "4.6",
            "MESA_GLSL_VERSION_OVERRIDE" to "460",
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
    LTW(
        id = "ltw",
        displayName = "LTW (GLES 래퍼)",
        description = "GL을 GLES 3.x로 변환. MobileGlues와 비슷한 컨셉이지만 Sodium·Iris 호환성이 더 좋음. 1.17+ 모드팩 추천.",
        // ★ "ltw" 가 아니라 "opengles3" 로 — LTW 자체는 이걸 안 보지만
        //   egl_bridge.c 의 strncmp("opengles", ..., 8) 분기를 타려고 필요.
        //   (set_gl_bridge_tbl 자동 호출됨 → 별도 ltw 분기 코드 제거 가능)
        pojavRenderer = "opengles3",
        libglName = "libltw.so",
        libglString = "GLES",
        libglEs = "3",
        emoji = "⚡",
        extraEnv = mapOf(
            // LTW 가 실제로 보는 env 만:
            "LIBGL_NOERROR"                         to "1",
            // Sodium/Iris 의 GL 4.6 체크 통과용 (LWJGL/Minecraft 쪽이 봄)
            "MESA_GL_VERSION_OVERRIDE"              to "4.6",
            "MESA_GLSL_VERSION_OVERRIDE"            to "460",
            "force_glsl_extensions_warn"            to "true",
            "allow_higher_compat_version"           to "true",
            "allow_glsl_extension_directive_midshader" to "true"
        )
    ),
    HOLY_GL4ES(
        id = "gl4es",
        displayName = "Holy-GL4ES",
        description = "GLES2 기반 변환. 가볍고 구버전(1.16-)에 적합. 모드 호환성 좋음.",
        pojavRenderer = "opengles2",
        libglName = "libgl4es_114.so",
        libglString = "GL4ES",
        libglEs = "2",
        emoji = "⚡",
        extraEnv = mapOf(
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NORMALIZE" to "1",
            "LIBGL_VSYNC" to "1",
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_FB" to "2",              // FBO 강제 경로
            "LIBGL_FBOFORCETEX" to "1",     // FBO color attachment 텍스처 강제
            "LIBGL_FORCE16BITS" to "0",
            "LIBGL_GL" to "21",             // GL 2.1로 명시 → LWJGL이 3.0 진입점 매핑 안 시도
            "LIBGL_ES" to "2"
        )
    ),
    GL4ES_DESKTOP(
        id = "gl4es_desktop",
        displayName = "GL4ES (Desktop GL bind)",
        description = "GL4ES이지만 데스크톱 OpenGL API로 바인딩. 일부 기기에서 더 잘 됨.",
        pojavRenderer = "opengles3_desktopgl",
        libglName = "libgl4es_114.so",
        libglString = "GL4ES",
        libglEs = "3",
        emoji = "🖥️",
        extraEnv = mapOf(
            "LIBGL_GL" to "33",          // GL 3.3 코어 강제 노출
            "LIBGL_ES" to "3",
            "LIBGL_NOERROR" to "1",      // GL 에러 무시 (지옥문이지만 부팅 한정 시도)
            "LIBGL_USEVBO" to "1",
            "LIBGL_BATCH" to "1",
            "LIBGL_NPOT" to "2",
            "LIBGL_GLES" to "2",         // 백엔드는 ES2 그대로
            "LIBGL_FBOMAKECURRENT" to "1",
            "LIBGL_USEFBO" to "1",
            "LIBGL_VSYNC" to "0",
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
        return base
    }

    companion object {
        fun fromId(id: String?): Renderer = entries.firstOrNull { it.id == id } ?: MOBILEGLUES
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