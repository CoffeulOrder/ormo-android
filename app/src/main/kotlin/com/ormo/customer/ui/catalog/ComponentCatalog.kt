package com.ormo.customer.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ormo.customer.ui.components.BadgeKind
import com.ormo.customer.ui.components.BottomCta
import com.ormo.customer.ui.components.ButtonKind
import com.ormo.customer.ui.components.ButtonSize
import com.ormo.customer.ui.components.DelayedSkeleton
import com.ormo.customer.ui.components.DialogLayout
import com.ormo.customer.ui.components.DialogTone
import com.ormo.customer.ui.components.FieldSize
import com.ormo.customer.ui.components.NavLayout
import com.ormo.customer.ui.components.OrmoBadge
import com.ormo.customer.ui.components.OrmoBottomSheet
import com.ormo.customer.ui.components.OrmoButton
import com.ormo.customer.ui.components.OrmoCheckbox
import com.ormo.customer.ui.components.OrmoDialog
import com.ormo.customer.ui.components.OrmoDivider
import com.ormo.customer.ui.components.OrmoField
import com.ormo.customer.ui.components.OrmoIcons
import com.ormo.customer.ui.components.OrmoPasswordField
import com.ormo.customer.ui.components.OrmoSnackbarHost
import com.ormo.customer.ui.components.OrmoSnackbarState
import com.ormo.customer.ui.components.OrmoSwitch
import com.ormo.customer.ui.components.OrmoThickDivider
import com.ormo.customer.ui.components.OrmoTopNav
import com.ormo.customer.ui.components.PasswordRuleRow
import com.ormo.customer.ui.components.SkeletonBlock
import com.ormo.customer.ui.components.SnackbarDurations
import com.ormo.customer.ui.components.TermsRow
import com.ormo.customer.ui.components.ormoShadow
import com.ormo.customer.ui.components.shadowLarge
import com.ormo.customer.ui.components.shadowSmall
import com.ormo.customer.ui.theme.Ormo
import com.ormo.customer.ui.theme.OrmoColors
import com.ormo.customer.ui.theme.OrmoTheme
import com.ormo.customer.ui.theme.OrmoType
import com.ormo.customer.ui.theme.Shapes
import com.ormo.customer.ui.theme.Space
import androidx.compose.material3.Icon

/**
 * 컴포넌트 카탈로그: 디자인 토큰과 공통 컴포넌트를 한 화면에서 훑어보는 개발용 화면 (debug 전용 진입).
 * 화면을 만들 때 같은 컴포넌트를 재사용하므로, 여기서 명세(4장)와 맞는지 눈으로 확인한다.
 */
@Composable
fun ComponentCatalog(modifier: Modifier = Modifier) {
    val c = Ormo.colors
    val snackbar = remember { OrmoSnackbarState() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(c.bgDefault)) {
        Column(Modifier.fillMaxSize()) {
            OrmoTopNav(title = "컴포넌트 카탈로그", onBack = {})
            OrmoDivider()
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.pageMargin),
                verticalArrangement = Arrangement.spacedBy(Space.s32),
            ) {
                item { Section("색 (Semantic)") { ColorSwatches(c) } }
                item { Section("타이포 (Pretendard)") { TypeSamples() } }
                item { Section("버튼") { Buttons() } }
                item { Section("입력칸") { Fields() } }
                item { Section("뱃지 · 체크 · 스위치 · 구분선") { Misc() } }
                item { Section("그림자 · 스켈레톤") { ShadowsAndSkeleton() } }
                item { Section("상단 내비게이션") { Navs() } }
                item {
                    Section("다이얼로그 · 시트 · 스낵바") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                            OrmoButton("다이얼로그 (가로)", { dialog = "h" }, Modifier.fillMaxWidth(), ButtonKind.WeakNeutral)
                            OrmoButton("다이얼로그 (세로)", { dialog = "v" }, Modifier.fillMaxWidth(), ButtonKind.WeakNeutral)
                            OrmoButton("다이얼로그 (확인만)", { dialog = "s" }, Modifier.fillMaxWidth(), ButtonKind.WeakNeutral)
                            OrmoButton("다이얼로그 (Critical)", { dialog = "c" }, Modifier.fillMaxWidth(), ButtonKind.WeakNeutral)
                            OrmoButton("바텀 시트", { sheet = true }, Modifier.fillMaxWidth(), ButtonKind.WeakBrand)
                            OrmoButton(
                                "스낵바 (되돌리기 4초)",
                                { snackbar.show("아메리카노를 삭제했어요", SnackbarDurations.UNDO, "되돌리기") },
                                Modifier.fillMaxWidth(), ButtonKind.WeakBrand,
                            )
                            OrmoButton(
                                "스낵바 (담기 2초)",
                                { snackbar.show("장바구니에 메뉴를 추가했어요", SnackbarDurations.ADDED) },
                                Modifier.fillMaxWidth(), ButtonKind.WeakBrand,
                            )
                        }
                    }
                }
            }
            BottomCta {
                OrmoButton("28,000원 결제하기", {}, Modifier.fillMaxWidth(), size = ButtonSize.XL)
                OrmoButton("다음에", {}, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            }
        }
        OrmoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter), bottomOffset = 160.dp)
    }

    when (dialog) {
        "h" -> OrmoDialog("주문을 취소할까요?", "취소하기", { dialog = null }, body = "결제 금액 전액을 환불해 드려요", dismissLabel = "닫기", onDismiss = { dialog = null })
        "v" -> OrmoDialog("결제를 5번 실패했어요", "장바구니로 이동", { dialog = null }, body = "장바구니에서 다시 주문해주세요", dismissLabel = "닫기", onDismiss = { dialog = null }, layout = DialogLayout.Vertical)
        "s" -> OrmoDialog("진행 중인 주문이 2건 있어요", "확인", { dialog = null }, body = "주문이 끝난 뒤에 탈퇴할 수 있어요")
        "c" -> OrmoDialog("탈퇴할까요?", "탈퇴하기", { dialog = null }, body = "이름과 이메일은 바로 삭제돼요", dismissLabel = "취소", onDismiss = { dialog = null }, tone = DialogTone.Critical)
    }
    if (sheet) {
        OrmoBottomSheet(
            onDismissRequest = { sheet = false },
            title = "알림을 받아볼까요?",
            footer = {
                OrmoButton("알림 받기", { sheet = false }, Modifier.fillMaxWidth(), size = ButtonSize.XL)
                OrmoButton("다음에", { sheet = false }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            },
        ) {
            Text("알림을 켜면 주문 진행 상황을 바로 알 수 있어요", style = OrmoType.bodyMdRegular, color = c.textSecondary)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        Text(title, style = OrmoType.bodyLgBold, color = Ormo.colors.textStrong)
        content()
    }
}

@Composable
private fun ColorSwatches(c: OrmoColors) {
    val groups = listOf(
        "텍스트" to listOf("Primary" to c.textPrimary, "Secondary" to c.textSecondary, "Tertiary" to c.textTertiary, "Quaternary" to c.textQuaternary, "Strong" to c.textStrong, "Brand" to c.textBrand, "Success" to c.textSuccess, "Danger" to c.textDanger, "Warning" to c.textWarning),
        "채움" to listOf("Brand" to c.fillBrand, "Brand Weak" to c.fillBrandWeak, "Neutral" to c.fillNeutral, "Neutral Weak" to c.fillNeutralWeak, "Success" to c.fillSuccess, "Success Weak" to c.fillSuccessWeak, "Danger" to c.fillDanger, "Danger Weak" to c.fillDangerWeak, "Warning Weak" to c.fillWarningWeak, "Pressed" to c.fillPressed),
        "아이콘" to listOf("Primary" to c.iconPrimary, "Secondary" to c.iconSecondary, "Tertiary" to c.iconTertiary, "Quaternary" to c.iconQuaternary, "Brand" to c.iconBrand, "Success" to c.iconSuccess, "Danger" to c.iconDanger, "Warning" to c.iconWarning),
        "배경·테두리" to listOf("Default" to c.bgDefault, "Lower" to c.bgLower, "Dim" to c.bgDim, "Border" to c.borderDefault),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        groups.forEach { (group, items) ->
            Text(group, style = OrmoType.bodySmSemibold, color = c.textSecondary)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                items.forEach { (name, color) ->
                    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(40.dp).background(Color.White, Shapes.control40).background(color, Shapes.control40))
                        Text(name, style = OrmoType.bodyXsMedium, color = c.textTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeSamples() {
    val c = Ormo.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
        Text("27", style = OrmoType.display2xlSemibold, color = c.textStrong)
        Text("display-sm 아메리카노", style = OrmoType.displaySmSemibold, color = c.textPrimary)
        Text("display-xs 주문 현황", style = OrmoType.displayXsSemibold, color = c.textPrimary)
        Text("body-xl Bold 주문을 취소할까요?", style = OrmoType.bodyXlBold, color = c.textPrimary)
        Text("body-lg Semibold 28,000원 결제하기", style = OrmoType.bodyLgSemibold, color = c.textPrimary)
        Text("body-md Regular 결제 금액 전액을 환불해 드려요", style = OrmoType.bodyMdRegular, color = c.textSecondary)
        Text("body-sm Medium 카드사에 따라 3~5일 걸려요", style = OrmoType.bodySmMedium, color = c.textTertiary)
        Text("body-xs Semibold 품절 · 신규 · 인기", style = OrmoType.bodyXsSemibold, color = c.textTertiary)
    }
}

@Composable
private fun Buttons() {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        listOf(ButtonSize.XL, ButtonSize.L, ButtonSize.M, ButtonSize.S).forEach { s ->
            OrmoButton("Solid ${s.name} ${s.height.value.toInt()}", {}, Modifier.fillMaxWidth(), size = s)
        }
        OrmoButton("Critical L", {}, Modifier.fillMaxWidth(), ButtonKind.Critical)
        OrmoButton("Weak (Brand) L", {}, Modifier.fillMaxWidth(), ButtonKind.WeakBrand)
        OrmoButton("Weak (Neutral) L", {}, Modifier.fillMaxWidth(), ButtonKind.WeakNeutral)
        OrmoButton("Outline L", {}, Modifier.fillMaxWidth(), ButtonKind.Outline)
        OrmoButton("Ghost 비밀번호 찾기", {}, Modifier.fillMaxWidth(), ButtonKind.Ghost)
        OrmoButton("Disabled XL", {}, Modifier.fillMaxWidth(), size = ButtonSize.XL, enabled = false)
        OrmoButton("Loading XL", {}, Modifier.fillMaxWidth(), size = ButtonSize.XL, loading = true)
    }
}

@Composable
private fun Fields() {
    var email by remember { mutableStateOf("kim") }
    var empty by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("abc12345") }
    var bad by remember { mutableStateOf("abc") }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s24)) {
        OrmoField(empty, { empty = it }, label = "이름", placeholder = "이름을 입력해주세요", helper = "1~20자")
        OrmoField(email, { email = it }, label = "학교 이메일", suffix = "@eulji.ac.kr", trailing = { Icon(OrmoIcons.ChevronDown, null, tint = Ormo.colors.iconSecondary) })
        OrmoPasswordField(pw, { pw = it }, label = "비밀번호")
        OrmoField(bad, { bad = it }, label = "비밀번호 확인", error = "비밀번호가 일치하지 않아요", size = FieldSize.M)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
            PasswordRuleRow("8자 이상 72바이트 이하", satisfied = true)
            PasswordRuleRow("영문과 숫자를 함께 사용", satisfied = false)
        }
    }
}

@Composable
private fun Misc() {
    var a by remember { mutableStateOf(true) }
    var b by remember { mutableStateOf(false) }
    var sw1 by remember { mutableStateOf(true) }
    var sw2 by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
            OrmoBadge("필수", BadgeKind.Required)
            OrmoBadge("선택", BadgeKind.Optional)
            OrmoBadge("인기", BadgeKind.Popular)
            OrmoBadge("신규", BadgeKind.New)
            OrmoBadge("품절", BadgeKind.SoldOut)
        }
        OrmoCheckbox(a, { a = it }, label = "원형 체크박스 (체크됨)")
        OrmoCheckbox(b, { b = it }, label = "원형 체크박스 (해제)")
        TermsRow("전체 동의", a && b, { a = it; b = it }, strong = true)
        TermsRow("(필수) 이용약관 동의", a, { a = it }, onOpen = {})
        TermsRow("(필수) 개인정보 처리방침 동의", b, { b = it }, onOpen = {})
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s16), verticalAlignment = Alignment.CenterVertically) {
            OrmoSwitch(sw1, { sw1 = it }, contentDescription = "주문 알림")
            OrmoSwitch(sw2, { sw2 = it }, contentDescription = "실시간 현황")
            OrmoSwitch(true, null, enabled = false)
        }
        OrmoDivider()
        OrmoDivider(inset = true)
        OrmoThickDivider()
    }
}

@Composable
private fun ShadowsAndSkeleton() {
    val c = Ormo.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s16)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s16)) {
            Box(Modifier.size(width = 120.dp, height = 72.dp).ormoShadow(shadowLarge(c), Shapes.control56).background(c.bgFloated, Shapes.control56), contentAlignment = Alignment.Center) {
                Text("Large", style = OrmoType.bodySmMedium, color = c.textSecondary)
            }
            Box(Modifier.size(width = 120.dp, height = 72.dp).ormoShadow(shadowSmall(c), Shapes.control56).background(c.bgFloated, Shapes.control56), contentAlignment = Alignment.Center) {
                Text("Small", style = OrmoType.bodySmMedium, color = c.textSecondary)
            }
        }
        DelayedSkeleton(loading = true, skeleton = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                SkeletonBlock(Modifier.fillMaxWidth().height(20.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(20.dp))
            }
        }, content = {})
    }
}

@Composable
private fun Navs() {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        OrmoTopNav(title = "Center + 뒤로가기", onBack = {}, applyStatusBarInset = false)
        OrmoTopNav(title = "Left", layout = NavLayout.Left, onBack = {}, applyStatusBarInset = false)
        OrmoTopNav(title = "주문 내역", layout = NavLayout.Large, applyStatusBarInset = false)
    }
}

@Preview(showBackground = true)
@Composable
private fun CatalogPreview() {
    OrmoTheme { ComponentCatalog() }
}
