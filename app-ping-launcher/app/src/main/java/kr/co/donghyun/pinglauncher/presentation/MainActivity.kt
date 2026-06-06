package kr.co.donghyun.pinglauncher.presentation

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.data.auth.MicrosoftAuthManager
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.data.instance.InstanceMeta
import kr.co.donghyun.pinglauncher.data.instance.InstanceType
import kr.co.donghyun.pinglauncher.data.mojang.DownloadPhase
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
import kr.co.donghyun.pinglauncher.data.mojang.VersionEntry
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.screen.MainScreen
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PingLauncherTheme
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.MinecraftDownloader
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.VersionRepository
import java.io.File

class MainActivity : BaseActivity() {

    private val _versions = MutableStateFlow<List<VersionEntry>>(emptyList())
    private val _progress = MutableStateFlow(DownloadProgress())
    private val _selectedVersion = MutableStateFlow<VersionEntry?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _showOnlyRelease = MutableStateFlow(true)

    private val versionRepo = VersionRepository()

    private var loginErrorMessage by mutableStateOf<String?>(null)
    private var isLoggedIn by mutableStateOf(false)
    private var username by mutableStateOf<String?>(null)

    private val _instances = MutableStateFlow<List<InstanceMeta>>(emptyList())

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_CANCELED) {
            val error = result.data?.getStringExtra(LoginActivity.RESULT_ERROR)
            if (error != null) {
                loginErrorMessage = error
            }
        } else if (result.resultCode == RESULT_OK) {
            loginErrorMessage = null
            // 세션 갱신
            refreshLoginState()
        }
    }

    override fun onCreated() {
        refreshLoginState()
        hideNavigation()

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                scrim = android.graphics.Color.TRANSPARENT
            )
        )

        refreshInstances()

        setContent {
            PingLauncherTheme {
                val versions by _versions.asStateFlow().collectAsState()
                val progress by _progress.asStateFlow().collectAsState()
                val selected by _selectedVersion.asStateFlow().collectAsState()
                val isLoading by _isLoading.asStateFlow().collectAsState()
                val showOnlyRelease by _showOnlyRelease.asStateFlow().collectAsState()
                val instances by _instances.asStateFlow().collectAsState()


                MainScreen(
                    versions = versions,
                    instances = instances,                          // ★ 추가
                    progress = progress,
                    selectedVersion = selected,
                    isLoading = isLoading,
                    onVersionSelect = { _selectedVersion.value = it },
                    onDownloadAndPlay = { version -> startDownload(version) },
                    onLaunchFabric = { v, l -> startFabricDownloadAndPlay(v, l) },
                    onLaunchForge  = { v, f -> startForgeDownloadAndPlay(v, f, false) },
                    onLaunchInstance = { meta -> launchInstance(meta) },   // ★ 추가
                    onDeleteInstance = { meta -> deleteInstance(meta) },   // ★ 추가
                    onOpenContents = { ContentPackBrowserActivity.start(this@MainActivity) },
                    onOpenNetworkSettings = { NetworkSettingsActivity.start(this@MainActivity) },
                    onOpenKeySettings = { KeyboardLayoutEditorActivity.start(this@MainActivity) },
                    onOpenJVMSettings = { JvmSettingsActivity.start(this@MainActivity) },
                    onOpenRendererSettings = { RendererSettingsActivity.start(this@MainActivity) },
                    uuid = MicrosoftAuthManager.loadSession(this@MainActivity)?.uuid,
                    isLoggedIn = isLoggedIn,
                    username = username,
                    onLogin = { loginLauncher.launch(Intent(this, LoginActivity::class.java)) },
                )
            }
        }

        // 버전 목록 로드
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = versionRepo.fetchVersionList()
                _versions.value = list
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "버전 목록 로드 실패: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun startDownload(version: VersionEntry) {
        val internalBaseDir = applicationContext.filesDir
        val nativesDir = File(internalBaseDir, "natives")

        // 인스턴스 디렉토리
        val instanceId = InstanceManager.vanillaId(version.id)
        val instanceDir = InstanceManager.instanceDir(this, instanceId)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)

                val preparer = MinecraftDownloader(
                    instanceDir = instanceDir,
                    versionEntry = version,
                    onProgress = { _progress.value = it }
                )

                val result = preparer.prepare()
                val assetIndexId = result.assetIndexId
                val realMainClass = result.mainClass
                val legacyGameArgs = result.minecraftArguments
                    ?.split(" ")
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()

                copyNativesFromApkLibDir(nativesDir)
                copyLwjglJarFromAssets(internalBaseDir)
                prePopulateLwjglExtractDir(internalBaseDir, nativesDir, version.id)

                InstanceManager.saveMeta(this@MainActivity, InstanceMeta(
                    id = instanceId,
                    name = version.id,
                    type = InstanceType.VANILLA,
                    mcVersion = version.id,
                    mainClass = realMainClass,                  // ← 매니페스트 값
                    assetIndexId = assetIndexId,
                    iconEmoji = "🌿",
                    gameArgs = legacyGameArgs                    // ← 1.12 이전 인자 보존
                ))

                _progress.value = DownloadProgress(phase = DownloadPhase.DONE)

                withContext(Dispatchers.Main) {
                    MinecraftActivity.start(
                        this@MainActivity,
                        versionId = version.id,
                        assetIndex = assetIndexId,
                        mainClass = realMainClass,               // ← 전달
                        instanceDir = instanceDir.absolutePath,
                    )
                }
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "❌ 오류: ${e.message}", e)
                _progress.value = DownloadProgress(
                    phase = DownloadPhase.ERROR,
                    error = e.message
                )
            }
        }
    }


    private fun prePopulateLwjglExtractDir(baseDir: File, nativesDir: File, versionId: String) {
        listOf("3.2.1", "3.2.2", "3.2.1-build-12", "3.2.2-build-12", "3.3.3", "3.3.3-snapshot").forEach { version ->
            val lwjglDir = File(getExternalFilesDir(null), "mc_$versionId/.lwjgl/$version")
            if (lwjglDir.exists()) lwjglDir.deleteRecursively()
            lwjglDir.mkdirs()
            nativesDir.listFiles()?.forEach { soFile ->
                soFile.copyTo(File(lwjglDir, soFile.name), overwrite = true)
                File(lwjglDir, soFile.name).setExecutable(true, false)
            }
        }
    }

    private fun copyNativesFromApkLibDir(nativesDir: File) {
        if (nativesDir.exists()) nativesDir.deleteRecursively()
        nativesDir.mkdirs()
        val apkLibDir = File(applicationInfo.nativeLibraryDir)
        apkLibDir.listFiles()?.forEach { soFile ->
            soFile.copyTo(File(nativesDir, soFile.name), overwrite = true)
            File(nativesDir, soFile.name).setExecutable(true, false)
        }
        val apkPath = applicationInfo.sourceDir
        java.util.zip.ZipFile(apkPath).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                .forEach { entry ->
                    val fileName = entry.name.substringAfterLast("/")
                    val dest = File(nativesDir, fileName)
                    if (!dest.exists()) {
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        dest.setExecutable(true, false)
                        dest.setReadable(true, false)
                    }
                }
        }
    }

    private fun refreshLoginState() {
        val session = MicrosoftAuthManager.loadSession(this)
        isLoggedIn = session != null && session.refreshToken.isNotEmpty()
        username = session?.username
    }

    private fun copyLwjglJarFromAssets(baseDir: File) {
        val dest = File(baseDir, "lwjgl3/lwjgl-glfw-classes.jar")
        dest.parentFile?.mkdirs()
        assets.open("lwjgl3/lwjgl-glfw-classes.jar").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    private fun startForgeDownloadAndPlay(
        version: VersionEntry,
        forgeVersion: String,
        isNeoForge: Boolean = false
    ) {
        val mcVersion = version.id
        val loaderType = if (isNeoForge) "neoforge" else "forge"
        val instanceId =
            "${loaderType}_${mcVersion.replace('.', '_')}_${forgeVersion.replace('.', '_')}"
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        val internalBaseDir = applicationContext.filesDir
        val nativesDir = File(internalBaseDir, "natives")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)

                // 1) 바닐라 MC 다운로드
                val mcPreparer = MinecraftDownloader(
                    instanceDir = instanceDir,
                    versionEntry = version,
                    onProgress = { _progress.value = it }
                )
                val manifest = mcPreparer.prepare()

                // 2) Forge / NeoForge 설치
                val forgeResult = kr.co.donghyun.pinglauncher.presentation.util.forge
                    .ForgeInstaller(instanceDir) { msg, cur, tot ->
                        _progress.value = DownloadProgress(
                            phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                            current = cur, total = tot, fileName = msg
                        )
                    }.install(this@MainActivity, mcVersion, forgeVersion, isNeoForge = isNeoForge)

                if (!forgeResult.success) {
                    Log.e("PING_LAUNCHER", "Forge 설치 실패: ${forgeResult.error}")
                    _progress.value = DownloadProgress(
                        phase = DownloadPhase.ERROR,
                        error = "Forge 설치 실패: ${forgeResult.error}"
                    )
                    return@launch
                }

                if (forgeResult.requiresProcessors) {
                    Log.i(
                        "PING_LAUNCHER",
                        "Modern Forge — 첫 실행 시 ProcessorLauncher 가 client jar 패칭"
                    )
                }

                // 3) natives & lwjgl
                copyNativesFromApkLibDir(nativesDir)
                copyLwjglJarFromAssets(internalBaseDir)
                prePopulateLwjglExtractDir(internalBaseDir, nativesDir, mcVersion)

                // 4) 빈 mods 폴더
                File(instanceDir, "mods").mkdirs()

                // 5) 인스턴스 메타 저장
                InstanceManager.saveMeta(
                    this@MainActivity,
                    InstanceMeta(
                        id = instanceId,
                        name = "$mcVersion · ${if (isNeoForge) "NeoForge" else "Forge"} $forgeVersion",
                        type = InstanceType.MODPACK,
                        mcVersion = mcVersion,
                        loaderType = loaderType,
                        loaderVersion = forgeVersion,
                        mainClass = forgeResult.mainClass,
                        extraJars = forgeResult.extraJars,
                        assetIndexId = manifest.assetIndexId,
                        iconEmoji = if (isNeoForge) "🟢" else "🔥",
                        gameJvmArgs = forgeResult.gameJvmArgs,
                        gameArgs = forgeResult.gameArgs
                    )
                )

                _progress.value = DownloadProgress(phase = DownloadPhase.DONE)

                withContext(Dispatchers.Main) {
                    MinecraftActivity.start(
                        this@MainActivity,
                        versionId = mcVersion,
                        assetIndex = manifest.assetIndexId,
                        extraJars = forgeResult.extraJars,
                        mainClass = forgeResult.mainClass,
                        instanceDir = instanceDir.absolutePath
                    )
                }
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "Forge 흐름 실패: ${e.message}", e)
                _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = e.message)
            }
        }
    }

    private fun startFabricDownloadAndPlay(version: VersionEntry, loaderVersion: String) {
        val mcVersion = version.id
        val instanceId = InstanceManager.fabricId(mcVersion, loaderVersion)
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        val internalBaseDir = applicationContext.filesDir
        val nativesDir = File(internalBaseDir, "natives")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                _progress.value = DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST)

                // 1) 바닐라 다운로드 (인스턴스 dir로)
                val mcPreparer = MinecraftDownloader(
                    instanceDir = instanceDir,
                    versionEntry = version,
                    onProgress = { _progress.value = it }
                )

                val manifest = mcPreparer.prepare()

                // 2) Fabric 라이브러리 같은 인스턴스 dir에 머지
                val fabricResult = kr.co.donghyun.pinglauncher.presentation.util.fabric.FabricInstaller(
                    instanceDir
                ) { msg, cur, tot ->
                    _progress.value = DownloadProgress(
                        phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                        current = cur, total = tot, fileName = msg
                    )
                }.install(mcVersion, loaderVersion)

                if (!fabricResult.success) {
                    _progress.value = DownloadProgress(
                        phase = DownloadPhase.ERROR,
                        error = "Fabric 설치 실패: ${fabricResult.error}"
                    )
                    return@launch
                }

                // 3) natives & lwjgl
                copyNativesFromApkLibDir(nativesDir)
                copyLwjglJarFromAssets(internalBaseDir)
                prePopulateLwjglExtractDir(internalBaseDir, nativesDir, mcVersion)

                // 4) mods 폴더 보장
                File(instanceDir, "mods").mkdirs()

                // 5) 인스턴스 메타 저장
                InstanceManager.saveMeta(
                    this@MainActivity,
                    InstanceMeta(
                        id = instanceId,
                        name = "$mcVersion · Fabric $loaderVersion",
                        type = InstanceType.FABRIC,
                        mcVersion = mcVersion,
                        loaderType = "fabric",
                        loaderVersion = loaderVersion,
                        mainClass = fabricResult.mainClass,
                        extraJars = fabricResult.extraJars,
                        assetIndexId = manifest.assetIndexId,
                        iconEmoji = "🧵",
                        gameJvmArgs = fabricResult.gameJvmArgs,
                        gameArgs = fabricResult.gameArgs
                    )
                )

                _progress.value = DownloadProgress(phase = DownloadPhase.DONE)

                withContext(Dispatchers.Main) {
                    MinecraftActivity.start(
                        this@MainActivity,
                        versionId = mcVersion,
                        assetIndex = manifest.assetIndexId,
                        extraJars = fabricResult.extraJars,
                        mainClass = fabricResult.mainClass,
                        instanceDir = instanceDir.absolutePath
                    )
                }
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "Fabric 흐름 실패: ${e.message}", e)
                _progress.value = DownloadProgress(phase = DownloadPhase.ERROR, error = e.message)
            }
        }
    }

    // 새 메서드
    private fun refreshInstances() {
        lifecycleScope.launch(Dispatchers.IO) {
            _instances.value = InstanceManager.listInstances(this@MainActivity)
                .sortedByDescending {
                    InstanceManager.instanceDir(this@MainActivity, it.id).lastModified()
                }
        }
    }

    private fun launchInstance(meta: InstanceMeta) {
        val instanceDir = InstanceManager.instanceDir(this, meta.id)
        val internalBase = applicationContext.filesDir
        val nativesDir = File(internalBase, "natives")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                copyNativesFromApkLibDir(nativesDir)
                copyLwjglJarFromAssets(internalBase)
                prePopulateLwjglExtractDir(internalBase, nativesDir, meta.mcVersion)
                withContext(Dispatchers.Main) {
                    MinecraftActivity.start(
                        this@MainActivity,
                        versionId   = meta.mcVersion,
                        assetIndex  = meta.assetIndexId,
                        extraJars   = meta.extraJars,
                        mainClass   = meta.mainClass,
                        instanceDir = instanceDir.absolutePath,
                    )
                }
            } catch (e: Exception) {
                Log.e("PING_LAUNCHER", "인스턴스 실행 실패: ${e.message}", e)
            }
        }
    }

    private fun deleteInstance(meta: InstanceMeta) {
        lifecycleScope.launch(Dispatchers.IO) {
            InstanceManager.deleteInstance(this@MainActivity, meta.id)
            refreshInstances()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("ping_launcher", MODE_PRIVATE)
        val pendingCrash = prefs.getString("pending_crash_instance", null)
        if (pendingCrash != null) {
            prefs.edit().remove("pending_crash_instance").apply()
            CrashReportActivity.start(this, pendingCrash)
        }
        refreshLoginState()
        refreshInstances()
    }
}