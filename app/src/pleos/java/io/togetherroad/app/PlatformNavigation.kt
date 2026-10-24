package io.togetherroad.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import io.togetherroad.core.Place
import kotlin.math.abs

/** Bench only: no Vehicle State parking integration yet. No background or remote route execution. */
class PlatformNavigation(context: Context) : NavigationGateway {
    override val label = "PLEOS · NaviHelper 3.0.5 · 정차 벤치 검증 전용"
    private val handler = Handler(Looper.getMainLooper())
    private val helper = NaviHelper(context.applicationContext)
    private var ready = false
    private var closed = false
    private var used = false
    private var routed = false
    private var target: Place? = null
    private var pending: ((Boolean, String) -> Unit)? = null
    private var failure = "초기화 대기 중"
    private val timeout = Runnable { finish(false, "SDK 결과 시간 초과 · 앱 재시작 후 벤치 재검증") }
    private val listener = object : NaviHelperEventListener {
        override fun onRoutingCompleted() {
            handler.post { if (pending != null) { routed = true; helper.getDestinationInfo() } }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            handler.post {
                val place = target
                if (pending != null && routed && place != null &&
                    abs(destinationInfo.latitude-place.latitude) < .001 &&
                    abs(destinationInfo.longitude-place.longitude) < .001) {
                    finish(true, "SDK 경로 탐색 완료 및 목적지 좌표 확인")
                }
            }
        }
        override fun onError(errorCode: NaviErrorCode) { handler.post { finish(false, "SDK 오류: $errorCode") } }
    }
    init {
        try {
            helper.initialize({ handler.post {
                if (!closed) { helper.addListener(listener); ready = true }
            } }, { error -> handler.post { failure = "SDK 초기화 실패: ${error.javaClass.simpleName}" } })
        } catch (e: Exception) { failure = "Pleos 서비스 연결 불가: ${e.javaClass.simpleName}" }
    }
    override fun apply(place: Place, callback: (Boolean, String) -> Unit) {
        if (closed || !ready) { callback(false, failure); return }
        // No request IDs in SDK callbacks: one request per gateway lifetime avoids reusing late callbacks.
        if (used) { callback(false, "벤치 안전 제한: 앱 재시작 후 새 요청 가능"); return }
        used = true; pending = callback; target = place; routed = false
        handler.postDelayed(timeout, 15_000)
        try {
            helper.requestRoute(RouteInfo(latitude = place.latitude, longitude = place.longitude,
                displayLatitude = null, displayLongitude = null, poiName = place.name,
                poiId = "", address = "", poiSubId = "0"))
        } catch (e: Exception) { finish(false, "요청 실패: ${e.javaClass.simpleName}") }
    }
    private fun finish(ok: Boolean, message: String) {
        val cb = pending ?: return
        pending = null; target = null; handler.removeCallbacks(timeout); cb(ok, message)
    }
    override fun close() {
        closed = true; pending = null; handler.removeCallbacksAndMessages(null)
        try { if (ready) helper.removeListener(listener); helper.release() } catch (_: Exception) { }
        ready = false
    }
}
