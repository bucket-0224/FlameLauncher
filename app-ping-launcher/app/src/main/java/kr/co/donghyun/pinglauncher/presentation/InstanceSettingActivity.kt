package kr.co.donghyun.pinglauncher.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.screen.InstanceSettingsScreen
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.maps.MapImporter
import kr.co.donghyun.pinglauncher.presentation.util.resources.ResourcePackImporter
import java.io.File

/**
 * 인스턴스별 설정 화면.
 *   - 맵(월드) 가져오기   : SAF 로 zip 선택 → 진행 다이얼로그 → saves/ 로 압축 해제
 *   - 리소스팩 가져오기   : SAF 로 zip 선택 → resourcepacks/ 로 복사
 *   - 인스턴스 삭제       : 확인 다이얼로그 후 삭제하고 화면 종료
 *
 * MainScreen 의 인스턴스 행에서 ⚙ 버튼으로 진입한다. (start(context, instanceId, instanceName))
 */
class InstanceSettingsActivity : BaseActivity() {

    private lateinit var instanceId: String
    private var instanceName: String = ""

    val importMessage = mutableStateOf("")
    val importing = mutableStateOf(false)


    // 맵 zip SAF 피커
    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importMap(
            importMessage = importMessage,
            importing = importing,
            zipUri = uri
        )
    }

    override fun onCreated() {
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: run {
            finish(); return
        }
        instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME) ?: instanceId

        setContent {
            PingLauncherTheme {
                InstanceSettingsScreen(
                    instanceName = instanceName,
                    launchMapPicker = { message, importing ->
                        importMessage.value = message
                        this.importing.value = importing

                        launchMapPicker()
                    },
                    deleteInstance = { finish ->
                        deleteInstance(finish)
                    },
                    finish = { finish() }
                )
            }
        }
    }


    // ── 동작들 ──

    private val zipMimeTypes = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    private fun launchMapPicker() {
        try {
            mapPicker.launch(zipMimeTypes)
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "맵 피커 실행 실패: ${e.message}", e)
            Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importMap(importMessage: MutableState<String>, importing: MutableState<Boolean>, zipUri: Uri) {
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

    private fun deleteInstance(deletedFinish : MutableState<Boolean>) {
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