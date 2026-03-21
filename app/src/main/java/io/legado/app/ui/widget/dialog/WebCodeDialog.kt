package io.legado.app.ui.widget.dialog

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.FragmentManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogWebCodeViewBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class WebCodeDialog() : BaseDialogFragment(R.layout.dialog_web_code_view),
    CodeEditorWebViewPool.Client {

    companion object {
        private const val DIALOG_TAG = "WebCodeDialog"

        fun show(
            manager: FragmentManager,
            code: String,
            requestId: String? = null,
            title: String? = null
        ): Boolean {
            if (manager.isStateSaved || manager.findFragmentByTag(DIALOG_TAG) != null) {
                return false
            }
            WebCodeDialog(code, requestId, title).show(manager, DIALOG_TAG)
            return true
        }
    }

    constructor(code: String, requestId: String? = null, title: String? = null) : this() {
        arguments = Bundle().apply {
            putString("code", code)
            putString("requestId", requestId)
            putString("title", title)
        }
    }

    private val binding by viewBinding(DialogWebCodeViewBinding::bind)
    private var pendingCode: String = ""
    private var encodedCode: String = ""
    private var editorReady = false
    private var bootFailed = false
    private var initialCodeApplied = false
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

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        arguments?.getString("title")?.let {
            binding.toolBar.title = it
        }
        binding.toolBar.inflateMenu(R.menu.code_edit)
        binding.toolBar.menu.applyTint(requireContext())
        val saveItem = binding.toolBar.menu.findItem(R.id.menu_save)
        saveItem?.isEnabled = false
        editorReady = false
        bootFailed = false
        initialCodeApplied = false
        pendingCode = arguments?.getString("code").orEmpty()
        encodedCode = Base64.encodeToString(
            pendingCode.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_save -> {
                    if (editorReady && initialCodeApplied && !bootFailed) {
                        CodeEditorWebViewPool.evaluateJavascript(
                            "window.__save && window.__save();"
                        )
                    }
                    return@setOnMenuItemClickListener true
                }
            }
            true
        }
        updateEditorUiState()
        if (!CodeEditorWebViewPool.attach(binding.webViewContainer, this)) {
            dismissAllowingStateLoss()
        }
    }

    private fun updateEditorUiState() {
        val contentReady = editorReady && initialCodeApplied && !bootFailed
        binding.toolBar.menu.findItem(R.id.menu_save)?.isEnabled = contentReady
        binding.loadingProgress.visibility = if (contentReady || bootFailed) {
            View.GONE
        } else {
            View.VISIBLE
        }
        binding.webViewContainer.alpha = if (contentReady || bootFailed) 1f else 0f
    }

    private fun sendInitialCodeToEditor() {
        if (!editorReady || bootFailed || initialCodeApplied) return
        CodeEditorWebViewPool.evaluateJavascript(
            "window.setCodeFromAndroid && window.setCodeFromAndroid('" + encodedCode + "');",
        ) {
            if (view == null) return@evaluateJavascript
            initialCodeApplied = true
            updateEditorUiState()
        }
    }

    private fun requestClose() {
        if (pendingClose || confirmShown) return
        if (!editorReady || !initialCodeApplied) {
            dismissAllowingStateLoss()
            return
        }
        pendingClose = true
        CodeEditorWebViewPool.evaluateJavascript("window.__getCode && window.__getCode();") { value ->
            pendingClose = false
            if (view == null) return@evaluateJavascript
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
        CodeEditorWebViewPool.detach(this)
        super.onDestroyView()
    }

    override fun onEditorReady() {
        if (view == null) return
        bootFailed = false
        editorReady = true
        updateEditorUiState()
        sendInitialCodeToEditor()
    }

    override fun onEditorBootError(message: String?) {
        if (view == null || editorReady) return
        bootFailed = true
        updateEditorUiState()
    }

    override fun onEditorSave(text: String) {
        if (view == null) return
        if (text == pendingCode) {
            dismissAllowingStateLoss()
            return
        }
        pendingCode = text
        val requestId = arguments?.getString("requestId")
        (parentFragment as? Callback)?.onCodeSave(text, requestId)
            ?: (activity as? Callback)?.onCodeSave(text, requestId)
        dismissAllowingStateLoss()
    }

    interface Callback {
        fun onCodeSave(code: String, requestId: String?)
    }
}
