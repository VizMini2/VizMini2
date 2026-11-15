// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.profiles.ProfileStore
import com.juancams.rviz2mini.profiles.ProfileSync
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The VizMini2 account on the PC: the same Firebase one as on the phone (Google, Microsoft, GitHub or email), and the
 * robot profiles synced in Firestore (`users/{uid}/profiles`, same format as Android). Firebase has no
 * desktop SDK: everything goes through its REST APIs. Google, Microsoft and GitHub open in the browser with a local page
 * (http://localhost, an authorized domain by default) that uses the Firebase web SDK and returns the session to the PC.
 */
object DesktopAccount {
    /** apiKey and projectId of the project (from app/google-services.json at build time; see build.gradle.kts). */
    private val config: JSONObject? by lazy {
        DesktopAccount::class.java.getResourceAsStream("/firebase.json")?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }
    val available get() = config != null
    private val apiKey get() = config!!.getString("apiKey")
    private val projectId get() = config!!.getString("projectId")

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val prefs get() = Platform.prefs("account")

    class Session(val uid: String, val email: String?, val provider: String?, val refreshToken: String) {
        @Volatile var idToken: String? = null
        @Volatile var expiresAt = 0L
    }

    sealed class SyncStatus {
        data object Idle : SyncStatus()
        data object Syncing : SyncStatus()
        data class Synced(val count: Int, val atMs: Long) : SyncStatus()
        data class Failed(val message: String) : SyncStatus()
    }

    var session by mutableStateOf<Session?>(restore()); private set
    var status by mutableStateOf<SyncStatus>(SyncStatus.Idle); private set
    /** After pulling changes from the cloud (so the profiles card repaints). */
    var changes by mutableStateOf(0); private set

    private fun restore(): Session? {
        val p = Platform.prefs("account")
        val rt = p.getString("refreshToken", null) ?: return null
        return Session(p.getString("uid", null) ?: return null, p.getString("email", null), p.getString("provider", null), rt)
    }

    private fun keep(s: Session?) {
        session = s
        val e = prefs.edit()
        if (s == null) e.putString("refreshToken", null).putString("uid", null).putString("email", null).putString("provider", null)
        else e.putString("refreshToken", s.refreshToken).putString("uid", s.uid).putString("email", s.email).putString("provider", s.provider)
        e.apply()
    }

    fun signOut() { keep(null); status = SyncStatus.Idle }

    // ---------------------------------------------------------------- sign in

    private fun post(url: String, body: String, contentType: String = "application/json", token: String? = null): JSONObject {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body))
        token?.let { b.header("Authorization", "Bearer $it") }
        return parse(http.send(b.build(), HttpResponse.BodyHandlers.ofString()))
    }

    private fun parse(r: HttpResponse<String>): JSONObject {
        val o = runCatching { JSONObject(r.body().ifEmpty { "{}" }) }.getOrElse { JSONObject() }
        if (r.statusCode() !in 200..299) throw IllegalStateException(o.optJSONObject("error")?.optString("message")?.ifEmpty { null } ?: "HTTP ${r.statusCode()}")
        return o
    }

    /** Email and password (the account must exist: it is created on the phone or here with [signUp]). */
    fun signInWithPassword(email: String, password: String) {
        val o = post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$apiKey",
            JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString())
        start(o, "password")
    }

    fun signUp(email: String, password: String) {
        val o = post("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey",
            JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString())
        start(o, "password")
    }

    fun sendPasswordReset(email: String) {
        post("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$apiKey",
            JSONObject().put("requestType", "PASSWORD_RESET").put("email", email).toString())
    }

    private fun start(o: JSONObject, provider: String) {
        val s = Session(o.getString("localId"), o.optString("email").ifEmpty { null }, provider, o.getString("refreshToken"))
        s.idToken = o.getString("idToken"); s.expiresAt = System.currentTimeMillis() + o.optLong("expiresIn", 3600) * 1000
        keep(s)
        sync()
    }

    /**
     * Google, Microsoft or GitHub in the browser. Opens http://localhost:<port> with the Firebase web SDK, which does
     * the provider sign-in (popup) and sends the refresh token here. `onDone(null)` on success; otherwise, the error.
     */
    fun signInWithBrowser(provider: String, onDone: (String?) -> Unit) {
        val cfg = config ?: return onDone("Not configured")
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        fun stop() = Thread { Thread.sleep(500); server.stop(0) }.start()
        server.createContext("/") { ex ->
            val page = SIGN_IN_PAGE.replace("@API_KEY@", cfg.getString("apiKey")).replace("@PROJECT@", cfg.getString("projectId"))
                .replace("@PROVIDER@", provider)
            val bytes = page.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/done") { ex ->
            val body = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            ex.sendResponseHeaders(204, -1); ex.close()
            stop()
            Thread {
                try {
                    val o = JSONObject(body)
                    if (o.has("error")) { Platform.main.post { onDone(o.getString("error")) }; return@Thread }
                    val s = Session(o.getString("uid"), o.optString("email").ifEmpty { null }, provider, o.getString("refreshToken"))
                    refresh(s)
                    Platform.main.post { keep(s); onDone(null); sync() }
                } catch (e: Exception) { Platform.main.post { onDone(e.message ?: e.toString()) } }
            }.start()
        }
        server.start()
        // If it does not finish within 5 minutes, it is closed.
        Thread { Thread.sleep(300_000); server.stop(0) }.apply { isDaemon = true }.start()
        openBrowser("http://localhost:$port/")
    }

    fun openBrowser(url: String) {
        Thread {
            runCatching {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) java.awt.Desktop.getDesktop().browse(URI(url))
                else ProcessBuilder("xdg-open", url).start()
            }
        }.start()
    }

    /** A valid id token (renewed with the refresh token when it expires). */
    private fun token(s: Session): String {
        if (s.idToken == null || System.currentTimeMillis() > s.expiresAt - 60_000) refresh(s)
        return s.idToken!!
    }

    private fun refresh(s: Session) {
        val o = post("https://securetoken.googleapis.com/v1/token?key=$apiKey",
            "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(s.refreshToken, "UTF-8"), "application/x-www-form-urlencoded")
        s.idToken = o.getString("id_token"); s.expiresAt = System.currentTimeMillis() + o.optString("expires_in", "3600").toLong() * 1000
    }

    // ---------------------------------------------------------------- Firestore

    private fun docsUrl(uid: String) = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/users/$uid/profiles"

    private fun remote(s: Session): Map<String, ProfileSync.Record> {
        val out = HashMap<String, ProfileSync.Record>()
        var page: String? = null
        do {
            val url = docsUrl(s.uid) + "?pageSize=300" + (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val r = parse(http.send(HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer ${token(s)}").GET().build(), HttpResponse.BodyHandlers.ofString()))
            val docs = r.optJSONArray("documents")
            if (docs != null) for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val f = d.optJSONObject("fields") ?: continue
                val name = f.optJSONObject("name")?.optString("stringValue") ?: continue
                val updated = f.optJSONObject("updatedAt")?.let { it.optString("integerValue").toLongOrNull() ?: it.optDouble("doubleValue", 0.0).toLong() } ?: 0L
                val deleted = f.optJSONObject("deleted")?.optBoolean("booleanValue") == true
                val json = f.optJSONObject("json")?.optString("stringValue", null)
                out[d.getString("name").substringAfterLast('/')] = ProfileSync.Record(name, updated, if (deleted) null else json)
            }
            page = r.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    private fun write(s: Session, r: ProfileSync.Record) {
        val fields = JSONObject()
            .put("name", JSONObject().put("stringValue", r.name))
            .put("updatedAt", JSONObject().put("integerValue", r.updatedAt.toString()))
            .put("deleted", JSONObject().put("booleanValue", r.deleted))
            .put("json", if (r.json == null) JSONObject().put("nullValue", JSONObject.NULL) else JSONObject().put("stringValue", r.json))
        val url = docsUrl(s.uid) + "/" + URLEncoder.encode(ProfileSync.key(r.name), "UTF-8")
        parse(http.send(HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer ${token(s)}").header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(JSONObject().put("fields", fields).toString())).build(), HttpResponse.BodyHandlers.ofString()))
    }

    /** Like Android's CloudProfiles.sync: pulls the cloud, the most recent wins on each side. */
    fun sync() {
        val s = session ?: return
        if (status == SyncStatus.Syncing || !available) return
        status = SyncStatus.Syncing
        Thread {
            try {
                val store = ProfileStore.get()
                val remote = remote(s)
                val plan = ProfileSync.plan(runOnMain { store.records() }, remote, s.uid)
                var changed = false
                runOnMain { for (r in plan.toLocal) changed = store.applyRemote(r, s.uid) || changed }
                for (r in plan.toRemote) write(s, r)
                runOnMain { store.markSynced(plan.synced, s.uid) }
                Platform.main.post { status = SyncStatus.Synced(store.all.size, System.currentTimeMillis()); if (changed) changes++ }
            } catch (e: Exception) {
                Platform.main.post { status = SyncStatus.Failed(e.message ?: e.toString()) }
            }
        }.start()
    }

    /** Uploads a profile change (save or delete) as soon as it is made. */
    fun push(name: String) {
        val s = session ?: return
        Thread {
            try {
                val r = runOnMain { ProfileStore.get().records()[ProfileSync.key(name)] } ?: return@Thread
                write(s, r)
                runOnMain { ProfileStore.get().markSynced(listOf(ProfileSync.key(name)), s.uid) }
                Platform.main.post { status = SyncStatus.Synced(ProfileStore.get().all.size, System.currentTimeMillis()) }
            } catch (e: Exception) { Platform.main.post { status = SyncStatus.Failed(e.message ?: e.toString()) } }
        }.start()
    }

    /** ProfileStore belongs to the main thread. */
    private fun <T> runOnMain(block: () -> T): T {
        val q = java.util.concurrent.ArrayBlockingQueue<Result<T>>(1)
        Platform.main.post { q.put(runCatching(block)) }
        return q.take().getOrThrow()
    }

    /** The local sign-in page for the browser (Firebase web SDK, signInWithPopup). */
    private val SIGN_IN_PAGE = """
<!doctype html><html><head><meta charset="utf-8"><title>VizMini2</title>
<style>body{font-family:system-ui,sans-serif;background:#111418;color:#e6e6e6;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
.card{background:#1d2228;padding:32px 40px;border-radius:20px;max-width:420px;text-align:center}button{font-size:16px;padding:12px 24px;border-radius:24px;border:0;background:#5cd3dd;color:#002;cursor:pointer}</style>
<script src="https://www.gstatic.com/firebasejs/10.14.1/firebase-app-compat.js"></script>
<script src="https://www.gstatic.com/firebasejs/10.14.1/firebase-auth-compat.js"></script>
</head><body><div class="card"><h2>VizMini2</h2><p id="msg">Sign in to sync your robot profiles with the phone.</p><button id="go">Continue</button></div>
<script>
firebase.initializeApp({apiKey:"@API_KEY@",authDomain:"@PROJECT@.firebaseapp.com",projectId:"@PROJECT@"});
const id="@PROVIDER@";
function provider(){ if(id==="google.com"){const p=new firebase.auth.GoogleAuthProvider();p.setCustomParameters({prompt:"select_account"});return p;}
 const p=new firebase.auth.OAuthProvider(id); if(id==="github.com")p.addScope("user:email"); if(id==="microsoft.com")p.setCustomParameters({prompt:"select_account"}); return p; }
function send(o){ return fetch("/done",{method:"POST",body:JSON.stringify(o)}); }
async function go(){
 document.getElementById("msg").textContent="…";
 try{ const r=await firebase.auth().signInWithPopup(provider()); const u=r.user;
  await send({uid:u.uid,email:u.email||"",refreshToken:u.refreshToken});
  document.getElementById("msg").textContent="Done. You can close this tab and go back to VizMini2."; document.getElementById("go").style.display="none";
 }catch(e){ document.getElementById("msg").textContent=e.message; send({error:e.code||e.message}); }
}
document.getElementById("go").onclick=go;
</script></body></html>
""".trimIndent()
}
