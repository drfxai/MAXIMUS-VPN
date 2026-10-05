package com.example.vpn.warp

import android.content.Context
import com.example.data.model.VlessProfile
import com.example.data.security.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The free Cloudflare WARP profile. The device is registered once; the registration (device id,
 * token and private key) is kept encrypted with [SecureStorage] and reused on later calls.
 */
object WarpProvider {
    private const val STORAGE_KEY = "warp_registration_v1"

    private val lock = Mutex()

    @Volatile
    private var cached: WarpAccount? = null

    /** The WARP WireGuard profile, registering a device on first use. Throws IOException when that fails. */
    suspend fun profile(context: Context): VlessProfile = WarpRegistration.toProfile(account(context))

    suspend fun account(context: Context): WarpAccount = lock.withLock {
        cached ?: withContext(Dispatchers.IO) {
            val storage = SecureStorage(context.applicationContext)
            WarpRegistration.loadOrRegister(
                stored = storage.getAndDecrypt(STORAGE_KEY),
                save = { storage.encryptAndSave(STORAGE_KEY, it) },
                register = { WarpRegistration.register() }
            )
        }.also { cached = it }
    }

    /** Drops the saved registration, so the next [profile] registers a new device. */
    suspend fun forget(context: Context) = lock.withLock {
        cached = null
        withContext(Dispatchers.IO) { SecureStorage(context.applicationContext).encryptAndSave(STORAGE_KEY, "") }
    }
}
