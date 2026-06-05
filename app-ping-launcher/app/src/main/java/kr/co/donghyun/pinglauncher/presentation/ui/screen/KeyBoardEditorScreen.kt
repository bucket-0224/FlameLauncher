package kr.co.donghyun.pinglauncher.presentation.ui.screen

import android.util.Log
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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

// ────────────────────────────────────────────────────────────────────────
// 공통 스케일 상수 (GameControllerView 와 반드시 동일하게 유지)
// ────────────────────────────────────────────────────────────────────────
private const val BASE_BUTTON_UNIT = 52f
private const val TARGET_DP_PHONE = 48f
private const val TARGET_DP_TABLET = 76f

private fun calcBaseScale(tablet: Boolean, density: Float): Float {
    val targetDp = if (tablet) TARGET_DP_TABLET else TARGET_DP_PHONE
    return (targetDp * density) / BASE_BUTTON_UNIT
}

// ────────────────────────────────────────────────────────────────────────
// 폰/가로화면용 게임패드 프리셋 (GLFW 코드 → (x, y) 비율)
// 마인크래프트 컨트롤러 표준 배치를 재현
// ────────────────────────────────────────────────────────────────────────
private val PHONE_LAYOUT_PRESETS: Map<Int, Pair<Float, Float>> = mapOf(
    // 이동 WASD (좌측 하단, 십자형)
    87  to (0.12f to 0.7f),  // W
    65  to (0.06f to 0.88f),  // A
    83  to (0.12f to 0.88f),  // S
    68  to (0.18f to 0.88f),  // D

    // 기능키 (좌측 상단)
    256 to (0.04f to 0.10f),  // ESC
    292 to (0.12f to 0.10f),  // F3
    294 to (0.20f to 0.10f),  // F5
    -6  to (0.28f to 0.10f),  // 키보드 토글

    // 채팅/커맨드/드롭 (좌측 중단)
    84  to (0.04f to 0.28f),  // T
    47  to (0.12f to 0.28f),  // /
    81  to (0.20f to 0.28f),  // Q

    // 우측 인벤토리 / 슬롯
    69  to (0.96f to 0.7f),  // E (인벤토리)
    -4  to (0.88f to 0.7f),  // 이전 슬롯
    -7 to (0.8f to 0.7f),
    -5  to (0.88f to 0.52f),  // 다음 슬롯

    // 점프/슬쩍/달리기 (우측 하단)
    340 to (0.76f to 0.88f),  // shift = sneak
    341 to (0.84f to 0.88f),  // ctrl  = sprint
    32  to (0.92f to 0.88f),  // space = jump
)

/** 저장 데이터의 width/height 를 표준값으로 정규화 (예전 빌드 호환) */
private fun normalizeButtons(buttons: List<KeyButton>): List<KeyButton> =
    buttons.map {
        if (it.width == BASE_BUTTON_UNIT && it.height == BASE_BUTTON_UNIT) it
        else it.copy(width = BASE_BUTTON_UNIT, height = BASE_BUTTON_UNIT)
    }

/** AABB 충돌 검사 — 임의의 두 버튼이 겹치면 true */
private fun hasOverlap(
    buttons: List<KeyButton>,
    canvasSize: IntSize,
    tablet: Boolean,
    density: Float
): Boolean {
    if (buttons.size < 2 || canvasSize.width == 0 || canvasSize.height == 0) return false

    val baseScale = calcBaseScale(tablet, density)
    val half = (BASE_BUTTON_UNIT * baseScale) / 2f
    val w = canvasSize.width.toFloat()
    val h = canvasSize.height.toFloat()

    val rects = Array(buttons.size) { i ->
        val cx = buttons[i].x * w
        val cy = buttons[i].y * h
        floatArrayOf(cx - half, cy - half, cx + half, cy + half)
    }

    for (i in rects.indices) {
        for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            if (a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]) return true
        }
    }
    return false
}

/**
 * GLFW 코드별 프리셋 좌표로 매핑하여 정리한다.
 * 프리셋에 없는 커스텀 키들은 상단 중앙에 가로로 배치.
 */
private fun applyPresetLayout(
    buttons: List<KeyButton>,
    canvasSize: IntSize,
    tablet: Boolean,
    density: Float
): List<KeyButton> {
    if (buttons.isEmpty()) return buttons

    val recognized = mutableListOf<KeyButton>()
    val unrecognized = mutableListOf<KeyButton>()

    buttons.forEach { btn ->
        val preset = PHONE_LAYOUT_PRESETS[btn.glfwCode]
        if (preset != null) {
            recognized.add(btn.copy(
                x = preset.first,
                y = preset.second,
                width = BASE_BUTTON_UNIT,
                height = BASE_BUTTON_UNIT
            ))
        } else {
            unrecognized.add(btn)
        }
    }

    if (unrecognized.isEmpty() || canvasSize.width == 0) {
        return recognized
    }

    // 미인식 키는 상단 중앙(y=0.10)에 가로 한 줄로 배치
    val baseScale = calcBaseScale(tablet, density)
    val buttonPx = BASE_BUTTON_UNIT * baseScale
    val gap = buttonPx * 0.3f
    val canvasW = canvasSize.width.toFloat()
    val totalW = unrecognized.size * buttonPx + (unrecognized.size - 1) * gap
    val startX = (canvasW - totalW) / 2f

    val extras = unrecognized.mapIndexed { i, btn ->
        val cx = startX + i * (buttonPx + gap) + buttonPx / 2f
        btn.copy(
            x = (cx / canvasW).coerceIn(0.05f, 0.95f),
            y = 0.42f,                                     // 미인식 키는 중앙쪽에
            width = BASE_BUTTON_UNIT,
            height = BASE_BUTTON_UNIT
        )
    }

    return recognized + extras
}

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
    val density = LocalDensity.current
    var buttons by remember { mutableStateOf(normalizeButtons(KeyLayoutManager.load(context))) }
    var selectedButtonId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val tablet = isTablet()

    // 캔버스 측정 후 겹침 감지 → 프리셋 레이아웃으로 자동 정리
    LaunchedEffect(canvasSize.width, canvasSize.height) {
        if (canvasSize.width > 0 && canvasSize.height > 0 &&
            hasOverlap(buttons, canvasSize, tablet, density.density)
        ) {
            buttons = applyPresetLayout(buttons, canvasSize, tablet, density.density)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
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
            Row {
                Button(
                    onClick = { KeyLayoutManager.save(context, buttons); onBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("적용", color = Color.White, fontSize = if (tablet) 13.sp else 11.sp)
                }
            }
        }

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
                    }
                )
            }

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
                    width = BASE_BUTTON_UNIT,
                    height = BASE_BUTTON_UNIT,
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
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val tablet = isTablet()
    val viewWidth = canvasSize.width.toFloat()
    val viewHeight = canvasSize.height.toFloat()

    val baseScale = calcBaseScale(tablet, density.density)

    val drawUnitPx = BASE_BUTTON_UNIT * baseScale
    val btnSizeDp = with(density) { drawUnitPx.toDp() }

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { (button.x * viewWidth).toDp() } - (btnSizeDp / 2),
                y = with(density) { (button.y * viewHeight).toDp() } - (btnSizeDp / 2)
            )
            .size(width = btnSizeDp, height = btnSizeDp)
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
        val calculatedFontSize = drawUnitPx * 0.22f
        Text(
            text = button.label,
            color = TextMain,
            fontSize = with(density) { calculatedFontSize.toSp() },
            fontWeight = FontWeight.Bold
        )

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