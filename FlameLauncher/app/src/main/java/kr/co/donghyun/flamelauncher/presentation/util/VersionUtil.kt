package kr.co.donghyun.flamelauncher.presentation.util

internal fun isVersionSupported(versionId: String): Boolean {
    val parts = versionId.split(".")
    parts.getOrNull(1)?.toIntOrNull() ?: 0
    parts.getOrNull(2)?.toIntOrNull() ?: 0
    return true
}
