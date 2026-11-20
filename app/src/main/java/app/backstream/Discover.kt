package app.backstream

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds who is live right now and sorts them by how close they are to you:
 * people you follow, then people your follows follow, then everyone else.
 * Live streams on nostr are rare enough that it simply fetches all of them.
 */
object Discover {
    const val FOLLOW = 0
    const val FRIEND_OF_FRIEND = 1
    const val OTHER = 2

    data class Item(
        /** "30311:<pubkey>:<d>", which Nostr.resolve can look up again later. */
        val key: String,
        val stream: Nostr.Stream,
        val hostName: String,
        val tier: Int,
        /** "followed by X and 3 others" for friend-of-friend streams, else empty. */
        val via: String,
        val viaCount: Int,
        val live: Boolean,
        val at: Long,
    )

    private val INDEXERS = Nostr.DEFAULT_RELAYS + "wss://purplepag.es"

    private class Candidate(val key: String, val e: JSONObject, val host: String, val stream: Nostr.Stream, val at: Long)

    fun load(client: OkHttpClient, me: String?): List<Item> {
        val now = System.currentTimeMillis() / 1000

        val filter = JSONObject().put("kinds", JSONArray().put(30311)).put("since", now - 3 * 86400).put("limit", 500)
        val raw = Nostr.fetch(client, Nostr.DEFAULT_RELAYS, listOf(filter), patient = true)
        val newest = newestBy(raw) { "30311:${it.optString("pubkey")}:${Nostr.tag(it, "d") ?: ""}" }

        val follows = if (me != null) contacts(client, me) else emptySet()
        fun mine(pk: String) = pk in follows || pk == me

        val candidates = newest.mapNotNull { (key, e) ->
            val stream = Nostr.streamOf(e) ?: return@mapNotNull null
            Candidate(key, e, host(e), stream, e.optLong("created_at"))
        }

        // A "live" event that hasn't been touched in a while is usually a stream that died
        // without saying so. Be strict with strangers (1 hour) and patient with your own
        // follows (a day), since missing one of theirs is the worse mistake.
        val live = candidates
            .filter { it.stream.live && Nostr.tag(it.e, "status") == "live" && now - it.at < (if (mine(it.host)) 86400 else 3600) }
            .groupBy { it.host }
            .map { (_, sameHost) -> sameHost.maxByOrNull { it.at }!! }

        val ended = candidates.filter {
            mine(it.host) && Nostr.tag(it.e, "status") == "ended" && !Nostr.tag(it.e, "recording").isNullOrEmpty()
        }

        // Friend-of-friend: ask "which of my follows follow this host?" for the few hosts
        // that are live, instead of downloading everyone's follow list.
        val strangers = live.map { it.host }.filter { !mine(it) }.distinct().take(100)
        val via = HashMap<String, MutableSet<String>>()
        if (strangers.isNotEmpty() && follows.isNotEmpty()) {
            val strangerSet = strangers.toSet()
            val filters = follows.take(2000).chunked(200).map { chunk ->
                JSONObject().put("kinds", JSONArray().put(3)).put("authors", JSONArray(chunk)).put("#p", JSONArray(strangers))
            }
            val lists = newestBy(Nostr.fetch(client, INDEXERS, filters, patient = true)) { it.optString("pubkey") }
            for ((follower, list) in lists) {
                for (p in pTags(list)) if (p in strangerSet) via.getOrPut(p) { HashSet() }.add(follower)
            }
        }

        val wanted = LinkedHashSet<String>()
        (live + ended).forEach { wanted.add(it.host) }
        via.values.forEach { wanted.addAll(it.sorted().take(2)) }
        val names = names(client, wanted.take(300))
        fun name(pk: String) = names[pk] ?: pk.take(8)

        val items = live.map { c ->
            val connectors = via[c.host]?.sorted().orEmpty()
            val tier = when {
                mine(c.host) -> FOLLOW
                connectors.isNotEmpty() -> FRIEND_OF_FRIEND
                else -> OTHER
            }
            val viaText = when {
                tier != FRIEND_OF_FRIEND -> ""
                connectors.size == 1 -> "followed by ${name(connectors[0])}"
                connectors.size == 2 -> "followed by ${name(connectors[0])} and ${name(connectors[1])}"
                else -> "followed by ${name(connectors[0])} and ${connectors.size - 1} others"
            }
            Item(c.key, c.stream, name(c.host), tier, viaText, connectors.size, true, c.at)
        } + ended.map { c -> Item(c.key, c.stream, name(c.host), FOLLOW, "", 0, false, c.at) }

        return items.sortedWith(
            compareBy<Item>({ !it.live }, { it.tier }).thenByDescending { it.viaCount }.thenByDescending { it.at }
        )
    }

    /** The streamer. Streaming services often sign the event themselves and tag the real host. */
    private fun host(e: JSONObject): String {
        val tags = e.optJSONArray("tags")
        var first: String? = null
        if (tags != null) for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.optString(0) != "p" || t.optString(1).length != 64) continue
            if (t.optString(3).equals("host", ignoreCase = true)) return t.optString(1)
            if (first == null) first = t.optString(1)
        }
        return first ?: e.optString("pubkey")
    }

    private fun contacts(client: OkHttpClient, me: String): Set<String> {
        val filter = JSONObject().put("kinds", JSONArray().put(3)).put("authors", JSONArray().put(me)).put("limit", 3)
        val list = Nostr.fetch(client, INDEXERS, listOf(filter)).maxByOrNull { it.optLong("created_at") } ?: return emptySet()
        return pTags(list).toSet()
    }

    private fun names(client: OkHttpClient, pubkeys: List<String>): Map<String, String> {
        if (pubkeys.isEmpty()) return emptyMap()
        val filter = JSONObject().put("kinds", JSONArray().put(0)).put("authors", JSONArray(pubkeys))
        val out = HashMap<String, String>()
        for ((pk, e) in newestBy(Nostr.fetch(client, INDEXERS, listOf(filter), patient = true)) { it.optString("pubkey") }) {
            val profile = try {
                JSONObject(e.optString("content"))
            } catch (_: Exception) {
                continue
            }
            val name = profile.optString("display_name").ifBlank { profile.optString("name") }.trim()
            if (name.isNotEmpty()) out[pk] = name
        }
        return out
    }

    private fun pTags(e: JSONObject): List<String> {
        val tags = e.optJSONArray("tags") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.optString(0) == "p" && t.optString(1).length == 64) out.add(t.optString(1))
        }
        return out
    }

    private fun newestBy(events: List<JSONObject>, key: (JSONObject) -> String): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        for (e in events) {
            val k = key(e)
            val old = out[k]
            if (old == null || old.optLong("created_at") < e.optLong("created_at")) out[k] = e
        }
        return out
    }
}
