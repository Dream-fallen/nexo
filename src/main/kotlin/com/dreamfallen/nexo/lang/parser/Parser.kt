package com.dreamfallen.nexo.lang.parser

import com.dreamfallen.nexo.lang.ast.*
import com.dreamfallen.nexo.lang.lexer.Lexer
import com.dreamfallen.nexo.lang.lexer.Token
import com.dreamfallen.nexo.lang.lexer.TokenKind

/** Recursive-descent statement parser and Pratt expression parser for the supported Nexo core. */
class Parser(private val source: String, private val sourceName: String = "<script>") {
    private val tokens = Lexer(source, sourceName).lex()
    private var current = 0
    private lateinit var mode: LanguageMode

    fun parse(): Program {
        val header = source.lineSequence().firstOrNull()?.trim()
        mode = when (header) { "using py" -> LanguageMode.PYTHON; "using js" -> LanguageMode.JAVASCRIPT; else -> throw ScriptException("The first line must be 'using py' or 'using js'", SourceLocation(1, 1)) }
        while (!atEnd() && !checkText("using")) advance()
        if (checkText("using")) { while (!atEnd() && !check(TokenKind.NEWLINE)) advance(); match(TokenKind.NEWLINE) }
        val statements = parseStatements(until = null)
        return Program(statements, mode, sourceName)
    }

    private fun parseStatements(until: String?): List<Statement> {
        val result = ArrayList<Statement>()
        skipNewlines()
        while (!atEnd() && (until == null || !checkText(until)) && !check(TokenKind.DEDENT)) {
            val before = current
            result += parseStatement()
            if (current == before) throw error("Parser made no progress")
            skipNewlines()
        }
        return result
    }

    private fun parseStatement(): Statement {
        if (match(TokenKind.INDENT)) throw error("Unexpected indentation")
        if (matchText("@")) {
            val decorator = parseExpression()
            skipNewlines()
            if (!matchText("def")) throw error("Only @event decorators are currently supported")
            val fn = parseFunction(previous().location)
            val call = decorator as? Call
            if ((call?.callee as? Name)?.value != "event") throw ScriptException("Only @event(...) decorators are supported", decorator.location)
            val eventName = (call.arguments.firstOrNull()?.value as? Literal)?.value as? String ?: throw ScriptException("Event decorator requires a string name", decorator.location)
            return EventDeclaration(eventName, fn.parameters.firstOrNull(), fn.body, decorator.location)
        }
        if (matchText("def") || matchText("function")) return parseFunction(previous().location)
        if (matchText("if")) return parseIf(previous().location)
        if (matchText("return")) {
            val loc = previous().location
            val value = if (check(TokenKind.NEWLINE) || check(TokenKind.EOF) || checkText(";")) null else parseExpression()
            matchText(";"); return ReturnStatement(value, loc)
        }
        if (matchText("let") || matchText("const") || matchText("var")) {
            val loc = previous().location; val name = consume(TokenKind.IDENTIFIER, "Expected variable name")
            val value = if (matchText("=")) parseExpression() else null
            matchText(";"); return VariableDeclaration(name.text, value, loc)
        }
        val loc = peek().location
        val expr = parseExpression()
        if (matchText("=")) { val value = parseExpression(); matchText(";"); return Assignment(expr, value, loc) }
        if (matchText("+=", "-=", "*=", "/=")) { val op = previous().text.dropLast(1); val value = parseExpression(); matchText(";"); return Assignment(expr, Binary(expr, op, value, loc), loc) }
        matchText(";")
        return ExpressionStatement(expr, loc)
    }

    private fun parseFunction(loc: SourceLocation): FunctionDeclaration {
        val name = consume(TokenKind.IDENTIFIER, "Expected function name").text
        consumeText("(", "Expected '('")
        val params = parseParameters()
        consumeText(")", "Expected ')'")
        return FunctionDeclaration(name, params, parseBlock(), loc)
    }

    private fun parseParameters(): List<String> {
        val result = ArrayList<String>()
        if (!checkText(")")) do { result += consume(TokenKind.IDENTIFIER, "Expected parameter name").text } while (matchText(",") && !checkText(")"))
        return result
    }

    private fun parseBlock(): List<Statement> {
        if (matchText("{")) {
            val body = parseStatements("}")
            consumeText("}", "Expected '}' after block")
            return body
        }
        consumeText(":", "Expected ':' or '{' before block")
        consume(TokenKind.NEWLINE, "Expected newline after ':'")
        skipNewlines()
        consume(TokenKind.INDENT, "Expected an indented block")
        val body = parseStatements(null)
        consume(TokenKind.DEDENT, "Expected end of indented block")
        return body
    }

    private fun parseIf(loc: SourceLocation): Statement {
        val condition = parseExpression()
        val thenBranch = parseBlock()
        skipNewlines()
        val elseBranch = if (matchText("else")) parseBlock() else emptyList()
        return IfStatement(condition, thenBranch, elseBranch, loc)
    }

    private fun parseExpression(minBinding: Int = 0): Expression {
        var left = parsePrefix()
        while (true) {
            if (checkText(".")) { if (10 < minBinding) break; advance(); val name = consume(TokenKind.IDENTIFIER, "Expected member name"); left = Member(left, name.text, name.location); continue }
            if (checkText("(")) { if (10 < minBinding) break; left = parseCall(left); continue }
            val op = peek().text.lowercase()
            val binding = bindingPower(op) ?: break
            if (binding.first < minBinding) break
            advance()
            val right = parseExpression(binding.second)
            left = Binary(left, op, right, left.location)
        }
        return left
    }

    private fun parsePrefix(): Expression {
        val token = advance()
        when (token.kind) {
            TokenKind.NUMBER -> return Literal(token.text.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong() else it } ?: 0L, token.location)
            TokenKind.STRING -> return Literal(token.text, token.location)
            TokenKind.IDENTIFIER -> when (token.text) {
                "true" -> return Literal(true, token.location); "false" -> return Literal(false, token.location); "null", "None", "undefined" -> return Literal(null, token.location)
                "not" -> return Unary("not", parseExpression(8), token.location)
                else -> return Name(token.text, token.location)
            }
            TokenKind.SYMBOL -> when (token.text) {
                "!", "-", "+" -> return Unary(token.text, parseExpression(8), token.location)
                "(" -> {
                    val saved = current
                    val parameters = ArrayList<String>()
                    var lambdaCandidate = true
                    if (!checkText(")")) {
                        while (true) { if (!check(TokenKind.IDENTIFIER)) { lambdaCandidate = false; break }; parameters += advance().text; if (!matchText(",")) break }
                    }
                    if (lambdaCandidate && matchText(")") && matchText("=>")) return Lambda(parameters, parseArrowBody(), token.location)
                    current = saved
                    val expression = parseExpression(); consumeText(")", "Expected ')'"); return expression
                }
                "[" -> {
                    val values = ArrayList<Expression>(); if (!checkText("]")) do { values += parseExpression() } while (matchText(",") && !checkText("]"))
                    consumeText("]", "Expected ']'"); return ListLiteral(values, token.location)
                }
                "{" -> {
                    val values = ArrayList<Pair<String, Expression>>()
                    if (!checkText("}")) do { val key = advance(); if (key.kind !in setOf(TokenKind.IDENTIFIER, TokenKind.STRING)) throw ScriptException("Expected object key", key.location); consumeText(":", "Expected ':' after object key"); values += key.text to parseExpression() } while (matchText(",") && !checkText("}"))
                    consumeText("}", "Expected '}'"); return ObjectLiteral(values, token.location)
                }
            }
            else -> Unit
        }
        throw ScriptException("Unexpected '${token.text}'", token.location)
    }

    private fun parseArrowBody(): List<Statement> {
        if (matchText("{")) { val body = parseStatements("}"); consumeText("}", "Expected '}' after arrow body"); return body }
        return listOf(ReturnStatement(parseExpression(), previous().location))
    }

    private fun parseCall(callee: Expression): Expression {
        val loc = advance().location; val args = ArrayList<CallArgument>()
        if (!checkText(")")) do {
            if (peek().kind == TokenKind.IDENTIFIER && peek(1).text == "=") { val name = advance().text; advance(); args += CallArgument(parseExpression(), name) }
            else args += CallArgument(parseExpression())
        } while (matchText(",") && !checkText(")"))
        consumeText(")", "Expected ')' after arguments")
        return Call(callee, args, loc)
    }

    private fun bindingPower(operator: String): Pair<Int, Int>? = when (operator) {
        "or", "||" -> 1 to 2; "and", "&&" -> 2 to 3
        "==", "===", "!=", "!==", "<", ">", "<=", ">=", "in" -> 3 to 4
        "+", "-" -> 4 to 5; "*", "/", "%" -> 5 to 6; "**" -> 6 to 6
        else -> null
    }

    private fun skipNewlines() { while (match(TokenKind.NEWLINE) || matchText(";")) Unit }
    private fun atEnd() = check(TokenKind.EOF)
    private fun check(kind: TokenKind) = peek().kind == kind
    private fun checkText(text: String) = peek().text == text
    private fun match(kind: TokenKind) = if (check(kind)) { advance(); true } else false
    private fun matchText(vararg texts: String) = if (peek().text in texts) { advance(); true } else false
    private fun advance(): Token { if (!atEnd()) current++; return tokens[current - 1] }
    private fun peek(distance: Int = 0) = tokens[(current + distance).coerceAtMost(tokens.lastIndex)]
    private fun previous() = tokens[(current - 1).coerceAtLeast(0)]
    private fun consume(kind: TokenKind, message: String): Token = if (check(kind)) advance() else throw error(message)
    private fun consumeText(text: String, message: String): Token = if (checkText(text)) advance() else throw error(message)
    private fun error(message: String) = ScriptException(message, peek().location)
}
