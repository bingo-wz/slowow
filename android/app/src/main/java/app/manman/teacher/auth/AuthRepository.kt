package app.manman.teacher.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface SessionProvider {
    val session: StateFlow<AuthSession?>
    suspend fun restore()
    suspend fun currentAccessToken(accountId: String): String
}

class AuthRepository(
    private val client: CloudBaseAuthClient,
    private val store: SecureSessionStore,
) : SessionProvider {
    private val refreshMutex = Mutex()
    private val mutableSession = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = mutableSession
    private var restored = false
    var sessionReadError: Boolean = false
        private set

    override suspend fun restore() = refreshMutex.withLock {
        if (!restored) {
            try {
                mutableSession.value = withContext(Dispatchers.IO) { store.load() }
                sessionReadError = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                sessionReadError = true
            }
            restored = true
        }
    }

    suspend fun requestCode(phone: String): VerificationRequest = client.requestCode(phone)

    suspend fun signIn(request: VerificationRequest, code: String) = refreshMutex.withLock {
        val authenticated = client.verify(request, code)
        withContext(Dispatchers.IO) { store.save(authenticated) }
        mutableSession.value = authenticated
        sessionReadError = false
    }

    override suspend fun currentAccessToken(accountId: String): String = refreshMutex.withLock {
        val current = mutableSession.value ?: throw AuthException("not_signed_in", "请先登录。")
        if (current.accountId != accountId) throw AuthException("account_changed", "账号已切换。")
        if (current.expiresAtMillis > System.currentTimeMillis() + 60_000) return@withLock current.accessToken
        val refreshed = client.refresh(current)
        if (refreshed.accountId != current.accountId) {
            throw AuthException("account_changed", "登录状态异常，请重新登录。")
        }
        withContext(Dispatchers.IO) { store.save(refreshed) }
        mutableSession.value = refreshed
        refreshed.accessToken
    }

    suspend fun signOut(): Boolean = refreshMutex.withLock {
        val current = mutableSession.value ?: return@withLock true
        val remoteSucceeded = try {
            client.signOut(current)
            true
        } catch (_: AuthException) {
            false
        }
        withContext(Dispatchers.IO) { store.clear() }
        mutableSession.value = null
        remoteSucceeded
    }
}
