package com.dreamfallen.nexo.runtime.interpreter

/** Holds built-in Nexo addon modules (skbee, skquery, skellett, skrayfall, tuske) without reflection. */
class ModuleRegistry {
    data class NexoModule(
        val name: String,
        val exports: Map<String, Any?>
    )

    private val modules = LinkedHashMap<String, NexoModule>()

    fun register(module: NexoModule) {
        modules[module.name.lowercase()] = module
    }

    fun get(name: String): NexoModule? = modules[name.lowercase()]
    fun registeredNames(): Set<String> = modules.keys.toSet()
}
