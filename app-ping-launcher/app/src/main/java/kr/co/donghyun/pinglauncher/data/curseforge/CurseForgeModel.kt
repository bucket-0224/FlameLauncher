package kr.co.donghyun.pinglauncher.data.curseforge

import com.google.gson.annotations.SerializedName

// ── API 응답 모델 ──────────────────────────────────────────

data class CurseForgeResponse<T>(
    val data: T
)

data class CurseForgeListResponse<T>(
    val data: List<T>,
    val pagination: Pagination?
)

data class Pagination(
    val index: Int,
    val pageSize: Int,
    val resultCount: Int,
    val totalCount: Int
)

// ── 모드팩 ──────────────────────────────────────────────────

data class CurseForgeMod(
    val id: Int,
    val name: String,
    val summary: String,
    val downloadCount: Long,
    val logo: CurseForgeLogo?,
    val latestFiles: List<CurseForgeFile>,
    val latestFilesIndexes: List<CurseForgeFileIndex>,
    val categories: List<CurseForgeCategory>
)

data class CurseForgeLogo(
    val url: String,
    val thumbnailUrl: String
)

data class CurseForgeFile(
    val id: Int,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String?,
    val gameVersions: List<String>,
    val fileLength: Long,
    val dependencies: List<CurseForgeDependency>,
    val releaseType: Int = 1   // 1=Release, 2=Beta, 3=Alpha
)
data class CurseForgeFileIndex(
    val gameVersion: String,
    val fileId: Int,
    val filename: String,
    val releaseType: Int,
    val modLoader: Int? = null
)

data class CurseForgeCategory(
    val id: Int,
    val name: String
)

data class CurseForgeDependency(
    val modId: Int,
    val relationType: Int  // 3 = required
)

// ── 모드팩 매니페스트 (.mrpack 압축 해제 후) ──────────────────

data class CurseForgeManifest(
    val minecraft: MinecraftInfo,
    val manifestType: String,
    val manifestVersion: Int,
    val name: String,
    val version: String,
    val author: String,
    val files: List<ManifestFile>,
    val overrides: String = "overrides"
)

data class MinecraftInfo(
    val version: String,
    val modLoaders: List<ModLoaderInfo>
)

data class ModLoaderInfo(
    val id: String,        // e.g. "forge-36.2.39"
    val primary: Boolean
)

data class ManifestFile(
    val projectID: Int,
    val fileID: Int,
    val required: Boolean
)

// ── Forge 버전 정보 ──────────────────────────────────────────

data class ForgeVersionManifest(
    val id: String,
    val mainClass: String,
    val libraries: List<ForgeLibrary>,
    val arguments: ForgeArguments?
)

data class ForgeLibrary(
    val name: String,
    val downloads: ForgeLibraryDownloads?,
    val url: String? = null
)

data class ForgeLibraryDownloads(
    val artifact: ForgeArtifact?
)

data class ForgeArtifact(
    val path: String,
    val url: String,
    val sha1: String,
    val size: Long
)

data class ForgeArguments(
    val game: List<String>?,
    val jvm: List<String>?
)

data class InstalledModPackCache(
    val mcVersion: String,
    val forgeId: String?,
    val mainClass: String,
    val extraJars: List<String>,
    val assetIndexId: String,
    val gameDirPath: String
)