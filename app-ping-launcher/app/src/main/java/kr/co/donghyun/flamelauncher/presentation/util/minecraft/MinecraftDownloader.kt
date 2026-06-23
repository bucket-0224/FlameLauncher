package kr.co.donghyun.flamelauncher.presentation.util.minecraft

import android.util.Log
import com.google.gson.Gson
import kr.co.donghyun.flamelauncher.data.mojang.DownloadPhase
import kr.co.donghyun.flamelauncher.data.mojang.DownloadProgress
import kr.co.donghyun.flamelauncher.data.mojang.MCPrepareResult
import kr.co.donghyun.flamelauncher.data.mojang.VersionEntry
import kr.co.donghyun.flamelauncher.data.mojang.VersionManifest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 바닐라 MC 다운로더 — 모든 파일을 instanceDir 하위에 저장
 *
 * instanceDir/
 *   assets/indexes/
 *   assets/objects/
 *   libraries/
 *   versions/<versionId>/
 */
class MinecraftDownloader(
    private val instanceDir: File,   // 인스턴스 루트 (예: instances/vanilla_1.21.4)
    private val versionEntry: VersionEntry,
    private val onProgress: (DownloadProgress) -> Unit
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    fun prepare(): MCPrepareResult {
        onProgress(DownloadProgress(phase = DownloadPhase.FETCHING_MANIFEST))
        val manifest = fetchManifest(versionEntry.url)

        // 클라이언트 JAR
        onProgress(DownloadProgress(phase = DownloadPhase.DOWNLOADING_CLIENT, fileName = "${manifest.id}.jar"))
        val clientJar = File(instanceDir, "versions/${manifest.id}/${manifest.id}.jar")
        downloadFile(manifest.downloads.client.url, clientJar, manifest.downloads.client.sha1)

        // 에셋 인덱스
        val assetIndexFile = File(instanceDir, "assets/indexes/${manifest.assetIndex.id}.json")
        downloadFile(manifest.assetIndex.url, assetIndexFile, null)

        // 라이브러리
        val librariesDir = File(instanceDir, "libraries")
        val artifacts = manifest.libraries.mapNotNull { lib ->
            lib.downloads.artifact?.let { lib to it }
        }
        artifacts.forEachIndexed { index, (lib, artifact) ->
            val path = getLibraryPath(lib.name)
            val libFile = File(librariesDir, path)
            onProgress(DownloadProgress(
                phase = DownloadPhase.DOWNLOADING_LIBRARIES,
                current = index + 1,
                total = artifacts.size,
                fileName = libFile.name
            ))
            downloadFile(artifact.url, libFile, artifact.sha1)
        }

        // 에셋 오브젝트
        downloadAssets(assetIndexFile, File(instanceDir, "assets/objects"))

        Log.d("PING_LAUNCHER", "✅ MC ${manifest.id} 준비 완료 → ${instanceDir.absolutePath}")
        return MCPrepareResult(
            assetIndexId = manifest.assetIndex.id,
            mainClass = manifest.mainClass,
            minecraftArguments = manifest.minecraftArguments
        )
    }

    private fun fetchManifest(url: String): VersionManifest {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: throw Exception("버전 JSON 읽기 실패")
            return gson.fromJson(json, VersionManifest::class.java)
        }
    }

    private fun downloadFile(url: String, destFile: File, expectedSha1: String?) {
        if (destFile.exists() && destFile.length() > 0) return
        destFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("PING_LAUNCHER", "다운로드 실패 (${response.code}): $url")
                return
            }
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destFile).use { input.copyTo(it) }
            }
        }
    }

    private fun downloadAssets(assetIndexFile: File, objectsDir: File) {
        if (!assetIndexFile.exists()) return
        val json = assetIndexFile.readText()
        val objects = com.google.gson.JsonParser.parseString(json)
            .asJsonObject["objects"].asJsonObject
        val entries = objects.entrySet().toList()
        val total = entries.size
        var downloaded = 0

        entries.forEach { (_, value) ->
            val hash = value.asJsonObject["hash"].asString
            val prefix = hash.substring(0, 2)
            val destFile = File(objectsDir, "$prefix/$hash")
            downloaded++
            onProgress(DownloadProgress(
                phase = DownloadPhase.DOWNLOADING_ASSETS,
                current = downloaded,
                total = total,
                fileName = hash.take(12) + "..."
            ))
            if (destFile.exists() && destFile.length() > 0) return@forEach
            destFile.parentFile?.mkdirs()
            try {
                val url = "https://resources.download.minecraft.net/$prefix/$hash"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(destFile).use { input.copyTo(it) }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun getLibraryPath(name: String): String {
        val parts = name.split(":")
        val basePath = "${parts[0].replace('.', '/')}/${parts[1]}/${parts[2]}/${parts[1]}-${parts[2]}"
        return "$basePath.jar"
    }
}