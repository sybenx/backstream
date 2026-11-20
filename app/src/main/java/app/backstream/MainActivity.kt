package app.backstream

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var panel: View
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var recents: ListView
    private lateinit var chatList: ListView
    private lateinit var tabLive: TextView
    private lateinit var tabRecent: TextView
    private lateinit var tabChat: TextView
    private lateinit var liveList: ListView
    private lateinit var liveAdapter: ArrayAdapter<CharSequence>
    private var liveTaps = listOf<(() -> Unit)?>()
    private var liveItems: List<Discover.Item>? = null
    private var liveFailed = false
    private var loadingLive = false
    private var showOthers = false
    private var tab = TAB_LIVE

    // "Notify me when they go live" for the streamer you're watching.
    private lateinit var star: TextView
    private var currentHost: String? = null
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var chatAdapter: ArrayAdapter<CharSequence>
    private var chat: Chat? = null
    private var showingChat = false

    // Signing in: a signer app on the phone (Keykin, Amber) holds the key; Backstream only knows who you are.
    private lateinit var composer: View
    private lateinit var signIn: Button
    private lateinit var message: EditText
    private lateinit var send: Button
    private var myPubkey: String? = null
    private var signerPkg: String? = null
    private var chatCoord: String? = null
    private var chatRelays = listOf<String>()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var waiting: Nostr.Stream? = null
    private var recentInputs = listOf<String>()

    private val http = OkHttpClient()
    private val io = Executors.newSingleThreadExecutor()

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            status.text = "Reconnecting…"
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) status.text = "Buffering…"
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) controller?.mediaMetadata?.title?.let { status.text = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        panel = findViewById(R.id.panel)
        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        recents = findViewById(R.id.recents)
        chatList = findViewById(R.id.chat)
        tabLive = findViewById(R.id.tabLive)
        tabRecent = findViewById(R.id.tabRecent)
        tabChat = findViewById(R.id.tabChat)
        liveList = findViewById(R.id.live)
        liveAdapter = ArrayAdapter(this, R.layout.chat_row, ArrayList<CharSequence>())
        liveList.adapter = liveAdapter
        liveList.setOnItemClickListener { _, _, position, _ -> liveTaps.getOrNull(position)?.invoke() }
        tabLive.setOnClickListener { if (tab == TAB_LIVE) refreshLive() else selectTab(TAB_LIVE) }
        tabRecent.setOnClickListener { selectTab(TAB_RECENT) }
        tabChat.setOnClickListener { selectTab(TAB_CHAT) }
        star = findViewById(R.id.star)
        star.setOnClickListener {
            val host = currentHost ?: return@setOnClickListener
            LiveWatch.toggle(this, host)
            showStar()
            renderLive()
        }
        LiveWatch.schedule(this)
        chatAdapter = ArrayAdapter(this, R.layout.chat_row, ArrayList<CharSequence>())
        chatList.adapter = chatAdapter

        composer = findViewById(R.id.composer)
        signIn = findViewById(R.id.signIn)
        message = findViewById(R.id.message)
        send = findViewById(R.id.send)
        myPubkey = getPreferences(MODE_PRIVATE).getString("pubkey", null)
        signerPkg = getPreferences(MODE_PRIVATE).getString("signer", null)
        signIn.setOnClickListener { signIn() }
        send.setOnClickListener { sendMessage() }
        message.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
        send.setOnLongClickListener {
            myPubkey = null
            signerPkg = null
            getPreferences(MODE_PRIVATE).edit().remove("pubkey").remove("signer").apply()
            status.text = "Signed out"
            showComposer()
            liveItems = null
            refreshLive()
            true
        }
        showComposer()

        findViewById<Button>(R.id.play).setOnClickListener { start(input.text.toString()) }
        input.setOnEditorActionListener { _, _, _ -> start(input.text.toString()); true }
        recents.setOnItemClickListener { _, _, position, _ ->
            recentInputs.getOrNull(position)?.let {
                input.setText(it)
                start(it)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        showRecents()
        applyLayout()
        selectTab(TAB_LIVE)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Opened from a "went live" notification.
        intent?.getStringExtra("play")?.let {
            intent.removeExtra("play")
            start(it)
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        // Shared text is sometimes "Title https://link"; keep just the link if there is one.
        val link = text.split(Regex("\\s+")).firstOrNull { it.startsWith("http") || it.startsWith("n") && it.contains("1") } ?: text
        input.setText(link)
        start(link)
    }

    override fun onStart() {
        super.onStart()
        refreshLive()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try {
                f.get()
            } catch (_: Exception) {
                null
            } ?: return@addListener
            controller = c
            c.addListener(listener)
            playerView.player = c
            preferSmallVideo(false)
            c.mediaMetadata.title?.let { if (c.mediaItemCount > 0) status.text = it }
            // Coming back to a stream that kept playing: pick its chat back up.
            if (c.mediaItemCount > 0 && chat == null && waiting == null) {
                val prefs = getPreferences(MODE_PRIVATE)
                currentHost = prefs.getString("host", null)
                showStar()
                startChat(prefs.getString("chatCoord", null), prefs.getString("chatRelays", "")!!.split(" ").filter { it.isNotEmpty() })
            }
            waiting?.let {
                waiting = null
                play(it)
            }
        }, mainExecutor)
    }

    override fun onStop() {
        // Screen gone: keep the audio, drop to the lightest video so it sips data and battery.
        preferSmallVideo(true)
        chat?.stop()
        chat = null
        playerView.player = null
        controller?.removeListener(listener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    private fun preferSmallVideo(small: Boolean) {
        val c = controller ?: return
        try {
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setForceLowestBitrate(small).build()
        } catch (_: Exception) {
        }
    }

    private fun start(text: String) {
        if (text.isBlank()) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        status.text = "Finding stream…"
        io.execute {
            try {
                val stream = Nostr.resolve(text, http)
                runOnUiThread {
                    remember(text.trim(), stream.title)
                    play(stream)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = e.message ?: "Couldn't find that stream" }
            }
        }
    }

    private fun play(stream: Nostr.Stream) {
        val c = controller
        if (c == null) {
            waiting = stream
            return
        }
        val uri = Uri.parse(stream.url)
        val item = MediaItem.Builder()
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(stream.title).setArtist(if (stream.live) "Live" else "Recording").build())
        if (stream.url.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        c.setMediaItem(item.build())
        c.prepare()
        c.play()
        status.text = if (stream.live) stream.title else "${stream.title} (recording)"
        getPreferences(MODE_PRIVATE).edit()
            .putString("chatCoord", stream.coord)
            .putString("host", stream.host)
            .putString("chatRelays", stream.relays.joinToString(" "))
            .apply()
        currentHost = stream.host
        showStar()
        startChat(stream.coord, stream.relays)
    }

    private fun showStar() {
        val host = currentHost
        star.visibility = if (host == null) View.GONE else View.VISIBLE
        if (host != null) star.text = if (host in LiveWatch.stars(this)) "★ Notifying" else "☆ Notify me"
    }

    private fun whenText(starts: Long): String {
        val ms = starts * 1000
        val day = if (DateUtils.isToday(ms)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
        val time = DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_TIME or day)
        return if (ms < System.currentTimeMillis()) "was due $time" else "starts $time"
    }

    // Live chat (read-only): shown under the video in place of the recents list.
    private fun startChat(coord: String?, relays: List<String>) {
        chat?.stop()
        chat = null
        chatAdapter.clear()
        chatCoord = coord
        chatRelays = relays
        if (coord == null || relays.isEmpty()) {
            showList(false)
            return
        }
        chat = Chat(http, coord, relays) { messages ->
            chatAdapter.setNotifyOnChange(false)
            chatAdapter.clear()
            for (m in messages) {
                val row = SpannableString("${m.name}  ${m.text}")
                row.setSpan(StyleSpan(Typeface.BOLD), 0, m.name.length, 0)
                row.setSpan(ForegroundColorSpan(0xFFF7931A.toInt()), 0, m.name.length, 0)
                chatAdapter.add(row)
            }
            chatAdapter.notifyDataSetChanged()
        }.also { it.start() }
        showList(true)
    }

    private fun showList(showChat: Boolean) =
        selectTab(if (showChat && chat != null) TAB_CHAT else if (tab == TAB_CHAT) TAB_LIVE else tab)

    private fun selectTab(wanted: Int) {
        tab = if (wanted == TAB_CHAT && chat == null) TAB_LIVE else wanted
        showingChat = tab == TAB_CHAT
        liveList.visibility = if (tab == TAB_LIVE) View.VISIBLE else View.GONE
        recents.visibility = if (tab == TAB_RECENT) View.VISIBLE else View.GONE
        chatList.visibility = if (tab == TAB_CHAT) View.VISIBLE else View.GONE
        composer.visibility = if (tab == TAB_CHAT) View.VISIBLE else View.GONE
        tabLive.setTextColor(if (tab == TAB_LIVE) ACCENT else GREY)
        tabRecent.setTextColor(if (tab == TAB_RECENT) ACCENT else GREY)
        tabChat.setTextColor(if (tab == TAB_CHAT) ACCENT else GREY)
        tabChat.alpha = if (chat == null) 0.4f else 1f
    }

    // Live now: everyone streaming, sorted by how close they are to you.
    private fun refreshLive() {
        if (loadingLive) return
        loadingLive = true
        if (liveItems == null) renderLive()
        val me = myPubkey
        val stars = LiveWatch.stars(this)
        bg.execute {
            val found = try {
                Discover.load(http, me, stars)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                loadingLive = false
                if (found != null) liveItems = found
                liveFailed = found == null
                renderLive()
            }
        }
    }

    private fun renderLive() {
        val rows = ArrayList<CharSequence>()
        val taps = ArrayList<(() -> Unit)?>()
        fun note(text: String, tap: (() -> Unit)? = null) {
            val row = SpannableString(text)
            row.setSpan(ForegroundColorSpan(if (tap != null) ACCENT else GREY), 0, text.length, 0)
            row.setSpan(RelativeSizeSpan(0.87f), 0, text.length, 0)
            rows.add(row)
            taps.add(tap)
        }
        val stars = LiveWatch.stars(this)
        fun stream(item: Discover.Item, extra: String = "") {
            val sub = listOf(
                (if (item.host in stars) "★ " else "") + item.hostName,
                item.via,
                if (item.standby) "not marked live, but streaming" else "",
                extra,
            ).filter { it.isNotEmpty() }.joinToString(" · ")
            val row = SpannableString("${item.stream.title}\n$sub")
            val split = item.stream.title.length
            row.setSpan(StyleSpan(Typeface.BOLD), 0, split, 0)
            row.setSpan(ForegroundColorSpan(GREY), split, row.length, 0)
            row.setSpan(RelativeSizeSpan(0.87f), split, row.length, 0)
            rows.add(row)
            taps.add {
                if (item.stream.url.isEmpty()) {
                    status.text = "That one hasn't started yet"
                } else {
                    remember(item.key, item.stream.title)
                    play(item.stream)
                }
            }
        }
        fun comingUp(items: List<Discover.Item>) {
            val soon = items.filter { it.starts > 0 }.sortedBy { it.starts }
            if (soon.isEmpty()) return
            note("Coming up")
            soon.forEach { stream(it, whenText(it.starts)) }
        }

        val items = liveItems
        if (myPubkey == null) note("Sign in to put your follows first", ::signIn)
        when {
            items == null && liveFailed -> note("Couldn't check for live streams. Tap Live to retry.")
            items == null -> note("Looking for live streams…")
            else -> {
                val live = items.filter { it.live }
                if (myPubkey == null) {
                    if (live.isEmpty()) note("Nobody is live right now") else note("Live now")
                    live.forEach { stream(it) }
                    comingUp(items)
                } else {
                    val follows = live.filter { it.tier == Discover.FOLLOW }
                    val friends = live.filter { it.tier == Discover.FRIEND_OF_FRIEND }
                    val others = live.filter { it.tier == Discover.OTHER }
                    note(if (follows.isEmpty()) "None of your follows are live" else "Your follows")
                    follows.forEach { stream(it) }
                    if (friends.isNotEmpty()) {
                        note("Followed by people you follow")
                        friends.forEach { stream(it) }
                    }
                    if (others.isNotEmpty()) {
                        note(if (showOthers) "Everyone else · tap to hide" else "Everyone else (${others.size}) · tap to show") {
                            showOthers = !showOthers
                            renderLive()
                        }
                        if (showOthers) others.forEach { stream(it) }
                    }
                    comingUp(items)
                    val ended = items.filter { !it.live && it.starts == 0L }
                    if (ended.isNotEmpty()) {
                        note("Recently ended, with a recording")
                        ended.forEach { stream(it) }
                    }
                }
            }
        }
        liveTaps = taps
        liveAdapter.setNotifyOnChange(false)
        liveAdapter.clear()
        liveAdapter.addAll(rows)
        liveAdapter.notifyDataSetChanged()
    }

    // Recents: newest first, up to 10, remembered on this phone only.
    private fun loadRecents(): JSONArray = try {
        JSONArray(getPreferences(MODE_PRIVATE).getString("recents", "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun remember(text: String, title: String) {
        val old = loadRecents()
        val next = JSONArray().put(JSONObject().put("i", text).put("t", title))
        for (n in 0 until old.length()) {
            val o = old.optJSONObject(n) ?: continue
            if (o.optString("i") != text && next.length() < 10) next.put(o)
        }
        getPreferences(MODE_PRIVATE).edit().putString("recents", next.toString()).apply()
        showRecents()
    }

    private fun showRecents() {
        val all = loadRecents()
        val titles = ArrayList<String>()
        val inputs = ArrayList<String>()
        for (n in 0 until all.length()) {
            val o = all.optJSONObject(n) ?: continue
            titles.add(o.optString("t"))
            inputs.add(o.optString("i"))
        }
        recentInputs = inputs
        recents.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
    }

    private fun showComposer() {
        val signedIn = myPubkey != null
        signIn.visibility = if (signedIn) View.GONE else View.VISIBLE
        message.visibility = if (signedIn) View.VISIBLE else View.GONE
        send.visibility = if (signedIn) View.VISIBLE else View.GONE
    }

    private fun signIn() {
        val ask = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).putExtra("type", "get_public_key")
        try {
            startActivityForResult(ask, REQ_SIGN_IN)
        } catch (_: ActivityNotFoundException) {
            status.text = "No signer app found. Install Keykin or Amber first."
        }
    }

    private fun sendMessage() {
        val text = message.text.toString().trim()
        val pk = myPubkey ?: return
        val coord = chatCoord ?: return
        if (text.isEmpty()) return
        val event = JSONObject()
            .put("kind", 1311)
            .put("created_at", System.currentTimeMillis() / 1000)
            .put("pubkey", pk)
            .put("content", text)
            .put("tags", JSONArray().put(JSONArray().put("a").put(coord).put(chatRelays.firstOrNull() ?: "").put("root")))
            .toString()
        send.isEnabled = false
        io.execute {
            // Quiet path first: works once you've told the signer to always allow Backstream.
            val signed = signQuietly(event, pk)
            runOnUiThread {
                if (signed != null) {
                    posted(signed)
                } else {
                    val ask = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$event"))
                        .putExtra("type", "sign_event")
                        .putExtra("id", "bs${System.currentTimeMillis()}")
                        .putExtra("current_user", pk)
                    signerPkg?.let { ask.setPackage(it) }
                    try {
                        startActivityForResult(ask, REQ_SIGN)
                    } catch (_: ActivityNotFoundException) {
                        send.isEnabled = true
                        status.text = "Signer app not found. Sign in again."
                    }
                }
            }
        }
    }

    private fun signQuietly(event: String, pk: String): String? {
        val pkg = signerPkg ?: return null
        try {
            contentResolver.query(Uri.parse("content://$pkg.SIGN_EVENT"), arrayOf(event, "", pk), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.getColumnIndex("rejected") < 0) {
                    val i = c.getColumnIndex("event")
                    if (i >= 0) return c.getString(i)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun posted(signed: String?) {
        send.isEnabled = true
        val event = try {
            JSONObject(signed ?: "")
        } catch (_: Exception) {
            null
        }
        if (event == null || event.optString("sig").isEmpty()) {
            status.text = "The signer didn't return a signed message"
            return
        }
        val c = chat
        if (c == null) {
            status.text = "Chat isn't connected"
            return
        }
        c.send(event)
        message.setText("")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_SIGN_IN -> {
                val who = data?.getStringExtra("result") ?: data?.getStringExtra("signature")
                if (resultCode != RESULT_OK || who.isNullOrBlank()) {
                    status.text = "Sign-in cancelled"
                    return
                }
                val pk = try {
                    Nostr.pubkeyHex(who)
                } catch (_: Exception) {
                    status.text = "The signer sent back something unexpected"
                    return
                }
                myPubkey = pk
                signerPkg = data?.getStringExtra("package")
                getPreferences(MODE_PRIVATE).edit().putString("pubkey", pk).putString("signer", signerPkg).apply()
                showComposer()
                liveItems = null
                refreshLive()
            }
            REQ_SIGN -> {
                if (resultCode != RESULT_OK) {
                    send.isEnabled = true
                    status.text = "Not sent"
                    return
                }
                posted(data?.getStringExtra("event"))
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    // Sideways = video fills the screen. Upright = video on top, controls below.
    private fun applyLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        panel.visibility = if (land) View.GONE else View.VISIBLE
        playerView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (land) ViewGroup.LayoutParams.MATCH_PARENT else resources.displayMetrics.widthPixels * 9 / 16,
        )
    }

    private companion object {
        const val REQ_SIGN_IN = 10
        const val REQ_SIGN = 11
        const val TAB_LIVE = 0
        const val TAB_RECENT = 1
        const val TAB_CHAT = 2
        const val ACCENT = 0xFFF7931A.toInt()
        const val GREY = 0xFFAAAAAA.toInt()
    }

    override fun onDestroy() {
        io.shutdown()
        bg.shutdown()
        super.onDestroy()
    }
}
