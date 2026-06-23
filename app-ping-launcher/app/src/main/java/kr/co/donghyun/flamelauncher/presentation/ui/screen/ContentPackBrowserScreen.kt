package kr.co.donghyun.flamelauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.alpha
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
import kr.co.donghyun.flamelauncher.data.mods.ContentItem
import kr.co.donghyun.flamelauncher.data.mods.ContentSource
import kr.co.donghyun.flamelauncher.data.mojang.DownloadProgress
import kr.co.donghyun.flamelauncher.data.renderer.RendererManager
import kr.co.donghyun.flamelauncher.presentation.ContentPackDetailActivity
import kr.co.donghyun.flamelauncher.presentation.ui.theme.*
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet

/**
 * CurseForge classId 기반 컨텐츠 분류.
 * - MODPACK: 4471
 * - MOD: 6
 * - TEXTURE_PACK(Resource Pack): 12
 * - SHADER_PACK: 6552
 */
enum class ContentType(val classId: Int, val label: String) {
    MODPACK(4471, "🗂️ 모드팩"),
    MOD(6, "📂 모드"),
    DATAPACK(6945, "📦 데이터팩"),
    TEXTURE_PACK(12, "🎨 텍스처팩"),
    SHADER_PACK(6552, "📋 쉐이더팩"),
    WORLD(17, "🗺️ 월드");

    val needsWorldSelection: Boolean get() = this == DATAPACK

    /** Modrinth project_type facet 값 (mod/modpack/resourcepack/shader/datapack). */
    val modrinthType: String
        get() = when (this) {
            MODPACK -> "modpack"
            MOD -> "mod"
            DATAPACK -> "datapack"
            TEXTURE_PACK -> "resourcepack"
            SHADER_PACK -> "shader"
            WORLD -> "modpack"   // Modrinth 엔 'world' 타입이 없음 — 검색은 modpack 으로 대체
        }

    /** 설치 시 사용자가 타겟 인스턴스를 골라야 하는 타입. 모드팩만 자체 인스턴스를 만듦. */
    val needsTargetInstance: Boolean
        get() = this != MODPACK

    /** 새 인스턴스를 만들 때 Fabric/Forge 같은 모드 로더가 반드시 필요한지. */
    val requiresModLoader: Boolean
        get() = this == MOD

    /** 하위호환 — 기존 코드에서 호출하던 이름 유지 */
    @Deprecated("requiresModLoader 사용", ReplaceWith("requiresModLoader"))
    val requiresLoader: Boolean get() = requiresModLoader
}

@Composable
fun ContentPackBrowserScreen(
    onBack : () -> Unit,
    contentPacks: List<ContentItem>,
    progress: DownloadProgress,
    isLoading: Boolean,
    isInstalling: Boolean,
    installingModId: String?,
    statusMessage: String,
    selectedSource: ContentSource,
    selectedContentType: ContentType,
    installedIds: Set<String>,
    onSearch: (query: String, type: ContentType) -> Unit,
    onSourceFilter: (ContentSource) -> Unit,
    onContentTypeFilter: (ContentType) -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    onInstall: (ContentItem) -> Unit,
    onLaunch: (ContentItem) -> Unit
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

    var showCautionDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().background(BgSurface).systemBarsPadding()) {
        Column(modifier = Modifier.border(1.dp, BgBorder, RoundedCornerShape(0.dp)).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface)
                    .padding(horizontal = if (tablet) 16.dp else 10.dp, vertical = if (tablet) 10.dp else 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "컨텐츠 검색",
                    color = TextMain,
                    fontSize = if (tablet) 18.sp else 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearch(it, selectedContentType)
                },
                textStyle = TextStyle(color = TextMain, fontSize = if (tablet) 13.sp else 11.sp),
                cursorBrush = SolidColor(Flame),
                modifier = Modifier
                    .fillMaxWidth(1f)
                    .background(BgDark, RoundedCornerShape(20.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .padding(vertical = if (tablet) 12.dp else 8.dp, horizontal = if (tablet) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // 컨텐츠 타입 필터 칩 (Modpack / Mod / TexturePack / ShaderPack)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(ContentType.entries) { type ->
                    val isSelected = selectedContentType == type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Flame else BgDark)
                            .border(1.dp, if (isSelected) Flame else BgBorder, RoundedCornerShape(16.dp))
                            .clickable {
                                onContentTypeFilter(type)
                                onSearch(searchQuery, type)
                            }
                            .padding(horizontal = if (tablet) 14.dp else 12.dp, vertical = if (tablet) 7.dp else 5.dp)
                    ) {
                        Text(
                            text = type.label,
                            color = if (isSelected) Color.White else TextSub,
                            fontSize = if (tablet) 12.sp else 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // 소스 선택 세그먼트 로우 (버전 필터 자리 → CurseForge / Modrinth)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ContentSource.entries.forEach { source ->
                    val isSelected = selectedSource == source
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Flame else BgDark)
                            .border(1.dp, if (isSelected) Flame else BgBorder, RoundedCornerShape(16.dp))
                            .clickable {
                                if (!isSelected) {
                                    onSourceFilter(source)
                                    onSearch(searchQuery, selectedContentType)
                                }
                            }
                            .padding(horizontal = if (tablet) 12.dp else 10.dp, vertical = if (tablet) 8.dp else 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = source.label,
                            color = if (isSelected) Color.White else TextSub,
                            fontSize = if (tablet) 13.sp else 11.sp,
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
                    .background(Flame.copy(alpha = 0.15f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(statusMessage, color = TextMain, fontSize = if (tablet) 12.sp else 10.sp)
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    color = Flame,
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
            items(contentPacks, key = { it.trackKey }) { mod ->
                ContentPackItem(
                    mod = mod,
                    isInstalling = isInstalling && installingModId == mod.trackKey,
                    isInstalled = installedIds.contains(mod.trackKey),
                    onInstall = { onInstall(mod) },
                    onLaunch = { onLaunch(mod) },
                    onDetail = { onInstall(mod) },
                    tablet = tablet
                )
            }

            if (isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Flame)
                    }
                }
            }
        }

        if (showCautionDialog) {
            AlertDialog(
                onDismissRequest = { showCautionDialog = false },
                title = { Text("⚠️주의: 모드팩은 제대로 호환되지 않을 수 있습니다.", color = TextPrimary) },
                text = {
                    Text(
                        """
                            모드팩은 기존 Forge/Fabric/NeoForge에 맞게 호환되도록 설계되었습니다.
                            모드팩이 런처에서는 제대로 동작하지 않을 수 있으며, 일부 모드가 호환되지 않을 수 있습니다.
                            크래시 원인을 공유하거나, 오류 원인이 되는 모드들에 대해서 모드를 키거나 끄도록 유도하는 기능을 제공하고 있으나,
                            개발자는 이러한 호환 문제에 대해 Issue를 제공받지 않습니다. 
                            
                            따라서 유저가 활성화된 커뮤니티에서 해결 방안을 논의하는 것을 추천드립니다. 
                        """.trimIndent(),
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showCautionDialog = false
                    }) { Text("이해했습니다.", color = Color(0xFFFF6B6B)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCautionDialog = false }) {
                        Text("취소", color = TextSecondary)
                    }
                },
                containerColor = BgSurface,
            )
        }
    }
}

@Composable
fun ContentPackItem(
    mod: ContentItem,
    isInstalling: Boolean,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onDetail: () -> Unit,
    tablet: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tablet) 84.dp else 72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, if (isInstalled) Flame else BgBorder, RoundedCornerShape(10.dp))
            // 설치(다운로드) 중인 카드는 탭으로 상세 진입을 막는다.
            .clickable(enabled = !isInstalling) { onDetail() }
            .padding(if (tablet) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AsyncImage(
            model = mod.logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(if (tablet) 60.dp else 48.dp)
                .clip(RoundedCornerShape(8.dp))
                // 설치 중이면 콘텐츠를 흐리게 하여 비활성 상태임을 표시
                .alpha(if (isInstalling) 0.4f else 1f),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (isInstalling) 0.4f else 1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
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
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 설치(다운로드) 중인 카드에만 진행 표시
        if (isInstalling) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CircularProgressIndicator(
                    color = Flame,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(if (tablet) 22.dp else 18.dp)
                )
                Text(
                    text = "설치 중",
                    color = Flame,
                    fontSize = if (tablet) 11.sp else 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}