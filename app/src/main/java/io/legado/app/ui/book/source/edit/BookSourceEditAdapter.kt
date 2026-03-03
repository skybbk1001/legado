package io.legado.app.ui.book.source.edit

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemSourceEditWebBinding
import io.legado.app.databinding.ViewCodeEditFieldBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.code.bindCodeEditField
import io.legado.app.ui.widget.text.EditEntity

class BookSourceEditAdapter(
    private val onUnsafeTextEdit: ((EditEntity) -> Unit)? = null
) : RecyclerView.Adapter<BookSourceEditAdapter.MyViewHolder>() {

    val editEntityMaxLine = AppConfig.sourceEditMaxLine
    private val unsafePreviewLines = 6

    var editEntities: ArrayList<EditEntity> = ArrayList()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val binding = ItemSourceEditWebBinding
            .inflate(LayoutInflater.from(parent.context), parent, false)
        val editText = binding.root.bindCodeEditField(R.id.codeField).editText
        editText.addLegadoPattern()
        editText.addJsonPattern()
        editText.addJsPattern()
        return MyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.bind(editEntities[position])
    }

    override fun getItemCount(): Int {
        return editEntities.size
    }

    inner class MyViewHolder(val binding: ItemSourceEditWebBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val codeFieldBinding: ViewCodeEditFieldBinding =
            binding.root.bindCodeEditField(R.id.codeField)

        fun bind(editEntity: EditEntity) = with(codeFieldBinding) {
            val rawText = editEntity.value.orEmpty()
            val isUnsafeText = isCombiningHeavy(rawText)
            editText.setTag(R.id.tag, editEntity.key)
            editText.maxLines = if (isUnsafeText) unsafePreviewLines else editEntityMaxLine
            if (editText.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        if (isUnsafeText) {
                            editText.isCursorVisible = false
                            editText.isFocusable = false
                            editText.isFocusableInTouchMode = false
                        } else {
                            editText.isCursorVisible = false
                            editText.isCursorVisible = true
                            editText.isFocusable = true
                            editText.isFocusableInTouchMode = true
                        }
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
            editText.setText(
                if (isUnsafeText) {
                    itemView.context.getString(R.string.combining_text_placeholder)
                } else {
                    rawText
                }
            )
            textInputLayout.hint = editEntity.hint
            btnWebEdit.imageTintList = ColorStateList.valueOf(
                ThemeStore.accentColor(itemView.context)
            )
            btnWebEdit.setOnClickListener {
                onUnsafeTextEdit?.invoke(editEntity)
            }
            if (isUnsafeText) {
                editText.isCursorVisible = false
                editText.isFocusable = false
                editText.isFocusableInTouchMode = false
                editText.setOnClickListener(null)
                editText.setOnLongClickListener(null)
            } else {
                editText.isCursorVisible = true
                editText.isFocusable = true
                editText.isFocusableInTouchMode = true
                editText.setOnClickListener(null)
                editText.setOnLongClickListener(null)
                editText.onFocusChangeListener = null
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

    private fun isCombiningHeavy(text: String): Boolean {
        if (text.isEmpty()) return false
        var combiningCount = 0
        var inspected = 0
        var run = 0
        var maxRun = 0
        val limit = minOf(text.length, 4000)
        for (i in 0 until limit) {
            val ch = text[i]
            val type = Character.getType(ch)
            val isCombining = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (isCombining) {
                combiningCount++
                run++
                if (run > maxRun) maxRun = run
            } else {
                run = 0
            }
            inspected++
        }
        if (maxRun >= 8) return true
        if (combiningCount >= 64) return true
        return combiningCount * 5 >= inspected
    }


}
