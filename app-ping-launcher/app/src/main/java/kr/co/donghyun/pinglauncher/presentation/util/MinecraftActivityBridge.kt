package kr.co.donghyun.pinglauncher.presentation.util

import android.view.View
import kr.co.donghyun.pinglauncher.presentation.MinecraftActivity
import kr.co.donghyun.pinglauncher.presentation.ui.components.GameControllerView
import java.io.File
import java.lang.ref.WeakReference

object MinecraftActivityBridge {
    @Volatile @JvmField var currentHotbarSlot: Int = 0  // 0-8

    @Volatile @JvmField var currentWorldName: String = "default"

    private var controllerShownOnce = false

    // ── 첫 프레임 콜백 ──
    // egl_bridge.c::pojavSwapBuffers 가 첫 swap 시 CallbackBridge.onFirstFrameRendered() 를
    // 호출하고, 그게 여기로 전달된다. MinecraftActivity 가 리스너를 등록/해제한다.
    @Volatile
    private var firstFrameListener: (() -> Unit)? = null

    @Volatile
    private var firstFrameAlreadyFired = false

    /**
     * MinecraftActivity 가 onCreate 에서 등록한다.
     * 만약 등록 전에 이미 첫 프레임이 발생했다면(레이스), 등록 즉시 한 번 호출해준다.
     */
    fun setFirstFrameListener(listener: (() -> Unit)?) {
        firstFrameListener = listener
        if (listener != null && firstFrameAlreadyFired) {
            listener()
        }
    }

    /** 네이티브(렌더 스레드)에서 호출됨. 리스너로 전달만 한다(스레드 전환은 리스너 쪽 책임). */
    @JvmStatic
    fun onFirstFrameRendered() {
        firstFrameAlreadyFired = true
        firstFrameListener?.invoke()
    }


    @JvmStatic
    fun onGrabStateChanged(grabbed: Boolean) {
//        if (grabbed) {
//            // 가장 최근 수정된 saves 폴더 찾기
//            try {
//                val instanceDir = MinecraftActivity.currentInstance?.instanceDir ?: return
//                val savesDir = File(instanceDir, "saves")
//                val latestWorld = savesDir.listFiles()
//                    ?.filter { it.isDirectory }
//                    ?.maxByOrNull { it.lastModified() }
//                    ?.name ?: "default"
//                currentWorldName = latestWorld
//            } catch (_: Exception) {}
//        }
    }



}