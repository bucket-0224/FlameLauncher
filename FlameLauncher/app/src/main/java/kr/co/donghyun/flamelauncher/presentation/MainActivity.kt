package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.flamelauncher.data.auth.MicrosoftAuthManager
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.data.instance.InstanceMeta
import kr.co.donghyun.flamelauncher.data.instance.InstanceType
import kr.co.donghyun.flamelauncher.data.mojang.DownloadPhase
import kr.co.donghyun.flamelauncher.data.mojang.DownloadProgress
import kr.co.donghyun.flamelauncher.data.mojang.VersionEntry
import kr.co.donghyun.flamelauncher.data.renderer.RendererPluginManager
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.ui.screen.MainScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.FlameLauncherTheme
import kr.co.donghyun.flamelauncher.presentation.util.fabric.FabricInstaller
import kr.co.donghyun.flamelauncher.presentation.util.forge.ForgeInstaller
import kr.co.donghyun.flamelauncher.presentation.util.minecraft.MinecraftDownloader
import kr.co.donghyun.flamelauncher.presentation.util.minecraft.VersionRepository
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

    // MobileGlues 미설치 안내 팝업 표시 여부. MinecraftActivity 가 RESULT_MOBILEGLUES_MISSING 으로
    // 종료하면 true 가 되어 설치 안내 다이얼로그를 띄운다.
    private var showMobileGluesMissing by mutableStateOf(false)

    /**
     * 게임 실행 런처. MinecraftActivity 가 MobileGlues 미설치로 게임을 띄우지 않고 종료하면
     * (RESULT_MOBILEGLUES_MISSING) 여기서 받아 안내 팝업을 띄운다.
     */
    private val minecraftLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == MinecraftActivity.RESULT_MOBILEGLUES_MISSING) {
            showMobileGluesMissing = true
        }
    }

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
            FlameLauncherTheme {
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
                    onOpenInstanceSettings = { meta ->
                        InstanceSettingsActivity.start(this@MainActivity, meta.id, meta.name)
                    },
                    onOpenContents = { ContentPackBrowserActivity.start(this@MainActivity) },
                    onOpenNetworkSettings = { NetworkSettingsActivity.start(this@MainActivity) },
                    onOpenKeySettings = { KeyboardLayoutEditorActivity.start(this@MainActivity) },
                    onOpenSettings = { SettingsActivity.start(this@MainActivity) },
                    onOpenRendererSettings = { },
                    uuid = MicrosoftAuthManager.loadSession(this@MainActivity)?.uuid,
                    isLoggedIn = isLoggedIn,
                    username = username,
                    onLogin = { loginLauncher.launch(Intent(this, LoginActivity::class.java)) },
                    onLaunchNeoForge = { v, f -> startForgeDownloadAndPlay(v, f, true) },
                )

                // MobileGlues 미설치 안내 팝업 (MinecraftActivity 가 미설치로 종료했을 때)
                if (showMobileGluesMissing) {
                    MobileGluesMissingDialog(
                        onInstall = {
                            showMobileGluesMissing = false
                            openMobileGluesInstall()
                        },
                        onDismiss = { showMobileGluesMissing = false },
                    )
                }
            }
        }

        // 버전 목록 로드
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val list = versionRepo.fetchVersionList()
                _versions.value = list
            } catch (e: Exception) {
                Log.e("FLAME_LAUNCHER", "버전 목록 로드 실패: ${e.message}")
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
                    MinecraftActivity.startForResult(
                        this@MainActivity,
                        minecraftLauncher,
                        versionId = version.id,
                        assetIndex = assetIndexId,
                        mainClass = realMainClass,               // ← 전달
                        instanceDir = instanceDir.absolutePath,
                    )
                }
            } catch (e: Exception) {
                Log.e("FLAME_LAUNCHER", "❌ 오류: ${e.message}", e)
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
                val forgeResult = ForgeInstaller(instanceDir) { msg, cur, tot ->
                    _progress.value = DownloadProgress(
                        phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                        current = cur, total = tot, fileName = msg
                    )
                }.install(this@MainActivity, mcVersion, forgeVersion, isNeoForge = isNeoForge)

                if (!forgeResult.success) {
                    Log.e("FLAME_LAUNCHER", "Forge 설치 실패: ${forgeResult.error}")
                    _progress.value = DownloadProgress(
                        phase = DownloadPhase.ERROR,
                        error = "Forge 설치 실패: ${forgeResult.error}"
                    )
                    return@launch
                }

                if (forgeResult.requiresProcessors) {
                    Log.i(
                        "FLAME_LAUNCHER",
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
                    MinecraftActivity.startForResult(
                        this@MainActivity,
                        minecraftLauncher,
                        versionId = mcVersion,
                        assetIndex = manifest.assetIndexId,
                        extraJars = forgeResult.extraJars,
                        mainClass = forgeResult.mainClass,
                        instanceDir = instanceDir.absolutePath
                    )
                }
            } catch (e: Exception) {
                Log.e("FLAME_LAUNCHER", "Forge 흐름 실패: ${e.message}", e)
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
                val fabricResult = FabricInstaller(
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
                    MinecraftActivity.startForResult(
                        this@MainActivity,
                        minecraftLauncher,
                        versionId = mcVersion,
                        assetIndex = manifest.assetIndexId,
                        extraJars = fabricResult.extraJars,
                        mainClass = fabricResult.mainClass,
                        instanceDir = instanceDir.absolutePath
                    )
                }
            } catch (e: Exception) {
                Log.e("FLAME_LAUNCHER", "Fabric 흐름 실패: ${e.message}", e)
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
                    MinecraftActivity.startForResult(
                        this@MainActivity,
                        minecraftLauncher,
                        versionId   = meta.mcVersion,
                        assetIndex  = meta.assetIndexId,
                        extraJars   = meta.extraJars,
                        mainClass   = meta.mainClass,
                        instanceDir = instanceDir.absolutePath,
                    )
                }
            } catch (e: Exception) {
                Log.e("FLAME_LAUNCHER", "인스턴스 실행 실패: ${e.message}", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLoginState()
        refreshInstances()
    }

    /** MobileGlues 설치 안내 — 외부 브라우저로 릴리스 페이지를 연다. */
    private fun openMobileGluesInstall() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RendererPluginManager.MOBILEGLUES_RELEASE_URL))
            )
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "MobileGlues 안내 페이지 열기 실패: ${e.message}", e)
            Toast.makeText(this, "브라우저를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
/**
 * MobileGlues 렌더러가 선택됐지만 플러그인 APK 가 설치돼 있지 않을 때 띄우는 안내 팝업.
 *  - "설치하러 가기" → GitHub Releases 페이지를 브라우저로 연다.
 *  - "다른 렌더러 사용" 안내(인스턴스 설정에서 변경) 텍스트 포함.
 */
@androidx.compose.runtime.Composable
private fun MobileGluesMissingDialog(
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "MobileGlues 미설치",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "이 인스턴스는 MobileGlues 렌더러로 설정돼 있지만, " +
                            "MobileGlues 앱이 설치돼 있지 않습니다.",
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "MobileGlues 앱을 설치한 뒤 다시 실행해 주세요. " +
                            "또는 인스턴스 설정에서 렌더러를 Zink/GL4ES 로 변경할 수 있습니다.",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text("설치하러 가기", color = Color(0xFFFF7A3D), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = Color(0xFFAAAAAA))
            }
        },
        containerColor = Color(0xFF1E1E1E),
    )
}