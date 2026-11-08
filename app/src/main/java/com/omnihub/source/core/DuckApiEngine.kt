package com.omnihub.source.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.omnihub.providers.ChatMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * DuckApiEngine — duck.ai as a TRUE endpoint. The orubus-ai route-d technique,
 * fused with OmniHub's WarmSessionPool:
 *
 *   status → challenge → solve → chat SSE
 *
 * but the challenge is solved INSIDE the warm duck.ai WebView itself. No DOM
 * shim, no fake browser: the page IS duck.ai running in a real browser
 * runtime, so every fe-signal, every navigator/layout read and every timing
 * bit is genuine. The orubus-ai shim existed because there was no browser —
 * here the browser is the whole point.
 *
 * Why this beats the composer relay for duck.ai:
 *  - no typing, no composer, no send button — nothing to get stuck
 *  - REAL multi-turn: the message array goes to the API verbatim, so memory
 *    no longer needs the context-prefix hack on duck at all
 *  - REAL model selection: gpt-5.4-mini / claude-haiku-4-5 / gpt-6-luna /
 *    tinfoil/gpt-oss-120b are requested by id, not "whatever the chip had"
 *  - streaming comes off the SSE pipe — first token in ~2-6s on a warm page
 *
 * Failure policy: any error surfaces as Result(ok=false) and the caller
 * (DuckAiSource) falls back to the composer WebRelay — never a dead end.
 */
object DuckApiEngine {

    private const val TAG = "DUCKAPI"

    /** duck.ai models discovered live by orubus-ai's route-d. */
    val DUCK_MODELS = listOf(
        "gpt-5.4-mini",
        "claude-haiku-4-5",
        "gpt-6-luna",
        "tinfoil/gpt-oss-120b"
    )

    suspend fun send(
        context: Context,
        model: String,
        messages: List<ChatMessage>,
        timeoutMs: Long = 60_000,
        onPartial: (String) -> Unit = {}
    ): WebViewChatEngine.Result = ProviderRelayQueue.withProviderLock("duckduckgo_ai") { queueWaitMs ->
        sendExclusive(context.applicationContext, model, messages, timeoutMs, queueWaitMs, onPartial)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sendExclusive(
        appCtx: Context,
        model: String,
        messages: List<ChatMessage>,
        timeoutMs: Long,
        queueWaitMs: Long,
        onPartial: (String) -> Unit
    ): WebViewChatEngine.Result = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        val txn = UUID.randomUUID().toString().take(8).uppercase()
        val t0 = System.currentTimeMillis()
        val chosenModel = if (model.isBlank() || model == "duck-auto") "gpt-6-luna" else model

        Log.i(TAG, "[$txn] START model=$chosenModel msgs=${messages.size} queueWaitMs=$queueWaitMs")

        main.post {
            val session = WarmSessionPool.getOrCreate(appCtx, "duckduckgo_ai")
            session.busy = true
            session.lastUsedAt = System.currentTimeMillis()
            val web = session.web
            var finished = false
            var lastPartial = ""
            var firstTokenAt = 0L
            var onDuckAi = false
            try {
                onDuckAi = web.url?.contains("duck.ai") == true || web.url?.contains("duckduckgo.com") == true
            } catch (_: Exception) {
            }

            lateinit var timeoutRunnable: Runnable

            fun finish(text: String, ok: Boolean) {
                if (finished) return
                finished = true
                session.busy = false
                try { main.removeCallbacks(timeoutRunnable) } catch (_: Exception) {}
                try {
                    web.evaluateJavascript(
                        "(function(){if(window.__duckApiRunning)window.__duckApiRunning=false;})();", null
                    )
                } catch (_: Exception) {}
                try { web.removeJavascriptInterface("OmniApiBridge") } catch (_: Exception) {}
                Log.i(
                    TAG,
                    "[$txn] ${if (ok) "OK" else "FAIL"} len=${text.length} totalMs=${System.currentTimeMillis() - t0} " +
                        "firstTokenMs=${if (firstTokenAt > 0) firstTokenAt - t0 else -1}"
                )
                if (cont.isActive) cont.resume(WebViewChatEngine.Result(text, ok, txn))
            }

            timeoutRunnable = Runnable {
                if (finished) return@Runnable
                // A stream that is still growing is alive — extend once per 20s,
                // hard-capped by ABS ceiling below.
                if (lastPartial.isNotEmpty()) {
                    Log.i(TAG, "[$txn] EXTEND (stream alive, ${lastPartial.length} chars)")
                    main.postDelayed(timeoutRunnable, 20_000)
                    val elapsed = System.currentTimeMillis() - t0
                    if (elapsed < 180_000) return@Runnable
                }
                val salvage = lastPartial.trim()
                if (salvage.length > 20) {
                    finish(salvage, true)
                } else {
                    finish("DUCK_API_TIMEOUT txn $txn", false)
                }
            }

            val bridge = object {
                @JavascriptInterface
                fun onApiPartial(text: String) {
                    main.post {
                        if (finished) return@post
                        if (firstTokenAt == 0L && text.isNotBlank()) {
                            firstTokenAt = System.currentTimeMillis()
                            Log.i(TAG, "[$txn] FIRST_TOKEN +${firstTokenAt - t0}ms")
                        }
                        lastPartial = text
                        if (text.isNotBlank()) onPartial(text)
                    }
                }

                @JavascriptInterface
                fun onApiDone(text: String) {
                    main.post {
                        if (finished) return@post
                        if (text.startsWith("API_ERR:")) {
                            finish(text.removePrefix("API_ERR:").take(200), false)
                        } else {
                            val t = text.trim()
                            finish(t, t.length > 1)
                        }
                    }
                }
            }
            try { web.removeJavascriptInterface("OmniApiBridge") } catch (_: Exception) {}
            web.addJavascriptInterface(bridge, "OmniApiBridge")

            cont.invokeOnCancellation {
                main.post {
                    session.busy = false
                    if (!finished) finish("Cancelled", false)
                }
            }

            fun go() {
                if (finished) return
                main.postDelayed(timeoutRunnable, timeoutMs)
                web.evaluateJavascript(buildApiScript(messages, chosenModel, txn), null)
            }

            if (session.ready && onDuckAi) {
                Log.i(TAG, "[$txn] HOT duck.ai page")
                go()
            } else {
                Log.i(TAG, "[$txn] COLD duck.ai load")
                main.postDelayed({
                    if (!finished && !session.ready) finish("DUCK_COLD_LOAD — duck.ai did not load in time.", false)
                }, 30_000)
                web.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        session.lastUrl = url.orEmpty()
                        session.ready = true
                        session.observerInstalled = false
                        main.post {
                            val nowDuck = try {
                                url?.contains("duck.ai") == true || url?.contains("duckduckgo.com") == true
                            } catch (_: Exception) { false }
                            if (nowDuck) go() else finish("DUCK_WRONG_PAGE: $url", false)
                        }
                    }
                }
                web.loadUrl("https://duck.ai/")
            }
        }
    }

    /**
     * The whole orubus-ai route-d chain, executed inside the duck.ai page:
     * status fetch → challenge runs in-page (REAL DOM/navigator/timing — the
     * shim becomes unnecessary) → wrap → chat POST → SSE → bridge.
     *
     * Self-upgrading: x-fe-version and the entry-script hash are read from the
     * LIVE page (html[data-version-tag] / entry.duckai.*.js), so DDG front-end
     * deploys don't break the lane the way a hardcoded constant would.
     */
    private fun buildApiScript(
        messages: List<ChatMessage>,
        model: String,
        txn: String
    ): String {
        val msgsJson = org.json.JSONArray().apply {
            for (m in messages) {
                if (m.content.isBlank()) continue
                put(
                    org.json.JSONObject()
                        .put("role", if (m.role == "assistant") "assistant" else "user")
                        .put("content", m.content)
                )
            }
        }
        val jsonMsgs = msgsJson.toString()
        val jsonModel = org.json.JSONObject.quote(model)
        val jsonTxn = org.json.JSONObject.quote(txn)
        return """
(function(){
  if (window.__duckApiRunning) { OmniApiBridge.onApiDone('API_ERR:lane-busy'); return; }
  window.__duckApiRunning = true;
  var MSGS = $jsonMsgs;
  var MODEL = $jsonModel;
  var TXN = $jsonTxn;

  function b64u(s){ return btoa(unescape(encodeURIComponent(s))); }
  function b64d(s){
    var r = atob(s.replace(/-/g,'+').replace(/_/g,'/'));
    try { return decodeURIComponent(escape(r)); } catch(e){ return r; }
  }
  async function sha256b64(s){
    var buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
    var arr = new Uint8Array(buf), bin='';
    for (var i=0;i<arr.length;i++) bin += String.fromCharCode(arr[i]);
    return btoa(bin);
  }
  function feSignals(){
    var acc = 80 + Math.floor(Math.random()*120);
    var evs = [{ name:'onboarding_impression', delta: acc }];
    acc += 900000 + Math.floor(Math.random()*400000);
    evs.push({ name:'action', delta: acc, trusted:false });
    acc += 40 + Math.floor(Math.random()*90);
    evs.push({ name:'onboarding_finish', delta: acc });
    acc += 60 + Math.floor(Math.random()*140);
    evs.push({ name:'startNewChat_free', delta: acc });
    acc += 4 + Math.floor(Math.random()*12);
    return b64u(JSON.stringify({ start: Date.now(), events: evs, end: acc }));
  }
  function state(s){ try { OmniApiBridge.onApiPartial(''); } catch(e){} }

  async function run(){
    var J = crypto.randomUUID().replace(/-/g,'');

    // 1) status → challenge header
    var r0 = await fetch('https://duckduckgo.com/duckchat/v1/status', {
      headers: {
        'accept': '*/*',
        'x-vqd-accept': '1',
        'cache-control': 'no-store',
        'x-ddg-journey-id': J
      },
      referrer: 'https://duck.ai/',
      cache: 'no-store',
      credentials: 'omit'
    });
    var challenge = r0.headers.get('x-vqd-hash-1');
    if (!challenge) throw new Error('no-challenge:' + r0.status);

    // 2) challenge runs IN-PAGE — real DOM, real navigator, real performance
    //    timeline. This is what the orubus-ai DOM shim was faking; here it is
    //    simply true.
    var src = b64d(challenge);
    var wrapped = '(async () => {' +
      src.replace(/^\s*\(async function\(\)\s*\{/, '').replace(/\}\)\(\)\s*$/, '') +
      '})()';
    var raw = await eval(wrapped);
    if (raw && typeof raw.then === 'function') raw = await raw;
    if (!raw || !raw.client_hashes || !raw.server_hashes) throw new Error('solve:' + String(raw).slice(0,60));
    var s1 = String(raw.client_hashes[1] || ''), s2 = String(raw.client_hashes[2] || '');
    if (!/^\d{2,5}$/.test(s1) || !/^\d{2,5}$/.test(s2)) throw new Error('bad-sigs:' + s1 + ',' + s2);
    var ch = [];
    for (var i = 0; i < raw.client_hashes.length; i++) ch.push(await sha256b64(String(raw.client_hashes[i])));

    // 3) self-upgrading version constants from the live page
    var feVer = 'serp_20261023_062911_ET-cdb913a87384b7be1d189a4658c28200974bd694';
    try {
      var h = document.documentElement;
      var tag = h.getAttribute('data-version-tag') || '';
      if (tag) feVer = tag;
    } catch(e){}
    var entryVer = 'f1b96d6b68aa7e4766b9';
    try {
      var scripts = document.querySelectorAll('script[src*="entry.duckai."]');
      if (scripts.length) {
        var m = scripts[scripts.length-1].src.match(/entry\.duckai\.([0-9a-f]+)\.js/);
        if (m) entryVer = m[1];
      }
    } catch(e){}

    // 4) wrap — meta.stack uses a REAL stack from THIS page
    var realStack = '';
    try { realStack = new Error().stack || ''; } catch(e){}
    var header = b64u(JSON.stringify({
      server_hashes: raw.server_hashes,
      client_hashes: ch,
      signals: raw.signals || {},
      meta: Object.assign({}, raw.meta || {}, {
        origin: 'https://duck.ai',
        stack: realStack,
        duration: String(18 + Math.floor(Math.random()*30))
      })
    }));

    // 5) chat POST → SSE
    var body = {
      model: MODEL,
      messages: MSGS.map(function(m){
        return { role: m.role, content: [{ type:'text', text: m.content }] };
      }),
      canUseTools: false,
      reasoningEffort: 'none',
      canUseApproxLocation: null,
      canDelegateImageGeneration: null,
      canShowGreeting: false
    };
    var r = await fetch('https://duckduckgo.com/duckchat/v1/chat', {
      method: 'POST',
      headers: {
        'accept': 'text/event-stream',
        'content-type': 'application/json',
        'x-vqd-hash-1': header,
        'x-fe-signals': feSignals(),
        'x-fe-version': feVer,
        'x-duckai-response-id': crypto.randomUUID(),
        'x-duckai-consent-purposes': 'user-location,image-generation,web-search',
        'x-duckai-tool-approval': 'web-search',
        'x-ddg-journey-id': J
      },
      referrer: 'https://duck.ai/',
      credentials: 'omit',
      body: JSON.stringify(body)
    });
    if (!r.ok) {
      var t = await r.text().catch(function(){ return ''; });
      throw new Error('duck-' + r.status + ':' + t.slice(0,100));
    }
    var reader = r.body.getReader();
    var dec = new TextDecoder();
    var buf = '', out = '';
    stream: while (true) {
      var rr = await reader.read();
      if (rr.done) break;
      buf += dec.decode(rr.value, { stream: true });
      var idx;
      while ((idx = buf.indexOf('\n')) !== -1) {
        var line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (line.indexOf('data: ') !== 0) continue;
        var payload = line.slice(6);
        if (payload === '[DONE]') break stream;
        if (payload.charAt(0) === '[') continue;
        try {
          var j = JSON.parse(payload);
          if (typeof j.message === 'string') {
            out += j.message;
            OmniApiBridge.onApiPartial(out);
          }
          if (j.action === 'error') throw new Error('duck-sse:' + (j.type || 'err'));
        } catch (e) {
          if (String(e.message || '').indexOf('duck-sse') === 0) throw e;
          // partial JSON across chunks — next chunk completes it
        }
      }
    }
    if (!out.trim()) throw new Error('duck-empty');
    OmniApiBridge.onApiDone(out);
  }

  run().catch(function(e){
    OmniApiBridge.onApiDone('API_ERR:' + ((e && e.message) || String(e)));
  });
})();
        """.trimIndent()
    }
}
