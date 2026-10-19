use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

fn bin() -> PathBuf {
    crate::config::helpers_dir().join("frontwin")
}
fn now() -> f64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs_f64() }
static CACHE: OnceLock<Mutex<HashMap<(String,i64,i64),(f64,Vec<u8>,Value)>>> = OnceLock::new();

pub fn front() -> (i64, String) {
    let o = Command::new(bin()).output().ok();
    let s = o.map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_default();
    let mut parts = s.split('\t');
    let wid: i64 = parts.next().unwrap_or("0").parse().unwrap_or(0);
    let name = parts.next().unwrap_or("").to_string();
    (wid, name)
}
pub fn grab(mode: &str, w: i64, q: i64) -> (Option<Vec<u8>>, Value) {
    let w = w.clamp(240, 1600); let q = q.clamp(20, 90);
    let key = (mode.to_string(), w, q);
    let c = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    {
        let g = c.lock();
        if let Some((t, b, m)) = g.get(&key) { if now() - t < 0.4 { return (Some(b.clone()), m.clone()) } }
    }
    let (wid, name) = if mode == "app" { front() } else { (0, String::new()) };
    let tmp = std::env::temp_dir();
    let png = tmp.join(format!("leoagent-sc-{}.png", std::process::id()));
    let jpg = tmp.join(format!("leoagent-sc-{}.jpg", std::process::id()));
    let mut cmd = Command::new("/usr/sbin/screencapture");
    if mode == "app" && wid != 0 {
        cmd.args(["-x","-o","-t","png","-l",&wid.to_string()]);
    } else {
        cmd.args(["-x","-m","-t","png"]);
    }
    cmd.arg(&png);
    let r = cmd.output();
    let size = fs::metadata(&png).map(|m| m.len()).unwrap_or(0);
    if size == 0 {
        let err = r.ok().and_then(|o| { let e = String::from_utf8_lossy(&o.stderr).trim().to_string(); if e.is_empty() { None } else { Some(e) } }).unwrap_or_else(|| "empty".into());
        let _ = fs::remove_file(&png);
        return (None, json!({"error":"capture_failed","detail":err,"front":name,
            "hint":"系统设置 > 隐私与安全性 > 屏幕与系统录音 中允许 leoagent-server, 并保持 Mac 已登录且未锁屏"}));
    }
    let _ = Command::new("/usr/bin/sips").args(["-s","format","jpeg","-s","formatOptions",&q.to_string(),"--resampleWidth",&w.to_string(),
        png.to_str().unwrap(),"--out",jpg.to_str().unwrap()]).output();
    let b = fs::read(&jpg).unwrap_or_default();
    let meta = json!({"front": if mode=="app" {name} else {"桌面".into()}, "wid": wid, "ts": now()});
    let _ = fs::remove_file(&png); let _ = fs::remove_file(&jpg);
    c.lock().insert(key, (now(), b.clone(), meta.clone()));
    (Some(b), meta)
}
