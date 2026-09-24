package com.webviewdp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

/**
 * The harness, on the phone.
 *
 * The WebView never loads the harness directly. It loads the loopback origin
 * that [LoopbackProxy] serves, and the proxy relays to the real harness over
 * Tailscale with the session this app holds. That is what makes the harness
 * treat this page as the operator: a page the harness served itself over the
 * tailnet is not loopback and gets no durable settings, while a page from
 * 127.0.0.1 is, and does. Everything else here is the wrapper - setup screen,
 * file chooser, renderer recovery, back handling.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var setupView: View
    private lateinit var urlInput: EditText
    private lateinit var session: UpstreamSession
    private var proxy: LoopbackProxy? = null
    private var localUrl: String? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingFileChooser = false
    private var lastMainUrl: String? = null
    private var rendererGoneCount = 0
    private var lastRendererGoneAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        session = UpstreamSession(this)
        webView = findViewById(R.id.webview)
        setupView = findViewById(R.id.setup)
        urlInput = findViewById(R.id.url_input)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                view.loadUrl(request.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                lastMainUrl = url
                Log.d(TAG, "page started: $url")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "main frame error: ${error.errorCode} ${error.description} for ${request.url}")
                    showSetup()
                    Toast.makeText(this@MainActivity, R.string.load_error, Toast.LENGTH_LONG).show()
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame && errorResponse.statusCode == 401) {
                    // The proxy reports the same refusal where it belongs, once:
                    // it asks for a fresh launch URL instead of reloading a page
                    // that cannot sign in.
                    Log.d(TAG, "main frame 401 for ${request.url}")
                }
            }

            /**
             * Android may kill the WebView's renderer process while the app
             * sits in the background (memory pressure; heavier pages make this
             * far more likely). The default behaviour then removes the WebView
             * from the view tree, leaving a permanently blank screen. Take
             * over: log the cause, and reload the last page so a fresh
             * renderer is spawned. Give up to the setup screen after repeated
             * rapid restarts instead of looping.
             */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer gone: didCrash=${detail.didCrash()}")
                val now = SystemClock.elapsedRealtime()
                rendererGoneCount =
                    if (now - lastRendererGoneAt > RENDERER_RESTART_WINDOW_MS) 1 else rendererGoneCount + 1
                lastRendererGoneAt = now
                if (rendererGoneCount > MAX_RENDERER_RESTARTS) {
                    Log.e(TAG, "renderer keeps dying ($rendererGoneCount restarts); returning to setup")
                    runOnUiThread {
                        showSetup()
                        Toast.makeText(this@MainActivity, R.string.renderer_error, Toast.LENGTH_LONG).show()
                    }
                    return true
                }
                runOnUiThread {
                    val target = lastMainUrl ?: localUrl
                    if (target != null) {
                        Log.i(TAG, "renderer gone: reloading $target")
                        view.loadUrl(target)
                    } else {
                        Log.w(TAG, "renderer gone: nothing to reload; returning to setup")
                        showSetup()
                    }
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                this@MainActivity.fileChooserParams = fileChooserParams
                if (hasMediaPermission()) {
                    launchFileChooser()
                } else {
                    pendingFileChooser = true
                    requestMediaPermission()
                }
                return true
            }
        }

        findViewById<Button>(R.id.connect_button).setOnClickListener { connect() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connect()
                true
            } else {
                false
            }
        }

        val savedUrl = session.storedUrl()
        if (savedUrl != null) {
            urlInput.setText(savedUrl)
            if (!session.hasSession()) adoptWebViewCookie(savedUrl)
            if (session.hasSession()) launch() else showSetup()
        } else {
            showSetup()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        // Renderer death while backgrounded can otherwise leave a blank view;
        // onRenderProcessGone reloads it, but cover the no-URL case too.
        if (webView.visibility == View.VISIBLE && webView.url.isNullOrBlank()) {
            val target = lastMainUrl ?: localUrl
            if (target != null) {
                Log.i(TAG, "resumed with no URL; reloading $target")
                rendererGoneCount = 0
                open(target)
            } else {
                showSetup()
            }
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    private fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasMediaPermission(): Boolean =
        checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestMediaPermission() {
        requestPermissions(arrayOf(mediaPermission()), MEDIA_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MEDIA_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.permission_photos, Toast.LENGTH_LONG).show()
        }
        if (pendingFileChooser) {
            pendingFileChooser = false
            launchFileChooser()
        }
    }

    private fun launchFileChooser() {
        val params = fileChooserParams ?: return
        val intent = params.createIntent()
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        // MODE_OPEN_MULTIPLE_WITH_PREVIEW (API 29) is not on every
        // compile target; >= MODE_OPEN_MULTIPLE covers both multiple modes.
        if (params.mode >= WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.data?.let { intent.clipData = ClipData.newRawUri("images", it) }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, FILE_CHOOSER_REQUEST)
        } catch (_: Exception) {
            filePathCallback = null
            fileChooserParams = null
        }
    }

    /**
     * Sign in with what was pasted, then show the harness. A launch URL carries
     * a one-time token; a bare origin is accepted while the stored session still
     * covers it. Both happen off the main thread - the harness is remote.
     */
    private fun connect() {
        val url = normalizeUrl(urlInput.text.toString())
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.url_empty, Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        Thread {
            val result = session.signIn(url)
            runOnUiThread {
                setBusy(false)
                result.fold(
                    onSuccess = { launch() },
                    onFailure = { failure ->
                        Log.w(TAG, "sign-in failed: ${failure.message}")
                        showSetup()
                        Toast.makeText(this, R.string.sign_in_failed, Toast.LENGTH_LONG).show()
                    },
                )
            }
        }.start()
    }

    /** Start the loopback origin and point the WebView at it. */
    private fun launch() {
        val upstream = session.upstream
        if (upstream == null) {
            showSetup()
            return
        }
        val listener = proxy ?: LoopbackProxy(
            upstream = upstream,
            client = session.proxyClient,
            cookieHeader = { session.cookieHeader(it) },
            onAuthFailure = { runOnUiThread { onSessionLost() } },
        ).also { proxy = it }
        val port = if (listener.port == 0) listener.start() else listener.port
        open("http://127.0.0.1:$port/")
    }

    private fun setBusy(busy: Boolean) {
        val button = findViewById<Button>(R.id.connect_button)
        button.isEnabled = !busy
        button.text = getString(if (busy) R.string.signing_in else R.string.connect)
    }

    private fun open(url: String) {
        localUrl = url
        setupView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        rendererGoneCount = 0
        webView.loadUrl(url)
    }

    /**
     * Take over the cookie an older install left in the WebView's jar, so an
     * update does not force a fresh launch URL on the user.
     */
    private fun adoptWebViewCookie(url: String) {
        val upstream = session.upstream ?: return
        try {
            session.adopt(CookieManager.getInstance().getCookie(url), upstream)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the WebView cookie jar: ${e.message}")
        }
    }

    /**
     * The harness refused the session this app holds, so nothing on the server
     * can hand it a new one: the user pastes a fresh launch URL.
     *
     * It used to read that URL from `auth.json` in the served dist. Every static
     * asset is public, so the file signed in anyone who could reach the port;
     * the launcher no longer writes it, and this app no longer reads it.
     */
    private fun onSessionLost() {
        Log.w(TAG, "the harness refused the stored session")
        showSetup()
        Toast.makeText(this, R.string.auth_error, Toast.LENGTH_LONG).show()
    }

    private fun showSetup() {
        webView.visibility = View.GONE
        setupView.visibility = View.VISIBLE
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val files = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            val delivered = files?.mapNotNull { copyToCache(it) }?.toTypedArray()?.takeIf { it.isNotEmpty() }
            filePathCallback?.onReceiveValue(delivered)
            filePathCallback = null
            fileChooserParams = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }


    /** Copy a picked content URI into our own cache and return a FileProvider
     * URI the WebView can always read - sidesteps every provider grant quirk
     * on modern Android. Returns null when the source cannot be read. */
    /** Pick an extension + copy strategy for the received MIME: standard
     * formats are copied byte-for-byte with their real extension (so the page
     * sees image/png etc.), anything else (HEIC, empty type, provider quirks)
     * is decoded by the platform and re-encoded as JPEG - the WebView then
     * hands the page a normal, fully readable image. */
    private fun copyToCache(uri: Uri): Uri? {
        return try {
            val mime = contentResolver.getType(uri) ?: ""
            val standard = mime == "image/png" || mime == "image/webp" ||
                mime == "image/gif" || mime == "image/jpeg" || mime == "image/jpg"
            val ext = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/jpeg", "image/jpg" -> "jpg"
                else -> "jpg"
            }
            val dir = File(cacheDir, "attachments").apply { mkdirs() }
            val target = File(dir, "attach_" + System.currentTimeMillis() + "_" + (dir.listFiles()?.size ?: 0) + "." + ext)
            if (standard) {
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
            } else {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
                var out = bitmap
                val maxDim = 4096
                if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                    out = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                }
                target.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (out !== bitmap) out.recycle()
            }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        proxy?.stop()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WebViewDP"
        private const val FILE_CHOOSER_REQUEST = 1001
        private const val MEDIA_PERMISSION_REQUEST = 1002
        private const val MAX_RENDERER_RESTARTS = 3
        private const val RENDERER_RESTART_WINDOW_MS = 60_000L
    }
}
