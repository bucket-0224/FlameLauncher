package kr.co.donghyun.pinglauncher.presentation.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.pinglauncher.data.mojang.DownloadPhase
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
import kr.co.donghyun.pinglauncher.data.mojang.VersionEntry
import kr.co.donghyun.pinglauncher.presentation.*
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.isVersionSupported

@Composable
fun MainScreen(
    versions: List<VersionEntry>,
    progress: DownloadProgress,
    selectedVersion: VersionEntry?,
    isLoading: Boolean,
    showOnlyRelease: Boolean,
    onVersionSelect: (VersionEntry) -> Unit,
    onToggleFilter: () -> Unit,
    onDownloadAndPlay: (VersionEntry) -> Unit,
    onOpenModPacks: () -> Unit,
    onOpenKeySettings: () -> Unit,
    onOpenJVMSettings: () -> Unit,
    isLoggedIn: Boolean,        // ← 추가
    username: String?,          // ← 추가
    onLogin: () -> Unit,        // ← 추가
    loginError: String? = null,
) {
    val isDownloading = progress.phase != DownloadPhase.IDLE &&
            progress.phase != DownloadPhase.DONE &&
            progress.phase != DownloadPhase.ERROR

    val filtered = if (showOnlyRelease)
        versions.filter { it.type == "release" }
    else versions

    // 전체를 Box로 감싸서 BottomPanel을 하단에 고정
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // 스크롤 가능한 상단 영역
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp)  // BottomPanel 높이만큼 패딩
        ) {
            Header(onOpenModPacks, onOpenKeySettings, onOpenJVMSettings)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = showOnlyRelease,
                    onClick = { if (!showOnlyRelease) onToggleFilter() },
                    label = { Text("정식 출시", color = TextPrimary, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PinkDark,
                        containerColor = BgSurface
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = showOnlyRelease,
                        borderColor = BgBorder,
                        selectedBorderColor = PinkPrimary
                    )
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = PinkPrimary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered) { version ->
                        VersionItem(
                            version = version,
                            isSelected = selectedVersion?.id == version.id,
                            onClick = { onVersionSelect(version) }
                        )
                    }
                    item {
                        Box(modifier = Modifier.height(64.dp))
                    }
                }
            }
        }

        // BottomPanel 하단 고정
        BottomPanel(
            selectedVersion = selectedVersion,
            progress = progress,
            isDownloading = isDownloading,
            onDownloadAndPlay = onDownloadAndPlay,
            modifier = Modifier.align(Alignment.BottomCenter),
            username = username,
            isLoggedIn = isLoggedIn,
            onLogin = onLogin,
            loginError = loginError
        )
    }
}

@Composable
fun Header(onOpenModPacks: () -> Unit, onOpenKeySettings: () -> Unit, onOpenJVMSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF2D0A20), BgDark)
                )
            )
            .padding(top = 56.dp, bottom = 12.dp, start = 20.dp, end = 12.dp)
    ) {
        Column(modifier = Modifier.align(Alignment.CenterStart)) {
            Text(
                text = "🌸 PingLauncher",
                color = PinkLight,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = "Minecraft Java Edition",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // is disabled now;
//            Box(
//                modifier = Modifier
//                    .clip(RoundedCornerShape(8.dp))
//                    .background(BgSurface)
//                    .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
//                    .clickable { onOpenModPacks() }
//                    .padding(horizontal = 10.dp, vertical = 6.dp),
//                contentAlignment = Alignment.Center
//            ) {
//                Text("📦 모드팩", color = PinkLight, fontSize = 12.sp, fontWeight = FontWeight.Medium)
//            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                    .clickable { onOpenKeySettings() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("🎮 키 설정", color = PinkLight, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                    .clickable { onOpenJVMSettings() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("⚙️ JVM 인자 설정", color = PinkLight, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun VersionItem(
    version: VersionEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(typeColor.copy(alpha = 0.15f))
                    .border(1.dp, typeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = typeLabel,
                    color = typeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Column {
                Text(
                    text = version.id,
                    color = if (isSupported) TextPrimary else TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = version.releaseTime.take(10),
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    if (!isSupported) {
                        Text(
                            text = "⚠ 미지원",
                            color = Color(0xFFFF6B6B),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        if (isSelected) {
            Text("✓", color = PinkPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BottomPanel(
    selectedVersion: VersionEntry?,
    progress: DownloadProgress,
    isDownloading: Boolean,
    onDownloadAndPlay: (VersionEntry) -> Unit,
    modifier: Modifier = Modifier,
    isLoggedIn: Boolean,
    username: String?,
    onLogin: () -> Unit,
    loginError : String?
) {
    val isSelectedSupported = selectedVersion?.let { isVersionSupported(it.id) } ?: false

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgSurface)
            .border(width = 1.dp, color = BgBorder, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                            DownloadPhase.DOWNLOADING_LIBRARIES -> "라이브러리 다운로드 중... (${progress.current}/${progress.total})"
                            DownloadPhase.DOWNLOADING_ASSETS -> "에셋 다운로드 중... (${progress.current}/${progress.total})"
                            DownloadPhase.ERROR -> "❌ ${progress.error ?: "오류 발생"}"
                            else -> ""
                        },
                        color = if (progress.phase == DownloadPhase.ERROR) Color(0xFFFF6B6B) else TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isDownloading) {
                        Text(
                            text = "${progress.percent}%",
                            color = PinkPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = PinkPrimary,
                        trackColor = BgBorder,
                    )
                    if (progress.fileName.isNotEmpty()) {
                        Text(
                            text = progress.fileName,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                Text(
                    text = selectedVersion?.id ?: "버전을 선택하세요",
                    color = if (selectedVersion != null) TextPrimary else TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (selectedVersion != null) {
                    Text(
                        text = if (isSelectedSupported) selectedVersion.type else "${selectedVersion.type} · 미지원",
                        color = if (isSelectedSupported) TextSecondary else Color(0xFFFF6B6B),
                        fontSize = 12.sp
                    )
                }
                if (isLoggedIn && username != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = username,
                        color = PinkPrimary,
                        fontSize = 11.sp
                    )
                }
            }

            if (!isLoggedIn) {
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = onLogin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PinkPrimary,
                            disabledContainerColor = BgBorder
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(
                            text = "🔑 로그인",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    if (loginError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "❌ $loginError",
                            color = Color(0xFFFF6B6B),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            } else {
                Button(
                    onClick = { selectedVersion?.let { onDownloadAndPlay(it) } },
                    enabled = selectedVersion != null && !isDownloading && isSelectedSupported,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PinkPrimary,
                        disabledContainerColor = BgBorder
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "▶  Play",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}