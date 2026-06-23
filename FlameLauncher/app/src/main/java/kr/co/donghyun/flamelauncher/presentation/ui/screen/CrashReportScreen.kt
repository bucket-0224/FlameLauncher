package kr.co.donghyun.flamelauncher.presentation.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSub
import kr.co.donghyun.flamelauncher.presentation.ui.theme.WarnBg
import kr.co.donghyun.flamelauncher.presentation.util.crash.CrashLogParser
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet

@Composable
fun CrashReportScreen(
    logPath: String,
    logContent: String,
    isLoading: Boolean,
    suspects: List<CrashLogParser.SuspectMod>,
    onBack: () -> Unit,
    onToggleMod: (jarName: String, enable: Boolean) -> Unit,
) {
    val tablet = isTablet()
    val context = LocalContext.current

    // 크래시 원문 접기/펼치기 (기본 접힘)
    var rawExpanded by remember { mutableStateOf(false) }

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
            TextButton(
                onClick = {
                    if (logContent.isNotEmpty()) {
                        copyToClipboard(context, logContent)
                        Toast.makeText(context, "로그를 복사했습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text("복사", color = Flame, fontSize = if (tablet) 14.sp else 11.sp)
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Flame)
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
            // ── 감지된 문제 가능성 모드 ──
            if (suspects.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "⚠️ 문제 가능성이 있는 모드",
                            color = TextMain,
                            fontSize = if (tablet) 14.sp else 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "크래시 로그에서 추출한 모드입니다. 끄면 다음 실행에서 로드되지 않습니다.\n" +
                                    "(원인이 아닐 수도 있으니 하나씩 시도해 보세요)",
                            color = TextSub,
                            fontSize = if (tablet) 11.sp else 9.sp
                        )
                        suspects.forEach { mod ->
                            SuspectRow(
                                mod = mod,
                                tablet = tablet,
                                pink = Flame,
                                textMain = TextMain,
                                textSub = TextSub,
                                warnBg = WarnBg,
                                onToggle = { enable -> onToggleMod(mod.jarName, enable) }
                            )
                        }
                    }
                }
            }

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

            // ── 로그 내용 (접기/펼치기) ──
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rawExpanded = !rawExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📋 크래시 원문",
                        color = TextMain,
                        fontSize = if (tablet) 14.sp else 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (rawExpanded) "접기 ▲" else "펼치기 ▼",
                        color = Flame,
                        fontSize = if (tablet) 13.sp else 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                AnimatedVisibility(visible = rawExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgSurface, RoundedCornerShape(10.dp))
                            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                            .padding(if (tablet) 12.dp else 10.dp)
                            .horizontalScroll(rememberScrollState())
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
                if (!rawExpanded) {
                    Text(
                        "탭하면 전체 로그를 볼 수 있습니다.",
                        color = TextSub,
                        fontSize = if (tablet) 11.sp else 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SuspectRow(
    mod: CrashLogParser.SuspectMod,
    tablet: Boolean,
    pink: androidx.compose.ui.graphics.Color,
    textMain: androidx.compose.ui.graphics.Color,
    textSub: androidx.compose.ui.graphics.Color,
    warnBg: androidx.compose.ui.graphics.Color,
    onToggle: (Boolean) -> Unit,
) {
    // 토글 가능 여부: mods 에 .jar 로 있거나(끄기 가능), .pingdisabled 로 있음(켜기 가능)
    val canToggle = mod.existsInMods || mod.currentlyDisabled
    // 현재 켜져 있는가 = mods 에 활성 jar 로 존재
    val isEnabled = mod.existsInMods && !mod.currentlyDisabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(warnBg, RoundedCornerShape(10.dp))
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = if (tablet) 14.dp else 12.dp, vertical = if (tablet) 12.dp else 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mod.displayName,
                color = textMain,
                fontSize = if (tablet) 13.sp else 11.sp,
                fontWeight = FontWeight.Bold
            )
            val sub = when {
                !canToggle && mod.jarName.isEmpty() ->
                    "로그에서 의심 모드로 언급됨 (mods 폴더에 파일 없음 — 끌 수 없음)"
                mod.currentlyDisabled -> "현재 꺼져 있음 · 스택 등장 ${mod.occurrences}회"
                canToggle -> "스택 등장 ${mod.occurrences}회"
                else -> "mods 폴더에 없음"
            }
            Text(
                text = sub,
                color = textSub,
                fontSize = if (tablet) 10.sp else 8.sp
            )
            if (mod.jarName.isNotEmpty()) {
                Text(
                    text = mod.jarName,
                    color = textSub,
                    fontSize = if (tablet) 9.sp else 7.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (canToggle) {
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = pink,
                    checkedTrackColor = pink.copy(alpha = 0.4f),
                )
            )
        } else {
            Text("—", color = textSub, fontSize = if (tablet) 14.sp else 12.sp)
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("crash log", text))
}