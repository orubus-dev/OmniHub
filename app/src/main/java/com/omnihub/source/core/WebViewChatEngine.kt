package com.omnihub.source.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * WebRelay 1.3.0 — Accuracy
 *
 * LATENCY MODEL (why v1.0.9.x could take minutes on one message):
 * - Kotlin waited only 600ms for a composer before injecting → NO_COMPOSER
 *   false-fail on any SPA that renders late → router fell through to the next
 *   provider → another cold load + 22s idle → minutes of cascade.
 * - The idle timeout started BEFORE the cold page load, so a slow first load
 *   (15-30s) ate the whole 22s budget → guaranteed fail → cascade.
 * - Engine failures were sometimes surfaced to the UI as if they were replies.
 *
 * v1.2.0 fixed the cascade (in-page composer wait, idle clock at inject,
 * generation-aware extension, React-safe typing, contextPrefix).
 *
 * v1.3.0 accuracy pass — every fix is STRUCTURAL, nothing is word-banned:
 *
 * 1. No more content-killing blocklists. The old chrome-phrase rejection
 *    nuked real replies that happened to contain phrases like "Chat can be…"
 *    or "Analysis:" → fake REPLY_TIMEOUT while the answer was on screen.
 *    Cleanup is now DOM-structural: the locked reply node is CLONED and its
 *    chrome children (buttons, toolbars, footers, disclaimers) are REMOVED
 *    before text is read. Text heuristics only trim positional labels
 *    ("You said:", timestamps) and the echo of our own message.
 *
 * 2. Transcript-delta fallback: providers whose DOM has no recognizable
 *    assistant nodes (Meta AI, some channels) used to time out with a clean
 *    reply visible. Now the conversation region is snapshotted before send
 *    and the post-send delta IS the reply when node-locking fails.
 *
 * 3. Submission ladder: type with READBACK verification (the field must
 *    actually contain the text — fixes "answer stuck in the composer"),
 *    then three submit strategies (send click → Enter → form submit), each
 *    verified by progress signals (cleared / generating / new node / region
 *    grew). Leftover composer text is cleared on failure — nothing sticks.
 *
 * 4. Speed: observation starts at the first submit (not after confirmation),
 *    polls tightened (60ms), completion quiet-window ~300ms, leaner context
 *    prefix. Warm duck.ai + preferred provider pre-load at app start.
 */
object WebViewChatEngine {

    data class Result(val text: String, val ok: Boolean, val transactionId: String = "")

    private const val TAG = "WEB"
    /** Quiet with no tokens after inject → ask the page if it is still generating. */
    private const val IDLE_TIMEOUT_MS = 22_000L
    /** Hard ceiling (safety only — normal completion is node stabilization) */
    private const val ABS_MAX_MS = 120_000L
    /** Extend deadline this much after each partial */
    private const val PARTIAL_EXTEND_MS = 15_000L
    /** Extension granted when the page is visibly still generating at idle timeout */
    private const val GEN_EXTEND_MS = 20_000L
    private const val MAX_GEN_EXTENSIONS = 3
    /** Cold page-load guard: page must finish loading within this window */
    private const val COLD_LOAD_TIMEOUT_MS = 45_000L

    /**
     * Structural cleanup only: echo of our own message + thinking tags +
     * positional role labels. Never rejects a non-empty captured reply —
     * a visible answer can no longer become a fake REPLY_TIMEOUT.
     */
    fun cleanReply(raw: String, userMessage: String): String? {
        var text = ReplySanitizer.lastTurnOnly(raw, userMessage)
        val um = userMessage.trim()
        if (um.isNotEmpty()) {
            if (text.length in 1..um.length && um.endsWith(text, ignoreCase = true)) return null
            if (text.startsWith(um, ignoreCase = true)) text = text.substring(um.length).trim()
        }
        text = ReplySanitizer.strip(text)
        if (text.length < 2) return null
        return text
    }

    suspend fun send(
        context: Context,
        siteUrl: String,
        providerId: String,
        userMessage: String,
        timeoutMs: Long = IDLE_TIMEOUT_MS,
        onPartial: (String) -> Unit = {},
        contextPrefix: String = ""
    ): Result = ProviderRelayQueue.withProviderLock(providerId) { queueWaitMs ->
        sendExclusive(context, siteUrl, providerId, userMessage, timeoutMs, queueWaitMs, onPartial, contextPrefix)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sendExclusive(
        context: Context,
        siteUrl: String,
        providerId: String,
        userMessage: String,
        timeoutMs: Long,
        queueWaitMs: Long,
        onPartial: (String) -> Unit,
        contextPrefix: String
    ): Result = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        val appCtx = context.applicationContext
        val txn = UUID.randomUUID().toString().take(8).uppercase()
        val t0 = System.currentTimeMillis()
        val pageMap = ProviderPageMap.load(appCtx, providerId)
        val mapHot = pageMap.isUsable()
        val target = siteUrl.ifBlank {
            ProviderCatalog.urlFor(providerId) ?: "https://chatgpt.com/"
        }

        Log.i(TAG, "[$txn] START provider=$providerId mapHot=$mapHot queueWaitMs=$queueWaitMs")

        main.post {
            val session = WarmSessionPool.getOrCreate(appCtx, providerId)
            session.busy = true
            session.lastTransactionId = txn
            session.lastUsedAt = System.currentTimeMillis()
            val web = session.web
            var finished = false
            var injected = false
            var firstTokenAt = 0L
            var sendConfirmedAt = 0L
            var lastPartialAt = 0L
            var lastPartialText = ""
            var genExtensions = 0

            lateinit var timeoutRunnable: Runnable
            lateinit var coldGuardRunnable: Runnable

            fun scheduleTimeout(delayMs: Long) {
                try { main.removeCallbacks(timeoutRunnable) } catch (_: Exception) {}
                val elapsed = System.currentTimeMillis() - t0
                val remainingAbs = (ABS_MAX_MS - elapsed).coerceAtLeast(0L)
                val delay = delayMs.coerceAtMost(remainingAbs)
                if (delay <= 0L) {
                    main.post(timeoutRunnable)
                } else {
                    main.postDelayed(timeoutRunnable, delay)
                }
            }

            fun clearComposerOnPage() {
                try {
                    web.evaluateJavascript(
                        "(function(){var cs=document.querySelectorAll('textarea,[contenteditable=\"true\"],div[role=\"textbox\"]');" +
                            "for(var i=0;i<cs.length;i++){var c=cs[i];" +
                            "try{if(c.tagName==='TEXTAREA'||c.tagName==='INPUT'){var p=c.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set){d.set.call(c,'');}else{c.value='';}c.dispatchEvent(new Event('input',{bubbles:true}));}" +
                            "else{c.textContent='';c.dispatchEvent(new InputEvent('input',{bubbles:true,data:'',inputType:'deleteContentBackward'}));}}catch(e){}}})();",
                        null
                    )
                } catch (_: Exception) {}
            }

            fun finish(text: String, ok: Boolean) {
                if (finished) return
                finished = true
                session.busy = false
                try { main.removeCallbacks(timeoutRunnable) } catch (_: Exception) {}
                try { main.removeCallbacks(coldGuardRunnable) } catch (_: Exception) {}
                try {
                    web.evaluateJavascript(
                        "(function(){window.__omniRunning=false;window.__omniActive=false;" +
                            "if(window.__omniObs){try{window.__omniObs.disconnect()}catch(e){}window.__omniObs=null;}" +
                            "if(window.__omniTimer){clearInterval(window.__omniTimer);window.__omniTimer=null;}" +
                            "window.__omniLockedNode=null;})();",
                        null
                    )
                } catch (_: Exception) {}
                // Composer hygiene: a failed relay must never leave our text
                // sitting in the field (the "stuck in composer" bug).
                if (!ok) clearComposerOnPage()
                if (!ok) ProviderPageMap.rememberFailure(appCtx, providerId)
                Log.i(
                    TAG,
                    "[$txn] ${if (ok) "OK" else "FAIL"} len=${text.length} totalMs=${System.currentTimeMillis() - t0} " +
                        "sendMs=${if (sendConfirmedAt > 0) sendConfirmedAt - t0 else -1} " +
                        "firstTokenMs=${if (firstTokenAt > 0) firstTokenAt - t0 else -1}"
                )
                if (cont.isActive) cont.resume(Result(text, ok, txn))
            }

            // Cold-load guard: cancelled the moment we inject (page ready)
            coldGuardRunnable = Runnable {
                if (!injected && !finished) {
                    Log.i(TAG, "[$txn] COLD_LOAD_TIMEOUT")
                    finish("COLD_LOAD_TIMEOUT — $providerId took over ${COLD_LOAD_TIMEOUT_MS / 1000}s to load.", false)
                }
            }

            timeoutRunnable = Runnable {
                if (finished) return@Runnable
                // Ask the page: is the model still writing? If yes, extend instead of failing.
                web.evaluateJavascript(
                    "(function(){try{return (window.__omniIsGenerating && window.__omniIsGenerating())?'1':'0';}catch(e){return '0';}})();"
                ) { gen ->
                    val stillGenerating = gen?.contains("1") == true
                    if (stillGenerating && genExtensions < MAX_GEN_EXTENSIONS) {
                        genExtensions++
                        Log.i(TAG, "[$txn] GEN_EXTEND #$genExtensions (model still writing)")
                        scheduleTimeout(GEN_EXTEND_MS)
                        return@evaluateJavascript
                    }
                    // Prefer last streamed partial if substantial; else page salvage
                    val fromStream = cleanReply(lastPartialText, userMessage)
                    if (fromStream != null && fromStream.length > 20) {
                        finish(fromStream, true)
                        return@evaluateJavascript
                    }
                    web.evaluateJavascript(
                        """
                        (function(){
                          if (window.__omniLockedNode) {
                            try {
                              var t = (window.__omniLockedNode.innerText || window.__omniLockedNode.textContent || '').trim();
                              if (t.length > 8) return t.slice(-12000);
                            } catch(e){}
                          }
                          var root=document.querySelector('main,[role=main],[data-testid*=conversation],#__next')||document.body;
                          var t=(root&&root.innerText||'').trim();
                          return t.slice(-12000);
                        })();
                        """.trimIndent()
                    ) { last ->
                        val t = last?.trim()?.removeSurrounding("\"")
                            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim().orEmpty()
                        val cleaned = cleanReply(t, userMessage)
                        if (cleaned != null && cleaned.length > 12) {
                            finish(cleaned, true)
                        } else if (lastPartialText.length > 40) {
                            val soft = ReplySanitizer.strip(lastPartialText)
                            finish(soft.ifBlank { lastPartialText }, soft.length > 8)
                        } else {
                            finish("REPLY_TIMEOUT txn $txn — no reply captured. Stay on signed-in chat and retry.", false)
                        }
                    }
                }
            }

            val bridgeName = "OmniBridge"
            try { web.removeJavascriptInterface(bridgeName) } catch (_: Exception) {}

            val bridge = object {
                @JavascriptInterface
                fun onReply(text: String) {
                    main.post {
                        val t = text.trim()
                        when {
                            t.startsWith("ERR:") ->
                                finish(t.removePrefix("ERR:").ifBlank { "Page error" }, false)
                            t.isBlank() -> finish("Empty reply.", false)
                            else -> {
                                val cleaned = cleanReply(t, userMessage)
                                    ?: ReplySanitizer.strip(t).takeIf { it.length > 8 }
                                    ?: t
                                finish(cleaned, cleaned.length > 1)
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun onLearn(inputSel: String?, sendSel: String?, convSel: String?) {
                    main.post {
                        ProviderPageMap.rememberSuccess(appCtx, providerId, inputSel, sendSel, convSel)
                    }
                }

                @JavascriptInterface
                fun onPartial(text: String) {
                    main.post {
                        if (finished) return@post
                        val raw = text.trim()
                        if (raw.isEmpty() || raw.startsWith("ERR:")) return@post
                        lastPartialAt = System.currentTimeMillis()
                        lastPartialText = raw
                        // Adaptive: keep waiting while the model is still writing
                        scheduleTimeout(PARTIAL_EXTEND_MS)
                        val cleaned = cleanReply(raw, userMessage)
                        if (cleaned != null && cleaned.length >= 3) {
                            if (firstTokenAt == 0L) {
                                firstTokenAt = System.currentTimeMillis()
                                Log.i(TAG, "[$txn] FIRST_TOKEN +${firstTokenAt - t0}ms")
                            }
                            onPartial(cleaned)
                        } else if (raw.length >= 12) {
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                            val soft = ReplySanitizer.strip(raw)
                            if (soft.length >= 8) onPartial(soft)
                        }
                    }
                }

                @JavascriptInterface
                fun onState(state: String) {
                    val now = System.currentTimeMillis()
                    if (state.startsWith("SEND_CONFIRMED") && sendConfirmedAt == 0L) sendConfirmedAt = now
                    Log.i(TAG, "[$txn] $state +${now - t0}ms")
                }
            }
            web.addJavascriptInterface(bridge, bridgeName)

            cont.invokeOnCancellation {
                main.post {
                    session.busy = false
                    if (!finished) finish("Cancelled", false)
                }
            }

            fun inject() {
                if (finished || injected) return
                injected = true
                try { main.removeCallbacks(coldGuardRunnable) } catch (_: Exception) {}
                // Idle clock starts NOW (page is ready) — never during cold load
                scheduleTimeout(timeoutMs)
                web.evaluateJavascript(
                    "(function(){window.__omniRunning=false;window.__omniActive=false;" +
                        "if(window.__omniObs){try{window.__omniObs.disconnect()}catch(e){}window.__omniObs=null;}" +
                        "if(window.__omniTimer){clearInterval(window.__omniTimer);window.__omniTimer=null;}" +
                        "window.__omniLockedNode=null;})();",
                    null
                )
                main.post {
                    if (finished) return@post
                    web.evaluateJavascript(
                        buildEngineScript(
                            userMessage,
                            contextPrefix,
                            pageMap.composer,
                            pageMap.send,
                            pageMap.conversation,
                            mapHot,
                            txn
                        ),
                        null
                    )
                }
            }

            val sameHost = try {
                val a = java.net.URI(target).host?.lowercase().orEmpty()
                val b = java.net.URI(session.lastUrl.ifBlank { "http://local" }).host?.lowercase().orEmpty()
                a.isNotBlank() && (a == b || b.endsWith(a) || a.endsWith(b))
            } catch (_: Exception) {
                false
            }

            if (session.ready && sameHost && session.lastUrl.isNotBlank()) {
                Log.i(TAG, "[$txn] HOT_SESSION mapHot=$mapHot")
                inject()
            } else {
                session.ready = false
                Log.i(TAG, "[$txn] COLD_LOAD")
                main.postDelayed(coldGuardRunnable, COLD_LOAD_TIMEOUT_MS)
                web.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        session.lastUrl = url.orEmpty()
                        session.ready = true
                        session.observerInstalled = false
                        main.post { inject() }
                    }
                }
                web.loadUrl(target)
            }
        }
    }

    private fun buildEngineScript(
        message: String,
        contextPrefix: String,
        mapComposer: String,
        mapSend: String,
        mapConv: String,
        mapHot: Boolean,
        txn: String
    ): String {
        val jsonMsg = org.json.JSONObject.quote(message)
        val jsonCtx = org.json.JSONObject.quote(contextPrefix)
        val jsonIn = org.json.JSONObject.quote(mapComposer)
        val jsonSend = org.json.JSONObject.quote(mapSend)
        val jsonConv = org.json.JSONObject.quote(mapConv)
        val jsonTxn = org.json.JSONObject.quote(txn)
        val mapHotJs = if (mapHot) "true" else "false"
        return """
(function(){
  if (window.__omniObs) { try { window.__omniObs.disconnect(); } catch(e){} window.__omniObs=null; }
  if (window.__omniTimer) { try { clearInterval(window.__omniTimer); } catch(e){} window.__omniTimer=null; }
  window.__omniRunning = true;
  window.__omniActive = true;
  window.__omniLockedNode = null;
  var MSG = $jsonMsg;
  var CTX = $jsonCtx;
  var MAP_IN = $jsonIn;
  var MAP_SEND = $jsonSend;
  var MAP_CONV = $jsonConv;
  var MAP_HOT = $mapHotJs;
  var TXN = $jsonTxn;

  function state(s){ try{ OmniBridge.onState(s); }catch(e){} }
  function fail(t){
    window.__omniRunning=false; window.__omniActive=false;
    if(window.__omniObs){try{window.__omniObs.disconnect()}catch(e){}window.__omniObs=null;}
    if(window.__omniTimer){clearInterval(window.__omniTimer);window.__omniTimer=null;}
    window.__omniLockedNode=null;
    try{ OmniBridge.onReply('ERR:'+t); }catch(e){}
  }
  function ok(t){
    window.__omniRunning=false; window.__omniActive=false;
    if(window.__omniObs){try{window.__omniObs.disconnect()}catch(e){}window.__omniObs=null;}
    if(window.__omniTimer){clearInterval(window.__omniTimer);window.__omniTimer=null;}
    try{ OmniBridge.onReply(t); }catch(e){}
  }
  function learn(i,s,c){ try{ OmniBridge.onLearn(i||'',s||'',c||''); }catch(e){} }
  function partial(t){ try{ OmniBridge.onPartial(t||''); }catch(e){} }

  function visible(el){
    if(!el) return false;
    var r=el.getBoundingClientRect();
    if(r.width<3||r.height<3) return false;
    var st=window.getComputedStyle(el);
    return st.display!=='none' && st.visibility!=='hidden' && parseFloat(st.opacity||'1')!==0;
  }

  function needsAuth(){
    var body=(document.body&&document.body.innerText||'').slice(0,2000).toLowerCase();
    if(/sign in to continue|log in to continue|create an account|enter your password/.test(body)) return true;
    var pw=document.querySelector('input[type="password"]');
    return !!(pw&&visible(pw));
  }

  // Tightened generation detection. No [class*="stop"] catch-all — it matched
  // unrelated elements and froze the completion gate on several providers.
  function isGenerating(){
    var sels=[
      'button[aria-label*="Stop" i]','button[data-testid*="stop"]','[data-testid="stop-button"]',
      'button[aria-label*="Stop generating" i]','.result-streaming','.streaming','[aria-busy="true"]'
    ];
    for(var i=0;i<sels.length;i++){
      try{ var n=document.querySelectorAll(sels[i]); for(var j=0;j<n.length;j++) if(visible(n[j])) return true; }catch(e){}
    }
    try{
      var t=(document.body&&document.body.innerText||'').slice(-600).toLowerCase();
      if(/stop generating|generating…|generating\.\.\.|thinking…|thinking\.\.\./.test(t)) return true;
    }catch(e){}
    return false;
  }
  window.__omniIsGenerating = isGenerating;

  function cssPath(el){
    if(!el||el.nodeType!==1) return '';
    if(el.id) return '#'+el.id;
    var dt=el.getAttribute('data-testid'); if(dt) return '[data-testid="'+dt+'"]';
    var al=el.getAttribute('aria-label');
    if(al) return el.tagName.toLowerCase()+'[aria-label="'+String(al).replace(/"/g,'')+'"]';
    var parts=[], cur=el;
    for(var d=0;cur&&cur.nodeType===1&&d<4;d++){
      var name=cur.tagName.toLowerCase(), parent=cur.parentElement;
      if(parent){
        var sibs=[].filter.call(parent.children,function(c){return c.tagName===cur.tagName;});
        if(sibs.length>1) name+=':nth-of-type('+([].indexOf.call(sibs,cur)+1)+')';
      }
      parts.unshift(name); cur=parent;
    }
    return parts.join(' > ');
  }

  function findComposer(){
    if(MAP_IN){
      try{ var el=document.querySelector(MAP_IN); if(el&&visible(el)) return el; }catch(e){}
    }
    var candidates = document.querySelectorAll(
      '#prompt-textarea, textarea[data-id], textarea, [contenteditable="true"], div[role="textbox"], .ProseMirror, [data-testid*="composer"], [data-testid*="prompt"]'
    );
    var best=null, bestScore=-1;
    for(var i=0;i<candidates.length;i++){
      var el=candidates[i];
      if(!visible(el)) continue;
      var r=el.getBoundingClientRect();
      var score = r.width * r.height;
      var ph=((el.getAttribute('placeholder')||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.id||'')+' '+(el.className||'')).toLowerCase();
      if(/message|prompt|chat|ask|send|input|composer/.test(ph)) score += 50000;
      if(r.bottom > window.innerHeight*0.5) score += 20000; // prefer bottom
      if(score > bestScore){ bestScore=score; best=el; }
    }
    return best;
  }

  function findSendButton(nearComposer){
    if(MAP_SEND){
      try{ var el=document.querySelector(MAP_SEND); if(el&&visible(el)) return el; }catch(e){}
    }
    var sels = [
      'button[data-testid*="send"]','button[aria-label*="Send" i]','button[aria-label*="Submit" i]',
      'button[type="submit"]','[data-testid="send-button"]'
    ];
    var candidates=[];
    for(var i=0;i<sels.length;i++){
      try{ var n=document.querySelectorAll(sels[i]); for(var j=0;j<n.length;j++) if(visible(n[j])) candidates.push(n[j]); }catch(e){}
    }
    if(nearComposer){
      var cr=nearComposer.getBoundingClientRect();
      candidates.sort(function(a,b){
        var ar=a.getBoundingClientRect(), br=b.getBoundingClientRect();
        var ad=Math.abs(ar.top-cr.top)+Math.abs(ar.left-cr.right);
        var bd=Math.abs(br.top-cr.top)+Math.abs(br.left-cr.right);
        return ad-bd;
      });
    }
    return candidates[0]||null;
  }

  function locateLatestAssistant(){
    var sels = [
      '[data-message-author-role="assistant"]',
      '[data-role="assistant"]',
      '[data-testid*="assistant"]',
      '[data-testid*="bot"]',
      '.markdown.prose',
      '.prose',
      '[class*="assistant"]',
      '[class*="bot-message"]',
      '[class*="ai-message"]',
      'div[data-message-id]'
    ];
    var nodes = [];
    for(var i=0;i<sels.length;i++){
      try{
        var found = document.querySelectorAll(sels[i]);
        for(var j=0;j<found.length;j++){
          if(visible(found[j]) && (found[j].innerText||found[j].textContent||'').trim().length > 0){
            nodes.push(found[j]);
          }
        }
      }catch(e){}
    }
    if(nodes.length===0) return null;
    return nodes[nodes.length-1];
  }

  function conversationRegion(){
    if(MAP_CONV){
      try{ var el=document.querySelector(MAP_CONV); if(el) return el; }catch(e){}
    }
    return document.querySelector('main,[role=main],[data-testid*=conversation],#__next')||document.body;
  }

  function regionText(){
    try{
      var root=conversationRegion();
      return ((root.innerText||root.textContent||'').trim());
    }catch(e){ return ''; }
  }

  // ---------- STRUCTURAL chrome removal ----------
  // The reply node is CLONED and its UI chrome (action bars, buttons,
  // toolbars, footers, disclaimers, metadata rows) is removed BY STRUCTURE
  // before text is read. No content words are ever matched or banned.
  function extractReplyText(node){
    if(!node) return '';
    var text='';
    try{
      var clone=node.cloneNode(true);
      var drop=clone.querySelectorAll(
        'button,[role="button"],[role="toolbar"],[role="menu"],nav,footer,aside,' +
        '[data-testid*="action"],[data-testid*="feedback"],[data-testid*="copy"],[data-testid*="thumb"],' +
        '[data-testid*="regenerate"],[data-testid*="edit"],[data-testid*="share"],[data-testid*="volume"],' +
        '[aria-label*="Copy" i],[aria-label*="Regenerate" i],[aria-label*="Edit" i],[aria-label*="Like" i],' +
        '[aria-label*="Dislike" i],[aria-label*="Share" i],[aria-label*="Read aloud" i],[aria-label*="thumb" i],' +
        '[class*="action-bar"],[class*="toolbar"],[class*="footer"],[class*="disclaimer"],' +
        '[class*="actions"],[class*="button-group"],[class*="feedback"],[class*="sr-only"],' +
        'svg,style,script,noscript'
      );
      for(var i=0;i<drop.length;i++){ try{ drop[i].parentNode.removeChild(drop[i]); }catch(e){} }
      text=(clone.innerText||clone.textContent||'').trim();
    }catch(e){
      text=(node.innerText||node.textContent||'').trim();
    }
    return text;
  }

  function getNodeText(node){
    return extractReplyText(node);
  }

  // React-safe value setter — plain el.value= is ignored by React-controlled
  // inputs (send button never enables → SEND_FAILED → cascade).
  function setNativeValue(el, text){
    try{
      var proto = el.tagName==='TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if(desc && desc.set){ desc.set.call(el, text); } else { el.value = text; }
      el.dispatchEvent(new Event('input',{bubbles:true}));
      el.dispatchEvent(new Event('change',{bubbles:true}));
    }catch(e){
      try{ el.value = text; el.dispatchEvent(new Event('input',{bubbles:true})); }catch(e2){}
    }
  }

  function setInputValue(el, text){
    if(!el) return false;
    try{
      el.focus();
      if(el.tagName==='TEXTAREA' || el.tagName==='INPUT'){
        setNativeValue(el, text);
        return true;
      }
      // contenteditable / ProseMirror — execCommand fires trusted input events
      try{
        var range=document.createRange(); range.selectNodeContents(el);
        var sel=window.getSelection(); sel.removeAllRanges(); sel.addRange(range);
        var okExec = document.execCommand('insertText', false, text);
        if(okExec && (el.textContent||'').length>0) return true;
      }catch(eInner){}
      el.innerHTML = '';
      el.textContent = text;
      el.dispatchEvent(new InputEvent('input',{bubbles:true,data:text,inputType:'insertText'}));
      return true;
    }catch(e){ return false; }
  }

  function inputIsEmpty(el){
    try{
      if(el.tagName==='TEXTAREA' || el.tagName==='INPUT') return (el.value||'').length===0;
      return (el.textContent||'').trim().length===0;
    }catch(e){ return false; }
  }

  function inputText(el){
    try{
      if(el.tagName==='TEXTAREA' || el.tagName==='INPUT') return el.value||'';
      return el.textContent||'';
    }catch(e){ return ''; }
  }

  function clearComposer(el){
    if(!el) return;
    try{
      if(el.tagName==='TEXTAREA' || el.tagName==='INPUT'){
        setNativeValue(el, '');
      } else {
        el.innerHTML='';
        el.textContent='';
        el.dispatchEvent(new InputEvent('input',{bubbles:true,data:'',inputType:'deleteContentBackward'}));
      }
    }catch(e){}
  }

  function clickSend(btn){
    if(!btn) return false;
    try{
      btn.focus();
      btn.click();
      return true;
    }catch(e){
      try{
        btn.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));
        return true;
      }catch(e2){ return false; }
    }
  }

  function pressEnter(el){
    try{
      ['keydown','keypress','keyup'].forEach(function(type){
        el.dispatchEvent(new KeyboardEvent(type,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
      });
      return true;
    }catch(e){ return false; }
  }

  function requestSubmitNear(el){
    try{
      var f = el && el.closest ? el.closest('form') : null;
      if(f){
        if(f.requestSubmit){ f.requestSubmit(); return true; }
        if(f.submit){ f.submit(); return true; }
      }
    }catch(e){}
    return false;
  }

  // ---------- dismiss page dialogs (structural: dialogs without composer) ----------
  function dismissOverlays(){
    try{
      var dialogs=document.querySelectorAll('[role="dialog"],[role="alertdialog"],dialog');
      for(var i=0;i<dialogs.length;i++){
        var d=dialogs[i];
        if(!visible(d)) continue;
        if(d.querySelector('[contenteditable="true"],textarea')) continue; // real UI — never touch
        var btns=d.querySelectorAll('button,[role="button"]');
        var hit=null;
        for(var j=0;j<btns.length;j++){
          var al=(btns[j].getAttribute('aria-label')||'')+' '+(btns[j].textContent||'');
          if(/close|dismiss|no thanks|not now|deny|later|cancel|decline/i.test(al)){ hit=btns[j]; break; }
        }
        if(!hit && btns.length>0) hit=btns[btns.length-1];
        if(hit){ try{ hit.click(); }catch(e){} }
      }
    }catch(e){}
  }

  // ========== MAIN FLOW ==========
  var t0 = Date.now();
  var input=null, lastSend=null;
  var beforeAssistants=[];
  var preRegion='';

  function snapshotAssistants(){
    beforeAssistants=[];
    try{
      var existing = document.querySelectorAll(
        '[data-message-author-role="assistant"],[data-role="assistant"],[data-testid*="assistant"],.markdown.prose,.prose'
      );
      for(var i=0;i<existing.length;i++) beforeAssistants.push(existing[i]);
    }catch(e){}
  }

  function snapshotHit(){
    return beforeAssistants.length ? beforeAssistants[beforeAssistants.length-1] : null;
  }

  function waitComposer(){
    dismissOverlays();
    input = findComposer();
    if(input){
      clearComposer(input); // hygiene: nothing of ours (or a failed past try) lingers
      state('COMPOSER_FOUND +' + (Date.now()-t0) + 'ms');
      return typeAndSend();
    }
    // Auth wall with no composer anywhere → fail fast, do not burn the budget
    if(Date.now()-t0 > 4000 && needsAuth()){
      fail('AUTH_REQUIRED — open provider, sign in, stay on chat box, then retry.');
      return;
    }
    if(Date.now()-t0 > 15000){
      fail('NO_COMPOSER — could not locate chat input within 15s.');
      return;
    }
    setTimeout(waitComposer, 120);
  }

  // Type with READBACK: the field must actually contain what we typed.
  // Fixes the "message stuck in the composer" family — a silent set failure
  // is retried with the next strategy instead of submitting an empty field.
  function typeWithReadback(){
    var payload = CTX ? (CTX + '\n\n' + MSG) : MSG;
    var tail = MSG.slice(-12);
    for(var attempt=0; attempt<3; attempt++){
      if(attempt===1){
        // strategy 2: execCommand on fresh selection
        try{
          input.focus();
          var range=document.createRange(); range.selectNodeContents(input);
          var sel=window.getSelection(); sel.removeAllRanges(); sel.addRange(range);
          document.execCommand('insertText', false, payload);
        }catch(e){}
      } else if(attempt===2){
        // strategy 3: direct textContent + trusted InputEvent
        try{
          input.innerHTML='';
          input.textContent=payload;
          input.dispatchEvent(new InputEvent('input',{bubbles:true,data:payload,inputType:'insertText'}));
        }catch(e){}
      } else {
        setInputValue(input, payload);
      }
      var now=(inputText(input)||'');
      if(now.indexOf(tail) !== -1 || now.length >= Math.min(payload.length, 24)) return true;
    }
    return (inputText(input)||'').length > 0;
  }

  function regionGrew(){
    var now=regionText();
    return now.length > preRegion.length + 8;
  }

  function submitProgress(){
    var cleared=false;
    try{ cleared = input && inputIsEmpty(input); }catch(e){}
    return isGenerating() || locateLatestAssistant() !== snapshotHit() || cleared || regionGrew();
  }

  // Submission ladder: click → Enter → form submit; each attempt gets ~2.1s
  // of progress watching. Verified progress beats blind clicking.
  function typeAndSend(){
    var readbackOk = typeWithReadback();
    state('TYPED' + (readbackOk ? '' : '_UNVERIFIED'));

    snapshotAssistants();
    preRegion = regionText();

    var round=0;
    function attempt(){
      round++;
      lastSend = findSendButton(input);
      var disabled = false;
      try{ disabled = lastSend && (lastSend.disabled || lastSend.getAttribute('aria-disabled')==='true'); }catch(e){}
      var did=false;
      if(round===1 && lastSend && !disabled){ did=clickSend(lastSend); }
      else if(round===2){ did=pressEnter(input); }
      else { did=clickSend(lastSend)||pressEnter(input)||requestSubmitNear(input); }
      state('SUBMIT_'+round+(did?'':'_noop'));
      watch(0);
    }
    function watch(tries){
      if(submitProgress()){
        state('SEND_CONFIRMED +' + (Date.now()-t0) + 'ms');
        observe();
        return;
      }
      if(tries >= 30){
        if(round < 3){ attempt(); return; }
        fail('SEND_FAILED — message not submitted after 3 strategies. Stay on signed-in chat UI and retry.');
        return;
      }
      setTimeout(function(){ watch(tries+1); }, 70);
    }
    attempt();
  }

  function observe(){
    state('THINKING');
    var lastEmitted = '';
    var quietTicks = 0;
    var grew = false;
    var lockedNode = null;
    var lockAttempts = 0;
    var deltaMode = false;

    function tryLockNode(){
      var current = locateLatestAssistant();
      if(!current) return null;
      var isNew = true;
      for(var i=0;i<beforeAssistants.length;i++){
        if(beforeAssistants[i] === current){ isNew=false; break; }
      }
      if(isNew || beforeAssistants.length===0) return current;
      var txt = getNodeText(current);
      if(txt.length > 20) return current;
      return null;
    }

    function deltaText(){
      var now=regionText();
      if(!now) return '';
      if(preRegion && now.indexOf(preRegion)===0) return now.substring(preRegion.length).trim();
      if(now.length > preRegion.length) return now.slice(-4000);
      return '';
    }

    function tick(){
      if(!window.__omniRunning) return true;

      if(!lockedNode && !deltaMode){
        lockAttempts++;
        lockedNode = tryLockNode();
        if(lockedNode){
          window.__omniLockedNode = lockedNode;
          state('LOCKED +' + (Date.now()-t0) + 'ms');
        } else if(lockAttempts > 25){
          // No recognizable assistant node on this provider — fall back to
          // the transcript delta (works regardless of DOM conventions).
          deltaMode = true;
          state('DELTA_MODE');
        }
      }

      var d = '';
      if(lockedNode){
        d = getNodeText(lockedNode);
      } else if(deltaMode){
        d = deltaText();
      }

      if(d && d.length>=3 && d !== lastEmitted){
        lastEmitted = d;
        state('STREAMING');
        partial(d);
        grew = true;
        quietTicks = 0;
      } else {
        quietTicks++;
      }

      var gen = isGenerating();

      var needQuiet = d.length > 1500 ? 7 : 5; // ~300-420ms at 60ms poll
      if(grew && quietTicks >= needQuiet && !gen && d.length >= 3){
        state('COMPLETED');
        learn(cssPath(input), cssPath(lastSend), MAP_CONV);
        ok(d || lastEmitted);
        return true;
      }

      // Long stable reply — completes even if the gen signal is stuck true
      if(grew && quietTicks >= 12 && d.length > 80){
        state('COMPLETED_STABLE');
        learn(cssPath(input), cssPath(lastSend), MAP_CONV);
        ok(d || lastEmitted);
        return true;
      }

      return false;
    }

    try{
      var root = conversationRegion();
      window.__omniObs = new MutationObserver(function(){ tick(); });
      window.__omniObs.observe(root, {childList:true, subtree:true, characterData:true});
    }catch(e){}

    window.__omniTimer = setInterval(function(){
      if(tick()) return;
      if(Date.now() - t0 > 110000){
        clearInterval(window.__omniTimer); window.__omniTimer=null;
        if(window.__omniObs){try{window.__omniObs.disconnect()}catch(e){}window.__omniObs=null;}
        var finalD = lockedNode ? getNodeText(lockedNode) : (lastEmitted || '');
        if(finalD.length >= 8 || lastEmitted.length >= 8){
          learn(cssPath(input), cssPath(lastSend), MAP_CONV);
          ok(finalD || lastEmitted);
        } else {
          fail('REPLY_TIMEOUT txn '+TXN);
        }
      }
    }, 60);
  }

  if(needsAuth() && !findComposer()){
    fail('AUTH_REQUIRED — open provider, sign in, stay on chat box, then retry.');
    return;
  }
  waitComposer();
})();
        """.trimIndent()
    }
}
