package kr.co.donghyun.pinglauncher.presentation.util.curseforge

import android.util.Log
import com.google.gson.Gson
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeManifest
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeMod
import kr.co.donghyun.pinglauncher.data.curseforge.InstalledModPackCache
import kr.co.donghyun.pinglauncher.data.mojang.DownloadPhase
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
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
        val existingMeta = kr.co.donghyun.pinglauncher.data.instance.InstanceManager.loadMeta(gameDir)
        if (existingMeta != null && existingMeta.loaderType != null) {
            Log.d("PING_LAUNCHER", "✅ 메타 캐시 발견: ${mod.name}")
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
        downloadFile(downloadUrl, modpackZip)

        val manifest = extractManifest(modpackZip)
            ?: return ModPackInstallResult(success = false, error = "manifest.json 없음")

        val manifestMc = manifest.minecraft.version
        val isManifestMcValid = manifestMc.matches(Regex("^1\\.\\d+(\\..*)?$"))
                || manifestMc.matches(Regex("^\\d+w\\d+[a-z]$"))

        val mcVersion = when {
            isManifestMcValid -> manifestMc
            mcVersionOverride != null -> {
                Log.w("PING_LAUNCHER", "manifest의 mcVersion='$manifestMc' 비정상 → CurseForge 메타의 '$mcVersionOverride' 사용")
                mcVersionOverride
            }
            else -> return ModPackInstallResult(
                success = false,
                error = "MC 버전 확인 실패 (manifest='$manifestMc', file 메타도 없음)"
            )
        }

        Log.d("PING_LAUNCHER", "📦 ${manifest.name} v${manifest.version}, MC=$mcVersion, loader=${manifest.minecraft.modLoaders.firstOrNull { it.primary }?.id}")

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
                Log.w("PING_LAUNCHER", "모드 다운로드 실패: ${manifestFile.fileID}")
            }
        }

        modpackZip.delete()
        disableIncompatibleMods(modsDir)

        Log.d("PING_LAUNCHER", "✅ 모드팩 설치: ${mod.name}, MC $mcVersion, $loaderType $loaderVersion")

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
                    Log.d("PING_LAUNCHER", "⚠ 비호환 모드 비활성화: ${file.name}")
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
                        Log.e("PING_LAUNCHER", "이 모드팩은 Modrinth(.mrpack) 형식 — 현재 미지원")
                        val mrJson = zip.getInputStream(mrpackEntry).bufferedReader().readText()
                        Log.d("PING_LAUNCHER", "mrpack 내용:\n$mrJson")
                        return null
                    }
                    Log.e("PING_LAUNCHER", "manifest.json도 modrinth.index.json도 없음")
                    Log.d("PING_LAUNCHER", "zip 내부 파일들:")
                    zip.entries().asSequence().take(20).forEach { Log.d("PING_LAUNCHER", "  ${it.name}") }
                    return null
                }
                val json = zip.getInputStream(entry).bufferedReader().readText()
                Log.d("PING_LAUNCHER", "═══ manifest.json 원본 ═══")
                json.lineSequence().take(40).forEach { Log.d("PING_LAUNCHER", it) }
                Log.d("PING_LAUNCHER", "═══════════════════════════")
                gson.fromJson(json, CurseForgeManifest::class.java)
            }
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "manifest.json 파싱 실패: ${e.message}")
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
            Log.e("PING_LAUNCHER", "overrides 추출 실패: ${e.message}")
        }
    }

    private fun downloadFile(url: String, destFile: File) {
        if (destFile.exists() && destFile.length() > 0) return
        destFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("다운로드 실패: $url (${response.code})")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destFile).use { input.copyTo(it) }
            }
        }
    }
}