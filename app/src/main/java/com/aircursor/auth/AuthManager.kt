package com.aircursor.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** The signed-in person. [guest] = no account (only while sign-in is not set up yet). */
data class AppUser(
    val uid: String,
    val name: String,
    val phone: String?,
    val email: String?,
    val provider: String,
    val guest: Boolean,
)

/**
 * Sign-in with Google or a phone number (OTP), using Firebase Authentication.
 * The session is kept by Firebase on this phone: you stay signed in across restarts and app
 * updates until you sign out. Signing out never deletes any data on the phone.
 *
 * Works only when the app is built with the owner's google-services.json (see docs/LOGIN-SETUP.md).
 * Without it, people can continue as a guest with just their name.
 */
object AuthManager {
    private const val TAG = "AuthManager"
    private const val PREFS = "aircursor_account"

    private val _user = MutableStateFlow<AppUser?>(null)
    val user: StateFlow<AppUser?> = _user

    var configured = false
        private set
    private var initialized = false
    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var pendingName = ""

    fun init(c: Context) {
        if (initialized) return
        initialized = true
        val app = c.applicationContext
        configured = try {
            FirebaseApp.getApps(app).isNotEmpty() || FirebaseApp.initializeApp(app) != null
        } catch (e: Throwable) {
            Log.i(TAG, "Firebase not configured: ${e.message}")
            false
        }
        if (configured) {
            val auth = FirebaseAuth.getInstance()
            _user.value = auth.currentUser?.let { toAppUser(app, it) }
            auth.addAuthStateListener { a -> _user.value = a.currentUser?.let { toAppUser(app, it) } }
        } else {
            _user.value = guest(app)
        }
    }

    fun googleAvailable(c: Context) = configured && webClientId(c) != null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun guest(c: Context): AppUser? {
        val name = prefs(c).getString("guest_name", null) ?: return null
        return AppUser("guest", name, null, null, "guest", true)
    }

    private fun toAppUser(c: Context, u: FirebaseUser): AppUser {
        val provider = u.providerData.map { it.providerId }.firstOrNull { it != "firebase" } ?: "firebase"
        val name = u.displayName?.takeIf { it.isNotBlank() }
            ?: prefs(c).getString("name_${u.uid}", null)
            ?: u.phoneNumber ?: u.email ?: "AirCursor user"
        return AppUser(u.uid, name, u.phoneNumber, u.email, provider, guest = false)
    }

    /** Only used while sign-in is not set up: remembers the name on this phone. */
    fun continueAsGuest(c: Context, name: String) {
        val n = name.trim().ifEmpty { "Guest" }
        prefs(c).edit().putString("guest_name", n).apply()
        _user.value = AppUser("guest", n, null, null, "guest", true)
    }

    /** Signs out (or leaves guest mode). Settings and AirCursor PC files stay on the phone. */
    suspend fun signOut(c: Context) {
        if (configured) {
            try {
                FirebaseAuth.getInstance().signOut()
                CredentialManager.create(c).clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "sign out", e)
            }
        }
        prefs(c).edit().remove("guest_name").apply()
        _user.value = null
    }

    // ---- Google ----

    private fun webClientId(c: Context): String? {
        val id = c.resources.getIdentifier("default_web_client_id", "string", c.packageName)
        return if (id != 0) c.getString(id).takeIf { it.isNotBlank() } else null
    }

    /** Returns null on success, else a message for the person. */
    suspend fun signInWithGoogle(activity: Activity): String? {
        val clientId = webClientId(activity) ?: return "Google sign-in is not set up yet."
        return try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(cred.data)
                signIn(activity, GoogleAuthProvider.getCredential(google.idToken, null), google.displayName.orEmpty())
            } else {
                "This account type is not supported."
            }
        } catch (e: GetCredentialCancellationException) {
            "Sign-in cancelled."
        } catch (e: GetCredentialException) {
            Log.w(TAG, "google", e)
            "No Google account available: ${e.message ?: e.type}"
        } catch (e: Exception) {
            Log.w(TAG, "google", e)
            friendly(e)
        }
    }

    // ---- Phone number (OTP) ----

    sealed class PhoneEvent {
        data object CodeSent : PhoneEvent()
        data object SignedIn : PhoneEvent()
        data class Error(val message: String) : PhoneEvent()
    }

    /** [phone] in international format, e.g. +919876543210. */
    fun sendCode(activity: Activity, phone: String, name: String, resend: Boolean, onEvent: (PhoneEvent) -> Unit) {
        if (!configured) {
            onEvent(PhoneEvent.Error("Phone sign-in is not set up yet."))
            return
        }
        pendingName = name.trim()
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Android read the SMS by itself: sign in without typing the code.
                activity.runOnUiThread {
                    signInAsync(activity, credential, pendingName) { err ->
                        onEvent(if (err == null) PhoneEvent.SignedIn else PhoneEvent.Error(err))
                    }
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.w(TAG, "phone", e)
                onEvent(PhoneEvent.Error(friendly(e)))
            }

            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                verificationId = id
                resendToken = token
                onEvent(PhoneEvent.CodeSent)
            }
        }
        val builder = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
            .setPhoneNumber(phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
        val token = resendToken
        if (resend && token != null) builder.setForceResendingToken(token)
        try {
            PhoneAuthProvider.verifyPhoneNumber(builder.build())
        } catch (e: Exception) {
            onEvent(PhoneEvent.Error(friendly(e)))
        }
    }

    fun verifyCode(activity: Activity, code: String, onDone: (String?) -> Unit) {
        val id = verificationId
        if (id == null) {
            onDone("Please request a new code.")
            return
        }
        val credential = try {
            PhoneAuthProvider.getCredential(id, code.trim())
        } catch (e: Exception) {
            onDone(friendly(e))
            return
        }
        signInAsync(activity, credential, pendingName, onDone)
    }

    // ---- Shared ----

    private suspend fun signIn(c: Context, credential: AuthCredential, name: String): String? =
        suspendCancellableCoroutine { cont -> signInAsync(c, credential, name) { if (cont.isActive) cont.resume(it) } }

    private fun signInAsync(c: Context, credential: AuthCredential, name: String, onDone: (String?) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val u = result.user
                if (u != null && name.isNotBlank() && u.displayName.isNullOrBlank()) {
                    prefs(c).edit().putString("name_${u.uid}", name).apply()
                    u.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build())
                        .addOnCompleteListener { _user.value = auth.currentUser?.let { toAppUser(c.applicationContext, it) } }
                }
                _user.value = u?.let { toAppUser(c.applicationContext, it) }
                onDone(null)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "sign in", e)
                onDone(friendly(e))
            }
    }

    private fun friendly(e: Exception): String {
        val code = (e as? FirebaseAuthException)?.errorCode.orEmpty()
        val msg = e.message.orEmpty()
        return when {
            e is FirebaseTooManyRequestsException -> "Too many attempts. Please try again later."
            code == "ERROR_INVALID_VERIFICATION_CODE" -> "Wrong code. Please check and try again."
            code == "ERROR_SESSION_EXPIRED" -> "The code expired. Tap Resend code."
            code == "ERROR_INVALID_PHONE_NUMBER" -> "Please enter a valid mobile number."
            code == "ERROR_OPERATION_NOT_ALLOWED" -> "This sign-in method is not switched on yet."
            msg.contains("BILLING", ignoreCase = true) -> "Phone sign-in is not active yet. Please use Google sign-in."
            e is FirebaseAuthInvalidCredentialsException -> "Invalid code or number."
            msg.contains("network", ignoreCase = true) -> "No internet connection."
            else -> msg.ifEmpty { "Sign-in failed. Please try again." }
        }
    }
}
