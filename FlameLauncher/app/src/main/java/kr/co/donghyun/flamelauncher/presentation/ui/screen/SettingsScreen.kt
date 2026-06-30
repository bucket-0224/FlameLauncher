package kr.co.donghyun.flamelauncher.presentation.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.flamelauncher.data.jvm.JvmSettingsManager
import kr.co.donghyun.flamelauncher.data.renderer.Renderer
import kr.co.donghyun.flamelauncher.data.renderer.RendererManager
import kr.co.donghyun.flamelauncher.data.renderer.RendererPluginManager
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSub
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tablet = isTablet()

    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memInfo = android.app.ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    val totalRamMb = (memInfo.totalMem / 1024 / 1024).toInt()
    val maxHeapMb = (totalRamMb / 256) * 256
    val defaultHeapMb = maxHeapMb / 2

    var settings by remember {
        val loaded = JvmSettingsManager.load(context)
        val adjusted = if (loaded.maxHeapMb == 2048)
            loaded.copy(maxHeapMb = defaultHeapMb, minHeapMb = defaultHeapMb / 4)
        else loaded
        mutableStateOf(adjusted)
    }
    var saved by remember { mutableStateOf(false) }

    // ── 전역(런처 기본) 렌더러 ─────────────────────────────────────
    // 인스턴스별 렌더러(InstanceMeta.rendererId)가 null 일 때 폴백되는 기본값.
    // 화면 진입 시 외부 플러그인(MobileGlues) 설치 여부를 한 번 스캔한다.
    LaunchedEffect(Unit) { RendererPluginManager.refresh(context, force = true) }
    var globalRenderer by remember { mutableStateOf(RendererManager.load(context)) }


    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        // 툴바 반응형 조정
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(0.dp))
                .padding(horizontal = if (tablet) 16.dp else 10.dp, vertical = if (tablet) 10.dp else 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("뒤로", color = TextSub, fontSize = if (tablet) 14.sp else 11.sp)
            }
            Text(
                text = "JVM 설정",
                color = TextMain,
                fontSize = if (tablet) 18.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { settings = JvmSettingsManager.reset(context); saved = false }) {
                    Text("초기화", color = Color(0xFFFF6B6B), fontSize = if (tablet) 13.sp else 11.sp)
                }
                Button(
                    onClick = { JvmSettingsManager.save(context, settings); saved = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Flame),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("저장", color = Color.White, fontSize = if (tablet) 13.sp else 11.sp)
                }
            }
        }

        if (saved) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Flame.copy(alpha = 0.1f))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("✅ 설정이 저장되었습니다.", color = Flame, fontSize = if (tablet) 13.sp else 11.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (tablet) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 10.dp)
        ) {
            // 전역 렌더러 섹션
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "🎨 기본 렌더러",
                    color = TextMain,
                    fontSize = if (tablet) 15.sp else 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "새 인스턴스의 기본값이자, 인스턴스별 렌더러를 지정하지 않았을 때 사용됩니다. " +
                            "(인스턴스 설정에서 개별로 덮어쓸 수 있어요)",
                    color = TextSub,
                    fontSize = if (tablet) 12.sp else 10.sp
                )

                Spacer(Modifier.height(2.dp))

                val mgAvailable = RendererPluginManager.isMobileGluesAvailable()
                // Zink/GL4ES 는 항상, MobileGlues 는 설치됐을 때만 후보.
                Renderer.selectableRenderers().forEach { r ->
                    GlobalRendererOption(
                        emoji = r.emoji,
                        title = r.displayName,
                        desc = r.description,
                        selected = globalRenderer.id == r.id,
                        tablet = tablet,
                        onClick = {
                            globalRenderer = r
                            RendererManager.save(context, r)
                        },
                    )
                }

                if (!mgAvailable) {
                    Text(
                        "ℹ️ MobileGlues 외부 앱을 설치하면 목록에 추가됩니다.",
                        color = TextSub,
                        fontSize = if (tablet) 11.sp else 9.sp
                    )
                }
            }

            // 화면 설정 섹션
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("🖥 화면 설정", color = TextMain, fontSize = if (tablet) 15.sp else 12.sp, fontWeight = FontWeight.Bold)

                SettingToggleRow(
                    emoji = "🖥", title = "전체 화면",
                    subtitle = "시스템 바를 숨기고 화면을 꽉 채웁니다",
                    checked = settings.fullscreen,
                    onCheckedChange = { settings = settings.copy(fullscreen = it); JvmSettingsManager.save(context, settings) },
                )
                ResolutionScaleRow(
                    percent = settings.resolutionScalePercent,
                    onPercentChange = { settings = settings.copy(resolutionScalePercent = it); JvmSettingsManager.save(context, settings) },
                )

                Spacer(Modifier.height(10.dp))
                Text("🧠 메모리 할당 (최대 RAM: ${totalRamMb}MB)", color = TextMain, fontSize = if (tablet) 15.sp else 12.sp, fontWeight = FontWeight.Bold)

                Column {
                    Text("최대 힙 메모리 (Xmx): ${settings.maxHeapMb} MB", color = TextMain, fontSize = if (tablet) 13.sp else 11.sp)
                    Slider(
                        value = settings.maxHeapMb.toFloat(),
                        onValueChange = { settings = settings.copy(maxHeapMb = it.toInt()) },
                        valueRange = 1024f..maxHeapMb.toFloat(),
                        steps = ((maxHeapMb - 1024) / 256),
                        colors = SliderDefaults.colors(thumbColor = Flame, activeTrackColor = Flame)
                    )
                }
            }

            // GC 섹션
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("🗑️ 가비지 컬렉터 (GC)", color = TextMain, fontSize = if (tablet) 15.sp else 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("G1GC 사용", color = TextMain, fontSize = if (tablet) 13.sp else 11.sp)
                        Text("대용량 메모리에 최적화된 최신 GC", color = TextSub, fontSize = if (tablet) 11.sp else 9.sp)
                    }
                    Switch(
                        checked = settings.useG1GC,
                        onCheckedChange = { settings = settings.copy(useG1GC = it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Flame)
                    )
                }
            }

            // 사용자 지정 인수 섹션
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("⌨️ 추가 JVM 인수", color = TextMain, fontSize = if (tablet) 15.sp else 12.sp, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = settings.extraJvmArgs,
                    onValueChange = { settings = settings.copy(extraJvmArgs = it) },
                    textStyle = TextStyle(color = TextMain, fontSize = if (tablet) 13.sp else 11.sp),
                    cursorBrush = SolidColor(Flame),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (tablet) 100.dp else 70.dp)
                        .background(BgDark, RoundedCornerShape(8.dp))
                        .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                )
            }
        }
    }
}
/**
 * 전역 렌더러 선택용 라디오 한 줄.
 * InstanceSettingsScreen 의 RendererOption 과 동일한 시각 언어를 쓰되,
 * SettingsScreen 의 폰트 스케일(tablet) 규칙에 맞춰 크기만 조정한다.
 */
@Composable
private fun GlobalRendererOption(
    emoji: String,
    title: String,
    desc: String,
    selected: Boolean,
    tablet: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) Flame else BgBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Flame.copy(alpha = 0.12f) else BgDark)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = if (tablet) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = if (tablet) 20.sp else 18.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) Flame else TextMain,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, color = TextSub, fontSize = if (tablet) 11.sp else 10.sp)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text("✓", color = Flame, fontSize = if (tablet) 18.sp else 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}