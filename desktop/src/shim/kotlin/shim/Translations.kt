// The phone's translation table (core's com.opentune.i18n.Tr), which loads its
// languages through Android resources. The desktop is in English, so a label is
// itself and only the {0}, {1}… placeholders are filled, the same way the phone
// fills them. See Downloads.kt for why this file sits here.
package com.opentune.i18n

object Tr {
    fun t(english: String): String = english

    /** [template] with `{0}`, `{1}`… replaced by [args] in order. */
    fun f(template: String, vararg args: Any?): String {
        if (args.isEmpty() || '{' !in template) return template
        val out = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val close = template.indexOf('}', i + 1)
                val index = if (close > i + 1) template.substring(i + 1, close).toIntOrNull() else null
                if (index != null && index in args.indices) {
                    out.append(args[index]); i = close + 1; continue
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }
}
