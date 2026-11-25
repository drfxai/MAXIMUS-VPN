package com.example.vpn

import com.example.data.model.ConnectionStatus

/**
 * A connect job that ends (returns or throws) while it is still the newest request and the visible state still says
 * "connecting" left the user with a spinner and no reason. Build 175 report: direct Psiphon ended that way at 11:10
 * (its region run read a stale stop fence as a cancel and threw a CancellationException nobody logged), and the screen
 * said "Preparing" for 18 minutes until the user pressed Disconnect. The job's end now turns such a state into
 * FAILED with a reason ([reason]); a job that was cancelled (a newer Connect or Disconnect) is left alone. Pure.
 */
object UnfinishedConnect {
    private val CONNECTING = setOf(
        ConnectionStatus.PREPARING, ConnectionStatus.CONNECTING, ConnectionStatus.VPN_INTERFACE_ESTABLISHED,
        ConnectionStatus.ENGINE_STARTED, ConnectionStatus.PROXY_CONNECTING, ConnectionStatus.VERIFYING
    )

    /** [current]: no newer Connect, Disconnect or reconnect came after the job's ticket; [cancelled]: the job was cancelled. */
    fun stuck(status: ConnectionStatus, current: Boolean, cancelled: Boolean): Boolean =
        current && !cancelled && status in CONNECTING

    /** What the FAILED state says. [error]: what ended the job, when it threw. */
    fun reason(error: Throwable?): String {
        val cause = error?.let { e ->
            val message = e.message?.takeIf { it.isNotBlank() }?.let { com.example.core.SecretRedactor.redact(it).take(160) }
            listOfNotNull(e.javaClass.simpleName, message).joinToString(": ")
        }
        return "The connection attempt ended without a result" + (cause?.let { " ($it)" } ?: "") +
            "; traffic is unverified. Try again, or pick another connection."
    }
}
