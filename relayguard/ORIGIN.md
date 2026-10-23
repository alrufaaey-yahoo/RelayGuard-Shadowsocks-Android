# RelayGuard engine origin

This directory vendors the Rust crate and Apps Script deployment assets from:

- https://github.com/alrufaaey-yahoo/RelayGuard-Android
- Upstream version: 1.9.38 (Cargo.toml)
- License: MIT (LICENSE)

The integrated Android app does not ship the RelayGuard standalone UI or its second VPN service. Instead, it starts the native RelayGuard proxy from the Shadowsocks VPN service and sets its SOCKS5 upstream to the active Shadowsocks local proxy.
