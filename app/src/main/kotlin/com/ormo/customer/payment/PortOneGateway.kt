package com.ormo.customer.payment

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ormo.data.payment.PaymentGateway
import com.ormo.data.payment.PgRequest
import com.ormo.data.payment.PgResult
import com.ormo.domain.auth.UserProfile
import com.ormo.domain.payment.PgCheckout
import io.portone.sdk.android.PortOne
import io.portone.sdk.android.payment.PaymentActivity
import io.portone.sdk.android.type.entity.Currency
import io.portone.sdk.android.type.entity.Customer
import io.portone.sdk.android.type.entity.EasyPayProvider
import io.portone.sdk.android.type.entity.PaymentPayMethod
import io.portone.sdk.android.type.request.PaymentRequest
import io.portone.sdk.android.type.request.PaymentRequestUnionEasyPay
import io.portone.sdk.android.type.response.PaymentResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * 포트원 V2 결제창 (KG이니시스 + 간편결제). 서버가 주문과 함께 준 설정([PgCheckout])으로 SDK 화면을 연다.
 *
 * - 결과는 알려 줄 뿐이고, 결제가 됐는지는 항상 서버에 확인한다 (`PaymentCoordinator`).
 * - SDK 의 `registerForPaymentActivity` 는 **그냥 닫은 경우 아무것도 알려 주지 않아**, 결과를 직접 받는다.
 * - 결제 중에 앱 프로세스가 죽으면 기다리던 곳이 없어지므로 결과를 버린다 → 다음 실행 때 저장해 둔 주문을 서버에 묻는다.
 * - 결제창에서 돌아오면 `PaymentCoordinator` 가 승인(MS-27 `POST /payments/confirm`)을 요청한다.
 *
 * @param fallback 서버가 포트원 설정을 주지 않을 때 쓸 결제 방식
 * @param customer KG이니시스가 요구하는 구매자 이름·이메일
 */
class PortOneGateway(
    private val fallback: PaymentGateway,
    private val customer: () -> UserProfile?,
    private val log: ((String) -> Unit)? = null,
) : PaymentGateway {

    private var activity: WeakReference<ComponentActivity>? = null
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var waiting: CompletableDeferred<PgResult>? = null
    private var insetsApplied = false

    private companion object {
        /** SDK 결제창이 결과를 담는 이름 (SDK 안의 `PortOne.RESPONSE` 는 밖에서 못 쓴다 — SDK 버전을 올리면 확인) */
        const val RESPONSE_EXTRA = "response"
    }

    /** `MainActivity.onCreate` 에서 부른다 */
    fun attach(host: ComponentActivity) {
        activity = WeakReference(host)
        launcher = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onResult)
        keepPaymentScreenBelowSystemBars(host.application)
    }

    override suspend fun pay(request: PgRequest): PgResult {
        val checkout = request.checkout ?: return fallback.pay(request)
        return withContext(Dispatchers.Main.immediate) {
            val host = activity?.get()
            val target = launcher
            if (host == null || target == null) return@withContext PgResult.Failed("PG_NO_SCREEN", null)
            val result = CompletableDeferred<PgResult>()
            waiting = result
            try {
                log?.invoke("PortOne 결제창 열기 paymentId=${checkout.paymentId} method=${request.method.id} amount=${request.amount}")
                PortOne.requestPayment(host, paymentRequest(checkout, request), target)
                result.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log?.invoke("PortOne 결제창을 열지 못함: $e")
                PgResult.Failed("PG_OPEN_FAILED", e.message)
            } finally {
                if (waiting === result) waiting = null
            }
        }
    }

    private fun paymentRequest(checkout: PgCheckout, request: PgRequest): PaymentRequest {
        val easyPay = when (request.method.id) {
            "toss" -> EasyPayProvider.TOSSPAY
            "naver" -> EasyPayProvider.NAVERPAY
            "kakao" -> EasyPayProvider.KAKAOPAY
            else -> null
        }
        val buyer = customer()
        return PaymentRequest(
            storeId = checkout.storeId,
            paymentId = checkout.paymentId,
            orderName = checkout.orderName,
            channelKey = checkout.channelKey,
            totalAmount = request.amount.toLong(),
            currency = Currency.KRW,
            payMethod = if (easyPay == null) PaymentPayMethod.CARD else PaymentPayMethod.EASY_PAY,
            customer = Customer(fullName = buyer?.name?.takeIf { it.isNotBlank() } ?: "ORMO 고객", email = buyer?.email),
            easyPay = easyPay?.let { PaymentRequestUnionEasyPay(easyPayProvider = it) },
        )
    }

    private fun onResult(result: ActivityResult) {
        val response = result.data?.let { data ->
            if (Build.VERSION.SDK_INT >= 33) {
                data.getParcelableExtra(RESPONSE_EXTRA, PaymentResponse::class.java)
            } else {
                @Suppress("DEPRECATION")
                data.getParcelableExtra(RESPONSE_EXTRA)
            }
        }
        log?.invoke(
            if (response == null) {
                "PortOne 결제창이 결과 없이 닫힘"
            } else {
                "PortOne 결과 code=${response.code} message=${response.message} pgCode=${response.pgCode} pgMessage=${response.pgMessage}"
            },
        )
        waiting?.complete(
            when {
                response == null -> PgResult.Canceled
                response.code == null -> PgResult.Approved(response.txId)
                else -> PgResult.Failed(response.code, response.message)
            },
        )
    }

    /** SDK 화면은 시스템 바 여백을 두지 않아 (Android 15+ 는 화면 끝까지 그려짐) 앱에서 넣어 준다 */
    private fun keepPaymentScreenBelowSystemBars(app: Application) {
        if (insetsApplied) return
        insetsApplied = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(started: Activity) {
                if (started !is PaymentActivity) return
                val content = started.findViewById<View>(android.R.id.content) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
                    val bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
                    )
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    WindowInsetsCompat.CONSUMED
                }
                ViewCompat.requestApplyInsets(content)
            }

            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
