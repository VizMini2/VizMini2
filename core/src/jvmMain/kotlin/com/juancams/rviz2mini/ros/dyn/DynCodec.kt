// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros.dyn

import org.json.JSONArray
import org.json.JSONObject
import us.ihmc.fastddsjava.cdr.CDRBuffer

/** A dynamic message: fields in .msg order. */
typealias MsgValue = LinkedHashMap<String, Any?>

/** Field value that does not fit its type. `path` is the field path ("pose.position.x"). */
class ValueException(val path: String, message: String) : Exception(if (path.isEmpty()) message else "$path: $message")

/**
 * Dynamic messages: a message is a `LinkedHashMap<String, Any?>` in .msg order with
 * Boolean (bool), Long (integers; uint64 keeps the bits), Double (float32/64), String, lists
 * (arrays) and nested maps (submessages).
 *
 * Serializes to CDR (XCDR1, little endian) just like jros2's generated classes and
 * rmw_fastrtps. Strings are written and read as real UTF-8 (jros2 truncates them to one byte per
 * character).
 */
object DynCodec {

    /** Field ROS adds to empty messages so they do not have size 0. */
    private const val EMPTY_STRUCT_BYTES = 1

    // ---------------------------------------------------------------- default values

    fun defaultValue(spec: MsgSpec): MsgValue {
        val m = MsgValue()
        for (f in spec.fields) m[f.name] = defaultField(f)
        return m
    }

    fun defaultField(f: FieldSpec): Any? {
        f.default?.let { d ->
            try { return parseDefault(f, d) } catch (_: Exception) { /* odd default value: ignored */ }
        }
        return when (f.array) {
            ArrayKind.FIXED -> MutableList(f.arraySize) { defaultElement(f) }
            ArrayKind.NONE -> defaultElement(f)
            else -> ArrayList<Any?>()
        }
    }

    private fun defaultElement(f: FieldSpec): Any? = f.prim?.let { defaultPrim(it) } ?: defaultValue(f.nested!!)

    fun defaultPrim(p: Prim): Any = when {
        p == Prim.BOOL -> false
        p.isFloat -> 0.0
        p.isText -> ""
        else -> 0L
    }

    private fun parseDefault(f: FieldSpec, text: String): Any? {
        val p = f.prim ?: return defaultValue(f.nested!!)
        if (!f.isArray) return coerceScalar(p, unquote(text), f.name, f.maxLength)
        val inner = text.trim().removePrefix("[").removeSuffix("]")
        val items = if (inner.isBlank()) emptyList() else splitList(inner).map { coerceScalar(p, unquote(it), f.name, f.maxLength) }
        return if (f.array == ArrayKind.FIXED) MutableList(f.arraySize) { items.getOrElse(it) { defaultPrim(p) } } else ArrayList(items)
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0]) t.substring(1, t.length - 1) else t
    }

    /** Splits "1, 2, 'a,b'" on commas outside quotes. */
    fun splitList(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in text) {
            when {
                quote != null -> { cur.append(ch); if (ch == quote) quote = null }
                ch == '"' || ch == '\'' -> { cur.append(ch); quote = ch }
                ch == ',' -> { out.add(cur.toString().trim()); cur.setLength(0) }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank() || out.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    fun deepCopy(v: Any?): Any? = when (v) {
        is Map<*, *> -> MsgValue().also { m -> for ((k, x) in v) m[k as String] = deepCopy(x) }
        is List<*> -> ArrayList<Any?>(v.size).also { l -> for (x in v) l.add(deepCopy(x)) }
        is ByteArray -> v.copyOf()
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    fun copyMsg(m: MsgValue): MsgValue = deepCopy(m) as MsgValue

    // ---------------------------------------------------------------- value conversion

    /**
     * Converts what the user types (or a JSON number/boolean) to the field's type.
     * Checks ranges: throws [ValueException] if it does not fit.
     */
    fun coerceScalar(p: Prim, raw: Any?, path: String, maxLength: Int = 0): Any {
        fun bad(why: String): Nothing = throw ValueException(path, why)
        if (raw == null || raw == JSONObject.NULL) return defaultPrim(p)
        return when {
            p == Prim.BOOL -> when (raw) {
                is Boolean -> raw
                is Number -> raw.toDouble() != 0.0
                else -> when (raw.toString().trim().lowercase()) {
                    "true", "1", "yes", "si", "sí", "on" -> true
                    "false", "0", "no", "off", "" -> false
                    else -> bad("\"$raw\" is not a boolean (true/false)")
                }
            }
            p.isText -> raw.toString().let { if (maxLength > 0 && it.length > maxLength) bad("more than $maxLength characters") else it }
            p.isFloat -> {
                val d = when (raw) {
                    is Number -> raw.toDouble()
                    else -> raw.toString().trim().let { s -> if (s.isEmpty()) 0.0 else s.replace(',', '.').toDoubleOrNull() ?: bad("\"$s\" is not a number") }
                }
                if (p == Prim.FLOAT32 && d.isFinite() && Math.abs(d) > Float.MAX_VALUE) bad("$d does not fit in float32")
                d
            }
            else -> {
                val s = (if (raw is Number && raw !is Double && raw !is Float) raw.toLong().toString() else raw.toString()).trim()
                if (s.isEmpty()) return 0L
                if (p == Prim.UINT64) {
                    val u = parseUnsigned(s) ?: bad("\"$s\" is not an unsigned integer")
                    return u.toLong()
                }
                val v = parseLong(s) ?: bad("\"$s\" is not an integer")
                val (lo, hi) = range(p)
                if (v < lo || v > hi) bad("$v out of range for ${p.rosName} ($lo..$hi)")
                v
            }
        }
    }

    private fun parseLong(s: String): Long? {
        val t = s.replace("_", "")
        t.toLongOrNull()?.let { return it }
        if (t.startsWith("0x", true)) return t.substring(2).toLongOrNull(16)
        if (t.startsWith("-0x", true)) return t.substring(3).toLongOrNull(16)?.let { -it }
        // "3.0" is accepted as 3.
        val d = t.toDoubleOrNull() ?: return null
        return if (d == Math.rint(d) && Math.abs(d) < 9.0e18) d.toLong() else null
    }

    private fun parseUnsigned(s: String): ULong? {
        val t = s.replace("_", "")
        return if (t.startsWith("0x", true)) t.substring(2).toULongOrNull(16) else t.toULongOrNull()
    }

    private fun range(p: Prim): Pair<Long, Long> = when (p) {
        Prim.INT8 -> -128L to 127L
        Prim.UINT8, Prim.BYTE, Prim.CHAR -> 0L to 255L
        Prim.INT16 -> -32768L to 32767L
        Prim.UINT16 -> 0L to 65535L
        Prim.INT32 -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
        Prim.UINT32 -> 0L to 0xFFFFFFFFL
        else -> Long.MIN_VALUE to Long.MAX_VALUE
    }

    /**
     * Saved JSON (what the user configured) -> full message with default values where
     * something is missing. A builtin_interfaces/Time field with the value "now" is filled with `nowNs`.
     * With `strict`, values that do not fit throw [ValueException]; without it the defaults are used.
     */
    fun fromJson(spec: MsgSpec, json: Any?, nowNs: Long = System.currentTimeMillis() * 1_000_000L, strict: Boolean = false, path: String = ""): MsgValue {
        if (spec.isTime && json is String && json.trim().equals("now", true)) return timeValue(nowNs)
        val obj = json as? JSONObject
        val m = MsgValue()
        for (f in spec.fields) {
            val p = if (path.isEmpty()) f.name else "$path.${f.name}"
            val raw = obj?.opt(f.name)
            m[f.name] = try {
                if (raw == null || raw == JSONObject.NULL) defaultField(f) else fieldFromJson(f, raw, nowNs, strict, p)
            } catch (e: ValueException) {
                if (strict) throw e else defaultField(f)
            }
        }
        return m
    }

    private fun fieldFromJson(f: FieldSpec, raw: Any, nowNs: Long, strict: Boolean, path: String): Any? {
        if (!f.isArray) return elementFromJson(f, raw, nowNs, strict, path)
        val items: List<Any?> = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.opt(it) }
            is String -> if (f.prim != null) (if (raw.isBlank()) emptyList() else splitList(raw.trim().removePrefix("[").removeSuffix("]")).map { unquote(it) })
                         else parseJsonArray(raw, path).let { a -> (0 until a.length()).map { a.opt(it) } }
            else -> throw ValueException(path, "expected a list")
        }
        val values = items.mapIndexed { i, x -> elementFromJson(f, x, nowNs, strict, "$path[$i]") }
        return when (f.array) {
            ArrayKind.FIXED -> {
                if (strict && values.size > f.arraySize) throw ValueException(path, "has ${values.size} elements and the maximum is ${f.arraySize}")
                MutableList(f.arraySize) { values.getOrElse(it) { defaultElement(f) } }
            }
            ArrayKind.BOUNDED -> {
                if (strict && values.size > f.arraySize) throw ValueException(path, "has ${values.size} elements and the maximum is ${f.arraySize}")
                ArrayList(values.take(f.arraySize))
            }
            else -> ArrayList(values)
        }
    }

    private fun parseJsonArray(text: String, path: String): JSONArray =
        if (text.isBlank()) JSONArray() else try { JSONArray(text) } catch (e: Exception) { throw ValueException(path, "invalid JSON list (${e.message})") }

    private fun elementFromJson(f: FieldSpec, raw: Any?, nowNs: Long, strict: Boolean, path: String): Any? =
        if (f.prim != null) coerceScalar(f.prim, raw, path, f.maxLength)
        else fromJson(f.nested!!, if (raw is String && !f.nested.isTime) parseJsonObject(raw, path) else raw, nowNs, strict, path)

    private fun parseJsonObject(text: String, path: String): JSONObject =
        if (text.isBlank()) JSONObject() else try { JSONObject(text) } catch (e: Exception) { throw ValueException(path, "invalid JSON object (${e.message})") }

    fun timeValue(ns: Long): MsgValue = MsgValue().apply { put("sec", Math.floorDiv(ns, 1_000_000_000L)); put("nanosec", Math.floorMod(ns, 1_000_000_000L)) }

    /** Message -> JSON for saving. */
    fun toJson(spec: MsgSpec, m: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for (f in spec.fields) o.put(f.name, fieldToJson(f, m[f.name]))
        return o
    }

    private fun fieldToJson(f: FieldSpec, v: Any?): Any? {
        if (!f.isArray) return elementToJson(f, v)
        val a = JSONArray()
        if (v is ByteArray) { for (b in v) a.put(b.toInt() and 0xFF); return a }
        for (x in (v as? List<*>).orEmpty()) a.put(elementToJson(f, x))
        return a
    }

    @Suppress("UNCHECKED_CAST")
    private fun elementToJson(f: FieldSpec, v: Any?): Any? =
        if (f.prim != null) (if (f.prim == Prim.UINT64 && v is Long) v.toULong().toString() else v ?: JSONObject.NULL)
        else toJson(f.nested!!, (v as? Map<String, Any?>) ?: defaultValue(f.nested))

    // ---------------------------------------------------------------- CDR

    fun write(spec: MsgSpec, m: Map<String, Any?>, buf: CDRBuffer) {
        if (spec.fields.isEmpty()) { buf.writeByte(0); return }
        for (f in spec.fields) writeField(f, m[f.name], buf)
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeField(f: FieldSpec, v: Any?, buf: CDRBuffer) {
        when (f.array) {
            ArrayKind.NONE -> writeElement(f, v, buf)
            ArrayKind.FIXED -> {
                val l = (v as? List<Any?>).orEmpty()
                for (i in 0 until f.arraySize) writeElement(f, l.getOrElse(i) { defaultElement(f) }, buf)
            }
            else -> {
                // Fast path for large uint8[]/byte[] (images): a ByteArray instead of a list.
                if (v is ByteArray && f.prim?.size == 1) {
                    val n = if (f.array == ArrayKind.BOUNDED) minOf(v.size, f.arraySize) else v.size
                    buf.writeInt(n)
                    buf.ensureRemainingCapacity(n)
                    buf.getBufferUnsafe().put(v, 0, n)
                    return
                }
                var l = (v as? List<Any?>).orEmpty()
                if (f.array == ArrayKind.BOUNDED && l.size > f.arraySize) l = l.take(f.arraySize)
                buf.writeInt(l.size)
                for (x in l) writeElement(f, x, buf)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeElement(f: FieldSpec, v: Any?, buf: CDRBuffer) {
        val p = f.prim
        if (p == null) { write(f.nested!!, (v as? Map<String, Any?>) ?: defaultValue(f.nested), buf); return }
        when (p) {
            Prim.BOOL -> buf.writeBoolean(v as? Boolean ?: false)
            Prim.BYTE, Prim.CHAR, Prim.INT8, Prim.UINT8 -> buf.writeByte(num(v).toByte())
            Prim.INT16, Prim.UINT16 -> buf.writeShort(num(v).toShort())
            Prim.INT32, Prim.UINT32 -> buf.writeInt(num(v).toInt())
            Prim.INT64, Prim.UINT64 -> buf.writeLong(num(v))
            Prim.FLOAT32 -> buf.writeFloat(dbl(v).toFloat())
            Prim.FLOAT64 -> buf.writeDouble(dbl(v))
            Prim.STRING -> {
                val bytes = (v?.toString() ?: "").toByteArray(Charsets.UTF_8)
                buf.writeInt(bytes.size + 1)
                for (b in bytes) buf.writeByte(b)
                buf.writeByte(0)
            }
            Prim.WSTRING -> buf.writeWString(StringBuilder(v?.toString() ?: ""))
        }
    }

    private fun num(v: Any?): Long = when (v) { is Number -> v.toLong(); is Boolean -> if (v) 1 else 0; else -> 0L }
    private fun dbl(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0

    fun read(spec: MsgSpec, buf: CDRBuffer): MsgValue {
        val m = MsgValue()
        if (spec.fields.isEmpty()) { buf.readByte(); return m }
        for (f in spec.fields) m[f.name] = readField(f, buf)
        return m
    }

    private fun readField(f: FieldSpec, buf: CDRBuffer): Any? = when (f.array) {
        ArrayKind.NONE -> readElement(f, buf)
        ArrayKind.FIXED -> ArrayList<Any?>(f.arraySize).also { l -> repeat(f.arraySize) { l.add(readElement(f, buf)) } }
        else -> {
            val n = buf.readInt()
            val remaining = buf.getBufferUnsafe().remaining()
            if (n < 0 || n > remaining) throw IllegalStateException("${f.name}: sequence of $n elements with $remaining bytes remaining")
            // uint8[]/byte[] (images, clouds, maps): a ByteArray instead of a list of Long, as when writing.
            if (f.prim == Prim.UINT8 || f.prim == Prim.BYTE) ByteArray(n).also { buf.getBufferUnsafe().get(it) }
            else ArrayList<Any?>(n).also { l -> repeat(n) { l.add(readElement(f, buf)) } }
        }
    }

    private fun readElement(f: FieldSpec, buf: CDRBuffer): Any? {
        val p = f.prim ?: return read(f.nested!!, buf)
        return when (p) {
            Prim.BOOL -> buf.readByte().toInt() != 0
            Prim.INT8 -> buf.readByte().toLong()
            Prim.BYTE, Prim.CHAR, Prim.UINT8 -> (buf.readByte().toInt() and 0xFF).toLong()
            Prim.INT16 -> buf.readShort().toLong()
            Prim.UINT16 -> (buf.readShort().toInt() and 0xFFFF).toLong()
            Prim.INT32 -> buf.readInt().toLong()
            Prim.UINT32 -> buf.readInt().toLong() and 0xFFFFFFFFL
            Prim.INT64, Prim.UINT64 -> buf.readLong()
            Prim.FLOAT32 -> buf.readFloat().toDouble()
            Prim.FLOAT64 -> buf.readDouble()
            Prim.STRING -> {
                val n = buf.readInt()
                if (n <= 0) "" else {
                    val bb = buf.getBufferUnsafe()
                    if (n > bb.remaining()) throw IllegalStateException("${f.name}: string of $n bytes with ${bb.remaining()} remaining")
                    val bytes = ByteArray(n - 1)
                    bb.get(bytes)
                    bb.get() // terminator
                    String(bytes, Charsets.UTF_8)
                }
            }
            Prim.WSTRING -> StringBuilder().also { buf.readWString(it) }.toString()
        }
    }

    /** Bytes taken by `m` starting at position `start` (relative to the start of the CDR data). */
    fun size(spec: MsgSpec, m: Map<String, Any?>, start: Int): Int = sizeMsg(spec, m, start) - start

    @Suppress("UNCHECKED_CAST")
    private fun sizeMsg(spec: MsgSpec, m: Map<String, Any?>, pos0: Int): Int {
        if (spec.fields.isEmpty()) return pos0 + EMPTY_STRUCT_BYTES
        var pos = pos0
        for (f in spec.fields) {
            val v = m[f.name]
            pos = when (f.array) {
                ArrayKind.NONE -> sizeElement(f, v, pos)
                ArrayKind.FIXED -> { val l = (v as? List<Any?>).orEmpty(); var q = pos; for (i in 0 until f.arraySize) q = sizeElement(f, l.getOrElse(i) { defaultElement(f) }, q); q }
                else -> {
                    if (v is ByteArray && f.prim?.size == 1) {
                        pos = align(pos, 4) + 4 + (if (f.array == ArrayKind.BOUNDED) minOf(v.size, f.arraySize) else v.size)
                        continue
                    }
                    var l = (v as? List<Any?>).orEmpty()
                    if (f.array == ArrayKind.BOUNDED && l.size > f.arraySize) l = l.take(f.arraySize)
                    var q = align(pos, 4) + 4
                    for (x in l) q = sizeElement(f, x, q)
                    q
                }
            }
        }
        return pos
    }

    @Suppress("UNCHECKED_CAST")
    private fun sizeElement(f: FieldSpec, v: Any?, pos: Int): Int {
        val p = f.prim ?: return sizeMsg(f.nested!!, (v as? Map<String, Any?>) ?: defaultValue(f.nested), pos)
        return when (p) {
            Prim.STRING -> align(pos, 4) + 4 + (v?.toString() ?: "").toByteArray(Charsets.UTF_8).size + 1
            Prim.WSTRING -> align(pos, 4) + 4 + 4 * (v?.toString() ?: "").length
            else -> align(pos, p.size) + p.size
        }
    }

    private fun align(pos: Int, n: Int) = if (n <= 1) pos else pos + (n - pos % n) % n

    // ---------------------------------------------------------------- text

    /** Text in `ros2 topic echo` style. */
    fun format(spec: MsgSpec, m: Map<String, Any?>, maxSeq: Int = 16): String {
        val sb = StringBuilder()
        formatInto(sb, spec, m, 0, maxSeq)
        return sb.toString().trimEnd().ifEmpty { "{}" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun formatInto(sb: StringBuilder, spec: MsgSpec, m: Map<String, Any?>, indent: Int, maxSeq: Int) {
        val pad = "  ".repeat(indent)
        for (f in spec.fields) {
            val v = m[f.name]
            sb.append(pad).append(f.name).append(':')
            if (!f.isArray) {
                if (f.nested != null) { sb.append('\n'); formatInto(sb, f.nested, (v as? Map<String, Any?>).orEmpty(), indent + 1, maxSeq) }
                else sb.append(' ').append(scalarText(f.prim!!, v)).append('\n')
                continue
            }
            val l = (v as? List<Any?>).orEmpty()
            if (l.isEmpty()) { sb.append(" []\n"); continue }
            if (f.prim != null) {
                sb.append(" [")
                for (i in 0 until minOf(l.size, maxSeq)) { if (i > 0) sb.append(", "); sb.append(scalarText(f.prim, l[i])) }
                if (l.size > maxSeq) sb.append(", … (+").append(l.size - maxSeq).append(')')
                sb.append("]\n")
            } else {
                sb.append('\n')
                for (i in 0 until minOf(l.size, maxSeq)) {
                    sb.append(pad).append("- \n")
                    formatInto(sb, f.nested!!, (l[i] as? Map<String, Any?>).orEmpty(), indent + 1, maxSeq)
                }
                if (l.size > maxSeq) sb.append(pad).append("- … (").append(l.size - maxSeq).append(" more)\n")
            }
        }
    }

    /** Text of a scalar for displaying or editing it. */
    fun scalarText(p: Prim, v: Any?): String = when {
        v == null -> ""
        p == Prim.UINT64 && v is Long -> v.toULong().toString()
        p.isFloat && v is Number -> v.toDouble().let { d -> if (d.isFinite() && d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() + ".0" else d.toString() }
        p.isText -> v.toString().let { t -> if (t.isEmpty() || t.any { it == ':' || it == '#' || it == '\n' } || t.trim() != t) "'${t.replace("'", "''")}'" else t }
        else -> v.toString()
    }
}
