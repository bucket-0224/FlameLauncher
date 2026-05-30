package kr.co.donghyun.pinglauncher.data.instance

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
    val gameArgs: List<String> = emptyList()     // Fabric profile의 arguments.game
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

    fun vanillaId(versionId: String) = "vanilla_$versionId"

    fun modpackId(modName: String): String =
        "modpack_" + modName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)

    fun fabricId(mcVersion: String, loaderVersion: String): String =
        "fabric_${mcVersion}_" + loaderVersion.replace(".", "_")
}