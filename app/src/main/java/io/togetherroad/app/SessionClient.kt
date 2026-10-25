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
    fun create(name:String){accept(request("/sessions",JSONObject().put("name",name).put("consent",true)))}
    fun join(code:String,name:String){accept(request("/join",JSONObject().put("inviteCode",code.uppercase()).put("name",name).put("consent",true)))}
    private fun accept(j:JSONObject){sessionId=j.getString("sessionId");memberId=j.getString("memberId");inviteCode=j.getString("inviteCode");token=j.getString("token")}
    fun snapshot()=request("/sessions/$sessionId")
    fun post(action:String,body:JSONObject=JSONObject())=request("/sessions/$sessionId/$action",body)
    fun route(origin:JSONObject,destination:JSONObject)=request("/route",JSONObject().put("origin",origin).put("destination",destination))
    fun clear(){token="";sessionId="";memberId="";inviteCode=""}
    private fun request(path:String,body:JSONObject?=null):JSONObject {
        val c=URI(baseUrl+path).toURL().openConnection() as HttpURLConnection
        c.connectTimeout=6000;c.readTimeout=20000;c.instanceFollowRedirects=false
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","TogetherOnTheRoad/0.3")
        if(token.isNotBlank())c.setRequestProperty("Authorization","Bearer $token")
        try{
            if(body!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}}
            val status=c.responseCode
            val raw=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}?:"{}"
            val result=JSONObject(raw)
            if(status !in 200..299)throw ApiException(status,result.optString("error","요청 실패"))
            return result
        }finally{c.disconnect()}
    }
}
