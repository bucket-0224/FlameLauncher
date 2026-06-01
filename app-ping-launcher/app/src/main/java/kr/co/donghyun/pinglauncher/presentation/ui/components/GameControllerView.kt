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

    /** 폰 landscape(~800dp 가로)를 기준으로 한 버튼 크기 배율. */
    private var buttonScale: Float = 1f

    // (기존 paint 들 그대로) ...
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

    /** 폰 기준 화면(800dp)을 1.0 으로 두고, 더 넓은 화면에서는 비율만큼 키운다. */
    private fun computeButtonScale(widthPx: Int): Float {
        val density = resources.displayMetrics.density
        val widthDp = widthPx / density
        return (widthDp / 800f).coerceIn(1f, 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buttonScale = computeButtonScale(w)
        Log.d("PING_LAUNCHER",
            "GameControllerView 크기: ${w}x${h}, buttonScale=$buttonScale")
        recalcRects(w, h)
    }

    private fun recalcRects(w: Int, h: Int) {
        buttonRects.clear()
        val density = resources.displayMetrics.density
        buttons.forEach { button ->
            val bw = button.width  * density * buttonScale
            val bh = button.height * density * buttonScale
            // 위치는 0~1 비율 기준이지만, 커진 버튼이 화면 밖으로 나가지 않게 클램프.
            val rawX = button.x * w
            val rawY = button.y * h
            val x = rawX.coerceIn(0f, (w - bw).coerceAtLeast(0f))
            val y = rawY.coerceIn(0f, (h - bh).coerceAtLeast(0f))
            buttonRects[button.id] = RectF(x, y, x + bw, y + bh)
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

            // 💡 기존 0.28f에서 0.21f로 축소하여 휴대폰 화면에 맞게 폰트 크기를 줄입니다.
            val fontSize = minOf(rect.width(), rect.height()) * 0.21f
            textPaint.textSize = fontSize

            // 텍스트가 수직 중앙에 잘 위치하도록 보정값(fontSize * 0.35f)을 유지하거나 미세 조정합니다.
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
                if (button == null) {
                    // isGrabbing=false(UI)일 때만 SurfaceView로 전달
                    // isGrabbing=true(인게임)일 때는 MinecraftSurface가 이미 처리
                    return false  // 기존과 동일 - 하위 뷰로 전달
                }
                pressedButtons[button.id] = pointerId
                handlePress(button.glfwCode, GLFW_PRESS)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // 해당 pointerId의 버튼 해제
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
                // 멀티터치 이동 처리
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    val buttonId = pressedButtons.entries.find { it.value == pid }?.key
                    if (buttonId != null) {
                        val rect = buttonRects[buttonId]
                        if (rect != null && !rect.contains(px, py)) {
                            // 버튼 밖으로 나가면 해제
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
            Log.d("PING_LAUNCHER", "버튼 ${button.label}: rect=$rect, touch=($x,$y), hit=${rect.contains(x,y)}")
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
                Log.d("PING_LAUNCHER", "이전 슬롯, 현재=$currentHotbarSlot")
                val prev = (currentHotbarSlot - 1 + 9) % 9
                currentHotbarSlot = prev
                activity.sendKey(49 + prev, GLFW_PRESS)
                activity.sendKey(49 + prev, GLFW_RELEASE)
            }
            glfwCode == -5 && action == GLFW_PRESS -> {
                Log.d("PING_LAUNCHER", "다음 슬롯, 현재=$currentHotbarSlot")
                val next = (currentHotbarSlot + 1) % 9
                currentHotbarSlot = next
                activity.sendKey(49 + next, GLFW_PRESS)
                activity.sendKey(49 + next, GLFW_RELEASE)
            }
            glfwCode == -6 && action == GLFW_PRESS -> {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
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
        get() = activity.getSharedPreferences("ping_launcher", Context.MODE_PRIVATE)
            .getInt(hotbarKey, 0)
        set(value) {
            activity.getSharedPreferences("ping_launcher", Context.MODE_PRIVATE)
                .edit { putInt(hotbarKey, value) }
        }
}