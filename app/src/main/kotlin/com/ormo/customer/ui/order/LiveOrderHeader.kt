package com.ormo.customer.ui.order

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.ormo.customer.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.auth.rememberReducedMotion
import com.ormo.customer.ui.theme.Base
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.domain.model.LiveBarStage
import com.ormo.domain.orders.LiveBarView

/** Figma 2.1.4 의 움직임 곡선: cubic-bezier(0.2, 0.9, 0.25, 1) */
val LiveFloatEasing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

/** 현황 막대(Live Order Bar · Flat) 높이 */
val LiveBarHeight = 148.dp // 위 16 + 일러스트 72 + 8 + 단계 막대·이름 36 + 아래 16 (글자를 키운 만큼: 제목 20 · 부제 16 · 단계 이름 14)

/** 현황 구역 높이: 막대 134 + 아래 여백 (1건은 메뉴 시트까지 8, 2건 이상은 점 아래 12 + 8 → 24) */
fun livePanelHeight(count: Int): Dp = if (count >= 2) 172.dp else 156.dp

/** 현황 구역이 헤더 밑으로 접히는 모션 / 단계·펼침·넘김 시간 */
const val LIVE_SWAP_MS = 450
const val LIVE_PAGE_MS = 350
const val LIVE_OPEN_MS = 360
const val LIVE_CLOSE_MS = 220

/** 위쪽 현황 구역(스크롤로 접힘)과 헤더 패널(펼침) 상태 */
@Stable
class LivePanelState {
    /** 메뉴를 끌어 올려 현황 구역이 접힌 만큼 (px, 0 = 펼쳐짐 · 구역 높이 = 완전히 접힘) */
    var collapse by mutableFloatStateOf(0f)

    /** 헤더 현황을 눌러 연 패널 (주문 2건 이상) */
    var expanded by mutableStateOf(false)
}

internal fun stageLabel(stage: LiveBarStage): String = when (stage) {
    LiveBarStage.CONFIRMING -> "주문 확인 중"
    LiveBarStage.MAKING -> "만드는 중"
    LiveBarStage.READY -> "픽업 대기"
}

internal fun stageFraction(stage: LiveBarStage): Float = when (stage) {
    LiveBarStage.CONFIRMING -> 1f / 3f
    LiveBarStage.MAKING -> 2f / 3f
    LiveBarStage.READY -> 1f
}

/**
 * 반복 물결의 진행값(0→1): [activeMs] 동안 감속하며 퍼지고 [periodMs] 까지 쉰다. 움직임 줄이기면 한 번만.
 */
@Composable
private fun rememberRipple(periodMs: Int, activeMs: Int, reduced: Boolean): State<Float> {
    if (reduced) {
        val once = remember { Animatable(0f) }
        LaunchedEffect(Unit) { once.animateTo(1f, tween(activeMs, easing = LinearOutSlowInEasing)) }
        return remember { derivedFromAnimatable(once) }
    }
    val transition = rememberInfiniteTransition(label = "live-ripple")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = periodMs
                0f at 0 using LinearOutSlowInEasing
                1f at activeMs
                1f at periodMs
            },
            RepeatMode.Restart,
        ),
        label = "live-ripple-progress",
    )
}

private fun derivedFromAnimatable(a: Animatable<Float, *>): State<Float> = object : State<Float> {
    override val value: Float get() = a.value
}

/**
 * 현황 구역 (Figma 2.1.4 · Live Order Bar Flat): 메뉴 홈 맨 위, 헤더 아래. 한 장 = 375×134 (좌우 20 · 위아래 16),
 * 글은 왼쪽 · 번호 상자 52 는 오른쪽. 주문이 둘 이상이면 화면 폭 한 장씩 넘기고(0.35초) 아래에 점 (주문 수, 최대 5).
 * 먼저 받을 주문이 첫 장. 막대를 누르면 그 주문 상세.
 */
@Composable
fun LiveOrderPanel(bars: List<LiveBarView>, onOrder: (orderId: String) -> Unit, modifier: Modifier = Modifier) {
    if (bars.isEmpty()) return
    val pager = rememberPagerState { bars.size }
    val reduced = rememberReducedMotion()
    Box(modifier.fillMaxWidth().height(livePanelHeight(bars.size))) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().height(LiveBarHeight),
            key = { bars.getOrNull(it)?.orderId ?: it },
            flingBehavior = PagerDefaults.flingBehavior(
                state = pager,
                snapAnimationSpec = tween(if (reduced) 0 else LIVE_PAGE_MS, easing = LiveFloatEasing),
            ),
            snapPosition = SnapPosition.Start,
        ) { page ->
            val bar = bars[page]
            LiveOrderFlatBar(
                bar = bar,
                onClick = { onOrder(bar.orderId) },
                position = if (bars.size > 1) "${page + 1}/${bars.size}" else null,
            )
        }
        if (bars.size >= 2) {
            PagerDots(
                count = minOf(bars.size, 5),
                current = pager.currentPage.coerceAtMost(minOf(bars.size, 5) - 1),
                modifier = Modifier.align(Alignment.TopCenter).offset(y = LiveBarHeight - 4.dp),
            )
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == current) Ormo.colors.fillBrand else Ormo.colors.track))
        }
    }
}

/** 막대 한 장 (글 왼쪽 · 번호 상자 오른쪽 · 아래 3칸 단계 막대와 단계 이름) */
@Composable
fun LiveOrderFlatBar(bar: LiveBarView, onClick: () -> Unit, modifier: Modifier = Modifier, position: String? = null) {
    val c = Ormo.colors
    val reduced = rememberReducedMotion()
    val ready = bar.stage == LiveBarStage.READY
    Column(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = bar.contentDescription
                if (position != null) stateDescription = position // 넘기는 중이면 '1/2' 로 읽는다
            }
            .padding(horizontal = Space.s20, vertical = Space.s16),
        verticalArrangement = Arrangement.spacedBy(Space.s8), // 일러스트와 단계 막대 사이 8
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // '27번' 은 파랑 (Figma 개정 2.1.4): "27번 음료를 만들고 있어요"
                val titleText = buildAnnotatedString {
                    bar.pickupNumber?.let { withStyle(SpanStyle(color = c.textBrand)) { append("${it}번") }; append(" ") }
                    append(bar.title)
                }
                Text(titleText, style = OrmoType.bodyXlSemibold, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                bar.subtitle?.let {
                    Text(it, style = OrmoType.bodyMdRegular, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // 일러스트 모션 (72 상자, Lottie 는 사방 12 큰 96): 주문 확인 중 · 만드는 중 · 픽업 대기. 장식이라 접근성에서는 숨긴다
            LiveIllustration(bar.stage, reduced)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { i ->
                    val shape = when (i) {
                        0 -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                        2 -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                        else -> RoundedCornerShape(0.dp)
                    }
                    val fill by androidx.compose.animation.animateColorAsState(
                        if (i < bar.filledCells) c.fillBrand else c.fillNeutralWeak,
                        tween(if (reduced) 0 else LIVE_PAGE_MS, easing = LiveFloatEasing),
                        label = "live-seg",
                    )
                    Box(Modifier.weight(1f).height(8.dp).clip(shape).background(fill))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                listOf("주문 확인", "만드는 중", "픽업 대기").forEachIndexed { i, label ->
                    val current = i == bar.filledCells - 1
                    Text(
                        label,
                        style = if (current) OrmoType.bodySmSemibold else OrmoType.bodySmMedium,
                        color = if (current) c.textBrand else c.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 현황 막대 오른쪽 일러스트 (디자인 제공 Lottie): 주문 확인 중=프린터에서 주문서가 나옴, 만드는 중=커피가 차오름, 픽업 대기=김이 피어오름 (2초 반복).
 * 동작 줄이기가 켜져 있거나 불러오는 중에는 정지 그림.
 */
@Composable
private fun LiveIllustration(stage: LiveBarStage, reduced: Boolean) {
    val lottieRes = when (stage) {
        LiveBarStage.CONFIRMING -> R.raw.ormo_live_checking
        LiveBarStage.MAKING -> R.raw.ormo_live_making
        LiveBarStage.READY -> R.raw.ormo_live_ready
    }
    val stillRes = when (stage) {
        LiveBarStage.CONFIRMING -> R.drawable.ormo_live_checking_still
        LiveBarStage.MAKING -> R.drawable.ormo_live_making_still
        LiveBarStage.READY -> R.drawable.ormo_live_ready_still
    }
    Box(Modifier.size(72.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(lottieRes))
        if (reduced || composition == null) {
            Image(painterResource(stillRes), contentDescription = null, modifier = Modifier.requiredSize(96.dp), contentScale = ContentScale.Fit)
        } else {
            LottieAnimation(composition = composition, iterations = LottieConstants.IterateForever, modifier = Modifier.requiredSize(96.dp), contentScale = ContentScale.Fit)
        }
    }
}

/**
 * 헤더 현황 (Live Order Header): 현황 구역이 헤더 밑으로 접히면 매장 이름 자리에 올라온다.
 * 원형 고리 32(가운데 번호) + 단계(18 Semibold) + '· 내 앞 n건' / '· 받아 가세요' / 2건 이상이면 '· 외 n건'.
 */
@Composable
fun LiveOrderHeaderTitle(bars: List<LiveBarView>, modifier: Modifier = Modifier) {
    val first = bars.firstOrNull() ?: return
    val c = Ormo.colors
    val detail = when {
        bars.size >= 2 -> "· 외 ${bars.size - 1}건"
        first.stage == LiveBarStage.READY -> "· 받아 가세요"
        first.aheadCount != null -> "· 내 앞 ${first.aheadCount}건"
        else -> null
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
        LiveRing(first.stage, first.pickupNumber)
        Text(stageLabel(first.stage), style = OrmoType.bodyXlSemibold, color = c.textPrimary, maxLines = 1)
        if (detail != null) Text(detail, style = OrmoType.bodyMdRegular, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 원형 진행 고리 32: 12시부터 시계 방향, 끝 둥글게 — 확인 중 1/3 · 만드는 중 2/3 · 픽업 대기 꽉 참.
 * 단계가 바뀌면 고리가 차오르며 살짝 커졌다 돌아온다(1 → 1.04 → 1, 0.3초). 픽업 대기면 32 → 60 (35% → 0) 물결이 1.2초마다.
 */
@Composable
private fun LiveRing(stage: LiveBarStage, number: Int?) {
    val c = Ormo.colors
    val reduced = rememberReducedMotion()
    val sweep by animateFloatAsState(stageFraction(stage) * 360f, tween(if (reduced) 0 else 500, easing = LiveFloatEasing), label = "ring-sweep")
    val bump = remember { Animatable(1f) }
    var last by remember { mutableStateOf(stage) }
    LaunchedEffect(stage) {
        if (last != stage) {
            last = stage
            if (!reduced) {
                bump.animateTo(1.04f, tween(150))
                bump.animateTo(1f, tween(150))
            }
        }
    }
    val ready = stage == LiveBarStage.READY
    val ripple by rememberRipple(periodMs = 1_200, activeMs = 1_200, reduced = reduced)
    val brand = c.fillBrand
    val trackColor = c.track
    Box(Modifier.size(32.dp).scale(bump.value), contentAlignment = Alignment.Center) {
        if (ready) {
            Canvas(Modifier.requiredSize(60.dp)) {
                val d = (32f + (60f - 32f) * ripple).dp.toPx()
                drawCircle(brand.copy(alpha = 0.35f * (1f - ripple)), radius = d / 2f)
            }
        }
        Canvas(Modifier.size(32.dp)) {
            val stroke = 3.4.dp.toPx()
            val inset = stroke / 2f
            val arcSize = GeoSize(size.width - stroke, size.height - stroke)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(brand, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(number?.toString() ?: "-", style = OrmoType.bodySmSemibold, color = c.textBrand)
    }
}

/**
 * 펼침 패널 (헤더 현황을 눌렀을 때, 주문 2건 이상): 헤더 바로 아래에 현황 막대가 줄줄이(구분선 없음, 아래 8).
 * 화면의 60%까지, 넘치면 패널 안에서 스크롤. 막대를 누르면 그 주문 상세.
 */
@Composable
fun LiveOrderExpandedBars(bars: List<LiveBarView>, maxHeight: Dp, onOrder: (orderId: String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
        bars.forEach { bar -> LiveOrderFlatBar(bar, onClick = { onOrder(bar.orderId) }) }
        Box(Modifier.height(8.dp))
    }
}
