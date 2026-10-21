//! An OS lock, not a stale PID file, owns a data directory.
use std::fs::{File, OpenOptions};
use std::io;
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

pub struct InstanceLock {
    _file: File,
}
impl InstanceLock {
    pub fn acquire(base: &Path) -> io::Result<Self> {
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(base.join("server.lock"))?;
        if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            return Err(io::Error::new(
                io::ErrorKind::AddrInUse,
                "该数据目录已有运行中的 LeoAgent 服务",
            ));
        }
        Ok(Self { _file: file })
    }
}
