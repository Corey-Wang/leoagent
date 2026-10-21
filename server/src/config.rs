//! ~/.config/leoagent/config.toml — created with a random token on first run.
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    /// Address the HTTP server binds to. Keep it on 127.0.0.1 and expose it through a tunnel / reverse proxy with TLS.
    pub listen: String,
    /// The single API token (phone app sends it as `X-Token`). Generated on first run.
    pub token: String,
    /// Temporary migration switch for old clients. Device pairing is the default.
    pub legacy_token_enabled: bool,
    /// Public base URL the phone uses (e.g. https://agent.example.com). Only used for the pairing QR code.
    pub public_url: String,
    /// Where bridge.db, the sandbox project, uploaded app logs and published APKs live.
    pub data_dir: String,
    /// Projects to register on first start (absolute paths or ~/...). The sandbox is always added.
    pub projects: Vec<String>,
    /// Optional local speech-to-text server (stt/stt.py). Empty = disabled.
    pub stt_url: String,
    /// Experimental Codex desktop sync. Only has effect when built with `--features desktop-sync`.
    pub desktop_sync: bool,
    /// Directory containing helper binaries (frontwin for app-window screenshots). Default: <data_dir>/bin
    pub helpers_dir: String,
    /// Global limit for active and unresolved tasks; a project still permits one.
    pub max_active_tasks: usize,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            listen: "127.0.0.1:8737".into(),
            token: String::new(),
            legacy_token_enabled: false,
            public_url: String::new(),
            data_dir: "~/.local/share/leoagent".into(),
            projects: vec![],
            stt_url: String::new(),
            desktop_sync: false,
            helpers_dir: String::new(),
            max_active_tasks: 4,
        }
    }
}

static CFG: OnceLock<Config> = OnceLock::new();
static PATH: OnceLock<PathBuf> = OnceLock::new();
pub fn get() -> &'static Config {
    CFG.get().expect("config not loaded")
}
pub fn path() -> &'static Path {
    PATH.get().expect("config not loaded")
}

pub fn default_path() -> PathBuf {
    if let Ok(p) = std::env::var("LEOAGENT_CONFIG") {
        return PathBuf::from(p);
    }
    let base = std::env::var("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| crate::db::home().join(".config"));
    base.join("leoagent").join("config.toml")
}

pub fn gen_token() -> String {
    let a = uuid::Uuid::new_v4().simple().to_string();
    let b = uuid::Uuid::new_v4().simple().to_string();
    format!("la_{}{}", &a[..20], &b[..20])
}

/// Load config, creating it (with a fresh random token, mode 0600) if missing.
pub fn load(p: &Path) -> Result<(Config, bool), String> {
    let mut created = false;
    let mut c: Config = if p.exists() {
        let t = std::fs::read_to_string(p).map_err(|e| format!("read {}: {e}", p.display()))?;
        toml::from_str(&t)
            .map_err(|e: toml::de::Error| format!("parse {}: {}", p.display(), e.message()))?
    } else {
        created = true;
        Config::default()
    };
    if c.token.trim().is_empty() {
        c.token = gen_token();
        created = true;
    }
    if !(1..=64).contains(&c.max_active_tasks) {
        return Err("max_active_tasks must be between 1 and 64".into());
    }
    if created {
        write(p, &c)?;
    }
    Ok((c, created))
}

pub fn write(p: &Path, c: &Config) -> Result<(), String> {
    let parent = p
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let body = format!(
        "# LeoAgent server config. Keep this file private: it contains your API token.\n{}",
        toml::to_string_pretty(c).map_err(|e| e.to_string())?
    );
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let temporary = p.with_extension(format!("{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| -> std::io::Result<()> {
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)?;
        file.write_all(body.as_bytes())?;
        file.sync_all()?;
        std::fs::rename(&temporary, p)?;
        std::fs::File::open(parent)?.sync_all()?;
        Ok(())
    })();
    if let Err(e) = result {
        let _ = std::fs::remove_file(&temporary);
        return Err(e.to_string());
    }
    Ok(())
}

pub fn install(p: PathBuf, c: Config) {
    let _ = PATH.set(p);
    let _ = CFG.set(c);
}
pub fn data_dir() -> PathBuf {
    crate::db::expand_tilde(&get().data_dir)
}
pub fn helpers_dir() -> PathBuf {
    if get().helpers_dir.is_empty() {
        data_dir().join("bin")
    } else {
        crate::db::expand_tilde(&get().helpers_dir)
    }
}
pub fn desktop_sync() -> bool {
    cfg!(feature = "desktop-sync") && get().desktop_sync
}
