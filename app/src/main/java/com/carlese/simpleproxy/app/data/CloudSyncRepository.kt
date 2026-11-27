package com.carlese.simpleproxy.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKey

@Immutable
data class CloudAccountState(
    val uid: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val isSigningIn: Boolean = false,
    val isSyncing: Boolean = false,
    val needsDriveConsent: Boolean = false,
    val pendingDriveConsent: PendingIntent? = null,
    val lastError: String? = null
)

class CloudSyncRepository(
    context: Context,
    private val dao: ProxyDao,
    private val settingsRepo: SettingsRepository
) {
    private val appContext = context.applicationContext
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val crypto = E2eeCryptoManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var listenerRegistration: ListenerRegistration? = null

    @Volatile
    private var activeDek: SecretKey? = null

    @Volatile
    private var activeVaultUid: String? = null

    private val _accountState = MutableStateFlow(stateFromUser(auth.currentUser))
    val accountState: StateFlow<CloudAccountState> = _accountState.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _accountState.update { current ->
            stateFromUser(user).copy(
                isSigningIn = false,
                isSyncing = current.isSyncing,
                needsDriveConsent = if (user == null) false else current.needsDriveConsent,
                pendingDriveConsent = if (user == null) null else current.pendingDriveConsent
            )
        }
        if (user != null) {
            scope.launch { prepareSession(user.uid, triggerSync = false, allowPromptConsent = false) }
        } else {
            stopListenersAndLockMemory()
        }
    }

    init {
        auth.addAuthStateListener(authListener)
        auth.currentUser?.let { user ->
            scope.launch {
                prepareSession(
                    uid = user.uid,
                    triggerSync = settingsRepo.settings.value.autoSyncOnStart,
                    allowPromptConsent = false
                )
            }
        }
    }

    fun setSigningIn(loading: Boolean) {
        _accountState.update { it.copy(isSigningIn = loading, lastError = if (loading) null else it.lastError) }
    }

    fun reportError(message: String?) {
        _accountState.update { it.copy(isSigningIn = false, isSyncing = false, lastError = message) }
    }

    fun clearError() {
        _accountState.update { it.copy(lastError = null) }
    }

    fun consumePendingDriveConsent() {
        _accountState.update { it.copy(pendingDriveConsent = null) }
    }

    fun onDriveConsentCancelled() {
        _accountState.update { it.copy(needsDriveConsent = true, pendingDriveConsent = null) }
    }

    suspend fun signInWithGoogleIdToken(idToken: String) {
        _accountState.update { it.copy(isSigningIn = true, lastError = null) }
        try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val uid = result.user?.uid ?: auth.currentUser?.uid
            if (uid != null) {
                prepareSession(uid, triggerSync = true, allowPromptConsent = true)
            }
        } catch (t: Throwable) {
            _accountState.update {
                it.copy(
                    isSigningIn = false,
                    lastError = t.localizedMessage ?: "Firebase Auth failed"
                )
            }
        } finally {
            _accountState.update { it.copy(isSigningIn = false) }
        }
    }

    suspend fun completeDriveConsent(data: Intent?) {
        val uid = auth.currentUser?.uid ?: return
        val tokenResult = DriveVaultClient.extractTokenFromIntent(appContext, data)
        tokenResult.onSuccess { accessToken ->
            syncMutex.withLock {
                _accountState.update { it.copy(isSyncing = true, lastError = null) }
                try {
                    val vaultKey = DriveVaultClient.fetchOrCreateVaultKey(uid, accessToken)
                    crypto.sealDekLocally(uid, vaultKey.vaultId, vaultKey.dek)
                    activeDek = vaultKey.dek
                    activeVaultUid = uid
                    _accountState.update { it.copy(needsDriveConsent = false, pendingDriveConsent = null) }
                    startRealtimeListener(uid)
                    syncUnlockedLocked(uid, vaultKey.dek)
                } catch (unauth: DriveUnauthorizedException) {
                    DriveVaultClient.clearCachedToken(appContext, accessToken)
                    _accountState.update {
                        it.copy(
                            needsDriveConsent = true,
                            isSyncing = false,
                            lastError = unauth.localizedMessage ?: "Google Drive token expired"
                        )
                    }
                } catch (t: Throwable) {
                    _accountState.update {
                        it.copy(
                            needsDriveConsent = true,
                            isSyncing = false,
                            lastError = t.localizedMessage ?: "Google Drive key error"
                        )
                    }
                }
            }
        }.onFailure { err ->
            _accountState.update {
                it.copy(
                    needsDriveConsent = true,
                    lastError = err.localizedMessage ?: "Google Drive consent failed"
                )
            }
        }
    }

    private suspend fun prepareSession(uid: String, triggerSync: Boolean, allowPromptConsent: Boolean) {
        syncMutex.withLock {
            val dek = ensureDriveDekLocked(uid, allowPromptConsent) ?: return
            startRealtimeListener(uid)
            if (triggerSync) {
                syncUnlockedLocked(uid, dek)
            }
        }
    }

    suspend fun syncNow() {
        val uid = auth.currentUser?.uid ?: return
        syncMutex.withLock {
            val dek = ensureDriveDekLocked(uid, allowPromptConsent = true) ?: return
            startRealtimeListener(uid)
            syncUnlockedLocked(uid, dek)
        }
    }

    private suspend fun ensureDriveDekLocked(uid: String, allowPromptConsent: Boolean): SecretKey? {
        activeDek?.let { cached ->
            if (activeVaultUid == uid) {
                _accountState.update { it.copy(needsDriveConsent = false) }
                return cached
            }
        }
        val localSealed = crypto.loadAnySealedDek(uid)
        if (localSealed != null) {
            activeDek = localSealed
            activeVaultUid = uid
            _accountState.update { it.copy(needsDriveConsent = false, pendingDriveConsent = null) }
            return localSealed
        }

        var outcome = DriveVaultClient.authorizeSilently(appContext)
        if (outcome is DriveAuthOutcome.Authorized) {
            try {
                return unlockWithDriveTokenLocked(uid, outcome.accessToken)
            } catch (_: DriveUnauthorizedException) {
                // Token scaduto o revocato lato Google: svuota cache Play Services e riprova (o chiedi consenso)
                DriveVaultClient.clearCachedToken(appContext, outcome.accessToken)
                outcome = DriveVaultClient.authorizeSilently(appContext)
            } catch (t: Throwable) {
                _accountState.update {
                    it.copy(
                        needsDriveConsent = true,
                        lastError = t.localizedMessage ?: "Google Drive key sync error"
                    )
                }
                return null
            }
        }

        return when (val finalOutcome = outcome) {
            is DriveAuthOutcome.Authorized -> {
                try {
                    unlockWithDriveTokenLocked(uid, finalOutcome.accessToken)
                } catch (t: Throwable) {
                    if (t is DriveUnauthorizedException) {
                        DriveVaultClient.clearCachedToken(appContext, finalOutcome.accessToken)
                    }
                    _accountState.update {
                        it.copy(
                            needsDriveConsent = true,
                            lastError = t.localizedMessage ?: "Google Drive key sync error"
                        )
                    }
                    null
                }
            }
            is DriveAuthOutcome.NeedsConsent -> {
                _accountState.update {
                    it.copy(
                        needsDriveConsent = true,
                        pendingDriveConsent = if (allowPromptConsent) finalOutcome.pendingIntent else null
                    )
                }
                null
            }
            is DriveAuthOutcome.Error -> {
                _accountState.update {
                    it.copy(
                        needsDriveConsent = true,
                        lastError = finalOutcome.message
                    )
                }
                null
            }
        }
    }

    private suspend fun unlockWithDriveTokenLocked(uid: String, accessToken: String): SecretKey {
        val vaultKey = DriveVaultClient.fetchOrCreateVaultKey(uid, accessToken)
        crypto.sealDekLocally(uid, vaultKey.vaultId, vaultKey.dek)
        activeDek = vaultKey.dek
        activeVaultUid = uid
        _accountState.update { it.copy(needsDriveConsent = false, pendingDriveConsent = null, lastError = null) }
        return vaultKey.dek
    }

    private suspend fun syncUnlockedLocked(uid: String, dek: SecretKey) {
        _accountState.update { it.copy(isSyncing = true, lastError = null) }
        try {
            val remoteSnap = proxiesCollection(uid).get().await()
            mergeRemoteLocked(uid, dek, remoteSnap.documents)

            val remoteMap = remoteSnap.documents.associateBy { it.id }
            val localProfiles = dao.getAllOnce()
            for (local in localProfiles) {
                val remoteDoc = remoteMap[local.id]
                val remoteUpdated = remoteDoc?.getLong("updatedAt") ?: -1L
                val remoteDeleted = remoteDoc?.getBoolean("deleted") ?: false
                val remoteEnc = remoteDoc?.getString("enc")
                val remoteIsPlaintext = remoteDoc != null && !remoteDeleted && remoteEnc.isNullOrBlank()
                val remoteUndecryptable = !remoteDeleted &&
                    remoteEnc != null &&
                    remoteEnc.isNotBlank() &&
                    parseEncryptedProfile(local.id, remoteUpdated, remoteEnc, uid, dek) == null
                if (remoteDoc == null || remoteIsPlaintext || remoteUndecryptable || (!remoteDeleted && local.updatedAt > remoteUpdated)) {
                    proxiesCollection(uid).document(local.id)
                        .set(toEncryptedFirestoreMap(local, uid, dek))
                        .await()
                }
            }

            markSyncedNow()
        } catch (t: Throwable) {
            _accountState.update {
                it.copy(lastError = t.localizedMessage ?: "Firestore sync error")
            }
        } finally {
            _accountState.update { it.copy(isSyncing = false) }
        }
    }

    suspend fun pushProfile(profile: ProxyProfile) {
        val uid = auth.currentUser?.uid ?: return
        syncMutex.withLock {
            val dek = ensureDriveDekLocked(uid, allowPromptConsent = true) ?: return
            runCatching {
                proxiesCollection(uid).document(profile.id)
                    .set(toEncryptedFirestoreMap(profile, uid, dek))
                    .await()
                markSyncedNow()
            }.onFailure { t ->
                _accountState.update { it.copy(lastError = t.localizedMessage) }
            }
        }
    }

    suspend fun pushDelete(id: String) {
        val uid = auth.currentUser?.uid ?: return
        syncMutex.withLock {
            if (ensureDriveDekLocked(uid, allowPromptConsent = true) == null) return
            runCatching {
                val tombstone = mapOf(
                    "id" to id,
                    "deleted" to true,
                    "updatedAt" to System.currentTimeMillis()
                )
                proxiesCollection(uid).document(id).set(tombstone).await()
                markSyncedNow()
            }.onFailure { t ->
                _accountState.update { it.copy(lastError = t.localizedMessage) }
            }
        }
    }

    suspend fun signOut() {
        val uid = auth.currentUser?.uid
        stopListenersAndLockMemory()
        if (uid != null) {
            crypto.clearLocalSeal(uid)
        }
        auth.signOut()
        settingsRepo.update { it.copy(lastSyncTime = null) }
        _accountState.value = CloudAccountState()
        withTimeoutOrNull(2_000) {
            GoogleSignInHelper.signOutProviders(appContext)
        }
    }

    fun stop() {
        auth.removeAuthStateListener(authListener)
        stopListenersAndLockMemory()
        scope.cancel()
    }

    private fun stopListenersOnly() {
        listenerRegistration?.remove()
        listenerRegistration = null
    }

    private fun stopListenersAndLockMemory() {
        stopListenersOnly()
        activeDek = null
        activeVaultUid = null
    }

    private fun startRealtimeListener(uid: String) {
        if (listenerRegistration != null && activeVaultUid == uid) return
        stopListenersOnly()
        listenerRegistration = proxiesCollection(uid).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                if (error != null) {
                    _accountState.update { it.copy(lastError = error.localizedMessage) }
                }
                return@addSnapshotListener
            }
            scope.launch {
                syncMutex.withLock {
                    if (activeVaultUid != uid) return@withLock
                    val dek = activeDek ?: return@withLock
                    mergeRemoteLocked(uid, dek, snapshot.documents)
                    markSyncedNow()
                }
            }
        }
    }

    private suspend fun mergeRemoteLocked(uid: String, dek: SecretKey, documents: List<DocumentSnapshot>) {
        val localMap = dao.getAllOnce().associateBy { it.id }
        for (doc in documents) {
            val id = doc.getString("id") ?: doc.id
            val deleted = doc.getBoolean("deleted") ?: false
            val remoteUpdatedAt = doc.getLong("updatedAt") ?: 0L
            val local = localMap[id]

            if (deleted) {
                if (local != null && remoteUpdatedAt >= local.updatedAt) {
                    dao.deleteById(id)
                }
                continue
            }

            val enc = doc.getString("enc")
            val remote: ProxyProfile? = if (!enc.isNullOrBlank()) {
                parseEncryptedProfile(id, remoteUpdatedAt, enc, uid, dek)
            } else {
                parseLegacyPlaintextProfile(id, remoteUpdatedAt, doc)?.also { legacyProfile ->
                    runCatching {
                        proxiesCollection(uid).document(id)
                            .set(toEncryptedFirestoreMap(legacyProfile, uid, dek))
                            .await()
                    }
                }
            }

            if (remote != null && (local == null || remoteUpdatedAt > local.updatedAt)) {
                dao.upsert(remote)
            }
        }

        val remaining = dao.getAllOnce()
        val activeId = settingsRepo.settings.value.activeProfileId
        if (activeId == null || remaining.none { it.id == activeId }) {
            settingsRepo.update { it.copy(activeProfileId = remaining.firstOrNull()?.id) }
        }
    }

    private fun parseEncryptedProfile(
        id: String,
        updatedAt: Long,
        enc: String,
        uid: String,
        dek: SecretKey
    ): ProxyProfile? = runCatching {
        val json = JSONObject(crypto.decryptString(enc, dek, "proxy:$uid:$id"))
        val host = json.optString("host").takeIf { it.isNotBlank() } ?: return null
        val protocolRaw = json.optString("protocol", ProxyProtocol.SOCKS5.name)
        val protocol = runCatching { ProxyProtocol.valueOf(protocolRaw) }.getOrDefault(ProxyProtocol.SOCKS5)
        val port = json.optInt("port", 1080).coerceIn(1, 65535)
        ProxyProfile(
            id = id,
            name = json.optString("name").ifBlank { "${protocol.name} · $host" },
            protocol = protocol,
            host = host,
            port = port,
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            updatedAt = updatedAt
        )
    }.getOrNull()

    private fun parseLegacyPlaintextProfile(
        id: String,
        updatedAt: Long,
        doc: DocumentSnapshot
    ): ProxyProfile? {
        val host = doc.getString("host")?.takeIf { it.isNotBlank() } ?: return null
        val protocolRaw = doc.getString("protocol") ?: ProxyProtocol.SOCKS5.name
        val protocol = runCatching { ProxyProtocol.valueOf(protocolRaw) }.getOrDefault(ProxyProtocol.SOCKS5)
        val port = (doc.getLong("port") ?: 1080L).toInt().coerceIn(1, 65535)
        return ProxyProfile(
            id = id,
            name = doc.getString("name") ?: "${protocol.name} · $host",
            protocol = protocol,
            host = host,
            port = port,
            username = doc.getString("username") ?: "",
            password = doc.getString("password") ?: "",
            updatedAt = updatedAt
        )
    }

    private fun proxiesCollection(uid: String) =
        firestore.collection("users").document(uid).collection("proxies")

    private fun toEncryptedFirestoreMap(p: ProxyProfile, uid: String, dek: SecretKey): Map<String, Any> {
        val json = JSONObject().apply {
            put("name", p.name)
            put("protocol", p.protocol.name)
            put("host", p.host)
            put("port", p.port)
            put("username", p.username)
            put("password", p.password)
        }.toString()
        val enc = crypto.encryptString(json, dek, "proxy:$uid:${p.id}")
        return mapOf(
            "id" to p.id,
            "v" to 2,
            "enc" to enc,
            "updatedAt" to p.updatedAt,
            "deleted" to false
        )
    }

    private fun markSyncedNow() {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        settingsRepo.update { it.copy(lastSyncTime = now) }
    }

    private fun stateFromUser(user: FirebaseUser?): CloudAccountState =
        if (user == null) {
            CloudAccountState()
        } else {
            CloudAccountState(
                uid = user.uid,
                email = user.email,
                displayName = user.displayName,
                photoUrl = user.photoUrl?.toString()
            )
        }
}
