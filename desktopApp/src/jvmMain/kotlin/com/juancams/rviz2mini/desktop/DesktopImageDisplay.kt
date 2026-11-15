// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.desktop

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import com.juancams.rviz2mini.camera.CameraFrameInfo
import com.juancams.rviz2mini.camera.ImageTopic
import com.juancams.rviz2mini.camera.ImageTopics
import com.juancams.rviz2mini.rviz.display.Display
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayHost
import com.juancams.rviz2mini.rviz.display.FrameContext
import com.juancams.rviz2mini.rviz.display.RvizImageStream
import com.juancams.rviz2mini.rviz.display.RvizImageStreams
import com.juancams.rviz2mini.rviz.display.StatusLevel
import com.juancams.rviz2mini.rviz.display.TopicDisplay
import com.juancams.rviz2mini.rviz.display.kindOf
import com.juancams.rviz2mini.ui.common.UiImages
import java.util.Locale

/** The images of the Image displays of a PC RViz view (the view draws them in its corners). */
class ImageOverlays {
    class Panel(val id: String, val corner: String, val x: Double, val y: Double, val size: Float, val alpha: Float, val image: ImageBitmap?, val name: String,
                /** It has an annotations topic, and whether they are shown. */
                val hasAnnotations: Boolean, val showAnnotations: Boolean)
    val panels = mutableStateMapOf<String, Panel>()
}

/** "Image" display on the PC: like the Android one, the image in a corner of the 3D view (width = fraction of the view). */
class DesktopImageDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config) {
    private var stream: RvizImageStream? = null
    private var lastInfo: CameraFrameInfo? = null
    private var error: String? = null
    private var visible = true
    private val overlays get() = host.overlay as? ImageOverlays

    override fun onActivate() {
        lastInfo = null; error = null
        val topic = ImageTopics.normalize(config.topic)
        if (!ImageTopics.isValidName(topic)) { setStatus(StatusLevel.ERROR, "Invalid topic: '${config.topic}'"); return }
        publish(null)
        stream = RvizImageStreams.open(host, ImageTopic(topic, kindOf(topic)), config.qosProfile(), com.juancams.rviz2mini.rviz.display.annotationsTopic(config), { argb, w, h, info ->
            if (!active) return@open
            lastInfo = info; error = null
            publish(UiImages.fromArgb(argb, w, h))
        }) { error = it }
    }

    private fun publish(image: ImageBitmap?) {
        val o = overlays ?: return
        val img = image ?: o.panels[config.id]?.image
        if (!visible) { o.panels.remove(config.id); return }
        o.panels[config.id] = ImageOverlays.Panel(config.id, config.string("corner"), config.number("x"), config.number("y"), config.number("size").toFloat(),
            config.number("alpha").toFloat(), img, config.name, config.string("annotations").isNotBlank(), config.bool("showAnnotations"))
    }

    override fun onDeactivate() {
        stream?.stop(); stream = null
        overlays?.panels?.remove(config.id)
    }

    override fun onConfigChanged(old: DisplayConfig) {
        if (!active) return
        if (old.topic != config.topic || old.qosKey() != config.qosKey() || old.string("annotations") != config.string("annotations") ||
            old.bool("showAnnotations") != config.bool("showAnnotations")) { onDeactivate(); onActivate() } else publish(null)
    }

    override fun render(ctx: FrameContext) {
        val info = lastInfo
        when {
            error != null -> setStatus(StatusLevel.ERROR, error!!)
            info == null -> waiting("Waiting for images on ${config.topic}")
            RvizImageStreams.annotationsError(stream?.annotations) != null -> setStatus(StatusLevel.WARN, RvizImageStreams.annotationsError(stream?.annotations)!!)
            else -> setStatus(StatusLevel.OK, String.format(Locale.ROOT, "%d×%d %s · %.1f fps", info.width, info.height, info.encoding, info.fps) +
                RvizImageStreams.annotationsSuffix(stream?.annotations))
        }
    }

    override fun setVisible(v: Boolean) { visible = v; if (!v) overlays?.panels?.remove(config.id) else if (active) publish(null) }
}
