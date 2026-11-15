// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.bag

import com.juancams.rviz2mini.ros.dyn.InterfaceRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

class McapTest {

    companion object {
        /** The same catalog that ships in the APK. */
        val registry: InterfaceRegistry by lazy {
            InterfaceRegistry().apply { loadBundle(com.juancams.rviz2mini.testing.TestAssets.text("ros_interfaces.txt")) }
        }

        fun resource(name: String): File = File("src/jvmTest/resources/bag/$name")

        /** CDR of std_msgs/String (with little-endian encapsulation header). */
        fun cdrString(s: String): ByteArray {
            val b = s.toByteArray(Charsets.UTF_8)
            val bb = ByteBuffer.allocate(4 + 4 + b.size + 1).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(byteArrayOf(0, 1, 0, 0)).putInt(b.size + 1).put(b).put(0)
            return bb.array()
        }

        /** CDR of geometry_msgs/Twist. */
        fun cdrTwist(vx: Double, wz: Double): ByteArray {
            val bb = ByteBuffer.allocate(4 + 48).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(byteArrayOf(0, 1, 0, 0)).putDouble(vx).putDouble(0.0).putDouble(0.0).putDouble(0.0).putDouble(0.0).putDouble(wz)
            return bb.array()
        }

        /** CDR of geometry_msgs/PoseStamped (with 8-byte alignment after the frame_id). */
        fun cdrPoseStamped(sec: Int, frame: String, x: Double): ByteArray {
            val f = frame.toByteArray(Charsets.UTF_8)
            val bb = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(byteArrayOf(0, 1, 0, 0)).putInt(sec).putInt(0).putInt(f.size + 1).put(f).put(0)
            while ((bb.position() - 4) % 8 != 0) bb.put(0)
            bb.putDouble(x).putDouble(0.0).putDouble(0.0) // position
            bb.putDouble(0.0).putDouble(0.0).putDouble(0.0).putDouble(1.0) // orientation
            return bb.array().copyOf(bb.position())
        }

        fun stringOf(cdr: ByteArray): String {
            val bb = ByteBuffer.wrap(cdr).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.getInt(4)
            return String(cdr, 8, n - 1, Charsets.UTF_8)
        }
    }

    private fun tempFile(): File = File.createTempFile("mcap", ".mcap").also { it.deleteOnExit() }

    private class Written(val file: File, val sent: List<Triple<Int, Long, ByteArray>>, val chatter: Int, val cmd: Int)

    /** Writes a bag with two channels and many messages (several small chunks). */
    private fun writeSample(chunkSize: Int = 4096, n: Int = 500): Written {
        val file = tempFile()
        val sent = ArrayList<Triple<Int, Long, ByteArray>>()
        val chatter: Int
        val cmd: Int
        McapWriter(file.outputStream(), chunkSize).use { w ->
            val s1 = w.addSchema("std_msgs/msg/String", data = RosSchemas.definition(registry, "std_msgs/msg/String"))
            val s2 = w.addSchema("geometry_msgs/msg/Twist", data = RosSchemas.definition(registry, "geometry_msgs/msg/Twist"))
            chatter = w.addChannel(s1, "/chatter", metadata = RosSchemas.channelMetadata())
            cmd = w.addChannel(s2, "/cmd_vel", metadata = RosSchemas.channelMetadata(reliable = false))
            val t0 = 1_700_000_000_000_000_000L
            for (i in 0 until n) {
                // Some disorder within each chunk: the reader must sort it.
                val t = t0 + i * 1_000_000L + (if (i % 7 == 3) -1_500_000L else 0L)
                val (ch, data) = if (i % 3 == 0) chatter to cdrString("msg ñ $i") else cmd to cdrTwist(i * 0.1, -i * 0.01)
                w.writeMessage(ch, t, t, data, sequence = i)
                sent.add(Triple(ch, t, data))
            }
        }
        return Written(file, sent, chatter, cmd)
    }

    @Test fun roundTrip() {
        val w = writeSample()
        McapReader.open(w.file).use { r ->
            assertTrue(r.usedSummary)
            assertEquals("ros2", r.profile)
            assertEquals("vizmini2", r.library)
            assertEquals(500L, r.messageCount)
            assertTrue("varios chunks", r.chunkCount > 3)
            assertEquals(setOf("/chatter", "/cmd_vel"), r.channels.values.map { it.topic }.toSet())
            val chatter = r.channels.getValue(w.chatter)
            assertEquals("std_msgs/msg/String", chatter.schemaName)
            assertEquals("cdr", chatter.messageEncoding)
            assertTrue(chatter.metadata.getValue("offered_qos_profiles").contains("reliability: reliable"))
            assertTrue(r.channels.getValue(w.cmd).metadata.getValue("offered_qos_profiles").contains("best_effort"))
            assertEquals("ros2msg", r.schemas.getValue(chatter.schemaId).encoding)
            assertEquals(167L, r.channelMessageCounts[w.chatter])
            assertEquals(333L, r.channelMessageCounts[w.cmd])
            assertEquals(w.sent.minOf { it.second }, r.startTimeNs)
            assertEquals(w.sent.maxOf { it.second }, r.endTimeNs)

            val expected = w.sent.sortedBy { it.second }
            val got = r.messages().toList()
            assertEquals(expected.size, got.size)
            for ((e, g) in expected.zip(got)) {
                assertEquals(e.first, g.channelId)
                assertEquals(e.second, g.logTimeNs)
                assertEquals(e.second, g.publishTimeNs)
                assertArrayEquals(e.third, g.data)
            }
            assertEquals("msg ñ 0", stringOf(got.first { it.channelId == w.chatter }.data))
            // It can be iterated again.
            assertEquals(500, r.messages().count())
        }
    }

    @Test fun filtersByTimeAndChannel() {
        val w = writeSample()
        McapReader.open(w.file).use { r ->
            val from = r.startTimeNs + 100_000_000L
            val to = r.startTimeNs + 200_000_000L
            val got = r.messages(from, to, setOf(w.cmd)).toList()
            val expected = w.sent.filter { it.first == w.cmd && it.second in from..to }.sortedBy { it.second }
            assertEquals(expected.map { it.second }, got.map { it.logTimeNs })
        }
    }

    @Test fun crcsAreValid() {
        val bytes = writeSample(chunkSize = 2000, n = 100).file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val footer = bytes.size - 8 - 29
        assertEquals(Mcap.OP_FOOTER, bytes[footer].toInt())
        val summaryStart = bb.getLong(footer + 9).toInt()
        val summaryCrc = bb.getInt(footer + 25).toLong() and 0xFFFFFFFFL
        val crc = CRC32().apply { update(bytes, summaryStart, footer + 25 - summaryStart) }.value
        assertEquals(crc, summaryCrc)
        // DataEnd right before the summary: 1 + 8 + 4 bytes.
        val dataEnd = summaryStart - 13
        assertEquals(Mcap.OP_DATA_END, bytes[dataEnd].toInt())
        val dataCrc = CRC32().apply { update(bytes, 0, dataEnd) }.value
        assertEquals(dataCrc, bb.getInt(dataEnd + 9).toLong() and 0xFFFFFFFFL)
    }

    @Test fun readsWithoutSummaryAndTruncated() {
        val w = writeSample()
        val bytes = w.file.readBytes()
        val footer = bytes.size - 8 - 29
        val summaryStart = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(footer + 9).toInt()

        // No summary or footer (like a recording that was not closed properly).
        val noSummary = tempFile().apply { writeBytes(bytes.copyOf(summaryStart - 13)) }
        McapReader.open(noSummary).use { r ->
            assertFalse(r.usedSummary)
            assertEquals(500L, r.messageCount)
            assertEquals(2, r.channels.size)
            assertEquals("geometry_msgs/msg/Twist", r.channels.getValue(w.cmd).schemaName)
            assertEquals(w.sent.sortedBy { it.second }.map { it.second }, r.messages().map { it.logTimeNs }.toList())
        }

        // Cut in the middle of a chunk: whatever is complete is read.
        val cut = tempFile().apply { writeBytes(bytes.copyOf(summaryStart / 2)) }
        McapReader.open(cut).use { r ->
            assertFalse(r.usedSummary)
            assertTrue(r.messageCount in 1 until 500)
            assertEquals(r.messageCount, r.messages().count().toLong())
        }
    }

    @Test fun emptyBag() {
        val file = tempFile()
        McapWriter(file.outputStream(), rosDistro = null).use { it.addMetadata("extra", mapOf("k" to "v")) }
        McapReader.open(file).use { r ->
            assertTrue(r.usedSummary)
            assertEquals(0L, r.messageCount)
            assertEquals(0, r.messages().count())
            assertEquals("v", r.metadata.single().metadata["k"])
        }
        // With the distro, the rosbag2 "rosbag2" record (even if there are no topics).
        McapWriter(file.outputStream()).close()
        McapReader.open(file).use { r ->
            val yaml = r.metadata.single { it.name == "rosbag2" }.metadata.getValue("serialized_metadata")
            assertTrue(yaml.contains("topics_with_message_count: []"))
            assertTrue(yaml.endsWith("ros_distro: jazzy"))
        }
    }

    @Test fun notMcap() {
        val f = tempFile().apply { writeText("hola, esto no es un bag") }
        try { McapReader.open(f); fail() } catch (_: McapException) { }
    }

    // ---------------------------------------------------------------- real rosbag2 Jazzy

    private fun checkJazzy(r: McapReader, chatterCount: Long, cmdCount: Long) {
        assertEquals("ros2", r.profile)
        val byTopic = r.channels.values.associateBy { it.topic }
        val chatter = byTopic.getValue("/chatter")
        val cmd = byTopic.getValue("/cmd_vel")
        assertEquals("std_msgs/msg/String", chatter.schemaName)
        assertEquals("geometry_msgs/msg/Twist", cmd.schemaName)
        assertEquals("cdr", cmd.messageEncoding)
        assertTrue(cmd.metadata.getValue("offered_qos_profiles").contains("reliability: reliable"))
        assertTrue(r.schemas.getValue(cmd.schemaId).text.contains("MSG: geometry_msgs/Vector3"))
        assertEquals(chatterCount + cmdCount, r.messageCount)
        assertEquals(chatterCount, r.channelMessageCounts[chatter.id])
        assertEquals(cmdCount, r.channelMessageCounts[cmd.id])

        val msgs = r.messages().toList()
        assertEquals(r.messageCount, msgs.size.toLong())
        assertEquals(msgs.sortedBy { it.logTimeNs }.map { it.logTimeNs }, msgs.map { it.logTimeNs })
        assertEquals(r.startTimeNs, msgs.first().logTimeNs)
        assertEquals(r.endTimeNs, msgs.last().logTimeNs)
        for (m in msgs) {
            if (m.channelId == chatter.id) assertEquals("hola mcap", stringOf(m.data))
            else {
                val bb = ByteBuffer.wrap(m.data).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(0.5, bb.getDouble(4), 0.0)
                assertEquals(0.25, bb.getDouble(4 + 40), 0.0)
            }
        }
    }

    @Test fun readsRosbag2JazzyUncompressed() {
        McapReader.open(resource("jazzy_plain.mcap")).use { r ->
            assertTrue(r.usedSummary)
            checkJazzy(r, 5, 22)
            val meta = r.metadata.last { it.name == "rosbag2" }.metadata
            assertTrue(meta.getValue("serialized_metadata").contains("ros_distro: jazzy"))
        }
    }

    @Test fun readsRosbag2JazzyLz4() {
        McapReader.open(resource("jazzy_lz4.mcap")).use { r ->
            assertTrue(r.usedSummary)
            assertEquals(2, r.chunkCount)
            checkJazzy(r, 48, 48)
        }
    }

    @Test fun readsRosbag2JazzyWithoutSummary() {
        for ((name, chatter, cmd) in listOf(Triple("jazzy_plain.mcap", 5L, 22L), Triple("jazzy_lz4.mcap", 48L, 48L))) {
            val bytes = resource(name).readBytes()
            // Cut at the DataEnd (opcode 0x0F, length 4).
            val dataEnd = (0 until bytes.size - 9).last { bytes[it].toInt() == 0x0F && bytes[it + 1].toInt() == 4 && (2..8).all { k -> bytes[it + k].toInt() == 0 } }
            val f = tempFile().apply { writeBytes(bytes.copyOf(dataEnd)) }
            McapReader.open(f).use { r ->
                assertFalse(r.usedSummary)
                checkJazzy(r, chatter, cmd)
            }
        }
    }

    /** Real bag from `ros2 bag record` with zstd chunks (storage config `compression: "Zstd"`). */
    @Test fun readsRosbag2JazzyZstd() {
        McapReader.open(resource("jazzy_zstd.mcap")).use { r ->
            assertEquals(128L, r.messageCount)
            val byTopic = r.messages().groupingBy { r.channels[it.channelId]!!.topic }.eachCount()
            assertEquals(mapOf("/ztest" to 64, "/zvel" to 64), byTopic)
            val first = r.messages().first { r.channels[it.channelId]!!.topic == "/ztest" }
            assertTrue(String(first.data, Charsets.UTF_8).contains("zstd hola"))
        }
    }

    @Test fun unknownCompressionIsRejectedWithClearMessage() {
        val b = LeBuffer()
        b.bytes(Mcap.MAGIC)
        var at = b.beginRecord(Mcap.OP_HEADER); b.str("ros2"); b.str("test"); b.endRecord(at)
        at = b.beginRecord(Mcap.OP_CHUNK)
        b.u64(1); b.u64(2); b.u64(10); b.u32(0); b.str("brotli"); b.u64(3); b.bytes(byteArrayOf(1, 2, 3))
        b.endRecord(at)
        val f = tempFile().apply { writeBytes(b.toByteArray()) }
        try {
            McapReader.open(f)
            fail()
        } catch (e: UnsupportedCompression) {
            assertEquals("brotli", e.compression)
            assertTrue(e.message!!.contains("not supported"))
        }
    }

    // ---------------------------------------------------------------- LZ4

    @Test fun lz4Frames() {
        val raw = GZIPInputStream(resource("lz4_raw.bin.gz").inputStream()).use { it.readBytes() }
        assertArrayEquals(raw, Lz4Frame.decompress(resource("lz4_linked.lz4").readBytes()))
        assertArrayEquals(raw, Lz4Frame.decompress(resource("lz4_linked.lz4").readBytes(), expectedSize = raw.size))
        // Independent blocks with per-block checksum, followed by another frame.
        assertArrayEquals(raw + raw, Lz4Frame.decompress(resource("lz4_indep.lz4").readBytes()))
    }

    private fun le32(vararg v: Long): ByteArray =
        ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).apply { for (x in v) putInt(x.toInt()) }.array()

    /** Fails with McapException; with a timeout, because some corrupt data used to never finish. */
    private fun assertCorrupt(data: ByteArray) {
        var error: Throwable? = null
        val t = Thread { error = runCatching { Lz4Frame.decompress(data) }.exceptionOrNull() }.apply { isDaemon = true; start() }
        t.join(5000)
        assertFalse("No termina (bucle infinito)", t.isAlive)
        assertTrue("Esperaba McapException y fue $error", error is McapException)
    }

    @Test fun lz4SkippableFrameWithHugeSizeDoesNotLoop() {
        // "Skippable" frame with size 0xFFFFFFF8: as an Int it is -8 and reading went back to the same magic forever.
        assertCorrupt(le32(0x184D2A50L, 0xFFFFFFF8L, 0L))
        assertCorrupt(le32(0x184D2A50L, 0x80000000L))
        // A well-formed one is skipped and reading continues.
        assertArrayEquals(ByteArray(0), Lz4Frame.decompress(le32(0x184D2A50L, 4L, 0L)))
    }

    @Test fun lz4BlockWithHugeSizeIsCorruptNotACrash() {
        // LZ4 frame (FLG = version 1, BD, HC) and a block that claims to be 2 GB.
        val header = le32(0x184D2204L) + byteArrayOf(0x40, 0x70, 0x00)
        assertCorrupt(header + le32(0x7FFFFFF0L) + ByteArray(8))
    }

    // ---------------------------------------------------------------- schemas

    @Test fun schemaDefinitionLikeRosbag2() {
        val twist = RosSchemas.definition(registry, "geometry_msgs/msg/Twist")
        val sep = "=".repeat(80)
        assertTrue(twist.startsWith(registry.definition("geometry_msgs/msg/Twist")!!.trimEnd()))
        assertEquals(1, Regex("MSG: geometry_msgs/Vector3\n").findAll(twist).count())
        assertTrue(twist.contains("\n$sep\nMSG: geometry_msgs/Vector3\n"))

        val pose = RosSchemas.definition(registry, "geometry_msgs/PoseStamped")
        val names = Regex("MSG: (\\S+)").findAll(pose).map { it.groupValues[1] }.toList()
        assertEquals(listOf("std_msgs/Header", "builtin_interfaces/Time", "geometry_msgs/Pose", "geometry_msgs/Point", "geometry_msgs/Quaternion"), names)
        assertEquals(names.size, Regex(sep).findAll(pose).count())
    }

    /**
     * Leaves a bag in build/mcap/writer_check.mcap to check it by hand with
     * `ros2 bag info` / `ros2 bag play` (see the compatibility report).
     */
    @Test fun writesBagForRos2Tools() {
        val dir = File("build/mcap").apply { mkdirs() }
        val file = File(dir, "writer_check.mcap")
        McapWriter(file.outputStream(), chunkSize = 8192, fileName = file.name).use { w ->
            val sString = w.addSchema("std_msgs/msg/String", data = RosSchemas.definition(registry, "std_msgs/msg/String"))
            val sTwist = w.addSchema("geometry_msgs/msg/Twist", data = RosSchemas.definition(registry, "geometry_msgs/msg/Twist"))
            val sPose = w.addSchema("geometry_msgs/msg/PoseStamped", data = RosSchemas.definition(registry, "geometry_msgs/msg/PoseStamped"))
            val chatter = w.addChannel(sString, "/bag_chatter", metadata = RosSchemas.channelMetadata())
            val cmd = w.addChannel(sTwist, "/bag_cmd_vel", metadata = RosSchemas.channelMetadata())
            val pose = w.addChannel(sPose, "/bag_pose", metadata = RosSchemas.channelMetadata(transientLocal = true))
            val t0 = System.currentTimeMillis() * 1_000_000L
            for (i in 0 until 100) {
                val t = t0 + i * 50_000_000L // 20 Hz for 5 s
                w.writeMessage(chatter, t, t, cdrString("desde vizmini2 $i"))
                w.writeMessage(cmd, t + 1000, t + 1000, cdrTwist(0.5, 0.25))
                if (i % 10 == 0) w.writeMessage(pose, t + 2000, t + 2000, cdrPoseStamped(i, "map", i.toDouble()))
            }
        }
        McapReader.open(file).use { r ->
            assertEquals(210L, r.messageCount)
            val yaml = r.metadata.single { it.name == "rosbag2" }.metadata.getValue("serialized_metadata")
            assertTrue(yaml.contains("message_count: 210\n"))
            assertTrue(yaml.contains("      name: /bag_pose\n      type: geometry_msgs/msg/PoseStamped\n"))
            assertTrue(yaml.contains("          durability: transient_local\n"))
        }
    }
}
