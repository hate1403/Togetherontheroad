package io.togetherroad.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import io.togetherroad.core.Place
import org.json.*
import java.io.File
import java.util.concurrent.Executors

/** Visible user action for Android's document picker and while-in-use microphone permission. */
class MediaActionActivity:Activity(){
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private var c:LiveController?=null
    private var recorder:MediaRecorder?=null
    private var recordingFile:File?=null
    private var began=0L
    private lateinit var status:TextView
    private lateinit var actionButton:Button
    private var mode=""
    private var finished=false
    private var export:JSONObject?=null
    override fun onCreate(state:Bundle?){super.onCreate(state);c=GroupOverlayService.activeController;mode=intent.getStringExtra("action")?:""
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(36,36,36,36);setBackgroundColor(Color.WHITE)}
        status=TextView(this).apply{textSize=24f;setTextColor(Color.rgb(23,28,35));gravity=Gravity.CENTER;text="미디어를 준비하고 있습니다"};root.addView(status)
        actionButton=Button(this).apply{text="취소";setOnClickListener{if(recorder!=null)stopAndSend()else backToNavigation()}};root.addView(actionButton,LinearLayout.LayoutParams(420,80));setContentView(root)
        if(c==null){status.text="그룹 지도를 먼저 실행해 주세요";return}
        if(state!=null){status.text="작업이 중단됐습니다. 취소 후 다시 실행해 주세요";return}
        when(mode){
            "MUSIC"->pick("audio/*")
            "MEMORY_IMAGE"->pick("image/*")
            "VOICE","MEMORY_VOICE"->{c!!.experience.pauseForRecording();if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)record()else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),82)}
            "EXPORT"->{export=JourneyMapData.from(c!!);if(export!!.getJSONArray("features").length()==0){status.text="동의한 참가자의 실제 위치 기록이 아직 없습니다"}else {
                val request=Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/geo+json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Together-Journey.geojson")
                val handler=request.resolveActivity(packageManager)
                if(handler==null||handler.className.contains("DocumentsUIStub")){
                    c!!.experience.album.saveJourney(export!!);c!!.experience.showJourney=true;c!!.notifyUi();status.text="여행 지도를 이 기기에 보관했습니다. 지도에서 실제 주행 기록을 다시 볼 수 있습니다.";actionButton.text="지도로 돌아가기"
                }else try{startActivityForResult(request,92)}catch(_:android.content.ActivityNotFoundException){status.text="이 환경에서는 외부 파일 저장을 지원하지 않습니다"}
            }}
            else->status.text="지원하지 않는 미디어 작업입니다"
        }
    }
    private fun pick(mime:String){
        status.text=if(mime.startsWith("audio"))"재생 권한이 있는 MP3 M4A WAV 파일을 선택하세요" else "위치에 남길 사진을 선택하세요"
        val request=Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE)
        val handler=request.resolveActivity(packageManager)
        if(handler==null||handler.className.contains("DocumentsUIStub")){pairedUpload();return}
        try{startActivityForResult(request,91)}catch(_:android.content.ActivityNotFoundException){pairedUpload()}
    }
    private fun pairedUpload(){
        val controller=c?:return;val member=controller.client?:return
        val b=JSONObject().put("use",if(mode=="MUSIC")"MUSIC" else "MEMORY")
        if(mode!="MUSIC"){
            val p=controller.memoryPlace?:controller.fix?.takeIf{System.currentTimeMillis()-it.observedAt<30000}?.let{Place("memory","여행 추억",it.latitude,it.longitude)}
            if(p==null){status.text="추억을 남길 장소를 먼저 선택해 주세요";return};b.put("name",p.name).put("place",LiveController.placeJson(p))
        }
        status.text="일회용 업로드 코드를 발급하고 있습니다"
        controller.journey("pair-upload",b){result->
            val pairs=result.getJSONObject("experience").getJSONArray("uploads");val pair=pairs.getJSONObject(pairs.length()-1)
            val url=controller.serverUrl.replace("10.0.2.2","127.0.0.1")+"/upload"
            status.text=getString(R.string.paired_upload_instructions,url,member.inviteCode,pair.getString("code"))
            status.setTextIsSelectable(true);actionButton.text="지도로 돌아가기";actionButton.setOnClickListener{backToNavigation()}
            if(mode=="MUSIC")controller.experience.setMusicEnabled(true)
            fun check(){if(isFinishing)return;val a=controller.snapshot?.optJSONObject("experience")?.optJSONArray("uploads");val done=a?.let{(0 until it.length()).map{n->it.getJSONObject(n)}.any{p->p.optString("id")==pair.getString("id")&&p.optBoolean("used")}}?:false
                if(done){finished=true;backToNavigation()}else if(System.currentTimeMillis()<pair.getLong("expiresAt"))main.postDelayed({check()},1000)else status.text="업로드 코드가 만료됐습니다. 지도로 돌아가 다시 요청해 주세요"
            };main.postDelayed({check()},1000)
        }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==82){if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED)record()else status.text="마이크 권한이 거부되어 녹음하지 않았습니다"}}
    @Suppress("DEPRECATION")private fun record(){
        if(c?.client==null){status.text="그룹 연결이 필요합니다";return}
        try{val file=File.createTempFile("ptt-",".m4a",cacheDir);recordingFile=file
            val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this)else MediaRecorder();recorder=r
            r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setAudioSamplingRate(16000);r.setAudioEncodingBitRate(32000);r.setOutputFile(file.path);r.prepare();r.start();began=SystemClock.elapsedRealtime()
            actionButton.text="녹음 끝내고 보내기";status.setText(R.string.recording_active)
            main.postDelayed({if(recorder!=null)stopAndSend()},19000)
        }catch(_:Exception){cancelRecording();status.text="이 기기의 마이크를 사용할 수 없습니다"}
    }
    private fun stopAndSend(){val r=recorder?:return;recorder=null;main.removeCallbacksAndMessages(null)
        try{r.stop();r.release();val duration=SystemClock.elapsedRealtime()-began;require(duration>=400){"너무 짧습니다"};val file=recordingFile!!;val bytes=file.readBytes();file.delete();recordingFile=null;upload(bytes,"audio/mp4","음성 메시지",duration)}
        catch(_:Exception){runCatching{r.release()};recordingFile?.delete();recordingFile=null;status.text="녹음이 너무 짧거나 저장할 수 없습니다. 다시 실행해 주세요"}
    }
    private fun cancelRecording(){recorder?.let{runCatching{it.stop()};it.release()};recorder=null;recordingFile?.delete();recordingFile=null}
    override fun onPause(){super.onPause();if(recorder!=null){cancelRecording();main.removeCallbacksAndMessages(null);status.text="화면 이동으로 녹음을 취소했습니다";actionButton.text="닫기"}}
    @Deprecated("Platform picker callback")override fun onActivityResult(request:Int,result:Int,data:Intent?){super.onActivityResult(request,result,data)
        if(result!=RESULT_OK||data?.data==null){backToNavigation();return}
        val uri=data.data!!
        if(request==92){val j=export?:return;io.execute{val ok=runCatching{contentResolver.openOutputStream(uri,"w")!!.use{it.write(j.toString(2).toByteArray())};c?.experience?.album?.saveJourney(j)};main.post{status.text=if(ok.isSuccess)"실제 주행 기록을 GeoJSON으로 저장했습니다" else "기록을 저장하지 못했습니다";actionButton.text="지도로 돌아가기"}};return}
        status.text="선택한 파일을 확인하고 있습니다";actionButton.isEnabled=false
        io.execute{val result=runCatching{
            val bytes=contentResolver.openInputStream(uri)!!.use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(32768);while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=20*1024*1024){"파일은 20MB 이하여야 합니다"};out.write(buf,0,n)};out.toByteArray()}
            if(mode=="MEMORY_IMAGE"){
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);require(bounds.outWidth>0&&bounds.outHeight>0){"지원하지 않는 이미지입니다"};var sample=1;while(bounds.outWidth/sample>2048||bounds.outHeight/sample>2048)sample*=2
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("이미지 오류")
                val out=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,85,out);bitmap.recycle();Triple(out.toByteArray(),"image/jpeg",0L)
            }else{
                val mime=when(contentResolver.getType(uri)){"audio/mp3","audio/mpeg"->"audio/mpeg";"audio/mp4","audio/x-m4a"->"audio/mp4";"audio/wav","audio/x-wav","audio/wave"->"audio/wav";else->error("MP3 M4A WAV 파일을 선택하세요")}
                val meta=MediaMetadataRetriever();val duration=try{meta.setDataSource(this,uri);meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?:0}finally{meta.release()};require(duration>0){"오디오 길이를 읽을 수 없습니다"};Triple(bytes,mime,duration)
            }
        };main.post{if(isFinishing)return@post;result.fold({(bytes,mime,duration)->upload(bytes,mime,displayName(uri),duration)},{status.text=it.message?:"파일 처리 실패";actionButton.isEnabled=true})}}
    }
    private fun displayName(uri:Uri):String=runCatching{contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0).take(70)else "선택한 미디어"}?:"선택한 미디어"}.getOrDefault("선택한 미디어")
    private fun upload(bytes:ByteArray,mime:String,name:String,duration:Long){val controller=c?:return
        if(controller.client==null){status.text="그룹 연결이 종료됐습니다";actionButton.isEnabled=true;return}
        val memory=mode.startsWith("MEMORY")
        val place:Place?=if(memory)controller.memoryPlace?:controller.fix?.takeIf{System.currentTimeMillis()-it.observedAt<30000}?.let{Place("memory","여행 추억",it.latitude,it.longitude)} else null
        if(memory&&place==null){status.text="지도에서 추억을 남길 장소를 선택해 주세요";actionButton.isEnabled=true;return}
        status.text="실제 미디어를 그룹 서버에 전송하고 있습니다";actionButton.isEnabled=false
        controller.mediaUpload(bytes,mime,name,if(memory)"MEMORY" else if(mode=="MUSIC")"MUSIC" else "VOICE",duration,place){finished=true;backToNavigation()}
        fun check(){if(finished||isFinishing)return;if(!controller.working){status.text=controller.status;actionButton.isEnabled=true;actionButton.text="지도로 돌아가기"}else main.postDelayed({check()},500)}
        main.postDelayed({check()},700)
    }
    private fun backToNavigation(){startActivity(Intent(this,NavOverlayActivity::class.java));finish()}
    override fun onDestroy(){cancelRecording();main.removeCallbacksAndMessages(null);io.shutdown();super.onDestroy()}
}

object JourneyMapData{
    fun from(c:LiveController):JSONObject{
        val trails=c.snapshot?.optJSONObject("experience")?.optJSONArray("trails")
        if(trails==null||trails.length()==0)return c.experience.album.journey()?:JSONObject().put("type","FeatureCollection").put("features",JSONArray())
        val features=JSONArray()
        for(i in 0 until trails.length()){
            val trail=trails.getJSONObject(i);val p=trail.getJSONArray("points");val segments=mutableListOf<JSONArray>();var segment=JSONArray();var last:JSONObject?=null
            for(j in 0 until p.length()){val point=p.getJSONObject(j);val before=last
                if(before!=null&&(point.getLong("at")-before.getLong("at")>30000||JourneyExperience.distance(before.getDouble("latitude"),before.getDouble("longitude"),point.getDouble("latitude"),point.getDouble("longitude"))>2000)){
                    if(segment.length()>=2)segments+=segment;segment=JSONArray()
                }
                segment.put(JSONArray().put(point.getDouble("longitude")).put(point.getDouble("latitude")));last=point
            }
            if(segment.length()>=2)segments+=segment
            if(segments.isNotEmpty())features.put(JSONObject().put("type","Feature").put("properties",JSONObject().put("memberId",trail.getString("memberId")).put("name",trail.getString("name"))).put("geometry",JSONObject().put("type","MultiLineString").put("coordinates",JSONArray(segments))))
        }
        return JSONObject().put("type","FeatureCollection").put("features",features)
    }
}
