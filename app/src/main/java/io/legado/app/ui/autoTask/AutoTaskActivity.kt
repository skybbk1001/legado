package io.legado.app.ui.autoTask

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.widget.PopupMenu
import io.legado.app.ui.autoTask.AutoTaskEditActivity
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.AutoTaskRule
import io.legado.app.lib.dialogs.alert
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.ACache
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutoTaskActivity : VMBaseActivity<ActivityAutoTaskBinding, AutoTaskViewModel>(),
    AutoTaskAdapter.CallBack,
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack {

    override val viewModel: AutoTaskViewModel by viewModels()
    override val binding: ActivityAutoTaskBinding by viewBinding(ActivityAutoTaskBinding::inflate)
    private val adapter: AutoTaskAdapter by lazy { AutoTaskAdapter(this, this) }
    private val importRecordKey = "autoTaskRecordKey"
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportAutoTaskDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(uri.toString())
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initSelectActionBar()
        observeData()
        bindImportResult()
        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> startActivity(AutoTaskEditActivity.startIntent(this))
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() = binding.run {
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(VerticalDivider(this@AutoTaskActivity))
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.auto_task_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
        upCountView()
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.rulesFlow.collectLatest {
                adapter.setItems(it, adapter.diffItemCallBack)
                invalidateOptionsMenu()
                upCountView()
            }
        }
    }

    private fun bindImportResult() {
        supportFragmentManager.setFragmentResultListener(
            ImportAutoTaskDialog.RESULT_KEY,
            this
        ) { _, _ ->
            viewModel.refresh()
        }
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportAutoTaskDialog(it))
                }
            }
            cancelButton()
        }
    }

    override fun edit(task: AutoTaskRule) {
        startActivity(AutoTaskEditActivity.startIntent(this, task.id))
    }

    override fun delete(task: AutoTaskRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.auto_task_delete) + "\n" + task.name)
            noButton()
            yesButton { viewModel.delete(task) }
        }
    }

    override fun toggle(task: AutoTaskRule, enabled: Boolean) {
        viewModel.save(task.copy(enable = enabled))
    }

    override fun showLog(task: AutoTaskRule) {
        showDialogFragment(AutoTaskLogDialog(task.id, task.name))
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.itemCount)
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            adapter.selectAll()
        } else {
            adapter.revertSelection()
        }
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        if (adapter.selection.isEmpty()) return
        alert(R.string.draw, R.string.sure_del) {
            yesButton { viewModel.delete(adapter.selection.map { it.id }) }
            noButton()
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.updateEnabled(adapter.selection.map { it.id }, true)
            R.id.menu_disable_selection -> viewModel.updateEnabled(adapter.selection.map { it.id }, false)
            R.id.menu_export_selection -> viewModel.exportSelection(adapter.selection.map { it.id }) { file ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "autoTaskSelection.json",
                        file,
                        "application/json"
                    )
                }
            }
        }
        return true
    }
}
