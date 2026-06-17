package kr.co.donghyun.pinglauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.pinglauncher.presentation.ui.theme.PinkPrimary
import kr.co.donghyun.pinglauncher.presentation.ui.theme.TextPrimary
import kr.co.donghyun.pinglauncher.presentation.ui.theme.TextSecondary
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet

// ── 진행/결과 상태 (Compose 에서 관찰) ──
private val importing = mutableStateOf(false)            // 맵 가져오는 중(다이얼로그)
private val importMessage = mutableStateOf("맵을 가져오는 중…")
private val deletedFinish = mutableStateOf(false)

@Composable
fun InstanceSettingsScreen(
    instanceName : String,
    launchMapPicker : (message : String, importing : Boolean) -> Unit,
    deleteInstance : (finish : MutableState<Boolean>) -> Unit,
    finish : () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isImporting by importing
    val msg by importMessage
    val finishNow by deletedFinish
    val tablet = isTablet()

    // 삭제 완료되면 화면 종료
    LaunchedEffect(finishNow) {
        if (finishNow) finish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 헤더 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = finish) {
                    Text("뒤로", color = TextSub, fontSize = if (tablet) 14.sp else 11.sp)
                }

                Spacer(Modifier.width(12.dp))
                Column {
                    Text("인스턴스 설정", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(instanceName, color = TextSecondary, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 설정 항목 리스트 ──
            SettingRow(
                emoji = "🗺",
                title = "맵 가져오기",
                subtitle = "zip 으로 받은 월드를 이 인스턴스에 추가",
                enabled = !isImporting,
            ) { launchMapPicker(importMessage.value, importing.value) }

            Spacer(Modifier.height(10.dp))

            SettingRow(
                emoji = "🗑",
                title = "인스턴스 삭제",
                subtitle = "이 인스턴스와 모든 데이터를 삭제합니다",
                enabled = !isImporting,
                danger = true,
            ) { showDeleteConfirm = true }
        }

        // ── 맵 가져오는 중 진행 다이얼로그 ──
        if (isImporting) {
            Dialog(onDismissRequest = { /* 진행 중에는 닫지 못함 */ }) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgSurface)
                        .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                        .padding(28.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = PinkPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(msg, color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        }

        // ── 삭제 확인 다이얼로그 ──
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = BgSurface,
                title = { Text("인스턴스 삭제", color = TextPrimary) },
                text = {
                    Text(
                        "‘$instanceName’ 인스턴스를 삭제할까요?\n저장된 월드, 설정, 모드가 모두 사라집니다.",
                        color = TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        deleteInstance(deletedFinish)
                    }) { Text("삭제", color = Color(0xFFE5484D)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("취소", color = TextSecondary)
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingRow(
    emoji: String,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) Color(0xFFE5484D) else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Text("›", color = TextSecondary, fontSize = 20.sp)
    }
}