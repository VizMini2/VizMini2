// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.plot

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.bt.ZmqReq
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Non-ROS data sources: JSON messages over UDP, MQTT
 * or ZeroMQ (SUB). Each message arrives as a map (nested JSON objects are maps too) and
 * the series read their fields with the same paths as ROS messages. They reconnect on their own.
 *
 * The series "topic" says where from: `udp://0.0.0.0:9870`, `mqtt://broker:1883/sensores/#`
 * (`mqtt://user:password@broker/...`, or `mqtts://` with TLS), `zmq://robot:9872` (or
 * `zmq://robot:9872/prefix`).
 */
abstract class JsonStream(val url: String, protected val onMessage: (Map<String, Any?>) -> Unit) {

    @Volatile var error: String? = null; protected set
    @Volatile var received = 0L; private set
    @Volatile protected var closed = false
    private val thread = Thread({ loop() }, "stream").apply { isDaemon = true }

    fun start() = thread.start()

    open fun close() { closed = true; thread.interrupt() }

    private fun loop() {
        while (!closed) {
            try {
                run()
            } catch (e: Exception) {
                if (closed) break
                error = e.message ?: e.javaClass.simpleName
                Log.w("VizMini2", "$url: ${e.message}")
                try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { break }
            }
        }
    }

    /** Connects and reads until it fails or is closed. */
    protected abstract fun run()

    /** A received message (JSON text). */
    protected fun deliver(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val text = String(bytes, offset, length, Charsets.UTF_8).trim()
        if (text.isEmpty()) return
        // A bare number (the usual thing for many MQTT sensors) counts as {"value": number}.
        val value = text.toDoubleOrNull() ?: try { JsonValues.parse(text) } catch (_: Exception) { return }
        val map = value as? Map<String, Any?> ?: mapOf("value" to value)
        received++
        error = null
        onMessage(map)
    }

    companion object {
        private const val RETRY_MS = 1500L

        fun isStream(topic: String) = topic.startsWith("udp://") || topic.startsWith("mqtt://") || topic.startsWith("mqtts://") || topic.startsWith("zmq://")

        /** Source for `url`, or null if it is not any of the types. */
        fun create(url: String, onMessage: (Map<String, Any?>) -> Unit): JsonStream? {
            val u = try { java.net.URI(url) } catch (_: Exception) { return null }
            val host = u.host ?: return null
            return when (u.scheme) {
                "udp" -> UdpJsonStream(url, u.port.takeIf { it > 0 } ?: 9870, onMessage)
                "mqtt", "mqtts" -> {
                    val tls = u.scheme == "mqtts"
                    val user = u.rawUserInfo?.substringBefore(':')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    val pass = u.rawUserInfo?.takeIf { ':' in it }?.substringAfter(':')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    MqttJsonStream(url, host, u.port.takeIf { it > 0 } ?: if (tls) 8883 else 1883, u.path.removePrefix("/").ifEmpty { "#" }, onMessage, tls, user, pass)
                }
                "zmq" -> ZmqSubJsonStream(url, host, u.port.takeIf { it > 0 } ?: 9872, u.path.removePrefix("/"), onMessage)
                else -> null
            }
        }
    }
}

/** JSON -> maps, lists, numbers, strings, bool and null. */
object JsonValues {
    fun parse(text: String): Any? = convert(if (text.startsWith("[")) JSONArray(text) else JSONObject(text))

    fun convert(v: Any?): Any? = when (v) {
        is JSONObject -> LinkedHashMap<String, Any?>().also { m -> for (k in v.keys()) m[k] = convert(v.opt(k)) }
        is JSONArray -> List(v.length()) { convert(v.opt(it)) }
        JSONObject.NULL -> null
        else -> v
    }
}

/** UDP datagrams with one JSON each. */
class UdpJsonStream(url: String, private val port: Int, onMessage: (Map<String, Any?>) -> Unit) : JsonStream(url, onMessage) {
    private var socket: DatagramSocket? = null

    override fun run() {
        DatagramSocket(null).use { s ->
            socket = s
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            s.soTimeout = 1000
            val buf = ByteArray(65536)
            while (!closed) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                deliver(p.data, p.offset, p.length)
            }
        }
    }

    override fun close() { super.close(); socket?.close() }
}

/**
 * Minimal MQTT 3.1.1 client: connects (with username and password if given, and TLS with the
 * system certificates if `tls`), subscribes to `filter` with QoS 0 and delivers the payload of each
 * PUBLISH.
 */
class MqttJsonStream(url: String, private val host: String, private val port: Int, private val filter: String,
                     onMessage: (Map<String, Any?>) -> Unit, private val tls: Boolean = false,
                     private val user: String? = null, private val password: String? = null) : JsonStream(url, onMessage) {
    private var socket: Socket? = null

    override fun run() {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), 5000)
        val sock = if (tls) (socketFactory ?: javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(plain, host, port, true).also {
            val ssl = it as javax.net.ssl.SSLSocket
            // Without this an SSLSocket does not check that the certificate belongs to this host.
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
        } else plain
        sock.use { s ->
            socket = s
            s.soTimeout = KEEPALIVE_S * 1000 / 2
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream().buffered())
            // CONNECT: protocol "MQTT" level 4, clean session, keepalive.
            val id = "vizmini2-" + (System.nanoTime() and 0xFFFFFF).toString(16)
            send(out, 0x10, ByteArrayOutputStream().apply {
                var flags = 0x02
                if (user != null) flags = flags or 0x80
                if (user != null && password != null) flags = flags or 0x40
                str(this, "MQTT"); write(4); write(flags); write(KEEPALIVE_S shr 8); write(KEEPALIVE_S and 0xFF); str(this, id)
                if (user != null) str(this, user)
                if (user != null && password != null) str(this, password)
            }.toByteArray())
            val (type, ack) = read(inp)
            if (type != 0x20 || ack.size < 2 || ack[1].toInt() != 0) throw IOException(when (ack.getOrNull(1)?.toInt()) {
                4 -> "MQTT: wrong user or password"; 5 -> "MQTT: not authorized"; else -> "MQTT: connection refused (${ack.getOrNull(1)})"
            })
            // SUBSCRIBE (id 1) to `filter` with QoS 0.
            send(out, 0x82, ByteArrayOutputStream().apply { write(0); write(1); str(this, filter); write(0) }.toByteArray())
            var lastSent = System.currentTimeMillis()
            while (!closed) {
                if (System.currentTimeMillis() - lastSent > KEEPALIVE_S * 500L) { send(out, 0xC0, ByteArray(0)); lastSent = System.currentTimeMillis() }
                val (t, body) = try { read(inp) } catch (_: SocketTimeoutException) { continue }
                if (t and 0xF0 == 0x30) {
                    val qos = (t shr 1) and 3
                    val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    var off = 2 + topicLen
                    if (qos > 0) {
                        // PUBACK in case the broker sends with QoS 1 anyway.
                        send(out, 0x40, byteArrayOf(body[off], body[off + 1]))
                        off += 2
                    }
                    deliver(body, off, body.size - off)
                }
            }
        }
    }

    override fun close() { super.close(); try { socket?.close() } catch (_: IOException) {} }

    private fun str(o: ByteArrayOutputStream, s: String) { val b = s.toByteArray(Charsets.UTF_8); o.write(b.size shr 8); o.write(b.size and 0xFF); o.write(b) }

    private fun send(o: OutputStream, type: Int, body: ByteArray) {
        val head = ByteArrayOutputStream()
        head.write(type)
        var n = body.size
        do { var d = n and 0x7F; n = n shr 7; if (n > 0) d = d or 0x80; head.write(d) } while (n > 0)
        o.write(head.toByteArray()); o.write(body); o.flush()
    }

    private fun read(i: DataInputStream): Pair<Int, ByteArray> {
        val type = i.readUnsignedByte()
        var len = 0; var mul = 1
        while (true) { val d = i.readUnsignedByte(); len += (d and 0x7F) * mul; if (d and 0x80 == 0) break; mul *= 128; if (mul > 128 * 128 * 128) throw IOException("MQTT: bad length") }
        val body = ByteArray(len); i.readFully(body)
        return type to body
    }

    companion object {
        private const val KEEPALIVE_S = 30
        /** For tests: a factory that trusts another CA (null = the system one). */
        @Volatile var socketFactory: javax.net.ssl.SSLSocketFactory? = null
    }
}

/**
 * ZeroMQ SUB socket (ZMTP 3.0, no encryption) connected to a PUB: subscribes to `prefix` (empty =
 * everything). The last part of each message is used (the first is usually the "topic").
 */
class ZmqSubJsonStream(url: String, private val host: String, private val port: Int, private val prefix: String,
                       onMessage: (Map<String, Any?>) -> Unit) : JsonStream(url, onMessage) {
    private var socket: Socket? = null

    override fun run() {
        Socket().use { s ->
            socket = s
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 1000
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream().buffered())
            out.write(ZmqReq.greeting()); out.flush()
            val peer = ByteArray(64)
            readFully(inp, peer)
            if (peer[0] != 0xFF.toByte() || peer[9] != 0x7F.toByte()) throw IOException("Not a ZeroMQ server")
            frame(out, 0x04, ZmqReq.readyCommand("SUB"))
            // Subscription (ZMTP 3.0): a message that starts with 1 followed by the prefix.
            frame(out, 0x00, byteArrayOf(1) + prefix.toByteArray(Charsets.UTF_8))
            out.flush()
            var last: ByteArray? = null
            while (!closed) {
                val (flags, body) = try { readFrame(inp) } catch (_: SocketTimeoutException) { continue }
                if (flags and 0x04 != 0) continue
                last = body
                if (flags and 0x01 == 0) { deliver(last); last = null }
            }
        }
    }

    override fun close() { super.close(); try { socket?.close() } catch (_: IOException) {} }

    /** Reads fully even if the timeout fires (the greeting can take a while). */
    private fun readFully(i: DataInputStream, b: ByteArray) {
        var off = 0
        val deadline = System.currentTimeMillis() + 5000
        while (off < b.size) {
            try { val n = i.read(b, off, b.size - off); if (n < 0) throw IOException("closed"); off += n }
            catch (e: SocketTimeoutException) { if (System.currentTimeMillis() > deadline || closed) throw e }
        }
    }

    private fun frame(o: OutputStream, flags: Int, body: ByteArray) {
        if (body.size > 255) { o.write(flags or 0x02); for (sh in 56 downTo 0 step 8) o.write(((body.size.toLong() shr sh) and 0xFF).toInt()) }
        else { o.write(flags); o.write(body.size) }
        o.write(body)
    }

    private fun readFrame(i: DataInputStream): Pair<Int, ByteArray> {
        val flags = i.readUnsignedByte()
        val size = if (flags and 0x02 != 0) i.readLong() else i.readUnsignedByte().toLong()
        if (size < 0 || size > (16L shl 20)) throw IOException("ZeroMQ frame too large")
        val b = ByteArray(size.toInt()); readFully(i, b)
        return flags to b
    }
}
