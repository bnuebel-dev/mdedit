package app.mdedit

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class PCloudException(msg: String) : Exception(msg)

data class CloudItem(val name: String, val isDir: Boolean, val id: Long)

object Shared {
    val http: OkHttpClient = OkHttpClient()
}

/** Minimaler pCloud-Client. Spricht ausschliesslich per HTTPS mit dem pCloud-API-Host. */
class PCloud(private val host: String, private val auth: String) {

    private fun url(method: String, params: Map<String, String>): HttpUrl {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .addPathSegment(method)
            .addQueryParameter("auth", auth)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun call(req: Request): JSONObject {
        Shared.http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val j = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw PCloudException("Unerwartete Antwort von pCloud (HTTP ${r.code})")
            }
            val code = j.optInt("result", -1)
            if (code != 0) {
                throw PCloudException(j.optString("error", "pCloud-Fehler $code") + " (Code $code)")
            }
            return j
        }
    }

    fun list(folderId: Long): List<CloudItem> {
        val j = call(Request.Builder().url(url("listfolder", mapOf("folderid" to folderId.toString()))).build())
        val arr = j.getJSONObject("metadata").optJSONArray("contents") ?: return emptyList()
        val out = ArrayList<CloudItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val isDir = o.getBoolean("isfolder")
            out.add(CloudItem(o.getString("name"), isDir, if (isDir) o.getLong("folderid") else o.getLong("fileid")))
        }
        return out
    }

    fun download(fileId: Long): String {
        val j = call(Request.Builder().url(url("getfilelink", mapOf("fileid" to fileId.toString()))).build())
        val link = "https://" + j.getJSONArray("hosts").getString(0) + j.getString("path")
        Shared.http.newCall(Request.Builder().url(link).build()).execute().use { r ->
            if (!r.isSuccessful) throw PCloudException("Download fehlgeschlagen (HTTP ${r.code})")
            return r.body?.string() ?: ""
        }
    }

    /** Macht das Token serverseitig ungueltig. */
    fun logout() {
        call(Request.Builder().url(url("logout", emptyMap())).build())
    }

    /** Laedt Text als Datei hoch (ueberschreibt eine gleichnamige Datei). Gibt die fileid zurueck. */
    fun upload(folderId: Long, name: String, text: String): Long {
        val body = text.toByteArray(Charsets.UTF_8).toRequestBody("application/octet-stream".toMediaType())
        val u = url(
            "uploadfile",
            mapOf("folderid" to folderId.toString(), "filename" to name, "nopartial" to "1")
        )
        val j = call(Request.Builder().url(u).put(body).build())
        return j.getJSONArray("fileids").getLong(0)
    }

    companion object {
        /** Meldet per E-Mail/Passwort an und liefert ein Auth-Token. Das Passwort wird nicht gespeichert. */
        fun login(host: String, user: String, pass: String, tfaCode: String): String {
            val form = FormBody.Builder()
                .add("getauth", "1")
                .add("username", user)
                .add("password", pass)
            if (tfaCode.isNotBlank()) form.add("tfacode", tfaCode.trim())
            val req = Request.Builder().url("https://$host/userinfo").post(form.build()).build()
            Shared.http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val j = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    throw PCloudException("Unerwartete Antwort von pCloud (HTTP ${r.code})")
                }
                val code = j.optInt("result", -1)
                if (code != 0) {
                    throw PCloudException(
                        j.optString("error", "Anmeldung fehlgeschlagen") +
                            " (Code $code) - Region (EU/US) pruefen"
                    )
                }
                val token = j.optString("auth", "")
                if (token.isEmpty()) throw PCloudException("pCloud hat kein Token geliefert")
                return token
            }
        }
    }
}
