package com.webviewdp

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The harness session this app holds on the user's behalf.
 *
 * The app signs in once with the pasted launch URL, keeps the 30-day cookie in
 * an encrypted jar of its own, and from then on is the only holder of the
 * credential: the WebView never sees it, and the harness's own sign-in page is
 * never rendered. Requests the WebView makes to the loopback origin are relayed
 * upstream by [LoopbackProxy] with this jar's cookies attached.
 */
class UpstreamSession(context: Context) {

    private val secrets = SecretStore(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private var cookies: List<Cookie> = decode(secrets.get(SECRET_COOKIES).orEmpty())

    /** The harness origin the app talks to, once one has been pasted. */
    var upstream: HttpUrl? = null
        private set

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            synchronized(lock) {
                val replaced = this@UpstreamSession.cookies.filterNot { old ->
                    cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path }
                }
                this@UpstreamSession.cookies = replaced + cookies
                secrets.put(SECRET_COOKIES, encode(this@UpstreamSession.cookies))
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            synchronized(lock) { return cookies.filter { it.expiresAt > now && it.matches(url) } }
        }
    }

    /** Follows redirects: the launch URL answers 303 and sets the cookie. */
    private val signInClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Never follows redirects: a proxy relays them, rewritten, to the WebView. */
    val proxyClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    init {
        prefs.getString(KEY_URL, null)?.let { upstream = baseOf(it) }
    }

    /** The origin to show in the setup field, or null before the first paste. */
    fun storedUrl(): String? = upstream?.toString()

    fun hasSession(): Boolean {
        val base = upstream ?: return false
        return cookieHeader(base) != null
    }

    /**
     * Sign in with what the user pasted. A launch URL carries a one-time token
     * and answers 303 with the 30-day cookie; a bare origin is accepted when a
     * stored cookie still covers it.
     */
    fun signIn(raw: String): Result<Unit> {
        val parsed = raw.trim().toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("not a URL"))
        val base = parsed.newBuilder().encodedPath("/").query(null).fragment(null).build()
        upstream = base
        prefs.edit().putString(KEY_URL, base.toString()).apply()

        val token = parsed.queryParameter("token")
        if (token != null) {
            val request = Request.Builder().url(parsed).header("Accept", "text/html").build()
            try {
                signInClient.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        return Result.failure(SecurityException("the launch token was refused"))
                    }
                    if (response.code >= 400) {
                        return Result.failure(IllegalStateException("harness answered ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return if (cookieHeader(base) == null) {
            Result.failure(SecurityException("no session was issued"))
        } else {
            Result.success(Unit)
        }
    }

    /** True when the harness still accepts the stored session. */
    fun verify(): Boolean {
        val base = upstream ?: return false
        if (cookieHeader(base) == null) return false
        val body = """{"type":"client-request","rpcId":"${UUID.randomUUID()}",""" +
            """"method":"session/list","payload":{"args":{"_request":{}}}}"""
        val request = Request.Builder()
            .url(base.newBuilder().encodedPath("/api/session/list").build())
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            signInClient.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> false
                    // A newer harness may have moved the endpoint; that is not an
                    // auth verdict, so let the page speak for itself.
                    404 -> true
                    200 -> response.body?.string()?.contains("\"ok\":true") == true
                    else -> true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "verify failed: ${e.message}")
            true
        }
    }

    /**
     * Take over the cookie an older install left in the WebView's jar, so the
     * update does not force a fresh launch URL on the user.
     */
    fun adopt(cookieHeader: String?, base: HttpUrl) {
        if (cookieHeader.isNullOrBlank()) return
        val expires = System.currentTimeMillis() + THIRTY_DAYS_MS
        val adopted = cookieHeader.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            try {
                Cookie.Builder().name(name).value(value).hostOnlyDomain(base.host).path("/")
                    .expiresAt(expires).build()
            } catch (e: Exception) {
                null
            }
        }
        if (adopted.isNotEmpty()) {
            Log.i(TAG, "adopted ${adopted.size} cookie(s) from the WebView")
            jar.saveFromResponse(base, adopted)
        }
    }

    /** The Cookie header for one upstream URL, or null when nothing covers it. */
    fun cookieHeader(url: HttpUrl): String? {
        val now = System.currentTimeMillis()
        val matching = synchronized(lock) { cookies.filter { it.expiresAt > now && it.matches(url) } }
        return matching.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun clearSession() {
        synchronized(lock) {
            cookies = emptyList()
            secrets.remove(SECRET_COOKIES)
        }
    }

    private fun encode(list: List<Cookie>): String = list.joinToString("\n") { cookie ->
        listOf(
            base64(cookie.name),
            base64(cookie.value),
            cookie.domain,
            cookie.path,
            cookie.expiresAt.toString(),
            cookie.hostOnly.toString(),
            cookie.secure.toString(),
            cookie.httpOnly.toString(),
        ).joinToString("\t")
    }

    private fun decode(text: String): List<Cookie> = text.lineSequence().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != 8) return@mapNotNull null
        try {
            val builder = Cookie.Builder()
                .name(unbase64(fields[0]))
                .value(unbase64(fields[1]))
                .path(fields[3])
                .expiresAt(fields[4].toLong())
            if (fields[5] == "true") builder.hostOnlyDomain(fields[2]) else builder.domain(fields[2])
            if (fields[6] == "true") builder.secure()
            if (fields[7] == "true") builder.httpOnly()
            builder.build()
        } catch (e: Exception) {
            null
        }
    }.toList()

    private fun base64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun unbase64(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    private fun baseOf(raw: String): HttpUrl? =
        raw.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()

    private companion object {
        const val TAG = "WebViewDP"
        const val PREFS = "webviewdp"
        const val KEY_URL = "url"
        const val SECRET_COOKIES = "cookies"
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
