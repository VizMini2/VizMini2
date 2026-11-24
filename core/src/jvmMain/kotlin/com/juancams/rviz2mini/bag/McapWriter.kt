// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.bag

import java.io.Closeable
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Writes an MCAP file in streaming fashion, as rosbag2 (Jazzy) does with `-s mcap`: header with
 * "ros2" profile, messages grouped in uncompressed chunks (~1 MiB) followed by their MessageIndex,
 * and on close a summary with Schema, Channel, Statistics, ChunkIndex and MetadataIndex plus their
 * SummaryOffset and the Footer. This way `ros2 bag info` / `ros2 bag play` read it without scanning the file.
 *
 * Computes the CRC32 of each chunk, of the data section and of the summary. Not thread-safe: whoever
 * records from several threads must synchronize the calls.
 *
 * Schema and channel ids start at 1 (schema 0 means "no schema").
 *
 * If `rosDistro` is not null, on close it adds the "rosbag2" Metadata record with the
 * `serialized_metadata` that rosbag2 writes (so `ros2 bag info` shows the distro). `fileName`
 * is only used for that YAML (relative_file_paths).
 */
class McapWriter(
    out: OutputStream,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    profile: String = "ros2",
    library: String = "vizmini2",
    private val rosDistro: String? = "jazzy",
    private val fileName: String = "vizmini2_0.mcap"
) : Closeable {

    /** Output that counts bytes and computes a resettable CRC32. */
    private class CrcOut(private val out: OutputStream) {
        var position = 0L
            private set
        val crc = CRC32()

        fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
            out.write(b, off, len)
            crc.update(b, off, len)
            position += len
        }

        fun write(buf: LeBuffer) = write(buf.array, 0, buf.size)

        fun flush() = out.flush()
        fun close() = out.close()
    }

    private class Schema(val name: String, val record: ByteArray)
    private class Channel(val schemaId: Int, val topic: String, val encoding: String, val metadata: Map<String, String>, val record: ByteArray)

    /** List of (log_time, offset) pairs of a channel within the current chunk. */
    private class IndexList {
        var data = LongArray(64)
        var n = 0
        fun add(time: Long, offset: Long) {
            if (n + 2 > data.size) data = data.copyOf(data.size * 2)
            data[n++] = time; data[n++] = offset
        }
    }

    private val out = CrcOut(out)
    private val schemas = LinkedHashMap<Int, Schema>()
    private val channels = LinkedHashMap<Int, Channel>()
    private val pendingMetadata = ArrayList<Pair<String, Map<String, String>>>()

    // Chunk under construction.
    private val chunk = LeBuffer(chunkSize + chunkSize / 8)
    private val chunkIndex = LinkedHashMap<Int, IndexList>()
    private var chunkStartTime = Long.MAX_VALUE
    private var chunkEndTime = Long.MIN_VALUE

    // For the summary.
    private val chunkIndexRecords = LeBuffer(4096)
    private val metadataIndexRecords = LeBuffer(256)
    private val channelCounts = LinkedHashMap<Int, Long>()
    private var chunkCount = 0
    private var startTime = Long.MAX_VALUE
    private var endTime = Long.MIN_VALUE
    private var closed = false

    /** Messages written so far. */
    var messageCount = 0L
        private set

    /** Bytes written to the file so far (not counting the chunk not yet flushed). */
    val bytesWritten: Long get() = out.position

    init {
        val b = LeBuffer(64)
        b.bytes(Mcap.MAGIC)
        val at = b.beginRecord(Mcap.OP_HEADER)
        b.str(profile)
        b.str(library)
        b.endRecord(at)
        this.out.write(b)
    }

    /**
     * Registers a schema. For ROS 2: `name` = "sensor_msgs/msg/LaserScan", encoding "ros2msg" and
     * `data` = the .msg with its dependencies (see [RosSchemas.definition]).
     */
    fun addSchema(name: String, encoding: String = "ros2msg", data: String): Int = addSchema(name, encoding, data.toByteArray(Charsets.UTF_8))

    fun addSchema(name: String, encoding: String, data: ByteArray): Int {
        check(!closed) { "McapWriter is closed" }
        val id = schemas.size + 1
        require(id <= 0xFFFF) { "Too many schemas" }
        val b = LeBuffer(data.size + name.length + 32)
        val at = b.beginRecord(Mcap.OP_SCHEMA)
        b.u16(id)
        b.str(name)
        b.str(encoding)
        b.u32(data.size)
        b.bytes(data)
        b.endRecord(at)
        val record = b.toByteArray()
        schemas[id] = Schema(name, record)
        // Like libmcap: the schema also goes inside the chunk so a linear read finds it.
        chunk.bytes(record)
        return id
    }

    /**
     * Registers a channel (a topic). rosbag2 stores the key "offered_qos_profiles" in `metadata`
     * (see [RosSchemas.offeredQosProfiles]).
     */
    fun addChannel(schemaId: Int, topic: String, messageEncoding: String = "cdr", metadata: Map<String, String> = emptyMap()): Int {
        check(!closed) { "McapWriter is closed" }
        require(schemaId == 0 || schemaId in schemas) { "Unknown schema id $schemaId" }
        val id = channels.size + 1
        require(id <= 0xFFFF) { "Too many channels" }
        val b = LeBuffer(256)
        val at = b.beginRecord(Mcap.OP_CHANNEL)
        b.u16(id)
        b.u16(schemaId)
        b.str(topic)
        b.str(messageEncoding)
        b.strMap(metadata)
        b.endRecord(at)
        val record = b.toByteArray()
        channels[id] = Channel(schemaId, topic, messageEncoding, metadata, record)
        channelCounts[id] = 0L
        chunk.bytes(record)
        return id
    }

    /**
     * Writes a message. `data` is the complete serialized payload (in CDR, with the 4 encapsulation
     * bytes in front). Times are nanoseconds since the epoch.
     */
    fun writeMessage(channelId: Int, logTimeNs: Long, publishTimeNs: Long, data: ByteArray, sequence: Int = 0) =
        writeMessage(channelId, logTimeNs, publishTimeNs, data, 0, data.size, sequence)

    fun writeMessage(channelId: Int, logTimeNs: Long, publishTimeNs: Long, data: ByteArray, offset: Int, length: Int, sequence: Int = 0) {
        check(!closed) { "McapWriter is closed" }
        require(channelId in channels) { "Unknown channel id $channelId" }
        val recordOffset = chunk.size.toLong()
        chunk.u8(Mcap.OP_MESSAGE)
        chunk.u64(2L + 4 + 8 + 8 + length)
        chunk.u16(channelId)
        chunk.u32(sequence)
        chunk.u64(logTimeNs)
        chunk.u64(publishTimeNs)
        chunk.bytes(data, offset, length)
        chunkIndex.getOrPut(channelId) { IndexList() }.add(logTimeNs, recordOffset)
        if (logTimeNs < chunkStartTime) chunkStartTime = logTimeNs
        if (logTimeNs > chunkEndTime) chunkEndTime = logTimeNs
        if (logTimeNs < startTime) startTime = logTimeNs
        if (logTimeNs > endTime) endTime = logTimeNs
        messageCount++
        channelCounts[channelId] = (channelCounts[channelId] ?: 0L) + 1
        if (chunk.size >= chunkSize) flushChunk()
    }

    /** Adds a Metadata record (written on close, at the end of the data section). */
    fun addMetadata(name: String, metadata: Map<String, String>) {
        check(!closed) { "McapWriter is closed" }
        pendingMetadata.add(name to LinkedHashMap(metadata))
    }

    /** Flushes the current chunk (if it has anything) and the underlying output. */
    fun flush() {
        if (closed) return
        flushChunk()
        out.flush()
    }

    private fun flushChunk() {
        if (chunk.size == 0) return
        val hasMessages = chunkStartTime != Long.MAX_VALUE
        val start = if (hasMessages) chunkStartTime else 0L
        val end = if (hasMessages) chunkEndTime else 0L
        val crc = CRC32().apply { update(chunk.array, 0, chunk.size) }.value

        // Chunk record (uncompressed).
        val chunkStart = out.position
        val head = LeBuffer(64)
        val at = head.beginRecord(Mcap.OP_CHUNK)
        head.u64(start)
        head.u64(end)
        head.u64(chunk.size.toLong())
        head.u32(crc)
        head.str("")
        head.u64(chunk.size.toLong())
        head.patchU64(at, (head.size - at - 8 + chunk.size).toLong())
        out.write(head)
        out.write(chunk)
        val chunkLength = out.position - chunkStart

        // One MessageIndex per channel, sorted by time.
        val indexStart = out.position
        val indexOffsets = LinkedHashMap<Int, Long>()
        val idx = LeBuffer(1024)
        for (channelId in chunkIndex.keys.sorted()) {
            val list = chunkIndex.getValue(channelId)
            val pairs = (0 until list.n / 2).sortedBy { list.data[2 * it] }
            idx.reset()
            val rat = idx.beginRecord(Mcap.OP_MESSAGE_INDEX)
            idx.u16(channelId)
            idx.u32(pairs.size * 16)
            for (p in pairs) { idx.u64(list.data[2 * p]); idx.u64(list.data[2 * p + 1]) }
            idx.endRecord(rat)
            indexOffsets[channelId] = out.position
            out.write(idx)
        }
        val indexLength = out.position - indexStart

        // ChunkIndex for the summary.
        val ci = chunkIndexRecords
        val cat = ci.beginRecord(Mcap.OP_CHUNK_INDEX)
        ci.u64(start)
        ci.u64(end)
        ci.u64(chunkStart)
        ci.u64(chunkLength)
        ci.u32(indexOffsets.size * 10)
        for ((ch, off) in indexOffsets) { ci.u16(ch); ci.u64(off) }
        ci.u64(indexLength)
        ci.str("")
        ci.u64(chunk.size.toLong())
        ci.u64(chunk.size.toLong())
        ci.endRecord(cat)

        chunkCount++
        chunk.reset()
        chunkIndex.clear()
        chunkStartTime = Long.MAX_VALUE
        chunkEndTime = Long.MIN_VALUE
    }

    /** Finishes the file (last chunk, DataEnd, summary and footer) and closes the output. */
    override fun close() {
        if (closed) return
        flushChunk()
        if (rosDistro != null) pendingMetadata.add("rosbag2" to mapOf("serialized_metadata" to rosbag2Metadata(rosDistro)))
        closed = true

        // Pending Metadata, at the end of the data section.
        val b = LeBuffer(1024)
        for ((name, map) in pendingMetadata) {
            b.reset()
            val at = b.beginRecord(Mcap.OP_METADATA)
            b.str(name)
            b.strMap(map)
            b.endRecord(at)
            val offset = out.position
            out.write(b)
            val mat = metadataIndexRecords.beginRecord(Mcap.OP_METADATA_INDEX)
            metadataIndexRecords.u64(offset)
            metadataIndexRecords.u64(b.size.toLong())
            metadataIndexRecords.str(name)
            metadataIndexRecords.endRecord(mat)
        }

        // DataEnd with the CRC of everything before it (from the magic).
        val dataCrc = out.crc.value
        b.reset()
        val dat = b.beginRecord(Mcap.OP_DATA_END)
        b.u32(dataCrc)
        b.endRecord(dat)
        out.write(b)

        // Summary: each group of records with its SummaryOffset.
        val summaryStart = out.position
        out.crc.reset()
        val offsets = LeBuffer(256)
        fun group(opcode: Int, write: () -> Unit) {
            val gs = out.position
            write()
            val len = out.position - gs
            if (len == 0L) return
            val at = offsets.beginRecord(Mcap.OP_SUMMARY_OFFSET)
            offsets.u8(opcode)
            offsets.u64(gs)
            offsets.u64(len)
            offsets.endRecord(at)
        }
        group(Mcap.OP_SCHEMA) { for (s in schemas.values) out.write(s.record) }
        group(Mcap.OP_CHANNEL) { for (c in channels.values) out.write(c.record) }
        group(Mcap.OP_STATISTICS) {
            b.reset()
            val at = b.beginRecord(Mcap.OP_STATISTICS)
            b.u64(messageCount)
            b.u16(schemas.size)
            b.u32(channels.size)
            b.u32(0) // attachments
            b.u32(pendingMetadata.size)
            b.u32(chunkCount)
            b.u64(if (messageCount > 0) startTime else 0L)
            b.u64(if (messageCount > 0) endTime else 0L)
            b.u32(channelCounts.size * 10)
            for ((ch, n) in channelCounts) { b.u16(ch); b.u64(n) }
            b.endRecord(at)
            out.write(b)
        }
        group(Mcap.OP_CHUNK_INDEX) { out.write(chunkIndexRecords) }
        group(Mcap.OP_METADATA_INDEX) { out.write(metadataIndexRecords) }
        val summaryOffsetStart = out.position
        out.write(offsets)

        // Footer: the summary CRC includes the footer itself up to summary_offset_start.
        b.reset()
        b.u8(Mcap.OP_FOOTER)
        b.u64(20)
        b.u64(summaryStart)
        b.u64(summaryOffsetStart)
        out.write(b)
        b.reset()
        b.u32(out.crc.value)
        b.bytes(Mcap.MAGIC)
        out.write(b)
        out.flush()
        out.close()
    }

    /** The BagMetadata YAML (version 9, Jazzy) that rosbag2 stores in the "rosbag2" record. */
    private fun rosbag2Metadata(distro: String): String {
        val start = if (messageCount > 0) startTime else 0L
        val duration = if (messageCount > 0) endTime - startTime else 0L
        val sb = StringBuilder()
        sb.append("version: 9\nstorage_identifier: mcap\n")
        sb.append("duration:\n  nanoseconds: ").append(duration).append('\n')
        sb.append("starting_time:\n  nanoseconds_since_epoch: ").append(start).append('\n')
        sb.append("message_count: ").append(messageCount).append('\n')
        sb.append("topics_with_message_count:")
        if (channels.isEmpty()) sb.append(" []")
        sb.append('\n')
        for ((id, c) in channels) {
            sb.append("  - topic_metadata:\n")
            sb.append("      name: ").append(yamlString(c.topic)).append('\n')
            sb.append("      type: ").append(yamlString(schemas[c.schemaId]?.name ?: "")).append('\n')
            sb.append("      serialization_format: ").append(yamlString(c.encoding)).append('\n')
            val qos = c.metadata["offered_qos_profiles"]
            if (qos.isNullOrBlank()) sb.append("      offered_qos_profiles: []\n")
            else {
                sb.append("      offered_qos_profiles:\n")
                for (line in qos.trimEnd().lines()) sb.append("        ").append(line).append('\n')
            }
            sb.append("      type_description_hash: ").append(yamlString(c.metadata["topic_type_hash"] ?: "")).append('\n')
            sb.append("    message_count: ").append(channelCounts[id] ?: 0L).append('\n')
        }
        sb.append("compression_format: \"\"\ncompression_mode: \"\"\n")
        sb.append("relative_file_paths:\n  - ").append(yamlString(fileName)).append('\n')
        sb.append("files:\n  - path: ").append(yamlString(fileName)).append('\n')
        sb.append("    starting_time:\n      nanoseconds_since_epoch: ").append(start).append('\n')
        sb.append("    duration:\n      nanoseconds: ").append(duration).append('\n')
        sb.append("    message_count: ").append(messageCount).append('\n')
        sb.append("custom_data: ~\n")
        sb.append("ros_distro: ").append(yamlString(distro))
        return sb.toString()
    }

    companion object {
        /** YAML string in double quotes if needed (empty, spaces, special characters). */
        fun yamlString(s: String): String =
            if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "/_.-" } && s[0] !in "0123456789-.") s
            else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        const val DEFAULT_CHUNK_SIZE = 1 shl 20
    }
}
