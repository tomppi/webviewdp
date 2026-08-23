package com.webviewdp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var setupView: View
    private lateinit var urlInput: EditText
    private lateinit var prefs: SharedPreferences
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingFileChooser = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    showSetup()
                    Toast.makeText(this@MainActivity, R.string.load_error, Toast.LENGTH_LONG).show()
                }
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

        val savedUrl = prefs.getString(KEY_URL, null)
        if (savedUrl != null) {
            urlInput.setText(savedUrl)
            open(savedUrl)
        } else {
            showSetup()
        }
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

    private fun connect() {
        val url = normalizeUrl(urlInput.text.toString())
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.url_empty, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString(KEY_URL, url).apply()
        open(url)
    }

    private fun open(url: String) {
        setupView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
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
    private fun copyToCache(uri: Uri): Uri? {
        return try {
            val dir = File(cacheDir, "attachments").apply { mkdirs() }
            val target = File(dir, "attach_" + System.currentTimeMillis() + "_" + (dir.listFiles()?.size ?: 0) + ".jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        } catch (_: Exception) {
            null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "webviewdp"
        private const val KEY_URL = "url"
        private const val FILE_CHOOSER_REQUEST = 1001
        private const val MEDIA_PERMISSION_REQUEST = 1002
    }
}