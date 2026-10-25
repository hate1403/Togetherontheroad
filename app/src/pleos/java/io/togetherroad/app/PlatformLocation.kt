package io.togetherroad.app
import android.content.Context
import android.content.pm.PackageManager
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

class PlatformLocation(private val context:Context):LiveLocationSource {
    private val sdk=FusedLocation(context.applicationContext)
    private val phone=PhoneLocation(context)
    private var lastFused=0L
    private var initialized=false
    private var callback:FusedLocationListener?=null
    override fun start(onFix:(LiveFix)->Unit,onStatus:(String)->Unit){
        // The Android fallback is a real observation source, explicitly distinguished from Pleos.
        phone.start({fix->if(System.currentTimeMillis()-lastFused>15000)onFix(fix)},onStatus)
        if(context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION")!=PackageManager.PERMISSION_GRANTED)return
        try{
            if(!sdk.isSupported())return
            sdk.initialize();initialized=true
            val l=object:FusedLocationListener{
                override fun onChanged(location:LocationData?){
                    val v=location?:return
                    if(kotlin.math.abs(System.currentTimeMillis()-v.timestampMillis)>30000)return
                    lastFused=System.currentTimeMillis()
                    onFix(LiveFix(v.latitude,v.longitude,v.timestampMillis,"PLEOS_FUSED",null))
                }
                override fun onError(exception:Exception){onStatus("Pleos 위치 연결 확인 중 · Android 위치 사용 가능")}
            }
            callback=l;sdk.registerFusedLocationCallback(l);sdk.getLastKnownLocation(l)
        }catch(_:Exception){onStatus("Android 위치 서비스로 연결했습니다")}
    }
    override fun close(){phone.close();try{callback?.let{sdk.unregisterFusedLocationCallback(it)};if(initialized)sdk.release()}catch(_:Exception){};callback=null;initialized=false}
}
