package io.togetherroad.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.*
import io.togetherroad.core.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** v0.1 bench prototype: explicit REPLAY data; never use while driving. */
class MainActivity : Activity() {
    private val bg = Color.rgb(16,28,36)
    private val muted = Color.rgb(161,180,191)
    private val mint = Color.rgb(98,228,193)
    private lateinit var root: LinearLayout
    private lateinit var nav: PlatformNavigation
    private val drive = ReplayDrive()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var client: SessionClient? = null
    private var networkState: JSONObject? = null
    private var status = "출발 전, 함께 이동할 그룹을 준비하세요."
    private var parked = true // simulation/bench acknowledgement, not vehicle hardware state
    private var seq = 0
    private var eta = 150
    private var consent = false
    private var generation = 0
    private var busy = false
    private var destroyed = false
    private val poll = object : Runnable {
        override fun run() {
            val c=client ?: return
            if (!busy && consent) request({ publish(c); c.snapshot() }, { networkState=it; render() })
            main.postDelayed(this,2000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nav = PlatformNavigation(this)
        window.statusBarColor = bg; window.navigationBarColor = bg
        render()
    }
    private fun dp(n: Int) = (resources.displayMetrics.density*n).toInt()
    private fun container() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value: String, size: Float=16f, color: Int=Color.WHITE, bold: Boolean=false) = TextView(this).apply {
        text=value;textSize=size;setTextColor(color);setPadding(0,dp(5),0,dp(5))
        if(bold) setTypeface(typeface,Typeface.BOLD)
    }
    private fun box(): LinearLayout = container().apply {
        setPadding(dp(18),dp(12),dp(18),dp(12))
        background=GradientDrawable().apply { setColor(Color.rgb(28,44,55));cornerRadius=dp(16).toFloat() }
        root.addView(this,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(10),0,dp(4))})
    }
    private fun button(parent: LinearLayout, label: String, enabled: Boolean=true, action: ()->Unit) {
        parent.addView(Button(this).apply {
            text=label;isAllCaps=false;setTextColor(bg);minHeight=dp(52)
            backgroundTintList=android.content.res.ColorStateList.valueOf(mint)
            isEnabled=enabled && !busy
            setOnClickListener { action() }
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
    }
    private fun render() {
        if (destroyed) return
        val scroll=ScrollView(this).apply{setBackgroundColor(bg);fitsSystemWindows=true}
        root=container().apply{setPadding(dp(24),dp(20),dp(24),dp(30))};scroll.addView(root);setContentView(scroll)
        root.addView(text("TOGETHER ON THE ROAD",12f,mint,true))
        root.addView(text("각자의 차에서,\n하나의 여정으로",30f,Color.WHITE,true))
        root.addView(text("REPLAY 위치·ETA  /  ${nav.label}",12f,muted))
        root.addView(text("정차한 개발·시연 환경 전용 · 실제 주행에서 사용하지 마세요",12f,Color.rgb(255,201,120)))
        root.addView(text(status,14f,mint))
        val check=Switch(this).apply {
            setText(R.string.bench_parked);setTextColor(Color.WHITE);isChecked=parked
            setOnCheckedChangeListener { _,v -> parked=v;render() }
        };root.addView(check)
        if (client != null) renderNetwork() else renderLocal()
    }
    private fun renderLocal() {
        if (!drive.active) {
            val b=box();b.addView(text("함께 떠날 준비",21f,Color.WHITE,true));b.addView(text("서울 · 수원 · 인천의 3대 차량이 강릉으로 이동하는 가상 시나리오입니다.",15f,muted))
            button(b,"위치 공유에 동의하고 3대 데모 시작",parked) { drive.start();status="3대 연결 · 위치와 ETA는 모두 가상 데이터";render() }
            button(b,"서버에 연결해 그룹 만들기 / 참여",parked) { connectDialog() }; return
        }
        val b=box();b.addView(text("강릉으로 함께 이동 중",21f,Color.WHITE,true))
        for (v in drive.vehicles) b.addView(text("${v.id}  ${v.origin}   ·   도착까지 ${v.etaMinutes}분   ·   REPLAY",17f))
        val gap=drive.vehicles.maxOf{it.etaMinutes}-drive.vehicles.minOf{it.etaMinutes}
        b.addView(text("그룹 도착 시간 편차 ${gap}분",14f,mint))
        button(b,"B 차량 25분 지연 주입",parked){drive.delayB();status="가상 지연 발생 · 합류 후보를 다시 비교해 보세요";render()}
        val rec=box();rec.addView(text("그룹 합류 도우미",21f,Color.WHITE,true));rec.addView(text("규칙 기반 점수 · 실제 도로 탐색 및 AI 모델 연결 전",12f,muted))
        for (c in drive.candidates()) rec.addView(text("${c.place.name}\n도착 ${c.arrivals.joinToString(" / ")}분 · 점수 ${c.score}",16f))
        button(rec,"최적 후보를 그룹에 제안",parked){drive.propose(System.currentTimeMillis());status="각 차량에서 별도로 승인해야 적용됩니다";render()}
        drive.proposal?.let { p ->
            val a=box();a.addView(text("제안 ${p.id} · ${p.place.name}",19f,Color.WHITE,true))
            for ((id,s) in p.states) {
                a.addView(text("차량 $id  ·  $s",16f))
                button(a,"차량 $id 운전자 승인 및 적용",parked && s==ApplyState.PENDING) { confirmApply {
                    try {
                        drive.approve(id,p.id,System.currentTimeMillis());drive.applying(id,p.id,System.currentTimeMillis())
                        // A is this real device. B/C always use explicit local replay acknowledgements.
                        if(id=="A") nav.apply(p.place){ok,msg->drive.complete(id,p.id,ok);status=msg;render()}
                        else main.postDelayed({drive.complete(id,p.id,true);status="차량 $id REPLAY 승인·적용 완료";render()},700)
                        render()
                    } catch(e:Exception){status=e.message ?: "만료된 제안";render()}
                } }
            }
        }
        button(root,"세션 종료 · 위치 삭제",parked){generation++;drive.stop();status="위치 공유를 종료하고 가상 위치를 삭제했습니다";render()}
    }
    private fun connectDialog() {
        val form=container().apply{setPadding(dp(20),0,dp(20),0)}
        fun input(hint:String,value:String="")=EditText(this).apply{this.hint=hint;setText(value);isSingleLine=true;form.addView(this)}
        val base=input("서버 URL","http://10.0.2.2:8787")
        val name=input("차량 표시 이름","A")
        val id=input("참여할 세션 ID (생성 시 비움)")
        val code=input("초대 코드 (생성 시 비움)")
        val agree=CheckBox(this).apply{setText(R.string.sharing_consent);form.addView(this)}
        val dialog=AlertDialog.Builder(this).setTitle("서버 연결 · REPLAY").setView(form).setNegativeButton("취소",null).setPositiveButton("연결",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(!agree.isChecked){agree.error="공유 동의가 필요합니다";return@setOnClickListener}
            try {
                val c=SessionClient(base.text.toString())
                val inputId=id.text.toString();val inputCode=code.text.toString();val inputName=name.text.toString()
                dialog.dismiss()
                request({
                    try { if(inputId.isBlank())c.create(inputName) else c.join(inputId,inputCode,inputName);c.snapshot() }
                    catch(e:Exception){ if(c.sessionId.isNotBlank())runCatching{c.post("leave")};c.clear();throw e }
                }, {
                    drive.stop();client=c;networkState=it;consent=true;seq=0
                    eta=when(it.getJSONArray("members").length()){2->158;3->155;else->150}
                    status="서버 연결 완료 · 2초 간격 가상 위치 공유";main.post(poll);render()
                })
            }catch(e:Exception){status=e.message ?: "URL 오류";render()}
        }};dialog.show()
    }
    private fun publish(c: SessionClient) {
        c.post("telemetry",JSONObject().put("seq",++seq).put("source","REPLAY").put("latitude",37.5).put("longitude",127.0)
            .put("etaMinutes",eta).put("observedAt",System.currentTimeMillis()))
    }
    private fun renderNetwork() {
        val c=client ?: return
        val b=box();b.addView(text("연결된 그룹 · 강릉",21f,Color.WHITE,true))
        button(b,"초대 정보 복사",parked) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Together invitation","세션 ID: ${c.sessionId}\n초대 코드: ${c.inviteCode}"))
            status="초대 정보를 복사했습니다 · 참가할 차량에 전달하세요";render()
        }
        b.addView(text("내 차량 ETA ${eta}분 · REPLAY",14f,mint))
        val state=networkState
        state?.getJSONArray("members")?.let { ms -> for(i in 0 until ms.length()) {
            val m=ms.getJSONObject(i);val t=m.optJSONObject("telemetry")
            b.addView(text("${m.getString("name")}  ·  ${t?.optInt("etaMinutes") ?: "대기"}분  ·  ${if(m.getBoolean("stale"))"갱신 필요" else "REPLAY"}",17f))
        } }
        button(b,"내 차량 25분 지연 주입",parked && consent){eta+=25;status="내 REPLAY ETA를 25분 늘렸습니다";render()}
        button(b,"그룹 합류 후보 제안",parked && consent){request({c.post("proposals");c.snapshot()},{networkState=it;status="그룹 제안이 도착했습니다";render()})}
        state?.optJSONObject("proposal")?.let{p ->
            val candidate=p.getJSONObject("candidate");val proposalId=p.getString("id")
            val st=p.getJSONObject("states").optString(c.memberId,"PENDING")
            b.addView(text("${candidate.getString("name")}\n내 적용 상태 $st",18f,Color.WHITE,true))
            button(b,"내 차량만 승인하고 적용",parked && consent && st=="PENDING") { confirmApply {
                request({c.post("approve",JSONObject().put("proposalId",proposalId))}, { response ->
                    val target=response.getJSONObject("candidate")
                    val gen=generation
                    nav.apply(Place(target.getString("id"),target.getString("name"),target.getDouble("latitude"),target.getDouble("longitude"))){ok,msg->
                        if(gen==generation && client===c && consent)request({c.post("result",JSONObject().put("proposalId",proposalId).put("result",if(ok)"APPLIED" else "FAILED"));c.snapshot()},{networkState=it;status=msg;render()})
                    }
                    status="적용 요청 중 · SDK/REPLAY 결과 확인 전";render()
                })
            } }
        }
        button(root,"공유 철회 및 그룹 나가기",parked){leaveNetwork(false)}
        button(root,"그룹 전체 종료",parked){leaveNetwork(true)}
    }
    private fun leaveNetwork(end:Boolean) {
        val c=client ?: return
        // Stop all local publication before waiting for network acknowledgement.
        consent=false;generation++;main.removeCallbacks(poll);networkState=null
        request({c.post(if(end)"end" else "leave");JSONObject()},{
            c.clear();client=null;status="서버에서 공유 종료·위치 삭제 확인";render()
        }, {message->
            // Keep credential for an explicit retry, but never publish or show cached group positions.
            status="로컬 공유 중단 · 서버 삭제 확인 실패. 종료 버튼으로 재시도: $message";render()
        })
    }
    private fun confirmApply(action:()->Unit) {
        if(!parked) return
        AlertDialog.Builder(this).setTitle("이 차량에서 적용할까요?")
            .setMessage("정차한 개발 벤치임을 확인하세요. Pleos 빌드는 내장 내비게이션을 실제 변경합니다. 현재 후보는 가상 테스트 좌표입니다.")
            .setNegativeButton("취소",null).setPositiveButton("승인"){_,_->action()}.show()
    }
    private fun request(work:()->JSONObject, done:(JSONObject)->Unit, error:((String)->Unit)?=null) {
        if(destroyed || busy) return
        val gen=generation;busy=true;render()
        io.execute {
            val result=runCatching(work)
            main.post {
                busy=false
                if(destroyed || gen!=generation) return@post
                result.fold(done,{e ->
                    val msg=e.message ?: e.javaClass.simpleName
                    if(error!=null)error(msg) else {networkState=null;status="연결/요청 실패 · 표시 데이터 지움: $msg";render()}
                })
            }
        }
    }
    override fun onDestroy() {
        destroyed=true;generation++;consent=false;main.removeCallbacksAndMessages(null);nav.close()
        // Best-effort revocation. Server TTL remains the fallback if the process/network disappears.
        client?.let { c -> io.execute { runCatching { c.post("leave") };c.clear() } }
        io.shutdown();super.onDestroy()
    }
}
