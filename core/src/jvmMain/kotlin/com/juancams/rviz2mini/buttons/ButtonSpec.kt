// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.buttons

import com.juancams.rviz2mini.rviz.display.QosPreset
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** What a button does when pressed. `kind` is the interface folder: msg, srv or action. */
enum class ButtonKind(val label: String, val kind: String, val targetLabel: String) {
    PUBLISH("Publish", "msg", "Topic"),
    SERVICE("Service", "srv", "Service"),
    ACTION("Action", "action", "Action");

    companion object {
        fun fromName(name: String?): ButtonKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A button in the "Buttons" tab.
 *
 * @param target name of the topic, service or action ("/cmd_vel").
 * @param type full interface ("geometry_msgs/msg/Twist", "std_srvs/srv/SetBool"...).
 * @param values JSON with the fields of the message / request / goal; anything missing uses its default.
 * @param repeatHz publish only: if > 0 it publishes at that rate while held down.
 * @param stopOnRelease with repetition: on release it publishes the message once with the numbers zeroed
 *   (release = stop, like a dead man's switch). Yes by default, also for buttons saved without the field.
 * @param timeoutS service: maximum time to wait for the response; action: for the acceptance.
 * @param confirm asks for confirmation before sending.
 * @param definitions custom (non-built-in) definitions used by the type, so the button
 *   works the same when loaded on another phone from a profile.
 */
data class ButtonSpec(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val kind: ButtonKind = ButtonKind.PUBLISH,
    val target: String = "",
    val type: String = "",
    val values: String = "{}",
    val color: Int = 0,
    val qos: QosPreset = QosPreset.RELIABLE,
    val repeatHz: Double = 0.0,
    val stopOnRelease: Boolean = true,
    val timeoutS: Double = 5.0,
    val confirm: Boolean = false,
    val definitions: Map<String, String> = emptyMap()
) {
    val displayLabel: String get() = label.ifBlank { target.ifBlank { kind.label } }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("kind", kind.name).put("target", target).put("type", type)
        .put("values", values).put("color", color).put("qos", qos.name).put("repeatHz", repeatHz)
        .put("stopOnRelease", stopOnRelease)
        .put("timeoutS", timeoutS).put("confirm", confirm)
        .apply { if (definitions.isNotEmpty()) put("definitions", JSONObject(definitions as Map<*, *>)) }

    companion object {
        /** Button colors (0 is the theme one). */
        val COLORS = intArrayOf(0, 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF546E7A.toInt())

        fun fromJson(o: JSONObject): ButtonSpec? {
            val kind = ButtonKind.fromName(o.optString("kind")) ?: return null
            return ButtonSpec(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                label = o.optString("label"),
                kind = kind,
                target = o.optString("target"),
                type = o.optString("type"),
                values = o.optString("values", "{}"),
                color = o.optInt("color").coerceIn(0, COLORS.size - 1),
                qos = QosPreset.entries.firstOrNull { it.name == o.optString("qos") } ?: QosPreset.RELIABLE,
                repeatHz = o.optDouble("repeatHz", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0,
                stopOnRelease = o.optBoolean("stopOnRelease", true),
                timeoutS = o.optDouble("timeoutS", 5.0).takeIf { it.isFinite() && it > 0 } ?: 5.0,
                confirm = o.optBoolean("confirm"),
                definitions = o.optJSONObject("definitions")?.let { d -> d.keys().asSequence().associateWith { d.optString(it) } }.orEmpty()
            )
        }
    }
}

/** List of buttons saved as JSON in the tab settings. */
object ButtonList {

    fun toJson(buttons: List<ButtonSpec>): String = JSONArray().apply { for (b in buttons) put(b.toJson()) }.toString()

    /** Ignores corrupt entries instead of failing. */
    fun fromJson(json: String?): List<ButtonSpec> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { ButtonSpec.fromJson(it) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Moves button `id` one position (`delta` = -1 before, +1 after). */
    fun move(buttons: List<ButtonSpec>, id: String, delta: Int): List<ButtonSpec> {
        val i = buttons.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in buttons.indices) return buttons
        return buttons.toMutableList().apply { add(j, removeAt(i)) }
    }
}
