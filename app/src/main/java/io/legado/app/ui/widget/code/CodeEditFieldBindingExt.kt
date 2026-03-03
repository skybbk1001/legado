package io.legado.app.ui.widget.code

import android.view.View
import androidx.annotation.IdRes
import io.legado.app.databinding.ViewCodeEditFieldBinding

fun View.bindCodeEditField(@IdRes fieldId: Int): ViewCodeEditFieldBinding {
    val fieldRoot = requireNotNull(findViewById<View>(fieldId)) {
        "Code edit field root not found: fieldId=$fieldId, parent=${javaClass.name}"
    }
    return ViewCodeEditFieldBinding.bind(fieldRoot)
}
