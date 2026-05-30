package kr.co.donghyun.pinglauncher.presentation

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeMod
import kr.co.donghyun.pinglauncher.data.curseforge.InstalledModPackCache
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.data.instance.InstanceMeta
import kr.co.donghyun.pinglauncher.data.instance.InstanceType
import kr.co.donghyun.pinglauncher.data.mojang.DownloadPhase
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.screen.ModPackBrowserScreen
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PingLauncherTheme
import kr.co.donghyun.pinglauncher.presentation.util.PrepareNatives.Companion.copyLwjglJar
import kr.co.donghyun.pinglauncher.presentation.util.PrepareNatives.Companion.prePopulateLwjgl
import kr.co.donghyun.pinglauncher.presentation.util.PrepareNatives.Companion.prepareNatives
import kr.co.donghyun.pinglauncher.presentation.util.curseforge.CurseForgeAPI
import kr.co.donghyun.pinglauncher.presentation.util.curseforge.ForgeInstaller
import kr.co.donghyun.pinglauncher.presentation.util.curseforge.ForgeMinecraftDownloader
import kr.co.donghyun.pinglauncher.presentation.util.curseforge.ModPackInstaller
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.VersionRepository
import java.io.File



class ModPackBrowserActivity : BaseActivity() {

    private val _modpacks = MutableStateFlow<List<CurseForgeMod>>(emptyList())
    private val _progress = MutableStateFlow(DownloadProgress())
    private val _isLoading = MutableStateFlow(false)
    private val _isInstalling = MutableStateFlow(false)
    private val _installingModId = MutableStateFlow<Int?>(null)
    private val _statusMessage = MutableStateFlow("")
    private val _selectedVersion = MutableStateFlow("")
    private val _installedIds = MutableStateFlow<Set<Int>>(emptySet())

    private val curseApi = CurseForgeAPI()
    private val gson = Gson()
    private var searchJob: Job? = null

    private val _page = MutableStateFlow(0)
    private val _hasMore = MutableStateFlow(true)

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ModPackBrowserActivity::class.java))
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )
        // 이미 설치된 모드팩 ID 로드
        loadInstalledIds()

        setContent {
            PingLauncherTheme {
                val modpacks by _modpacks.asStateFlow().collectAsState()
                val progress by _progress.asStateFlow().collectAsState()
                val isLoading by _isLoading.asStateFlow().collectAsState()
                val isInstalling by _isInstalling.asStateFlow().collectAsState()
                val installingModId by _installingModId.asStateFlow().collectAsState()
                val statusMessage by _statusMessage.asStateFlow().collectAsState()
                val selectedVersion by _selectedVersion.asStateFlow().collectAsState()
                val installedIds by _installedIds.asStateFlow().collectAsState()
                val hasMore by _hasMore.asStateFlow().collectAsState()

                ModPackBrowserScreen(
                    modpacks = modpacks,
                    progress = progress,
                    isLoading = isLoading,
                    isInstalling = isInstalling,
                    installingModId = installingModId,
                    statusMessage = statusMessage,
                    selectedVersion = selectedVersion,
                    installedIds = installedIds,
                    hasMore = hasMore,
                    onSearch = { query, version -> search(query, version, 0) },
                    onVersionFilter = { _selectedVersion.value = it },
                    onInstall = { mod -> installModpack(mod) },
                    onLaunch = { mod -> launchModpack(mod) },
                    onLoadMore = { search(_modpacks.value.last().name, selectedVersion, _page.value + 1) }
                )
            }
        }

        search("", "")
    }

    private fun loadInstalledIds() {
        lifecycleScope.launch(Dispatchers.IO) {
            val instances = InstanceManager.listInstances(this@ModPackBrowserActivity)
            // instance.json에 modpackCurseForgeId 저장하거나
            // 이름 기반으로 매칭
            // 일단 모든 modpack 타입 인스턴스를 로드
            val installedNames = instances
                .filter { it.type == InstanceType.MODPACK }
                .map { it.name }
                .toSet()

            // modpack 이름으로 현재 목록과 매칭
            val currentMods = _modpacks.value
            val ids = currentMods
                .filter { mod -> installedNames.contains(mod.name) }
                .map { it.id }
                .toSet()
            _installedIds.value = ids
        }
    }

    private fun isInstalled(mod: CurseForgeMod): Boolean {
        val instanceId = InstanceManager.modpackId(mod.name)
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        return File(instanceDir, "instance.json").exists()
    }

    private fun loadCache(mod: CurseForgeMod): InstalledModPackCache? {
        val instanceId = InstanceManager.modpackId(mod.name)
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        val meta = InstanceManager.loadMeta(instanceDir) ?: return null
        return InstalledModPackCache(
            mcVersion = meta.mcVersion,
            forgeId = meta.loaderType?.let { "forge-${meta.mcVersion}-${meta.loaderVersion}" },
            mainClass = meta.mainClass,
            extraJars = meta.extraJars,
            assetIndexId = meta.assetIndexId,
            gameDirPath = instanceDir.absolutePath
        )
    }
    private fun search(query: String, version: String, page: Int = 0) {
        if (page == 0) searchJob?.cancel()
        searchJob = lifecycleScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val results = curseApi.searchModpacks(
                    query = query,
                    gameVersion = version,
                    pageSize = 20,
                    index = page * 20
                )
                val filtered = results.filter { mod ->
                    mod.latestFilesIndexes.any { idx -> idx.modLoader == 4 }
                }
                _modpacks.value = if (page == 0) filtered else _modpacks.value + filtered
                _hasMore.value = filtered.size == 20
                _page.value = page
            } catch (e: Exception) {
                _modpacks.value = if (page == 0) emptyList() else _modpacks.value
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun launchModpack(mod: CurseForgeMod) {
        val instanceId = InstanceManager.modpackId(mod.name)
        val instanceDir = InstanceManager.instanceDir(this@ModPackBrowserActivity, instanceId)
        val meta = InstanceManager.loadMeta(instanceDir) ?: return

        lifecycleScope.launch(Dispatchers.Main) {
            prepareNatives(applicationContext, applicationInfo)
            copyLwjglJar(applicationContext)
            prePopulateLwjgl(meta.mcVersion, applicationContext)
            launchInstance(meta, instanceDir)
        }
    }

    private fun installModpack(mod: CurseForgeMod) {
        lifecycleScope.launch(Dispatchers.IO) {
            _isInstalling.value = true
            _installingModId.value = mod.id
            _statusMessage.value = ""

            try {
                val instanceId = InstanceManager.modpackId(mod.name)
                val instanceDir = InstanceManager.instanceDir(this@ModPackBrowserActivity, instanceId)

                // 캐시 확인
                val existingMeta = InstanceManager.loadMeta(instanceDir)
                if (existingMeta != null) {
                    val assetExists = File(instanceDir, "assets/indexes/${existingMeta.assetIndexId}.json").exists()
                    if (assetExists) {
                        Log.d("PING_LAUNCHER", "✅ 인스턴스 캐시 사용: ${mod.name}")
                        _installedIds.value = _installedIds.value + mod.id
                        _progress.value = DownloadProgress(phase = DownloadPhase.DONE)
                        withContext(Dispatchers.Main) { launchInstance(existingMeta, instanceDir) }
                        return@launch
                    }
                }

                // 1) 모드팩 콘텐츠 (zip, manifest, overrides, mods 다운로드)
                _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST, fileName = mod.name)

                val mcRegex = Regex("^1\\.\\d+(\\..*)?$")

// 1순위: latestFilesIndexes에서 Fabric(=4) + 정상 MC 버전 항목 (검색 응답에 이미 포함됨)
                val fabricIdx = mod.latestFilesIndexes
                    .filter { it.modLoader == 4 }
                    .firstOrNull { it.gameVersion.matches(mcRegex) }

                val (chosenFileId, chosenMc) = if (fabricIdx != null) {
                    Log.d("PING_LAUNCHER", "Fabric 인덱스 선택: fileId=${fabricIdx.fileId}, mc=${fabricIdx.gameVersion}")
                    fabricIdx.fileId to fabricIdx.gameVersion
                } else {
                    // 2순위: files 엔드포인트 폴백
                    val files = curseApi.getModFiles(mod.id)
                    val targetFile = files.firstOrNull()
                        ?: run { _statusMessage.value = "❌ 파일 목록 비어있음"; return@launch }
                    val mcFromFile = targetFile.gameVersions.firstOrNull { it.matches(mcRegex) }
                    Log.d("PING_LAUNCHER", "files 폴백: id=${targetFile.id}, gameVersions=${targetFile.gameVersions}, mc=$mcFromFile")
                    targetFile.id to (mcFromFile ?: "")
                }

                if (chosenMc.isEmpty()) {
                    val tags = mod.latestFilesIndexes.map { "${it.gameVersion}/loader=${it.modLoader}" }
                    _statusMessage.value = "❌ MC 버전 추론 실패. 태그: $tags"
                    Log.e("PING_LAUNCHER", "MC 버전 추론 실패. latestFilesIndexes=$tags")
                    return@launch
                }

                val installer = ModPackInstaller(
                    baseDir = instanceDir,
                    curseForgeApi = curseApi,
                    onProgress = { _progress.value = it }
                )
                val result = installer.install(
                    mod = mod,
                    fileId = chosenFileId,
                    mcVersionOverride = chosenMc
                )

                if (!result.success) { _statusMessage.value = "❌ ${result.error}"; return@launch }

                // 2) 로더 분기 — Fabric만 지원
                if (result.loaderType != "fabric") {
                    _statusMessage.value = "❌ ${result.loaderType ?: "알 수 없는"} 모드팩은 아직 지원되지 않습니다."
                    InstanceManager.deleteInstance(this@ModPackBrowserActivity, instanceId)
                    return@launch
                }
                val loaderVersion = result.loaderVersion
                    ?: run { _statusMessage.value = "❌ Fabric 로더 버전 누락"; return@launch }

                // 3) Vanilla MC 다운로드
                _statusMessage.value = "Minecraft ${result.mcVersion} 다운로드 중..."
                val versionRepo = VersionRepository()
                val versionUrl = try { versionRepo.fetchVersionJsonUrl(result.mcVersion) } catch (_: Exception) { "" }
                if (versionUrl.isEmpty()) {
                    _statusMessage.value = "❌ MC ${result.mcVersion} 을 찾을 수 없음"; return@launch
                }
                val mcDownloader = ForgeMinecraftDownloader(
                    gameDir = instanceDir,
                    versionUrl = versionUrl,
                    versionId = result.mcVersion,
                    onProgress = { _progress.value = it }
                )
                val assetIndexId = mcDownloader.prepare()

                // 4) Fabric Loader 설치 (새 FabricInstaller)
                _statusMessage.value = "Fabric Loader $loaderVersion 설치 중..."
                val fabricInstaller = kr.co.donghyun.pinglauncher.presentation.util.fabric.FabricInstaller(
                    instanceDir
                ) { msg, cur, tot ->
                    _statusMessage.value = msg
                    _progress.value = DownloadProgress(
                        phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                        current = cur, total = tot, fileName = msg
                    )
                }
                val fabricResult = fabricInstaller.install(result.mcVersion, loaderVersion)
                if (!fabricResult.success) {
                    _statusMessage.value = "❌ Fabric 설치 실패: ${fabricResult.error}"
                    return@launch
                }

                // 5) 메타 저장
                val meta = InstanceMeta(
                    id = instanceId,
                    name = mod.name,
                    type = InstanceType.MODPACK,
                    mcVersion = result.mcVersion,
                    loaderType = "fabric",
                    loaderVersion = loaderVersion,
                    mainClass = fabricResult.mainClass,
                    extraJars = fabricResult.extraJars,
                    assetIndexId = assetIndexId,
                    iconEmoji = "📦",
                    gameJvmArgs = fabricResult.gameJvmArgs,
                    gameArgs = fabricResult.gameArgs
                )
                InstanceManager.saveMeta(this@ModPackBrowserActivity, meta)

                _installedIds.value = _installedIds.value + mod.id
                _progress.value = DownloadProgress(phase = DownloadPhase.DONE)
                _statusMessage.value = "✅ 설치 완료!"

                prepareNatives(applicationContext, applicationInfo)
                copyLwjglJar(applicationContext)
                prePopulateLwjgl(result.mcVersion, applicationContext)

                withContext(Dispatchers.Main) { launchInstance(meta, instanceDir) }
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "모드팩 설치 실패: ${e.message}", e)
                _statusMessage.value = "❌ ${e.message}"
            } finally {
                _isInstalling.value = false
                _installingModId.value = null
            }
        }
    }

    private fun launchInstance(meta: InstanceMeta, instanceDir: File) {
        MinecraftActivity.start(
            this@ModPackBrowserActivity,
            versionId = meta.mcVersion,
            assetIndex = meta.assetIndexId,
            extraJars = meta.extraJars,
            mainClass = meta.mainClass,
            instanceDir = instanceDir.absolutePath
        )
    }

    override fun onResume() {
        super.onResume()
        // 설치 목록 갱신
        lifecycleScope.launch(Dispatchers.IO) {
            val instances = InstanceManager.listInstances(this@ModPackBrowserActivity)
            val installedNames = instances.filter { it.type == InstanceType.MODPACK }.map { it.name }.toSet()
            val ids = _modpacks.value.filter { installedNames.contains(it.name) }.map { it.id }.toSet()
            _installedIds.value = ids
        }
    }
}
