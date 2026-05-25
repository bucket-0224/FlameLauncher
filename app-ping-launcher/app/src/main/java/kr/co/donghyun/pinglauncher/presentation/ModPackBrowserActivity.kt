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
        val cache = loadCache(mod) ?: return
        val instanceId = InstanceManager.modpackId(mod.name)  // "modpack_COBBLEVERSE_..."
        val instanceDir = InstanceManager.instanceDir(this@ModPackBrowserActivity, instanceId)

        lifecycleScope.launch(Dispatchers.Main) {
            prepareNatives(applicationContext, applicationInfo)
            copyLwjglJar(applicationContext)
            prePopulateLwjgl(cache.mcVersion, applicationContext)

            MinecraftActivity.start(
                this@ModPackBrowserActivity,
                versionId = cache.mcVersion,
                assetIndex = cache.assetIndexId,
                extraJars = cache.extraJars,
                mainClass = cache.mainClass,
                instanceDir = instanceDir.absolutePath
            )
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
                val cacheFile = File(instanceDir, "instance.json")
                val existingMeta = if (cacheFile.exists()) {
                    try { InstanceManager.loadMeta(instanceDir) } catch (_: Exception) { null }
                } else null

                if (existingMeta != null) {
                    // 캐시 존재 — 바로 실행
                    val assetExists = File(instanceDir, "assets/indexes/${existingMeta.assetIndexId}.json").exists()
                    if (assetExists) {
                        Log.d("PING_LAUNCHER", "✅ 캐시에서 로드: ${mod.name}")
                        _installedIds.value = _installedIds.value + mod.id
                        _progress.value = DownloadProgress(phase = DownloadPhase.DONE)
                        withContext(Dispatchers.Main) {
                            launchInstance(existingMeta, instanceDir)
                        }
                        return@launch
                    }
                }

                // 모드팩 설치
                _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST, fileName = mod.name)
                val files = curseApi.getModFiles(mod.id)
                if (files.isEmpty()) { _statusMessage.value = "❌ 파일을 찾을 수 없음"; return@launch }

                val installer = ModPackInstaller(
                    baseDir = instanceDir,   // ← 인스턴스 폴더 안에 설치
                    curseForgeApi = curseApi,
                    onProgress = { _progress.value = it }
                )
                val result = installer.install(mod, files.first().id)
                if (!result.success) { _statusMessage.value = "❌ ${result.error}"; return@launch }

                // Fabric/Forge 설치
                val extraJars = mutableListOf<String>()
                var mainClass = "net.minecraft.client.main.Main"
                var loaderType: String? = null
                var loaderVersion: String? = null

                result.forgeId?.let { forgeId ->
                    val forgeInstaller = ForgeInstaller(instanceDir) { msg ->
                        _statusMessage.value = msg
                    }
                    val forgeResult = forgeInstaller.install(forgeId)
                    if (forgeResult.success) {
                        extraJars.addAll(forgeResult.extraJars)
                        mainClass = forgeResult.mainClass
                        loaderType = if (forgeId.contains("fabric")) "fabric" else "forge"
                        loaderVersion = forgeResult.forgeVersion
                    }
                }

                // MC 다운로드 — instanceDir 안에
                _statusMessage.value = "Minecraft ${result.mcVersion} 다운로드 중..."
                val versionRepo = VersionRepository()
                val versionUrl = try { versionRepo.fetchVersionJsonUrl(result.mcVersion) } catch (_: Exception) { "" }
                if (versionUrl.isEmpty()) { _statusMessage.value = "❌ MC ${result.mcVersion} 버전을 찾을 수 없음"; return@launch }

                val mcDownloader = ForgeMinecraftDownloader(
                    gameDir = instanceDir,   // ← 인스턴스 폴더 안에
                    versionUrl = versionUrl,
                    versionId = result.mcVersion,
                    onProgress = { _progress.value = it }
                )
                val assetIndexId = mcDownloader.prepare()

                // 인스턴스 메타 저장
                val meta = InstanceMeta(
                    id = instanceId,
                    name = mod.name,
                    type = InstanceType.MODPACK,
                    mcVersion = result.mcVersion,
                    loaderType = loaderType,
                    loaderVersion = loaderVersion,
                    mainClass = mainClass,
                    extraJars = extraJars,
                    assetIndexId = assetIndexId,
                    iconEmoji = "📦"
                )
                InstanceManager.saveMeta(this@ModPackBrowserActivity, meta)
                _installedIds.value = _installedIds.value + mod.id
                _progress.value = DownloadProgress(phase = DownloadPhase.DONE)
                _statusMessage.value = "✅ 설치 완료!"

                prepareNatives(applicationContext, applicationInfo)
                copyLwjglJar(applicationContext)
                prePopulateLwjgl(result.mcVersion, applicationContext)

                withContext(Dispatchers.Main) {
                    launchInstance(meta, instanceDir)
                }
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
