package com.ormo.customer.ui.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ormo.customer.ui.theme.ormoNavigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ormo.core.Outcome
import com.ormo.customer.ui.components.BadgeKind
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.DelayedSkeleton
import com.ormo.customer.ui.components.OrmoBadge
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.SkeletonBlock
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.data.store.StoreLoadFailure
import com.ormo.data.store.StoreRepository
import com.ormo.domain.store.InfoLine
import com.ormo.domain.store.Store
import com.ormo.domain.store.StoreInfo
import com.ormo.domain.store.StoreInfoRules
import com.ormo.domain.store.StoreRules
import com.ormo.domain.store.StoreStatus
import java.time.LocalDateTime

/**
 * 매장 정보 / 판매자 정보 (Figma 1.2.1): 이름 + 영업 상태 뱃지, 위치, 운영시간(같은 시간은 묶어서 오른쪽 정렬), 공지(없으면 묶음 숨김),
 * 판매자 정보 6줄(신고번호 없으면 '준비 중'), 하단 필수 고지 상자 — 'ORMO는 통신판매중개자로 …' (전자상거래법상 필수라 항상 보여준다).
 * 주문 가능 여부는 운영시간이 아니라 매장의 영업 상태로 판단하므로, 여기 운영시간은 안내용이다.
 */
@Composable
fun StoreInfoScreen(storeId: String, repo: StoreRepository, onBack: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Outcome<StoreInfo, StoreLoadFailure>?>(initialValue = null, storeId, attempt) {
        value = null
        value = repo.loadInfo(storeId)
    }
    val store by produceState<Store?>(null, storeId) { value = (repo.loadStores() as? Outcome.Ok)?.value?.all?.firstOrNull { it.id == storeId } }
    val c = Ormo.colors
    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        OrmoTopNav(title = "매장 정보", onBack = onBack)
        val r = result
        if (r is Outcome.Fail) {
            com.ormo.customer.ui.components.OrmoResult(
                icon = com.ormo.customer.ui.components.OrmoIcons.WifiOff,
                title = "매장 정보를 불러오지 못했어요",
                action = "다시 시도" to { attempt++ },
            )
        } else {
            DelayedSkeleton(
                loading = r == null,
                skeleton = {
                    Column(Modifier.padding(Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                        SkeletonBlock(Modifier.fillMaxWidth().height(28.dp))
                        SkeletonBlock(Modifier.fillMaxWidth().height(160.dp))
                    }
                },
            ) {
                val info = (r as? Outcome.Ok)?.value
                if (info != null) {
                    val view = StoreInfoRules.view(info)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        // Figma 1.2.1: 글자 시작 x=24, 매장 이름 글자 y=119
                        // 이름은 줄바꿈, 상태 뱃지는 맨 윗줄의 세로 가운데
                        val firstLine = with(androidx.compose.ui.platform.LocalDensity.current) { OrmoType.bodyXlBold.lineHeight.toDp() }
                        Row(Modifier.padding(start = Space.s24, end = Space.s24, top = 7.dp, bottom = 17.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                            Text(store?.displayName ?: info.name, style = OrmoType.bodyXlBold, color = c.textStrong, modifier = Modifier.weight(1f, fill = false))
                            store?.let { s ->
                                val label = StoreRules.statusLabel(s.status, LocalDateTime.now())
                                Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) {
                                    OrmoBadge(label.text, when (s.status) { is StoreStatus.Open -> BadgeKind.Optional; is StoreStatus.Break -> BadgeKind.Warning; else -> BadgeKind.SoldOut })
                                }
                            }
                        }
                        view.location?.let { Section("위치") { Text(it, style = OrmoType.bodyMdRegular, color = c.textSecondary) } }
                        Section("운영시간") { view.hours.forEach { Line(it) } }
                        if (view.notice != null) Section("공지") { Text(view.notice!!, style = OrmoType.bodyMdRegular, color = c.textPrimary) }
                        OrmoThickDivider(Modifier.padding(top = Space.s8))
                        Section("판매자 정보") { view.seller.forEach { FixedLine(it) } }
                        Text(
                            view.disclosure,
                            style = OrmoType.bodySmRegular,
                            color = c.textSecondary,
                            modifier = Modifier
                                .padding(horizontal = Space.s24, vertical = Space.s8)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.fillNeutralWeak)
                                .padding(Space.s16),
                        )
                        Box(Modifier.ormoNavigationBarsPadding().height(Space.s16))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.s24, vertical = Space.s12), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text(title, style = OrmoType.bodyLgSemibold, color = Ormo.colors.textStrong)
        content()
    }
}

/**
 * 판매자 정보 한 줄: **두 열이 줄마다 같은 고정 너비** — 라벨 칸 [LABEL_WIDTH], 값 칸은 나머지 전부. 어느 쪽이든 길면 칸 안에서 줄바꿈하고
 * (다른 칸을 밀지 않는다), 값은 오른쪽 끝에 붙는다 (Figma 1.2.1: 라벨 왼쪽 · 값 오른쪽 양쪽 정렬).
 */
@Composable
private fun FixedLine(line: InfoLine) {
    val c = Ormo.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s16), verticalAlignment = Alignment.Top) {
        Text(line.label, style = OrmoType.bodyMdRegular, color = c.textTertiary, modifier = Modifier.width(LABEL_WIDTH))
        // 양쪽 정렬: 라벨은 왼쪽 끝, 값은 오른쪽 끝에 붙고 (여러 줄이어도 오른쪽 정렬), 줄바꿈은 각자의 칸 안에서
        Text(line.value, style = OrmoType.bodyMdRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

private val LABEL_WIDTH = 96.dp

/** 라벨은 왼쪽(Text/Tertiary), 값은 오른쪽 정렬 (Figma 1.2.1) */
@Composable
private fun Line(line: InfoLine) {
    val c = Ormo.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s16)) {
        Text(line.label, style = OrmoType.bodyMdRegular, color = c.textTertiary)
        Text(line.value, style = OrmoType.bodyMdRegular, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
