/*
 * Minimal Terracotta LAN-over-internet UI for FlameLauncher.
 *
 * This is original UI written for the minimal port; it drives TerracottaController.
 * Backend (Terracotta/EasyTier) is ported from ZalithLauncher2 (GPL-3.0) — see NOTICE.
 *
 * 흐름:
 *  - 대기(Waiting): "룸 만들기"(호스트) / "코드로 참여"(게스트) 선택
 *  - 호스트 진행: HostScanning → HostStarting → HostOK(룸 코드 표시·복사)
 *  - 게스트 진행: GuestConnecting → GuestStarting → GuestOK(접속 완료)
 *  - 예외: Exception(메시지 표시 + 대기 복귀)
 */
package kr.co.donghyun.flamelauncher.presentation.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Green
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.donghyun.flamelauncher.presentation.util.terracota.TerracottaController
import kr.co.donghyun.flamelauncher.presentation.util.terracota.TerracottaState
import androidx.compose.ui.res.stringResource
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgItem
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSub


/**
 * Terracotta 최소 다이얼로그.
 *
 * @param controller 이미 start() 된 컨트롤러
 * @param userName   플레이어 이름(없으면 null → 익명)
 */
@Composable
fun TerracottaDialog(
    controller: TerracottaController,
    userName: String?,
    onClose: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(BgSurface, RoundedCornerShape(16.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 헤더
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "온라인 LAN 멀티플레이",
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            when (val s = state) {
                null, is TerracottaState.Waiting -> WaitingContent(controller, userName, context)
                is TerracottaState.HostScanning,
                is TerracottaState.HostStarting -> ProgressContent("룸 생성 중...", "친구를 위한 코드를 준비하고 있어요.")
                is TerracottaState.HostOK -> HostOkContent(s, controller, context)
                is TerracottaState.GuestConnecting,
                is TerracottaState.GuestStarting -> ProgressContent("접속 중...", "호스트의 월드에 연결하고 있어요.")
                is TerracottaState.GuestOK -> GuestOkContent(controller)
                is TerracottaState.Exception -> ExceptionContent(s, controller)
                else -> ProgressContent("처리 중...", null)
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Flame),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("닫기", color = Color.White) }

        }
    }
}

@Composable
private fun WaitingContent(
    controller: TerracottaController,
    userName: String?,
    context: Context,
) {
    var showJoin by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }

    Text(
        "한 명이 게임에서 'Open to LAN'으로 월드를 연 뒤 룸을 만들면, 친구는 코드로 인터넷 너머에서 접속할 수 있어요.",
        color = TextSub, fontSize = 12.sp
    )

    // 호스트
    ActionButton(
        title = "룸 만들기 (호스트)",
        subtitle = "내 LAN 월드를 친구에게 공개",
        accent = Flame
    ) {
        controller.hostRoom(userName)
    }

    // 게스트
    ActionButton(
        title = "코드로 참여 (게스트)",
        subtitle = "친구가 준 룸 코드로 접속",
        accent = Green
    ) {
        showJoin = !showJoin
    }

    if (showJoin) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; codeError = false },
            label = { Text("룸 코드", color = TextSub) },
            singleLine = true,
            isError = codeError,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green,
                unfocusedBorderColor = BgBorder,
                focusedTextColor = TextMain,
                unfocusedTextColor = TextMain,
                cursorColor = Green
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (codeError) {
            Text("유효하지 않은 룸 코드예요.", color = Red, fontSize = 11.sp)
        }
        Button(
            onClick = {
                val trimmed = code.trim()
                if (trimmed.isEmpty() || !controller.isValidRoomCode(trimmed)) {
                    codeError = true
                } else {
                    val ok = controller.joinRoom(trimmed, userName)
                    if (!ok) {
                        codeError = true
                        Toast.makeText(context, "참여에 실패했어요. 코드를 확인해 주세요.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("참여", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HostOkContent(
    state: TerracottaState.HostOK,
    controller: TerracottaController,
    context: Context,
) {
    val code = state.code ?: "(코드 없음)"

    Text("룸이 열렸어요!", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text("아래 코드를 친구에게 보내세요.", color = TextSub, fontSize = 12.sp)

    // 코드 박스
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgItem)
            .border(1.dp, Flame, RoundedCornerShape(10.dp))
            .clickable { copyToClipboard(context, "Terracotta room code", code) }
            .padding(vertical = 16.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            code,
            color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
    Text("코드를 탭하면 복사돼요.", color = TextSub, fontSize = 10.sp)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { copyToClipboard(context, "Terracotta room code", code) },
            colors = ButtonDefaults.buttonColors(containerColor = Flame),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
        ) { Text("코드 복사", color = Color.White) }

        OutlinedButton(
            onClick = { controller.reset() },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f)
        ) { Text("룸 닫기", color = TextSub) }
    }
}

@Composable
private fun GuestOkContent(controller: TerracottaController) {
    Text("접속 완료!", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text(
        "게임의 멀티플레이어 화면에 호스트의 LAN 월드가 나타나요. 거기서 접속하세요.",
        color = TextSub, fontSize = 12.sp
    )
    OutlinedButton(
        onClick = { controller.reset() },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Text("연결 끊기", color = TextSub) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressContent(title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingIndicator(
            modifier = Modifier.size(48.dp),
            color = Flame
        )
        Column {
            Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = TextSub, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ExceptionContent(
    state: TerracottaState.Exception,
    controller: TerracottaController,
) {
    val msg = runCatching { stringResource(state.getEnumType().textRes) }
        .getOrElse { "알 수 없는 오류" }

    Text("오류가 발생했어요", color = Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text(msg, color = TextSub, fontSize = 12.sp)
    Button(
        onClick = { controller.reset() },
        colors = ButtonDefaults.buttonColors(containerColor = Flame),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Text("다시 시도", color = Color.White) }
}

@Composable
private fun ActionButton(
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgItem)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = TextSub, fontSize = 11.sp)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "복사했어요", Toast.LENGTH_SHORT).show()
}