package io.legado.app.ui.autoTask

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemAutoTaskBinding
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.startActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoTaskAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<AutoTaskRule, ItemAutoTaskBinding>(context) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val selectedIds = linkedSetOf<String>()

    val selection: List<AutoTaskRule>
        get() {
            return getItems().filter { selectedIds.contains(it.id) }
        }

    val diffItemCallBack = object : DiffUtil.ItemCallback<AutoTaskRule>() {
        override fun areItemsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: AutoTaskRule, newItem: AutoTaskRule): Any? {
            val payload = Bundle()
            if (oldItem.name != newItem.name) {
                payload.putBoolean("name", true)
            }
            if (oldItem.enable != newItem.enable) {
                payload.putBoolean("enabled", true)
            }
            if (oldItem.lastRunAt != newItem.lastRunAt ||
                oldItem.lastError != newItem.lastError ||
                oldItem.cron != newItem.cron
            ) {
                payload.putBoolean("summary", true)
            }
            return if (payload.isEmpty) null else payload
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemAutoTaskBinding {
        return ItemAutoTaskBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemAutoTaskBinding,
        item: AutoTaskRule,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            binding.cbTask.text = item.name.ifBlank { item.id }
            binding.swtEnabled.isChecked = item.enable
            binding.titleDesc.text = buildSummary(item)
            binding.cbTask.isChecked = selectedIds.contains(item.id)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as? Bundle ?: continue
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> binding.cbTask.text = item.name.ifBlank { item.id }
                        "enabled" -> binding.swtEnabled.isChecked = item.enable
                        "summary" -> binding.titleDesc.text = buildSummary(item)
                    }
                }
            }
            binding.cbTask.isChecked = selectedIds.contains(item.id)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemAutoTaskBinding) {
        binding.cbTask.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                getItem(holder.layoutPosition)?.let { task ->
                    if (isChecked) {
                        selectedIds.add(task.id)
                    } else {
                        selectedIds.remove(task.id)
                    }
                    callBack.upCountView()
                }
            }
        }
        binding.swtEnabled.setOnCheckedChangeListener { buttonView, isChecked ->
            val item = getItem(holder.layoutPosition) ?: return@setOnCheckedChangeListener
            if (buttonView.isPressed) {
                callBack.toggle(item, isChecked)
            }
        }
        binding.ivEdit.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.edit(it) }
        }
        binding.ivMenuMore.setOnClickListener { view ->
            getItem(holder.layoutPosition)?.let { showMenu(view, it) }
        }
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.edit(it) }
        }
    }

    override fun onCurrentListChanged() {
        val currentIds = getItems().map { it.id }.toHashSet()
        val iterator = selectedIds.iterator()
        while (iterator.hasNext()) {
            if (!currentIds.contains(iterator.next())) {
                iterator.remove()
            }
        }
        callBack.upCountView()
    }

    fun selectAll() {
        getItems().forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    fun revertSelection() {
        getItems().forEach {
            if (selectedIds.contains(it.id)) {
                selectedIds.remove(it.id)
            } else {
                selectedIds.add(it.id)
            }
        }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    private fun buildSummary(task: AutoTaskRule): String {
        val cron = task.cron?.trim().orEmpty().ifBlank { "-" }
        val status = when {
            !task.lastError.isNullOrBlank() ->
                context.getString(R.string.auto_task_last_error, task.lastError)
            task.lastRunAt > 0L ->
                context.getString(
                    R.string.auto_task_last_run,
                    timeFormat.format(Date(task.lastRunAt))
                )
            else -> context.getString(R.string.auto_task_not_run)
        }
        return context.getString(R.string.auto_task_item_summary, cron, status)
    }

    private fun showMenu(view: View, task: AutoTaskRule) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.auto_task_item)
        popupMenu.menu.findItem(R.id.menu_login)?.isVisible = !task.loginUrl.isNullOrBlank()
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_login -> context.startActivity<SourceLoginActivity> {
                    putExtra("type", "autoTask")
                    putExtra("key", task.id)
                }
                R.id.menu_log -> callBack.showLog(task)
                R.id.menu_delete -> callBack.delete(task)
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        fun edit(task: AutoTaskRule)
        fun delete(task: AutoTaskRule)
        fun toggle(task: AutoTaskRule, enabled: Boolean)
        fun upCountView()
        fun showLog(task: AutoTaskRule)
    }
}
