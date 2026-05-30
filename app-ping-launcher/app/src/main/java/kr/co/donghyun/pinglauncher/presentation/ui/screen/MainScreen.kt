package kr.co.donghyun.pinglauncher.presentation.ui.screen

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.donghyun.pinglauncher.BuildConfig
import kr.co.donghyun.pinglauncher.data.mojang.DownloadPhase
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
import kr.co.donghyun.pinglauncher.data.mojang.VersionEntry
import kr.co.donghyun.pinglauncher.presentation.*
import kr.co.donghyun.pinglauncher.presentation.ui.components.LoaderSelectDialog
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.isVersionSupported
import java.net.URL

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
    onLaunchFabric: (VersionEntry, String) -> Unit,
    onOpenContents: () -> Unit,
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

    var showLoaderDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp)
        ) {
            // 상단 프로필 헤더
            ProfileHeader(
                isLoggedIn = isLoggedIn,
                username = username,
                uuid = uuid,
                onLogin = onLogin,
                onOpenContents = onOpenContents,
                onOpenKeySettings = onOpenKeySettings,
                onOpenJVMSettings = onOpenJVMSettings,
            )

            // 필터
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
                FilterChip(
                    selected = !showOnlyRelease,
                    onClick = { if (showOnlyRelease) onToggleFilter() },
                    label = { Text("전체", color = TextPrimary, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PinkDark,
                        containerColor = BgSurface
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = !showOnlyRelease,
                        borderColor = BgBorder,
                        selectedBorderColor = PinkPrimary
                    )
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    item { Box(modifier = Modifier.height(64.dp)) }
                }
            }
        }

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

        if (showLoaderDialog && selectedVersion != null) {
            LoaderSelectDialog(
                versionId = selectedVersion.id,
                onDismiss = { showLoaderDialog = false },
                onLaunchVanilla = {
                    showLoaderDialog = false
                    onDownloadAndPlay(selectedVersion)
                },
                onLaunchFabric = { loaderVersion ->
                    showLoaderDialog = false
                    onLaunchFabric(selectedVersion, loaderVersion)
                }
            )
        }
    }
}

@Composable
fun ProfileHeader(
    isLoggedIn: Boolean,
    username: String?,
    uuid: String?,
    onLogin: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenKeySettings: () -> Unit,
    onOpenJVMSettings: () -> Unit,
) {
    // 스킨 얼굴 이미지 로드
    var skinFace by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uuid) {
        skinFace = loadSkinFace(uuid)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF2D0A20), BgDark)
                )
            )
            .padding(top = 48.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
    ) {
        Column {
            // 프로필 행
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 스킨 얼굴
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A0A14))
                            .border(1.5.dp, PinkDark, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (skinFace != null) {
                            Image(
                                bitmap = skinFace!!,
                                contentDescription = "스킨",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        } else {
                            Text(
                                text = if (isLoggedIn) username?.take(1)?.uppercase() ?: "?" else "?",
                                color = PinkLight,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "🌸 PingLauncher",
                            color = PinkLight,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        if (isLoggedIn && username != null) {
                            Text(
                                text = username,
                                color = PinkPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Text(
                                text = "로그인 필요",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // 로그인 버튼 (미로그인 시)
                if (!isLoggedIn) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(PinkDark)
                            .border(1.dp, PinkPrimary, RoundedCornerShape(8.dp))
                            .clickable { onLogin() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("🔑 로그인", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 버튼 행
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "📦 추가 컨텐츠" to onOpenContents,
                    "🎮 키 설정" to onOpenKeySettings,
                    "⚙️ JVM" to onOpenJVMSettings,
                ).forEach { (label, action) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgSurface)
                            .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                            .clickable { action() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = PinkLight, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private suspend fun loadSkinFace(uuid: String?, size: Int = 64): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        // uuid가 null이거나 비어있다면 공식 스티브(Steve) UUID를 사용합니다.
        val cleanUuid = if (!uuid.isNullOrBlank()) {
            uuid.replace("-", "")
        } else {
            "MHF_Alex" // 모장 공식 알렉스 고유 닉네임 입력
        }

        // MC-Heads API 활용
        val urlString = "https://mc-heads.net/avatar/$cleanUuid/$size"
        val url = URL(urlString)
        val stream = url.openStream()
        val bitmap = BitmapFactory.decodeStream(stream)

        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        e.printStackTrace()
        null
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
                Text(text = typeLabel, color = typeColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }

            Column {
                Text(
                    text = version.id,
                    color = if (isSupported) TextPrimary else TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = version.releaseTime.take(10), color = TextSecondary, fontSize = 11.sp)
                    if (!isSupported) {
                        Text(text = "⚠ 미지원", color = Color(0xFFFF6B6B), fontSize = 11.sp)
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
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoggedIn: Boolean,
    username: String?,
    onLogin: () -> Unit,
    loginError: String?
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
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
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
            }

            Button(
                onClick = { selectedVersion?.let { onPlayClick() } },
                enabled = selectedVersion != null && !isDownloading && isSelectedSupported && (BuildConfig.DEBUG || isLoggedIn),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PinkPrimary,
                    disabledContainerColor = BgBorder
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(44.dp)
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("▶  Play", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}