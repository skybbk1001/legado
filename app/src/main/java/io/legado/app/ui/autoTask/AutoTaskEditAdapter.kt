package io.legado.app.ui.autoTask

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemAutoTaskGroupBinding
import io.legado.app.databinding.ItemSourceEditWebBinding
import io.legado.app.databinding.ViewCodeEditFieldBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.code.bindCodeEditField
import io.legado.app.ui.widget.text.EditEntity

class AutoTaskEditAdapter(
    private val onWebEdit: ((EditEntity) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class Section(
        val key: String,
        val title: String,
        var expanded: Boolean = true,
        val fields: List<EditEntity>
    )

    private sealed class Item {
        data class Header(val section: Section) : Item()
        data class Field(val entity: EditEntity) : Item()
    }

    private val editEntityMaxLine = AppConfig.sourceEditMaxLine
    private val sections = arrayListOf<Section>()
    private val items = arrayListOf<Item>()

    fun setSections(list: List<Section>) {
        sections.clear()
        sections.addAll(list)
        rebuildItems()
        notifyDataSetChanged()
    }

    fun notifyEntityUpdated(entity: EditEntity) {
        val index = items.indexOfFirst { it is Item.Field && it.entity === entity }
        if (index >= 0) {
            notifyItemChanged(index)
        } else {
            notifyDataSetChanged()
        }
    }

    private fun rebuildItems() {
        items.clear()
        sections.forEach { section ->
            items.add(Item.Header(section))
            if (section.expanded) {
                section.fields.forEach { field ->
                    items.add(Item.Field(field))
                }
            }
        }
    }

    private fun toggleSection(key: String) {
        sections.firstOrNull { it.key == key }?.let {
            it.expanded = !it.expanded
            rebuildItems()
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is Item.Header -> R.layout.item_auto_task_group
            is Item.Field -> R.layout.item_source_edit_web
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            R.layout.item_auto_task_group -> GroupHolder(
                ItemAutoTaskGroupBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> {
                val binding = ItemSourceEditWebBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                val editText = binding.root.bindCodeEditField(R.id.codeField).editText
                editText.addLegadoPattern()
                editText.addJsonPattern()
                editText.addJsPattern()
                FieldHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as GroupHolder).bind(item.section)
            is Item.Field -> (holder as FieldHolder).bind(item.entity)
        }
    }

    inner class GroupHolder(
        private val binding: ItemAutoTaskGroupBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: Section) = binding.run {
            tvTitle.text = section.title
            ivArrow.rotation = if (section.expanded) 0f else -90f
            root.setOnClickListener { toggleSection(section.key) }
        }
    }

    inner class FieldHolder(
        private val binding: ItemSourceEditWebBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val codeFieldBinding: ViewCodeEditFieldBinding =
            binding.root.bindCodeEditField(R.id.codeField)

        fun bind(editEntity: EditEntity) = with(codeFieldBinding) {
            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = editEntityMaxLine
            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        editText.isCursorVisible = false
                        editText.isCursorVisible = true
                        editText.isFocusable = true
                        editText.isFocusableInTouchMode = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                    }
                }
                editText.addOnAttachStateChangeListener(listener)
                editText.setTag(R.id.tag1, listener)
            }
            editText.getTag(R.id.tag2)?.let {
                if (it is TextWatcher) {
                    editText.removeTextChangedListener(it)
                }
            }
            editText.setText(editEntity.value)
            textInputLayout.hint = editEntity.hint
            btnWebEdit.imageTintList = ColorStateList.valueOf(
                ThemeStore.accentColor(itemView.context)
            )
            btnWebEdit.setOnClickListener {
                onWebEdit?.invoke(editEntity)
            }
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                }

                override fun afterTextChanged(s: Editable?) {
                    editEntity.value = (s?.toString())
                }
            }
            editText.addTextChangedListener(textWatcher)
            editText.setTag(R.id.tag2, textWatcher)
        }
    }
}
