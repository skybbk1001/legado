package com.script.rhino

import org.htmlunit.corejs.javascript.Scriptable

fun interface JavaObjectWrapFactory {

    fun wrap(scope: Scriptable?, javaObject: Any, staticType: Class<*>?): Scriptable

}
