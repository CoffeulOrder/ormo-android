package com.ormo.customer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.topInset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.common.CommonErrorRules
import kotlinx.coroutines.delay

/** 스낵바·토스트 유지 시간 (디자인 4.7) */
object SnackbarDurations {
    /** 담기 완료 "장바구니에 메뉴를 추가했어요" */
    const val ADDED = 2_000L

    /** 장바구니 삭제 (되돌리기) */
    const val UNDO = 4_000L

    /** 일부 요청 실패 "연결이 불안해요. 다시 시도해 주세요" */
    const val PARTIAL_FAILURE = 3_000L

    /** 취소 불가 토스트 "주문이 접수되어 취소할 수 없어요" (버튼 위 16) */
    const val NOT_CANCELABLE = 3_000L

    /** 비밀번호 변경 토스트 */
    const val PASSWORD_CHANGED = 2_000L
}

/** 스낵바 앞의 24 원형 아이콘: 성공은 초록 체크, 오류는 빨간 느낌표 (Figma) */
enum class SnackbarKind { Success, Error }

class SnackbarMessage(
    val text: String,
    val durationMs: Long,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val kind: SnackbarKind = SnackbarKind.Success,
    /** true 면 화면 위(상태 표시줄 아래 4)에 뜬다 — 로그인 실패·잠금 토스트 (Figma 0.1.2) */
    val atTop: Boolean = false,
)

/**
 * 한 번에 하나만 보이는 스낵바/토스트 상태. 새 메시지를 보이면 이전 것은 바로 교체된다.
 * 같은 문구를 연달아 보여도 새로 시작되도록 메시지 객체 자체를 키로 쓴다.
 */
class OrmoSnackbarState {
    var current by mutableStateOf<SnackbarMessage?>(null)
        private set

    fun show(
        text: String,
        durationMs: Long,
        actionLabel: String? = null,
        kind: SnackbarKind = SnackbarKind.Success,
        atTop: Boolean = false,
        onAction: (() -> Unit)? = null,
    ) {
        current = SnackbarMessage(text, durationMs, actionLabel, onAction, kind, atTop)
    }

    fun dismiss(message: SnackbarMessage? = current) {
        if (current === message) current = null
    }
}

/**
 * 스낵바·토스트 표시 자리. 배경은 Fill/Neutral 단색 — 명세의 Blur/Toast(배경 블러 30)는 Compose 에 배경 블러가 없어 단색으로 대체했다
 * (대체 여부는 디자이너 확인 필요). 좌우 여백 16·최소 높이 48·반경 14는 명세에 없어 가정한 값.
 * @param bottomOffset 하단에서 띄울 거리 (취소 불가 토스트는 버튼 위 16)
 */
@Composable
fun OrmoSnackbarHost(
    state: OrmoSnackbarState,
    modifier: Modifier = Modifier,
    bottomOffset: Dp = Space.s16,
) {
    val msg = state.current?.takeUnless { it.atTop } // 위쪽 토스트는 OrmoTopToastHost 가 그린다
    LaunchedEffect(state.current) {
        val m = state.current
        if (m != null) {
            delay(m.durationMs)
            state.dismiss(m)
        }
    }
    Box(modifier.fillMaxWidth().padding(horizontal = Space.s16).padding(bottom = bottomOffset), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 },
        ) {
            // 사라지는 동안에도 마지막 문구를 그린다
            val shown = remember(msg) { msg }
            if (shown != null) SnackbarSurface(shown, onDismiss = { state.dismiss(shown) }, hug = true)
        }
    }
}

/** 화면 위쪽 토스트 자리 (Figma: 알약, 가운데, 위에서 상태 표시줄 아래 4). 화면 맨 위 Box 안에서 호출한다. */
@Composable
fun OrmoTopToastHost(state: OrmoSnackbarState, modifier: Modifier = Modifier) {
    val msg = state.current?.takeIf { it.atTop }
    Box(modifier.fillMaxWidth().padding(top = topInset() + 4.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { -it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 },
        ) {
            val shown = remember(msg) { msg }
            if (shown != null) SnackbarSurface(shown, onDismiss = { state.dismiss(shown) }, hug = true)
        }
    }
}

@Composable
private fun SnackbarSurface(msg: SnackbarMessage, onDismiss: () -> Unit, hug: Boolean = false) {
    val c = Ormo.colors
    // Figma: 알약(반경 full) · 앞에 24 원형 아이콘 · 글자 흰색. 위쪽 토스트는 글자 폭에 맞추고, 아래쪽은 좌우 16 여백 안에서 넓게.
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
    Row(
        (if (hug) Modifier else Modifier.fillMaxWidth())
            .heightIn(min = 48.dp)
            .ormoShadow(shadowSmall(c), shape)
            .clip(shape)
            .background(c.fillNeutral)
            .padding(start = Space.s12, end = Space.s24, top = Space.s12, bottom = Space.s12)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s8),
    ) {
        Box(
            Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (msg.kind == SnackbarKind.Error) c.fillDanger else c.fillSuccess),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                if (msg.kind == SnackbarKind.Error) OrmoIcons.Alert else OrmoIcons.Check,
                contentDescription = null,
                tint = c.textOnFill,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(msg.text, style = OrmoType.bodyMdMedium, color = c.textOnFill, modifier = if (hug) Modifier else Modifier.weight(1f))
        if (msg.actionLabel != null) {
            Text(
                msg.actionLabel,
                style = OrmoType.bodySmSemibold,
                color = Base.Blue200,
                modifier = Modifier
                    .padding(start = Space.s16)
                    .clickable {
                        msg.onAction?.invoke()
                        onDismiss()
                    },
            )
        }
    }
}

// ───────────── 스켈레톤 (디자인 6.1.4) ─────────────

/** shimmer 가 흐르는 자리 표시 블록. shimmer 는 명세에 없고 "개발에서 추가" 라고 되어 있다. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val c = Ormo.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(c.bgLower, Color.White.copy(alpha = 0.7f), c.bgLower),
                    startX = x * 400f,
                    endX = x * 400f + 400f,
                ),
            ),
    )
}

/**
 * 로딩 중 스켈레톤: **0.3초 안에 불러오면 띄우지 않는다** (깜빡임 방지). 0.3초가 지나도 로딩 중이면 [skeleton] 을 보인다.
 * 로딩이 끝나면 [content]. 기준 시간은 `:domain` 의 `CommonErrorRules.SKELETON_DELAY_MS`.
 */
@Composable
fun DelayedSkeleton(
    loading: Boolean,
    skeleton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var showSkeleton by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(CommonErrorRules.SKELETON_DELAY_MS)
            showSkeleton = true
        } else {
            showSkeleton = false
        }
    }
    Box(modifier.wrapContentSize()) {
        when {
            !loading -> content()
            showSkeleton -> skeleton()
        }
    }
}
