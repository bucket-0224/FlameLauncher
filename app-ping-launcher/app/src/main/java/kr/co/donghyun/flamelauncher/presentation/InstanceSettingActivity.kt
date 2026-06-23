package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.MutableState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.ui.screen.InstanceSettingsScreen
import kr.co.donghyun.flamelauncher.presentation.ui.theme.PingLauncherTheme
import kr.co.donghyun.flamelauncher.presentation.util.maps.MapImporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModImporter
import java.io.File

/**
 * 인스턴스별 설정 화면.
 *   - 모드(.jar) 추가     : SAF 로 .jar (여러 개) 선택 → mods/ 로 복사 (로더 설치된 인스턴스만)
 *   - 월드(맵) 가져오기   : SAF 로 zip 선택 → 진행 다이얼로그 → saves/ 로 압축 해제
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

    override fun onCreated() {
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: run {
            finish(); return
        }
        instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME) ?: instanceId

        // 이 인스턴스의 로더 종류 확인 (모드 메뉴 활성/비활성 판단용)
        val loaderLabel = detectLoaderLabel()

        setContent {
            PingLauncherTheme {
                InstanceSettingsScreen(
                    instanceName = instanceName,
                    loaderInstalled = loaderLabel != null,
                    loaderLabel = loaderLabel,
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


    // ── 동작들 ──

    private val zipMimeTypes = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    // 일부 파일관리자는 .jar 을 octet-stream 으로만 노출한다. 안 보이면 arrayOf("*/*") 로 넓혀도 됨.
    private val jarMimeTypes = arrayOf(
        "application/java-archive",
        "application/x-java-archive",
        "application/octet-stream",
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

    private fun importMap(
        importMessage: MutableState<String>,
        importing: MutableState<Boolean>,
        zipUri: Uri,
    ) {
        val savesDir = File(InstanceManager.instanceDir(this, instanceId), "saves")
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
        val modsDir = File(InstanceManager.instanceDir(this, instanceId), "mods")
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