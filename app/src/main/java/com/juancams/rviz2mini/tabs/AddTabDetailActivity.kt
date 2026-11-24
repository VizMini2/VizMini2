// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.tabs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.camera.CameraFragment
import com.juancams.rviz2mini.lifecycle.LifecycleFragment
import com.juancams.rviz2mini.lifecycle.LifecycleModel
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.rviz.display.DisplayConfig
import com.juancams.rviz2mini.rviz.display.DisplayList
import com.juancams.rviz2mini.rviz.display.DisplayType
import com.juancams.rviz2mini.ui.Screens

/**
 * Detail sheet of a tab type: looping example video, what it does, name and the settings that make sense
 * to give when creating it (camera topic, teleop cmd_vel...). "Create" returns the tab.
 */
class AddTabDetailActivity : AppCompatActivity() {

    private var clip: com.juancams.rviz2mini.ui.LoopingVideoView? = null

    override fun onPause() {
        super.onPause()
        clip?.pause()
    }

    override fun onResume() {
        super.onResume()
        clip?.resume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = TabType.fromName(intent.getStringExtra(EXTRA_TYPE).orEmpty()) ?: run { finish(); return }
        val entry = TabCatalog.of(type)
        val c = Screens.setup(this, type.label(), getString(entry.summary)).content

        // The tab working with the demo robot, looping (the screenshot while it loads).
        clip = com.juancams.rviz2mini.ui.LoopingVideoView(this).apply {
            setVideo(entry.video, entry.image)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setBackgroundResource(R.drawable.bg_thumb)
        }
        c.addView(clip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Screens.dp(this, 380)).apply { topMargin = Screens.dp(this@AddTabDetailActivity, 8) })

        val about = Screens.card(c)
        about.addView(Screens.title(this, getString(R.string.add_tab_what_it_does), entry.icon))
        for (d in entry.details) about.addView(TextView(this).apply {
            text = "•  " + getString(d)
            textSize = 14f
            setPadding(0, Screens.dp(this@AddTabDetailActivity, 6), 0, 0)
        })

        val setup = Screens.card(c)
        setup.addView(Screens.title(this, getString(R.string.add_tab_set_up), R.drawable.ic_settings))
        val nameLayout = input(setup, getString(R.string.common_name), getString(R.string.add_tab_name_helper, TabList.resolveName("", type, TabStore.get().all, type.label())))
        val nameInput = nameLayout.editText!!
        nameInput.filters = arrayOf(android.text.InputFilter.LengthFilter(TabList.MAX_NAME_LENGTH))

        // Settings specific to each type.
        val extras = ArrayList<(TabSpec) -> TabSpec>()
        when (type) {
            TabType.RVIZ -> {
                val frame = input(setup, getString(R.string.add_tab_fixed_frame), getString(R.string.add_tab_fixed_frame_helper)).editText!!
                val common = check(setup, getString(R.string.add_tab_common_displays), true)
                extras += { s ->
                    var t = s.withSetting(RvizFragment.KEY_FIXED_FRAME, frame.text.toString().trim().ifEmpty { null })
                    if (common.isChecked) t = t.withSetting(RvizFragment.KEY_DISPLAYS, DisplayList.toJson(DisplayList.defaults() + listOf(
                        DisplayConfig.create(DisplayType.MAP), DisplayConfig.create(DisplayType.ROBOT_MODEL),
                        DisplayConfig.create(DisplayType.LASER_SCAN), DisplayConfig.create(DisplayType.PATH, "Plan", "/plan"))))
                    t
                }
            }
            TabType.CAMERA -> {
                val topicLayout = input(setup, getString(R.string.add_tab_image_topic), getString(R.string.add_tab_image_topic_helper), autocomplete = true)
                val topic = topicLayout.editText as MaterialAutoCompleteTextView
                RosConnection.graph()?.topicsOfType("sensor_msgs/msg/Image", "sensor_msgs/msg/CompressedImage")?.let { found ->
                    topic.setSimpleItems(found.map { it.name }.toTypedArray())
                }
                extras += { s ->
                    val t = topic.text.toString().trim()
                    if (t.isEmpty()) s else {
                        val compressed = RosConnection.graph()?.topics(true)?.firstOrNull { it.name == t }?.type == "sensor_msgs/msg/CompressedImage" || t.endsWith("/compressed")
                        s.withSetting(CameraFragment.KEY_TOPIC, if (t.startsWith("/")) t else "/$t").withSetting(CameraFragment.KEY_KIND, if (compressed) "COMPRESSED" else "RAW")
                    }
                }
            }
            TabType.TELEOP, TabType.TELEOP_DUAL -> {
                val topic = input(setup, getString(R.string.add_tab_velocity_topic), getString(R.string.add_tab_velocity_topic_helper)).editText!!
                topic.setText("/cmd_vel")
                extras += { s -> s.withSetting("topic", topic.text.toString().trim().ifEmpty { "/cmd_vel" }.let { if (it.startsWith("/")) it else "/$it" }) }
            }
            TabType.DIAGNOSTICS -> {
                setup.addView(Screens.hint(this, getString(R.string.add_tab_start_with)))
                val group = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
                // Key saved in the tab -> button text.
                val modes = listOf("LOG" to R.string.add_tab_mode_log, "DIAG" to R.string.add_tab_mode_diagnostics, "ECHO" to R.string.add_tab_mode_echo, "PLOT" to R.string.add_tab_mode_plot)
                val ids = modes.map { (_, label) ->
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        id = android.view.View.generateViewId(); setText(label); minWidth = 0; setPadding(Screens.dp(this@AddTabDetailActivity, 8), 0, Screens.dp(this@AddTabDetailActivity, 8), 0)
                    }.also { group.addView(it) }.id
                }
                group.check(ids[0])
                setup.addView(group)
                extras += { s -> s.withSetting("mode", modes[ids.indexOf(group.checkedButtonId).coerceAtLeast(0)].first) }
            }
            TabType.LIFECYCLE -> {
                val found = RosConnection.graph()?.lifecycleNodes().orEmpty()
                val add = check(setup, if (found.isEmpty()) getString(R.string.add_tab_lifecycle_none) else getString(R.string.add_tab_lifecycle_found, found.size), found.isNotEmpty())
                add.isEnabled = found.isNotEmpty()
                extras += { s -> if (add.isChecked) s.withSetting(LifecycleFragment.KEY_NODES, LifecycleModel.formatNodes(found)) else s }
            }
            else -> setup.addView(Screens.hint(this, getString(R.string.add_tab_no_settings)))
        }

        val create = MaterialButton(this).apply {
            setText(R.string.common_create)
            setIconResource(R.drawable.ic_add)
            setOnClickListener {
                val name = TabList.resolveName(nameInput.text.toString(), type, TabStore.get().all, type.label())
                var spec = TabSpec.create(type, name)
                for (e in extras) spec = e(spec)
                setResult(Activity.RESULT_OK, Intent().putExtra(AddTabActivity.RESULT_TAB, TabList.toJson(listOf(spec))))
                finish()
            }
        }
        c.addView(create, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Screens.dp(this, 56)).apply { topMargin = Screens.dp(this@AddTabDetailActivity, 16) })
    }

    private fun input(parent: LinearLayout, hint: String, helper: String, autocomplete: Boolean = false): TextInputLayout {
        // With suggestions: Material dropdown style (same box as the others, plus an arrow).
        val style = if (autocomplete) com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle else com.google.android.material.R.attr.textInputStyle
        val layout = TextInputLayout(this, null, style).apply {
            this.hint = hint
            helperText = helper
            setBoxCornerRadii(dp14, dp14, dp14, dp14)
        }
        val edit = if (autocomplete) MaterialAutoCompleteTextView(layout.context).apply {
            threshold = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        } else TextInputEditText(layout.context)
        edit.isSingleLine = true
        layout.addView(edit)
        parent.addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Screens.dp(this@AddTabDetailActivity, 10) })
        return layout
    }

    private val dp14 get() = Screens.dp(this, 14).toFloat()

    private fun check(parent: LinearLayout, text: String, checked: Boolean) = MaterialCheckBox(this).apply {
        this.text = text
        isChecked = checked
        parent.addView(this)
    }

    companion object {
        const val EXTRA_TYPE = "type"
    }
}
