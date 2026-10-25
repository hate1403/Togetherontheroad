package io.togetherroad.app
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.*

data class LiveFix(val latitude:Double,val longitude:Double,val observedAt:Long,val source:String,val accuracyMeters:Float?)
interface LiveLocationSource:AutoCloseable{fun start(onFix:(LiveFix)->Unit,onStatus:(String)->Unit)}
/** Android provider observations only; no coordinates or fresh timestamps are fabricated. */
class PhoneLocation(private val context:Context):LiveLocationSource {
    private val manager=context.getSystemService(LocationManager::class.java)
    private var listener:LocationListener?=null
    override fun start(onFix:(LiveFix)->Unit,onStatus:(String)->Unit){
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){onStatus("위치 권한이 필요합니다");return}
        val l=object:LocationListener{
            override fun onLocationChanged(location:Location){
                if(System.currentTimeMillis()-location.time>30000)return
                onFix(LiveFix(location.latitude,location.longitude,location.time,
                    if(location.provider==LocationManager.GPS_PROVIDER)"ANDROID_GPS" else "ANDROID_NETWORK",
                    if(location.hasAccuracy())location.accuracy else null))
            }
            override fun onProviderDisabled(provider:String){onStatus("위치 서비스가 꺼져 있습니다")}
            override fun onProviderEnabled(provider:String){}
            @Deprecated("Legacy callback")override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
        }
        listener=l
        var count=0
        for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER))try{
            if(manager.isProviderEnabled(provider)){
                manager.requestLocationUpdates(provider,2000L,0f,l,Looper.getMainLooper());count++
                manager.getLastKnownLocation(provider)?.let{l.onLocationChanged(it)}
            }
        }catch(_:Exception){}
        onStatus(if(count>0)"현재 위치 수신 중" else "사용 가능한 위치 서비스가 없습니다")
    }
    override fun close(){listener?.let{manager.removeUpdates(it)};listener=null}
}
