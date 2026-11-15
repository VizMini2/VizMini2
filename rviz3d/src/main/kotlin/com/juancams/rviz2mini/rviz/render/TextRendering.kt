// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.rviz.render

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where the atlas glyphs are drawn: bold white text with a black outline (`strokePx`) on transparent.
 * Each platform provides it (Android: Canvas; PC: Java2D). `ascent` is negative (as in Android).
 */
interface GlyphCanvas {
    val ascent: Float
    val descent: Float
    fun measure(text: String): Float
    /** Outline and fill, with the baseline at `y`. */
    fun draw(text: String, x: Float, y: Float)
    /** The premultiplied RGBA pixels, in a direct buffer ready to read. */
    fun pixels(): java.nio.ByteBuffer

    companion object {
        @Volatile var create: (size: Int, textPx: Float, strokePx: Float) -> GlyphCanvas = { _, _, _ -> error("GlyphCanvas sin instalar") }
    }
}

/**
 * Glyph atlas (white text with black outline) in a Filament texture. Generated once
 * with [GlyphCanvas]. Each glyph stores its UV rectangle and its width/height ratio.
 */
class TextAtlas(engine: Engine) {

    /** `aspect` = box width (with margin) / height; `advance` = actual cursor advance / height; `pad` = left margin / height. */
    class Glyph(val u0: Float, val v0: Float, val u1: Float, val v1: Float, val aspect: Float, val advance: Float, val pad: Float)

    val texture: Texture
    private val glyphs = HashMap<Char, Glyph>()

    init {
        val chars = (32..126).map { it.toChar() } + "áéíóúÁÉÍÓÚñÑüÜ¿¡°·…–—ºª€".toList()
        val textPx = 56f
        val cellH = 72
        val size = 1024
        val canvas = GlyphCanvas.create(size, textPx, 7f)
        var x = 4; var y = 4
        val baseline = (cellH - (canvas.descent + canvas.ascent)) / 2f
        for (c in chars) {
            val adv = canvas.measure(c.toString())
            val w = (adv + 10).toInt().coerceAtLeast(12)
            if (x + w > size) { x = 4; y += cellH + 4 }
            canvas.draw(c.toString(), x + 5f, y + baseline)
            // Flipped V: Filament materials flip V (flipUV) just like with maps.
            glyphs[c] = Glyph(
                x.toFloat() / size, 1f - y.toFloat() / size, (x + w).toFloat() / size, 1f - (y + cellH).toFloat() / size,
                w.toFloat() / cellH, adv / cellH, 5f / cellH
            )
            x += w + 4
        }
        val buf = canvas.pixels() // premultiplied RGBA
        val levels = 8
        // generateMipmaps() requires declaring GEN_MIPMAPPABLE (Filament 1.75).
        texture = Texture.Builder().width(size).height(size).levels(levels)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8).build(engine)
        texture.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        texture.generateMipmaps(engine)
    }

    fun glyph(c: Char): Glyph = glyphs[c] ?: glyphs['?']!!

    fun destroy(engine: Engine) = engine.destroyTexture(texture)
}

/**
 * A text to draw, anchored at (x, y, z) in the fixed frame. `height` in meters, or in screen
 * pixels if `screenPx` (constant size when zooming in/out, better for TF names on a phone).
 */
data class TextItem(val text: String, val x: Float, val y: Float, val z: Float, val color: Int, val height: Float, val screenPx: Boolean = false)

/**
 * All the scene's texts in a single mesh: one quad per glyph, camera-facing in the
 * vertex shader (like the points). Only uploaded to the GPU if something changes.
 */
class TextEntity(private val engine: Engine, private val scene: Scene, material: Material, private val atlas: TextAtlas) {

    val entity: Int = EntityManager.get().create()
    private val materialInstance: MaterialInstance = material.createInstance().apply {
        setParameter("atlas", atlas.texture, TextureSampler(
            TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE
        ))
    }
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null
    private var capacity = 0 // glyphs
    private var built = false
    private var last: List<TextItem> = emptyList()
    private var visibleNow = true

    fun update(items: List<TextItem>) {
        if (items == last) return
        last = items
        var n = 0
        for (t in items) n += t.text.count { it != '\n' }
        if (n == 0) { setVisible(false); return }
        if (n > capacity) reallocate(maxOf(64, Integer.highestOneBit(n - 1) shl 1), n * 6)

        val pos = ByteBuffer.allocateDirect(n * 4 * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val col = ByteBuffer.allocateDirect(n * 4 * 4).order(ByteOrder.nativeOrder())
        val off = ByteBuffer.allocateDirect(n * 4 * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val uv = ByteBuffer.allocateDirect(n * 4 * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (t in items) {
            val h = t.height
            val mode = if (t.screenPx) 1f else 0f
            val r = (t.color shr 16).toByte(); val g = (t.color shr 8).toByte(); val b = t.color.toByte(); val a = (t.color ushr 24).toByte()
            // Several lines ('\n', e.g. TEXT_VIEW_FACING markers or the Select label): each one centered and the
            // last one just above the point. The offset is in the label's plane, so they do not overlap
            // from any viewpoint (separating them in z does not work in the top-down view).
            val lines = t.text.split('\n')
            for ((li, line) in lines.withIndex()) {
                var width = 0f
                for (c in line) width += atlas.glyph(c).advance * h
                var cx = -width / 2
                val y0 = h * 0.4f + (lines.size - 1 - li) * h * 1.15f // slightly above the point
                for (c in line) {
                    val gl = atlas.glyph(c)
                    val w = gl.aspect * h
                    val left = cx - gl.pad * h
                    // corners: (left, bottom), (right, bottom), (right, top), (left, top)
                    val corners = floatArrayOf(left, y0, left + w, y0, left + w, y0 + h, left, y0 + h)
                    val uvs = floatArrayOf(gl.u0, gl.v1, gl.u1, gl.v1, gl.u1, gl.v0, gl.u0, gl.v0)
                    for (k in 0 until 4) {
                        pos.put(t.x); pos.put(t.y); pos.put(t.z)
                        col.put(r); col.put(g); col.put(b); col.put(a)
                        off.put(corners[k * 2]); off.put(corners[k * 2 + 1]); off.put(mode)
                        uv.put(uvs[k * 2]); uv.put(uvs[k * 2 + 1])
                    }
                    cx += gl.advance * h
                }
            }
        }
        pos.flip(); col.flip(); off.flip(); uv.flip()
        vb!!.setBufferAt(engine, 0, pos)
        vb!!.setBufferAt(engine, 1, col)
        vb!!.setBufferAt(engine, 2, off)
        vb!!.setBufferAt(engine, 3, uv)
        val rm = engine.renderableManager
        rm.setGeometryAt(rm.getInstance(entity), 0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, n * 6)
        setVisible(true)
    }

    private fun reallocate(cap: Int, count: Int) {
        val oldVb = vb; val oldIb = ib
        vb = VertexBuffer.Builder().vertexCount(cap * 4).bufferCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 2, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 3, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        val idx = ByteBuffer.allocateDirect(cap * 6 * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        for (i in 0 until cap) { val b = i * 4; idx.put(b); idx.put(b + 1); idx.put(b + 2); idx.put(b); idx.put(b + 2); idx.put(b + 3) }
        idx.flip()
        ib = IndexBuffer.Builder().indexCount(cap * 6).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        ib!!.setBuffer(engine, idx)
        capacity = cap
        if (!built) {
            RenderableManager.Builder(1).geometry(0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, count)
                .material(0, materialInstance).boundingBox(com.google.android.filament.Box(0f, 0f, 0f, 1e4f, 1e4f, 1e4f))
                .culling(false).castShadows(false).receiveShadows(false)
                .priority(7) // texts last: on top of transparent things
                .build(engine, entity)
            engine.transformManager.create(entity)
            scene.addEntity(entity)
            built = true
        } else {
            val rm = engine.renderableManager
            rm.setGeometryAt(rm.getInstance(entity), 0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, count)
        }
        oldVb?.let { engine.destroyVertexBuffer(it) }
        oldIb?.let { engine.destroyIndexBuffer(it) }
    }

    fun setVisible(v: Boolean) {
        visibleNow = v
        if (!built) return
        val rm = engine.renderableManager
        val inst = rm.getInstance(entity)
        if (inst != 0) rm.setLayerMask(inst, 0xFF, if (v) 0x01 else 0x00)
    }

    fun destroy() {
        scene.removeEntity(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        vb?.let { engine.destroyVertexBuffer(it) }
        ib?.let { engine.destroyIndexBuffer(it) }
        engine.destroyMaterialInstance(materialInstance)
    }
}
