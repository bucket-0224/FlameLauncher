package kr.co.donghyun.pinglauncher.presentation

import ContentPackDetailScreen
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.pinglauncher.BuildConfig
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeFile
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeListResponse
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.screen.ContentType
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet
import kr.co.donghyun.pinglauncher.data.jvm.isLegacyVersion
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class ContentDetail(
    val screenshots: List<ContentScreenshot> = emptyList(),
    val description: String = "",
    val rawHtml: String = ""
)

data class ContentScreenshot(
    val thumbnailUrl: String,  // 작은 썸네일
    val fullUrl: String        // 원본
)

/** 모드 로더 종류 */
enum class ModLoader(val displayName: String, val curseForgeId: Int) {
    FABRIC("Fabric", 4),
    FORGE("Forge", 1),
    NEOFORGE("NeoForge", 6);
}

/**
 * 기존 인스턴스 요약 정보.
 * @param id 인스턴스 ID
 * @param name 표시명
 * @param gameVersion 마인크래프트 버전 (예: "1.20.1")
 * @param loader 설치된 모드 로더 (null이면 바닐라)
 */
data class InstanceSummary(
    val id: String,
    val name: String,
    val gameVersion: String,
    val loader: ModLoader?
)

/**
 * 모드/모드팩이 실제로 제공하는 (MC 버전 × 로더) 조합 한 개.
 * CurseForge files 응답에서 파일별로 뽑아 중복 제거해 만든다.
 * 사용자가 이 조합 하나를 탭하면 버전·로더를 따로 고를 필요 없이 그대로 새 인스턴스가 만들어진다.
 *
 * @param loader null이면 바닐라(로더 무관 — 텍스처/쉐이더/월드 등)
 * @param sortKey 내림차순 정렬용 비교키(major*10000 + minor*100 + patch). 최신 버전이 위로.
 */
data class VersionLoaderCombo(
    val mcVersion: String,
    val loader: ModLoader?,
    val sortKey: Int
) {
    /** 칩/리스트에 표시할 라벨. 예: "1.20.1 · Forge", "1.21.1 · Vanilla" */
    val label: String get() = "$mcVersion · ${loader?.displayName ?: "Vanilla"}"
}

/**
 * MC 버전 → 사람이 알아보기 쉬운 업데이트 이름.
 * 모르는 버전은 null (호출부에서 버전 숫자만 표시).
 */
fun mcVersionNickname(version: String): String? = when (version) {
    "1.21.8", "1.21.7", "1.21.6" -> "Chase the Skies"
    "1.21.5"                     -> "Spring to Life"
    "1.21.4"                     -> "The Garden Awakens"
    "1.21.2", "1.21.3"           -> "Bundles of Bravery"
    "1.21", "1.21.1"             -> "Tricky Trials"
    "1.20.5", "1.20.6"           -> "Armored Paws"
    "1.20.3", "1.20.4"           -> "Decorated Pots"
    "1.20.2"                     -> "Playful Update"
    "1.20", "1.20.1"             -> "Trails & Tales"
    "1.19.4"                     -> "Feature Preview"
    "1.19", "1.19.1", "1.19.2", "1.19.3" -> "The Wild Update"
    "1.18", "1.18.1", "1.18.2"   -> "Caves & Cliffs II"
    "1.17", "1.17.1"             -> "Caves & Cliffs I"
    "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5" -> "Nether Update"
    "1.15", "1.15.1", "1.15.2"   -> "Buzzy Bees"
    "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4" -> "Village & Pillage"
    "1.13", "1.13.1", "1.13.2"   -> "Update Aquatic"
    "1.12", "1.12.1", "1.12.2"   -> "World of Color"
    else -> null
}

class ContentPackDetailActivity : BaseActivity() {

    private val _detail = MutableStateFlow<ContentDetail?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _isInstalled = MutableStateFlow(false)
    private val _fullscreenIndex = MutableStateFlow<Int?>(null)
    private val _showInstallTargetDialog = MutableStateFlow(false)
    private val _loaderInstances = MutableStateFlow<List<InstanceSummary>>(emptyList())

    private val _supportedLoaders = MutableStateFlow<Set<ModLoader>>(emptySet())
    private val _supportedMcVersions = MutableStateFlow<Set<String>>(emptySet())   // 데이터팩 지원 MC 버전

    // 자동 감지된 (MC 버전 × 로더) 조합 — 새 인스턴스 만들기에서 버전/로더 따로 고를 필요 없이 한 번에 선택
    private val _supportedCombos = MutableStateFlow<List<VersionLoaderCombo>>(emptyList())

    // ── 데이터팩 전용: 인스턴스 선택 후 월드 선택 단계 ──
    private val _showWorldDialog = MutableStateFlow(false)
    private val _worldCandidates = MutableStateFlow<List<String>>(emptyList())
    private var _pendingDatapackInstance: InstanceSummary? = null

    companion object {
        // "1.x" / "1.x.y" 형태만 매칭 (gameVersions 의 로더 태그와 MC 버전 구분)
        private val MC_VERSION_REGEX = Regex("""^\d+\.\d+(\.\d+)?$""")

        const val EXTRA_MOD_ID = "mod_id"
        const val EXTRA_MOD_NAME = "mod_name"
        const val EXTRA_MOD_SUMMARY = "mod_summary"
        const val EXTRA_MOD_LOGO = "mod_logo"
        const val EXTRA_MOD_DOWNLOADS = "mod_downloads"
        const val EXTRA_CONTENT_TYPE = "content_type"

        // 설치 결과로 전달되는 추가 정보
        const val EXTRA_TARGET_INSTANCE_ID = "target_instance_id"   // 기존 인스턴스 선택 시
        const val EXTRA_TARGET_VERSION = "target_version"           // 새 인스턴스 생성 시
        const val EXTRA_TARGET_LOADER = "target_loader"             // 새 인스턴스 생성 시 (ModLoader.name)
        const val EXTRA_TARGET_WORLD = "target_world"               // 데이터팩 설치 대상 월드명

        fun start(
            context: Context,
            modId: Int,
            modName: String,
            modSummary: String,
            modLogo: String?,
            modDownloads: Long,
            contentType: ContentType
        ) {
            context.startActivity(
                Intent(context, ContentPackDetailActivity::class.java).apply {
                    putExtra(EXTRA_MOD_ID, modId)
                    putExtra(EXTRA_MOD_NAME, modName)
                    putExtra(EXTRA_MOD_SUMMARY, modSummary)
                    putExtra(EXTRA_MOD_LOGO, modLogo)
                    putExtra(EXTRA_MOD_DOWNLOADS, modDownloads)
                    putExtra(EXTRA_CONTENT_TYPE, contentType.name)
                }
            )
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                scrim = android.graphics.Color.TRANSPARENT
            )
        )
        val modId = intent.getIntExtra(EXTRA_MOD_ID, -1)
        val modName = intent.getStringExtra(EXTRA_MOD_NAME) ?: ""
        val modSummary = intent.getStringExtra(EXTRA_MOD_SUMMARY) ?: ""
        val modLogo = intent.getStringExtra(EXTRA_MOD_LOGO)
        val modDownloads = intent.getLongExtra(EXTRA_MOD_DOWNLOADS, 0)
        val contentType = runCatching {
            ContentType.valueOf(intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: ContentType.MODPACK.name)
        }.getOrDefault(ContentType.MODPACK)

        _isInstalled.value = isContentInstalled(modId, modName)

        val instanceId = InstanceManager.modpackId(modName)
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        _isInstalled.value = instanceDir.resolve("instance.json").exists()

        setContent {
            PingLauncherTheme {
                val detail by _detail.asStateFlow().collectAsState()
                val isLoading by _isLoading.asStateFlow().collectAsState()
                val isInstalled by _isInstalled.asStateFlow().collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    ContentPackDetailScreen(
                        modId = modId,
                        modName = modName,
                        modSummary = modSummary,
                        modLogo = modLogo,
                        modDownloads = modDownloads,
                        detail = detail,
                        isLoading = isLoading,
                        isInstalled = isInstalled,
                        contentType = contentType,
                        onBack = { finish() },
                        onInstall = { handleInstallRequest(modId, contentType) },
                        onLaunch = {
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_MOD_ID, modId).putExtra("action", "launch"))
                            finish()
                        },
                        onImageClick = { index -> _fullscreenIndex.value = index }
                    )

                    // 전체화면 이미지 뷰어
                    val fullscreenIndex by _fullscreenIndex.asStateFlow().collectAsState()

                    fullscreenIndex?.let { startIndex ->
                        val screenshots = detail?.screenshots ?: emptyList()
                        Dialog(
                            onDismissRequest = { _fullscreenIndex.value = null },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.97f))
                            ) {
                                val pagerState = rememberPagerState(
                                    initialPage = startIndex,
                                    pageCount = { screenshots.size }
                                )

                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { _fullscreenIndex.value = null },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = screenshots[page].fullUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxWidth(),
                                            contentScale = ContentScale.FillWidth
                                        )
                                    }
                                }

                                // 페이지 인디케이터
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 32.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    repeat(screenshots.size) { index ->
                                        val isSelected = pagerState.currentPage == index
                                        Box(
                                            modifier = Modifier
                                                .size(if (isSelected) 8.dp else 6.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(
                                                    if (isSelected) Color.White
                                                    else Color.White.copy(alpha = 0.3f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 설치 타겟 선택 다이얼로그
                    val showInstallDialog by _showInstallTargetDialog.asStateFlow().collectAsState()
                    val loaderInstances by _loaderInstances.asStateFlow().collectAsState()
                    val supportedLoaders by _supportedLoaders.asStateFlow().collectAsState()   // ★ 추가
                    val supportedMcVersions by _supportedMcVersions.asStateFlow().collectAsState()
                    val supportedCombos by _supportedCombos.asStateFlow().collectAsState()

                    if (showInstallDialog) {
                        InstallTargetDialog(
                            contentType = contentType,
                            // allowVanilla: 텍스처/쉐이더 같이 로더 불문 콘텐츠이고
                            //               감지된 로더도 없을 때만
                            allowVanilla = !contentType.requiresModLoader && supportedLoaders.isEmpty(),
                            supportedLoaders = supportedLoaders,         // ★ 추가
                            supportedMcVersions = supportedMcVersions,
                            supportedCombos = supportedCombos,           // ★ 자동 감지 조합
                            existingInstances = loaderInstances,
                            // 데이터팩은 기존 인스턴스(+월드)에만 설치 — 새 인스턴스 생성 불가
                            hideCreateNew = contentType == ContentType.DATAPACK,
                            onDismiss = { _showInstallTargetDialog.value = false },
                            onUseExisting = { instance ->
                                if (contentType == ContentType.DATAPACK) {
                                    // 데이터팩: 인스턴스 선택 후 그 인스턴스의 월드를 추가로 골라야 함
                                    val worlds = listWorldsForInstance(instance)
                                    if (worlds.isEmpty()) {
                                        Toast.makeText(
                                            this@ContentPackDetailActivity,
                                            "이 인스턴스에 월드가 없습니다. 먼저 게임에서 월드를 만들어 주세요.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        _pendingDatapackInstance = instance
                                        _worldCandidates.value = worlds
                                        _showInstallTargetDialog.value = false
                                        _showWorldDialog.value = true
                                    }
                                } else {
                                    _showInstallTargetDialog.value = false
                                    setResult(RESULT_OK, Intent()
                                        .putExtra(EXTRA_MOD_ID, modId)
                                        .putExtra("action", "install")
                                        .putExtra(EXTRA_TARGET_INSTANCE_ID, instance.id))
                                    finish()
                                }
                            },
                            onCreateNew = { version, loader ->
                                _showInstallTargetDialog.value = false
                                val intent = Intent()
                                    .putExtra(EXTRA_MOD_ID, modId)
                                    .putExtra("action", "install")
                                    .putExtra(EXTRA_TARGET_VERSION, version)
                                if (loader != null) intent.putExtra(EXTRA_TARGET_LOADER, loader.name)
                                setResult(RESULT_OK, intent)
                                finish()
                            }
                        )
                    }

                    // 데이터팩 월드 선택 다이얼로그
                    val showWorldDialog by _showWorldDialog.asStateFlow().collectAsState()
                    val worldCandidates by _worldCandidates.asStateFlow().collectAsState()
                    if (showWorldDialog) {
                        WorldSelectDialog(
                            worlds = worldCandidates,
                            onDismiss = { _showWorldDialog.value = false },
                            onSelect = { world ->
                                _showWorldDialog.value = false
                                val instance = _pendingDatapackInstance
                                if (instance != null) {
                                    setResult(RESULT_OK, Intent()
                                        .putExtra(EXTRA_MOD_ID, modId)
                                        .putExtra("action", "install")
                                        .putExtra(EXTRA_TARGET_INSTANCE_ID, instance.id)
                                        .putExtra(EXTRA_TARGET_WORLD, world))
                                    finish()
                                }
                            }
                        )
                    }
                }
            }
        }

        if (modId != -1) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    _detail.value = fetchModDetail(modId)
                } catch (e: Exception) {
                    Log.e("PING_LAUNCHER", "상세 정보 로드 실패: ${e.message}")
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * 인스턴스의 월드(세이브) 목록. level.dat 가 있는 폴더만 월드로 본다.
     * 데이터팩 설치 대상 선택에 쓴다.
     */
    private fun listWorldsForInstance(instance: InstanceSummary): List<String> {
        val instanceDir = InstanceManager.instanceDir(this, instance.id)
        val savesDir = if (isLegacyVersion(instance.gameVersion))
            File(instanceDir, ".minecraft/saves")
        else
            File(instanceDir, "saves")
        if (!savesDir.isDirectory) return emptyList()
        return savesDir.listFiles()
            ?.filter { it.isDirectory && File(it, "level.dat").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    private fun handleInstallRequest(modId: Int, contentType: ContentType) {
        if (!contentType.needsTargetInstance) {
            setResult(RESULT_OK, Intent()
                .putExtra(EXTRA_MOD_ID, modId)
                .putExtra("action", "install"))
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 파일 목록 한 번만 받아서 로더/버전/조합을 모두 뽑는다 (API 호출 1회로 통합)
            val files = fetchFilesForDetect(modId)

            // 1) 이 모드가 지원하는 로더 집합
            val supported = extractSupportedLoaders(files)
            _supportedLoaders.value = supported

            // 2) Vanilla 허용 여부:
            //    - 텍스처/쉐이더 (requiresModLoader=false) 이고
            //    - 감지된 로더가 없는 경우 (= 로더 무관 자원)
            val includeVanilla = !contentType.requiresModLoader && supported.isEmpty()

            // 3) 데이터팩은 MC 버전(pack_format)에 민감 → 호환 버전 인스턴스만 노출.
            val supportedMcVersions = if (contentType == ContentType.DATAPACK)
                extractSupportedMcVersions(files)
            else emptySet()
            _supportedMcVersions.value = supportedMcVersions

            // 4) (MC 버전 × 로더) 조합 자동 감지 — 새 인스턴스 만들기에서 한 번에 선택
            //    로더가 필요 없는 콘텐츠(allowVanilla)는 로더 없는 버전-only 조합으로.
            _supportedCombos.value = extractVersionLoaderCombos(files, includeVanilla)

            // 5) 인스턴스 목록 — 로더 + (데이터팩이면) MC 버전 필터 적용
            _loaderInstances.value = scanInstances(includeVanilla, supported, supportedMcVersions)
            _showInstallTargetDialog.value = true
        }
    }

    /**
     * 감지용 파일 목록 1회 페치. 로더/버전/조합 추출에 공통으로 쓴다.
     * 네트워크 실패 / 비어있음 → emptyList (각 추출 함수가 폴백 처리).
     */
    private fun fetchFilesForDetect(modId: Int): List<CurseForgeFile> {
        val client = OkHttpClient()
        val req = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId/files?pageSize=50&index=0")
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return emptyList()
                val type = object : TypeToken<CurseForgeListResponse<CurseForgeFile>>(){}.type
                Gson().fromJson<CurseForgeListResponse<CurseForgeFile>>(body, type).data ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w("PING_LAUNCHER", "파일 감지 페치 실패: ${e.message}")
            emptyList()
        }
    }

    /**
     * 파일 목록의 gameVersions 로더 태그를 모은다.
     *  - 네트워크 실패 / 응답 비어있음 → emptySet (호출자가 폴백 처리)
     */
    private fun extractSupportedLoaders(files: List<CurseForgeFile>): Set<ModLoader> {
        val out = mutableSetOf<ModLoader>()
        files.forEach { f ->
            f.gameVersions.forEach { gv ->
                when (gv.lowercase()) {
                    "forge"    -> out += ModLoader.FORGE
                    "fabric"   -> out += ModLoader.FABRIC
                    "neoforge" -> out += ModLoader.NEOFORGE
                    // Quilt 는 ModLoader enum 에 없어서 skip
                }
            }
        }
        Log.d("PING_LAUNCHER", "🔍 supported loaders: $out (files=${files.size})")
        return out
    }

    /**
     * 파일 목록에서 이 콘텐츠가 지원하는 MC 버전 집합을 모은다.
     * gameVersions 에는 로더 태그("Forge")와 MC 버전("1.20.1")이 섞여 있으므로,
     * "1.x" / "1.x.y" 형태(숫자.숫자[.숫자])만 골라낸다.
     */
    private fun extractSupportedMcVersions(files: List<CurseForgeFile>): Set<String> {
        val out = mutableSetOf<String>()
        files.forEach { f ->
            f.gameVersions.forEach { gv ->
                if (MC_VERSION_REGEX.matches(gv.trim())) out += gv.trim()
            }
        }
        Log.d("PING_LAUNCHER", "🔍 supported MC versions: $out (files=${files.size})")
        return out
    }

    /**
     * 파일 목록에서 (MC 버전 × 로더) 조합을 전부 뽑아 중복 제거 후 최신순 정렬.
     *
     * 한 파일의 gameVersions 안에는 MC 버전 여러 개 + 로더 태그 여러 개가 섞여 있을 수 있다.
     * 따라서 파일별로 (그 파일의 MC 버전들) × (그 파일의 로더들) 을 곱해 조합을 만든다.
     *  - 로더 태그가 전혀 없는 파일:
     *      · allowVanilla=true  → 로더 null(바닐라) 조합으로 추가 (텍스처/쉐이더/월드 등)
     *      · allowVanilla=false → 로더를 특정할 수 없으므로 건너뜀
     *  - 네트워크 실패로 files 가 비면 emptyList → 다이얼로그가 정적 폴백 목록 사용
     */
    private fun extractVersionLoaderCombos(
        files: List<CurseForgeFile>,
        allowVanilla: Boolean,
    ): List<VersionLoaderCombo> {
        val combos = linkedSetOf<Pair<String, ModLoader?>>()  // 삽입 순서 유지 + 중복 제거

        files.forEach { f ->
            val versions = f.gameVersions.map { it.trim() }.filter { MC_VERSION_REGEX.matches(it) }
            if (versions.isEmpty()) return@forEach

            val loaders = f.gameVersions.mapNotNull { gv ->
                when (gv.lowercase()) {
                    "forge"    -> ModLoader.FORGE
                    "fabric"   -> ModLoader.FABRIC
                    "neoforge" -> ModLoader.NEOFORGE
                    else       -> null
                }
            }.distinct()

            when {
                loaders.isNotEmpty() ->
                    versions.forEach { v -> loaders.forEach { l -> combos += v to l } }
                allowVanilla ->
                    versions.forEach { v -> combos += v to null }
                // 로더도 없고 바닐라도 불허 → 스킵 (모드인데 로더 태그 누락된 비정상 파일)
            }
        }

        val result = combos.map { (v, l) -> VersionLoaderCombo(v, l, mcVersionSortKey(v)) }
            // 최신 버전 먼저, 같은 버전이면 로더 enum 순서(Fabric→Forge→NeoForge)
            .sortedWith(compareByDescending<VersionLoaderCombo> { it.sortKey }
                .thenBy { it.loader?.ordinal ?: -1 })

        Log.d("PING_LAUNCHER", "🔍 version×loader combos: ${result.map { it.label }} (files=${files.size})")
        return result
    }

    /** "1.20.1" → 비교키(major*10000 + minor*100 + patch). 정렬 전용. */
    private fun mcVersionSortKey(v: String): Int {
        val p = v.split(".").mapNotNull { it.toIntOrNull() }
        return (p.getOrElse(0) { 0 } * 10000) + (p.getOrElse(1) { 0 } * 100) + p.getOrElse(2) { 0 }
    }

    /**
     * 설치된 인스턴스 목록. [includeVanilla]=true면 로더 없는 바닐라도 포함.
     */
    private fun scanInstances(
        includeVanilla: Boolean,
        supportedLoaders: Set<ModLoader> = emptySet(),
        supportedMcVersions: Set<String> = emptySet(),   // 비어있지 않으면 이 MC 버전만 통과
    ): List<InstanceSummary> {
        return try {
            InstanceManager.listInstances(this).mapNotNull { meta ->
                val loader = when (meta.loaderType?.lowercase()) {
                    "fabric"   -> ModLoader.FABRIC
                    "forge"    -> ModLoader.FORGE
                    "neoforge" -> ModLoader.NEOFORGE
                    else       -> null   // Vanilla
                }

                when {
                    // 바닐라 인스턴스
                    loader == null -> {
                        if (!includeVanilla) return@mapNotNull null
                    }
                    // 로더 인스턴스 — supportedLoaders 가 비어있으면 (감지 실패)
                    // 보수적으로 전부 통과시킴. 아니면 교집합만 통과.
                    supportedLoaders.isNotEmpty() && loader !in supportedLoaders ->
                        return@mapNotNull null
                }

                // MC 버전 필터 — supportedMcVersions 가 비어있지 않을 때만 적용.
                // (데이터팩 등 버전 민감 콘텐츠. 감지 실패 시엔 비어있어 필터 미적용)
                if (supportedMcVersions.isNotEmpty() && meta.mcVersion !in supportedMcVersions) {
                    return@mapNotNull null
                }

                InstanceSummary(
                    id = meta.id,
                    name = meta.name,
                    gameVersion = meta.mcVersion,
                    loader = loader
                )
            }
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "인스턴스 스캔 실패: ${e.message}")
            emptyList()
        }
    }

    private fun fetchModDetail(modId: Int): ContentDetail {
        val client = OkHttpClient()
        val apiKey = BuildConfig.CURSEFORGE_API_KEY
        var screenshots = listOf<ContentScreenshot>()
        var description = ""
        var rawHtmlWebView = ""

        val modRequest = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()

        client.newCall(modRequest).execute().use { response ->
            val json = response.body?.string() ?: return@use
            val data = JsonParser.parseString(json)
                .asJsonObject["data"]?.asJsonObject ?: return@use

            screenshots = data["screenshots"]?.asJsonArray?.mapNotNull { el ->
                val obj = el.asJsonObject
                val full = obj["url"]?.asString ?: return@mapNotNull null
                val thumb = "$full?width=400&height=225"
                ContentScreenshot(thumbnailUrl = thumb, fullUrl = full)
            } ?: emptyList()
        }

        val descRequest = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId/description")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()

        client.newCall(descRequest).execute().use { response ->
            val json = response.body?.string() ?: return@use
            val rawHtml = JsonParser.parseString(json)
                .asJsonObject["data"]?.asString ?: ""
            rawHtmlWebView = rawHtml
            description = rawHtml
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
                .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
                .replace(Regex("<h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<div[^>]*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]*>"), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        return ContentDetail(screenshots = screenshots, description = description, rawHtml = rawHtmlWebView)
    }

    private fun isContentInstalled(modId: Int, modName: String): Boolean {
        if (modId < 0) return false
        return InstanceManager.listInstances(this).any { meta ->
            meta.sourceModId == modId || (meta.sourceModId == null && meta.name == modName)
        }
    }
}

/**
 * 모드 설치 시 어떤 인스턴스에 설치할지 선택하는 다이얼로그.
 * - existingInstances 비어있으면 신규 생성 섹션만 노출
 * - 비어있지 않으면 두 섹션 모두 노출
 */
@Composable
private fun InstallTargetDialog(
    contentType: ContentType,
    allowVanilla: Boolean,
    supportedLoaders: Set<ModLoader>,           // ★ 추가
    supportedMcVersions: Set<String> = emptySet(),   // 데이터팩 지원 MC 버전(표기용)
    supportedCombos: List<VersionLoaderCombo> = emptyList(),   // 자동 감지된 (버전×로더) 조합
    existingInstances: List<InstanceSummary>,
    onDismiss: () -> Unit,
    onUseExisting: (InstanceSummary) -> Unit,
    onCreateNew: (version: String, loader: ModLoader?) -> Unit,
    hideCreateNew: Boolean = false,   // 데이터팩 등 — 새 인스턴스 생성 섹션 숨김(인스턴스 선택만)
) {
    val tablet = isTablet()

    // 화면 높이의 90% 를 다이얼로그 최대 높이로. 내용이 짧으면 그만큼만 차지(wrap),
    // 길면 이 값에서 멈추고 내부 LazyColumn 이 스크롤 → 하단 버튼은 항상 보임.
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    // (색상/디멘션 변수들 — 기존 그대로)
    val Pink = Color(0xFFE91E8C)
    val BgDark = Color(0xFF120B10)
    val BgSurface = Color(0xFF1E0E1A)
    val BgBorder = Color(0xFF3D1A32)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    val titleSize       = if (tablet) 18.sp else 14.sp
    val sectionSize     = if (tablet) 15.sp else 12.sp
    val descSize        = if (tablet) 13.sp else 11.sp
    val labelSize       = if (tablet) 13.sp else 11.sp
    val itemTitleSize   = if (tablet) 14.sp else 11.sp
    val itemSubSize     = if (tablet) 12.sp else 9.sp
    val chipSize        = if (tablet) 13.sp else 11.sp
    val buttonSize      = if (tablet) 13.sp else 11.sp
    val pickerLabelSize = if (tablet) 13.sp else 11.sp

    val dialogWidthRatio  = if (tablet) 0.7f else 0.95f
    val outerPad          = if (tablet) 22.dp else 16.dp
    val verticalGap       = if (tablet) 16.dp else 12.dp
    val sectionGap        = if (tablet) 10.dp else 6.dp
    val listItemPadH      = if (tablet) 14.dp else 10.dp
    val listItemPadV      = if (tablet) 12.dp else 9.dp
    val chipPadH          = if (tablet) 12.dp else 9.dp
    val chipPadV          = if (tablet) 7.dp else 5.dp
    val loaderRowPadV     = if (tablet) 12.dp else 8.dp
    val actionButtonH     = if (tablet) 44.dp else 38.dp

    // ── 로더 후보 — supportedLoaders 가 비어있으면 (감지 실패) 폴백으로 전체 ──
    val loaderOptions: List<ModLoader> = when {
        supportedLoaders.isNotEmpty() -> ModLoader.entries.filter { it in supportedLoaders }
        else -> ModLoader.entries.toList()
    }

    val supportedSingle = supportedLoaders.size == 1
    val loaderLocked = supportedSingle    // 로더가 하나뿐이면 선택 불가, 표시만

    // 자동 감지 조합이 있으면 그걸 1순위로 쓴다. (버전/로더 따로 고를 필요 X)
    // 비어있으면 = 감지 실패 → 아래 정적 폴백(수동 버전/로더 선택) 으로 떨어진다.
    val hasCombos = supportedCombos.isNotEmpty()
    var selectedCombo by remember(supportedCombos) {
        mutableStateOf(supportedCombos.firstOrNull())
    }

    // ── 정적 폴백 (조합 감지 실패 시에만 사용) ──
    val fallbackVersions = listOf("26.2", "1.21.8", "1.21.6", "1.21.5", "1.21.4", "1.21.1", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2")
    var selectedVersion by remember { mutableStateOf(fallbackVersions.first()) }
    var selectedLoader by remember {
        mutableStateOf<ModLoader?>(
            when {
                supportedSingle -> supportedLoaders.first()
                allowVanilla    -> null
                else            -> loaderOptions.firstOrNull() ?: ModLoader.FABRIC
            }
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(dialogWidthRatio)
                    // 짧은 기기에서도 버튼이 안 잘리도록 화면 90%로 상한만 두고, 내용은 wrap
                    .heightIn(max = maxDialogHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {}
                    // 키보드/내비게이션 바가 떠도 내용이 안 가려지게
                    .imePadding()
                    .padding(outerPad),
                verticalArrangement = Arrangement.spacedBy(verticalGap)
            ) {
                // ── 헤더 (고정) ──
                Text("${contentType.label} 설치 대상 선택",
                    color = TextMain, fontSize = titleSize, fontWeight = FontWeight.Bold)

                // ── 스크롤 영역 (헤더와 하단 버튼을 제외한 전부) ──
                //   하나의 LazyColumn 으로 묶어 weight(1f) 를 줘서
                //   내용이 길어도 하단 버튼이 절대 잘리지 않게 한다.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(verticalGap)
                ) {
                    // ── 안내 줄들 ──
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val supportLine = when {
                                supportedLoaders.isEmpty() && contentType.requiresModLoader ->
                                    "지원 로더 정보를 가져올 수 없어 모든 로더를 표시합니다."
                                supportedLoaders.isEmpty() ->
                                    "이 콘텐츠는 로더와 무관합니다."
                                supportedSingle ->
                                    "이 모드는 ${supportedLoaders.first().displayName} 전용입니다."
                                else ->
                                    "지원 로더: ${supportedLoaders.joinToString(", ") { it.displayName }}"
                            }
                            Text(supportLine, color = TextSub, fontSize = descSize)

                            Text(
                                text = when {
                                    hideCreateNew && existingInstances.isEmpty() ->
                                        "설치할 수 있는 인스턴스가 없습니다."
                                    hideCreateNew ->
                                        "데이터팩을 추가할 인스턴스를 선택하세요. (다음 단계에서 월드를 고릅니다)"
                                    existingInstances.isEmpty() ->
                                        "호환 인스턴스 없음 — 새로 만듭니다."
                                    else -> "기존 인스턴스에 추가하거나, 새로 만들 수 있습니다."
                                },
                                color = TextSub, fontSize = descSize
                            )
                        }
                    }

                    // ── 기존 인스턴스 ──
                    if (existingInstances.isNotEmpty()) {
                        item {
                            Text("기존 인스턴스 (호환되는 것만)", color = TextMain,
                                fontSize = sectionSize, fontWeight = FontWeight.Bold)
                        }
                        items(existingInstances) { inst ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BgDark)
                                    .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                                    .clickable { onUseExisting(inst) }
                                    .padding(horizontal = listItemPadH, vertical = listItemPadV),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(inst.name, color = TextMain,
                                        fontSize = itemTitleSize, fontWeight = FontWeight.Bold)
                                    Text(
                                        "MC ${inst.gameVersion} · ${inst.loader?.displayName ?: "Vanilla"}",
                                        color = TextSub, fontSize = itemSubSize
                                    )
                                }
                                Text("선택", color = Pink,
                                    fontSize = labelSize, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // ── 새 인스턴스 만들기 (데이터팩이면 숨김) ──
                    if (!hideCreateNew) {
                        if (existingInstances.isNotEmpty()) {
                            item { HorizontalDivider(color = BgBorder) }
                        }
                        item {
                            Text("새 인스턴스 만들기", color = TextMain,
                                fontSize = sectionSize, fontWeight = FontWeight.Bold)
                        }

                        if (hasCombos) {
                            // ── 자동 감지된 (버전 × 로더) 조합을 버전별로 그룹지어 리스트로 표시 ──
                            item {
                                Text("호환 버전 / 로더 (자동 감지)", color = TextSub, fontSize = pickerLabelSize)
                            }
                            // 버전 단위로 묶기 (combos 는 이미 최신 버전 → 로더 순으로 정렬돼 있음)
                            val grouped: Map<String, List<VersionLoaderCombo>> =
                                supportedCombos.groupBy { it.mcVersion }

                            grouped.forEach { (version, combos) ->
                                // 버전 헤더 (이름 포함)
                                item(key = "ver_$version") {
                                    val nick = mcVersionNickname(version)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Text("MC $version", color = TextMain,
                                            fontSize = itemTitleSize, fontWeight = FontWeight.Bold)
                                        if (nick != null) {
                                            Text("· $nick", color = TextSub, fontSize = itemSubSize,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                // 해당 버전의 로더 행들
                                items(combos, key = { "${it.mcVersion}_${it.loader?.name ?: "vanilla"}" }) { combo ->
                                    val sel = combo == selectedCombo
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) Pink.copy(alpha = 0.18f) else BgDark)
                                            .border(1.dp, if (sel) Pink else BgBorder, RoundedCornerShape(8.dp))
                                            .clickable { selectedCombo = combo }
                                            .padding(horizontal = listItemPadH, vertical = listItemPadV),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 선택 표시 라디오 점
                                        Box(
                                            modifier = Modifier
                                                .size(if (tablet) 16.dp else 14.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(if (sel) Pink else Color.Transparent)
                                                .border(
                                                    1.5.dp,
                                                    if (sel) Pink else TextSub,
                                                    RoundedCornerShape(50)
                                                )
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            combo.loader?.displayName ?: "Vanilla",
                                            color = if (sel) TextMain else TextSub,
                                            fontSize = itemTitleSize,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (sel) {
                                            Text("선택됨", color = Pink,
                                                fontSize = labelSize, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            // ── 폴백: 조합 감지 실패 시 수동으로 버전/로더 선택 ──
                            item {
                                Text("버전", color = TextSub, fontSize = pickerLabelSize)
                            }
                            item {
                                androidx.compose.foundation.lazy.LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(sectionGap)
                                ) {
                                    items(fallbackVersions) { v ->
                                        val sel = v == selectedVersion
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(if (sel) Pink else BgDark)
                                                .border(1.dp, if (sel) Pink else BgBorder, RoundedCornerShape(16.dp))
                                                .clickable { selectedVersion = v }
                                                .padding(horizontal = chipPadH, vertical = chipPadV)
                                        ) {
                                            Text(v,
                                                color = if (sel) Color.White else TextSub,
                                                fontSize = chipSize,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }

                            item {
                                Text(
                                    when {
                                        loaderLocked -> "로더 (이 모드 전용)"
                                        allowVanilla -> "타입"
                                        else         -> "모드 로더"
                                    },
                                    color = TextSub, fontSize = pickerLabelSize
                                )
                            }
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(sectionGap)
                                ) {
                                    // Vanilla 칩 — allowVanilla 일 때만
                                    if (allowVanilla) {
                                        val sel = selectedLoader == null
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) Pink else BgDark)
                                                .border(1.dp, if (sel) Pink else BgBorder, RoundedCornerShape(8.dp))
                                                .clickable { selectedLoader = null }
                                                .padding(vertical = loaderRowPadV),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("Vanilla",
                                                color = if (sel) Color.White else TextSub,
                                                fontSize = chipSize,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }

                                    loaderOptions.forEach { loader ->
                                        val sel = loader == selectedLoader
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) Pink else BgDark)
                                                .border(1.dp, if (sel) Pink else BgBorder, RoundedCornerShape(8.dp))
                                                .let {
                                                    if (loaderLocked) it
                                                    else it.clickable { selectedLoader = loader }
                                                }
                                                .padding(vertical = loaderRowPadV),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(loader.displayName,
                                                color = if (sel) Color.White else TextSub,
                                                fontSize = chipSize,
                                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 하단 버튼 (고정) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sectionGap)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(actionButtonH),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSub)
                    ) { Text("취소", fontSize = buttonSize) }
                    // 새로 만들고 설치 버튼 — 데이터팩이면 숨김(인스턴스 선택만)
                    if (!hideCreateNew) {
                        // 조합 모드면 selectedCombo, 폴백이면 selectedVersion/selectedLoader 사용
                        val createEnabled = if (hasCombos) selectedCombo != null else true
                        Button(
                            onClick = {
                                if (hasCombos) {
                                    selectedCombo?.let { onCreateNew(it.mcVersion, it.loader) }
                                } else {
                                    onCreateNew(selectedVersion, selectedLoader)
                                }
                            },
                            enabled = createEnabled,
                            modifier = Modifier.weight(1f).height(actionButtonH),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Pink,
                                disabledContainerColor = BgDark
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("새로 만들고 설치",
                                color = if (createEnabled) Color.White else TextSub,
                                fontSize = buttonSize, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
/**
 * 데이터팩 설치 시, 인스턴스 선택 후 그 인스턴스의 월드를 고르는 다이얼로그.
 * 월드는 saves/ 아래 level.dat 가 있는 폴더만 후보로 들어온다(호출부에서 listWorldsForInstance 로 필터).
 */
@Composable
private fun WorldSelectDialog(
    worlds: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val tablet = isTablet()

    val Pink = Color(0xFFE91E8C)
    val BgSurface = Color(0xFF1E0E1A)
    val BgBorder = Color(0xFF3D1A32)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    val titleSize = if (tablet) 18.sp else 14.sp
    val descSize  = if (tablet) 13.sp else 11.sp
    val itemSize  = if (tablet) 14.sp else 12.sp
    val buttonSize = if (tablet) 13.sp else 11.sp
    val itemPadH  = if (tablet) 14.dp else 12.dp
    val itemPadV  = if (tablet) 12.dp else 10.dp

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = {
            Text("데이터팩을 넣을 월드 선택", color = TextMain, fontSize = titleSize, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "선택한 월드의 datapacks 폴더에 설치됩니다.\n게임에서 해당 월드를 열면 자동으로 적용됩니다.",
                    color = TextSub,
                    fontSize = descSize
                )
                Spacer(Modifier.height(4.dp))
                worlds.forEach { world ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                            .clickable { onSelect(world) }
                            .padding(horizontal = itemPadH, vertical = itemPadV)
                    ) {
                        Text(
                            text = "🗺️ $world",
                            color = TextMain,
                            fontSize = itemSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", color = TextSub, fontSize = buttonSize)
            }
        },
    )
}