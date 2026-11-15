// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.juancams.rviz2mini.profiles.CloudProfiles
import com.juancams.rviz2mini.profiles.ProfileStore
import com.juancams.rviz2mini.profiles.ProfileSync
import com.juancams.rviz2mini.profiles.RobotProfile
import com.juancams.rviz2mini.ros.RosConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Cloud profiles with the phone's real account: uploads a test profile, reads it from Firestore,
 * deletes it and checks that the deletion also arrives. Only if signed in.
 */
@RunWith(AndroidJUnit4::class)
class CloudProfilesDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun profileRoundTrip() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        assumeTrue("Sin sesión iniciada", uid != null)
        val store = ProfileStore.get()
        val name = "Cloud test ${System.currentTimeMillis() % 100000}"
        val doc = FirebaseFirestore.getInstance().collection("users").document(uid!!).collection("profiles").document(ProfileSync.key(name))
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                store.save(RobotProfile(name, RosConfig(42, "cloud_test"), emptyList()))
                CloudProfiles.push(context, name)
            }
            var snap = Tasks.await(doc.get(), 15, TimeUnit.SECONDS)
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (!snap.exists() && SystemClock.uptimeMillis() < deadline) { SystemClock.sleep(500); snap = Tasks.await(doc.get(), 15, TimeUnit.SECONDS) }
            assertTrue("El perfil no llegó a Firestore (¿reglas?)", snap.exists())
            assertEquals(name, snap.getString("name"))
            assertTrue(snap.getString("json")!!.contains("\"domainId\":42"))

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                store.delete(name)
                CloudProfiles.push(context, name)
            }
            SystemClock.sleep(3000)
            assertEquals(true, Tasks.await(doc.get(), 15, TimeUnit.SECONDS).getBoolean("deleted"))
        } finally {
            // Do not leave garbage in the user's account.
            try { Tasks.await(doc.delete(), 15, TimeUnit.SECONDS) } catch (_: Exception) {}
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.delete(name) }
        }
    }
}
