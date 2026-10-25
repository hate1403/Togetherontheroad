package io.togetherroad.app

import android.content.Context
import android.content.Intent
import ai.pleos.playground.navi.data.NavigationScreenRequest
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.constants.NaviMode
import io.togetherroad.core.Place
import kotlin.math.abs

/** Bench only: no Vehicle State parking integration yet. No background or remote route execution. */
class PlatformNavigation(private val context: Context) : NavigationGateway {
    override val label = "PLEOS · NaviHelper 3.0.5 · 정차 벤치 검증 전용"
    private val handler = Handler(Looper.getMainLooper())
    private val helper = NaviHelper(context.applicationContext)
    private val calls=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var ready = false
    private var closed = false
    private var used = false
    private var routed = false
    private var guiding = false
    private var matchedDestination = false
    private var target: Place? = null
    private var pending: ((Boolean, String) -> Unit)? = null
    private var failure = "초기화 대기 중"
    private val timeout = Runnable { finish(false, "내장 내비 결과 확인 시간 초과 · $failure") }
    private val listener = object : NaviHelperEventListener {
        // Connect 2.1.0's service sends the older completion event; newer services use onRoutingCompleted.
        @Suppress("DEPRECATION")
        override fun onRouteEnded() { completed() }
        override fun onRoutingCompleted() { completed() }
        override fun onRouteStateInfo(info:RouteStateInfo) {
            handler.post {
                if(pending!=null){
                    guiding=info.naviMode==NaviMode.ROUTE_GUIDING
                    failure="내비 상태: ${info.naviMode}"
                    tryComplete()
                }
            }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            handler.post {
                val place=target
                if(pending!=null&&place!=null){
                    matchedDestination=abs(destinationInfo.latitude-place.latitude)<.001&&abs(destinationInfo.longitude-place.longitude)<.001
                    tryComplete()
                }
            }
        }
        override fun onError(errorCode: NaviErrorCode) { handler.post { finish(false, "SDK 오류: $errorCode") } }
    }
    private fun completed(){handler.post{if(pending!=null){routed=true;calls.execute{if(!closed)try{helper.getRouteStateInfo();helper.getDestinationInfo()}catch(e:Exception){handler.post{finish(false,"내비 상태 조회 실패")}}}}}}
    private fun tryComplete(){if(pending!=null&&routed&&guiding&&matchedDestination)finish(true,"내장 내비 경로 안내 상태와 목적지 좌표를 확인했습니다")}
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
        used = true; pending = callback; target = place; routed = false; guiding=false; matchedDestination=false
        handler.postDelayed(timeout, 30_000)
        try {
            calls.execute{if(!closed)try{
                helper.requestRoute(RouteInfo(latitude = place.latitude, longitude = place.longitude,
                    displayLatitude = null, displayLongitude = null, poiName = place.name,
                    poiId = "", address = "", poiSubId = "0"))
            }catch(e:Exception){handler.post{finish(false,"내비 요청 실패: ${e.javaClass.simpleName}")}}}
        } catch (e: Exception) { finish(false, "요청 실패: ${e.javaClass.simpleName}") }
    }
    private fun finish(ok: Boolean, message: String) {
        val cb = pending ?: return
        pending = null; target = null; handler.removeCallbacks(timeout); cb(ok, message)
    }
    fun applyWhenReady(place: Place, callback: (Boolean, String) -> Unit) {
        var count=0
        fun attempt(){if(closed)return;if(ready)apply(place,callback)else if(++count<25)handler.postDelayed({attempt()},150)else callback(false,failure)}
        attempt()
    }
    fun showNavigation(callback: (Boolean, String) -> Unit) {
        var tries=0
        fun launch() {
            if(closed) return
            if(ready) {
                try {
                    calls.execute{if(!closed)try{
                        helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
                        handler.post{if(!closed)callback(true,"내장 내비게이션 표시 요청")}
                    }catch(e:Exception){handler.post{if(!closed)fallbackNavigation(callback)}}}
                } catch(e:Exception) { fallbackNavigation(callback) }
            } else if(++tries<20) handler.postDelayed({launch()},150)
            else fallbackNavigation(callback)
        }
        launch()
    }
    private fun fallbackNavigation(callback:(Boolean,String)->Unit) {
        val intent=context.packageManager.getLaunchIntentForPackage("ai.umos.maps.android.navigation.app")
        if(intent!=null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            callback(true,"에뮬레이터 내장 내비게이션 실행")
        } else callback(false,"내장 내비게이션 실행 불가")
    }
    override fun close() {
        closed = true; pending = null; handler.removeCallbacksAndMessages(null)
        val registered=ready;ready=false
        calls.execute{try{if(registered)helper.removeListener(listener);helper.release()}catch(_:Exception){}}
        calls.shutdown()
    }
}
