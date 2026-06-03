package kr.co.donghyun.pinglauncher.presentation.util.forge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 한 Forge 빌드 엔트리.
 *
 *  - fullVersion        : "1.21.1-52.0.10" 같은 정식 풀 표기
 *  - installerUrl       : 호출자가 다운받을 installer JAR URL
 *  - clientUrl          : universal/client JAR URL (필요 시 사용)
 */
data class ForgeLoaderEntry(
    val mcVersion: String,
    val forgeVersion: String,
    val recommended: Boolean,
    val latest: Boolean,
) {
    val fullVersion: String get() = "$mcVersion-$forgeVersion"

    val installerUrl: String get() =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
                "$fullVersion/forge-$fullVersion-installer.jar"

    val clientUrl: String get() =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
                "$fullVersion/forge-$fullVersion-universal.jar"
}

class ForgeMetaAPI {

    companion object {
        private const val PROMOTIONS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
        private const val MAVEN_METADATA_URL =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
    }

    /**
     * 특정 마인크래프트 버전에 대해 사용 가능한 모든 Forge 빌드를 반환한다.
     * 정렬: 신 → 구.
     *
     * 네트워크 호출이므로 반드시 IO 디스패처에서 부를 것.
     */
    fun listLoaders(mcVersion: String): List<ForgeLoaderEntry> {
        val promos = fetchPromotions()
        val recommended = promos["$mcVersion-recommended"]
        val latest = promos["$mcVersion-latest"]

        return fetchAllVersions()
            .asSequence()
            .filter { (mc, _) -> mc == mcVersion }
            .map { (mc, forge) ->
                ForgeLoaderEntry(
                    mcVersion = mc,
                    forgeVersion = forge,
                    recommended = forge == recommended,
                    latest = forge == latest,
                )
            }
            .toList()
            // Maven metadata 는 일반적으로 오래된 순서. 신 -> 구로 뒤집어 반환.
            .asReversed()
    }

    /** promotions_slim.json 파싱. 실패 시 빈 맵. */
    private fun fetchPromotions(): Map<String, String> = try {
        val text = httpGet(PROMOTIONS_URL)
        val promos = JSONObject(text).getJSONObject("promos")
        promos.keys().asSequence().associateWith { promos.getString(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    /** maven-metadata.xml 파싱 → (mcVersion, forgeVersion) 쌍 목록 */
    private fun fetchAllVersions(): List<Pair<String, String>> {
        val xml = httpGet(MAVEN_METADATA_URL)
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("version")
        val out = ArrayList<Pair<String, String>>(nodes.length)
        for (i in 0 until nodes.length) {
            val v = nodes.item(i).textContent ?: continue
            // "<mcVersion>-<forgeVersion>"  예) "1.21.1-52.0.10"
            val dash = v.indexOf('-')
            if (dash <= 0) continue
            out += v.substring(0, dash) to v.substring(dash + 1)
        }
        return out
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}