import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kr.co.donghyun.pinglauncher.presentation.ContentDetail
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet

/**
 * 컨텐츠 상세 화면.
 * - 상단: 뒤로가기 + 타이틀
 * - 헤더: 로고, 이름, 다운로드 수, 요약
 * - 스크린샷 캐러셀 (탭하면 onImageClick으로 인덱스 전달 → Activity에서 풀스크린 뷰어 띄움)
 * - 설명 (HTML 파싱된 평문)
 * - 하단 고정 액션: 설치/열기
 *
 * default package에 두는 이유는 기존 코드(`import ModPackDetailScreen`)가
 * default package import를 가정하고 있었기 때문에 그대로 호환되도록 맞춤.
 */
@Composable
fun ContentPackDetailScreen(
    modId: Int,
    modName: String,
    modSummary: String,
    modLogo: String?,
    modDownloads: Long,
    detail: ContentDetail?,
    isLoading: Boolean,
    isInstalled: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onImageClick: (Int) -> Unit
) {
    val tablet = isTablet()

    val Pink = Color(0xFFE91E8C)
    val BgDark = Color(0xFF120B10)
    val BgSurface = Color(0xFF1E0E1A)
    val BgBorder = Color(0xFF3D1A32)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        // 상단 바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .padding(horizontal = if (tablet) 16.dp else 12.dp, vertical = if (tablet) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("←", color = TextMain, fontSize = if (tablet) 22.sp else 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "상세 정보",
                color = TextMain,
                fontSize = if (tablet) 17.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 본문 스크롤 영역
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(if (tablet) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 12.dp)
        ) {
            // 헤더 카드
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 14.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsyncImage(
                    model = modLogo,
                    contentDescription = null,
                    modifier = Modifier
                        .size(if (tablet) 88.dp else 72.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = modName,
                        color = TextMain,
                        fontSize = if (tablet) 18.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "↓ ${formatDownloads(modDownloads)}",
                        color = Pink,
                        fontSize = if (tablet) 12.sp else 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (modSummary.isNotBlank()) {
                        Text(
                            text = modSummary,
                            color = TextSub,
                            fontSize = if (tablet) 12.sp else 10.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 스크린샷 캐러셀
            val screenshots = detail?.screenshots ?: listOf()
            if (screenshots.isNotEmpty()) {
                Text(
                    "스크린샷",
                    color = TextMain,
                    fontSize = if (tablet) 14.sp else 12.sp,
                    fontWeight = FontWeight.Bold
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(screenshots.toList().size) { index ->
                        val shot = screenshots[index]
                        AsyncImage(
                            model = shot,
                            contentDescription = null,
                            modifier = Modifier
                                .height(if (tablet) 140.dp else 110.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                                .clickable { onImageClick(index) },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            // 설명
            Text(
                "설명",
                color = TextMain,
                fontSize = if (tablet) 14.sp else 12.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgSurface)
                    .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                    .padding(if (tablet) 14.dp else 12.dp)
            ) {
                when {
                    isLoading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Pink,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("불러오는 중...", color = TextSub, fontSize = if (tablet) 12.sp else 11.sp)
                        }
                    }
                    detail?.description?.isNotBlank() == true -> {
                        Text(
                            text = detail.description,
                            color = TextMain,
                            fontSize = if (tablet) 12.sp else 11.sp,
                            lineHeight = if (tablet) 18.sp else 16.sp
                        )
                    }
                    else -> {
                        Text(
                            text = "설명이 없습니다.",
                            color = TextSub,
                            fontSize = if (tablet) 12.sp else 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (tablet) 80.dp else 70.dp))
        }

        // 하단 액션 영역 (고정)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder)
                .padding(horizontal = if (tablet) 16.dp else 12.dp, vertical = if (tablet) 12.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { if (isInstalled) onLaunch() else onInstall() },
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(if (tablet) 48.dp else 42.dp)
            ) {
                Text(
                    text = if (isInstalled) "▶ 열기" else "설치",
                    color = Color.White,
                    fontSize = if (tablet) 14.sp else 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 1,234 / 12.3K / 1.2M 형태로 표시 */
private fun formatDownloads(count: Long): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
}