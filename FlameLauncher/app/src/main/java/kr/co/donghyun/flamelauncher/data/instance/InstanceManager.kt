package kr.co.donghyun.flamelauncher.data.instance

import android.content.Context
import com.google.gson.Gson
import java.io.File

enum class InstanceType { VANILLA, MODPACK, FABRIC }

data class InstanceMeta(
    val id: String,
    val name: String,
    val type: InstanceType,
    val mcVersion: String,
    val loaderType: String? = null,        // "fabric", "forge"
    val loaderVersion: String? = null,
    val mainClass: String = "net.minecraft.client.main.Main",
    val extraJars: List<String> = emptyList(),
    val assetIndexId: String = "",
    val iconEmoji: String = "🌿",
    val gameJvmArgs: List<String> = emptyList(), // Fabric profile의 arguments.jvm
    val gameArgs: List<String> = emptyList(),    // Fabric profile의 arguments.game
    val sourceModId: Int? = null,
    /**
     * 이 인스턴스에 사용할 렌더러 id ("zink" / "gl4es" / "mobileglues").
     * null 이면 전역 기본 렌더러(RendererManager.load)를 따른다 — 하위호환(기존 인스턴스 json엔 키 없음).
     */
    val rendererId: String? = null,
)

object InstanceManager {
    private const val META_FILE = "instance.json"
    private val gson = Gson()

    fun instancesDir(context: Context): File =
        File(context.getExternalFilesDir(null), "instances")

    fun instanceDir(context: Context, id: String): File =
        File(instancesDir(context), id)

    fun listInstances(context: Context): List<InstanceMeta> {
        val dir = instancesDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { loadMeta(it) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun loadMeta(instanceDir: File): InstanceMeta? {
        return try {
            val f = File(instanceDir, META_FILE)
            if (!f.exists()) return null
            gson.fromJson(f.readText(), InstanceMeta::class.java)
        } catch (_: Exception) { null }
    }

    fun saveMeta(context: Context, meta: InstanceMeta) {
        val dir = instanceDir(context, meta.id).also { it.mkdirs() }
        File(dir, META_FILE).writeText(gson.toJson(meta))
    }

    fun deleteInstance(context: Context, id: String) {
        instanceDir(context, id).deleteRecursively()
    }

    // ── 인스턴스별 렌더러 ───────────────────────────────────────────

    /**
     * 이 인스턴스에 설정된 렌더러 id 를 읽는다. 설정 안 했으면 null.
     * (null 이면 호출 측에서 전역 기본 렌더러로 폴백)
     */
    fun loadRendererId(context: Context, id: String): String? =
        loadMeta(instanceDir(context, id))?.rendererId

    /**
     * 이 인스턴스의 렌더러 id 를 갱신·저장한다. 메타가 없으면 무시(false).
     * @param rendererId "zink"/"gl4es"/"mobileglues", 또는 null(전역 기본 사용)
     * @return 저장 성공 여부
     */
    fun updateRendererId(context: Context, id: String, rendererId: String?): Boolean {
        val meta = loadMeta(instanceDir(context, id)) ?: return false
        saveMeta(context, meta.copy(rendererId = rendererId))
        return true
    }

    fun vanillaId(versionId: String) = "vanilla_$versionId"

    fun modpackId(modName: String): String =
        "modpack_" + modName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)

    fun fabricId(mcVersion: String, loaderVersion: String): String =
        "fabric_${mcVersion}_" + loaderVersion.replace(".", "_")
}