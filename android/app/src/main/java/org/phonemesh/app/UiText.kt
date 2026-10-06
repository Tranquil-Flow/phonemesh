package org.phonemesh.app

import android.content.Context

/** A string resource and its arguments, worded in the chosen language when shown. Arguments may be UiText too. */
data class UiText(val id: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String {
        if (args.isEmpty()) return context.getString(id)
        return context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    }
}
