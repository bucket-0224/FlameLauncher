package kr.co.donghyun.flamelauncher.data.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * 외부 렌더러 플러그인(APK) 감지 매니저.
 *
 * ZalithLauncher2 / FCL 의 렌더러 플러그인 규격(meta-data 기반)을 따른다.
 *   - meta-data `zalithRendererPlugin`=true  또는 `fclPlugin`=true 인 설치된 APK 를 스캔
 *   - meta-data `renderer` = "표시이름:GL라이브러리명:EGL라이브러리명"
 *   - meta-data `pojavEnv` = "KEY=VALUE:KEY=VALUE..."  (POJAV_RENDERER / DLOPEN / LIB_MESA_NAME 등)
 *   - 플러그인 .so 는 그 APK 의 nativeLibraryDir 에 들어있다 → 절대경로로 dlopen 해야 함
 *
 * FlameLauncher 는 현재 외부 플러그인 중 **MobileGlues(`com.fcl.plugin.mobileglues`)** 만
 * 렌더러 목록에 노출한다. (내부 렌더러 Zink/GL4ES + 외부 1종 = 3종)
 * 다른 플러그인까지 열고 싶으면 [ALLOWED_PACKAGES] 에 패키지명을 추가하면 된다.
 *
 * 사용 흐름:
 *   1) 앱/설정 진입 시 [refresh] 호출 → 설치된 플러그인 1회 스캔
 *   2) [mobileGlues] 로 감지 결과 조회 (null 이면 미설치)
 *   3) 게임 실행 시 Renderer.MOBILEGLUES.buildEnv(...) 가 [mobileGlues]?.nativeLibraryDir 을 사용해
 *      LIBGL_NAME/DLOPEN/POJAVEXEC_EGL 를 절대경로로 채운다
 */
object RendererPluginManager {

    private const val TAG = "RendererPlugin"

    /** 렌더러 목록에 노출을 허용할 외부 플러그인 패키지. MobileGlues 만 허용. */
    private val ALLOWED_PACKAGES = setOf(
        "com.fcl.plugin.mobileglues",
    )

    /**
     * 감지된 외부 렌더러 플러그인 1건.
     * @param packageName       플러그인 APK 패키지명
     * @param appName           플러그인 앱 표시 이름(런처에서 "○○ 제공" 표기용)
     * @param displayName       meta-data `des` (렌더러 표시 이름)
     * @param pojavRendererId   pojavEnv 의 POJAV_RENDERER 값(없으면 renderer 의 첫 토큰)
     * @param glName            renderer 토큰[1] — GL 라이브러리 파일명(절대경로가 아닐 수 있음)
     * @param eglName           renderer 토큰[2] — EGL 라이브러리 파일명
     * @param nativeLibraryDir  플러그인 APK 의 네이티브 라이브러리 디렉터리(절대경로)
     * @param env               pojavEnv 에서 파싱한 추가 환경변수(POJAV_RENDERER/DLOPEN 제외분 + 가공분)
     * @param dlopen            DLOPEN= 으로 지정된 추가 로드 라이브러리 목록(파일명 또는 절대경로)
     */
    data class RendererPlugin(
        val packageName: String,
        val appName: String,
        val displayName: String,
        val pojavRendererId: String,
        val glName: String,
        val eglName: String,
        val nativeLibraryDir: String,
        val env: Map<String, String>,
        val dlopen: List<String>,
    ) {
        /** glName 을 절대경로로. 이미 '/'로 시작하면 그대로, 아니면 nativeLibraryDir 기준. */
        val glLibAbsolutePath: String
            get() = if (glName.startsWith("/")) glName else "$nativeLibraryDir/$glName"

        /** eglName 을 절대경로로. */
        val eglLibAbsolutePath: String
            get() = if (eglName.startsWith("/")) eglName else "$nativeLibraryDir/$eglName"

        /** DLOPEN 라이브러리들을 절대경로 리스트로. */
        fun dlopenAbsolutePaths(): List<String> = dlopen.map { lib ->
            if (lib.startsWith("/")) lib else "$nativeLibraryDir/$lib"
        }
    }

    @Volatile
    private var scanned = false

    /** 감지된 MobileGlues 플러그인. 미설치/미스캔이면 null. */
    @Volatile
    var mobileGlues: RendererPlugin? = null
        private set

    /** MobileGlues 가 설치되어 사용 가능한가. */
    fun isMobileGluesAvailable(): Boolean = mobileGlues != null

    /**
     * 설치된 플러그인을 1회 스캔한다. 이미 스캔했으면 [force]=false 시 스킵.
     * 앱 시작 시 또는 인스턴스 설정 화면 진입 시 호출하면 된다.
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun refresh(context: Context, force: Boolean = false) {
        if (scanned && !force) return
        scanned = true
        mobileGlues = null

        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA

        val resolved = try {
            // Android 11+(targetSdk 30+) 에서는 manifest 의 <queries> 가 있어야 다른 앱이 보인다.
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN), flags)
        } catch (e: Exception) {
            Log.e(TAG, "queryIntentActivities 실패: ${e.message}", e)
            emptyList()
        }

        for (info in resolved) {
            val appInfo = info.activityInfo?.applicationInfo ?: continue
            val plugin = parse(context, appInfo) ?: continue
            if (plugin.packageName in ALLOWED_PACKAGES) {
                if (plugin.packageName == "com.fcl.plugin.mobileglues") {
                    mobileGlues = plugin
                    Log.i(TAG, "✅ MobileGlues 감지: ${plugin.appName} " +
                            "(gl=${plugin.glLibAbsolutePath}, egl=${plugin.eglLibAbsolutePath})")
                }
            }
        }

        if (mobileGlues == null) {
            Log.d(TAG, "MobileGlues 미설치(또는 메타데이터 없음)")
        }
    }

    /**
     * 단일 APK 의 ApplicationInfo 에서 렌더러 플러그인 메타데이터를 파싱한다.
     * 시스템 앱이거나 플러그인 플래그가 없으면 null.
     * (ZL2 RendererPluginManager.parseApkPlugin 의 핵심 로직 이식)
     */
    private fun parse(context: Context, info: ApplicationInfo): RendererPlugin? {
        // 시스템 앱 제외
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return null

        val metaData = info.metaData ?: return null
        val isPlugin = metaData.getBoolean("fclPlugin", false) ||
                metaData.getBoolean("zalithRendererPlugin", false)
        if (!isPlugin) return null

        val rendererString = metaData.getString("renderer") ?: return null
        val des = metaData.getString("des") ?: return null
        val pojavEnvString = metaData.getString("pojavEnv") ?: ""
        val nativeLibraryDir = info.nativeLibraryDir ?: return null

        // renderer = "Name:libGl.so:libEgl.so"
        val rendererTokens = rendererString.split(":")
        if (rendererTokens.size < 3) {
            Log.w(TAG, "renderer 메타 형식 오류(${info.packageName}): '$rendererString'")
            return null
        }

        var pojavRendererId = rendererTokens[0]
        val envMap = mutableMapOf<String, String>()
        val dlopenList = mutableListOf<String>()

        // pojavEnv = "KEY=VALUE:KEY=VALUE:DLOPEN=a.so,b.so"
        pojavEnvString.split(":").forEach { entry ->
            if (!entry.contains("=")) return@forEach
            val idx = entry.indexOf("=")
            val key = entry.substring(0, idx)
            val value = entry.substring(idx + 1)
            when (key) {
                "POJAV_RENDERER" -> pojavRendererId = value
                "DLOPEN" -> value.split(",").forEach { lib ->
                    if (lib.isNotBlank()) dlopenList.add(lib)
                }
                // Mesa 라이브러리 경로는 nativeLibraryDir 기준 절대경로로 보정
                "LIB_MESA_NAME", "MESA_LIBRARY" -> envMap[key] = "$nativeLibraryDir/$value"
                else -> envMap[key] = value
            }
        }

        val pm = context.packageManager
        val appName = info.loadLabel(pm).toString()

        return RendererPlugin(
            packageName = info.packageName,
            appName = appName,
            displayName = des,
            pojavRendererId = pojavRendererId,
            glName = rendererTokens[1],
            eglName = rendererTokens[2],
            nativeLibraryDir = nativeLibraryDir,
            env = envMap,
            dlopen = dlopenList,
        )
    }

    /** MobileGlues 설치 페이지(GitHub Releases) — 미설치 시 안내에 사용. */
    const val MOBILEGLUES_RELEASE_URL =
        "https://github.com/MobileGL-Dev/MobileGlues-release/releases"
}