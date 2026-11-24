// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.ros

import com.juancams.rviz2mini.i18n.tr
import com.juancams.rviz2mini.rviz.display.QosSettings

/**
 * Why messages from a topic are not arriving, based on what is known from the graph: no connection, nobody publishes it (and maybe there is
 * one with a similar name, e.g. with a namespace), it is of another type, or there is a publisher but nothing arrives (almost always
 * incompatible QoS: a best effort publisher with a reliable subscriber, or a latched topic without transient local).
 * Each case may come with a concrete fix. No Android: tested on the JVM.
 */
object TopicDiagnosis {

    /** A fix the interface offers with a button. */
    sealed class Fix(val label: String) {
        class UseTopic(val topic: String) : Fix(tr("diagnosis_fix_topic", topic))
        class UseQos(val reliability: String?, val durability: String?) : Fix(tr("diagnosis_fix_qos", listOfNotNull(reliability, durability).joinToString(" + ")))
    }

    data class Result(val message: String, val fix: Fix? = null)

    /** The minimum of the graph needed to decide (provided by [RosGraph]; tests build it by hand). */
    data class TopicInfo(val name: String, val types: List<String>, val publishers: Int, val subscribers: Int)

    /** After how long without messages with a publisher the QoS is suspected. */
    const val QOS_SUSPECT_MS = 4000L

    /**
     * `accepts`: whether some types are acceptable to the listener (null = any); `expectedLabel`, for the message.
     * `waitedMs`: time since it subscribed without receiving anything. Null if it is still too early to say anything.
     */
    fun diagnose(
        topic: String, accepts: ((List<String>) -> Boolean)?, expectedLabel: String, qos: QosSettings?, waitedMs: Long,
        connected: Boolean, topics: List<TopicInfo>?,
    ): Result? {
        if (!connected) return Result(tr("diagnosis_not_connected"))
        if (topics == null) return null // no graph yet
        val name = topic.trim().let { if (it.startsWith("/")) it else "/$it" }
        val t = topics.firstOrNull { it.name == name }
        if (t == null || t.publishers == 0) {
            // One with the same ending, of an acceptable type? (e.g. /robot1/scan instead of /scan)
            val last = name.substringAfterLast('/')
            val similar = topics.filter { o ->
                o.name != name && o.publishers > 0 && o.name.substringAfterLast('/') == last && (accepts == null || accepts(o.types))
            }.sortedBy { it.name.length }
            val base = if (t == null) tr("diagnosis_no_topic", name) else tr("diagnosis_no_publisher", name, t.subscribers)
            return similar.firstOrNull()?.let { Result(base + " " + tr("diagnosis_similar", it.name), Fix.UseTopic(it.name)) } ?: Result(base)
        }
        if (accepts != null && !accepts(t.types)) return Result(tr("diagnosis_wrong_type", name, t.types.joinToString(), expectedLabel))
        if (waitedMs < QOS_SUSPECT_MS || qos == null) return null
        // There is a publisher and nothing arrives: almost certainly the QoS.
        val latched = isLatchedName(name)
        return when {
            latched && qos.durability != QosSettings.TRANSIENT_LOCAL ->
                Result(tr("diagnosis_qos_latched", name, t.publishers), Fix.UseQos(QosSettings.RELIABLE, QosSettings.TRANSIENT_LOCAL))
            qos.reliability == QosSettings.RELIABLE ->
                Result(tr("diagnosis_qos_reliable", name, t.publishers), Fix.UseQos(QosSettings.BEST_EFFORT, null))
            qos.durability == QosSettings.TRANSIENT_LOCAL ->
                Result(tr("diagnosis_qos_transient", name, t.publishers), Fix.UseQos(null, QosSettings.VOLATILE))
            else -> Result(tr("diagnosis_publisher_silent", name, t.publishers))
        }
    }

    /** Those usually published once and latched (maps, descriptions, static ones). */
    fun isLatchedName(topic: String) = topic.endsWith("_static") || topic.endsWith("/map") || topic == "/map" ||
        topic.endsWith("robot_description") || topic.endsWith("_description") || topic.endsWith("/map_metadata")

    /** With the current graph of [RosConnection]. */
    fun diagnoseNow(topic: String, accepts: ((List<String>) -> Boolean)?, expectedLabel: String, qos: QosSettings?, waitedMs: Long): Result? {
        val connected = RosConnection.status is RosConnection.Status.Connected
        val topics = RosConnection.graph()?.topics(includeHidden = true)?.map { TopicInfo(it.name, it.types, it.publishers, it.subscribers) }
        return diagnose(topic, accepts, expectedLabel, qos, waitedMs, connected, topics)
    }
}
