package com.example.panels.servers

import android.content.Context
import com.example.data.security.SecureStorage
import com.example.panels.PanelStore

/**
 * The user's servers, encrypted with the Android Keystore key of [SecureStorage]. 3X-UI panels
 * installed before servers existed appear once as servers abroad that need their password again.
 */
class ServerStore(context: Context) {
    private val app = context.applicationContext
    private val secure = SecureStorage(app)

    @Synchronized
    fun load(): List<ManagedServer> {
        if (secure.getAndDecrypt(MIGRATED_KEY) != "1") {
            val migrated = ServerMigration.fromPanels(PanelStore(app).load())
            persist(migrated)
            secure.encryptAndSave(MIGRATED_KEY, "1")
            return migrated
        }
        return ServerJson.decode(secure.getAndDecrypt(KEY, "[]")).sortedBy { it.createdAt }
    }

    @Synchronized
    fun save(server: ManagedServer) = persist(load().filterNot { it.id == server.id } + server)

    @Synchronized
    fun delete(id: String) = persist(load().filterNot { it.id == id })

    private fun persist(servers: List<ManagedServer>) = secure.encryptAndSave(KEY, ServerJson.encode(servers))

    companion object {
        private const val KEY = "managed_servers_v1"
        private const val MIGRATED_KEY = "managed_servers_migrated_v1"
    }
}
