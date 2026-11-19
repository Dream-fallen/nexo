package com.dreamfallen.nexo.runtime.interpreter

import com.dreamfallen.nexo.lang.ast.*
import com.dreamfallen.nexo.runtime.scope.Scope

/** Sandboxed interpreter core. Host APIs are exposed explicitly as globals; no reflection is used. */
class Interpreter(
    globals: Map<String, Any?> = emptyMap(),
    private val instructionLimit: Long = 100_000,
    private val callDepthLimit: Int = 128
) {
    fun interface NativeFunction { fun invoke(positional: List<Any?>, named: Map<String, Any?>): Any? }
    private val global = Scope()
    private var steps = 0L
    private var depth = 0
    private val events = LinkedHashMap<String, MutableList<CallableValue>>()

    init {
        globals.forEach(global::define)
        global.define("require", NativeFunction { p, _ -> require(p.firstOrNull()?.toString()); null })
        global.define("event", NativeFunction { p, _ -> throw IllegalStateException("event() requires a callback as its second argument") })
    }

    fun execute(program: Program) {
        steps = 0
        program.statements.forEach { executeStatement(it, global) }
    }

    /** Invoke callbacks registered by `@event(...)` or `event(name, callback)` with an event object. */
    fun dispatch(eventName: String, event: Any?) {
        events[eventName]?.toList()?.forEach { invoke(it, listOf(event), emptyMap(), SourceLocation(1, 1)) }
    }

    fun registeredEvents(): Set<String> = events.keys.toSet()

    private fun executeStatement(statement: Statement, scope: Scope) {
        tick(statement.location)
        try {
            when (statement) {
                is ExpressionStatement -> evaluate(statement.expression, scope)
                is VariableDeclaration -> scope.define(statement.name, statement.value?.let { evaluate(it, scope) })
                is Assignment -> assign(statement.target, evaluate(statement.value, scope), scope)
                is FunctionDeclaration -> scope.define(statement.name, UserFunction(statement.parameters, statement.body, scope))
                is EventDeclaration -> events.getOrPut(statement.name) { ArrayList() }.add(UserFunction(statement.parameter?.let(::listOf) ?: emptyList(), statement.body, scope))
                is ReturnStatement -> throw ReturnSignal(statement.value?.let { evaluate(it, scope) })
                is IfStatement -> (if (truthy(evaluate(statement.condition, scope))) statement.thenBranch else statement.elseBranch).forEach { executeStatement(it, scope) }
            }
        } catch (e: ScriptException) { throw e }
        catch (e: ReturnSignal) { throw e }
        catch (e: RuntimeException) { throw ScriptException(e.message ?: e.javaClass.simpleName, statement.location) }
    }

    private fun evaluate(expression: Expression, scope: Scope): Any? {
        tick(expression.location)
        return when (expression) {
            is Literal -> expression.value
            is Name -> scope.get(expression.value)
            is Member -> getMember(evaluate(expression.receiver, scope), expression.name)
            is ListLiteral -> expression.elements.map { evaluate(it, scope) }
            is ObjectLiteral -> expression.entries.associateTo(LinkedHashMap()) { it.first to evaluate(it.second, scope) }
            is Unary -> unary(expression.operator, evaluate(expression.operand, scope))
            is Binary -> {
                val left = evaluate(expression.left, scope)
                when (expression.operator) {
                    "and", "&&" -> if (!truthy(left)) left else evaluate(expression.right, scope)
                    "or", "||" -> if (truthy(left)) left else evaluate(expression.right, scope)
                    else -> binary(left, expression.operator, evaluate(expression.right, scope))
                }
            }
            is Lambda -> UserFunction(expression.parameters, expression.body, scope)
            is Call -> {
                val callee = evaluate(expression.callee, scope)
                val positional = ArrayList<Any?>(); val named = LinkedHashMap<String, Any?>()
                expression.arguments.forEach { arg -> val value = evaluate(arg.value, scope); if (arg.name == null) positional += value else named[arg.name] = value }
                invoke(callee, positional, named, expression.location)
            }
        }
    }

    private fun invoke(callee: Any?, positional: List<Any?>, named: Map<String, Any?>, location: SourceLocation): Any? {
        tick(location)
        return when (callee) {
            is NativeFunction -> callee.invoke(positional, named)
            is UserFunction -> {
                if (++depth > callDepthLimit) { depth--; throw ScriptException("Maximum call depth exceeded", location) }
                try {
                    val local = callee.closure.child()
                    callee.parameters.forEachIndexed { index, name -> local.define(name, named[name] ?: positional.getOrNull(index)) }
                    try { callee.body.forEach { executeStatement(it, local) }; null } catch (signal: ReturnSignal) { signal.value }
                } finally { depth-- }
            }
            is BoundMethod -> callee.invoke(positional, named)
            else -> throw ScriptException("Value is not callable", location)
        }
    }

    private fun assign(target: Expression, value: Any?, scope: Scope) {
        when (target) {
            is Name -> scope.assign(target.value, value)
            is Member -> {
                val receiver = evaluate(target.receiver, scope)
                if (receiver is MutableMap<*, *>) { @Suppress("UNCHECKED_CAST") (receiver as MutableMap<String, Any?>)[target.name] = value }
                else throw ScriptException("Cannot assign property '${target.name}'", target.location)
            }
            else -> throw ScriptException("Invalid assignment target", target.location)
        }
    }

    private fun getMember(receiver: Any?, name: String): Any? {
        if (receiver is Map<*, *>) return receiver[name] ?: throw IllegalArgumentException("No property '$name'")
        if (receiver is List<*>) {
            if (name == "length" || name == "size") return receiver.size
            if (name == "get") return BoundMethod { p, _ -> receiver[(p.firstOrNull() as Number).toInt()] }
        }
        if (receiver is String) {
            if (name == "length" || name == "size") return receiver.length
            if (name == "toUpperCase" || name == "upper") return BoundMethod { _, _ -> receiver.uppercase() }
            if (name == "toLowerCase" || name == "lower") return BoundMethod { _, _ -> receiver.lowercase() }
        }
        throw IllegalArgumentException("No property '$name'")
    }

    private fun unary(op: String, value: Any?): Any? = when (op) {
        "!", "not" -> !truthy(value); "-" -> -number(value); "+" -> number(value); else -> throw IllegalArgumentException("Unknown unary operator '$op'")
    }
    private fun binary(a: Any?, op: String, b: Any?): Any? = when (op) {
        "+" -> if (a is String || b is String) a.toString() + b.toString() else number(a) + number(b)
        "-" -> number(a) - number(b); "*" -> number(a) * number(b); "/" -> number(a) / number(b); "%" -> number(a) % number(b); "**" -> Math.pow(number(a), number(b))
        "==", "===" -> a == b; "!=", "!==" -> a != b
        ">" -> compare(a, b) > 0; ">=" -> compare(a, b) >= 0; "<" -> compare(a, b) < 0; "<=" -> compare(a, b) <= 0
        "in" -> when (b) { is String -> a.toString() in b; is Collection<*> -> a in b; is Map<*, *> -> a in b.keys; else -> false }
        else -> throw IllegalArgumentException("Unknown operator '$op'")
    }
    private fun compare(a: Any?, b: Any?): Int = if (a is Number && b is Number) number(a).compareTo(number(b)) else a.toString().compareTo(b.toString())
    private fun number(value: Any?): Double = when (value) { is Number -> value.toDouble(); is String -> value.toDouble(); else -> throw IllegalArgumentException("Expected a number") }
    private fun truthy(value: Any?): Boolean = when (value) { null -> false; is Boolean -> value; is Number -> value.toDouble() != 0.0; is String -> value.isNotEmpty(); else -> true }
    private fun tick(location: SourceLocation) { if (++steps > instructionLimit) throw ScriptException("Instruction limit exceeded", location) }
    private fun require(name: String?) { if (name.isNullOrBlank()) throw IllegalArgumentException("require() expects a module name") }

    private data class UserFunction(val parameters: List<String>, val body: List<Statement>, val closure: Scope)
    private class BoundMethod(val block: (List<Any?>, Map<String, Any?>) -> Any?) { fun invoke(p: List<Any?>, n: Map<String, Any?>) = block(p, n) }
    private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
}
