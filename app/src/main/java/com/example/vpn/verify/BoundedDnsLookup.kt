package com.example.vpn.verify

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.*

/** Android network DNS can block independently of a socket timeout. Bound both time and workers. */
object BoundedDnsLookup {
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(), { task -> Thread(task, "vpn-dns-check").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    fun resolve(timeoutMs: Int, lookup: () -> Array<InetAddress>): Array<InetAddress> {
        val future = try { workers.submit(Callable { lookup() }) }
        catch (e: RejectedExecutionException) { throw UnknownHostException("VPN DNS checks are busy").apply { initCause(e) } }
        try { return future.get(timeoutMs.coerceIn(100, 30_000).toLong(), TimeUnit.MILLISECONDS) }
        catch (e: InterruptedException) { throw e }
        catch (e: ExecutionException) { throw (e.cause ?: e) }
        catch (e: TimeoutException) { throw java.net.SocketTimeoutException("VPN DNS lookup timed out").apply { initCause(e) } }
        finally { future.cancel(true) }
    }
}
