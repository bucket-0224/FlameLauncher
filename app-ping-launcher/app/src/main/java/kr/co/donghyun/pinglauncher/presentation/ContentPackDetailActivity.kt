package kr.co.donghyun.pinglauncher.presentation

import ContentPackDetailScreen
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import okhttp3.OkHttpClient
import okhttp3.Request

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

class ContentPackDetailActivity : BaseActivity() {

    private val _detail = MutableStateFlow<ContentDetail?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _isInstalled = MutableStateFlow(false)
    private val _fullscreenIndex = MutableStateFlow<Int?>(null)
    private val _showInstallTargetDialog = MutableStateFlow(false)
    private val _loaderInstances = MutableStateFlow<List<InstanceSummary>>(emptyList())

    private val _supportedLoaders = MutableStateFlow<Set<ModLoader>>(emptySet())

    companion object {
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

                    if (showInstallDialog) {
                        InstallTargetDialog(
                            contentType = contentType,
                            // allowVanilla: 텍스처/쉐이더 같이 로더 불문 콘텐츠이고
                            //               감지된 로더도 없을 때만
                            allowVanilla = !contentType.requiresModLoader && supportedLoaders.isEmpty(),
                            supportedLoaders = supportedLoaders,         // ★ 추가
                            existingInstances = loaderInstances,
                            onDismiss = { _showInstallTargetDialog.value = false },
                            onUseExisting = { instance ->
                                _showInstallTargetDialog.value = false
                                setResult(RESULT_OK, Intent()
                                    .putExtra(EXTRA_MOD_ID, modId)
                                    .putExtra("action", "install")
                                    .putExtra(EXTRA_TARGET_INSTANCE_ID, instance.id))
                                finish()
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

    private fun handleInstallRequest(modId: Int, contentType: ContentType) {
        if (!contentType.needsTargetInstance) {
            setResult(RESULT_OK, Intent()
                .putExtra(EXTRA_MOD_ID, modId)
                .putExtra("action", "install"))
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 1) 이 모드가 지원하는 로더 집합 감지
            val supported = detectSupportedLoaders(modId)
            _supportedLoaders.value = supported

            // 2) Vanilla 허용 여부:
            //    - 텍스처/쉐이더 (requiresModLoader=false) 이고
            //    - 감지된 로더가 없는 경우 (= 로더 무관 자원)
            //    두 조건 모두 만족할 때만 바닐라 후보
            val includeVanilla = !contentType.requiresModLoader && supported.isEmpty()

            // 3) 인스턴스 목록도 같은 로더 필터 적용
            _loaderInstances.value = scanInstances(includeVanilla, supported)
            _showInstallTargetDialog.value = true
        }
    }

    /**
     * CurseForge files API 한 번 쳐서 gameVersions 의 로더 태그를 모은다.
     *  - 최신 50개 파일을 보면 거의 모든 활성 모드는 지원 로더가 다 잡힘
     *  - 네트워크 실패 / 응답 비어있음 → emptySet (호출자가 폴백 처리)
     */
    private fun detectSupportedLoaders(modId: Int): Set<ModLoader> {
        val client = OkHttpClient()
        val req = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId/files?pageSize=50&index=0")
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return emptySet()
                val type = object : TypeToken<CurseForgeListResponse<CurseForgeFile>>(){}.type
                val files = Gson().fromJson<CurseForgeListResponse<CurseForgeFile>>(body, type).data

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
                Log.d("PING_LAUNCHER",
                    "🔍 mod $modId supported loaders: $out (files=${files.size})")
                out
            }
        } catch (e: Exception) {
            Log.w("PING_LAUNCHER", "로더 감지 실패: ${e.message}")
            emptySet()
        }
    }

    /**
     * 설치된 인스턴스 목록. [includeVanilla]=true면 로더 없는 바닐라도 포함.
     */
    private fun scanInstances(
        includeVanilla: Boolean,
        supportedLoaders: Set<ModLoader> = emptySet(),
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
    existingInstances: List<InstanceSummary>,
    onDismiss: () -> Unit,
    onUseExisting: (InstanceSummary) -> Unit,
    onCreateNew: (version: String, loader: ModLoader?) -> Unit,
) {
    val tablet = isTablet()

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

    val supportedVersions = listOf("26.2", "1.21.8", "1.21.6", "1.21.5", "1.21.4", "1.21.1", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2")
    var selectedVersion by remember { mutableStateOf(supportedVersions.first()) }

    // ── 기본 선택 로더 ──
    //   1) 지원 로더가 정확히 1개면 강제로 그것
    //   2) Vanilla 허용이면 null
    //   3) 아니면 후보 첫 번째 (없으면 Fabric)
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
                    .heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {}
                    .verticalScroll(rememberScrollState())
                    .padding(outerPad),
                verticalArrangement = Arrangement.spacedBy(verticalGap)
            ) {
                Text("${contentType.label} 설치 대상 선택",
                    color = TextMain, fontSize = titleSize, fontWeight = FontWeight.Bold)

                // ── 지원 로더 안내 줄 ──
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
                    text = if (existingInstances.isEmpty())
                        "호환 인스턴스 없음 — 새로 만듭니다."
                    else "기존 인스턴스에 추가하거나, 새로 만들 수 있습니다.",
                    color = TextSub, fontSize = descSize
                )

                // ── 기존 인스턴스 ─────────────────────────────
                if (existingInstances.isNotEmpty()) {
                    Text("기존 인스턴스 (호환되는 것만)", color = TextMain,
                        fontSize = sectionSize, fontWeight = FontWeight.Bold)
                    // 부모 Column 이 이미 verticalScroll 이므로 여기서는 LazyColumn 대신
                    // 일반 Column 으로 나열한다. (스크롤 중첩 방지 + 항목이 잘리지 않음)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(sectionGap)
                    ) {
                        existingInstances.forEach { inst ->
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
                    HorizontalDivider(color = BgBorder)
                }

                // ── 새 인스턴스 만들기 ────────────────────────
                Text("새 인스턴스 만들기", color = TextMain,
                    fontSize = sectionSize, fontWeight = FontWeight.Bold)

                Text("버전", color = TextSub, fontSize = pickerLabelSize)
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(sectionGap)
                ) {
                    items(supportedVersions) { v ->
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

                // ── 로더 칩 — supportedLoaders 가 1개면 잠금 표시 ──
                Text(
                    when {
                        loaderLocked -> "로더 (이 모드 전용)"
                        allowVanilla -> "타입"
                        else         -> "모드 로더"
                    },
                    color = TextSub, fontSize = pickerLabelSize
                )
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
                                    // 잠금 상태면 클릭 막음
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
                    Button(
                        onClick = { onCreateNew(selectedVersion, selectedLoader) },
                        modifier = Modifier.weight(1f).height(actionButtonH),
                        colors = ButtonDefaults.buttonColors(containerColor = Pink),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("새로 만들고 설치",
                            color = Color.White, fontSize = buttonSize, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}