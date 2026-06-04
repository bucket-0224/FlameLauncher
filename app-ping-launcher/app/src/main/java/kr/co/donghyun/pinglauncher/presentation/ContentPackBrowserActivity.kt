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
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeResponse
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.data.instance.InstanceMeta
import kr.co.donghyun.pinglauncher.data.instance.InstanceType
import kr.co.donghyun.pinglauncher.data.mojang.DownloadPhase
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
import kr.co.donghyun.pinglauncher.data.mojang.VersionEntry
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.screen.ContentPackBrowserScreen
import kr.co.donghyun.pinglauncher.presentation.ui.screen.ContentType
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PingLauncherTheme
import kr.co.donghyun.pinglauncher.presentation.util.fabric.FabricInstaller
import kr.co.donghyun.pinglauncher.presentation.util.fabric.FabricMetaAPI
import kr.co.donghyun.pinglauncher.presentation.util.forge.ForgeInstaller
import kr.co.donghyun.pinglauncher.presentation.util.forge.ForgeMetaAPI
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.MinecraftDownloader
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.VersionRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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

    // CurseForge Podium project ID (modrinth: "podium", CF slug: "podium-sodium")
    private val PODIUM_MOD_ID = 1209829   // ← CurseForge "Podium (Pojav x Sodium)"

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
                Log.d("PING_LAUNCHER",
                    "install request: mod=${mod.name} type=$contentType " +
                            "targetInstance=$targetInstanceId targetVersion=$targetVersion targetLoader=$targetLoader")

                when {
                    // 기존 인스턴스 선택
                    targetInstanceId != null ->
                        installToExistingInstance(mod, targetInstanceId, contentType)

                    // 새 인스턴스 — loader 가 null 이어도 Vanilla 로 진행되어야 함 (★ 수정 포인트)
                    targetVersion != null ->
                        installToNewInstance(mod, targetVersion, targetLoader, contentType)

                    // 그 외 (모드팩 등 — 타겟 선택 없이 바로 설치)
                    else ->
                        installDirect(mod, contentType)
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
                    else -> {
                        // ★ 여기 도달했다는 건 detailLauncher 분기에 버그가 있다는 뜻
                        Log.e("PING_LAUNCHER",
                            "❌ $contentType 가 installDirect 로 들어옴 — " +
                                    "detailLauncher 분기 확인 필요 (targetVersion/InstanceId 누락?)")
                        _statusMessage.value = "내부 오류: 설치 타겟이 지정되지 않음"
                    }
                }
                refreshInstalledIds()
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "설치 실패: ${e.message}", e)
            } finally {
                endInstall()
            }
        }
    }

    /**
     * Sodium 이 설치 대상에 있고, Podium 이 아직 없으면 Podium 도 같이 받도록 보강.
     * Sodium 의 PojavLauncher 차단을 무력화해주는 호환성 패치.
     */
    private fun augmentWithPodiumIfSodium(
        items: List<Pair<CurseForgeMod, CurseForgeFile>>,
        mcVersion: String,
        loaderType: String?
    ): List<Pair<CurseForgeMod, CurseForgeFile>> {
        val hasSodium = items.any { (m, _) ->
            m.name.equals("Sodium", ignoreCase = true) ||
                    m.name.startsWith("Sodium", ignoreCase = true) && !m.name.contains("Extra", true)
        }
        if (!hasSodium) return items
        if (items.any { it.first.id == PODIUM_MOD_ID }) return items  // 이미 포함
        if (loaderType?.lowercase() != "fabric" && loaderType?.lowercase() != "neoforge") {
            // Podium 은 Fabric/NeoForge 만 지원
            return items
        }

        val podiumMod  = fetchModInfo(PODIUM_MOD_ID) ?: return items
        val podiumFile = fetchLatestFileForVersion(PODIUM_MOD_ID, mcVersion, loaderType) ?: run {
            Log.w("PING_LAUNCHER", "Podium 호환 파일 못 찾음 mc=$mcVersion loader=$loaderType")
            return items
        }
        Log.d("PING_LAUNCHER", "🩹 Sodium 감지 → Podium 자동 추가: ${podiumFile.fileName}")
        return items + (podiumMod to podiumFile)
    }

    private suspend fun addContentToInstance(
        mod: CurseForgeMod,
        instanceId: String,
        contentType: ContentType
    ): Boolean {
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        val meta = InstanceManager.loadMeta(instanceDir)
            ?: throw IllegalStateException("인스턴스 메타 없음: $instanceId")

        val loaderFilter = if (contentType == ContentType.MOD) meta.loaderType else null
        Log.d("PING_LAUNCHER",
            "addContentToInstance: mod=${mod.id} type=$contentType " +
                    "instance=$instanceId mc=${meta.mcVersion} loaderFilter=$loaderFilter")

        // ── 1) 메인 파일 결정 ─────────────────────────────────────────
        val rootFile = withContext(Dispatchers.IO) {
            fetchLatestFileForVersion(mod.id, meta.mcVersion, loaderFilter)
        } ?: run {
            Log.w("PING_LAUNCHER", "❌ ${mod.name} — MC ${meta.mcVersion} 호환 파일 없음")
            _statusMessage.value = "${mod.name} — MC ${meta.mcVersion} 호환 파일 없음"
            return false
        }

        // ── 2) MOD 타입이면 의존성 재귀 해결 ─────────────────────────
        val deps = if (contentType == ContentType.MOD) {
            withContext(Dispatchers.IO) {
                resolveDependencies(rootFile, meta.mcVersion, loaderFilter)
            }
        } else emptyList()

        val withPodium = withContext(Dispatchers.IO) {
            augmentWithPodiumIfSodium(listOf(mod to rootFile) + deps, meta.mcVersion, loaderFilter)
        }
        val allItems = withPodium   // 기존: val allItems = listOf(mod to rootFile) + deps

        Log.d("PING_LAUNCHER",
            "📦 설치 대상 ${allItems.size}개 (메인 1 + 의존성 ${deps.size})")
        deps.forEach { (m, f) ->
            Log.d("PING_LAUNCHER", "  ↳ ${m.name} → ${f.fileName} (rt=${f.releaseType})")
        }

        // ── 3) 출력 디렉토리 결정 ────────────────────────────────────
        val subDir = when (contentType) {
            ContentType.MOD          -> "mods"
            ContentType.TEXTURE_PACK -> "resourcepacks"
            ContentType.SHADER_PACK  -> "shaderpacks"
            ContentType.MODPACK      -> "mods"
        }
        val baseDir = if (isLegacyVersion(meta.mcVersion))
            instanceDir.resolve(".minecraft") else instanceDir
        val outDir = baseDir.resolve(subDir).also { it.mkdirs() }

        // ── 4) 순차 다운로드 (충돌 jar 정리 → 다운로드) ──────────────
        var allOk = true
        allItems.forEachIndexed { idx, (m, f) ->
            _statusMessage.value = "[${idx + 1}/${allItems.size}] ${m.name} 다운로드 중..."

            // 같은 prefix의 다른 버전 jar 정리 (이번에 받을 파일과 정확히 같은 이름은 보존)
            if (contentType == ContentType.MOD) {
                withContext(Dispatchers.IO) { removeConflictingJars(outDir, f.fileName) }
            }

            val outFile = outDir.resolve(f.fileName)
            if (outFile.exists() && outFile.length() == f.fileLength && f.fileLength > 0) {
                Log.d("PING_LAUNCHER", "  → 이미 동일 파일 존재, 스킵: ${f.fileName}")
                return@forEachIndexed
            }

            val downloadUrl = resolveDownloadUrl(f)
            try {
                withContext(Dispatchers.IO) {
                    downloadFile(downloadUrl, outFile, f.fileName)
                }
                if (!outFile.exists() || outFile.length() == 0L) {
                    Log.e("PING_LAUNCHER", "  ❌ 검증 실패: ${outFile.absolutePath}")
                    allOk = false
                } else {
                    Log.d("PING_LAUNCHER",
                        "  ✅ ${f.fileName} (${outFile.length()}B)")
                }
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "  ❌ 예외: ${f.fileName} — ${e.message}", e)
                allOk = false
            }
        }

        // ── 5) 텍스처/쉐이더는 메인 파일에만 활성화 ──────────────────
        if (allOk) {
            withContext(Dispatchers.IO) {
                when (contentType) {
                    ContentType.TEXTURE_PACK -> enableResourcePack(baseDir, meta.mcVersion, rootFile.fileName)
                    ContentType.SHADER_PACK  -> enableShaderPack(baseDir, rootFile.fileName)
                    else -> { }
                }
            }
        }

        return allOk
    }

    // ContentPackBrowserActivity.kt 의 private 멤버로 추가

    /**
     * options.txt 의 resourcePacks 항목에 [packFileName] 을 등록한다.
     * - 1.13+ 는 "file/" 접두사 필요, 1.12 이하는 파일명 그대로.
     * - 이미 존재하면 추가하지 않음.
     * - options.txt 없으면 기본값으로 새로 만든다.
     */
    private fun enableResourcePack(baseDir: File, mcVersion: String, packFileName: String) {
        val optionsFile = File(baseDir, "options.txt")
        val packToken = if (isLegacyVersion(mcVersion)) packFileName else "file/$packFileName"

        val lines: MutableList<String> = if (optionsFile.exists())
            optionsFile.readLines().toMutableList()
        else mutableListOf("renderDistance:8", "graphicsMode:0")

        val idx = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        if (idx >= 0) {
            val raw = lines[idx].substringAfter("resourcePacks:")
            val current = parseRpList(raw).toMutableList()
            if (packToken !in current) current.add(packToken)        // 맨 뒤 = 우선순위 최상
            lines[idx] = "resourcePacks:" + serializeRpList(current)
        } else {
            lines.add("resourcePacks:" + serializeRpList(listOf("vanilla", packToken)))
        }
        if (lines.none { it.startsWith("incompatibleResourcePacks:") }) {
            lines.add("incompatibleResourcePacks:[]")
        }

        optionsFile.writeText(lines.joinToString("\n"))
        Log.d("PING_LAUNCHER", "📝 options.txt 갱신: $packToken (${optionsFile.absolutePath})")
    }

    /**
     * Iris/Oculus 의 config/iris.properties 에 [shaderFileName] 을 셋한다.
     * Iris/Oculus 가 설치되지 않은 인스턴스에서는 단순히 무시되는 hint 일 뿐 — 해는 없음.
     */
    private fun enableShaderPack(baseDir: File, shaderFileName: String) {
        val irisConfig = File(baseDir, "config/iris.properties")
        irisConfig.parentFile?.mkdirs()

        val survivors = if (irisConfig.exists())
            irisConfig.readLines().filter {
                !it.startsWith("shaderPack=") && !it.startsWith("shaders.enabled=")
            }
        else emptyList()

        val merged = survivors + listOf(
            "shaders.enabled=true",
            "shaderPack=$shaderFileName"
        )
        irisConfig.writeText(merged.joinToString("\n"))
        Log.d("PING_LAUNCHER", "📝 iris.properties 갱신: $shaderFileName")
    }

// ── options.txt 의 resourcePacks 값 파싱/직렬화 ──
//   resourcePacks:["vanilla","file/Faithful.zip"]   <- 이런 포맷

    private fun parseRpList(raw: String): List<String> {
        val t = raw.trim()
        if (!t.startsWith("[") || !t.endsWith("]")) return emptyList()
        val inner = t.substring(1, t.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        // "a","b" → [a, b]. 단순 split — 항목 안에 쉼표 들어갈 일 없음.
        return inner.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }

    private fun serializeRpList(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }

    private fun installToExistingInstance(
        mod: CurseForgeMod,
        instanceId: String,
        contentType: ContentType
    ) {
        lifecycleScope.launch {
            beginInstall(mod, "${mod.name} → 인스턴스($instanceId) 설치 중...")
            try {
                addContentToInstance(mod, instanceId, contentType)
                refreshInstalledIds()
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "기존 인스턴스 설치 실패: ${e.message}", e)
            } finally {
                endInstall()
            }
        }
    }

    private fun installToNewInstance(
        mod: CurseForgeMod,
        mcVersion: String,
        loader: ModLoader?,                 // ← null 이면 바닐라
        contentType: ContentType
    ) {
        lifecycleScope.launch {
            val loaderName = loader?.displayName ?: "Vanilla"
            beginInstall(mod, "$loaderName $mcVersion 인스턴스 준비 중...")
            try {
                val versionEntry = withContext(Dispatchers.IO) {
                    VersionRepository().fetchVersionList().firstOrNull { it.id == mcVersion }
                } ?: run {
                    Log.e("PING_LAUNCHER", "MC $mcVersion manifest 못 찾음")
                    _statusMessage.value = "MC $mcVersion 를 찾을 수 없습니다."
                    return@launch
                }

                val instanceId: String = when (loader) {
                    null               -> setupVanillaInstance(mcVersion, versionEntry) ?: return@launch
                    ModLoader.FABRIC   -> setupFabricInstance(mcVersion, versionEntry)  ?: return@launch
                    ModLoader.FORGE    -> setupForgeInstance(mcVersion, versionEntry, isNeoForge = false) ?: return@launch
                    ModLoader.NEOFORGE -> setupForgeInstance(mcVersion, versionEntry, isNeoForge = true)  ?: return@launch
                }

                _statusMessage.value = "${mod.name} 다운로드 중..."
                addContentToInstance(mod, instanceId, contentType)
                refreshInstalledIds()
                _progress.value = DownloadProgress(phase = DownloadPhase.DONE)
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "신규 인스턴스 설치 실패: ${e.message}", e)
                _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = e.message)
            } finally {
                endInstall()
            }
        }
    }

    /** 바닐라 인스턴스 생성 또는 재사용. 실패 시 null. */
    private suspend fun setupVanillaInstance(
        mcVersion: String,
        versionEntry: kr.co.donghyun.pinglauncher.data.mojang.VersionEntry
    ): String? {
        val instanceId = InstanceManager.vanillaId(mcVersion)
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        if (InstanceManager.loadMeta(instanceDir) != null) {
            Log.d("PING_LAUNCHER", "ℹ️ Vanilla 인스턴스 재사용: $instanceId")
            return instanceId
        }

        _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)
        _statusMessage.value = "MC $mcVersion 다운로드 중..."
        val mcResult = withContext(Dispatchers.IO) {
            MinecraftDownloader(instanceDir, versionEntry) { _progress.value = it }.prepare()
        }

        // 빈 폴더 미리 생성 — 텍스처/쉐이더 떨어뜨릴 곳
        File(instanceDir, "mods").mkdirs()
        File(instanceDir, "resourcepacks").mkdirs()
        File(instanceDir, "shaderpacks").mkdirs()

        val legacyArgs = mcResult.minecraftArguments
            ?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

        InstanceManager.saveMeta(this, InstanceMeta(
            id = instanceId,
            name = mcVersion,
            type = InstanceType.VANILLA,
            mcVersion = mcVersion,
            mainClass = mcResult.mainClass,
            assetIndexId = mcResult.assetIndexId,
            iconEmoji = "🌿",
            gameArgs = legacyArgs
        ))
        return instanceId
    }

    /** 새 Fabric 인스턴스를 만들거나 기존 것 반환. 실패 시 null. */
    private suspend fun setupFabricInstance(
        mcVersion: String,
        versionEntry: VersionEntry
    ): String? {
        val fabricApi = FabricMetaAPI()
        val loaderList = withContext(Dispatchers.IO) { fabricApi.listLoaders(mcVersion) }
        val loaderVersion = loaderList.firstOrNull { it.loader.stable }?.loader?.version
            ?: loaderList.firstOrNull()?.loader?.version
            ?: run {
                Log.e("PING_LAUNCHER", "Fabric loader 후보 없음 mc=$mcVersion")
                return null
            }

        val instanceId = InstanceManager.fabricId(mcVersion, loaderVersion)
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        if (InstanceManager.loadMeta(instanceDir) != null) {
            Log.d("PING_LAUNCHER", "ℹ️ Fabric 인스턴스 재사용: $instanceId")
            return instanceId
        }

        _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)
        _statusMessage.value = "MC $mcVersion 다운로드 중..."
        val mcResult = withContext(Dispatchers.IO) {
            MinecraftDownloader(instanceDir, versionEntry) { _progress.value = it }.prepare()
        }

        _statusMessage.value = "Fabric $loaderVersion 설치 중..."
        val fr = withContext(Dispatchers.IO) {
            FabricInstaller(instanceDir) { msg, cur, tot ->
                _progress.value = DownloadProgress(
                    phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                    current = cur, total = tot, fileName = msg
                )
            }.install(mcVersion, loaderVersion)
        }
        if (!fr.success) {
            Log.e("PING_LAUNCHER", "Fabric 설치 실패: ${fr.error}")
            return null
        }
        File(instanceDir, "mods").mkdirs()

        InstanceManager.saveMeta(this, InstanceMeta(
            id = instanceId,
            name = "$mcVersion · Fabric $loaderVersion",
            type = InstanceType.FABRIC,
            mcVersion = mcVersion,
            loaderType = "fabric",
            loaderVersion = loaderVersion,
            mainClass = fr.mainClass,
            extraJars = fr.extraJars,
            assetIndexId = mcResult.assetIndexId,
            iconEmoji = "🧵",
            gameJvmArgs = fr.gameJvmArgs,
            gameArgs = fr.gameArgs
        ))
        return instanceId
    }

    /**
     * 새 Forge/NeoForge 인스턴스 셋업. recommended → latest 순으로 자동 선택.
     *
     * 1.13+ 는 install_profile.json 의 processors 단계가 따로 있어
     * 실제 부팅 시 실패할 수 있음 — requiresProcessors 가 true 면 로그로 경고하고
     * 상태 메시지에도 표시.
     */
    private suspend fun setupForgeInstance(
        mcVersion: String,
        versionEntry: VersionEntry,
        isNeoForge: Boolean
    ): String? {
        val forgeApi = ForgeMetaAPI()
        val loaderList = withContext(Dispatchers.IO) {
            runCatching { forgeApi.listLoaders(mcVersion) }.getOrDefault(emptyList())
        }
        if (loaderList.isEmpty()) {
            Log.e("PING_LAUNCHER", "Forge 후보 없음 mc=$mcVersion")
            _statusMessage.value = "$mcVersion 용 Forge 빌드가 없습니다."
            return null
        }
        val forgeVersion = loaderList.firstOrNull { it.recommended }?.forgeVersion
            ?: loaderList.firstOrNull { it.latest }?.forgeVersion
            ?: loaderList.first().forgeVersion

        val loaderType = if (isNeoForge) "neoforge" else "forge"
        val instanceId = "${loaderType}_${mcVersion.replace('.', '_')}_${forgeVersion.replace('.', '_')}"
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        if (InstanceManager.loadMeta(instanceDir) != null) {
            Log.d("PING_LAUNCHER", "ℹ️ $loaderType 인스턴스 재사용: $instanceId")
            return instanceId
        }

        // 1) MC 다운로드
        _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)
        _statusMessage.value = "MC $mcVersion 다운로드 중..."
        val mcResult = withContext(Dispatchers.IO) {
            MinecraftDownloader(instanceDir, versionEntry) { _progress.value = it }.prepare()
        }

        // 2) Forge / NeoForge 설치
        _statusMessage.value = "${if (isNeoForge) "NeoForge" else "Forge"} $forgeVersion 설치 중..."
        val fr = withContext(Dispatchers.IO) {
            ForgeInstaller(instanceDir) { msg, cur, tot ->
                _progress.value = DownloadProgress(
                    phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                    current = cur, total = tot, fileName = msg
                )
            }.install(this@ContentPackBrowserActivity, mcVersion, forgeVersion, isNeoForge = isNeoForge)
        }
        if (!fr.success) {
            Log.e("PING_LAUNCHER", "Forge 설치 실패: ${fr.error}")
            _statusMessage.value = "Forge 설치 실패: ${fr.error}"
            return null
        }

        if (fr.requiresProcessors) {
            Log.i("PING_LAUNCHER",
                "Forge 1.13+ — ProcessorLauncher 경유로 부팅합니다. " +
                        "최초 실행 시 BinaryPatcher 등이 돌아가서 시간이 걸릴 수 있습니다.")
            _statusMessage.value = "Modern Forge — 최초 실행 시 client jar 패칭이 수행됩니다."
        }
        File(instanceDir, "mods").mkdirs()

        InstanceManager.saveMeta(this, InstanceMeta(
            id = instanceId,
            name = "$mcVersion · ${if (isNeoForge) "NeoForge" else "Forge"} $forgeVersion",
            type = InstanceType.MODPACK,         // FABRIC 외엔 MODPACK 으로 분류해두는 듯
            mcVersion = mcVersion,
            loaderType = loaderType,
            loaderVersion = forgeVersion,
            mainClass = fr.mainClass,
            extraJars = fr.extraJars,
            assetIndexId = mcResult.assetIndexId,
            iconEmoji = if (isNeoForge) "🟢" else "🔥",
            gameJvmArgs = fr.gameJvmArgs,
            gameArgs = fr.gameArgs
        ))
        return instanceId
    }

    /** 모드팩 설치 (자체 인스턴스 생성). zip 추출/매니페스트 처리는 별도 인스톨러로 위임. */
    private suspend fun installModpack(mod: CurseForgeMod) {
        val instanceId = InstanceManager.modpackId(mod.name)
        val file = withContext(Dispatchers.IO) {
            fetchLatestFileForVersion(mod.id, gameVersion = null, loaderType = null)
        } ?: return
        val downloadUrl = resolveDownloadUrl(file)
        if (downloadUrl.isBlank()) {
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
     * 특정 mod의 최신 호환 파일 선택.
     * 정렬 우선순위: release > beta > alpha, 그 다음 id desc (최신).
     * gameVersion이 주어지면 그 버전 문자열이 gameVersions에 정확히 포함된 것만.
     */
    private fun fetchLatestFileForVersion(
        modId: Int,
        gameVersion: String?,
        loaderType: String?
    ): CurseForgeFile? {
        val url = StringBuilder("https://api.curseforge.com/v1/mods/$modId/files?index=0&pageSize=50")
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
                val files = gson.fromJson<CurseForgeListResponse<CurseForgeFile>>(body, type).data

                // 1) MC 버전 정확 매칭 (e.g. "1.21.1" 만, "1.21"이나 "1.21.2"는 제외)
                val mcMatched = if (!gameVersion.isNullOrBlank())
                    files.filter { it.gameVersions.contains(gameVersion) }
                else files

                // 2) 로더 매칭 검증 (server 필터가 가끔 새는 케이스 대비)
                val loaderMatched = if (!loaderType.isNullOrBlank()) {
                    mcMatched.filter { f ->
                        f.gameVersions.any { it.equals(loaderType, ignoreCase = true) }
                                // 일부 모드는 로더 태그를 안 박음 — 그건 그대로 허용
                                || f.gameVersions.none { it in LOADER_TAGS }
                    }
                } else mcMatched

                // 3) release > beta > alpha, 같은 등급이면 id desc
                loaderMatched
                    .sortedWith(compareBy({ it.releaseType }, { -it.id }))
                    .firstOrNull()
                    .also {
                        Log.d("PING_LAUNCHER",
                            "📋 mod=$modId mc=$gameVersion loader=$loaderType " +
                                    "→ ${it?.fileName ?: "(없음)"} (rt=${it?.releaseType})")
                    }
            }.getOrNull()
        }
    }

    private val LOADER_TAGS = setOf("Forge", "Fabric", "NeoForge", "Quilt")

    private fun fetchModInfo(modId: Int): CurseForgeMod? {
        val request = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId")
            .header("x-api-key", BuildConfig.CURSEFORGE_API_KEY)
            .header("Accept", "application/json")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return@runCatching null
                val type = object : TypeToken<CurseForgeResponse<CurseForgeMod>>() {}.type
                gson.fromJson<CurseForgeResponse<CurseForgeMod>>(body, type).data
            }
        }.getOrNull()
    }

    /**
     * file 의 RequiredDependency(relationType=3) 들을 같은 mc/loader 로 재귀 해결.
     * @return (mod, file) 쌍 리스트. 중복 modId는 한 번만.
     */
    private fun resolveDependencies(
        rootFile: CurseForgeFile,
        mcVersion: String,
        loaderType: String?,
        visited: MutableSet<Int> = mutableSetOf()
    ): List<Pair<CurseForgeMod, CurseForgeFile>> {
        val out = mutableListOf<Pair<CurseForgeMod, CurseForgeFile>>()
        val required = rootFile.dependencies.filter { it.relationType == 3 }

        for (dep in required) {
            if (!visited.add(dep.modId)) continue

            val depMod = fetchModInfo(dep.modId)
            if (depMod == null) {
                Log.w("PING_LAUNCHER", "  ↳ 의존성 mod 정보 못 받음: id=${dep.modId}")
                continue
            }

            val depFile = fetchLatestFileForVersion(dep.modId, mcVersion, loaderType)
            if (depFile == null) {
                Log.w("PING_LAUNCHER",
                    "  ↳ ${depMod.name} — mc=$mcVersion loader=$loaderType 호환 파일 없음")
                continue
            }

            out += depMod to depFile
            out += resolveDependencies(depFile, mcVersion, loaderType, visited)
        }
        return out
    }

    /**
     * "sodium-fabric-0.8.12-alpha.4+mc1.21.1.jar" → "sodium-fabric"
     * "iris-fabric-1.8.8+mc1.21.1.jar"            → "iris-fabric"
     *
     * jar 이름에서 첫 숫자가 등장하기 직전까지를 mod 식별 prefix 로 사용.
     */
    private fun extractModFilePrefix(fileName: String): String {
        val nameOnly = fileName.removeSuffix(".jar")
        val m = Regex("^([a-zA-Z][a-zA-Z0-9_\\-]*?)[-_]+\\d").find(nameOnly)
        return m?.groupValues?.get(1) ?: nameOnly
    }

    private fun removeConflictingJars(outDir: File, newFileName: String) {
        val newPrefix = extractModFilePrefix(newFileName)
        if (newPrefix.length < 3) return  // "fa" 같은 짧은 건 위험해서 스킵

        outDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.extension != "jar") return@forEach
            if (f.name == newFileName) return@forEach              // 정확히 같으면 두기
            if (f.name.endsWith(".disabled")) return@forEach       // 비활성은 손대지 않음

            val oldPrefix = extractModFilePrefix(f.name)
            if (oldPrefix.equals(newPrefix, ignoreCase = true)) {
                Log.d("PING_LAUNCHER",
                    "🗑 같은 prefix($newPrefix) 기존 jar 제거: ${f.name}")
                f.delete()
            }
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

    fun isLegacyVersion(versionId: String): Boolean {
        // 1.12.2 이하: legacy (AWT 필요)
        // 1.13+: modern (LWJGL3, AWT 불필요)
        val parts = versionId.removePrefix("1.").split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        return major <= 12
    }

    private fun resolveDownloadUrl(file: CurseForgeFile): String {
        val direct = file.downloadUrl
        if (!direct.isNullOrBlank()) return direct

        val part1 = file.id / 1000
        val part2 = file.id % 1000
        val safeName = file.fileName.replace(" ", "%20")
        return "https://edge.forgecdn.net/files/$part1/$part2/$safeName".also {
            Log.i("PING_LAUNCHER",
                "🔁 downloadUrl=null → CDN fallback: mod=${file.id} → $it")
        }
    }

}