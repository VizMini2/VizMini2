// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.geom

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.rviz.display.Colors
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.sin

sealed class UrdfShape {
    data class Box(val x: Double, val y: Double, val z: Double) : UrdfShape()
    data class Cylinder(val radius: Double, val length: Double) : UrdfShape()
    data class Sphere(val radius: Double) : UrdfShape()
    data class Mesh(val uri: String, val sx: Double, val sy: Double, val sz: Double) : UrdfShape()
}

/** A `<visual>` or `<collision>` geometry of a link, with its origin relative to the link frame. */
class UrdfGeometry(val link: String, val origin: DoubleArray, val shape: UrdfShape, val color: Int?, val collision: Boolean)

/**
 * A joint: `child` hangs from `parent` with `origin`; `axis` is the rotation or
 * translation axis in the joint frame (which is the child link's). `effortLimit` (0 = no
 * limit) is the maximum effort from `<limit effort="...">`.
 */
class UrdfJoint(val name: String, val type: String, val parent: String, val child: String, val origin: DoubleArray,
                val axis: DoubleArray, val effortLimit: Double,
                /** `<limit lower upper>` (NaN if absent: continuous or unlimited). */
                val lower: Double = Double.NaN, val upper: Double = Double.NaN,
                /** `<mimic joint multiplier offset>`: this one copies another (e.g. the second finger of a gripper). */
                val mimic: String? = null, val mimicMultiplier: Double = 1.0, val mimicOffset: Double = 0.0) {
    /** It moves (it is neither fixed nor a copy of another). */
    val movable: Boolean get() = mimic == null && type in MOVABLE
    companion object { val MOVABLE = setOf("revolute", "continuous", "prismatic") }
}

class UrdfModel(val name: String, val links: List<String>, val geometries: List<UrdfGeometry>, val joints: List<UrdfJoint> = emptyList())

/**
 * Minimal URDF parser (what a viewer needs: links, visual/collision and materials).
 * Does not resolve xacro: robot_state_publisher publishes the already expanded URDF on /robot_description.
 */
object UrdfParser {

    fun parse(xml: String): UrdfModel {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // No external entities (XXE): the URDF comes over the network.
            for (f in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl",
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities"
            )) {
                try { setFeature(f, f.endsWith("disallow-doctype-decl")) } catch (_: Exception) {}
            }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val robot = doc.documentElement
        require(robot.tagName == "robot") { "The document is not a URDF (<robot>)" }

        // Global materials: <robot><material name="x"><color rgba="..."/></material>
        val materials = HashMap<String, Int>()
        for (m in robot.children("material")) {
            parseColor(m)?.let { materials[m.getAttribute("name")] = it }
        }

        val links = ArrayList<String>()
        val geometries = ArrayList<UrdfGeometry>()
        for (link in robot.children("link")) {
            val name = link.getAttribute("name")
            if (name.isEmpty()) continue
            links.add(name)
            for ((tag, collision) in listOf("visual" to false, "collision" to true)) {
                for (v in link.children(tag)) {
                    val shape = v.children("geometry").firstOrNull()?.let { parseShape(it) } ?: continue
                    val origin = v.children("origin").firstOrNull()?.let { parseOrigin(it) } ?: TfMath.identity()
                    val color = v.children("material").firstOrNull()?.let { mat ->
                        parseColor(mat) ?: materials[mat.getAttribute("name")]
                    }
                    geometries.add(UrdfGeometry(name, origin, shape, color, collision))
                }
            }
        }
        val joints = robot.children("joint").mapNotNull { j ->
            val name = j.getAttribute("name")
            val parent = j.children("parent").firstOrNull()?.getAttribute("link").orEmpty()
            val child = j.children("child").firstOrNull()?.getAttribute("link").orEmpty()
            if (name.isEmpty() || parent.isEmpty() || child.isEmpty()) return@mapNotNull null
            val axis = numbers(j.children("axis").firstOrNull()?.getAttribute("xyz") ?: "1 0 0", 3)
            val n = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
            val limit = j.children("limit").firstOrNull()
            val mimic = j.children("mimic").firstOrNull()
            UrdfJoint(name, j.getAttribute("type"), parent, child,
                j.children("origin").firstOrNull()?.let { parseOrigin(it) } ?: TfMath.identity(),
                if (n > 1e-9) DoubleArray(3) { axis[it] / n } else doubleArrayOf(1.0, 0.0, 0.0),
                limit?.getAttribute("effort")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0,
                lower = limit?.getAttribute("lower")?.toDoubleOrNull() ?: Double.NaN,
                upper = limit?.getAttribute("upper")?.toDoubleOrNull() ?: Double.NaN,
                mimic = mimic?.getAttribute("joint")?.takeIf { it.isNotEmpty() },
                mimicMultiplier = mimic?.getAttribute("multiplier")?.toDoubleOrNull() ?: 1.0,
                mimicOffset = mimic?.getAttribute("offset")?.toDoubleOrNull() ?: 0.0)
        }
        return UrdfModel(robot.getAttribute("name"), links, geometries, joints)
    }

    private fun Element.children(tag: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tag) out.add(n)
        }
        return out
    }

    private fun numbers(s: String?, count: Int, default: Double = 0.0): DoubleArray {
        val parts = s?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        return DoubleArray(count) { parts.getOrNull(it)?.toDoubleOrNull() ?: default }
    }

    private fun parseColor(material: Element): Int? {
        val c = material.children("color").firstOrNull() ?: return null
        val v = numbers(c.getAttribute("rgba"), 4, 1.0)
        return Colors.rgbf(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat())
    }

    private fun parseShape(geometry: Element): UrdfShape? {
        geometry.children("box").firstOrNull()?.let {
            val s = numbers(it.getAttribute("size"), 3)
            return UrdfShape.Box(s[0], s[1], s[2])
        }
        geometry.children("cylinder").firstOrNull()?.let {
            return UrdfShape.Cylinder(it.getAttribute("radius").toDoubleOrNull() ?: 0.0, it.getAttribute("length").toDoubleOrNull() ?: 0.0)
        }
        geometry.children("sphere").firstOrNull()?.let {
            return UrdfShape.Sphere(it.getAttribute("radius").toDoubleOrNull() ?: 0.0)
        }
        geometry.children("mesh").firstOrNull()?.let {
            val s = if (it.hasAttribute("scale")) numbers(it.getAttribute("scale"), 3, 1.0) else doubleArrayOf(1.0, 1.0, 1.0)
            return UrdfShape.Mesh(it.getAttribute("filename"), s[0], s[1], s[2])
        }
        return null
    }

    /** `<origin xyz rpy>`: fixed rotation R = Rz(yaw) · Ry(pitch) · Rx(roll), as in URDF. */
    fun parseOrigin(origin: Element): DoubleArray {
        val xyz = numbers(origin.getAttribute("xyz"), 3)
        val rpy = numbers(origin.getAttribute("rpy"), 3)
        return poseFromXyzRpy(xyz[0], xyz[1], xyz[2], rpy[0], rpy[1], rpy[2])
    }

    fun poseFromXyzRpy(x: Double, y: Double, z: Double, roll: Double, pitch: Double, yaw: Double): DoubleArray {
        val cr = cos(roll / 2); val sr = sin(roll / 2)
        val cp = cos(pitch / 2); val sp = sin(pitch / 2)
        val cy = cos(yaw / 2); val sy = sin(yaw / 2)
        val qw = cr * cp * cy + sr * sp * sy
        val qx = sr * cp * cy - cr * sp * sy
        val qy = cr * sp * cy + sr * cp * sy
        val qz = cr * cp * sy - sr * sp * cy
        return TfMath.fromTranslationQuaternion(x, y, z, qx, qy, qz, qw)
    }
}

/** Binary or ASCII STL -> triangles (9 floats per triangle). */
object StlParser {

    fun parse(bytes: ByteArray): FloatArray {
        if (bytes.size >= 84) {
            val n = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (84 + 50 * n == bytes.size.toLong()) return parseBinary(bytes, n.toInt())
        }
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.US_ASCII).trimStart()
        if (head.startsWith("solid")) return parseAscii(String(bytes, Charsets.US_ASCII))
        throw IllegalArgumentException("Not a valid STL file")
    }

    private fun parseBinary(bytes: ByteArray, n: Int): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(n * 9)
        for (i in 0 until n) {
            val base = 84 + i * 50 + 12 // skip the normal
            for (k in 0 until 9) out[i * 9 + k] = bb.getFloat(base + k * 4)
        }
        return out
    }

    private fun parseAscii(text: String): FloatArray {
        val out = FloatList()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("vertex")) continue
            val p = t.split(Regex("\\s+"))
            if (p.size < 4) continue
            out.add(p[1].toFloat()); out.add(p[2].toFloat()); out.add(p[3].toFloat())
        }
        val usable = out.size - out.size % 9
        return out.toArray().copyOf(usable)
    }
}

/** Translation of URDF mesh URIs to mesh server URLs (tools/mesh_server.py). */
object MeshUris {
    /** Formats that can be drawn. */
    fun isSupported(uri: String) = uri.substringBefore('?').lowercase().let { it.endsWith(".stl") || it.endsWith(".dae") }

    /** Parses according to the extension (STL or Collada). */
    fun parse(uri: String, bytes: ByteArray): MeshData =
        if (uri.substringBefore('?').lowercase().endsWith(".dae")) ColladaParser.parse(bytes)
        else StlParser.parse(bytes).let { MeshData(it, IntArray(it.size / 9)) }

    /**
     * package://pkg/path -> {base}/pkg/path ; file:///path -> {base}/__file__/path ;
     * http(s):// is used as is. Without a configured server (empty base) returns null.
     */
    fun resolve(uri: String, baseUrl: String): String? {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        val base = baseUrl.trim().trimEnd('/')
        // No mesh server: if the file is on this machine (the PC with the robot or the simulation), take it from there.
        if (base.isEmpty()) return localFile(uri)?.let { "file://" + it.absolutePath }
        return when {
            uri.startsWith("package://") -> "$base/${uri.removePrefix("package://")}"
            uri.startsWith("file://") -> "$base/__file__/${uri.removePrefix("file://").trimStart('/')}"
            else -> null
        }
    }

    /** `file:///path` or `package://package/path` on this machine (AMENT_PREFIX_PATH or /opt/ros/<distro>), if it exists. */
    fun localFile(uri: String): java.io.File? = runCatching {
        when {
            uri.startsWith("file://") -> java.io.File(uri.removePrefix("file://")).takeIf { it.isFile }
            uri.startsWith("package://") -> {
                val rest = uri.removePrefix("package://")
                val pkg = rest.substringBefore('/'); val path = rest.substringAfter('/', "")
                val prefixes = System.getenv("AMENT_PREFIX_PATH").orEmpty().split(':').filter { it.isNotBlank() } +
                    (java.io.File("/opt/ros").listFiles()?.map { it.path } ?: emptyList())
                prefixes.asSequence().map { java.io.File(it, "share/$pkg/$path") }.firstOrNull { it.isFile }
            }
            else -> null
        }
    }.getOrNull()
}
