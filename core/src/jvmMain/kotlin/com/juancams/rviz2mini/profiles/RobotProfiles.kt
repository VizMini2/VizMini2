// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.profiles

import com.juancams.rviz2mini.platform.Platform
import com.juancams.rviz2mini.ros.RosConfig
import com.juancams.rviz2mini.tabs.TabList
import com.juancams.rviz2mini.tabs.TabSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Robot profile: ROS 2 connection + all the tabs with their configuration (RViz displays,
 * camera topic, teleop settings...). Lets you switch robot with one tap.
 */
data class RobotProfile(val name: String, val ros: RosConfig, val tabs: List<TabSpec>) {

    /** Copy of the tabs with new ids, to load them without clashing with existing fragments. */
    fun tabsWithFreshIds(): List<TabSpec> {
        val ids = tabs.associate { it.id to UUID.randomUUID().toString() }
        return tabs.map { t ->
            var s = t.copy(id = ids.getValue(t.id))
            // Widgets point to their dashboard and the dashboard stores their ids in the layout.
            t.dashboardId?.let { d -> s = s.withSetting(TabSpec.KEY_DASHBOARD, ids[d] ?: d) }
            t.settings[DASHBOARD_LAYOUT]?.let { layout ->
                val items = com.juancams.rviz2mini.dashboard.DashboardGrid.fromJson(layout).map { it.copy(id = ids[it.id] ?: it.id) }
                s = s.withSetting(DASHBOARD_LAYOUT, com.juancams.rviz2mini.dashboard.DashboardGrid.toJson(items))
            }
            s
        }
    }
}

/** Key of a dashboard's layout (the one from DashboardView). */
private const val DASHBOARD_LAYOUT = "layout"

/** Profile (de)serialization and rules. No Android: tested on the JVM. */
object RobotProfiles {

    const val FORMAT = "rviz2mini-profile"
    const val MAX_NAME_LENGTH = 32

    fun validateName(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return "Enter a name"
        if (t.length > MAX_NAME_LENGTH) return "Maximum $MAX_NAME_LENGTH characters"
        return null
    }

    fun toJson(p: RobotProfile): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("name", p.name)
        .put("ros", JSONObject().put("domainId", p.ros.domainId).put("nodeName", p.ros.nodeName).put("interfaceWhitelist", p.ros.interfaceWhitelist)
            .put("discoveryPeers", p.ros.discoveryPeers).put("discoveryServers", p.ros.discoveryServers))
        .put("tabs", JSONArray(TabList.toJson(p.tabs)))

    /** null if it is not a valid profile. Out-of-range values are corrected as in RosConfig.load. */
    fun fromJson(o: JSONObject): RobotProfile? {
        val name = o.optString("name").trim()
        if (validateName(name) != null) return null
        val ros = o.optJSONObject("ros") ?: return null
        val domain = ros.optInt("domainId", 0).coerceIn(RosConfig.MIN_DOMAIN_ID, RosConfig.MAX_DOMAIN_ID)
        val node = ros.optString("nodeName").takeIf { RosConfig.validateNodeName(it) == null } ?: RosConfig.DEFAULT_NODE_NAME
        val tabs = TabList.fromJson(o.optJSONArray("tabs")?.toString()) ?: return null
        return RobotProfile(name, RosConfig(domain, node, ros.optString("interfaceWhitelist"), ros.optString("discoveryPeers"), ros.optString("discoveryServers")), tabs)
    }

    fun listToJson(list: List<RobotProfile>): String = JSONArray().apply { for (p in list) put(toJson(p)) }.toString()

    fun listFromJson(json: String?): List<RobotProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::fromJson) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Exported file (one profile) -> profile; throws IllegalArgumentException with the reason. */
    fun parseFile(text: String): RobotProfile {
        val o = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("Not a JSON file") }
        if (o.optString("format") != FORMAT) throw IllegalArgumentException("Not an VizMini2 profile")
        return fromJson(o) ?: throw IllegalArgumentException("The profile is incomplete or corrupted")
    }

    /** Replaces the profile with the same name (case-insensitive) or adds it, sorted by name. */
    fun upsert(list: List<RobotProfile>, p: RobotProfile): List<RobotProfile> =
        (list.filterNot { it.name.equals(p.name, ignoreCase = true) } + p).sortedBy { it.name.lowercase() }
}

/** Profiles saved in the "rviz2mini_profiles" preferences (on Android, the usual SharedPreferences). UI thread only. */
class ProfileStore private constructor() {

    private val prefs = Platform.prefs(PREFS)
    private var profiles = RobotProfiles.listFromJson(prefs.getString(KEY_PROFILES, null))

    val all: List<RobotProfile> get() = profiles

    /** Last profile saved or loaded (to show it in Config). */
    var activeName: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(v) = prefs.edit().putString(KEY_ACTIVE, v).apply()

    fun get(name: String) = profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * When each profile was modified, whether it was deleted and which account it was last synced with
     * (key [ProfileSync.key] -> [Meta]), to sync with the cloud.
     */
    private class Meta(val t: Long, val deleted: Boolean, val owner: String? = null)
    private val meta: MutableMap<String, Meta> = readMeta()

    // A change made on the phone does not belong to any account yet: it is uploaded to the one that syncs next.
    fun save(p: RobotProfile) {
        profiles = RobotProfiles.upsert(profiles, p)
        meta[ProfileSync.key(p.name)] = Meta(System.currentTimeMillis(), false)
        persist()
    }

    fun delete(name: String) {
        profiles = profiles.filterNot { it.name.equals(name, ignoreCase = true) }
        if (activeName.equals(name, ignoreCase = true)) activeName = null
        meta[ProfileSync.key(name)] = Meta(System.currentTimeMillis(), true)
        persist()
    }

    /** These profiles are already the same in the cloud of `uid`: they now belong to that account. */
    fun markSynced(keys: Collection<String>, uid: String) {
        var changed = false
        for (k in keys) {
            val m = meta[k]
            if (m != null && m.owner != uid) { meta[k] = Meta(m.t, m.deleted, uid); changed = true }
            else if (m == null && profiles.any { ProfileSync.key(it.name) == k }) { meta[k] = Meta(0L, false, uid); changed = true }
        }
        if (changed) persist()
    }

    /** State of each profile (deleted ones too) for [ProfileSync.plan]. */
    fun records(): Map<String, ProfileSync.Record> {
        val out = HashMap<String, ProfileSync.Record>()
        for (p in profiles) {
            val k = ProfileSync.key(p.name)
            out[k] = ProfileSync.Record(p.name, meta[k]?.t ?: 0L, RobotProfiles.toJson(p).toString(), meta[k]?.owner)
        }
        for ((k, m) in meta) if (m.deleted && k !in out) out[k] = ProfileSync.Record(k, m.t, null, m.owner)
        return out
    }

    /** Applies a change coming from the cloud of `owner`. True if any profile on the phone changes. */
    fun applyRemote(r: ProfileSync.Record, owner: String? = null): Boolean {
        val k = ProfileSync.key(r.name)
        if (r.deleted) {
            val had = profiles.any { ProfileSync.key(it.name) == k }
            profiles = profiles.filterNot { ProfileSync.key(it.name) == k }
            if (activeName?.let { ProfileSync.key(it) } == k) activeName = null
            meta[k] = Meta(r.updatedAt, true, owner)
            persist()
            return had
        }
        val p = ProfileSync.parse(r.json!!) ?: return false
        profiles = RobotProfiles.upsert(profiles, p)
        meta[k] = Meta(r.updatedAt, false, owner)
        persist()
        return true
    }

    private fun readMeta(): MutableMap<String, Meta> {
        val out = HashMap<String, Meta>()
        try {
            val o = org.json.JSONObject(prefs.getString(KEY_META, "{}") ?: "{}")
            for (k in o.keys()) o.optJSONObject(k)?.let {
                out[k] = Meta(it.optLong("t"), it.optBoolean("d"), if (it.isNull("u")) null else it.optString("u").ifEmpty { null })
            }
        } catch (_: Exception) { }
        return out
    }

    private fun persist() {
        val m = org.json.JSONObject()
        for ((k, v) in meta) m.put(k, org.json.JSONObject().put("t", v.t).put("d", v.deleted).apply { v.owner?.let { put("u", it) } })
        prefs.edit().putString(KEY_PROFILES, RobotProfiles.listToJson(profiles)).putString(KEY_META, m.toString()).apply()
    }

    companion object {
        private const val PREFS = "rviz2mini_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active"
        private const val KEY_META = "meta"

        @Volatile private var instance: ProfileStore? = null

        fun get(): ProfileStore =
            instance ?: synchronized(this) { instance ?: ProfileStore().also { instance = it } }
    }
}
