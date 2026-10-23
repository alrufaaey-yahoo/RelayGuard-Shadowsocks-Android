# Third-party notices

This application combines upstream Shadowsocks Android with the RelayGuard Rust proxy engine. Preserve the upstream notices and comply with the corresponding licenses when redistributing modified source or binaries.

## Shadowsocks Android

- Source: https://github.com/shadowsocks/shadowsocks-android
- License: GNU General Public License v3.0 or later (see repository [`LICENSE`](LICENSE)).
- The upstream project bundles or builds additional components; refer to its README and each component's own notice for exact terms. This checkout includes the source snapshots referenced by `.gitmodules` (including badvpn, libancillary, libevent, redsocks and shadowsocks-rust) so a full native build can run without submodule initialization.

## RelayGuard

- Source: https://github.com/alrufaaey-yahoo/RelayGuard-Android
- Rust crate source and Apps Script assets are included under [`relayguard/`](relayguard/).
- License: MIT (see [`relayguard/LICENSE`](relayguard/LICENSE)).
- This fork modifies the Android integration and routes RelayGuard's outbound TLS/raw TCP streams through the local Shadowsocks SOCKS endpoint. The RelayGuard upstream code is not represented as an official release.

## Dependency licensing

Gradle and Cargo dependencies remain subject to their respective upstream licenses. Review the resolved dependency metadata for the exact versions included in a release. No private signing key or credential is included in this repository.
