//! Read-only Codex state_5.sqlite + rollout jsonl
use crate::db::{home, tilde, now};
use crate::py;
use parking_lot::Mutex;
use rusqlite::{Connection, params};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

fn cx() -> PathBuf { home().join(".codex") }
fn db() -> Connection {
    Connection::open_with_flags(
        format!("file:{}?mode=ro", cx().join("state_5.sqlite").display()),
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_URI,
    ).expect("state_5")
}

static OPEN_CACHE: OnceLock<Mutex<(f64, HashMap<String, String>)>> = OnceLock::new();

pub fn opened() -> HashMap<String, String> {
    let c = OPEN_CACHE.get_or_init(|| Mutex::new((0.0, HashMap::new())));
    let mut g = c.lock();
    if now() - g.0 < 15.0 { return g.1.clone() }
    let mut res = HashMap::new();
    let pids = Command::new("pgrep").arg("-x").arg("codex").output().ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).split_whitespace().map(str::to_string).collect::<Vec<_>>()).unwrap_or_default();
    let mut kind = HashMap::new();
    for p in &pids {
        let pp = Command::new("ps").args(["-o","ppid=","-p",p]).output().ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_default();
        let cmd = if pp.is_empty() { String::new() } else {
            Command::new("ps").args(["-o","command=","-p",&pp]).output().ok().map(|o| String::from_utf8_lossy(&o.stdout).to_string()).unwrap_or_default()
        };
        let k = if cmd.contains("ChatGPT.app/Contents/MacOS") || cmd.contains("Codex.app") { "desktop" }
            else if cmd.contains("codex-acp") { "bridge" } else { "other" };
        kind.insert(p.clone(), k.to_string());
    }
    if !pids.is_empty() {
        if let Ok(out) = Command::new("lsof").args(["-p", &pids.join(","), "-Fpn"]).output() {
            let mut cur = String::new();
            for l in String::from_utf8_lossy(&out.stdout).lines() {
                if let Some(p) = l.strip_prefix('p') { cur = p.to_string() }
                else if let Some(n) = l.strip_prefix('n') {
                    if n.contains("rollout-") && n.ends_with(".jsonl") {
                        let tid = if n.len() >= 41 { &n[n.len()-41..n.len()-6] } else { continue };
                        let k = kind.get(&cur).cloned().unwrap_or_else(|| "other".into());
                        if res.get(tid).map(|x: &String| x.as_str()) != Some("desktop") { res.insert(tid.to_string(), k); }
                    }
                }
            }
        }
    }
    g.0 = now(); g.1 = res.clone(); res
}

static ORIG: &[(&str, &str)] = &[
    ("Codex Desktop","desktop"),("codex_work_desktop","desktop"),("codex-tui","cli"),("codex_exec","cli"),("@agentclientprotocol/codex-acp","acp")];

pub fn list_threads(q: &str, limit: i64) -> Vec<Value> {
    let c = db();
    let projs: Vec<(String, String, i64, String)> = {
        let mut s = c.prepare("select p.id,p.name,p.position,r.path from projects p join project_roots r on r.project_id=p.id order by p.position").unwrap();
        s.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))).unwrap().filter_map(|x| x.ok()).collect()
    };
    let mut sql = String::from(
        "select id,coalesce(nullif(name,''),nullif(title,''),first_user_message,'') t,cwd,originator,source,recency_at_ms,updated_at_ms,is_pinned,rollout_path, coalesce(git_branch,''),coalesce(first_user_message,'') from threads where archived=0 and source not like '{%' and (thread_source='user' or (originator like '%codex-acp%' and coalesce(first_user_message,'')<>''))");
    let mut a: Vec<Value> = vec![];
    if !q.is_empty() {
        sql.push_str(" and (name like ? or title like ? or first_user_message like ? or cwd like ?)");
        let pat = format!("%{q}%"); for _ in 0..4 { a.push(json!(pat)); }
    }
    sql.push_str(" order by coalesce(recency_at_ms,updated_at_ms) desc limit ?");
    a.push(json!(limit));
    let mut stmt = c.prepare(&sql).unwrap();
    let params: Vec<Box<dyn rusqlite::ToSql>> = a.iter().map(|v| -> Box<dyn rusqlite::ToSql> {
        match v { Value::Number(n) if n.is_i64() => Box::new(n.as_i64().unwrap()), Value::String(s) => Box::new(s.clone()), _ => Box::new(v.to_string()) }
    }).collect();
    let refs: Vec<&dyn rusqlite::ToSql> = params.iter().map(|x| x.as_ref()).collect();
    let rows: Vec<_> = stmt.query_map(refs.as_slice(), |r| {
        Ok((r.get::<_,String>(0)?, r.get::<_,String>(1)?, r.get::<_,String>(2)?, r.get::<_,Option<String>>(3)?, r.get::<_,Option<String>>(4)?,
            r.get::<_,Option<i64>>(5)?, r.get::<_,Option<i64>>(6)?, r.get::<_,i64>(7)?, r.get::<_,Option<String>>(8)?, r.get::<_,String>(9)?))
    }).unwrap().filter_map(|x| x.ok()).collect();
    drop(stmt); drop(c);
    let op = opened(); let mut out = vec![];
    for (id, title, cwd, originator, source, recency, updated, pinned, rollout, branch) in rows {
        let mut pj = None;
        let mut projs_s = projs.clone();
        projs_s.sort_by_key(|x| std::cmp::Reverse(x.3.len()));
        for p in &projs_s {
            if cwd == p.3 || cwd.starts_with(&(p.3.trim_end_matches('/').to_string() + "/")) { pj = Some(p.clone()); break }
        }
        let st = op.get(&id).cloned();
        let active = rollout.as_ref().map(|rp| {
            Path::new(rp).exists() && fs::metadata(rp).ok().and_then(|m| m.modified().ok()).map(|t| now() - t.duration_since(UNIX_EPOCH).unwrap().as_secs_f64() < 90.0).unwrap_or(false)
        }).unwrap_or(false);
        let origin = ORIG.iter().find(|(k,_)| originator.as_deref() == Some(*k)).map(|(_,v)| *v)
            .unwrap_or(if source.as_deref() == Some("cli") { "cli" } else if source.as_deref() == Some("vscode") { "desktop" } else { "other" });
        let title = title.trim().lines().next().unwrap_or("新对话").chars().take(80).collect::<String>();
        let title = if title.is_empty() { "新对话".into() } else { title };
        out.push(json!({
            "id": id, "title": title, "cwd": tilde(&cwd), "origin": origin,
            "updated": (recency.or(updated).unwrap_or(0) as f64) / 1000.0,
            "pinned": pinned != 0, "project": pj.as_ref().map(|p| p.1.clone()), "project_id": pj.as_ref().map(|p| p.0.clone()),
            "project_pos": pj.as_ref().map(|p| p.2).unwrap_or(999),
            "open_on_mac": st.as_deref() == Some("desktop"), "active": active, "branch": branch
        }));
    }
    out
}

pub fn thread_row(tid: &str) -> Option<(String, Option<String>, String, String)> {
    let c = db();
    c.query_row("select id,rollout_path,cwd,coalesce(nullif(name,''),nullif(title,''),first_user_message,'') from threads where id=?", params![tid],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))).ok()
}

pub fn resolve_rollout(path: Option<&str>) -> Option<PathBuf> {
    let path = path?;
    for p in [path.to_string(), format!("{path}.zst"),
        path.replace("/sessions/", "/archived_sessions/"),
        path.replace("/sessions/", "/archived_sessions/") + ".zst"] {
        if Path::new(&p).exists() { return Some(PathBuf::from(p)) }
    }
    let tid = if path.len() >= 42 { &Path::new(path).file_name()?.to_string_lossy()[path.len().saturating_sub(42).min(Path::new(path).file_name()?.len())..] } else { return None };
    // basename[-42:-6] in Python for rollout-xxx.jsonl
    let bn = Path::new(path).file_name()?.to_string_lossy();
    if bn.len() < 42 { return None }
    let tid = &bn[bn.len()-42..bn.len()-6];
    let mut g = vec![];
    for base in [cx().join("sessions"), cx().join("archived_sessions")] {
        if let Ok(walk) = walkdir_glob(&base, tid) { g.extend(walk) }
    }
    g.into_iter().next()
}
fn walkdir_glob(base: &Path, tid: &str) -> std::io::Result<Vec<PathBuf>> {
    let mut out = vec![];
    fn rec(d: &Path, tid: &str, out: &mut Vec<PathBuf>) {
        let Ok(rd) = fs::read_dir(d) else { return };
        for e in rd.filter_map(|x| x.ok()) {
            let p = e.path();
            if p.is_dir() { rec(&p, tid, out) }
            else if p.file_name().map(|n| n.to_string_lossy().contains(tid) && n.to_string_lossy().contains(".jsonl")).unwrap_or(false) { out.push(p) }
        }
    }
    rec(base, tid, &mut out); Ok(out)
}

static SKIP_MSG: &[&str] = &["# AGENTS.md","<environment_context","<app-context","<INSTRUCTIONS","<user_instructions","<permissions","<turn_aborted"];

pub fn parse_rollout(path: Option<&str>, trunc: fn(&str, usize) -> String) -> Vec<(String, Value)> {
    let Some(path) = resolve_rollout(path) else { return vec![] };
    let text = if path.extension().and_then(|e| e.to_str()) == Some("zst") || path.to_string_lossy().ends_with(".jsonl.zst") {
        let raw = fs::read(&path).unwrap_or_default();
        zstd::decode_all(&raw[..]).ok().and_then(|b| String::from_utf8(b).ok()).unwrap_or_default()
    } else { fs::read_to_string(&path).unwrap_or_default() };
    let mut ev = vec![]; let mut tools: HashMap<String, Value> = HashMap::new();
    for line in text.lines() {
        let Ok(o) = serde_json::from_str::<Value>(line) else { continue };
        if o.get("type").and_then(|x| x.as_str()) != Some("response_item") { continue }
        let p = o.get("payload").cloned().unwrap_or(json!({}));
        let t = p.get("type").and_then(|x| x.as_str()).unwrap_or("");
        if t == "message" {
            let content = p.get("content").and_then(|x| x.as_array()).cloned().unwrap_or_default();
            let txt: String = content.iter().filter_map(|x| x.get("text").and_then(|t| t.as_str())).collect();
            let role = p.get("role").and_then(|x| x.as_str()).unwrap_or("");
            if role == "user" {
                let st = txt.trim();
                if st.is_empty() || SKIP_MSG.iter().any(|s| st.starts_with(s)) || st.starts_with('<') { continue }
                let imgs = content.iter().filter(|x| x.get("type").and_then(|t| t.as_str()) == Some("input_image")).count();
                ev.push(("user".into(), json!({"text": st, "images": imgs})));
            } else if role == "assistant" && !txt.trim().is_empty() {
                ev.push(("msg".into(), json!({"text": txt})));
            }
        } else if t == "reasoning" {
            let s: String = p.get("summary").and_then(|x| x.as_array()).into_iter().flatten()
                .filter_map(|x| x.get("text").and_then(|t| t.as_str())).collect();
            if !s.trim().is_empty() { ev.push(("thought".into(), json!({"text": s}))); }
        } else if matches!(t, "function_call"|"custom_tool_call"|"local_shell_call") {
            let name = p.get("name").and_then(|x| x.as_str()).unwrap_or("shell");
            let cid = p.get("call_id").or_else(|| p.get("id")).and_then(|x| x.as_str()).unwrap_or("").to_string();
            let mut d = json!({"id": cid, "status": "completed", "kind": "execute", "title": name});
            if t == "custom_tool_call" {
                let inp = p.get("input").and_then(|x| x.as_str()).unwrap_or("");
                let title: String = if name == "apply_patch" {
                    inp.lines().filter(|l| l.starts_with("*** ") && l.contains("File:")).map(|l| l.split(": ").nth(1).unwrap_or("")).collect::<Vec<_>>().join(" ")
                } else { name.to_string() };
                d["kind"] = json!(if name == "apply_patch" {"edit"} else {"other"});
                d["title"] = json!(title.chars().take(200).collect::<String>());
                d["output"] = json!(trunc(inp, 6000));
            } else {
                let args: Value = serde_json::from_str(p.get("arguments").and_then(|x| x.as_str()).unwrap_or("{}")).unwrap_or(json!({}));
                let mut cmd = args.get("cmd").or_else(|| args.get("command")).cloned().unwrap_or(Value::Null);
                if let Value::Array(a) = &cmd { cmd = json!(a.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join(" ")); }
                if let Some(c) = cmd.as_str() {
                    d["title"] = json!(c.chars().take(200).collect::<String>());
                    d["cmd"] = json!(trunc(c, 600));
                } else if name != "exec_command" {
                    d["kind"] = json!("other"); d["title"] = json!(name);
                }
            }
            tools.insert(cid.clone(), d.clone());
            ev.push(("tool".into(), d));
        } else if matches!(t, "function_call_output"|"custom_tool_call_output") {
            let cid = p.get("call_id").and_then(|x| x.as_str()).unwrap_or("");
            let Some(d) = tools.get_mut(cid) else { continue };
            let out = match p.get("output") { Some(Value::String(s)) => s.clone(), Some(v) => py::dumps(v), _ => String::new() };
            if let Some(m) = regex::Regex::new(r"exited with code (\d+)|Exit code: (\d+)").unwrap().captures(&out.chars().take(300).collect::<String>()) {
                let code = m.get(1).or(m.get(2)).unwrap().as_str().parse::<i64>().unwrap_or(0);
                d["exit"] = json!(code);
            }
            if d.get("kind").and_then(|x| x.as_str()) != Some("edit") { d["output"] = json!(trunc(&out, 6000)); }
            ev.push(("tool".into(), d.clone()));
        }
    }
    ev
}
