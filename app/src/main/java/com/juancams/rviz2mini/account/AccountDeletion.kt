// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.account

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Delete the account from the app (required by Google Play): cloud profiles, the account document
 * created by previous versions and the Firebase Authentication account. Firebase requires having
 * signed in recently to delete the account, so the user's identity is verified again first: with the
 * password or with their provider (Google…).
 */
object AccountDeletion {

    private const val TAG = "VizMini2"

    /** Password accounts (created with previous versions). Fails (without deleting anything) if it is wrong. */
    fun delete(password: String): Task<Void> {
        val user = FirebaseAuth.getInstance().currentUser ?: return Tasks.forException(IllegalStateException("Not signed in"))
        val email = user.email ?: return Tasks.forException(IllegalStateException("No email"))
        return deleteAfter(user.reauthenticate(EmailAuthProvider.getCredential(email, password)))
    }

    /** All of the deletion after `reauthentication` (password or provider); if that fails nothing is touched. */
    fun deleteAfter(reauthentication: Task<*>): Task<Void> {
        val user = FirebaseAuth.getInstance().currentUser ?: return Tasks.forException(IllegalStateException("Not signed in"))
        val doc = FirebaseFirestore.getInstance().collection("users").document(user.uid)
        // onSuccessTask: each step only if the previous one succeeded.
        return reauthentication
            // Cloud profiles (the rules let each user delete their own).
            .continueWithTask { t -> if (t.isSuccessful) doc.collection("profiles").get() else Tasks.forException(t.exception ?: Exception("Reauthentication failed")) }
            .onSuccessTask { snap -> Tasks.whenAll(snap!!.documents.map { it.reference.delete() }) }
            // The account document (only exists if it was created with a previous version). Even if it fails,
            // the account is deleted anyway.
            .onSuccessTask {
                doc.delete().continueWith { t ->
                    if (!t.isSuccessful) Log.w(TAG, "No se pudo borrar users/${user.uid}", t.exception)
                    null
                }
            }
            .onSuccessTask { user.delete() }
    }
}
