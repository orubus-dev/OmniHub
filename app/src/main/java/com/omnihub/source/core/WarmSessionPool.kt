package com.omnihub.source.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap

/**
 * One long-lived WebView per provider. Exclusive access via [ProviderRelayQueue].
 */
object WarmSessionPool {

    data class Session(
        val web: WebView,
        var ready: Boolean = false,
        var lastUrl: String = "",
        @Volatile var busy: Boolean = false,
        var lastUsedAt: Long = System.currentTimeMillis(),
        var lastTransactionId: String = "",
        var observerInstalled: Boolean = false
    )
    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Session>()
    private const val MAX_SESSIONS = 5

    /**
     * Pre-load a provider page in the background so the FIRST message is hot.
     * Called at app start (duck.ai + preferred provider) and whenever the user
     * picks a provider. Kills the 15-30s cold load from the first reply.
     */
    fun warm(context: Context, providerId: String, siteUrl: String) {
        if (siteUrl.isBlank()) return
        runOnMain {
            try {
                val s = getOrCreate(context, providerId)
                if (!s.ready && !s.busy && s.lastUrl.isBlank()) {
                    s.web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            s.lastUrl = url.orEmpty()
                            s.ready = true
                            s.observerInstalled = false
                        }
                    }
                    s.web.loadUrl(siteUrl)
                }
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(context: Context, providerId: String): Session {
        sessions[providerId]?.let {
            it.lastUsedAt = System.currentTimeMillis()
            return it
        }
        if (sessions.size >= MAX_SESSIONS) {
            val victim = sessions.entries
                .filter { !it.value.busy }
                .minByOrNull { it.value.lastUsedAt }
            victim?.key?.let { release(it) }
        }
        val app = context.applicationContext
        val web = WebView(app)
        try {
            web.layoutParams = ViewGroup.LayoutParams(1080, 1920)
            web.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, 1080, 1920)
        } catch (_: Exception) {
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        }
        val session = Session(web)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                session.lastUrl = url.orEmpty()
                session.ready = true
                session.observerInstalled = false
            }
        }
        // Silent automation client: the relay never needs location, camera,
        // mic or any runtime permission. Denying at the bridge level kills
        // popups like duck.ai's "Share your precise location" before they can
        // block a transaction — no per-site hacks, no dialog clicking.
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }
        }
        sessions[providerId] = session
        return session
    }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post(block)
    }

    fun release(providerId: String) {
        runOnMain {
            sessions.remove(providerId)?.let { s ->
                try {
                    s.busy = false
                    s.web.stopLoading()
                    s.web.destroy()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun releaseAll() {
        sessions.keys.toList().forEach { release(it) }
    }
}
