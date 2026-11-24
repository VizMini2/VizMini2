// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.alerts

import com.juancams.rviz2mini.indicators.IndicatorMath
import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.dyn.DynRos
import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * A rule of the user's on a topic: a numeric field crossing a threshold ("/battery_state percentage < 0.2") or the
 * topic going silent ("/scan without messages for 5 s").
 */
data class TopicRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val topic: String,
    val kind: Kind = Kind.VALUE,
    /** Path of the field (as in indicators: "percentage", "status[0].level", "pose.pose.position.x"). */
    val field: String = "",
    val op: Op = Op.LT,
    val threshold: Double = 0.0,
    val silenceS: Double = 5.0,
    val severity: AlertRules.Severity = AlertRules.Severity.WARNING,
    val enabled: Boolean = true,
) {
    enum class Kind { VALUE, SILENCE }
    enum class Op(val symbol: String) {
        LT("<"), LE("≤"), GT(">"), GE("≥"), EQ("="), NE("≠");

        fun test(v: Double, t: Double): Boolean = when (this) {
            LT -> v < t; LE -> v <= t; GT -> v > t; GE -> v >= t; EQ -> v == t; NE -> v != t
        }
    }

    /** "percentage < 0.2" or "silent 5 s" (for lists and notifications). */
    fun condition(): String = if (kind == Kind.SILENCE) com.juancams.rviz2mini.i18n.tr("alerts_silent_for", fmt(silenceS))
        else "$field ${op.symbol} ${fmt(threshold)}"

    fun title(): String = name.ifBlank { "$topic ${condition()}" }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("topic", topic).put("kind", kind.name).put("field", field)
        .put("op", op.name).put("threshold", threshold).put("silence", silenceS).put("severity", severity.name).put("enabled", enabled)

    companion object {
        fun fmt(v: Double): String = if (v == Math.rint(v) && Math.abs(v) < 1e9) v.toLong().toString() else String.format(Locale.ROOT, "%.3g", v)

        fun fromJson(o: JSONObject): TopicRule? = runCatching {
            TopicRule(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() }, name = o.optString("name"), topic = o.getString("topic"),
                kind = Kind.valueOf(o.optString("kind", "VALUE")), field = o.optString("field"), op = Op.valueOf(o.optString("op", "LT")),
                threshold = o.optDouble("threshold", 0.0), silenceS = o.optDouble("silence", 5.0),
                severity = AlertRules.Severity.valueOf(o.optString("severity", "WARNING")), enabled = o.optBoolean("enabled", true),
            )
        }.getOrNull()
    }
}

/** The rules, saved in the app's settings (the same file on the phone and on the PC). */
object TopicRules {
    private const val PREFS = "rviz2mini_alerts"
    private const val KEY = "topic_rules"
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun load(): List<TopicRule> = runCatching {
        val a = JSONArray(Platform.prefs(PREFS).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { TopicRule.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(rules: List<TopicRule>) {
        Platform.prefs(PREFS).edit().putString(KEY, JSONArray(rules.map { it.toJson() }).toString()).apply()
        listeners.forEach { it() }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}

/**
 * When a rule fires: on the change (the condition becoming true, or the topic falling silent), not on every message;
 * and again, as RECOVERED, when it stops holding. `cooldownMs`: no new notice for the same rule before that.
 */
class TopicRuleTracker(val rule: TopicRule, private val cooldownMs: Long = 30_000) {
    private var active = false
    private var lastAlert = 0L
    private var lastMessage = 0L
    @Volatile var lastValue: Double? = null; private set

    private fun raise(now: Long, text: String): AlertRules.Alert? {
        active = true
        if (lastAlert != 0L && now - lastAlert < cooldownMs) return null
        lastAlert = now
        return AlertRules.Alert("rule:${rule.id}", rule.title(), text, rule.severity)
    }

    private fun recover(text: String): AlertRules.Alert {
        active = false
        return AlertRules.Alert("rule:${rule.id}", rule.title(), text, AlertRules.Severity.RECOVERED)
    }

    /** A message arrived (with the field's value for VALUE rules, null if it does not have it). */
    @Synchronized fun onMessage(value: Double?, now: Long): AlertRules.Alert? {
        lastMessage = now
        if (rule.kind == TopicRule.Kind.SILENCE) return if (active) recover(com.juancams.rviz2mini.i18n.tr("alerts_back", rule.topic)) else null
        val v = value ?: return null
        lastValue = v
        val holds = rule.op.test(v, rule.threshold)
        return when {
            holds && !active -> raise(now, "${rule.topic}: ${rule.field} = ${TopicRule.fmt(v)} (${rule.op.symbol} ${TopicRule.fmt(rule.threshold)})")
            !holds && active -> recover("${rule.topic}: ${rule.field} = ${TopicRule.fmt(v)}")
            else -> null
        }
    }

    /** Called every few seconds: a SILENCE rule fires when there has been nothing for `silenceS` (after a first message). */
    @Synchronized fun tick(now: Long): AlertRules.Alert? {
        if (rule.kind != TopicRule.Kind.SILENCE || lastMessage == 0L || active) return null
        return if (now - lastMessage > rule.silenceS * 1000) raise(now, com.juancams.rviz2mini.i18n.tr("alerts_silent_text", rule.topic, TopicRule.fmt((now - lastMessage) / 1000.0))) else null
    }

    @get:Synchronized val firing: Boolean get() = active
}

/** The platform's running monitor (Android's AlertMonitor, the PC's DesktopAlerts), for the Alerts tab. */
object TopicAlertMonitors {
    @Volatile var current: TopicAlertMonitor? = null
    /** Where notices go and when (shown in the Alerts tab): "in the background while the service runs"... */
    @Volatile var whereKey: String = "alerts_where_app"
}

/** The latest notices (both platforms' monitors add to it; the Alerts tab shows it). */
object AlertLog {
    data class Entry(val timeMs: Long, val alert: AlertRules.Alert)

    private const val MAX = 200
    private val entries = ArrayDeque<Entry>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    @Synchronized fun add(a: AlertRules.Alert, now: Long = System.currentTimeMillis()) {
        entries.addFirst(Entry(now, a))
        while (entries.size > MAX) entries.removeLast()
        listeners.forEach { it() }
    }

    @Synchronized fun all(): List<Entry> = entries.toList()
    @Synchronized fun clear() { entries.clear(); listeners.forEach { it() } }
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}

/**
 * Watches the topics of the rules (any message type, decoded dynamically) and reports the notices through
 * `notify` (each platform shows them its way). [sync] after changing rules; [tick] every few seconds.
 */
class TopicAlertMonitor(private val registry: InterfaceRegistry, private val notify: (AlertRules.Alert) -> Unit) {
    private val trackers = HashMap<String, TopicRuleTracker>()
    private val subs = HashMap<String, Pair<String, DynRos.Subscription>>()

    /** The state of each rule (for the Alerts tab): firing and the last value seen. */
    @Synchronized fun state(): Map<String, Pair<Boolean, Double?>> = trackers.mapValues { it.value.firing to it.value.lastValue }

    @Synchronized fun sync(rules: List<TopicRule>) {
        val enabled = rules.filter { it.enabled && it.topic.isNotBlank() }
        // Trackers: kept if the rule did not change.
        val keep = enabled.associateBy { it.id }
        trackers.keys.retainAll { id -> keep[id] == trackers[id]?.rule }
        for (r in enabled) trackers.getOrPut(r.id) { TopicRuleTracker(r) }
        // One subscription per topic (with the type the graph says).
        val topics = enabled.map { it.topic }.toSet()
        for (t in subs.keys.toList()) if (t !in topics) subs.remove(t)?.second?.let { runCatching { it.close() } }
        val g = RosConnection.graph()
        for (t in topics) {
            val type = g?.topics(includeHidden = true)?.firstOrNull { it.name == t }?.types?.firstOrNull() ?: continue
            if (subs[t]?.first == type) continue
            subs.remove(t)?.second?.let { runCatching { it.close() } }
            val spec = runCatching { registry.msg(type) }.getOrNull() ?: continue
            subs[t] = type to DynRos.subscribe(t, spec, RosConnection.sensorQos()) { v -> onMessage(t, v) }
        }
    }

    private fun onMessage(topic: String, v: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        val list = synchronized(this) { trackers.values.filter { it.rule.topic == topic } }
        for (tr in list) {
            val value = if (tr.rule.kind == TopicRule.Kind.VALUE) number(IndicatorMath.valueAt(v, tr.rule.field)) else null
            tr.onMessage(value, now)?.let(::emit)
        }
    }

    fun tick(now: Long = System.currentTimeMillis()) {
        val list = synchronized(this) { trackers.values.toList() }
        for (tr in list) tr.tick(now)?.let(::emit)
    }

    private fun emit(a: AlertRules.Alert) { AlertLog.add(a); notify(a) }

    @Synchronized fun close() {
        subs.values.forEach { runCatching { it.second.close() } }; subs.clear(); trackers.clear()
    }

    companion object {
        fun number(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
}
