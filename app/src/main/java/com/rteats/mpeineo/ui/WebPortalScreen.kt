package com.rteats.mpeineo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebPortalScreen(
    url: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    var webView by remember(url) { mutableStateOf<WebView?>(null) }
    var canGoBack by remember(url) { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    Box(
        modifier = modifier.testTag(testTag),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)

                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        cookies.setAcceptThirdPartyCookies(this, true)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url
                            if (target.scheme == "http" || target.scheme == "https") {
                                return false
                            }

                            return runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, target))
                                true
                            }.getOrDefault(true)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            canGoBack = view.canGoBack()
                        }
                    }

                    loadUrl(url)
                    webView = this
                }
            },
            update = { view ->
                webView = view
                canGoBack = view.canGoBack()
            },
            onRelease = { view ->
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.destroy()
                if (webView === view) {
                    webView = null
                    canGoBack = false
                }
            },
        )
    }
}
