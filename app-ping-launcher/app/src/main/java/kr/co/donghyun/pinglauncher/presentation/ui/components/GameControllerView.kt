package kr.co.donghyun.pinglauncher.presentation.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kr.co.donghyun.pinglauncher.data.key.KeyButton
import kr.co.donghyun.pinglauncher.data.key.KeyLayoutManager
import kr.co.donghyun.pinglauncher.presentation.MinecraftActivity
import androidx.core.content.edit
import kr.co.donghyun.pinglauncher.presentation.util.MinecraftActivityBridge

private const val GLFW_PRESS = 1
private const val GLFW_RELEASE = 0

// ────────────────────────────────────────────────────────────────────────
// KeyboardLayoutEditorScreen 의 상수/공식과 반드시 동일하게 유지.
// ────────────────────────────────────────────────────────────────────────
private const val BASE_BUTTON_UNIT = 52f
private const val TARGET_DP_PHONE = 48f
private const val TARGET_DP_TABLET = 96f

// 폰/가로화면용 게임패드 프리셋 (GLFW 코드 → x, y 비율)
private val PHONE_LAYOUT_PRESETS: Map<Int, Pair<Float, Float>> = mapOf(
    87  to (0.12f to 0.55f),  // W
    65  to (0.04f to 0.78f),  // A
    83  to (0.12f to 0.78f),  // S
    68  to (0.20f to 0.78f),  // D
    256 to (0.04f to 0.10f),  // ESC
    292 to (0.12f to 0.10f),  // F3
    294 to (0.20f to 0.10f),  // F5
    -6  to (0.28f to 0.10f),  // keyboard toggle
    84  to (0.04f to 0.28f),  // T
    47  to (0.12f to 0.28f),  // /
    81  to (0.20f to 0.28f),  // Q
    69  to (0.96f to 0.55f),  // E (inventory)
    -4  to (0.88f to 0.55f),  // prev slot
    -5  to (0.88f to 0.28f),  // next slot
    340 to (0.76f to 0.78f),  // shift = sneak
    341 to (0.84f to 0.78f),  // ctrl  = sprint
    32  to (0.92f to 0.78f),  // space = jump
)

class GameControllerView(context: Context) : View(context) {
    private val activity = context as MinecraftActivity
    private var buttons: List<KeyButton> = KeyLayoutManager.load(context)
    private val buttonRects = mutableMapOf<String, RectF>()
    private val pressedButtons = mutableMapOf<String, Int>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(217, 26, 10, 20); style = Paint.Style.FILL
    }
    private val bgAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(217, 107, 0, 64); style = Paint.Style.FILL
    }
    private val bgPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 156, 16, 96); style = Paint.Style.FILL
    }
    private val bgAccentPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 233, 30, 140); style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(178, 122, 40, 85); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val borderAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(178, 255, 107, 181); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val cornerRadius = 20f

    private fun isTabletDevice(): Boolean =
        resources.configuration.smallestScreenWidthDp >= 600

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("PING_LAUNCHER", "GameControllerView 크기: ${w}x${h}")
        recalcRects(w, h)
    }

    private fun rectsOverlap(buttons: List<KeyButton>, w: Int, h: Int, drawSize: Float): Boolean {
        if (buttons.size < 2) return false
        val half = drawSize / 2f
        val rs = Array(buttons.size) { i ->
            val cx = buttons[i].x * w
            val cy = buttons[i].y * h
            floatArrayOf(cx - half, cy - half, cx + half, cy + half)
        }
        for (i in rs.indices) {
            for (j in i + 1 until rs.size) {
                val a = rs[i]; val b = rs[j]
                if (a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]) return true
            }
        }
        return false
    }

    /**
     * GLFW 코드별 프리셋으로 매핑하여 정리. 미인식 키는 화면 중앙쪽에 가로로 배치.
     */
    private fun applyPresetLayout(
        buttons: List<KeyButton>, w: Int, h: Int, drawSize: Float
    ): List<KeyButton> {
        if (buttons.isEmpty()) return buttons

        val recognized = mutableListOf<KeyButton>()
        val unrecognized = mutableListOf<KeyButton>()

        buttons.forEach { btn ->
            val preset = PHONE_LAYOUT_PRESETS[btn.glfwCode]
            if (preset != null) {
                recognized.add(btn.copy(
                    x = preset.first, y = preset.second,
                    width = BASE_BUTTON_UNIT, height = BASE_BUTTON_UNIT
                ))
            } else {
                unrecognized.add(btn)
            }
        }

        if (unrecognized.isEmpty() || w == 0) return recognized

        val gap = drawSize * 0.3f
        val totalW = unrecognized.size * drawSize + (unrecognized.size - 1) * gap
        val startX = (w - totalW) / 2f
        val extras = unrecognized.mapIndexed { i, btn ->
            val cx = startX + i * (drawSize + gap) + drawSize / 2f
            btn.copy(
                x = (cx / w).coerceIn(0.05f, 0.95f),
                y = 0.42f,
                width = BASE_BUTTON_UNIT, height = BASE_BUTTON_UNIT
            )
        }

        return recognized + extras
    }

    /**
     * density 기반 통합 스케일링 + 겹침 시 프리셋 레이아웃으로 자동 정리.
     */
    private fun recalcRects(w: Int, h: Int) {
        buttonRects.clear()

        val density = resources.displayMetrics.density
        val tablet = isTabletDevice()
        val targetDp = if (tablet) TARGET_DP_TABLET else TARGET_DP_PHONE
        val baseScale = (targetDp * density) / BASE_BUTTON_UNIT
        val drawSize = BASE_BUTTON_UNIT * baseScale

        // 겹침 발견 시 프리셋 적용 + 저장
        if (rectsOverlap(buttons, w, h, drawSize)) {
            Log.d("PING_LAUNCHER", "버튼 겹침 감지 — 프리셋 레이아웃으로 자동 정리")
            buttons = applyPresetLayout(buttons, w, h, drawSize)
            try { KeyLayoutManager.save(context, buttons) } catch (_: Exception) {}
        }

        buttons.forEach { button ->
            val centerX = button.x * w
            val centerY = button.y * h
            val left = centerX - (drawSize / 2f)
            val top = centerY - (drawSize / 2f)
            buttonRects[button.id] = RectF(left, top, left + drawSize, top + drawSize)
        }
    }

    override fun onDraw(canvas: Canvas) {
        buttons.forEach { button ->
            val rect = buttonRects[button.id] ?: return@forEach
            val isPressed = pressedButtons.containsKey(button.id)

            // ★ 전투 토글 버튼은 모드 상태에 따라 활성/비활성 표시
            val isCombatToggleActive = (button.glfwCode == -7 && activity.combatMode)

            val fill = when {
                isCombatToggleActive -> bgAccentPressedPaint   // 활성 = 진한 핑크
                isPressed && button.isAccent -> bgAccentPressedPaint
                isPressed -> bgPressedPaint
                button.isAccent -> bgAccentPaint
                else -> bgPaint
            }
            val border = if (button.isAccent || isCombatToggleActive) borderAccentPaint else borderPaint

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fill)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, border)

            // 라벨도 모드에 따라 바꾸기
            val labelText = when {
                button.glfwCode == -7 -> if (activity.combatMode) "⚔️" else "🛠️"
                else -> button.label
            }

            val fontSize = minOf(rect.width(), rect.height()) * 0.23f
            textPaint.textSize = fontSize
            canvas.drawText(
                labelText,
                rect.centerX(),
                rect.centerY() + fontSize * 0.35f,
                textPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val button = findButton(x, y) ?: return false
                pressedButtons[button.id] = pointerId
                handlePress(button.glfwCode, GLFW_PRESS)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val buttonId = pressedButtons.entries.find { it.value == pointerId }?.key
                if (buttonId != null) {
                    val button = buttons.find { it.id == buttonId }
                    pressedButtons.remove(buttonId)
                    if (button != null) handlePress(button.glfwCode, GLFW_RELEASE)
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    val buttonId = pressedButtons.entries.find { it.value == pid }?.key
                    if (buttonId != null) {
                        val rect = buttonRects[buttonId]
                        if (rect != null && !rect.contains(px, py)) {
                            val button = buttons.find { it.id == buttonId }
                            pressedButtons.remove(buttonId)
                            if (button != null) handlePress(button.glfwCode, GLFW_RELEASE)
                            invalidate()
                        }
                    }
                }
                return pressedButtons.isNotEmpty()
            }
        }
        return false
    }

    private fun findButton(x: Float, y: Float): KeyButton? {
        buttons.forEach { button ->
            val rect = buttonRects[button.id] ?: return@forEach
            if (rect.contains(x, y)) return button
        }
        return null
    }

    private fun handlePress(glfwCode: Int, action: Int) {
        when {
            glfwCode >= 0 -> activity.sendKey(glfwCode, action)
            glfwCode == -1 -> activity.sendMouseButton(0, action)
            glfwCode == -2 -> activity.sendMouseButton(1, action)
            glfwCode == -3 -> activity.sendMouseButton(2, action)
            glfwCode == -4 && action == GLFW_PRESS -> {
                val prev = (currentHotbarSlot - 1 + 9) % 9
                currentHotbarSlot = prev
                activity.sendKey(49 + prev, GLFW_PRESS)
                activity.sendKey(49 + prev, GLFW_RELEASE)
            }
            glfwCode == -5 && action == GLFW_PRESS -> {
                val next = (currentHotbarSlot + 1) % 9
                currentHotbarSlot = next
                activity.sendKey(49 + next, GLFW_PRESS)
                activity.sendKey(49 + next, GLFW_RELEASE)
            }
            glfwCode == -6 && action == GLFW_PRESS -> {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                val surfaceView = activity.window.decorView.findViewWithTag<android.view.View>("minecraft_surface")
                surfaceView?.requestFocus()
                @Suppress("DEPRECATION")
                imm.toggleSoftInput(
                    android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                    android.view.inputmethod.InputMethodManager.HIDE_IMPLICIT_ONLY
                )
            }
            glfwCode == -7 && action == GLFW_PRESS -> {
                activity.combatMode = !activity.combatMode
                Log.d("PING_LAUNCHER", "전투 모드: ${if (activity.combatMode) "ON" else "OFF"}")
                invalidate()  // 버튼 색상 갱신용
            }
        }
    }

    private val hotbarKey: String
        get() = "hotbar_slot_${MinecraftActivityBridge.currentWorldName}"

    private var currentHotbarSlot: Int
        get() = activity.getSharedPreferences("ping_launcher", Context.MODE_PRIVATE).getInt(hotbarKey, 0)
        set(value) {
            activity.getSharedPreferences("ping_launcher", Context.MODE_PRIVATE).edit { putInt(hotbarKey, value) }
        }
}