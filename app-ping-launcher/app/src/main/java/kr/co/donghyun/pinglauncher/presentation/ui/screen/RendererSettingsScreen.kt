package kr.co.donghyun.pinglauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.pinglauncher.data.renderer.Renderer
import kr.co.donghyun.pinglauncher.data.renderer.RendererManager
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet

@Composable
fun RendererSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(RendererManager.load(context)) }
    var saved by remember { mutableStateOf(false) }
    val tablet = isTablet()

    val Pink = Color(0xFFE91E8C)
    val BgDark = Color(0xFF120B10)
    val BgSurface = Color(0xFF1E0E1A)
    val BgBorder = Color(0xFF3D1A32)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
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
                text = "렌더러 설정",
                color = TextMain,
                fontSize = if (tablet) 18.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = {
                    RendererManager.save(context, selected)
                    saved = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("저장", color = Color.White, fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (tablet) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tablet) 12.dp else 8.dp)
        ) {
            Text(
                text = "마인크래프트를 그릴 그래픽 엔진 엔진 파이프라인을 선택하세요. 기기와 모드팩 버전에 따라 성능 차이가 발생할 수 있습니다.",
                color = TextSub,
                fontSize = if (tablet) 13.sp else 11.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (saved) {
                Text(
                    text = "✅ 저장됨 — 다음 실행부터 적용됩니다",
                    color = Pink,
                    fontSize = if (tablet) 13.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Renderer.values().forEach { renderer ->
                val isSelected = selected == renderer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0xFF2D0A20) else BgSurface)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Pink else BgBorder,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { selected = renderer }
                        .padding(if (tablet) 16.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(renderer.emoji, fontSize = if (tablet) 22.sp else 18.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = renderer.displayName,
                                color = if (isSelected) Pink else TextMain,
                                fontSize = if (tablet) 15.sp else 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (isSelected) {
                            Text("✓", color = Pink, fontSize = if (tablet) 18.sp else 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = renderer.description,
                        color = TextSub,
                        fontSize = if (tablet) 12.sp else 10.sp,
                        lineHeight = if (tablet) 16.sp else 13.sp
                    )
                }
            }
        }
    }
}