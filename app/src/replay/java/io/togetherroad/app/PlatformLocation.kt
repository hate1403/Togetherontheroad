package io.togetherroad.app
import android.content.Context
class PlatformLocation(context:Context):LiveLocationSource {
    private val phone=PhoneLocation(context)
    override fun start(onFix:(LiveFix)->Unit,onStatus:(String)->Unit)=phone.start(onFix,onStatus)
    override fun close()=phone.close()
}
