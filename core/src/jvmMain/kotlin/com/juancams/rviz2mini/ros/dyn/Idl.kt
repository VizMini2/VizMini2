// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros.dyn

/** ROS 2 basic types (.msg). `size` is its size in CDR (0 = string, variable length). */
enum class Prim(val rosName: String, val size: Int) {
    BOOL("bool", 1), BYTE("byte", 1), CHAR("char", 1),
    INT8("int8", 1), UINT8("uint8", 1), INT16("int16", 2), UINT16("uint16", 2),
    INT32("int32", 4), UINT32("uint32", 4), INT64("int64", 8), UINT64("uint64", 8),
    FLOAT32("float32", 4), FLOAT64("float64", 8),
    STRING("string", 0), WSTRING("wstring", 0);

    val isInteger get() = this in INTEGERS
    val isFloat get() = this == FLOAT32 || this == FLOAT64
    val isText get() = this == STRING || this == WSTRING

    companion object {
        private val INTEGERS = setOf(BYTE, CHAR, INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, UINT64)
        fun of(name: String): Prim? = entries.firstOrNull { it.rosName == name }
    }
}

/** Array size: NONE (scalar), FIXED (`T[3]`), BOUNDED (`T[<=3]`) or UNBOUNDED (`T[]`). */
enum class ArrayKind { NONE, FIXED, BOUNDED, UNBOUNDED }

/**
 * An already resolved field. Exactly one of `prim` / `nested` is non-null. `maxLength` is the limit
 * of `string<=N` (0 = unlimited). `default` is the default value written in the .msg,
 * uninterpreted.
 */
class FieldSpec(
    val name: String,
    val prim: Prim?,
    val nested: MsgSpec?,
    val array: ArrayKind = ArrayKind.NONE,
    val arraySize: Int = 0,
    val maxLength: Int = 0,
    val default: String? = null
) {
    val isArray get() = array != ArrayKind.NONE

    /** "float64[36]", "geometry_msgs/msg/Pose[]"... */
    val typeLabel: String
        get() {
            val base = prim?.rosName?.let { if (maxLength > 0) "$it<=$maxLength" else it } ?: nested!!.name
            return base + when (array) {
                ArrayKind.NONE -> ""
                ArrayKind.FIXED -> "[$arraySize]"
                ArrayKind.BOUNDED -> "[<=$arraySize]"
                ArrayKind.UNBOUNDED -> "[]"
            }
        }
}

class ConstSpec(val name: String, val type: String, val value: String)

/**
 * Already resolved message structure (its submessages too). `name` is the ROS name
 * ("geometry_msgs/msg/Pose", "std_srvs/srv/SetBool_Request") and `ddsName` the DDS type used by
 * rmw_fastrtps ("geometry_msgs::msg::dds_::Pose_").
 */
class MsgSpec(
    val name: String,
    val fields: List<FieldSpec>,
    val constants: List<ConstSpec> = emptyList(),
    /** Variant with the rmw_cyclonedds request/reply header in front (see [withCycloneHeader]). */
    val cycloneHeader: Boolean = false,
) {
    val ddsName: String = ddsTypeName(name)

    /**
     * The same request or reply as sent by rmw_cyclonedds_cpp: with a 16-byte header in front
     * (the client's `uint64 guid` and the call's `int64 seq`, in the encapsulation byte order). In CDR it is the
     * same as two leading fields, and since it is 16 bytes the rest keeps its alignment. Same DDS type.
     */
    fun withCycloneHeader(): MsgSpec =
        if (cycloneHeader) this
        else MsgSpec(name, listOf(FieldSpec(CYCLONE_GUID, Prim.UINT64, null), FieldSpec(CYCLONE_SEQ, Prim.INT64, null)) +
            // An empty message carries a padding byte in CDR, which DynCodec only writes if there are no fields:
            // with the header there are already two, so it is added explicitly (otherwise the request arrives short and is not read).
            fields.ifEmpty { listOf(FieldSpec(EMPTY_STRUCT_FIELD, Prim.UINT8, null)) }, constants, true)

    val isTime get() = name == "builtin_interfaces/msg/Time"

    override fun toString() = name

    companion object {
        /** Fields of the rmw_cyclonedds header in the [withCycloneHeader] variant. */
        const val CYCLONE_GUID = "__cyclone_guid"
        const val CYCLONE_SEQ = "__cyclone_seq"
        /** The padding byte of a message with no fields (the name rosidl uses). */
        const val EMPTY_STRUCT_FIELD = "structure_needs_at_least_one_member"

        /** "pkg/srv/Name_Request" -> "pkg::srv::dds_::Name_Request_". */
        fun ddsTypeName(rosName: String): String {
            val p = rosName.split('/')
            return "${p[0]}::${p[1]}::dds_::${p[2]}_"
        }
    }
}

/** Service: request and reply. */
class SrvSpec(val name: String, val request: MsgSpec, val response: MsgSpec)

/**
 * Action with the messages it uses on DDS (the rcl_action ones): send goal, get result and
 * feedback, plus the "plain" goal/result/feedback for editing and display.
 */
class ActionSpec(
    val name: String,
    val goal: MsgSpec,
    val result: MsgSpec,
    val feedback: MsgSpec,
    val sendGoal: SrvSpec,
    val getResult: SrvSpec,
    val feedbackMessage: MsgSpec
)

class IdlException(message: String) : Exception(message)

/** A .msg line already split into parts, before resolving types. */
class RawField(val type: String, val name: String, val default: String?, val constValue: String?)

object IdlParser {

    private val NAME = Regex("[A-Za-z][A-Za-z0-9_]*")

    /** Splits a .srv / .action into sections at the "---" lines. */
    fun sections(text: String): List<String> {
        val out = ArrayList<StringBuilder>()
        out.add(StringBuilder())
        for (line in text.lines()) {
            if (line.trim() == "---") out.add(StringBuilder()) else out.last().append(line).append('\n')
        }
        return out.map { it.toString() }
    }

    fun parseFields(text: String): List<RawField> {
        val out = ArrayList<RawField>()
        for ((i, rawLine) in text.lines().withIndex()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            val sp = line.indexOfFirst { it == ' ' || it == '\t' }
            if (sp < 0) throw IdlException("Line ${i + 1}: missing field name (\"$line\")")
            val type = line.substring(0, sp)
            val rest = line.substring(sp).trim()
            // Constant: "uint8 OK=0" (there may be spaces around the '=').
            val eq = rest.indexOf('=')
            val nameEnd = rest.indexOfFirst { it == ' ' || it == '\t' || it == '=' }.let { if (it < 0) rest.length else it }
            val name = rest.substring(0, nameEnd)
            if (!NAME.matches(name)) throw IdlException("Line ${i + 1}: invalid field name \"$name\"")
            if (eq >= 0 && rest.substring(nameEnd, eq).isBlank()) {
                out.add(RawField(type, name, null, rest.substring(eq + 1).trim()))
            } else {
                val def = rest.substring(nameEnd).trim().ifEmpty { null }
                out.add(RawField(type, name, def, null))
            }
        }
        return out
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        for ((i, ch) in line.withIndex()) {
            if (quote != null) {
                if (ch == quote) quote = null
            } else if (ch == '"' || ch == '\'') quote = ch
            else if (ch == '#') return line.substring(0, i)
        }
        return line
    }
}

/**
 * Interface definitions (.msg/.srv/.action) by full name ("pkg/msg/Name"). The built-in
 * ones come from assets/ros_interfaces.txt (tools/gen_ros_interfaces.py); the user can
 * add their own. Resolves nested types and caches what is already resolved.
 */
class InterfaceRegistry {

    private val defs = LinkedHashMap<String, String>()
    private val msgCache = HashMap<String, MsgSpec>()
    private val srvCache = HashMap<String, SrvSpec>()
    private val actionCache = HashMap<String, ActionSpec>()

    @Synchronized fun loadBundle(text: String) {
        var name: String? = null
        val body = StringBuilder()
        fun flush() { name?.let { defs[it] = body.toString() }; body.setLength(0) }
        for (line in text.lines()) {
            if (line.startsWith("@")) { flush(); name = line.substring(1).trim() }
            else if (name != null) body.append(line).append('\n')
        }
        flush()
        clearCaches()
    }

    /** Adds or replaces a definition. `fullName` = "pkg/msg|srv|action/Name". */
    @Synchronized fun define(fullName: String, text: String) {
        val n = normalize(fullName) ?: throw IdlException("Invalid name: $fullName (use package/msg|srv|action/Name)")
        defs[n] = text
        clearCaches()
    }

    /** Like [define], but first checks that it resolves fully; otherwise leaves everything as it was. */
    @Synchronized fun defineChecked(fullName: String, text: String): String {
        val n = normalize(fullName) ?: throw IdlException("Invalid name: $fullName (use package/msg|srv|action/Name)")
        val old = defs[n]
        define(n, text)
        try {
            when (n.split('/')[1]) {
                "msg" -> msg(n)
                "srv" -> srv(n)
                else -> action(n)
            }
        } catch (e: IdlException) {
            if (old == null) defs.remove(n) else defs[n] = old
            clearCaches()
            throw e
        }
        return n
    }

    @Synchronized fun has(fullName: String) = normalize(fullName)?.let { it in defs } ?: false

    @Synchronized fun definition(fullName: String): String? = normalize(fullName)?.let { defs[it] }

    /** Full names of a kind ("msg", "srv" or "action"), sorted. */
    @Synchronized fun names(kind: String): List<String> = defs.keys.filter { it.split('/').getOrNull(1) == kind }.sorted()

    @Synchronized fun msg(fullName: String): MsgSpec {
        val n = normalize(fullName, "msg") ?: throw IdlException("Invalid type: $fullName")
        return resolveMsg(n, ArrayList())
    }

    @Synchronized fun srv(fullName: String): SrvSpec {
        val n = normalize(fullName, "srv") ?: throw IdlException("Invalid service: $fullName")
        srvCache[n]?.let { return it }
        val text = defs[n] ?: throw IdlException("Unknown service $n")
        val parts = IdlParser.sections(text)
        if (parts.size != 2) throw IdlException("$n: a .srv must have a request and a response separated by ---")
        val pkg = n.substringBefore('/')
        val req = buildMsg("${n}_Request", pkg, parts[0], ArrayList())
        val res = buildMsg("${n}_Response", pkg, parts[1], ArrayList())
        return SrvSpec(n, req, res).also { srvCache[n] = it }
    }

    @Synchronized fun action(fullName: String): ActionSpec {
        val n = normalize(fullName, "action") ?: throw IdlException("Invalid action: $fullName")
        actionCache[n]?.let { return it }
        val text = defs[n] ?: throw IdlException("Unknown action $n")
        val parts = IdlParser.sections(text)
        if (parts.size != 3) throw IdlException("$n: an .action must have a goal, a result and feedback separated by ---")
        val pkg = n.substringBefore('/')
        val goal = buildMsg("${n}_Goal", pkg, parts[0], ArrayList())
        val result = buildMsg("${n}_Result", pkg, parts[1], ArrayList())
        val feedback = buildMsg("${n}_Feedback", pkg, parts[2], ArrayList())
        val uuid = resolveMsg("unique_identifier_msgs/msg/UUID", ArrayList())
        val time = resolveMsg("builtin_interfaces/msg/Time", ArrayList())
        val sendGoal = SrvSpec(
            "${n}_SendGoal",
            MsgSpec("${n}_SendGoal_Request", listOf(FieldSpec("goal_id", null, uuid), FieldSpec("goal", null, goal))),
            MsgSpec("${n}_SendGoal_Response", listOf(FieldSpec("accepted", Prim.BOOL, null), FieldSpec("stamp", null, time)))
        )
        val getResult = SrvSpec(
            "${n}_GetResult",
            MsgSpec("${n}_GetResult_Request", listOf(FieldSpec("goal_id", null, uuid))),
            MsgSpec("${n}_GetResult_Response", listOf(FieldSpec("status", Prim.INT8, null), FieldSpec("result", null, result)))
        )
        val fbMsg = MsgSpec("${n}_FeedbackMessage", listOf(FieldSpec("goal_id", null, uuid), FieldSpec("feedback", null, feedback)))
        return ActionSpec(n, goal, result, feedback, sendGoal, getResult, fbMsg).also { actionCache[n] = it }
    }

    private fun clearCaches() { msgCache.clear(); srvCache.clear(); actionCache.clear() }

    /** Another registry with the same definitions (to change one without touching this one; e.g. another version of a package). */
    @Synchronized fun copy(): InterfaceRegistry = InterfaceRegistry().also { it.defs.putAll(defs) }

    private fun resolveMsg(n: String, stack: MutableList<String>): MsgSpec {
        msgCache[n]?.let { return it }
        if (n in stack) throw IdlException("Recursive definition: ${(stack + n).joinToString(" -> ")}")
        val text = defs[n] ?: throw IdlException("Unknown type $n")
        stack.add(n)
        try {
            return buildMsg(n, n.substringBefore('/'), text, stack).also { msgCache[n] = it }
        } finally {
            stack.removeAt(stack.size - 1)
        }
    }

    private fun buildMsg(name: String, pkg: String, text: String, stack: MutableList<String>): MsgSpec {
        val fields = ArrayList<FieldSpec>()
        val consts = ArrayList<ConstSpec>()
        for (raw in IdlParser.parseFields(text)) {
            if (raw.constValue != null) { consts.add(ConstSpec(raw.name, raw.type, raw.constValue)); continue }
            fields.add(buildField(raw, pkg, name, stack))
        }
        return MsgSpec(name, fields, consts)
    }

    private fun buildField(raw: RawField, pkg: String, owner: String, stack: MutableList<String>): FieldSpec {
        var t = raw.type
        var array = ArrayKind.NONE
        var size = 0
        val br = t.indexOf('[')
        if (br >= 0) {
            if (!t.endsWith("]")) throw IdlException("$owner.${raw.name}: invalid type ${raw.type}")
            val inside = t.substring(br + 1, t.length - 1).trim()
            t = t.substring(0, br)
            when {
                inside.isEmpty() -> array = ArrayKind.UNBOUNDED
                inside.startsWith("<=") -> { array = ArrayKind.BOUNDED; size = inside.substring(2).trim().toIntOrNull() ?: throw IdlException("$owner.${raw.name}: invalid size") }
                else -> { array = ArrayKind.FIXED; size = inside.toIntOrNull() ?: throw IdlException("$owner.${raw.name}: invalid size") }
            }
        }
        var maxLen = 0
        val le = t.indexOf("<=")
        if (le >= 0) {
            maxLen = t.substring(le + 2).toIntOrNull() ?: throw IdlException("$owner.${raw.name}: invalid length")
            t = t.substring(0, le)
        }
        val prim = Prim.of(t)
        if (prim != null) return FieldSpec(raw.name, prim, null, array, size, maxLen, raw.default)
        val nestedName = when {
            t == "Header" -> "std_msgs/msg/Header"
            t.count { it == '/' } == 0 -> "$pkg/msg/$t"
            t.count { it == '/' } == 1 -> t.replace("/", "/msg/")
            else -> t
        }
        return FieldSpec(raw.name, null, resolveMsg(nestedName, stack), array, size, 0, raw.default)
    }

    companion object {
        /** "pkg/Name" -> "pkg/<kind>/Name"; "pkg/msg/Name" stays the same. null if invalid. */
        fun normalize(name: String, defaultKind: String? = null): String? {
            val p = name.trim().split('/').filter { it.isNotEmpty() }
            return when {
                p.size == 3 && p[1] in KINDS -> p.joinToString("/")
                p.size == 2 && defaultKind != null -> "${p[0]}/$defaultKind/${p[1]}"
                else -> null
            }
        }

        private val KINDS = setOf("msg", "srv", "action")
    }
}
