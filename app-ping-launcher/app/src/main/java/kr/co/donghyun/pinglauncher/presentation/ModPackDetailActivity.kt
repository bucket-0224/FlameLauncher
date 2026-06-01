package kr.co.donghyun.pinglauncher.presentation

import ModPackDetailScreen
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.pinglauncher.BuildConfig
import kr.co.donghyun.pinglauncher.data.instance.InstanceManager
import kr.co.donghyun.pinglauncher.presentation.base.BaseActivity
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import okhttp3.OkHttpClient
import okhttp3.Request

data class ModDetail(
    val screenshots: List<ModScreenshot> = emptyList(),
    val description: String = "",
    val rawHtml: String = ""
)

data class ModScreenshot(
    val thumbnailUrl: String,  // 작은 썸네일
    val fullUrl: String        // 원본
)

class ModPackDetailActivity : BaseActivity() {

    private val _detail = MutableStateFlow<ModDetail?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _isInstalled = MutableStateFlow(false)
    private val _fullscreenIndex = MutableStateFlow<Int?>(null)


    companion object {
        const val EXTRA_MOD_ID = "mod_id"
        const val EXTRA_MOD_NAME = "mod_name"
        const val EXTRA_MOD_SUMMARY = "mod_summary"
        const val EXTRA_MOD_LOGO = "mod_logo"
        const val EXTRA_MOD_DOWNLOADS = "mod_downloads"

        fun start(context: Context, modId: Int, modName: String, modSummary: String, modLogo: String?, modDownloads: Long) {
            context.startActivity(
                Intent(context, ModPackDetailActivity::class.java).apply {
                    putExtra(EXTRA_MOD_ID, modId)
                    putExtra(EXTRA_MOD_NAME, modName)
                    putExtra(EXTRA_MOD_SUMMARY, modSummary)
                    putExtra(EXTRA_MOD_LOGO, modLogo)
                    putExtra(EXTRA_MOD_DOWNLOADS, modDownloads)
                }
            )
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                scrim = android.graphics.Color.TRANSPARENT
            )
        )
        val modId = intent.getIntExtra(EXTRA_MOD_ID, -1)
        val modName = intent.getStringExtra(EXTRA_MOD_NAME) ?: ""
        val modSummary = intent.getStringExtra(EXTRA_MOD_SUMMARY) ?: ""
        val modLogo = intent.getStringExtra(EXTRA_MOD_LOGO)
        val modDownloads = intent.getLongExtra(EXTRA_MOD_DOWNLOADS, 0)

        val instanceId = InstanceManager.modpackId(modName)
        val instanceDir = InstanceManager.instanceDir(this, instanceId)
        _isInstalled.value = instanceDir.resolve("instance.json").exists()

        setContent {
            PingLauncherTheme {
                val detail by _detail.asStateFlow().collectAsState()
                val isLoading by _isLoading.asStateFlow().collectAsState()
                val isInstalled by _isInstalled.asStateFlow().collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    ModPackDetailScreen(
                        modId = modId,
                        modName = modName,
                        modSummary = modSummary,
                        modLogo = modLogo,
                        modDownloads = modDownloads,
                        detail = detail,
                        isLoading = isLoading,
                        isInstalled = isInstalled,
                        onBack = { finish() },
                        onInstall = {
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_MOD_ID, modId).putExtra("action", "install"))
                            finish()
                        },
                        onLaunch = {
                            setResult(RESULT_OK, Intent().putExtra(EXTRA_MOD_ID, modId).putExtra("action", "launch"))
                            finish()
                        },
                        onImageClick = { index -> _fullscreenIndex.value = index  }
                    )

                    // 전체화면 이미지 뷰어
                    val fullscreenIndex by _fullscreenIndex.asStateFlow().collectAsState()

                    fullscreenIndex?.let { startIndex ->
                        val screenshots = detail?.screenshots ?: emptyList()
                        Dialog(
                            onDismissRequest = { _fullscreenIndex.value = null },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.97f))
                            ) {
                                val pagerState = rememberPagerState(
                                    initialPage = startIndex,
                                    pageCount = { screenshots.size }
                                )

                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.fillMaxSize()
                                ) { page ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { _fullscreenIndex.value = null },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AsyncImage(
                                            model = screenshots[page].fullUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxWidth(),
                                            contentScale = ContentScale.FillWidth
                                        )
                                    }
                                }

                                // 페이지 인디케이터
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 32.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    repeat(screenshots.size) { index ->
                                        val isSelected = pagerState.currentPage == index
                                        Box(
                                            modifier = Modifier
                                                .size(if (isSelected) 8.dp else 6.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(
                                                    if (isSelected) Color.White
                                                    else Color.White.copy(alpha = 0.3f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (modId != -1) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    _detail.value = fetchModDetail(modId)
                } catch (e: Exception) {
                    Log.e("PING_LAUNCHER", "상세 정보 로드 실패: ${e.message}")
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun fetchModDetail(modId: Int): ModDetail {
        val client = OkHttpClient()
        val apiKey = BuildConfig.CURSEFORGE_API_KEY
        var screenshots = listOf<ModScreenshot>()
        var description = ""
        var rawHtmlWebView = ""

        val modRequest = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()

        client.newCall(modRequest).execute().use { response ->
            val json = response.body?.string() ?: return@use
            val data = JsonParser.parseString(json)
                .asJsonObject["data"]?.asJsonObject ?: return@use

            screenshots = data["screenshots"]?.asJsonArray?.mapNotNull { el ->
                val obj = el.asJsonObject
                val full = obj["url"]?.asString ?: return@mapNotNull null
                // 썸네일: 원본 URL에서 크기 파라미터 추가 (CurseForge CDN 지원)
                val thumb = full.replace("https://media.forgecdn.net", "https://media.forgecdn.net")
                    .let { "$it?width=400&height=225" }
                ModScreenshot(thumbnailUrl = thumb, fullUrl = full)
            } ?: emptyList()
        }

        val descRequest = Request.Builder()
            .url("https://api.curseforge.com/v1/mods/$modId/description")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .build()

        client.newCall(descRequest).execute().use { response ->
            val json = response.body?.string() ?: return@use
            val rawHtml = JsonParser.parseString(json)
                .asJsonObject["data"]?.asString ?: ""
            rawHtmlWebView = rawHtml
            description = rawHtml
                // 줄바꿈 태그 먼저 변환
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
                .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
                .replace(Regex("<h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<div[^>]*>", RegexOption.IGNORE_CASE), "\n")
                // 나머지 태그 제거
                .replace(Regex("<[^>]*>"), "")
                // HTML 엔티티 변환
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                // 연속 공백/줄바꿈 정리
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        return ModDetail(screenshots = screenshots, description = description, rawHtml = rawHtmlWebView)
    }
}