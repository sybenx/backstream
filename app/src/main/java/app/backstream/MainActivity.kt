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
import android.text.style.ForegroundColorSpan
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
    private lateinit var listLabel: TextView
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
        listLabel = findViewById(R.id.listLabel)
        chatAdapter = ArrayAdapter(this, R.layout.chat_row, ArrayList<CharSequence>())
        chatList.adapter = chatAdapter
        listLabel.setOnClickListener { if (chat != null) showList(!showingChat) }

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
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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
            .putString("chatRelays", stream.relays.joinToString(" "))
            .apply()
        startChat(stream.coord, stream.relays)
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

    private fun showList(showChat: Boolean) {
        showingChat = showChat && chat != null
        chatList.visibility = if (showingChat) View.VISIBLE else View.GONE
        recents.visibility = if (showingChat) View.GONE else View.VISIBLE
        composer.visibility = if (showingChat) View.VISIBLE else View.GONE
        listLabel.text = when {
            showingChat -> "Chat · tap for recent"
            chat != null -> "Recent · tap for chat"
            else -> "Recent"
        }
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
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
