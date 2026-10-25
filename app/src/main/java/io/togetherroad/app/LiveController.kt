package io.togetherroad.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.togetherroad.core.Place
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.*

/** Owns real device observations, authenticated membership and real route responses. */
class LiveController(private val context:Context,private val changed:()->Unit) :AutoCloseable {
    private val prefs=context.getSharedPreferences("road_settings",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor()
    private val location=PlatformLocation(context)
    var serverUrl=prefs.getString("server","http://10.0.2.2:8787")!!;private set
    var displayName=prefs.getString("name","내 차량")!!;private set
    var client:SessionClient?=null;private set
    var snapshot:JSONObject?=null;private set
    var fix:LiveFix?=null;private set
    var destination:Place?=null;private set
    var route:JSONObject?=null;private set
    var totalDistanceMeters:Double?=null;private set
    var status="현재 위치를 기다리고 있습니다";private set
    var busy=false;private set
    var working=false;private set
    private var closed=false
    private var sharing=false
    private var seq=0
    private var lastSentFix=0L
    private var lastRouteOrigin:LiveFix?=null
    private var epoch=0
    private var taskId=0
    private var nextRouteTryAt=0L
    private var routeFailures=0
    private var locationRenderPending=false
    private fun locationChanged(){if(!locationRenderPending){locationRenderPending=true;main.postDelayed({locationRenderPending=false;if(!closed)changed()},250)}}
    private val ticker=object:Runnable{
        override fun run(){if(closed)return;poll();main.postDelayed(this,2500)}
    }
    fun start(){location.start({f->main.post{if(!closed){fix=f;if(client==null&&destination==null)status="지도에서 목적지를 길게 눌러 지정하세요";locationChanged()}}},{s->main.post{if(!closed&&fix==null){status=s;changed()}}});main.post(ticker)}
    fun configure(url:String,name:String){require(!working&&client==null);SessionClient(url);require(name.isNotBlank()&&name.length<=20);epoch++;route=null;lastRouteOrigin=null;nextRouteTryAt=0;serverUrl=url;displayName=name;prefs.edit().putString("server",url).putString("name",name).apply();changed()}
    private fun poll(){
        if(fix!=null&&System.currentTimeMillis()-fix!!.observedAt>30000&&!busy){status="위치가 오래되었습니다 · GPS 수신을 확인하세요";changed()}
        val c=client
        if(c!=null&&sharing&&!busy){
            val f=fix
            work({
                if(f!=null&&System.currentTimeMillis()-f.observedAt<30000&&f.observedAt!=lastSentFix){
                    c.post("telemetry",point(f).put("seq",++seq).put("source",f.source).put("observedAt",f.observedAt).also{if(f.accuracyMeters!=null)it.put("accuracyMeters",f.accuracyMeters)})
                    lastSentFix=f.observedAt
                }
                c.snapshot()
            },{j->snapshot=j;destination=j.optJSONObject("destination")?.let(::place);val members=j.getJSONArray("members");for(i in 0 until members.length()){val m=members.getJSONObject(i);if(m.getString("id")==c.memberId){route=m.optJSONObject("route");totalDistanceMeters=if(m.isNull("totalDistanceMeters"))null else m.getDouble("totalDistanceMeters")}}
                val own=(0 until members.length()).map{members.getJSONObject(it)}.firstOrNull{it.getString("id")==c.memberId}
                status=when{fix==null||System.currentTimeMillis()-fix!!.observedAt>30000->"위치가 오래되었습니다 · GPS 수신을 확인하세요";own?.optBoolean("routePending")==true->"그룹 연결됨 · 도로 경로 계산 중";!own?.optString("routeError").isNullOrBlank()&&own?.optString("routeError")!="null"->"경로 서버 응답 실패 · 경로 재시도를 눌러주세요";else->"그룹 ${members.length()}대 연결 · 위치 공유 중"};changed()
            },{e->if(e is ApiException&&e.status in listOf(401,404)){c.clear();client=null;snapshot=null;sharing=false;status="그룹이 종료되었습니다"}else{snapshot=null;status="서버 연결이 끊겼습니다 · 다시 연결 중"};changed()},force=false)
        }else if(c==null&&!busy&&destination!=null&&fix!=null){
            val f=fix!!;val last=lastRouteOrigin
            if(System.currentTimeMillis()>=nextRouteTryAt&&System.currentTimeMillis()-f.observedAt<30000&&(route==null||last==null||meters(last,f)>=250||System.currentTimeMillis()-route!!.optLong("computedAt")>60000))calculateSolo()
        }
    }
    fun setDestination(p:Place){
        if(working)return
        val c=client
        if(c==null){epoch++;nextRouteTryAt=0;routeFailures=0;destination=p;route=null;totalDistanceMeters=null;lastRouteOrigin=null;calculateSolo(true)}
        else work({c.post("destination",placeJson(p))},{destination=p;route=null;status="공동 목적지를 설정했습니다";changed()})
    }
    private fun calculateSolo(interactive:Boolean=false){
        if(busy&&!interactive)return
        val f=fix;val d=destination
        if(f==null||d==null){status="현재 위치가 있어야 경로를 계산할 수 있습니다";changed();return}
        if(System.currentTimeMillis()-f.observedAt>30000){status="현재 위치가 오래되었습니다 · GPS 수신을 확인하세요";changed();return}
        status="도로 경로를 계산하고 있습니다";nextRouteTryAt=System.currentTimeMillis()+15000
        val c=SessionClient(serverUrl)
        work({c.route(point(f),placeJson(d))},{r->route=r;totalDistanceMeters=totalDistanceMeters?:r.getDouble("distanceMeters");lastRouteOrigin=f;routeFailures=0;status="도로 경로 연결됨 · 교통정보 미반영";changed()}, {e->route=null;routeFailures++;nextRouteTryAt=System.currentTimeMillis()+(15000L*(1L shl min(routeFailures-1,3)));status="도로 경로를 불러오지 못했습니다 · 재시도하거나 연결 설정을 확인하세요";changed()},force=interactive)
    }
    fun createGroup(){
        val c=SessionClient(serverUrl);val target=destination
        work({try{c.create(displayName);if(target!=null)c.post("destination",placeJson(target));c.snapshot()}catch(e:Throwable){if(c.sessionId.isNotBlank())runCatching{c.post("leave")};throw e}},{j->client=c;sharing=true;seq=0;lastSentFix=0;snapshot=j;status="그룹을 만들었습니다 · 초대 코드를 공유하세요";changed()})
    }
    fun joinGroup(code:String){val c=SessionClient(serverUrl);work({c.join(code,displayName);c.snapshot()},{j->epoch++;client=c;sharing=true;seq=0;lastSentFix=0;snapshot=j;route=null;totalDistanceMeters=null;destination=j.optJSONObject("destination")?.let(::place);status="그룹에 참여했습니다";changed()})}
    fun propose(p:Place){val c=client?:return;work({c.post("proposals",JSONObject().put("candidate",placeJson(p)).put("kind","MEETUP"))},{status="합류 장소를 제안했습니다";changed()})}
    fun vote(yes:Boolean){val c=client?:return;val p=snapshot?.optJSONObject("proposal")?:return;work({c.post("vote",JSONObject().put("proposalId",p.getString("id")).put("yes",yes))},{status=if(yes)"제안에 동의했습니다" else "다른 장소를 요청했습니다";changed()})}
    fun message(kind:String){val c=client?:return;work({c.post("message",JSONObject().put("kind",kind))},{status="그룹에 전달했습니다";changed()})}
    fun adoptProposal(){val c=client?:return;val p=snapshot?.optJSONObject("proposal")?:return
        work({c.post("adopt",JSONObject().put("proposalId",p.getString("id")))},{res->route=res.optJSONObject("route");status="선택한 합류 장소를 이 차량의 실제 경로에 적용했습니다";changed()})
    }
    fun leave(end:Boolean=false){
        val c=client?:return
        // Revocation must queue behind an in-flight request, never be dropped by busy UI state.
        sharing=false;epoch++;snapshot=null;route=null;totalDistanceMeters=null
        status="위치 전송 중단 · 그룹 연결을 종료하고 있습니다"
        work({c.post(if(end)"end" else "leave")},{c.clear();client=null;status="그룹 공유를 종료했습니다";changed()}, {e->
            if(e is ApiException&&e.status in listOf(401,404)){c.clear();client=null;status="그룹 공유를 종료했습니다"}
            else status="위치 전송 중단 · 서버 종료 확인 실패, 나가기를 다시 눌러주세요"
            changed()
        },force=true)
    }
    fun retry(){val c=client;if(c!=null)work({c.post("retry")},{status="경로를 다시 요청했습니다";changed()})else if(!working){epoch++;nextRouteTryAt=0;routeFailures=0;route=null;calculateSolo(true)}}
    fun isOwner()=client!=null&&snapshot?.optString("ownerId")==client?.memberId
    private fun fail(e:Throwable){status=when(e){is ApiException->when(e.status){403->"그룹 생성자만 목적지를 바꿀 수 있습니다";404->"초대 코드나 그룹을 확인하세요";409->"현재 상태에서는 실행할 수 없습니다. 위치나 제안 상태를 확인하세요";else->"요청 실패: ${e.message}"};else->"서버 연결을 확인하세요"};changed()}
    private fun work(task:()->JSONObject,done:(JSONObject)->Unit,error:(Throwable)->Unit={fail(it)},force:Boolean=true){
        if((busy&&!force)||closed)return
        val gen=epoch;val ticket=++taskId;busy=true;if(force)working=true;changed()
        executor.execute{val result=runCatching(task);main.post{if(ticket==taskId){busy=false;working=false};if(closed||gen!=epoch)return@post;result.fold(done,error)}}
    }
    override fun close(){closed=true;sharing=false;epoch++;main.removeCallbacksAndMessages(null);location.close();client?.let{c->executor.execute{runCatching{c.post("leave")};c.clear()}};executor.shutdown();snapshot=null;fix=null;route=null}
    companion object{
        fun placeJson(p:Place)=JSONObject().put("name",p.name).put("latitude",p.latitude).put("longitude",p.longitude)
        fun place(j:JSONObject)=Place("${j.getDouble("latitude")},${j.getDouble("longitude")}",j.optString("name","선택한 위치"),j.getDouble("latitude"),j.getDouble("longitude"))
        fun point(f:LiveFix)=JSONObject().put("latitude",f.latitude).put("longitude",f.longitude)
        private fun meters(a:LiveFix,b:LiveFix):Double{val l1=Math.toRadians(a.latitude);val l2=Math.toRadians(b.latitude);val dl=Math.toRadians(b.longitude-a.longitude);return 6371000*2*asin(sqrt((sin((l2-l1)/2).pow(2)+cos(l1)*cos(l2)*sin(dl/2).pow(2)).coerceIn(0.0,1.0)))}
    }
}
