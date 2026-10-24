package io.togetherroad.app
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Bench REST client. Tokens remain in memory only, never in URL, logs or preferences. */
class SessionClient(base: String) {
    private val baseUrl = base.trimEnd('/').also {
        val uri = URI(it)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) { "서버 기본 URL을 확인하세요" }
    }
    var sessionId: String = ""; private set
    var memberId: String = ""; private set
    var inviteCode: String = ""; private set
    private var token: String = ""
    fun create(name: String) { accept(call("/sessions", JSONObject().put("name", name).put("consent", true))) }
    fun join(id: String, code: String, name: String) {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "세션 ID를 확인하세요" }
        accept(call("/sessions/$id/join", JSONObject().put("name",name).put("consent",true).put("inviteCode",code)))
    }
    private fun accept(j: JSONObject) { sessionId=j.getString("sessionId"); memberId=j.getString("memberId"); inviteCode=j.getString("inviteCode"); token=j.getString("token") }
    fun snapshot() = call("/sessions/$sessionId")
    fun post(action: String, body: JSONObject = JSONObject()) = call("/sessions/$sessionId/$action",body)
    fun clear() { token="";sessionId="";memberId="";inviteCode="" }
    private fun call(path: String, body: JSONObject? = null): JSONObject {
        val c = URI(baseUrl+path).toURL().openConnection() as HttpURLConnection
        c.connectTimeout=4000;c.readTimeout=4000;c.instanceFollowRedirects=false
        c.setRequestProperty("Accept","application/json")
        if (token.isNotBlank()) c.setRequestProperty("Authorization","Bearer $token")
        try {
            if (body != null) {
                c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val text = (if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val j=JSONObject(text)
            check(status in 200..299) { "HTTP $status: ${j.optString("error", "요청 실패")}" }
            return j
        } finally { c.disconnect() }
    }
}
