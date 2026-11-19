package com.dreamfallen.nexo.runtime.scope

/** Lexically nested environment; local writes stay local unless a name already exists in an outer scope. */
class Scope(private val parent: Scope? = null) {
    private val values = HashMap<String, Any?>()
    fun define(name: String, value: Any?) { values[name] = value }
    fun containsLocal(name: String) = values.containsKey(name)
    fun get(name: String): Any? = if (values.containsKey(name)) values[name] else parent?.get(name) ?: throw IllegalArgumentException("Undefined variable '$name'")
    fun assign(name: String, value: Any?) {
        if (values.containsKey(name)) values[name] = value
        else if (parent?.contains(name) == true) parent.assign(name, value)
        else values[name] = value
    }
    fun child() = Scope(this)
    private fun contains(name: String): Boolean = values.containsKey(name) || parent?.contains(name) == true
}
