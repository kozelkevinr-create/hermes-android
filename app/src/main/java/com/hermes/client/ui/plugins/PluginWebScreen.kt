package com.hermes.client.ui.plugins

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.network.GatedAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

/** What the WebView needs to load a dashboard page as the signed-in user. */
data class WebTarget(val url: String, val token: String)

@HiltViewModel
class PluginWebViewModel @Inject constructor(
    private val store: CredentialStore,
    private val gatedAuth: GatedAuth,
) : ViewModel() {
    /**
     * Resolve [path] against the configured dashboard and, for a password-protected dashboard,
     * log in and copy the session cookies into the WebView's [CookieManager]. In token mode the
     * session token is sent as a header on the first request (the dashboard page itself is
     * expected to carry it onward — verify on device).
     */
    suspend fun prepare(path: String): WebTarget? = withContext(Dispatchers.IO) {
        val cfg = store.load() ?: return@withContext null
        val base = cfg.baseUrl.trimEnd('/')
        val url = base + path
        if (cfg.username.isNotBlank()) {
            if (gatedAuth.cookieJar.cookiesFor(base.toHttpUrlOrNull() ?: return@withContext null).isEmpty()) {
                gatedAuth.login()
            }
            val httpUrl = base.toHttpUrlOrNull() ?: return@withContext null
            val cm = CookieManager.getInstance()
            gatedAuth.cookieJar.cookiesFor(httpUrl).forEach { cm.setCookie(base, it.toString()) }
            cm.flush()
        }
        WebTarget(url, cfg.token)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginWebScreen(
    path: String,
    title: String,
    onBack: () -> Unit,
    vm: PluginWebViewModel = hiltViewModel(),
) {
    var target by remember { mutableStateOf<WebTarget?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(path) { target = vm.prepare(path) }

    BackHandler(enabled = web?.canGoBack() == true) { web?.goBack() }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val t = target
            if (t == null) {
                com.hermes.client.ui.components.LoadingState()
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            web = this
                            val headers = if (t.token.isNotBlank())
                                mapOf("X-Hermes-Session-Token" to t.token) else emptyMap()
                            loadUrl(t.url, headers)
                        }
                    },
                    onRelease = { it.destroy(); web = null },
                )
            }
        }
    }
}
