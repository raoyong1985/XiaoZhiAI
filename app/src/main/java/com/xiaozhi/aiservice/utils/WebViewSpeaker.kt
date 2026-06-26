package com.xiaozhi.aiservice.utils

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.*

/**
 * WebView 语音播报 — 适用于系统没有 TTS 引擎的设备。
 * WebView 的 Web Speech API 自带合成引擎，不依赖系统 TTS。
 */
object WebViewSpeaker {

    private var webView: WebView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) {
        if (webView != null) return
        webView = WebView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            loadDataWithBaseURL(null, HTML, "text/html", "UTF-8", null)
        }
    }

    fun speak(text: String) {
        val safeText = text.replace("'", "\\'").replace("\n", " ")
        webView?.post {
            webView?.evaluateJavascript("say('$safeText')", null)
        }
    }

    fun release() {
        scope.cancel()
        webView?.destroy()
        webView = null
    }

    private val HTML = """
<!DOCTYPE html><html><body><script>
var u = new SpeechSynthesisUtterance();
u.lang = 'zh-CN';
u.rate = 1.0;
function say(t) { u.text = t; window.speechSynthesis.cancel(); window.speechSynthesis.speak(u); }
</script></body></html>
""".trimIndent()
}
