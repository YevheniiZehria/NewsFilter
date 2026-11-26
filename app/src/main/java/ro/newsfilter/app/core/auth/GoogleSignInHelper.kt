package ro.newsfilter.app.core.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * „Continuă cu Google” prin **Credential Manager** (selectorul nativ de conturi Google).
 *
 * Întoarce token-ul ID Google, pe care [ro.newsfilter.app.data.repository.AuthRepositoryImpl]
 * îl schimbă într-o sesiune Firebase (`GoogleAuthProvider`). Firebase verifică token-ul, deci
 * aplicația nu trimite nicăieri un email „de încredere”.
 *
 * Client ID-ul web (`default_web_client_id`) este generat automat de pluginul google-services
 * din `google-services.json`, după activarea furnizorului Google în consola Firebase.
 */
@Singleton
class GoogleSignInHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed interface Result {
        data class Success(val idToken: String) : Result
        data object Cancelled : Result
        data class Error(val message: String) : Result
    }

    /** Citit la runtime: resursa lipsește dacă Google Sign-In nu este activat în Firebase. */
    private val webClientId: String?
        get() {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            return if (resId != 0) context.getString(resId).takeIf { it.isNotBlank() } else null
        }

    val isConfigured: Boolean get() = webClientId != null

    /** Afișează selectorul de conturi (necesită un Activity) și întoarce token-ul ID. */
    suspend fun signIn(activity: Activity): Result {
        val clientId = webClientId
            ?: return Result.Error("Autentificarea Google nu este activată în proiectul Firebase.")

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()

        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                Result.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Result.Error("Tip de credențial neașteptat.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (e: NoCredentialException) {
            Result.Error("Nu există niciun cont Google pe acest dispozitiv.")
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager a eșuat", e)
            Result.Error("Autentificarea Google a eșuat. Încearcă din nou.")
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Token Google invalid", e)
            Result.Error("Răspuns invalid de la Google.")
        }
    }

    private companion object {
        const val TAG = "NewsFilterAuth"
    }
}
