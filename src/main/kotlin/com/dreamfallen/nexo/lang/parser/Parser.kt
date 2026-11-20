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
        mode = when (header) {
            "using py" -> LanguageMode.PYTHON
            "using js" -> LanguageMode.JAVASCRIPT
            else -> throw ScriptException("The first line must be 'using py' or 'using js'", SourceLocation(1, 1))
        }

        while (!atEnd() && !checkText("using")) advance()
        if (checkText("using")) {
            while (!atEnd() && !check(TokenKind.NEWLINE)) advance()
            match(TokenKind.NEWLINE)
        }

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

        if (matchText("require")) return parseRequire(previous().location)
        if (matchText("@")) return parseDecorator()

        if (matchText("def")) {
            requirePython("'def' is only valid in Python mode; use 'function' in JavaScript mode")
            return parseFunction(previous().location)
        }
        if (matchText("function")) {
            requireJavaScript("'function' is only valid in JavaScript mode; use 'def' in Python mode")
            return parseFunction(previous().location)
        }

        if (matchText("if")) return parseIf(previous().location)
        if (matchText("while")) return parseWhile(previous().location)
        if (matchText("for")) return parseFor(previous().location)
        if (matchText("try")) return parseTryCatch(previous().location)

        if (matchText("break")) {
            val loc = previous().location
            matchText(";")
            return BreakStatement(loc)
        }
        if (matchText("continue")) {
            val loc = previous().location
            matchText(";")
            return ContinueStatement(loc)
        }
        if (matchText("return")) {
            val loc = previous().location
            val value = if (check(TokenKind.NEWLINE) || check(TokenKind.EOF) || checkText(";")) null else parseExpression()
            matchText(";")
            return ReturnStatement(value, loc)
        }

        if (matchText("let", "const", "var")) {
            requireJavaScript("Variable keywords ('let', 'const', 'var') are only valid in JavaScript mode")
            val loc = previous().location
            val name = consume(TokenKind.IDENTIFIER, "Expected variable name")
            val value = if (matchText("=")) parseExpression() else null
            matchText(";")
            return VariableDeclaration(name.text, value, loc)
        }

        val loc = peek().location
        val expr = parseExpression()
        if (matchText("=")) {
            val value = parseExpression()
            matchText(";")
            return Assignment(expr, value, loc)
        }
        if (matchText("+=", "-=", "*=", "/=", "%=", "**=")) {
            val op = previous().text.dropLast(1)
            val value = parseExpression()
            matchText(";")
            return Assignment(expr, Binary(expr, op, value, loc), loc)
        }

        matchText(";")
        return ExpressionStatement(expr, loc)
    }

    private fun parseRequire(loc: SourceLocation): RequireStatement {
        consumeText("(", "Expected '(' after 'require'")
        val modToken = advance()
        val moduleName = when (modToken.kind) {
            TokenKind.IDENTIFIER, TokenKind.STRING -> modToken.text
            else -> throw ScriptException("Expected module name in require()", modToken.location)
        }
        consumeText(")", "Expected ')' after module name")

        var alias: String? = null
        var priority: Int? = null

        while (matchText("with")) {
            when {
                matchText("alias") -> {
                    if (priority != null) throw error("Cannot combine 'with alias' and 'with priority'")
                    if (alias != null) throw error("Duplicate 'with alias' clause")
                    alias = consume(TokenKind.IDENTIFIER, "Expected alias identifier").text
                }
                matchText("priority") -> {
                    if (alias != null) throw error("Cannot combine 'with alias' and 'with priority'")
                    if (priority != null) throw error("Duplicate 'with priority' clause")
                    val numTok = consume(TokenKind.NUMBER, "Expected priority number (1-5)")
                    val p = numTok.text.toIntOrNull() ?: throw error("Priority must be an integer between 1 and 5")
                    if (p !in 1..5) throw error("Priority must be between 1 (highest) and 5 (lowest)")
                    priority = p
                }
                else -> throw error("Expected 'alias' or 'priority' after 'with'")
            }
        }

        matchText(";")
        return RequireStatement(moduleName, alias, priority, loc)
    }

    private fun parseDecorator(): Statement {
        val decorator = parseExpression()
        skipNewlines()
        val isFn = if (mode == LanguageMode.PYTHON) matchText("def") else matchText("function")
        if (!isFn) {
            val expected = if (mode == LanguageMode.PYTHON) "'def'" else "'function'"
            throw error("Decorators must be followed by $expected")
        }

        val fn = parseFunction(previous().location)
        val call = decorator as? Call
            ?: throw ScriptException("Decorator must be a call expression", decorator.location)
        val decoratorName = (call.callee as? Name)?.value
            ?: throw ScriptException("Invalid decorator target", decorator.location)

        return when (decoratorName) {
            "event" -> {
                val eventName = (call.arguments.firstOrNull()?.value as? Literal)?.value as? String
                    ?: throw ScriptException("@event requires a string event name", decorator.location)
                EventDeclaration(eventName, fn.parameters.firstOrNull()?.name, fn.body, decorator.location)
            }
            "command" -> {
                val commandName = (call.arguments.firstOrNull()?.value as? Literal)?.value as? String
                    ?: throw ScriptException("@command requires a string command name", decorator.location)
                val options = call.arguments.drop(1)
                    .filter { it.name != null }
                    .associate { it.name!! to it.value }
                CommandDeclaration(commandName, options, fn.parameters, fn.body, decorator.location)
            }
            "every" -> {
                val interval = call.arguments.firstOrNull()?.value
                    ?: throw ScriptException("@every requires an interval argument", decorator.location)
                ScheduleDeclaration(interval, fn.body, decorator.location)
            }
            else -> throw ScriptException("Unsupported decorator '@$decoratorName'", decorator.location)
        }
    }

    private fun parseFunction(loc: SourceLocation): FunctionDeclaration {
        val name = consume(TokenKind.IDENTIFIER, "Expected function name").text
        consumeText("(", "Expected '('")
        val params = parseParameters()
        consumeText(")", "Expected ')'")
        return FunctionDeclaration(name, params, parseBlock(), loc)
    }

    private fun parseParameters(): List<Parameter> {
        val result = ArrayList<Parameter>()
        if (!checkText(")")) {
            do {
                val name = consume(TokenKind.IDENTIFIER, "Expected parameter name").text
                val defaultVal = if (matchText("=")) parseExpression() else null
                result += Parameter(name, defaultVal)
            } while (matchText(",") && !checkText(")"))
        }
        return result
    }

    private fun parseBlock(): List<Statement> {
        if (mode == LanguageMode.JAVASCRIPT) {
            consumeText("{", "Expected '{' before block in JavaScript mode")
            val body = parseStatements("}")
            consumeText("}", "Expected '}' after block")
            return body
        }

        consumeText(":", "Expected ':' before block in Python mode")
        if (!check(TokenKind.NEWLINE) && !check(TokenKind.EOF)) {
            val single = parseStatement()
            return listOf(single)
        }
        consume(TokenKind.NEWLINE, "Expected newline after ':'")
        skipNewlines()
        consume(TokenKind.INDENT, "Expected an indented block")
        val body = parseStatements(null)
        consume(TokenKind.DEDENT, "Expected end of indented block")
        return body
    }

    private fun parseIf(loc: SourceLocation): Statement {
        val condition = parseIfCondition()
        val thenBranch = parseBlock()
        skipNewlines()
        val elseBranch = when {
            mode == LanguageMode.PYTHON && matchText("elif") -> listOf(parseIf(previous().location))
            matchText("else") -> {
                if (mode == LanguageMode.JAVASCRIPT && matchText("if")) {
                    listOf(parseIf(previous().location))
                } else {
                    parseBlock()
                }
            }
            else -> emptyList()
        }
        return IfStatement(condition, thenBranch, elseBranch, loc)
    }

    private fun parseWhile(loc: SourceLocation): Statement {
        val condition = parseIfCondition()
        val body = parseBlock()
        return WhileStatement(condition, body, loc)
    }

    private fun parseFor(loc: SourceLocation): Statement {
        if (mode == LanguageMode.JAVASCRIPT) {
            consumeText("(", "Expected '(' after 'for' in JavaScript mode")
            matchText("let", "const", "var")
            val variable = consume(TokenKind.IDENTIFIER, "Expected loop variable name").text
            if (!matchText("of") && !matchText("in")) throw error("Expected 'of' or 'in' in JavaScript for loop")
            val iterable = parseExpression()
            consumeText(")", "Expected ')' after for-loop header")
            return ForStatement(variable, iterable, parseBlock(), loc)
        }
        val variable = consume(TokenKind.IDENTIFIER, "Expected loop variable name").text
        consumeText("in", "Expected 'in' after loop variable")
        val iterable = parseExpression()
        return ForStatement(variable, iterable, parseBlock(), loc)
    }

    private fun parseTryCatch(loc: SourceLocation): Statement {
        val tryBranch = parseBlock()
        skipNewlines()
        if (mode == LanguageMode.JAVASCRIPT) {
            consumeText("catch", "Expected 'catch' after 'try' block")
            var catchVar: String? = null
            if (matchText("(")) {
                catchVar = consume(TokenKind.IDENTIFIER, "Expected exception variable name").text
                consumeText(")", "Expected ')' after catch variable")
            }
            val catchBranch = parseBlock()
            return TryCatchStatement(tryBranch, catchVar, catchBranch, loc)
        }
        consumeText("except", "Expected 'except' after 'try' block")
        var catchVar: String? = null
        if (!checkText(":")) {
            val first = consume(TokenKind.IDENTIFIER, "Expected exception type or variable").text
            catchVar = if (matchText("as")) consume(TokenKind.IDENTIFIER, "Expected exception alias after 'as'").text else first
        }
        val catchBranch = parseBlock()
        return TryCatchStatement(tryBranch, catchVar, catchBranch, loc)
    }

    private fun parseIfCondition(): Expression {
        if (mode == LanguageMode.JAVASCRIPT && checkText("(")) {
            advance()
            val cond = parseExpression()
            consumeText(")", "Expected ')' after condition")
            return cond
        }
        return parseExpression()
    }

    private fun parseExpression(minBinding: Int = 0): Expression {
        var left = parsePrefix()
        while (true) {
            if (checkText(".")) {
                if (10 < minBinding) break
                advance()
                val name = consume(TokenKind.IDENTIFIER, "Expected member name")
                left = Member(left, name.text, name.location)
                continue
            }
            if (checkText("[")) {
                if (10 < minBinding) break
                val loc = advance().location
                val index = parseExpression()
                consumeText("]", "Expected ']' after index")
                left = IndexAccess(left, index, loc)
                continue
            }
            if (checkText("(")) {
                if (10 < minBinding) break
                left = parseCall(left)
                continue
            }
            if (checkText("++") || checkText("--")) {
                if (10 < minBinding) break
                val op = advance().text
                left = Unary("post$op", left, left.location)
                continue
            }
            if (mode == LanguageMode.JAVASCRIPT && checkText("?")) {
                if (1 < minBinding) break
                advance()
                val thenExpr = parseExpression()
                consumeText(":", "Expected ':' in ternary expression")
                val elseExpr = parseExpression(1)
                left = Ternary(left, thenExpr, elseExpr, left.location)
                continue
            }
            if (mode == LanguageMode.PYTHON && checkText("if")) {
                if (1 < minBinding) break
                advance()
                val cond = parseExpression()
                consumeText("else", "Expected 'else' in Python conditional expression")
                val elseExpr = parseExpression(1)
                left = Ternary(cond, left, elseExpr, left.location)
                continue
            }
            if (checkText("not") && peek(1).text == "in") {
                if (3 < minBinding) break
                advance()
                advance()
                val right = parseExpression(4)
                left = Binary(left, "not in", right, left.location)
                continue
            }
            if (checkText("is") && peek(1).text == "not") {
                if (3 < minBinding) break
                advance()
                advance()
                val right = parseExpression(4)
                left = Binary(left, "is not", right, left.location)
                continue
            }
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
            TokenKind.NUMBER -> return Literal(
                token.text.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong() else it } ?: 0L,
                token.location
            )
            TokenKind.STRING -> return Literal(token.text, token.location)
            TokenKind.F_STRING -> return parseInterpolatedString(token.text, isTemplateLiteral = false, token.location)
            TokenKind.TEMPLATE_STRING -> return parseInterpolatedString(token.text, isTemplateLiteral = true, token.location)
            TokenKind.IDENTIFIER -> when (token.text) {
                "true", "True" -> return Literal(true, token.location)
                "false", "False" -> return Literal(false, token.location)
                "null", "None", "undefined" -> return Literal(null, token.location)
                "not" -> return Unary("not", parseExpression(8), token.location)
                "lambda" -> {
                    requirePython("'lambda' is only valid in Python mode; use '=>' in JavaScript mode")
                    val params = ArrayList<Parameter>()
                    if (!checkText(":")) {
                        do {
                            val name = consume(TokenKind.IDENTIFIER, "Expected lambda parameter").text
                            val defaultVal = if (matchText("=")) parseExpression() else null
                            params += Parameter(name, defaultVal)
                        } while (matchText(","))
                    }
                    consumeText(":", "Expected ':' after lambda parameters")
                    return Lambda(params, listOf(ReturnStatement(parseExpression(), token.location)), token.location)
                }
                else -> {
                    if (mode == LanguageMode.JAVASCRIPT && matchText("=>")) {
                        return Lambda(listOf(Parameter(token.text)), parseArrowBody(), token.location)
                    }
                    return Name(token.text, token.location)
                }
            }
            TokenKind.SYMBOL -> when (token.text) {
                "!", "-", "+", "++", "--" -> return Unary(token.text, parseExpression(8), token.location)
                "(" -> {
                    val saved = current
                    val parameters = ArrayList<Parameter>()
                    var lambdaCandidate = true
                    if (!checkText(")")) {
                        while (true) {
                            if (!check(TokenKind.IDENTIFIER)) {
                                lambdaCandidate = false
                                break
                            }
                            val pName = advance().text
                            val pDefault = if (matchText("=")) parseExpression() else null
                            parameters += Parameter(pName, pDefault)
                            if (!matchText(",")) break
                        }
                    }
                    if (lambdaCandidate && matchText(")") && matchText("=>")) {
                        requireJavaScript("Arrow functions '=>' are only valid in JavaScript mode")
                        return Lambda(parameters, parseArrowBody(), token.location)
                    }
                    current = saved
                    val expression = parseExpression()
                    consumeText(")", "Expected ')'")
                    return expression
                }
                "[" -> {
                    val values = ArrayList<Expression>()
                    if (!checkText("]")) {
                        do {
                            values += parseExpression()
                        } while (matchText(",") && !checkText("]"))
                    }
                    consumeText("]", "Expected ']' ")
                    return ListLiteral(values, token.location)
                }
                "{" -> {
                    val values = ArrayList<Pair<String, Expression>>()
                    if (!checkText("}")) {
                        do {
                            val key = advance()
                            if (key.kind !in setOf(TokenKind.IDENTIFIER, TokenKind.STRING)) {
                                throw ScriptException("Expected object key", key.location)
                            }
                            consumeText(":", "Expected ':' after object key")
                            values += key.text to parseExpression()
                        } while (matchText(",") && !checkText("}"))
                    }
                    consumeText("}", "Expected '}'")
                    return ObjectLiteral(values, token.location)
                }
            }
            else -> Unit
        }
        throw ScriptException("Unexpected '${token.text}'", token.location)
    }

    private fun parseInterpolatedString(raw: String, isTemplateLiteral: Boolean, loc: SourceLocation): Expression {
        val parts = ArrayList<Expression>()
        val openMarker = if (isTemplateLiteral) "\${" else "{"
        var i = 0
        val literalBuf = StringBuilder()
        while (i < raw.length) {
            if (raw.startsWith(openMarker, i)) {
                if (literalBuf.isNotEmpty()) {
                    parts += Literal(literalBuf.toString(), loc)
                    literalBuf.clear()
                }
                i += openMarker.length
                val exprStart = i
                var depth = 1
                var inQuote: Char? = null
                while (i < raw.length && depth > 0) {
                    val ch = raw[i]
                    if (inQuote != null) {
                        if (ch == '\\' && i + 1 < raw.length) {
                            i += 2
                            continue
                        }
                        if (ch == inQuote) inQuote = null
                    } else {
                        if (ch == '\'' || ch == '"') inQuote = ch
                        else if (ch == '{') depth++
                        else if (ch == '}') depth--
                    }
                    if (depth > 0) i++
                }
                if (depth != 0) throw ScriptException("Unclosed interpolation in string", loc)
                val innerSource = raw.substring(exprStart, i)
                val header = if (mode == LanguageMode.PYTHON) "using py\n" else "using js\n"
                val innerProg = Parser(header + innerSource, sourceName).parse()
                val exprStmt = innerProg.statements.firstOrNull() as? ExpressionStatement
                    ?: throw ScriptException("Invalid expression inside string interpolation", loc)
                parts += exprStmt.expression
                i++
            } else {
                literalBuf.append(raw[i])
                i++
            }
        }
        if (literalBuf.isNotEmpty()) parts += Literal(literalBuf.toString(), loc)
        if (parts.isEmpty()) return Literal("", loc)
        if (parts.size == 1 && parts[0] is Literal) return parts[0]
        return InterpolatedString(parts, loc)
    }

    private fun parseArrowBody(): List<Statement> {
        if (matchText("{")) {
            val body = parseStatements("}")
            consumeText("}", "Expected '}' after arrow body")
            return body
        }
        return listOf(ReturnStatement(parseExpression(), previous().location))
    }

    private fun parseCall(callee: Expression): Expression {
        val loc = advance().location
        val args = ArrayList<CallArgument>()
        if (!checkText(")")) {
            do {
                if (peek().kind == TokenKind.IDENTIFIER && peek(1).text == "=") {
                    val name = advance().text
                    advance()
                    args += CallArgument(parseExpression(), name)
                } else {
                    args += CallArgument(parseExpression())
                }
            } while (matchText(",") && !checkText(")"))
        }
        consumeText(")", "Expected ')' after arguments")
        return Call(callee, args, loc)
    }

    private fun bindingPower(operator: String): Pair<Int, Int>? = when (operator) {
        "or", "||" -> 1 to 2
        "and", "&&" -> 2 to 3
        "==", "===", "!=", "!==", "<", ">", "<=", ">=", "in", "is" -> 3 to 4
        "+", "-" -> 4 to 5
        "*", "/", "%" -> 5 to 6
        "**" -> 6 to 6
        else -> null
    }

    private fun requirePython(message: String) {
        if (mode != LanguageMode.PYTHON) throw error(message)
    }

    private fun requireJavaScript(message: String) {
        if (mode != LanguageMode.JAVASCRIPT) throw error(message)
    }

    private fun skipNewlines() {
        while (match(TokenKind.NEWLINE) || matchText(";")) Unit
    }

    private fun atEnd() = check(TokenKind.EOF)
    private fun check(kind: TokenKind) = peek().kind == kind
    private fun checkText(text: String) = peek().text == text
    private fun match(kind: TokenKind) = if (check(kind)) { advance(); true } else false
    private fun matchText(vararg texts: String) = if (peek().text in texts) { advance(); true } else false
    private fun advance(): Token {
        if (!atEnd()) current++
        return tokens[current - 1]
    }
    private fun peek(distance: Int = 0) = tokens[(current + distance).coerceAtMost(tokens.lastIndex)]
    private fun previous() = tokens[(current - 1).coerceAtLeast(0)]
    private fun consume(kind: TokenKind, message: String): Token = if (check(kind)) advance() else throw error(message)
    private fun consumeText(text: String, message: String): Token = if (checkText(text)) advance() else throw error(message)
    private fun error(message: String) = ScriptException(message, peek().location)
}
