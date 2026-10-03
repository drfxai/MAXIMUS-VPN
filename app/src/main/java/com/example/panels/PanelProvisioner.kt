package com.example.panels

import com.example.RayApplication
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PanelProvisioner(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    /**
     * Reads the SSH host key during key exchange only. No credentials are sent and
     * no remote command is executed. The returned fingerprint must be explicitly
     * confirmed/pinned before installation.
     */
    fun discoverSshHostKey(host: String, port: Int): String {
        require(host.isNotBlank()) { "Server IP/host is required" }
        require(port in 1..65535) { "Invalid SSH port" }
        val repo = DiscoveryHostKeyRepository()
        val session = JSch().apply { hostKeyRepository = repo }
            .getSession("__maximus_key_probe__", host.trim(), port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "none")
                timeout = 8_000
            }
        try {
            runCatching { session.connect(8_000) }
            return repo.observedFingerprint.takeIf { it.isNotBlank() }
                ?: error("SSH server did not present a host key")
        } finally {
            runCatching { session.disconnect() }
        }
    }

    fun installXui(
        request: XuiInstallRequest,
        onLog: (String) -> Unit = {}
    ): ManagedPanel {
        require(request.host.isNotBlank()) { "Server IP/host is required" }
        require(request.username.isNotBlank()) { "SSH username is required" }
        require(request.password.isNotBlank()) { "SSH password is required" }
        require(request.sshPort in 1..65535) { "Invalid SSH port" }

        val storedFingerprint = runCatching {
            PanelStore(RayApplication.instance).load()
                .firstOrNull { it.host.equals(request.host.trim(), ignoreCase = true) && it.sshPort == request.sshPort }
                ?.hostKeySha256.orEmpty()
        }.getOrDefault("")
        val expectedFingerprint = request.expectedHostKeySha256.ifBlank { storedFingerprint }
        require(expectedFingerprint.isNotBlank()) {
            "Verify the server SHA256 host-key fingerprint out of band before connecting"
        }
        val cleanFp = expectedFingerprint.removePrefix("SHA256:").trim()
        require(cleanFp.matches(Regex("[A-Za-z0-9+/=_-]{16,64}"))) { "Invalid SSH SHA256 fingerprint format" }

        onLog("[SSH] Verifying pinned host key for ${request.host}:${request.sshPort}")
        val pinRepo = PinningHostKeyRepository(expectedFingerprint)
        val jsch = JSch().apply { hostKeyRepository = pinRepo }
        val session = jsch.getSession(request.username.trim(), request.host.trim(), request.sshPort).apply {
            setPassword(request.password)
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            timeout = 20_000
        }

        try {
            session.connect(20_000)
            check(pinRepo.observedFingerprint.isNotBlank()) { "SSH host key was not presented" }
            onLog("[SSH] Host key verified: ${pinRepo.observedFingerprint}")

            val installerUrl =
                "https://raw.githubusercontent.com/MHSanaei/3x-ui/7ef22f94c950ff09f0870e2295fa65ad5968742c/install.sh"
            val command = """
                set -eu
                umask 077
                tmp=$(mktemp /tmp/maximus-3xui.XXXXXX)
                trap 'rm -f "${'$'}tmp"' EXIT
                if command -v curl >/dev/null 2>&1; then
                  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 "$installerUrl" -o "${'$'}tmp"
                elif command -v wget >/dev/null 2>&1; then
                  wget --https-only -qO "${'$'}tmp" "$installerUrl"
                else
                  echo 'curl or wget is required' >&2
                  exit 127
                fi
                XUI_NONINTERACTIVE=1 bash "${'$'}tmp" v3.8.5
                test -s /etc/x-ui/install-result.env
                cat /etc/x-ui/install-result.env
            """.trimIndent()

            onLog("[3X-UI] Installing signed release v3.8.5 in unattended mode")
            val output = exec(session, command, 8 * 60_000L)
            val env = parseEnv(output)
            val username = env["XUI_USERNAME"].orEmpty()
            val password = env["XUI_PASSWORD"].orEmpty()
            val panelPort = env["XUI_PANEL_PORT"]?.toIntOrNull()
                ?: error("3x-ui installer did not return panel port")
            val basePath = env["XUI_WEB_BASE_PATH"].orEmpty().ifBlank { "/" }
            val apiToken = env["XUI_API_TOKEN"].orEmpty()
            require(username.isNotBlank() && password.isNotBlank()) {
                "3x-ui installer did not return generated credentials"
            }
            require(panelPort in 1..65535) { "3x-ui returned an invalid panel port" }

            val normalizedPath = "/" + basePath.trim('/').let { if (it.isBlank()) "" else "$it/" }
            val accessUrl = env["XUI_ACCESS_URL"].orEmpty()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: "http://${request.host.trim()}:$panelPort$normalizedPath"

            onLog("[3X-UI] Installation completed • panel port $panelPort")
            return ManagedPanel(
                id = UUID.randomUUID().toString(),
                type = PanelType.XUI,
                name = "3X-UI • ${request.host.trim()}",
                url = accessUrl,
                username = username,
                password = password,
                apiToken = apiToken,
                host = request.host.trim(),
                sshPort = request.sshPort,
                hostKeySha256 = pinRepo.observedFingerprint
            )
        } finally {
            runCatching { session.disconnect() }
        }
    }

    fun deployBpbWorker(
        request: CloudflareInstallRequest,
        onLog: (String) -> Unit = {}
    ): ManagedPanel {
        val token = request.apiToken.trim()
        require(token.isNotBlank()) { "Cloudflare API token is required" }

        onLog("[CLOUDFLARE] Verifying API token")
        val verify = cfJson(
            Request.Builder()
                .url("https://api.cloudflare.com/client/v4/user/tokens/verify")
                .header("Authorization", "Bearer $token")
                .get().build()
        )
        check(verify.optJSONObject("result")?.optString("status") == "active") { "Cloudflare API token is not active" }

        val accounts = cfJson(
            Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts?per_page=50")
                .header("Authorization", "Bearer $token")
                .get().build()
        ).optJSONArray("result") ?: JSONArray()
        val accountId = request.accountId.trim().ifBlank {
            require(accounts.length() == 1) {
                "Multiple Cloudflare accounts are accessible. Enter the Account ID explicitly."
            }
            accounts.getJSONObject(0).getString("id")
        }
        require(accountId.matches(Regex("[a-fA-F0-9]{32}"))) { "Invalid Cloudflare Account ID" }
        check((0 until accounts.length()).any { accounts.optJSONObject(it)?.optString("id") == accountId }) {
            "The token cannot access the selected Cloudflare account"
        }

        // Workers/KV deployment tokens do not need User Details Read. Requiring
        // GET /user here caused otherwise-valid least-privilege tokens to fail with 403.
        // BPB only needs the email as a setting; use the optional user-entered value and
        // a non-secret placeholder when it was intentionally omitted.
        val accountEmail = request.accountEmail.trim()
            .takeIf { it.isNotBlank() }
            ?: "maximus@$accountId.invalid"

        val workerName = "${request.panelNamePrefix.take(12).lowercase().replace(Regex("[^a-z0-9-]"), "-")}-${randomLower(12)}"
            .trim('-')
        onLog("[CLOUDFLARE] Creating isolated KV namespace")
        val kvPayload = JSONObject().put("title", "$workerName-worker-${System.currentTimeMillis()}")
        val kv = cfJson(
            Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/$accountId/storage/kv/namespaces")
                .header("Authorization", "Bearer $token")
                .post(kvPayload.toString().toRequestBody(JSON))
                .build()
        )
        val namespaceId = kv.optJSONObject("result")?.optString("id").orEmpty()
        require(namespaceId.isNotBlank()) { "Cloudflare did not return a KV namespace ID" }

        try {
            val subdomain = getOrCreateWorkersSubdomain(accountId, token)
            onLog("[BPB] Downloading stable BPB v5.1.1 worker from the official release")
            val upstream = http.newCall(
                Request.Builder()
                    .url("https://github.com/bia-pain-bache/BPB-Worker-Panel/releases/download/v5.1.1/worker.js")
                    .header("Accept", "application/octet-stream")
                    .get().build()
            ).execute().use { response ->
                check(response.isSuccessful) {
                    "Unable to download BPB worker (HTTP ${response.code}, final host ${response.request.url.host})"
                }
                response.body?.string().orEmpty()
            }
            require(upstream.length > 1024) { "Downloaded BPB worker is invalid" }

            val securePath = randomFrom("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_", 16)
            val trojanPass = randomFrom("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@&*_-+", 20)
            val settings = JSONObject()
                .put("accID", accountId)
                .put("accEmail", accountEmail.lowercase())
                .put("apiToken", token)
                .put("vlUUID", UUID.randomUUID().toString())
                .put("trPass", trojanPass)
                .put("securePath", securePath)
                .put("proxyIpMode", "proxyip")
                .put("proxyIPs", JSONArray())
                .put("prefixes", JSONArray())
                .put("fallback", "")
                .put("dohUrl", "")
                .put("mainDomain", "$workerName.$subdomain")

            // BPB 5.1.x is Wizard-only and reads EMBEDED_SETTINGS. A per-install nonce
            // makes the uploaded module unique, matching the official Wizard build flow.
            val workerSource = "// Maximus BPB build ${UUID.randomUUID()}\n" +
                "// @ts-nocheck\nconst EMBEDED_SETTINGS = ${settings};\n" + upstream
            val metadata = JSONObject()
                .put("main_module", "worker.js")
                .put("compatibility_date", java.time.LocalDate.now().toString())
                .put("compatibility_flags", JSONArray().put("nodejs_compat"))
                .put("bindings", JSONArray().put(
                    JSONObject()
                        .put("type", "kv_namespace")
                        .put("name", "kv")
                        .put("namespace_id", namespaceId)
                ))

            onLog("[CLOUDFLARE] Uploading Worker module and KV binding")
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "metadata", null,
                    metadata.toString().toRequestBody("application/json".toMediaType())
                )
                .addFormDataPart(
                    "worker.js", "worker.js",
                    workerSource.toRequestBody("application/javascript+module".toMediaType())
                )
                .build()
            cfJson(
                Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName")
                    .header("Authorization", "Bearer $token")
                    .put(multipart)
                    .build()
            )

            val enableBody = JSONObject().put("enabled", true).put("previews_enabled", true)
            cfJson(
                Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$workerName/subdomain")
                    .header("Authorization", "Bearer $token")
                    .post(enableBody.toString().toRequestBody(JSON))
                    .build()
            )

            val panelUrl = "https://$workerName.$subdomain/$securePath/panel"
            onLog("[BPB] Worker deployed successfully")
            return ManagedPanel(
                id = UUID.randomUUID().toString(),
                type = PanelType.BPB_WORKER,
                name = "BPB Worker • $workerName",
                url = panelUrl,
                username = accountEmail.lowercase(),
                password = request.panelPassword,
                apiToken = token,
                accountId = accountId,
                host = "$workerName.$subdomain"
            )
        } catch (t: Throwable) {
            // Best-effort rollback of the namespace created by this failed attempt.
            runCatching {
                http.newCall(
                    Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/accounts/$accountId/storage/kv/namespaces/$namespaceId")
                        .header("Authorization", "Bearer $token")
                        .delete().build()
                ).execute().close()
            }
            throw t
        }
    }
    fun createQuickConfig(panel: ManagedPanel): QuickConfigResult {
        require(panel.type == PanelType.XUI) { "Quick config is supported for 3X-UI panels" }
        require(panel.host.isNotBlank() && panel.apiToken.isNotBlank() && panel.url.isNotBlank()) {
            "A configured 3X-UI panel with an API token is required."
        }

        val uuid = UUID.randomUUID().toString()
        val port = Random.nextInt(20000, 60000)
        val remark = generate3xuiRemark()
        val base = panel.url.trimEnd('/').removeSuffix("/panel")

        // Match the current 3X-UI default VLESS/RAW/none payload. 3X-UI now
        // prefers nested JSON objects; stringified legacy fields can produce a
        // database entry that does not match the running Xray configuration.
        val settings = JSONObject()
            .put("clients", JSONArray().put(
                JSONObject()
                    .put("id", uuid)
                    .put("flow", "")
                    .put("email", remark)
                    .put("limitIp", 0)
                    .put("totalGB", 0)
                    .put("expiryTime", 0)
                    .put("enable", true)
                    .put("tgId", "")
                    .put("subId", randomLower(16))
                    .put("comment", "")
                    .put("reset", 0)
            ))
            .put("decryption", "none")
            .put("fallbacks", JSONArray())

        val streamSettings = JSONObject()
            .put("network", "tcp")
            .put("security", "none")
            .put("tcpSettings", JSONObject()
                .put("acceptProxyProtocol", false)
                .put("header", JSONObject().put("type", "none")))

        val sniffing = JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
            .put("metadataOnly", false)
            .put("routeOnly", false)

        val body = JSONObject()
            .put("remark", remark)
            .put("enable", true)
            .put("listen", "")
            .put("port", port)
            .put("protocol", "vless")
            .put("expiryTime", 0)
            .put("total", 0)
            .put("settings", settings)
            .put("streamSettings", streamSettings)
            .put("sniffing", sniffing)

        val response = panelPost(base, panel.apiToken, "/panel/api/inbounds/add", body)
        check(response.optBoolean("success", false)) {
            response.optString("msg", "3x-ui rejected the inbound")
        }

        // Never invent a client URI after an API write. Read the canonical link
        // back from 3X-UI so the app uses exactly the identity/config Xray runs.
        val linksResponse = panelJson(base, panel.apiToken, "/panel/api/inbounds/allLinks")
        check(linksResponse.optBoolean("success", false)) {
            linksResponse.optString("msg", "3x-ui could not export the new inbound")
        }
        val links = linksResponse.optJSONArray("obj") ?: JSONArray()
        var canonicalUri = ""
        for (i in 0 until links.length()) {
            val candidate = links.optString(i)
            if (candidate.startsWith("vless://") &&
                (candidate.contains(uuid, ignoreCase = true) ||
                 candidate.substringAfter('#', "").contains(remark, ignoreCase = true))) {
                canonicalUri = candidate
                break
            }
        }
        check(canonicalUri.isNotBlank()) {
            "3x-ui created the inbound but did not expose a matching runtime client link"
        }

        return QuickConfigResult(canonicalUri, remark)
    }

    private fun generate3xuiRemark(): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val rnd = Random
        val tag = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        return "X-$tag"
    }

    fun createQuickRealityConfig(panel: ManagedPanel): QuickConfigResult {
        require(panel.type == PanelType.XUI) { "Quick config is supported for 3x-ui panels" }
        require(panel.apiToken.isNotBlank()) { "This panel does not have an API token" }
        val base = panel.url.trimEnd('/').removeSuffix("/panel")
        require(base.startsWith("https://")) { "Panel API requires HTTPS" }
        val keyObj = panelJson(base, panel.apiToken, "/panel/api/server/getNewX25519Cert")
            .optJSONObject("obj") ?: error("3x-ui did not return a Reality keypair")

        val uuid = UUID.randomUUID().toString()
        val shortId = randomHex(8)
        val port = Random.nextInt(20000, 45000)
        val body = JSONObject().apply {
            put("remark", "Maximus-Reality-$port")
            put("enable", true)
            put("port", port)
            put("protocol", "vless")
            put("expiryTime", 0)
            put("total", 0)
            put(
                "settings",
                JSONObject()
                    .put("decryption", "none")
                    .put(
                        "clients",
                        JSONArray().put(
                            JSONObject()
                                .put("id", uuid)
                                .put("flow", "xtls-rprx-vision")
                                .put("email", "maximus-${randomLower(6)}")
                                .put("enable", true)
                        )
                    )
            )
            put(
                "streamSettings",
                JSONObject()
                    .put("network", "tcp")
                    .put("security", "reality")
                    .put(
                        "realitySettings",
                        JSONObject()
                            .put("show", false)
                            .put("dest", "www.microsoft.com:443")
                            .put("xver", 0)
                            .put("serverNames", JSONArray().put("www.microsoft.com"))
                            .put("privateKey", keyObj.optString("privateKey"))
                            .put("shortIds", JSONArray().put(shortId))
                    )
            )
            put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
            )
        }

        for (field in listOf("settings", "streamSettings", "sniffing")) {
            body.put(field, body.getJSONObject(field).toString())
        }
        val added = panelPost(base, panel.apiToken, "/panel/api/inbounds/add", body)
        check(added.optBoolean("success")) { added.optString("msg", "3x-ui rejected the inbound") }

        val links = runCatching {
            panelJson(base, panel.apiToken, "/panel/api/inbounds/allLinks").optJSONArray("obj")
        }.getOrNull() ?: JSONArray()
        var uri = ""
        for (i in 0 until links.length()) {
            val candidate = links.optString(i)
            if (candidate.contains(uuid)) {
                uri = candidate
                break
            }
        }
        if (uri.isBlank()) {
            val pbk = keyObj.optString("publicKey")
            uri = "vless://$uuid@${panel.host}:$port?type=tcp&security=reality&pbk=$pbk&fp=chrome&sni=www.microsoft.com&sid=$shortId&spx=%2F&flow=xtls-rprx-vision#Maximus-Reality-$port"
        }
        return QuickConfigResult(uri, "Maximus Reality")
    }

    private fun panelJson(base: String, token: String, path: String) =
        json(Request.Builder().url(base + path).header("Authorization", "Bearer $token").get().build())

    private fun panelPost(base: String, token: String, path: String, payload: JSONObject) =
        json(
            Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(JSON))
                .build()
        )

    private fun workerExists(accountId: String, token: String, name: String): Boolean =
        http.newCall(
            Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/$accountId/workers/scripts/$name")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        ).execute().use { it.isSuccessful }

    private fun getOrCreateWorkersSubdomain(accountId: String, token: String): String {
        val get = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching { cfJson(get) }.getOrNull()?.optJSONObject("result")?.optString("subdomain")
            ?.takeIf { it.isNotBlank() }?.let { return "$it.workers.dev" }

        repeat(3) {
            val candidate = "maximus-${randomLower(10)}"
            val res = cfJson(
                Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain")
                    .header("Authorization", "Bearer $token")
                    .put(JSONObject().put("subdomain", candidate).toString().toRequestBody(JSON))
                    .build()
            )
            if (res.optBoolean("success")) {
                val sub = res.optJSONObject("result")?.optString("subdomain").orEmpty()
                return "${if (sub.isBlank()) candidate else sub}.workers.dev"
            }
        }
        error("Unable to create a workers.dev subdomain")
    }

    private fun exec(session: Session, command: String, timeoutMs: Long): String {
        val channel = session.openChannel("exec") as ChannelExec
        val output = java.io.ByteArrayOutputStream()
        channel.setCommand(command)
        channel.setInputStream(null)
        val stdout = channel.inputStream
        val stderr = channel.errStream
        try {
            channel.connect(10_000)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            val buffer = ByteArray(8192)
            while (true) {
                for (stream in listOf(stdout, stderr)) {
                    while (stream.available() > 0) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, stream.available()))
                        if (count > 0) {
                            check(output.size() + count < 4 * 1024 * 1024) { "Installer output exceeded limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (channel.isClosed && stdout.available() == 0 && stderr.available() == 0) break
                check(System.nanoTime() < deadline) { "Remote installation timed out; check server before retrying" }
                Thread.sleep(50)
            }
            check(channel.exitStatus == 0) { "Remote installer failed; inspect server installation logs" }
            return output.toString("UTF-8")
        } finally {
            channel.disconnect()
        }
    }

    private fun parseEnv(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0 || !line.startsWith("XUI_")) null
            else line.substring(0, i) to line.substring(i + 1).trim().trim('\'', '"')
        }.toMap()

    private fun cfJson(request: Request): JSONObject =
        json(request).also { if (!it.optBoolean("success")) error(cloudflareError(it)) }

    private fun json(request: Request): JSONObject =
        http.newCall(request).execute().use {
            val text = it.body?.string().orEmpty()
            check(it.isSuccessful) { "HTTP ${it.code}: check API permissions and panel availability" }
            JSONObject(text)
        }

    private fun cloudflareError(o: JSONObject) =
        o.optJSONArray("errors")?.toString() ?: "Cloudflare API request failed"

    private fun randomLower(n: Int) = randomFrom("abcdefghijklmnopqrstuvwxyz0123456789", n)

    private fun randomHex(bytes: Int) =
        ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private fun randomFrom(chars: String, n: Int): String {
        val random = SecureRandom()
        return buildString(n) { repeat(n) { append(chars[random.nextInt(chars.length)]) } }
    }

    private class DiscoveryHostKeyRepository : HostKeyRepository {
        @Volatile var observedFingerprint: String = ""
            private set

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.CHANGED
            val fp = Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
            observedFingerprint = "SHA256:$fp"
            // Accept only for the unauthenticated discovery session. The real
            // installer creates a separate session with PinningHostKeyRepository.
            return HostKeyRepository.OK
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "Maximus SSH key discovery"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private class PinningHostKeyRepository(expected: String) : HostKeyRepository {
        private val expectedNormalized = expected.removePrefix("SHA256:").trim()
        @Volatile var observedFingerprint: String = ""
            private set

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.CHANGED
            val fp = Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
            observedFingerprint = "SHA256:$fp"
            return if (expectedNormalized.isNotBlank() && MessageDigest.isEqual(expectedNormalized.toByteArray(Charsets.US_ASCII), fp.toByteArray(Charsets.US_ASCII))) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "Maximus pinned host keys"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
