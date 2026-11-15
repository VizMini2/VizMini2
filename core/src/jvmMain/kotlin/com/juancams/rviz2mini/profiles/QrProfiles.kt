// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.profiles

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import org.json.JSONObject

/**
 * Profiles in QR codes: the profile JSON compressed (deflate) and in Base64 URL, with a prefix to
 * recognize it ("R2M1:"). It can carry the full profile or just the connection (domain, node, peers,
 * server), which always fits and works as a "sticker" on the robot. No Android: tested on the JVM.
 */
object QrProfiles {

    private const val PREFIX = "R2M1:"
    /** What fits comfortably in a QR readable by a phone (correction level M). */
    const val MAX_PAYLOAD = 1800
    /** Per QR when split across several: less dense, reads well from afar or from a monitor. */
    const val PART_PAYLOAD = 1000

    data class Decoded(val profile: RobotProfile, val connectionOnly: Boolean)

    fun encode(p: RobotProfile, connectionOnly: Boolean): String {
        val json = RobotProfiles.toJson(if (connectionOnly) p.copy(tabs = emptyList()) else p)
        if (connectionOnly) json.put("connectionOnly", true)
        val raw = shortIds(json.toString()).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun fits(payload: String, max: Int = MAX_PAYLOAD) = payload.length <= max

    private val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /**
     * Replaces the random ids (36 characters, they do not compress) with short ones unique within the profile: they take
     * the most space. On import they are regenerated anyway ([RobotProfile.tabsWithFreshIds]); also those inside the
     * JSON settings (dashboard layout, displays...), because the whole text is substituted.
     */
    internal fun shortIds(json: String): String {
        val map = LinkedHashMap<String, String>()
        return UUID_RE.replace(json) { m -> map.getOrPut(m.value) { "q" + map.size.toString(36) } }
    }

    // ---------------------------------------------------------------- multiple QR codes

    private const val PART_PREFIX = "R2MP:"

    /**
     * A profile that does not fit in one QR, split across several: "R2MP:<batch>:<no.>:<total>:<chunk>" (the chunks together are the
     * usual "R2M1:..."). `max`: characters per QR.
     */
    fun parts(payload: String, max: Int = PART_PAYLOAD, single: Int = MAX_PAYLOAD): List<String> {
        if (payload.length <= single) return listOf(payload)
        val set = (payload.hashCode().toLong() and 0xFFFFFF).toString(36)
        val chunk = max - 24
        val pieces = payload.chunked(chunk)
        return pieces.mapIndexed { i, c -> "$PART_PREFIX$set:${i + 1}:${pieces.size}:$c" }
    }

    fun isPart(text: String) = text.trim().startsWith(PART_PREFIX)

    /** Joins the QR codes of a multi-part profile, in any order. */
    class Assembler {
        private var set: String? = null
        private val got = HashMap<Int, String>()
        var total = 0; private set
        val count get() = got.size

        /** Adds a chunk; returns the complete "R2M1:..." when all are in, or null. */
        fun add(text: String): String? {
            val t = text.trim().removePrefix(PART_PREFIX)
            val f = t.split(':', limit = 4)
            if (f.size < 4) throw IllegalArgumentException("The QR code is damaged")
            val (s, i, n, c) = f
            if (s != set) { set = s; got.clear() }
            total = n.toIntOrNull() ?: throw IllegalArgumentException("The QR code is damaged")
            got[i.toIntOrNull() ?: throw IllegalArgumentException("The QR code is damaged")] = c
            if (got.size != total || (1..total).any { it !in got }) return null
            return (1..total).joinToString("") { got.getValue(it) }
        }

        fun reset() { set = null; got.clear(); total = 0 }
    }

    /** Throws IllegalArgumentException with a user-facing text if it is not a VizMini2 QR. */
    fun decode(text: String): Decoded {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) throw IllegalArgumentException("This QR code is not an VizMini2 profile")
        val bytes = try { Base64.getUrlDecoder().decode(t.removePrefix(PREFIX)) } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The QR code is damaged")
        }
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 1_000_000) throw IllegalArgumentException("The QR code is damaged")
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw IllegalArgumentException("The QR code is damaged")
        } finally {
            inflater.end()
        }
        val o = try { JSONObject(out.toString(Charsets.UTF_8.name())) } catch (e: Exception) { throw IllegalArgumentException("The QR code is damaged") }
        val p = RobotProfiles.fromJson(o) ?: throw IllegalArgumentException("The profile in the QR code is incomplete")
        return Decoded(p, o.optBoolean("connectionOnly"))
    }
}
