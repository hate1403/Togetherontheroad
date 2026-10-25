package io.togetherroad.app

import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import io.togetherroad.core.*
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import java.util.Locale
import kotlin.math.*

/** Genuine road map and authenticated group state. Empty or failed data never becomes a fake value. */
@android.annotation.SuppressLint("ViewConstructor")
class LiveNavigationView(context:Context,private val c:LiveController,
    private val sendNative:(Place,(Boolean,String)->Unit)->Unit,private val minimize:()->Unit,private val close:()->Unit):FrameLayout(context) {
    private val blue=Color.rgb(25,102,238)
    private val ink=Color.rgb(23,28,35)
    private val grey=Color.rgb(112,121,132)
    private val pale=Color.rgb(244,246,248)
    private val mapView:MapView
    private var map:MapLibreMap?=null
    private var styled=false
    private var firstLocation=false
    private var selectedId:String?=null
    private val markers=mutableListOf<Marker>()
    private var polyline:Polyline?=null
    private val journeyLines=mutableListOf<Polyline>()
    private var journeyKey=""
    private var journeyPanel:JourneyPanel?=null
    private val waveBanner=label("",28f,true,blue)
    private val memoryBanner=button("가까운 여행 추억 열기"){journeyPanel?.dismiss();journeyPanel=JourneyPanel(context,c,sendNative).also{it.openLocalMemories()}}
    private var routeKey=0L
    private var markerKey=""
    private var actionsKey=""
    private var memberUiKey=""
    private var proposalKey=""
    private var proposalStatus:TextView?=null
    private var mapError:String?=null
    private val icons=mutableMapOf<String,Icon>()
    private val side=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE);setPadding(dp(26),dp(24),dp(26),dp(20))}
    private val rows=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val groupActions=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val proposalBox=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val progress=LiveProgressView(context)
    private val summaryTitle=label("목적지를 선택해 주세요",27f,true)
    private val summaryDetail=label("지도에서 장소를 길게 누르면 설정할 수 있습니다",16f,false,grey)
    private val stateLabel=label("위치와 지도를 연결하고 있습니다",15f,false,grey)
    private val groupLabel=label("내 차량",25f,true)
    private val countLabel=label("현재 위치 확인 중",15f,false,grey)
    private val messageLabel=label("",15f,false,grey)
    private val routeBadge=label("실제 도로 경로 · 교통정보 미반영",13f,false,grey)
    private val nativeButton:Button
    private val overviewButton:Button
    private val recenterButton:Button
    private var dialog:AlertDialog?=null
    private var displayMembers=emptyList<GroupMember>()
    init {
        setBackgroundColor(Color.WHITE)
        MapLibre.getInstance(context)
        val options=MapLibreMapOptions.createFromAttributes(context).textureMode(true).logoEnabled(false).attributionEnabled(false).compassEnabled(false)
        mapView=MapView(context,options);mapView.onCreate(null)
        val base=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL};addView(base,LayoutParams(-1,-1))
        val mapArea=FrameLayout(context);base.addView(mapArea,LinearLayout.LayoutParams(0,-1,1f));mapArea.addView(mapView,LayoutParams(-1,-1))
        val sideWidth=if(resources.configuration.screenWidthDp>1000)dp(420) else dp(330)
        base.addView(side,LinearLayout.LayoutParams(sideWidth,-1))
        val summary=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(17),dp(22),dp(16));background=shape(Color.WHITE,18);elevation=dp(3).toFloat()}
        summary.addView(label(if(Build.HARDWARE.contains("ranchu")||Build.PRODUCT.contains("avd"))"TOGETHER ON THE ROAD · AVD 테스트 위치" else "TOGETHER ON THE ROAD",12f,true,blue));summary.addView(summaryTitle);summary.addView(summaryDetail);summary.addView(routeBadge)
        mapArea.addView(summary,LayoutParams(-1,-2,Gravity.TOP).apply{setMargins(dp(22),dp(22),dp(22),0)})
        mapArea.addView(waveBanner,LayoutParams(-1,dp(60),Gravity.TOP).apply{setMargins(dp(28),dp(165),dp(28),0)})
        mapArea.addView(memoryBanner,LayoutParams(-1,dp(52),Gravity.BOTTOM).apply{setMargins(dp(28),0,dp(28),dp(155))})
        val bottom=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12));background=shape(Color.WHITE,14);elevation=dp(2).toFloat()}
        bottom.addView(stateLabel)
        val controls=LinearLayout(context)
        recenterButton=button("내 위치"){c.fix?.let{map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude,it.longitude),14.0))}}
        overviewButton=button("그룹 전체"){fitAll()}
        val retry=button("경로 재시도"){c.retry()}
        controls.addView(recenterButton,LinearLayout.LayoutParams(0,dp(52),1f));controls.addView(overviewButton,LinearLayout.LayoutParams(0,dp(52),1f));controls.addView(retry,LinearLayout.LayoutParams(0,dp(52),1f));bottom.addView(controls)
        mapArea.addView(bottom,LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(22),0,dp(22),dp(40))})
        mapArea.addView(label("OpenFreeMap © OpenMapTiles · © OpenStreetMap",12f,false,grey),LayoutParams(-2,dp(28),Gravity.BOTTOM or Gravity.START).apply{leftMargin=dp(24)})
        val titleRow=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        titleRow.addView(groupLabel,LinearLayout.LayoutParams(0,-2,1f));titleRow.addView(button("접기"){minimize()},LinearLayout.LayoutParams(dp(66),dp(48)));titleRow.addView(button("종료"){close()},LinearLayout.LayoutParams(dp(66),dp(48)))
        side.addView(titleRow);side.addView(countLabel);space(side,15)
        side.addView(groupActions)
        divider(side)
        val memberScroll=ScrollView(context).apply{isFillViewport=false};memberScroll.addView(rows);side.addView(memberScroll,LinearLayout.LayoutParams(-1,0,.32f))
        side.addView(label("목적지까지",21f,true));side.addView(label("남은 도로 거리 · 동일한 거리 축",13f,false,grey))
        side.addView(progress,LinearLayout.LayoutParams(-1,0,.40f))
        side.addView(proposalBox,LinearLayout.LayoutParams(-1,-2));side.addView(messageLabel)
        val messages=LinearLayout(context)
        for((title,kind) in listOf("휴식" to "REST","지연" to "LATE","출발" to "READY","도착" to "ARRIVED"))messages.addView(button(title){c.message(kind)},LinearLayout.LayoutParams(0,dp(50),1f))
        messages.tag="messages";side.addView(messages)
        nativeButton=button("이 목적지를 내장 내비에 전달"){
            val dest=c.destination?:return@button
            confirm("내장 내비게이션으로 안내할까요?","이 차량의 목적지가 ${dest.name}(으)로 변경됩니다."){
                sendNative(dest){ok,msg->post{notice(if(ok)"내장 내비 연결 확인" else "내비 연결을 확인해 주세요",msg)}}
            }
        }
        side.addView(nativeButton,LinearLayout.LayoutParams(-1,dp(54)))
        side.addView(button("음악 · 인사 · 음성 · 여행 추억"){journeyPanel?.dismiss();journeyPanel=JourneyPanel(context,c,sendNative)},LinearLayout.LayoutParams(-1,dp(54)))
        side.addView(button("연결 설정"){settings()},LinearLayout.LayoutParams(-1,dp(46)))
        mapView.addOnDidFailLoadingMapListener{_->post{mapError="지도를 불러오지 못했습니다 · 네트워크 확인 후 앱을 다시 열어주세요";render()}}
        mapView.getMapAsync{m->
            map=m;m.uiSettings.isRotateGesturesEnabled=false;m.uiSettings.isTiltGesturesEnabled=false
            m.cameraPosition=org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(36.3,127.8)).zoom(6.0).build()
            m.setStyle("https://tiles.openfreemap.org/styles/positron"){
                styled=true;mapError=null;render();fitAll()
            }
            m.addOnMapLongClickListener{point->selectPlace(point);true}
            m.setOnMarkerClickListener{marker->selectedId=marker.snippet;render();false}
        }
        render()
    }
    fun start(){mapView.onStart();mapView.onResume()}
    fun destroy(){journeyPanel?.dismiss();journeyPanel=null;dialog?.dismiss();dialog=null;mapView.onPause();mapView.onStop();mapView.onDestroy()}
    private fun dp(v:Int)=(resources.displayMetrics.density*v).toInt()
    private fun label(s:String,size:Float,bold:Boolean=false,color:Int=ink)=TextView(context).apply{text=s;textSize=size;setTextColor(color);if(bold)typeface=Typeface.create("sans-serif",Typeface.BOLD);setPadding(0,dp(3),0,dp(3))}
    private fun shape(color:Int,r:Int=12)=GradientDrawable().apply{setColor(color);cornerRadius=dp(r).toFloat()}
    private fun button(s:String,action:()->Unit)=Button(context).apply{text=s;isAllCaps=false;textSize=17f;val primary=s in listOf("그룹 만들기","동의","이 차량의 지도 경로에 적용");setTextColor(if(primary)Color.WHITE else ink);background=shape(if(primary)blue else pale,10);minHeight=dp(46);setPadding(dp(6),0,dp(6),0);isEnabled=!c.working||s in listOf("종료","접기","그룹 나가기","내 위치","그룹 전체");setOnClickListener{action()}}
    private fun space(parent:LinearLayout,n:Int){parent.addView(View(context),LinearLayout.LayoutParams(1,dp(n)))}
    private fun divider(parent:LinearLayout){parent.addView(View(context).apply{setBackgroundColor(Color.rgb(231,234,238))},LinearLayout.LayoutParams(-1,dp(1)).apply{setMargins(0,dp(16),0,dp(16))})}
    private fun avatar(m:GroupMember):TextView=label(m.initial,21f,true,Color.WHITE).apply{gravity=Gravity.CENTER;background=shape(m.color,30)}
    fun render(){
        val snap=c.snapshot
        journeyPanel?.refresh()
        val waves=snap?.optJSONObject("experience")?.optJSONArray("waves")
        val wave=waves?.let{(0 until it.length()).map{n->it.getJSONObject(n)}.lastOrNull{w->w.optString("memberId")!=c.client?.memberId&&(c.client?.serverNow()?:System.currentTimeMillis())-w.optLong("createdAt")<8000}}
        waveBanner.text=wave?.let{"👋 ${it.getString("name")} 님이 인사했어요"}?:"";waveBanner.visibility=if(wave!=null)View.VISIBLE else View.GONE
        memoryBanner.visibility=if(c.experience.nearbyMemory!=null)View.VISIBLE else View.GONE
        val connected=c.client!=null
        groupLabel.text=if(connected)"함께 이동" else "내 차량"
        countLabel.text=if(connected&&snap!=null)"${snap.getJSONArray("members").length()}대 참여 중" else if(connected)"그룹 연결 확인 중" else "초대할 차량이 있으면 그룹을 만드세요"
        stateLabel.text=mapError?:c.status
        summaryTitle.text=c.destination?.name?:"목적지를 선택해 주세요"
        summaryDetail.text=c.route?.let{"${km(it.getDouble("distanceMeters"))}  ·  약 ${ceil(it.getDouble("durationSeconds")/60).toInt()}분"}?:if(c.destination!=null)"현재 위치와 도로 경로를 확인하고 있습니다" else "지도에서 장소를 길게 눌러 설정하세요"
        routeBadge.visibility=if(c.route!=null)View.VISIBLE else View.GONE
        routeBadge.text=c.route?.let{"실제 도로 경로 · 교통정보 미반영 · ${max(0,(System.currentTimeMillis()-it.optLong("computedAt"))/60000)}분 전 계산"}?:""
        recenterButton.isEnabled=c.fix!=null
        nativeButton.visibility=if(context.packageName.endsWith(".pleos"))View.VISIBLE else View.GONE
        nativeButton.isEnabled=c.destination!=null&&!c.working
        val nextActionsKey="${connected}:${c.client?.inviteCode}"
        if(actionsKey!=nextActionsKey){
        actionsKey=nextActionsKey
        groupActions.removeAllViews()
        if(!connected){
            val row=LinearLayout(context);row.addView(button("그룹 만들기"){createGroup()},LinearLayout.LayoutParams(0,dp(52),1f));row.addView(button("코드로 참여"){joinGroup()},LinearLayout.LayoutParams(0,dp(52),1f));groupActions.addView(row)
        }else{
            groupActions.addView(button("초대 코드  ${c.client!!.inviteCode}"){
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("그룹 초대 코드",c.client!!.inviteCode));notice("초대 코드 복사","다른 차량에서 코드로 참여를 선택해 입력하세요.")
            },LinearLayout.LayoutParams(-1,dp(50)))
            groupActions.addView(button("그룹 나가기"){confirm("그룹에서 나갈까요?","위치 공유를 중단하고 서버에서 이 차량의 위치를 삭제합니다."){c.leave()}},LinearLayout.LayoutParams(-1,dp(46)))
        }
        }
        updateActionAvailability(groupActions)
        displayMembers=members()
        val nextMemberUiKey=displayMembers.joinToString{"${it.id}:${it.name}:${it.stale}:${it.remainingMeters}:${it.etaMinutes}:${it.avatarEmoji}:${it.stateEmoji}:${it.convoy}"}+selectedId
        if(memberUiKey!=nextMemberUiKey){
        memberUiKey=nextMemberUiKey
        rows.removeAllViews()
        for(m in displayMembers){
            val row=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(7),dp(9),dp(7),dp(9));background=shape(if(m.id==selectedId)Color.rgb(237,244,255) else Color.WHITE,12)}
            row.addView(avatar(m),LinearLayout.LayoutParams(dp(44),dp(44)))
            val info=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0)}
            info.addView(label("${m.avatarEmoji} ${m.name}",19f,true));info.addView(label(if(m.stale)"최근 위치 확인 필요" else if(m.point==null)"위치 수신 대기" else if(c.client==null)"현재 위치 수신" else "${m.convoy} · ${m.stateEmoji} 위치 공유 중",12f,false,if(m.stale)Color.rgb(174,111,24) else grey));row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
            val value=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.END}
            value.addView(label(m.remainingMeters?.let(::km)?:"—",19f,true));value.addView(label(m.etaMinutes?.let{"약 ${it}분"}?:"경로 대기",13f,false,grey));row.addView(value)
            row.setOnClickListener{selectedId=m.id;displayMembers.firstOrNull{it.id==m.id}?.point?.let{map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude,it.longitude),12.0))};render()};rows.addView(row)
        }
        }
        progress.members=if(displayMembers.size<=4)displayMembers else displayMembers.filter{it.id==selectedId}+displayMembers.filter{it.id!=selectedId}.take(3)
        renderProposal()
        val messages=snap?.optJSONArray("messages")
        messageLabel.text=if(messages!=null&&messages.length()>0){val last=messages.getJSONObject(messages.length()-1);"${last.getString("name")} · ${last.getString("text")}"}else""
        messageLabel.visibility=if(messageLabel.text.isBlank())View.GONE else View.VISIBLE
        side.findViewWithTag<LinearLayout>("messages")?.let{row->row.visibility=if(connected)View.VISIBLE else View.GONE;for(i in 0 until row.childCount)row.getChildAt(i).isEnabled=!c.working&&(i!=3||c.route?.getDouble("distanceMeters")?.let{it<=200}==true)}
        if(styled)updateMap()
    }
    private fun members():List<GroupMember>{
        val result=mutableListOf<GroupMember>();val data=c.snapshot?.optJSONArray("members");val me=c.client?.memberId
        if(data!=null)for(i in 0 until data.length()){
            val m=data.getJSONObject(i);val t=m.optJSONObject("telemetry");val route=m.optJSONObject("route");val id=m.getString("id");val name=if(id==me)"${m.getString("name")} · 나" else m.getString("name")
            val profiles=c.snapshot?.optJSONObject("experience")?.optJSONArray("profiles");val profile=profiles?.let{(0 until it.length()).map{n->it.getJSONObject(n)}.firstOrNull{p->p.optString("memberId")==id}}
            result+=GroupMember(id,name,m.getString("name").take(1),color(id),t?.let{GeoPoint(it.getDouble("latitude"),it.getDouble("longitude"))},
                route?.getDouble("distanceMeters"),if(m.isNull("totalDistanceMeters"))null else m.getDouble("totalDistanceMeters"),route?.let{ceil(it.getDouble("durationSeconds")/60).toInt()},m.optBoolean("stale",true),profile?.optString("avatar")?:"🚗",profile?.optString("status")?:"🚗",profile?.optString("convoy")?:"")
        }
        if(result.isEmpty())result+=GroupMember("self","${c.displayName} · 나",c.displayName.take(1),blue,c.fix?.let{GeoPoint(it.latitude,it.longitude)},c.route?.getDouble("distanceMeters"),c.totalDistanceMeters,c.route?.let{ceil(it.getDouble("durationSeconds")/60).toInt()},c.fix?.let{System.currentTimeMillis()-it.observedAt>30000}?:true)
        if(data!=null){
            val scale=result.flatMap{listOfNotNull(it.totalMeters,it.remainingMeters)}.filter{it.isFinite()&&it>0}.maxOrNull()
            return result.map{it.copy(totalMeters=scale)}
        }
        return result
    }
    private fun color(id:String):Int=if(id==c.client?.memberId||id=="self")blue else listOf(0xFF178775.toInt(),0xFFB7791F.toInt(),0xFF8B60C7.toInt(),0xFFCB627B.toInt())[abs(id.hashCode()%4)]
    private fun km(d:Double)=if(d>=1000)String.format(Locale.KOREA,"%.1f km",d/1000)else"${d.roundToInt()} m"
    private fun updateMap(){
        val m=map?:return
        val proposal=c.snapshot?.optJSONObject("proposal")
        val candidate=proposal?.takeIf{it.optString("state") in listOf("VOTING","ACCEPTED")}?.optJSONObject("candidate")
        val nextMarkerKey=displayMembers.joinToString{it.toString()}+c.destination.toString()+candidate.toString()+c.snapshot?.optJSONObject("experience")?.optJSONArray("drops").toString()
        if(markerKey!=nextMarkerKey){markerKey=nextMarkerKey
        for(marker in markers)m.removeMarker(marker);markers.clear()
        for(member in displayMembers){val point=member.point?:continue
            val iconKey="${member.initial}:${member.color}:${member.stale}:${member.avatarEmoji}:${member.stateEmoji}"
            val icon=icons.getOrPut(iconKey){
            val bitmap=Bitmap.createBitmap(dp(52),dp(64),Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color=Color.WHITE;canvas.drawCircle(dp(26).toFloat(),dp(26).toFloat(),dp(25).toFloat(),paint);paint.color=if(member.stale)Color.GRAY else member.color;canvas.drawCircle(dp(26).toFloat(),dp(26).toFloat(),dp(21).toFloat(),paint)
            paint.color=Color.WHITE;paint.textSize=dp(20).toFloat();paint.typeface=Typeface.DEFAULT_BOLD;paint.textAlign=Paint.Align.CENTER;canvas.drawText(member.avatarEmoji,dp(26).toFloat(),dp(31).toFloat(),paint)
            IconFactory.getInstance(context).fromBitmap(bitmap)}
            markers+=m.addMarker(MarkerOptions().position(LatLng(point.latitude,point.longitude)).title(member.name).snippet(member.id).icon(icon))
        }
        c.destination?.let{dest->markers+=m.addMarker(MarkerOptions().position(LatLng(dest.latitude,dest.longitude)).title(dest.name))}
        candidate?.let{place->
            val icon=icons.getOrPut("meetup"){
                val bitmap=Bitmap.createBitmap(dp(52),dp(64),Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color=Color.WHITE;canvas.drawCircle(dp(26).toFloat(),dp(26).toFloat(),dp(25).toFloat(),paint)
                paint.color=Color.rgb(23,135,117);canvas.drawCircle(dp(26).toFloat(),dp(26).toFloat(),dp(21).toFloat(),paint)
                paint.color=Color.WHITE;paint.textSize=dp(18).toFloat();paint.typeface=Typeface.DEFAULT_BOLD;paint.textAlign=Paint.Align.CENTER;canvas.drawText("합",dp(26).toFloat(),dp(32).toFloat(),paint)
                IconFactory.getInstance(context).fromBitmap(bitmap)
            }
            markers+=m.addMarker(MarkerOptions().position(LatLng(place.getDouble("latitude"),place.getDouble("longitude"))).title("합류 · ${place.getString("name")}").icon(icon))
        }
        }
        val journey=if(c.experience.showJourney)JourneyMapData.from(c)else null
        val nextJourney=journey?.toString()?:"off"
        if(journeyKey!=nextJourney){journeyKey=nextJourney;journeyLines.forEach{m.removePolyline(it)};journeyLines.clear()
            journey?.optJSONArray("features")?.let{features->for(i in 0 until features.length()){
                val f=features.getJSONObject(i);val segments=f.getJSONObject("geometry").getJSONArray("coordinates");for(j in 0 until segments.length()){
                    val segment=segments.getJSONArray(j);val points=(0 until segment.length()).map{n->val p=segment.getJSONArray(n);LatLng(p.getDouble(1),p.getDouble(0))}
                    if(points.size>=2)journeyLines+=m.addPolyline(PolylineOptions().addAll(points).color(color(f.getJSONObject("properties").getString("memberId"))).width(4f))
                }
            }}
        }
        val r=c.route;val key=r?.optLong("computedAt")?:0
        if(key!=routeKey){polyline?.let{m.removePolyline(it)};polyline=null;routeKey=key
            r?.optJSONObject("geometry")?.optJSONArray("coordinates")?.let{coords->val points=mutableListOf<LatLng>();for(i in 0 until coords.length()){val p=coords.getJSONArray(i);points+=LatLng(p.getDouble(1),p.getDouble(0))};if(points.size>=2)polyline=m.addPolyline(PolylineOptions().addAll(points).color(blue).width(6f));fitAll()}
        }
        if(!firstLocation&&c.fix!=null){firstLocation=true;c.fix!!.let{m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude,it.longitude),12.0))}}
    }
    private fun fitAll(){val m=map?:return;val points=displayMembers.mapNotNull{it.point?.let{p->LatLng(p.latitude,p.longitude)}}.toMutableList();c.destination?.let{points+=LatLng(it.latitude,it.longitude)}
        c.snapshot?.optJSONObject("proposal")?.takeIf{it.optString("state") in listOf("VOTING","ACCEPTED")}?.optJSONObject("candidate")?.let{points+=LatLng(it.getDouble("latitude"),it.getDouble("longitude"))}
        c.route?.optJSONObject("geometry")?.optJSONArray("coordinates")?.let{coords->for(i in 0 until coords.length() step max(1,coords.length()/300)){val p=coords.getJSONArray(i);points+=LatLng(p.getDouble(1),p.getDouble(0))}}
        if(points.size>1){try{m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(),dp(80),dp(180),dp(60),dp(160)))}catch(_:Exception){}}
        else points.firstOrNull()?.let{m.animateCamera(CameraUpdateFactory.newLatLngZoom(it,12.0))}
    }
    private fun selectPlace(point:LatLng){
        if(c.working)return
        var name="선택한 위치"
        try{map?.queryRenderedFeatures(map!!.projection.toScreenLocation(point))?.firstOrNull{it.hasProperty("name")&&!it.getProperty("name").isJsonNull}?.let{name=it.getStringProperty("name")}}catch(_:Exception){}
        val form=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),0)}
        val field=EditText(context).apply{setTextColor(ink);setHintTextColor(grey);setText(name);isSingleLine=true;filters=arrayOf(android.text.InputFilter.LengthFilter(70));hint="장소 이름"};form.addView(field);form.addView(label(String.format(Locale.US,"%.5f, %.5f",point.latitude,point.longitude),15f,false,grey))
        c.memoryPlace=Place("memory",name,point.latitude,point.longitude)
        if(c.client!=null)form.addView(button("이 장소에 사진 추억 남기기"){c.memoryPlace=Place("memory",field.text.toString().ifBlank{"여행 추억"},point.latitude,point.longitude);dialog?.dismiss();c.openMedia("MEMORY_IMAGE")})
        val builder=AlertDialog.Builder(context).setTitle("이 장소로 이동").setView(form).setNegativeButton("취소",null)
        if(c.client!=null&&!c.isOwner())builder.setPositiveButton("내 목적지로 설정"){_,_->c.journey("personal-destination",JSONObject().put("destination",LiveController.placeJson(Place("selected",field.text.toString().ifBlank{"선택한 위치"},point.latitude,point.longitude))))}
        if(c.client==null||c.isOwner())builder.setPositiveButton("목적지로 설정"){_,_->c.setDestination(Place("selected",field.text.toString().ifBlank{"선택한 위치"},point.latitude,point.longitude))}
        if(c.client!=null&&c.destination!=null)builder.setNeutralButton("합류 장소 제안"){_,_->c.propose(Place("proposal",field.text.toString().ifBlank{"선택한 위치"},point.latitude,point.longitude))}
        show(builder.create())
    }
    private fun createGroup(){
        val form=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),0)}
        form.addView(label("참여자에게 현재 위치와 목적지·도착 정보를 공유합니다.",17f))
        val agree=CheckBox(context).apply{setTextColor(ink);buttonTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(blue,grey));text="이 그룹에 위치 공유 동의";textSize=17f};form.addView(agree)
        val d=AlertDialog.Builder(context).setTitle("그룹 드라이브 시작").setView(form).setNegativeButton("취소",null).setPositiveButton("그룹 만들기",null).create()
        show(d);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(agree.isChecked){d.dismiss();c.createGroup()}else agree.error="공유 동의가 필요합니다"}
    }
    private fun joinGroup(){
        val form=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),0)}
        val code=EditText(context).apply{setTextColor(ink);setHintTextColor(grey);hint="8자리 초대 코드";isSingleLine=true;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS};form.addView(code)
        val agree=CheckBox(context).apply{setTextColor(ink);buttonTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(blue,grey));text="그룹 참여자에게 위치 공유 동의";textSize=17f};form.addView(agree)
        val d=AlertDialog.Builder(context).setTitle("초대 코드로 참여").setView(form).setNegativeButton("취소",null).setPositiveButton("참여",null).create()
        show(d);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(agree.isChecked&&code.text.length==8){d.dismiss();c.joinGroup(code.text.toString().trim())}else code.error="초대 코드와 공유 동의를 확인하세요"}
    }
    private fun settings(){
        val form=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),0)}
        val name=EditText(context).apply{setTextColor(ink);setHintTextColor(grey);filters=arrayOf(android.text.InputFilter.LengthFilter(20));hint="차량 표시 이름";setText(c.displayName);isSingleLine=true};val server=EditText(context).apply{setTextColor(ink);setHintTextColor(grey);hint="그룹 서버 주소";setText(c.serverUrl);isSingleLine=true}
        form.addView(name);form.addView(server);form.addView(label("그룹 참여 중에는 연결 설정을 바꿀 수 없습니다.",14f,false,grey))
        show(AlertDialog.Builder(context).setTitle("연결 설정").setView(form).setNegativeButton("취소",null).setPositiveButton("저장"){_,_->if(c.client==null)try{c.configure(server.text.toString(),name.text.toString())}catch(_:Exception){notice("입력을 확인하세요","서버 주소와 표시 이름이 올바르지 않습니다.")}}.create())
    }
    private fun renderProposal(){
        val p=c.snapshot?.optJSONObject("proposal")
        val nextKey=p?.toString()?:"none"
        if(proposalKey==nextKey){
            if(p?.optString("state")=="VOTING")proposalStatus?.text=context.getString(R.string.proposal_voting_remaining,max(0,(p.getLong("expiresAt")-System.currentTimeMillis())/1000))
            updateActionAvailability(proposalBox);return
        }
        proposalKey=nextKey;proposalBox.removeAllViews();proposalStatus=null
        if(p==null)return
        val candidate=p.getJSONObject("candidate");proposalBox.addView(label("합류 제안 · ${candidate.getString("name")}",18f,true))
        val state=p.getString("state");val remaining=max(0,(p.getLong("expiresAt")-System.currentTimeMillis())/1000)
        proposalStatus=label(when(state){"VOTING"->"참여자 동의 확인 중 · ${remaining}초 남음";"ACCEPTED"->"모두 동의했습니다 · 차량별 적용 필요";"REJECTED"->"다른 장소를 다시 제안해 주세요";else->"제안이 만료됐습니다"},13f,false,grey)
        proposalBox.addView(proposalStatus)
        if(state=="VOTING"){
            val row=LinearLayout(context);row.addView(button("동의"){c.vote(true)},LinearLayout.LayoutParams(0,dp(48),1f));row.addView(button("다른 장소"){c.vote(false)},LinearLayout.LayoutParams(0,dp(48),1f));proposalBox.addView(row)
        }else if(state=="ACCEPTED"){
            val own=p.getJSONObject("states").optString(c.client?.memberId)
            if(own in listOf("PENDING","FAILED"))proposalBox.addView(button(if(own=="FAILED")"도로 경로 적용 다시 시도" else "이 차량의 지도 경로에 적용"){c.adoptProposal()})
            else proposalBox.addView(label(when(own){"APPLIED"->"이 차량의 도로 경로 적용 완료";"FAILED"->"경로 적용 실패 · 다시 제안해 주세요";else->"도로 경로 확인 중"},14f,false,grey))
        }
    }
    private fun updateActionAvailability(root:View){
        if(root is Button)root.isEnabled=!c.working||root.text=="그룹 나가기"
        else if(root is android.view.ViewGroup)for(i in 0 until root.childCount)updateActionAvailability(root.getChildAt(i))
    }
    private fun confirm(title:String,body:String,action:()->Unit){show(AlertDialog.Builder(context).setTitle(title).setMessage(body).setNegativeButton("취소",null).setPositiveButton("확인"){_,_->action()}.create())}
    private fun notice(title:String,body:String){show(AlertDialog.Builder(context).setTitle(title).setMessage(body).setPositiveButton("확인",null).create())}
    private fun show(d:AlertDialog){dialog?.dismiss();dialog=d;d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);d.show()}
}

class LiveProgressView @JvmOverloads constructor(context:Context,attrs:android.util.AttributeSet?=null):View(context,attrs){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    var members:List<GroupMember> = emptyList();set(v){field=v;invalidate()}
    override fun onDraw(c:Canvas){
        val n=members.size;if(n==0)return
        val density=resources.displayMetrics.density;val top=36*density;val bottom=height-36*density
        if(bottom<=top)return
        p.strokeCap=Paint.Cap.ROUND;p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        for((i,m)in members.withIndex()){
            val x=width*(i+.5f)/n;val progress=if(m.stale)null else m.progress
            p.color=Color.rgb(233,237,242);p.strokeWidth=9*density;c.drawLine(x,top,x,bottom,p)
            if(progress!=null){val y=bottom-(bottom-top)*progress.toFloat();p.color=m.color;c.drawLine(x,y,x,bottom,p);p.color=Color.WHITE;c.drawCircle(x,y,21*density,p);p.color=m.color;c.drawCircle(x,y,18*density,p);p.textSize=17*density;p.color=Color.WHITE;c.drawText(m.initial,x,y+6*density,p)}
            p.textSize=12*density;p.color=Color.rgb(134,142,151);c.drawText("도착",x,top-15*density,p);c.drawText(if(progress==null)"거리 대기" else String.format(Locale.KOREA,"%.1fkm 기준",(m.totalMeters?:0.0)/1000),x,bottom+23*density,p)
        }
    }
}
