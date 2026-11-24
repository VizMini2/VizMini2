// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.rviz.display

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.camera.CameraStream
import com.juancams.rviz2mini.camera.ImageTopic
import com.juancams.rviz2mini.camera.ImageTopics
import com.juancams.rviz2mini.rviz.geom.OverlayLayout
import java.util.Locale

/**
 * "Image" display (like RViz2's): a camera image in a panel over the 3D view. It starts in the chosen corner (several
 * panels in the same corner stack up); drag it with one finger to put it anywhere, pinch it to resize it, and use
 * the eye button (or double tap) to show or hide its annotations. Raw or compressed depending on the topic.
 */
class ImageDisplay(host: DisplayHost, config: DisplayConfig) : Display(host, config), CameraStream.Listener {

    private var stream: CameraStream? = null
    /** The panel: the image plus the annotations button. */
    private var panel: FrameLayout? = null
    private var image: com.juancams.rviz2mini.camera.AnnotatedImageView? = null
    private var eye: ImageButton? = null
    private var annotations: com.juancams.rviz2mini.camera.ImageAnnotationSource? = null
    private var frames = 0L
    private var lastInfo: CameraStream.FrameInfo? = null
    private var error: String? = null

    override fun onActivate() = start()

    override fun onDeactivate() {
        stream?.stop(); stream = null
        annotations?.close(); annotations = null
        panel?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        panel = null; image = null; eye = null
    }

    override fun onConfigChanged(old: DisplayConfig) {
        if (!active) return
        if (old.topic != config.topic || old.qosKey() != config.qosKey() || old.string("annotations") != config.string("annotations") ||
            old.bool("showAnnotations") != config.bool("showAnnotations")) { onDeactivate(); start() } else layoutView()
    }

    private fun start() {
        frames = 0; lastInfo = null; error = null
        val topic = ImageTopics.normalize(config.topic)
        if (!ImageTopics.isValidName(topic)) { setStatus(StatusLevel.ERROR, "Invalid topic: '${config.topic}'"); return }
        val overlay = (host.overlay as? FrameLayout) ?: run { setStatus(StatusLevel.ERROR, "No room for the image"); return }
        annotations = annotationsTopic(config)?.let { com.juancams.rviz2mini.camera.ImageAnnotationSource(it) }
        val ctx = overlay.context
        val img = com.juancams.rviz2mini.camera.AnnotatedImageView(ctx).apply {
            annotations = this@ImageDisplay.annotations?.state
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            contentDescription = config.name
        }
        val d = ctx.resources.displayMetrics.density
        val button = ImageButton(ctx).apply {
            setImageResource(if (config.bool("showAnnotations")) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            contentDescription = ctx.getString(R.string.rviz_image_annotations, config.name)
            visibility = if (config.string("annotations").isBlank()) View.GONE else View.VISIBLE
            setOnClickListener { toggleAnnotations() }
        }
        panel = FrameLayout(ctx).apply {
            addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(button, FrameLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt(), Gravity.TOP or Gravity.START))
            setOnTouchListener(DragAndPinch())
        }
        image = img; eye = button
        layoutView()
        stream = CameraStream(ImageTopic(topic, kindOf(topic)), this, config.qosProfile()).also { it.start() }
    }

    /**
     * The change goes after the current touch or click: applying it re-lays the panel (it may move to another parent),
     * and removing a view while it is handling its own event crashes Android.
     */
    private fun applyLater(c: DisplayConfig) { (panel ?: return).post { if (active) host.updateDisplayConfig(c) } }

    private fun toggleAnnotations() = applyLater(config.with("showAnnotations", (!config.bool("showAnnotations")).toString()))

    /** Image aspect ratio (width / height), 4:3 until the first image arrives. */
    private fun aspect(): Pair<Int, Int> = lastInfo?.let { it.width to it.height } ?: (4 to 3)

    /**
     * In its corner (a column per corner, so several panels stack) or, once dragged, at its own position; the width is
     * a fraction of the view and the height follows the image.
     */
    private fun layoutView() {
        val v = panel ?: return
        val overlay = (host.overlay as? FrameLayout) ?: return
        val vw = overlay.width.takeIf { it > 0 } ?: overlay.resources.displayMetrics.widthPixels
        val vh = overlay.height.takeIf { it > 0 } ?: overlay.resources.displayMetrics.heightPixels
        val m = (6 * overlay.resources.displayMetrics.density).toInt()
        v.alpha = config.number("alpha").toFloat()
        val (iw, ih) = aspect()
        val r = OverlayLayout.rect(config.string("corner"), config.number("x"), config.number("y"), config.number("size"), vw, vh, iw, ih, m)
        if (OverlayLayout.isFree(config.number("x"), config.number("y"))) {
            if (v.parent !== overlay) { (v.parent as? android.view.ViewGroup)?.removeView(v); overlay.addView(v) }
            v.translationX = 0f; v.translationY = 0f
            v.layoutParams = FrameLayout.LayoutParams(r[2], FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = r[0]; topMargin = r[1] }
        } else {
            val column = column(overlay, config.string("corner"))
            if (v.parent !== column) { (v.parent as? android.view.ViewGroup)?.removeView(v); column.addView(v) }
            v.translationX = 0f; v.translationY = 0f
            v.layoutParams = android.widget.LinearLayout.LayoutParams(r[2], android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, m, m, m) }
        }
    }

    private fun column(overlay: FrameLayout, corner: String): android.widget.LinearLayout {
        val tag = "image-column:$corner"
        (overlay.findViewWithTag<android.widget.LinearLayout>(tag))?.let { return it }
        val gravity = when (corner) {
            "Top left" -> Gravity.TOP or Gravity.START
            "Top right" -> Gravity.TOP or Gravity.END
            "Bottom left" -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.BOTTOM or Gravity.END
        }
        return android.widget.LinearLayout(overlay.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            this.tag = tag
            overlay.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity))
        }
    }

    /** One finger moves the panel, two fingers resize it, a double tap shows or hides the annotations. */
    private inner class DragAndPinch : View.OnTouchListener {
        private var startX = 0f; private var startY = 0f
        private var startRect: IntArray? = null
        private var startDist = 0f
        private var pinching = false
        private var moved = false
        private val doubleTap = GestureDetector(host.androidContext as android.content.Context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { if (config.string("annotations").isNotBlank()) toggleAnnotations(); return true }
        })

        private fun rectOf(v: View): IntArray {
            val overlay = host.overlay as FrameLayout
            val loc = IntArray(2).also { v.getLocationInWindow(it) }; val o = IntArray(2).also { overlay.getLocationInWindow(it) }
            return intArrayOf(loc[0] - o[0], loc[1] - o[1], v.width, v.height)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            doubleTap.onTouchEvent(e)
            val overlay = host.overlay as? FrameLayout ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; startRect = rectOf(v); pinching = false; moved = false; v.parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { startDist = dist(e); pinching = true; startRect = rectOf(v) }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching && e.pointerCount >= 2 && startDist > 0) {
                        val s = dist(e) / startDist
                        v.scaleX = s; v.scaleY = s; v.pivotX = 0f; v.pivotY = 0f
                        moved = true
                    } else if (!pinching) {
                        val dx = e.rawX - startX; val dy = e.rawY - startY
                        if (moved || Math.hypot(dx.toDouble(), dy.toDouble()) > 8 * v.resources.displayMetrics.density) {
                            moved = true; v.translationX = dx; v.translationY = dy
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val r = startRect
                    if (moved && r != null) {
                        val vw = overlay.width; val vh = overlay.height
                        val c = if (pinching) {
                            val size = OverlayLayout.resized(config.number("size"), 0f, vw, v.scaleX)
                            config.with("size", size.toString()).with("x", (r[0].toDouble() / vw).toString()).with("y", (r[1].toDouble() / vh).toString())
                        } else OverlayLayout.moved(r, v.translationX, v.translationY, vw, vh).let { (x, y) -> config.with("x", x.toString()).with("y", y.toString()) }
                        v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f; v.translationY = 0f
                        if (e.actionMasked == MotionEvent.ACTION_UP) applyLater(c) else v.post { layoutView() }
                    }
                    pinching = false; moved = false
                }
            }
            return true
        }

        private fun dist(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
    }

    override fun onFrame(bitmap: Bitmap, info: CameraStream.FrameInfo) {
        if (!active) return
        frames++
        val first = lastInfo == null
        lastInfo = info
        error = null
        image?.setImageBitmap(bitmap)
        // With the real aspect ratio the panel may have to move to stay inside the view.
        if (first) layoutView()
    }

    override fun onStreamError(message: String) { error = message }

    override fun render(ctx: FrameContext) {
        val info = lastInfo
        when {
            error != null -> setStatus(StatusLevel.ERROR, error!!)
            info == null -> waiting("Waiting for images on ${config.topic}")
            annotations?.error != null -> setStatus(StatusLevel.WARN, annotations!!.error!!)
            else -> setStatus(StatusLevel.OK, String.format(Locale.ROOT, "%d×%d %s · %.1f fps", info.width, info.height, info.encoding, info.fps) +
                (annotations?.let { a -> if (a.type == null) " · waiting for ${a.topic}" else " · ${a.state.shapes(com.juancams.rviz2mini.camera.ImageAnnotationSource.nowMs()).size} annotations" } ?: ""))
        }
    }

    override fun setVisible(v: Boolean) { panel?.visibility = if (v) View.VISIBLE else View.GONE }

    /** For tests: the panel, its image and the annotations button. */
    internal fun panelForTest(): View? = panel
    internal fun eyeForTest(): View? = eye

    companion object {
        fun kindOf(topic: String) = com.juancams.rviz2mini.rviz.display.kindOf(topic)
    }
}
