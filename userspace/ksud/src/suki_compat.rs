//! Command spellings the SukiSU manager expects.
//!
//! The manager UI in this port was written against SukiSU's ksud, which writes
//! app profiles through `temporary-root apply <uid> <mode> <expected> <json>`
//! instead of `profile set`. The JSON is the manager's own profile object
//! (snake-free, camelCase), so it is translated here into the kernel layout.
//!
//! `mode` and `expectedRoot` carry the lease bookkeeping SukiSU does in the
//! daemon; this kernel has no lease command, so a profile is simply written and
//! `expectedRoot` only guards against a stale editor overwriting a revoke that
//! already happened.

use anyhow::{Context, Result, bail};
use serde::Deserialize;

use crate::{ksu_uapi, ksucalls};

/// Root identity a profile defaults to when the manager leaves those fields out.
const ROOT_UID: i32 = 0;
const ROOT_GID: i32 = 0;
const KERNEL_SU_DOMAIN: &str = "u:r:ksu:s0";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProfileJson {
    #[serde(default)]
    name: String,
    #[serde(default)]
    current_uid: i32,
    #[serde(default)]
    allow_su: bool,
    #[serde(default = "yes")]
    root_use_default: bool,
    #[serde(default)]
    root_template: Option<String>,
    #[serde(default = "root_uid")]
    uid: i32,
    #[serde(default = "root_gid")]
    gid: i32,
    #[serde(default)]
    groups: Vec<i32>,
    #[serde(default)]
    capabilities: Vec<i64>,
    #[serde(default = "ksu_domain")]
    context: String,
    #[serde(default)]
    namespace: i32,
    #[serde(default = "yes")]
    non_root_use_default: bool,
    #[serde(default = "yes")]
    umount_modules: bool,
    #[serde(default)]
    rules: String,
    #[serde(default)]
    flags: i64,
}

fn yes() -> bool {
    true
}
fn root_uid() -> i32 {
    ROOT_UID
}
fn root_gid() -> i32 {
    ROOT_GID
}
fn ksu_domain() -> String {
    KERNEL_SU_DOMAIN.to_string()
}

/// `temporary-root apply <manager-uid> <mode> <expected-root> <json>`
pub fn temporary_root_apply(
    _manager_uid: i32,
    _mode: &str,
    expected_root: bool,
    json: &str,
) -> Result<()> {
    let wanted: ProfileJson =
        serde_json::from_str(json).context("could not parse the app profile")?;
    if wanted.name.is_empty() {
        bail!("the profile carries no key");
    }

    let uid = if wanted.current_uid != 0 {
        wanted.current_uid
    } else {
        uid_of_package(&wanted.name).unwrap_or(wanted.uid)
    };

    // `expectedRoot` is the state the editor was showing. Refuse when the kernel
    // has moved on since then, which is how a stale editor is kept from
    // resurrecting a grant that was already revoked.
    let current = match ksucalls::get_app_profile(&wanted.name, uid)? {
        Some(profile) => profile.allow_su,
        None => false,
    };
    if current != expected_root {
        bail!("profile state moved on (kernel says {current}, editor expected {expected_root})");
    }

    let mut profile = ksucalls::new_profile(&wanted.name, uid)?;
    profile.allow_su = wanted.allow_su;

    if wanted.allow_su {
        let mut rp: ksu_uapi::app_profile__bindgen_ty_1__bindgen_ty_1 = unsafe { std::mem::zeroed() };
        rp.use_default = wanted.root_use_default;
        if let Some(template) = wanted.root_template.as_deref() {
            ksucalls::write_c_str(&mut rp.template_name, template).context("template")?;
        }
        rp.profile.uid = wanted.uid;
        rp.profile.gid = wanted.gid;
        if wanted.groups.len() > rp.profile.groups.len() {
            bail!(
                "{} groups given, and the kernel keeps at most {}",
                wanted.groups.len(),
                rp.profile.groups.len()
            );
        }
        rp.profile.groups_count = wanted.groups.len() as u32;
        rp.profile.groups[..wanted.groups.len()].copy_from_slice(&wanted.groups);
        // The manager hands over a list of capability numbers; the kernel keeps
        // three masks. Granting every named capability in all three sets is what
        // its old JNI layer did.
        let mut caps: u64 = 0;
        for cap in &wanted.capabilities {
            if *cap < 0 || *cap >= 64 {
                bail!("capability {cap} is out of range");
            }
            caps |= 1u64 << *cap;
        }
        rp.profile.capabilities.effective = caps;
        rp.profile.capabilities.permitted = caps;
        rp.profile.capabilities.inheritable = caps;
        ksucalls::write_c_str(&mut rp.profile.selinux_domain, &wanted.context)
            .context("selinux domain")?;
        rp.profile.namespaces = wanted.namespace;
        rp.profile.flags = wanted.flags as u64;
        profile.__bindgen_anon_1.rp_config = rp;
    } else {
        let mut nrp: ksu_uapi::app_profile__bindgen_ty_1__bindgen_ty_2 = unsafe { std::mem::zeroed() };
        nrp.use_default = wanted.non_root_use_default;
        nrp.profile.umount_modules = wanted.umount_modules;
        profile.__bindgen_anon_1.nrp_config = nrp;
    }

    ksucalls::set_app_profile(&profile)
}

/// `temporary-root status <uid>` - the lease state the manager polls once a second.
///
/// This kernel keeps no lease table, so a grant can only be reported as
/// `permanent`; the manager treats anything else as "no lease".
pub fn temporary_root_status(uid: i32) -> Result<()> {
    let _ = uid;
    println!("none");
    Ok(())
}

/// The uid Android currently gives a package.
fn uid_of_package(package: &str) -> Option<i32> {
    let list = "/data/system/packages.list";
    let text = std::fs::read_to_string(list).ok()?;
    for line in text.lines() {
        let mut fields = line.split_whitespace();
        if fields.next() == Some(package) {
            return fields.next()?.parse().ok();
        }
    }
    None
}

/// Where the root bridge listens for su requests. Under /data/local/tmp because
/// the asking process is an ordinary app uid and the socket has to be reachable
/// before anything is granted.
pub const REQUEST_SOCKET: &str = "/data/local/tmp/.ksu-req.sock";

/// Ask the manager whether this uid may have root, blocking until it answers.
///
/// KernelSU-Next's kernel has no notion of a pending request: an uid is either
/// on the allowlist or it silently runs unprivileged. The manager is therefore
/// asked through the bridge, and approval means the profile is written before
/// this returns, so the following ioctl succeeds.
pub fn request_root_approval() -> bool {
    use std::io::{Read, Write};
    use std::os::unix::net::UnixStream;

    let path = std::env::var("KSU_REQUEST_SOCKET").unwrap_or_else(|_| REQUEST_SOCKET.to_string());
    let mut stream = match UnixStream::connect(&path) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("no su request bridge at {path}: {e}");
            return false;
        }
    };

    let cmdline = std::env::args().collect::<Vec<_>>().join(" ");
    // First line: the requesting process, so the manager can name something that
    // has no package of its own (shell, a helper spawned by an app).
    let name = std::fs::read_to_string("/proc/self/cmdline")
        .map(|raw| raw.split('\0').next().unwrap_or("").to_string())
        .unwrap_or_default();
    let payload = format!("{name}\n{cmdline}");
    let len = payload.len() as u32;
    if stream.write_all(&len.to_le_bytes()).is_err()
        || stream.write_all(payload.as_bytes()).is_err()
    {
        return false;
    }

    let mut answer = [0u8; 1];
    match stream.read_exact(&mut answer) {
        Ok(()) => answer[0] == 1,
        Err(_) => false,
    }
}
