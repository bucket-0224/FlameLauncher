package kr.co.donghyun.pinglauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import java.util.UUID

private val Pink = Color(0xFFE91E8C)
private val TextMain = Color(0xFFFCE4EC)
private val TextSub = Color(0xFFBB86A0)

object GlfwKeysAll {
    data class KeyInfo(val label: String, val glfwCode: Int)

    val ALL_KEYS = listOf(
        // 알파벳
        KeyInfo("A", 65), KeyInfo("B", 66), KeyInfo("C", 67), KeyInfo("D", 68),
        KeyInfo("E", 69), KeyInfo("F", 70), KeyInfo("G", 71), KeyInfo("H", 72),
        KeyInfo("I", 73), KeyInfo("J", 74), KeyInfo("K", 75), KeyInfo("L", 76),
        KeyInfo("M", 77), KeyInfo("N", 78), KeyInfo("O", 79), KeyInfo("P", 80),
        KeyInfo("Q", 81), KeyInfo("R", 82), KeyInfo("S", 83), KeyInfo("T", 84),
        KeyInfo("U", 85), KeyInfo("V", 86), KeyInfo("W", 87), KeyInfo("X", 88),
        KeyInfo("Y", 89), KeyInfo("Z", 90),
        // 숫자
        KeyInfo("0", 48), KeyInfo("1", 49), KeyInfo("2", 50), KeyInfo("3", 51),
        KeyInfo("4", 52), KeyInfo("5", 53), KeyInfo("6", 54), KeyInfo("7", 55),
        KeyInfo("8", 56), KeyInfo("9", 57),
        // 기능키
        KeyInfo("F1", 290), KeyInfo("F2", 291), KeyInfo("F3", 292), KeyInfo("F4", 293),
        KeyInfo("F5", 294), KeyInfo("F6", 295), KeyInfo("F7", 296), KeyInfo("F8", 297),
        KeyInfo("F9", 298), KeyInfo("F10", 299), KeyInfo("F11", 300), KeyInfo("F12", 301),
        // 특수키
        KeyInfo("ESC", 256), KeyInfo("↵", 257), KeyInfo("Tab", 258), KeyInfo("Space", 32),
        KeyInfo("BS", 259), KeyInfo("Del", 261), KeyInfo("Ins", 260),
        KeyInfo("Home", 268), KeyInfo("End", 269), KeyInfo("PgUp", 266), KeyInfo("PgDn", 267),
        // 방향키
        KeyInfo("↑", 265), KeyInfo("↓", 264), KeyInfo("←", 263), KeyInfo("→", 262),
        // 수정키
        KeyInfo("⇧L", 340), KeyInfo("⇧R", 344),
        KeyInfo("⌃L", 341), KeyInfo("⌃R", 345),
        KeyInfo("AltL", 342), KeyInfo("AltR", 346),
        KeyInfo("Super", 343),
        KeyInfo("CpLk", 280), KeyInfo("ScrLk", 281), KeyInfo("NmLk", 282),
        // 특수문자
        KeyInfo("'", 39), KeyInfo(",", 44), KeyInfo("-", 45), KeyInfo(".", 46),
        KeyInfo("/", 47), KeyInfo(";", 59), KeyInfo("=", 61),
        KeyInfo("[", 91), KeyInfo("\\", 92), KeyInfo("]", 93), KeyInfo("`", 96),
        // 키패드
        KeyInfo("N0", 320), KeyInfo("N1", 321), KeyInfo("N2", 322), KeyInfo("N3", 323),
        KeyInfo("N4", 324), KeyInfo("N5", 325), KeyInfo("N6", 326), KeyInfo("N7", 327),
        KeyInfo("N8", 328), KeyInfo("N9", 329),
        KeyInfo("N.", 330), KeyInfo("N/", 331), KeyInfo("N*", 332),
        KeyInfo("N-", 333), KeyInfo("N+", 334), KeyInfo("N↵", 335),
        // 마우스
        KeyInfo("⚔", -1),
        KeyInfo("🧱", -2),
        KeyInfo("🖱", -3),
        KeyInfo("⌨️", -6),  // 소프트 키보드
    )
}

@Composable
fun KeyboardLayoutEditorScreen(
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var buttons by remember { mutableStateOf(KeyLayoutManager.load(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedButtonId by remember { mutableStateOf<String?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        // 상단 툴바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(width = 1.dp, color = BgBorder, shape = RoundedCornerShape(0.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("취소", color = TextSub, fontSize = 14.sp)
            }
            Text("키패드 편집", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    buttons = KeyLayoutManager.reset(context)
                    selectedButtonId = null
                }) {
                    Text("초기화", color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        KeyLayoutManager.save(context, buttons)
                        onSave()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("저장", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        // 편집 영역
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1A1A2E))
                .onGloballyPositioned { canvasSize = it.size }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        selectedButtonId = null
                    }
                }
        ) {
            if (canvasSize != IntSize.Zero) {
                buttons.forEach { button ->
                    DraggableKeyButton(
                        button = button,
                        canvasSize = canvasSize,
                        isSelected = selectedButtonId == button.id,
                        onSelect = {
                            selectedButtonId = if (selectedButtonId == button.id) null else button.id
                        },
                        onMove = { newX, newY ->
                            buttons = buttons.map {
                                if (it.id == button.id) it.copy(x = newX, y = newY) else it
                            }
                        },
                        onDelete = {
                            buttons = buttons.filter { it.id != button.id }
                            selectedButtonId = null
                        },
                        onResizeW = { newW ->
                            buttons = buttons.map {
                                if (it.id == button.id) it.copy(width = newW) else it
                            }
                        },
                        onResizeH = { newH ->
                            buttons = buttons.map {
                                if (it.id == button.id) it.copy(height = newH) else it
                            }
                        }
                    )
                }
            }
        }

        // 하단 바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(width = 1.dp, color = BgBorder, shape = RoundedCornerShape(0.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "드래그 이동 • 탭 선택 → 크기조절/삭제",
                color = TextSub, fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("+ 추가", color = Color.White, fontSize = 13.sp)
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
                    width = 52f,
                    height = 52f,
                    isAccent = keyInfo.glfwCode == 32 || keyInfo.glfwCode == -2
                )
                showAddDialog = false
            }
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
    onResizeW: (Float) -> Unit,
    onResizeH: (Float) -> Unit
) {
    val density = LocalDensity.current
    val buttonRef = rememberUpdatedState(button)
    val onMoveRef = rememberUpdatedState(onMove)

    val wDp = with(density) { (button.width).dp }
    val hDp = with(density) { (button.height).dp }
    val xDp = with(density) { (button.x * canvasSize.width).toDp() }
    val yDp = with(density) { (button.y * canvasSize.height).toDp() }

    val bgColor = if (button.isAccent) PinkDark.copy(alpha = 0.85f) else BgDark.copy(alpha = 0.85f)
    val borderColor = when {
        isSelected -> Pink
        button.isAccent -> Pink.copy(alpha = 0.6f)
        else -> BgBorder
    }

    Box(modifier = Modifier.offset(x = xDp, y = yDp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(wDp)
                .height(hDp)
                .background(bgColor, RoundedCornerShape(10.dp))
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(10.dp)
                )
                .pointerInput(button.id, canvasSize) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var isDragging = false
                        var totalDrag = androidx.compose.ui.geometry.Offset.Zero
                        val touchSlop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                if (!isDragging) onSelect()
                                break
                            }
                            val dragDelta = change.positionChange()
                            totalDrag += dragDelta
                            if (!isDragging && totalDrag.getDistance() > touchSlop) isDragging = true
                            if (isDragging) {
                                change.consume()
                                val current = buttonRef.value
                                val newX = (current.x + dragDelta.x / canvasSize.width).coerceIn(0f, 0.95f)
                                val newY = (current.y + dragDelta.y / canvasSize.height).coerceIn(0f, 0.9f)
                                onMoveRef.value(newX, newY)
                            }
                        }
                    }
                }
        ) {
            Text(
                text = button.label,
                color = Color.White,
                fontSize = (minOf(wDp.value, hDp.value) * 0.3f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        if (isSelected) {
            // 삭제
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .background(Color(0xFFFF4444), CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture { awaitFirstDown().consume(); onDelete() }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            // 크기 조절 패널
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 36.dp)
                    .background(BgSurface, RoundedCornerShape(6.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(6.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 가로
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("W", color = TextSub, fontSize = 9.sp)
                    Text("−", color = TextMain, fontSize = 16.sp,
                        modifier = Modifier.pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown().consume(); onResizeW((button.width - 8f).coerceAtLeast(32f)) }
                        })
                    Text("${button.width.toInt()}", color = TextSub, fontSize = 9.sp)
                    Text("+", color = TextMain, fontSize = 16.sp,
                        modifier = Modifier.pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown().consume(); onResizeW((button.width + 8f).coerceAtMost(200f)) }
                        })
                }
                // 세로
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("H", color = TextSub, fontSize = 9.sp)
                    Text("−", color = TextMain, fontSize = 16.sp,
                        modifier = Modifier.pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown().consume(); onResizeH((button.height - 8f).coerceAtLeast(32f)) }
                        })
                    Text("${button.height.toInt()}", color = TextSub, fontSize = 9.sp)
                    Text("+", color = TextMain, fontSize = 16.sp,
                        modifier = Modifier.pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown().consume(); onResizeH((button.height + 8f).coerceAtMost(200f)) }
                        })
                }
            }
        }
    }
}

@Composable
fun AddKeyDialog(
    onDismiss: () -> Unit,
    onAdd: (GlfwKeysAll.KeyInfo) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredKeys = remember(searchQuery) {
        if (searchQuery.isEmpty()) GlfwKeysAll.ALL_KEYS
        else GlfwKeysAll.ALL_KEYS.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .background(BgSurface, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text("키 추가", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("검색...", color = TextSub) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Pink,
                    unfocusedBorderColor = BgBorder,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    cursorColor = Pink
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 그리드 형태로 키 표시
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 8개씩 묶어서 행으로
                val rows = filteredKeys.chunked(8)
                items(rows) { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        row.forEach { keyInfo ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(
                                        if (keyInfo.glfwCode < 0) PinkDark.copy(alpha = 0.4f)
                                        else BgDark,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (keyInfo.glfwCode < 0) Pink.copy(alpha = 0.6f) else BgBorder,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .pointerInput(keyInfo) {
                                        awaitEachGesture {
                                            awaitFirstDown().consume()
                                            onAdd(keyInfo)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = keyInfo.label,
                                    color = TextMain,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                        // 빈 칸 채우기
                        repeat(8 - row.size) {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }
    }
}