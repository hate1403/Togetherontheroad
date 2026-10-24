package io.togetherroad.app
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.togetherroad.core.Place
class PlatformNavigation(context: Context) : NavigationGateway {
    override val label = "REPLAY · 내비게이션 모의 응답"
    private val handler = Handler(Looper.getMainLooper())
    override fun apply(place: Place, callback: (Boolean, String) -> Unit) {
        handler.postDelayed({ callback(true, "REPLAY 적용 완료 · 실제 내비 변경 없음") }, 700)
    }
    override fun close() { handler.removeCallbacksAndMessages(null) }
}
