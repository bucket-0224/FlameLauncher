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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kr.co.donghyun.pinglauncher.presentation.util.MinecraftActivityBridge
import kr.co.donghyun.pinglauncher.presentation.util.minecraft.MinecraftInputConnection

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
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                    val config = resources.configuration
                    val hasHardwareKeyboard = config.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS

                    if (hasHardwareKeyboard) {
                        // 물리 키보드 있음 → onKeyDown/onKeyUp 경로로만 받기
                        return null
                    }

                    outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                            EditorInfo.IME_FLAG_NO_FULLSCREEN or
                            EditorInfo.IME_ACTION_NONE
                    return MinecraftInputConnection(this, activity)
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
                    ctx.sendKey(glfwKey, action)
                    true
                }

                setOnTouchListener { _, event ->
                    Log.d("PING_LAUNCHER", "Surface 터치: ${event.action}, isGrabbing=${activity.isGrabbing}, combat=${activity.combatMode}")
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
                                    // ── UI 모드 (인벤토리/메뉴) — 기존 동작 유지 ──
                                    activity.currentCursorX = event.x
                                    activity.currentCursorY = event.y
                                    activity.sendCursorPos(activity.currentCursorX, activity.currentCursorY)
                                    activity.sendMouseButton(0, 1)
                                } else {
                                    // ── 인게임 모드 — 롱프레스 타이머 ──
                                    // 전투 모드: 길게 = 우클릭 유지 (방패/활)
                                    // 일반 모드: 길게 = 좌클릭 유지 (블록 파괴)
                                    val longBtn = if (activity.combatMode) 1 else 0
                                    longPressRunnable = Runnable {
                                        if (!isDragging) {
                                            isLongPress = true
                                            activity.sendMouseButton(longBtn, 1)  // PRESS
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
                                        // UI — 절대 좌표
                                        activity.currentCursorX = event.x
                                        activity.currentCursorY = event.y
                                    }
                                    activity.sendCursorPos(activity.currentCursorX, activity.currentCursorY)
                                }

                                lastX = event.x
                                lastY = event.y
                            }

                            MotionEvent.ACTION_UP -> {
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null

                                if (activity.isGrabbing) {
                                    // ── 인게임 모드 ──
                                    if (isLongPress) {
                                        // 롱프레스 중이었으면 해당 버튼 release
                                        val longBtn = if (activity.combatMode) 1 else 0
                                        activity.sendMouseButton(longBtn, 0)  // RELEASE
                                    } else if (!isDragging) {
                                        // 짧은 탭
                                        // 전투 모드: 탭 = 좌클릭 (공격)
                                        // 일반 모드: 탭 = 우클릭 (놓기/상호작용)
                                        val tapBtn = if (activity.combatMode) 0 else 1
                                        activity.sendMouseButton(tapBtn, 1)   // PRESS
                                        handler.postDelayed({
                                            activity.sendMouseButton(tapBtn, 0)   // RELEASE
                                        }, 50)
                                    }
                                } else {
                                    // ── UI 모드 — 좌클릭 release ──
                                    activity.sendMouseButton(0, 0)
                                }

                                isLongPress = false
                                isDragging = false
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                // 안전망: 어떤 이유로 취소되면 모든 버튼 release
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null
                                if (isLongPress && activity.isGrabbing) {
                                    val longBtn = if (activity.combatMode) 1 else 0
                                    activity.sendMouseButton(longBtn, 0)
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