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

class GameControllerView(context: Context) : View(context) {
    private val activity = context as MinecraftActivity
    private val buttons: List<KeyButton> = KeyLayoutManager.load(context)
    private val buttonRects = mutableMapOf<String, RectF>()
    private val pressedButtons = mutableMapOf<String, Int>()

    // (기존 paint 들 그대로 유지)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d("PING_LAUNCHER", "GameControllerView 크기: ${w}x${h}")
        recalcRects(w, h)
    }

    /**
     * KeyBoardEditorScreen(Compose)의 배치 메커니즘과 완전히 동일하게 매핑합니다.
     */
    private fun recalcRects(w: Int, h: Int) {
        buttonRects.clear()
        val density = resources.displayMetrics.density

        buttons.forEach { button ->
            // 1. 임의의 가로 배율(buttonScale)을 제거하고, 편집기에서 지정된 정밀 DP 크기를 그대로 픽셀로 변환합니다.
            val bw = button.width * density
            val bh = button.height * density

            // 2. Compose의 비율 전제 조건(x, y는 버튼의 '중심점' 비율)을 그대로 따릅니다.
            val centerX = button.x * w
            val centerY = button.y * h

            // 3. 중심점을 기준으로 좌상단(left, top) 좌표를 역산하여 RectF를 생성합니다.
            val left = centerX - (bw / 2f)
            val top = centerY - (bh / 2f)

            buttonRects[button.id] = RectF(left, top, left + bw, top + bh)
        }
    }

    override fun onDraw(canvas: Canvas) {
        buttons.forEach { button ->
            val rect = buttonRects[button.id] ?: return@forEach
            val isPressed = pressedButtons.containsKey(button.id)

            val fill = when {
                isPressed && button.isAccent -> bgAccentPressedPaint
                isPressed -> bgPressedPaint
                button.isAccent -> bgAccentPaint
                else -> bgPaint
            }
            val border = if (button.isAccent) borderAccentPaint else borderPaint

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fill)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, border)

            // 기존 비율 유지를 위한 폰트 크기 조정
            val fontSize = minOf(rect.width(), rect.height()) * 0.23f
            textPaint.textSize = fontSize

            canvas.drawText(
                button.label,
                rect.centerX(),
                rect.centerY() + fontSize * 0.35f,
                textPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d("PING_LAUNCHER", "GameControllerView 터치: ${event.actionMasked}, x=${event.x}, y=${event.y}")

        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val button = findButton(x, y)
                if (button == null) return false
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
        Log.d("PING_LAUNCHER", "handlePress: glfwCode=$glfwCode, action=$action")
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