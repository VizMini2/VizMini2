// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.juancams.rviz2mini.camera.CameraFragment
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.rviz.RvizFragment
import com.juancams.rviz2mini.tabs.TabFragment
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import com.juancams.rviz2mini.tabs.TabStore
import com.juancams.rviz2mini.tabs.TabType
import com.juancams.rviz2mini.teleop.TeleopFragment

/**
 * Tab container. At the bottom: Home and Config (fixed, cannot be deleted), the tabs created
 * by the user and the "+" button. At the top: the name of the current tab.
 *
 * Each tab is a Fragment created the first time it is opened and afterwards only
 * hidden/shown: the hidden one is capped at STARTED (it gets onPause and freezes) without losing
 * its state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabStore: TabStore
    private lateinit var tabLayout: TabLayout
    private lateinit var titleView: TextView
    private lateinit var titleIcon: android.widget.ImageView
    private lateinit var connectionBadge: TextView
    /** Connection status in the top bar; tapping it opens Config. */
    private val statusListener: (RosConnection.Status) -> Unit = { s ->
        val (label, color) = ConnectionUi.label(this, s)
        val text = if (s is RosConnection.Status.Connected) {
            when {
                s.bridge != null -> getString(R.string.main_bridge_badge)
                s.zenoh != null -> getString(R.string.main_zenoh_badge, s.domainId)
                else -> getString(R.string.main_domain_badge, s.domainId)
            }
        } else label
        ConnectionUi.stylePill(connectionBadge, text, color)
    }
    private var currentTabId: String? = null

    private val tabSelectedListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) { showTab(tab.tag as String) }
        override fun onTabUnselected(tab: TabLayout.Tab) {}
        override fun onTabReselected(tab: TabLayout.Tab) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Repackaged copy signed with another key: we do not continue.
        if (!com.juancams.rviz2mini.account.Integrity.enforce(this)) return
        alive++
        setContentView(R.layout.activity_main)

        RosConnection.start()
        // Compiles (or reads from disk) the 3D materials in the background before opening an RViz tab.
        com.juancams.rviz2mini.rviz.FilamentHub.prepare()
        com.juancams.rviz2mini.ros.RosForegroundService.startIfEnabled(this)
        com.juancams.rviz2mini.alerts.AlertMonitor.start(this)
        installShareFiles()
        com.juancams.rviz2mini.demo.DemoMode.resumeIfActive(this)
        com.juancams.rviz2mini.bag.BagSession.restoreIfStale()
        if (savedInstanceState == null && !com.juancams.rviz2mini.home.OnboardingActivity.seen(this) && !underInstrumentation()) showTour()
        // Profiles of the linked account (if any): when signing in on another phone they appear automatically.
        com.juancams.rviz2mini.profiles.CloudProfiles.sync(this)
        tabStore = TabStore.get()
        // Timeline of a live bag, between the content and the tab bar.
        findViewById<View>(R.id.tabContent).let { content ->
            val parent = content.parent as android.view.ViewGroup
            parent.addView(timeline.view, parent.indexOfChild(content) + 1, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        tabLayout = findViewById(R.id.bottomTabs)
        titleView = findViewById(R.id.tabTitle)
        titleIcon = findViewById(R.id.tabIcon)
        connectionBadge = findViewById(R.id.connectionBadge)
        connectionBadge.setOnClickListener { openConfig() }
        setupRecordButton()
        RosConnection.addStatusListener(statusListener)
        findViewById<ImageButton>(R.id.addTabButton).setOnClickListener { showNewTabDialog() }

        addTabView(HOME_TAB_ID, getString(R.string.main_tab_home), R.drawable.ic_home, longPress = false)
        addTabView(CONFIG_TAB_ID, getString(R.string.main_tab_config), R.drawable.ic_settings, longPress = false)
        // Crashlytics: which account and network each crash report belongs to.
        com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { setUserId(it) }
            setCustomKey("domain_id", RosConnection.config.domainId)
            setCustomKey("demo_mode", com.juancams.rviz2mini.demo.DemoMode.isActive(this@MainActivity))
        }
        for (spec in tabStore.all.filter { !it.isWidget }) addTabView(spec.id, spec.name, iconFor(spec.type), longPress = true)

        // When opening the app Home (welcome) is shown; when the activity is recreated, the one that was open.
        val initial = savedInstanceState?.getString(STATE_CURRENT_TAB)?.takeIf { isKnownTab(it) } ?: HOME_TAB_ID
        tabLayout.addOnTabSelectedListener(tabSelectedListener)
        selectTab(initial)
        showTab(initial) // in case it was already selected and the listener did not fire
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CURRENT_TAB, currentTabId)
    }

    /** Physical gamepads: axes and buttons go to the visible tab if it accepts them (teleop). */
    private fun visibleGamepadTarget(): com.juancams.rviz2mini.tabs.GamepadInput? =
        currentTabId?.let { supportFragmentManager.findFragmentByTag(it) } as? com.juancams.rviz2mini.tabs.GamepadInput

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK &&
            ev.action == android.view.MotionEvent.ACTION_MOVE && visibleGamepadTarget()?.onGamepadMotion(ev) == true
        ) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (android.view.KeyEvent.isGamepadButton(event.keyCode) && visibleGamepadTarget()?.onGamepadKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    private val timeline by lazy { com.juancams.rviz2mini.bag.BagTimeline(this) }

    override fun onResume() { super.onResume(); timeline.start() }
    override fun onPause() { super.onPause(); timeline.stop() }

    override fun onDestroy() {
        super.onDestroy()
        RosConnection.removeStatusListener(statusListener)
        com.juancams.rviz2mini.record.ScreenRecordService.removeListener(recordListener)
        alive--
        // When leaving the app (not on rotation) everything is closed, including the background service. If
        // another one is already open, the connection belongs to it and is not touched.
        if (isFinishing && alive == 0) {
            com.juancams.rviz2mini.ros.RosForegroundService.stop(this)
            RosConnection.stop()
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun isKnownTab(id: String) = id in FIXED_TABS || tabStore.get(id) != null

    private val tour = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == com.juancams.rviz2mini.home.OnboardingActivity.RESULT_START_DEMO) startDemo()
    }

    /** Quick tour of the app (also from Home). */
    fun showTour() = tour.launch(android.content.Intent(this, com.juancams.rviz2mini.home.OnboardingActivity::class.java))

    /** Enters demo mode and opens its RViz. */
    fun startDemo() {
        com.juancams.rviz2mini.demo.DemoMode.enter(this) { replaceTabs(it) }
        tabStore.all.firstOrNull()?.let { openTab(it.id) }
    }

    /** During instrumented tests the tour does not open by itself (it would cover the screens under test). */
    private fun underInstrumentation(): Boolean = try {
        Class.forName("androidx.test.platform.app.InstrumentationRegistry"); true
    } catch (_: ClassNotFoundException) { false }

    /** Opens a tab by id. */
    fun openTab(id: String) {
        selectTab(id)
        showTab(id)
    }

    /** Opens the Config tab (from Home). */
    fun openConfig() {
        selectTab(CONFIG_TAB_ID)
        showTab(CONFIG_TAB_ID)
    }

    private fun addTabView(id: String, name: String, icon: Int, longPress: Boolean, position: Int = tabLayout.tabCount) {
        val tab = tabLayout.newTab().setText(name).setIcon(icon).setTag(id)
        tabLayout.addTab(tab, position, false)
        if (longPress) {
            tab.view.setOnLongClickListener { v ->
                showTabMenu(v, id)
                true
            }
        }
    }

    private fun findTab(id: String): TabLayout.Tab? =
        (0 until tabLayout.tabCount).map { tabLayout.getTabAt(it)!! }.firstOrNull { it.tag == id }

    private fun selectTab(id: String) {
        findTab(id)?.let { if (!it.isSelected) it.select() }
    }

    private fun showTab(id: String) {
        if (id == currentTabId && supportFragmentManager.findFragmentByTag(id)?.isHidden == false) return
        val fm = supportFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)

        var target = fm.findFragmentByTag(id)
        if (target == null) {
            target = createFragment(id)
            tx.add(R.id.tabContent, target, id)
        }
        for (f in fm.fragments) {
            if (f === target || f.id != R.id.tabContent) continue
            tx.hide(f)
            tx.setMaxLifecycle(f, Lifecycle.State.STARTED) // frozen
        }
        tx.show(target)
        tx.setMaxLifecycle(target, Lifecycle.State.RESUMED)
        tx.commitNow()

        currentTabId = id
        titleView.text = when (id) {
            HOME_TAB_ID -> getString(R.string.main_tab_home)
            CONFIG_TAB_ID -> getString(R.string.main_title_config)
            else -> tabStore.get(id)?.name.orEmpty()
        }
        titleIcon.setImageResource(when (id) {
            HOME_TAB_ID -> R.drawable.ic_home
            CONFIG_TAB_ID -> R.drawable.ic_settings
            else -> tabStore.get(id)?.let { iconFor(it.type) } ?: R.drawable.ic_tab
        })
    }

    private fun createFragment(id: String): Fragment {
        if (id == HOME_TAB_ID) return com.juancams.rviz2mini.home.HomeFragment()
        if (id == CONFIG_TAB_ID) return ConfigFragment()
        val spec = tabStore.get(id) ?: return com.juancams.rviz2mini.home.HomeFragment()
        return com.juancams.rviz2mini.tabs.TabFragments.create(spec)
    }

    private fun iconFor(type: TabType): Int = com.juancams.rviz2mini.tabs.TabFragments.icon(type)

    // ---------------------------------------------------------------- create / rename / delete

    /** The "+": tab catalog on its own screen ([com.juancams.rviz2mini.tabs.AddTabActivity]). */
    fun showNewTabDialog() {
        addTab.launch(android.content.Intent(this, com.juancams.rviz2mini.tabs.AddTabActivity::class.java))
    }

    private val addTab = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val json = r.data?.getStringExtra(com.juancams.rviz2mini.tabs.AddTabActivity.RESULT_TAB) ?: return@registerForActivityResult
        addSpecs(TabList.fromJson(json).orEmpty())
    }

    /** Adds tabs with their widgets (a Foxglove layout with several tabs brings several dashboards), and opens the first. */
    private fun addSpecs(specs: List<TabSpec>) {
        val shown = specs.filter { !it.isWidget }
        if (shown.isEmpty()) return
        for (s in specs) tabStore.add(s)
        for (t in shown) addTabView(t.id, t.name, iconFor(t.type), longPress = true)
        openTab(shown.first().id)
    }

    private fun showTabMenu(anchor: View, id: String) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.common_rename)
            menu.add(0, MENU_DUPLICATE, 1, R.string.main_duplicate_tab)
            menu.add(0, MENU_SHARE, 2, R.string.main_share_tab)
            menu.add(0, MENU_DELETE, 3, R.string.common_delete)
            // Export for another tool (RViz, Foxglove, PlotJuggler), when the tab has something it understands.
            val spec = tabStore.get(id)
            if (spec != null) com.juancams.rviz2mini.ui.interop.ConfigInterop.exportFormats(spec, tabStore.all)
                .filter { it != com.juancams.rviz2mini.ui.interop.ConfigFormat.VIZMINI2 }
                .forEachIndexed { i, f -> menu.add(0, MENU_EXPORT + f.ordinal, 4 + i, com.juancams.rviz2mini.i18n.tr("interop_export_as", f.label)) }
            setOnMenuItemClickListener {
                if (it.itemId >= MENU_EXPORT) { exportTab(id, com.juancams.rviz2mini.ui.interop.ConfigFormat.entries[it.itemId - MENU_EXPORT]); return@setOnMenuItemClickListener true }
                when (it.itemId) {
                    MENU_RENAME -> showRenameDialog(id)
                    MENU_DUPLICATE -> duplicateTab(id)
                    MENU_SHARE -> shareTab(id)
                    MENU_DELETE -> confirmDelete(id)
                }
                true
            }
        }.show()
    }

    /** A copy of the tab (and of its widgets if it is a dashboard) at the end. */
    private fun duplicateTab(id: String) {
        val spec = tabStore.get(id) ?: return
        val all = tabStore.all
        addSpecs(com.juancams.rviz2mini.tabs.TabSharing.copies(listOf(spec) + all.filter { it.dashboardId == id }, all))
    }

    /** The system's screen capture permission, then the recording service. */
    private val screenCapture = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) com.juancams.rviz2mini.record.ScreenRecordService.start(this, r.resultCode, data)
    }
    private val recordListener: (Boolean) -> Unit = { on -> updateRecordButton(on) }

    private fun setupRecordButton() {
        val b = findViewById<android.widget.ImageButton>(R.id.recordButton)
        b.setOnClickListener {
            if (com.juancams.rviz2mini.record.ScreenRecordService.recording) com.juancams.rviz2mini.record.ScreenRecordService.stop(this)
            else runCatching { screenCapture.launch(com.juancams.rviz2mini.record.ScreenRecordService.captureIntent(this)) }
        }
        com.juancams.rviz2mini.record.ScreenRecordService.addListener(recordListener)
        updateRecordButton(com.juancams.rviz2mini.record.ScreenRecordService.recording)
    }

    private fun updateRecordButton(on: Boolean) {
        val b = findViewById<android.widget.ImageButton>(R.id.recordButton) ?: return
        b.setImageResource(if (on) R.drawable.ic_stop_circle else R.drawable.ic_videocam)
        b.imageTintList = android.content.res.ColorStateList.valueOf(if (on) getColor(R.color.status_error)
            else com.google.android.material.color.MaterialColors.getColor(b, com.google.android.material.R.attr.colorOnSurfaceVariant))
        b.contentDescription = getString(if (on) R.string.record_notification_stop else R.string.record_start)
    }

    /** Shared screens that hand over files (a map's PGM and YAML): to other apps with the share sheet. */
    private fun installShareFiles() {
        com.juancams.rviz2mini.ui.common.FileDialogs.saveFiles = { files, done ->
            val ok = runCatching {
                val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
                val uris = ArrayList(files.map { (name, bytes) ->
                    val f = java.io.File(dir, name.replace(Regex("[^A-Za-z0-9_.-]+"), "_"))
                    f.writeBytes(bytes)
                    androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
                })
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, files.first().first))
            }.isSuccess
            done(if (ok) files.first().first else null)
        }
    }

    /** Shares the tab as a file for another tool; what had no equivalent is said first. */
    private fun exportTab(id: String, f: com.juancams.rviz2mini.ui.interop.ConfigFormat) {
        val spec = tabStore.get(id) ?: return
        val r = try { com.juancams.rviz2mini.ui.interop.ConfigInterop.export(spec, tabStore.all, f) } catch (e: Exception) {
            android.widget.Toast.makeText(this, e.message ?: "?", android.widget.Toast.LENGTH_LONG).show(); return
        }
        val share = {
            val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, r.fileName).apply { writeText(r.text) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = if (f == com.juancams.rviz2mini.ui.interop.ConfigFormat.PLOTJUGGLER) "text/xml" else if (f == com.juancams.rviz2mini.ui.interop.ConfigFormat.RVIZ) "text/plain" else "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, r.fileName))
        }
        if (r.notes.isEmpty()) share()
        else com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(com.juancams.rviz2mini.i18n.tr("interop_export_as", f.label))
            .setMessage(com.juancams.rviz2mini.i18n.tr("interop_notes") + "\n\n" + r.notes.distinct().joinToString("\n") { "• $it" })
            .setPositiveButton(com.juancams.rviz2mini.i18n.tr("interop_share")) { _, _ -> share() }
            .setNegativeButton(R.string.common_cancel, null).show()
    }

    /** Shares the tab as a JSON file (imported from "+"). */
    private fun shareTab(id: String) {
        val spec = tabStore.get(id) ?: return
        val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
        val safe = spec.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "tab" }
        val f = java.io.File(dir, "$safe.vizmini2.json")
        f.writeText(com.juancams.rviz2mini.tabs.TabSharing.export(spec, tabStore.all))
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, spec.name))
    }

    private fun showRenameDialog(id: String) {
        val spec = tabStore.get(id) ?: return
        val input = EditText(this).apply {
            setText(spec.name)
            setSelection(spec.name.length)
            maxLines = 1
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_rename_tab)
            .setView(input)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val others = tabStore.all.filter { it.id != id }
                val name = TabList.resolveName(input.text.toString(), spec.type, others, spec.type.label())
                tabStore.update(spec.copy(name = name))
                findTab(id)?.text = name
                if (currentTabId == id) titleView.text = name
            }
            .show()
    }

    private fun confirmDelete(id: String) {
        val spec = tabStore.get(id) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.common_delete_named, spec.name))
            .setMessage(R.string.main_delete_tab_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ -> deleteTab(id) }
            .show()
    }

    private fun deleteTab(id: String) {
        val tab = findTab(id) ?: return
        val wasCurrent = currentTabId == id
        val position = tab.position

        // A dashboard takes its widgets with it.
        for (w in tabStore.all.filter { it.dashboardId == id }) tabStore.remove(w.id)
        tabStore.remove(id)
        supportFragmentManager.findFragmentByTag(id)?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }
        if (wasCurrent) currentTabId = null
        tabLayout.removeTab(tab)

        if (wasCurrent) {
            // We select the neighbor on the left (at least Home and Config remain).
            val neighbour = tabLayout.getTabAt((position - 1).coerceAtLeast(0))
            neighbour?.let {
                it.select()
                showTab(it.tag as String)
            }
        }
    }

    /**
     * Replaces all user tabs with [tabs] (robot profile). Called from the
     * Config tab, which stays visible.
     */
    fun replaceTabs(tabs: List<TabSpec>) {
        val fm = supportFragmentManager
        val old = tabStore.all.toList()
        val tx = fm.beginTransaction()
        for (spec in old) fm.findFragmentByTag(spec.id)?.let { tx.remove(it) }
        tx.commitNow()
        for (spec in old) findTab(spec.id)?.let { tabLayout.removeTab(it) }
        if (currentTabId !in FIXED_TABS) currentTabId = null
        tabStore.replaceAll(tabs)
        for (spec in tabs.filter { !it.isWidget }) addTabView(spec.id, spec.name, iconFor(spec.type), longPress = true)
        if (currentTabId == null) { selectTab(CONFIG_TAB_ID); showTab(CONFIG_TAB_ID) }
    }

    private companion object {
        const val HOME_TAB_ID = "home"
        const val CONFIG_TAB_ID = "config"
        val FIXED_TABS = setOf(HOME_TAB_ID, CONFIG_TAB_ID)
        const val STATE_CURRENT_TAB = "current_tab"
        const val MENU_RENAME = 1
        const val MENU_DELETE = 2
        const val MENU_DUPLICATE = 3
        const val MENU_SHARE = 4
        /** + the format's ordinal. */
        const val MENU_EXPORT = 20
        /** Live MainActivity instances: a new one may start before the old one is destroyed. */
        var alive = 0
    }
}
