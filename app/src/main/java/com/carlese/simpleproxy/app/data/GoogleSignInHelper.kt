@file:Suppress("DEPRECATION")

package com.carlese.simpleproxy.app.data

import android.annotation.SuppressLint
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

import kotlinx.coroutines.tasks.await

sealed interface GoogleSignInOutcome {
    data class Success(val idToken: String) : GoogleSignInOutcome
    data class NeedLegacyIntent(val intentSender: IntentSender) : GoogleSignInOutcome
    data class NeedClassicIntent(val intent: Intent) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Error(val message: String) : GoogleSignInOutcome
}

object GoogleSignInHelper {
    private const val TAG = "SimpleProxyGoogle"

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
        val isTv = isTelevision(activity)
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
        } catch (t: GetCredentialCancellationException) {
            if (isTv) {
                Log.w(TAG, "Credential Manager cancelled on TV; trying the classic Google Sign-In flow")
                createClassicIntentOutcome(activity, webClientId)
            } else {
                GoogleSignInOutcome.Cancelled
            }
        } catch (t: GetCredentialUnsupportedException) {
            if (isTv) createClassicIntentOutcome(activity, webClientId)
            else createLegacyIntentOutcome(activity, webClientId)
        } catch (t: NoCredentialException) {
            if (isTv) createClassicIntentOutcome(activity, webClientId)
            else createLegacyIntentOutcome(activity, webClientId)
        } catch (t: Throwable) {
            if (isTv) {
                Log.w(TAG, "Credential Manager failed on TV (${t.javaClass.simpleName}); trying the classic flow")
                createClassicIntentOutcome(activity, webClientId)
            } else {
                GoogleSignInOutcome.Error(t.localizedMessage ?: "Google Sign-In failed")
            }
        }
    }

    private fun isTelevision(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.getSystemService(UiModeManager::class.java)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION

    private fun createClassicIntentOutcome(
        context: Context,
        webClientId: String,
    ): GoogleSignInOutcome = runCatching {
        createClassicSignInIntent(context, webClientId)
    }.fold(
        onSuccess = { GoogleSignInOutcome.NeedClassicIntent(it) },
        onFailure = { GoogleSignInOutcome.Error(it.localizedMessage ?: "Google Sign-In failed") },
    )

    private fun createClassicSignInIntent(context: Context, webClientId: String): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, options).signInIntent
    }

    suspend fun extractIdTokenFromClassicSignInIntent(data: Intent?): Result<String> = runCatching {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data).await()
        account.idToken ?: throw IllegalStateException("Google ID Token missing")
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
        val webClientId = resolveWebClientId(context)
        if (webClientId.isNotBlank()) {
            runCatching {
                val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(webClientId)
                    .requestEmail()
                    .build()
                GoogleSignIn.getClient(context, options).signOut().await()
            }
        }
    }
}
