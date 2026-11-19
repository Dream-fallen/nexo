package com.dreamfallen.nexo.lang.lexer

import com.dreamfallen.nexo.lang.ast.ScriptException
import com.dreamfallen.nexo.lang.ast.SourceLocation

internal enum class TokenKind { IDENTIFIER, NUMBER, STRING, F_STRING, TEMPLATE_STRING, SYMBOL, NEWLINE, INDENT, DEDENT, EOF }
internal data class Token(val kind: TokenKind, val text: String, val location: SourceLocation)

/** Single-pass lexer. Python-mode indentation is emitted as INDENT/DEDENT tokens. */
internal class Lexer(private val source: String, private val sourceName: String = "<script>") {
    private val tokens = ArrayList<Token>(source.length / 3)
    private val indents = ArrayDeque<Int>().apply { addLast(0) }
    private val python = source.lineSequence().firstOrNull()?.trim() == "using py"
    private var offset = 0
    private var line = 1
    private var column = 1
    private var atLineStart = true
    private var nesting = 0

    fun lex(): List<Token> {
        while (offset < source.length) {
            if (atLineStart && nesting == 0) scanIndent()
            if (offset >= source.length) break
            val c = source[offset]
            when {
                c == '\r' -> advance()
                c == '\\' && (peek(1) == '\n' || (peek(1) == '\r' && peek(2) == '\n')) -> {
                    advance()
                    if (source[offset] == '\r') advance()
                    if (offset < source.length && source[offset] == '\n') advance()
                    atLineStart = false
                }
                c == '\n' -> {
                    val loc = location()
                    advance()
                    if (nesting == 0) add(TokenKind.NEWLINE, "\n", loc)
                    atLineStart = true
                }
                c.isWhitespace() -> advance()
                c == '#' || (c == '/' && peek(1) == '/') -> skipLineComment()
                c == '/' && peek(1) == '*' -> skipBlockComment()
                c == '\'' || c == '"' || c == '`' -> scanString(c, isFString = false)
                c.isDigit() -> scanNumber()
                c.isLetter() || c == '_' || c == '$' -> scanIdentifier()
                else -> scanSymbol()
            }
        }
        val end = location()
        if (tokens.lastOrNull()?.kind != TokenKind.NEWLINE) add(TokenKind.NEWLINE, "\n", end)
        while (indents.size > 1) { indents.removeLast(); add(TokenKind.DEDENT, "", end) }
        add(TokenKind.EOF, "", end)
        return tokens
    }

    private fun scanIndent() {
        val start = offset
        var width = 0
        while (offset < source.length && (source[offset] == ' ' || source[offset] == '\t')) {
            if (source[offset] == '\t') throw ScriptException("Tabs are not supported for indentation in $sourceName", location())
            width++; advance()
        }
        if (offset >= source.length || source[offset] == '\n' || source[offset] == '\r' || source[offset] == '#' || source.startsWith("//", offset)) {
            atLineStart = false
            return
        }
        atLineStart = false
        if (!python) return
        val current = indents.last()
        if (width > current) {
            indents.addLast(width)
            add(TokenKind.INDENT, source.substring(start, offset), SourceLocation(line, 1))
        } else if (width < current) {
            while (indents.size > 1 && width < indents.last()) {
                indents.removeLast()
                add(TokenKind.DEDENT, "", SourceLocation(line, 1))
            }
            if (width != indents.last()) throw ScriptException("Inconsistent indentation", SourceLocation(line, 1))
        }
    }

    private fun scanIdentifier() {
        val start = offset; val loc = location()
        while (offset < source.length && (source[offset].isLetterOrDigit() || source[offset] == '_' || source[offset] == '$')) advance()
        if (source.substring(start, offset) == "f" && offset < source.length && (source[offset] == '\'' || source[offset] == '"')) {
            scanString(source[offset], isFString = true, tokenLocation = loc)
        } else {
            add(TokenKind.IDENTIFIER, source.substring(start, offset), loc)
        }
    }

    private fun scanNumber() {
        val start = offset; val loc = location()
        while (offset < source.length && (source[offset].isDigit() || source[offset] == '_')) advance()
        if (offset < source.length && source[offset] == '.' && peek(1)?.isDigit() == true) {
            advance()
            while (offset < source.length && (source[offset].isDigit() || source[offset] == '_')) advance()
        }
        add(TokenKind.NUMBER, source.substring(start, offset).replace("_", ""), loc)
    }

    private fun scanString(quote: Char, isFString: Boolean, tokenLocation: SourceLocation = location()) {
        val isTriple = quote != '`' && peek(1) == quote && peek(2) == quote
        if (isTriple) {
            repeat(3) { advance() }
        } else {
            advance()
        }

        val result = StringBuilder()
        while (offset < source.length) {
            if (isTriple) {
                if (source[offset] == quote && peek(1) == quote && peek(2) == quote) {
                    repeat(3) { advance() }
                    val kind = if (isFString) TokenKind.F_STRING else TokenKind.STRING
                    add(kind, result.toString(), tokenLocation)
                    return
                }
            } else if (source[offset] == quote) {
                advance()
                val kind = when {
                    quote == '`' -> TokenKind.TEMPLATE_STRING
                    isFString -> TokenKind.F_STRING
                    else -> TokenKind.STRING
                }
                add(kind, result.toString(), tokenLocation)
                return
            }

            val c = source[offset]
            if (c == '\n' && quote != '`' && !isTriple) throw ScriptException("Unterminated string literal", tokenLocation)
            if (c == '\\' && offset + 1 < source.length) {
                advance(); val escaped = source[offset]
                result.append(when (escaped) {
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '\\' -> '\\'; '\'' -> '\''; '"' -> '"'; '$' -> '$'; else -> escaped
                })
                advance()
            } else {
                result.append(c)
                advance()
            }
        }
        throw ScriptException("Unterminated string literal", tokenLocation)
    }

    private fun scanSymbol() {
        val loc = location()
        val pair = "${source[offset]}${peek(1) ?: ""}"
        val triple = if (offset + 2 < source.length) source.substring(offset, offset + 3) else ""
        val symbol = when {
            triple in setOf("===", "!==", "...", "**=") -> triple
            pair in setOf("==", "!=", "<=", ">=", "=>", "->", "&&", "||", "+=", "-=", "*=", "/=", "%=", "**", "++", "--") -> pair
            else -> source[offset].toString()
        }
        repeat(symbol.length) { advance() }
        if (symbol in setOf("(", "[", "{")) nesting++
        if (symbol in setOf(")", "]", "}")) {
            nesting--
            if (nesting < 0) throw ScriptException("Unexpected '$symbol'", loc)
        }
        add(TokenKind.SYMBOL, symbol, loc)
    }

    private fun skipLineComment() { while (offset < source.length && source[offset] != '\n') advance() }
    private fun skipBlockComment() {
        val start = location(); advance(); advance()
        while (offset < source.length && !(source[offset] == '*' && peek(1) == '/')) advance()
        if (offset >= source.length) throw ScriptException("Unterminated block comment", start)
        advance(); advance()
    }
    private fun peek(distance: Int): Char? = source.getOrNull(offset + distance)
    private fun advance() { if (source[offset] == '\n') { line++; column = 1 } else column++; offset++; atLineStart = false }
    private fun location() = SourceLocation(line, column)
    private fun add(kind: TokenKind, text: String, loc: SourceLocation) { tokens.add(Token(kind, text, loc)) }
}
