package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.webkit.WebView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogWebCodeViewBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class WebCodeDialog() : BaseDialogFragment(R.layout.dialog_web_code_view) {

    constructor(code: String, requestId: String? = null, title: String? = null) : this() {
        arguments = Bundle().apply {
            putString("code", code)
            putString("requestId", requestId)
            putString("title", title)
        }
    }

    private val binding by viewBinding(DialogWebCodeViewBinding::bind)
    private var pendingCode: String = ""
    private var pageReady = false
    private var pendingClose = false
    private var confirmShown = false

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                requestClose()
                true
            } else {
                false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        arguments?.getString("title")?.let {
            binding.toolBar.title = it
        }
        binding.toolBar.inflateMenu(R.menu.code_edit)
        binding.toolBar.menu.applyTint(requireContext())
        val saveItem = binding.toolBar.menu.findItem(R.id.menu_save)
        saveItem?.isEnabled = false
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_save -> {
                    if (pageReady) {
                        binding.webView.evaluateJavascript("window.__save && window.__save();", null)
                    }
                    return@setOnMenuItemClickListener true
                }
            }
            true
        }
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            textZoom = 100
        }
        binding.webView.addJavascriptInterface(JsBridge(), "Android")
        pendingCode = arguments?.getString("code").orEmpty()
        val encoded = Base64.encodeToString(
            pendingCode.orEmpty().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                saveItem?.isEnabled = true
                view?.evaluateJavascript(
                    "window.setCodeFromAndroid && window.setCodeFromAndroid('$encoded');",
                    null
                )
            }
        }
        binding.webView.loadUrl("file:///android_asset/web/code-editor/editor.html")
    }

    private fun requestClose() {
        if (pendingClose || confirmShown) return
        if (!pageReady) {
            dismissAllowingStateLoss()
            return
        }
        pendingClose = true
        binding.webView.evaluateJavascript("window.__getCode && window.__getCode();") { value ->
            pendingClose = false
            val current = decodeJsString(value)
            if (current == null || current == pendingCode) {
                dismissAllowingStateLoss()
                return@evaluateJavascript
            }
            confirmShown = true
            alert(R.string.exit, R.string.exit_no_save) {
                positiveButton(R.string.yes) {
                    confirmShown = false
                }
                negativeButton(R.string.no) {
                    confirmShown = false
                    dismissAllowingStateLoss()
                }
                onDismiss {
                    confirmShown = false
                }
            }
        }
    }

    private fun decodeJsString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return try {
            org.json.JSONArray("[$value]").getString(0)
        } catch (e: Exception) {
            value
        }
    }

    override fun onDestroyView() {
        binding.webView.apply {
            removeJavascriptInterface("Android")
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroyView()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun save(text: String) {
            binding.root.post {
                if (text == pendingCode) {
                    dismissAllowingStateLoss()
                    return@post
                }
                pendingCode = text
                val requestId = arguments?.getString("requestId")
                (parentFragment as? Callback)?.onCodeSave(text, requestId)
                    ?: (activity as? Callback)?.onCodeSave(text, requestId)
                dismissAllowingStateLoss()
            }
        }
    }

    interface Callback {
        fun onCodeSave(code: String, requestId: String?)
    }
}
