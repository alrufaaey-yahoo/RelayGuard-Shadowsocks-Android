package com.github.shadowsocks.bg

/** Runtime-only endpoint published after the embedded RelayGuard listener is ready. */
object RelayGuardRuntime {
    @Volatile
    var httpProxyPort: Int = 0
        private set

    fun publishHttpProxyPort(port: Int) {
        require(port in 1..65535)
        httpProxyPort = port
    }

    fun clear() {
        httpProxyPort = 0
    }
}
