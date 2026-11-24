// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.geom

import com.juancams.rviz2mini.TfMath
import com.juancams.rviz2mini.rviz.display.Colors
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.sin

/** Mesh ready to draw: triangles (9 floats each) and color per triangle (0 = no own color). */
class MeshData(val triangles: FloatArray, val colors: IntArray) {
    val triangleCount: Int get() = triangles.size / 9
}

/**
 * Collada (.dae) parser good enough for robots: triangulated geometry, node hierarchy,
 * units, up_axis and the materials' diffuse color. No textures or animations.
 */
object ColladaParser {

    fun parse(bytes: ByteArray): MeshData {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        require(root.tagName == "COLLADA") { "Not a Collada file" }

        val asset = root.child("asset")
        val unit = asset?.child("unit")?.getAttribute("meter")?.toDoubleOrNull() ?: 1.0
        val upAxis = asset?.child("up_axis")?.textContent?.trim() ?: "Y_UP"
        // Global correction: units -> meters and "up" axis -> Z (like assimp in RViz).
        var rootTransform = MeshBuilder.scale(unit, unit, unit)
        when (upAxis) {
            "Y_UP" -> rootTransform = TfMath.multiply(rotation(1.0, 0.0, 0.0, Math.PI / 2), rootTransform, DoubleArray(16))
            "X_UP" -> rootTransform = TfMath.multiply(rotation(0.0, 0.0, 1.0, Math.PI / 2), rootTransform, DoubleArray(16))
        }

        val effects = HashMap<String, Int>()
        root.child("library_effects")?.children("effect")?.forEach { e -> diffuseOf(e)?.let { effects[e.getAttribute("id")] = it } }
        val materials = HashMap<String, Int>()
        root.child("library_materials")?.children("material")?.forEach { m ->
            val effectId = m.child("instance_effect")?.getAttribute("url")?.removePrefix("#") ?: return@forEach
            effects[effectId]?.let { materials[m.getAttribute("id")] = it }
        }
        val geometries = HashMap<String, Element>()
        root.child("library_geometries")?.children("geometry")?.forEach { geometries[it.getAttribute("id")] = it }
        val libraryNodes = HashMap<String, Element>()
        root.child("library_nodes")?.children("node")?.forEach { libraryNodes[it.getAttribute("id")] = it }

        val tris = FloatList()
        val colors = IntList()

        fun emitGeometry(g: Element, m: DoubleArray, symbolColors: Map<String, Int>) {
            val mesh = g.child("mesh") ?: return
            val sources = HashMap<String, Pair<FloatArray, Int>>()
            for (src in mesh.children("source")) {
                val arr = src.child("float_array")?.textContent?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }?.toFloatArray() ?: continue
                val stride = src.child("technique_common")?.child("accessor")?.getAttribute("stride")?.toIntOrNull() ?: 3
                sources[src.getAttribute("id")] = arr to stride
            }
            // <vertices> redirects to the positions source.
            val vertices = mesh.child("vertices")
            val vertexSourceId = vertices?.children("input")?.firstOrNull { it.getAttribute("semantic") == "POSITION" }
                ?.getAttribute("source")?.removePrefix("#")
            for (prim in mesh.childElements()) {
                val kind = prim.tagName
                if (kind != "triangles" && kind != "polylist" && kind != "polygons") continue
                val inputs = prim.children("input")
                val stride = (inputs.maxOfOrNull { it.getAttribute("offset").toIntOrNull() ?: 0 } ?: 0) + 1
                val vin = inputs.firstOrNull { it.getAttribute("semantic") == "VERTEX" } ?: continue
                val vOffset = vin.getAttribute("offset").toIntOrNull() ?: 0
                val srcId = vin.getAttribute("source").removePrefix("#").let { if (it == vertices?.getAttribute("id")) vertexSourceId else it }
                val (pos, pstride) = sources[srcId] ?: continue
                val color = symbolColors[prim.getAttribute("material")] ?: 0

                fun vertex(index: Int): DoubleArray {
                    val i = index * pstride
                    val x = pos[i].toDouble(); val y = pos[i + 1].toDouble(); val z = pos[i + 2].toDouble()
                    return doubleArrayOf(
                        m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14]
                    )
                }
                fun emitPolygon(indices: IntArray) {
                    // Fan from the first vertex.
                    val vs = indices.map { vertex(it) }
                    for (k in 1 until vs.size - 1) {
                        for (v in listOf(vs[0], vs[k], vs[k + 1])) { tris.add(v[0].toFloat()); tris.add(v[1].toFloat()); tris.add(v[2].toFloat()) }
                        colors.add(color)
                    }
                }

                when (kind) {
                    "triangles" -> {
                        val p = ints(prim.child("p")?.textContent)
                        var i = 0
                        while (i + 3 * stride <= p.size) {
                            emitPolygon(intArrayOf(p[i + vOffset], p[i + stride + vOffset], p[i + 2 * stride + vOffset]))
                            i += 3 * stride
                        }
                    }
                    "polylist" -> {
                        val counts = ints(prim.child("vcount")?.textContent)
                        val p = ints(prim.child("p")?.textContent)
                        var i = 0
                        for (c in counts) {
                            if (i + c * stride > p.size) break
                            emitPolygon(IntArray(c) { k -> p[i + k * stride + vOffset] })
                            i += c * stride
                        }
                    }
                    "polygons" -> for (pe in prim.children("p")) {
                        val p = ints(pe.textContent)
                        emitPolygon(IntArray(p.size / stride) { k -> p[k * stride + vOffset] })
                    }
                }
            }
        }

        fun visit(node: Element, parent: DoubleArray, depth: Int) {
            if (depth > 64) return
            var m = parent.copyOf()
            for (c in node.childElements()) {
                val t = when (c.tagName) {
                    "matrix" -> { // row-major in Collada
                        val v = doubles(c.textContent)
                        if (v.size == 16) DoubleArray(16) { i -> v[(i % 4) * 4 + i / 4] } else null
                    }
                    "translate" -> doubles(c.textContent).let { if (it.size >= 3) MeshBuilder.translation(it[0], it[1], it[2]) else null }
                    "rotate" -> doubles(c.textContent).let { if (it.size >= 4) rotation(it[0], it[1], it[2], Math.toRadians(it[3])) else null }
                    "scale" -> doubles(c.textContent).let { if (it.size >= 3) MeshBuilder.scale(it[0], it[1], it[2]) else null }
                    else -> null
                }
                if (t != null) m = TfMath.multiply(m, t, DoubleArray(16))
            }
            for (ig in node.children("instance_geometry")) {
                val g = geometries[ig.getAttribute("url").removePrefix("#")] ?: continue
                val symbolColors = HashMap<String, Int>()
                ig.child("bind_material")?.child("technique_common")?.children("instance_material")?.forEach { im ->
                    materials[im.getAttribute("target").removePrefix("#")]?.let { symbolColors[im.getAttribute("symbol")] = it }
                }
                emitGeometry(g, m, symbolColors)
            }
            for (child in node.children("node")) visit(child, m, depth + 1)
            for (inst in node.children("instance_node")) libraryNodes[inst.getAttribute("url").removePrefix("#")]?.let { visit(it, m, depth + 1) }
        }

        val sceneUrl = root.child("scene")?.child("instance_visual_scene")?.getAttribute("url")?.removePrefix("#")
        val scenes = root.child("library_visual_scenes")?.children("visual_scene").orEmpty()
        val scene = scenes.firstOrNull { it.getAttribute("id") == sceneUrl } ?: scenes.firstOrNull()
        if (scene != null) {
            for (n in scene.children("node")) visit(n, rootTransform, 0)
        } else {
            // No scene: all geometries as they are.
            for (g in geometries.values) emitGeometry(g, rootTransform, emptyMap())
        }
        return MeshData(tris.toArray(), colors.toArray())
    }

    private fun diffuseOf(effect: Element): Int? {
        val technique = effect.child("profile_COMMON")?.child("technique") ?: return null
        val shading = technique.childElements().firstOrNull { it.tagName in setOf("phong", "lambert", "blinn", "constant") } ?: return null
        val color = (shading.child("diffuse") ?: shading.child("emission"))?.child("color") ?: return null
        val v = doubles(color.textContent)
        if (v.size < 3) return null
        return Colors.rgbf(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), (v.getOrNull(3) ?: 1.0).toFloat())
    }

    /** Rotation of `angle` rad around the axis (x, y, z). */
    private fun rotation(x: Double, y: Double, z: Double, angle: Double): DoubleArray {
        val n = kotlin.math.sqrt(x * x + y * y + z * z).takeIf { it > 0 } ?: return TfMath.identity()
        val s = sin(angle / 2)
        return TfMath.fromTranslationQuaternion(0.0, 0.0, 0.0, x / n * s, y / n * s, z / n * s, cos(angle / 2))
    }

    private fun ints(s: String?): IntArray = s?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }?.toIntArray() ?: IntArray(0)
    private fun doubles(s: String?): DoubleArray = s?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }?.toDoubleArray() ?: DoubleArray(0)

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { out.add(it) }
        return out
    }
    private fun Element.children(tag: String) = childElements().filter { it.tagName == tag }
    private fun Element.child(tag: String) = childElements().firstOrNull { it.tagName == tag }
}
