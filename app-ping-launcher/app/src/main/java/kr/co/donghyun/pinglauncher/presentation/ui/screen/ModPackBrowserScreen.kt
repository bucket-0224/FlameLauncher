package kr.co.donghyun.pinglauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.co.donghyun.pinglauncher.data.curseforge.CurseForgeMod
import kr.co.donghyun.pinglauncher.data.mojang.DownloadProgress
import kr.co.donghyun.pinglauncher.presentation.ModPackDetailActivity
import kr.co.donghyun.pinglauncher.presentation.ui.theme.*
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet

@Composable
fun ModPackBrowserScreen(
    modpacks: List<CurseForgeMod>,
    progress: DownloadProgress,
    isLoading: Boolean,
    isInstalling: Boolean,
    installingModId: Int?,
    statusMessage: String,
    selectedVersion: String,
    installedIds: Set<Int>,
    onSearch: (String, String) -> Unit,
    onVersionFilter: (String) -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    onInstall: (CurseForgeMod) -> Unit,
    onLaunch: (CurseForgeMod) -> Unit
) {
    val tablet = isTablet()
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }.collect { info ->
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            if (last >= total - 3 && total > 0 && hasMore && !isLoading) onLoadMore()
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val supportedVersions = listOf("", "1.21.1", "1.20.1", "1.19.4", "1.18.2", "1.16.5", "1.12.2")

    val Pink = Color(0xFFE91E8C)
    val BgDark = Color(0xFF120B10)
    val BgSurface = Color(0xFF1E0E1A)
    val BgBorder = Color(0xFF3D1A32)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        // 상단 바 검색 영역
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .padding(vertical = if (tablet) 12.dp else 8.dp, horizontal = if (tablet) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "모드팩 검색",
                    color = TextMain,
                    fontSize = if (tablet) 18.sp else 14.sp,
                    fontWeight = FontWeight.Bold
                )
                BasicTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        onSearch(it, selectedVersion)
                    },
                    textStyle = TextStyle(color = TextMain, fontSize = if (tablet) 13.sp else 11.sp),
                    cursorBrush = SolidColor(Pink),
                    modifier = Modifier
                        .weight(1f)
                        .height(if (tablet) 38.dp else 32.dp)
                        .background(BgDark, RoundedCornerShape(20.dp))
                        .border(1.dp, BgBorder, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = if (tablet) 8.dp else 6.dp)
                )
            }

            // 버전 필터 세그먼트 로우
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(supportedVersions) { version ->
                    val isSelected = selectedVersion == version
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Pink else BgDark)
                            .border(1.dp, if (isSelected) Pink else BgBorder, RoundedCornerShape(16.dp))
                            .clickable {
                                onVersionFilter(version)
                                onSearch(searchQuery, version)
                            }
                            .padding(horizontal = if (tablet) 12.dp else 10.dp, vertical = if (tablet) 6.dp else 4.dp)
                    ) {
                        Text(
                            text = if (version.isEmpty()) "전체 버전" else version,
                            color = if (isSelected) Color.White else TextSub,
                            fontSize = if (tablet) 12.sp else 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // 다운로드 인디케이터 배너 알림창
        if (isInstalling) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Pink.copy(alpha = 0.15f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(statusMessage, color = TextMain, fontSize = if (tablet) 12.sp else 10.sp)
                LinearProgressIndicator(
                    progress = { progress.percent.toFloat() },
                    color = Pink,
                    trackColor = BgBorder,
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }
        }

        // 메인 리스트 레이아웃 Grid 처리 (태블릿은 2열, 폰은 1열 구성 대응)
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (tablet) 2 else 1),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(if (tablet) 14.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(modpacks, key = { it.id }) { mod ->
                ModPackItem(
                    mod = mod,
                    isInstalling = isInstalling && installingModId == mod.id,
                    isInstalled = installedIds.contains(mod.id),
                    onInstall = { onInstall(mod) },
                    onLaunch = { onLaunch(mod) },
                    onDetail = { ModPackDetailActivity.start(ctx, mod.id, mod.name, mod.summary, mod.logo?.url, mod.downloadCount) },
                    tablet = tablet
                )
            }

            if (isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Pink)
                    }
                }
            }
        }
    }
}

@Composable
fun ModPackItem(
    mod: CurseForgeMod,
    isInstalling: Boolean,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onDetail: () -> Unit,
    tablet: Boolean
) {
    val Pink = Color(0xFFE91E8C)
    val BgSurface = Color(0xFF1E0E1A)
    val BgBorder = Color(0xFF3D1A32)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, if (isInstalled) Pink else BgBorder, RoundedCornerShape(10.dp))
            .clickable { onDetail() }
            .padding(if (tablet) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AsyncImage(
            model = mod.logo?.url,
            contentDescription = null,
            modifier = Modifier
                .size(if (tablet) 60.dp else 48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = mod.name,
                color = TextMain,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = mod.summary,
                color = TextSub,
                fontSize = if (tablet) 11.sp else 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(contentAlignment = Alignment.Center) {
            if (isInstalling) {
                CircularProgressIndicator(color = Pink, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Button(
                    onClick = { if (isInstalled) onLaunch() else onInstall() },
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(if (tablet) 32.dp else 28.dp)
                ) {
                    Text(
                        text = if (isInstalled) "▶ 열기" else "설치",
                        color = Color.White,
                        fontSize = if (tablet) 11.sp else 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}