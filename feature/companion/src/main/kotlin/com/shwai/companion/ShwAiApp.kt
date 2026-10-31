package com.shwai.companion

import android.app.Application
import androidx.room.Room
import com.shwai.core.database.ShwAiDatabase
import com.shwai.core.security.KeystoreTokenStore
import com.shwai.core.security.Providers
import com.shwai.core.security.TokenRepository
import com.shwai.feature.keyboard.KeyboardDependencies
import com.shwai.integration.tools.CalendarEventTool
import com.shwai.integration.tools.FileDiscoveryTool
import com.shwai.integration.tools.GmailDraftTool
import com.shwai.integration.tools.MapLinkTool
import com.shwai.integration.tools.ShareSheetTool
import com.shwai.sdk.intelligence.GeminiFlashClient
import com.shwai.sdk.orchestration.AgentEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppGraph(app: Application) {
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    val tokenStore = KeystoreTokenStore(app)
    val tokens = TokenRepository(tokenStore)
    val database = Room.databaseBuilder(app, ShwAiDatabase::class.java, "shwai.db").build()
    private val intelligence = GeminiFlashClient(http, apiKey = { tokenStore.get(Providers.GEMINI_KEY) })
    val engine = AgentEngine(
        intelligence = intelligence,
        tools = listOf(
            FileDiscoveryTool(app),
            CalendarEventTool(app),
            MapLinkTool(),
            GmailDraftTool(app, tokens, http),
            ShareSheetTool(app),
        ),
        audit = database.auditLog(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

class ShwAiApp : Application(), KeyboardDependencies {
    val graph: AppGraph by lazy { AppGraph(this) }
    override val agentEngine: AgentEngine get() = graph.engine
}
