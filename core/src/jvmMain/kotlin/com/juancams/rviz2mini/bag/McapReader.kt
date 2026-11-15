// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.PriorityQueue
import java.util.zip.CRC32

/** A bag schema. For ROS 2: `name` = "std_msgs/msg/String", encoding "ros2msg". */
class McapSchema(val id: Int, val name: String, val encoding: String, val data: ByteArray) {
    val text: String get() = String(data, Charsets.UTF_8)
    override fun toString() = "McapSchema($id, $name, $encoding)"
}

/** A channel (topic). `schemaName` is the ROS type ("" if it has no schema). */
data class McapChannel(
    val id: Int,
    val schemaId: Int,
    val topic: String,
    val messageEncoding: String,
    val metadata: Map<String, String>,
    val schemaName: String
)

/** A message. `data` is the payload as is (in CDR it includes the 4 encapsulation bytes). */
class McapMessage(val channelId: Int, val sequence: Int, val logTimeNs: Long, val publishTimeNs: Long, val data: ByteArray) {
    override fun toString() = "McapMessage(ch=$channelId, t=$logTimeNs, ${data.size} B)"
}

class McapMetadata(val name: String, val metadata: Map<String, String>)

/**
 * Reads MCAP files: those from [McapWriter] and those from rosbag2 (uncompressed, "lz4" or "zstd" chunks).
 * If the file has a summary (the usual case) only the summary is read on open; if not (cut-off
 * recording, other writers) the file is scanned once. Messages are read chunk by chunk with
 * RandomAccessFile, without loading the whole file.
 *
 * Other compressions throw [UnsupportedCompression] on open.
 */
/** Chunk compressions that can be read. */
private val SUPPORTED = setOf("", "lz4", "zstd")

class McapReader private constructor(file: File) : Closeable {

    /** A piece of the data section: a chunk or a standalone message, with its time range. */
    private class Block(val offset: Long, val length: Long, val start: Long, val end: Long)

    private class Pending(val msg: McapMessage, val order: Long)

    private val raf = RandomAccessFile(file, "r")
    private val fileLength = raf.length()

    var profile = ""; private set
    var library = ""; private set

    private val schemaMap = LinkedHashMap<Int, McapSchema>()
    private val rawChannels = LinkedHashMap<Int, McapChannel>()
    private val counts = LinkedHashMap<Int, Long>()
    private val metadataList = ArrayList<McapMetadata>()
    private val blocks = ArrayList<Block>()

    val schemas: Map<Int, McapSchema> get() = schemaMap

    /** Channels by id, with the type name already resolved. */
    lateinit var channels: Map<Int, McapChannel>
        private set

    var startTimeNs = 0L; private set
    var endTimeNs = 0L; private set
    var messageCount = 0L; private set

    /** Messages per channel (id -> count). */
    val channelMessageCounts: Map<Int, Long> get() = counts

    val metadata: List<McapMetadata> get() = metadataList

    val chunkCount: Int get() = blocks.size

    /** true if the file's summary was used; false if it had to be scanned entirely. */
    var usedSummary = false; private set

    val durationNs: Long get() = endTimeNs - startTimeNs

    init {
        try {
            readHeader()
            usedSummary = tryReadSummary()
            if (!usedSummary) {
                schemaMap.clear(); rawChannels.clear(); counts.clear(); metadataList.clear(); blocks.clear()
                linearScan()
            }
            channels = rawChannels.mapValues { (_, c) -> c.copy(schemaName = schemaMap[c.schemaId]?.name ?: "") }
        } catch (e: Throwable) {
            raf.close()
            throw e
        }
    }

    // ---------------------------------------------------------------- low-level reading

    private fun readAt(pos: Long, n: Int): ByteArray = synchronized(raf) {
        if (n < 0 || pos < 0 || pos + n > fileLength) throw McapException("Truncated MCAP file")
        val b = ByteArray(n)
        raf.seek(pos)
        raf.readFully(b)
        b
    }

    private fun readHeader() {
        if (fileLength < Mcap.MAGIC.size + Mcap.RECORD_HEADER) throw McapException("Not an MCAP file")
        val head = readAt(0, Mcap.MAGIC.size + Mcap.RECORD_HEADER)
        for (i in Mcap.MAGIC.indices) if (head[i] != Mcap.MAGIC[i]) throw McapException("Not an MCAP file")
        if (head[8].toInt() != Mcap.OP_HEADER) throw McapException("MCAP file without header")
        val len = LeReader.le64(head, 9)
        if (len < 0 || len > 1 shl 20) throw McapException("Bad MCAP header")
        val r = LeReader(readAt(17, len.toInt()))
        profile = r.str()
        library = r.str()
    }

    /** Iterates over the records of a buffer calling `fn(opcode, body reader)`. */
    private inline fun forEachRecord(buf: ByteArray, from: Int, to: Int, fn: (Int, LeReader) -> Unit) {
        var p = from
        while (p + Mcap.RECORD_HEADER <= to) {
            val op = buf[p].toInt() and 0xFF
            val len = LeReader.le64(buf, p + 1)
            val start = p + Mcap.RECORD_HEADER
            if (len < 0 || start + len > to) throw McapException("Truncated MCAP record")
            fn(op, LeReader(buf, start, (start + len).toInt()))
            p = (start + len).toInt()
        }
    }

    private fun parseSchema(r: LeReader) {
        val id = r.u16()
        val name = r.str()
        val enc = r.str()
        val data = r.bytes(r.len32())
        if (id != 0) schemaMap[id] = McapSchema(id, name, enc, data)
    }

    private fun parseChannel(r: LeReader) {
        val id = r.u16()
        val schemaId = r.u16()
        val topic = r.str()
        val enc = r.str()
        val meta = r.strMap()
        rawChannels[id] = McapChannel(id, schemaId, topic, enc, meta, "")
    }

    private fun parseMessage(r: LeReader): McapMessage {
        val ch = r.u16()
        val seq = r.u32().toInt()
        val log = r.u64()
        val pub = r.u64()
        return McapMessage(ch, seq, log, pub, r.bytes(r.remaining))
    }

    private fun parseMetadata(r: LeReader) {
        val name = r.str()
        metadataList.add(McapMetadata(name, r.strMap()))
    }

    /**
     * Body of a Chunk record -> decompressed records. Checks the compression and the CRC
     * (if it is not 0, which means "not computed").
     */
    private fun chunkRecords(r: LeReader): ByteArray {
        r.u64(); r.u64()
        val usize = r.u64()
        val crc = r.u32()
        val compression = r.str()
        val n = r.len64()
        if (usize < 0 || usize > Int.MAX_VALUE - 16) throw McapException("MCAP chunk too large")
        val out = when (compression) {
            "" -> r.buf.copyOfRange(r.pos, r.pos + n)
            "lz4" -> Lz4Frame.decompress(r.buf, r.pos, n, usize.toInt())
            // zstd with the reference library (zstd-jni, 16 KB aligned).
            "zstd" -> com.github.luben.zstd.Zstd.decompress(r.buf.copyOfRange(r.pos, r.pos + n), usize.toInt())
            else -> throw UnsupportedCompression(compression)
        }
        if (out.size.toLong() != usize) throw McapException("MCAP chunk has the wrong uncompressed size")
        if (crc != 0L) {
            val c = CRC32().apply { update(out, 0, out.size) }.value
            if (c != crc) throw McapException("MCAP chunk CRC mismatch")
        }
        return out
    }

    private fun checkCompression(compression: String) {
        if (compression !in SUPPORTED) throw UnsupportedCompression(compression)
    }

    // ---------------------------------------------------------------- summary

    /** Reads the summary. Returns false if there is none or it is not enough to avoid scanning the file. */
    private fun tryReadSummary(): Boolean {
        val footerPos = fileLength - Mcap.MAGIC.size - Mcap.FOOTER_RECORD
        if (footerPos < Mcap.MAGIC.size) return false
        val tail = readAt(footerPos, Mcap.FOOTER_RECORD + Mcap.MAGIC.size)
        for (i in Mcap.MAGIC.indices) if (tail[Mcap.FOOTER_RECORD + i] != Mcap.MAGIC[i]) return false
        if (tail[0].toInt() != Mcap.OP_FOOTER || LeReader.le64(tail, 1) != 20L) return false
        val summaryStart = LeReader.le64(tail, 9)
        if (summaryStart <= 0 || summaryStart >= footerPos) return false
        if (footerPos - summaryStart > Int.MAX_VALUE) return false
        val summary = readAt(summaryStart, (footerPos - summaryStart).toInt())

        var stats: LongArray? = null // message_count, channel_count, chunk_count, start, end
        val chunkIdx = ArrayList<Block>()
        val metaIdx = ArrayList<Pair<Long, Long>>()
        try {
            forEachRecord(summary, 0, summary.size) { op, r ->
                when (op) {
                    Mcap.OP_SCHEMA -> parseSchema(r)
                    Mcap.OP_CHANNEL -> parseChannel(r)
                    Mcap.OP_STATISTICS -> {
                        val msgs = r.u64()
                        r.u16()
                        val chans = r.u32()
                        r.u32(); r.u32()
                        val chunks = r.u32()
                        val start = r.u64()
                        val end = r.u64()
                        val n = r.len32()
                        val stop = r.pos + n
                        while (r.pos < stop) { val ch = r.u16(); counts[ch] = r.u64() }
                        stats = longArrayOf(msgs, chans, chunks, start, end)
                    }
                    Mcap.OP_CHUNK_INDEX -> {
                        val start = r.u64()
                        val end = r.u64()
                        val off = r.u64()
                        val len = r.u64()
                        r.skip(r.len32()) // MessageIndex offsets
                        r.u64()
                        checkCompression(r.str())
                        chunkIdx.add(Block(off, len, start, end))
                    }
                    Mcap.OP_METADATA_INDEX -> metaIdx.add(r.u64() to r.u64())
                }
            }
        } catch (e: UnsupportedCompression) {
            throw e
        } catch (_: McapException) {
            return false
        }
        val s = stats ?: return false
        // No chunks but there are messages: they are standalone in the data section; it has to be scanned.
        if (s[2] != chunkIdx.size.toLong() || (s[0] > 0 && chunkIdx.isEmpty())) return false
        if (s[1] != rawChannels.size.toLong()) return false
        for (b in chunkIdx) if (b.offset < 0 || b.length < 0 || b.offset + b.length > fileLength) return false

        messageCount = s[0]
        startTimeNs = s[3]
        endTimeNs = s[4]
        blocks.addAll(chunkIdx)
        for ((off, len) in metaIdx) {
            try {
                val rec = readAt(off, len.toInt())
                forEachRecord(rec, 0, rec.size) { op, r -> if (op == Mcap.OP_METADATA) parseMetadata(r) }
            } catch (_: McapException) { }
        }
        return true
    }

    // ---------------------------------------------------------------- linear scan

    /** No summary: scans the data section record by record (tolerates a truncated end). */
    private fun linearScan() {
        var pos = Mcap.MAGIC.size.toLong()
        var minT = Long.MAX_VALUE
        var maxT = Long.MIN_VALUE
        var total = 0L
        while (pos + Mcap.RECORD_HEADER <= fileLength) {
            val head = readAt(pos, Mcap.RECORD_HEADER)
            val op = head[0].toInt() and 0xFF
            val len = LeReader.le64(head, 1)
            val bodyAt = pos + Mcap.RECORD_HEADER
            if (len < 0 || bodyAt + len > fileLength) break // recording cut off halfway
            if (op == Mcap.OP_DATA_END || op == Mcap.OP_FOOTER) break
            when (op) {
                Mcap.OP_SCHEMA, Mcap.OP_CHANNEL, Mcap.OP_METADATA, Mcap.OP_MESSAGE, Mcap.OP_CHUNK -> {
                    if (len > Int.MAX_VALUE) throw McapException("MCAP record too large")
                    val body = readAt(bodyAt, len.toInt())
                    val r = LeReader(body)
                    when (op) {
                        Mcap.OP_SCHEMA -> parseSchema(r)
                        Mcap.OP_CHANNEL -> parseChannel(r)
                        Mcap.OP_METADATA -> parseMetadata(r)
                        Mcap.OP_MESSAGE -> {
                            val ch = r.u16(); r.u32()
                            val t = r.u64()
                            counts[ch] = (counts[ch] ?: 0L) + 1
                            total++
                            minT = minOf(minT, t); maxT = maxOf(maxT, t)
                            blocks.add(Block(pos, Mcap.RECORD_HEADER + len, t, t))
                        }
                        else -> {
                            val recs = chunkRecords(r)
                            var cMin = Long.MAX_VALUE
                            var cMax = Long.MIN_VALUE
                            forEachRecord(recs, 0, recs.size) { cop, cr ->
                                when (cop) {
                                    Mcap.OP_SCHEMA -> parseSchema(cr)
                                    Mcap.OP_CHANNEL -> parseChannel(cr)
                                    Mcap.OP_MESSAGE -> {
                                        val ch = cr.u16(); cr.u32()
                                        val t = cr.u64()
                                        counts[ch] = (counts[ch] ?: 0L) + 1
                                        total++
                                        cMin = minOf(cMin, t); cMax = maxOf(cMax, t)
                                    }
                                }
                            }
                            if (cMin <= cMax) {
                                blocks.add(Block(pos, Mcap.RECORD_HEADER + len, cMin, cMax))
                                minT = minOf(minT, cMin); maxT = maxOf(maxT, cMax)
                            }
                        }
                    }
                }
            }
            pos = bodyAt + len
        }
        messageCount = total
        if (total > 0) { startTimeNs = minT; endTimeNs = maxT }
    }

    // ---------------------------------------------------------------- messages

    private fun readBlock(b: Block, sink: (McapMessage) -> Unit) {
        if (b.length > Int.MAX_VALUE) throw McapException("MCAP chunk too large")
        val rec = readAt(b.offset, b.length.toInt())
        forEachRecord(rec, 0, rec.size) { op, r ->
            when (op) {
                Mcap.OP_MESSAGE -> sink(parseMessage(r))
                Mcap.OP_CHUNK -> {
                    val recs = chunkRecords(r)
                    forEachRecord(recs, 0, recs.size) { cop, cr -> if (cop == Mcap.OP_MESSAGE) sink(parseMessage(cr)) }
                }
            }
        }
    }

    /**
     * Messages with `startNs <= log_time <= endNs` (and from `channelIds`, if given) in
     * log_time order; on ties, in file order. Only keeps in memory the chunks that
     * overlap in time with the current message. Can be iterated several times.
     */
    fun messages(startNs: Long = Long.MIN_VALUE, endNs: Long = Long.MAX_VALUE, channelIds: Set<Int>? = null): Sequence<McapMessage> = sequence {
        val sel = blocks.filter { it.end >= startNs && it.start <= endNs }.sortedWith(compareBy<Block>({ it.start }, { it.offset }))
        val queue = PriorityQueue<Pending>(compareBy<Pending>({ it.msg.logTimeNs }, { it.order }))
        var i = 0
        var order = 0L
        while (true) {
            // Loads all blocks that may contain something earlier than the first pending message.
            while (i < sel.size && (queue.isEmpty() || sel[i].start <= queue.peek()!!.msg.logTimeNs)) {
                readBlock(sel[i]) { m ->
                    if (m.logTimeNs in startNs..endNs && (channelIds == null || m.channelId in channelIds)) queue.add(Pending(m, order++))
                }
                i++
            }
            val p = queue.poll() ?: break
            yield(p.msg)
        }
    }

    override fun close() = synchronized(raf) { raf.close() }

    companion object {
        fun open(file: File): McapReader = McapReader(file)
    }
}
