package kr.co.donghyun.pinglauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kr.co.donghyun.pinglauncher.data.key.KeyButton
import kr.co.donghyun.pinglauncher.data.key.KeyLayoutManager
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet
import java.util.UUID

private val Pink = Color(0xFFE91E8C)
private val TextMain = Color(0xFFFCE4EC)
private val TextSub = Color(0xFFBB86A0)

object GlfwKeysAll {
    data class KeyInfo(val label: String, val glfwCode: Int)

    val ALL_KEYS = listOf(
        KeyInfo("A", 65), KeyInfo("B", 66), KeyInfo("C", 67), KeyInfo("D", 68),
        KeyInfo("E", 69), KeyInfo("F", 70), KeyInfo("G", 71), KeyInfo("H", 72),
        KeyInfo("I", 73), KeyInfo("J", 74), KeyInfo("K", 75), KeyInfo("L", 76),
        KeyInfo("M", 77), KeyInfo("N", 78), KeyInfo("O", 79), KeyInfo("P", 80),
        KeyInfo("Q", 81), KeyInfo("R", 82), KeyInfo("S", 83), KeyInfo("T", 84),
        KeyInfo("U", 85), KeyInfo("V", 86), KeyInfo("W", 87), KeyInfo("X", 88),
        KeyInfo("Y", 89), KeyInfo("Z", 90),
        KeyInfo("0", 48), KeyInfo("1", 49), KeyInfo("2", 50), KeyInfo("3", 51),
        KeyInfo("4", 52), KeyInfo("5", 53), KeyInfo("6", 54), KeyInfo("7", 55),
        KeyInfo("8", 56), KeyInfo("9", 57),
        KeyInfo("ESC", 256), KeyInfo("↵", 257), KeyInfo("Tab", 258), KeyInfo("Space", 32),
        KeyInfo("BS", 259), KeyInfo("Del", 261), KeyInfo("Ins", 260),
        KeyInfo("↑", 265), KeyInfo("↓", 264), KeyInfo("←", 263), KeyInfo("→", 262),
        KeyInfo("⇧L", 340), KeyInfo("⌃L", 341), KeyInfo("AltL", 342),
        KeyInfo("F1", 290), KeyInfo("F2", 291), KeyInfo("F3", 292), KeyInfo("F4", 293),
        KeyInfo("F5", 294), KeyInfo("F6", 295), KeyInfo("F7", 296), KeyInfo("F8", 297),
        KeyInfo("F9", 298), KeyInfo("F10", 299), KeyInfo("F11", 300), KeyInfo("F12", 301)
    )
}

@Composable
fun KeyboardLayoutEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var buttons by remember { mutableStateOf(KeyLayoutManager.load(context)) }
    var selectedButtonId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val tablet = isTablet()

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        // 툴바 레이아웃 비율 반응형 조정
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(0.dp))
                .padding(horizontal = if (tablet) 16.dp else 10.dp, vertical = if (tablet) 12.dp else 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("취소", color = TextSub, fontSize = if (tablet) 16.sp else 13.sp)
            }
            Text("가상 키패드 편집", color = TextMain, fontSize = if (tablet) 18.sp else 14.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { buttons = KeyLayoutManager.reset(context); selectedButtonId = null }) {
                    Text("초기화", color = Color(0xFFFF6B6B), fontSize = if (tablet) 14.sp else 11.sp)
                }
                Button(
                    onClick = { KeyLayoutManager.save(context, buttons); onBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(if (tablet) 36.dp else 30.dp)
                ) {
                    Text("적용", color = Color.White, fontSize = if (tablet) 13.sp else 11.sp)
                }
            }
        }

        // 배치 편집 캔버스 공간
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                .onGloballyPositioned { canvasSize = it.size }
        ) {
            buttons.forEach { btn ->
                DraggableKeyButton(
                    button = btn,
                    canvasSize = canvasSize,
                    isSelected = selectedButtonId == btn.id,
                    onSelect = { selectedButtonId = btn.id },
                    onMove = { dx, dy ->
                        buttons = buttons.map {
                            if (it.id == btn.id) {
                                val nx = (it.x + dx).coerceIn(0.05f, 0.95f)
                                val ny = (it.y + dy).coerceIn(0.05f, 0.95f)
                                it.copy(x = nx, y = ny)
                            } else it
                        }
                    },
                    onDelete = {
                        buttons = buttons.filter { it.id != btn.id }
                        selectedButtonId = null
                    },
                    tablet = tablet
                )
            }

            // 키 추가용 플로팅 플랫 컴포넌트
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .height(if (tablet) 44.dp else 36.dp)
            ) {
                Text("+ 키 추가", fontSize = if (tablet) 14.sp else 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showAddDialog) {
        AddKeyDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { keyInfo ->
                buttons = buttons + KeyButton(
                    id = UUID.randomUUID().toString(),
                    label = keyInfo.label,
                    glfwCode = keyInfo.glfwCode,
                    x = 0.5f,
                    y = 0.5f,
                    width = if (tablet) 52f else 42f,
                    height = if (tablet) 52f else 42f,
                    isAccent = keyInfo.glfwCode == 32
                )
                showAddDialog = false
            },
            tablet = tablet
        )
    }
}

@Composable
fun DraggableKeyButton(
    button: KeyButton,
    canvasSize: IntSize,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onDelete: () -> Unit,
    tablet: Boolean
) {
    val density = LocalDensity.current
    val viewWidth = canvasSize.width.toFloat()
    val viewHeight = canvasSize.height.toFloat()

    val btnWidthDp = button.width.dp
    val btnHeightDp = button.height.dp

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { (button.x * viewWidth).toDp() } - (btnWidthDp / 2),
                y = with(density) { (button.y * viewHeight).toDp() } - (btnHeightDp / 2)
            )
            .size(width = btnWidthDp, height = btnHeightDp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Pink.copy(alpha = 0.4f) else BgSurface)
            .border(2.dp, if (isSelected) Pink else BgBorder, RoundedCornerShape(8.dp))
            .pointerInput(button.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onSelect()
                    var lastPos = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val dragEvent = event.changes.firstOrNull { it.pressed } ?: break
                        val currentPos = dragEvent.position
                        val dx = (currentPos.x - lastPos.x) / viewWidth
                        val dy = (currentPos.y - lastPos.y) / viewHeight
                        if (dx != 0f || dy != 0f) {
                            onMove(dx, dy)
                            dragEvent.consume()
                        }
                        lastPos = currentPos
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(button.label, color = TextMain, fontSize = if (tablet) 12.sp else 10.sp, fontWeight = FontWeight.Bold)

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .background(Color.Red, CircleShape)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AddKeyDialog(onDismiss: () -> Unit, onAdd: (GlfwKeysAll.KeyInfo) -> Unit, tablet: Boolean) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (tablet) 0.8f else 0.95f)
                .height(if (tablet) 450.dp else 340.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Column {
                Text("추가할 키 선택", color = TextMain, fontSize = if (tablet) 16.sp else 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val chunks = GlfwKeysAll.ALL_KEYS.chunked(if (tablet) 6 else 4)
                    items(chunks) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { keyInfo ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(if (tablet) 44.dp else 34.dp)
                                        .background(BgDark, RoundedCornerShape(6.dp))
                                        .border(1.dp, BgBorder, RoundedCornerShape(6.dp))
                                        .clickable { onAdd(keyInfo) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(keyInfo.label, color = TextMain, fontSize = if (tablet) 12.sp else 10.sp)
                                }
                            }
                            repeat((if (tablet) 6 else 4) - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}