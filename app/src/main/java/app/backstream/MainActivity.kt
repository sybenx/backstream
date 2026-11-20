package app.backstream

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
            waiting?.let {
                waiting = null
                play(it)
            }
        }, mainExecutor)
    }

    override fun onStop() {
        // Screen gone: keep the audio, drop to the lightest video so it sips data and battery.
        preferSmallVideo(true)
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

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
