package kr.co.donghyun.flamelauncher.presentation.ui.screen

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.FlamePrimary
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextPrimary
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSecondary
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet

/**
 * 진행/결과 상태는 모두 이 Composable 이 소유한다(remember).
 * Activity 는 상태를 들고 있지 않고, 콜백으로 전달받은 MutableState 를 갱신만 한다.
 *  - launchMapPicker(message, importing) : 월드 zip 가져오기 시작/종료 시 importing 토글
 *  - launchModPicker(message, importing) : 모드 .jar 추가 시작/종료 시 importing 토글
 *  - deleteInstance(finish)              : 삭제 완료 시 finish.value = true
 *
 * @param loaderInstalled 이 인스턴스에 Forge/NeoForge/Fabric 이 설치돼 있으면 true.
 *                        false 면 모드 추가 메뉴를 비활성화한다(바닐라엔 모드 못 넣음).
 * @param loaderLabel     표시용 로더 이름("Forge"/"Fabric"/"NeoForge"). 없으면 null.
 */
@Composable
fun InstanceSettingsScreen(
    instanceName : String,
    loaderInstalled : Boolean,
    loaderLabel : String?,
    launchMapPicker : (importMessage : MutableState<String>, importing : MutableState<Boolean>) -> Unit,
    launchModPicker : (importMessage : MutableState<String>, importing : MutableState<Boolean>) -> Unit,
    deleteInstance : (finish : MutableState<Boolean>) -> Unit,
    finish : () -> Unit
) {
    // ── 화면이 살아있는 동안만 유지되는 상태 (진입할 때마다 false 로 초기화) ──
    val importing = remember { mutableStateOf(false) }
    val importMessage = remember { mutableStateOf("가져오는 중…") }
    val deletedFinish = remember { mutableStateOf(false) }

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
                    Text("뒤로", color = TextSecondary, fontSize = if (tablet) 14.sp else 11.sp)
                }

                Spacer(Modifier.width(12.dp))
                Column {
                    Text("인스턴스 설정", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(instanceName, color = TextSecondary, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 콘텐츠 추가 ──
            SectionLabel("콘텐츠 추가")
            Spacer(Modifier.height(8.dp))

            // 모드(.jar) 추가 — 로더가 깔린 인스턴스에서만 활성
            SettingRow(
                emoji = "🧩",
                title = "모드 추가 (.jar)",
                subtitle = if (loaderInstalled)
                    "${loaderLabel ?: "로더"} · .jar 모드 파일을 mods 에 추가"
                else
                    "Forge / Fabric / NeoForge 설치 시 사용 가능",
                enabled = !isImporting && loaderInstalled,
            ) { launchModPicker(importMessage, importing) }

            Spacer(Modifier.height(10.dp))

            // 월드(맵) 가져오기 — 기존
            SettingRow(
                emoji = "🗺",
                title = "월드(맵) 가져오기",
                subtitle = "zip 으로 받은 월드를 saves 에 추가",
                enabled = !isImporting,
            ) { launchMapPicker(importMessage, importing) }

            Spacer(Modifier.height(20.dp))

            // ── 관리 ──
            SectionLabel("관리")
            Spacer(Modifier.height(8.dp))

            SettingRow(
                emoji = "🗑",
                title = "인스턴스 삭제",
                subtitle = "이 인스턴스와 모든 데이터를 삭제합니다",
                enabled = !isImporting,
                danger = true,
            ) { showDeleteConfirm = true }
        }

        // ── 가져오는 중 진행 다이얼로그 (모드/월드 공용) ──
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
                            color = FlamePrimary,
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
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
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
    val titleColor = if (danger) Color(0xFFE5484D) else TextPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)   // 비활성 시 흐리게
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
                color = titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Text("›", color = TextSecondary, fontSize = 20.sp)
    }
}