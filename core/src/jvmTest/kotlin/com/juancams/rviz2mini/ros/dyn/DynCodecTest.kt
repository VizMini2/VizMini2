// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ros.dyn

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import us.ihmc.fastddsjava.cdr.CDRBuffer
import us.ihmc.jros2.ROS2Message
import java.io.File

class DynCodecTest {

    companion object {
        /** The same catalog that ships in the APK. */
        val registry: InterfaceRegistry by lazy {
            InterfaceRegistry().apply { loadBundle(com.juancams.rviz2mini.testing.TestAssets.text("ros_interfaces.txt")) }
        }
    }

    private fun jrosBytes(msg: ROS2Message<*>): ByteArray {
        val buf = CDRBuffer()
        buf.ensureRemainingCapacity(CDRBuffer.PAYLOAD_HEADER.size + msg.calculateSizeBytes(0))
        buf.writePayloadHeader()
        msg.serialize(buf)
        return bytesOf(buf)
    }

    private fun dynBytes(spec: MsgSpec, v: MsgValue): ByteArray {
        val buf = CDRBuffer()
        buf.ensureRemainingCapacity(CDRBuffer.PAYLOAD_HEADER.size + DynCodec.size(spec, v, 0))
        val cap = buf.getBufferUnsafe().capacity()
        buf.writePayloadHeader()
        DynCodec.write(spec, v, buf)
        assertTrue("el tamaño calculado se queda corto", buf.getBufferUnsafe().position() <= cap)
        return bytesOf(buf)
    }

    private fun bytesOf(buf: CDRBuffer): ByteArray {
        val bb = buf.getBufferUnsafe().duplicate()
        val n = bb.position()
        bb.rewind()
        return ByteArray(n).also { bb.get(it) }
    }

    private fun readDyn(spec: MsgSpec, bytes: ByteArray): MsgValue {
        val buf = CDRBuffer()
        buf.ensureRemainingCapacity(bytes.size)
        buf.getBufferUnsafe().put(bytes)
        buf.rewind()
        buf.readPayloadHeader()
        return DynCodec.read(spec, buf)
    }

    /**
     * rmw_cyclonedds variant: the header (guid uint64, seq int64, in the encapsulation's byte order) goes right after the
     * encapsulation and the rest is exactly the bytes of the normal request; reading it recovers both fields.
     */
    @Test
    fun cycloneHeaderIsPrependedWithoutChangingTheBody() {
        val specs = listOf(registry.srv("example_interfaces/srv/AddTwoInts").request, registry.srv("rcl_interfaces/srv/GetParameters").request,
            registry.srv("lifecycle_msgs/srv/ChangeState").request, registry.srv("example_interfaces/srv/AddTwoInts").response,
            // Empty requests (GetState, GetInteractiveMarkers): header + the padding byte.
            registry.srv("lifecycle_msgs/srv/GetState").request, registry.srv("visualization_msgs/srv/GetInteractiveMarkers").request)
        for (spec in specs) {
            val type = spec.name
            val v = DynCodec.defaultValue(spec).apply {
                if (spec.fields.any { it.name == "a" }) { put("a", 2L); put("b", 40L) }
                if (spec.fields.any { it.name == "names" }) put("names", arrayListOf("use_sim_time", "max_speed"))
            }
            val plain = dynBytes(spec, v)
            val guid = 0x0123456789ABCDEFL; val seq = 7L
            val withHeader = dynBytes(spec.withCycloneHeader(), MsgValue(v).apply {
                put(MsgSpec.CYCLONE_GUID, guid); put(MsgSpec.CYCLONE_SEQ, seq)
                if (spec.fields.isEmpty()) put(MsgSpec.EMPTY_STRUCT_FIELD, 0L)
            })
            val header = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(guid).putLong(seq).array()
            assertArrayEquals(type, plain.copyOfRange(0, 4) + header + plain.copyOfRange(4, plain.size), withHeader)
            val back = readDyn(spec.withCycloneHeader(), withHeader)
            assertEquals(type, guid, back[MsgSpec.CYCLONE_GUID])
            assertEquals(type, seq, back[MsgSpec.CYCLONE_SEQ])
            assertEquals(type, spec.ddsName, spec.withCycloneHeader().ddsName)
        }
    }

    /** jros2 -> bytes -> dynamic -> bytes: they must come out identical, with the exact size. */
    private fun roundTrip(rosType: String, msg: ROS2Message<*>): MsgValue {
        val spec = registry.msg(rosType)
        val expected = jrosBytes(msg)
        val v = readDyn(spec, expected)
        val again = dynBytes(spec, v)
        assertArrayEquals("$rosType: bytes distintos", expected, again)
        assertEquals("$rosType: tamaño", expected.size - 4, DynCodec.size(spec, v, 0))
        return v
    }

    @Test
    fun everyBundledInterfaceResolves() {
        val errors = ArrayList<String>()
        for (n in registry.names("msg")) try { registry.msg(n) } catch (e: IdlException) { errors.add("$n: ${e.message}") }
        for (n in registry.names("srv")) try { registry.srv(n) } catch (e: IdlException) { errors.add("$n: ${e.message}") }
        for (n in registry.names("action")) try { registry.action(n) } catch (e: IdlException) { errors.add("$n: ${e.message}") }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
        assertTrue(registry.names("srv").contains("std_srvs/srv/SetBool"))
        assertTrue(registry.names("action").contains("nav2_msgs/action/NavigateToPose"))
    }

    @Test
    fun matchesJros2Serialization() {
        roundTrip("nav_msgs/msg/Odometry", nav_msgs.Odometry().apply {
            header.setFrameId("odom"); header.stamp.sec = 12; header.stamp.nanosec = 34
            setChildFrameId("base_link")
            pose.pose.position.x = 1.5; pose.covariance[3] = 0.25
            twist.twist.angular.z = -0.5
        })
        roundTrip("sensor_msgs/msg/JointState", sensor_msgs.JointState().apply {
            name.add("a"); name.add("joint_bc"); position.add(1.0); position.add(-2.0); velocity.add(3.0)
        })
        roundTrip("sensor_msgs/msg/LaserScan", sensor_msgs.LaserScan().apply {
            header.setFrameId("laser"); angleMin = -1f; for (i in 0 until 7) ranges.add(i * 0.5f)
        })
        roundTrip("visualization_msgs/msg/MarkerArray", visualization_msgs.MarkerArray().apply {
            markers.add().apply { setNs("n"); id = 3; type = 4; points.add().x = 1.0; points.add().y = 2.0; setText("hola"); frameLocked = true }
            markers.add().apply { setNs("otro"); colors.add().r = 0.5f }
        })
        roundTrip("sensor_msgs/msg/CameraInfo", sensor_msgs.CameraInfo().apply {
            width = 640; height = 480; setDistortionModel("plumb_bob"); d.add(0.1); k[0] = 500.0; binningX = 2
        })
        roundTrip("diagnostic_msgs/msg/DiagnosticArray", diagnostic_msgs.DiagnosticArray().apply {
            status.add().apply { setName("motor"); level = 2; setMessage("caliente"); values.add().apply { setKey("t"); setValue("80") } }
        })
        roundTrip("sensor_msgs/msg/Imu", sensor_msgs.Imu().apply { orientation.w = 1.0; linearAcceleration.z = 9.8 })
        roundTrip("std_msgs/msg/String", std_msgs.String_().apply { setData("abc") })
        roundTrip("std_msgs/msg/UInt64", std_msgs.UInt64().apply { data = -1L })
        roundTrip("tf2_msgs/msg/TFMessage", tf2_msgs.TFMessage().apply {
            transforms.add().apply { header.setFrameId("map"); setChildFrameId("odom"); transform.rotation.w = 1.0 }
        })
        roundTrip("sensor_msgs/msg/BatteryState", sensor_msgs.BatteryState().apply { voltage = 12.5f; cellVoltage.add(3.7f); setLocation("x") })
        roundTrip("rcl_interfaces/msg/Log", rcl_interfaces.Log().apply { level = 20; setName("nodo"); setMsg("hola"); line = 7 })
    }

    /** Reference bytes taken from rclpy (serialize_message) on ROS 2 Jazzy. */
    private fun assertMatchesRclpy(spec: MsgSpec, v: MsgValue, hex: String) {
        val ours = dynBytes(spec, v)
        val golden = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // rclpy pads up to a multiple of 4; the padding is not part of the message.
        assertTrue("${spec.name}: ${ours.size} vs ${golden.size}", golden.size - ours.size in 0..3)
        assertArrayEquals(spec.name, golden.copyOf(ours.size), ours)
        assertEquals(v, readDyn(spec, golden))
    }

    @Test
    fun matchesRclpySerialization() {
        // Empty message: 1 byte (jros2 std_msgs.Empty does not write it, ROS 2 does).
        val empty = registry.srv("std_srvs/srv/Empty").request
        assertMatchesRclpy(empty, MsgValue(), "0001000000000000")
        assertMatchesRclpy(registry.msg("std_msgs/msg/String"), MsgValue().apply { put("data", "Camión") }, "000100000800000043616d69c3b36e00")
        val fib = registry.action("example_interfaces/action/Fibonacci")
        val goal = DynCodec.defaultValue(fib.sendGoal.request)
        @Suppress("UNCHECKED_CAST")
        (goal["goal_id"] as MutableMap<String, Any?>)["uuid"] = (0L until 16L).toMutableList()
        @Suppress("UNCHECKED_CAST")
        (goal["goal"] as MutableMap<String, Any?>)["order"] = 5L
        assertMatchesRclpy(fib.sendGoal.request, goal, "00010000000102030405060708090a0b0c0d0e0f05000000")
        val res = DynCodec.defaultValue(fib.getResult.response)
        res["status"] = 4L
        @Suppress("UNCHECKED_CAST")
        (res["result"] as MutableMap<String, Any?>)["sequence"] = mutableListOf(0L, 1L, 1L, 2L)
        assertMatchesRclpy(fib.getResult.response, res, "00010000040000000400000000000000010000000100000002000000")
    }

    @Test
    fun valuesFromJros2AreReadable() {
        val v = roundTrip("geometry_msgs/msg/Twist", geometry_msgs.Twist().apply { linear.x = 0.5; angular.z = -1.0 })
        @Suppress("UNCHECKED_CAST")
        assertEquals(0.5, (v["linear"] as Map<String, Any?>)["x"])
        val text = DynCodec.format(registry.msg("geometry_msgs/msg/Twist"), v)
        assertTrue(text, text.startsWith("linear:\n  x: 0.5\n  y: 0.0"))
    }

    @Test
    fun utf8StringsSurvive() {
        val spec = registry.msg("std_msgs/msg/String")
        val v = MsgValue().apply { put("data", "Camión ñ €") }
        assertEquals("Camión ñ €", readDyn(spec, dynBytes(spec, v))["data"])
    }

    @Test
    fun defaultsComeFromTheDefinition() {
        val q = DynCodec.defaultValue(registry.msg("geometry_msgs/msg/Quaternion"))
        assertEquals(1.0, q["w"])
        assertEquals(0.0, q["x"])
        val uuid = DynCodec.defaultValue(registry.msg("unique_identifier_msgs/msg/UUID"))
        assertEquals(16, (uuid["uuid"] as List<*>).size)
    }

    @Test
    fun jsonRoundTripAndNow() {
        val spec = registry.msg("geometry_msgs/msg/PoseStamped")
        val json = JSONObject("""{"header":{"stamp":"now","frame_id":"map"},"pose":{"position":{"x":"1,5","y":2}}}""")
        val v = DynCodec.fromJson(spec, json, nowNs = 3_500_000_000L, strict = true)
        @Suppress("UNCHECKED_CAST") val header = v["header"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val stamp = header["stamp"] as Map<String, Any?>
        assertEquals(3L, stamp["sec"]); assertEquals(500_000_000L, stamp["nanosec"])
        @Suppress("UNCHECKED_CAST") val pos = (v["pose"] as Map<String, Any?>)["position"] as Map<String, Any?>
        assertEquals(1.5, pos["x"]); assertEquals(2.0, pos["y"])
        @Suppress("UNCHECKED_CAST") val ori = (v["pose"] as Map<String, Any?>)["orientation"] as Map<String, Any?>
        assertEquals(1.0, ori["w"])
        // And back: what was stored reads back the same.
        val back = DynCodec.fromJson(spec, DynCodec.toJson(spec, v), strict = true)
        assertEquals(v, back)
    }

    @Test
    fun strictRejectsOutOfRange() {
        val spec = registry.srv("turtlesim/srv/SetPen").request
        try {
            DynCodec.fromJson(spec, JSONObject("""{"r": 300}"""), strict = true)
            fail("300 no cabe en uint8")
        } catch (e: ValueException) {
            assertEquals("r", e.path)
        }
        // Without strict the default value is used.
        assertEquals(0L, DynCodec.fromJson(spec, JSONObject("""{"r": 300}"""))["r"])
    }

    @Test
    fun primitiveArraysAcceptCommaText() {
        val spec = registry.msg("std_msgs/msg/Float64MultiArray")
        val v = DynCodec.fromJson(spec, JSONObject("""{"data":"1, 2.5, -3"}"""), strict = true)
        assertEquals(listOf(1.0, 2.5, -3.0), v["data"])
    }

    @Test
    fun actionMessagesHaveRclActionLayout() {
        val a = registry.action("example_interfaces/action/Fibonacci")
        assertEquals("example_interfaces::action::dds_::Fibonacci_SendGoal_Request_", a.sendGoal.request.ddsName)
        assertEquals("example_interfaces::action::dds_::Fibonacci_GetResult_Response_", a.getResult.response.ddsName)
        assertEquals("example_interfaces::action::dds_::Fibonacci_FeedbackMessage_", a.feedbackMessage.ddsName)
        assertEquals(listOf("goal_id", "goal"), a.sendGoal.request.fields.map { it.name })
        assertEquals(listOf("status", "result"), a.getResult.response.fields.map { it.name })
        val s = registry.srv("std_srvs/srv/Empty")
        assertEquals("std_srvs::srv::dds_::Empty_Request_", s.request.ddsName)
        // Empty message: one byte, as in ROS 2.
        assertEquals(1, DynCodec.size(s.request, DynCodec.defaultValue(s.request), 0))
    }

    @Test
    fun customDefinitionsAreValidatedAndResolveLocalTypes() {
        val r = InterfaceRegistry().apply { loadBundle(com.juancams.rviz2mini.testing.TestAssets.text("ros_interfaces.txt")) }
        r.defineChecked("my_robot/msg/Light", "uint8 RED=1\nuint8 color 1\nstring<=8 label \"a#b\"")
        r.defineChecked("my_robot/srv/SetLights", "Light[<=4] lights\nHeader header\n---\nbool ok")
        val req = r.srv("my_robot/srv/SetLights").request
        assertEquals("my_robot/msg/Light", req.fields[0].nested!!.name)
        val light = DynCodec.defaultValue(r.msg("my_robot/msg/Light"))
        assertEquals(1L, light["color"]); assertEquals("a#b", light["label"])
        try {
            r.defineChecked("my_robot/srv/Bad", "NoExiste x\n---\n")
            fail()
        } catch (_: IdlException) { }
        assertTrue(!r.has("my_robot/srv/Bad"))
    }
}
