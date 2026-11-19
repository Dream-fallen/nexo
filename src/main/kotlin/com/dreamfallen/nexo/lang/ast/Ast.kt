package com.dreamfallen.nexo.lang.ast

/** Source position is one-based to make diagnostics directly usable in editors. */
data class SourceLocation(val line: Int, val column: Int)

data class Program(val statements: List<Statement>, val mode: LanguageMode, val sourceName: String)
enum class LanguageMode { PYTHON, JAVASCRIPT }

data class ScriptException(message: String, val location: SourceLocation? = null) : RuntimeException(
    if (location == null) message else "$message (${location.line}:${location.column})"
)

sealed interface Statement { val location: SourceLocation }
data class ExpressionStatement(val expression: Expression, override val location: SourceLocation) : Statement
data class VariableDeclaration(val name: String, val value: Expression?, override val location: SourceLocation) : Statement
data class Assignment(val target: Expression, val value: Expression, override val location: SourceLocation) : Statement
data class FunctionDeclaration(val name: String, val parameters: List<String>, val body: List<Statement>, override val location: SourceLocation) : Statement
data class EventDeclaration(val name: String, val parameter: String?, val body: List<Statement>, override val location: SourceLocation) : Statement
data class ReturnStatement(val value: Expression?, override val location: SourceLocation) : Statement
data class IfStatement(val condition: Expression, val thenBranch: List<Statement>, val elseBranch: List<Statement>, override val location: SourceLocation) : Statement

data class Parameter(val name: String, val defaultValue: Expression? = null)
sealed interface Expression { val location: SourceLocation }
data class Literal(val value: Any?, override val location: SourceLocation) : Expression
data class Name(val value: String, override val location: SourceLocation) : Expression
data class Member(val receiver: Expression, val name: String, override val location: SourceLocation) : Expression
data class Call(val callee: Expression, val arguments: List<CallArgument>, override val location: SourceLocation) : Expression
data class CallArgument(val value: Expression, val name: String? = null)
data class Unary(val operator: String, val operand: Expression, override val location: SourceLocation) : Expression
data class Binary(val left: Expression, val operator: String, val right: Expression, override val location: SourceLocation) : Expression
data class Lambda(val parameters: List<String>, val body: List<Statement>, override val location: SourceLocation) : Expression
data class ListLiteral(val elements: List<Expression>, override val location: SourceLocation) : Expression
data class ObjectLiteral(val entries: List<Pair<String, Expression>>, override val location: SourceLocation) : Expression
