package io.togetherroad.app

import android.content.Context
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import org.json.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/** Real media playback and locally retained memories. No automatic microphone capture. */
class JourneyExperience(private val context:Context,private val c:LiveController,private val changed:()->Unit):AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private val audio=context.getSystemService(AudioManager::class.java)
    private val cache=File(context.cacheDir,"journey-media").apply{mkdirs()}
    val album=LocalJourneyAlbum(context)
    var musicEnabled=false;private set
    var showJourney=false
    var musicStatus="동기 음악 수신 꺼짐";private set
    var driftMs:Long?=null;private set
    var nearbyMemory:JSONObject?=null;private set
    private var player:MediaPlayer?=null
    private var voice:MediaPlayer?=null
    private var loaded=""
    private var loading=false
    private var retryAt=0L
    private var prepared=false
    private var focusHeld=false
    private var focusPaused=false
    @Volatile private var closed=false
    private var membership=""
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener{value->main.post{
            if(value==AudioManager.AUDIOFOCUS_GAIN){focusPaused=false;focusHeld=true}
            else {focusPaused=true;focusHeld=false;player?.let{if(prepared)runCatching{it.pause()}};voice?.let{runCatching{it.pause()}};musicStatus="내비 안내·통화 등 다른 오디오 우선";if(value==AudioManager.AUDIOFOCUS_LOSS)musicEnabled=false}
            changed()
        }}.build()
    private val session=MediaSession(context,"TogetherSyncMusic").apply{
        setCallback(object:MediaSession.Callback(){override fun onPlay(){setMusicEnabled(true);val music=c.snapshot?.optJSONObject("experience")?.optJSONObject("music");if(music?.optString("state")=="ENDED"&&(music.optString("ownerId")==c.client?.memberId||c.isOwner()))c.journey("music-resume")};override fun onPause(){setMusicEnabled(false)};override fun onStop(){setMusicEnabled(false)}})
    }
    private val tick=object:Runnable{override fun run(){if(closed)return;update();main.postDelayed(this,500)}}
    fun start(){main.post(tick)}
    fun setMusicEnabled(enabled:Boolean){musicEnabled=enabled;focusPaused=false;if(!enabled){releaseMusic();musicStatus="동기 음악 수신 꺼짐"};update();changed()}
    private fun acquireFocus():Boolean {
        if(focusHeld)return true
        c.mediaActive(true)
        val granted=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusHeld=granted;if(!granted){c.mediaActive(false);musicStatus="오디오 포커스 대기 중"};return granted
    }
    private fun releaseMusic(){player?.let{runCatching{it.release()}};player=null;prepared=false;loaded="";loading=false;driftMs=null;session.isActive=false
        if(voice==null){audio.abandonAudioFocusRequest(focus);focusHeld=false;c.mediaActive(false)}
    }
    private fun update(){
        val client=c.client
        val sessionKey=client?.let{"${it.sessionId}:${it.memberId}"}?:""
        if(membership!=sessionKey){membership=sessionKey;musicEnabled=false;musicStatus="동기 음악 수신 꺼짐";releaseMusic();voice?.release();voice=null;cache.listFiles()?.forEach{it.delete()}}
        nearbyMemory=c.fix?.takeIf{System.currentTimeMillis()-it.observedAt<30000}?.let{fix->album.entries().firstOrNull{distance(fix.latitude,fix.longitude,it.getDouble("latitude"),it.getDouble("longitude"))<=100}}
        val track=c.snapshot?.optJSONObject("experience")?.optJSONObject("music")
        if(!musicEnabled||client==null||track==null){if(player!=null)releaseMusic();if(musicEnabled&&client!=null)musicStatus="공유된 곡을 기다리고 있습니다";return}
        val id=track.getString("id")
        if(loaded!=id&&!loading&&System.currentTimeMillis()>=retryAt){
            releaseMusic();loading=true;loaded=id;musicStatus="실제 오디오 파일 준비 중";val assetId=track.getString("assetId")
            io.execute{val result=runCatching{val file=File(cache,"$assetId.audio");if(!file.exists())file.writeBytes(client.download(assetId));file};main.post{
                if(closed||c.client!==client||loaded!=id){result.getOrNull()?.delete();loading=false;return@post}
                result.fold({file->
                    try{val mp=MediaPlayer();player=mp;mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());mp.setDataSource(file.path)
                        mp.setOnPreparedListener{if(closed||player!==mp)return@setOnPreparedListener;prepared=true;loading=false;session.isActive=true;session.setMetadata(android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE,track.getString("name")).putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,it.duration.toLong()).build());update()}
                        mp.setOnErrorListener{_,_,_->musicStatus="오디오 재생 실패 · 파일 형식을 확인하세요";retryAt=System.currentTimeMillis()+30000;releaseMusic();changed();true};mp.prepareAsync()
                    }catch(_:Exception){loading=false;retryAt=System.currentTimeMillis()+30000;releaseMusic();musicStatus="오디오 파일을 재생할 수 없습니다"}
                },{loading=false;retryAt=System.currentTimeMillis()+30000;loaded="";musicStatus="음악 수신 실패 · 연결 확인 후 재시도"});changed()
            }};return
        }
        val mp=player?:return
        if(!prepared||focusPaused||voice!=null)return
        val target=(track.getLong("positionMs")+if(track.getString("state")=="PLAYING")kotlin.math.max(0,client.serverNow()-track.getLong("startAt"))else 0).coerceIn(0,track.getLong("durationMs"))
        runCatching{
            if(target>=mp.duration){if(mp.isPlaying)mp.pause();musicStatus="곡 재생 완료";session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY).setState(PlaybackState.STATE_STOPPED,mp.duration.toLong(),0f).build());if(focusHeld){audio.abandonAudioFocusRequest(focus);focusHeld=false;c.mediaActive(false)};return@runCatching}
            driftMs=mp.currentPosition-target
            if(abs(driftMs!!)>750)mp.seekTo(target,MediaPlayer.SEEK_CLOSEST)
            if(track.getString("state")=="PLAYING"&&client.serverNow()>=track.getLong("startAt")){
                if(!mp.isPlaying&&acquireFocus())mp.start()
                musicStatus=if(mp.isPlaying)"동기 재생 중 · 오차 ${driftMs}ms" else musicStatus
            }else{if(mp.isPlaying)mp.pause();musicStatus=if(track.getString("state")=="PAUSED")"그룹 음악 일시정지" else "함께 재생할 시각을 기다립니다"}
            session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP).setState(if(mp.isPlaying)PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,mp.currentPosition.toLong(),if(mp.isPlaying)1f else 0f).build())
        }.onFailure{musicStatus="재생 상태 확인 실패"}
    }
    fun pauseForRecording(){setMusicEnabled(false);voice?.release();voice=null}
    fun media(assetId:String,done:(ByteArray?)->Unit){val client=c.client?:return
        io.execute{val bytes=runCatching{client.download(assetId)}.getOrNull();main.post{if(!closed&&c.client===client)done(bytes)}}
    }
    fun playVoice(assetId:String,notice:(String)->Unit){media(assetId){bytes->if(bytes==null)notice("음성을 불러올 수 없습니다")else playLocalVoice(bytes,notice)}}
    fun playLocalVoice(bytes:ByteArray,notice:(String)->Unit){
        try{player?.let{if(prepared)it.pause()};voice?.release();val file=File(cache,"voice-current.m4a");file.writeBytes(bytes)
            val mp=MediaPlayer();voice=mp;mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());mp.setDataSource(file.path)
            mp.setOnPreparedListener{if(closed||voice!==mp)return@setOnPreparedListener;if(acquireFocus())it.start()else{voice?.release();voice=null;notice("다른 오디오 사용이 끝난 후 재생해 주세요")}}
            mp.setOnCompletionListener{it.release();if(voice===mp){voice=null;if(!musicEnabled){audio.abandonAudioFocusRequest(focus);focusHeld=false;c.mediaActive(false)}}}
            mp.setOnErrorListener{_,_,_->if(voice===mp){voice?.release();voice=null;if(!musicEnabled){audio.abandonAudioFocusRequest(focus);focusHeld=false;c.mediaActive(false)}};notice("음성 재생 실패");true};mp.prepareAsync()
        }catch(_:Exception){voice?.release();voice=null;notice("음성 재생 실패")}
    }
    override fun close(){closed=true;main.removeCallbacksAndMessages(null);voice?.release();voice=null;releaseMusic();session.release();io.shutdown();cache.listFiles()?.forEach{it.delete()}}
    companion object{fun distance(a:Double,b:Double,c:Double,d:Double):Float{val r=FloatArray(1);android.location.Location.distanceBetween(a,b,c,d,r);return r[0]}}
}

/** Device-local, explicit retention. Never contains session credentials. */
class LocalJourneyAlbum(context:Context){
    private val root=File(context.filesDir,"journey-album").apply{mkdirs()}
    private val index=File(root,"memories.json")
    fun entries():List<JSONObject> =runCatching{val a=JSONArray(index.readText());(0 until a.length()).map{a.getJSONObject(it)}}.getOrDefault(emptyList())
    fun save(drop:JSONObject,bytes:ByteArray){require(bytes.size<=5*1024*1024);val all=entries().toMutableList();val id=drop.getString("id");require(id.matches(Regex("[0-9a-f-]{36}")));require(all.any{it.getString("id")==id}||all.size<50){"기기 추억 보관 한도는 50개입니다"}
        val place=drop.getJSONObject("place");File(root,"$id.media").writeBytes(bytes);all.removeAll{it.getString("id")==id};all+=JSONObject().put("id",id).put("name",drop.getString("name")).put("mime",drop.getString("mime")).put("latitude",place.getDouble("latitude")).put("longitude",place.getDouble("longitude"));index.writeText(JSONArray(all).toString())
    }
    fun bytes(id:String):ByteArray{require(id.matches(Regex("[0-9a-f-]{36}")));return File(root,"$id.media").readBytes()}
    fun delete(id:String){val kept=entries().filter{it.getString("id")!=id};index.writeText(JSONArray(kept).toString());if(id.matches(Regex("[0-9a-f-]{36}")))File(root,"$id.media").delete()}
    fun saveJourney(geojson:JSONObject){File(root,"journey.geojson").writeText(geojson.toString())}
    fun deleteJourney(){File(root,"journey.geojson").delete()}
    fun journey():JSONObject?=runCatching{JSONObject(File(root,"journey.geojson").readText())}.getOrNull()
}
