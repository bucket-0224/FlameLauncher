package kr.co.donghyun.flamelauncher.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgItem
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSub

/**
 * 기기 비호환으로 자동 비활성화된 모드를 알리는 오버레이.
 *
 * 게임의 첫 프레임이 렌더된 뒤(= 게임이 정상 진입한 직후) 한 번 표시된다.
 * 실행 전 [MinecraftActivity.disableUnsupportedMods] 가 데스크탑 전용(arm64 미지원
 * 네이티브 포함) 모드를 `.jar.pingdisabled` 로 비활성화하면서 그 목록을 수집하고,
 * 여기로 전달한다.
 *
 * @param disabled  비활성화된 모드 표시명 목록(파일명 기반). 비어 있으면 호출되지 않는다.
 * @param onClose   닫기(이후 다시 표시되지 않음)
 */
@Composable
fun DisabledModsOverlay(
    disabled: List<DisabledModInfo>,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.7f)
                .background(BgSurface, RoundedCornerShape(16.dp))
                .border(1.dp, BgBorder, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 헤더
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⚠️ 비활성화된 모드",
                    color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClose) { Text("확인", color = Flame, fontSize = 13.sp) }
            }

            Text(
                "이 기기(arm64 안드로이드)에서 동작하지 않는 모드 ${disabled.size}개를 자동으로 껐어요. " +
                        "데스크톱 전용 네이티브(.so/.dll)를 포함하거나 안드로이드를 지원하지 않는 모드예요. " +
                        "게임은 이 모드들을 제외하고 실행됩니다.",
                color = TextSub, fontSize = 12.sp, lineHeight = 17.sp
            )

            // 목록(스크롤)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgItem)
                    .border(1.dp, BgBorder, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                disabled.forEach { mod ->
                    Column {
                        Text(mod.displayName, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(mod.reason, color = TextSub, fontSize = 11.sp)
                    }
                }
            }

            Text(
                "※ 비활성화된 모드는 삭제되지 않았어요. 파일명이 .jar.pingdisabled 로 바뀐 것뿐이라, " +
                        "원하면 파일 관리자에서 되돌릴 수 있어요.",
                color = TextSub, fontSize = 10.sp, lineHeight = 14.sp
            )
        }
    }
}

/**
 * 비활성화된 모드 한 개의 표시 정보.
 * @param displayName  사용자에게 보여줄 이름(원본 .jar 파일명)
 * @param reason       비활성화 사유(짧은 설명)
 */
data class DisabledModInfo(
    val displayName: String,
    val reason: String,
)