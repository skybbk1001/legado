package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskEditBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.recycler.NoChildScrollLinearLayoutManager
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.imeHeight
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AutoTaskEditActivity :
    VMBaseActivity<ActivityAutoTaskEditBinding, AutoTaskEditViewModel>(),
    KeyboardToolPop.CallBack {

    companion object {
        fun startIntent(context: Context, id: String? = null): Intent {
            return Intent(context, AutoTaskEditActivity::class.java).apply {
                if (!id.isNullOrBlank()) {
                    putExtra("id", id)
                }
            }
        }
    }

    override val binding by viewBinding(ActivityAutoTaskEditBinding::inflate)
    override val viewModel by viewModels<AutoTaskEditViewModel>()

    private val adapter = AutoTaskEditAdapter()
    private val fieldMap = linkedMapOf<String, EditEntity>()
    private var task: AutoTaskRule? = null
    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            task = it
            upView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        val loginUrl = getFieldValue("loginUrl")
        menu.findItem(R.id.menu_login)?.let {
            it.isVisible = true
            it.isEnabled = loginUrl.isNotBlank()
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> {
                val rule = buildTask() ?: return true
                viewModel.save(rule) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
            R.id.menu_debug_task -> {
                val rule = buildTask() ?: return true
                viewModel.save(rule) {
                    startActivity(AutoTaskDebugActivity.startIntent(this, rule.id))
                }
            }
            R.id.menu_login -> openLogin()
            R.id.menu_help -> showHelpDialog()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = NoChildScrollLinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    private fun upView(rule: AutoTaskRule) {
        binding.cbEnable.isChecked = rule.enable
        binding.cbCookie.isChecked = rule.enabledCookieJar
        fieldMap.clear()
        addField("name", rule.name, R.string.name)
        addField("cron", rule.cron?.ifBlank { AutoTask.DEFAULT_CRON }, R.string.auto_task_cron)
        addField("comment", rule.comment, R.string.auto_task_comment)
        addField("script", rule.script, R.string.auto_task_script)
        addField("header", rule.header, R.string.auto_task_header)
        addField("jsLib", rule.jsLib, R.string.auto_task_jslib)
        addField("concurrentRate", rule.concurrentRate, R.string.auto_task_concurrent_rate)
        addField("loginUrl", rule.loginUrl, R.string.login_url)
        addField("loginUi", rule.loginUi, R.string.login_ui)
        addField("loginCheckJs", rule.loginCheckJs, R.string.login_check_js)
        adapter.setSections(
            listOf(
                AutoTaskEditAdapter.Section(
                    "basic",
                    getString(R.string.auto_task_group_basic),
                    true,
                    listOf(fieldMap.getValue("name"), fieldMap.getValue("cron"))
                ),
                AutoTaskEditAdapter.Section(
                    "script",
                    getString(R.string.auto_task_group_script),
                    true,
                    listOf(fieldMap.getValue("comment"), fieldMap.getValue("script"))
                ),
                AutoTaskEditAdapter.Section(
                    "request",
                    getString(R.string.auto_task_group_request),
                    true,
                    listOf(
                        fieldMap.getValue("header"),
                        fieldMap.getValue("jsLib"),
                        fieldMap.getValue("concurrentRate")
                    )
                ),
                AutoTaskEditAdapter.Section(
                    "login",
                    getString(R.string.auto_task_group_login),
                    true,
                    listOf(
                        fieldMap.getValue("loginUrl"),
                        fieldMap.getValue("loginUi"),
                        fieldMap.getValue("loginCheckJs")
                    )
                )
            )
        )
    }

    private fun addField(key: String, value: String?, hintRes: Int) {
        fieldMap[key] = EditEntity(key, value, hintRes)
    }

    private fun getFieldValue(key: String): String {
        return fieldMap[key]?.value?.trim().orEmpty()
    }

    private fun buildTask(): AutoTaskRule? {
        val name = getFieldValue("name")
        if (name.isBlank()) {
            toastOnUi(getString(R.string.auto_task_name_required))
            return null
        }
        val cron = getFieldValue("cron").ifBlank { AutoTask.DEFAULT_CRON }
        if (CronSchedule.parse(cron) == null) {
            toastOnUi(getString(R.string.auto_task_cron_invalid))
            return null
        }
        val script = getFieldValue("script")
        if (script.isBlank()) {
            toastOnUi(getString(R.string.auto_task_script_empty))
            return null
        }
        val rule = task ?: AutoTaskRule()
        rule.name = name
        rule.cron = cron
        rule.comment = getFieldValue("comment").ifBlank { null }
        rule.script = script
        rule.header = getFieldValue("header").ifBlank { null }
        rule.jsLib = getFieldValue("jsLib").ifBlank { null }
        rule.concurrentRate = getFieldValue("concurrentRate").ifBlank { null }
        rule.loginUrl = getFieldValue("loginUrl").ifBlank { null }
        rule.loginUi = getFieldValue("loginUi").ifBlank { null }
        rule.loginCheckJs = getFieldValue("loginCheckJs").ifBlank { null }
        rule.enable = binding.cbEnable.isChecked
        rule.enabledCookieJar = binding.cbCookie.isChecked
        task = rule
        return rule
    }

    private fun openLogin() {
        val rule = buildTask() ?: return
        val loginUrl = rule.loginUrl.orEmpty()
        if (loginUrl.isBlank()) {
            toastOnUi(getString(R.string.source_no_login))
            return
        }
        viewModel.save(rule) {
            startActivity<SourceLoginActivity> {
                putExtra("type", "autoTask")
                putExtra("key", rule.id)
            }
        }
    }

    private fun showHelpDialog() {
        showHelp("autoTaskHelp")
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf()
    }

    override fun onHelpActionSelect(action: String) {
    }

    override fun sendText(text: String) {
        if (text.isBlank()) return
        val view = window?.decorView?.findFocus()
        if (view is EditText) {
            val start = view.selectionStart
            val end = view.selectionEnd
            val edit = view.editableText
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else if (start > end) {
                edit.replace(end, start, text)
            } else {
                edit.replace(start, end, text)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }
}
