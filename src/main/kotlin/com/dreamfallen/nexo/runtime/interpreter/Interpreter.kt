package com.dreamfallen.nexo.runtime.interpreter

import com.dreamfallen.nexo.lang.ast.*
import com.dreamfallen.nexo.runtime.scope.Scope

/** Sandboxed interpreter core. Host APIs are exposed explicitly as globals; no reflection is used. */
class Interpreter(
    globals: Map<String, Any?> = emptyMap(),
    private val instructionLimit: Long = 100_000,
    private val callDepthLimit: Int = 128
) {
    sealed interface CallableValue

    fun interface NativeFunction : CallableValue {
        fun invoke(positional: List<Any?>, named: Map<String, Any?>): Any?
    }

    data class RegisteredCommand(
        val name: String,
        val options: Map<String, Any?>,
        val handler: CallableValue
    )

    data class RegisteredSchedule(
        val interval: Any?,
        val handler: CallableValue
    )

    private val global = Scope()
    private var steps = 0L
    private var depth = 0
    private val events = LinkedHashMap<String, MutableList<CallableValue>>()
    private val commands = LinkedHashMap<String, RegisteredCommand>()
    private val schedules = ArrayList<RegisteredSchedule>()

    init {
        globals.forEach(global::define)
        global.define("require", NativeFunction { p, _ -> require(p.firstOrNull()?.toString()); null })
        global.define("event", NativeFunction { p, _ ->
            val name = p.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("event() requires an event name")
            val callback = p.getOrNull(1) as? CallableValue
                ?: throw IllegalArgumentException("event() requires a callable second argument")
            events.getOrPut(name) { ArrayList() }.add(callback)
            null
        })
    }

    fun execute(program: Program) {
        steps = 0
        program.statements.forEach { executeStatement(it, global) }
    }

    /** Invoke callbacks registered by `@event(...)` or `event(name, callback)` with an event object. */
    fun dispatch(eventName: String, event: Any?) {
        events[eventName]?.toList()?.forEach {
            invoke(it, listOf(event), emptyMap(), SourceLocation(1, 1))
        }
    }

    /** Invoke a command registered via `@command(...)`. */
    fun dispatchCommand(commandName: String, sender: Any?, args: List<Any?> = emptyList()): Any? {
        val cmd = commands[commandName] ?: throw IllegalArgumentException("Unknown command '$commandName'")
        return invoke(cmd.handler, listOf(sender) + args, emptyMap(), SourceLocation(1, 1))
    }

    fun registeredEvents(): Set<String> = events.keys.toSet()
    fun registeredCommands(): Map<String, RegisteredCommand> = commands.toMap()
    fun registeredSchedules(): List<RegisteredSchedule> = schedules.toList()

    private fun executeStatement(statement: Statement, scope: Scope) {
        tick(statement.location)
        try {
            when (statement) {
                is ExpressionStatement -> evaluate(statement.expression, scope)
                is VariableDeclaration -> scope.define(statement.name, statement.value?.let { evaluate(it, scope) })
                is Assignment -> assign(statement.target, evaluate(statement.value, scope), scope)
                is FunctionDeclaration -> scope.define(statement.name, UserFunction(statement.parameters, statement.body, scope))
                is EventDeclaration -> {
                    val params = statement.parameter?.let { listOf(Parameter(it)) } ?: emptyList()
                    events.getOrPut(statement.name) { ArrayList() }.add(UserFunction(params, statement.body, scope))
                }
                is CommandDeclaration -> {
                    val evaluatedOptions = statement.options.mapValues { (_, expr) -> evaluate(expr, scope) }
                    val handler = UserFunction(statement.parameters, statement.body, scope)
                    commands[statement.name] = RegisteredCommand(statement.name, evaluatedOptions, handler)
                }
                is ScheduleDeclaration -> {
                    val intervalValue = evaluate(statement.interval, scope)
                    val handler = UserFunction(emptyList(), statement.body, scope)
                    schedules += RegisteredSchedule(intervalValue, handler)
                }
                is ReturnStatement -> throw ReturnSignal(statement.value?.let { evaluate(it, scope) })
                is BreakStatement -> throw BreakSignal()
                is ContinueStatement -> throw ContinueSignal()
                is IfStatement -> {
                    val branch = if (truthy(evaluate(statement.condition, scope))) {
                        statement.thenBranch
                    } else {
                        statement.elseBranch
                    }
                    branch.forEach { executeStatement(it, scope) }
                }
                is WhileStatement -> {
                    while (truthy(evaluate(statement.condition, scope))) {
                        tick(statement.location)
                        try {
                            statement.body.forEach { executeStatement(it, scope) }
                        } catch (_: ContinueSignal) {
                            continue
                        } catch (_: BreakSignal) {
                            break
                        }
                    }
                }
                is ForStatement -> {
                    val iterableObj = evaluate(statement.iterable, scope)
                    val items: Iterable<Any?> = when (iterableObj) {
                        is Iterable<*> -> iterableObj
                        is Map<*, *> -> iterableObj.keys
                        is String -> iterableObj.map { it.toString() }
                        is Array<*> -> iterableObj.toList()
                        else -> throw ScriptException("Object is not iterable", statement.iterable.location)
                    }
                    val loopScope = scope.child()
                    for (item in items) {
                        tick(statement.location)
                        loopScope.define(statement.variable, item)
                        try {
                            statement.body.forEach { executeStatement(it, loopScope) }
                        } catch (_: ContinueSignal) {
                            continue
                        } catch (_: BreakSignal) {
                            break
                        }
                    }
                }
                is TryCatchStatement -> {
                    try {
                        statement.tryBranch.forEach { executeStatement(it, scope) }
                    } catch (signal: ReturnSignal) {
                        throw signal
                    } catch (signal: BreakSignal) {
                        throw signal
                    } catch (signal: ContinueSignal) {
                        throw signal
                    } catch (e: RuntimeException) {
                        val catchScope = scope.child()
                        if (statement.catchVariable != null) {
                            catchScope.define(statement.catchVariable, e.message ?: e.javaClass.simpleName)
                        }
                        statement.catchBranch.forEach { executeStatement(it, catchScope) }
                    }
                }
            }
        } catch (e: ScriptException) {
            throw e
        } catch (e: ReturnSignal) {
            throw e
        } catch (e: BreakSignal) {
            throw e
        } catch (e: ContinueSignal) {
            throw e
        } catch (e: RuntimeException) {
            throw ScriptException(e.message ?: e.javaClass.simpleName, statement.location)
        }
    }

    private fun evaluate(expression: Expression, scope: Scope): Any? {
        tick(expression.location)
        return when (expression) {
            is Literal -> expression.value
            is InterpolatedString -> expression.parts.joinToString("") {
                evaluate(it, scope)?.toString() ?: "null"
            }
            is Name -> scope.get(expression.value)
            is Member -> getMember(evaluate(expression.receiver, scope), expression.name)
            is IndexAccess -> getIndex(
                evaluate(expression.receiver, scope),
                evaluate(expression.index, scope),
                expression.location
            )
            is ListLiteral -> expression.elements.mapTo(ArrayList()) { evaluate(it, scope) }
            is ObjectLiteral -> expression.entries.associateTo(LinkedHashMap()) { it.first to evaluate(it.second, scope) }
            is Unary -> evaluateUnary(expression, scope)
            is Binary -> {
                val left = evaluate(expression.left, scope)
                when (expression.operator) {
                    "and", "&&" -> if (!truthy(left)) left else evaluate(expression.right, scope)
                    "or", "||" -> if (truthy(left)) left else evaluate(expression.right, scope)
                    else -> binary(left, expression.operator, evaluate(expression.right, scope))
                }
            }
            is Ternary -> {
                val cond = truthy(evaluate(expression.condition, scope))
                evaluate(if (cond) expression.thenExpr else expression.elseExpr, scope)
            }
            is Lambda -> UserFunction(expression.parameters, expression.body, scope)
            is Call -> {
                val callee = evaluate(expression.callee, scope)
                val positional = ArrayList<Any?>()
                val named = LinkedHashMap<String, Any?>()
                expression.arguments.forEach { arg ->
                    val value = evaluate(arg.value, scope)
                    if (arg.name == null) positional += value else named[arg.name] = value
                }
                invoke(callee, positional, named, expression.location)
            }
        }
    }

    private fun evaluateUnary(expr: Unary, scope: Scope): Any? {
        if (expr.operator in setOf("++", "--", "post++", "post--")) {
            val isPost = expr.operator.startsWith("post")
            val delta = if (expr.operator.endsWith("++")) 1.0 else -1.0
            val currentVal = number(evaluate(expr.operand, scope))
            val updated = if (currentVal % 1.0 == 0.0) {
                (currentVal + delta).toLong()
            } else {
                currentVal + delta
            }
            assign(expr.operand, updated, scope)
            return if (isPost) {
                if (currentVal % 1.0 == 0.0) currentVal.toLong() else currentVal
            } else {
                updated
            }
        }

        val value = evaluate(expr.operand, scope)
        return when (expr.operator) {
            "!", "not" -> !truthy(value)
            "-" -> {
                val n = number(value)
                if (n % 1.0 == 0.0) -n.toLong() else -n
            }
            "+" -> number(value)
            else -> throw IllegalArgumentException("Unknown unary operator '${expr.operator}'")
        }
    }

    private fun invoke(
        callee: Any?,
        positional: List<Any?>,
        named: Map<String, Any?>,
        location: SourceLocation
    ): Any? {
        tick(location)
        return when (callee) {
            is NativeFunction -> callee.invoke(positional, named)
            is UserFunction -> {
                if (++depth > callDepthLimit) {
                    depth--
                    throw ScriptException("Maximum call depth exceeded", location)
                }
                try {
                    val local = callee.closure.child()
                    callee.parameters.forEachIndexed { index, param ->
                        val provided = when {
                            named.containsKey(param.name) -> named[param.name]
                            index < positional.size -> positional[index]
                            param.defaultValue != null -> evaluate(param.defaultValue, local)
                            else -> null
                        }
                        local.define(param.name, provided)
                    }
                    try {
                        callee.body.forEach { executeStatement(it, local) }
                        null
                    } catch (signal: ReturnSignal) {
                        signal.value
                    }
                } finally {
                    depth--
                }
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
                if (receiver is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    (receiver as MutableMap<String, Any?>)[target.name] = value
                } else {
                    throw ScriptException("Cannot assign property '${target.name}'", target.location)
                }
            }
            is IndexAccess -> {
                val receiver = evaluate(target.receiver, scope)
                val index = evaluate(target.index, scope)
                when (receiver) {
                    is MutableList<*> -> {
                        val idx = (index as? Number)?.toInt()
                            ?: throw ScriptException("List index must be a number", target.location)
                        val resolved = if (idx < 0) receiver.size + idx else idx
                        if (resolved !in receiver.indices) {
                            throw ScriptException("List index $idx out of bounds", target.location)
                        }
                        @Suppress("UNCHECKED_CAST")
                        (receiver as MutableList<Any?>)[resolved] = value
                    }
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        (receiver as MutableMap<Any?, Any?>)[index] = value
                    }
                    else -> throw ScriptException("Target does not support index assignment", target.location)
                }
            }
            else -> throw ScriptException("Invalid assignment target", target.location)
        }
    }

    private fun getIndex(receiver: Any?, index: Any?, location: SourceLocation): Any? = when (receiver) {
        is List<*> -> {
            val idx = (index as? Number)?.toInt()
                ?: throw ScriptException("List index must be a number", location)
            val resolved = if (idx < 0) receiver.size + idx else idx
            receiver.getOrElse(resolved) { throw ScriptException("List index $idx out of bounds", location) }
        }
        is Map<*, *> -> receiver[index]
        is String -> {
            val idx = (index as? Number)?.toInt()
                ?: throw ScriptException("String index must be a number", location)
            val resolved = if (idx < 0) receiver.length + idx else idx
            if (resolved !in receiver.indices) throw ScriptException("String index $idx out of bounds", location)
            receiver[resolved].toString()
        }
        else -> throw ScriptException("Value cannot be indexed", location)
    }

    private fun getMember(receiver: Any?, name: String): Any? {
        if (receiver is Map<*, *>) {
            if (name == "keys") return BoundMethod { _, _ -> receiver.keys.toList() }
            if (name == "values") return BoundMethod { _, _ -> receiver.values.toList() }
            if (name == "containsKey" || name == "has") {
                return BoundMethod { p, _ -> receiver.containsKey(p.firstOrNull()) }
            }
            return if (receiver.containsKey(name)) receiver[name] else throw IllegalArgumentException("No property '$name'")
        }
        if (receiver is List<*>) {
            if (name == "length" || name == "size") return receiver.size
            if (name == "get") return BoundMethod { p, _ -> receiver[(p.firstOrNull() as Number).toInt()] }
            if (name == "append" || name == "push" || name == "add") return BoundMethod { p, _ ->
                @Suppress("UNCHECKED_CAST")
                (receiver as MutableList<Any?>).add(p.firstOrNull())
                receiver.size
            }
            if (name == "pop") return BoundMethod { _, _ ->
                @Suppress("UNCHECKED_CAST")
                (receiver as MutableList<Any?>).removeLastOrNull()
            }
        }
        if (receiver is String) {
            if (name == "length" || name == "size") return receiver.length
            if (name == "toUpperCase" || name == "upper") return BoundMethod { _, _ -> receiver.uppercase() }
            if (name == "toLowerCase" || name == "lower") return BoundMethod { _, _ -> receiver.lowercase() }
            if (name == "trim" || name == "strip") return BoundMethod { _, _ -> receiver.trim() }
            if (name == "split") return BoundMethod { p, _ -> receiver.split(p.firstOrNull()?.toString() ?: " ") }
            if (name == "contains") return BoundMethod { p, _ -> receiver.contains(p.firstOrNull()?.toString() ?: "") }
        }
        throw IllegalArgumentException("No property '$name'")
    }

    private fun binary(a: Any?, op: String, b: Any?): Any? = when (op) {
        "+" -> if (a is String || b is String) a.toString() + b.toString() else simplifyNumber(number(a) + number(b))
        "-" -> simplifyNumber(number(a) - number(b))
        "*" -> simplifyNumber(number(a) * number(b))
        "/" -> simplifyNumber(number(a) / number(b))
        "%" -> simplifyNumber(number(a) % number(b))
        "**" -> simplifyNumber(Math.pow(number(a), number(b)))
        "==", "===", "is" -> a == b
        "!=", "!==", "is not" -> a != b
        ">" -> compare(a, b) > 0
        ">=" -> compare(a, b) >= 0
        "<" -> compare(a, b) < 0
        "<=" -> compare(a, b) <= 0
        "in" -> containsValue(a, b)
        "not in" -> !containsValue(a, b)
        else -> throw IllegalArgumentException("Unknown operator '$op'")
    }

    private fun containsValue(needle: Any?, haystack: Any?): Boolean = when (haystack) {
        is String -> needle.toString() in haystack
        is Collection<*> -> needle in haystack
        is Map<*, *> -> needle in haystack.keys
        else -> false
    }

    private fun simplifyNumber(value: Double): Number =
        if (value % 1.0 == 0.0 && value in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) value.toLong() else value

    private fun compare(a: Any?, b: Any?): Int =
        if (a is Number && b is Number) number(a).compareTo(number(b)) else a.toString().compareTo(b.toString())

    private fun number(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDouble()
        else -> throw IllegalArgumentException("Expected a number")
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    private fun tick(location: SourceLocation) {
        if (++steps > instructionLimit) throw ScriptException("Instruction limit exceeded", location)
    }

    private fun require(name: String?) {
        if (name.isNullOrBlank()) throw IllegalArgumentException("require() expects a module name")
    }

    private data class UserFunction(
        val parameters: List<Parameter>,
        val body: List<Statement>,
        val closure: Scope
    ) : CallableValue

    private class BoundMethod(val block: (List<Any?>, Map<String, Any?>) -> Any?) : CallableValue {
        fun invoke(p: List<Any?>, n: Map<String, Any?>) = block(p, n)
    }

    private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
    private class BreakSignal : RuntimeException(null, null, false, false)
    private class ContinueSignal : RuntimeException(null, null, false, false)
}
