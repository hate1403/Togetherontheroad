package io.togetherroad.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.widget.TextView

/** User-controlled foreground experience with actual map tiles, device fixes and server state. */
class GroupOverlayService:Service(){
    private lateinit var wm:WindowManager
    private var view:LiveNavigationView?=null
    private var bubble:TextView?=null
    private lateinit var controller:LiveController
    private var native:PlatformNavigation?=null
    private var controllerStarted=false
    override fun onCreate(){
        super.onCreate();wm=getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"함께 이동",NotificationManager.IMPORTANCE_LOW))
        controller=LiveController(this){view?.render();updateBubble()}
    }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stopSelf();return START_NOT_STICKY}
        if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY}
        val stop=PendingIntent.getService(this,1,Intent(this,GroupOverlayService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_road).setContentTitle("Together on the Road")
            .setContentText("위치와 그룹 연결 사용 중 · 종료하면 공유가 중단됩니다").setOngoing(true)
            .addAction(Notification.Action.Builder(null,"연결 종료",stop).build()).build()
        if(Build.VERSION.SDK_INT>=29){
            var type=if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)type=type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(31,notification,type)
        }else startForeground(31,notification)
        if(!controllerStarted){controllerStarted=true;controller.start()}
        if(intent?.action==ACTION_SHOW){
            // Attach after Pleos has completed its foreground transition, above its temporary UI windows.
            if(view==null)showExperience()
        }
        return START_NOT_STICKY
    }
    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()
    @Suppress("DEPRECATION")private fun display():DisplayMetrics=DisplayMetrics().also{wm.defaultDisplay.getRealMetrics(it)}
    private fun layout(width:Int,height:Int)=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,PixelFormat.TRANSLUCENT).apply{
        gravity=Gravity.TOP or Gravity.END;setTitle("TogetherLiveNavigation")
        if(Build.VERSION.SDK_INT>=30)setFitInsetsTypes(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
    }
    private fun showExperience(){
        bubble?.let{wm.removeView(it)};bubble=null
        val full=display();val car=packageManager.hasSystemFeature("android.hardware.type.automotive")
        // Keep Pleos's separate driving display and climate/status bars visible.
        val width=if(car)(full.widthPixels*2/3)-dp(25) else full.widthPixels
        val params=layout(width,WindowManager.LayoutParams.MATCH_PARENT).apply{x=if(car)dp(25)else 0}
        val content=LiveNavigationView(this,controller,{place,done->
            native?.close();val gateway=PlatformNavigation(this);native=gateway
            // Each explicit SDK request has its own listener lifecycle; actual callbacks decide the result.
            val handler=Handler(Looper.getMainLooper())
            handler.postDelayed({gateway.applyWhenReady(place){ok,msg->done(ok,msg);gateway.close();if(native===gateway)native=null}},600)
        },{showBubble()},{stopSelf()})
        view=content;wm.addView(content,params);content.start()
    }
    private fun updateBubble(){
        bubble?.text=if(controller.client==null)"지도 열기" else controller.snapshot?.optJSONArray("members")?.length()?.let{"함께 이동 · ${it}대"}?:"그룹 연결 확인 중"
    }
    private fun showBubble(){
        native?.close();native=null
        view?.let{it.destroy();runCatching{wm.removeViewImmediate(it)}};view=null
        if(bubble!=null)return
        val small=TextView(this).apply{
            textSize=18f;setTextColor(Color.rgb(25,102,238));gravity=Gravity.CENTER
            background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(24).toFloat();setStroke(dp(1),Color.rgb(225,230,237))}
            elevation=dp(6).toFloat();setOnClickListener{showExperience()}
        }
        bubble=small;updateBubble()
        wm.addView(small,layout(dp(180),dp(64)).apply{x=dp(25);y=dp(14);setTitle("TogetherGroupCompact")})
    }
    override fun onDestroy(){
        controller.close();native?.close();native=null
        view?.let{it.destroy();runCatching{wm.removeView(it)}};bubble?.let{runCatching{wm.removeView(it)}}
        view=null;bubble=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    companion object{const val ACTION_SHOW="io.togetherroad.SHOW_GROUP_OVERLAY";const val ACTION_STOP="io.togetherroad.STOP_GROUP_OVERLAY";private const val CHANNEL="group_navigation_overlay"}
}
