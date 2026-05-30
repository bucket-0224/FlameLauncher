package kr.co.donghyun.pinglauncher.presentation.util

internal fun isVersionSupported(versionId: String): Boolean {
    val parts = versionId.split(".")
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return true
}
