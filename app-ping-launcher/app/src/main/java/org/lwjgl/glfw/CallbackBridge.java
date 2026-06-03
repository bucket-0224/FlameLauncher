package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import kr.co.donghyun.pinglauncher.presentation.MinecraftActivity;
import kr.co.donghyun.pinglauncher.presentation.util.MinecraftActivityBridge;

/**
 * PojavLauncher의 CallbackBridge — 안드로이드 측 진입점.
 * input_bridge_v3.c::JNI_OnLoad 가 이 클래스의 static 메서드를
 * GetStaticMethodID 로 찾는다. 없으면 JVM 부팅 시점에 abort.
 */
public class CallbackBridge {
    private static final String TAG = "CallbackBridge";

    // ─── JVM이 호출하는 native들 (libglfw.so 안에 구현 있음) ─────────
    public static native void nativeSetUseInputStackQueue(boolean useStackQueue);
    public static native boolean nativeSendChar(char codepoint);
    public static native boolean nativeSendCharMods(char codepoint, int mods);
    public static native void nativeSendKey(int key, int scancode, int action, int mods);
    public static native void nativeSendCursorPos(float x, float y);
    public static native void nativeSendMouseButton(int button, int action, int mods);
    public static native void nativeSendScroll(double xoffset, double yoffset);
    public static native void nativeSendScreenSize(int width, int height);
    public static native void nativeSetGrabbing(boolean grabbing);
    public static native boolean nativeSetInputReady(boolean inputReady);
    public static native String nativeClipboard(int action, byte[] copySrc);

    // ─── JVM → Android 콜백 (네이티브가 이 메서드들을 호출) ───────────

    /** 클립보드 접근. action: 2000=COPY, 2001=PASTE, 2002=OPEN. */
    public static String accessAndroidClipboard(int action, String copyContent) {
        try {
            Context ctx = MinecraftActivity.Companion.getCurrentInstance();
            if (ctx == null) return "";
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return "";

            switch (action) {
                case 2000: // COPY
                    cm.setPrimaryClip(ClipData.newPlainText("Minecraft", copyContent));
                    return "";
                case 2001: // PASTE
                    if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                            && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                        return t != null ? t.toString() : "";
                    }
                    return "";
                case 2002: // OPEN — xdg-open 후킹으로 들어옴
                    Log.i(TAG, "openPath: " + copyContent);
                    if (!copyContent.isEmpty()) {
                        net.kdt.pojavlaunch.MainActivity.openLink(copyContent);
                    }
                    return "";
                default:
                    return "";
            }
        } catch (Throwable t) {
            Log.e(TAG, "accessAndroidClipboard error", t);
            return "";
        }
    }

    /** 마인크래프트가 마우스를 잡았는지/풀었는지 알림. */
    public static void onGrabStateChanged(boolean grabbing) {
        Log.d(TAG, "onGrabStateChanged: " + grabbing);
        try {
            MinecraftActivityBridge.onGrabStateChanged(grabbing);
        } catch (Throwable t) {
            Log.w(TAG, "onGrabStateChanged dispatch failed", t);
        }
    }

    /** 컨트롤러 direct input 활성화 요청. 미지원이라 no-op. */
    public static void onDirectInputEnable() {
        Log.d(TAG, "onDirectInputEnable (no-op)");
    }
}