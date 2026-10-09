package com.x45.expense.cloud

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

/**
 * "Continue with Google": asks Android for a Google ID token for our server's client id.
 * The server checks the token with Google, so the phone never handles a password.
 * No Google account on the phone yet? Fall back to Google's own sign-in screen, which can add one.
 */
object GoogleSignIn {
    fun start(act: ComponentActivity, serverClientId: String, done: (token: String?, error: String?) -> Unit) {
        act.lifecycleScope.launch {
            try {
                val cm = CredentialManager.create(act)
                val picker = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setServerClientId(serverClientId).build()
                val r = try {
                    cm.getCredential(act, GetCredentialRequest.Builder().addCredentialOption(picker).build())
                } catch (e: NoCredentialException) {
                    cm.getCredential(act, GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build()).build())
                }
                val c = r.credential
                if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) done(GoogleIdTokenCredential.createFrom(c.data).idToken, null)
                else done(null, "That wasn't a Google account")
            } catch (e: GetCredentialCancellationException) {
                done(null, "Sign-in cancelled")
            } catch (e: NoCredentialException) {
                done(null, "Add a Google account to this phone first: Settings → Passwords & accounts")
            } catch (e: Exception) {
                done(null, e.message ?: "Sign-in cancelled")
            }
        }
    }
}
