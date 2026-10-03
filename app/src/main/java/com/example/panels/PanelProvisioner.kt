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
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class PanelProvisioner(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    /** Pause between readiness checks after an install (zero in unit tests). */
    private val retryDelayMs: Long = 3_000L,
    /** Connect/auth timeout of each SSH attempt; the last attempt uses the default algorithm set. */
    private val sshAttemptTimeoutsMs: List<Int> = listOf(25_000, 40_000, 60_000)
) {
    /**
     * Reads the SSH host key during key exchange only. No credentials are sent and
     * no remote command is executed. The returned fingerprint must be explicitly
     * confirmed/pinned before installation.
     */
    fun discoverSshHostKey(host: String, port: Int): String {
        require(host.isNotBlank()) { "Server IP/host is required" }
        require(port in 1..65535) { "Invalid SSH port" }
        // Key exchange can be slow on busy servers or mobile networks; retry once with more time.
        for (timeoutMs in listOf(10_000, 20_000)) {
            val repo = DiscoveryHostKeyRepository()
            val session = JSch().apply { hostKeyRepository = repo }
                .getSession("__maximus_key_probe__", host.trim(), port).apply {
                    setConfig("StrictHostKeyChecking", "yes")
                    setConfig("PreferredAuthentications", "none")
                    timeout = timeoutMs
                }
            try {
                runCatching { session.connect(timeoutMs) }
                repo.observedFingerprint.takeIf { it.isNotBlank() }?.let { return it }
            } finally {
                runCatching { session.disconnect() }
            }
        }
        error("The server did not present an SSH host key. Check the IP address, port and firewall.")
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

        val host = request.host.trim()
        onLog("[SSH] Checking that $host:${request.sshPort} accepts connections")
        preflightTcp(host, request.sshPort)

        onLog("[SSH] Verifying pinned host key for $host:${request.sshPort}")
        val pinRepo = PinningHostKeyRepository(expectedFingerprint)
        val session = connectSsh(request, pinRepo, onLog)

        try {
            check(pinRepo.observedFingerprint.isNotBlank()) { "SSH host key was not presented" }
            onLog("[SSH] Host key verified and password accepted: ${pinRepo.observedFingerprint}")

            onLog("[3X-UI] Installing signed release $XUI_VERSION in unattended mode")
            val output = exec(session, buildInstallCommand(host), 15 * 60_000L) { line ->
                val clean = line.trim()
                if (clean.isNotEmpty() && !SECRET_LINE.containsMatchIn(clean) && !clean.startsWith("XUI_")) {
                    onLog("[3X-UI] ${clean.take(110)}")
                }
            }
            val env = parseEnv(output.substringAfter(RESULT_MARKER, ""))
            val username = env["XUI_USERNAME"].orEmpty()
            val password = env["XUI_PASSWORD"].orEmpty()
            val panelPort = env["XUI_PANEL_PORT"]?.toIntOrNull()
                ?: error("3x-ui installer did not return the panel port")
            val basePath = env["XUI_WEB_BASE_PATH"].orEmpty().ifBlank { "/" }
            val apiToken = env["XUI_API_TOKEN"].orEmpty()
            require(username.isNotBlank() && password.isNotBlank()) {
                "3x-ui installer did not return generated credentials"
            }
            require(panelPort in 1..65535) { "3x-ui returned an invalid panel port" }

            val normalizedPath = "/" + basePath.trim('/').let { if (it.isBlank()) "" else "$it/" }
            val reported = env["XUI_ACCESS_URL"].orEmpty()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val certSha = PinnedTls.normalize(env["XUI_CERT_SHA256"].orEmpty())
                .takeIf { it.matches(Regex("[0-9a-f]{64}")) }.orEmpty()
            val reportedScheme = reported?.substringBefore("://") ?: "http"
            // The self-signed certificate created during install only applies to panels that were
            // plain HTTP; a panel that already had a real certificate keeps it.
            val scheme = if (certSha.isNotBlank() && reportedScheme == "http") "https" else reportedScheme
            val pinned = if (reportedScheme == "http") certSha else ""
            // Always address the server the way the user reached it (NAT/private-IP safe).
            val accessUrl = "$scheme://${hostForUrl(host)}:$panelPort$normalizedPath"
            onLog("[3X-UI] Installation completed • panel port $panelPort")
            if (scheme == "https" && pinned.isNotBlank()) onLog("[3X-UI] HTTPS enabled with a pinned certificate")
            if (scheme == "http") onLog("[WARN] Panel is plain HTTP; Android blocks it, so in-app config creation will not work")

            val note = verifyXuiPanel(accessUrl, apiToken, username, password, panelPort, pinned, onLog)
            return ManagedPanel(
                id = UUID.randomUUID().toString(),
                type = PanelType.XUI,
                name = "3X-UI • $host",
                url = accessUrl,
                username = username,
                password = password,
                apiToken = apiToken,
                host = host,
                sshPort = request.sshPort,
                hostKeySha256 = pinRepo.observedFingerprint,
                healthNote = note,
                certSha256 = pinned
            )
        } finally {
            runCatching { session.disconnect() }
        }
    }

    private fun hostForUrl(host: String) =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    /** Waits for the panel API to answer from this device and reports the outcome. */
    private fun verifyXuiPanel(
        accessUrl: String,
        apiToken: String,
        username: String,
        password: String,
        panelPort: Int,
        pinnedCertSha256: String,
        onLog: (String) -> Unit
    ): String {
        onLog("[3X-UI] Waiting for the panel API")
        val api = XuiApiClient(accessUrl, apiToken, username, password, http, pinnedCertSha256)
        var lastError = ""
        repeat(10) { attempt ->
            try {
                api.requireAccess()
                onLog("[3X-UI] Panel API verified and ready")
                return "Panel API verified"
            } catch (e: Exception) {
                lastError = e.message.orEmpty()
                if (attempt < 9) Thread.sleep(retryDelayMs)
            }
        }
        onLog("[WARN] Panel API check failed: ${lastError.take(110)}")
        return when {
            lastError.contains("plain HTTP", ignoreCase = true) ->
                "Panel installed, but it still answers over plain HTTP. Tap Retry Installation to repair HTTPS."
            lastError.contains("firewall", ignoreCase = true) ->
                "Panel installed, but TCP $panelPort is not reachable from this device. Open it in the VPS/cloud firewall."
            else -> "Panel installed, but its API did not answer: ${lastError.take(110)}"
        }
    }

    private fun preflightTcp(host: String, port: Int) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 10_000) }
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("Cannot resolve '$host'. Check the server IP address.")
        } catch (e: java.io.IOException) {
            throw IllegalStateException(
                "TCP $host:$port is not reachable from this device (${e.message}). " +
                    "Check the IP address, the SSH port and the cloud firewall."
            )
        }
    }

    /**
     * Opens the SSH session. A short password-auth handshake can stall on some servers/networks
     * (slow PAM/DNS, large key-exchange packets), so it is retried with growing timeouts and, on the
     * last attempt, the library's default algorithm set. Authentication and host-key errors are
     * never retried.
     */
    private fun connectSsh(
        request: XuiInstallRequest,
        pinRepo: PinningHostKeyRepository,
        onLog: (String) -> Unit
    ): Session {
        data class Attempt(val timeoutMs: Int, val slimKex: Boolean)
        val attempts = sshAttemptTimeoutsMs.mapIndexed { i, ms -> Attempt(ms, slimKex = i < sshAttemptTimeoutsMs.lastIndex) }
        var last: Exception? = null
        attempts.forEachIndexed { index, attempt ->
            if (index > 0) {
                onLog("[SSH] Handshake did not finish, retrying (${index + 1}/${attempts.size})")
                Thread.sleep(minOf(2_000L * index, retryDelayMs.coerceAtLeast(0L) * index))
            } else {
                Thread.sleep(minOf(1_000L, retryDelayMs.coerceAtLeast(0L)))
            }
            val session = JSch().apply { hostKeyRepository = pinRepo }
                .getSession(request.username.trim(), request.host.trim(), request.sshPort).apply {
                    setPassword(request.password)
                    setConfig("StrictHostKeyChecking", "yes")
                    setConfig("PreferredAuthentications", "password,keyboard-interactive")
                    if (attempt.slimKex) {
                        setConfig(
                            "kex",
                            "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256," +
                                "diffie-hellman-group14-sha256,diffie-hellman-group-exchange-sha256"
                        )
                    }
                    setServerAliveInterval(15_000)
                    setServerAliveCountMax(8)
                    timeout = attempt.timeoutMs
                }
            try {
                session.connect(attempt.timeoutMs)
                // The connect timeout must not stay as the socket read timeout: the installer is
                // silent for minutes and would otherwise kill the session with "Read timed out".
                runCatching { session.timeout = 0 }
                return session
            } catch (e: Exception) {
                runCatching { session.disconnect() }
                last = e
                val msg = e.message.orEmpty()
                when {
                    msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
                        throw IllegalStateException(
                            "SSH login failed: wrong username/password, or this server does not allow password login for '${request.username.trim()}'."
                        )
                    msg.contains("HostKey", true) || msg.contains("reject", true) && msg.contains("key", true) ->
                        throw IllegalStateException(
                            "The server's SSH host key does not match the verified fingerprint. Run Test Connection again and verify the fingerprint."
                        )
                }
            }
        }
        throw IllegalStateException(
            "The server accepts connections on port ${request.sshPort} but the SSH login did not complete " +
                "(${last?.message.orEmpty().take(80)}). Check that password login is enabled for " +
                "'${request.username.trim()}' and that the server is not rate-limiting SSH."
        )
    }

    private fun buildInstallCommand(host: String): String {
        val url = XUI_INSTALLER_URL
        val isIp = host.matches(Regex("[0-9.]+")) || host.contains(':')
        val san = if (isIp) "IP.1 = $host" else "DNS.1 = $host"
        return """
            set -eu
            umask 077
            SUDO=""
            if [ "§(id -u)" -ne 0 ]; then
              if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then SUDO="sudo -n"
              else echo 'root access or passwordless sudo is required' >&2; exit 20; fi
            fi
            if §SUDO test -s /etc/x-ui/install-result.env && §SUDO systemctl is-active --quiet x-ui 2>/dev/null; then
              echo 'Existing 3X-UI installation detected, reusing it'
            else
              if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
                if command -v apt-get >/dev/null 2>&1; then
                  §SUDO apt-get update -y >/dev/null 2>&1 || true
                  §SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates >/dev/null 2>&1 || true
                elif command -v dnf >/dev/null 2>&1; then §SUDO dnf install -y curl ca-certificates >/dev/null 2>&1 || true
                elif command -v yum >/dev/null 2>&1; then §SUDO yum install -y curl ca-certificates >/dev/null 2>&1 || true
                elif command -v apk >/dev/null 2>&1; then §SUDO apk add --no-cache curl ca-certificates >/dev/null 2>&1 || true
                fi
              fi
              tmp=§(mktemp /tmp/maximus-3xui.XXXXXX)
              trap 'rm -f "§tmp"' EXIT
              if command -v curl >/dev/null 2>&1; then
                curl --fail --silent --show-error --location --retry 3 --proto '=https' --tlsv1.2 "$url" -o "§tmp"
              elif command -v wget >/dev/null 2>&1; then
                wget --https-only -qO "§tmp" "$url"
              else
                echo 'curl or wget is required' >&2
                exit 127
              fi
              §SUDO env XUI_NONINTERACTIVE=1 bash "§tmp" $XUI_VERSION
            fi
            §SUDO test -s /etc/x-ui/install-result.env
            # Open the panel port and the usual proxy ports when a host firewall is active.
            XUI_PANEL_PORT=§(§SUDO sed -n 's/^XUI_PANEL_PORT=//p' /etc/x-ui/install-result.env | head -n1 | tr -d "'\"")
            for p in "§XUI_PANEL_PORT" 443 8443 8080 8880 2052 2082 2086 2095; do
              if command -v ufw >/dev/null 2>&1 && §SUDO ufw status 2>/dev/null | grep -q 'Status: active'; then
                §SUDO ufw allow "§p"/tcp >/dev/null 2>&1 || true
              fi
              if command -v firewall-cmd >/dev/null 2>&1 && §SUDO firewall-cmd --state >/dev/null 2>&1; then
                §SUDO firewall-cmd --permanent --add-port="§p"/tcp >/dev/null 2>&1 || true
              fi
            done
            if command -v firewall-cmd >/dev/null 2>&1; then §SUDO firewall-cmd --reload >/dev/null 2>&1 || true; fi
            # Android refuses plain-HTTP panels, so serve the panel over HTTPS with a certificate that
            # is created here (over this authenticated SSH session) and pinned by the app.
            ACCESS_URL=§(§SUDO sed -n 's/^XUI_ACCESS_URL=//p' /etc/x-ui/install-result.env | head -n1)
            CERT_DIR=/etc/x-ui/maximus-tls
            # The x-ui on PATH is the management script; certificate flags belong to the panel binary.
            XUI_BIN=/usr/local/x-ui/x-ui
            if ! §SUDO test -x "§XUI_BIN"; then
              XUI_BIN=§(§SUDO systemctl show -p ExecStart x-ui 2>/dev/null | sed -n 's/.*path=\([^ ;]*\).*/\1/p' | head -n1)
            fi
            served_fp() {
              echo | timeout 8 openssl s_client -connect "127.0.0.1:§XUI_PANEL_PORT" 2>/dev/null \
                | openssl x509 -noout -fingerprint -sha256 2>/dev/null | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f'
            }
            restart_panel() {
              if command -v systemctl >/dev/null 2>&1; then §SUDO systemctl restart x-ui >/dev/null 2>&1 || true
              elif command -v rc-service >/dev/null 2>&1; then §SUDO rc-service x-ui restart >/dev/null 2>&1 || true
              fi
            }
            SERVED_FP=""
            case "§ACCESS_URL" in
              https:*) echo 'Panel already uses HTTPS, keeping its certificate' ;;
              *)
                if ! command -v openssl >/dev/null 2>&1; then
                  if command -v apt-get >/dev/null 2>&1; then §SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y openssl >/dev/null 2>&1 || true
                  elif command -v dnf >/dev/null 2>&1; then §SUDO dnf install -y openssl >/dev/null 2>&1 || true
                  elif command -v yum >/dev/null 2>&1; then §SUDO yum install -y openssl >/dev/null 2>&1 || true
                  elif command -v apk >/dev/null 2>&1; then §SUDO apk add --no-cache openssl >/dev/null 2>&1 || true
                  fi
                fi
                if command -v openssl >/dev/null 2>&1; then
                  SERVED_FP=§(served_fp)
                  if [ -z "§SERVED_FP" ]; then
                    echo 'Enabling HTTPS on the panel'
                    if ! §SUDO test -s §CERT_DIR/panel.crt || ! §SUDO test -s §CERT_DIR/panel.key; then
                      §SUDO mkdir -p §CERT_DIR && §SUDO chmod 700 §CERT_DIR
                      cnf=§(mktemp /tmp/maximus-cert.XXXXXX)
                      printf '[req]\ndistinguished_name = dn\nx509_extensions = v3\nprompt = no\n[dn]\nCN = Maximus Panel\n[v3]\nsubjectAltName = @alt\n[alt]\n$san\n' > "§cnf"
                      §SUDO openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout §CERT_DIR/panel.key -out §CERT_DIR/panel.crt -config "§cnf" >/dev/null 2>&1 || true
                      rm -f "§cnf"
                      §SUDO chmod 600 §CERT_DIR/panel.key 2>/dev/null || true
                    fi
                    if [ -n "§XUI_BIN" ] && §SUDO test -s §CERT_DIR/panel.crt; then
                      §SUDO "§XUI_BIN" cert -webCert §CERT_DIR/panel.crt -webCertKey §CERT_DIR/panel.key 2>&1 | tail -n 3 || true
                      restart_panel
                      for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
                        sleep 2
                        SERVED_FP=§(served_fp)
                        if [ -n "§SERVED_FP" ]; then break; fi
                      done
                    fi
                  fi
                  if [ -n "§SERVED_FP" ]; then echo 'Panel is serving HTTPS'; else echo 'HTTPS could not be enabled on the panel' >&2; fi
                fi
                ;;
            esac
            echo '$RESULT_MARKER'
            §SUDO cat /etc/x-ui/install-result.env
            # Fingerprint of the certificate the panel actually serves (not just the file on disk).
            if [ -n "§SERVED_FP" ]; then echo "XUI_CERT_SHA256=§SERVED_FP"; fi
        """.trimIndent().replace('§', '$')
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

        // BPB v5 signs the web panel in with (accEmail, KV "pwd"). Neither needs to be a real
        // Cloudflare identity, so both are generated here unless the user typed their own, and
        // the password is written straight into the Worker's KV namespace after deployment.
        val loginName = request.accountEmail.trim().lowercase()
            .ifBlank { "maximus-${randomLower(6)}" }
        val loginPassword = request.panelPassword.trim().ifBlank { randomFrom(PASSWORD_ALPHABET, 16) }

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
                .put("accEmail", loginName)
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

            val hostName = "$workerName.$subdomain"
            val panelUrl = "https://$hostName/$securePath/panel"
            onLog("[BPB] Worker deployed successfully")

            onLog("[BPB] Creating panel login")
            putKv(accountId, token, namespaceId, "pwd", loginPassword)
            check(readKv(accountId, token, namespaceId, "pwd") == loginPassword) {
                "The panel password could not be stored in Cloudflare KV"
            }
            val vlUuid = settings.getString("vlUUID")
            val note = verifyBpb(hostName, securePath, loginName, loginPassword, onLog)
            return ManagedPanel(
                id = UUID.randomUUID().toString(),
                type = PanelType.BPB_WORKER,
                name = "BPB Worker • $workerName",
                url = panelUrl,
                username = loginName,
                password = loginPassword,
                apiToken = token,
                accountId = accountId,
                host = hostName,
                vlessUuid = vlUuid,
                securePath = securePath,
                healthNote = note
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
    private fun putKv(accountId: String, token: String, namespaceId: String, key: String, value: String) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "{}")
            .addFormDataPart("value", value)
            .build()
        cfJson(
            Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/$accountId/storage/kv/namespaces/$namespaceId/values/$key")
                .header("Authorization", "Bearer $token")
                .put(body)
                .build()
        )
    }

    private fun readKv(accountId: String, token: String, namespaceId: String, key: String): String =
        http.newCall(
            Request.Builder()
                .url("https://api.cloudflare.com/client/v4/accounts/$accountId/storage/kv/namespaces/$namespaceId/values/$key")
                .header("Authorization", "Bearer $token")
                .get().build()
        ).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }

    /**
     * Proves the new BPB panel works end to end: the login accepts the generated credentials and a
     * VLESS WebSocket handshake on the worker is accepted. A freshly created workers.dev hostname can
     * take a little while to resolve, so both checks are retried.
     */
    private fun verifyBpb(
        hostName: String,
        securePath: String,
        username: String,
        password: String,
        onLog: (String) -> Unit
    ): String {
        onLog("[BPB] Verifying login and VLESS endpoint")
        val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val loginBody = JSONObject().put("username", username).put("password", password)
        var loginOk = false
        var loginError = ""
        repeat(12) { attempt ->
            if (!loginOk) {
                try {
                    noRedirect.newCall(
                        Request.Builder()
                            .url("https://$hostName/$securePath/login/authenticate")
                            .post(loginBody.toString().toRequestBody(JSON))
                            .build()
                    ).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        loginOk = r.isSuccessful && runCatching { JSONObject(text).optBoolean("success") }.getOrDefault(false)
                        if (!loginOk) loginError = "HTTP ${r.code}"
                    }
                } catch (e: Exception) {
                    loginError = e.message.orEmpty().take(60)
                }
                if (!loginOk && attempt < 11) Thread.sleep(retryDelayMs)
            }
        }
        onLog(if (loginOk) "[BPB] Panel login verified" else "[WARN] Panel login not confirmed yet: $loginError")

        val wsOk = probeBpbWebSocket(hostName)
        onLog(if (wsOk) "[BPB] VLESS WebSocket endpoint answers" else "[WARN] VLESS endpoint not confirmed yet")

        return when {
            loginOk && wsOk -> "Login and VLESS endpoint verified"
            loginOk -> "Login verified; VLESS endpoint may need a few minutes to propagate"
            else -> "Deployed; the new workers.dev address may need a few minutes to propagate"
        }
    }

    private fun probeBpbWebSocket(hostName: String): Boolean {
        repeat(5) { attempt ->
            val latch = java.util.concurrent.CountDownLatch(1)
            val opened = java.util.concurrent.atomic.AtomicBoolean(false)
            val request = Request.Builder()
                .url("https://$hostName/vl/${randomLower(12)}?ed=2560")
                .build()
            val ws = http.newWebSocket(request, object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    opened.set(true)
                    latch.countDown()
                }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    latch.countDown()
                }
            })
            latch.await(8, TimeUnit.SECONDS)
            runCatching { ws.close(1000, null) }
            if (opened.get()) return true
            if (attempt < 4) Thread.sleep(retryDelayMs)
        }
        return false
    }

    /** Quick VLESS + Reality (Vision) config, created and verified on the panel. */
    fun createQuickConfig(panel: ManagedPanel): QuickConfigResult {
        require(panel.type == PanelType.XUI) { "Quick config is supported for 3X-UI panels" }
        val result = ThreeXUiConfigGenerator(http).generateRecommended(panel)
        return QuickConfigResult(result.clientUri, result.serverRemark, result.reachable, result.statusMessage)
    }

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

    private fun exec(
        session: Session,
        command: String,
        timeoutMs: Long,
        onLine: ((String) -> Unit)? = null
    ): String {
        val channel = session.openChannel("exec") as ChannelExec
        val output = java.io.ByteArrayOutputStream()
        channel.setCommand(command)
        channel.setInputStream(null)
        val stdout = channel.inputStream
        val stderr = channel.errStream
        val pending = StringBuilder()
        var inResult = false
        fun feed(chunk: String) {
            pending.append(chunk)
            while (true) {
                val nl = pending.indexOf("\n")
                if (nl < 0) break
                val line = pending.substring(0, nl).trimEnd('\r')
                pending.delete(0, nl + 1)
                if (line.contains(RESULT_MARKER)) inResult = true
                if (!inResult) onLine?.invoke(line)
            }
        }
        try {
            channel.connect(15_000)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            val buffer = ByteArray(8192)
            while (true) {
                for (stream in listOf(stdout, stderr)) {
                    while (stream.available() > 0) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, stream.available()))
                        if (count > 0) {
                            check(output.size() + count < 4 * 1024 * 1024) { "Installer output exceeded limit" }
                            output.write(buffer, 0, count)
                            if (onLine != null) feed(String(buffer, 0, count, Charsets.UTF_8))
                        }
                    }
                }
                if (channel.isClosed && stdout.available() == 0 && stderr.available() == 0) break
                check(System.nanoTime() < deadline) { "Remote installation timed out; check server before retrying" }
                Thread.sleep(50)
            }
            if (channel.exitStatus != 0) {
                val tail = output.toString("UTF-8").lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !SECRET_LINE.containsMatchIn(it) }
                    .toList().takeLast(3).joinToString(" | ").take(220)
                error("Remote installer failed (exit ${channel.exitStatus})${if (tail.isNotBlank()) ": $tail" else ""}")
            }
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
        private const val XUI_VERSION = "v3.8.5"
        private const val XUI_INSTALLER_URL =
            "https://raw.githubusercontent.com/MHSanaei/3x-ui/7ef22f94c950ff09f0870e2295fa65ad5968742c/install.sh"
        private const val RESULT_MARKER = "---MAXIMUS-RESULT---"
        private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        private val SECRET_LINE = Regex("(pass(word)?|user(name)?|token|secret|credential)", RegexOption.IGNORE_CASE)
    }
}
