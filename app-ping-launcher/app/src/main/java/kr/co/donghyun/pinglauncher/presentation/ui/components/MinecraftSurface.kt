package kr.co.donghyun.pinglauncher.presentation.ui.components

import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kr.co.donghyun.pinglauncher.presentation.MinecraftActivity
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kr.co.donghyun.pinglauncher.presentation.util.MinecraftActivityBridge

// 드래그 판정 거리 (px) — 이 이상 움직여야 카메라 회전 시작
private const val DRAG_SLOP = 20f
var downX = 0f
var downY = 0f
var lastX = 0f
var lastY = 0f
var isDragging = false
var isLongPress = false
var longPressRunnable: Runnable? = null
val handler = android.os.Handler(android.os.Looper.getMainLooper())
private const val LONG_PRESS_TIMEOUT = 500L

@Composable
fun MinecraftSurface(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (Surface, SurfaceHolder) -> Unit,
    onSurfaceChanged: (Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit = {},   // ← 추가
) {
    AndroidView(
        factory = { ctx ->
            val activity = ctx as MinecraftActivity
            object : SurfaceView(ctx) {
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                    outAttrs.inputType = android.text.InputType.TYPE_NULL
                    return object : android.view.inputmethod.BaseInputConnection(this, false) {
                        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                            text?.forEach { char ->
                                if (char == ' ') {
                                    ctx.sendKey(32, GLFW_PRESS)
                                    ctx.sendKey(32, GLFW_RELEASE)
                                } else {
                                    try {
                                        Class.forName("org.lwjgl.glfw.CallbackBridge")
                                            .getMethod("nativeSendChar", Char::class.java)
                                            .invoke(null, char)
                                    } catch (_: Exception) {}
                                }
                            }
                            return true
                        }
                    }
                }
            }.apply {
                tag = "minecraft_surface"
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) =
                        onSurfaceCreated(holder.surface, holder)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) =
                        onSurfaceChanged(w, h)
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceDestroyed()
                    }
                })

                setOnKeyListener { _, keyCode, event ->
                    val glfwKey = ctx.androidKeyToGlfw(keyCode) ?: return@setOnKeyListener false
                    val action = if (event.action == android.view.KeyEvent.ACTION_DOWN) 1 else 0
                    try {
                        val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
                        val scancode = ctx.getScancode(glfwKey)
                        cb.getMethod("nativeSendKey", Int::class.java, Int::class.java, Int::class.java, Int::class.java)
                            .invoke(null, glfwKey, scancode, action, 0)
                        if (action == 1) {
                            val keyChar = ctx.glfwKeyToChar(glfwKey)
                            if (keyChar != '\u0000') {
                                cb.getMethod("nativeSendChar", Char::class.java).invoke(null, keyChar)
                            }
                        }
                    } catch (_: Exception) {}
                    true
                }

                setOnTouchListener { _, event ->
                    Log.d("PING_LAUNCHER", "Surface 터치: ${event.action}, isGrabbing=${activity.isGrabbing}")
                    try {
                        when (event.action and MotionEvent.ACTION_MASK) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                downY = event.y
                                lastX = event.x
                                lastY = event.y
                                isDragging = false
                                isLongPress = false

                                if (!activity.isGrabbing) {
                                    activity.currentCursorX = event.x
                                    activity.currentCursorY = event.y
                                    try {
                                        val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
                                        cb.getMethod("nativeSendCursorPos", Float::class.java, Float::class.java)
                                            .invoke(null, activity.currentCursorX, activity.currentCursorY)
                                        cb.getMethod("nativeSendMouseButton", Int::class.java, Int::class.java, Int::class.java)
                                            .invoke(null, 0, 1, 0)
                                    } catch (_: Exception) {}
                                } else {
                                    // 인게임 — 롱클릭 타이머 (기존 유지)
                                    longPressRunnable = Runnable {
                                        if (!isDragging || isLongPress) {
                                            isLongPress = true
                                            try {
                                                val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
                                                cb.getMethod("nativeSendMouseButton", Int::class.java, Int::class.java, Int::class.java)
                                                    .invoke(null, 0, 1, 0)
                                            } catch (_: Exception) {}
                                        }
                                    }.also { handler.postDelayed(it, LONG_PRESS_TIMEOUT) }
                                }
                            }

                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.x - lastX
                                val dy = event.y - lastY
                                val totalDx = event.x - downX
                                val totalDy = event.y - downY

                                if (!isDragging &&
                                    (totalDx * totalDx + totalDy * totalDy) > DRAG_SLOP * DRAG_SLOP
                                ) {
                                    isDragging = true
                                    if (!isLongPress) {
                                        longPressRunnable?.let { handler.removeCallbacks(it) }
                                        longPressRunnable = null
                                    }
                                }

                                if (isDragging) {
                                    if (activity.isGrabbing) {
                                        // 인게임 — 델타 기반 카메라 회전
                                        activity.currentCursorX += dx * activity.MOUSE_SENSITIVITY
                                        activity.currentCursorY += dy * activity.MOUSE_SENSITIVITY
                                    } else {
                                        // UI(인벤토리 등) — 터치 위치 = 커서 위치
                                        activity.currentCursorX = event.x
                                        activity.currentCursorY = event.y
                                    }
                                    try {
                                        val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
                                        cb.getMethod("nativeSendCursorPos", Float::class.java, Float::class.java)
                                            .invoke(null, activity.currentCursorX, activity.currentCursorY)
                                    } catch (_: Exception) {}
                                }

                                lastX = event.x
                                lastY = event.y
                            }

                            MotionEvent.ACTION_UP -> {
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null

                                if (activity.isGrabbing) {
                                    // 항상 좌클릭 해제
                                    try {
                                        val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
                                        cb.getMethod("nativeSendMouseButton", Int::class.java, Int::class.java, Int::class.java)
                                            .invoke(null, 0, 0, 0)
                                    } catch (_: Exception) {}

                                    if (!isLongPress && !isDragging) {
                                        try {
                                            val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
                                            cb.getMethod("nativeSendMouseButton", Int::class.java, Int::class.java, Int::class.java)
                                                .invoke(null, 1, 1, 0)
                                            handler.postDelayed({
                                                try {
                                                    cb.getMethod("nativeSendMouseButton", Int::class.java, Int::class.java, Int::class.java)
                                                        .invoke(null, 1, 0, 0)
                                                } catch (_: Exception) {}
                                            }, 100)
                                        } catch (_: Exception) {}
                                    }
                                } else {
                                    // 일반 UI — 좌클릭 해제
                                    try {
                                        val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
                                        cb.getMethod("nativeSendMouseButton", Int::class.java, Int::class.java, Int::class.java)
                                            .invoke(null, 0, 0, 0)
                                    } catch (_: Exception) {}
                                }

                                isLongPress = false
                                isDragging = false
                            }
                        }
                    } catch (_: Exception) {}

                    true
                }
            }
        },
        modifier = modifier.fillMaxSize()
    )
}