package kr.co.donghyun.flamelauncher.presentation

import android.content.Context
import android.content.Intent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.data.instance.InstanceMeta
import kr.co.donghyun.flamelauncher.presentation.util.hosts.ServerFavorites
import kr.co.donghyun.flamelauncher.presentation.ui.theme.*
import kr.co.donghyun.flamelauncher.presentation.util.hosts.HostEntry
import kr.co.donghyun.flamelauncher.presentation.util.hosts.HostsManager
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet

class NetworkSettingsActivity : BaseActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, NetworkSettingsActivity::class.java))
        }
    }

    override fun onCreated() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = android.graphics.Color.TRANSPARENT)
        )
        setContent {
            FlameLauncherTheme {
                NetworkSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun NetworkSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tablet = isTablet()

    var entries by remember { mutableStateOf(HostsManager.load(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<HostEntry?>(null) }

    // ── 서버 즐겨찾기(멀티플레이) 상태 ──
    val instances = remember { InstanceManager.listInstances(context) }
    // (instanceId, mcVersion, name, address) 평면 목록으로 모든 인스턴스의 즐겨찾기를 모음
    fun loadAllServers(): List<ServerRow> =
        instances.flatMap { meta ->
            ServerFavorites.list(context, meta.id).map { fav ->
                ServerRow(
                    instanceId = meta.id,
                    instanceName = meta.name,
                    mcVersion = meta.mcVersion,
                    name = fav.name,
                    address = fav.address
                )
            }
        }
    var serverRows by remember { mutableStateOf(loadAllServers()) }
    var showServerDialog by remember { mutableStateOf(false) }

    fun persist(updated: List<HostEntry>) {
        entries = updated
        HostsManager.save(context, updated)
    }

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        // 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder)
                .padding(horizontal = if (tablet) 16.dp else 10.dp, vertical = if (tablet) 10.dp else 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("뒤로", color = TextSecondary, fontSize = if (tablet) 14.sp else 11.sp)
            }
            Text("네트워크 설정", color = TextPrimary,
                fontSize = if (tablet) 18.sp else 14.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Flame),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("+ 추가", color = Color.White,
                    fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (tablet) 20.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InfoCard(tablet)

            // ── 내 서버 (멀티플레이) 섹션 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎮 내 서버 (${serverRows.size}개)",
                    color = TextPrimary,
                    fontSize = if (tablet) 15.sp else 12.sp,
                    fontWeight = FontWeight.Bold)
                Button(
                    onClick = { showServerDialog = true },
                    enabled = instances.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Flame),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("+ 서버 추가", color = Color.White,
                        fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (instances.isEmpty()) {
                ServerEmptyBox("인스턴스가 없습니다.\n먼저 게임 인스턴스를 만든 뒤 서버를 등록하세요.", tablet)
            } else if (serverRows.isEmpty()) {
                ServerEmptyBox(
                    "등록된 서버가 없습니다.\n친구가 PC + playit.gg 로 호스트한 주소를 ‘+ 서버 추가’ 로 등록하세요.\n게임 멀티플레이 목록에 자동으로 표시됩니다.",
                    tablet
                )
            } else {
                serverRows.forEach { row ->
                    ServerRowItem(
                        row = row,
                        tablet = tablet,
                        onDelete = {
                            ServerFavorites.remove(context, row.instanceId, row.mcVersion, row.address)
                            serverRows = loadAllServers()
                        }
                    )
                }
            }

            Text("🗂 호스트 매핑 (${entries.size}개)",
                color = TextPrimary,
                fontSize = if (tablet) 15.sp else 12.sp,
                fontWeight = FontWeight.Bold)

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgSurface, RoundedCornerShape(10.dp))
                        .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                        .padding(if (tablet) 24.dp else 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "추가된 호스트가 없습니다.\n‘+ 추가’ 버튼으로 Hamachi/LAN 서버 IP 를 등록하세요.",
                        color = TextSecondary,
                        fontSize = if (tablet) 12.sp else 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                entries.forEach { entry ->
                    HostEntryItem(
                        entry = entry,
                        tablet = tablet,
                        onToggle = {
                            persist(entries.map {
                                if (it.hostname == entry.hostname && it.ip == entry.ip)
                                    it.copy(enabled = !it.enabled) else it
                            })
                        },
                        onEdit = { editingEntry = entry },
                        onDelete = {
                            persist(entries.filterNot {
                                it.hostname == entry.hostname && it.ip == entry.ip
                            })
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        HostEntryDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { newEntry ->
                persist(entries + newEntry)
                showAddDialog = false
            },
            tablet = tablet
        )
    }
    editingEntry?.let { e ->
        HostEntryDialog(
            initial = e,
            onDismiss = { editingEntry = null },
            onSave = { updated ->
                persist(entries.map {
                    if (it.hostname == e.hostname && it.ip == e.ip) updated else it
                })
                editingEntry = null
            },
            tablet = tablet
        )
    }

    if (showServerDialog) {
        ServerAddDialog(
            instances = instances,
            tablet = tablet,
            onDismiss = { showServerDialog = false },
            onSave = { instanceId, mcVersion, name, address ->
                ServerFavorites.add(context, instanceId, mcVersion, name, address)
                serverRows = loadAllServers()
                showServerDialog = false
            }
        )
    }
}

@Composable
private fun InfoCard(tablet: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(if (tablet) 14.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("ℹ️ 동작 방식",
            color = Flame, fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
        Text(
            "• KR 도메인: KT/SK DNS 가 자동으로 폴백에 포함됩니다 (별도 설정 불필요)\n" +
                    "• Hamachi: 친구가 알려준 호스트네임 ↔ 25.x.x.x IP 를 여기 등록\n" +
                    "• 단, Hamachi 네트워크 자체에 접속하려면 단말에 Hamachi 모바일 앱 등이 필요합니다",
            color = TextSecondary,
            fontSize = if (tablet) 12.sp else 10.sp,
            lineHeight = if (tablet) 17.sp else 14.sp
        )
    }
}

@Composable
private fun HostEntryItem(
    entry: HostEntry,
    tablet: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, if (entry.enabled) BgBorder else BgBorder.copy(alpha = 0.4f),
                RoundedCornerShape(10.dp))
            .clickable { onEdit() }
            .padding(horizontal = if (tablet) 14.dp else 10.dp, vertical = if (tablet) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.hostname,
                color = if (entry.enabled) TextPrimary else TextSecondary,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "→ ${entry.ip}",
                color = if (entry.enabled) Flame else TextSecondary,
                fontSize = if (tablet) 12.sp else 10.sp,
            )
            if (entry.note.isNotBlank()) {
                Text(entry.note, color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = if (tablet) 10.sp else 9.sp)
            }
        }
        Switch(
            checked = entry.enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Flame,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BgBorder
            )
        )
        Box(
            modifier = Modifier
                .size(if (tablet) 32.dp else 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BgDark)
                .border(1.dp, BgBorder, RoundedCornerShape(6.dp))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Text("🗑", fontSize = if (tablet) 14.sp else 12.sp)
        }
    }
}

@Composable
private fun HostEntryDialog(
    initial: HostEntry?,
    onDismiss: () -> Unit,
    onSave: (HostEntry) -> Unit,
    tablet: Boolean,
) {
    var hostname by remember { mutableStateOf(initial?.hostname ?: "") }
    var ip       by remember { mutableStateOf(initial?.ip ?: "") }
    var note     by remember { mutableStateOf(initial?.note ?: "") }
    var error    by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (tablet) 0.65f else 0.95f)
                .clip(RoundedCornerShape(14.dp))
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                .padding(if (tablet) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (initial == null) "호스트 추가" else "호스트 수정",
                color = TextPrimary,
                fontSize = if (tablet) 17.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )

            LabeledField("호스트네임", hostname, "예: myserver.hamachi", tablet) { hostname = it; error = null }
            LabeledField("IP 주소",  ip,       "예: 25.123.45.67",     tablet) { ip = it; error = null }
            LabeledField("메모(선택)", note,    "예: 친구 서버",          tablet) { note = it }

            error?.let {
                Text("❌ $it", color = Color(0xFFFF6B6B),
                    fontSize = if (tablet) 12.sp else 10.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("취소", color = TextSecondary,
                        fontSize = if (tablet) 13.sp else 11.sp)
                }
                Button(
                    onClick = {
                        val h = hostname.trim()
                        val i = ip.trim()
                        when {
                            h.isBlank() -> error = "호스트네임을 입력하세요"
                            i.isBlank() -> error = "IP 주소를 입력하세요"
                            !validateIp(i) -> error = "IPv4 형식이 아닙니다 (예: 25.1.2.3)"
                            !validateHost(h) -> error = "호스트네임에 사용할 수 없는 문자가 있습니다"
                            else -> onSave(HostEntry(h, i, note.trim(), enabled = initial?.enabled ?: true))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Flame),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("저장", color = Color.White,
                        fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String, value: String, hint: String,
    tablet: Boolean,
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextSecondary, fontSize = if (tablet) 12.sp else 10.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = TextPrimary,
                fontSize = if (tablet) 13.sp else 11.sp),
            cursorBrush = SolidColor(Flame),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(hint, color = TextSecondary.copy(alpha = 0.4f),
                            fontSize = if (tablet) 13.sp else 11.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(BgDark, RoundedCornerShape(8.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

private fun validateIp(s: String): Boolean {
    val parts = s.split('.')
    if (parts.size != 4) return false
    return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}

private fun validateHost(s: String): Boolean =
    s.isNotBlank() && s.length <= 253 &&
            s.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' }
// ───────────────────────── 내 서버(멀티플레이) ─────────────────────────

/** 화면 표시용 — 즐겨찾기 한 줄 + 어느 인스턴스 소속인지. */
private data class ServerRow(
    val instanceId: String,
    val instanceName: String,
    val mcVersion: String,
    val name: String,
    val address: String,
)

@Composable
private fun ServerEmptyBox(text: String, tablet: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(if (tablet) 24.dp else 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = TextSecondary,
            fontSize = if (tablet) 12.sp else 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = if (tablet) 17.sp else 15.sp
        )
    }
}

@Composable
private fun ServerRowItem(
    row: ServerRow,
    tablet: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = if (tablet) 14.dp else 10.dp, vertical = if (tablet) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.name,
                color = TextPrimary,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                "→ ${row.address}",
                color = Flame,
                fontSize = if (tablet) 12.sp else 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                "📦 ${row.instanceName} · MC ${row.mcVersion}",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = if (tablet) 10.sp else 9.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(if (tablet) 32.dp else 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BgDark)
                .border(1.dp, BgBorder, RoundedCornerShape(6.dp))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Text("🗑", fontSize = if (tablet) 14.sp else 12.sp)
        }
    }
}

@Composable
private fun ServerAddDialog(
    instances: List<InstanceMeta>,
    tablet: Boolean,
    onDismiss: () -> Unit,
    onSave: (instanceId: String, mcVersion: String, name: String, address: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(instances.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (tablet) 0.65f else 0.95f)
                .clip(RoundedCornerShape(14.dp))
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(14.dp))
                .padding(if (tablet) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "서버 추가",
                color = TextPrimary,
                fontSize = if (tablet) 17.sp else 14.sp,
                fontWeight = FontWeight.Bold
            )

            LabeledField("서버 이름", name, "예: 친구 월드", tablet) { name = it; error = null }
            LabeledField("주소", address, "예: purple-cat-1234.playit.gg:25565", tablet) { address = it; error = null }

            // ── 인스턴스 선택 ──
            Text("설치할 인스턴스", color = TextSecondary, fontSize = if (tablet) 12.sp else 10.sp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (tablet) 200.dp else 160.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                instances.forEach { inst ->
                    val sel = selected?.id == inst.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Flame.copy(alpha = 0.18f) else BgDark)
                            .border(1.dp, if (sel) Flame else BgBorder, RoundedCornerShape(8.dp))
                            .clickable { selected = inst; error = null }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(inst.name, color = TextPrimary,
                                fontSize = if (tablet) 13.sp else 11.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("MC ${inst.mcVersion}", color = TextSecondary,
                                fontSize = if (tablet) 10.sp else 9.sp)
                        }
                        if (sel) Text("✓", color = Flame,
                            fontSize = if (tablet) 14.sp else 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            error?.let {
                Text("❌ $it", color = Color(0xFFFF6B6B),
                    fontSize = if (tablet) 12.sp else 10.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("취소", color = TextSecondary, fontSize = if (tablet) 13.sp else 11.sp)
                }
                Button(
                    onClick = {
                        val n = name.trim()
                        val a = address.trim()
                        val inst = selected
                        when {
                            a.isBlank() -> error = "주소를 입력하세요"
                            inst == null -> error = "인스턴스를 선택하세요"
                            else -> onSave(inst.id, inst.mcVersion, n.ifBlank { a }, a)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Flame),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("저장", color = Color.White,
                        fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}