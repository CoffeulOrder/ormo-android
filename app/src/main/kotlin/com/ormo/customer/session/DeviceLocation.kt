package com.ormo.customer.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 휴대폰의 위치 권한(대략적 위치)이 켜져 있는가 */
fun hasCoarseLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * 기기의 대략적인 현재 위치 (위도, 경도). 위치는 **기기 안에서 거리 계산에만** 쓰고 서버로 보내지 않는다.
 * 마지막으로 알려진 위치가 있으면 바로, 없으면 한 번만 새로 받는다 (최대 3초). 권한이 없거나 못 받으면 null.
 */
@SuppressLint("MissingPermission")
suspend fun currentApproxLocation(context: Context): Pair<Double, Double>? {
    if (!hasCoarseLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
    val last: Location? = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null) return last.latitude to last.longitude
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: providers.firstOrNull() ?: return null
    val fresh = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            runCatching {
                manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { loc -> if (cont.isActive) cont.resume(loc) }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
    return fresh?.let { it.latitude to it.longitude }
}
