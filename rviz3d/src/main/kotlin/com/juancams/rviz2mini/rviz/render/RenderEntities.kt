// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.render

import com.google.android.filament.Box
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
import com.juancams.rviz2mini.rviz.geom.PointList
import com.juancams.rviz2mini.rviz.geom.VertexList
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Filament entities whose geometry changes at runtime.
 *
 * Each upload uses NEW direct buffers: Filament reads them asynchronously on its render thread,
 * so rewriting one that is still in flight would give corrupt geometry. Uploads only happen when the
 * content changes (compared with the last upload), so static content costs nothing per frame.
 */

private fun floatBuffer(src: FloatArray, count: Int) =
    ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(src, 0, count); flip() }

/** ARGB (Int) -> RGBA (bytes) for the normalized COLOR attribute. */
private fun colorBuffer(argb: IntArray, count: Int, repeat: Int = 1): ByteBuffer {
    val b = ByteBuffer.allocateDirect(count * 4 * repeat).order(ByteOrder.nativeOrder())
    for (i in 0 until count) {
        val c = argb[i]
        val r = (c shr 16).toByte(); val g = (c shr 8).toByte(); val bl = c.toByte(); val a = (c ushr 24).toByte()
        repeat(repeat) { b.put(r); b.put(g); b.put(bl); b.put(a) }
    }
    b.flip()
    return b
}

private fun nextCapacity(n: Int): Int {
    var c = 64
    while (c < n) c = c shl 1
    return c
}

/** Base: an entity with transform and visibility. */
abstract class RenderEntity(protected val engine: Engine, protected val scene: Scene) {
    val entity: Int = EntityManager.get().create()
    /** Filament layer: 0x01 is seen by the main view; others, only by the views that ask for them. */
    protected open val layer: Int = LAYER_MAIN
    protected var built = false
    private var visible = true
    private var hasContent = false

    fun setTransform(m: FloatArray) {
        if (!built) return
        val tm = engine.transformManager
        val inst = tm.getInstance(entity)
        if (inst != 0) tm.setTransform(inst, m)
    }

    fun setVisible(v: Boolean) {
        visible = v
        applyVisibility()
    }

    protected fun setHasContent(v: Boolean) {
        hasContent = v
        applyVisibility()
    }

    private fun applyVisibility() {
        if (!built) return
        val rm = engine.renderableManager
        val inst = rm.getInstance(entity)
        if (inst != 0) rm.setLayerMask(inst, 0xFF, if (visible && hasContent) layer else 0x00)
    }

    protected fun registerRenderable() {
        engine.transformManager.create(entity)
        scene.addEntity(entity)
        built = true
        applyVisibility()
    }

    open fun destroy() {
        scene.removeEntity(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
    }
}

/** Lines or triangles with per-vertex color. */
class MeshEntity(
    engine: Engine, scene: Scene, private val type: PrimitiveType, material: Material
) : RenderEntity(engine, scene) {

    private val materialInstance: MaterialInstance = material.createInstance()
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null
    private var capacity = 0
    private var lastPositions = FloatArray(0)
    private var lastColors = IntArray(0)

    val vertexCount: Int get() = lastColors.size

    fun update(v: VertexList) {
        val n = v.vertexCount
        if (n == 0) {
            lastPositions = FloatArray(0); lastColors = IntArray(0)
            setHasContent(false)
            return
        }
        val pos = v.positions.data; val col = v.colors.data
        if (n == lastColors.size && sameFloats(pos, lastPositions, n * 3) && sameInts(col, lastColors, n)) {
            setHasContent(true)
            return
        }
        lastPositions = v.positions.toArray(); lastColors = v.colors.toArray()

        if (n > capacity) reallocate(nextCapacity(n), n)
        vb!!.setBufferAt(engine, 0, floatBuffer(pos, n * 3))
        vb!!.setBufferAt(engine, 1, colorBuffer(col, n))
        val rm = engine.renderableManager
        rm.setGeometryAt(rm.getInstance(entity), 0, type, vb!!, ib!!, 0, n)
        setHasContent(true)
    }

    private fun reallocate(cap: Int, count: Int) {
        val oldVb = vb; val oldIb = ib
        vb = VertexBuffer.Builder().vertexCount(cap).bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)
        val idx = ByteBuffer.allocateDirect(cap * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        for (i in 0 until cap) idx.put(i)
        idx.flip()
        ib = IndexBuffer.Builder().indexCount(cap).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        ib!!.setBuffer(engine, idx)
        capacity = cap

        if (!built) {
            RenderableManager.Builder(1).geometry(0, type, vb!!, ib!!, 0, count)
                .material(0, materialInstance).boundingBox(BIG_BOX).culling(false)
                .castShadows(false).receiveShadows(false)
                .build(engine, entity)
            registerRenderable()
        } else {
            val rm = engine.renderableManager
            rm.setGeometryAt(rm.getInstance(entity), 0, type, vb!!, ib!!, 0, count)
        }
        oldVb?.let { engine.destroyVertexBuffer(it) }
        oldIb?.let { engine.destroyIndexBuffer(it) }
    }

    override fun destroy() {
        super.destroy()
        vb?.let { engine.destroyVertexBuffer(it) }
        ib?.let { engine.destroyIndexBuffer(it) }
        engine.destroyMaterialInstance(materialInstance)
    }
}

/** Points drawn as camera-facing squares (4 vertices per point). */
class PointsEntity(engine: Engine, scene: Scene, material: Material) : RenderEntity(engine, scene) {

    private val materialInstance: MaterialInstance = material.createInstance()
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null
    private var capacity = 0 // in points
    private var lastPositions = FloatArray(0)
    private var lastColors = IntArray(0)
    private var lastSizes = FloatArray(0)

    val pointCount: Int get() = lastColors.size

    fun update(p: PointList) {
        val n = p.count
        if (n == 0) {
            lastPositions = FloatArray(0); lastColors = IntArray(0); lastSizes = FloatArray(0)
            setHasContent(false)
            return
        }
        if (n == lastColors.size && sameFloats(p.positions.data, lastPositions, n * 3) &&
            sameInts(p.colors.data, lastColors, n) && sameFloats(p.sizes.data, lastSizes, n)
        ) {
            setHasContent(true)
            return
        }
        lastPositions = p.positions.toArray(); lastColors = p.colors.toArray(); lastSizes = p.sizes.toArray()

        if (n > capacity) reallocate(nextCapacity(n), n * 6)
        val pos = ByteBuffer.allocateDirect(n * 4 * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val corners = ByteBuffer.allocateDirect(n * 4 * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until n) {
            val x = lastPositions[i * 3]; val y = lastPositions[i * 3 + 1]; val z = lastPositions[i * 3 + 2]
            val h = lastSizes[i] / 2f
            repeat(4) { pos.put(x); pos.put(y); pos.put(z) }
            // (offset in meters, unit corner) — the unit corner is used by circles.
            corners.put(-h); corners.put(-h); corners.put(-1f); corners.put(-1f)
            corners.put(h); corners.put(-h); corners.put(1f); corners.put(-1f)
            corners.put(h); corners.put(h); corners.put(1f); corners.put(1f)
            corners.put(-h); corners.put(h); corners.put(-1f); corners.put(1f)
        }
        pos.flip(); corners.flip()
        vb!!.setBufferAt(engine, 0, pos)
        vb!!.setBufferAt(engine, 1, colorBuffer(lastColors, n, repeat = 4))
        vb!!.setBufferAt(engine, 2, corners)
        val rm = engine.renderableManager
        rm.setGeometryAt(rm.getInstance(entity), 0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, n * 6)
        setHasContent(true)
    }

    private fun reallocate(cap: Int, count: Int) {
        val oldVb = vb; val oldIb = ib
        vb = VertexBuffer.Builder().vertexCount(cap * 4).bufferCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 2, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        val idx = ByteBuffer.allocateDirect(cap * 6 * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        for (i in 0 until cap) {
            val b = i * 4
            idx.put(b); idx.put(b + 1); idx.put(b + 2); idx.put(b); idx.put(b + 2); idx.put(b + 3)
        }
        idx.flip()
        ib = IndexBuffer.Builder().indexCount(cap * 6).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        ib!!.setBuffer(engine, idx)
        capacity = cap
        if (!built) {
            RenderableManager.Builder(1).geometry(0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, count)
                .material(0, materialInstance).boundingBox(BIG_BOX).culling(false)
                .castShadows(false).receiveShadows(false)
                .build(engine, entity)
            registerRenderable()
        } else {
            val rm = engine.renderableManager
            rm.setGeometryAt(rm.getInstance(entity), 0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, count)
        }
        oldVb?.let { engine.destroyVertexBuffer(it) }
        oldIb?.let { engine.destroyIndexBuffer(it) }
    }

    override fun destroy() {
        super.destroy()
        vb?.let { engine.destroyVertexBuffer(it) }
        ib?.let { engine.destroyIndexBuffer(it) }
        engine.destroyMaterialInstance(materialInstance)
    }
}

/** Segments with thickness in meters (4 vertices per segment, the shader orients them to the camera). */
class ThickLinesEntity(engine: Engine, scene: Scene, material: Material) : RenderEntity(engine, scene) {

    private val materialInstance: MaterialInstance = material.createInstance()
    private var vb: VertexBuffer? = null
    private var ib: IndexBuffer? = null
    private var capacity = 0 // in segments
    private var lastPositions = FloatArray(0)
    private var lastColors = IntArray(0)
    private var lastWidths = FloatArray(0)

    val segmentCount: Int get() = lastWidths.size

    fun update(s: com.juancams.rviz2mini.rviz.geom.SegmentList) {
        val n = s.count
        if (n == 0) {
            lastPositions = FloatArray(0); lastColors = IntArray(0); lastWidths = FloatArray(0)
            setHasContent(false)
            return
        }
        if (n == lastWidths.size && sameFloats(s.positions.data, lastPositions, n * 6) &&
            sameInts(s.colors.data, lastColors, n * 2) && sameFloats(s.widths.data, lastWidths, n)
        ) { setHasContent(true); return }
        lastPositions = s.positions.toArray(); lastColors = s.colors.toArray(); lastWidths = s.widths.toArray()

        if (n > capacity) reallocate(nextCapacity(n), n * 6)
        val pos = ByteBuffer.allocateDirect(n * 4 * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val other = ByteBuffer.allocateDirect(n * 4 * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val half = ByteBuffer.allocateDirect(n * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val cols = IntArray(n * 4)
        for (i in 0 until n) {
            val o = i * 6
            val ax = lastPositions[o]; val ay = lastPositions[o + 1]; val az = lastPositions[o + 2]
            val bx = lastPositions[o + 3]; val by = lastPositions[o + 4]; val bz = lastPositions[o + 5]
            val h = lastWidths[i] / 2f
            // v0 = a(+), v1 = a(-), v2 = b(+), v3 = b(-). At b the "other end" is a, so the sign
            // is flipped so that v0/v2 end up on the same side.
            pos.put(ax); pos.put(ay); pos.put(az); other.put(bx); other.put(by); other.put(bz); half.put(h)
            pos.put(ax); pos.put(ay); pos.put(az); other.put(bx); other.put(by); other.put(bz); half.put(-h)
            pos.put(bx); pos.put(by); pos.put(bz); other.put(ax); other.put(ay); other.put(az); half.put(-h)
            pos.put(bx); pos.put(by); pos.put(bz); other.put(ax); other.put(ay); other.put(az); half.put(h)
            cols[i * 4] = lastColors[i * 2]; cols[i * 4 + 1] = lastColors[i * 2]
            cols[i * 4 + 2] = lastColors[i * 2 + 1]; cols[i * 4 + 3] = lastColors[i * 2 + 1]
        }
        pos.flip(); other.flip(); half.flip()
        vb!!.setBufferAt(engine, 0, pos)
        vb!!.setBufferAt(engine, 1, colorBuffer(cols, n * 4))
        vb!!.setBufferAt(engine, 2, other)
        vb!!.setBufferAt(engine, 3, half)
        val rm = engine.renderableManager
        rm.setGeometryAt(rm.getInstance(entity), 0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, n * 6)
        setHasContent(true)
    }

    private fun reallocate(cap: Int, count: Int) {
        val oldVb = vb; val oldIb = ib
        vb = VertexBuffer.Builder().vertexCount(cap * 4).bufferCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 2, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 3, VertexBuffer.AttributeType.FLOAT, 0, 4)
            .build(engine)
        val idx = ByteBuffer.allocateDirect(cap * 6 * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        for (i in 0 until cap) {
            val b = i * 4
            idx.put(b); idx.put(b + 1); idx.put(b + 2); idx.put(b + 1); idx.put(b + 3); idx.put(b + 2)
        }
        idx.flip()
        ib = IndexBuffer.Builder().indexCount(cap * 6).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        ib!!.setBuffer(engine, idx)
        capacity = cap
        if (!built) {
            RenderableManager.Builder(1).geometry(0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, count)
                .material(0, materialInstance).boundingBox(BIG_BOX).culling(false)
                .castShadows(false).receiveShadows(false)
                .build(engine, entity)
            registerRenderable()
        } else {
            val rm = engine.renderableManager
            rm.setGeometryAt(rm.getInstance(entity), 0, PrimitiveType.TRIANGLES, vb!!, ib!!, 0, count)
        }
        oldVb?.let { engine.destroyVertexBuffer(it) }
        oldIb?.let { engine.destroyIndexBuffer(it) }
    }

    override fun destroy() {
        super.destroy()
        vb?.let { engine.destroyVertexBuffer(it) }
        ib?.let { engine.destroyIndexBuffer(it) }
        engine.destroyMaterialInstance(materialInstance)
    }
}

/** Rectangle with an RGBA texture on the XY plane (maps). */
class TextureQuadEntity(engine: Engine, scene: Scene, material: Material) : RenderEntity(engine, scene) {

    private val materialInstance: MaterialInstance = material.createInstance()
    private val vb: VertexBuffer = VertexBuffer.Builder().vertexCount(4).bufferCount(2)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
        .build(engine)
    private val ib: IndexBuffer = IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
    private var texture: Texture? = null
    private var texW = 0
    private var texH = 0

    init {
        val idx = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).asShortBuffer()
        idx.put(shortArrayOf(0, 1, 2, 0, 2, 3)); idx.flip()
        ib.setBuffer(engine, idx)
        // In Filament the first row of the image corresponds to v = 1: we flip V so that
        // row 0 of the OccupancyGrid (the one at the map origin) ends up at y = 0 (verified on the phone).
        vb.setBufferAt(engine, 1, floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), 8))
        RenderableManager.Builder(1).geometry(0, PrimitiveType.TRIANGLES, vb, ib)
            .material(0, materialInstance).boundingBox(BIG_BOX).culling(false)
            .castShadows(false).receiveShadows(false)
            .build(engine, entity)
        registerRenderable()
    }

    /** `rgba` of w*h*4 bytes; row 0 corresponds to y = 0. Rectangle size in meters. */
    fun update(rgba: ByteArray, w: Int, h: Int, widthM: Float, heightM: Float) {
        if (w <= 0 || h <= 0) { setHasContent(false); return }
        if (texture == null || w != texW || h != texH) {
            texture?.let { engine.destroyTexture(it) }
            texture = Texture.Builder().width(w).height(h).levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8).build(engine)
            texW = w; texH = h
            val sampler = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            materialInstance.setParameter("image", texture!!, sampler)
        }
        val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder())
        buf.put(rgba); buf.flip()
        texture!!.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        vb.setBufferAt(engine, 0, floatBuffer(floatArrayOf(0f, 0f, 0f, widthM, 0f, 0f, widthM, heightM, 0f, 0f, heightM, 0f), 12))
        setHasContent(true)
    }

    override fun destroy() {
        super.destroy()
        engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(ib)
        texture?.let { engine.destroyTexture(it) }
        engine.destroyMaterialInstance(materialInstance)
    }
}

/**
 * A camera image on a quad (`corners`: the corners of pixels (0,0), (w,0),
 * (w,h) and (0,h), in the entity's frame) for the Camera display background: it goes on its own layer
 * (only the camera view sees it) and with a depthless material that is drawn first,
 * so everything else in the scene stays in front.
 */
class ImagePlaneEntity(engine: Engine, scene: Scene, material: Material, override val layer: Int, priority: Int = 0) : RenderEntity(engine, scene) {

    private val materialInstance: MaterialInstance = material.createInstance()
    private val vb: VertexBuffer = VertexBuffer.Builder().vertexCount(4).bufferCount(2)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
        .build(engine)
    private val ib: IndexBuffer = IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
    private var texture: Texture? = null
    private var texW = 0
    private var texH = 0

    init {
        val idx = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).asShortBuffer()
        idx.put(shortArrayOf(0, 1, 2, 0, 2, 3)); idx.flip()
        ib.setBuffer(engine, idx)
        // As in TextureQuadEntity: image row 0 goes with v = 1.
        vb.setBufferAt(engine, 1, floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), 8))
        RenderableManager.Builder(1).geometry(0, PrimitiveType.TRIANGLES, vb, ib)
            .material(0, materialInstance).boundingBox(BIG_BOX).culling(false)
            .castShadows(false).receiveShadows(false).priority(priority)
            .build(engine, entity)
        registerRenderable()
    }

    /** Transparency (only with the overlay material). */
    fun setAlpha(a: Float) = materialInstance.setParameter("alpha", a)

    /** `rgba` of w*h*4 bytes (row 0 at the top). */
    fun update(rgba: java.nio.ByteBuffer, w: Int, h: Int, corners: FloatArray) {
        if (w <= 0 || h <= 0) { setHasContent(false); return }
        if (texture == null || w != texW || h != texH) {
            texture?.let { engine.destroyTexture(it) }
            texture = Texture.Builder().width(w).height(h).levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8).build(engine)
            texW = w; texH = h
            val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            materialInstance.setParameter("image", texture!!, sampler)
        }
        texture!!.setImage(engine, 0, Texture.PixelBufferDescriptor(rgba, Texture.Format.RGBA, Texture.Type.UBYTE))
        setCorners(corners)
        setHasContent(true)
    }

    fun setCorners(corners: FloatArray) = vb.setBufferAt(engine, 0, floatBuffer(corners, 12))

    override fun destroy() {
        super.destroy()
        engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(ib)
        texture?.let { engine.destroyTexture(it) }
        engine.destroyMaterialInstance(materialInstance)
    }
}

/** Filament layers: the normal one and the camera views' background. */
const val LAYER_MAIN = 0x01
const val LAYER_CAMERA_BACKGROUND = 0x02

private val BIG_BOX = Box(0f, 0f, 0f, 1e4f, 1e4f, 1e4f)

private fun sameFloats(a: FloatArray, b: FloatArray, n: Int): Boolean {
    if (b.size != n || a.size < n) return false
    for (i in 0 until n) if (a[i] != b[i]) return false
    return true
}

private fun sameInts(a: IntArray, b: IntArray, n: Int): Boolean {
    if (b.size != n || a.size < n) return false
    for (i in 0 until n) if (a[i] != b[i]) return false
    return true
}
