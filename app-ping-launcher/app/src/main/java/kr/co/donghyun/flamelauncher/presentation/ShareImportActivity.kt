package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.ui.theme.*
import kr.co.donghyun.flamelauncher.presentation.util.maps.MapImporter
import kr.co.donghyun.flamelauncher.presentation.util.mods.ModImporter
import java.io.File

/**
 * 파일관리자 등에서 .jar / .zip 을 "공유"하면 이 액티비티가 받아서
 * 종류(모드/월드)를 자동 감지하고, 사용자가 고른 인스턴스의 mods/ 또는 saves/ 로 넣는다.
 *
 * AndroidManifest 에 ACTION_SEND / ACTION_SEND_MULTIPLE intent-filter 등록 필요(아래 스니펫 참고).
 *
 * ⚠️ 확인 필요한 가정 한 곳:
 *   loadInstances() 가 인스턴스 목록을 "<externalFilesDir>/instances/<id>" 구조로 가정한다.
 *   프로젝트의 InstanceManager 가 목록 API(예: listInstances)를 따로 제공하면 그걸로 바꾸는 게 가장 정확하다.
 *   (실제 import 경로는 InstanceManager.instanceDir(ctx, id) 를 그대로 쓰므로 거긴 안전함)
 */
class ShareImportActivity : BaseActivity() {

    private enum class ImportType { MOD, WORLD }

    private data class SharedFileItem(val uri: Uri, val name: String, val detected: ImportType)
    private data class InstancePick(val id: String, val label: String, val loaderType: String?)

    private val importing = mutableStateOf(false)
    private val statusMsg = mutableStateOf("")

    override fun onCreated() {
        val uris = extractSharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "가져올 파일을 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val files = uris.map { uri ->
            val name = queryDisplayName(uri) ?: (uri.lastPathSegment ?: "file")
            SharedFileItem(uri, name, detectType(name))
        }
        val instances = loadInstances(this)

        setContent {
            PingLauncherTheme {
                ShareImportContent(
                    files = files,
                    instances = instances,
                    importing = importing.value,
                    statusMsg = statusMsg.value,
                    onCancel = { finish() },
                    onConfirm = { instanceId, type -> doImport(files, instanceId, type) },
                )
            }
        }
    }

    // ── 공유 인텐트에서 Uri 추출 ──

    private fun extractSharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
        Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33)
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun detectType(name: String): ImportType =
        if (name.endsWith(".jar", ignoreCase = true)) ImportType.MOD else ImportType.WORLD

    // ── 인스턴스 목록 ──

    private fun loadInstances(context: Context): List<InstancePick> {
        // ⚠️ 가정: 인스턴스가 <externalFilesDir>/instances/<id> 에 있음.
        val root = File(context.getExternalFilesDir(null), "instances")
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        return dirs.map { dir ->
            val loader = runCatching { InstanceManager.loadMeta(dir)?.loaderType }.getOrNull()
            InstancePick(id = dir.name, label = dir.name, loaderType = loader)
        }
    }

    // ── 실제 가져오기 ──

    private fun doImport(files: List<SharedFileItem>, instanceId: String, type: ImportType) {
        importing.value = true
        statusMsg.value = if (type == ImportType.MOD) "모드 추가 중…" else "월드 추가 중…"

        lifecycleScope.launch(Dispatchers.IO) {
            val baseDir = InstanceManager.instanceDir(applicationContext, instanceId)
            val text = when (type) {
                ImportType.MOD -> {
                    val r = ModImporter.importJars(
                        applicationContext, files.map { it.uri }, File(baseDir, "mods")
                    )
                    when (r) {
                        is ModImporter.Result.Success -> buildString {
                            append("모드 ${r.added.size}개 추가됨")
                            if (r.skipped.isNotEmpty()) append(" · ${r.skipped.size}개 건너뜀")
                        }
                        is ModImporter.Result.Failure -> r.reason
                    }
                }
                ImportType.WORLD -> {
                    val savesDir = File(baseDir, "saves")
                    var ok = 0
                    val fails = ArrayList<String>()
                    files.forEach { f ->
                        when (val res = MapImporter.importZip(applicationContext, f.uri, savesDir)) {
                            is MapImporter.Result.Success -> ok++
                            is MapImporter.Result.Failure -> fails.add("${f.name}: ${res.reason}")
                        }
                    }
                    buildString {
                        append("월드 ${ok}개 추가됨")
                        if (fails.isNotEmpty()) append("\n실패: " + fails.joinToString("\n"))
                    }
                }
            }
            withContext(Dispatchers.Main) {
                importing.value = false
                Toast.makeText(this@ShareImportActivity, text, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
    } catch (_: Exception) {
        null
    }

    companion object {
        // 외부 호출 필요 없음 — 매니페스트 intent-filter 로만 진입.
    }

    // ──────────────────────────────────────────────────────────────
    // Compose UI
    // ──────────────────────────────────────────────────────────────

    @Composable
    private fun ShareImportContent(
        files: List<SharedFileItem>,
        instances: List<InstancePick>,
        importing: Boolean,
        statusMsg: String,
        onCancel: () -> Unit,
        onConfirm: (instanceId: String, type: ImportType) -> Unit,
    ) {
        var type by remember { mutableStateOf(files.firstOrNull()?.detected ?: ImportType.WORLD) }
        var selectedInstance by remember { mutableStateOf<String?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("인스턴스에 추가", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                // 파일 목록
                Text("${files.size}개 파일", color = TextSecondary, fontSize = 12.sp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    files.take(5).forEach {
                        Text("• ${it.name}", color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                    }
                    if (files.size > 5)
                        Text("… 외 ${files.size - 5}개", color = TextSecondary, fontSize = 11.sp)
                }

                // 종류
                Text("종류", color = TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip("모드 (.jar)", type == ImportType.MOD, Modifier.weight(1f)) {
                        type = ImportType.MOD
                        // 모드로 바꿨는데 선택된 인스턴스에 로더가 없으면 선택 해제
                        val ld = instances.find { it.id == selectedInstance }?.loaderType
                        if (ld == null) selectedInstance = null
                    }
                    TypeChip("월드 (.zip)", type == ImportType.WORLD, Modifier.weight(1f)) {
                        type = ImportType.WORLD
                    }
                }

                // 인스턴스
                Text("인스턴스", color = TextSecondary, fontSize = 12.sp)
                if (instances.isEmpty()) {
                    Text("인스턴스가 없습니다.", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(instances, key = { it.id }) { inst ->
                            // 모드는 로더가 있는 인스턴스에만 넣을 수 있음
                            val needsLoader = type == ImportType.MOD && inst.loaderType == null
                            val enabled = !needsLoader
                            val sel = selectedInstance == inst.id
                            InstanceRow(
                                label = inst.label,
                                loaderType = inst.loaderType,
                                selected = sel,
                                enabled = enabled,
                            ) { if (enabled) selectedInstance = inst.id }
                        }
                    }
                }

                // 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onCancel) { Text("취소", color = TextSecondary) }
                    Button(
                        onClick = { selectedInstance?.let { onConfirm(it, type) } },
                        enabled = selectedInstance != null && !importing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FlamePrimary,
                            disabledContainerColor = BgBorder
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("추가", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 진행 오버레이
            if (importing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = FlamePrimary, strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(statusMsg, color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun TypeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Color(0xFF2D0A20) else BgDark)
                .border(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) FlamePrimary else BgBorder,
                    RoundedCornerShape(10.dp)
                )
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) Color.White else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    @Composable
    private fun InstanceRow(
        label: String,
        loaderType: String?,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.4f)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Color(0xFF2D0A20) else BgDark)
                .border(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) FlamePrimary else BgBorder,
                    RoundedCornerShape(8.dp)
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label, color = TextPrimary, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                loaderType?.replaceFirstChar { it.uppercase() } ?: "vanilla",
                color = TextSecondary, fontSize = 10.sp
            )
        }
    }
}