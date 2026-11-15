// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes

/**
 * Short looping video without sound, cropped like an ImageView in CENTER_CROP (cards of the
 * "+" catalog). While loading, or if the system has animations disabled, the
 * still screenshot is shown.
 */
class LoopingVideoView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {

    private val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val texture = TextureView(context)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var video: Int = 0
    private var videoW = 0
    private var videoH = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(poster, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = this
    }

    fun setVideo(@RawRes video: Int, @DrawableRes posterRes: Int) {
        this.video = video
        poster.setImageResource(posterRes)
        if (texture.isAvailable) texture.surfaceTexture?.let { start(it) }
    }

    /** With the card in the background no battery is wasted. */
    fun pause() { player?.takeIf { it.isPlaying }?.pause() }
    fun resume() { player?.start() }

    private fun animationsOff() =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun start(st: SurfaceTexture) {
        if (video == 0 || player != null || animationsOff()) return
        val s = Surface(st).also { surface = it }
        try {
            player = MediaPlayer().apply {
                setDataSource(context, Uri.parse("android.resource://${context.packageName}/$video"))
                setSurface(s)
                isLooping = true
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; applyCrop() }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) poster.animate().alpha(0f).setDuration(250).start()
                    false
                }
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, what, extra -> Log.w("VizMini2", "Vídeo del catálogo: error $what/$extra"); true }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w("VizMini2", "No se pudo reproducir el vídeo del catálogo", e)
            release()
        }
    }

    private fun release() {
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    /** Scales the video to fill the view without distorting it (the excess is cut off, centered). */
    private fun applyCrop() {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (videoW == 0 || videoH == 0 || vw == 0f || vh == 0f) return
        val s = maxOf(vw / videoW, vh / videoH)
        texture.setTransform(Matrix().apply { setScale(videoW * s / vw, videoH * s / vh, vw / 2, vh / 2) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCrop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = start(st)
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyCrop()
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
