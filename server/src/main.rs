//! leoagent-server: drive Codex / ACP coding agents on this machine from the LeoAgent phone app.
mod agents; mod appapi; mod ask; mod config; mod db; mod events; mod files; mod history; mod orch; mod py; mod runtime; mod screen; mod threads;
#[cfg(feature = "desktop-sync")] mod desk;
pub use orch::{done_note, set_title, queue_prompt};
use axum::{body::Body, extract::Request, http::{header::HeaderName, HeaderMap, HeaderValue, Method, Response, StatusCode}, Router};
use db::{Db, home, now, tilde, expand_tilde};
use events::Bus;
use parking_lot::Mutex;
use runtime::AgentLike;
use orch::State;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::sync::{Arc, OnceLock};
use std::thread;
use std::time::Duration;

static S: OnceLock<Arc<State>> = OnceLock::new();
pub(crate) fn st() -> &'static Arc<State> { S.get().unwrap() }

type Resp = Response<Body>;
fn j(code: u16, o: &Value, accept_enc: &str) -> Resp {
    let mut b = py::dumps(o).into_bytes(); let mut gz = false;
    if b.len() > 2048 && accept_enc.contains("gzip") {
        use flate2::{write::GzEncoder, Compression}; use std::io::Write;
        let mut e = GzEncoder::new(Vec::new(), Compression::new(5));
        let _ = e.write_all(&b); b = e.finish().unwrap_or(b); gz = true;
    }
    let mut r = Response::new(Body::from(b.clone()));
    *r.status_mut() = StatusCode::from_u16(code).unwrap();
    let h = r.headers_mut();
    h.insert("Content-Type", "application/json; charset=utf-8".parse().unwrap());
    h.insert("Content-Length", b.len().to_string().parse().unwrap());
    h.insert("Cache-Control", "no-store".parse().unwrap());
    if gz { h.insert("Content-Encoding", "gzip".parse().unwrap()); }
    r
}
fn j_err(code: u16, msg: &str) -> Resp { j(code, &json!({"error": msg}), "") }
fn parse_qs(q: &str) -> HashMap<String, String> {
    let mut m = HashMap::new();
    for (k, vs) in py::parse_qs(q) { if let Some(v) = vs.into_iter().next() { m.insert(k, v); } }
    m
}
fn under_home(p: &str) -> Option<PathBuf> {
    let rp = std::fs::canonicalize(expand_tilde(p)).ok()?;
    let h = home();
    if rp == h || rp.starts_with(&h) { Some(rp) } else { None }
}
pub(crate) fn sess_row(sid: &str) -> Option<Value> {
    let db = &st().db;
    db.q1("select s.id,s.project_id,s.acp_sid,s.title,s.model,s.effort,s.mode,s.created,s.updated,p.path,p.name,coalesce(s.agent,'codex') from sessions s join projects p on p.id=s.project_id where s.id=?",
        rusqlite::params![sid], |r| {
            Ok(json!({"id": r.get::<_,String>(0)?, "project_id": r.get::<_,i64>(1)?, "acp_sid": r.get::<_,Option<String>>(2)?,
                "title": r.get::<_,Option<String>>(3)?, "model": r.get::<_,Option<String>>(4)?, "effort": r.get::<_,Option<String>>(5)?,
                "mode": r.get::<_,Option<String>>(6)?, "created": r.get::<_,f64>(7)?, "updated": r.get::<_,f64>(8)?,
                "path": r.get::<_,String>(9)?, "project": r.get::<_,String>(10)?, "agent": r.get::<_,String>(11)?}))
        })
}
pub(crate) fn agent_of(sid: &str) -> String {
    st().db.q1("select coalesce(agent,'codex') from sessions where id=?", rusqlite::params![sid], |r| r.get::<_,String>(0)).unwrap_or_else(|| "codex".into())
}
fn seed(db: &Db, sandbox: &Path) {
    let n: i64 = db.q1("select count(*) from projects", [], |r| r.get(0)).unwrap_or(0);
    if n > 0 { return }
    let mut cands = vec![sandbox.to_path_buf()];
    for x in &config::get().projects { cands.push(expand_tilde(x)); }
    for p in cands {
        if p.is_dir() {
            db.exec("insert or ignore into projects(name,path,created) values(?,?,?)",
                rusqlite::params![p.file_name().and_then(|n|n.to_str()).unwrap_or(""), p.to_string_lossy().as_ref(), now()]);
        }
    }
}

async fn handle(req: Request) -> Resp {
    let method = req.method().clone();
    let path_q = req.uri().path_and_query().map(|x| x.as_str().to_string()).unwrap_or_else(|| "/".into());
    let headers = req.headers().clone();
    let body = axum::body::to_bytes(req.into_body(), 64*1024*1024).await.unwrap_or_default();
    tokio::task::spawn_blocking(move || route(method, path_q, headers, body)).await.unwrap_or_else(|_| j_err(500, "join"))
}

fn route(method: Method, path_q: String, headers: HeaderMap, body: bytes::Bytes) -> Resp {
    let accept = headers.get("accept-encoding").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
    let mut path = py::unquote(path_q.split('?').next().unwrap_or("/"));
    let qs = parse_qs(path_q.split_once('?').map(|x| x.1).unwrap_or(""));
    if path.starts_with("/codex") { path = path[6..].to_string(); if path.is_empty() { path = "/".into() } }
    if path == "/" || path.is_empty() { return j(200, &json!({"ok":true,"name":"leoagent-server"}), &accept) }
    if path.starts_with("/api/preview/") { return preview(&path[13..], &headers, method == Method::HEAD) }
    let token = headers.get("x-token").and_then(|v| v.to_str().ok()).map(|s| s.to_string()).unwrap_or_else(|| qs.get("token").cloned().unwrap_or_default());
    if !(!token.is_empty() && constant_time_eq(&token, &st().token)) { return j(401, &json!({"error":"bad token"}), &accept) }
    let parts: Vec<&str> = path.split('/').filter(|x| !x.is_empty()).collect();
    if parts.first() != Some(&"api") { return j(404, &json!({"error":"not found"}), &accept) }
    let parts = &parts[1..];
    let mut m = method.as_str().to_string();
    if m == "POST" && headers.get("x-http-method-override").and_then(|v| v.to_str().ok()) == Some("PATCH") { m = "PATCH".into(); }
    match api(&m, parts, &qs, &headers, &body, &accept) {
        Ok(r) => r,
        Err(ApiErr::NotFound(e)) => j(404, &json!({"error": e}), &accept),
        Err(ApiErr::Bad(e)) => j(400, &json!({"error": e}), &accept),
        Err(ApiErr::Other(code, e)) => j(code, &json!({"error": e}), &accept),
        Err(ApiErr::Raw(r)) => r,
    }
}
fn constant_time_eq(a: &str, b: &str) -> bool {
    if a.len() != b.len() { return false }
    let mut r = 0u8; for (x,y) in a.bytes().zip(b.bytes()) { r |= x ^ y } r == 0
}
enum ApiErr { NotFound(String), Bad(String), Other(u16, String), Raw(Resp) }
type ApiRes = Result<Resp, ApiErr>;

fn body_json(body: &bytes::Bytes) -> Value {
    if body.is_empty() { json!({}) } else { serde_json::from_slice(body).unwrap_or(json!({})) }
}

fn api(m: &str, P: &[&str], qs: &HashMap<String,String>, headers: &HeaderMap, body: &bytes::Bytes, accept: &str) -> ApiRes {
    let s = st(); let db = &s.db;
    if let Some(o) = appapi::handle(m, P, qs, headers, body) {
        return Ok(match o {
            appapi::Out::Json(c, v) => j(c, &v, accept),
            appapi::Out::Bytes(c, ct, b, hs) => {
                let n = b.len();
                let mut r = Response::new(Body::from(b));
                *r.status_mut() = StatusCode::from_u16(c).unwrap_or(StatusCode::BAD_GATEWAY);
                if let Ok(v) = HeaderValue::from_str(&ct) { r.headers_mut().insert("Content-Type", v); }
                r.headers_mut().insert("Content-Length", n.to_string().parse().unwrap());
                r.headers_mut().insert("Cache-Control", "no-store".parse().unwrap());
                for (k, v) in hs { if let (Ok(kn), Ok(vv)) = (HeaderName::try_from(k), HeaderValue::from_str(&v)) { r.headers_mut().insert(kn, vv); } }
                r
            }
        })
    }
    if P == ["screen"] {
        let (b, meta) = screen::grab(qs.get("mode").map(|s|s.as_str()).unwrap_or("desktop"),
            qs.get("w").and_then(|x| x.parse().ok()).unwrap_or(720),
            qs.get("q").and_then(|x| x.parse().ok()).unwrap_or(50));
        if b.is_none() { return Ok(j(503, &meta, accept)) }
        let b = b.unwrap();
        let mut r = Response::new(Body::from(b.clone()));
        *r.status_mut() = StatusCode::OK;
        r.headers_mut().insert("Content-Type", "image/jpeg".parse().unwrap());
        r.headers_mut().insert("Content-Length", b.len().to_string().parse().unwrap());
        r.headers_mut().insert("Cache-Control", "no-store".parse().unwrap());
        let front = meta.get("front").and_then(|x| x.as_str()).unwrap_or("");
        if let Ok(v) = HeaderValue::from_str(&urlencoding::encode(front)) { r.headers_mut().insert("X-Front", v); }
        return Ok(r);
    }
    if P == ["debug","raw"] { return Ok(j(200, &json!(*s.raw.lock()), accept)) }
    if P == ["health"] {
        let ag = s.agents.lock();
        return Ok(j(200, &json!({"ok":true,"agents":ag.len(),"running": ag.values().filter(|a|a.running()).count(),
            "backend": if s.use_desk() {"desktop"} else {"acp"}, "desktop": orch::desktop_running(), "version": env!("CARGO_PKG_VERSION"),
            "pref": db.kv("backend", Some(json!("auto"))).unwrap_or(json!("auto"))}), accept));
    }
    if P == ["models"] {
        let mut models = s.models.lock().clone();
        if models.get("models").and_then(|x| x.as_array()).map(|a| a.is_empty()).unwrap_or(true) {
            if let Some(v) = db.kv("models", None) { models = v; *s.models.lock() = models.clone(); }
        }
        return Ok(j(200, &models, accept));
    }
    if P == ["projects"] && m == "GET" {
        // merge desktop projects
        let gs = home().join(".codex/.codex-global-state.json");
        if let Ok(v) = std::fs::read_to_string(&gs).and_then(|t| Ok(serde_json::from_str::<Value>(&t).unwrap_or(json!({})))) {
            if let Some(lps) = v.get("local-projects").and_then(|x| x.as_object()) {
                for pj in lps.values() {
                    for rp in pj.get("rootPaths").and_then(|x| x.as_array()).into_iter().flatten() {
                        if let Some(p) = rp.as_str() {
                            if Path::new(p).is_dir() {
                                db.exec("insert or ignore into projects(name,path,created) values(?,?,?)",
                                    rusqlite::params![pj.get("name").and_then(|x|x.as_str()).unwrap_or_else(|| Path::new(p).file_name().and_then(|n|n.to_str()).unwrap_or("")), p, now()]);
                            }
                        }
                    }
                }
            }
        }
        let rows: Vec<(i64,String,String,i64,Option<f64>)> = db.q(
            "select p.id,p.name,p.path,(select count(*) from sessions s where s.project_id=p.id),(select max(updated) from sessions s where s.project_id=p.id) from projects p order by 5 desc nulls last, p.name",
            [], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)));
        let out: Vec<Value> = rows.into_iter().map(|(id,name,path,sessions,updated)| json!({
            "id":id,"name":name,"path":tilde(&path),"sessions":sessions,"updated":updated
        })).collect();
        return Ok(j(200, &json!(out), accept));
    }
    if P == ["projects"] && m == "POST" {
        let b = body_json(body);
        let rp = under_home(b.get("path").and_then(|x| x.as_str()).unwrap_or(""));
        if rp.as_ref().map(|p| !p.is_dir()).unwrap_or(true) { return Err(ApiErr::Bad("目录不存在或不在 ~ 下".into())) }
        let rp = rp.unwrap();
        db.exec("insert or ignore into projects(name,path,created) values(?,?,?)",
            rusqlite::params![b.get("name").and_then(|x|x.as_str()).unwrap_or_else(|| rp.file_name().and_then(|n|n.to_str()).unwrap_or("")), rp.to_string_lossy().as_ref(), now()]);
        let id: i64 = db.q1("select id from projects where path=?", rusqlite::params![rp.to_string_lossy().as_ref()], |r| r.get(0)).unwrap();
        return Ok(j(200, &json!({"id": id}), accept));
    }
    if P.len() == 2 && P[0] == "projects" && m == "DELETE" {
        let pid: i64 = P[1].parse().map_err(|_| ApiErr::Bad("bad id".into()))?;
        let n: i64 = db.q1("select count(*) from sessions where project_id=?", rusqlite::params![pid], |r| r.get(0)).unwrap_or(0);
        if n > 0 { return Err(ApiErr::Bad("先删除该项目下的对话".into())) }
        db.exec("delete from projects where id=?", rusqlite::params![pid]);
        return Ok(j(200, &json!({"ok":true}), accept));
    }
    if P == ["browse"] {
        let rp = under_home(qs.get("path").map(|s|s.as_str()).unwrap_or("~"));
        if rp.as_ref().map(|p| !p.is_dir()).unwrap_or(true) { return Err(ApiErr::Bad("bad path".into())) }
        let rp = rp.unwrap();
        let mut names: Vec<_> = std::fs::read_dir(&rp).map(|rd| rd.filter_map(|e| e.ok()).map(|e| e.file_name().to_string_lossy().into_owned()).collect()).unwrap_or_default();
        names.sort_by_key(|a| a.to_lowercase());
        let mut ds = vec![];
        for x in names {
            let fp = rp.join(&x);
            if x.starts_with('.') || !fp.is_dir() || x == "Library" { continue }
            ds.push(json!({"name":x,"git": fp.join(".git").is_dir()}));
            if ds.len() >= 400 { break }
        }
        return Ok(j(200, &json!({"path": tilde(&rp.to_string_lossy()), "dirs": ds}), accept));
    }
    if P == ["sessions"] && m == "GET" {
        let pid = qs.get("project");
        let rows: Vec<(String,Option<String>,Option<String>,Option<String>,Option<String>,f64,i64,String)> = if let Some(pid) = pid {
            let pid: i64 = pid.parse().map_err(|_| ApiErr::Bad("bad project".into()))?;
            db.q("select s.id,s.title,s.model,s.effort,s.mode,s.updated,s.project_id,p.name from sessions s join projects p on p.id=s.project_id where s.project_id=? order by s.updated desc limit 200",
                rusqlite::params![pid], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?,r.get(6)?,r.get(7)?)))
        } else {
            db.q("select s.id,s.title,s.model,s.effort,s.mode,s.updated,s.project_id,p.name from sessions s join projects p on p.id=s.project_id order by s.updated desc limit 200",
                [], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?,r.get(6)?,r.get(7)?)))
        };
        let out: Vec<Value> = rows.into_iter().map(|(id,title,model,effort,mode,updated,project_id,project)| {
            let mut o = json!({"id":id,"title": title.filter(|t| !t.is_empty()).unwrap_or_else(|| "新对话".into()),
                "model":model,"effort":effort,"mode":mode,"updated":updated,"project_id":project_id,"project":project});
            if let Some(m) = o.as_object_mut() { for (k,v) in s.status_json(&id).as_object().unwrap() { m.insert(k.clone(), v.clone()); } }
            o
        }).collect();
        return Ok(j(200, &json!(out), accept));
    }
    if P == ["sessions"] && m == "POST" {
        let b = body_json(body);
        let pid = b.get("project").and_then(|x| x.as_i64()).ok_or(ApiErr::Bad("no project".into()))?;
        if db.q1("select id from projects where id=?", rusqlite::params![pid], |r| r.get::<_,i64>(0)).is_none() {
            return Err(ApiErr::NotFound("'no project'".into()));
        }
        let sid = uuid::Uuid::new_v4().simple().to_string()[..16].to_string();
        let ag = b.get("agent").and_then(|x| x.as_str()).map(|s| s.to_string())
            .unwrap_or_else(|| db.kv("default_agent", Some(json!("codex"))).and_then(|v| v.as_str().map(|s|s.to_string())).unwrap_or_else(|| "codex".into()));
        if !agents::reg().iter().any(|a| a.id == ag) { return Err(ApiErr::Bad("未知 agent".into())) }
        let mode = b.get("mode").and_then(|x| x.as_str()).map(|s|s.to_string())
            .unwrap_or_else(|| db.kv("default_mode", Some(json!("auto"))).and_then(|v| v.as_str().map(|s|s.to_string())).unwrap_or_else(|| "auto".into()));
        db.exec("insert into sessions(id,project_id,acp_sid,title,model,effort,mode,created,updated,agent) values(?,?,?,?,?,?,?,?,?,?)",
            rusqlite::params![sid, pid, Option::<String>::None, "", b.get("model").and_then(|x|x.as_str()).unwrap_or(""),
                b.get("effort").and_then(|x|x.as_str()).unwrap_or(""), mode, now(), now(), ag]);
        if let Some(cfg) = b.get("config").and_then(|x| x.as_object()) {
            if !cfg.is_empty() { db.setkv(&format!("cfgset:{sid}"), &Value::Object(cfg.clone())); }
        }
        return Ok(j(200, &sess_row(&sid).unwrap(), accept));
    }
    if P == ["agents"] && m == "GET" {
        let pr = db.kv("agentprobe", Some(json!({}))).unwrap_or(json!({}));
        let en = db.kv("agents_enabled", None);
        let def_mode = db.kv("default_mode", Some(json!("auto"))).and_then(|v| v.as_str().map(|s|s.to_string())).unwrap_or_else(|| "auto".into());
        let mut out = vec![];
        for a in agents::reg() {
            let inst = agents::installed(&a);
            let p = pr.get(a.id).cloned().unwrap_or(json!({}));
            if !inst && p.is_null() || (!inst && p.as_object().map(|m| m.is_empty()).unwrap_or(true) && p != json!({})) {
                // Python: if not inst and not p: continue — empty dict is truthy in Python! so only skip if p is missing/None
            }
            if !inst && (p.is_null() || p == Value::Null) { continue }
            // actually `not p` for {} is False in Python, so empty probe still shows if... wait `if not inst and not p` — {} is truthy, so we don't continue. Only None/missing.
            if !inst && pr.get(a.id).is_none() { continue }
            let mut cfg = db.kv(&format!("agentcfg:{}", a.id), None).or_else(|| p.get("cfg").cloned()).unwrap_or(json!([]));
            let mm = a.modes.get(def_mode.as_str());
            let defs = db.kv(&format!("agentdef:{}", a.id), Some(json!({}))).unwrap_or(json!({}));
            if let Some(arr) = cfg.as_array_mut() {
                for o in arr.iter_mut() {
                    let id = o.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
                    let cv = defs.get(&id).cloned()
                        .or_else(|| mm.filter(|(k,_)| *k == id).map(|(_,v)| json!(v)))
                        .or_else(|| o.get("currentValue").cloned());
                    if let Some(cv) = cv { o["currentValue"] = cv; }
                }
            }
            let enabled = a.id == "codex" || match &en {
                None => p.get("ok").and_then(|x| x.as_bool()).unwrap_or(false),
                Some(v) => { let aid = a.id; v.as_array().map(|arr| arr.iter().any(|x| x.as_str()==Some(aid))).unwrap_or(false) },
            };
            let list = p.pointer("/caps/sessionCapabilities/list").is_some();
            out.push(json!({"id":a.id,"name":a.name,"icon":a.icon,"installed":inst,"ok":p.get("ok").and_then(|x|x.as_bool()).unwrap_or(false),
                "err":p.get("err"),"checked":p.get("ts"),"secs":p.get("secs"),
                "version": p.pointer("/info/version"), "enabled": enabled, "config": cfg,
                "defaults": db.kv(&format!("agentdef:{}", a.id), Some(json!({}))).unwrap_or(json!({})), "list": list}));
        }
        return Ok(j(200, &json!({"agents":out,"default": db.kv("default_agent", Some(json!("codex"))).unwrap_or(json!("codex")),
            "probing": s.probing.load(Ordering::SeqCst)}), accept));
    }
    if P == ["agents","probe"] && m == "POST" {
        let only = body_json(body).get("only").cloned();
        thread::spawn(move || orch::probe_all(only));
        return Ok(j(200, &json!({"ok":true}), accept));
    }
    if P == ["agents","settings"] && m == "POST" {
        let b = body_json(body);
        if let Some(en) = b.get("enabled").and_then(|x| x.as_array()) {
            let mut set: Vec<String> = en.iter().filter_map(|x| x.as_str().map(|s|s.to_string())).collect();
            if !set.iter().any(|x| x=="codex") { set.push("codex".into()); }
            set.sort(); set.dedup();
            db.setkv("agents_enabled", &json!(set));
        }
        if let Some(d) = b.get("default").and_then(|x| x.as_str()) {
            if agents::reg().iter().any(|a| a.id == d) { db.setkv("default_agent", &json!(d)); }
        }
        if let (Some(defs), Some(ag)) = (b.get("defaults"), b.get("agent").and_then(|x|x.as_str())) {
            if agents::reg().iter().any(|a| a.id == ag) && defs.is_object() { db.setkv(&format!("agentdef:{ag}"), defs); }
        }
        return Ok(j(200, &json!({"ok":true}), accept));
    }
    if P == ["settings"] {
        if m == "POST" {
            let b = body_json(body);
            if b.get("default_mode").is_some() {
                db.setkv("default_mode", &json!(b.get("default_mode").and_then(|x|x.as_str()).filter(|s| !s.is_empty()).unwrap_or("auto")));
            }
            if let Some(be) = b.get("backend").and_then(|x| x.as_str()) {
                if be == "auto" || be == "acp" { db.setkv("backend", &json!(be)); }
            }
        }
        return Ok(j(200, &json!({"default_mode": db.kv("default_mode", Some(json!("auto"))).unwrap_or(json!("auto")),
            "backend": db.kv("backend", Some(json!("auto"))).unwrap_or(json!("auto")),
            "active": if s.use_desk() {"desktop"} else {"acp"}, "desktop_sync_available": config::desktop_sync(),
            "stt": !config::get().stt_url.is_empty()}), accept));
    }
    if P == ["threads"] {
        let mut L = threads::list_threads(qs.get("q").map(|s|s.as_str()).unwrap_or(""), qs.get("limit").and_then(|x|x.parse().ok()).unwrap_or(400));
        let m: HashMap<String,(String,Option<String>)> = {
            let rows: Vec<(String,String,Option<String>)> = db.q(
                "select acp_sid,id,(select v from kv where k='imp:'||sessions.id) from sessions where acp_sid is not null", [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)));
            rows.into_iter().map(|(a,i,v)| (a,(i,v))).collect()
        };
        let pm: HashMap<String,String> = db.q("select path,name from projects", [], |r| {
            Ok((tilde(&r.get::<_,String>(0)?), r.get::<_,String>(1)?))
        }).into_iter().collect();
        for t in &mut L {
            let mut running = false;
            if let Some(id) = t.get("id").and_then(|x| x.as_str()) {
                if let Some((sid, imp)) = m.get(id) {
                    t["sid"] = json!(sid);
                    if t.get("origin").and_then(|x|x.as_str()) == Some("acp") && imp.is_none() { t["origin"] = json!("phone"); }
                    running = s.status(sid).running;
                }
            }
            t["running"] = json!(running);
            t["agent"] = json!("codex");
            if !crate::py::truthy(t.get("project")) && !t.get("pinned").and_then(|x|x.as_bool()).unwrap_or(false) {
                if let Some(cwd) = t.get("cwd").and_then(|x| x.as_str()) {
                    if let Some(name) = pm.get(cwd) { t["project"] = json!(name); }
                }
            }
        }
        let seen: std::collections::HashSet<String> = L.iter().filter_map(|t| t.get("id").and_then(|x|x.as_str()).map(|s|s.to_string())).collect();
        let extra: Vec<(String,Option<String>,String,f64,String,String)> = db.q(
            "select s.id,s.title,p.path,s.updated,coalesce(s.agent,'codex'),p.name from sessions s join projects p on p.id=s.project_id where s.acp_sid is null or coalesce(s.agent,'codex')!='codex' order by s.updated desc limit 300",
            [], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?)));
        for (id, title, path, updated, agent, project) in extra {
            L.push(json!({"id": Value::Null, "sid": id, "title": title.filter(|t|!t.is_empty()).unwrap_or_else(||"新对话".into()),
                "cwd": tilde(&path), "origin": "phone", "updated": updated, "pinned": false, "project": project, "project_pos": 999,
                "open_on_mac": false, "active": false, "running": s.status_json(&id)["running"], "agent": agent}));
        }
        if let Some(ag) = qs.get("agent") { L.retain(|t| t.get("agent").and_then(|x|x.as_str()) == Some(ag)); }
        L.sort_by(|a,b| {
            let ua = a.get("updated").and_then(|x| x.as_f64()).unwrap_or(0.0);
            let ub = b.get("updated").and_then(|x| x.as_f64()).unwrap_or(0.0);
            ub.partial_cmp(&ua).unwrap_or(std::cmp::Ordering::Equal)
        });
        let _ = seen; // silence
        return Ok(j(200, &json!(L), accept));
    }
    if P == ["notes","count"] {
        let aft: i64 = qs.get("after").and_then(|x| x.parse().ok()).unwrap_or(0);
        let count: i64 = db.q1("select count(*) from notes where seq>?", rusqlite::params![aft], |r| r.get(0)).unwrap_or(0);
        let last: i64 = db.q1("select max(seq) from notes", [], |r| r.get::<_,Option<i64>>(0)).flatten().unwrap_or(0);
        return Ok(j(200, &json!({"count":count,"last":last}), accept));
    }
    if P == ["notes"] {
        let lim = qs.get("limit").and_then(|x| x.parse().ok()).unwrap_or(100i64).min(500);
        let rows = query_notes(db, qs, lim);
        return Ok(j(200, &json!(rows), accept));
    }
    if P == ["notify"] {
        let since: i64 = qs.get("since").and_then(|x| x.parse().ok()).unwrap_or(0);
        let wait = qs.get("wait").and_then(|x| x.parse::<f64>().ok()).unwrap_or(0.0).min(25.0);
        let running = { let ag = s.agents.lock(); ag.values().any(|a| a.running() || !a.perm_ids().is_empty()) };
        if since <= 0 {
            let last: i64 = db.q1("select max(seq) from notes", [], |r| r.get::<_,Option<i64>>(0)).flatten().unwrap_or(0);
            return Ok(j(200, &json!({"last":last,"items":[],"running":running}), accept));
        }
        let end = now() + wait;
        let rows = loop {
            let rows: Vec<Value> = db.q("select seq,ts,sid,kind,title,text from notes where seq>? order by seq limit 50", rusqlite::params![since],
                |r| Ok(json!({"seq":r.get::<_,i64>(0)?,"ts":r.get::<_,f64>(1)?,"sid":r.get::<_,Option<String>>(2)?,"kind":r.get::<_,String>(3)?,"title":r.get::<_,String>(4)?,"text":r.get::<_,String>(5)?})));
            if !rows.is_empty() || now() >= end { break rows }
            let mut g = db.lock.lock();
            let _ = db.cond.wait_for(&mut g, Duration::from_secs_f64((end - now()).max(0.01)));
        };
        let last = rows.last().and_then(|r| r.get("seq").and_then(|x| x.as_i64())).unwrap_or(since);
        return Ok(j(200, &json!({"last":last,"items":rows,"running":running}), accept));
    }
    // sessions/:id/...
    if !P.is_empty() && P[0] == "sessions" && P.len() >= 2 {
        return session_api(m, P, qs, headers, body, accept);
    }
    // agents/:id/sessions — skip spawning for 2a NOPROBE
    if P.len() == 3 && P[0] == "agents" && P[2] == "sessions" && m == "GET" {
        let aid = P[1].to_string();
        let r = orch::ext_sessions(&aid);
        return Ok(j(200, &r, accept));
    }
    if P.len() == 4 && P[0] == "agents" && P[2] == "sessions" && P[3] == "import" && m == "POST" {
        let b = body_json(body); let aid = P[1].to_string();
        return match orch::agents_import(&aid, &b) { Ok(r) => Ok(j(200, &r, accept)), Err(e) => Err(ApiErr::Other(500, e)) };
    }
    if P.len() == 3 && P[0] == "threads" && P[2] == "open" && m == "POST" {
        let tid = P[1].to_string();
        return match orch::threads_open(&tid) { Ok(r) => Ok(j(200, &r, accept)), Err(e) if e == "no thread" => Err(ApiErr::NotFound("'no thread'".into())), Err(e) => Err(ApiErr::Other(500, e)) };
    }
    if P.len() >= 1 && P[0] == "perm" && P.len() == 2 && m == "POST" {
        let b = body_json(body);
        let rid = P[1];
        for a in s.agents.lock().values() {
            if a.perm_ids().iter().any(|x| x == rid) {
                let opt = b.get("option").filter(|x| !x.is_null()).map(py::pystr);
                let a2 = a.clone(); let rid2 = rid.to_string(); thread::spawn(move || { a2.answer_perm(&rid2, opt); });
                return Ok(j(200, &json!({"ok":true}), accept));
            }
        }
        return Err(ApiErr::NotFound("审批已过期".into()));
    }
    if P.len() >= 1 && P[0] == "ask" && P.len() == 2 && m == "POST" {
        let b = body_json(body);
        let rid = P[1];
        let all: Vec<Arc<dyn AgentLike>> = s.agents.lock().values().cloned().collect();
        for a in all {
            if a.asks().has(rid) {
                match runtime::answer_ask(a.as_ref(), rid, &b.get("answers").cloned().filter(|x| x.is_object()).unwrap_or(json!({})), b.get("text").map(py::pystr).unwrap_or_default().trim(), py::truthy(b.get("skip"))) {
                    Ok(true) => return Ok(j(200, &json!({"ok":true}), accept)),
                    Ok(false) => continue,
                    Err(e) => return Err(ApiErr::Bad(e.chars().take(200).collect())),
                }
            }
        }
        return Err(ApiErr::NotFound("该问题已过期或已在桌面端回答".into()));
    }
    Err(ApiErr::NotFound("not found".into()))
}

fn query_notes(db: &Db, qs: &HashMap<String,String>, lim: i64) -> Vec<Value> {
    // simplified matching Python filters
    let all: Vec<(i64,f64,Option<String>,String,String,String)> = db.q(
        "select seq,ts,sid,kind,title,text from notes order by seq desc", [],
        |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get(5)?)));
    let since = qs.get("since").and_then(|x| x.parse::<i64>().ok());
    let before = qs.get("before").and_then(|x| x.parse::<i64>().ok());
    let today_ts = if qs.get("date").map(|s|s.as_str()) == Some("today") {
        let today = chrono::Local::now().date_naive().and_hms_opt(0,0,0).unwrap();
        Some(today.and_local_timezone(chrono::Local).unwrap().timestamp() as f64)
    } else { None };
    all.into_iter().filter(|(seq,ts,_,_,_,_)| {
        since.map(|s| *seq > s).unwrap_or(true) && before.map(|b| *seq < b).unwrap_or(true) && today_ts.map(|t| *ts >= t).unwrap_or(true)
    }).take(lim as usize).map(|(seq,ts,sid,kind,title,text)| json!({"seq":seq,"ts":ts,"sid":sid,"kind":kind,"title":title,"text":text})).collect()
}

fn session_api(m: &str, P: &[&str], qs: &HashMap<String,String>, headers: &HeaderMap, body: &bytes::Bytes, accept: &str) -> ApiRes {
    let s = st(); let db = &s.db;
    let sid = P[1];
    let sess = sess_row(sid).ok_or(ApiErr::NotFound("no session".into()))?;
    let sub = if P.len() > 2 { P[2] } else { "" };

    if matches!(sub, "file"|"files"|"fileinfo"|"preview") && m == "GET" {
        let project_paths: Vec<String> = db.q("select path from projects", [], |r| r.get(0));
        let R = files::roots(sess["path"].as_str().unwrap(), &project_paths);
        let path = qs.get("path").map(|s|s.as_str()).unwrap_or_else(|| sess["path"].as_str().unwrap());
        let fp = files::resolve(path, sess["path"].as_str().unwrap(), &R).map_err(|e| {
            if e == "sensitive" || e.contains("outside") { ApiErr::Other(403, "路径不在项目目录内".into()) } else { ApiErr::Other(403, e.into()) }
        })?;
        return match sub {
            "file" => {
                let range = headers.get("range").and_then(|v| v.to_str().ok());
                match files::send_file(&fp, range, false) {
                    Ok(fb) => {
                        let mut r = Response::new(Body::from(fb.body));
                        *r.status_mut() = StatusCode::from_u16(fb.code).unwrap();
                        for (k,v) in fb.headers { if let (Ok(kn), Ok(vv)) = (HeaderName::try_from(k), HeaderValue::from_str(&v)) { r.headers_mut().insert(kn, vv); } }
                        Ok(r)
                    }
                    Err((c, o)) => Ok(j(c, &o, accept)),
                }
            }
            "fileinfo" => Ok(j(200, &files::info(&fp, Path::new(sess["path"].as_str().unwrap())), accept)),
            "files" => {
                if !fp.is_dir() { return Err(ApiErr::NotFound("文件不存在".into())) }
                Ok(j(200, &json!({"dir": tilde(&fp.to_string_lossy()), "abs": fp.to_string_lossy(),
                    "root": tilde(sess["path"].as_str().unwrap()),
                    "items": files::listing(&fp, qs.get("recent").map(|s|s.as_str())==Some("1"), qs.get("limit").and_then(|x|x.parse().ok()).unwrap_or(200))}), accept))
            }
            "preview" => {
                let tok = files::ptok(&s.token, sid);
                s.pvmap.lock().insert(tok.clone(), sid.to_string());
                let i = R.iter().position(|r| fp.starts_with(r)).ok_or(ApiErr::Other(403, "路径不在项目目录内".into()))?;
                let rel = fp.strip_prefix(&R[i]).unwrap().to_string_lossy();
                Ok(j(200, &json!({"url": format!("/api/preview/{tok}/r{i}/{}", urlencoding::encode(&rel).replace("%2F","/")),
                    "prefix": format!("/api/preview/{tok}/"), "roots": R.iter().map(|p| p.to_string_lossy()).collect::<Vec<_>>()}), accept))
            }
            _ => unreachable!()
        };
    }
    if sub.is_empty() && m == "GET" {
        let mut o = sess.clone();
        o["path"] = json!(tilde(sess["path"].as_str().unwrap()));
        o["agent_name"] = json!(agents::get(sess["agent"].as_str().unwrap_or("codex")).name);
        if let Some(m) = o.as_object_mut() { for (k,v) in s.status_json(sid).as_object().unwrap() { m.insert(k.clone(), v.clone()); } }
        return Ok(j(200, &o, accept));
    }
    if sub == "config" && m == "GET" {
        return Ok(j(200, &json!({"agent": sess["agent"], "options": db.kv(&format!("cfg:{sid}"), None).or_else(|| db.kv(&format!("agentcfg:{}", sess["agent"].as_str().unwrap_or("codex")), None)).unwrap_or(json!([])),
            "set": db.kv(&format!("cfgset:{sid}"), Some(json!({}))).unwrap_or(json!({}))}), accept));
    }
    if sub == "config" && m == "POST" {
        let b = body_json(body);
        let mut cs = db.kv(&format!("cfgset:{sid}"), Some(json!({}))).unwrap_or(json!({}));
        cs[b["configId"].as_str().unwrap_or("")] = b.get("value").cloned().unwrap_or(Value::Null);
        db.setkv(&format!("cfgset:{sid}"), &cs);
        let mut opts = db.kv(&format!("cfg:{sid}"), None).filter(|x| py::truthy(Some(x))).unwrap_or(json!([]));
        let a = s.agent(sid).filter(|a| a.alive() && a.acp_sid().is_some() && !a.is_desk());
        if let Some(a) = a {
            let r = a.rpc().unwrap().call("session/set_config_option", json!({"sessionId": a.acp_sid(), "configId": b["configId"], "value": b.get("value")}), 30);
            match r { Ok(r) => if let Some(c) = r.get("configOptions").filter(|x| py::truthy(Some(x))) { opts = c.clone(); db.setkv(&format!("cfg:{sid}"), &opts); },
                      Err(e) => return Err(ApiErr::Bad(e.chars().take(200).collect())) }
        } else if let Some(arr) = opts.as_array_mut() {
            for o in arr { if o.get("id") == b.get("configId") { o["currentValue"] = b.get("value").cloned().unwrap_or(Value::Null); } }
            db.setkv(&format!("cfg:{sid}"), &opts);
        }
        return Ok(j(200, &json!({"options": opts, "set": cs}), accept));
    }
    if sub.is_empty() && m == "PATCH" {
        let b = body_json(body);
        for f in ["title","model","effort","mode"] {
            if let Some(v) = b.get(f) {
                db.exec(&format!("update sessions set {f}=? where id=?"), rusqlite::params![v.as_str().unwrap_or(""), sid]);
            }
        }
        if let Some(a) = s.agent(sid) { if a.alive() && !a.is_desk() && ["model","effort","mode"].iter().any(|f| b.get(*f).is_some()) {
            let sid2 = sid.to_string(); thread::spawn(move || { if let (Some(r), Some(row)) = (a.rpc(), sess_row(&sid2)) { orch::apply_cfg(&r, &a.acp_sid(), &row); } }); } }
        return Ok(j(200, &sess_row(sid).unwrap(), accept));
    }
    if sub.is_empty() && m == "DELETE" {
        if let Some(a) = s.agents.lock().remove(sid) { a.kill(); }
        db.exec("delete from events where sid=?", rusqlite::params![sid]);
        db.exec("delete from sessions where id=?", rusqlite::params![sid]);
        return Ok(j(200, &json!({"ok":true}), accept));
    }
    if sub == "events" {
        let since: i64 = qs.get("since").and_then(|x| x.parse().ok()).unwrap_or(0);
        let wait = qs.get("wait").and_then(|x| x.parse::<f64>().ok()).unwrap_or(0.0).min(25.0);
        let end = now() + wait;
        let rows = loop {
            let rows: Vec<(i64,String,String)> = db.q("select seq,type,data from events where sid=? and seq>? order by seq limit 2000",
                rusqlite::params![sid, since], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?)));
            if !rows.is_empty() || now() >= end { break rows }
            let mut g = db.lock.lock();
            let _ = db.cond.wait_for(&mut g, Duration::from_secs_f64((end-now()).max(0.01)));
        };
        let events: Vec<Value> = rows.into_iter().map(|(seq,typ,data)| json!({"seq":seq,"type":typ,"data": serde_json::from_str::<Value>(&data).unwrap_or(json!({}))})).collect();
        let mut o = json!({"events": events});
        if let Some(m) = o.as_object_mut() { for (k,v) in s.status_json(sid).as_object().unwrap() { m.insert(k.clone(), v.clone()); } }
        return Ok(j(200, &o, accept));
    }
    if sub == "history" {
        let t0 = now();
        let turns = qs.get("turns").and_then(|x| x.parse().ok()).unwrap_or(6usize);
        let before = qs.get("before").and_then(|x| x.parse().ok());
        let mut h = history::history(db, sid, turns, before);
        if let Some(m) = h.as_object_mut() {
            for (k,v) in s.status_json(sid).as_object().unwrap() { m.insert(k.clone(), v.clone()); }
            m.insert("ms".into(), json!(((now()-t0)*1000.0 * 10.0).round()/10.0));
        }
        return Ok(j(200, &h, accept));
    }
    if sub == "tool" && P.len() == 4 {
        let seq: i64 = P[3].parse().map_err(|_| ApiErr::Bad("bad seq".into()))?;
        let data: Option<String> = db.q1("select data from events where sid=? and seq=?", rusqlite::params![sid, seq], |r| r.get(0));
        return Ok(j(200, &data.and_then(|d| serde_json::from_str(&d).ok()).unwrap_or(json!({})), accept));
    }
    if sub == "stream" {
        if py::truthy(sess.get("acp_sid")) { let sid2 = sid.to_string(); thread::spawn(move || { orch::desk_attach(&sid2, false); }); }
        return Ok(sse(sid.to_string(), qs.get("since").and_then(|x| x.parse().ok()).unwrap_or(0)));
    }
    if sub == "prompt" && m == "POST" {
        // 2a: accept but do not run live if NO_DESK / test mode — still validate
        let b = body_json(body);
        let text = b.get("text").and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
        let imgs = b.get("images").and_then(|x| x.as_array()).cloned().unwrap_or_default();
        if s.status(sid).running { return Err(ApiErr::Other(409, "正在运行".into())) }
        if text.is_empty() && imgs.iter().all(|i| i.get("data").is_none()) { return Err(ApiErr::Bad("空消息".into())) }
        // live path for 2b
        if sess.get("title").and_then(|x| x.as_str()).unwrap_or("").is_empty() && !text.is_empty() {
            let t: String = text.chars().take(40).collect();
            db.exec("update sessions set title=? where id=?", rusqlite::params![&t, sid]);
            s.bus.emit(sid, "title", json!({"title": t}));
        }
        let nimg = imgs.iter().filter(|i| i.get("data").is_some()).count();
        s.bus.emit(sid, "user", json!({"text": text, "images": if text.is_empty() { nimg } else { nimg /* Python: len(blocks)-(1 if text else 0) = nimg */ }}));
        // images count in Python: len(blocks) - (1 if text else 0) which equals number of image blocks
        if let Some(a) = s.agents.lock().get(sid) { a.set_running(true); }
        let sid2 = sid.to_string(); let text2 = text.clone(); let imgs2 = imgs.clone();
        let mut blocks: Vec<Value> = imgs2.iter().filter(|i| i.get("data").is_some()).map(|i| json!({"type":"image","mimeType": i.get("mime").cloned().unwrap_or(json!("image/jpeg")), "data": i["data"]})).collect();
        if !text2.is_empty() { blocks.push(json!({"type":"text","text": text2})); }
        thread::spawn(move || orch::run_prompt(&sid2, &blocks, &text2, &imgs2));
        return Ok(j(200, &json!({"ok":true}), accept));
    }
    if sub == "cancel" && m == "POST" {
        if let Some(a) = s.agent(sid).filter(|a| a.alive()) { thread::spawn(move || a.cancel()); }
        return Ok(j(200, &json!({"ok":true}), accept));
    }
    if sub == "diff" {
        let cwd = sess["path"].as_str().unwrap();
        let g = |args: &[&str]| -> String {
            Command::new("git").args(["-C", cwd]).args(args).env_clear().envs(runtime::env_map())
                .output().ok().map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default()
        };
        use std::process::Command;
        if !Path::new(cwd).join(".git").is_dir() && g(&["rev-parse","--git-dir"]).is_empty() {
            return Ok(j(200, &json!({"git":false,"stat":"","diff":"","untracked":[]}), accept));
        }
        let mut diff = g(&["diff","HEAD","--no-color"]);
        if diff.is_empty() { diff = g(&["diff","--no-color"]); }
        let unt: Vec<String> = g(&["ls-files","--others","--exclude-standard"]).lines().map(|s|s.to_string()).take(100).collect();
        for f in unt.iter().take(20) {
            let fp = Path::new(cwd).join(f);
            if let Ok(st) = std::fs::metadata(&fp) {
                if st.len() < 60000 {
                    if let Ok(txt) = std::fs::read_to_string(&fp) {
                        let lines: Vec<_> = txt.lines().collect();
                        diff.push_str(&format!("diff --git a/{f} b/{f}\nnew file\n--- /dev/null\n+++ b/{f}\n@@ -0,0 +1,{} @@\n", lines.len()));
                        for l in &lines { diff.push('+'); diff.push_str(l); diff.push('\n'); }
                    }
                }
            }
        }
        let trunc = diff.len() > 300000;
        let diff_out: String = diff.chars().take(300000).collect();
        return Ok(j(200, &json!({"git":true,"stat": g(&["diff","HEAD","--stat","--no-color"]), "diff": diff_out, "truncated": trunc,
            "untracked": unt, "branch": g(&["branch","--show-current"]).trim()}), accept));
    }
    Err(ApiErr::NotFound("not found".into()))
}

fn preview(rest: &str, headers: &HeaderMap, head: bool) -> Resp {
    let (tok, rel) = rest.split_once('/').unwrap_or((rest, ""));
    let s = st();
    let mut sid = s.pvmap.lock().get(tok).cloned();
    if sid.is_none() {
        let ids: Vec<String> = s.db.q("select id from sessions", [], |r| r.get(0));
        let mut map = s.pvmap.lock();
        for id in ids { map.insert(files::ptok(&s.token, &id), id); }
        sid = map.get(tok).cloned();
    }
    let Some(sid) = sid else { return j(404, &json!({"error":"not found"}), "") };
    let Some(sess) = sess_row(&sid) else { return j(404, &json!({"error":"not found"}), "") };
    let project_paths: Vec<String> = s.db.q("select path from projects", [], |r| r.get(0));
    let R = files::roots(sess["path"].as_str().unwrap(), &project_paths);
    let (ri, rel2) = rel.split_once('/').unwrap_or((rel, ""));
    let base = if ri.starts_with('r') {
        ri[1..].parse::<usize>().ok().and_then(|i| R.get(i)).cloned()
    } else { None };
    let Some(base) = base else { return j(404, &json!({"error":"not found"}), "") };
    let mut fp = match files::resolve(&base.join(if rel2.is_empty() {"index.html"} else {rel2}).to_string_lossy(), sess["path"].as_str().unwrap(), &R) {
        Ok(p) => p, Err(_) => return j(403, &json!({"error":"forbidden"}), ""),
    };
    if fp.is_dir() { fp = fp.join("index.html"); }
    let range = headers.get("range").and_then(|v| v.to_str().ok());
    match files::send_file(&fp, range, head) {
        Ok(fb) => {
            let mut r = Response::new(Body::from(fb.body));
            *r.status_mut() = StatusCode::from_u16(fb.code).unwrap();
            for (k,v) in fb.headers { if let (Ok(kn), Ok(vv)) = (HeaderName::try_from(k), HeaderValue::from_str(&v)) { r.headers_mut().insert(kn, vv); } }
            r
        }
        Err((c,o)) => j(c, &o, ""),
    }
}

fn sse(sid: String, mut since: i64) -> Resp {
    let (tx, rx) = tokio::sync::mpsc::channel::<Result<bytes::Bytes, std::io::Error>>(16);
    thread::spawn(move || {
        let s = st();
        let _ = tx.blocking_send(Ok(bytes::Bytes::from(format!(": {}\n\n", " ".repeat(2048)))));
        let mut last_hb = now(); let mut st_prev: Option<Value> = None;
        loop {
            let rows: Vec<(i64,String,String,f64)> = {
                let end_wait = now() + 15.0;
                loop {
                    let rows = s.db.q("select seq,type,data,ts from events where sid=? and seq>? order by seq limit 500",
                        rusqlite::params![&sid, since], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?)));
                    if !rows.is_empty() { break rows }
                    let mut g = s.db.lock.lock();
                    let _ = s.db.cond.wait_for(&mut g, Duration::from_secs_f64((end_wait-now()).max(0.01)));
                    if now() >= end_wait { break rows }
                }
            };
            for (seq, typ, data, ts) in &rows {
                since = *seq;
                let payload = json!({"seq":seq,"type":typ,"data": serde_json::from_str::<Value>(data).unwrap_or(json!({})), "ts": ts});
                let line = format!("id: {seq}\nevent: ev\ndata: {}\n\n", py::dumps(&payload));
                if tx.blocking_send(Ok(bytes::Bytes::from(line))).is_err() { return }
            }
            let ns = s.status_json(&sid);
            if st_prev.as_ref() != Some(&ns) {
                st_prev = Some(ns.clone());
                let line = format!("event: status\ndata: {}\n\n", py::dumps(&ns));
                if tx.blocking_send(Ok(bytes::Bytes::from(line))).is_err() { return }
            }
            if now() - last_hb > 15.0 {
                if tx.blocking_send(Ok(bytes::Bytes::from(": hb\n\n"))).is_err() { return }
                last_hb = now();
            }
        }
    });
    let stream = tokio_stream::wrappers::ReceiverStream::new(rx);
    let mut r = Response::new(Body::from_stream(stream));
    *r.status_mut() = StatusCode::OK;
    r.headers_mut().insert("Content-Type", "text/event-stream; charset=utf-8".parse().unwrap());
    r.headers_mut().insert("Cache-Control", "no-store, no-transform".parse().unwrap());
    r.headers_mut().insert("X-Accel-Buffering", "no".parse().unwrap());
    r.headers_mut().insert("Connection", "close".parse().unwrap());
    r
}

const USAGE: &str = "leoagent-server — drive Codex / ACP agents on this machine from the LeoAgent app

USAGE:
  leoagent-server [serve]        start the server (default)
  leoagent-server init           create the config file (with a random token) and exit
  leoagent-server pair [--svg F] print a pairing QR code (leoagent://pair?url=…&token=…)
  leoagent-server rotate-token   generate a new token (phones must re-pair)
  leoagent-server config-path    print the config file location

OPTIONS:
  -c, --config <FILE>  config file (default: $LEOAGENT_CONFIG or ~/.config/leoagent/config.toml)
";

#[tokio::main]
async fn main() {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let mut cfg_path = config::default_path();
    if let Some(i) = args.iter().position(|a| a == "-c" || a == "--config") {
        if i + 1 >= args.len() { eprintln!("--config needs a value"); std::process::exit(2) }
        cfg_path = PathBuf::from(args[i + 1].clone()); args.drain(i..=i + 1);
    }
    let cmd = args.first().cloned().unwrap_or_else(|| "serve".into());
    if cmd == "-h" || cmd == "--help" || cmd == "help" { print!("{USAGE}"); return }
    if cmd == "-V" || cmd == "--version" { println!("leoagent-server {}", env!("CARGO_PKG_VERSION")); return }
    if cmd == "config-path" { println!("{}", cfg_path.display()); return }
    let (cfg, created) = match config::load(&cfg_path) { Ok(x) => x, Err(e) => { eprintln!("config error: {e}"); std::process::exit(1) } };
    if created { eprintln!("created {} (new random token)", cfg_path.display()); }
    config::install(cfg_path.clone(), cfg);
    match cmd.as_str() {
        "init" => { println!("config: {}", cfg_path.display()); return }
        "pair" => {
            let svg = args.iter().position(|a| a == "--svg").and_then(|i| args.get(i + 1)).cloned();
            if let Err(e) = appapi::print_pair(svg.as_deref()) { eprintln!("{e}"); std::process::exit(1) }
            return
        }
        "rotate-token" => {
            let mut c = config::get().clone(); c.token = config::gen_token();
            if let Err(e) = config::write(&cfg_path, &c) { eprintln!("{e}"); std::process::exit(1) }
            println!("new token written to {} — restart the server and re-pair your phone", cfg_path.display()); return
        }
        #[cfg(feature = "desktop-sync")]
        "ipc-check" => {
            // read-only: connect+initialize, then read-model-settings per thread id (no follow, no turns)
            println!("desktop_running={}", desk::desktop_running());
            let ok = desk::ipc().ok(); println!("ipc_ok={ok} client_id={:?}", desk::ipc().cid.lock());
            for t in &args[1..] { println!("owned {t} = {}", desk::ipc().owned(t)); }
            return;
        }
        "serve" => {}
        other => { eprintln!("unknown command: {other}\n\n{USAGE}"); std::process::exit(2) }
    }
    let base = config::data_dir();
    let sandbox = base.join("sandbox");
    if let Err(e) = std::fs::create_dir_all(&sandbox) { eprintln!("cannot create {}: {e}", sandbox.display()); std::process::exit(1) }
    let db_path = base.join("bridge.db");
    let token = config::get().token.clone();
    let db = Db::open(&db_path);
    seed(&db, &sandbox);
    let bus = Bus::new(db.clone());
    let mut models = json!({"models":[],"efforts":["low","medium","high","xhigh"],"default":null});
    if let Some(v) = db.kv("models", None) { models = v; }
    let state = Arc::new(State { db: db.clone(), bus, agents: Mutex::new(HashMap::new()), models: Mutex::new(models),
        probing: std::sync::atomic::AtomicBool::new(false), token, base: base.clone(), pvmap: Mutex::new(HashMap::new()), raw: Mutex::new(vec![]), alk: Mutex::new(()) });
    let _ = S.set(state);
    if std::env::var("LEOAGENT_NOPROBE").is_err() {
        thread::spawn(orch::startup_models_probe);
        if !py::truthy(st().db.kv("agentprobe", None).as_ref()) { thread::spawn(|| { thread::sleep(Duration::from_secs(20)); orch::probe_all(None); }); }
    }
    // reaper
    thread::spawn(|| loop {
        thread::sleep(Duration::from_secs(60));
        let idle = 15.0 * 60.0;
        let mut ag = st().agents.lock();
        let dead: Vec<String> = ag.iter().filter(|(_,a)| !a.alive() || (!a.running() && a.perm_ids().is_empty() && a.asks().is_empty() && now()-a.last() > idle)).map(|(k,_)| k.clone()).collect();
        for sid in dead { if let Some(a) = ag.remove(&sid) { eprintln!("{} reap {}", chrono::Local::now().format("%m-%d %H:%M:%S"), &sid[..6.min(sid.len())]); a.kill(); } }
    });
    let listen = config::get().listen.clone();
    let app = Router::new().fallback(handle);
    let listener = match tokio::net::TcpListener::bind(&listen).await { Ok(l) => l, Err(e) => { eprintln!("bind {listen}: {e}"); std::process::exit(1) } };
    eprintln!("{} leoagent-server {} on http://{listen} (data: {}, desktop-sync: {})", chrono::Local::now().format("%m-%d %H:%M:%S"),
        env!("CARGO_PKG_VERSION"), base.display(), if config::desktop_sync() { "on" } else { "off" });
    axum::serve(listener, app).await.unwrap();
}
