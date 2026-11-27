//! Root ioctl bridge.
//!
//! The kernel decides who the manager is by comparing the caller's app id with
//! the one baked in at build time (plus an APK certificate check on this build),
//! so a manager that was compiled elsewhere can never talk to the driver
//! directly - every ioctl comes back `EPERM`. It *can* ask `su` for a root
//! shell, and the driver happily serves uid 0 (`manager_or_root` is the
//! permission class on almost every command).
//!
//! This command is that bridge: a privileged daemon that keeps the driver fd
//! open and forwards raw ioctl requests coming from the manager app over a
//! unix socket. The number encodes its own argument size (`_IOC_SIZE`), so no
//! per-command table is needed here - the daemon stays valid no matter how the
//! uapi grows.
//!
//! Safety: the peer must present the token passed on the command line *and*
//! match the expected uid, so another app cannot borrow the root session.

use std::io::{Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;

pub const MAX_ARG: usize = 1 << 20;
const MAX_TOKEN: usize = 128;
const MAX_REQUEST: usize = 4096;

/// Application-level op the manager uses to answer a pending su request. It
/// never reaches the driver.
pub const OP_DECISION: u32 = 0xFFFF_FFFF;

type Pending = std::sync::Mutex<std::collections::HashMap<u32, std::sync::mpsc::Sender<bool>>>;

static PENDING: std::sync::OnceLock<Pending> = std::sync::OnceLock::new();

fn pending() -> &'static Pending {
    PENDING.get_or_init(|| std::sync::Mutex::new(std::collections::HashMap::new()))
}

pub fn run(
    socket: &Path,
    token: &str,
    peer_uid: u32,
    daemonize: bool,
    req_socket: Option<&Path>,
    manager: &str,
    borrow_uid: u32,
) -> anyhow::Result<()> {
    if unsafe { libc::getuid() } != 0 {
        anyhow::bail!("proxy must run as root");
    }
    if daemonize {
        // Detach from the su session so the bridge outlives the shell that
        // started it: daemon() forks, calls setsid() and points stdio at
        // /dev/null in the parent's place.
        let _ = std::fs::remove_file(socket);
        if unsafe { libc::daemon(0, 0) } < 0 {
            log::warn!("daemonize failed");
        }
    }

    if ksucalls_fd() < 0 {
        anyhow::bail!("could not obtain the kernelsu driver fd");
    }

    if let Some(req) = req_socket {
        let _ = std::fs::remove_file(req);
        match UnixListener::bind(req) {
            Ok(listener) => {
                std::fs::set_permissions(req, std::fs::Permissions::from_mode(0o666))?;
                let manager = manager.to_owned();
                std::thread::spawn(move || {
                    for conn in listener.incoming() {
                        match conn {
                            Ok(stream) => {
                                let manager = manager.clone();
                                std::thread::spawn(move || {
                                    if let Err(e) = serve_request(stream, &manager) {
                                        log::warn!("su request ended: {e}");
                                    }
                                });
                            }
                            Err(e) => {
                                log::warn!("su request accept failed: {e}");
                                break;
                            }
                        }
                    }
                });
                log::info!("su requests on {}", req.display());
            }
            Err(e) => log::warn!("could not listen for su requests on {}: {e}", req.display()),
        }
    }

    let _ = std::fs::remove_file(socket);
    let listener = UnixListener::bind(socket)?;
    std::fs::set_permissions(socket, std::fs::Permissions::from_mode(0o666))?;

    log::info!("proxy listening on {}", socket.display());

    let token = token.to_owned();
    for conn in listener.incoming() {
        match conn {
            Ok(stream) => {
                let token = token.clone();
                std::thread::spawn(move || {
                    if let Err(e) = serve(stream, &token, peer_uid, borrow_uid) {
                        log::warn!("proxy client ended: {e}");
                    }
                });
            }
            Err(e) => {
                log::warn!("proxy accept failed: {e}");
                break;
            }
        }
    }
    Ok(())
}

fn ksucalls_fd() -> i32 {
    crate::ksucalls::driver_fd()
}

/// The app id the kernel considers to be the manager.
///
/// Some of the driver's commands are gated on `is_manager()` rather than on
/// root (`SET_APP_PROFILE`, `GET_APP_PROFILE`), so a root bridge alone cannot
/// manage superusers. Borrowing that uid for the duration of one ioctl is
/// enough: the check is a plain comparison against the caller's uid.
fn manager_appid(fd: i32) -> u32 {
    let mut appid: u32 = 0;
    let ret = unsafe {
        libc::ioctl(
            fd,
            crate::ksu_uapi::KSU_IOCTL_GET_MANAGER_APPID as _,
            std::ptr::addr_of_mut!(appid),
        )
    };
    if ret == 0 && appid != 0 {
        log::info!("manager app id is {appid}");
        appid
    } else {
        log::warn!("manager app id unavailable (ret {ret})");
        0
    }
}

fn run_ioctl(fd: i32, op: u32, ptr: *mut libc::c_void, manager_appid: u32) -> i32 {
    let ret = unsafe { libc::ioctl(fd, op as _, ptr) } as i32;
    if ret >= 0 || manager_appid == 0 {
        return ret;
    }

    let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
    if errno != libc::EPERM {
        return ret;
    }

    // Retry as the pinned manager. `is_manager()` compares `current_uid()`, i.e.
    // the *real* uid, so the real id has to move too; the saved-set-uid keeps 0
    // around so the daemon can take it back afterwards. Credentials are per
    // process, hence the lock.
    static BORROW: std::sync::Mutex<()> = std::sync::Mutex::new(());
    let _guard = BORROW.lock().unwrap_or_else(|e| e.into_inner());

    unsafe {
        if libc::setresuid(manager_appid, manager_appid, 0) != 0 {
            return ret;
        }
        let borrowed = libc::ioctl(fd, op as _, ptr) as i32;
        libc::setresuid(u32::MAX, 0, u32::MAX);
        libc::setresuid(0, 0, 0);
        if borrowed >= 0 {
            log::info!("op 0x{op:x} served as the manager uid");
        }
        borrowed
    }
}

fn serve(mut stream: UnixStream, token: &str, peer_uid: u32, borrow_uid: u32) -> anyhow::Result<()> {
    let uid = peer_uid_of(&stream)?;
    if peer_uid != 0 && uid != peer_uid {
        anyhow::bail!("unexpected peer uid {uid}");
    }

    let mut hdr = [0u8; 4];
    stream.read_exact(&mut hdr)?;
    let len = u32::from_le_bytes(hdr) as usize;
    if len > MAX_TOKEN {
        anyhow::bail!("bad token length {len}");
    }
    let mut buf = vec![0u8; len];
    stream.read_exact(&mut buf)?;
    if buf != token.as_bytes() {
        anyhow::bail!("token mismatch");
    }

    let fd = ksucalls_fd();
    if fd < 0 {
        anyhow::bail!("driver fd lost");
    }
    // The kernel does not tell root who its manager is on every build
    // (`GET_MANAGER_APPID` answers 0 here), so the manager passes the uid of the
    // package it pinned; commands gated on `is_manager()` are retried under it.
    let manager = match manager_appid(fd) {
        0 => borrow_uid,
        id => id,
    };

    loop {
        let mut req = [0u8; 8];
        if stream.read_exact(&mut req).is_err() {
            return Ok(());
        }
        let op = u32::from_le_bytes([req[0], req[1], req[2], req[3]]);
        let size = u32::from_le_bytes([req[4], req[5], req[6], req[7]]) as usize;
        if size > MAX_ARG {
            anyhow::bail!("argument too large: {size}");
        }

        let mut arg = vec![0u8; size];
        if size > 0 {
            stream.read_exact(&mut arg)?;
        }

        let ptr: *mut libc::c_void = if size == 0 {
            std::ptr::null_mut()
        } else {
            arg.as_mut_ptr().cast()
        };

        let ret = if op == OP_DECISION {
            resolve_decision(&arg);
            0
        } else {
            run_ioctl(fd, op, ptr, manager)
        };

        let mut out = Vec::with_capacity(8 + size);
        out.extend_from_slice(&ret.to_le_bytes());
        out.extend_from_slice(&(size as u32).to_le_bytes());
        out.extend_from_slice(&arg);
        stream.write_all(&out)?;
    }
}

/// `[u32 uid][u8 allow]` from the manager: hand the answer to the su process
/// waiting in [`serve_request`].
fn resolve_decision(payload: &[u8]) {
    if payload.len() < 5 {
        log::warn!("decision payload too short");
        return;
    }
    let uid = u32::from_le_bytes([payload[0], payload[1], payload[2], payload[3]]);
    let allow = payload[4] != 0;
    if let Some(tx) = pending().lock().unwrap_or_else(|e| e.into_inner()).remove(&uid) {
        let _ = tx.send(allow);
        log::info!("su decision for uid {uid}: {allow}");
    } else {
        log::warn!("no pending su request for uid {uid}");
    }
}

fn serve_request(mut stream: UnixStream, manager: &str) -> anyhow::Result<()> {
    let uid = peer_uid_of(&stream)?;

    let mut hdr = [0u8; 4];
    stream.read_exact(&mut hdr)?;
    let len = u32::from_le_bytes(hdr) as usize;
    if len > MAX_REQUEST {
        anyhow::bail!("request too long: {len}");
    }
    let mut cmd = vec![0u8; len];
    if len > 0 {
        stream.read_exact(&mut cmd)?;
    }
    let payload = String::from_utf8_lossy(&cmd).to_string();
    let (name, cmd) = match payload.split_once('\n') {
        Some((name, cmd)) => (name.to_string(), cmd.replace('\n', " ")),
        None => (String::new(), payload.replace('\n', " ")),
    };

    let key = package_of_uid(uid).unwrap_or_default();
    let label = if key.is_empty() {
        if name.is_empty() {
            format!("uid {uid}")
        } else {
            format!("{name} (uid {uid})")
        }
    } else {
        format!("{key} (uid {uid})")
    };

    let (tx, rx) = std::sync::mpsc::channel();
    pending()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(uid, tx);

    // Bring the manager to the front asking about this uid. The manager writes
    // the profile itself and answers over the bridge socket.
    let status = std::process::Command::new("/system/bin/am")
        .args([
            "start",
            "--user",
            "0",
            "-n",
            manager,
            "--es",
            "su_uid",
            &uid.to_string(),
            "--es",
            "su_key",
            &key,
            "--es",
            "su_name",
            &name,
            "--es",
            "su_cmd",
            &cmd,
        ])
        .env("PATH", "/system/bin:/system/xbin")
        .status();
    if let Err(e) = status {
        log::warn!("could not start the manager for a su request: {e}");
    }

    let allow = rx
        .recv_timeout(std::time::Duration::from_secs(90))
        .unwrap_or(false);
    pending()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&uid);

    stream.write_all(&[u8::from(allow)])?;
    log::info!("su request from {label}: {allow}");
    Ok(())
}

/// The package that owns a uid, from the list the system keeps for itself.
fn package_of_uid(uid: u32) -> Option<String> {
    let list = "/data/system/packages.list";
    let text = std::fs::read_to_string(list).ok()?;
    for line in text.lines() {
        let mut fields = line.split_whitespace();
        let package = fields.next()?;
        if fields.next().and_then(|v| v.parse::<u32>().ok()) == Some(uid) {
            return Some(package.to_string());
        }
    }
    None
}


fn peer_uid_of(stream: &UnixStream) -> anyhow::Result<u32> {
    let mut cred: libc::ucred = unsafe { std::mem::zeroed() };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::c_uint;
    let ret = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            std::ptr::addr_of_mut!(cred).cast(),
            std::ptr::addr_of_mut!(len),
        )
    };
    if ret != 0 {
        anyhow::bail!("getsockopt(SO_PEERCRED) failed");
    }
    Ok(cred.uid)
}
