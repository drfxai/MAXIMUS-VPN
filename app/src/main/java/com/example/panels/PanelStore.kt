package com.example.panels

import android.content.Context
import com.example.data.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

class PanelStore(context: Context) {
    private val secure = SecureStorage(context.applicationContext)

    fun load(): List<ManagedPanel> {
        val raw = secure.getAndDecrypt(KEY, "[]")
        val parsed = runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ManagedPanel(
                            id = o.getString("id"),
                            type = PanelType.valueOf(o.getString("type")),
                            name = o.optString("name"),
                            url = o.optString("url"),
                            username = o.optString("username"),
                            password = o.optString("password"),
                            apiToken = o.optString("apiToken"),
                            host = o.optString("host"),
                            sshPort = o.optInt("sshPort", 22),
                            hostKeySha256 = o.optString("hostKeySha256"),
                            accountId = o.optString("accountId"),
                            createdAt = o.optLong("createdAt", 0L),
                            vlessUuid = o.optString("vlessUuid"),
                            securePath = o.optString("securePath"),
                            healthNote = o.optString("healthNote"),
                            certSha256 = o.optString("certSha256")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        if (parsed.isEmpty()) {
            return emptyList()
        }
        return parsed
    }

    fun save(panel: ManagedPanel) {
        val all = load().filterNot { it.id == panel.id } + panel
        persist(all)
    }

    fun delete(id: String) {
        persist(load().filterNot { it.id == id })
    }

    private fun persist(panels: List<ManagedPanel>) {
        val arr = JSONArray()
        panels.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("type", it.type.name)
                put("name", it.name)
                put("url", it.url)
                put("username", it.username)
                put("password", it.password)
                put("apiToken", it.apiToken)
                put("host", it.host)
                put("sshPort", it.sshPort)
                put("hostKeySha256", it.hostKeySha256)
                put("accountId", it.accountId)
                put("createdAt", it.createdAt)
                put("vlessUuid", it.vlessUuid)
                put("securePath", it.securePath)
                put("healthNote", it.healthNote)
                put("certSha256", it.certSha256)
            })
        }
        secure.encryptAndSave(KEY, arr.toString())
    }

    companion object {
        private const val KEY = "managed_panels_v1"

        val defaultPanels = emptyList<ManagedPanel>()
    }
}
