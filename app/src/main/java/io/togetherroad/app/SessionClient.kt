package io.togetherroad.app
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

class ApiException(val status:Int,message:String):Exception(message)
/** Actual local/provisioned server client. Credentials never enter a URL or a log. */
class SessionClient(base:String) {
    private val baseUrl=base.trimEnd('/').also {
        val uri=URI(it);require(uri.scheme in listOf("http","https")&&!uri.host.isNullOrBlank()&&uri.userInfo==null&&uri.query==null&&uri.fragment==null&&uri.path in listOf("","/")){"서버 기본 주소를 확인하세요"}
    }
    var sessionId="";private set
    var memberId="";private set
    var inviteCode="";private set
    private var token=""
    @Volatile var serverOffsetMs=0L;private set
    fun serverNow()=System.currentTimeMillis()+serverOffsetMs
    fun create(name:String){accept(request("/sessions",JSONObject().put("name",name).put("consent",true)))}
    fun join(code:String,name:String){accept(request("/join",JSONObject().put("inviteCode",code.uppercase()).put("name",name).put("consent",true)))}
    private fun accept(j:JSONObject){sessionId=j.getString("sessionId");memberId=j.getString("memberId");inviteCode=j.getString("inviteCode");token=j.getString("token")}
    fun snapshot()=request("/sessions/$sessionId")
    fun post(action:String,body:JSONObject=JSONObject())=request("/sessions/$sessionId/$action",body)
    fun route(origin:JSONObject,destination:JSONObject)=request("/route",JSONObject().put("origin",origin).put("destination",destination))
    fun clear(){token="";sessionId="";memberId="";inviteCode=""}
    fun experience(action:String,body:JSONObject=JSONObject())=post("experience/$action",body)
    fun upload(bytes:ByteArray,mime:String,name:String,use:String,durationMs:Long=0):JSONObject {
        val c=connection("/sessions/$sessionId/assets");c.requestMethod="POST";c.doOutput=true;c.setFixedLengthStreamingMode(bytes.size)
        c.setRequestProperty("Content-Type",mime);c.setRequestProperty("X-Media-Name",java.net.URLEncoder.encode(name,"UTF-8").replace("+","%20"));c.setRequestProperty("X-Media-Use",use);c.setRequestProperty("X-Duration-Ms",durationMs.toString())
        try{c.outputStream.use{it.write(bytes)};val status=c.responseCode;val j=JSONObject((if(status in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()});if(status !in 200..299)throw ApiException(status,j.optString("error"));return j}finally{c.disconnect()}
    }
    fun download(assetId:String):ByteArray {
        require(assetId.matches(Regex("[0-9a-f-]{36}")))
        val c=connection("/sessions/$sessionId/assets/$assetId")
        try{if(c.responseCode!=200)throw ApiException(c.responseCode,"미디어 접근 권한 또는 도착 조건을 확인하세요")
            return c.inputStream.use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(32768);while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=20*1024*1024){"미디어가 너무 큽니다"};out.write(buf,0,n)};out.toByteArray()}
        }finally{c.disconnect()}
    }
    private fun connection(path:String):HttpURLConnection=(URI(baseUrl+path).toURL().openConnection() as HttpURLConnection).apply{
        connectTimeout=6000;readTimeout=20000;instanceFollowRedirects=false
        setRequestProperty("Authorization","Bearer $token")
    }
    private fun request(path:String,body:JSONObject?=null):JSONObject {
        val began=System.currentTimeMillis()
        val c=URI(baseUrl+path).toURL().openConnection() as HttpURLConnection
        c.connectTimeout=6000;c.readTimeout=20000;c.instanceFollowRedirects=false
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","TogetherOnTheRoad/0.3")
        if(token.isNotBlank())c.setRequestProperty("Authorization","Bearer $token")
        try{
            if(body!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}}
            val status=c.responseCode
            val raw=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}?:"{}"
            val result=JSONObject(raw)
            if(result.has("serverNow"))serverOffsetMs=result.getLong("serverNow")-(began+System.currentTimeMillis())/2
            if(status !in 200..299)throw ApiException(status,result.optString("error","요청 실패"))
            return result
        }finally{c.disconnect()}
    }
}
