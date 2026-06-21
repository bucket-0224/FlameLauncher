package kr.co.donghyun.pinglauncher.presentation.ui.components

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
var isHotbarTouch = false   // 이번 터치가 핫바 영역에서 시작됐나(슬롯 선택 전용)
var longPressRunnable: Runnable? = null
val handler = android.os.Handler(android.os.Looper.getMainLooper())
private const val LONG_PRESS_TIMEOUT = 500L

// ── 제스처 추적 상태 ──
//   GameControllerView 가 버튼 외 영역 터치를 이 SurfaceView 로 forward 하는데,
//   버튼 손가락이 떼지거나 할 때 "DOWN 없이 MOVE 만" 들어오는 경우가 있다.
//   그 orphan MOVE 를 stale lastX/lastY 와 비교하면 델타가 거대해져 카메라가 획 돈다.
//   이를 막기 위해:
//     - activePointerId: 카메라/클릭을 담당하는 단 하나의 포인터 id (멀티터치 혼선 방지)
//     - hasActiveTouch : 유효한 DOWN 을 받아 추적 중인지. false 인 상태에서 MOVE 가 오면
//                        델타를 보내지 않고 기준점만 다시 잡는다(획 돎 방지).
var activePointerId = -1
var hasActiveTouch = false
private const val INVALID_POINTER = -1

// 제스처 시작 시점의 grab 상태. 한 제스처(다운~업) 도중 grab 이 바뀌면
//   (예: 메뉴에서 "게임으로" 버튼을 탭 → 그 클릭으로 grab=true 전환)
//   그 제스처의 남은 이동을 카메라에 반영하면 안 된다(안 그러면 누른 방향으로 휙 돎).
//   gestureStartGrab 과 현재 grab 이 다르면 cameraFrozen=true 로 시점 처리를 막는다.
var gestureStartGrab = false
var cameraFrozen = false

// ── grab 전환 직후 카메라 입력 차단(시간 기반) ──
//   "게임으로" 탭처럼 클릭과 거의 동시에 grab=false→true 가 되면 MOVE 가 거의 없어
//   제스처 단위 cameraFrozen 이 설정될 틈이 없다. 그래서 grab 이 바뀐 "순간"을 감지해
//   그 후 GRAB_SETTLE_MS 동안 들어오는 모든 카메라 델타를 무시한다(제스처 무관).
//   prevGrabForSettle: 직전에 관찰한 grab 값(전환 에지 감지용)
//   grabChangedAt     : 마지막 grab 전환 시각(uptimeMillis)
private const val GRAB_SETTLE_MS = 250L
var prevGrabForSettle = false
var grabChangedAt = 0L

/** grab 전환 에지를 감지해 전환 시각을 기록(전환 직후 안정화 창 판정용). */
private fun noteGrabEdge(grabbing: Boolean) {
    if (grabbing != prevGrabForSettle) {
        prevGrabForSettle = grabbing
        grabChangedAt = android.os.SystemClock.uptimeMillis()
    }
}

/** grab 전환 후 안정화 시간(GRAB_SETTLE_MS) 이내인가 → 카메라 입력 무시 구간. */
private fun inGrabSettleWindow(): Boolean =
    (android.os.SystemClock.uptimeMillis() - grabChangedAt) < GRAB_SETTLE_MS

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
                    try {
                        // 매 이벤트마다 grab 전환 에지 갱신(전환 직후 안정화 창 판정용).
                        noteGrabEdge(activity.isGrabbing)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                // 새 제스처 시작 — 이 포인터를 카메라/클릭 담당으로 고정.
                                activePointerId = event.getPointerId(0)
                                hasActiveTouch = true
                                downX = event.x
                                downY = event.y
                                lastX = event.x
                                lastY = event.y
                                isDragging = false
                                isLongPress = false
                                isHotbarTouch = false
                                // 이 제스처 시작 시점의 grab 상태 기록 + 카메라 동결 해제.
                                gestureStartGrab = activity.isGrabbing
                                cameraFrozen = false

                                // ── 인게임(grab) + 핫바 영역 터치 → 슬롯 선택 전용 ──
                                if (activity.isGrabbing) {
                                    val rect = activity.computeHotbarRect(width, height)
                                    if (rect != null && rect.contains(event.x, event.y)) {
                                        val idx = (((event.x - rect.left) / (rect.width() / 9f)).toInt())
                                            .coerceIn(0, 8)
                                        activity.selectHotbarSlot(idx)
                                        isHotbarTouch = true
                                        return@setOnTouchListener true
                                    }
                                }

                                if (!activity.isGrabbing) {
                                    // ── UI 모드 (인벤토리/메뉴) ──
                                    //   1) 손가락 위치로 커서 이동 → 2) 좌클릭(버튼 적중)
                                    activity.currentCursorX = event.x
                                    activity.currentCursorY = event.y
                                    activity.sendCursorPos(activity.currentCursorX, activity.currentCursorY)
                                    activity.sendMouseButton(0, 1)
                                    //   3) 클릭 직후 커서를 화면 중앙으로 되돌린다.
                                    //   이유: 이 클릭이 "게임으로" 같은 버튼이면 곧 grab=true 로 전환되는데,
                                    //   마인크래프트는 grab 진입 시 "마지막 커서 위치"와 화면 중앙의 차이를
                                    //   시점 델타로 환산한다. 손가락 위치(예: 화면 하단)가 남아 있으면 그
                                    //   차이만큼 카메라가 휙 튄다(보통 위로). 클릭은 이미 위 좌표로 적중했고,
                                    //   전환 시 기준만 중앙으로 맞추면 델타가 0 이 되어 튐이 사라진다.
                                    //   (메뉴가 그대로 유지되는 일반 클릭에서도 다음 터치에서 다시 좌표를
                                    //   잡으므로 무해하다.)
                                    val cx = width / 2f
                                    val cy = height / 2f
                                    activity.currentCursorX = cx
                                    activity.currentCursorY = cy
                                    activity.sendCursorPos(cx, cy)
                                } else {
                                    // ── 인게임 모드 — 롱프레스 타이머 ──
                                    val longBtn = if (activity.combatMode) 1 else 0
                                    longPressRunnable = Runnable {
                                        // 드래그 중이거나, 도중 grab 이 바뀌어 동결됐으면 발동 안 함.
                                        if (!isDragging && !cameraFrozen && activity.isGrabbing) {
                                            isLongPress = true
                                            activity.sendMouseButton(longBtn, 1)
                                        }
                                    }.also { handler.postDelayed(it, LONG_PRESS_TIMEOUT) }
                                }
                            }

                            MotionEvent.ACTION_POINTER_DOWN -> {
                                // 두 번째 이후 손가락 — 카메라/클릭 담당을 가로채지 않는다.
                                // (멀티터치로 인한 동시 입력/획 돎 방지: active 포인터만 처리)
                                return@setOnTouchListener true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (isHotbarTouch) return@setOnTouchListener true

                                // ── orphan MOVE 가드 ──
                                //   GameControllerView 가 버튼 손가락 처리 중 DOWN 없이 MOVE 만
                                //   forward 하면 hasActiveTouch=false 거나 active 포인터가 이 이벤트에
                                //   없다. 이때 stale lastX/lastY 로 델타를 계산하면 카메라가 획 돈다.
                                //   → 델타를 보내지 않고 기준점만 다시 잡고 끝낸다.
                                val pIndex = if (activePointerId != INVALID_POINTER)
                                    event.findPointerIndex(activePointerId) else -1
                                if (!hasActiveTouch || pIndex < 0) {
                                    if (pIndex >= 0) {
                                        lastX = event.getX(pIndex)
                                        lastY = event.getY(pIndex)
                                    }
                                    return@setOnTouchListener true
                                }

                                val curX = event.getX(pIndex)
                                val curY = event.getY(pIndex)

                                // ── grab 상태가 제스처 도중 바뀌면 카메라 동결 ──
                                //   메뉴에서 "게임으로" 버튼을 탭하면 그 클릭으로 grab=true 가 되는데,
                                //   같은 제스처의 남은 MOVE 가 델타 회전으로 처리되면 누른 방향으로 휙 돈다.
                                //   grab 이 시작 때와 달라지면 이 제스처가 끝날 때까지 시점 반영을 막는다.
                                if (!cameraFrozen && activity.isGrabbing != gestureStartGrab) {
                                    cameraFrozen = true
                                }
                                // 제스처 단위 동결 OR grab 전환 직후 안정화 창이면 시점 무시.
                                //   (클릭과 거의 동시에 grab 이 켜져 MOVE 가 없던 경우까지 커버)
                                if (cameraFrozen || inGrabSettleWindow()) {
                                    lastX = curX
                                    lastY = curY
                                    return@setOnTouchListener true
                                }

                                val totalDx = curX - downX
                                val totalDy = curY - downY

                                if (!isDragging &&
                                    (totalDx * totalDx + totalDy * totalDy) > DRAG_SLOP * DRAG_SLOP
                                ) {
                                    isDragging = true
                                    if (!isLongPress) {
                                        longPressRunnable?.let { handler.removeCallbacks(it) }
                                        longPressRunnable = null
                                    }
                                    // 드래그 시작 순간 기준점 리셋 → 슬롭 넘는 동안의 이동이
                                    // 카메라에 한꺼번에 튀지 않게.
                                    lastX = curX
                                    lastY = curY
                                }

                                if (isDragging) {
                                    if (activity.isGrabbing) {
                                        val dx2 = curX - lastX
                                        val dy2 = curY - lastY
                                        activity.currentCursorX += dx2 * activity.MOUSE_SENSITIVITY
                                        activity.currentCursorY += dy2 * activity.MOUSE_SENSITIVITY
                                    } else {
                                        activity.currentCursorX = curX
                                        activity.currentCursorY = curY
                                    }
                                    activity.sendCursorPos(activity.currentCursorX, activity.currentCursorY)
                                }

                                lastX = curX
                                lastY = curY
                            }

                            MotionEvent.ACTION_POINTER_UP -> {
                                // 떼진 포인터가 active(카메라 담당)였다면 제스처를 깔끔히 종료.
                                val upIndex = event.actionIndex
                                val upId = event.getPointerId(upIndex)
                                if (upId == activePointerId) {
                                    longPressRunnable?.let { handler.removeCallbacks(it) }
                                    longPressRunnable = null
                                    if (activity.isGrabbing && isLongPress) {
                                        val longBtn = if (activity.combatMode) 1 else 0
                                        activity.sendMouseButton(longBtn, 0)
                                    }
                                    isLongPress = false
                                    isDragging = false
                                    hasActiveTouch = false
                                    activePointerId = INVALID_POINTER
                                }
                                return@setOnTouchListener true
                            }

                            MotionEvent.ACTION_UP -> {
                                if (isHotbarTouch) {
                                    isHotbarTouch = false
                                    hasActiveTouch = false
                                    activePointerId = INVALID_POINTER
                                    cameraFrozen = false
                                    return@setOnTouchListener true
                                }
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null

                                // 클릭/탭 처리 기준은 "제스처 시작 시점의 grab" 이다.
                                //   메뉴에서 시작한 탭(예: "게임으로" 버튼)이 도중에 grab=true 로
                                //   바뀌었다고 해서 인게임 탭(공격/상호작용)을 새로 쏘면 안 된다.
                                //   → 시작이 메뉴면 메뉴 좌클릭 release 로 마무리.
                                if (gestureStartGrab && !cameraFrozen) {
                                    // 순수 인게임 제스처
                                    if (isLongPress) {
                                        val longBtn = if (activity.combatMode) 1 else 0
                                        activity.sendMouseButton(longBtn, 0)
                                    } else if (!isDragging) {
                                        val tapBtn = if (activity.combatMode) 0 else 1
                                        activity.sendMouseButton(tapBtn, 1)
                                        handler.postDelayed({
                                            activity.sendMouseButton(tapBtn, 0)
                                        }, 50)
                                    }
                                } else if (gestureStartGrab && cameraFrozen) {
                                    // 인게임에서 시작했으나 도중 grab 해제(예: ESC 로 메뉴 진입):
                                    //   롱프레스 중이었으면 안전하게 release 만.
                                    if (isLongPress) {
                                        val longBtn = if (activity.combatMode) 1 else 0
                                        activity.sendMouseButton(longBtn, 0)
                                    }
                                } else {
                                    // 메뉴에서 시작한 제스처 → 메뉴 좌클릭 release
                                    activity.sendMouseButton(0, 0)
                                }

                                isLongPress = false
                                isDragging = false
                                hasActiveTouch = false
                                activePointerId = INVALID_POINTER
                                cameraFrozen = false
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null
                                if (isLongPress && gestureStartGrab) {
                                    val longBtn = if (activity.combatMode) 1 else 0
                                    activity.sendMouseButton(longBtn, 0)
                                }
                                isLongPress = false
                                isDragging = false
                                isHotbarTouch = false
                                hasActiveTouch = false
                                activePointerId = INVALID_POINTER
                                cameraFrozen = false
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