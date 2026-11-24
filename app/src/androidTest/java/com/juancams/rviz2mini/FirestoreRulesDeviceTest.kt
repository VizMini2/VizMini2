// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Published Firestore rules, with the account linked on the phone: `-e firestore true`. Writes,
 * reads and deletes a temporary profile of its own and checks that nothing else can be touched.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreRulesDeviceTest {

    private fun <T> await(t: Task<T>): T = Tasks.await(t, 20, TimeUnit.SECONDS)

    /** Checks that Firestore denies the operation due to the rules. */
    private fun denied(what: String, t: () -> Task<*>) {
        try {
            await(t())
            fail("Firestore permitió: $what")
        } catch (e: java.util.concurrent.ExecutionException) {
            val code = (e.cause as? FirebaseFirestoreException)?.code
            assertEquals(what, FirebaseFirestoreException.Code.PERMISSION_DENIED, code)
            Log.i(TAG, "denegado (bien): $what")
        }
    }

    @Test
    fun onlyOwnProfiles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("firestore") == "true")
        val user = FirebaseAuth.getInstance().currentUser
        assumeTrue("Sin cuenta vinculada", user != null)
        val db = FirebaseFirestore.getInstance()
        val mine = db.collection("users").document(user!!.uid)

        val ref = mine.collection("profiles").document("zz_rules_check")
        await(ref.set(mapOf("name" to "zz_rules_check", "updatedAt" to 0L, "deleted" to true, "json" to null)))
        assertEquals("zz_rules_check", await(ref.get()).getString("name"))
        await(mine.collection("profiles").get())
        await(ref.delete())
        Log.i(TAG, "perfiles propios: escribir, leer y borrar OK")

        val other = db.collection("users").document("zz_someone_else")
        denied("leer perfiles de otra cuenta") { other.collection("profiles").get() }
        denied("escribir perfiles de otra cuenta") { other.collection("profiles").document("x").set(mapOf("name" to "x")) }
        denied("listar las cuentas") { db.collection("users").get() }
        denied("escribir el documento de la cuenta") { mine.set(mapOf("isAdmin" to true), SetOptions.merge()) }
        denied("escribir fuera de users") { db.collection("config").document("app").set(mapOf("x" to 1)) }
    }

    companion object {
        private const val TAG = "FirestoreRulesTest"
    }
}
