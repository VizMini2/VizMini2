// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz

import com.juancams.rviz2mini.platform.Log
import com.juancams.rviz2mini.platform.Platform

import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.ToneMapper
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.VertexBuffer
import com.google.android.filament.filamat.MaterialBuilder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Filament engine and GPU resources shared by all RViz tabs
 * (materials, axes and grid geometry). Reference-counted; UI thread only.
 *
 * Compiling the materials with filamat takes 5-10 s on a phone: it is done only once on a separate
 * thread ([prepare]) and the binaries are saved to disk, so [acquire] only loads them (ms).
 */
object FilamentHub {

    private const val TAG = "FilamentHub"
    /** After the last reference is released the engine lives a bit longer: rotating the phone does not recreate it. */
    private const val RELEASE_DELAY_MS = 5000L

    private var refs = 0
    private var alive = false
    private var filamentLoaded = false
    /** Bumped on every acquire: an old "destroy later" does nothing. */
    private var releaseToken = 0

    /** Where compiled materials and their fingerprint are stored (changes when another version is installed). Set by each platform. */
    @Volatile var materialsDir: () -> File = { File(com.juancams.rviz2mini.platform.Platform.cacheDir, "filamat") }
    @Volatile var fingerprint: () -> String = { "dev" }
    /** What to compile the materials for: mobile (OpenGL ES and Vulkan) or desktop (OpenGL). */
    @Volatile var materialPlatform: MaterialBuilder.Platform = MaterialBuilder.Platform.DESKTOP
    @Volatile var materialApi: MaterialBuilder.TargetApi = MaterialBuilder.TargetApi.OPENGL
    /** The engine backend (Android: the usual one; PC: OpenGL, which the materials are compiled for). */
    @Volatile var backend: Engine.Backend = Engine.Backend.OPENGL

    /** Material binaries (name -> filamat package), ready after [prepare]. */
    private val packages = HashMap<String, ByteArray>()
    private var preparing = false
    private val waiters = ArrayList<() -> Unit>()

    /** If the materials could not be prepared, the reason (the 3D viewer cannot work). */
    var prepareError: String? = null
        private set

    /** Materials already compiled: [acquire] does not block. */
    var isPrepared = false
        private set

    lateinit var engine: Engine
        private set

    lateinit var axisX: VertexBuffer; private set
    lateinit var axisY: VertexBuffer; private set
    lateinit var axisZ: VertexBuffer; private set
    lateinit var lineIndices: IndexBuffer; private set
    lateinit var matRed: Material; private set
    lateinit var matGreen: Material; private set
    lateinit var matBlue: Material; private set
    lateinit var matLine: Material; private set

    /** Per-vertex color (opaque / transparent with premultiplied alpha). */
    lateinit var matVertexColor: Material; private set
    lateinit var matVertexColorTransparent: Material; private set
    /** Points as camera-facing squares (CUSTOM0 = corner * size). */
    lateinit var matPoints: Material; private set
    lateinit var matPointsTransparent: Material; private set
    lateinit var matCircles: Material; private set
    lateinit var matCirclesTransparent: Material; private set
    /** Camera-facing segments with width in meters (CUSTOM0 = other end, CUSTOM1.x = ±half width). */
    lateinit var matThickLines: Material; private set
    lateinit var matThickLinesTransparent: Material; private set
    /** Text: atlas glyphs as camera-facing quads (CUSTOM0 = offset in m, UV0 = atlas). */
    lateinit var matText: Material; private set
    lateinit var textAtlas: com.juancams.rviz2mini.rviz.render.TextAtlas; private set

    /** Quad with RGBA texture (occupancy maps). */
    lateinit var matTexture: Material; private set

    /** Background image of the Camera display: opaque, does not read or write depth. */
    lateinit var matBackground: Material; private set

    /** Camera display image over the scene, with transparency ("alpha" parameter). */
    lateinit var matOverlay: Material; private set

    /** No tone mapping (linear): colors come out as requested, same as in RViz. */
    lateinit var colorGrading: ColorGrading; private set

    lateinit var gridVertices: VertexBuffer; private set
    lateinit var gridIndices: IndexBuffer; private set
    lateinit var gridMaterial: Material; private set
    var gridIndexCount = 0; private set

    const val AXIS_LENGTH = 0.5f
    const val GRID_SIZE = 50

    /** Starts compiling (or reading from disk) the materials in the background. Idempotent; UI thread. */
    fun prepare() {
        if (isPrepared || preparing) return
        preparing = true
        Thread({
            val loaded = HashMap<String, ByteArray>()
            var error: String? = null
            try {
                loadOrCompile(loaded)
            } catch (t: Throwable) {
                Log.e(TAG, "Error preparando los materiales", t)
                error = t.message ?: t.javaClass.simpleName
            }
            Platform.main.post {
                packages.putAll(loaded)
                preparing = false
                prepareError = error
                isPrepared = error == null
                val pending = ArrayList(waiters)
                waiters.clear()
                for (w in pending) w()
            }
        }, "filament-materials").start()
    }

    /**
     * Runs [block] on the UI thread when preparation finishes (right away if it was done).
     * If it failed, [isPrepared] is false and [prepareError] says why.
     */
    fun whenPrepared(block: () -> Unit) {
        if (isPrepared) { block(); return }
        prepareError = null
        waiters.add(block)
        prepare()
    }

    private fun loadOrCompile(out: HashMap<String, ByteArray>) {
        val t0 = System.currentTimeMillis()
        val dir = materialsDir()
        // The binaries depend on the Filament version and the shaders: they are invalidated on install.
        val fingerprint = "${fingerprint()}-${MATERIALS.size}-$materialPlatform"
        val stampFile = File(dir, "stamp")
        val valid = try { stampFile.readText() == fingerprint } catch (_: Exception) { false }
        if (valid) {
            for (spec in MATERIALS) {
                val f = File(dir, spec.name + ".filamat")
                if (f.isFile) out[spec.name] = f.readBytes()
            }
            if (out.size == MATERIALS.size) {
                Log.i(TAG, "Materiales leídos de disco en ${System.currentTimeMillis() - t0} ms")
                return
            }
            out.clear()
        }
        dir.deleteRecursively()
        dir.mkdirs()
        Filament.init() // loads the native libraries (repeating it does nothing)
        MaterialBuilder.init()
        try {
            for (spec in MATERIALS) {
                val pkg = spec.builder().targetApi(materialApi).platform(materialPlatform).build()
                check(pkg.isValid) { "Couldn't compile material ${spec.name}" }
                val bytes = ByteArray(pkg.buffer.remaining())
                pkg.buffer.get(bytes)
                out[spec.name] = bytes
                File(dir, spec.name + ".filamat").writeBytes(bytes)
            }
        } finally {
            MaterialBuilder.shutdown()
        }
        stampFile.writeText(fingerprint)
        Log.i(TAG, "Materiales compilados en ${System.currentTimeMillis() - t0} ms")
    }

    /** UI thread. Requires [isPrepared] (see [whenPrepared]). */
    fun acquire() {
        check(isPrepared) { "FilamentHub.acquire() called before prepare()" }
        releaseToken++
        refs++
        if (alive) return
        alive = true
        if (!filamentLoaded) {
            Filament.init()
            filamentLoaded = true
        }
        engine = Engine.create(backend)
        colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)

        axisX = lineBuffer(floatArrayOf(0f, 0f, 0f, AXIS_LENGTH, 0f, 0f))
        axisY = lineBuffer(floatArrayOf(0f, 0f, 0f, 0f, AXIS_LENGTH, 0f))
        axisZ = lineBuffer(floatArrayOf(0f, 0f, 0f, 0f, 0f, AXIS_LENGTH))
        lineIndices = indexBuffer(shortArrayOf(0, 1))

        matRed = material("UnlitRed")
        matGreen = material("UnlitGreen")
        matBlue = material("UnlitBlue")
        matLine = material("UnlitYellow")
        gridMaterial = material("UnlitGrey")
        matVertexColor = material("VertexColor")
        matVertexColorTransparent = material("VertexColorT")
        matPoints = material("PointsS")
        matPointsTransparent = material("PointsST")
        matCircles = material("PointsC")
        matCirclesTransparent = material("PointsCT")
        matThickLines = material("ThickLines")
        matThickLinesTransparent = material("ThickLinesT")
        matText = material("Text")
        matTexture = material("Texture")
        matBackground = material("Background")
        matOverlay = material("Overlay")
        textAtlas = com.juancams.rviz2mini.rviz.render.TextAtlas(engine)

        createGrid()
    }

    fun release() {
        if (refs <= 0) return
        if (--refs > 0) return
        val token = ++releaseToken
        Platform.main.postDelayed({ if (token == releaseToken && refs == 0 && alive) destroyAll() }, RELEASE_DELAY_MS)
    }

    private fun destroyAll() {
        alive = false
        for (vb in listOf(axisX, axisY, axisZ, gridVertices)) engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(lineIndices)
        engine.destroyIndexBuffer(gridIndices)
        textAtlas.destroy(engine)
        for (m in listOf(
            matRed, matGreen, matBlue, matLine, gridMaterial, matVertexColor, matVertexColorTransparent,
            matPoints, matPointsTransparent, matCircles, matCirclesTransparent, matThickLines, matThickLinesTransparent, matTexture, matBackground, matOverlay, matText
        )) engine.destroyMaterial(m)
        engine.destroyColorGrading(colorGrading)
        engine.destroy()
    }

    fun newLineVertexBuffer(): VertexBuffer =
        VertexBuffer.Builder().vertexCount(2).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)

    private fun lineBuffer(data: FloatArray): VertexBuffer {
        val vb = newLineVertexBuffer()
        vb.setBufferAt(engine, 0, floatBuffer(data))
        return vb
    }

    private fun floatBuffer(data: FloatArray) =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data).position(0)
        }

    private fun indexBuffer(indices: ShortArray): IndexBuffer {
        val buf = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        buf.put(indices).position(0)
        val ib = IndexBuffer.Builder().indexCount(indices.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        ib.setBuffer(engine, buf)
        return ib
    }

    private class MaterialSpec(val name: String, val builder: () -> MaterialBuilder)

    private val MATERIALS = listOf(
        MaterialSpec("UnlitRed") { unlitBuilder(1.0f, 0.0f, 0.0f) },
        MaterialSpec("UnlitGreen") { unlitBuilder(0.0f, 1.0f, 0.0f) },
        MaterialSpec("UnlitBlue") { unlitBuilder(0.0f, 0.5f, 1.0f) },
        MaterialSpec("UnlitYellow") { unlitBuilder(1.0f, 1.0f, 0.0f) },
        MaterialSpec("UnlitGrey") { unlitBuilder(0.4f, 0.4f, 0.4f) },
        MaterialSpec("VertexColor") { vertexColorBuilder(transparent = false) },
        MaterialSpec("VertexColorT") { vertexColorBuilder(transparent = true) },
        MaterialSpec("PointsS") { pointsBuilder(transparent = false) },
        MaterialSpec("PointsST") { pointsBuilder(transparent = true) },
        MaterialSpec("PointsC") { pointsBuilder(transparent = false, circles = true) },
        MaterialSpec("PointsCT") { pointsBuilder(transparent = true, circles = true) },
        MaterialSpec("ThickLines") { thickLinesBuilder(transparent = false) },
        MaterialSpec("ThickLinesT") { thickLinesBuilder(transparent = true) },
        MaterialSpec("Text") { textBuilder() },
        MaterialSpec("Texture") { textureBuilder() },
        MaterialSpec("Background") { backgroundBuilder() },
        MaterialSpec("Overlay") { overlayBuilder() },
    )

    private fun material(name: String): Material {
        val bytes = packages[name] ?: error("Missing material $name")
        val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buf.flip()
        return Material.Builder().payload(buf, bytes.size).build(engine)
    }

    private fun unlitBuilder(r: Float, g: Float, b: Float): MaterialBuilder =
        MaterialBuilder().name("Unlit").shading(MaterialBuilder.Shading.UNLIT)
            .material("void material(inout MaterialInputs inputs) { prepareMaterial(inputs); inputs.baseColor = vec4(${srgbToLinear(r)}, ${srgbToLinear(g)}, ${srgbToLinear(b)}, 1.0); }")

    /**
     * Filament works in linear space and encodes the output in sRGB; RViz draws colors as
     * they are. To see exactly the requested color (0-1, sRGB) it must be converted to linear.
     */
    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    /** Text: atlas glyphs as camera-facing quads (CUSTOM0 = offset, UV0 = atlas). */
    private fun textBuilder(): MaterialBuilder =
        MaterialBuilder().name("Text").shading(MaterialBuilder.Shading.UNLIT)
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .require(MaterialBuilder.VertexAttribute.CUSTOM0)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .samplerParameter(MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT, MaterialBuilder.ParameterPrecision.DEFAULT, "atlas")
            .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
            .depthWrite(false)
            .depthCulling(false) // as in RViz, text always drawn above the geometry
            .doubleSided(true)
            // CUSTOM0 = (x, y, mode): mode 0 = offset in meters; mode 1 = in screen
            // pixels (converted to meters using depth: constant size when zooming).
            .materialVertex(
                "void materialVertex(inout MaterialVertexInputs material) {" +
                    " vec4 c = getCustom0(); mat4 v = getWorldFromViewMatrix();" +
                    " float d = -(getViewFromWorldMatrix() * vec4(material.worldPosition.xyz, 1.0)).z;" +
                    " mat4 p = getClipFromViewMatrix();" +
                    " float persp = p[2][3] < -0.5 ? max(d, 0.001) : 1.0;" +
                    " float wpp = 2.0 * persp / (p[1][1] * getResolution().y);" +
                    " float k = c.z > 0.5 ? wpp : 1.0;" +
                    " material.worldPosition.xyz += (v[0].xyz * c.x + v[1].xyz * c.y) * k; }"
            )
            .material(
                "void material(inout MaterialInputs inputs) { prepareMaterial(inputs);" +
                    " vec4 t = texture(materialParams_atlas, getUV0()); vec4 c = getColor();" +
                    " t.rgb = mix(t.rgb / 12.92, pow((t.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), t.rgb)); c.rgb = mix(c.rgb / 12.92, pow((c.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c.rgb));" +
                    " inputs.baseColor = vec4(t.rgb * c.rgb * c.a, t.a * c.a); }"
            )

    /** Quad with RGBA texture (occupancy maps). */
    private fun textureBuilder(): MaterialBuilder =
        MaterialBuilder().name("Texture").shading(MaterialBuilder.Shading.UNLIT)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .samplerParameter(
                MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT,
                MaterialBuilder.ParameterPrecision.DEFAULT, "image"
            )
            .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
            .doubleSided(true)
            .material(
                "void material(inout MaterialInputs inputs) { prepareMaterial(inputs);" +
                    " vec4 c = texture(materialParams_image, getUV0()); c.rgb = mix(c.rgb / 12.92, pow((c.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c.rgb));" +
                    " inputs.baseColor = vec4(c.rgb * c.a, c.a); }"
            )

    private fun backgroundBuilder(): MaterialBuilder =
        MaterialBuilder().name("Background").shading(MaterialBuilder.Shading.UNLIT)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .samplerParameter(
                MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT,
                MaterialBuilder.ParameterPrecision.DEFAULT, "image"
            )
            .blending(MaterialBuilder.BlendingMode.OPAQUE)
            .depthWrite(false).depthCulling(false)
            .doubleSided(true)
            .material(
                "void material(inout MaterialInputs inputs) { prepareMaterial(inputs);" +
                    " vec4 c = texture(materialParams_image, getUV0()); c.rgb = mix(c.rgb / 12.92, pow((c.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c.rgb));" +
                    " inputs.baseColor = vec4(c.rgb, 1.0); }"
            )

    private fun overlayBuilder(): MaterialBuilder =
        MaterialBuilder().name("Overlay").shading(MaterialBuilder.Shading.UNLIT)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .samplerParameter(
                MaterialBuilder.SamplerType.SAMPLER_2D, MaterialBuilder.SamplerFormat.FLOAT,
                MaterialBuilder.ParameterPrecision.DEFAULT, "image"
            )
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "alpha")
            .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
            .depthWrite(false).depthCulling(false)
            .doubleSided(true)
            .material(
                "void material(inout MaterialInputs inputs) { prepareMaterial(inputs);" +
                    " vec4 c = texture(materialParams_image, getUV0()); c.rgb = mix(c.rgb / 12.92, pow((c.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c.rgb));" +
                    " float a = materialParams.alpha; inputs.baseColor = vec4(c.rgb * a, a); }"
            )

    private fun vertexColorBuilder(transparent: Boolean): MaterialBuilder {
        val b = MaterialBuilder().name(if (transparent) "VertexColorT" else "VertexColor")
            .shading(MaterialBuilder.Shading.UNLIT)
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .doubleSided(true)
            .material(
                "void material(inout MaterialInputs inputs) { prepareMaterial(inputs);" +
                    " vec4 c = getColor(); c.rgb = mix(c.rgb / 12.92, pow((c.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c.rgb)); inputs.baseColor = vec4(c.rgb * c.a, c.a); }"
            )
        if (transparent) b.blending(MaterialBuilder.BlendingMode.TRANSPARENT)
        return b
    }

    /**
     * Points: CUSTOM0 = (x, y offset in meters, unit corner x, y). The vertex shader moves
     * each corner in the camera plane (squares always facing, like RViz's "Squares").
     * Circles discard what lies outside the inscribed circle (MASKED if opaque).
     */
    private fun pointsBuilder(transparent: Boolean, circles: Boolean = false): MaterialBuilder {
        val b = MaterialBuilder().name("Points" + (if (circles) "C" else "S") + (if (transparent) "T" else ""))
            .shading(MaterialBuilder.Shading.UNLIT)
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .require(MaterialBuilder.VertexAttribute.CUSTOM0)
            .doubleSided(true)
        if (circles) {
            b.variable(MaterialBuilder.Variable.CUSTOM0, "corner")
                .materialVertex(
                    "void materialVertex(inout MaterialVertexInputs material) {" +
                        " vec4 c = getCustom0(); mat4 v = getWorldFromViewMatrix();" +
                        " material.worldPosition.xyz += v[0].xyz * c.x + v[1].xyz * c.y;" +
                        " material.corner = vec4(c.zw, 0.0, 0.0); }"
                )
                .material(
                    "void material(inout MaterialInputs inputs) { prepareMaterial(inputs);" +
                        " vec4 c = getColor(); c.rgb = mix(c.rgb / 12.92, pow((c.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c.rgb)); float inside = dot(variable_corner.xy, variable_corner.xy) <= 1.0 ? 1.0 : 0.0;" +
                        " float a = c.a * inside; inputs.baseColor = vec4(c.rgb * a, a); }"
                )
            b.blending(if (transparent) MaterialBuilder.BlendingMode.TRANSPARENT else MaterialBuilder.BlendingMode.MASKED)
        } else {
            b.materialVertex(
                "void materialVertex(inout MaterialVertexInputs material) {" +
                    " vec4 c = getCustom0(); mat4 v = getWorldFromViewMatrix();" +
                    " material.worldPosition.xyz += v[0].xyz * c.x + v[1].xyz * c.y; }"
            ).material(
                "void material(inout MaterialInputs inputs) { prepareMaterial(inputs);" +
                    " vec4 c = getColor(); c.rgb = mix(c.rgb / 12.92, pow((c.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c.rgb)); inputs.baseColor = vec4(c.rgb * c.a, c.a); }"
            )
            if (transparent) b.blending(MaterialBuilder.BlendingMode.TRANSPARENT)
        }
        return b
    }

    /** Each vertex is offset perpendicular to the segment and to the camera direction. */
    private fun thickLinesBuilder(transparent: Boolean): MaterialBuilder {
        val b = MaterialBuilder().name(if (transparent) "ThickLinesT" else "ThickLines")
            .shading(MaterialBuilder.Shading.UNLIT)
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .require(MaterialBuilder.VertexAttribute.CUSTOM0)
            .require(MaterialBuilder.VertexAttribute.CUSTOM1)
            .doubleSided(true)
            .materialVertex(
                "void materialVertex(inout MaterialVertexInputs material) {" +
                    " vec3 dir = mat3(getWorldFromModelMatrix()) * (getCustom0().xyz - getPosition().xyz);" +
                    " mat4 v = getWorldFromViewMatrix(); vec3 side = cross(dir, v[2].xyz); float l = length(side);" +
                    " side = l > 1e-6 ? side / l : v[0].xyz;" +
                    " material.worldPosition.xyz += side * getCustom1().x; }"
            )
            .material(
                "void material(inout MaterialInputs inputs) { prepareMaterial(inputs);" +
                    " vec4 c = getColor(); c.rgb = mix(c.rgb / 12.92, pow((c.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c.rgb)); inputs.baseColor = vec4(c.rgb * c.a, c.a); }"
            )
        if (transparent) b.blending(MaterialBuilder.BlendingMode.TRANSPARENT)
        return b
    }

    private fun createGrid() {
        val linesCount = (GRID_SIZE + 1) * 2
        val vertexData = FloatArray(linesCount * 2 * 3)
        var index = 0
        val half = GRID_SIZE / 2f
        for (i in -GRID_SIZE / 2..GRID_SIZE / 2) {
            vertexData[index++] = -half; vertexData[index++] = i.toFloat(); vertexData[index++] = 0f
            vertexData[index++] = half; vertexData[index++] = i.toFloat(); vertexData[index++] = 0f
            vertexData[index++] = i.toFloat(); vertexData[index++] = -half; vertexData[index++] = 0f
            vertexData[index++] = i.toFloat(); vertexData[index++] = half; vertexData[index++] = 0f
        }
        gridVertices = VertexBuffer.Builder().vertexCount(linesCount * 2).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
        gridVertices.setBufferAt(engine, 0, floatBuffer(vertexData))
        gridIndexCount = linesCount * 2
        gridIndices = indexBuffer(ShortArray(gridIndexCount) { it.toShort() })
    }
}
