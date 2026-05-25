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

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                scrim = android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            PingLauncherTheme {
                val versions by _versions.asStateFlow().collectAsState()
                val progress by _progress.asStateFlow().collectAsState()
                val selected by _selectedVersion.asStateFlow().collectAsState()
                val isLoading by _isLoading.asStateFlow().collectAsState()
                val showOnlyRelease by _showOnlyRelease.asStateFlow().collectAsState()

                MainScreen(
                    versions = versions,
                    progress = progress,
                    selectedVersion = selected,
                    isLoading = isLoading,
                    showOnlyRelease = showOnlyRelease,
                    onVersionSelect = { _selectedVersion.value = it },
                    onToggleFilter = { _showOnlyRelease.value = !showOnlyRelease },
                    onDownloadAndPlay = { version -> startDownload(version) },
                    onOpenModPacks = { ModPackBrowserActivity.start(this@MainActivity) },
                    onOpenKeySettings = { KeyboardLayoutEditorActivity.start(this@MainActivity) },
                    onOpenJVMSettings = { JvmSettingsActivity.start(this@MainActivity) },
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

                val assetIndexId = preparer.prepare()

                copyNativesFromApkLibDir(nativesDir)
                copyLwjglJarFromAssets(internalBaseDir)
                prePopulateLwjglExtractDir(internalBaseDir, nativesDir, version.id)

                // 인스턴스 메타 저장
                InstanceManager.saveMeta(this@MainActivity, InstanceMeta(
                    id = instanceId,
                    name = version.id,
                    type = InstanceType.VANILLA,
                    mcVersion = version.id,
                    assetIndexId = assetIndexId,
                    iconEmoji = "🌿"
                ))

                _progress.value = DownloadProgress(phase = DownloadPhase.DONE)

                withContext(Dispatchers.Main) {
                    val instanceId = InstanceManager.vanillaId(version.id)  // "vanilla_1.21.4"
                    val instanceDir = InstanceManager.instanceDir(this@MainActivity, instanceId)

                    MinecraftActivity.start(
                        this@MainActivity,
                        versionId = version.id,
                        assetIndex = assetIndexId,
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
        assets.open("lwjgl-glfw-classes.jar").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("ping_launcher", Context.MODE_PRIVATE)
        val pendingCrash = prefs.getString("pending_crash_instance", null)
        if (pendingCrash != null) {
            prefs.edit().remove("pending_crash_instance").apply()
            CrashReportActivity.start(this, pendingCrash)
        }
        refreshLoginState()
    }
}