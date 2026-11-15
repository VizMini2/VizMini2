// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bt

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal ZeroMQ REQ socket (ZMTP 3.0 with the NULL mechanism, no encryption) over a TCP socket: just
 * enough to talk to a REP like Groot2Publisher's without pulling in a ZeroMQ library. One
 * blocking request at a time; on any error it is closed and a new one has to be created.
 *
 * With `socketType = "SUB"` it is a subscriber: [subscribe] and then [receive].
 */
class ZmqReq(host: String, port: Int, timeoutMs: Int = 3000, private val socketType: String = "REQ") : AutoCloseable {

    private val socket = Socket()
    private val input: DataInputStream
    private val output: OutputStream

    init {
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            input = DataInputStream(socket.getInputStream().buffered())
            output = socket.getOutputStream()
            handshake()
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    private fun handshake() {
        output.write(greeting())
        output.flush()
        val peer = ByteArray(GREETING_SIZE)
        input.readFully(peer)
        if (peer[0] != 0xFF.toByte() || peer[9] != 0x7F.toByte()) throw IOException("Not a ZeroMQ server")
        if (peer[10] < 3) throw IOException("ZeroMQ ${peer[10]}.x is not supported (needs ZMTP 3)")
        val mechanism = String(peer, 12, 20, Charsets.US_ASCII).trimEnd('\u0000')
        if (mechanism != "NULL") throw IOException("ZeroMQ security '$mechanism' is not supported")
        writeFrame(FLAG_COMMAND, readyCommand(socketType))
        output.flush()
        // The READY from the other side (or an ERROR).
        val (flags, body) = readFrame()
        if (flags and FLAG_COMMAND == 0) throw IOException("ZeroMQ handshake failed")
        val name = String(body, 1, body[0].toInt() and 0xFF, Charsets.US_ASCII)
        if (name == "ERROR") throw IOException("ZeroMQ: " + String(body, 7, body.size - 7, Charsets.UTF_8))
    }

    /** Sends a multipart request and returns the parts of the reply. */
    fun request(parts: List<ByteArray>): List<ByteArray> {
        writeFrame(FLAG_MORE, ByteArray(0)) // Empty REQ delimiter.
        parts.forEachIndexed { i, p -> writeFrame(if (i < parts.size - 1) FLAG_MORE else 0, p) }
        output.flush()
        val reply = ArrayList<ByteArray>()
        while (true) {
            val (flags, body) = readFrame()
            if (flags and FLAG_COMMAND != 0) continue // PING and other commands: ignored.
            reply += body
            if (flags and FLAG_MORE == 0) break
        }
        if (reply.firstOrNull()?.isEmpty() == true) reply.removeAt(0)
        return reply
    }

    /** SUB: subscribe to messages starting with `prefix` (ZMTP 3.0: a 0x01 message + prefix). */
    fun subscribe(prefix: ByteArray = ByteArray(0)) {
        writeFrame(0, byteArrayOf(1) + prefix)
        output.flush()
    }

    /** SUB: the next message (its parts); throws if the timeout passes with nothing. */
    fun receive(): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        while (true) {
            val (flags, body) = readFrame()
            if (flags and FLAG_COMMAND != 0) continue
            parts += body
            if (flags and FLAG_MORE == 0) return parts
        }
    }

    private fun writeFrame(flags: Int, body: ByteArray) {
        if (body.size > 255) {
            output.write(flags or FLAG_LONG)
            val n = body.size.toLong()
            for (shift in 56 downTo 0 step 8) output.write((n shr shift).toInt() and 0xFF)
        } else {
            output.write(flags)
            output.write(body.size)
        }
        output.write(body)
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val flags = input.readUnsignedByte()
        val size = if (flags and FLAG_LONG != 0) input.readLong() else input.readUnsignedByte().toLong()
        if (size < 0 || size > MAX_FRAME) throw IOException("ZeroMQ frame too large: $size bytes")
        val body = ByteArray(size.toInt())
        input.readFully(body)
        return flags to body
    }

    override fun close() { try { socket.close() } catch (_: IOException) {} }

    companion object {
        private const val GREETING_SIZE = 64
        private const val FLAG_MORE = 0x01
        private const val FLAG_LONG = 0x02
        private const val FLAG_COMMAND = 0x04
        private const val MAX_FRAME = 64L shl 20

        /** ZMTP 3.0 greeting: signature, version, NULL mechanism and as-server = 0. */
        fun greeting(): ByteArray = ByteArray(GREETING_SIZE).apply {
            this[0] = 0xFF.toByte(); this[9] = 0x7F
            this[10] = 3; this[11] = 0
            "NULL".toByteArray(Charsets.US_ASCII).copyInto(this, 12)
        }

        /** READY command with the Socket-Type property. */
        fun readyCommand(socketType: String): ByteArray = ByteArrayOutputStream().apply {
            write(5); write("READY".toByteArray(Charsets.US_ASCII))
            val key = "Socket-Type".toByteArray(Charsets.US_ASCII)
            val value = socketType.toByteArray(Charsets.US_ASCII)
            write(key.size); write(key)
            write(0); write(0); write(0); write(value.size); write(value)
        }.toByteArray()
    }
}
