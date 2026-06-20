package kr.co.donghyun.pinglauncher.presentation.ui.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.BuildConfig
import kr.co.donghyun.pinglauncher.data.instance.InstanceMeta
import kr.co.donghyun.pinglauncher.data.mojang.DownloadPhase
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
import kr.co.donghyun.pinglauncher.data.mojang.VersionEntry
import kr.co.donghyun.pinglauncher.presentation.ui.components.LoaderSelectDialog
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.isVersionSupported
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet
import java.net.URL

enum class MainTab(val label: String) {
    INSTALLED("📦 설치됨"),
    RELEASE("🌿 정식 출시"),
    ALL("📜 전체"),
}

@Composable
fun MainScreen(
    versions: List<VersionEntry>,
    instances: List<InstanceMeta>,
    progress: DownloadProgress,
    selectedVersion: VersionEntry?,
    isLoading: Boolean,
    onVersionSelect: (VersionEntry) -> Unit,
    onDownloadAndPlay: (VersionEntry) -> Unit,
    onLaunchFabric: (VersionEntry, String) -> Unit,
    onLaunchForge: (VersionEntry, String) -> Unit,
    onLaunchNeoForge: (VersionEntry, String) -> Unit,
    onLaunchInstance: (InstanceMeta) -> Unit,
    onOpenInstanceSettings: (InstanceMeta) -> Unit,
    onOpenContents: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenKeySettings: () -> Unit,
    onOpenJVMSettings: () -> Unit,
    onOpenRendererSettings: () -> Unit,
    uuid: String?,
    isLoggedIn: Boolean,
    username: String?,
    onLogin: () -> Unit,
    loginError: String? = null,
) {
    val isDownloading = progress.phase != DownloadPhase.IDLE &&
            progress.phase != DownloadPhase.DONE &&
            progress.phase != DownloadPhase.ERROR

    // 인스턴스가 있으면 INSTALLED 가 기본, 없으면 RELEASE
    var selectedTab by remember(instances.isEmpty()) {
        mutableStateOf(if (instances.isNotEmpty()) MainTab.INSTALLED else MainTab.RELEASE)
    }

    val filteredVersions = when (selectedTab) {
        MainTab.RELEASE -> versions.filter { it.type == "release" }
        MainTab.ALL     -> versions
        MainTab.INSTALLED -> emptyList()
    }

    var showLoaderDialog by remember { mutableStateOf(false) }
    val tablet = isTablet()

    Box(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Column(modifier = Modifier.fillMaxSize().padding(bottom = if (tablet) 0.dp else 40.dp)) {
            ProfileHeader(
                isLoggedIn, username, uuid, onLogin,
                onOpenContents, onOpenKeySettings, onOpenNetworkSettings, onOpenJVMSettings, onOpenRendererSettings
            )

            // ── 3-way 탭 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MainTab.entries.forEach { tab ->
                    val sel = selectedTab == tab
                    val count = when (tab) {
                        MainTab.INSTALLED -> instances.size
                        MainTab.RELEASE   -> versions.count { it.type == "release" }
                        MainTab.ALL       -> versions.size
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) PinkDark else BgSurface)
                            .border(
                                if (sel) 1.5.dp else 1.dp,
                                if (sel) PinkPrimary else BgBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { selectedTab = tab }
                            .padding(vertical = if (tablet) 10.dp else 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (count > 0) "${tab.label} ($count)" else tab.label,
                            color = if (sel) Color.White else TextSecondary,
                            fontSize = if (tablet) 13.sp else 10.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            if (isLoading && selectedTab != MainTab.INSTALLED) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PinkPrimary)
                }
            } else if (tablet) {
                Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                    Box(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                        when (selectedTab) {
                            MainTab.INSTALLED -> InstancesList(isLoggedIn, instances, onLaunchInstance, onOpenInstanceSettings)
                            else -> VersionsList(filteredVersions, selectedVersion, onVersionSelect)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxHeight()
                            .background(BgSurface)
                            .border(1.dp, BgBorder)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (selectedTab == MainTab.INSTALLED) {
                            InstalledPanel(instances.size)
                        } else {
                            SidePlayPanel(
                                selectedVersion = selectedVersion,
                                progress = progress,
                                isDownloading = isDownloading,
                                isLoggedIn = isLoggedIn,
                                onPlayClick = { showLoaderDialog = true },
                                onLogin = onLogin
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(20.dp))
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (selectedTab) {
                        MainTab.INSTALLED -> InstancesList(isLoggedIn, instances, onLaunchInstance, onOpenInstanceSettings)
                        else -> VersionsList(filteredVersions, selectedVersion, onVersionSelect)
                    }
                }
            }
        }

        // 폰: 하단 패널은 INSTALLED 탭이 아닐 때만
        if (!tablet && selectedTab != MainTab.INSTALLED) {
            BottomPanel(
                selectedVersion = selectedVersion,
                progress = progress,
                isDownloading = isDownloading,
                onPlayClick = { showLoaderDialog = true },
                modifier = Modifier.align(Alignment.BottomCenter),
                username = username,
                isLoggedIn = isLoggedIn,
                onLogin = onLogin,
                loginError = loginError
            )
        }

        if (showLoaderDialog && selectedVersion != null) {
            LoaderSelectDialog(
                versionId = selectedVersion.id,
                onDismiss = { showLoaderDialog = false },
                onLaunchVanilla = { showLoaderDialog = false; onDownloadAndPlay(selectedVersion) },
                onLaunchFabric  = { v -> showLoaderDialog = false; onLaunchFabric(selectedVersion, v) },
                onLaunchForge   = { v -> showLoaderDialog = false; onLaunchForge(selectedVersion, v) },
                onLaunchNeoForge= { v -> showLoaderDialog = false; onLaunchNeoForge(selectedVersion, v) },
            )
        }
    }
}

@Composable
private fun VersionsList(
    versions: List<VersionEntry>,
    selectedVersion: VersionEntry?,
    onVersionSelect: (VersionEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(versions) { v ->
            VersionItem(v, selectedVersion?.id == v.id) { onVersionSelect(v) }
        }
        item { Box(modifier = Modifier.height(64.dp)) }
    }
}

@Composable
private fun InstancesList(
    isLoggedIn: Boolean,
    instances: List<InstanceMeta>,
    onLaunch: (InstanceMeta) -> Unit,
    onOpenSettings: (InstanceMeta) -> Unit,
) {
    if (instances.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📭", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "설치된 인스턴스가 없습니다",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "버전 탭에서 다운로드하거나, 추가 컨텐츠에서 모드팩을 설치하세요",
                    color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(instances, key = { it.id }) { meta ->
            InstanceItem(
                meta,
                isLoggedIn = isLoggedIn,
                onLaunch = { onLaunch(meta) },
                onOpenSettings = { onOpenSettings(meta) },
            )
        }
        item { Box(modifier = Modifier.height(64.dp)) }
    }
}

@Composable
private fun InstanceItem(
    meta: InstanceMeta,
    onLaunch: () -> Unit,
    isLoggedIn: Boolean,
    onOpenSettings: () -> Unit,
) {
    val tablet = isTablet()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = if (tablet) 14.dp else 10.dp, vertical = if (tablet) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(meta.iconEmoji, fontSize = if (tablet) 28.sp else 22.sp)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                meta.name,
                color = TextPrimary,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val loaderLabel = when (meta.loaderType?.lowercase()) {
                "fabric"   -> "Fabric ${meta.loaderVersion ?: ""}"
                "forge"    -> "Forge ${meta.loaderVersion ?: ""}"
                "neoforge" -> "NeoForge ${meta.loaderVersion ?: ""}"
                else       -> "Vanilla"
            }
            Text(
                "MC ${meta.mcVersion} · $loaderLabel",
                color = TextSecondary,
                fontSize = if (tablet) 11.sp else 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 설정 (맵/리소스팩 가져오기, 삭제 등)
        Box(
            modifier = Modifier
                .size(if (tablet) 32.dp else 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(BgDark)
                .border(1.dp, BgBorder, RoundedCornerShape(6.dp))
                .clickable { onOpenSettings() },
            contentAlignment = Alignment.Center,
        ) {
            Text("⚙️", fontSize = if (tablet) 14.sp else 12.sp)
        }

        // 실행
        Button(
            onClick = onLaunch,
            enabled = isLoggedIn,
            colors = ButtonDefaults.buttonColors(
                containerColor = PinkPrimary,
                disabledContainerColor = BgBorder
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            modifier = Modifier.height(if (tablet) 36.dp else 30.dp),
        ) {
            Text(
                "▶ 실행",
                color = Color.White,
                fontSize = if (tablet) 12.sp else 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun InstalledPanel(count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("설치된 인스턴스", color = TextSecondary, fontSize = 12.sp)
        Text(
            "${count}개",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "좌측 목록에서 ▶ 실행을 눌러 바로 시작할 수 있습니다.",
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

/** 태블릿 우측 패널 — 큰 Play 버튼과 진행률을 한 곳에. */
@Composable
private fun SidePlayPanel(
    selectedVersion: VersionEntry?,
    progress: DownloadProgress,
    isDownloading: Boolean,
    isLoggedIn: Boolean,
    onPlayClick: () -> Unit,
    onLogin: () -> Unit,
) {
    val isSupported = selectedVersion?.let { isVersionSupported(it.id) } == true
    val tablet = isTablet()

    // 💡 전체 요소를 Column으로 감싸줍니다.
    // 여기에 modifier = Modifier.fillMaxHeight() 등을 추가하여 높이를 꽉 채워야 Spacer가 정상 작동합니다.
    Column {
        Column(modifier = Modifier.fillMaxHeight()) {

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("선택된 버전", color = TextSecondary, fontSize = 12.sp)
                Text(
                    text = selectedVersion?.id ?: "버전을 선택하세요",
                    color = if (selectedVersion != null) TextPrimary else TextSecondary,
                    fontSize = if(tablet) 28.sp else 24.sp,
                    fontWeight = FontWeight.Bold
                )
                if (selectedVersion != null) {
                    Text(
                        text = if (isSupported) selectedVersion.type else "${selectedVersion.type} · 미지원",
                        color = if (isSupported) TextSecondary else Color(0xFFFF6B6B),
                        fontSize = 13.sp
                    )
                }
            }

            AnimatedVisibility(visible = isDownloading || progress.phase == DownloadPhase.ERROR) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (progress.phase) {
                                DownloadPhase.FETCHING_MANIFEST -> "버전 정보 가져오는 중..."
                                DownloadPhase.DOWNLOADING_CLIENT -> "클라이언트 다운로드 중..."
                                DownloadPhase.DOWNLOADING_LIBRARIES -> "라이브러리 (${progress.current}/${progress.total})"
                                DownloadPhase.DOWNLOADING_ASSETS -> "에셋 (${progress.current}/${progress.total})"
                                DownloadPhase.ERROR -> "❌ ${progress.error ?: "오류"}"
                                else -> ""
                            },
                            color = if (progress.phase == DownloadPhase.ERROR) Color(0xFFFF6B6B) else TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f) // Row 내부이므로 정상 작동
                        )
                        if (isDownloading) {
                            Text("${progress.percent}%", color = PinkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (isDownloading) {
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = PinkPrimary,
                            trackColor = BgBorder,
                        )
                    }
                }
            }

            // 💡 이제 ColumnScope 내부이므로 weight가 올바르게 인식됩니다.
            Spacer(modifier = Modifier.weight(1f, fill = true))

            if(!isLoggedIn) {
                Text("로그인이 필요합니다.",
                    color = TextSecondary,
                    fontSize = if (isTablet()) 14.sp else 10.sp, fontWeight = FontWeight.Normal)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onPlayClick,
                enabled = selectedVersion != null && !isDownloading && isSupported && isLoggedIn,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PinkPrimary,
                    disabledContainerColor = BgBorder
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text("▶  Play", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }

            if (!isLoggedIn && !BuildConfig.DEBUG) {
                // 버튼 간의 간격이 필요하다면 여기에 Spacer나 패딩을 추가할 수 있습니다.
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onLogin,
                    colors = ButtonDefaults.buttonColors(containerColor = PinkDark),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("🔑 로그인 필요", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}


// ProfileHeader, VersionItem, BottomPanel, loadSkinFace 는 기존 코드 그대로 유지
// (위에서 import만 추가했고 컴포저블 본문은 손대지 않았습니다)

@Composable
fun ProfileHeader(
    isLoggedIn: Boolean,
    username: String?,
    uuid: String?,
    onLogin: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenKeySettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenJVMSettings: () -> Unit,
    onOpenRendererSettings: () -> Unit,
) {
    // ── 이하 기존 ProfileHeader 본문 그대로 ──
    var skinFace by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uuid) { skinFace = loadSkinFace(uuid) }
    val tablet = isTablet()

    Box(
        modifier = Modifier.fillMaxWidth().background(
            Brush.verticalGradient(colors = listOf(Color(0xFF2D0A20), BgDark))
        ).padding(top = if(tablet) 48.dp else 24.dp, bottom = if(tablet) 12.dp else 0.dp, start = 16.dp, end = 16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(if(tablet) 48.dp else 36.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A0A14))
                            .border(1.5.dp, PinkDark, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (skinFace != null) {
                            Image(bitmap = skinFace!!, contentDescription = "스킨",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        } else {
                            Text(if (isLoggedIn) username?.take(1)?.uppercase() ?: "?" else "?",
                                color = PinkLight, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                    Column {
                        Text("🌸 PingLauncher", color = PinkLight, fontSize = if (tablet) 18.sp else 14.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        if (isLoggedIn && username != null) {
                            Text(username, color = PinkPrimary, fontSize = if (tablet) 13.sp else 11.sp, fontWeight = FontWeight.Medium)
                        } else {
                            Text("로그인 필요", color = TextSecondary, fontSize = if (tablet) 12.sp else 8.sp,)
                        }
                    }
                }
                if (!isLoggedIn) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(PinkDark)
                            .border(1.dp, PinkPrimary, RoundedCornerShape(8.dp))
                            .clickable { onLogin() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("🔑 로그인", color = Color.White, fontSize = if (tablet) 12.sp else 8.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(modifier = Modifier.height(if(tablet) 12.dp else 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "📦 추가 컨텐츠" to onOpenContents,
                    "🎮 키 설정" to onOpenKeySettings,
                    "⚙️ JVM" to onOpenJVMSettings,
                    "🌐 네트워크" to onOpenNetworkSettings,
                ).forEach { (label, action) ->
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(BgSurface)
                            .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                            .clickable { action() }
                            .padding(horizontal = 8.dp, vertical = if(tablet) 6.dp else 4.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label, color = PinkLight, fontSize = if (tablet) 11.sp else 7.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

private suspend fun loadSkinFace(uuid: String?, size: Int = 64): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val cleanUuid = if (!uuid.isNullOrBlank()) uuid.replace("-", "") else "MHF_Alex"
        val url = URL("https://mc-heads.net/avatar/$cleanUuid/$size")
        BitmapFactory.decodeStream(url.openStream())?.asImageBitmap()
    } catch (e: Exception) { e.printStackTrace(); null }
}

@Composable
fun VersionItem(version: VersionEntry, isSelected: Boolean, onClick: () -> Unit) {
    val tablet = isTablet()
    val typeColor = when (version.type) {
        "release" -> TagRelease
        "snapshot" -> TagSnapshot
        else -> TagOld
    }
    val typeLabel = when (version.type) {
        "release" -> "Release"
        "snapshot" -> "Snapshot"
        "old_beta" -> "Beta"
        else -> "Alpha"
    }
    val isSupported = remember(version.id) { isVersionSupported(version.id) }

    // ── 폰/태블릿 별 디멘션 ─────────────────────────────────
    val idSize       = if (tablet) 14.sp else 10.sp
    val subSize      = if (tablet)  11.sp else 7.sp
    val typeBadgeSize= if (tablet)  11.sp else 7.sp
    val checkSize    = if (tablet) 18.sp else 14.sp
    val padH         = if (tablet) 14.dp else 10.dp
    val padV         = if (tablet)  10.dp else 6.dp
    val rowGap       = if (tablet)  12.dp else 8.dp
    val badgePadH    = if (tablet)  10.dp else  8.dp
    val badgePadV    = if (tablet)  3.dp else  0.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) Color(0xFF2D0A20)
                else BgSurface.copy(alpha = if (isSupported) 1f else 0.6f)
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) PinkPrimary else BgBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = padH, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rowGap)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(typeColor.copy(alpha = 0.15f))
                    .border(1.dp, typeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = badgePadH, vertical = badgePadV)
            ) {
                Text(typeLabel, color = typeColor,
                    fontSize = typeBadgeSize, fontWeight = FontWeight.Medium)
            }
            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
                Text(
                    text = version.id,
                    color = if (isSupported) TextPrimary else TextSecondary,
                    fontSize = idSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(version.releaseTime.take(10),
                        color = TextSecondary, fontSize = subSize)
                    if (!isSupported) {
                        Text("⚠ 미지원", color = Color(0xFFFF6B6B), fontSize = subSize)
                    }
                }
            }
        }
        if (isSelected) {
            Text("✓", color = PinkPrimary, fontSize = checkSize, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BottomPanel(
    selectedVersion: VersionEntry?,
    progress: DownloadProgress,
    isDownloading: Boolean,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoggedIn: Boolean,
    username: String?,
    onLogin: () -> Unit,
    loginError: String?
) {
    // ── 기존 BottomPanel 본문 그대로 ──
    val isSelectedSupported = selectedVersion?.let { isVersionSupported(it.id) } == true
    Column(
        modifier = modifier.fillMaxWidth().background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedVisibility(visible = isDownloading || progress.phase == DownloadPhase.ERROR) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = when (progress.phase) {
                            DownloadPhase.FETCHING_MANIFEST -> "버전 정보 가져오는 중..."
                            DownloadPhase.DOWNLOADING_CLIENT -> "클라이언트 다운로드 중..."
                            DownloadPhase.DOWNLOADING_LIBRARIES -> "라이브러리 (${progress.current}/${progress.total})"
                            DownloadPhase.DOWNLOADING_ASSETS -> "에셋 (${progress.current}/${progress.total})"
                            DownloadPhase.ERROR -> "❌ ${progress.error ?: "오류"}"
                            else -> ""
                        },
                        color = if (progress.phase == DownloadPhase.ERROR) Color(0xFFFF6B6B) else TextSecondary,
                        fontSize = if (isTablet()) 12.sp else 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isDownloading) {
                        Text("${progress.percent}%", color = PinkPrimary,  fontSize = if (isTablet()) 12.sp else 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = PinkPrimary, trackColor = BgBorder,
                    )
                    if (progress.fileName.isNotEmpty()) {
                        Text(progress.fileName, color = TextSecondary,  fontSize = if (isTablet()) 11.sp else 7.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                if(!isLoggedIn) {
                    Text("로그인이 필요합니다.",
                        color = TextSecondary,
                        fontSize = if (isTablet()) 14.sp else 10.sp, fontWeight = FontWeight.Normal)
                }
                Text(selectedVersion?.id ?: "버전을 선택하세요",
                    color = if (selectedVersion != null) TextPrimary else TextSecondary,
                    fontSize = if (isTablet()) 15.sp else 11.sp, fontWeight = FontWeight.SemiBold)
                if (selectedVersion != null) {
                    Text(if (isSelectedSupported) selectedVersion.type else "${selectedVersion.type} · 미지원",
                        color = if (isSelectedSupported) TextSecondary else Color(0xFFFF6B6B),  fontSize = if (isTablet()) 12.sp else 8.sp)
                }
            }
            Button(
                onClick = { selectedVersion?.let { onPlayClick() } },
                enabled = selectedVersion != null && !isDownloading && isSelectedSupported && (BuildConfig.DEBUG || isLoggedIn),
                colors = ButtonDefaults.buttonColors(containerColor = PinkPrimary, disabledContainerColor = BgBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(if(isTablet()) 44.dp else 36.dp)
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("▶  Play", color = Color.White, fontWeight = FontWeight.Bold,  fontSize = if (isTablet()) 15.sp else 11.sp)
                }
            }
        }
    }
}