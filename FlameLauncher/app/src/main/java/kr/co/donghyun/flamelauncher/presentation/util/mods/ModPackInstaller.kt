package kr.co.donghyun.flamelauncher.presentation.util.mods

import android.util.Log
import com.google.gson.Gson
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.data.mods.CurseForgeManifest
import kr.co.donghyun.flamelauncher.data.mods.CurseForgeMod
import kr.co.donghyun.flamelauncher.data.mojang.DownloadPhase
import kr.co.donghyun.flamelauncher.data.mojang.DownloadProgress
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class ModPackInstallResult(
    val success: Boolean,
    val mcVersion: String = "",
    val loaderType: String? = null,      // "fabric", "forge", "neoforge"
    val loaderVersion: String? = null,
    val gameDir: File? = null,
    val error: String? = null
)

class ModPackInstaller(
    private val baseDir: File,
    private val curseForgeApi: CurseForgeAPI,
    private val onProgress: (DownloadProgress) -> Unit
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    // 알려진 비호환 모드 목록
    private val INCOMPATIBLE_MODS = listOf<String>()

    fun install(mod: CurseForgeMod, fileId: Int, mcVersionOverride: String? = null ): ModPackInstallResult {
        val gameDir = baseDir

        // 캐시 — InstanceMeta 그대로 사용
        val existingMeta = InstanceManager.loadMeta(gameDir)
        if (existingMeta != null && existingMeta.loaderType != null) {
            Log.d("FLAME_LAUNCHER", "✅ 메타 캐시 발견: ${mod.name}")
            return ModPackInstallResult(
                success = true,
                mcVersion = existingMeta.mcVersion,
                loaderType = existingMeta.loaderType,
                loaderVersion = existingMeta.loaderVersion,
                gameDir = gameDir
            )
        }

        onProgress(DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST, fileName = mod.name))

        // 1. 모드팩 zip 다운로드
        val downloadUrl = curseForgeApi.getFileDownloadUrl(mod.id, fileId)
            ?: return ModPackInstallResult(success = false, error = "다운로드 URL을 가져올 수 없음")

        val modpackZip = File(baseDir, "temp/modpack_${mod.id}_${fileId}.zip")
        modpackZip.parentFile?.mkdirs()

        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_CLIENT, fileName = "${mod.name}.zip"))
        downloadFile(downloadUrl, modpackZip) { downloaded, total, speed ->
            onProgress(DownloadProgress(
                phase = DownloadPhase.DOWNLOADING_CLIENT,
                fileName = "${mod.name}.zip · ${byteDetail(downloaded, total, speed)}",
            ))
        }

        val manifest = extractManifest(modpackZip)
            ?: return ModPackInstallResult(success = false, error = "manifest.json 없음")

        val manifestMc = manifest.minecraft.version
        val isManifestMcValid = manifestMc.matches(Regex("^1\\.\\d+(\\..*)?$"))
                || manifestMc.matches(Regex("^\\d+w\\d+[a-z]$"))

        val mcVersion = when {
            isManifestMcValid -> manifestMc
            mcVersionOverride != null -> {
                Log.w("FLAME_LAUNCHER", "manifest의 mcVersion='$manifestMc' 비정상 → CurseForge 메타의 '$mcVersionOverride' 사용")
                mcVersionOverride
            }
            else -> return ModPackInstallResult(
                success = false,
                error = "MC 버전 확인 실패 (manifest='$manifestMc', file 메타도 없음)"
            )
        }

        Log.d("FLAME_LAUNCHER", "📦 ${manifest.name} v${manifest.version}, MC=$mcVersion, loader=${manifest.minecraft.modLoaders.firstOrNull { it.primary }?.id}")

        val loaderEntry = manifest.minecraft.modLoaders.firstOrNull { it.primary }
        val (loaderType, loaderVersion) = parseLoaderId(loaderEntry?.id ?: "")

        // overrides 추출
        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_LIBRARIES, fileName = "파일 추출 중..."))
        extractOverrides(modpackZip, gameDir, manifest.overrides)

        // 모드 다운로드
        val totalMods = manifest.files.filter { it.required }.size
        var downloadedMods = 0
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }

        val requiredFiles = manifest.files.filter { it.required }
        val fileIds = requiredFiles.map { it.fileID }
        val fileInfoMap = try {
            curseForgeApi.getFiles(fileIds).associateBy { it.id }
        } catch (_: Exception) { emptyMap() }

        requiredFiles.forEach { manifestFile ->
            downloadedMods++
            onProgress(DownloadProgress(
                phase = DownloadPhase.DOWNLOADING_ASSETS,
                current = downloadedMods, total = totalMods,
                fileName = "모드 다운로드 중..."
            ))
            try {
                val fileInfo = fileInfoMap[manifestFile.fileID]
                val modUrl = fileInfo?.downloadUrl
                    ?: curseForgeApi.getFileDownloadUrl(manifestFile.projectID, manifestFile.fileID)
                    ?: return@forEach
                val fileName = fileInfo?.fileName ?: "mod_${manifestFile.fileID}.jar"
                val destFile = File(modsDir, fileName)
                if (!destFile.exists() || destFile.length() == 0L) downloadFile(modUrl, destFile)
            } catch (e: Exception) {
                Log.w("FLAME_LAUNCHER", "모드 다운로드 실패: ${manifestFile.fileID}")
            }
        }

        modpackZip.delete()
        disableIncompatibleMods(modsDir)

        Log.d("FLAME_LAUNCHER", "✅ 모드팩 설치: ${mod.name}, MC $mcVersion, $loaderType $loaderVersion")

        return ModPackInstallResult(
            success = true,
            mcVersion = mcVersion,
            loaderType = loaderType,
            loaderVersion = loaderVersion,
            gameDir = gameDir
        )
    }

    private fun parseLoaderId(id: String): Pair<String?, String?> = when {
        id.startsWith("fabric-") -> "fabric" to id.removePrefix("fabric-")
        id.startsWith("forge-") -> "forge" to id.removePrefix("forge-")
        id.startsWith("neoforge-") -> "neoforge" to id.removePrefix("neoforge-")
        else -> null to null
    }

    private fun disableIncompatibleMods(modsDir: File) {
        if (!modsDir.exists()) return
        modsDir.listFiles()?.forEach { file ->
            if (file.extension == "jar") {
                val fileName = file.name.lowercase()
                val isIncompatible = INCOMPATIBLE_MODS.any { mod ->
                    fileName.contains(mod)
                }
                if (isIncompatible) {
                    val disabled = File(file.parent, "${file.name}.disabled")
                    file.renameTo(disabled)
                    Log.d("FLAME_LAUNCHER", "⚠ 비호환 모드 비활성화: ${file.name}")
                }
            }
        }
    }

    private fun extractManifest(zipFile: File): CurseForgeManifest? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("manifest.json")
                if (entry == null) {
                    // .mrpack (Modrinth) 형식인지 확인
                    val mrpackEntry = zip.getEntry("modrinth.index.json")
                    if (mrpackEntry != null) {
                        Log.e("FLAME_LAUNCHER", "이 모드팩은 Modrinth(.mrpack) 형식 — 현재 미지원")
                        val mrJson = zip.getInputStream(mrpackEntry).bufferedReader().readText()
                        Log.d("FLAME_LAUNCHER", "mrpack 내용:\n$mrJson")
                        return null
                    }
                    Log.e("FLAME_LAUNCHER", "manifest.json도 modrinth.index.json도 없음")
                    Log.d("FLAME_LAUNCHER", "zip 내부 파일들:")
                    zip.entries().asSequence().take(20).forEach { Log.d("FLAME_LAUNCHER", "  ${it.name}") }
                    return null
                }
                val json = zip.getInputStream(entry).bufferedReader().readText()
                Log.d("FLAME_LAUNCHER", "═══ manifest.json 원본 ═══")
                json.lineSequence().take(40).forEach { Log.d("FLAME_LAUNCHER", it) }
                Log.d("FLAME_LAUNCHER", "═══════════════════════════")
                gson.fromJson(json, CurseForgeManifest::class.java)
            }
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "manifest.json 파싱 실패: ${e.message}")
            null
        }
    }

    private fun extractOverrides(zipFile: File, gameDir: File, overridesFolder: String) {
        try {
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("$overridesFolder/") && !it.isDirectory }
                    .forEach { entry ->
                        val relativePath = entry.name.removePrefix("$overridesFolder/")
                        val destFile = File(gameDir, relativePath)
                        destFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(destFile).use { input.copyTo(it) }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "overrides 추출 실패: ${e.message}")
        }
    }

    private fun downloadFile(
        url: String,
        destFile: File,
        onBytes: ((downloaded: Long, total: Long, speedBps: Double) -> Unit)? = null,
    ) {
        if (destFile.exists() && destFile.length() > 0) return
        destFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("다운로드 실패: $url (${response.code})")
            val body = response.body ?: throw Exception("응답 본문 없음: $url")
            val contentLen = body.contentLength()

            if (onBytes == null) {
                // 진행 보고가 필요 없는 경우(개별 모드 등) — 기존처럼 통째 복사.
                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { input.copyTo(it) }
                }
                return
            }

            // 진행 보고가 필요한 경우(모드팩 zip 등) — 스트리밍하며 속도 계산.
            val startNs = System.nanoTime()
            var lastTickNs = startNs
            var lastTickBytes = 0L
            var speedBps = 0.0
            var lastPublishNs = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        total += read

                        val now = System.nanoTime()
                        val dtNs = now - lastTickNs
                        if (dtNs >= 150_000_000L) {
                            speedBps = (total - lastTickBytes) * 1_000_000_000.0 / dtNs
                            lastTickNs = now
                            lastTickBytes = total
                        }
                        if (now - lastPublishNs >= 150_000_000L) {
                            lastPublishNs = now
                            onBytes(total, contentLen, speedBps)
                        }
                    }
                    val elapsed = (System.nanoTime() - startNs).coerceAtLeast(1)
                    if (speedBps <= 0.0) speedBps = total * 1_000_000_000.0 / elapsed
                    onBytes(total, contentLen, speedBps)
                }
            }
        }
    }

    /** 바이트/속도 → "12.3 MB / 45.0 MB · 8.4 MB/s" 라벨(파일명 뒤에 붙일 상세). */
    private fun byteDetail(downloaded: Long, total: Long, speedBps: Double): String = buildString {
        if (total > 0) append("${fmtBytes(downloaded)} / ${fmtBytes(total)}")
        else if (downloaded > 0) append(fmtBytes(downloaded))
        if (speedBps > 1.0) {
            if (isNotEmpty()) append(" · ")
            append(fmtSpeed(speedBps))
        }
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1024L*1024L*1024L -> String.format("%.2f GB", b / (1024.0*1024.0*1024.0))
        b >= 1024L*1024L       -> String.format("%.1f MB", b / (1024.0*1024.0))
        b >= 1024L             -> String.format("%.0f KB", b / 1024.0)
        else                   -> "$b B"
    }

    private fun fmtSpeed(bps: Double): String = when {
        bps >= 1024.0*1024.0 -> String.format("%.1f MB/s", bps / (1024.0*1024.0))
        bps >= 1024.0        -> String.format("%.0f KB/s", bps / 1024.0)
        else                 -> String.format("%.0f B/s", bps)
    }
}