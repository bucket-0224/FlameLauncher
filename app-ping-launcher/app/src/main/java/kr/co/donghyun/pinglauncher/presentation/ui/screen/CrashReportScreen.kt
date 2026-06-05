package kr.co.donghyun.pinglauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.pinglauncher.data.key.KeyLayoutManager
import kr.co.donghyun.pinglauncher.presentation.ModEntry
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet

@Composable
fun CrashReportScreen(
    mods: List<ModEntry>,
    crashSummary: String,
    isLoading: Boolean,
    onToggleMod: (ModEntry) -> Unit,
    onBack: () -> Unit,
    onRelaunch: () -> Unit,
) {
    val tablet = isTablet()
    val Pink = Color(0xFFE91E8C)
    val Red = Color(0xFFFF4444)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
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
                    Text("뒤로", color = TextSub, fontSize = if (tablet) 16.sp else 13.sp)
                }
                Text(
                    text = "크래시 복구 센터",
                    color = TextMain,
                    fontSize = if (tablet) 18.sp else 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(if (tablet) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 요약 섹션
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚠️ 최근 게임 분석 결과", color = Red, fontSize = if (tablet) 15.sp else 12.sp, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgSurface, RoundedCornerShape(10.dp))
                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                            .padding(if (tablet) 14.dp else 10.dp)
                    ) {
                        Text(
                            text = crashSummary.ifEmpty { "최근 감지된 오류 로그가 없거나 정상 종료되었습니다." },
                            color = TextMain,
                            fontSize = if (tablet) 13.sp else 11.sp,
                            lineHeight = if (tablet) 18.sp else 14.sp
                        )
                    }
                }
            }

            // 모드 관리 섹션 (반응형 그리드 구성 배치)
            item {
                Text("🛠️ 설치된 모드 토글 활성화/비활성화", color = TextMain, fontSize = if (tablet) 15.sp else 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Pink)
                    }
                } else {
                    // 태블릿은 2열, 폰은 1열로 바인딩 처리
                    val chunks = mods.chunked(if (tablet) 2 else 1)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        chunks.forEach { chunk ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                chunk.forEach { mod ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        ModToggleItem(mod = mod, onToggle = { onToggleMod(mod) }, tablet = tablet)
                                    }
                                }
                                if (chunk.size < (if (tablet) 2 else 1)) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModToggleItem(mod: ModEntry, onToggle: () -> Unit, tablet: Boolean) {
    val Pink = Color(0xFFE91E8C)
    val Red = Color(0xFFFF4444)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BgSurface)
            .border(1.dp, if (mod.isSuspected) Red.copy(alpha = 0.6f) else BgBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = if (tablet) 12.dp else 10.dp, vertical = if (tablet) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mod.isSuspected) {
                    Text("⚠️", fontSize = if (tablet) 12.sp else 10.sp)
                }
                Text(
                    text = mod.modId,
                    color = if (mod.enabled) TextMain else TextSub,
                    fontSize = if (tablet) 13.sp else 11.sp,
                    fontWeight = if (mod.isSuspected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = mod.fileName,
                color = TextSub.copy(alpha = 0.6f),
                fontSize = if (tablet) 10.sp else 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Switch(
            checked = mod.enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = if (mod.isSuspected) Red else Pink,
                uncheckedThumbColor = TextSub,
                uncheckedTrackColor = BgBorder
            ),
            modifier = Modifier.size(if (tablet) 48.dp else 40.dp)
        )
    }
}