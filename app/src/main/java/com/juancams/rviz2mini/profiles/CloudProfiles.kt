// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.profiles

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.juancams.rviz2mini.NetworkState
import com.juancams.rviz2mini.R
import org.json.JSONObject
import com.juancams.rviz2mini.profiles.ProfileSync.Plan
import com.juancams.rviz2mini.profiles.ProfileSync.Record
import com.juancams.rviz2mini.profiles.ProfileSync.key
import com.juancams.rviz2mini.profiles.ProfileSync.plan
import com.juancams.rviz2mini.profiles.ProfileSync.parse

/**
 * Robot profiles in the cloud (Firestore, `users/{uid}/profiles/{id}`), to have them on any
 * phone with the same account. Each profile stores when it was modified and deletions remain as
 * "tombstones": when syncing the most recent change wins, whether from the phone or the cloud.
 */
object CloudProfiles {

    // ---------------------------------------------------------------- Firestore

    sealed class Status {
        data object Idle : Status()
        data object Syncing : Status()
        data class Synced(val count: Int, val atMs: Long) : Status()
        data object SignedOut : Status()
        data class Failed(val message: String) : Status()
        /** No internet. `pending`: there are phone changes that will be uploaded when the connection returns. */
        data class Offline(val pending: Boolean) : Status()
    }

    @Volatile var status: Status = Status.Idle
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Status) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun addListener(l: (Status) -> Unit) { listeners.add(l); l(status) }
    fun removeListener(l: (Status) -> Unit) { listeners.remove(l) }

    private fun setStatus(s: Status) {
        status = s
        main.post { for (l in listeners) l(s) }
    }

    private fun collection() = FirebaseAuth.getInstance().currentUser?.uid?.let {
        FirebaseFirestore.getInstance().collection("users").document(it).collection("profiles")
    }

    /**
     * Syncs in both directions. `onDone` on the main thread with the profiles that changed
     * on the phone. Returns false if it could not start because there is no internet (the status becomes
     * [Status.Offline] and the phone's changes wait until next time).
     */
    fun sync(context: Context, onDone: (changedLocally: Boolean) -> Unit = {}): Boolean {
        val col = collection() ?: run { setStatus(Status.SignedOut); return true }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { setStatus(Status.SignedOut); return true }
        val app = context.applicationContext
        if (status == Status.Syncing) return true
        if (!NetworkState.hasInternet(app)) {
            setStatus(Status.Offline(pending(app)))
            onDone(false)
            return false
        }
        setStatus(Status.Syncing)
        val store = ProfileStore.get()
        // Always from the server: the phone's copy is no good for comparing with the cloud.
        col.get(Source.SERVER).addOnSuccessListener { snap ->
            val remote = snap.documents.mapNotNull { d ->
                val name = d.getString("name") ?: return@mapNotNull null
                d.id to Record(name, d.getLong("updatedAt") ?: 0L, if (d.getBoolean("deleted") == true) null else d.getString("json"))
            }.toMap()
            val plan = plan(store.records(), remote, uid)
            var changed = false
            for (r in plan.toLocal) changed = store.applyRemote(r, uid) || changed
            val batch = FirebaseFirestore.getInstance().batch()
            for (r in plan.toRemote) batch.set(col.document(key(r.name)), document(r), SetOptions.merge())
            if (plan.toRemote.isNotEmpty()) setPending(app, true)
            // If the connection drops halfway, Firestore does not fail: it keeps the upload and waits. After a
            // while a notice says it is pending (when it finally uploads, the status becomes synced).
            main.postDelayed({ if (status == Status.Syncing) setStatus(Status.Offline(true)) }, COMMIT_TIMEOUT_MS)
            batch.commit().addOnSuccessListener {
                store.markSynced(plan.synced, uid)
                setPending(app, false)
                setStatus(Status.Synced(store.all.size, System.currentTimeMillis()))
                onDone(changed)
            }.addOnFailureListener { e -> fail(app, e); onDone(changed) }
        }.addOnFailureListener { e -> fail(app, e); onDone(false) }
        return true
    }

    /**
     * Uploads a local change immediately (saving or deleting a profile). Without internet the change stays in
     * the Firestore queue (on disk) and is uploaded automatically when the connection returns; also, the next
     * sync compares it again. Returns false in that case, to notify the user.
     */
    fun push(context: Context, name: String): Boolean {
        val col = collection() ?: return true
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return true
        val app = context.applicationContext
        val r = ProfileStore.get().records()[key(name)] ?: return true
        setPending(app, true)
        col.document(key(name)).set(document(r), SetOptions.merge())
            .addOnSuccessListener {
                ProfileStore.get().markSynced(listOf(key(name)), uid)
                setPending(app, false)
                setStatus(Status.Synced(ProfileStore.get().all.size, System.currentTimeMillis()))
            }
            .addOnFailureListener { fail(app, it) }
        if (!NetworkState.hasInternet(app)) {
            setStatus(Status.Offline(true))
            return false
        }
        return true
    }

    // Phone changes not yet uploaded (survives closing the app, for the Settings notice).
    private fun prefs(context: Context) = context.getSharedPreferences("cloud_profiles", Context.MODE_PRIVATE)
    private fun pending(context: Context) = prefs(context).getBoolean("pending", false)
    private fun setPending(context: Context, value: Boolean) = prefs(context).edit().putBoolean("pending", value).apply()

    private const val COMMIT_TIMEOUT_MS = 20_000L

    private fun document(r: Record): Map<String, Any?> = mapOf(
        "name" to r.name, "updatedAt" to r.updatedAt, "deleted" to r.deleted, "json" to r.json
    )

    private fun fail(context: Context, e: Exception) {
        Log.e("VizMini2", "Error sincronizando perfiles", e)
        // No connection to the server is not an account error: it stays pending for later.
        if ((e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
            setStatus(Status.Offline(pending(context)))
            return
        }
        val msg = when ((e as? FirebaseFirestoreException)?.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                context.getString(R.string.cloud_permission_denied)
            FirebaseFirestoreException.Code.UNAVAILABLE -> context.getString(R.string.cloud_unavailable)
            else -> e.message ?: context.getString(R.string.cloud_unknown_error)
        }
        setStatus(Status.Failed(msg))
    }

    /** To show a remote profile: JSON -> profile. */
}
