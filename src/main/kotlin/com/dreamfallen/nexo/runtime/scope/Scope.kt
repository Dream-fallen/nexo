package com.dreamfallen.nexo.runtime.scope

/** Lexically nested environment supporting both JS-style outer mutation and Python-style local binding. */
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

    /** Mutates an existing variable in outer scopes if found, otherwise defines locally (JS behavior). */
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

    /** Always binds in the current local scope unless not in a child scope (Python behavior). */
    fun assignLocal(name: String, value: Any?) {
        values[name] = value
    }

    fun child() = Scope(this)
}
