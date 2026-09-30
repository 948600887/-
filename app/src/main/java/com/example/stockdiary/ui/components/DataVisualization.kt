package com.example.stockdiary.ui.components

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DataVisualization(
    htmlFile: String,
    modifier: Modifier = Modifier,
    data: Any? = null
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            WebView(it).apply {
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                
                loadUrl("file:///android_asset/$htmlFile")
            }
        },
        update = { webView ->
            data?.let {
                val jsonData = com.google.gson.Gson().toJson(it)
                val script = "javascript:window.postMessage({type: 'updateData', data: $jsonData}, '*')"
                webView.evaluateJavascript(script, null)
            }
        }
    )
}
