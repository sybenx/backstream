package app.backstream

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Turns a pasted link (zap.stream URL, naddr, nevent, npub, or a direct .m3u8) into a playable URL. */
object Nostr {
    data class Stream(
        val url: String,
        val title: String,
        val live: Boolean,
        /** "30311:<pubkey>:<d>" for a nostr live event; chat messages point at this. */
        val coord: String? = null,
        val relays: List<String> = emptyList(),
    )

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val ENTITY = Regex("(naddr1|nevent1|note1|npub1|nprofile1)[02-9ac-hj-np-z]+", RegexOption.IGNORE_CASE)
    private val COORD = Regex("^(\\d+):([0-9a-f]{64}):(.*)$")
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://relay.snort.social",
    )

    fun resolve(input: String, client: OkHttpClient): Stream {
        val text = input.trim()
        val entity = ENTITY.find(text)?.value
        if (text.startsWith("http") && (entity == null || text.contains(".m3u8"))) {
            val name = text.substringBefore('?').trimEnd('/').substringAfterLast('/')
            return Stream(text, name.ifEmpty { text }, true)
        }
        val coordMatch = COORD.matchEntire(text)
        if (entity == null && coordMatch == null) throw IllegalArgumentException("Paste a zap.stream link, naddr, npub or .m3u8 URL")

        val relays = LinkedHashSet<String>()
        val filters = ArrayList<JSONObject>()
        val (hrp, data) = if (coordMatch != null) "coord" to ByteArray(0) else bech32Decode(entity!!)
        when (hrp) {
            "coord" -> filters.add(
                JSONObject().put("kinds", JSONArray().put(coordMatch!!.groupValues[1].toInt()))
                    .put("authors", JSONArray().put(coordMatch.groupValues[2]))
                    .put("#d", JSONArray().put(coordMatch.groupValues[3]))
            )
            "naddr" -> {
                var d = ""
                var author = ""
                var kind = 30311
                for ((t, v) in tlv(data)) when (t) {
                    0 -> d = String(v, Charsets.UTF_8)
                    1 -> relays.add(String(v, Charsets.UTF_8))
                    2 -> author = hex(v)
                    3 -> kind = v.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
                }
                filters.add(
                    JSONObject().put("kinds", JSONArray().put(kind))
                        .put("authors", JSONArray().put(author))
                        .put("#d", JSONArray().put(d))
                )
            }
            "nevent" -> {
                var id = ""
                for ((t, v) in tlv(data)) when (t) {
                    0 -> id = hex(v)
                    1 -> relays.add(String(v, Charsets.UTF_8))
                }
                filters.add(JSONObject().put("ids", JSONArray().put(id)))
            }
            "note" -> filters.add(JSONObject().put("ids", JSONArray().put(hex(data))))
            "npub", "nprofile" -> {
                var pk = if (hrp == "npub") hex(data) else ""
                if (hrp == "nprofile") for ((t, v) in tlv(data)) when (t) {
                    0 -> pk = hex(v)
                    1 -> relays.add(String(v, Charsets.UTF_8))
                }
                // Streaming servers often sign the event themselves and tag the streamer with "p".
                filters.add(JSONObject().put("kinds", JSONArray().put(30311)).put("#p", JSONArray().put(pk)).put("limit", 20))
                filters.add(JSONObject().put("kinds", JSONArray().put(30311)).put("authors", JSONArray().put(pk)).put("limit", 20))
            }
            else -> throw IllegalArgumentException("Don't know how to play a $hrp link")
        }
        relays.addAll(DEFAULT_RELAYS)

        val events = fetch(client, relays.toList(), filters)
        if (events.isEmpty()) throw IllegalStateException("Couldn't find that stream on any relay")

        val best = events
            .mapNotNull { e -> mediaUrl(e)?.let { Triple(e, it.first, it.second) } }
            .sortedWith(compareByDescending<Triple<JSONObject, String, Boolean>> { it.third }.thenByDescending { it.first.optLong("created_at") })
            .firstOrNull() ?: throw IllegalStateException("That event has no playable stream in it")

        return streamOf(best.first, relays.toList())!!
    }

    /** Builds a playable stream from an event, or null when it has nothing to play. */
    fun streamOf(e: JSONObject, extraRelays: List<String> = DEFAULT_RELAYS): Stream? {
        val media = mediaUrl(e) ?: return null
        val title = (tag(e, "title") ?: tag(e, "summary")).takeUnless { it.isNullOrBlank() } ?: "Nostr stream"
        val coord = if (e.optInt("kind") == 30311) "30311:${e.optString("pubkey")}:${tag(e, "d") ?: ""}" else null
        val chatRelays = LinkedHashSet<String>()
        chatRelays.addAll(tagAll(e, "relays"))
        chatRelays.addAll(extraRelays)
        return Stream(media.first, title, media.second, coord, chatRelays.filter { it.startsWith("wss://") })
    }

    /** Returns (url, isLive) for an event, or null when it has nothing playable. */
    private fun mediaUrl(e: JSONObject): Pair<String, Boolean>? {
        val ended = tag(e, "status") == "ended"
        val streaming = tag(e, "streaming")
        val recording = tag(e, "recording")
        if (!ended && !streaming.isNullOrEmpty()) return streaming to true
        if (!recording.isNullOrEmpty()) return recording to false
        if (!streaming.isNullOrEmpty()) return streaming to false
        tag(e, "url")?.let { if (it.startsWith("http")) return it to false }
        // Video events keep their file in an "imeta" tag as "url https://..."
        val tags = e.optJSONArray("tags") ?: return null
        for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.optString(0) != "imeta") continue
            for (j in 1 until t.length()) {
                val part = t.optString(j)
                if (part.startsWith("url ")) return part.substring(4).trim() to false
            }
        }
        return null
    }

    fun tag(e: JSONObject, name: String): String? {
        val tags = e.optJSONArray("tags") ?: return null
        for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.optString(0) == name && t.length() > 1) return t.optString(1)
        }
        return null
    }

    fun tagAll(e: JSONObject, name: String): List<String> {
        val tags = e.optJSONArray("tags") ?: return emptyList()
        for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.optString(0) == name) return (1 until t.length()).map { t.optString(it) }
        }
        return emptyList()
    }

    /** patient = wait for every relay (for lists); otherwise return as soon as one good answer is in. */
    fun fetch(client: OkHttpClient, relays: List<String>, filters: List<JSONObject>, patient: Boolean = false): List<JSONObject> {
        val req = JSONArray().put("REQ").put("bs")
        filters.forEach { req.put(it) }
        val message = req.toString()
        val events = ArrayList<JSONObject>()
        val latch = CountDownLatch(relays.size)
        val sockets = ArrayList<WebSocket>()
        val answered = AtomicInteger(0)

        for (relay in relays) {
            try {
                val listener = object : WebSocketListener() {
                    private var done = false
                    private fun finish(ws: WebSocket) {
                        synchronized(this) {
                            if (done) return
                            done = true
                        }
                        latch.countDown()
                        ws.close(1000, null)
                    }

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(message)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val a = JSONArray(text)
                            when (a.optString(0)) {
                                "EVENT" -> synchronized(events) { events.add(a.getJSONObject(2)) }
                                "EOSE" -> {
                                    answered.incrementAndGet()
                                    finish(webSocket)
                                }
                                "CLOSED" -> finish(webSocket)
                            }
                        } catch (_: Exception) {
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(webSocket)
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(webSocket)
                }
                sockets.add(client.newWebSocket(Request.Builder().url(relay).build(), listener))
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        // Don't wait on slow relays: once one has fully answered with a result,
        // give the rest a moment (in case they hold a newer copy) and go.
        val deadline = System.currentTimeMillis() + 7000
        var firstHit = 0L
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            val now = System.currentTimeMillis()
            if (now >= deadline) break
            if (!patient && answered.get() > 0 && synchronized(events) { events.isNotEmpty() }) {
                if (firstHit == 0L) firstHit = now else if (now - firstHit >= 700) break
            }
        }
        sockets.forEach { it.cancel() }
        return synchronized(events) { ArrayList(events) }
    }

    /** Accepts an npub or plain hex and returns hex. */
    fun pubkeyHex(s: String): String = if (s.startsWith("npub1")) hex(bech32Decode(s).second) else s.trim().lowercase()

    private fun bech32Decode(s: String): Pair<String, ByteArray> {
        val str = s.lowercase()
        val pos = str.lastIndexOf('1')
        require(pos > 0 && str.length - pos > 6) { "That link looks cut off" }
        val out = ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (c in str.substring(pos + 1, str.length - 6)) {
            val v = CHARSET.indexOf(c)
            require(v >= 0) { "That link has a typo in it" }
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xff)
            }
            acc = acc and ((1 shl bits) - 1)
        }
        return str.substring(0, pos) to out.toByteArray()
    }

    private fun tlv(data: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xff
            val len = data[i + 1].toInt() and 0xff
            if (i + 2 + len > data.size) break
            out.add(type to data.copyOfRange(i + 2, i + 2 + len))
            i += 2 + len
        }
        return out
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
