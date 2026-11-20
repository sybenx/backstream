package app.backstream

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Read-only live chat for one stream: follows kind 1311 messages that point at the
 * stream, looks up display names, and hands the newest messages to the screen.
 * Everything below the socket callbacks runs on the main thread.
 */
class Chat(
    client: OkHttpClient,
    private val coord: String,
    relays: List<String>,
    private val onUpdate: (List<Msg>) -> Unit,
) {
    data class Msg(val id: String, val pubkey: String, val at: Long, val text: String, var name: String)

    private val client = client.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()
    private val relays = relays.distinct().take(6)
    private val main = Handler(Looper.getMainLooper())
    private val sockets = HashMap<String, WebSocket>()
    private val msgs = HashMap<String, Msg>()
    private val names = HashMap<String, Pair<Long, String>>()
    private val asked = HashSet<String>()
    private val wanted = ArrayList<String>()
    private var profileSubs = 0
    private var active = false
    private var publishQueued = false
    private var profilesQueued = false

    fun start() {
        active = true
        relays.forEach(::connect)
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        sockets.values.forEach { it.cancel() }
        sockets.clear()
    }

    private fun connect(relay: String) {
        if (!active) return
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val filter = JSONObject().put("kinds", JSONArray().put(1311)).put("#a", JSONArray().put(coord)).put("limit", 100)
                webSocket.send(JSONArray().put("REQ").put("chat").put(filter).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val a = try {
                    JSONArray(text)
                } catch (_: Exception) {
                    return
                }
                main.post { handle(webSocket, a) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

            private fun lost(ws: WebSocket) {
                main.post {
                    if (active && sockets[relay] === ws) {
                        sockets.remove(relay)
                        main.postDelayed({ connect(relay) }, 5000)
                    }
                }
            }
        }
        try {
            sockets[relay] = client.newWebSocket(Request.Builder().url(relay).build(), listener)
        } catch (_: Exception) {
        }
    }

    private fun handle(ws: WebSocket, a: JSONArray) {
        if (!active) return
        when (a.optString(0)) {
            "EOSE" -> if (a.optString(1).startsWith("p")) ws.send(JSONArray().put("CLOSE").put(a.optString(1)).toString())
            "EVENT" -> {
                val e = a.optJSONObject(2) ?: return
                val pk = e.optString("pubkey")
                when (e.optInt("kind")) {
                    1311 -> {
                        val id = e.optString("id")
                        if (id.isEmpty() || msgs.containsKey(id)) return
                        msgs[id] = Msg(id, pk, e.optLong("created_at"), e.optString("content").trim(), names[pk]?.second ?: pk.take(8))
                        if (asked.add(pk)) {
                            wanted.add(pk)
                            if (!profilesQueued) {
                                profilesQueued = true
                                main.postDelayed(::askProfiles, 300)
                            }
                        }
                        publish()
                    }
                    0 -> {
                        val at = e.optLong("created_at")
                        if ((names[pk]?.first ?: -1L) >= at) return
                        val profile = try {
                            JSONObject(e.optString("content"))
                        } catch (_: Exception) {
                            return
                        }
                        val name = profile.optString("display_name").ifBlank { profile.optString("name") }.trim()
                        if (name.isEmpty()) return
                        names[pk] = at to name
                        msgs.values.forEach { if (it.pubkey == pk) it.name = name }
                        publish()
                    }
                }
            }
        }
    }

    private fun askProfiles() {
        profilesQueued = false
        if (wanted.isEmpty()) return
        val authors = JSONArray()
        wanted.forEach { authors.put(it) }
        wanted.clear()
        val req = JSONArray().put("REQ").put("p${profileSubs++}")
            .put(JSONObject().put("kinds", JSONArray().put(0)).put("authors", authors)).toString()
        sockets.values.forEach { it.send(req) }
    }

    private fun publish() {
        if (publishQueued) return
        publishQueued = true
        main.postDelayed({
            publishQueued = false
            onUpdate(msgs.values.filter { it.text.isNotEmpty() }.sortedBy { it.at }.takeLast(200).map { it.copy() })
        }, 150)
    }
}
