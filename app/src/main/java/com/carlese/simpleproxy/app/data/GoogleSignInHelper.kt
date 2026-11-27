@file:Suppress("DEPRECATION")

package com.carlese.simpleproxy.app.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

import kotlinx.coroutines.tasks.await

sealed interface GoogleSignInOutcome {
    data class Success(val idToken: String) : GoogleSignInOutcome
    data class NeedLegacyIntent(val intentSender: IntentSender) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Error(val message: String) : GoogleSignInOutcome
}

object GoogleSignInHelper {

    @SuppressLint("DiscouragedApi")
    fun resolveWebClientId(context: Context): String {
        val resId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )
        return if (resId != 0) context.getString(resId) else ""
    }

    suspend fun requestGoogleIdToken(activity: Activity): GoogleSignInOutcome {
        val webClientId = resolveWebClientId(activity)
        if (webClientId.isBlank()) {
            return GoogleSignInOutcome.Error(
                "Web Client ID missing in google-services.json. Enable Google Sign-In in Firebase console and download google-services.json."
            )
        }

        val credentialManager = CredentialManager.create(activity)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val result = credentialManager.getCredential(
                context = activity,
                request = request
            )
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleSignInOutcome.Success(googleCred.idToken)
            } else {
                GoogleSignInOutcome.Error("Invalid Google credential.")
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInOutcome.Cancelled
        } catch (_: GetCredentialUnsupportedException) {
            createLegacyIntentOutcome(activity, webClientId)
        } catch (_: NoCredentialException) {
            createLegacyIntentOutcome(activity, webClientId)
        } catch (t: Throwable) {
            GoogleSignInOutcome.Error(t.localizedMessage ?: "Google Sign-In failed")
        }
    }

    private suspend fun createLegacyIntentOutcome(
        context: Context,
        webClientId: String,
    ): GoogleSignInOutcome = runCatching {
        createLegacyIntentSender(context, webClientId)
    }.fold(
        onSuccess = { GoogleSignInOutcome.NeedLegacyIntent(it) },
        onFailure = { GoogleSignInOutcome.Error(it.localizedMessage ?: "Google Sign-In failed") },
    )

    @Suppress("DEPRECATION")
    private suspend fun createLegacyIntentSender(context: Context, webClientId: String): IntentSender {
        val request = GetSignInIntentRequest.builder()
            .setServerClientId(webClientId)
            .build()
        return Identity.getSignInClient(context).getSignInIntent(request).await().intentSender
    }

    @Suppress("DEPRECATION")
    fun extractIdTokenFromLegacyIntent(context: Context, data: Intent?): Result<String> {
        return runCatching {
            val credential = Identity.getSignInClient(context)
                .getSignInCredentialFromIntent(data)
            credential.googleIdToken ?: throw IllegalStateException("Google ID Token missing")
        }
    }

    @Suppress("DEPRECATION")
    suspend fun signOutProviders(context: Context) {
        runCatching {
            CredentialManager.create(context)
                .clearCredentialState(ClearCredentialStateRequest())
        }
        runCatching {
            Identity.getSignInClient(context).signOut().await()
        }
    }
}
