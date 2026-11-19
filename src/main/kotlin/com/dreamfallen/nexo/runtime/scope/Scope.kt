package com.dreamfallen.nexo.runtime.scope

/** Lexically nested environment; local writes stay local unless a name already exists in an outer scope. */
class Scope(private val parent: Scope? = null) {
    private val values = HashMap<String, Any?>()
    fun define(name: String, value: Any?) { values[name] = value }
    fun containsLocal(name: String) = values.containsKey(name)

    fun get(name: String): Any? {
        var scope: Scope? = this
        while (scope != null) {
            if (scope.values.containsKey(name)) return scope.values[name]
            scope = scope.parent
        }
        throw IllegalArgumentException("Undefined variable '$name'")
    }

    fun assign(name: String, value: Any?) {
        var scope: Scope? = this
        while (scope != null) {
            if (scope.values.containsKey(name)) {
                scope.values[name] = value
                return
            }
            scope = scope.parent
        }
        values[name] = value
    }

    fun child() = Scope(this)
}
