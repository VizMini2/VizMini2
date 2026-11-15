// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.home

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.juancams.rviz2mini.AppInfo
import com.juancams.rviz2mini.MainActivity
import com.juancams.rviz2mini.R
import com.juancams.rviz2mini.profiles.ProfileStore
import com.juancams.rviz2mini.ros.RosConnection
import com.juancams.rviz2mini.ros.RosGraph
import com.juancams.rviz2mini.tabs.TabStore

/**
 * Fixed home tab: greeting, connection status, ROS graph summary (topics,
 * services, actions, participants), workspace, release notes and the account
 * (optional: it only serves to have the cloud profiles on every phone).
 */
class HomeFragment : Fragment() {

    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var stats: LinearLayout
    private lateinit var workspaceText: TextView
    private lateinit var graphHint: TextView
    private var graph: RosGraph? = null
    private val main = Handler(Looper.getMainLooper())

    private val statusListener: (RosConnection.Status) -> Unit = { showStatus(it) }
    /** Linking, signing out or deleting the account refreshes the account card. */
    private val authListener = FirebaseAuth.AuthStateListener { if (view != null) showAccount() }

    /** The graph changes on its own: it is refreshed while the tab is visible. */
    private val graphTick = object : Runnable {
        override fun run() {
            refreshGraph()
            main.postDelayed(this, GRAPH_REFRESH_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(root: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val version = AppInfo.versionLabel(ctx)
        AppInfo.showCredit(root.findViewById(R.id.homeFooter), getString(R.string.home_footer, version))

        statusText = root.findViewById(R.id.homeStatus)
        statusDetail = root.findViewById(R.id.homeStatusDetail)
        stats = root.findViewById(R.id.homeStats)
        workspaceText = root.findViewById(R.id.homeWorkspace)
        graphHint = root.findViewById(R.id.homeGraphHint)

        root.findViewById<Button>(R.id.homeReconnect).setOnClickListener { RosConnection.restart() }
        root.findViewById<Button>(R.id.homeOpenConfig).setOnClickListener { (activity as? MainActivity)?.openConfig() }
        root.findViewById<View>(R.id.homeGraphRefresh).setOnClickListener { refreshGraph() }
        root.findViewById<Button>(R.id.homeNodeGraph).setOnClickListener { com.juancams.rviz2mini.graph.GraphActivity.open(ctx) }
        root.findViewById<Button>(R.id.homeTfTree).setOnClickListener { com.juancams.rviz2mini.graph.GraphActivity.open(ctx, tf = true) }
        root.findViewById<Button>(R.id.homeNewTab).setOnClickListener { (activity as? MainActivity)?.showNewTabDialog() }
        root.findViewById<Button>(R.id.homeDemo).setOnClickListener { confirmDemo() }
        root.findViewById<Button>(R.id.homeDemoExit).setOnClickListener {
            (activity as? MainActivity)?.let { a -> com.juancams.rviz2mini.demo.DemoMode.exit(a) { tabs -> a.replaceTabs(tabs) } }
            showDemo()
        }
        com.juancams.rviz2mini.demo.DemoMode.addListener(demoListener)
        root.findViewById<View>(R.id.homeChangelog).setOnClickListener { startActivity(Intent(ctx, ChangelogActivity::class.java)) }
        root.findViewById<TextView>(R.id.homeChangelogText).text = getString(R.string.home_changelog_text, version)
        root.findViewById<View>(R.id.homeTour).setOnClickListener { (activity as? MainActivity)?.showTour() }
        root.findViewById<View>(R.id.homePrivacy).setOnClickListener { com.juancams.rviz2mini.PrivacyActivity.open(ctx) }
        root.findViewById<View>(R.id.homeDocs).setOnClickListener { openUrl(docsUrl()) }
        root.findViewById<View>(R.id.homeContact).setOnClickListener { contact() }
        // Donations: removed in the Google Play build (res/values/config.xml).
        // Next to the history: in the APK, "Support the project" (GitHub Sponsors); in the Google
        // Play build, the GitHub profile, because Play does not allow asking for donations with an external payment.
        val card = root.findViewById<View>(R.id.homeGithubCard)
        if (resources.getBoolean(R.bool.play_store)) {
            card.setOnClickListener { openUrl(getString(R.string.author_github_url)) }
        } else {
            root.findViewById<android.widget.ImageView>(R.id.homeGithubIcon).apply {
                setImageResource(R.drawable.ic_favorite)
                imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.status_error))
            }
            root.findViewById<TextView>(R.id.homeGithubTitle).setText(R.string.home_support_title)
            root.findViewById<TextView>(R.id.homeGithubText).setText(R.string.home_support_text)
            card.setOnClickListener { openUrl(getString(R.string.donate_url)) }
        }
        root.findViewById<Button>(R.id.homeLogout).setOnClickListener { confirmLogout() }
        root.findViewById<Button>(R.id.homeDeleteAccount).setOnClickListener { confirmDeleteAccount() }
        FirebaseAuth.getInstance().addAuthStateListener(authListener)

        showStats(null)
        RosConnection.addStatusListener(statusListener)
    }

    override fun onResume() {
        super.onResume()
        showWorkspace()
        showDemo()
        main.post(graphTick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(graphTick)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        RosConnection.removeStatusListener(statusListener)
        main.removeCallbacks(graphTick)
        com.juancams.rviz2mini.demo.DemoMode.removeListener(demoListener)
        FirebaseAuth.getInstance().removeAuthStateListener(authListener)
    }

    // ---------------------------------------------------------------- demo mode

    private val demoListener: (Boolean) -> Unit = { if (view != null) { showDemo(); showWorkspace() } }

    private fun showDemo() {
        val v = view ?: return
        val active = com.juancams.rviz2mini.demo.DemoMode.isActive(requireContext())
        v.findViewById<View>(R.id.homeDemoBanner).visibility = if (active) View.VISIBLE else View.GONE
        v.findViewById<Button>(R.id.homeDemo).isEnabled = !active
    }

    private fun confirmDemo() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_demo_confirm_title)
            .setMessage(R.string.home_demo_confirm_message)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.home_start_demo) { _, _ ->
                (activity as? MainActivity)?.startDemo()
                showDemo()
            }
            .show()
    }

    // ---------------------------------------------------------------- connection

    private fun showStatus(s: RosConnection.Status) {
        if (view == null) return
        val ctx = requireContext()
        val (text, color) = when (s) {
            is RosConnection.Status.Connected -> ctx.getString(R.string.common_status_connected) to ctx.getColor(R.color.status_ok)
            RosConnection.Status.Connecting -> ctx.getString(R.string.common_status_connecting) to ctx.getColor(R.color.status_warn)
            RosConnection.Status.Disconnected -> ctx.getString(R.string.common_status_disconnected) to ctx.getColor(R.color.status_idle)
            is RosConnection.Status.Error -> ctx.getString(R.string.home_status_error) to ctx.getColor(R.color.status_error)
        }
        statusText.text = text
        statusText.setTextColor(color)
        val c = RosConnection.config
        statusDetail.text = when (s) {
            is RosConnection.Status.Error -> s.message
            else -> if (c.usesBridge) ctx.getString(R.string.home_bridge_detail, c.bridgeUrl) else ctx.getString(R.string.common_domain_node, c.domainId, c.nodeName) +
                (c.whitelistEntries().takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.home_via_suffix, it.joinToString()) } ?: "") +
                (c.peerEntries().takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.common_peers_suffix, it.joinToString()) } ?: "")
        }
        if (s is RosConnection.Status.Connected) {
            // DDS takes a while to learn about the rest of the network after connecting.
            RosConnection.discover { if (view != null) showStats(it) }
        } else {
            showStats(null)
        }
    }

    // ---------------------------------------------------------------- graph

    private fun refreshGraph() {
        if (view == null) return
        showStats(RosConnection.graph())
    }

    private fun showStats(g: RosGraph?) {
        graph = g
        // Only our own endpoints are visible: usually the Wi-Fi is filtering multicast.
        val alone = g != null && !g.hasOthers()
        // In the demo the simulated robot shares the app's node: "only this device" is expected.
        graphHint.text = if (com.juancams.rviz2mini.demo.DemoMode.isActive(requireContext())) getString(R.string.home_graph_demo)
        else if (alone && RosConnection.status is RosConnection.Status.Connected)
            getString(R.string.home_graph_alone)
        else getString(R.string.home_graph_hint)
        stats.removeAllViews()
        // The key decides which list opens; the visible text comes from the resources.
        val entries = listOf(
            Triple(STAT_TOPICS, R.string.home_stat_topics, g?.topics()?.size),
            Triple(STAT_SERVICES, R.string.home_stat_services, g?.services()?.size),
            Triple(STAT_ACTIONS, R.string.home_stat_actions, g?.actions()?.size),
            Triple(STAT_NODES, R.string.home_stat_nodes, g?.nodes()?.size),
            Triple(STAT_LIFECYCLE, R.string.home_stat_lifecycle, g?.lifecycleNodes()?.size),
        )
        for ((key, labelRes, count) in entries) {
            val label = getString(labelRes)
            val cell = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                background = with(TypedValueHolder) { selectableBackground(requireContext()) }
                isClickable = count != null
                setOnClickListener { showList(key, label) }
            }
            cell.addView(TextView(requireContext()).apply {
                text = count?.toString() ?: "–"
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            })
            cell.addView(TextView(requireContext()).apply {
                text = label
                textSize = 12f
                maxLines = 1
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            stats.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** List of what is on the network, with its type (like `ros2 topic list -t`). */
    private fun showList(kind: String, label: String) {
        val g = graph ?: return
        val rows: List<Pair<String, String>> = when (kind) {
            STAT_TOPICS -> g.topics().map { it.name to getString(R.string.home_topic_detail, it.types.joinToString(), it.publishers, it.subscribers) }
            STAT_SERVICES -> g.services().map { it.name to it.type + if (it.servers == 0) getString(R.string.home_service_no_server) else "" }
            STAT_ACTIONS -> g.actions().map { it.name to it.type }
            STAT_NODES -> g.nodes().map { n ->
                n.name to listOfNotNull(
                    getString(R.string.home_lifecycle_tag).takeIf { n.isLifecycle },
                    getString(R.string.home_node_detail, n.publishers.size, n.subscribers.size, n.services.size)
                ).joinToString(" · ")
            }
            else -> g.lifecycleNodes().map { it to getString(R.string.home_lifecycle_node) }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.home_list_title, label, rows.size))
        if (rows.isEmpty()) {
            dialog.setMessage(getString(R.string.home_nothing_found, RosConnection.config.domainId))
        } else {
            val items = rows.map { (name, detail) ->
                android.text.SpannableStringBuilder(name).apply {
                    append("\n")
                    val start = length
                    append(detail)
                    setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(MaterialColors.getColor(stats, com.google.android.material.R.attr.colorOnSurfaceVariant)),
                        start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }.toTypedArray<CharSequence>()
            // Nodes can be opened in the inspector (parameters, topics, services).
            dialog.setItems(items) { _, i ->
                when (kind) {
                    STAT_NODES, STAT_LIFECYCLE -> com.juancams.rviz2mini.params.NodeInspectorActivity.open(requireContext(), rows[i].first)
                }
            }
        }
        dialog.setPositiveButton(R.string.common_close, null).show()
    }

    // ---------------------------------------------------------------- workspace

    private fun showWorkspace() {
        if (view == null) return
        val ctx = requireContext()
        val tabs = TabStore.get().all.filter { !it.isWidget }
        val profiles = ProfileStore.get()
        val byType = tabs.groupingBy { it.type.label() }.eachCount().entries.joinToString { (t, n) -> if (n == 1) t else getString(R.string.home_type_count, t, n) }
        workspaceText.text = buildString {
            append(if (tabs.isEmpty()) getString(R.string.home_no_tabs) else resources.getQuantityString(R.plurals.home_tabs_summary, tabs.size, tabs.size, byType))
            append(getString(R.string.home_workspace_profile, profiles.activeName ?: getString(R.string.home_profile_none), profiles.all.size))
        }
    }

    // ---------------------------------------------------------------- account

    /** The documentation in the app's language (the Spanish one lives under es/). */
    private fun docsUrl(): String {
        val base = getString(R.string.docs_url)
        return if (resources.configuration.locales[0].language == "es") base + "es/" else base
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    /** Contact email, with what a bug report needs (version, Android, phone). */
    private fun contact() {
        val ctx = requireContext()
        val email = getString(R.string.contact_email)
        val body = getString(R.string.home_contact_body,
            AppInfo.versionName(ctx), android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT,
            android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.home_contact_subject, AppInfo.versionName(ctx)))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            // No email app: at least show the address.
            android.widget.Toast.makeText(ctx, getString(R.string.home_contact_no_app, email), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Delete the account: explains what gets deleted and asks for the password (Firebase requires a recent sign-in). */
    private fun confirmDeleteAccount() {
        val ctx = requireContext()
        val user = FirebaseAuth.getInstance().currentUser ?: return
        // No password (Google, Microsoft or GitHub accounts): confirmed by signing in again with the provider.
        if (!com.juancams.rviz2mini.account.SocialSignIn.hasPassword(user)) {
            val provider = com.juancams.rviz2mini.account.SocialSignIn.providersOf(user).firstOrNull() ?: return
            val message = getString(R.string.account_delete_message) +
                "\n\n" + getString(R.string.account_delete_prompt_provider, providerName(provider))
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.account_delete_title)
                .setMessage(message)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.account_delete_confirm) { _, _ ->
                    runDeletion(com.juancams.rviz2mini.account.AccountDeletion.deleteAfter(
                        com.juancams.rviz2mini.account.SocialSignIn.reauthenticate(requireActivity(), user, provider)))
                }
                .show()
            return
        }
        val box = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val layout = box.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dialogInputLayout)
        val input = box.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.dialogInput)
        layout.hint = getString(R.string.common_password)
        layout.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val message = getString(R.string.account_delete_message) +
            "\n\n" + getString(R.string.account_delete_prompt)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.account_delete_title)
            .setMessage(message)
            .setView(box)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.account_delete_confirm) { _, _ -> deleteAccount(input.text.toString()) }
            .show()
    }

    private fun deleteAccount(password: String) {
        if (password.isEmpty()) { android.widget.Toast.makeText(requireContext(), R.string.login_enter_password, android.widget.Toast.LENGTH_SHORT).show(); return }
        runDeletion(com.juancams.rviz2mini.account.AccountDeletion.delete(password))
    }

    private fun providerName(p: com.juancams.rviz2mini.account.SocialSignIn.Provider) = getString(when (p) {
        com.juancams.rviz2mini.account.SocialSignIn.Provider.GOOGLE -> R.string.provider_google
        com.juancams.rviz2mini.account.SocialSignIn.Provider.MICROSOFT -> R.string.provider_microsoft
        com.juancams.rviz2mini.account.SocialSignIn.Provider.GITHUB -> R.string.provider_github
    })

    /**
     * Account card. No account: Google, Microsoft and GitHub with "Link" (signs in or creates
     * the account). With an account: the email and its sign-in methods, each with "Link" or "Unlink"
     * (so nobody ends up with one account per provider), plus sign out and delete account.
     */
    private fun showAccount() {
        val root = view ?: return
        val user = FirebaseAuth.getInstance().currentUser
        root.findViewById<TextView>(R.id.homeAccountLabel).setText(if (user == null) R.string.home_account_optional else R.string.home_signed_in_as)
        root.findViewById<TextView>(R.id.homeAccount).text = user?.email ?: getString(R.string.home_account_why)
        root.findViewById<TextView>(R.id.homeLinkedTitle).visibility = if (user == null) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.homeAccountActions).visibility = if (user == null) View.GONE else View.VISIBLE
        val box = root.findViewById<LinearLayout>(R.id.homeLinked)
        box.removeAllViews()
        val linked = user?.let { com.juancams.rviz2mini.account.SocialSignIn.providersOf(it) }.orEmpty()
        val hasPassword = user?.let { com.juancams.rviz2mini.account.SocialSignIn.hasPassword(it) } ?: false
        val methods = linked.size + (if (hasPassword) 1 else 0)
        val iconColor = MaterialColors.getColor(box, com.google.android.material.R.attr.colorOnSurface)

        fun row(icon: Int, tintIcon: Boolean, name: String, action: String?, onAction: (() -> Unit)?) {
            val r = layoutInflater.inflate(R.layout.item_linked_method, box, false)
            r.findViewById<android.widget.ImageView>(R.id.linkedIcon).apply {
                setImageResource(icon)
                imageTintList = if (tintIcon) android.content.res.ColorStateList.valueOf(iconColor) else null
            }
            r.findViewById<TextView>(R.id.linkedName).text = name
            r.findViewById<Button>(R.id.linkedAction).apply {
                visibility = if (action == null) View.GONE else View.VISIBLE
                text = action
                setOnClickListener { onAction?.invoke() }
            }
            box.addView(r)
        }

        if (hasPassword) row(R.drawable.ic_mail, true, getString(R.string.linked_password), null, null)
        for (p in com.juancams.rviz2mini.account.SocialSignIn.Provider.entries) {
            val icon = when (p) {
                com.juancams.rviz2mini.account.SocialSignIn.Provider.GOOGLE -> R.drawable.ic_brand_google
                com.juancams.rviz2mini.account.SocialSignIn.Provider.MICROSOFT -> R.drawable.ic_brand_microsoft
                com.juancams.rviz2mini.account.SocialSignIn.Provider.GITHUB -> R.drawable.ic_brand_github
            }
            val tint = p == com.juancams.rviz2mini.account.SocialSignIn.Provider.GITHUB  // The logo is white.
            when {
                user == null -> row(icon, tint, providerName(p), getString(R.string.linked_link)) { signIn(p) }
                p !in linked -> row(icon, tint, providerName(p), getString(R.string.linked_link)) { link(p) }
                // The only sign-in method cannot be removed: the user would be locked out of the account.
                methods <= 1 -> row(icon, tint, providerName(p), null, null)
                else -> row(icon, tint, providerName(p), getString(R.string.linked_unlink)) { confirmUnlink(p) }
            }
        }
    }

    /** No account: sign in (or create the account) with the provider and sync the profiles. */
    private fun signIn(p: com.juancams.rviz2mini.account.SocialSignIn.Provider) {
        val a = requireActivity()
        // Without tying the notice to the activity: Microsoft and GitHub open the browser on top and it would be lost.
        com.juancams.rviz2mini.account.SocialSignIn.signIn(a, p).addOnCompleteListener { t ->
            if (t.isSuccessful) {
                com.juancams.rviz2mini.profiles.CloudProfiles.sync(a)
                FirebaseAuth.getInstance().currentUser?.uid?.let { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setUserId(it) }
                if (isAdded) android.widget.Toast.makeText(a, getString(R.string.account_linked, providerName(p)), android.widget.Toast.LENGTH_LONG).show()
                return@addOnCompleteListener
            }
            if (!isAdded) return@addOnCompleteListener
            val e = t.exception
            android.util.Log.w("VizMini2", "No se pudo entrar con ${p.id}", e)
            val text = when (e) {
                is com.juancams.rviz2mini.account.SocialSignIn.Cancelled -> return@addOnCompleteListener
                // There is already an account with that email created with another provider.
                is com.google.firebase.auth.FirebaseAuthUserCollisionException -> getString(R.string.social_account_exists_message)
                is androidx.credentials.exceptions.NoCredentialException -> getString(R.string.social_no_google_account)
                is com.google.firebase.FirebaseNetworkException -> getString(R.string.common_no_connection)
                else -> getString(R.string.social_failed, e?.message ?: "")
            }
            MaterialAlertDialogBuilder(a).setTitle(R.string.linked_title).setMessage(text).setPositiveButton(R.string.common_ok, null).show()
        }
    }

    private fun link(p: com.juancams.rviz2mini.account.SocialSignIn.Provider) {
        val a = requireActivity()
        val user = FirebaseAuth.getInstance().currentUser ?: return
        // Without tying the notice to the activity: Microsoft and GitHub open the browser on top and it would be lost.
        com.juancams.rviz2mini.account.SocialSignIn.link(a, user, p).addOnCompleteListener { t ->
            if (!isAdded) return@addOnCompleteListener
            if (t.isSuccessful) {
                android.widget.Toast.makeText(a, getString(R.string.linked_done, providerName(p)), android.widget.Toast.LENGTH_SHORT).show()
                showAccount()
                return@addOnCompleteListener
            }
            val e = t.exception
            android.util.Log.w("VizMini2", "No se pudo vincular ${p.id}", e)
            val text = when (e) {
                is com.juancams.rviz2mini.account.SocialSignIn.Cancelled -> return@addOnCompleteListener
                // That provider account is already a different VizMini2 account.
                is com.google.firebase.auth.FirebaseAuthUserCollisionException -> getString(R.string.linked_in_use, providerName(p))
                is androidx.credentials.exceptions.NoCredentialException -> getString(R.string.social_no_google_account)
                is com.google.firebase.FirebaseNetworkException -> getString(R.string.common_no_connection)
                else -> getString(R.string.social_failed, e?.message ?: "")
            }
            MaterialAlertDialogBuilder(a).setTitle(R.string.linked_title).setMessage(text).setPositiveButton(R.string.common_ok, null).show()
        }
    }

    private fun confirmUnlink(p: com.juancams.rviz2mini.account.SocialSignIn.Provider) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.linked_unlink_title, providerName(p)))
            .setMessage(getString(R.string.linked_unlink_message, providerName(p)))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.linked_unlink) { _, _ ->
                val a = requireActivity()
                val user = FirebaseAuth.getInstance().currentUser ?: return@setPositiveButton
                com.juancams.rviz2mini.account.SocialSignIn.unlink(user, p).addOnCompleteListener { t ->
                    if (!isAdded) return@addOnCompleteListener
                    if (t.isSuccessful) showAccount()
                    else android.widget.Toast.makeText(a, getString(R.string.social_failed, t.exception?.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun runDeletion(deletion: com.google.android.gms.tasks.Task<Void>) {
        val a = requireActivity()
        val deleteButton = view?.findViewById<Button>(R.id.homeDeleteAccount)
        deleteButton?.isEnabled = false
        // Without tying the notice to the activity: with Microsoft or GitHub the browser opens on top and that notice would be lost.
        deletion.addOnCompleteListener { t ->
            if (t.isSuccessful) {
                // Stay in the app, without an account: the phone's profiles stay on the phone.
                FirebaseAuth.getInstance().signOut()
                deleteButton?.isEnabled = true
                android.widget.Toast.makeText(a, R.string.account_deleted, android.widget.Toast.LENGTH_LONG).show()
                return@addOnCompleteListener
            }
            deleteButton?.isEnabled = true
            val e = t.exception
            if (e is com.juancams.rviz2mini.account.SocialSignIn.Cancelled || a.isDestroyed) return@addOnCompleteListener
            val text = when (e) {
                is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> a.getString(R.string.account_wrong_password)
                is com.google.firebase.FirebaseNetworkException -> a.getString(R.string.common_no_connection)
                else -> a.getString(R.string.account_delete_failed, e?.message)
            }
            MaterialAlertDialogBuilder(a).setTitle(R.string.account_delete_title).setMessage(text).setPositiveButton(R.string.common_ok, null).show()
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_sign_out_title)
            .setMessage(R.string.home_sign_out_message)
            .setNegativeButton(R.string.common_cancel, null)
            // Stay in the app, without an account: the phone's profiles stay, but they are no longer synced.
            .setPositiveButton(R.string.common_sign_out) { _, _ -> FirebaseAuth.getInstance().signOut() }
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Ripple background from the theme for clickable cells. */
    private object TypedValueHolder {
        fun selectableBackground(ctx: android.content.Context): android.graphics.drawable.Drawable? {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            return androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
        }
    }

    companion object {
        private const val GRAPH_REFRESH_MS = 5000L


        // Internal keys of the graph cells (not shown).
        private const val STAT_TOPICS = "topics"
        private const val STAT_SERVICES = "services"
        private const val STAT_ACTIONS = "actions"
        private const val STAT_NODES = "nodes"
        private const val STAT_LIFECYCLE = "lifecycle"

    }
}
