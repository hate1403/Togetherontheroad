package io.togetherroad.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/** Small permission entry point; the working map/group UI lives in the navigation overlay. */
class NavOverlayActivity:Activity(){
    private var started=false
    private var askedLocation=false
    private var gateway:PlatformNavigation?=null
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        if(!Settings.canDrawOverlays(this)){
            AlertDialog.Builder(this).setTitle("내비게이션에서 함께 이동")
                .setMessage("지도와 그룹 정보를 내비게이션 영역에 함께 표시하려면 다른 앱 위에 표시 권한이 필요합니다.")
                .setNegativeButton("취소"){_,_->finish()}.setPositiveButton("표시 권한 설정"){_,_->
                    try{startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}
                    catch(_:android.content.ActivityNotFoundException){AlertDialog.Builder(this).setTitle("이 환경의 표시 권한을 확인하세요").setMessage("이 시스템에는 표시 권한 설정 화면이 없습니다. 플랫폼에서 앱의 표시 권한을 구성한 후 다시 실행해 주세요.").setPositiveButton("확인"){_,_->finish()}.show()}
                }.setOnCancelListener{finish()}.show()
        }
    }
    override fun onResume(){
        super.onResume()
        if(started||!Settings.canDrawOverlays(this))return
        if(!askedLocation&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            askedLocation=true;val permissions=mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
            if(packageName.endsWith(".pleos"))try{val info=packageManager.getPermissionInfo("pleos.car.permission.FUSED_LOCATION",0);if(info.protectionLevel and 15==1)permissions+="pleos.car.permission.FUSED_LOCATION"}catch(_:Exception){}
            requestPermissions(permissions.toTypedArray(),81);return
        }
        launch()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==81)launch()}
    private fun launch(){
        if(started)return;started=true
        startForegroundService(Intent(this,GroupOverlayService::class.java))
        gateway=PlatformNavigation(this);gateway?.showNavigation{_,_->
            main.postDelayed({
                startService(Intent(this,GroupOverlayService::class.java).setAction(GroupOverlayService.ACTION_SHOW))
                finish()
            },if(packageName.endsWith(".pleos"))2000L else 100L)
        }
    }
    override fun onDestroy(){main.removeCallbacksAndMessages(null);gateway?.close();gateway=null;super.onDestroy()}
}
