//! TcpStream wrappers that supports connecting with options

#[cfg(unix)]
use std::os::unix::io::{AsRawFd, RawFd};
#[cfg(windows)]
use std::os::windows::io::{AsRawSocket, RawSocket};
use std::{
    io,
    net::SocketAddr,
    ops::{Deref, DerefMut},
    pin::Pin,
    task::{self, Poll},
};

use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64_STANDARD};
use futures::{future, ready};
use pin_project::pin_project;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf},
    net::{TcpListener as TokioTcpListener, TcpStream as TokioTcpStream},
};

use crate::{ServerAddr, config::HttpConnectProxy, context::Context, relay::socks5::Address};

use super::{
    AcceptOpts, ConnectOpts, is_dual_stack_addr,
    sys::{
        TcpStream as SysTcpStream, create_inbound_tcp_socket, set_common_sockopt_after_accept, set_tcp_fastopen,
        socket_bind_dual_stack,
    },
};

/// TcpStream for outbound connections
#[pin_project]
pub struct TcpStream(#[pin] SysTcpStream);

impl TcpStream {
    /// Connects to address
    pub async fn connect_with_opts(addr: &SocketAddr, opts: &ConnectOpts) -> io::Result<TcpStream> {
        // tcp_stream_connect(addr, opts).await.map(TcpStream)
        SysTcpStream::connect(*addr, opts).await.map(TcpStream)
    }

    /// Connects shadowsocks server
    pub async fn connect_server_with_opts(
        context: &Context,
        addr: &ServerAddr,
        opts: &ConnectOpts,
    ) -> io::Result<TcpStream> {
        let stream = match *addr {
            ServerAddr::SocketAddr(ref addr) => SysTcpStream::connect(*addr, opts).await?,
            ServerAddr::DomainName(ref domain, port) => {
                lookup_then_connect!(context, domain, port, |addr| {
                    SysTcpStream::connect(addr, opts).await
                })?
                .1
            }
        };

        Ok(TcpStream(stream))
    }

    /// Connect to a Shadowsocks server through an HTTP CONNECT proxy.
    pub async fn connect_server_through_http_connect(
        context: &Context,
        target: &ServerAddr,
        proxy: &HttpConnectProxy,
        opts: &ConnectOpts,
    ) -> io::Result<TcpStream> {
        proxy
            .validate()
            .map_err(|message| io::Error::new(io::ErrorKind::InvalidInput, message))?;
        let raw_host = proxy.server.as_str();
        let host = if raw_host.starts_with('[') && raw_host.ends_with(']') {
            &raw_host[1..raw_host.len() - 1]
        } else {
            raw_host
        };
        let proxy_addr = match host.parse::<std::net::IpAddr>() {
            Ok(ip) => ServerAddr::SocketAddr(SocketAddr::new(ip, proxy.server_port)),
            Err(_) => ServerAddr::DomainName(host.to_owned(), proxy.server_port),
        };
        let mut stream = Self::connect_server_with_opts(context, &proxy_addr, opts).await?;
        tokio::time::timeout(
            std::time::Duration::from_secs(15),
            http_connect_handshake(&mut stream, target, proxy),
        )
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "HTTP CONNECT handshake timed out"))??;
        Ok(stream)
    }

    /// Connects proxy remote target
    pub async fn connect_remote_with_opts(
        context: &Context,
        addr: &Address,
        opts: &ConnectOpts,
    ) -> io::Result<TcpStream> {
        let stream = match *addr {
            Address::SocketAddress(ref addr) => SysTcpStream::connect(*addr, opts).await?,
            Address::DomainNameAddress(ref domain, port) => {
                lookup_then_connect!(context, domain, port, |addr| {
                    SysTcpStream::connect(addr, opts).await
                })?
                .1
            }
        };

        Ok(TcpStream(stream))
    }

    /// Returns the local address that this stream is bound to.
    pub fn local_addr(&self) -> io::Result<SocketAddr> {
        self.0.local_addr()
    }

    /// Returns the remote address that this stream is connected to.
    pub fn peer_addr(&self) -> io::Result<SocketAddr> {
        self.0.peer_addr()
    }

    /// Gets the value of the `TCP_NODELAY` option on this socket.
    pub fn nodelay(&self) -> io::Result<bool> {
        self.0.nodelay()
    }

    /// Sets the value of the `TCP_NODELAY` option on this socket.
    pub fn set_nodelay(&self, nodelay: bool) -> io::Result<()> {
        self.0.set_nodelay(nodelay)
    }
}

const HTTP_CONNECT_MAX_HEADER_BYTES: usize = 16 * 1024;

async fn http_connect_handshake<S>(stream: &mut S, target: &ServerAddr, proxy: &HttpConnectProxy) -> io::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let authority = target.to_string();
    if authority
        .bytes()
        .any(|b| b.is_ascii_control() || b.is_ascii_whitespace())
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid Shadowsocks server address for CONNECT",
        ));
    }

    let mut request = format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\nProxy-Connection: Keep-Alive\r\n");
    if let (Some(username), Some(password)) = (&proxy.username, &proxy.password) {
        let credentials = BASE64_STANDARD.encode(format!("{username}:{password}"));
        request.push_str(&format!("Proxy-Authorization: Basic {credentials}\r\n"));
    }
    request.push_str("\r\n");
    stream.write_all(request.as_bytes()).await?;
    stream.flush().await?;

    // Read one byte at a time so bytes belonging to the tunneled Shadowsocks stream
    // can never be consumed by a buffered HTTP reader.
    let mut response = Vec::with_capacity(256);
    let mut byte = [0u8; 1];
    while !response.ends_with(b"\r\n\r\n") {
        if response.len() >= HTTP_CONNECT_MAX_HEADER_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "HTTP CONNECT response headers exceed 16 KiB",
            ));
        }
        if stream.read(&mut byte).await? == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "HTTP CONNECT proxy closed before responding",
            ));
        }
        response.push(byte[0]);
    }

    let line_end = response.iter().position(|b| *b == b'\n').unwrap_or(response.len());
    let status_line = std::str::from_utf8(&response[..line_end])
        .map_err(|_| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "HTTP CONNECT returned a non-UTF-8 status line",
            )
        })?
        .trim_end_matches('\r');
    let mut status_parts = status_line.split_ascii_whitespace();
    let version = status_parts.next().unwrap_or_default();
    let status = status_parts.next().and_then(|value| value.parse::<u16>().ok());
    if !matches!(version, "HTTP/1.0" | "HTTP/1.1") || !status.is_some_and(|code| (200..300).contains(&code)) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!(
                "HTTP CONNECT proxy rejected the tunnel (status {})",
                status.map_or("invalid".to_owned(), |v| v.to_string())
            ),
        ));
    }
    Ok(())
}

impl AsyncRead for TcpStream {
    fn poll_read(self: Pin<&mut Self>, cx: &mut task::Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        self.project().0.poll_read(cx, buf)
    }
}

impl AsyncWrite for TcpStream {
    fn poll_write(self: Pin<&mut Self>, cx: &mut task::Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        self.project().0.poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut task::Context<'_>) -> Poll<io::Result<()>> {
        self.project().0.poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut task::Context<'_>) -> Poll<io::Result<()>> {
        self.project().0.poll_shutdown(cx)
    }
}

/// `TcpListener` for accepting inbound connections
#[derive(Debug)]
pub struct TcpListener {
    inner: TokioTcpListener,
    accept_opts: AcceptOpts,
}

impl TcpListener {
    /// Creates a new TcpListener, which will be bound to the specified address.
    pub async fn bind_with_opts(addr: &SocketAddr, accept_opts: AcceptOpts) -> io::Result<TcpListener> {
        let socket = create_inbound_tcp_socket(addr, &accept_opts).await?;

        if let Some(size) = accept_opts.tcp.send_buffer_size {
            socket.set_send_buffer_size(size)?;
        }

        if let Some(size) = accept_opts.tcp.recv_buffer_size {
            socket.set_recv_buffer_size(size)?;
        }

        // On platforms with Berkeley-derived sockets, this allows to quickly
        // rebind a socket, without needing to wait for the OS to clean up the
        // previous one.
        //
        // On Windows, this allows rebinding sockets which are actively in use,
        // which allows “socket hijacking”, so we explicitly don't set it here.
        // https://docs.microsoft.com/en-us/windows/win32/winsock/using-so-reuseaddr-and-so-exclusiveaddruse
        #[cfg(not(windows))]
        socket.set_reuseaddr(true)?;

        let set_dual_stack = is_dual_stack_addr(addr);

        if set_dual_stack {
            socket_bind_dual_stack(&socket, addr, accept_opts.ipv6_only)?;
        } else {
            socket.bind(*addr)?;
        }

        // mio's default backlog is 1024
        let inner = socket.listen(1024)?;

        // Enable TFO if supported
        // macos requires TCP_FASTOPEN to be set after listen(), but other platform doesn't have this constraint
        if accept_opts.tcp.fastopen {
            set_tcp_fastopen(&inner)?;
        }

        Ok(TcpListener { inner, accept_opts })
    }

    /// Create a `TcpListener` from tokio's `TcpListener`
    pub fn from_listener(listener: TokioTcpListener, accept_opts: AcceptOpts) -> io::Result<TcpListener> {
        // Enable TFO if supported
        // macos requires TCP_FASTOPEN to be set after listen(), but other platform doesn't have this constraint
        if accept_opts.tcp.fastopen {
            set_tcp_fastopen(&listener)?;
        }

        Ok(TcpListener {
            inner: listener,
            accept_opts,
        })
    }

    /// Polls to accept a new incoming connection to this listener.
    pub fn poll_accept(&self, cx: &mut task::Context<'_>) -> Poll<io::Result<(TokioTcpStream, SocketAddr)>> {
        let (stream, peer_addr) = ready!(self.inner.poll_accept(cx))?;
        set_common_sockopt_after_accept(&stream, &self.accept_opts)?;
        Poll::Ready(Ok((stream, peer_addr)))
    }

    /// Accept a new incoming connection to this listener
    pub async fn accept(&self) -> io::Result<(TokioTcpStream, SocketAddr)> {
        future::poll_fn(|cx| self.poll_accept(cx)).await
    }

    /// Unwraps and take the internal `TcpListener`
    pub fn into_inner(self) -> TokioTcpListener {
        self.inner
    }
}

impl Deref for TcpListener {
    type Target = TokioTcpListener;

    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl DerefMut for TcpListener {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.inner
    }
}

impl From<TcpListener> for TokioTcpListener {
    fn from(listener: TcpListener) -> TokioTcpListener {
        listener.inner
    }
}

#[cfg(unix)]
impl AsRawFd for TcpStream {
    fn as_raw_fd(&self) -> RawFd {
        self.0.as_raw_fd()
    }
}

#[cfg(windows)]
impl AsRawSocket for TcpStream {
    fn as_raw_socket(&self) -> RawSocket {
        self.0.as_raw_socket()
    }
}

#[cfg(test)]
mod http_connect_tests {
    use super::*;

    async fn read_http_headers<S: AsyncRead + Unpin>(stream: &mut S) -> io::Result<Vec<u8>> {
        let mut headers = Vec::new();
        let mut byte = [0u8; 1];
        while !headers.ends_with(b"\r\n\r\n") {
            stream.read_exact(&mut byte).await?;
            headers.push(byte[0]);
        }
        Ok(headers)
    }

    #[tokio::test]
    async fn connect_sends_vps_authority_and_basic_auth_and_preserves_tunnel_bytes() {
        let (mut client, mut proxy_side) = tokio::io::duplex(4096);
        let proxy = HttpConnectProxy {
            server: "proxy.example".to_owned(),
            server_port: 8080,
            username: Some("user".to_owned()),
            password: Some("pass".to_owned()),
        };
        let target = ServerAddr::DomainName("vps.example".to_owned(), 20115);

        let proxy_task = tokio::spawn(async move {
            let request = read_http_headers(&mut proxy_side).await.unwrap();
            let request = String::from_utf8(request).unwrap();
            assert!(request.starts_with("CONNECT vps.example:20115 HTTP/1.1\r\n"));
            assert!(request.contains("Host: vps.example:20115\r\n"));
            assert!(request.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"));
            proxy_side
                .write_all(b"HTTP/1.1 200 Connection established\r\n\r\nS")
                .await
                .unwrap();
            let mut byte = [0u8; 1];
            proxy_side.read_exact(&mut byte).await.unwrap();
            assert_eq!(&byte, b"X");
            proxy_side.write_all(b"Y").await.unwrap();
        });

        http_connect_handshake(&mut client, &target, &proxy).await.unwrap();
        let mut byte = [0u8; 1];
        client.read_exact(&mut byte).await.unwrap();
        assert_eq!(&byte, b"S");
        client.write_all(b"X").await.unwrap();
        client.read_exact(&mut byte).await.unwrap();
        assert_eq!(&byte, b"Y");
        proxy_task.await.unwrap();
    }

    #[tokio::test]
    async fn connect_rejects_proxy_auth_failure() {
        let (mut client, mut proxy_side) = tokio::io::duplex(1024);
        let proxy = HttpConnectProxy {
            server: "proxy.example".to_owned(),
            server_port: 8080,
            username: None,
            password: None,
        };
        let target = ServerAddr::DomainName("vps.example".to_owned(), 20115);
        let proxy_task = tokio::spawn(async move {
            read_http_headers(&mut proxy_side).await.unwrap();
            proxy_side
                .write_all(b"HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
                .await
                .unwrap();
        });

        let error = http_connect_handshake(&mut client, &target, &proxy).await.unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
        proxy_task.await.unwrap();
    }
}
