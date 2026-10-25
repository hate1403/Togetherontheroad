package io.togetherroad.app
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.togetherroad.core.Place
class PlatformNavigation(context: Context) : NavigationGateway {
    override val label = "일반 Android"
    private val handler = Handler(Looper.getMainLooper())
    override fun apply(place: Place, callback: (Boolean, String) -> Unit) {
        callback(false, "이 기기에는 Pleos 내장 내비게이션이 없습니다")
    }
    fun showNavigation(callback: (Boolean, String) -> Unit) { callback(false, "일반 Android AVD") }
    fun applyWhenReady(place: Place, callback: (Boolean, String) -> Unit) = apply(place, callback)
    override fun close() { handler.removeCallbacksAndMessages(null) }
}
