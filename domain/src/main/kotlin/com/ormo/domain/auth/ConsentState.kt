package com.ormo.domain.auth

enum class ConsentId(val required: Boolean) {
    /** [필수] 서비스 이용약관 */
    TERMS(true),

    /** [필수] 개인정보 수집·이용 */
    PRIVACY(true),
}

/**
 * 약관 동의 시트 (디자인 0.2.5): 처음엔 모두 비어 있고 [동의하고 가입하기] 꺼짐.
 * [전체 동의]로 모두 체크, 하나라도 해제하면 전체 동의도 해제(= 파생값). 필수 항목이 모두 체크되어야 가입할 수 있다.
 * 동의 시각과 약관 버전은 서버에 저장한다 (요청에 시각을 보내지 않고 서버가 기록).
 */
data class ConsentState(val checked: Set<ConsentId> = emptySet()) {
    val allChecked: Boolean get() = checked.containsAll(ConsentId.entries)

    val canSubmit: Boolean get() = ConsentId.entries.filter { it.required }.all { it in checked }

    fun isChecked(id: ConsentId): Boolean = id in checked

    fun toggleAll(): ConsentState = if (allChecked) ConsentState() else ConsentState(ConsentId.entries.toSet())

    fun toggle(id: ConsentId): ConsentState =
        ConsentState(if (id in checked) checked - id else checked + id)
}
