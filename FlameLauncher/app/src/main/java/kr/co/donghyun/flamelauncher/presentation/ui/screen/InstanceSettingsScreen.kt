package kr.co.donghyun.flamelauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kr.co.donghyun.flamelauncher.data.jvm.JvmSettings
import kr.co.donghyun.flamelauncher.data.jvm.JvmSettingsManager
import kr.co.donghyun.flamelauncher.data.renderer.Renderer
import kr.co.donghyun.flamelauncher.data.renderer.RendererPluginManager
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.FlamePrimary
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextPrimary
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSecondary
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet
import kotlin.math.roundToInt

/**
 * 진행/결과 상태는 모두 이 Composable 이 소유한다(remember).
 * Activity 는 상태를 들고 있지 않고, 콜백으로 전달받은 MutableState 를 갱신만 한다.
 *  - launchMapPicker(message, importing) : 월드 zip 가져오기 시작/종료 시 importing 토글
 *  - launchModPicker(message, importing) : 모드 .jar 추가 시작/종료 시 importing 토글
 *  - importModpack(message, importing)   : 모드팩(zip) 가져오기 시작/종료 시 importing 토글
 *  - exportModpack(message, importing)   : 현재 mods/config 를 모드팩(zip)으로 추출 시작/종료 시 importing 토글
 *  - deleteInstance(finish)              : 삭제 완료 시 finish.value = true
 *
 * @param loaderInstalled 이 인스턴스에 Forge/NeoForge/Fabric 이 설치돼 있으면 true.
 *                        false 면 모드 추가 메뉴를 비활성화한다(바닐라엔 모드 못 넣음).
 * @param loaderLabel     표시용 로더 이름("Forge"/"Fabric"/"NeoForge"). 없으면 null.
 *
 * @param currentRendererId 이 인스턴스에 저장된 렌더러 id. null 이면 전역 기본을 따른다.
 * @param onRendererSelected 렌더러를 고르면 호출(저장은 Activity 가 InstanceManager 로 처리).
 *                           null 을 넘기면 "전역 기본 사용"으로 되돌린다.
 * @param onInstallMobileGlues MobileGlues 미설치 상태에서 설치 안내를 눌렀을 때(브라우저 등).
 *
 * @param installedMods   이 인스턴스 mods/ 에 들어있는 모드 목록(활성 .jar + 비활성 .jar.disabled).
 *                        화면 진입 시 Activity 가 스캔해 넘기고, 삭제 후 refreshMods 로 다시 채운다.
 * @param onDeleteMod     모드 1개 삭제 요청. fileName 은 InstalledMod.fileName(확장자 포함).
 * @param refreshMods     모드 목록을 다시 읽어달라는 요청(삭제 다이얼로그를 열 때/삭제 후 호출).
 */
@Composable
fun InstanceSettingsScreen(
    instanceName : String,
    loaderInstalled : Boolean,
    loaderLabel : String?,
    currentRendererId : String?,
    onRendererSelected : (rendererId : String?) -> Unit,
    onInstallMobileGlues : () -> Unit,
    installedMods : List<InstalledMod>,
    onDeleteMod : (fileName : String) -> Unit,
    refreshMods : () -> Unit,
    launchMapPicker : (importMessage : MutableState<String>, importing : MutableState<Boolean>) -> Unit,
    launchModPicker : (importMessage : MutableState<String>, importing : MutableState<Boolean>) -> Unit,
    importModpack : (importMessage : MutableState<String>, importing : MutableState<Boolean>) -> Unit,
    exportModpack : (importMessage : MutableState<String>, importing : MutableState<Boolean>) -> Unit,
    deleteInstance : (finish : MutableState<Boolean>) -> Unit,
    finish : () -> Unit
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(JvmSettingsManager.load(context)) }

    // ── 화면이 살아있는 동안만 유지되는 상태 (진입할 때마다 false 로 초기화) ──
    val importing = remember { mutableStateOf(false) }
    val importMessage = remember { mutableStateOf("가져오는 중…") }
    val deletedFinish = remember { mutableStateOf(false) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRendererPicker by remember { mutableStateOf(false) }
    var showModManager by remember { mutableStateOf(false) }
    val isImporting by importing
    val msg by importMessage
    val finishNow by deletedFinish
    val tablet = isTablet()

    // 삭제 완료되면 화면 종료
    LaunchedEffect(finishNow) {
        if (finishNow) finish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 헤더 (상단 고정 — 스크롤되지 않음) ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = finish) {
                    Text("뒤로", color = TextSecondary, fontSize = if (tablet) 14.sp else 11.sp)
                }

                Spacer(Modifier.width(12.dp))
                Column {
                    Text("인스턴스 설정", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(instanceName, color = TextSecondary, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 헤더 아래 내용만 스크롤 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // ── 그래픽 ──
                SectionLabel("그래픽")
                Spacer(Modifier.height(8.dp))

                // 렌더러 — 인스턴스별 설정. 탭하면 선택 다이얼로그.
                val mgAvailable = RendererPluginManager.isMobileGluesAvailable()
                val rendererLabel = rendererDisplayLabel(currentRendererId)
                val rendererSub = when {
                    currentRendererId == null -> "전역 기본값 사용 · 탭하여 이 인스턴스 전용으로 변경"
                    currentRendererId == "mobileglues" && !mgAvailable ->
                        "MobileGlues 미설치 — 실행 시 Zink로 폴백됩니다"
                    else -> "이 인스턴스 전용 렌더러"
                }
                SettingRow(
                    emoji = rendererEmoji(currentRendererId),
                    title = "렌더러 · $rendererLabel",
                    subtitle = rendererSub,
                    enabled = !isImporting,
                ) { showRendererPicker = true }

                Spacer(Modifier.height(20.dp))

                // ── 콘텐츠 추가 ──
                SectionLabel("콘텐츠 추가")
                Spacer(Modifier.height(8.dp))

                // 모드(.jar) 추가 — 로더가 깔린 인스턴스에서만 활성
                SettingRow(
                    emoji = "🧩",
                    title = "모드 추가 (.jar)",
                    subtitle = if (loaderInstalled)
                        "${loaderLabel ?: "로더"} · .jar 모드 파일을 mods 에 추가"
                    else
                        "Forge / Fabric / NeoForge 설치 시 사용 가능",
                    enabled = !isImporting && loaderInstalled,
                ) { launchModPicker(importMessage, importing) }

                Spacer(Modifier.height(10.dp))

                // 설치된 모드 관리 — 개별 삭제. mods/ 가 있는(로더 설치된) 인스턴스에서만.
                val modCountLabel = if (installedMods.isEmpty()) "설치된 모드 없음"
                else "총 ${installedMods.size}개 · 탭하여 개별 삭제"
                SettingRow(
                    emoji = "🗂",
                    title = "설치된 모드 관리",
                    subtitle = if (loaderInstalled) modCountLabel
                    else "Forge / Fabric / NeoForge 설치 시 사용 가능",
                    enabled = !isImporting && loaderInstalled,
                ) {
                    refreshMods()           // 다이얼로그 열기 직전 최신 목록으로 갱신
                    showModManager = true
                }

                Spacer(Modifier.height(10.dp))

                // 월드(맵) 가져오기 — 기존
                SettingRow(
                    emoji = "🗺",
                    title = "월드(맵) 가져오기",
                    subtitle = "zip 으로 받은 월드를 saves 에 추가",
                    enabled = !isImporting,
                ) { launchMapPicker(importMessage, importing) }

                Spacer(Modifier.height(20.dp))

                // ── 모드팩 ──
                SectionLabel("모드팩")
                Spacer(Modifier.height(8.dp))

                // 모드팩 가져오기 — zip 의 mods/config 를 현재 인스턴스에 풀어넣음
                SettingRow(
                    emoji = "📥",
                    title = "모드팩 가져오기",
                    subtitle = "모드팩(zip)의 모드·설정을 이 인스턴스에 추가합니다",
                    enabled = !isImporting,
                ) { importModpack(importMessage, importing) }

                Spacer(Modifier.height(10.dp))

                // 모드팩으로 추출 — 현재 mods/config 를 manifest 와 함께 zip 으로 저장
                SettingRow(
                    emoji = "📦",
                    title = "모드팩으로 추출",
                    subtitle = "현재 mods·config 를 모드팩(zip)으로 내보냅니다",
                    enabled = !isImporting,
                ) { exportModpack(importMessage, importing) }

                Spacer(Modifier.height(20.dp))

                // ── 관리 ──
                SectionLabel("관리")
                Spacer(Modifier.height(8.dp))

                SettingRow(
                    emoji = "🗑",
                    title = "인스턴스 삭제",
                    subtitle = "이 인스턴스와 모든 데이터를 삭제합니다",
                    enabled = !isImporting,
                    danger = true,
                ) { showDeleteConfirm = true }
            }
        }

        // ── 진행 다이얼로그 (모드 추가/월드·모드팩 가져오기/모드팩 추출 공용) ──
        if (isImporting) {
            Dialog(onDismissRequest = { /* 진행 중에는 닫지 못함 */ }) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgSurface)
                        .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                        .padding(28.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = FlamePrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(msg, color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        }

        // ── 렌더러 선택 다이얼로그 ──
        if (showRendererPicker) {
            RendererPickerDialog(
                currentRendererId = currentRendererId,
                onDismiss = { showRendererPicker = false },
                onSelect = { id ->
                    showRendererPicker = false
                    onRendererSelected(id)
                },
                onInstallMobileGlues = {
                    showRendererPicker = false
                    onInstallMobileGlues()
                },
            )
        }

        // ── 설치된 모드 관리 다이얼로그 ──
        if (showModManager) {
            ModManagerDialog(
                mods = installedMods,
                onDelete = { fileName ->
                    onDeleteMod(fileName)
                    refreshMods()       // 삭제 직후 목록 재조회 → 다이얼로그가 즉시 반영
                },
                onDismiss = { showModManager = false },
            )
        }

        // ── 삭제 확인 다이얼로그 ──
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = BgSurface,
                title = { Text("인스턴스 삭제", color = TextPrimary) },
                text = {
                    Text(
                        "‘$instanceName’ 인스턴스를 삭제할까요?\n저장된 월드, 설정, 모드가 모두 사라집니다.",
                        color = TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        deleteInstance(deletedFinish)
                    }) { Text("삭제", color = Color(0xFFE5484D)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("취소", color = TextSecondary)
                    }
                },
            )
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}


/**
 * 전체화면 등 on/off 설정용 토글 행. 기존 SettingRow 와 같은 카드 스타일.
 * 행 전체를 눌러도 토글되고, 우측 스위치로도 토글된다.
 */
@Composable
fun SettingToggleRow(
    emoji: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = FlamePrimary,
                checkedBorderColor = FlamePrimary,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = BgDark,
                uncheckedBorderColor = BgBorder,
            ),
        )
    }
}

/**
 * 렌더 해상도 배율 슬라이더 행. (ZalithLauncher2 의 Resolution 과 동일한 역할)
 * 100% = 네이티브, 낮출수록 FPS 상승. 5% 단위로 스냅.
 *
 * @param percent          현재 배율(%) — JvmSettings.resolutionScalePercent
 * @param onPercentChange  값이 바뀔 때 호출. 보통 여기서 settings.copy(...) + save 한다.
 */
@Composable
fun ResolutionScaleRow(
    percent: Int,
    onPercentChange: (Int) -> Unit,
    min: Int = JvmSettings.RES_SCALE_MIN_PERCENT,
    max: Int = JvmSettings.RES_SCALE_MAX_PERCENT,
    step: Int = 5,
) {
    val clamped = percent.coerceIn(min, max)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", fontSize = 22.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("렌더 해상도", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (clamped >= 100) "네이티브 해상도"
                    else "낮출수록 FPS 상승 · 화면은 약간 흐려짐",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("$clamped%", color = FlamePrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(4.dp))

        // 25~100 을 5% 단위로 → 선택 가능한 점 개수 - 2 = steps
        val steps = (((max - min) / step) - 1).coerceAtLeast(0)
        Slider(
            value = clamped.toFloat(),
            onValueChange = { v ->
                val snapped = ((v / step).roundToInt() * step).coerceIn(min, max)
                if (snapped != clamped) onPercentChange(snapped)
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = FlamePrimary,
                activeTrackColor = FlamePrimary,
                inactiveTrackColor = BgBorder,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SettingRow(
    emoji: String,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val titleColor = if (danger) Color(0xFFE5484D) else TextPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)   // 비활성 시 흐리게
            .clip(RoundedCornerShape(10.dp))
            .background(BgSurface)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Text("›", color = TextSecondary, fontSize = 20.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더러 선택 UI
// ─────────────────────────────────────────────────────────────────────────────

/** currentRendererId → 표시 라벨. null 이면 "전역 기본". */
private fun rendererDisplayLabel(id: String?): String = when (id) {
    null          -> "전역 기본"
    else          -> Renderer.fromId(id).displayName
}

/** currentRendererId → 이모지. null 이면 일반 팔레트. */
private fun rendererEmoji(id: String?): String = when (id) {
    null -> "🎨"
    else -> Renderer.fromId(id).emoji
}

/**
 * 렌더러 선택 다이얼로그.
 *  - "전역 기본 사용" + 내부 렌더러(Zink/GL4ES) + (설치 시) MobileGlues 를 라디오 형태로 나열.
 *  - MobileGlues 가 미설치면 비활성 + "설치" 안내 버튼을 보여준다.
 */
@Composable
private fun RendererPickerDialog(
    currentRendererId: String?,
    onDismiss: () -> Unit,
    onSelect: (rendererId: String?) -> Unit,
    onInstallMobileGlues: () -> Unit,
) {
    val mgAvailable = RendererPluginManager.isMobileGluesAvailable()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = { Text("렌더러 선택", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 전역 기본 사용 (rendererId = null)
                RendererOption(
                    emoji = "🎨",
                    title = "전역 기본 사용",
                    desc = "런처 전체 기본 렌더러 설정을 따릅니다.",
                    selected = currentRendererId == null,
                    enabled = true,
                    onClick = { onSelect(null) },
                )
                Spacer(Modifier.height(8.dp))

                // 내부 렌더러: Zink, GL4ES
                Renderer.entries.filter { !it.isPlugin }.forEach { r ->
                    RendererOption(
                        emoji = r.emoji,
                        title = r.displayName,
                        desc = r.description,
                        selected = currentRendererId == r.id,
                        enabled = true,
                        onClick = { onSelect(r.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // 외부 플러그인 렌더러: MobileGlues
                val mg = Renderer.MOBILEGLUES
                RendererOption(
                    emoji = mg.emoji,
                    title = mg.displayName + if (!mgAvailable) " (미설치)" else "",
                    desc = if (mgAvailable) mg.description
                    else "별도 앱(MobileGlues)을 설치해야 사용할 수 있습니다.",
                    selected = currentRendererId == mg.id,
                    enabled = mgAvailable,
                    onClick = { if (mgAvailable) onSelect(mg.id) },
                )

                if (!mgAvailable) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onInstallMobileGlues) {
                        Text("MobileGlues 설치 안내 열기 ›", color = FlamePrimary, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = TextSecondary) }
        },
    )
}

/** 라디오 형태의 렌더러 한 줄. */
@Composable
private fun RendererOption(
    emoji: String,
    title: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) FlamePrimary else BgBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Flame.copy(alpha = 0.12f) else BgDark)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) FlamePrimary else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, color = TextSecondary, fontSize = 11.sp)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text("✓", color = FlamePrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
// ─────────────────────────────────────────────────────────────────────────────
// 설치된 모드 관리 (개별 삭제)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * mods/ 에서 발견한 모드 1개.
 * @param fileName 실제 파일명(확장자 포함). 삭제는 이 이름으로 한다.
 *                 활성 모드는 "xxx.jar", 비활성(자동 비활성 포함)은 "xxx.jar.disabled".
 * @param displayName 사용자에게 보여줄 이름(.disabled/.jar 꼬리표 제거).
 * @param enabled .disabled 가 아니면 true.
 * @param sizeBytes 파일 크기(표시용).
 */
data class InstalledMod(
    val fileName: String,
    val displayName: String,
    val enabled: Boolean,
    val sizeBytes: Long,
)

/**
 * 설치된 모드 목록 다이얼로그. 각 항목 우측의 삭제(🗑) 버튼으로 개별 제거한다.
 * 삭제 자체는 Activity(onDelete)가 파일을 지우고, 호출부가 refreshMods 로 목록을 다시 채운다.
 */
@Composable
private fun ModManagerDialog(
    mods: List<InstalledMod>,
    onDelete: (fileName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 삭제 재확인 대상(파일명). null 이면 확인창 안 띄움.
    var pendingDelete by remember { mutableStateOf<InstalledMod?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        title = { Text("설치된 모드 (${mods.size})", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            if (mods.isEmpty()) {
                Text("이 인스턴스에 설치된 모드가 없습니다.", color = TextSecondary, fontSize = 13.sp)
            } else {
                // 목록이 길 수 있으니 높이를 제한하고 스크롤.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(mods, key = { it.fileName }) { mod ->
                        ModRow(mod = mod, onDeleteClick = { pendingDelete = mod })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = TextSecondary) }
        },
    )

    // 개별 삭제 재확인
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = BgSurface,
            title = { Text("모드 삭제", color = TextPrimary) },
            text = {
                Text(
                    "‘${target.displayName}’ 모드를 삭제할까요?\n파일이 영구적으로 제거됩니다.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.fileName)
                    pendingDelete = null
                }) { Text("삭제", color = Color(0xFFE5484D)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("취소", color = TextSecondary)
                }
            },
        )
    }
}

/** 모드 목록 한 줄: 이름 + (비활성 배지) + 크기 + 삭제 버튼. */
@Composable
private fun ModRow(
    mod: InstalledMod,
    onDeleteClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgDark)
            .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (mod.enabled) "🧩" else "🚫", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                mod.displayName,
                color = if (mod.enabled) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    if (!mod.enabled) append("비활성 · ")
                    append(formatFileSize(mod.sizeBytes))
                },
                color = TextSecondary,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDeleteClick) {
            Text("🗑 삭제", color = Color(0xFFE5484D), fontSize = 12.sp)
        }
    }
}

/** 바이트 → 사람이 읽기 쉬운 크기. */
private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L         -> String.format("%.0f KB", bytes / 1024.0)
    else                   -> "$bytes B"
}