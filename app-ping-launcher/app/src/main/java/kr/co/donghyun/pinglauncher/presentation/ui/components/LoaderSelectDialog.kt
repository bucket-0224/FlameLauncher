package kr.co.donghyun.pinglauncher.presentation.ui.components

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.fabric.FabricLoaderEntry
import kr.co.donghyun.pinglauncher.presentation.util.fabric.FabricMetaAPI

private val Pink     = Color(0xFFE91E8C)
private val TextMain = Color(0xFFFCE4EC)
private val TextSub  = Color(0xFFBB86A0)

@Composable
fun LoaderSelectDialog(
    versionId: String,
    onDismiss: () -> Unit,
    onLaunchVanilla: () -> Unit,
    onLaunchFabric: (loaderVersion: String) -> Unit
) {
    var choice by remember { mutableStateOf("vanilla") }
    var fabricList by remember { mutableStateOf<List<FabricLoaderEntry>>(emptyList()) }
    var selectedLoader by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSnapshots by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadFabric() {
        if (fabricList.isNotEmpty() || loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { FabricMetaAPI().listLoaders(versionId) }
                fabricList = list
                selectedLoader = list.firstOrNull { it.loader.stable }?.loader?.version
                    ?: list.firstOrNull()?.loader?.version
            } catch (e: Exception) {
                error = e.message ?: "Fabric 로더 목록 불러오기 실패"
            }
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(BgSurface, RoundedCornerShape(16.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("로더 선택 — $versionId", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoaderTab(
                    label = "🌿 바닐라",
                    selected = choice == "vanilla",
                    modifier = Modifier.weight(1f)
                ) { choice = "vanilla" }
                LoaderTab(
                    label = "🧵 Fabric",
                    selected = choice == "fabric",
                    modifier = Modifier.weight(1f)
                ) {
                    choice = "fabric"
                    loadFabric()
                }
            }

            if (choice == "fabric") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("로더 버전", color = TextSub, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("스냅샷 포함", color = TextSub, fontSize = 14.sp)
                            Spacer(modifier = Modifier.padding(start = 12.dp))
                            Switch(
                                checked = showSnapshots,
                                onCheckedChange = { showSnapshots = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = Pink,
                                    uncheckedTrackColor = BgBorder
                                )
                            )
                        }
                    }
                    when {
                        loading -> Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                            CircularProgressIndicator(color = Pink, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                        error != null -> Text("❌ $error", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                        else -> {
                            val visible = fabricList.filter { showSnapshots || it.loader.stable }
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(visible, key = { it.loader.version }) { entry ->
                                    val isSel = selectedLoader == entry.loader.version
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) Color(0xFF2D0A20) else BgDark)
                                            .border(
                                                if (isSel) 1.5.dp else 1.dp,
                                                if (isSel) Pink else BgBorder,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedLoader = entry.loader.version }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(entry.loader.version, color = TextMain, fontSize = 13.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                        if (entry.loader.stable) {
                                            Text("stable", color = Pink, fontSize = 10.sp)
                                        } else {
                                            Text("snapshot", color = TextSub, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) { Text("취소", color = TextSub, fontSize = 13.sp) }
                Button(
                    onClick = {
                        if (choice == "vanilla") onLaunchVanilla()
                        else selectedLoader?.let { onLaunchFabric(it) }
                    },
                    enabled = choice == "vanilla" || selectedLoader != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Pink,
                        disabledContainerColor = BgBorder
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("▶ 실행", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LoaderTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PinkDark else BgDark)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) Pink else BgBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else TextSub,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}