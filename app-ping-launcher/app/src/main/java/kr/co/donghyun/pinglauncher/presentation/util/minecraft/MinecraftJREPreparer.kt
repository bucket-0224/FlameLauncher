package kr.co.donghyun.pinglauncher.presentation.util.minecraft

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MinecraftJREPreparer {
    companion object {
        private const val TAG = "PING_LAUNCHER"

        /**
         * MC 버전 → 필요한 Java major
         *  1.0  ~ 1.16.x  → 8
         *  1.17 ~ 1.17.x  → 16
         *  1.18 ~ 1.20.4  → 17
         *  1.20.5+        → 21
         */
        fun pickJavaMajor(mcVersion: String): Int {
            // "1.16.5", "1.20.4", "1.21" ... 스냅샷("23w14a")은 21로 처리
            val parts = mcVersion.split(".")
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 21
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return when {
                minor <= 16 -> 8
                minor == 17 -> 16
                minor in 18..19 -> 17
                minor == 20 && patch <= 4 -> 17
                else -> 21
            }
        }

        private fun assetExists(context: Context, name: String): Boolean = try {
            context.assets.open(name).close(); true
        } catch (_: Exception) { false }

        /**
         * 요청한 major의 jre{N}.zip이 없으면 상위 LTS로 폴백한다.
         * 8 → 17 → 21 순으로 시도.
         */
        private fun resolveAvailableMajor(context: Context, requested: Int): Int {
            val order = linkedSetOf(requested, 17, 21).toList()
            return order.firstOrNull { assetExists(context, "jre${it}.zip") }
                ?: throw Exception("assets/에 jre{8,17,21}.zip이 하나도 없습니다.")
        }

        @JvmStatic
        fun prepareJreAndGetPath(context: Context, mcVersion: String): String {
            val requestedMajor = pickJavaMajor(mcVersion)
            val major = resolveAvailableMajor(context, requestedMajor)
            if (major != requestedMajor) {
                Log.w(TAG, "jre${requestedMajor}.zip 없음 → jre${major}.zip 로 폴백 (mcVersion=$mcVersion)")
            }

            val targetDir = File(context.filesDir, "jre${major}_runtime")

            if (!targetDir.exists() || targetDir.listFiles()?.isEmpty() == true) {
                targetDir.mkdirs()
                Log.i(TAG, "📦 JRE $major 최초 압축 해제 시작...")
                context.assets.open("jre${major}.zip").use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val outFile = File(targetDir, entry.name)
                            if (entry.isDirectory) outFile.mkdirs()
                            else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { zip.copyTo(it) }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                Log.i(TAG, "✅ JRE $major 압축 해제 완료!")
            }

            val libJvm = targetDir.walkTopDown().firstOrNull { it.name == "libjvm.so" }
                ?: throw Exception("❌ jre${major}_runtime 안에 libjvm.so 없음")
            libJvm.setExecutable(true, false)
            Log.i(TAG, "🎯 libjvm.so: ${libJvm.absolutePath}")
            return libJvm.absolutePath
        }

        fun findJreLibDir(context: Context, mcVersion: String): File? {
            val major = pickJavaMajor(mcVersion)
            val candidates = listOf(major, 17, 21).distinct()
            for (m in candidates) {
                val root = File(context.filesDir, "jre${m}_runtime")
                if (!root.exists()) continue
                // libjava.so가 있는 폴더 = JRE21은 lib/, JRE8은 lib/aarch64/
                val libJava = root.walkTopDown().firstOrNull { it.name == "libjava.so" }
                if (libJava != null) return libJava.parentFile
            }
            return null
        }
    }
}