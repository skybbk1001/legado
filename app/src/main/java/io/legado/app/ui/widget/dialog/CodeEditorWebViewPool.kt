package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 保活本地代码编辑器 WebView，避免每次打开弹窗都重新加载整页资源。
 */
object CodeEditorWebViewPool {

    interface Client {
        fun onEditorReady()
        fun onEditorBootError(message: String?)
        fun onEditorSave(text: String)
    }

    private const val EDITOR_URL = "file:///android_asset/web/code-editor/editor.html"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var webView: WebView? = null
    private var currentClient: Client? = null
    private var editorReady = false
    private var bootFailed = false
    private var lastBootError: String? = null

    private val jsBridge = object {
        @JavascriptInterface
        fun onEditorReady() {
            mainHandler.post {
                bootFailed = false
                lastBootError = null
                editorReady = true
                currentClient?.onEditorReady()
            }
        }

        @JavascriptInterface
        fun onEditorBootError(message: String?) {
            mainHandler.post {
                if (editorReady) return@post
                bootFailed = true
                lastBootError = message
                currentClient?.onEditorBootError(message)
            }
        }

        @JavascriptInterface
        fun save(text: String) {
            mainHandler.post {
                currentClient?.onEditorSave(text)
            }
        }
    }

    fun prewarm(context: Context) {
        runOnMain {
            ensureWebView(context)
        }
    }

    fun attach(container: ViewGroup, client: Client): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CodeEditorWebViewPool.attach must run on the main thread"
        }
        val activeClient = currentClient
        if (activeClient != null && activeClient !== client) {
            return false
        }
        val target = ensureWebView(container.context, forceRecreate = bootFailed)
        val parent = target.parent as? ViewGroup
        if (parent != null && parent !== container && currentClient !== client) {
            return false
        }
        currentClient = client
        (target.context as? MutableContextWrapper)?.baseContext = container.context
        if (parent !== container) {
            parent?.removeView(target)
            container.removeAllViews()
            container.addView(
                target,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        when {
            bootFailed -> client.onEditorBootError(lastBootError)
            editorReady -> client.onEditorReady()
        }
        return true
    }

    fun detach(client: Client) {
        runOnMain {
            if (currentClient !== client) return@runOnMain
            val target = webView
            val parent = target?.parent as? ViewGroup
            parent?.removeView(target)
            currentClient = null
            val baseContext = appContext ?: target?.context?.applicationContext
            if (target != null && baseContext != null) {
                (target.context as? MutableContextWrapper)?.baseContext = baseContext
            }
        }
    }

    fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)? = null) {
        runOnMain {
            val target = webView
            if (target == null) {
                resultCallback?.invoke(null)
                return@runOnMain
            }
            target.evaluateJavascript(script, resultCallback)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureWebView(context: Context, forceRecreate: Boolean = false): WebView {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        val existing = webView
        if (existing != null && !forceRecreate) {
            return existing
        }
        if (existing != null) {
            destroyWebView(existing)
        }
        editorReady = false
        bootFailed = false
        lastBootError = null
        return WebView(MutableContextWrapper(applicationContext)).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                textZoom = 100
            }
            setBackgroundColor(Color.TRANSPARENT)
            addJavascriptInterface(jsBridge, "Android")
            loadUrl(EDITOR_URL)
            webView = this
        }
    }

    private fun destroyWebView(target: WebView) {
        (target.parent as? ViewGroup)?.removeView(target)
        target.removeJavascriptInterface("Android")
        target.stopLoading()
        target.loadUrl("about:blank")
        target.clearHistory()
        target.removeAllViews()
        target.destroy()
    }
}
