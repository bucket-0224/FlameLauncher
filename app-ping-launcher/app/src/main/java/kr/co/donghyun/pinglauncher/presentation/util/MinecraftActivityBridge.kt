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