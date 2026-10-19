use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Clone)]
pub struct AgentDef {
    pub id: &'static str, pub name: &'static str, pub icon: &'static str,
    pub cmd: &'static [&'static str],
    pub defaults: Value,
    pub modes: HashMap<&'static str, (&'static str, &'static str)>,
}
pub fn path_env() -> String {
    let home = std::env::var("HOME").unwrap_or_default();
    format!("/opt/homebrew/bin:/usr/local/bin:{home}/.local/bin:{home}/.npm-global/bin:/usr/bin:/bin:/usr/sbin:/sbin")
}
fn modes(pairs: &[(&'static str, &'static str, &'static str)]) -> HashMap<&'static str, (&'static str, &'static str)> {
    pairs.iter().map(|(k,a,b)| (*k, (*a,*b))).collect()
}
pub fn reg() -> Vec<AgentDef> {
    vec![
        AgentDef { id:"codex", name:"Codex", icon:"◎", cmd:&["codex-acp"], defaults:json!({}), modes:modes(&[]) },
        AgentDef { id:"claude", name:"Claude", icon:"✳", cmd:&["claude-agent-acp"], defaults:json!({}), modes:modes(&[("auto","mode","acceptEdits"),("ask","mode","default")]) },
        AgentDef { id:"deepseek", name:"DeepSeek", icon:"🐋", cmd:&["deepseek-acp"], defaults:json!({}), modes:modes(&[("auto","sandbox","workspace-write"),("ask","sandbox","read-only")]) },
        AgentDef { id:"opencode", name:"OpenCode", icon:"⌬", cmd:&["opencode","acp"], defaults:json!({}), modes:modes(&[]) },
        AgentDef { id:"gemini", name:"Gemini", icon:"✦", cmd:&["gemini","--acp"], defaults:json!({}), modes:modes(&[]) },
        AgentDef { id:"qwen", name:"Qwen Code", icon:"Q", cmd:&["qwen","--acp"], defaults:json!({}), modes:modes(&[]) },
        AgentDef { id:"goose", name:"Goose", icon:"🪿", cmd:&["goose","acp"], defaults:json!({}), modes:modes(&[]) },
        AgentDef { id:"kimi", name:"Kimi", icon:"K", cmd:&["kimi","--acp"], defaults:json!({}), modes:modes(&[]) },
        AgentDef { id:"auggie", name:"Auggie", icon:"A", cmd:&["auggie","--acp"], defaults:json!({}), modes:modes(&[]) },
    ]
}
pub fn get(aid: &str) -> AgentDef { reg().into_iter().find(|a| a.id == aid).unwrap_or_else(|| reg()[0].clone()) }
pub fn which(bin: &str) -> Option<PathBuf> {
    Command::new("/usr/bin/which").env("PATH", path_env()).arg(bin).output().ok()
        .filter(|o| o.status.success()).map(|o| PathBuf::from(String::from_utf8_lossy(&o.stdout).trim()))
}
pub fn installed(a: &AgentDef) -> bool { which(a.cmd[0]).is_some() }
pub fn cmd(aid: &str) -> Vec<String> {
    let a = get(aid); let p = which(a.cmd[0]);
    let mut v = vec![p.map(|x| x.to_string_lossy().into_owned()).unwrap_or_else(|| a.cmd[0].to_string())];
    v.extend(a.cmd[1..].iter().map(|s| s.to_string())); v
}
pub fn flat_options(o: &Value) -> Vec<Value> {
    let mut L = vec![];
    for x in o.get("options").and_then(|x| x.as_array()).into_iter().flatten() {
        if x.get("options").is_some() && x.get("value").is_none() {
            for y in x.get("options").and_then(|x| x.as_array()).into_iter().flatten() {
                let mut yy = y.clone(); if let Some(m) = yy.as_object_mut() { m.insert("group".into(), x.get("name").or_else(|| x.get("group")).cloned().unwrap_or(Value::Null)); }
                L.push(yy);
            }
        } else { L.push(x.clone()) }
    }
    L
}
pub fn probe(aid: &str, _cwd: &Path, _env: &HashMap<String,String>, _do_prompt: bool, _extra: &Value) -> Value {
    let a = get(aid);
    let ts = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs_f64();
    if !installed(&a) { return json!({"ok": false, "ts": ts, "err": "未安装"}) }
    json!({"ok": false, "ts": ts, "err": "probe deferred to 2b"})
}
