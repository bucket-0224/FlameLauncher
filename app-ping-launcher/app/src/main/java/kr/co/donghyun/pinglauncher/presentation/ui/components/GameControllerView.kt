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
private const val TARGET_DP_TABLET = 76f


// 폰/가로화면용 게임패드 프리셋 (GLFW 코드 → x, y 비율)
private val PHONE_LAYOUT_PRESETS: Map<Int, Pair<Float, Float>> = mapOf(
    87  to (0.14f to 0.7f),  // W
    65  to (0.06f to 0.88f),  // A
    83  to (0.14f to 0.88f),  // S
    68  to (0.22f to 0.88f),  // D
    256 to (0.06f to 0.10f),  // ESC
    292 to (0.14f to 0.10f),  // F3
    294 to (0.22f to 0.10f),  // F5
    -6  to (0.30f to 0.10f),  // keyboard toggle
    84  to (0.06f to 0.28f),  // T
    47  to (0.14f to 0.28f),  // /
    81  to (0.22f to 0.28f),  // Q
    69  to (0.96f to 0.7f),  // E (inventory)
    -4  to (0.88f to 0.7f),  // prev slot
    -5  to (0.88f to 0.52f),  // next slot
    340 to (0.76f to 0.88f),  // shift = sneak
    341 to (0.84f to 0.88f),  // ctrl  = sprint
    32  to (0.92f to 0.88f),  // space = jump
    -7 to (0.8f to 0.7f),
)

class GameControllerView(context: Context) : View(context) {
    private var imeVisible: Boolean = false

    private val activity = context as MinecraftActivity
    private var buttons: List<KeyButton> = KeyLayoutManager.load(context)
    private val buttonRects = mutableMapOf<String, RectF>()
    private val pressedButtons = mutableMapOf<String, Int>()
    private val forwardedPids = mutableSetOf<Int>()  // 우리가 SurfaceView 로 떠넘긴 pointer
    private var surfaceViewCached: View? = null


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

    private fun surfaceView(): View? {
        val cur = surfaceViewCached
        if (cur != null && cur.isAttachedToWindow) return cur
        val sv = activity.window.decorView.findViewWithTag<View>("minecraft_surface")
        surfaceViewCached = sv
        return sv
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // 1) 새 pointer 분류 (버튼 위인지 vs 빈 영역인지)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                forwardedPids.clear()
                classifyNewPointer(event, event.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                classifyNewPointer(event, event.actionIndex)
            }
        }

        // 2) 이번 이벤트의 pointer 들을 ours/theirs 로 나눠서 각각 다른 view 로 보냄
        val theirEvent = filterEvent(event, keep = forwardedPids)
        val ourPids = (0 until event.pointerCount)
            .map { event.getPointerId(it) }
            .filter { it !in forwardedPids }
            .toSet()
        val ourEvent = filterEvent(event, keep = ourPids)

        if (ourEvent != null) {
            super.dispatchTouchEvent(ourEvent)   // → 우리 onTouchEvent
            ourEvent.recycle()
        }
        if (theirEvent != null) {
            surfaceView()?.dispatchTouchEvent(theirEvent)  // → SurfaceView 의 setOnTouchListener
            theirEvent.recycle()
        }

        // 3) pointer 끝나면 cleanup
        when (action) {
            MotionEvent.ACTION_POINTER_UP ->
                forwardedPids.remove(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                forwardedPids.clear()
        }
        return true
    }

    private fun classifyNewPointer(event: MotionEvent, index: Int) {
        val pid = event.getPointerId(index)
        val onButton = findButton(event.getX(index), event.getY(index)) != null
        if (!onButton) forwardedPids.add(pid)
    }

    /**
     * source 에서 keep 에 들어있는 pointer 만 남긴 새 MotionEvent 생성.
     * action 도 적절히 변환:
     *  - 액션 대상 pointer 가 keep 에 없으면 ACTION_MOVE 로 다운그레이드
     *  - keep 에 1개만 남으면 ACTION_POINTER_DOWN/UP → ACTION_DOWN/UP 으로 정규화
     */
    private fun filterEvent(source: MotionEvent, keep: Set<Int>): MotionEvent? {
        if (keep.isEmpty()) return null
        val keepIndices = (0 until source.pointerCount)
            .filter { source.getPointerId(it) in keep }
        if (keepIndices.isEmpty()) return null

        val sourceAction = source.actionMasked
        val sourceActionIdx = source.actionIndex
        val sourceActionPid = source.getPointerId(sourceActionIdx)

        val isDown = sourceAction == MotionEvent.ACTION_DOWN
                || sourceAction == MotionEvent.ACTION_POINTER_DOWN
        val isUp = sourceAction == MotionEvent.ACTION_UP
                || sourceAction == MotionEvent.ACTION_POINTER_UP

        val newAction: Int
        val newActionIdx: Int

        when {
            (isDown || isUp) && sourceActionPid !in keep -> {
                // 이번 액션의 주인공이 우리가 keep 하는 pointer 가 아님 → 그냥 MOVE
                newAction = MotionEvent.ACTION_MOVE
                newActionIdx = 0
            }
            isDown -> {
                val mapped = keepIndices.indexOf(sourceActionIdx)
                newAction =
                    if (keepIndices.size == 1) MotionEvent.ACTION_DOWN
                    else MotionEvent.ACTION_POINTER_DOWN
                newActionIdx = mapped
            }
            isUp -> {
                val mapped = keepIndices.indexOf(sourceActionIdx)
                newAction =
                    if (keepIndices.size == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP
                newActionIdx = mapped
            }
            else -> {
                newAction = sourceAction
                newActionIdx = 0
            }
        }

        val combinedAction = newAction or
                (newActionIdx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        val props = Array(keepIndices.size) { MotionEvent.PointerProperties() }
        val coords = Array(keepIndices.size) { MotionEvent.PointerCoords() }
        keepIndices.forEachIndexed { newI, origI ->
            source.getPointerProperties(origI, props[newI])
            source.getPointerCoords(origI, coords[newI])
        }

        return MotionEvent.obtain(
            source.downTime,
            source.eventTime,
            combinedAction,
            keepIndices.size,
            props,
            coords,
            source.metaState,
            source.buttonState,
            source.xPrecision,
            source.yPrecision,
            source.deviceId,
            source.edgeFlags,
            source.source,
            source.flags
        )
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
                var changed = false
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)

                    // 이 포인터가 지금 잡고 있는 버튼 (없을 수도)
                    val currentButtonId = pressedButtons.entries.find { it.value == pid }?.key
                    val newButton = findButton(px, py)
                    val newButtonId = newButton?.id

                    // 같은 버튼이면 변화 없음
                    if (currentButtonId == newButtonId) continue

                    // 1) 기존 버튼이 있었다면 release
                    if (currentButtonId != null) {
                        val oldButton = buttons.find { it.id == currentButtonId }
                        pressedButtons.remove(currentButtonId)
                        if (oldButton != null) handlePress(oldButton.glfwCode, GLFW_RELEASE)
                        changed = true
                    }

                    // 2) 새 버튼이 swipe 대상이고, 다른 손가락이 이미 잡고 있지 않다면 press
                    if (newButton != null
                        && newButton.isSwipeable()
                        && !pressedButtons.containsKey(newButton.id)
                    ) {
                        pressedButtons[newButton.id] = pid
                        handlePress(newButton.glfwCode, GLFW_PRESS)
                        changed = true
                    }
                }
                if (changed) invalidate()
                return pressedButtons.isNotEmpty()
            }
        }
        return false
    }

    private fun KeyButton.isSwipeable(): Boolean =
        glfwCode >= 0 || glfwCode in -3..-1

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
            // glfwCode == -6 분기 전체 교체
            glfwCode == -6 && action == GLFW_PRESS -> {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                val surfaceView = activity.window.decorView
                    .findViewWithTag<android.view.View>("minecraft_surface") ?: return

                // 매번 포커스 다시 잡기 — 한 번 잃으면 showSoftInput 이 무시됨
                surfaceView.isFocusable = true
                surfaceView.isFocusableInTouchMode = true
                surfaceView.requestFocus()

                if (imeVisible) {
                    imm.hideSoftInputFromWindow(surfaceView.windowToken, 0)
                    imeVisible = false
                } else {
                    // SHOW_IMPLICIT 말고 0 — 사용자가 명시적으로 누른 거니까 강제로
                    surfaceView.post {
                        imm.showSoftInput(surfaceView, 0)
                    }
                    imeVisible = true
                }
            }
            glfwCode == -7 && action == GLFW_PRESS -> {
                activity.combatMode = !activity.combatMode
                Log.d("PING_LAUNCHER", "전투 모드: ${if (activity.combatMode) "ON" else "OFF"}")
                invalidate()  // 버튼 색상 갱신용
            }
        }
    }

    fun setImeVisibleExternal(visible: Boolean) {
        if (imeVisible != visible) {
            imeVisible = visible
        }
    }

    override fun setVisibility(visibility: Int) {
        val from = when (visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> "?$visibility"
        }
        Log.d("GCV_VIS", "setVisibility($from)", Throwable("setVisibility trace"))
        super.setVisibility(visibility)
    }

    private val hotbarKey: String
        get() = "hotbar_slot_${MinecraftActivityBridge.currentWorldName}"

    private var currentHotbarSlot: Int
        get() = activity.getSharedPreferences("ping_launcher", Context.MODE_PRIVATE).getInt(hotbarKey, 0)
        set(value) {
            activity.getSharedPreferences("ping_launcher", Context.MODE_PRIVATE).edit { putInt(hotbarKey, value) }
        }
}