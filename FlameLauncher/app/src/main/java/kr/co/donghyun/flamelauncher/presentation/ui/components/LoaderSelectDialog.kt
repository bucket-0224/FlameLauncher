package kr.co.donghyun.flamelauncher.presentation.ui.components

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
import kr.co.donghyun.flamelauncher.presentation.ui.theme.*
import kr.co.donghyun.flamelauncher.presentation.util.fabric.FabricLoaderEntry
import kr.co.donghyun.flamelauncher.presentation.util.fabric.FabricMetaAPI
import kr.co.donghyun.flamelauncher.presentation.util.forge.ForgeLoaderEntry
import kr.co.donghyun.flamelauncher.presentation.util.forge.ForgeMetaAPI
import kr.co.donghyun.flamelauncher.presentation.util.forge.NeoForgeMetaAPI
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kr.co.donghyun.flamelauncher.R

@Composable
fun LoaderSelectDialog(
    versionId: String,
    onDismiss: () -> Unit,
    onLaunchVanilla: () -> Unit,
    onLaunchFabric: (loaderVersion: String) -> Unit,
    onLaunchForge:  (forgeVersion: String) -> Unit,
    onLaunchNeoForge: (neoforgeVersion: String) -> Unit,   // ★ 추가
) {
    var choice by remember { mutableStateOf("vanilla") }
    val tablet = isTablet()

    // ─── Fabric ───
    var fabricList     by remember { mutableStateOf<List<FabricLoaderEntry>>(emptyList()) }
    var selectedLoader by remember { mutableStateOf<String?>(null) }
    var showSnapshots  by remember { mutableStateOf(false) }

    // ─── Forge ───
    var forgeList     by remember { mutableStateOf<List<ForgeLoaderEntry>>(emptyList()) }
    var selectedForge by remember { mutableStateOf<String?>(null) }
    var showAllForge  by remember { mutableStateOf(false) }

    // ─── NeoForge ───
    var neoforgeList     by remember { mutableStateOf<List<ForgeLoaderEntry>>(emptyList()) }
    var selectedNeoForge by remember { mutableStateOf<String?>(null) }
    var showAllNeoForge  by remember { mutableStateOf(false) }

    var loading by remember { mutableStateOf(false) }
    var error   by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadFabric() {
        if (fabricList.isNotEmpty() || loading) return
        loading = true; error = null
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

    fun loadNeoForge() {
        if (neoforgeList.isNotEmpty() || loading) return
        loading = true; error = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { NeoForgeMetaAPI().listLoaders(versionId) }
                if (list.isEmpty()) {
                    error = "$versionId 에 사용 가능한 NeoForge 빌드가 없음 (1.20.1 은 별도 fork)"
                } else {
                    neoforgeList = list
                    selectedNeoForge = list.firstOrNull { it.recommended }?.forgeVersion
                        ?: list.firstOrNull { it.latest }?.forgeVersion
                                ?: list.first().forgeVersion
                }
            } catch (e: Exception) {
                error = e.message ?: "NeoForge 로더 목록 불러오기 실패"
            }
            loading = false
        }
    }

    fun loadForge() {
        if (forgeList.isNotEmpty() || loading) return
        loading = true; error = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { ForgeMetaAPI().listLoaders(versionId) }
                if (list.isEmpty()) {
                    error = "$versionId 에 사용 가능한 Forge 빌드가 없음"
                } else {
                    forgeList = list
                    // 기본 선택: recommended → latest → 첫 항목
                    selectedForge = list.firstOrNull { it.recommended }?.forgeVersion
                        ?: list.firstOrNull { it.latest }?.forgeVersion
                                ?: list.first().forgeVersion
                }
            } catch (e: Exception) {
                error = e.message ?: "Forge 로더 목록 불러오기 실패"
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
            Text("로더 선택 — $versionId", color = TextMain, fontSize = if(tablet) 17.sp else 13.sp, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LoaderTab(
                    label = "바닐라",
                    iconRes = R.drawable.img_minecraft,
                    selected = choice == "vanilla",
                    modifier = Modifier.weight(1f)
                ) { choice = "vanilla"; error = null }
                LoaderTab(
                    label = "Fabric",
                    iconRes = R.drawable.img_loader_fabric,
                    selected = choice == "fabric",
                    modifier = Modifier.weight(1f)
                ) { choice = "fabric"; error = null; loadFabric() }
                LoaderTab(
                    label = "Forge",
                    iconRes = R.drawable.img_anvil,
                    selected = choice == "forge",
                    modifier = Modifier.weight(1f),
                    accent = Orange
                ) { choice = "forge"; error = null; loadForge() }
                LoaderTab(
                    label = "NeoForge",
                    iconRes = R.drawable.img_loader_neoforge,
                    selected = choice == "neoforge",
                    modifier = Modifier.weight(1f),
                    accent = Color(0xFF4CAF50)
                ) { choice = "neoforge"; error = null; loadNeoForge() }
            }

            // ─── NeoForge 패널 ───
            if (choice == "neoforge") {
                val NeoGreen = Color(0xFF4CAF50)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NeoForge 빌드", color = TextSub, fontSize = if(tablet) 14.sp else 10.sp)
                    when {
                        loading -> CenterSpinner(NeoGreen)
                        error != null -> ErrorText(error!!)
                        else -> {
                            val visible =
                                if (showAllNeoForge) neoforgeList
                                else neoforgeList.filter { it.recommended || it.latest }
                                    .ifEmpty { neoforgeList.take(5) }

                            LazyColumn(
                                modifier = Modifier.heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(visible, key = { it.forgeVersion }) { entry ->
                                    val isSel = selectedNeoForge == entry.forgeVersion
                                    val label = when {
                                        entry.recommended && entry.latest -> "recommended·latest"
                                        entry.recommended -> "recommended"
                                        entry.latest -> "latest"
                                        else -> ""
                                    }
                                    LoaderRow(
                                        version = entry.forgeVersion,
                                        rightLabel = label,
                                        rightColor = if (entry.recommended || entry.latest) NeoGreen else TextSub,
                                        selected = isSel,
                                        accent = NeoGreen
                                    ) { selectedNeoForge = entry.forgeVersion }
                                }
                            }
                        }
                    }
                }
            }

            // ─── Fabric 패널 ───
            if (choice == "fabric") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("로더 버전", color = TextSub, fontSize = if(tablet) 12.sp else 8.sp)
                    }
                    when {
                        loading -> CenterSpinner(Flame)
                        error != null -> ErrorText(error!!)
                        else -> {
                            val visible = fabricList.filter { showSnapshots || it.loader.stable }
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(visible, key = { it.loader.version }) { entry ->
                                    val isSel = selectedLoader == entry.loader.version
                                    LoaderRow(
                                        version = entry.loader.version,
                                        rightLabel = if (entry.loader.stable) "stable" else "snapshot",
                                        rightColor = if (entry.loader.stable) Flame else TextSub,
                                        selected = isSel,
                                        accent = Flame
                                    ) { selectedLoader = entry.loader.version }
                                }
                            }
                        }
                    }
                }
            }

            // ─── Forge 패널 ───
            if (choice == "forge") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Forge 빌드", color = TextSub, fontSize = if(tablet) 14.sp else 10.sp)
                    }
                    when {
                        loading -> CenterSpinner(Orange)
                        error != null -> ErrorText(error!!)
                        else -> {
                            // 기본은 recommended/latest 만, 토글하면 전체
                            val visible = (             if (showAllForge) forgeList
                            else forgeList.filter { it.recommended || it.latest }
                                .ifEmpty { forgeList.take(5) }).reversed()

                            LazyColumn(
                                modifier = Modifier.heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(visible, key = { it.forgeVersion }) { entry ->
                                    val isSel = selectedForge == entry.forgeVersion
                                    val label = when {
                                        entry.recommended && entry.latest -> "recommended·latest"
                                        entry.recommended -> "recommended"
                                        entry.latest -> "latest"
                                        else -> ""
                                    }
                                    LoaderRow(
                                        version = entry.forgeVersion,
                                        rightLabel = label,
                                        rightColor = if (entry.recommended || entry.latest) Orange else TextSub,
                                        selected = isSel,
                                        accent = Orange
                                    ) { selectedForge = entry.forgeVersion }
                                }
                            }
                        }
                    }
                }
            }

            // ─── 액션 버튼 ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) { Text("취소", color = TextSub, fontSize = if(tablet) 13.sp else 9.sp) }
                val canRun = when (choice) {
                    "vanilla"  -> true
                    "fabric"   -> selectedLoader != null
                    "forge"    -> selectedForge  != null
                    "neoforge" -> selectedNeoForge != null
                    else -> false
                }
                Button(
                    onClick = {
                        when (choice) {
                            "vanilla"  -> onLaunchVanilla()
                            "fabric"   -> selectedLoader?.let(onLaunchFabric)
                            "forge"    -> selectedForge?.let(onLaunchForge)
                            "neoforge" -> selectedNeoForge?.let(onLaunchNeoForge)
                        }
                    },
                    enabled = canRun,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (choice) {
                            "forge"    -> Orange
                            "neoforge" -> Color(0xFF4CAF50)
                            else       -> Flame
                        },
                        disabledContainerColor = BgBorder
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("▶ 실행", color = Color.White, fontSize = if(tablet) 13.sp else 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LoaderTab(
    label: String,
    iconRes: Int,
    selected: Boolean,
    modifier: Modifier,
    accent: Color = Flame,
    onClick: () -> Unit,
) {
    val tablet = isTablet()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) FlameDark else BgDark)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) accent else BgBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = if(tablet) 12.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (tablet) 8.dp else 4.dp)
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(if (tablet) 24.dp else 18.dp)
            )
            Text(
                label,
                color = if (selected) Color.White else TextSub,
                fontSize = if(tablet) 13.sp else 9.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun LoaderRow(
    version: String,
    rightLabel: String,
    rightColor: Color,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val tablet = isTablet()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF2D0A20) else BgDark)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) accent else BgBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            version, color = TextMain, fontSize = if(tablet) 13.sp else 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        if (rightLabel.isNotEmpty()) {
            Text(rightLabel, color = rightColor, fontSize = if(tablet) 10.sp else 6.sp)
        }
    }
}

@Composable
private fun CenterSpinner(color: Color) {
    Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
        CircularProgressIndicator(color = color, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ErrorText(msg: String) {
    Text("❌ $msg", color = Color(0xFFFF6B6B), fontSize = 12.sp)
}