package kr.co.donghyun.flamelauncher.presentation.util.mods

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream


object ModpackImporter {

    data class Manifest(
        val name: String?,
        val mcVersion: String?,
        val loaderType: String?,
        val loaderVersion: String?,
        val modCount: Int,
    )

    sealed interface Result {
        data class Success(
            val modCount: Int,
            val configCount: Int,
            val manifest: Manifest?,
            val mcMismatch: Boolean,
        ) : Result

        data class Failure(val reason: String) : Result
    }

    /**
     * @param zipUri            SAF 로 고른 모드팩 zip
     * @param gameDir           대상 인스턴스의 게임 디렉터리(여기 아래 mods/, config/ 로 풀린다)
     * @param currentMcVersion  현재 인스턴스 mc 버전(호환 경고용, 없으면 null)
     * @param currentLoaderType 현재 인스턴스 로더("fabric"/"forge"/"neoforge"/null=바닐라). 로더 게이트용.
     */
    fun import(
        context: Context,
        zipUri: Uri,
        gameDir: File,
        currentMcVersion: String?,
        currentLoaderType: String?,
    ): Result {
        return try {
// ── Pass 1: 추출 전에 manifest 만 먼저 읽어 로더 호환을 검사한다 ──
            val manifest = readManifest(context, zipUri)

            val packLoader = manifest?.loaderType?.trim()?.lowercase()?.ifBlank { null }
            val curLoader = currentLoaderType?.trim()?.lowercase()?.ifBlank { null }

// 모드팩 로더를 알 수 있고(known) 현재 로더와 다르면 거부.
// (현재 로더 null = 바닐라 → 모든 모드 로더와 "다름")
            if (packLoader != null && packLoader != curLoader) {
                return Result.Failure(
                    "모드 로더가 달라 가져올 수 없어요. " +
                            "(모드팩: ${loaderDisplay(packLoader)} · 현재 인스턴스: ${loaderDisplay(curLoader)})"
                )
            }

// ── Pass 2: 실제 추출 ──
            val input = context.contentResolver.openInputStream(zipUri)
                ?: return Result.Failure("파일을 열 수 없습니다.")

            val modsDir = File(gameDir, "mods")
            val configDir = File(gameDir, "config")
            val gameCanonical = gameDir.canonicalPath

            var modCount = 0
            var configCount = 0

            ZipInputStream(BufferedInputStream(input)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory) {
                        when {
                            name.startsWith("mods/") && name.endsWith(".jar", ignoreCase = true) -> {
                                val fileName = name.substringAfterLast('/')
                                if (fileName.isNotBlank()) {
                                    val target = File(modsDir, fileName)
                                    if (isWithin(target, gameCanonical)) {
                                        target.parentFile?.mkdirs()
                                        target.outputStream().use { zin.copyTo(it) }
                                        modCount++
                                    }
                                }
                            }

                            name.startsWith("config/") -> {
                                val rel = name.removePrefix("config/")
                                if (rel.isNotBlank() && !rel.contains("..")) {
                                    val target = File(configDir, rel)
                                    if (isWithin(target, gameCanonical)) {
                                        target.parentFile?.mkdirs()
                                        target.outputStream().use { zin.copyTo(it) }
                                        configCount++
                                    }
                                }
                            }

                            else -> { /* manifest.json 등 그 외 항목 무시 */ }
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }

            if (modCount == 0 && configCount == 0) {
                return Result.Failure("이 zip 에서 가져올 모드/설정을 찾지 못했어요. 올바른 모드팩 zip 인가요?")
            }

            val packMc = manifest?.mcVersion
            val mismatch = !packMc.isNullOrBlank() &&
                    !currentMcVersion.isNullOrBlank() &&
                    packMc != currentMcVersion

            Result.Success(modCount, configCount, manifest, mismatch)
        } catch (e: Exception) {
            Result.Failure("가져오기 실패: ${e.message}")
        }
    }

    /** 추출 없이 manifest.json 만 읽는다(찾는 즉시 중단). 우리 추출기는 manifest 를 맨 앞에 쓴다. */
    private fun readManifest(context: Context, zipUri: Uri): Manifest? {
        try {
            context.contentResolver.openInputStream(zipUri).use { input ->
                if (input == null) return null
                ZipInputStream(BufferedInputStream(input)).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val n = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && n == "manifest.json") {
                            return parseManifest(zin.readBytes().toString(Charsets.UTF_8))
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
// 무시 → null
        }
        return null
    }

    private fun loaderDisplay(loader: String?): String = when (loader) {
        "fabric"   -> "Fabric"
        "forge"    -> "Forge"
        "neoforge" -> "NeoForge"
        null       -> "없음(바닐라)"
        else       -> loader
    }

    /** target 의 정규 경로가 base 안에 있는지(경로 탈출 방지) */
    private fun isWithin(target: File, baseCanonical: String): Boolean {
        return try {
            val tp = target.canonicalPath
            tp == baseCanonical || tp.startsWith(baseCanonical + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    private fun parseManifest(text: String): Manifest? {
        return try {
            val o = JSONObject(text)
            fun strOrNull(k: String): String? =
                if (o.isNull(k)) null else o.optString(k, "").ifBlank { null }
            Manifest(
                name = strOrNull("name"),
                mcVersion = strOrNull("mcVersion"),
                loaderType = strOrNull("loaderType"),
                loaderVersion = strOrNull("loaderVersion"),
                modCount = o.optJSONArray("mods")?.length() ?: 0,
            )
        } catch (e: Exception) {
            null
        }
    }
}