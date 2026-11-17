// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import com.juancams.rviz2mini.ui.showComposeDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.juancams.rviz2mini.profiles.CloudProfiles
import com.juancams.rviz2mini.profiles.ProfileStore
import com.juancams.rviz2mini.profiles.RobotProfile
import com.juancams.rviz2mini.profiles.RobotProfiles
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.tabs.TabStore

/** Fixed configuration tab: DDS/ROS 2 parameters and robot profiles (the account is on Home). */
class ConfigFragment : Fragment() {

    private lateinit var statusText: TextView
    private lateinit var cloudStatus: TextView
    private val cloudListener: (CloudProfiles.Status) -> Unit = { s ->
        if (view != null) cloudStatus.text = when (s) {
            CloudProfiles.Status.Idle -> getString(R.string.cloud_not_synced)
            CloudProfiles.Status.Syncing -> getString(R.string.cloud_syncing)
            CloudProfiles.Status.SignedOut -> getString(R.string.cloud_signed_out)
            // Own text (not DateUtils): DateUtils uses the system language, not the app's.
            is CloudProfiles.Status.Synced -> getString(R.string.cloud_synced, ago(s.atMs))
            is CloudProfiles.Status.Failed -> getString(R.string.cloud_failed, s.message)
            is CloudProfiles.Status.Offline -> getString(if (s.pending) R.string.cloud_offline_pending else R.string.cloud_offline)
        }
    }
    private lateinit var statusPill: TextView
    private lateinit var profileText: TextView
    private lateinit var domainInput: TextInputEditText
    private lateinit var nodeInput: TextInputEditText
    private lateinit var whitelistInput: TextInputEditText
    private lateinit var serviceFormatInput: com.google.android.material.textfield.MaterialAutoCompleteTextView
    /** Service format chosen in the dropdown (applied with "Apply and reconnect"). */
    private var serviceFormat = com.juancams.rviz2mini.ros.ServiceFormat.AUTO
    private lateinit var peersInput: TextInputEditText
    private lateinit var bridgeInput: TextInputEditText
    private lateinit var zenohInput: TextInputEditText
    private lateinit var zenohLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var serversInput: TextInputEditText
    private val profiles: ProfileStore by lazy { ProfileStore.get() }
    /** Profile pending export while the user picks where to save it. */
    private var exporting: RobotProfile? = null

    private val createProfileFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = exporting ?: return@registerForActivityResult
        exporting = null
        if (uri != null) writeProfile(uri, p)
    }
    private lateinit var keepSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private val requestNotifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        // The service works with or without the permission; without it the notification is not shown in the shade.
        setKeepConnection(true)
    }

    /** Enabling alerts on Android 13+ requires asking for the notifications permission. */
    private val requestAlertNotifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(requireContext(), R.string.config_notifications_blocked, Toast.LENGTH_LONG).show()
    }

    private val openProfileFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readProfile(uri)
    }
    private val statusListener: (RosConnection.Status) -> Unit = { showStatus(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config, container, false)

    override fun onViewCreated(root: View, savedInstanceState: Bundle?) {
        statusText = root.findViewById(R.id.connectionStatus)
        statusPill = root.findViewById(R.id.connectionPill)
        root.findViewById<Button>(R.id.languageButton).apply {
            text = AppLanguage.currentLabel(requireContext())
            setOnClickListener { AppLanguage.pick(requireContext()) }
        }
        root.findViewById<Button>(R.id.themeButton).apply {
            text = AppTheme.label(requireContext(), AppTheme.current(requireContext()))
            setOnClickListener { AppTheme.pick(requireContext()) }
        }
        val domainLayout = root.findViewById<TextInputLayout>(R.id.domainIdLayout)
        // Find which domains have robots (the same finder as the PC) and connect with one tap.
        (domainLayout.parent?.parent as? android.view.ViewGroup)?.let { card ->
            val row = domainLayout.parent as View
            val button = com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.find_robots_button)
                setIconResource(R.drawable.ic_wifi_tethering)
                setOnClickListener {
                    showComposeDialog { dismiss ->
                        com.juancams.rviz2mini.ui.common.FindRobotsDialog(onDismiss = dismiss) { d ->
                            dismiss()
                            val c = RosConnection.config.copy(domainId = d)
                            RosConnection.applyConfig(c)
                            showRosConfig(c)
                        }
                    }
                }
            }
            card.addView(button, card.indexOfChild(row) + 1, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            // Topic variables (${ns}/scan): the same editor as the PC.
            val vars = com.google.android.material.button.MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.variables_title)
                setOnClickListener { showComposeDialog { dismiss -> com.juancams.rviz2mini.ui.common.VariablesDialog(dismiss) } }
            }
            card.addView(vars, card.indexOfChild(button) + 1, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        domainInput = root.findViewById(R.id.domainIdInput)
        val nodeLayout = root.findViewById<TextInputLayout>(R.id.nodeNameLayout)
        nodeInput = root.findViewById(R.id.nodeNameInput)
        whitelistInput = root.findViewById(R.id.whitelistInput)
        serviceFormatInput = root.findViewById(R.id.serviceFormatInput)
        val formats = com.juancams.rviz2mini.ros.ServiceFormat.entries
        serviceFormatInput.setSimpleItems(formats.map { serviceFormatLabel(it) }.toTypedArray())
        serviceFormatInput.setOnItemClickListener { _, _, position, _ -> serviceFormat = formats[position] }
        val peersLayout = root.findViewById<TextInputLayout>(R.id.peersLayout)
        peersInput = root.findViewById(R.id.peersInput)
        val serversLayout = root.findViewById<TextInputLayout>(R.id.serversLayout)
        serversInput = root.findViewById(R.id.serversInput)
        val bridgeLayout = root.findViewById<TextInputLayout>(R.id.bridgeLayout)
        bridgeInput = root.findViewById(R.id.bridgeInput)
        zenohInput = root.findViewById(R.id.zenohInput)
        zenohLayout = root.findViewById(R.id.zenohLayout)
        showRosConfig(RosConnection.config)

        root.findViewById<Button>(R.id.applyButton).setOnClickListener {
            val domainText = domainInput.text?.toString().orEmpty()
            val nodeText = nodeInput.text?.toString().orEmpty()
            domainLayout.error = RosConfig.validateDomainId(domainText)
            nodeLayout.error = RosConfig.validateNodeName(nodeText)
            peersLayout.error = RosConfig.validatePeers(peersInput.text?.toString().orEmpty())
            serversLayout.error = RosConfig.validateServers(serversInput.text?.toString().orEmpty())
            bridgeLayout.error = RosConfig.validateBridgeUrl(bridgeInput.text?.toString().orEmpty())
            val zenohText = zenohInput.text?.toString()?.trim().orEmpty()
            zenohLayout.error = RosConfig.validateZenohRouter(zenohText)
                ?: getString(R.string.config_zenoh_and_bridge).takeIf { zenohText.isNotEmpty() && !bridgeInput.text.isNullOrBlank() }
            if (domainLayout.error != null || nodeLayout.error != null || peersLayout.error != null || serversLayout.error != null ||
                bridgeLayout.error != null || zenohLayout.error != null) return@setOnClickListener

            val newConfig = RosConfig(
                domainId = domainText.trim().toInt(),
                nodeName = nodeText.trim(),
                interfaceWhitelist = whitelistInput.text?.toString()?.trim().orEmpty(),
                discoveryPeers = RosConfig.splitPeers(peersInput.text?.toString().orEmpty()).joinToString(", "),
                discoveryServers = RosConfig.splitPeers(serversInput.text?.toString().orEmpty()).joinToString(", "),
                bridgeUrl = bridgeInput.text?.toString()?.trim().orEmpty(),
                serviceFormat = serviceFormat,
                zenohRouter = zenohText
            )
            RosConnection.applyConfig(newConfig)
            Toast.makeText(requireContext(), getString(R.string.config_reconnecting, newConfig.domainId), Toast.LENGTH_SHORT).show()
        }


        keepSwitch = root.findViewById(R.id.keepConnectionSwitch)
        keepSwitch.isChecked = com.juancams.rviz2mini.ros.RosForegroundService.isEnabled(requireContext())
        keepSwitch.setOnCheckedChangeListener { _, checked ->
            val ctx = requireContext()
            if (checked && android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else setKeepConnection(checked)
        }
        // Disabled from the notification.
        com.juancams.rviz2mini.ros.RosForegroundService.onDisabled = { activity?.runOnUiThread { if (view != null) keepSwitch.isChecked = false } }

        setupAlerts(root)
        setupKnownRobots(root)

        profileText = root.findViewById(R.id.profileActive)
        cloudStatus = root.findViewById(R.id.cloudStatus)
        root.findViewById<Button>(R.id.cloudSync).setOnClickListener { syncCloud(manual = true) }
        CloudProfiles.addListener(cloudListener)
        syncCloud()
        showActiveProfile()
        root.findViewById<Button>(R.id.saveProfileButton).setOnClickListener { askSaveProfile() }
        root.findViewById<Button>(R.id.loadProfileButton).setOnClickListener { pickProfile(getString(R.string.config_load_profile)) { askLoadProfile(it) } }
        root.findViewById<Button>(R.id.exportProfileButton).setOnClickListener {
            pickProfile(getString(R.string.config_export_profile)) { p ->
                exporting = p
                createProfileFile.launch(p.name.replace(Regex("[^A-Za-z0-9_.-]+"), "_") + ".json")
            }
        }
        root.findViewById<Button>(R.id.qrShareButton).setOnClickListener { pickQrSource() }
        root.findViewById<Button>(R.id.qrScanButton).setOnClickListener { scanQr() }
        root.findViewById<Button>(R.id.importProfileButton).setOnClickListener { openProfileFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }

        RosConnection.addStatusListener(statusListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        RosConnection.removeStatusListener(statusListener)
        CloudProfiles.removeListener(cloudListener)
        com.juancams.rviz2mini.ros.RosForegroundService.onDisabled = null
    }

    internal fun setKeepConnection(enabled: Boolean) {
        val ctx = requireContext()
        com.juancams.rviz2mini.ros.RosForegroundService.setEnabled(ctx, enabled)
        if (enabled) com.juancams.rviz2mini.ros.RosForegroundService.startIfEnabled(ctx)
        else com.juancams.rviz2mini.ros.RosForegroundService.stop(ctx)
        if (keepSwitch.isChecked != enabled) keepSwitch.isChecked = enabled
    }

    private fun showRosConfig(c: RosConfig) {
        domainInput.setText(c.domainId.toString())
        nodeInput.setText(c.nodeName)
        whitelistInput.setText(c.interfaceWhitelist)
        peersInput.setText(c.discoveryPeers)
        serversInput.setText(c.discoveryServers)
        bridgeInput.setText(c.bridgeUrl)
        zenohInput.setText(c.zenohRouter)
        serviceFormat = c.serviceFormat
        serviceFormatInput.setText(serviceFormatLabel(c.serviceFormat), false)
    }

    private fun serviceFormatLabel(f: com.juancams.rviz2mini.ros.ServiceFormat) = getString(when (f) {
        com.juancams.rviz2mini.ros.ServiceFormat.AUTO -> R.string.config_service_format_auto
        com.juancams.rviz2mini.ros.ServiceFormat.FAST_DDS -> R.string.config_service_format_fastdds
        com.juancams.rviz2mini.ros.ServiceFormat.CYCLONE -> R.string.config_service_format_cyclone
    })

    // ---------------------------------------------------------------- known robots and network

    private fun setupKnownRobots(root: View) {
        val ctx = requireContext()
        val sw = root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.knownRobotsSwitch)
        sw.isChecked = com.juancams.rviz2mini.ros.KnownRobots.enabled()
        sw.setOnCheckedChangeListener { _, c -> com.juancams.rviz2mini.ros.KnownRobots.setEnabled(c); showKnownRobots() }
        root.findViewById<TextView>(R.id.knownRobotsList).setOnClickListener { askForget() }
        root.findViewById<Button>(R.id.networkCheckButton).setOnClickListener { networkCheck() }
        showKnownRobots()
    }

    private fun showKnownRobots() {
        val v = view ?: return
        val ctx = requireContext()
        val domain = RosConnection.config.domainId
        val list = com.juancams.rviz2mini.ros.KnownRobots.list(domain)
        v.findViewById<TextView>(R.id.knownRobotsList).text = if (list.isEmpty()) getString(R.string.config_known_robots_none)
            else getString(R.string.config_known_robots_list, domain, list.joinToString { r -> r.ip + (r.name.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") })
    }

    private fun askForget() {
        val ctx = requireContext()
        val domain = RosConnection.config.domainId
        val list = com.juancams.rviz2mini.ros.KnownRobots.list(domain)
        if (list.isEmpty()) return
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.config_known_robots_forget_title)
            .setItems(list.map { it.ip + (it.name.takeIf { n -> n.isNotBlank() }?.let { n -> " · $n" } ?: "") }.toTypedArray()) { _, i ->
                com.juancams.rviz2mini.ros.KnownRobots.forget(domain, list[i].ip)
                showKnownRobots()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    /** What the phone sees on the network and what to do if it does not see the robot. */
    private fun networkCheck() {
        val ctx = requireContext()
        val domain = RosConnection.config.domainId
        val message = if (RosConnection.status !is RosConnection.Status.Connected) getString(R.string.config_network_not_connected) else {
            val others = RosConnection.remoteParticipants()
            val summary = if (others.isEmpty()) getString(R.string.config_network_alone, domain) else getString(R.string.config_network_ok, others.size, domain)
            val ips = com.juancams.rviz2mini.ros.KnownRobots.ownIps().filterNot { it.startsWith("127.") }.joinToString().ifEmpty { "–" }
            val visible = others.joinToString { (n, a) -> n.ifBlank { "?" } + " " + a.joinToString("/") }.ifEmpty { "–" }
            summary + "\n\n" + getString(R.string.config_network_details, ips, RosConnection.activePeers.joinToString().ifEmpty { "–" }, visible)
        }
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.config_network_check_title).setMessage(message)
            .setPositiveButton(R.string.common_ok, null)
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
        showKnownRobots()
    }

    // ---------------------------------------------------------------- alerts

    private fun setupAlerts(root: View) {
        val ctx = requireContext()
        val master = root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.alertsSwitch)
        val options = root.findViewById<View>(R.id.alertsOptions)
        val connection = root.findViewById<android.widget.CheckBox>(R.id.alertConnection)
        val diagnostics = root.findViewById<android.widget.CheckBox>(R.id.alertDiagnostics)
        val warnings = root.findViewById<android.widget.CheckBox>(R.id.alertDiagWarnings)
        val lifecycle = root.findViewById<android.widget.CheckBox>(R.id.alertLifecycle)
        val s = com.juancams.rviz2mini.alerts.AlertSettings.load(ctx)
        master.isChecked = s.enabled
        connection.isChecked = s.connection
        diagnostics.isChecked = s.diagnostics
        warnings.isChecked = s.diagnosticWarnings
        lifecycle.isChecked = s.lifecycle
        fun refresh() {
            options.visibility = if (master.isChecked) View.VISIBLE else View.GONE
            warnings.isEnabled = diagnostics.isChecked
        }
        fun save() {
            refresh()
            com.juancams.rviz2mini.alerts.AlertMonitor.apply(ctx, com.juancams.rviz2mini.alerts.AlertSettings(
                master.isChecked, connection.isChecked, diagnostics.isChecked, warnings.isChecked, lifecycle.isChecked))
        }
        refresh()
        master.setOnCheckedChangeListener { _, checked ->
            if (checked && !com.juancams.rviz2mini.alerts.AlertMonitor.canNotify(ctx) && android.os.Build.VERSION.SDK_INT >= 33) {
                requestAlertNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            save()
        }
        for (c in listOf(connection, diagnostics, warnings, lifecycle)) c.setOnCheckedChangeListener { _, _ -> save() }
        root.findViewById<Button>(R.id.alertTest).setOnClickListener {
            if (!com.juancams.rviz2mini.alerts.AlertMonitor.canNotify(ctx)) {
                Toast.makeText(ctx, R.string.config_allow_notifications, Toast.LENGTH_SHORT).show()
                if (android.os.Build.VERSION.SDK_INT >= 33) requestAlertNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return@setOnClickListener
            }
            com.juancams.rviz2mini.alerts.AlertMonitor.notify(com.juancams.rviz2mini.alerts.AlertRules.Alert(
                "test", ctx.getString(R.string.config_test_alert_title), ctx.getString(R.string.config_test_alert_text), com.juancams.rviz2mini.alerts.AlertRules.Severity.WARNING))
        }
    }

    // ---------------------------------------------------------------- robot profiles

    private fun showActiveProfile() {
        profileText.text = getString(R.string.config_profile_summary, profiles.activeName ?: getString(R.string.config_profile_none), profiles.all.size)
    }

    /** Current state (applied connection + tabs) as a profile. */
    private fun currentAsProfile(name: String) = RobotProfile(name, RosConnection.config, TabStore.get().all.toList())

    private fun askSaveProfile() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            setText(profiles.activeName.orEmpty()); hint = ctx.getString(R.string.config_profile_name_hint); setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(RobotProfiles.MAX_NAME_LENGTH))
        }
        val box = android.widget.FrameLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        MaterialAlertDialogBuilder(ctx).setTitle(R.string.config_save_profile).setView(box)
            .setMessage(ctx.getString(R.string.config_domain_tabs, RosConnection.config.domainId, tabsLabel(TabStore.get().all.size)))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val name = input.text.toString().trim()
                RobotProfiles.validateName(name)?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                saveProfile(currentAsProfile(name))
            }.show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    /** `manual`: the user asked for it with the button, so without internet they are notified. */
    private fun syncCloud(manual: Boolean = false) {
        val started = CloudProfiles.sync(requireContext()) { changed -> if (changed && view != null) showActiveProfile() }
        if (!started && manual) Toast.makeText(requireContext(), R.string.cloud_offline_sync, Toast.LENGTH_LONG).show()
    }

    /**
     * Uploads a profile change to the cloud. Without internet it stays saved on the phone and is uploaded when
     * the connection comes back: the user is told with that message instead of `doneText`.
     */
    private fun pushCloud(name: String, deleted: Boolean = false, doneText: String? = null) {
        val ctx = requireContext()
        if (CloudProfiles.push(ctx, name)) {
            doneText?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            return
        }
        val text = getString(if (deleted) R.string.cloud_offline_deleted else R.string.cloud_offline_saved)
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun saveProfile(p: RobotProfile) {
        profiles.save(p)
        profiles.activeName = p.name
        showActiveProfile()
        pushCloud(p.name, doneText = getString(R.string.config_profile_saved, p.name))
    }

    private fun pickProfile(title: String, onPicked: (RobotProfile) -> Unit) {
        val list = profiles.all
        if (list.isEmpty()) {
            Toast.makeText(requireContext(), R.string.config_no_profiles, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { getString(R.string.config_profile_item, it.name, it.ros.domainId, tabsLabel(it.tabs.size)) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext()).setTitle(title)
            .setItems(labels) { _, i -> onPicked(list[i]) }
            .setNegativeButton(R.string.common_cancel, null)
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    private fun askLoadProfile(p: RobotProfile) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(p.name)
            .setMessage(getString(R.string.config_load_message, p.ros.domainId, p.ros.nodeName,
                p.tabs.joinToString { it.name }.ifEmpty { getString(R.string.config_load_no_tabs) }))
            .setNegativeButton(R.string.common_cancel, null)
            .setNeutralButton(R.string.common_delete) { _, _ -> confirmDeleteProfile(p) }
            .setPositiveButton(R.string.common_load) { _, _ -> loadProfile(p) }
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    private fun confirmDeleteProfile(p: RobotProfile) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.common_delete_named, p.name))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ -> profiles.delete(p.name); pushCloud(p.name, deleted = true); showActiveProfile() }
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    internal fun loadProfile(p: RobotProfile) {
        if (p.ros != RosConnection.config) RosConnection.applyConfig(p.ros)
        showRosConfig(p.ros)
        (activity as? MainActivity)?.replaceTabs(p.tabsWithFreshIds())
        profiles.activeName = p.name
        showActiveProfile()
    }

    internal fun saveProfileForTest(name: String) = saveProfile(currentAsProfile(name))

    private fun writeProfile(uri: android.net.Uri, p: RobotProfile) {
        val ctx = requireContext().applicationContext
        val text = RobotProfiles.toJson(p).toString(2)
        Thread({
            val ok = try {
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
            } catch (e: Exception) { false }
            activity?.runOnUiThread { Toast.makeText(ctx, if (ok) R.string.config_profile_exported else R.string.config_write_failed, Toast.LENGTH_SHORT).show() }
        }, "profile-export").start()
    }

    private fun readProfile(uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        Thread({
            val result = try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error(ctx.getString(R.string.config_open_failed))
                Result.success(RobotProfiles.parseFile(text))
            } catch (t: Throwable) { Result.failure(t) }
            activity?.runOnUiThread {
                if (view == null) return@runOnUiThread
                result.fold({ p -> askImport(p) }, {
                    Toast.makeText(ctx, ctx.getString(R.string.config_import_failed, it.message), Toast.LENGTH_LONG).show()
                })
            }
        }, "profile-import").start()
    }

    /** Save (and load) a profile coming from a file or a QR. */
    private fun askImport(p: RobotProfile) {
        val exists = profiles.get(p.name) != null
        MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.config_import_title, p.name))
            .setMessage((if (exists) getString(R.string.config_import_replaces) else "") +
                getString(R.string.config_domain_tabs, p.ros.domainId, tabsLabel(p.tabs.size)))
            .setNegativeButton(R.string.common_cancel, null)
            .setNeutralButton(R.string.common_save) { _, _ -> profiles.save(p); pushCloud(p.name); showActiveProfile() }
            .setPositiveButton(R.string.config_save_and_load) { _, _ -> profiles.save(p); pushCloud(p.name); loadProfile(p) }
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    // ---------------------------------------------------------------- QR

    /** What to share: the current configuration or a saved profile. */
    private fun pickQrSource() {
        val list = profiles.all
        val labels = listOf(getString(R.string.config_qr_current)) + list.map { getString(R.string.config_qr_item, it.name, it.ros.domainId) }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.config_share_qr)
            .setItems(labels.toTypedArray()) { _, i ->
                showQr(if (i == 0) currentAsProfile(profiles.activeName ?: RosConnection.config.nodeName) else list[i - 1])
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    private fun showQr(p: RobotProfile) {
        val ctx = requireContext()
        val full = com.juancams.rviz2mini.profiles.QrProfiles.encode(p, connectionOnly = false)
        val conn = com.juancams.rviz2mini.profiles.QrProfiles.encode(p, connectionOnly = true)
        // If the full profile does not fit in one QR, it goes in several that cycle automatically (the scanning phone joins them).
        val fullParts = com.juancams.rviz2mini.profiles.QrProfiles.parts(full)
        val fullFits = true
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val toggle = com.google.android.material.button.MaterialButtonToggleGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        fun option(text: String) = com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); this.text = text; toggle.addView(this)
        }
        val bFull = option(ctx.getString(R.string.config_qr_full))
        val bConn = option(ctx.getString(R.string.config_qr_connection_only))
        box.addView(toggle)
        val image = android.widget.ImageView(ctx).apply { setBackgroundColor(Color.WHITE); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        box.addView(image, android.widget.LinearLayout.LayoutParams(dp(260), dp(260)).apply { topMargin = dp(14) })
        val caption = TextView(ctx).apply { textSize = 12f; gravity = android.view.Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        box.addView(caption)
        var part = 0
        var showingConn = false
        fun render(connectionOnly: Boolean) {
            showingConn = connectionOnly
            val text = if (connectionOnly) conn else fullParts[part % fullParts.size]
            image.setImageBitmap(qrBitmap(text, dp(236)))
            caption.text = if (!connectionOnly && fullParts.size > 1) ctx.getString(R.string.config_qr_part, part % fullParts.size + 1, fullParts.size) + "\n" +
                ctx.getString(R.string.config_qr_caption_full, p.name, p.ros.domainId, tabsLabel(p.tabs.size))
            else if (connectionOnly)
                ctx.getString(R.string.common_domain_node, p.ros.domainId, p.ros.nodeName) +
                    (p.ros.peerEntries().takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.config_qr_peers, it.joinToString()) } ?: "") +
                    ctx.getString(R.string.config_qr_scan_to_connect)
            else ctx.getString(R.string.config_qr_caption_full, p.name, p.ros.domainId, tabsLabel(p.tabs.size))
        }
        if (!fullFits) {
            bFull.isEnabled = false
            bFull.text = ctx.getString(R.string.config_qr_full_too_big)
        }
        toggle.addOnButtonCheckedListener { _, id, checked -> if (checked) render(id == bConn.id) }
        toggle.check(if (fullFits) bFull.id else bConn.id)
        render(!fullFits)
        val cycle = object : Runnable {
            override fun run() {
                if (!showingConn && fullParts.size > 1) { part++; render(false) }
                image.postDelayed(this, QR_PART_MS)
            }
        }
        if (fullParts.size > 1) image.postDelayed(cycle, QR_PART_MS)
        MaterialAlertDialogBuilder(ctx).setTitle(p.name).setView(box)
            .setPositiveButton(R.string.common_done, null)
            .setOnDismissListener { image.removeCallbacks(cycle) }
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    /** The QR codes of a profile split into several chunks, as they are scanned. */
    private val qrParts = com.juancams.rviz2mini.profiles.QrProfiles.Assembler()

    private fun qrBitmap(text: String, size: Int): android.graphics.Bitmap {
        val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 0)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun scanQr() {
        val ctx = requireContext()
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { code ->
                if (view == null) return@addOnSuccessListener
                val raw = code.rawValue.orEmpty()
                val decoded = try {
                    if (com.juancams.rviz2mini.profiles.QrProfiles.isPart(raw)) {
                        // A profile in several QRs: this chunk is saved and scanning resumes until all are in.
                        val whole = qrParts.add(raw)
                        if (whole == null) {
                            Toast.makeText(ctx, getString(R.string.config_qr_part_read, qrParts.count, qrParts.total), Toast.LENGTH_SHORT).show()
                            view?.postDelayed({ if (view != null) scanQr() }, 400)
                            return@addOnSuccessListener
                        }
                        qrParts.reset()
                        com.juancams.rviz2mini.profiles.QrProfiles.decode(whole)
                    } else com.juancams.rviz2mini.profiles.QrProfiles.decode(raw)
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                if (decoded.connectionOnly) askConnect(decoded.profile) else askImport(decoded.profile)
            }
            .addOnFailureListener { e ->
                Toast.makeText(ctx, ctx.getString(R.string.config_qr_scanner_failed, e.message), Toast.LENGTH_LONG).show()
            }
    }

    /** QR with only the connection: applied without touching the tabs. */
    private fun askConnect(p: RobotProfile) {
        val r = p.ros
        MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.config_connect_title, p.name))
            .setMessage(getString(R.string.common_domain_node, r.domainId, r.nodeName) +
                (r.peerEntries().takeIf { it.isNotEmpty() }?.let { getString(R.string.config_connect_peers, it.joinToString()) } ?: "") +
                (r.serverEntries().takeIf { it.isNotEmpty() }?.let { getString(R.string.config_connect_server, it.joinToString()) } ?: "") +
                getString(R.string.config_connect_tabs_stay))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.config_connect) { _, _ ->
                RosConnection.applyConfig(r)
                showRosConfig(r)
                Toast.makeText(requireContext(), getString(R.string.config_reconnecting, r.domainId), Toast.LENGTH_SHORT).show()
            }
            .show().also { com.juancams.rviz2mini.rviz.OpenDialogs.last = it }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val QR_PART_MS = 2000L }

    private fun ago(ms: Long): String {
        val min = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> getString(R.string.common_just_now)
            min < 60 -> getString(R.string.common_minutes_ago, min.toInt())
            else -> getString(R.string.common_hours_ago, (min / 60).toInt())
        }
    }

    private fun tabsLabel(n: Int) = resources.getQuantityString(R.plurals.common_tabs_count, n, n)

    private fun showStatus(status: RosConnection.Status) {
        if (view == null) return
        val ctx = requireContext()
        val (label, color) = ConnectionUi.label(ctx, status)
        ConnectionUi.stylePill(statusPill, label, color)
        val c = RosConnection.config
        statusText.text = when (status) {
            is RosConnection.Status.Error -> status.message
            is RosConnection.Status.Connected -> ctx.getString(R.string.common_domain_node, status.domainId, status.nodeName) +
                (c.peerEntries().takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.common_peers_suffix, it.joinToString()) } ?: "")
            else -> ctx.getString(R.string.common_domain_node, c.domainId, c.nodeName)
        }
    }
}
