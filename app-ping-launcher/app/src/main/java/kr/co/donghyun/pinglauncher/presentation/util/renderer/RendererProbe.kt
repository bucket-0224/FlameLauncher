package kr.co.donghyun.pinglauncher.presentation.util.renderer

object RendererProbe {
    init {
        System.loadLibrary("pingjvm")  // libpingjvm.so 가 이 함수를 export
    }
    @JvmStatic external fun nativeZinkCompatible(): Boolean
}