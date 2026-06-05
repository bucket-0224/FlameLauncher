package kr.co.donghyun.pinglauncher.presentation.util.minecraft

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import kr.co.donghyun.pinglauncher.presentation.MinecraftActivity

class MinecraftInputConnection(
    targetView: View,
    private val activity: MinecraftActivity,
) : BaseInputConnection(targetView, /*fullEditor=*/ true) {

    // 조합 중인 문자열을 보관. 한글 IME 가 setComposingText 로 매 자모마다 호출.
    // ex) ㄱ → 가 → 갑 → 강  … 매번 마지막 형태로 덮어쓴다.
    private var pendingComposition: String = ""

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // 조합 중이었다면 commit 시점에 이미 조합 결과가 text 로 옴 → 그대로 송신
        pendingComposition = ""
        text?.forEach { sendChar(it) }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // 조합 중간 상태 보관만. MC 에는 아직 송신하지 않음.
        pendingComposition = text?.toString().orEmpty()
        return true
    }

    override fun finishComposingText(): Boolean {
        if (pendingComposition.isNotEmpty()) {
            pendingComposition.forEach { sendChar(it) }
            pendingComposition = ""
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val glfwAction = if (isDown) 1 else 0
        val glfwKey = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL        -> 259  // Backspace
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 257  // Enter
            KeyEvent.KEYCODE_DPAD_LEFT  -> 263
            KeyEvent.KEYCODE_DPAD_RIGHT -> 262
            KeyEvent.KEYCODE_DPAD_UP    -> 265
            KeyEvent.KEYCODE_DPAD_DOWN  -> 264
            KeyEvent.KEYCODE_FORWARD_DEL -> 261  // Delete
            else -> return super.sendKeyEvent(event)
        }
        activity.sendKey(glfwKey, glfwAction)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // Samsung 키보드 등 일부 IME 는 backspace 대신 이걸로 보냄
        repeat(beforeLength) {
            activity.sendKey(259, 1)
            activity.sendKey(259, 0)
        }
        repeat(afterLength) {
            activity.sendKey(261, 1)
            activity.sendKey(261, 0)
        }
        return true
    }

    private fun sendChar(c: Char) {
        try {
            Class.forName("org.lwjgl.glfw.CallbackBridge")
                .getMethod("nativeSendChar", Char::class.java)
                .invoke(null, c)
        } catch (_: Exception) {}
    }
}