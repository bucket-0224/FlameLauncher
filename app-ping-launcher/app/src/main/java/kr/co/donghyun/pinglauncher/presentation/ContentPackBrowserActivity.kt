package kr.co.donghyun.pinglauncher.presentation

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.BuildConfig
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeFile
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeListResponse
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeLogo
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeMod
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.data.instance.InstanceMeta
import kr.co.donghyun.pinglauncher.data.instance.InstanceType
import kr.co.donghyun.pinglauncher.data.mojang.DownloadPhase
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.screen.ContentPackBrowserScreen
import kr.co.donghyun.pinglauncher.presentation.ui.screen.ContentType
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PingLauncherTheme
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 컨텐츠(모드팩/모드/텍스처팩/쉐이더팩) 브라우저 액티비티.
 *
 * 책임:
 *  - CurseForge 검색 호출 및 페이징 (debounce 포함)
 *  - 컨텐츠 타입(classId) / 버전 / 검색어 필터 상태 관리
 *  - 설치된 인스턴스(ID 집합) 추적 (설치 여부 표시용)
 *  - ContentPackDetailActivity 결과 수신 후 실제 설치/실행 트리거
 *
 * CurseForge API 게임 ID: 432 (Minecraft)
 */
class ContentPackBrowserActivity : BaseActivity() {

    // ───── 화면 상태 ─────
    private val _contentPacks = MutableStateFlow<List<CurseForgeMod>>(emptyList())
    private val _progress = MutableStateFlow(DownloadProgress())
    private val _isLoading = MutableStateFlow(false)
    private val _isInstalling = MutableStateFlow(false)
    private val _installingModId = MutableStateFlow<Int?>(null)
    private val _statusMessage = MutableStateFlow("")
    private val _selectedVersion = MutableStateFlow("")
    private val _selectedContentType = MutableStateFlow(ContentType.MODPACK)
    private val _installedIds = MutableStateFlow<Set<Int>>(emptySet())
    private val _hasMore = MutableStateFlow(true)

    // ───── 내부 상태 ─────
    private var currentQuery: String = ""
    private var currentPageIndex: Int = 0
    private val pageSize: Int = 20
    private var searchJob: Job? = null
    private var pendingInstallMod: CurseForgeMod? = null  // 결과 처리 시 어떤 mod였는지 기억

    private val httpClient = OkHttpClient()
    private val gson = Gson()

    /** Detail Activity의 결과 수신 */
    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        val modId = data.getIntExtra(ContentPackDetailActivity.EXTRA_MOD_ID, -1)
        val action = data.getStringExtra("action") ?: return@registerForActivityResult
        val mod = _contentPacks.value.firstOrNull { it.id == modId } ?: pendingInstallMod

        when (action) {
            "install" -> {
                if (mod == null) {
                    Log.w("PING_LAUNCHER", "설치 대상 mod 정보를 찾지 못함 (id=$modId)")
                    return@registerForActivityResult
                }

                val targetInstanceId = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_INSTANCE_ID)
                val targetVersion = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_VERSION)
                val targetLoader = data.getStringExtra(ContentPackDetailActivity.EXTRA_TARGET_LOADER)
                    ?.let { runCatching { ModLoader.valueOf(it) }.getOrNull() }

                val contentType = _selectedContentType.value
                when {
                    targetInstanceId != null -> installToExistingInstance(mod, targetInstanceId, contentType)
                    targetVersion != null && targetLoader != null ->
                        installToNewInstance(mod, targetVersion, targetLoader, contentType)
                    else -> installDirect(mod, contentType)
                }
            }
            "launch" -> {
                if (mod != null) launchMod(mod)
            }
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )

        refreshInstalledIds()

        setContent {
            PingLauncherTheme {
                val contentPacks by _contentPacks.asStateFlow().collectAsState()
                val progress by _progress.asStateFlow().collectAsState()
                val isLoading by _isLoading.asStateFlow().collectAsState()
                val isInstalling by _isInstalling.asStateFlow().collectAsState()
                val installingModId by _installingModId.asStateFlow().collectAsState()
                val statusMessage by _statusMessage.asStateFlow().collectAsState()
                val selectedVersion by _selectedVersion.asStateFlow().collectAsState()
                val selectedContentType by _selectedContentType.asStateFlow().collectAsState()
                val installedIds by _installedIds.asStateFlow().collectAsState()
                val hasMore by _hasMore.asStateFlow().collectAsState()

                ContentPackBrowserScreen(
                    contentPacks = contentPacks,
                    progress = progress,
                    isLoading = isLoading,
                    isInstalling = isInstalling,
                    installingModId = installingModId,
                    statusMessage = statusMessage,
                    selectedVersion = selectedVersion,
                    selectedContentType = selectedContentType,
                    installedIds = installedIds,
                    onSearch = { query, version, type -> debouncedSearch(query, version, type) },
                    onVersionFilter = { _selectedVersion.value = it },
                    onContentTypeFilter = { _selectedContentType.value = it },
                    onLoadMore = { loadMore() },
                    hasMore = hasMore,
                    onInstall = { mod -> openDetailForInstall(mod) },
                    onLaunch = { mod -> launchMod(mod) }
                )
            }
        }

        // 초기 로딩
        debouncedSearch("", "", ContentType.MODPACK)
    }

    override fun onResume() {
        super.onResume()
        refreshInstalledIds()
    }

    // ───── 검색 / 페이징 ─────

    /** 입력 디바운싱 적용 검색. 새 검색 시 페이지/리스트 초기화 */
    private fun debouncedSearch(query: String, version: String, type: ContentType) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(250)
            currentQuery = query
            _selectedVersion.value = version
            _selectedContentType.value = type
            currentPageIndex = 0
            _contentPacks.value = emptyList()
            _hasMore.value = true
            performSearch(reset = true)
        }
    }

    private fun loadMore() {
        if (_isLoading.value || !_hasMore.value) return
        lifecycleScope.launch { performSearch(reset = false) }
    }

    private suspend fun performSearch(reset: Boolean) {
        _isLoading.value = true
        try {
            val results = withContext(Dispatchers.IO) {
                fetchCurseForgeSearch(
                    query = currentQuery,
                    classId = _selectedContentType.value.classId,
                    gameVersion = _selectedVersion.value,
                    index = currentPageIndex
                )
            }
            val merged = if (reset) results else _contentPacks.value + results
            // 같은 id 중복 제거 (페이징 경계에서 안전장치)
            _contentPacks.value = merged.distinctBy { it.id }
            currentPageIndex += results.size
            _hasMore.value = results.size >= pageSize
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "검색 실패: ${e.message}")
            _hasMore.value = false
        } finally {
            _isLoading.value = false
        }
    }

    /** CurseForge /v1/mods/search 호출 */
    private fun fetchCurseForgeSearch(
        query: String,
        classId: Int,
        gameVersion: String,
        index: Int
    ): List<CurseForgeMod> {
        val urlBuilder = StringBuilder("https://api.curseforge.com/v1/mods/search")
            .append("?gameId=432")
            .append("&classId=").append(classId)
            .append("&index=").append(index)
            .append("&pageSize=").append(pageSize)
            .append("&sortField=2")    // Popularity
            .append("&sortOrder=desc")

        if (query.isNotBlank()) urlBuilder.append("&searchFilter=").append(java.net.URLEncoder.encode(query, "UTF-8"))
        if (gameVersion.isNotBlank()) urlBuilder.append("&gameVersion=").append(gameVersion)

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return emptyList()
            val type = object : TypeToken<CurseForgeListResponse<CurseForgeMod>>() {}.type
            return runCatching {
                gson.fromJson<CurseForgeListResponse<CurseForgeMod>>(body, type).data
            }.getOrElse {
                Log.e("PING_LAUNCHER", "검색 응답 파싱 실패: ${it.message}")
                emptyList()
            }
        }
    }

    // ───── 설치 흐름 ─────

    /** 카드의 "설치" 버튼 → Detail Activity 띄우고 거기서 타겟을 받아옴 */
    private fun openDetailForInstall(mod: CurseForgeMod) {
        pendingInstallMod = mod
        val intent = Intent(this, ContentPackDetailActivity::class.java).apply {
            putExtra(ContentPackDetailActivity.EXTRA_MOD_ID, mod.id)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_NAME, mod.name)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_SUMMARY, mod.summary)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_LOGO, mod.logo?.url)
            putExtra(ContentPackDetailActivity.EXTRA_MOD_DOWNLOADS, mod.downloadCount)
            putExtra(ContentPackDetailActivity.EXTRA_CONTENT_TYPE, _selectedContentType.value.name)
        }
        detailLauncher.launch(intent)
    }

    /** 추가 정보 없이 바로 설치 (모드팩/텍스처팩/쉐이더팩 케이스) */
    private fun installDirect(mod: CurseForgeMod, contentType: ContentType) {
        lifecycleScope.launch {
            beginInstall(mod, "${contentType.label} 설치 중...")
            try {
                when (contentType) {
                    ContentType.MODPACK -> installModpack(mod)
                    ContentType.TEXTURE_PACK -> installResourceArtifact(mod, contentType)
                    ContentType.SHADER_PACK -> installResourceArtifact(mod, contentType)
                    ContentType.MOD -> {
                        // requiresLoader=true이므로 이 경로로 오면 안 됨
                        Log.w("PING_LAUNCHER", "MOD는 installDirect 경로로 오면 안 됨")
                    }
                }
                refreshInstalledIds()
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "설치 실패: ${e.message}")
            } finally {
                endInstall()
            }
        }
    }

    /** 기존 인스턴스에 모드 추가 설치 */
    private fun installToExistingInstance(
        mod: CurseForgeMod,
        instanceId: String,
        contentType: ContentType
    ) {
        lifecycleScope.launch {
            beginInstall(mod, "${mod.name} → 인스턴스($instanceId) 설치 중...")
            try {
                val meta = InstanceManager.loadMeta(InstanceManager.instanceDir(this@ContentPackBrowserActivity, instanceId))
                    ?: throw IllegalStateException("인스턴스 메타를 찾을 수 없음: $instanceId")

                val targetDir = InstanceManager.instanceDir(this@ContentPackBrowserActivity, instanceId)
                val file = withContext(Dispatchers.IO) {
                    fetchLatestFileForVersion(mod.id, meta.mcVersion, meta.loaderType)
                }
                if (file == null) {
                    Log.w("PING_LAUNCHER", "호환되는 파일을 찾지 못함: mod=${mod.id}, mc=${meta.mcVersion}, loader=${meta.loaderType}")
                    return@launch
                }
                val downloadUrl = file.downloadUrl
                if (downloadUrl.isNullOrBlank()) {
                    Log.w("PING_LAUNCHER", "다운로드 URL이 없음 (배포 차단된 파일일 수 있음): mod=${mod.id}, file=${file.id}")
                    return@launch
                }

                val subDir = when (contentType) {
                    ContentType.MOD -> "mods"
                    ContentType.TEXTURE_PACK -> "resourcepacks"
                    ContentType.SHADER_PACK -> "shaderpacks"
                    ContentType.MODPACK -> "mods" // 사실상 여기로 오진 않음
                }
                val outDir = targetDir.resolve(".minecraft").resolve(subDir).also { it.mkdirs() }
                withContext(Dispatchers.IO) {
                    downloadFile(downloadUrl, outDir.resolve(file.fileName), file.fileName)
                }
                refreshInstalledIds()
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "기존 인스턴스 설치 실패: ${e.message}")
            } finally {
                endInstall()
            }
        }
    }

    /** 새 인스턴스(버전+로더) 생성 후 설치 */
    private fun installToNewInstance(
        mod: CurseForgeMod,
        mcVersion: String,
        loader: ModLoader,
        contentType: ContentType
    ) {
        lifecycleScope.launch {
            beginInstall(mod, "${loader.displayName} $mcVersion 인스턴스 생성 후 설치 중...")
            try {
                val loaderType = when (loader) {
                    ModLoader.FABRIC -> "fabric"
                    ModLoader.FORGE -> "forge"
                    ModLoader.NEOFORGE -> "neoforge"
                }

                // 인스턴스 ID는 fabric인 경우 InstanceManager.fabricId 사용, 그 외엔 동일 패턴으로 생성
                val instanceId = when (loader) {
                    ModLoader.FABRIC -> InstanceManager.fabricId(mcVersion, "latest")
                    ModLoader.FORGE -> "forge_${mcVersion.replace('.', '_')}"
                    ModLoader.NEOFORGE -> "neoforge_${mcVersion.replace('.', '_')}"
                }

                // 인스턴스 메타 저장 (loader별 실제 셋업은 별도 인스톨러 모듈에서 진행되어야 함)
                val meta = InstanceMeta(
                    id = instanceId,
                    name = "${loader.displayName} $mcVersion",
                    type = if (loader == ModLoader.FABRIC) InstanceType.FABRIC else InstanceType.MODPACK,
                    mcVersion = mcVersion,
                    loaderType = loaderType
                )
                InstanceManager.saveMeta(this@ContentPackBrowserActivity, meta)

                // 새 인스턴스에 컨텐츠 설치
                installToExistingInstance(mod, instanceId, contentType)
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "신규 인스턴스 설치 실패: ${e.message}")
                endInstall()
            }
        }
    }

    /** 모드팩 설치 (자체 인스턴스 생성). zip 추출/매니페스트 처리는 별도 인스톨러로 위임. */
    private suspend fun installModpack(mod: CurseForgeMod) {
        val instanceId = InstanceManager.modpackId(mod.name)
        val file = withContext(Dispatchers.IO) {
            fetchLatestFileForVersion(mod.id, gameVersion = null, loaderType = null)
        } ?: return
        val downloadUrl = file.downloadUrl
        if (downloadUrl.isNullOrBlank()) {
            Log.w("PING_LAUNCHER", "모드팩 다운로드 URL 없음: mod=${mod.id}")
            return
        }

        // mcVersion / loaderType 은 모드팩 매니페스트(manifest.json)에서 정확하게 가져와야 하지만,
        // 매니페스트 추출 전 임시로 gameVersions 필드에서 추정. 추출 후 saveMeta 재호출로 보정 권장.
        val meta = InstanceMeta(
            id = instanceId,
            name = mod.name,
            type = InstanceType.MODPACK,
            mcVersion = file.primaryMcVersion() ?: "",
            loaderType = file.primaryLoader()
        )
        InstanceManager.saveMeta(this, meta)

        val dir = InstanceManager.instanceDir(this, instanceId).also { it.mkdirs() }
        withContext(Dispatchers.IO) {
            downloadFile(downloadUrl, dir.resolve(file.fileName), file.fileName)
        }
        // TODO: zip 추출 및 manifest.json 기반 모드/오버라이드 처리는 ModpackInstaller에 위임
    }

    /** 텍스처팩/쉐이더팩 등 인스턴스 비종속 컨텐츠 설치 (글로벌 폴더에 저장) */
    private suspend fun installResourceArtifact(mod: CurseForgeMod, contentType: ContentType) {
        val file = withContext(Dispatchers.IO) {
            fetchLatestFileForVersion(mod.id, gameVersion = null, loaderType = null)
        } ?: return
        val downloadUrl = file.downloadUrl
        if (downloadUrl.isNullOrBlank()) {
            Log.w("PING_LAUNCHER", "리소스/쉐이더 다운로드 URL 없음: mod=${mod.id}")
            return
        }

        val subDir = when (contentType) {
            ContentType.TEXTURE_PACK -> "resourcepacks"
            ContentType.SHADER_PACK -> "shaderpacks"
            else -> return
        }
        val root = getExternalFilesDir(null) ?: return
        val outDir = root.resolve(subDir).also { it.mkdirs() }
        withContext(Dispatchers.IO) {
            downloadFile(downloadUrl, outDir.resolve(file.fileName), file.fileName)
        }
    }

    // ───── 보조: CurseForge 파일 조회/다운로드 ─────

    /**
     * 특정 mod의 최신 파일. gameVersion / loaderType 으로 필터 가능.
     * - loaderType: "fabric" / "forge" / "neoforge" / "quilt" (null이면 전체)
     */
    private fun fetchLatestFileForVersion(
        modId: Int,
        gameVersion: String?,
        loaderType: String?
    ): CurseForgeFile? {
        val url = StringBuilder("https://api.curseforge.com/v1/mods/$modId/files?index=0&pageSize=20")
        if (!gameVersion.isNullOrBlank()) url.append("&gameVersion=").append(gameVersion)
        if (!loaderType.isNullOrBlank()) {
            val modLoaderType = when (loaderType.lowercase()) {
                "forge" -> 1
                "fabric" -> 4
                "quilt" -> 5
                "neoforge" -> 6
                else -> null
            }
            if (modLoaderType != null) url.append("&modLoaderType=").append(modLoaderType)
        }

        val request = Request.Builder()
            .url(url.toString())
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return null
            val type = object : TypeToken<CurseForgeListResponse<CurseForgeFile>>() {}.type
            return runCatching {
                gson.fromJson<CurseForgeListResponse<CurseForgeFile>>(body, type).data.firstOrNull()
            }.getOrNull()
        }
    }

    /** 파일의 gameVersions에서 실제 마인크래프트 버전(예: "1.20.1")만 추출 */
    private fun CurseForgeFile.primaryMcVersion(): String? =
        gameVersions.firstOrNull { it.matches(Regex("\\d+\\.\\d+(\\.\\d+)?")) }

    /** 파일의 gameVersions에서 로더명("fabric"/"forge"/"neoforge"/"quilt") 추출 */
    private fun CurseForgeFile.primaryLoader(): String? =
        gameVersions.firstOrNull {
            it.equals("Fabric", true) || it.equals("Forge", true)
                    || it.equals("NeoForge", true) || it.equals("Quilt", true)
        }?.lowercase()

    /**
     * URL → destination 다운로드. 진행률은 _progress 로 publish.
     * DownloadProgress.current/total 이 Int 라서 KB 단위로 환산해 안전 범위 유지.
     */
    private fun downloadFile(url: String, destination: java.io.File, displayName: String) {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { response ->
            val body = response.body ?: return
            val contentLen = body.contentLength()
            val totalKb = if (contentLen > 0) (contentLen / 1024).toInt().coerceAtLeast(1) else 0
            _progress.value = DownloadProgress(
                phase = DownloadPhase.DOWNLOADING_CLIENT,
                current = 0,
                total = totalKb,
                fileName = displayName
            )

            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        total += read
                        if (totalKb > 0) {
                            _progress.value = DownloadProgress(
                                phase = DownloadPhase.DOWNLOADING_CLIENT,
                                current = (total / 1024).toInt().coerceAtMost(totalKb),
                                total = totalKb,
                                fileName = displayName
                            )
                        }
                    }
                }
            }

            _progress.value = DownloadProgress(
                phase = DownloadPhase.DONE,
                current = totalKb,
                total = totalKb,
                fileName = displayName
            )
        }
    }

    // ───── 인스턴스 ID 추적 (설치 여부 표시) ─────

    private fun refreshInstalledIds() {
        lifecycleScope.launch(Dispatchers.IO) {
            val instances = InstanceManager.listInstances(this@ContentPackBrowserActivity)
            // modpackId는 modpack_NAME 패턴 - 현재는 mod ID와 직접 매핑 X
            // 대신 인스턴스 이름과 검색 결과의 name을 매칭하는 단순 휴리스틱을 사용.
            // 더 견고하게 하려면 InstanceMeta에 sourceModId 같은 필드를 추가하는 게 좋음.
            val installedNames = instances.map { it.name }.toSet()
            val ids = _contentPacks.value.filter { it.name in installedNames }.map { it.id }.toSet()
            _installedIds.value = ids
        }
    }

    private fun launchMod(mod: CurseForgeMod) {
        // 인스턴스 실행은 별도 LaunchActivity / Launcher 모듈로 위임.
        Log.d("PING_LAUNCHER", "실행 요청: ${mod.name}")
        // TODO: 실제 게임 런처 호출 (예: GameLauncher.launch(this, instanceId))
    }

    private fun beginInstall(mod: CurseForgeMod, message: String) {
        _isInstalling.value = true
        _installingModId.value = mod.id
        _statusMessage.value = message
        _progress.value = DownloadProgress()
    }

    private fun endInstall() {
        _isInstalling.value = false
        _installingModId.value = null
        _statusMessage.value = ""
        _progress.value = DownloadProgress()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ContentPackBrowserActivity::class.java))
        }
    }
}