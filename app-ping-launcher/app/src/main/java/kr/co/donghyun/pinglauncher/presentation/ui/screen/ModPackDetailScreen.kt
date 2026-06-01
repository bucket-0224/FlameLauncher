import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kr.co.donghyun.pinglauncher.presentation.ModDetail
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.pinglauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.pinglauncher.presentation.util.window.isTablet

@Composable
fun ModPackDetailScreen(
    modId: Int,
    modName: String,
    modSummary: String,
    modLogo: String?,
    modDownloads: Long,
    detail: ModDetail?,
    isLoading: Boolean,
    isInstalled: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onImageClick: (Int) -> Unit
) {
    val tablet = isTablet()
    val Pink = Color(0xFFE91E8C)
    val TextMain = Color(0xFFFCE4EC)
    val TextSub = Color(0xFFBB86A0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        // 툴바 (태블릿 유무에 맞춰 상하 여백 조절)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(0.dp))
                .padding(horizontal = 12.dp, vertical = if (tablet) 10.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Text("뒤로", color = TextSub, fontSize = if (tablet) 14.sp else 11.sp)
            }
            Text(
                text = modName,
                color = TextMain,
                fontSize = if (tablet) 15.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            if (isInstalled) {
                Button(
                    onClick = onLaunch,
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (tablet) 6.dp else 4.dp),
                    modifier = Modifier.height(if (tablet) 36.dp else 30.dp)
                ) {
                    Text("▶ 열기", color = Color.White, fontSize = if (tablet) 12.sp else 10.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onInstall,
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (tablet) 6.dp else 4.dp),
                    modifier = Modifier.height(if (tablet) 36.dp else 30.dp)
                ) {
                    Text("설치", color = Color.White, fontSize = if (tablet) 12.sp else 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // 헤더 배너 (태블릿/폰 높이 차등화 및 로고 크기 대응)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (tablet) 200.dp else 140.dp)
                        .background(Color(0xFF1A0A14))
                ) {
                    if (modLogo != null) {
                        AsyncImage(
                            model = "https://images.hdqwalls.com/download/minecraft-live-2025-game-c6-1024x768.jpg",
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alpha = 0.35f
                        )
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(if (tablet) 16.dp else 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (modLogo != null) {
                            AsyncImage(
                                model = modLogo,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(if (tablet) 72.dp else 52.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column {
                            Text(
                                text = modName,
                                color = TextMain,
                                fontSize = if (tablet) 20.sp else 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "⬇ ${formatCount(modDownloads)}",
                                color = TextSub,
                                fontSize = if (tablet) 12.sp else 10.sp
                            )
                            if (isInstalled) {
                                Text(
                                    text = "✅ 설치됨",
                                    color = Pink,
                                    fontSize = if (tablet) 12.sp else 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 요약 설명
            item {
                Text(
                    text = modSummary,
                    color = TextSub,
                    fontSize = if (tablet) 13.sp else 11.sp,
                    modifier = Modifier.padding(if (tablet) 16.dp else 12.dp)
                )
            }

            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Pink)
                    }
                }
            } else {
                // 스크린샷 섹션
                val screenshots = detail?.screenshots ?: emptyList()
                if (screenshots.isNotEmpty()) {
                    item {
                        Text(
                            text = "스크린샷",
                            color = TextMain,
                            fontSize = if (tablet) 15.sp else 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = if (tablet) 16.dp else 12.dp, vertical = 8.dp)
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = if (tablet) 16.dp else 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(screenshots) { page, screenshot ->
                                Box(
                                    modifier = Modifier
                                        .width(if (tablet) 240.dp else 180.dp)
                                        .height(if (tablet) 135.dp else 101.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BgSurface)
                                        .clickable { onImageClick(page) }
                                ) {
                                    AsyncImage(
                                        model = screenshot.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("🔍", fontSize = if (tablet) 12.sp else 9.sp)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // 상세 설명 (WebView 포함)
                item {
                    Text(
                        text = "설명",
                        color = TextMain,
                        fontSize = if (tablet) 15.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = if (tablet) 16.dp else 12.dp, vertical = 8.dp)
                    )

                    var webViewHeight by remember { mutableIntStateOf(1000) }

                    AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.setSupportZoom(false)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                isScrollContainer = false
                                isHorizontalScrollBarEnabled = false
                                isVerticalScrollBarEnabled = false

                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                        view?.evaluateJavascript(
                                            "(function() { return document.body.scrollHeight; })();"
                                        ) { height ->
                                            val h = height?.toFloatOrNull()?.toInt() ?: 1000
                                            webViewHeight = h
                                        }
                                    }
                                }
                            }
                        },
                        update = { webView ->
                            // WebView 내부 기본 글씨 크기도 기기 유형(tablet)에 맞춰 반응형 스타일링 적용
                            val baseFontSize = if (tablet) "14px" else "12px"
                            val styledHtml = """
                                <html>
                                <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                                <style>
                                    * { box-sizing: border-box; }
                                    body {
                                        background: transparent;
                                        color: #BB86A0;
                                        font-size: $baseFontSize;
                                        font-family: sans-serif;
                                        padding: 0 16px;
                                        margin: 0;
                                        max-width: 100%;
                                        overflow-x: hidden;
                                        word-break: break-word;
                                    }
                                    img {
                                        max-width: 100% !important;
                                        height: auto !important;
                                        border-radius: 8px;
                                        margin: 8px 0;
                                        display: block;
                                    }
                                    a { color: #E91E8C; }
                                    h1,h2,h3,h4 { color: #FCE4EC; }
                                    p { margin: 8px 0; line-height: 1.6; }
                                    ul, ol { padding-left: 24px; }
                                    table { max-width: 100%; overflow-x: auto; display: block; }
                                </style>
                                </head>
                                <body>${detail?.rawHtml ?: ""}</body>
                                </html>
                            """.trimIndent()
                            webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(webViewHeight.dp)
                            .padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}K"
    else -> count.toString()
}