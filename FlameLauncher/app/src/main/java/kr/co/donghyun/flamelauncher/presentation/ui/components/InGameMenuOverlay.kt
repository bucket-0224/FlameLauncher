package kr.co.donghyun.flamelauncher.presentation.ui.components

import android.app.Activity
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
import androidx.compose.ui.window.DialogProperties
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgItem
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Green
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSub

/**
 * 인게임 설정 메뉴(Compose 오버레이).
 *
 * GameControllerView 의 ☰ 버튼으로 열린다. 두 구역:
 *  1) GUI(화면 버튼) 표시 토글 — controllerVisible 을 제어
 *  2) 온라인 LAN(Terracotta) — 룸 만들기/참여 다이얼로그 진입
 *
 * @param activity                현재 게임 Activity (Terracotta 가 VpnService 에 필요)
 * @param userName                플레이어 이름(없으면 null → 익명)
 * @param controllerVisible       현재 화면 버튼 표시 상태(초기값)
 * @param onToggleController      GUI 표시 토글 요청(실제 전환은 GameControllerView 가 수행)
 * @param onClose                 메뉴 닫기
 */
@Composable
fun InGameMenuOverlay(
    activity: Activity,
    userName: String?,
    controllerVisible: Boolean,
    onToggleController: () -> Unit,
    onClose: () -> Unit,
) {
    // 메뉴 안에서 Terracotta 다이얼로그를 띄울지
    var showTerracotta by remember { mutableStateOf(false) }
    // GUI 토글 상태(뷰의 실제 상태를 초기값으로, 전환 시 뷰에 위임)
    var guiOn by remember { mutableStateOf(controllerVisible) }

    if (showTerracotta) {
        // Terracotta 진입(자체적으로 VPN 동의 + 호스트/게스트 처리)
        TerracottaEntry(
            activity = activity,
            userName = userName,
            onClose = { showTerracotta = false }
        )
        return
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(BgSurface, RoundedCornerShape(16.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 헤더
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "인게임 설정",
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) { Text("닫기", color = TextSub, fontSize = 12.sp) }
            }

            // ── 1) GUI 표시 토글 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgItem)
                    .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("화면 버튼 표시", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("화면의 조작 버튼(GUI) 표시/숨김", color = TextSub, fontSize = 11.sp)
                }
                Switch(
                    checked = guiOn,
                    onCheckedChange = {
                        guiOn = it
                        onToggleController()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Flame,
                        uncheckedTrackColor = BgBorder
                    )
                )
            }

            // ── 2) 온라인 LAN (Terracotta) ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgItem)
                    .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                    .clickable { showTerracotta = true }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("🛰️ 온라인 LAN 멀티플레이", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "게임에서 '랜에 공개'를 켠 뒤, 룸을 만들거나 코드로 참여하세요.",
                    color = TextSub, fontSize = 11.sp
                )
            }

            Text(
                "※ 온라인 LAN 은 먼저 게임에서 월드를 열고 '랜에 공개(Open to LAN)'를 켠 상태여야 동작합니다.",
                color = TextSub, fontSize = 10.sp
            )
        }
    }
}