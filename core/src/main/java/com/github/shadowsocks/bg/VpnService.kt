/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.bg

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.github.shadowsocks.Core
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.core.R
import com.github.shadowsocks.net.ConcurrentLocalSocketListener
import com.github.shadowsocks.net.DefaultNetworkListener
import com.github.shadowsocks.net.DnsResolverCompat
import com.github.shadowsocks.net.Subnet
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.utils.int
import com.therealaleph.mhrv.Native
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.net.URL
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(), BaseService.Interface {
    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
        private const val RELAY_HTTP_PORT = 18080
        private const val RELAY_SOCKS_PORT = 18081

        private fun <T> FileDescriptor.use(block: (FileDescriptor) -> T) = try {
            block(this)
        } finally {
            try {
                Os.close(this)
            } catch (_: ErrnoException) { }
        }
    }

    private inner class ProtectWorker : ConcurrentLocalSocketListener("ShadowsocksVpnThread",
            File(Core.deviceStorage.noBackupFilesDir, "protect_path")) {
        override fun acceptInternal(socket: LocalSocket) {
            if (socket.inputStream.read() == -1) return
            val success = socket.ancillaryFileDescriptors!!.single()!!.use { fd ->
                underlyingNetwork.let { network ->
                    if (network != null) try {
                        network.bindSocket(fd)
                        return@let true
                    } catch (e: IOException) {
                        @SuppressLint("NewApi")
                        when ((e.cause as? ErrnoException)?.errno) {
                            OsConstants.EPERM, OsConstants.EACCES, OsConstants.ENONET -> Timber.d(e)
                            else -> Timber.w(e)
                        }
                        return@let false
                    }
                    protect(fd.int)
                }
            }
            try {
                socket.outputStream.write(if (success) 0 else 1)
            } catch (_: IOException) { }        // ignore connection early close
        }
    }

    inner class NullConnectionException : NullPointerException(), BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    override val data = BaseService.Data(this)
    override val tag: String get() = "ShadowsocksVpnService"
    override fun createNotification(profileName: String): ServiceNotification =
            ServiceNotification(this, profileName, "service-vpn")

    private var relayHandle: Long = 0
    private var conn: ParcelFileDescriptor? = null
    private var worker: ProtectWorker? = null
    private var active = false
    private var metered = false
    @Volatile
    private var underlyingNetwork: Network? = null
        set(value) {
            field = value
            if (active) setUnderlyingNetworks(underlyingNetworks)
        }
    private val underlyingNetworks get() =
        // clearing underlyingNetworks makes Android 9 consider the network to be metered
        if (Build.VERSION.SDK_INT == 28 && metered) null else underlyingNetwork?.let { arrayOf(it) }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override fun onRevoke() = stopRunner()

    override fun killProcesses(scope: CoroutineScope) {
        if (relayHandle != 0L) {
            val handle = relayHandle
            relayHandle = 0L
            scope.launch(Dispatchers.IO) { Native.stopProxy(handle) }
        }
        super.killProcesses(scope)
        active = false
        scope.launch { DefaultNetworkListener.stop(this) }
        worker?.shutdown(scope)
        worker = null
        conn?.close()
        conn = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)

    override suspend fun preInit() {
        // double check prepare to workaround bugs in certain OS
        if (prepare(this) != null) throw NullConnectionException()
        DefaultNetworkListener.start(this) { underlyingNetwork = it }
    }
    override suspend fun rawResolver(query: ByteArray) =
            // no need to listen for network here as this is only used for forwarding local DNS queries.
            // retries should be attempted by client.
            DnsResolverCompat.resolveRaw(underlyingNetwork ?: throw IOException("no network"), query)
    override suspend fun openConnection(url: URL) = DefaultNetworkListener.get().openConnection(url)

    override suspend fun startProcesses() {
        worker = ProtectWorker().apply { start() }
        super.startProcesses()
        if (DataStore.relayEnabled) startRelayGuard()
        sendFd(startVpn())
    }

    override val isVpnService get() = true

    private suspend fun startRelayGuard() {
        val deployments = DataStore.relayDeploymentUrls
            .split(Regex("[,;\\s]+"))
            .mapNotNull { raw ->
                val value = raw.trim().trim('"', '\'')
                if (value.isEmpty()) return@mapNotNull null
                Regex("(?i)^https?://script\\.google\\.com/macros/s/([A-Za-z0-9_-]+)/exec/?(?:\\?.*)?$")
                    .matchEntire(value)?.groupValues?.get(1)
                    ?: value.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            }.distinct()
        val authKey = DataStore.relayAuthKey.trim()
        val frontDomain = DataStore.relayFrontDomain.trim().ifBlank { "www.google.com" }
        if (deployments.isEmpty() || authKey.isBlank()) {
            throw IOException("Script relay requires a valid Deployment URL and auth_key in Settings")
        }
        val dataDir = File(filesDir, "relayguard").apply { mkdirs() }
        Native.setDataDir(dataDir.absolutePath)
        val config = JSONObject().apply {
            put("mode", "apps_script")
            put("listen_host", "127.0.0.1")
            put("listen_port", RELAY_HTTP_PORT)
            put("socks5_port", RELAY_SOCKS_PORT)
            put("script_ids", JSONArray(deployments))
            put("auth_key", authKey)
            put("front_domain", frontDomain)
            put("google_ip", frontDomain)
            put("sni_hosts", JSONArray(listOf(frontDomain)))
            // Both RelayGuard relay requests and raw TCP streams use Shadowsocks as their only upstream.
            put("upstream_socks5", "127.0.0.1:${DataStore.portProxy}")
            put("verify_ssl", DataStore.relayVerifySsl)
            put("youtube_via_relay", DataStore.relayYoutubeViaRelay)
            put("log_level", DataStore.relayLogLevel)
            put("parallel_relay", DataStore.relayParallel)
            put("block_quic", DataStore.relayBlockQuic)
            put("block_stun", DataStore.relayBlockStun)
            put("block_doh", DataStore.relayBlockDoh)
            put("tunnel_doh", DataStore.relayTunnelDoh)
            put("force_http1", DataStore.relayForceHttp1)
            put("coalesce_step_ms", DataStore.relayCoalesceStepMs)
            put("coalesce_max_ms", DataStore.relayCoalesceMaxMs)
        }
        relayHandle = withContext(Dispatchers.IO) { Native.startProxy(config.toString()) }
        if (relayHandle == 0L) throw IOException("RelayGuard failed to start; inspect Android service logs")
        val ready = withContext(Dispatchers.IO) {
            var attempt = 0
            var listening = false
            while (attempt < 60 && !listening) {
                try {
                    java.net.Socket().use {
                        it.connect(java.net.InetSocketAddress("127.0.0.1", RELAY_SOCKS_PORT), 100)
                    }
                    listening = true
                } catch (_: IOException) {
                    delay(50)
                }
                attempt += 1
            }
            listening
        }
        if (!ready) {
            withContext(Dispatchers.IO) { Native.stopProxy(relayHandle) }
            relayHandle = 0L
            throw IOException("RelayGuard SOCKS listener did not become ready")
        }
        Timber.i("RelayGuard started in chain mode: local relay -> Shadowsocks SOCKS -> VPS")
    }

    private fun startVpn(): FileDescriptor {
        val profile = data.proxy!!.profile
        val builder = Builder()
                .setConfigureIntent(Core.configureIntent(this))
                .setSession(profile.formattedName)
                .setMtu(VPN_MTU)
                .addAddress(PRIVATE_VLAN4_CLIENT, 30)
                .addDnsServer(PRIVATE_VLAN4_ROUTER)

        if (profile.ipv6) builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)

        val me = packageName
        if (profile.proxyApps) {
            profile.individual.split('\n')
                    .filter { it != me }
                    .forEach {
                        try {
                            if (profile.bypass) builder.addDisallowedApplication(it)
                            else builder.addAllowedApplication(it)
                        } catch (ex: PackageManager.NameNotFoundException) {
                            Timber.w(ex)
                        }
                    }
            if (profile.bypass) builder.addDisallowedApplication(me)
        } else {
            builder.addDisallowedApplication(me)
        }

        when (profile.route) {
            Acl.ALL, Acl.BYPASS_CHN, Acl.CUSTOM_RULES -> {
                builder.addRoute("0.0.0.0", 0)
                if (profile.ipv6) builder.addRoute("::", 0)
            }
            else -> {
                resources.getStringArray(R.array.bypass_private_route).forEach {
                    val subnet = Subnet.fromString(it)!!
                    builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
                }
                builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
                // https://issuetracker.google.com/issues/149636790
                if (profile.ipv6) builder.addRoute("2000::", 3)
            }
        }

        metered = profile.metered
        active = true   // possible race condition here?
        builder.setUnderlyingNetworks(underlyingNetworks)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)

        val conn = builder.establish() ?: throw NullConnectionException()
        this.conn = conn

        val cmd = arrayListOf(File(applicationInfo.nativeLibraryDir, Executable.TUN2SOCKS).absolutePath,
                "--netif-ipaddr", PRIVATE_VLAN4_ROUTER,
                "--socks-server-addr", if (DataStore.relayEnabled) "127.0.0.1:$RELAY_SOCKS_PORT" else "${DataStore.listenAddress}:${DataStore.portProxy}",
                "--tunmtu", VPN_MTU.toString(),
                "--sock-path", "sock_path",
                "--dnsgw", "127.0.0.1:${DataStore.portLocalDns}",
                "--loglevel", "warning")
        if (profile.ipv6) {
            cmd += "--netif-ip6addr"
            cmd += PRIVATE_VLAN6_ROUTER
        }
        cmd += "--enable-udprelay"
        data.processes!!.start(cmd, onRestartCallback = {
            try {
                sendFd(conn.fileDescriptor)
            } catch (e: ErrnoException) {
                stopRunner(false, e.message)
            }
        })
        return conn.fileDescriptor
    }

    private suspend fun sendFd(fd: FileDescriptor) {
        var tries = 0
        val path = File(Core.deviceStorage.noBackupFilesDir, "sock_path").absolutePath
        while (true) try {
            delay(50L shl tries)
            LocalSocket().use { localSocket ->
                localSocket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                localSocket.setFileDescriptorsForSend(arrayOf(fd))
                localSocket.outputStream.write(42)
            }
            return
        } catch (e: IOException) {
            if (tries > 5) throw e
            tries += 1
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        data.binder.close()
    }
}
