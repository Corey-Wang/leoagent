//! Orchestration (= bridge.py runtime glue): state, agent lifecycle, prompts, desk attach, probes, import
#![allow(dead_code)]
use crate::agents::{self as AG};
use crate::db::{home, now, Db};
#[cfg(feature = "desktop-sync")]
use crate::desk::{self, Desk, FirstTurn};
use crate::events::Bus;
use crate::py::{pystr, truthy};
use crate::runtime::{log, AcpAgent, AgentLike, Rpc};
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub struct State {
    pub db: Arc<Db>,
    pub bus: Arc<Bus>,
    pub agents: Mutex<HashMap<String, Arc<dyn AgentLike>>>,
    pub models: Mutex<Value>,
    pub probing: AtomicBool,
    pub token: String,
    pub base: PathBuf,
    pub pvmap: Mutex<HashMap<String, PreviewGrant>>,
    pub downloads: Mutex<HashMap<String, (String, f64)>>,
    pub raw: Mutex<Vec<(f64, usize)>>,
    pub alk: Mutex<()>,
    pub generation: String,
}
#[derive(Clone)]
pub struct PreviewGrant {
    pub sid: String,
    pub caller: String,
    pub base: PathBuf,
    pub expires: f64,
}
pub struct Status {
    pub running: bool,
    pub alive: bool,
    pub pending: Vec<String>,
    pub backend: Option<&'static str>,
}
impl State {
    pub fn status(&self, sid: &str) -> Status {
        let a = self.agents.lock().get(sid).cloned();
        match a {
            None => Status {
                running: false,
                alive: false,
                pending: vec![],
                backend: None,
            },
            Some(a) => Status {
                running: a.running(),
                alive: a.alive(),
                pending: a.perm_ids(),
                backend: Some(a.backend()),
            },
        }
    }
    pub fn status_json(&self, sid: &str) -> Value {
        let s = self.status(sid);
        let task = crate::tasks::latest(&self.db, sid).ok().flatten();
        let state = task
            .as_ref()
            .and_then(|t| t["state"].as_str())
            .unwrap_or("idle");
        let running = s.running
            || matches!(
                state,
                "queued"
                    | "running"
                    | "waiting_approval"
                    | "waiting_input"
                    | "cancelling"
                    | "recovering"
            );
        json!({"running":running,"alive":s.alive,"pending":s.pending,"backend":s.backend,
            "task":task,"task_state":state,"server_generation":self.generation,
            "interactions":crate::tasks::live_interactions(&self.db,sid).unwrap_or_default()})
    }
    /// Desktop sync is used only when compiled in (`--features desktop-sync`), enabled in config, not switched off in the app, and the desktop IPC answers.
    pub fn use_desk(&self) -> bool {
        #[cfg(feature = "desktop-sync")]
        {
            crate::config::desktop_sync()
                && self
                    .db
                    .kv("backend", Some(json!("auto")))
                    .and_then(|v| v.as_str().map(|s| s.to_string()))
                    .as_deref()
                    != Some("acp")
                && desk::ipc().ok()
        }
        #[cfg(not(feature = "desktop-sync"))]
        {
            false
        }
    }
    pub fn agent(&self, sid: &str) -> Option<Arc<dyn AgentLike>> {
        self.agents.lock().get(sid).cloned()
    }
}
fn st() -> &'static Arc<State> {
    crate::st()
}
pub fn desktop_running() -> bool {
    #[cfg(feature = "desktop-sync")]
    {
        crate::config::desktop_sync() && desk::desktop_running()
    }
    #[cfg(not(feature = "desktop-sync"))]
    {
        false
    }
}
fn desk_owned(_tid: &str) -> bool {
    #[cfg(feature = "desktop-sync")]
    {
        st().use_desk() && desk::ipc().owned(_tid)
    }
    #[cfg(not(feature = "desktop-sync"))]
    {
        false
    }
}
pub fn sandbox() -> String {
    st().base.join("sandbox").to_string_lossy().into_owned()
}
fn kvo(k: &str) -> Value {
    st().db
        .kv(k, Some(json!({})))
        .filter(|v| truthy(Some(v)))
        .unwrap_or(json!({}))
}

pub fn models_from(res: &Value) {
    let mm = res
        .get("models")
        .filter(|x| truthy(Some(x)))
        .cloned()
        .unwrap_or(json!({}));
    let ids: Vec<String> = mm["availableModels"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|m| pystr(&m["modelId"]))
        .collect();
    if ids.is_empty() {
        return;
    }
    let mut names = HashMap::new();
    for o in res["configOptions"].as_array().into_iter().flatten() {
        if o["id"] == "model" {
            names = o["options"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|x| {
                    (
                        pystr(&x["value"]),
                        x.get("name").cloned().unwrap_or(Value::Null),
                    )
                })
                .collect();
        }
    }
    let mut base: Vec<String> = vec![];
    for i in &ids {
        let b = i.split('[').next().unwrap().to_string();
        if !base.contains(&b) {
            base.push(b)
        }
    }
    let re = regex::Regex::new(r"^(.*)\[(.*)\]$").unwrap();
    let mut effs: HashMap<String, Vec<String>> = HashMap::new();
    for i in &ids {
        if let Some(c) = re.captures(i) {
            effs.entry(c[1].to_string())
                .or_default()
                .push(c[2].to_string());
        }
    }
    let cur = mm.get("currentModelId").cloned().unwrap_or(json!(""));
    let mut m = st().models.lock();
    m["models"] = json!(base.iter().map(|b| json!({"id": b, "name": names.get(b).cloned().unwrap_or(json!(b)), "efforts": effs.get(b).cloned().unwrap_or_default()})).collect::<Vec<_>>());
    m["default"] = cur;
    st().db.setkv("models", &m);
}
pub fn apply_cfg_generic(a: &AcpAgent, s: &Value, res: &Value) {
    let db = &st().db;
    let ag = AG::get(s["agent"].as_str().unwrap_or("codex"));
    let sid = pystr(&s["id"]);
    let mut cfg = res
        .get("configOptions")
        .filter(|x| truthy(Some(x)))
        .cloned();
    if let Some(c) = &cfg {
        db.setkv(&format!("cfg:{sid}"), c);
        db.setkv(&format!("agentcfg:{}", ag.id), c);
    }
    let mut want = ag.defaults.as_object().cloned().unwrap_or_default();
    for (k, v) in kvo(&format!("agentdef:{}", ag.id)).as_object().unwrap() {
        want.insert(k.clone(), v.clone());
    }
    let cs = kvo(&format!("cfgset:{sid}"));
    for (k, v) in cs.as_object().unwrap() {
        want.insert(k.clone(), v.clone());
    }
    let mode = s["mode"]
        .as_str()
        .filter(|x| !x.is_empty())
        .unwrap_or("auto");
    if let Some((k, v)) = ag.modes.get(mode) {
        if !cs.as_object().unwrap().contains_key(*k) {
            want.insert(k.to_string(), json!(v));
        }
    }
    let asid = a.acp_sid.lock().clone();
    for (k, v) in want {
        let o = cfg
            .as_ref()
            .and_then(|c| c.as_array())
            .and_then(|c| c.iter().find(|o| o["id"].as_str() == Some(&k)))
            .cloned();
        if let Some(o) = &o {
            if o.get("currentValue") == Some(&v) {
                continue;
            }
            if !AG::flat_options(o)
                .iter()
                .any(|x| x.get("value") == Some(&v))
            {
                continue;
            }
        }
        match a.rpc.call(
            "session/set_config_option",
            json!({"sessionId": asid, "configId": k, "value": v}),
            30,
        ) {
            Ok(r) => {
                if let Some(c) = r.get("configOptions").filter(|x| truthy(Some(x))) {
                    db.setkv(&format!("cfg:{sid}"), c);
                    cfg = Some(c.clone());
                }
            }
            Err(e) => log(&format!("set_config_option {k} {v} {e}")),
        }
    }
}
pub fn apply_cfg(rpc: &Rpc, asid: &Option<String>, s: &Value) {
    if truthy(s.get("model")) {
        let mid = format!(
            "{}{}",
            pystr(&s["model"]),
            if truthy(s.get("effort")) {
                format!("[{}]", pystr(&s["effort"]))
            } else {
                String::new()
            }
        );
        if let Err(e) = rpc.call(
            "session/set_model",
            json!({"sessionId": asid, "modelId": mid}),
            30,
        ) {
            log(&format!("set_model {e}"))
        }
    }
    if let Err(e) = rpc.call("session/set_mode", json!({"sessionId": asid, "modeId": if s["mode"] == "ask" { "read-only" } else { "agent" }}), 30) { log(&format!("set_mode {e}")) }
}
pub fn get_agent(sid: &str) -> Result<Arc<dyn AgentLike>, String> {
    let s = st();
    let _g = s.alk.lock();
    if let Some(a) = s.agent(sid) {
        if a.alive() {
            if let Some(rpc) = a.rpc() {
                crate::tasks::bind_runtime(&s.db, sid, rpc.pid(), &s.generation)
                    .map_err(|e| e.message)?;
            }
            return Ok(a);
        }
    }
    let sess = crate::sess_row(sid).ok_or("no session")?;
    let a = AcpAgent::spawn(&sess)?;
    let runtime_id = match crate::tasks::record_runtime(&s.db, sid, a.rpc.pid(), &s.generation) {
        Ok(id) => id,
        Err(e) => {
            a.rpc.kill();
            return Err(e.message);
        }
    };
    let fail = |e: String| {
        a.rpc.kill();
        let _ = crate::tasks::runtime_exited(&s.db, &runtime_id);
        e
    };
    let ini = a.rpc.call("initialize", json!({"protocolVersion": 1, "clientCapabilities": {"fs": {"readTextFile": false, "writeTextFile": false}, "terminal": false}}), 60).map_err(fail)?;
    if ini.get("protocolVersion").and_then(Value::as_i64) != Some(1) {
        return Err(fail("Agent 不支持当前 ACP 协议版本 1".into()));
    }
    let mut res = Value::Null;
    let agent = sess["agent"].as_str().unwrap_or("codex").to_string();
    if let Some(asid) = a.acp_sid.lock().clone() {
        if ini
            .pointer("/agentCapabilities/loadSession")
            .and_then(Value::as_bool)
            != Some(true)
        {
            return Err(fail(
                "context_restore_failed：Agent 不支持恢复旧对话，请新建对话".into(),
            ));
        }
        a.loading.store(true, Ordering::SeqCst);
        let rec = agent != "codex"
            && s.db
                .q1(
                    "select 1 from events where sid=? limit 1",
                    rusqlite::params![sid],
                    |r| r.get::<_, i64>(0),
                )
                .is_none();
        a.record.store(rec, Ordering::SeqCst);
        match a.rpc.call(
            "session/load",
            json!({"sessionId": asid, "cwd": sess["path"], "mcpServers": []}),
            120,
        ) {
            Ok(r) => res = r,
            Err(e) => {
                return Err(fail(format!(
                    "context_restore_failed：旧对话未恢复，未发送本轮消息；请新建对话。{e}"
                )))
            }
        }
        thread::sleep(Duration::from_millis(if rec { 500 } else { 300 }));
        a.loading.store(false, Ordering::SeqCst);
        a.record.store(false, Ordering::SeqCst);
        s.bus.flush(Some(sid));
    }
    if a.acp_sid.lock().is_none() {
        res = a
            .rpc
            .call(
                "session/new",
                json!({"cwd": sess["path"], "mcpServers": []}),
                60,
            )
            .map_err(fail)?;
        let ns = pystr(&res["sessionId"]);
        *a.acp_sid.lock() = Some(ns.clone());
        s.db.exec(
            "update sessions set acp_sid=? where id=?",
            rusqlite::params![ns, sid],
        );
    }
    if agent == "codex" {
        models_from(&res);
        apply_cfg(&a.rpc, &a.acp_sid.lock().clone(), &sess);
    } else {
        apply_cfg_generic(&a, &sess, &res);
    }
    let arc: Arc<dyn AgentLike> = a;
    if let Some(old) = s.agents.lock().insert(sid.to_string(), arc.clone()) {
        if !Arc::ptr_eq(&old, &arc) {
            old.kill();
        }
    }
    Ok(arc)
}

// ───────── probes ─────────
pub fn probe(aid: &str, cwd: &str, prompt: bool, timeout: u64, extra: &Value) -> Value {
    let a = AG::get(aid);
    let mut out = json!({"ok": false, "ts": now()});
    if !AG::installed(&a) {
        out["err"] = json!("未安装");
        return out;
    }
    let t0 = now();
    let txt = Arc::new(Mutex::new(String::new()));
    let r = (|| -> Result<(), String> {
        let rpc = Rpc::spawn(
            &AG::cmd(aid),
            cwd,
            Some(crate::runtime::env_map()),
            None,
            0,
            "进程退出".into(),
        )?;
        let (r2, t2) = (Arc::downgrade(&rpc), txt.clone());
        rpc.set_handler(Box::new(move |m| {
            if m.get("id").is_some() { if let Some(r) = r2.upgrade() {
                let oid = m.pointer("/params/options").and_then(|x| x.as_array()).and_then(|o| o.iter().find(|o| o["kind"].as_str().unwrap_or("").contains("reject"))).map(|o| o["optionId"].clone());
                r.send(&json!({"jsonrpc": "2.0", "id": m["id"], "result": {"outcome": match oid { Some(o) => json!({"outcome": "selected", "optionId": o}), None => json!({"outcome": "cancelled"}) }}}));
            } }
            else if m["method"] == "session/update" { let u = &m["params"]["update"]; if u["sessionUpdate"] == "agent_message_chunk" { t2.lock().push_str(u["content"]["text"].as_str().unwrap_or("")); } }
        }));
        let mut go = || -> Result<(), String> {
            let ini = rpc.call("initialize", json!({"protocolVersion": 1, "clientCapabilities": {"fs": {"readTextFile": false, "writeTextFile": false}, "terminal": false}}), 60)?;
            out["info"] = ini.get("agentInfo").cloned().unwrap_or(Value::Null);
            out["caps"] = ini
                .get("agentCapabilities")
                .filter(|x| truthy(Some(x)))
                .cloned()
                .unwrap_or(json!({}));
            let new = rpc.call("session/new", json!({"cwd": cwd, "mcpServers": []}), 90)?;
            out["cfg"] = new
                .get("configOptions")
                .filter(|x| truthy(Some(x)))
                .cloned()
                .unwrap_or(json!([]));
            out["models"] = new.get("models").cloned().unwrap_or(Value::Null);
            out["modes"] = new.get("modes").cloned().unwrap_or(Value::Null);
            let sid = new["sessionId"].clone();
            let mut d = a.defaults.as_object().cloned().unwrap_or_default();
            for (k, v) in extra.as_object().into_iter().flatten() {
                d.insert(k.clone(), v.clone());
            }
            for (k, v) in d {
                rpc.call(
                    "session/set_config_option",
                    json!({"sessionId": sid, "configId": k, "value": v}),
                    30,
                )?;
            }
            if prompt {
                rpc.call("session/prompt", json!({"sessionId": sid, "prompt": [{"type": "text", "text": "只回复 OK 两个字母，不要使用任何工具。"}]}), timeout)?;
                let rep: String = txt.lock().trim().chars().take(40).collect();
                out["reply"] = json!(rep);
                if rep.is_empty() {
                    return Err("无回复".into());
                }
            }
            Ok(())
        };
        let r = go();
        rpc.kill();
        r
    })();
    match r {
        Ok(()) => out["ok"] = json!(true),
        Err(e) => out["err"] = json!(e.chars().take(300).collect::<String>()),
    }
    out["secs"] = crate::py::fnum(crate::py::round(now() - t0, 1));
    out
}
pub fn probe_all(only: Option<Value>) {
    let s = st();
    if s.probing.swap(true, Ordering::SeqCst) {
        return;
    }
    let pr = Arc::new(Mutex::new(
        s.db.kv("agentprobe", Some(json!({})))
            .filter(|v| v.is_object())
            .unwrap_or(json!({})),
    ));
    let only: Option<Vec<String>> = only
        .and_then(|o| o.as_array().map(|a| a.iter().map(pystr).collect()))
        .filter(|v: &Vec<String>| !v.is_empty());
    let mut hs = vec![];
    for a in AG::reg() {
        if let Some(o) = &only {
            if !o.iter().any(|x| x == a.id) {
                continue;
            }
        }
        let pr = pr.clone();
        hs.push(thread::spawn(move || {
            if !AG::installed(&a) {
                pr.lock().as_object_mut().unwrap().remove(a.id);
                return;
            }
            let r = probe(
                a.id,
                &sandbox(),
                a.id != "codex",
                90,
                &kvo(&format!("agentdef:{}", a.id)),
            );
            if truthy(r.get("cfg")) {
                st().db.setkv(&format!("agentcfg:{}", a.id), &r["cfg"]);
            }
            log(&format!(
                "probe {} {} {} {}",
                a.id,
                r["ok"],
                r.get("err").map(pystr).unwrap_or("None".into()),
                r["secs"]
            ));
            pr.lock()[a.id] = r;
        }));
    }
    for h in hs {
        let _ = h.join();
    }
    s.db.setkv("agentprobe", &pr.lock());
    s.probing.store(false, Ordering::SeqCst);
}
pub fn startup_models_probe() {
    let acp = std::env::var("CODEX_ACP")
        .ok()
        .or_else(|| AG::which("codex-acp").map(|p| p.to_string_lossy().into_owned()))
        .unwrap_or_else(|| "codex-acp".into());
    let r = (|| -> Result<(), String> {
        let rpc = Rpc::spawn(
            &[acp],
            &sandbox(),
            Some(crate::runtime::env_map()),
            None,
            0,
            "codex-acp 退出".into(),
        )?;
        rpc.set_handler(Box::new(|_| {}));
        let r = (|| {
            rpc.call(
                "initialize",
                json!({"protocolVersion": 1, "clientCapabilities": {}}),
                60,
            )?;
            rpc.call(
                "session/new",
                json!({"cwd": sandbox(), "mcpServers": []}),
                90,
            )
        })();
        rpc.kill();
        models_from(&r?);
        Ok(())
    })();
    match r {
        Ok(()) => log(&format!(
            "models {}",
            st().models.lock()["models"]
                .as_array()
                .map(|a| a.len())
                .unwrap_or(0)
        )),
        Err(e) => log(&format!("probe err {e}")),
    }
}
pub fn ext_sessions(aid: &str) -> Value {
    let r = (|| -> Result<Vec<Value>, String> {
        let rpc = Rpc::spawn(
            &AG::cmd(aid),
            &sandbox(),
            Some(crate::runtime::env_map()),
            None,
            0,
            "进程退出".into(),
        )?;
        rpc.set_handler(Box::new(|_| {}));
        let res = (|| {
            let _ = rpc.call(
                "initialize",
                json!({"protocolVersion": 1, "clientCapabilities": {}}),
                40,
            );
            rpc.call("session/list", json!({}), 40)
        })();
        rpc.kill();
        let res = res.unwrap_or(json!({}));
        Ok(res
            .get("sessions")
            .and_then(|x| x.as_array())
            .cloned()
            .unwrap_or_default())
    })()
    .unwrap_or_default();
    let mine: std::collections::HashSet<String> = st()
        .db
        .q(
            "select acp_sid from sessions where agent=? and acp_sid is not null",
            rusqlite::params![aid],
            |r| r.get::<_, String>(0),
        )
        .into_iter()
        .collect();
    let h = home().to_string_lossy().into_owned();
    json!(r
        .into_iter()
        .filter(|x| x["cwd"].as_str().unwrap_or("").starts_with(&h))
        .take(200)
        .map(|mut x| {
            let im = mine.contains(x["sessionId"].as_str().unwrap_or("\u{0}"));
            x["imported"] = json!(im);
            x
        })
        .collect::<Vec<_>>())
}

// ───────── turn helpers ─────────
pub fn done_note(sid: &str, stop: &str) {
    let s = st();
    let row = crate::sess_row(sid).unwrap_or(json!({}));
    let last = s.db.q1(
        "select data from events where sid=? and type='msg' order by seq desc limit 1",
        rusqlite::params![sid],
        |r| r.get::<_, String>(0),
    );
    let body: String = last
        .and_then(|d| serde_json::from_str::<Value>(&d).ok())
        .map(|v| {
            v["text"]
                .as_str()
                .unwrap_or("")
                .trim()
                .replace('\n', " ")
                .chars()
                .take(200)
                .collect()
        })
        .unwrap_or_default();
    let t = if truthy(row.get("title")) {
        pystr(&row["title"])
    } else {
        "对话".into()
    };
    let head = match stop {
        "cancelled" => "已停止",
        "error" => "出错",
        _ => "完成",
    };
    s.bus.note(
        sid,
        match stop { "cancelled" => "cancelled", "error" => "error", _ => "done" },
        &format!("{head} · {t}"),
        if body.is_empty() { stop } else { &body },
    );
}
pub fn set_title(sid: &str, t: &str) {
    let s = st();
    if t.is_empty() {
        return;
    }
    let cur =
        s.db.q1(
            "select title from sessions where id=?",
            rusqlite::params![sid],
            |r| r.get::<_, Option<String>>(0),
        )
        .flatten()
        .unwrap_or_default();
    if cur.is_empty() {
        let t: String = t.chars().take(60).collect();
        s.db.exec(
            "update sessions set title=? where id=?",
            rusqlite::params![t, sid],
        );
        s.bus.emit(sid, "title", json!({"title": t}));
    }
}
pub fn queue_prompt(sid: &str, text: String, source: Option<&str>) -> Result<(), String> {
    let fallback = uuid::Uuid::new_v4().to_string();
    crate::tasks::defer_prompt(&st().db, sid, source.unwrap_or(&fallback), &text)
        .map_err(|e| e.message)
}
pub fn start_deferred_worker() {
    thread::spawn(|| {
        while !crate::runtime::stopping() {
            match crate::tasks::deferred_candidates(&st().db) {
                Ok(candidates) => {
                    for (id, sid, body) in candidates {
                        if crate::runtime::stopping() {
                            break;
                        }
                        match crate::tasks::submit_deferred(
                            &st().db,
                            &id,
                            &sid,
                            &body,
                            &st().generation,
                            crate::config::get().max_active_tasks,
                        ) {
                            Ok(a) if a.fresh => {
                                let task = a.receipt["task_id"].as_str().unwrap_or("").to_string();
                                dispatch_task(task, sid, vec![json!({"type":"text","text":body})]);
                            }
                            Ok(_) => {}
                            Err(e)
                                if matches!(
                                    e.code.as_str(),
                                    "deferred_waiting" | "deferred_not_ready"
                                ) => {}
                            Err(e) => log(&format!("deferred task {}: {}", id, e.message)),
                        }
                    }
                }
                Err(e) => log(&format!("deferred queue: {}", e.message)),
            }
            thread::sleep(Duration::from_secs(1));
        }
    });
}
fn dispatch_task(task: String, sid: String, blocks: Vec<Value>) {
    thread::spawn(move || {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            run_task(&task, &sid, &blocks)
        }));
        if result.is_err() {
            let _ = crate::tasks::transition(
                &st().db,
                &task,
                "unknown",
                Some("execution_worker_panicked"),
            );
            if let Some(a) = st().agent(&sid) {
                a.set_running(false);
            }
        }
    });
}
#[cfg(not(feature = "desktop-sync"))]
pub fn desk_attach(_sid: &str, _force: bool) -> Option<Arc<dyn AgentLike>> {
    None
}
#[cfg(not(feature = "desktop-sync"))]
fn desk_prompt(_sid: &str, _text: &str, _imgs: &[Value]) -> Result<bool, String> {
    Ok(false)
}
#[cfg(feature = "desktop-sync")]
pub fn desk_attach(sid: &str, force: bool) -> Option<Arc<dyn AgentLike>> {
    let s = st();
    let row = crate::sess_row(sid)?;
    let tid = row["acp_sid"]
        .as_str()
        .filter(|x| !x.is_empty())?
        .to_string();
    if row["agent"].as_str().unwrap_or("codex") != "codex" {
        return None;
    }
    if let Some(a) = s.agent(sid) {
        if a.is_desk() && a.backend() == "desktop" && a.alive() && a.rpc().is_none() {
            return Some(a);
        }
        if a.running() || !a.perm_ids().is_empty() {
            return None;
        }
    }
    if !s.use_desk() {
        return None;
    }
    if !(if force {
        desk::ensure_owner(&tid, 15)
    } else {
        desk::ipc().owned(&tid)
    }) {
        return None;
    }
    let _g = s.alk.lock();
    if let Some(a) = s.agent(sid) {
        if a.is_desk() && a.rpc().is_none() && a.alive() {
            return Some(a);
        }
        a.kill();
    }
    let d: Arc<dyn AgentLike> = Desk::new(sid, &tid);
    s.agents.lock().insert(sid.into(), d.clone());
    log(&format!(
        "desk attached {} {}",
        &sid[..6.min(sid.len())],
        &tid[..8.min(tid.len())]
    ));
    Some(d)
}
#[cfg(feature = "desktop-sync")]
fn desk_prompt(sid: &str, text: &str, imgs: &[Value]) -> Result<bool, String> {
    let s = st();
    let row = crate::sess_row(sid).ok_or("no session")?;
    let cfg = json!({"model": row["model"], "effort": row["effort"]});
    if !truthy(row.get("acp_sid")) {
        s.bus.emit(sid, "turn", json!({"state": "running"}));
        let ft = FirstTurn::new(sid, row["path"].as_str().unwrap_or("."), cfg)?;
        let fa: Arc<dyn AgentLike> = ft.clone();
        s.agents.lock().insert(sid.into(), fa.clone());
        let r = ft.run(text, imgs, row["mode"].as_str().unwrap_or("auto"));
        let stop = match &r {
            Ok(tid) => {
                s.db.exec(
                    "update sessions set acp_sid=? where id=?",
                    rusqlite::params![tid, sid],
                );
                ft.wait_done()
            }
            Err(_) => String::new(),
        };
        ft.kill();
        {
            let mut ag = s.agents.lock();
            if ag.get(sid).map(|x| Arc::ptr_eq(x, &fa)).unwrap_or(false) {
                ag.remove(sid);
            }
        }
        r?;
        s.bus.flush(Some(sid));
        s.bus
            .emit(sid, "turn", json!({"state": "idle", "stopReason": stop}));
        done_note(sid, &stop);
        let sid2 = sid.to_string();
        thread::spawn(move || {
            desk_attach(&sid2, true);
        });
        return Ok(true);
    }
    let Some(a) = desk_attach(sid, true) else {
        return Ok(false);
    };
    s.bus.emit(sid, "turn", json!({"state": "running"}));
    let d = desk::ipc()
        .subs
        .lock()
        .get(row["acp_sid"].as_str().unwrap())
        .and_then(|w| w.upgrade())
        .ok_or("desk gone")?;
    let _ = a;
    d.start(text, imgs, &cfg)?;
    Ok(true)
}
pub fn submit_prompt(
    sid: &str,
    caller: &str,
    op: &str,
    epoch: Option<&str>,
    payload: &Value,
) -> Result<crate::tasks::Admission, crate::tasks::Error> {
    let s = st();
    s.bus.flush(Some(sid));
    let admission = crate::tasks::submit(
        &s.db,
        caller,
        op,
        sid,
        epoch,
        payload,
        &s.generation,
        crate::config::get().max_active_tasks,
    )?;
    if admission.fresh {
        let sid = sid.to_string();
        let task = admission.receipt["task_id"].as_str().unwrap().to_string();
        let text = payload["text"].as_str().unwrap_or("").trim().to_string();
        let imgs = payload["images"].as_array().cloned().unwrap_or_default();
        let mut blocks:Vec<Value>=imgs.iter().filter(|i|i.get("data").is_some()).map(|i|json!({"type":"image","mimeType":i.get("mime").cloned().unwrap_or(json!("image/jpeg")),"data":i["data"]})).collect();
        if !text.is_empty() {
            blocks.push(json!({"type":"text","text":text}));
        }
        dispatch_task(task, sid, blocks);
    }
    Ok(admission)
}

fn run_task(task: &str, sid: &str, blocks: &[Value]) {
    let s = st();
    if !crate::tasks::transition(&s.db, task, "running", None).unwrap_or(false) {
        return;
    }
    let result = (|| -> Result<String, (bool, String)> {
        if std::env::var("LEOAGENT_NO_PROMPT").is_ok() {
            return Err((false, "NO_PROMPT test mode".into()));
        }
        let ag = get_agent(sid).map_err(|e| (false, e))?;
        let cancelled = crate::tasks::get(&s.db, task)
            .map_err(|e| (false, e.message))?
            .is_some_and(|t| t["cancel_requested"] == true);
        if cancelled {
            return Ok("cancelled".into());
        }
        // A desktop-owned handle cannot be silently retried through another backend.
        let rpc = ag.rpc().ok_or_else(|| {
            (
                false,
                "该会话正由实验桌面后端管理；请使用独立 ACP 会话执行持久任务".into(),
            )
        })?;
        ag.set_running(true);
        ag.touch();
        s.bus
            .emit(sid, "turn", json!({"state":"running","task_id":task}));
        if !crate::tasks::claim_dispatch(&s.db, task).map_err(|e| (false, e.message))? {
            return Ok("cancelled".into());
        }
        rpc.call(
            "session/prompt",
            json!({"sessionId":ag.acp_sid(),"prompt":blocks}),
            6 * 3600,
        )
        .map(|r| {
            r.get("stopReason")
                .and_then(Value::as_str)
                .unwrap_or("end_turn")
                .to_string()
        })
        .map_err(|e| (true, e))
    })();
    let (state, reason) = match result {
        Ok(stop) if stop == "end_turn" => ("succeeded", stop),
        Ok(stop) if stop == "cancelled" => ("cancelled", stop),
        Ok(stop) => ("failed", stop),
        Err((dispatched, e)) => {
            s.bus.emit(sid, "error", json!({"msg":e}));
            (if dispatched { "unknown" } else { "failed" }, e)
        }
    };
    s.bus.flush(Some(sid));
    // Keep the project claim until the old runtime has stopped generating events.
    if state == "unknown" {
        if let Some(a) = s.agent(sid) {
            a.kill();
        }
    }
    if let Some(a) = s.agent(sid) {
        a.set_running(false);
        a.touch();
    }
    match crate::tasks::transition(&s.db, task, state, Some(&reason)) {
        Ok(true) => {}
        Ok(false) => return,
        Err(e) => {
            log(&e.message);
            return;
        }
    }
    s.bus.emit(sid,"turn",json!({"state":"idle","stopReason":if state=="failed"{"error"}else{state},"task_id":task,"reason":reason}));

}

pub fn cancel_task(id: &str) -> Result<Option<Value>, crate::tasks::Error> {
    let s = st();
    let task = crate::tasks::request_cancel(&s.db, id)?;
    if let Some(t) = &task {
        if t["state"] == "cancelling" {
            send_cancel(id, t["session_id"].as_str().unwrap());
        }
    }
    Ok(task)
}

pub fn send_cancel(id: &str, sid: &str) {
    let sid = sid.to_string();
    let id = id.to_string();
    if let Some(a) = st().agent(&sid) {
        thread::spawn(move || a.cancel());
    }
    thread::spawn(move || {
        thread::sleep(Duration::from_secs(20));
        if crate::tasks::cancel_timeout(&st().db, &id).unwrap_or(false) {
            if let Some(a) = st().agent(&sid) {
                a.kill();
                a.set_running(false);
            }
            st().bus.emit(&sid,"turn",json!({"task_id":id,"state":"idle","stopReason":"unknown","reason":"cancel_timeout"}));
        }
    });
}

pub fn resolve_task(
    caller: &str,
    op: &str,
    id: &str,
    sid: &str,
) -> Result<crate::tasks::Admission, crate::tasks::Error> {
    let s = st();
    if let Some(a) = s.agent(sid) {
        a.kill();
        if a.alive() {
            return crate::tasks::finish_resolution(
                &s.db,
                caller,
                op,
                id,
                Some("无法确认原 Agent 已结束，项目仍保持占用"),
            );
        }
    }
    for record in crate::tasks::runtime_evidence(&s.db, id)? {
        if crate::tasks::runtime_may_be_alive(
            record["pid"].as_i64().unwrap_or(0) as i32,
            record["process_start"].as_str(),
        ) {
            return crate::tasks::finish_resolution(&s.db,caller,op,id,Some("原 Agent 进程可能仍在运行，请在电脑上核对并结束该进程后重试；服务不会按旧 PID 自动终止进程"));
        }
    }
    s.agents.lock().remove(sid);
    crate::tasks::finish_resolution(&s.db, caller, op, id, None)
}

// ───────── imports ─────────
pub fn import_rollout(sid: &str, rp: Option<&str>, mt: f64) {
    let s = st();
    let evs = if rp.is_some() {
        crate::threads::parse_rollout(rp, crate::events::trunc)
    } else {
        vec![]
    };
    {
        let _g = s.db.lock.lock();
        for (typ, d) in evs {
            s.db.exec(
                "insert into events(sid,ts,type,data) values(?,?,?,?)",
                rusqlite::params![sid, mt, typ, crate::py::dumps(&d)],
            );
        }
        s.db.exec(
            "update sessions set updated=? where id=?",
            rusqlite::params![if mt != 0.0 { mt } else { now() }, sid],
        );
    }
    s.db.setkv(&format!("imp:{sid}"), &crate::py::fnum(mt));
    s.db.notify_all();
}
fn ensure_project(cwd: &str) -> i64 {
    let db = &st().db;
    if let Some(p) = db.q1(
        "select id from projects where path=?",
        rusqlite::params![cwd],
        |r| r.get::<_, i64>(0),
    ) {
        return p;
    }
    let name = cwd
        .trim_end_matches('/')
        .rsplit('/')
        .next()
        .filter(|x| !x.is_empty())
        .unwrap_or(cwd)
        .to_string();
    db.exec(
        "insert or ignore into projects(name,path,created) values(?,?,?)",
        rusqlite::params![name, cwd, now()],
    );
    db.q1(
        "select id from projects where path=?",
        rusqlite::params![cwd],
        |r| r.get::<_, i64>(0),
    )
    .unwrap_or(0)
}
fn new_sid() -> String {
    uuid::Uuid::new_v4().simple().to_string()[..16].to_string()
}
fn def_mode() -> String {
    st().db
        .kv("default_mode", Some(json!("auto")))
        .map(|v| pystr(&v))
        .unwrap_or("auto".into())
}
pub fn threads_open(tid: &str) -> Result<Value, String> {
    let s = st();
    let (_, rp, cwd, title) = crate::threads::thread_row(tid).ok_or("no thread")?;
    let ex = s.db.q1(
        "select id from sessions where acp_sid=?",
        rusqlite::params![tid],
        |r| r.get::<_, String>(0),
    );
    let rp: Option<String> = crate::threads::resolve_rollout(rp.as_deref())
        .map(|p| p.to_string_lossy().into_owned())
        .or(rp);
    let mt = rp
        .as_ref()
        .and_then(|p| std::fs::metadata(p).ok())
        .and_then(|m| m.modified().ok())
        .map(|t| {
            t.duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs_f64()
        })
        .unwrap_or(0.0);
    let sid = if let Some(sid) = ex {
        let imp =
            s.db.kv(&format!("imp:{sid}"), None)
                .and_then(|v| v.as_f64());
        let busy = s
            .agent(&sid)
            .map(|a| a.running() || a.alive())
            .unwrap_or(false);
        if let Some(imp) = imp {
            if mt > imp + 1.0 && !busy && !desk_owned(tid) {
                s.db.exec("delete from events where sid=?", rusqlite::params![sid]);
                import_rollout(&sid, rp.as_deref(), mt);
            }
        }
        sid
    } else {
        let pr = ensure_project(&cwd);
        let sid = new_sid();
        let t: String = title
            .trim()
            .lines()
            .next()
            .unwrap_or("")
            .chars()
            .take(60)
            .collect();
        s.db.exec("insert into sessions(id,project_id,acp_sid,title,model,effort,mode,created,updated,agent) values(?,?,?,?,?,?,?,?,?,?)",
            rusqlite::params![sid, pr, tid, t, "", "", def_mode(), now(), if mt != 0.0 { mt } else { now() }, "codex"]);
        import_rollout(&sid, rp.as_deref(), mt);
        sid
    };
    let opened = crate::threads::opened().get(tid).cloned();
    let sid2 = sid.clone();
    thread::spawn(move || {
        desk_attach(&sid2, false);
    });
    let mut r = crate::sess_row(&sid).ok_or("no session")?;
    r["open_on_mac"] = json!(opened.as_deref() == Some("desktop"));
    Ok(r)
}
pub fn agents_import(aid: &str, b: &Value) -> Result<Value, String> {
    let s = st();
    let esid = b.get("sessionId").map(pystr).ok_or("sessionId")?;
    let cwd = b
        .get("cwd")
        .filter(|x| truthy(Some(x)))
        .map(pystr)
        .unwrap_or_else(sandbox);
    if let Some(ex) = s.db.q1(
        "select id from sessions where acp_sid=? and agent=?",
        rusqlite::params![esid, aid],
        |r| r.get::<_, String>(0),
    ) {
        return crate::sess_row(&ex).ok_or("no session".into());
    }
    let pr = ensure_project(&cwd);
    let sid = new_sid();
    let t: String = b
        .get("title")
        .filter(|x| truthy(Some(x)))
        .map(pystr)
        .unwrap_or_default()
        .chars()
        .take(60)
        .collect();
    s.db.exec("insert into sessions(id,project_id,acp_sid,title,model,effort,mode,created,updated,agent) values(?,?,?,?,?,?,?,?,?,?)",
        rusqlite::params![sid, pr, esid, t, "", "", def_mode(), now(), now(), aid]);
    if let Err(e) = get_agent(&sid) {
        log(&format!("import load {e}"))
    }
    crate::sess_row(&sid).ok_or("no session".into())
}
