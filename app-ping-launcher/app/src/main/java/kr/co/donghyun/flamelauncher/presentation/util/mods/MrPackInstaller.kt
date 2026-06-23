package kr.co.donghyun.flamelauncher.presentation.util.mods

import android.util.Log
import com.google.gson.Gson
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.data.mods.MrpackFile
import kr.co.donghyun.flamelauncher.data.mods.MrpackIndex
import kr.co.donghyun.flamelauncher.data.mojang.DownloadPhase
import kr.co.donghyun.flamelauncher.data.mojang.DownloadProgress
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.text.get

/**
 * Modrinth .mrpack 모드팩 설치기.
 *
 * CurseForge 의 ModPackInstaller 와 대칭. 같은 ModPackInstallResult 를 반환해
 * 기존 설치 후처리(인스턴스 생성/로더 설치)를 그대로 재활용한다.
 *
 * .mrpack 구조:
 *  - zip 컨테이너, 내부 modrinth.index.json 이 매니페스트
 *  - files[]: 각 항목이 직접 downloads[] URL + path(게임 디렉터리 상대경로) 보유
 *             → CurseForge 처럼 파일 메타를 또 조회할 필요가 없다(다운로드가 직접적).
 *  - overrides/, client-overrides/ : 게임 디렉터리에 그대로 덮어쓸 파일들
 *  - dependencies: {"minecraft":"1.20.1","fabric-loader":"0.15.7"} 같은 맵
 */
class MrpackInstaller(
    private val baseDir: File,
    private val modrinthApi: ModrinthAPI,
    private val onProgress: (DownloadProgress) -> Unit,
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    /**
     * @param mrpackUrl    설치할 .mrpack 파일 URL (ModrinthVersion.files 의 primary url)
     * @param displayName  진행 표시용 이름
     */
    fun install(mrpackUrl: String, displayName: String): ModPackInstallResult {
        val gameDir = baseDir

        // 캐시 — 기존 인스턴스 메타 있으면 재사용 (CurseForge 쪽과 동일 동작)
        val existingMeta = InstanceManager.loadMeta(gameDir)
        if (existingMeta != null && existingMeta.loaderType != null) {
            Log.d("PING_LAUNCHER", "✅ 메타 캐시 발견(.mrpack): $displayName")
            return ModPackInstallResult(
                success = true,
                mcVersion = existingMeta.mcVersion,
                loaderType = existingMeta.loaderType,
                loaderVersion = existingMeta.loaderVersion,
                gameDir = gameDir
            )
        }

        onProgress(DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST, fileName = displayName))

        // 1) .mrpack 다운로드
        val mrpackZip = File(baseDir, "temp/mrpack_${System.currentTimeMillis()}.mrpack")
        mrpackZip.parentFile?.mkdirs()
        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_CLIENT, fileName = "$displayName.mrpack"))
        try {
            downloadFile(mrpackUrl, mrpackZip)
        } catch (e: Exception) {
            return ModPackInstallResult(success = false, error = ".mrpack 다운로드 실패: ${e.message}")
        }

        // 2) modrinth.index.json 파싱
        val index = extractIndex(mrpackZip)
            ?: return ModPackInstallResult(success = false, error = "modrinth.index.json 없음/파싱 실패").also { mrpackZip.delete() }

        val mcVersion = index.dependencies["minecraft"]
            ?: return ModPackInstallResult(success = false, error = "mrpack에 minecraft 버전 없음").also { mrpackZip.delete() }

        val (loaderType, loaderVersion) = resolveLoader(index.dependencies)

        Log.d("PING_LAUNCHER", "📦 .mrpack ${index.name} v${index.versionId}, MC=$mcVersion, loader=$loaderType $loaderVersion")

        // 3) overrides 추출 (overrides/, client-overrides/ 둘 다)
        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_LIBRARIES, fileName = "파일 추출 중..."))
        extractOverrides(mrpackZip, gameDir, "overrides")
        extractOverrides(mrpackZip, gameDir, "client-overrides")

        // 4) files[] 다운로드 (각 항목이 직접 URL 보유)
        val targetFiles = index.files.filter { isClientWanted(it) }
        val total = targetFiles.size
        var done = 0
        targetFiles.forEach { f ->
            done++
            onProgress(DownloadProgress(
                phase = DownloadPhase.DOWNLOADING_ASSETS,
                current = done, total = total,
                fileName = f.path.substringAfterLast('/')
            ))
            val url = f.downloads.firstOrNull() ?: return@forEach
            val dest = File(gameDir, f.path)        // path 가 곧 게임 디렉터리 상대경로
            dest.parentFile?.mkdirs()
            try {
                if (!dest.exists() || dest.length() == 0L) downloadFile(url, dest)
            } catch (e: Exception) {
                Log.w("PING_LAUNCHER", "mrpack 파일 다운로드 실패: ${f.path} (${e.message})")
            }
        }

        mrpackZip.delete()
        Log.d("PING_LAUNCHER", "✅ .mrpack 설치 완료: $displayName, MC $mcVersion, $loaderType $loaderVersion")

        return ModPackInstallResult(
            success = true,
            mcVersion = mcVersion,
            loaderType = loaderType,
            loaderVersion = loaderVersion,
            gameDir = gameDir
        )
    }

    /** 클라이언트에 필요한 파일만(서버 전용 제외). env 가 없으면 포함. */
    private fun isClientWanted(f: MrpackFile): Boolean {
        val client = f.env?.client ?: return true
        return client != "unsupported"
    }

    /**
     * .mrpack dependencies 맵 → (loaderType, loaderVersion)
     *   키: minecraft / forge / neoforge / fabric-loader / quilt-loader
     *   PingLauncher 의 loaderType 표기(fabric/forge/neoforge)에 맞춘다. quilt 는 미지원→null.
     */
    private fun resolveLoader(deps: Map<String, String>): Pair<String?, String?> {
        deps["fabric-loader"]?.let { return "fabric" to it }
        deps["forge"]?.let { return "forge" to it }
        deps["neoforge"]?.let { return "neoforge" to it }
        // quilt-loader 등은 현재 미지원
        return null to null
    }

    private fun extractIndex(zipFile: File): MrpackIndex? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("modrinth.index.json") ?: run {
                    Log.e("PING_LAUNCHER", "modrinth.index.json 없음")
                    return null
                }
                val json = zip.getInputStream(entry).bufferedReader().readText()
                gson.fromJson(json, MrpackIndex::class.java)
            }
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "modrinth.index.json 파싱 실패: ${e.message}")
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
                        if (relativePath.isBlank()) return@forEach
                        val destFile = File(gameDir, relativePath)
                        destFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(destFile).use { input.copyTo(it) }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e("PING_LAUNCHER", "overrides 추출 실패($overridesFolder): ${e.message}")
        }
    }

    private fun downloadFile(url: String, destFile: File) {
        if (destFile.exists() && destFile.length() > 0) return
        destFile.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "donghyun/PingLauncher/1.0 (kr.co.donghyun.pinglauncher)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("다운로드 실패: $url (${response.code})")
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destFile).use { input.copyTo(it) }
            }
        }
    }
}