//! Read-only diagnostics: never start an agent or inspect model credentials.
use crate::{agents, config, db};
use serde_json::{json, Value};
use std::path::Path;
pub fn report(path: &Path) -> Value {
    let cfg = std::fs::read_to_string(path)
        .map_err(|e| e.to_string())
        .and_then(|s| toml::from_str::<config::Config>(&s).map_err(|e| e.message().to_string()));
    let mut out = json!({"version":env!("CARGO_PKG_VERSION"),"platform":std::env::consts::OS,
        "config_path":path,"agent_authentication":"not_checked","started_agent":false,
        "agents":agents::reg().into_iter().map(|a|{
            let command=agents::cmd(a.id);
            json!({"id":a.id,"command":command,"executable_found":agents::installed(&a)})
        }).collect::<Vec<_>>()});
    match cfg {
        Ok(c) => {
            out["config"] = json!({"ok":true,"listen":c.listen,"public_url_configured":!c.public_url.is_empty(),
                "data_directory_exists":db::expand_tilde(&c.data_dir).is_dir(),"max_active_tasks":c.max_active_tasks,
                "legacy_token_enabled":c.legacy_token_enabled,"desktop_sync":c.desktop_sync});
        }
        Err(e) => {
            out["config"] = json!({"ok":false,"error":e});
        }
    }
    out
}
