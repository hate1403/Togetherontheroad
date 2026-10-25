package io.togetherroad.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*
import io.togetherroad.core.Place
import org.json.*

/** Functional controls for the eight shared experiences, over the navigation map. */
class JourneyPanel(private val context:Context,private val c:LiveController,private val native:(Place,(Boolean,String)->Unit)->Unit){
    private val ink=Color.rgb(23,28,35)
    private val blue=Color.rgb(25,102,238)
    private val root=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(28,16,28,16)}
    private val dynamic=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val state=label("")
    private var key=""
    private val children=mutableSetOf<AlertDialog>()
    private val d:AlertDialog
    private val mediaConsent:CheckBox
    private val trailConsent:CheckBox
    init{
        root.addView(state)
        mediaConsent=check("Sync Music 수신과 내 기기 재생에 동의",c.experience.musicEnabled){c.experience.setMusicEnabled(it)};root.addView(mediaConsent)
        row(root,"음악 파일 선택 및 동기 재생" to {confirm("그룹에 음악 공유","재생·공유 권한이 있는 MP3 M4A WAV 파일만 선택해 주세요. 다른 차량은 각자 재생에 동의해야 합니다."){c.openMedia("MUSIC")}},"인사 보내기 👋" to {c.journey("wave")})
        row(root,"음성 보내기 최대 20초" to {c.openMedia("VOICE")},"차량 아바타와 상태" to {profile()})
        row(root,"내 목적지 공유" to {c.destination?.let{c.journey("follow-share",JSONObject().put("destination",LiveController.placeJson(it))) }?:notice("먼저 목적지를 선택해 주세요")},"사진 추억 남기기" to {chooseMemory("MEMORY_IMAGE")})
        row(root,"장소에 음성 남기기" to {chooseMemory("MEMORY_VOICE")},"내 기기의 추억" to {localAlbum()})
        trailConsent=check("내 주행 경로 기록과 그룹의 지도 열람·내보내기에 동의",false){c.journey("trail-consent",JSONObject().put("enabled",it))};root.addView(trailConsent)
        root.addView(label("경로 기록은 별도 동의 후 새 위치부터 수집합니다. 철회하면 서버 기록은 지워지며 이미 내보낸 사본은 별도 삭제해야 합니다.",13f))
        root.addView(check("여러 차량의 실제 주행 기록을 지도에 겹쳐 보기",c.experience.showJourney){c.experience.showJourney=it;c.notifyUi()})
        row(root,"여행 지도 기기에 보관" to {c.openMedia("EXPORT")},"동기 음악 일시정지 또는 재개" to {
            val track=c.snapshot?.optJSONObject("experience")?.optJSONObject("music")
            if(track==null)notice("공유된 곡이 없습니다")else c.journey(if(track.optString("state")=="PLAYING")"music-pause" else "music-resume")
        })
        root.addView(label("Convoy Leader는 동일 목적지의 최신 도로 거리 순서입니다. 100m 이내 차이는 동행으로 표시하며 실제 차선·차간 순서를 판정하지 않습니다.",13f))
        root.addView(dynamic)
        val scroll=ScrollView(context);scroll.addView(root)
        d=AlertDialog.Builder(context).setTitle("함께 즐기는 여정").setView(scroll).setPositiveButton("지도로 돌아가기",null).create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);d.show();d.window?.setLayout((resourcesWidth()*0.72).toInt().coerceAtMost(dp(900)),-2);refresh()
    }
    private fun resourcesWidth()=context.resources.displayMetrics.widthPixels
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
    private fun label(text:String,size:Float=16f)=TextView(context).apply{this.text=text;textSize=size;setTextColor(ink);setPadding(0,9,0,9)}
    private fun button(text:String,run:()->Unit)=Button(context).apply{this.text=text;textSize=18f;isAllCaps=false;val primary=text=="음악 파일 선택 및 동기 재생";setTextColor(if(primary)Color.WHITE else ink);backgroundTintList=android.content.res.ColorStateList.valueOf(if(primary)blue else Color.rgb(244,246,248));elevation=0f;setOnClickListener{run()}}
    private fun row(parent:LinearLayout,vararg actions:Pair<String,()->Unit>){val r=LinearLayout(context);for((title,run)in actions)r.addView(button(title,run),LinearLayout.LayoutParams(0,dp(52),1f));parent.addView(r)}
    private fun check(text:String,value:Boolean,action:(Boolean)->Unit)=CheckBox(context).apply{this.text=text;textSize=16f;setTextColor(ink);buttonTintList=android.content.res.ColorStateList.valueOf(blue);isChecked=value;setOnClickListener{action(isChecked)}}
    fun dismiss(){children.toList().forEach{it.dismiss()};children.clear();d.dismiss()}
    private fun showChild(x:AlertDialog,closed:()->Unit={}){children.add(x);x.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);x.setOnDismissListener{children.remove(x);closed()};x.show()}
    fun refresh(){
        if(!d.isShowing)return
        state.text=context.getString(R.string.journey_status,c.status,c.experience.musicStatus)
        mediaConsent.isChecked=c.experience.musicEnabled
        val exp=c.snapshot?.optJSONObject("experience")
        val profiles=exp?.optJSONArray("profiles")?:JSONArray()
        val own=(0 until profiles.length()).map{profiles.getJSONObject(it)}.firstOrNull{it.optString("memberId")==c.client?.memberId}
        trailConsent.isChecked=own?.optBoolean("trailConsent")?:false;trailConsent.isEnabled=c.client!=null&&!c.working
        val stable=JSONObject(exp?.toString()?:"{}");stable.remove("trails")
        val next=stable.toString()+c.client?.memberId
        if(next==key)return;key=next;dynamic.removeAllViews()
        if(c.client==null){dynamic.addView(label("그룹에 참여하면 음악·인사·음성·친구 목적지와 장소 추억을 공유할 수 있습니다."));return}
        val voices=exp?.optJSONArray("voices")?:JSONArray()
        if(voices.length()>0)dynamic.addView(label("받은 음성 메시지",19f))
        for(i in maxOf(0,voices.length()-5) until voices.length()){
            val voice=voices.getJSONObject(i);dynamic.addView(button("${voice.getString("name")} · ${voice.getLong("durationMs")/1000}초 음성 재생"){c.experience.playVoice(voice.getString("assetId"),::notice)})
        }
        dynamic.addView(label("Follow Me 친구가 공유한 목적지",19f))
        var targets=0
        for(i in 0 until profiles.length()){
            val p=profiles.getJSONObject(i);if(p.getString("memberId")==c.client?.memberId)continue
            val dest=p.optJSONObject("followDestination")?:continue;targets++
            val name=memberName(p.getString("memberId"))
            dynamic.addView(button("$name 따라가기 · ${dest.getString("name")}"){
                confirm("이 차량의 목적지를 변경할까요?","$name 님이 공유한 ${dest.getString("name")} 목적지로 도로 경로를 계산합니다."+(if(context.packageName.endsWith(".pleos"))" 이 차량의 내장 내비에도 전달합니다." else "")){
                    c.journey("follow",JSONObject().put("memberId",p.getString("memberId"))){result->
                        if(context.packageName.endsWith(".pleos"))native(LiveController.place(result.getJSONObject("destination"))){_,message->notice(message)}
                    }
                }
            })
        }
        if(targets==0)dynamic.addView(label("친구가 목적지를 공유하면 여기에서 선택할 수 있습니다.",14f))
        dynamic.addView(label("Memory Drop 장소에 도착하면 열리는 추억",19f))
        val drops=exp?.optJSONArray("drops")?:JSONArray()
        if(drops.length()==0)dynamic.addView(label("지도에서 장소를 선택하거나 현재 위치에 사진·음성을 남겨 보세요.",14f))
        for(i in 0 until drops.length()){
            val drop=drops.getJSONObject(i);val unlocked=drop.getBoolean("unlocked")
            val b=button((if(unlocked)"열기 · " else "도착하면 열림 · ")+drop.getString("name")){c.experience.media(drop.getString("assetId")){bytes->if(bytes==null)notice("미디어를 열 수 없습니다")else preview(drop,bytes,true)}}
            b.isEnabled=unlocked;dynamic.addView(b)
            if(drop.optString("ownerId")==c.client?.memberId)dynamic.addView(button("이 추억을 그룹에서 삭제"){confirm("추억 삭제","그룹 서버의 사진 또는 음성도 함께 삭제합니다."){c.journey("drop-delete",JSONObject().put("dropId",drop.getString("id")))}})
        }
    }
    private fun memberName(id:String):String{val a=c.snapshot?.optJSONArray("members")?:return "친구";for(i in 0 until a.length()){val m=a.getJSONObject(i);if(m.getString("id")==id)return m.getString("name")};return "친구"}
    private fun chooseMemory(mode:String){
        val p=c.memoryPlace?:c.fix?.takeIf{System.currentTimeMillis()-it.observedAt<30000}?.let{Place("memory","여행 추억",it.latitude,it.longitude)}
        if(p==null){notice("지도에서 추억을 남길 장소를 선택하거나 현재 위치를 수신해 주세요");return}
        confirm("이 장소에 추억을 남길까요?","${p.name} (${String.format(java.util.Locale.US,"%.5f, %.5f",p.latitude,p.longitude)})\n그룹 친구가 100m 이내에서 새 위치를 두 번 보내면 열립니다. 서버 보관은 현재 그룹 종료까지입니다."){c.memoryPlace=p;dismiss();c.openMedia(mode)}
    }
    private fun profile(){val avatars=arrayOf("🚗","🚙","🚐","🚕","🚘");choose("차량 아바타 선택",avatars){avatar->choose("직접 선택한 상태",arrayOf("🚗 이동 준비","☕ 휴식","⏳ 지연","✅ 출발 준비")){state->c.journey("profile",JSONObject().put("avatar",avatar).put("status",state.substringBefore(" ")))}}}
    private fun choose(title:String,items:Array<String>,picked:(String)->Unit){val x=AlertDialog.Builder(context).setTitle(title).setItems(items){_,i->picked(items[i])}.create();showChild(x)}
    private fun preview(drop:JSONObject,bytes:ByteArray,remote:Boolean){
        if(drop.getString("mime").startsWith("audio")){
            if(remote)c.experience.playVoice(drop.getString("assetId"),::notice)else c.experience.playLocalVoice(bytes,::notice)
            if(remote)confirm("이 음성을 내 기기에 보관할까요?","보관한 추억은 그룹 종료 후에도 남습니다. 내 기기의 추억 메뉴에서 삭제할 수 있습니다."){runCatching{c.experience.album.save(drop,bytes)}.fold({notice("기기에 보관했습니다")},{notice(it.message?:"보관 실패")})};return
        }
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);var sample=1
        if(bounds.outWidth<=0||bounds.outHeight<=0){notice("이미지를 표시할 수 없습니다");return}
        while(bounds.outWidth/sample>2048||bounds.outHeight/sample>2048)sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:return
        val image=ImageView(context).apply{setImageBitmap(bitmap);adjustViewBounds=true;maxHeight=dp(480);scaleType=ImageView.ScaleType.FIT_CENTER}
        val b=AlertDialog.Builder(context).setTitle(drop.getString("name")).setView(image).setNegativeButton("닫기",null)
        if(remote)b.setPositiveButton("내 기기에 보관"){_,_->runCatching{c.experience.album.save(drop,bytes)}.fold({notice("기기에 보관했습니다")},{notice(it.message?:"보관 실패")})}
        else b.setPositiveButton("기기에서 삭제"){_,_->c.experience.album.delete(drop.getString("id"));notice("기기에서 삭제했습니다")}
        val x=b.create();showChild(x){image.setImageDrawable(null);bitmap.recycle()}
    }
    fun openLocalMemories()=localAlbum()
    private fun localAlbum(){
        val entries=c.experience.album.entries();val journey=c.experience.album.journey()!=null
        if(entries.isEmpty()&&!journey){notice("기기에 보관한 추억이 없습니다. 열린 그룹 추억에서 내 기기에 보관을 선택하세요.");return}
        val names=entries.mapIndexed{i,item->"${i+1}. ${item.getString("name")}"}.toMutableList();if(journey)names.add("보관한 여행 지도 삭제")
        choose("내 기기의 추억",names.toTypedArray()){choice->
            if(choice=="보관한 여행 지도 삭제")confirm("보관한 지도 삭제","이 기기에 저장한 여행 지도 기록을 삭제합니다."){c.experience.album.deleteJourney();c.experience.showJourney=false;c.notifyUi();notice("보관한 지도를 삭제했습니다")}
            else{val drop=entries[choice.substringBefore(".").toInt()-1];choose(drop.getString("name"),arrayOf("열기","기기에서 삭제")){action->if(action=="기기에서 삭제"){c.experience.album.delete(drop.getString("id"));notice("기기에서 삭제했습니다")}else runCatching{c.experience.album.bytes(drop.getString("id"))}.fold({preview(drop,it,false)},{notice("보관 파일을 열 수 없습니다")})}}
        }
    }
    private fun confirm(title:String,body:String,run:()->Unit){val x=AlertDialog.Builder(context).setTitle(title).setMessage(body).setNegativeButton("취소",null).setPositiveButton("확인"){_,_->run()}.create();showChild(x)}
    private fun notice(message:String){val x=AlertDialog.Builder(context).setMessage(message).setPositiveButton("확인",null).create();showChild(x)}
}
