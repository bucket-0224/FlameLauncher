package kr.co.donghyun.pinglauncher.presentation.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet

@Composable
fun CrashReportScreen(
    logPath: String,
    logContent: String,
    isLoading: Boolean,
    onBack: () -> Unit,
) {
    val tablet = isTablet()
    val context = LocalContext.current
    val Pink = androidx.compose.ui.graphics.Color(0xFFE91E8C)
    val TextMain = androidx.compose.ui.graphics.Color(0xFFFCE4EC)
    val TextSub = androidx.compose.ui.graphics.Color(0xFFBB86A0)

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        // ── 상단 바 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(0.dp))
                .padding(
                    horizontal = if (tablet) 16.dp else 10.dp,
                    vertical = if (tablet) 12.dp else 8.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("뒤로", color = TextSub, fontSize = if (tablet) 14.sp else 11.sp)
            }
            Text(
                text = "크래시 로그",
                color = TextMain,
                fontSize = if (tablet) 18.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )
            // 복사 버튼 (내용이 있을 때만 동작)
            TextButton(
                onClick = {
                    if (logContent.isNotEmpty()) {
                        copyToClipboard(context, logContent)
                        Toast.makeText(context, "로그를 복사했습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("복사", color = Pink, fontSize = if (tablet) 14.sp else 11.sp)
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Pink)
            }
            return@Column
        }

        if (logPath.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "저장된 크래시 로그가 없습니다.\n게임이 정상 종료되었거나 아직 크래시가 발생하지 않았습니다.",
                    color = TextSub,
                    fontSize = if (tablet) 14.sp else 12.sp
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(if (tablet) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 로그 파일 경로 ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "📄 로그 경로",
                        color = TextMain,
                        fontSize = if (tablet) 14.sp else 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgSurface, RoundedCornerShape(10.dp))
                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                            .padding(if (tablet) 12.dp else 10.dp)
                    ) {
                        Text(
                            text = logPath,
                            color = TextSub,
                            fontSize = if (tablet) 11.sp else 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // ── 로그 내용 (전체) ──
            item {
                Text(
                    "📋 로그 내용",
                    color = TextMain,
                    fontSize = if (tablet) 14.sp else 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgSurface, RoundedCornerShape(10.dp))
                        .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                        .padding(if (tablet) 12.dp else 10.dp)
                        .horizontalScroll(rememberScrollState())   // 긴 줄 가로 스크롤
                ) {
                    Text(
                        text = logContent,
                        color = TextMain,
                        fontSize = if (tablet) 11.sp else 9.sp,
                        lineHeight = if (tablet) 16.sp else 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("crash log", text))
}