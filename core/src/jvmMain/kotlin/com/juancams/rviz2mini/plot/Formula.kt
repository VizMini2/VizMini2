// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.plot

/** Malformed formula (the message says where). */
class FormulaException(message: String) : Exception(message)

/**
 * Formula of a computed series, with an expression:
 * `{speed} * 3.6`, `{cmd} - {odom}`, `hypot({vx}, {vy})`, `atan2({y}, {x}) * 180 / pi`. Each
 * `{name}` is another series of the tab (by its name in the legend).
 *
 * Operators + - * / % ^ (power), parentheses, the constants `pi` and `e` and the functions in
 * [FUNCTIONS].
 */
class Formula private constructor(val source: String, private val root: Node, val refs: List<String>) {

    private sealed class Node {
        class Num(val v: Double) : Node()
        class Ref(val name: String) : Node()
        class Unary(val op: Char, val a: Node) : Node()
        class Binary(val op: Char, val a: Node, val b: Node) : Node()
        class Call(val fn: String, val args: List<Node>) : Node()
    }

    /** Value from the series values (`value(name)`); null if any is missing. */
    fun eval(value: (String) -> Double?): Double? = try { eval(root, value) } catch (_: Missing) { null }

    /** A series value is missing (no stack trace: it is a normal case, not an error). */
    private object Missing : RuntimeException(null, null, false, false)

    private fun eval(n: Node, value: (String) -> Double?): Double = when (n) {
        is Node.Num -> n.v
        is Node.Ref -> value(n.name) ?: throw Missing
        is Node.Unary -> -eval(n.a, value)
        is Node.Binary -> {
            val a = eval(n.a, value); val b = eval(n.b, value)
            when (n.op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> a / b
                '%' -> a % b
                else -> Math.pow(a, b)
            }
        }
        is Node.Call -> {
            val x = n.args.map { eval(it, value) }
            when (n.fn) {
                "abs" -> Math.abs(x[0]); "sqrt" -> Math.sqrt(x[0]); "exp" -> Math.exp(x[0])
                "log" -> Math.log(x[0]); "log10" -> Math.log10(x[0])
                "sin" -> Math.sin(x[0]); "cos" -> Math.cos(x[0]); "tan" -> Math.tan(x[0])
                "asin" -> Math.asin(x[0]); "acos" -> Math.acos(x[0]); "atan" -> Math.atan(x[0])
                "atan2" -> Math.atan2(x[0], x[1]); "hypot" -> Math.hypot(x[0], x[1])
                "min" -> Math.min(x[0], x[1]); "max" -> Math.max(x[0], x[1])
                "floor" -> Math.floor(x[0]); "ceil" -> Math.ceil(x[0]); "round" -> Math.rint(x[0])
                "sign" -> Math.signum(x[0]); "deg" -> Math.toDegrees(x[0]); "rad" -> Math.toRadians(x[0])
                else -> throw IllegalStateException(n.fn)
            }
        }
    }

    companion object {
        /** Functions and how many arguments they take. */
        val FUNCTIONS: Map<String, Int> = mapOf(
            "abs" to 1, "sqrt" to 1, "exp" to 1, "log" to 1, "log10" to 1, "sin" to 1, "cos" to 1, "tan" to 1,
            "asin" to 1, "acos" to 1, "atan" to 1, "atan2" to 2, "hypot" to 2, "min" to 2, "max" to 2,
            "floor" to 1, "ceil" to 1, "round" to 1, "sign" to 1, "deg" to 1, "rad" to 1
        )

        fun parse(text: String): Formula {
            val p = Parser(text)
            val root = p.expression()
            p.skip()
            if (p.i < text.length) throw FormulaException("Unexpected '${text[p.i]}' at ${p.i + 1}")
            if (p.refs.isEmpty()) throw FormulaException("Use at least one series, like {name}")
            return Formula(text, root, p.refs.distinct())
        }
    }

    /** Recursive descent: sum < product < sign < power < atom. */
    private class Parser(val s: String) {
        var i = 0
        val refs = ArrayList<String>()

        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char? { skip(); return s.getOrNull(i) }

        fun expression(): Node {
            var a = term()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') { i++; a = Node.Binary(c, a, term()) } else return a
            }
        }

        fun term(): Node {
            var a = unary()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/' || c == '%') { i++; a = Node.Binary(c, a, unary()) } else return a
            }
        }

        /** Sign binds less tightly than power: -x^2 is -(x^2). */
        fun unary(): Node = when (peek()) {
            '-' -> { i++; Node.Unary('-', unary()) }
            '+' -> { i++; unary() }
            else -> power()
        }

        /** Right-associative power: 2^3^2 is 2^(3^2); the exponent may carry a sign. */
        fun power(): Node {
            val a = atom()
            return if (peek() == '^') { i++; Node.Binary('^', a, unary()) } else a
        }

        fun atom(): Node {
            val c = peek() ?: throw FormulaException("Unexpected end")
            return when {
                c == '(' -> { i++; val e = expression(); expect(')'); e }
                c == '{' -> {
                    val close = s.indexOf('}', i)
                    if (close < 0) throw FormulaException("Missing '}'")
                    val name = s.substring(i + 1, close).trim()
                    if (name.isEmpty()) throw FormulaException("Empty series name at ${i + 1}")
                    i = close + 1
                    refs += name
                    Node.Ref(name)
                }
                c.isDigit() || c == '.' -> {
                    val m = Regex("""^(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""").find(s.substring(i)) ?: throw FormulaException("Bad number at ${i + 1}")
                    i += m.value.length
                    Node.Num(m.value.toDouble())
                }
                c.isLetter() -> {
                    val m = Regex("""^[A-Za-z_][A-Za-z0-9_]*""").find(s.substring(i))!!
                    val name = m.value
                    i += name.length
                    when {
                        name == "pi" -> Node.Num(Math.PI)
                        name == "e" -> Node.Num(Math.E)
                        name in FUNCTIONS -> {
                            expect('(')
                            val args = arrayListOf(expression())
                            while (peek() == ',') { i++; args += expression() }
                            expect(')')
                            val n = FUNCTIONS.getValue(name)
                            if (args.size != n) throw FormulaException("$name() takes $n argument${if (n == 1) "" else "s"}")
                            Node.Call(name, args)
                        }
                        else -> throw FormulaException("Unknown name '$name' (series go in braces: {$name})")
                    }
                }
                else -> throw FormulaException("Unexpected '$c' at ${i + 1}")
            }
        }

        fun expect(c: Char) {
            if (peek() != c) throw FormulaException("Expected '$c' at ${i + 1}")
            i++
        }
    }
}
