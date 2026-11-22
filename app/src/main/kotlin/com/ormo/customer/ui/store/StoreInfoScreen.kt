package com.ormo.customer.ui.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.ormo.core.Outcome
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.DelayedSkeleton
import com.ormo.customer.ui.components.InfoRow
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.SkeletonBlock
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Space
import com.ormo.data.store.StoreLoadFailure
import com.ormo.data.store.StoreRepository
import com.ormo.domain.store.StoreInfo
import com.ormo.domain.store.StoreInfoRules

/**
 * 매장 정보 / 판매자 정보 (디자인 1.2.1): 위치, 요일별 운영시간, 공지(없으면 묶음 숨김), 판매자 정보 6줄(신고번호 없으면 '준비 중'),
 * 하단 필수 고지 — 'ORMO는 통신판매중개자로 …' (전자상거래법상 필수라 항상 보여준다).
 * 주문 가능 여부는 운영시간이 아니라 매장의 영업 상태로 판단하므로, 여기 운영시간은 안내용이다.
 */
@Composable
fun StoreInfoScreen(storeId: String, repo: StoreRepository, onBack: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Outcome<StoreInfo, StoreLoadFailure>?>(initialValue = null, storeId, attempt) {
        value = null
        value = repo.loadInfo(storeId)
    }
    val c = Ormo.colors
    Column(Modifier.fillMaxSize().background(c.bgDefault)) {
        OrmoTopNav(title = "매장 정보", onBack = onBack)
        val r = result
        if (r is Outcome.Fail) {
            Column(
                Modifier.fillMaxSize().padding(Space.pageMargin),
                verticalArrangement = Arrangement.spacedBy(Space.s8, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("매장 정보를 불러오지 못했어요", style = OrmoType.bodyLgSemibold, color = c.textPrimary)
                OrmoButton("다시 시도", { attempt++ }, Modifier.padding(top = Space.s16), kind = ButtonKind.WeakNeutral, size = ButtonSize.M)
            }
        } else {
            DelayedSkeleton(
                loading = r == null,
                skeleton = {
                    Column(Modifier.padding(Space.pageMargin), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                        SkeletonBlock(Modifier.fillMaxWidth().height2(28))
                        SkeletonBlock(Modifier.fillMaxWidth().height2(160))
                    }
                },
            ) {
                val view = (r as? Outcome.Ok)?.value?.let { StoreInfoRules.view(it) }
                val name = (r as? Outcome.Ok)?.value?.name.orEmpty()
                if (view != null) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Column(Modifier.padding(horizontal = Space.pageMargin, vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                            Text(name, style = OrmoType.displayXsSemibold, color = c.textPrimary)
                            view.location?.let { Text(it, style = OrmoType.bodyMdRegular, color = c.textSecondary) }
                        }
                        OrmoThickDivider()
                        Section("운영시간") { view.hours.forEach { InfoRow(it.label, it.value, labelWidth = 32) } }
                        if (view.notice != null) {
                            OrmoThickDivider()
                            Section("공지") { Text(view.notice!!, style = OrmoType.bodyMdRegular, color = c.textPrimary) }
                        }
                        OrmoThickDivider()
                        Section("판매자 정보") { view.seller.forEach { InfoRow(it.label, it.value, labelWidth = 148) } }
                        Text(
                            view.disclosure,
                            style = OrmoType.bodyXsMedium,
                            color = c.textTertiary,
                            modifier = Modifier.fillMaxWidth().background(c.bgLower).padding(Space.pageMargin),
                        )
                        Box(Modifier.fillMaxWidth().background(c.bgLower).navigationBarsPadding())
                    }
                }
            }
        }
    }
}

private fun Modifier.height2(dp: Int): Modifier = this.height(dp.dp)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.pageMargin, vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        Text(title, style = OrmoType.bodyLgBold, color = Ormo.colors.textStrong)
        content()
    }
}
