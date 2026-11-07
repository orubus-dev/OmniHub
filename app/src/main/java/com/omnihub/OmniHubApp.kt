package com.omnihub

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.omnihub.analytics.AnalyticsCollector
import com.omnihub.analytics.AnalyticsRepository
import com.omnihub.core.OmniRouter
import com.omnihub.history.ChatRepository
import com.omnihub.history.IncognitoRetention
import com.omnihub.localapi.LocalGateway
import com.omnihub.mcp.McpClient
import com.omnihub.providers.ProviderRegistry
import com.omnihub.providers.impl.ProviderBootstrap
import com.omnihub.providers.websession.WebSessionManager
import com.omnihub.soul.SoulManager
import com.omnihub.source.AutoIssueReporter
import com.omnihub.source.SourceManager
import com.omnihub.source.SourceRouter
import com.omnihub.source.core.ProviderAuthStore
import com.omnihub.source.core.WarmSessionPool
import com.omnihub.terminal.OmniTerminal
import com.omnihub.workspace.OmniWorkspace

class OmniHubApp : Application() {
    lateinit var registry: ProviderRegistry
        private set
    lateinit var router: OmniRouter
        private set
    lateinit var sourceRouter: SourceRouter
        private set
    lateinit var sourceManager: SourceManager
        private set
    lateinit var issueReporter: AutoIssueReporter
        private set
    lateinit var soul: SoulManager
        private set
    lateinit var chatRepo: ChatRepository
        private set
    lateinit var webSessions: WebSessionManager
        private set
    lateinit var mcp: McpClient
        private set
    lateinit var analytics: AnalyticsCollector
        private set
    lateinit var analyticsRepo: AnalyticsRepository
        private set
    lateinit var workspace: OmniWorkspace
        private set
    lateinit var terminal: OmniTerminal
        private set
    var localGateway: LocalGateway? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        registry = ProviderRegistry(this)
        soul = SoulManager(this)
        chatRepo = ChatRepository(this)
        webSessions = WebSessionManager(this)
        mcp = McpClient(this)
        sourceManager = SourceManager(this)
        issueReporter = AutoIssueReporter(this)
        analytics = AnalyticsCollector(this)
        analyticsRepo = AnalyticsRepository(analytics, chatRepo, soul)
        workspace = OmniWorkspace(this)
        terminal = OmniTerminal(this, workspace)
        ProviderBootstrap.reload(this, registry)
        router = OmniRouter(registry)
        sourceRouter = SourceRouter(sourceManager, soul, issueReporter)
        IncognitoRetention.purgeExpired(this)
        if (getSharedPreferences("omnihub_localapi", MODE_PRIVATE).getBoolean("enabled", false)) {
            try { startLocalGateway() } catch (_: Exception) { }
        }
        // Pre-warm the always-usable keyless lane + the user's preferred
        // provider so the FIRST message skips the 15-30s cold page load.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                WarmSessionPool.warm(this, "duckduckgo_ai", "https://duck.ai")
                val preferred = ProviderAuthStore.preferredProvider(this)
                if (preferred != "auto") {
                    sourceManager.get(preferred)?.let { src ->
                        WarmSessionPool.warm(this, src.info.id, src.info.websiteUrl)
                    }
                }
            } catch (_: Exception) {
            }
        }, 1500)
    }

    /** Local OpenAI-compatible endpoint over signed-in sources (OmniRoute mode). */
    fun startLocalGateway(port: Int = LocalGateway.DEFAULT_PORT) {
        if (LocalGateway.running.get()) return
        LocalGateway.lanEnabled = getSharedPreferences("omnihub_localapi", MODE_PRIVATE)
            .getBoolean("lan", false)
        localGateway = LocalGateway(this, port).also { it.startGateway() }
    }

    fun stopLocalGateway() {
        localGateway?.stop()
        localGateway = null
    }

    fun reloadProviders() {
        ProviderBootstrap.reload(this, registry)
        sourceManager.reload()
    }

    companion object {
        lateinit var instance: OmniHubApp
            private set
    }
}
