package kr.co.donghyun.flamelauncher.data.renderer

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
    /** 외부 APK 플러그인이 .so 를 제공하는 렌더러인지(MobileGlues). 내부 번들 렌더러는 false. */
    val isPlugin: Boolean = false,
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
    ),

    /**
     * MobileGlues — OpenGL 4.x → OpenGL ES 3.2 변환 레이어 (외부 APK 플러그인).
     * 셰이더(Iris/Sodium) 성능이 가장 좋은 모던 렌더러. 1.17+ / 셰이더팩에 추천.
     *
     * 내부 번들이 아니라 **별도 설치 APK**(`com.fcl.plugin.mobileglues`)가 제공하는 libmobileglues.so 를
     * 그 APK 의 nativeLibraryDir 에서 dlopen 한다. 따라서 RendererPluginManager.refresh() 로
     * 먼저 감지되어 있어야 하며(plugin == null 이면 .so 경로를 알 수 없음), buildEnv() 에 plugin 을 넘긴다.
     *
     * 핵심 env (FCL 실제 구동 로그 기준):
     *   POJAV_RENDERER = opengles3 / LIBGL_ES = 3
     *   LIBGL_NAME = libmobileglues.so / LIBGL_EGL = libmobileglues.so / POJAVEXEC_EGL = libmobileglues.so
     *   DLOPEN = <pluginNativeDir>/libmobileglues.so  (정보취득용 libmobileglues_info_getter.so 가 의존성)
     *   MG_DIR_PATH = <cacheDir>/MobileGlues  (config.json / latest.log 위치)
     */
    MOBILEGLUES(
        id = "mobileglues",
        displayName = "MobileGlues",
        description = "OpenGL을 GLES3.2로 변환. 셰이더/모던 버전에 최적. 별도 앱 설치 필요.",
        pojavRenderer = "opengles3",
        libglName = "libmobileglues.so",
        libglString = "MobileGlues",
        libglEs = "3",
        emoji = "🚀",
        isPlugin = true,
        extraEnv = mapOf(
            // EGL 도 MobileGlues 가 직접 제공 (PojavLauncher/FCL 동일)
            "LIBGL_EGL" to "libmobileglues.so",
            "POJAVEXEC_EGL" to "libmobileglues.so",
        )
    );

    /**
     * 렌더러 환경변수 맵을 만든다.
     *
     * @param cacheDir 캐시 디렉터리(GLSL 캐시 / TMPDIR / MobileGlues 작업폴더).
     * @param nativeDir 런처 본체의 nativeLibraryDir(POJAV_NATIVEDIR).
     * @param plugin    MOBILEGLUES 처럼 외부 APK 가 .so 를 제공하는 경우 그 플러그인 정보.
     *                  내부 렌더러(Zink/GL4ES)에는 null 로 두면 된다.
     *                  null 인데 isPlugin==true 이면 .so 경로를 모르는 상태이므로
     *                  파일명만 채운다(이 경우 native dlopen 이 실패할 수 있음 → 호출 측에서 미설치 처리 권장).
     */
    fun buildEnv(
        cacheDir: String,
        nativeDir: String,
        plugin: RendererPluginManager.RendererPlugin? = null,
    ): Map<String, String> {
        // 외부 플러그인이 제공하는 .so 는 그 APK 의 nativeLibraryDir 절대경로로 dlopen 해야 한다.
        val glNameResolved: String
        val dlopenResolved: String
        if (isPlugin && plugin != null) {
            glNameResolved = plugin.glLibAbsolutePath
            dlopenResolved = plugin.glLibAbsolutePath
        } else {
            glNameResolved = libglName
            dlopenResolved = libglName
        }

        val base = mutableMapOf(
            "POJAV_RENDERER" to pojavRenderer,
            "LIBGL_NAME" to glNameResolved,
            "LIBGL_STRING" to libglString,
            "LIBGL_ES" to libglEs,
            "DLOPEN" to dlopenResolved,
            "MESA_GLSL_CACHE_DIR" to cacheDir,
            "TMPDIR" to cacheDir,
            "POJAV_NATIVEDIR" to nativeDir,
            "FORCE_VSYNC" to "false",
            "POJAV_VSYNC" to "1"
        )
        base.putAll(extraEnv)

        if (id == "mobileglues") {
            // 플러그인이 감지된 경우, EGL/EXEC_EGL 도 절대경로로 덮어쓴다.
            if (plugin != null) {
                base["LIBGL_EGL"] = plugin.eglLibAbsolutePath
                base["POJAVEXEC_EGL"] = plugin.eglLibAbsolutePath
                // 플러그인이 pojavEnv 로 넘긴 추가 env / DLOPEN 도 반영 (info_getter 등)
                base.putAll(plugin.env)
                val extraDlopen = plugin.dlopenAbsolutePaths()
                    .filter { it != plugin.glLibAbsolutePath }
                if (extraDlopen.isNotEmpty()) {
                    // 기존 DLOPEN(gl 본체) + 추가 라이브러리들을 콤마로 합침
                    base["DLOPEN"] = (listOf(base["DLOPEN"]!!) + extraDlopen).joinToString(",")
                }
            }

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

        /**
         * 현재 기기/설치 상태에서 인스턴스 설정에 노출할 렌더러 목록.
         * 내부 렌더러(Zink/GL4ES)는 항상, MobileGlues 는 APK 가 감지됐을 때만 포함.
         *
         * 호출 전에 RendererPluginManager.refresh(context) 를 한 번 돌려두면 된다.
         */
        fun selectableRenderers(): List<Renderer> = buildList {
            add(ZINK)
            add(GL4ES)
            if (RendererPluginManager.isMobileGluesAvailable()) add(MOBILEGLUES)
        }
    }
}

/**
 * 전역(런처 기본) 렌더러 설정.
 *
 * 인스턴스별 렌더러는 InstanceMeta.rendererId 에 저장한다.
 * 인스턴스에 값이 없을 때(null)의 폴백 기본값으로 이 전역 설정을 사용한다.
 */
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