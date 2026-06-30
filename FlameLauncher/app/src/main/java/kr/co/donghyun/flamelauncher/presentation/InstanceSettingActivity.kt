package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.data.renderer.Renderer
import kr.co.donghyun.flamelauncher.data.renderer.RendererPluginManager
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.ui.screen.InstalledMod
import kr.co.donghyun.flamelauncher.presentation.ui.screen.InstanceSettingsScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.FlameLauncherTheme
import kr.co.donghyun.flamelauncher.presentation.util.maps.MapImporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModImporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModpackExporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModpackImporter
import java.io.File

/**
 * 인스턴스별 설정 화면.
 *   - 모드(.jar) 추가     : SAF 로 .jar (여러 개) 선택 → mods/ 로 복사 (로더 설치된 인스턴스만)
 *   - 월드(맵) 가져오기   : SAF 로 zip 선택 → 진행 다이얼로그 → saves/ 로 압축 해제
 *   - 모드팩 가져오기      : SAF 로 모드팩 zip 선택 → mods/·config/ 를 이 인스턴스에 풀어넣음
 *   - 모드팩으로 추출      : 현재 mods/·config/ 를 manifest 와 함께 zip 으로 묶어 SAF 로 저장
 *   - 인스턴스 삭제       : 확인 다이얼로그 후 삭제하고 화면 종료
 *
 * 모든 UI 상태(진행 중 여부, 메시지, 종료 플래그)는 InstanceSettingsScreen 이 remember 로 소유한다.
 * Activity 는 상태를 들고 있지 않고, Screen 이 넘겨준 MutableState 를 갱신만 한다.
 *
 * MainScreen 의 인스턴스 행에서 ⚙ 버튼으로 진입한다. (start(context, instanceId, instanceName))
 */
class InstanceSettingsActivity : BaseActivity() {

    private lateinit var instanceId: String
    private var instanceName: String = ""

    // 월드(맵) zip 피커가 돌려준 결과를 처리할 때 갱신할 상태.
    private var pendingMapMessage: MutableState<String>? = null
    private var pendingMapImporting: MutableState<Boolean>? = null

    // 모드(.jar) 피커가 돌려준 결과를 처리할 때 갱신할 상태.
    private var pendingModMessage: MutableState<String>? = null
    private var pendingModImporting: MutableState<Boolean>? = null

    // 모드팩 추출(CreateDocument) 결과를 처리할 때 갱신할 상태.
    private var pendingExportMessage: MutableState<String>? = null
    private var pendingExportImporting: MutableState<Boolean>? = null

    // 모드팩 가져오기(OpenDocument) 결과를 처리할 때 갱신할 상태.
    private var pendingMpImportMessage: MutableState<String>? = null
    private var pendingMpImportImporting: MutableState<Boolean>? = null

    // ── 월드(맵) zip SAF 피커 (단일) ──
    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val message = pendingMapMessage
        val importing = pendingMapImporting
        pendingMapMessage = null
        pendingMapImporting = null

        if (uri == null || message == null || importing == null) return@registerForActivityResult
        importMap(importMessage = message, importing = importing, zipUri = uri)
    }

    // ── 모드(.jar) SAF 피커 (여러 개) ──
    private val modPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val message = pendingModMessage
        val importing = pendingModImporting
        pendingModMessage = null
        pendingModImporting = null

        if (uris.isEmpty() || message == null || importing == null) return@registerForActivityResult
        importMods(importMessage = message, importing = importing, jarUris = uris)
    }

    // ── 모드팩 추출 SAF 피커 (CreateDocument) ──
    private val modpackExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val message = pendingExportMessage
        val importing = pendingExportImporting
        pendingExportMessage = null
        pendingExportImporting = null

        if (uri == null || message == null || importing == null) return@registerForActivityResult
        exportModpackToUri(importMessage = message, importing = importing, outputUri = uri)
    }

    // ── 모드팩 가져오기 SAF 피커 (OpenDocument) ──
    private val modpackImportPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val message = pendingMpImportMessage
        val importing = pendingMpImportImporting
        pendingMpImportMessage = null
        pendingMpImportImporting = null

        if (uri == null || message == null || importing == null) return@registerForActivityResult
        importModpackFromUri(importMessage = message, importing = importing, zipUri = uri)
    }

    override fun onCreated() {
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: run {
            finish(); return
        }
        instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME) ?: instanceId

        // 이 인스턴스의 로더 종류 확인 (모드 메뉴 활성/비활성 판단용)
        val loaderLabel = detectLoaderLabel()

        // 외부 렌더러 플러그인(MobileGlues) 설치 여부 스캔 — 화면 진입마다 갱신
        // (설치/삭제 후 돌아왔을 때 즉시 반영되도록 force=true)
        RendererPluginManager.refresh(this, force = true)

        // 인스턴스에 저장된 렌더러 id. null 이면 전역 기본 사용.
        val rendererState = mutableStateOf(
            InstanceManager.loadRendererId(this, instanceId)
        )

        // 이 인스턴스 mods/ 의 설치된 모드 목록. 진입 시 1회 스캔, 삭제/새로고침 때 갱신.
        val modsState = mutableStateOf(scanInstalledMods())

        setContent {
            FlameLauncherTheme {
                InstanceSettingsScreen(
                    instanceName = instanceName,
                    loaderInstalled = loaderLabel != null,
                    loaderLabel = loaderLabel,
                    currentRendererId = rendererState.value,
                    onRendererSelected = { id ->
                        // 메타에 저장하고 화면 상태 갱신
                        InstanceManager.updateRendererId(this, instanceId, id)
                        rendererState.value = id
                        val label = id?.let { Renderer.fromId(it).displayName } ?: "전역 기본"
                        Toast.makeText(this, "렌더러: $label 으로 설정됨", Toast.LENGTH_SHORT).show()
                    },
                    onInstallMobileGlues = { openMobileGluesRelease() },
                    installedMods = modsState.value,
                    onDeleteMod = { fileName ->
                        deleteMod(fileName)
                        modsState.value = scanInstalledMods()   // 삭제 후 즉시 갱신
                    },
                    refreshMods = { modsState.value = scanInstalledMods() },
                    launchMapPicker = { importMessage, importing ->
                        pendingMapMessage = importMessage
                        pendingMapImporting = importing
                        launchMapPicker()
                    },
                    launchModPicker = { importMessage, importing ->
                        pendingModMessage = importMessage
                        pendingModImporting = importing
                        launchModPicker()
                    },
                    importModpack = { importMessage, importing ->
                        pendingMpImportMessage = importMessage
                        pendingMpImportImporting = importing
                        launchModpackImporter()
                    },
                    exportModpack = { importMessage, importing ->
                        pendingExportMessage = importMessage
                        pendingExportImporting = importing
                        launchModpackExporter()
                    },
                    deleteInstance = { finish ->
                        deleteInstance(finish)
                    },
                    finish = { finish() }
                )
            }
        }
    }


    // ── 로더 감지 ──

    /**
     * MobileGlues 설치 안내 — 외부 브라우저로 릴리스 페이지를 연다.
     */
    private fun openMobileGluesRelease() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RendererPluginManager.MOBILEGLUES_RELEASE_URL))
            )
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "MobileGlues 안내 페이지 열기 실패: ${e.message}", e)
            Toast.makeText(this, "브라우저를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 인스턴스 메타의 loaderType 을 읽어 표시용 이름으로 변환한다.
     * forge/neoforge/fabric 이면 해당 이름, 그 외(바닐라/없음)면 null.
     */
    private fun detectLoaderLabel(): String? {
        val meta = runCatching {
            InstanceManager.loadMeta(InstanceManager.instanceDir(this, instanceId))
        }.getOrNull()
        return when (meta?.loaderType?.lowercase()) {
            "forge"    -> "Forge"
            "neoforge" -> "NeoForge"
            "fabric"   -> "Fabric"
            else       -> null
        }
    }

    // ── 설치된 모드 스캔/삭제 ──

    /**
     * 이 인스턴스 mods/ 디렉터리의 모드 목록을 읽는다.
     * 활성(.jar) + 비활성(.jar.disabled, 자동 비활성 포함) 모두 포함하며,
     * 활성 먼저 → 이름순으로 정렬한다.
     */
    private fun scanInstalledMods(): List<InstalledMod> {
        val modsDir = File(gameDirForInstance(), "mods")
        if (!modsDir.isDirectory) return emptyList()
        return modsDir.listFiles()
            ?.filter { f ->
                f.isFile && (
                        f.name.endsWith(".jar", ignoreCase = true) ||
                                f.name.endsWith(".jar.disabled", ignoreCase = true)
                        )
            }
            ?.map { f ->
                val enabled = f.name.endsWith(".jar", ignoreCase = true)
                val display = f.name
                    .removeSuffix(".disabled")
                    .removeSuffix(".jar")
                InstalledMod(
                    fileName = f.name,
                    displayName = display,
                    enabled = enabled,
                    sizeBytes = f.length(),
                )
            }
            ?.sortedWith(compareByDescending<InstalledMod> { it.enabled }.thenBy { it.displayName.lowercase() })
            ?: emptyList()
    }

    /**
     * mods/ 안의 모드 파일 1개를 삭제한다.
     * fileName 은 scanInstalledMods 가 준 실제 파일명(확장자/.disabled 포함)이라
     * 활성/비활성 어느 쪽이든 그대로 지운다. 디렉터리 밖 경로는 방어적으로 차단.
     */
    private fun deleteMod(fileName: String) {
        val modsDir = File(gameDirForInstance(), "mods")
        val target = File(modsDir, fileName)
        // 경로 탈출 방지: 정규화한 부모가 modsDir 와 같아야만 삭제.
        if (target.parentFile?.canonicalFile != modsDir.canonicalFile) {
            Log.w("FLAME_LAUNCHER", "모드 삭제 거부(경로 이상): $fileName")
            return
        }
        val ok = runCatching { target.delete() }.getOrDefault(false)
        if (ok) Log.d("FLAME_LAUNCHER", "🗑 모드 삭제: $fileName")
        else Log.w("FLAME_LAUNCHER", "모드 삭제 실패: $fileName")
    }

    /**
     * 모드/월드가 들어갈 게임 디렉터리.
     * 1.12.2 이하(legacy)는 <instance>/.minecraft, 1.13+ 는 <instance> 자체.
     * (ContentPackBrowserActivity 와 동일 기준 — 레거시는 .minecraft/mods, .minecraft/saves 를 읽음)
     */
    private fun gameDirForInstance(): File {
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        val mcVersion = runCatching {
            InstanceManager.loadMeta(instanceDir)?.mcVersion
        }.getOrNull() ?: ""
        return if (isLegacyVersion(mcVersion)) File(instanceDir, ".minecraft") else instanceDir
    }

    private fun isLegacyVersion(versionId: String): Boolean {
        val parts = versionId.removePrefix("1.").split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        return major <= 12
    }


    // ── 동작들 ──

    private val zipMimeTypes = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    // 일부 파일관리자/다운로드 폴더는 .jar 을 비표준 MIME 으로 노출하거나 위 타입과 매칭이 안 돼
    // 피커에서 회색으로 비활성된다(OptiFine 등). "*/*" 를 포함해 모두 보이게 하고,
    // 실제 jar(zip) 여부는 ModImporter 의 PK 매직바이트 검사로 거른다.
    private val jarMimeTypes = arrayOf(
        "application/java-archive",
        "application/x-java-archive",
        "application/octet-stream",
        "*/*",
    )

    private fun launchMapPicker() {
        try {
            mapPicker.launch(zipMimeTypes)
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "맵 피커 실행 실패: ${e.message}", e)
            pendingMapMessage = null
            pendingMapImporting = null
            Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchModPicker() {
        try {
            modPicker.launch(jarMimeTypes)
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "모드 피커 실행 실패: ${e.message}", e)
            pendingModMessage = null
            pendingModImporting = null
            Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 모드팩 추출 SAF(CreateDocument)를 띄운다.
     * 빈 mods 면 SAF 로 빈 파일이 생성되는 걸 막기 위해 미리 거른다.
     */
    private fun launchModpackExporter() {
        val modsDir = File(gameDirForInstance(), "mods")
        val hasMods = modsDir
            .listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
            ?.isNotEmpty() == true
        if (!hasMods) {
            pendingExportMessage = null
            pendingExportImporting = null
            Toast.makeText(this, "내보낼 모드가 없습니다. 먼저 모드를 추가하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val safeName = instanceName.ifBlank { instanceId }
                .replace(Regex("[^a-zA-Z0-9가-힣_\\-]"), "_")
                .take(40)
            modpackExporter.launch("${safeName}-modpack.zip")
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "모드팩 내보내기 피커 실행 실패: ${e.message}", e)
            pendingExportMessage = null
            pendingExportImporting = null
            Toast.makeText(this, "저장 위치 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /** 모드팩 가져오기 SAF(OpenDocument)를 띄운다. */
    private fun launchModpackImporter() {
        try {
            modpackImportPicker.launch(zipMimeTypes)
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "모드팩 가져오기 피커 실행 실패: ${e.message}", e)
            pendingMpImportMessage = null
            pendingMpImportImporting = null
            Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importMap(
        importMessage: MutableState<String>,
        importing: MutableState<Boolean>,
        zipUri: Uri,
    ) {
        val savesDir = File(gameDirForInstance(), "saves")
        importMessage.value = "맵을 가져오는 중…"
        importing.value = true   // 진행 다이얼로그 표시

        lifecycleScope.launch(Dispatchers.IO) {
            val result = MapImporter.importZip(
                context = applicationContext,
                zipUri = zipUri,
                savesDir = savesDir,
            )
            withContext(Dispatchers.Main) {
                importing.value = false   // 다이얼로그 닫기
                val text = when (result) {
                    is MapImporter.Result.Success ->
                        "‘${result.worldName}’ 맵을 가져왔습니다 (${result.fileCount}개 파일)"
                    is MapImporter.Result.Failure ->
                        result.reason
                }
                Toast.makeText(this@InstanceSettingsActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importMods(
        importMessage: MutableState<String>,
        importing: MutableState<Boolean>,
        jarUris: List<Uri>,
    ) {
        val modsDir = File(gameDirForInstance(), "mods")
        importMessage.value = "모드를 추가하는 중…"
        importing.value = true

        lifecycleScope.launch(Dispatchers.IO) {
            val result = ModImporter.importJars(
                context = applicationContext,
                uris = jarUris,
                modsDir = modsDir,
            )
            withContext(Dispatchers.Main) {
                importing.value = false
                val text = when (result) {
                    is ModImporter.Result.Success -> buildString {
                        append("모드 ${result.added.size}개 추가됨")
                        if (result.skipped.isNotEmpty())
                            append(" · ${result.skipped.size}개 건너뜀")
                    }
                    is ModImporter.Result.Failure -> result.reason
                }
                Toast.makeText(this@InstanceSettingsActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportModpackToUri(
        importMessage: MutableState<String>,
        importing: MutableState<Boolean>,
        outputUri: Uri,
    ) {
        val gameDir = gameDirForInstance()
        val modsDir = File(gameDir, "mods")
        val configDir = File(gameDir, "config").takeIf { it.isDirectory }
        val meta = runCatching {
            InstanceManager.loadMeta(InstanceManager.instanceDir(this, instanceId))
        }.getOrNull()

        importMessage.value = "모드팩으로 추출하는 중…"
        importing.value = true

        lifecycleScope.launch(Dispatchers.IO) {
            val result = ModpackExporter.export(
                context = applicationContext,
                outputUri = outputUri,
                modsDir = modsDir,
                configDir = configDir,
                meta = meta,
                displayName = instanceName,
            )
            withContext(Dispatchers.Main) {
                importing.value = false
                val text = when (result) {
                    is ModpackExporter.Result.Success -> buildString {
                        append("모드 ${result.modCount}개")
                        if (result.configCount > 0) append(" · 설정 ${result.configCount}개")
                        append("를 모드팩으로 추출했습니다.")
                    }
                    is ModpackExporter.Result.Failure -> result.reason
                }
                Toast.makeText(this@InstanceSettingsActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importModpackFromUri(
        importMessage: MutableState<String>,
        importing: MutableState<Boolean>,
        zipUri: Uri,
    ) {
        val gameDir = gameDirForInstance()
        val meta = runCatching {
            InstanceManager.loadMeta(InstanceManager.instanceDir(this, instanceId))
        }.getOrNull()
        val currentMc = meta?.mcVersion
        val currentLoader = meta?.loaderType

        importMessage.value = "모드팩을 가져오는 중…"
        importing.value = true

        lifecycleScope.launch(Dispatchers.IO) {
            val result = ModpackImporter.import(
                context = applicationContext,
                zipUri = zipUri,
                gameDir = gameDir,
                currentMcVersion = currentMc,
                currentLoaderType = currentLoader,
            )
            withContext(Dispatchers.Main) {
                importing.value = false
                val text = when (result) {
                    is ModpackImporter.Result.Success -> buildString {
                        append("모드 ${result.modCount}개")
                        if (result.configCount > 0) append(" · 설정 ${result.configCount}개")
                        append(" 가져옴")
                        if (result.mcMismatch) {
                            val packMc = result.manifest?.mcVersion ?: "?"
                            append("\n주의: 모드팩 버전($packMc)이 이 인스턴스와 달라 작동하지 않을 수 있어요.")
                        }
                    }
                    is ModpackImporter.Result.Failure -> result.reason
                }
                Toast.makeText(this@InstanceSettingsActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteInstance(deletedFinish: MutableState<Boolean>) {
        lifecycleScope.launch(Dispatchers.IO) {
            InstanceManager.deleteInstance(this@InstanceSettingsActivity, instanceId)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@InstanceSettingsActivity, "인스턴스를 삭제했습니다.", Toast.LENGTH_SHORT).show()
                deletedFinish.value = true   // 화면 종료
            }
        }
    }

    companion object {
        private const val EXTRA_INSTANCE_ID = "instance_id"
        private const val EXTRA_INSTANCE_NAME = "instance_name"

        fun start(context: Context, instanceId: String, instanceName: String) {
            context.startActivity(
                Intent(context, InstanceSettingsActivity::class.java).apply {
                    putExtra(EXTRA_INSTANCE_ID, instanceId)
                    putExtra(EXTRA_INSTANCE_NAME, instanceName)
                }
            )
        }
    }
}