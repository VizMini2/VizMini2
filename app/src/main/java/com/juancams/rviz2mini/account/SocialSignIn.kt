// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.account

import android.app.Activity
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.juancams.rviz2mini.R

/**
 * Link a Google, Microsoft or GitHub account (Firebase Authentication). It is optional: it only serves
 * to have the profiles in the cloud on every phone. Google uses Android's account
 * picker; Microsoft and GitHub, their sign-in page.
 */
object SocialSignIn {

    enum class Provider(val id: String) {
        GOOGLE(GoogleAuthProvider.PROVIDER_ID), MICROSOFT("microsoft.com"), GITHUB("github.com");

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id }
        }
    }

    /** The user closed the picker or the page without choosing an account: not an error to show. */
    class Cancelled : Exception("Cancelled")

    private val auth get() = FirebaseAuth.getInstance()

    /** Signs in (or creates the account the first time). */
    fun signIn(activity: Activity, provider: Provider): Task<AuthResult> {
        val result = when (provider) {
            Provider.GOOGLE -> googleCredential(activity).onSuccessTask { auth.signInWithCredential(it!!) }
            else -> auth.pendingAuthResult ?: auth.startActivityForSignInWithProvider(activity, oauth(provider))
        }
        return result.continueWithTask { t -> if (t.isSuccessful) Tasks.forResult(t.result) else Tasks.forException<AuthResult>(cancelOr(t.exception)) }
    }

    /** External providers of the account (empty if it only has email and password, from earlier versions). */
    fun providersOf(user: FirebaseUser): List<Provider> = user.providerData.mapNotNull { Provider.of(it.providerId) }

    fun hasPassword(user: FirebaseUser) = user.providerData.any { it.providerId == "password" }

    /** Re-authenticate with the provider (Firebase requires it before deleting the account). */
    fun reauthenticate(activity: Activity, user: FirebaseUser, provider: Provider): Task<*> = when (provider) {
        Provider.GOOGLE -> googleCredential(activity).onSuccessTask { user.reauthenticate(it!!) }
        else -> user.startActivityForReauthenticateWithProvider(activity, oauth(provider))
    }.continueWithTask<Any?> { t -> if (t.isSuccessful) Tasks.forResult<Any?>(null) else Tasks.forException<Any?>(cancelOr(t.exception)) }

    /**
     * Adds another way to sign in to the open account. If that Google, Microsoft or GitHub account is already
     * another VizMini2 account, it fails with FirebaseAuthUserCollisionException.
     */
    fun link(activity: Activity, user: FirebaseUser, provider: Provider): Task<*> = when (provider) {
        Provider.GOOGLE -> googleCredential(activity).onSuccessTask { user.linkWithCredential(it!!) }
        else -> user.startActivityForLinkWithProvider(activity, oauth(provider))
    }.continueWithTask<Any?> { t -> if (t.isSuccessful) Tasks.forResult<Any?>(null) else Tasks.forException<Any?>(cancelOr(t.exception)) }

    /** Removes a sign-in method; the caller checks that it is not the only one. */
    fun unlink(user: FirebaseUser, provider: Provider): Task<AuthResult> = user.unlink(provider.id)

    private fun oauth(provider: Provider) = OAuthProvider.newBuilder(provider.id).apply {
        when (provider) {
            // The email, even if it is hidden in their GitHub profile.
            Provider.GITHUB -> scopes = listOf("user:email")
            // Let them choose an account (personal, work or university).
            Provider.MICROSOFT -> addCustomParameter("prompt", "select_account")
            Provider.GOOGLE -> {}
        }
    }.build()

    /** System Google account picker: returns the credential for Firebase. */
    private fun googleCredential(activity: Activity): Task<AuthCredential> {
        val tcs = TaskCompletionSource<AuthCredential>()
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(activity).getCredentialAsync(activity, request, CancellationSignal(), ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val c = result.credential
                    if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        tcs.setResult(GoogleAuthProvider.getCredential(GoogleIdTokenCredential.createFrom(c.data).idToken, null))
                    } else tcs.setException(IllegalStateException("Unexpected credential ${c.type}"))
                }

                override fun onError(e: GetCredentialException) {
                    tcs.setException(if (e is GetCredentialCancellationException) Cancelled() else e)
                }
            })
        return tcs.task
    }

    /** Microsoft and GitHub report that the page was closed with their own code. */
    private fun cancelOr(e: Exception?): Exception {
        val code = (e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode
        return if (code == "ERROR_WEB_CONTEXT_CANCELED" || code == "ERROR_WEB_CONTEXT_ALREADY_PRESENTED") Cancelled() else e ?: Exception("Sign-in failed")
    }
}
