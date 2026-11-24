// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.profiles

import org.json.JSONObject

/**
 * Profile sync with the cloud, without the cloud: the state of each profile on one side (this device or the
 * cloud) and what to copy to each side so they end up equal (the most recent change wins; deletions are "tombstones").
 * Used by Android (Firestore) and the PC (Firestore over REST).
 */
object ProfileSync {
    /**
     * State of a profile on one side (phone or cloud). `json` is null for deletions. `owner`: on the phone, uid
     * of the account it was last synced with (null if never or if it has changed since then).
     */
    data class Record(val name: String, val updatedAt: Long, val json: String?, val owner: String? = null) {
        val deleted: Boolean get() = json == null
    }

    /** `synced`: keys that end up equal on the phone and in this account's cloud (they become its own). */
    data class Plan(val toLocal: List<Record>, val toRemote: List<Record>, val synced: Set<String> = emptySet())

    /** Document key: the name without uppercase or odd characters (names are case-insensitive). */
    fun key(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9_.-]+"), "_").take(64).ifEmpty { "_" }

    /**
     * What must be copied to each side so both end up equal (the most recent wins). With `uid`, the
     * phone profiles that come from another account (synced with it and not touched since) are not
     * uploaded to this one: they stay on the phone, but are not mixed into another account's cloud.
     */
    fun plan(local: Map<String, Record>, remote: Map<String, Record>, uid: String? = null): Plan {
        val toLocal = ArrayList<Record>()
        val toRemote = ArrayList<Record>()
        val mine = if (uid == null) local else local.filterValues { it.owner == null || it.owner == uid }
        val keys = (mine.keys + remote.keys).toSortedSet()
        for (k in keys) {
            val l = mine[k]
            val r = remote[k]
            when {
                l == null -> toLocal += r!!
                r == null -> toRemote += l
                r.updatedAt > l.updatedAt -> toLocal += r
                l.updatedAt > r.updatedAt -> toRemote += l
            }
        }
        // A deletion the other side doesn't have doesn't need to be copied to the phone.
        return Plan(toLocal.filterNot { it.deleted && mine[key(it.name)] == null }, toRemote, keys)
    }

    fun parse(json: String): RobotProfile? = try { RobotProfiles.fromJson(JSONObject(json)) } catch (_: Exception) { null }
}
