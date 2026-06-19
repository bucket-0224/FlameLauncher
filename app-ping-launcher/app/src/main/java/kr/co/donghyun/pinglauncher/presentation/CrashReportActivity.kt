package kr.co.donghyun.pinglauncher.presentation


import android.content.Context
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.screen.CrashReportScreen
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.crash.CrashLogParser
import java.io.File

class CrashReportActivity : BaseActivity() {

    private val _logPath = MutableStateFlow("")     // 최신 로그 파일의 전체 경로
    private val _logContent = MutableStateFlow("")  // 그 파일의 전체 내용
    private val _isLoading = MutableStateFlow(true)
    private val _suspects = MutableStateFlow<List<CrashLogParser.SuspectMod>>(emptyList())

    private lateinit var modsDir: File

    companion object {
        private const val EXTRA_INSTANCE_DIR = "instance_dir"

        fun start(context: Context, instanceDir: String) {
            context.startActivity(
                Intent(context, CrashReportActivity::class.java).apply {
                    putExtra(EXTRA_INSTANCE_DIR, instanceDir)
                }
            )
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )

        val instanceDir = intent.getStringExtra(EXTRA_INSTANCE_DIR) ?: run { finish(); return }
        val instanceDirFile = File(instanceDir)
        modsDir = File(instanceDirFile, "mods")

        setContent {
            PingLauncherTheme {
                val logPath by _logPath.asStateFlow().collectAsState()
                val logContent by _logContent.asStateFlow().collectAsState()
                val isLoading by _isLoading.asStateFlow().collectAsState()
                val suspects by _suspects.asStateFlow().collectAsState()

                CrashReportScreen(
                    logPath = logPath,
                    logContent = logContent,
                    isLoading = isLoading,
                    suspects = suspects,
                    onBack = { finish() },
                    onToggleMod = { jarName, enable -> toggleMod(jarName, enable) },
                )
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            loadLatestLog(instanceDirFile)
        }
    }

    /** 최신 크래시 리포트 파일의 경로와 전체 내용을 읽고, 의심 모드를 파싱한다. */
    private fun loadLatestLog(instanceDir: File) {
        val crashDir = File(instanceDir, "crash-reports")
        val latestCrash = crashDir.listFiles()
            ?.filter { it.extension == "txt" }
            ?.maxByOrNull { it.lastModified() }

        if (latestCrash == null) {
            _logPath.value = ""
            _logContent.value = ""
            _suspects.value = emptyList()
            _isLoading.value = false
            return
        }

        _logPath.value = latestCrash.absolutePath
        val content = try {
            latestCrash.readText()
        } catch (e: Exception) {
            "로그 파일을 읽는 중 오류가 발생했습니다: ${e.message}"
        }
        _logContent.value = content
        _suspects.value = try {
            CrashLogParser.parseSuspects(content, modsDir)
        } catch (_: Exception) {
            emptyList()
        }
        _isLoading.value = false
    }

    /** 의심 모드 토글 후, 목록(켜짐/꺼짐 상태)을 갱신. */
    private fun toggleMod(jarName: String, enable: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = CrashLogParser.toggleMod(modsDir, jarName, enable)
            if (ok) {
                // 상태 재계산 (현재 로그 기준 재파싱 — mods 폴더 변화 반영)
                _suspects.value = try {
                    CrashLogParser.parseSuspects(_logContent.value, modsDir)
                } catch (_: Exception) {
                    _suspects.value
                }
            }
        }
    }
}