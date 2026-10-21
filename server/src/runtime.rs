//! JSON-RPC stdio child, shared ask store, agent trait, ACP agent (= bridge.py Agent)
#![allow(dead_code)]
use crate::{agents, ask, db, events};
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::mpsc;
use std::sync::Weak;
use std::sync::{Arc, OnceLock};
use std::thread;
use std::time::Duration;

pub fn now() -> f64 {
    db::now()
}
pub fn log(msg: &str) {
    eprintln!("{} {}", chrono::Local::now().format("%m-%d %H:%M:%S"), msg);
}
pub fn rid12() -> String {
    uuid::Uuid::new_v4().simple().to_string()[..12].to_string()
}
pub fn env_map() -> HashMap<String, String> {
    let mut e: HashMap<String, String> = std::env::vars().collect();
    let home = db::home().to_string_lossy().into_owned();
    e.insert("PATH".into(), agents::path_env());
    e.insert("HOME".into(), home);
    e
}
pub fn no_desk() -> bool {
    std::env::var("LEOAGENT_NO_DESK").is_ok()
}

// ───────── JSON-RPC over stdio ─────────
type Handler = Box<dyn Fn(Value) + Send + Sync>;
static RPCS: OnceLock<Mutex<Vec<Weak<Rpc>>>> = OnceLock::new();
static STOPPING: AtomicBool = AtomicBool::new(false);
pub fn stopping() -> bool {
    STOPPING.load(Ordering::SeqCst)
}
pub fn shutdown_owned() {
    let owned = {
        let mut handles = RPCS.get_or_init(|| Mutex::new(Vec::new())).lock();
        STOPPING.store(true, Ordering::SeqCst);
        let owned = handles.iter().filter_map(Weak::upgrade).collect::<Vec<_>>();
        handles.clear();
        owned
    };
    let threads = owned
        .into_iter()
        .map(|rpc| thread::spawn(move || rpc.kill()))
        .collect::<Vec<_>>();
    for handle in threads {
        let _ = handle.join();
    }
}
pub struct Rpc {
    child: Mutex<Child>,
    stdin: Mutex<ChildStdin>,
    n: AtomicI64,
    wait: Mutex<HashMap<i64, mpsc::Sender<Value>>>,
    handler: OnceLock<Handler>,
    on_exit: OnceLock<Box<dyn Fn() + Send + Sync>>,
    pub dead: AtomicBool,
    exit_msg: String,
}
impl Rpc {
    pub fn spawn(
        cmd: &[String],
        cwd: &str,
        env: Option<HashMap<String, String>>,
        log_tag: Option<String>,
        start_id: i64,
        exit_msg: String,
    ) -> Result<Arc<Self>, String> {
        let mut handles = RPCS.get_or_init(|| Mutex::new(Vec::new())).lock();
        if stopping() {
            return Err("server_stopping".into());
        }
        let mut c = Command::new(&cmd[0]);
        c.args(&cmd[1..])
            .current_dir(cwd)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(if log_tag.is_some() {
                Stdio::piped()
            } else {
                Stdio::null()
            });
        use std::os::unix::process::CommandExt;
        // Only processes spawned by this Rpc enter this group. Never derive a signal
        // target from the persisted runtime table after a server restart.
        c.process_group(0);
        if let Some(e) = env {
            c.env_clear().envs(e);
        }
        let mut child = c.spawn().map_err(|e| format!("{}: {e}", cmd[0]))?;
        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        let stderr = child.stderr.take();
        let r = Arc::new(Self {
            child: Mutex::new(child),
            stdin: Mutex::new(stdin),
            n: AtomicI64::new(start_id),
            wait: Mutex::new(HashMap::new()),
            handler: OnceLock::new(),
            on_exit: OnceLock::new(),
            dead: AtomicBool::new(false),
            exit_msg,
        });
        handles.retain(|w| w.strong_count() > 0);
        handles.push(Arc::downgrade(&r));
        drop(handles);
        if let (Some(se), Some(tag)) = (stderr, log_tag) {
            thread::spawn(move || {
                for l in BufReader::new(se).lines().flatten() {
                    if !l.trim().is_empty() {
                        log(&format!(
                            "[{tag}] {}",
                            l.chars().take(300).collect::<String>()
                        ))
                    }
                }
            });
        }
        let r2 = r.clone();
        thread::spawn(move || {
            for l in BufReader::new(stdout).lines() {
                let Ok(l) = l else { break };
                let Ok(m) = serde_json::from_str::<Value>(&l) else {
                    continue;
                };
                let is_resp = m.get("id").is_some() && m.get("method").is_none();
                if is_resp {
                    if let Some(id) = m["id"].as_i64() {
                        if let Some(tx) = r2.wait.lock().remove(&id) {
                            let _ = tx.send(m);
                        }
                    }
                    continue;
                }
                for _ in 0..100 {
                    if r2.handler.get().is_some() {
                        break;
                    }
                    thread::sleep(Duration::from_millis(10));
                }
                if let Some(h) = r2.handler.get() {
                    let h: &Handler = h;
                    let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| h(m)));
                    if res.is_err() {
                        log("rpc handler panic");
                    }
                }
            }
            r2.dead.store(true, Ordering::SeqCst);
            for (_, tx) in r2.wait.lock().drain() {
                let _ = tx.send(json!({"error": {"message": r2.exit_msg}}));
            }
            if let Some(f) = r2.on_exit.get() {
                f();
            }
        });
        Ok(r)
    }
    pub fn set_handler(&self, h: Handler) {
        let _ = self.handler.set(h);
    }
    pub fn set_on_exit(&self, f: Box<dyn Fn() + Send + Sync>) {
        let _ = self.on_exit.set(f);
    }
    pub fn send_checked(&self, o: &Value) -> Result<(), String> {
        let mut s = self.stdin.lock();
        let r = writeln!(s, "{}", serde_json::to_string(o).unwrap())
            .and_then(|_| s.flush())
            .map_err(|e| e.to_string());
        if let Err(e) = &r {
            self.dead.store(true, Ordering::SeqCst);
            for (_, tx) in self.wait.lock().drain() {
                let _ = tx.send(json!({"error":{"message":format!("pipe write failed: {e}")}}));
            }
        }
        r
    }
    pub fn send(&self, o: &Value) {
        if let Err(e) = self.send_checked(o) {
            log(&format!("rpc write failed: {e}"));
        }
    }
    pub fn notify(&self, method: &str, params: Value) {
        self.send(&json!({"jsonrpc":"2.0","method":method,"params":params}))
    }
    pub fn call(&self, method: &str, params: Value, timeout: u64) -> Result<Value, String> {
        let i = self.n.fetch_add(1, Ordering::SeqCst) + 1;
        let (tx, rx) = mpsc::channel();
        self.wait.lock().insert(i, tx);
        if self.dead.load(Ordering::SeqCst) {
            self.wait.lock().remove(&i);
            return Err(format!("{method}: {}", self.exit_msg));
        }
        self.send(&json!({"jsonrpc":"2.0","id":i,"method":method,"params":params}));
        match rx.recv_timeout(Duration::from_secs(timeout)) {
            Err(_) => {
                self.wait.lock().remove(&i);
                Err(method.to_string())
            }
            Ok(r) => {
                if let Some(e) = r.get("error") {
                    return Err(format!(
                        "{method}: {}",
                        e.get("message")
                            .map(|m| m.as_str().map(|s| s.to_string()).unwrap_or(m.to_string()))
                            .unwrap_or("None".into())
                    ));
                }
                Ok(r.get("result").cloned().unwrap_or(Value::Null))
            }
        }
    }
    pub fn alive(&self) -> bool {
        !self.dead.load(Ordering::SeqCst) && self.child.lock().try_wait().ok().flatten().is_none()
    }
    pub fn pid(&self) -> u32 {
        self.child.lock().id()
    }
    pub fn kill(&self) {
        let mut c = self.child.lock();
        // A reaped PID can be reused. Never signal it or its old process group.
        if !matches!(c.try_wait(), Ok(None)) {
            return;
        }
        let group = -(c.id() as i32);
        unsafe {
            libc::kill(group, libc::SIGTERM);
        }
        // Keep the owned child unreaped until the group escalation; this prevents
        // its PID/group identity being reused while we still hold the handle.
        thread::sleep(Duration::from_millis(500));
        unsafe {
            libc::kill(group, libc::SIGKILL);
        }
        let _ = c.wait();
        self.dead.store(true, Ordering::SeqCst);
    }
}

// ───────── asks (desk.Asker) ─────────
#[derive(Default)]
pub struct Asks {
    pub m: Mutex<Vec<(String, Value, Value)>>,
} // (rid, spec, ctx), insertion order
impl Asks {
    pub fn has(&self, rid: &str) -> bool {
        self.m.lock().iter().any(|x| x.0 == rid)
    }
    pub fn is_empty(&self) -> bool {
        self.m.lock().is_empty()
    }
    pub fn take(&self, rid: &str) -> Option<(Value, Value)> {
        let mut g = self.m.lock();
        let i = g.iter().position(|x| x.0 == rid)?;
        let (_, s, c) = g.remove(i);
        Some((s, c))
    }
    pub fn put(&self, rid: &str, spec: Value, ctx: Value) {
        self.m.lock().push((rid.to_string(), spec, ctx))
    }
    pub fn ctxs(&self) -> Vec<Value> {
        self.m.lock().iter().map(|x| x.2.clone()).collect()
    }
    pub fn add(&self, sid: &str, spec: Value, ctx: Value) -> String {
        let rid = rid12();
        let mut spec = spec;
        spec["rid"] = json!(rid);
        self.put(&rid, spec.clone(), ctx);
        let bus = &crate::st().bus;
        bus.emit(sid, "ask", spec.clone());
        rid
    }
    pub fn answered_elsewhere(&self, sid: &str, ids: &HashSet<String>, r: &Value) {
        let hit: Vec<String> = self
            .m
            .lock()
            .iter()
            .filter(|(_, spec, ctx)| {
                ctx.get("type").and_then(|x| x.as_str()) == Some("async")
                    && spec["questions"]
                        .as_array()
                        .map(|qs| {
                            qs.iter()
                                .any(|q| ids.contains(q["id"].as_str().unwrap_or("")))
                        })
                        .unwrap_or(false)
            })
            .map(|x| x.0.clone())
            .collect();
        for rid in hit {
            self.take(&rid);
            let summ = r
                .as_array()
                .map(|a| {
                    a.iter()
                        .filter(|x| x.is_object())
                        .map(|x| crate::py::pystr(x.get("answer").unwrap_or(&Value::Null)))
                        .collect::<Vec<_>>()
                        .join("；")
                })
                .unwrap_or_default();
            crate::st().bus.emit(
                sid,
                "ask_done",
                json!({"rid": rid, "summary": summ, "by": "desktop"}),
            );
        }
    }
    pub fn gone(&self, sid: &str, pred: impl Fn(&Value) -> bool) {
        let hit: Vec<String> = self
            .m
            .lock()
            .iter()
            .filter(|x| pred(&x.2))
            .map(|x| x.0.clone())
            .collect();
        for rid in hit {
            self.take(&rid);
            crate::st().bus.emit(
                sid,
                "ask_done",
                json!({"rid": rid, "summary": "已在桌面端处理", "by": "desktop"}),
            );
        }
    }
}
pub fn async_text(spec: &Value, answers: &Value) -> Option<String> {
    let mut out = vec![];
    for q in spec["questions"].as_array().into_iter().flatten() {
        let id = q["id"].as_str().unwrap_or("");
        let a: Vec<String> = answers
            .get(id)
            .and_then(|x| x.as_array())
            .map(|a| a.iter().map(crate::py::pystr).collect())
            .unwrap_or_default();
        if !a.is_empty() {
            out.push(json!({"questionItemId": id, "question": q["title"], "answer": a.join("、")}));
        }
    }
    if out.is_empty() {
        None
    } else {
        Some(ask::reply_text(&json!(out)))
    }
}

// ───────── agent trait ─────────
pub trait AgentLike: Send + Sync {
    fn sid(&self) -> &str;
    fn alive(&self) -> bool;
    fn running(&self) -> bool;
    fn set_running(&self, v: bool);
    fn backend(&self) -> &'static str;
    fn last(&self) -> f64;
    fn touch(&self);
    fn perm_ids(&self) -> Vec<String>;
    fn asks(&self) -> &Asks;
    fn answer_perm(&self, rid: &str, option: Option<String>) -> bool;
    fn send_answer(
        &self,
        ctx: &Value,
        spec: &Value,
        answers: &Value,
        text: &str,
        skip: bool,
    ) -> Result<(), String>;
    fn cancel(&self);
    fn cancel_perms(&self) {
        for r in self.perm_ids() {
            self.answer_perm(&r, None);
        }
    }
    fn kill(&self);
    fn rpc(&self) -> Option<Arc<Rpc>> {
        None
    }
    fn acp_sid(&self) -> Option<String> {
        None
    }
    fn is_desk(&self) -> bool {
        false
    }
}
/// desk.Asker.answer_ask → Ok(false)=not mine
pub fn answer_ask(
    a: &dyn AgentLike,
    rid: &str,
    answers: &Value,
    text: &str,
    skip: bool,
) -> Result<bool, String> {
    let Some((spec, ctx)) = a.asks().take(rid) else {
        return Ok(false);
    };
    let mut ans = serde_json::Map::new();
    if let Some(m) = answers.as_object() {
        for (k, v) in m {
            ans.insert(k.clone(), if v.is_array() { v.clone() } else { json!([v]) });
        }
    }
    if !text.is_empty() {
        if let Some(qs) = spec["questions"].as_array().filter(|q| q.len() == 1) {
            let k = qs[0]["id"].as_str().unwrap_or("").to_string();
            let mut arr: Vec<Value> = ans
                .get(&k)
                .and_then(|x| x.as_array())
                .cloned()
                .unwrap_or_default()
                .into_iter()
                .filter(|x| crate::py::truthy(Some(x)))
                .collect();
            arr.push(json!(text));
            ans.insert(k, json!(arr));
        }
    }
    let ans = Value::Object(ans);
    if let Err(e) = a.send_answer(&ctx, &spec, &ans, text, skip) {
        a.asks().put(rid, spec, ctx);
        return Err(e);
    }
    crate::st().bus.emit(a.sid(), "ask_done", json!({"rid": rid, "summary": if skip { "已跳过".to_string() } else { ask::summarize(&ans) }, "by": "phone"}));
    Ok(true)
}
pub fn elicit_content(answers: &Value) -> Value {
    let mut m = serde_json::Map::new();
    if let Some(o) = answers.as_object() {
        for (k, v) in o {
            m.insert(
                k.clone(),
                if v.as_array().map(|a| a.len() == 1).unwrap_or(false) {
                    v[0].clone()
                } else {
                    v.clone()
                },
            );
        }
    }
    Value::Object(m)
}

// ───────── ACP agent ─────────
pub struct AcpAgent {
    pub sid: String,
    pub agent: String,
    pub cwd: String,
    pub acp_sid: Mutex<Option<String>>,
    pub rpc: Arc<Rpc>,
    pub running: AtomicBool,
    pub last: Mutex<f64>,
    pub loading: AtomicBool,
    pub record: AtomicBool,
    tools: Mutex<HashMap<String, Value>>,
    perms: Mutex<Vec<(String, Value, Vec<Value>)>>,
    asks: Asks,
    home: String,
}
impl AcpAgent {
    pub fn spawn(sess: &Value) -> Result<Arc<Self>, String> {
        let agent = sess["agent"].as_str().unwrap_or("codex").to_string();
        let cwd = sess["path"].as_str().unwrap_or(".").to_string();
        let sid = sess["id"].as_str().unwrap().to_string();
        let cmd = agents::cmd(&agent);
        let rpc = Rpc::spawn(
            &cmd,
            &cwd,
            Some(env_map()),
            Some(format!("acp {}", &sid[..6.min(sid.len())])),
            0,
            format!("{} 进程退出", agents::get(&agent).name),
        )?;
        let a = Arc::new(Self {
            sid,
            agent,
            cwd,
            acp_sid: Mutex::new(sess["acp_sid"].as_str().map(|s| s.to_string())),
            rpc: rpc.clone(),
            running: AtomicBool::new(false),
            last: Mutex::new(now()),
            loading: AtomicBool::new(false),
            record: AtomicBool::new(false),
            tools: Mutex::new(HashMap::new()),
            perms: Mutex::new(vec![]),
            asks: Asks::default(),
            home: db::home().to_string_lossy().into_owned(),
        });
        let w = Arc::downgrade(&a);
        rpc.set_handler(Box::new(move |m| {
            if let Some(a) = w.upgrade() {
                a.on_msg(m)
            }
        }));
        let w2 = Arc::downgrade(&a);
        rpc.set_on_exit(Box::new(move || {
            if let Some(a) = w2.upgrade() {
                log(&format!("agent {} exited", &a.sid[..6.min(a.sid.len())]));
                a.running.store(false, Ordering::SeqCst);
            }
        }));
        Ok(a)
    }
    fn on_msg(&self, m: Value) {
        if m.get("method").is_some() && m.get("id").is_some() {
            self.on_req(&m)
        } else if m["method"] == "session/update"
            && (!self.loading.load(Ordering::SeqCst) || self.record.load(Ordering::SeqCst))
        {
            self.on_upd(m["params"].get("update").cloned().unwrap_or(json!({})))
        }
    }
    fn on_req(&self, m: &Value) {
        let bus = &crate::st().bus;
        if m["method"] == "session/request_permission" {
            let pr = &m["params"];
            let tc = pr.get("toolCall").cloned().unwrap_or(json!({}));
            let rid = rid12();
            let opts: Vec<Value> = pr["options"].as_array().into_iter().flatten().map(|o| json!({"id": o.get("optionId"), "name": o.get("name"), "kind": o.get("kind")})).collect();
            self.perms
                .lock()
                .push((rid.clone(), m["id"].clone(), opts.clone()));
            let tcid = tc
                .get("toolCallId")
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            let mut title = if crate::py::truthy(tc.get("title")) {
                crate::py::pystr(&tc["title"])
            } else {
                self.tools
                    .lock()
                    .get(&tcid)
                    .and_then(|t| t.get("title"))
                    .filter(|x| crate::py::truthy(Some(x)))
                    .map(crate::py::pystr)
                    .unwrap_or_else(|| "操作".into())
            };
            let mut detail = String::new();
            for c in tc
                .get("content")
                .and_then(|x| x.as_array())
                .into_iter()
                .flatten()
            {
                match c.get("type").and_then(|x| x.as_str()) {
                    Some("content") => {
                        detail.push_str(
                            c.pointer("/content/text")
                                .and_then(|x| x.as_str())
                                .unwrap_or(""),
                        );
                        detail.push('\n');
                    }
                    Some("diff") => detail.push_str(&format!(
                        "修改 {}\n",
                        crate::py::pystr(c.get("path").unwrap_or(&Value::Null))
                    )),
                    _ => {}
                }
            }
            let ri = tc.get("rawInput");
            if let Some(cmd) = ri
                .and_then(|r| r.as_object())
                .and_then(|o| o.get("command"))
                .filter(|c| crate::py::truthy(Some(c)))
            {
                let cmd = match cmd {
                    Value::Array(a) => a.iter().map(crate::py::pystr).collect::<Vec<_>>().join(" "),
                    x => crate::py::pystr(x),
                };
                let cwd = ri
                    .unwrap()
                    .get("cwd")
                    .filter(|c| crate::py::truthy(Some(c)))
                    .map(|c| format!("\n(cwd: {})", crate::py::pystr(c).replace(&self.home, "~")))
                    .unwrap_or_default();
                detail = format!("{detail}$ {cmd}{cwd}").trim().to_string();
                if title == "Run command" || title == "操作" {
                    title = "运行命令".into();
                }
            } else if crate::py::truthy(ri) && detail.is_empty() {
                detail = events::trunc_any(ri.unwrap(), 1500);
            }
            bus.emit(&self.sid, "perm", json!({"rid": rid, "title": title, "detail": detail.trim(), "options": opts, "toolId": tc.get("toolCallId")}));
        } else {
            let spec =
                ask::ask_from_request(&json!({"method": m["method"], "params": m.get("params")}));
            self.asks.add(
                &self.sid,
                spec,
                json!({"type": "req", "jid": m["id"], "req": m}),
            );
        }
    }
    fn on_upd(&self, u: Value) {
        *self.last.lock() = now();
        let bus = &crate::st().bus;
        let k = u
            .get("sessionUpdate")
            .and_then(|x| x.as_str())
            .unwrap_or("");
        match k {
            "agent_message_chunk" | "agent_thought_chunk" => {
                let c = u.get("content").cloned().unwrap_or(json!({}));
                if k == "agent_message_chunk" {
                    let mut r = crate::st().raw.lock();
                    r.push((
                        now(),
                        c["text"].as_str().map(|s| s.chars().count()).unwrap_or(0),
                    ));
                    let n = r.len();
                    if n > 2000 {
                        r.drain(..n - 2000);
                    }
                }
                if c["type"] == "text" {
                    bus.emit(
                        &self.sid,
                        if k == "agent_message_chunk" {
                            "msg"
                        } else {
                            "thought"
                        },
                        json!({"text": c["text"].as_str().unwrap_or("")}),
                    );
                }
            }
            "tool_call" | "tool_call_update" => {
                let tid = u.get("toolCallId").cloned().unwrap_or(Value::Null);
                let key = crate::py::pystr(&tid);
                let mut tools = self.tools.lock();
                let t = tools.entry(key).or_insert_with(|| json!({"id": tid}));
                for f in ["title", "kind", "status"] {
                    if crate::py::truthy(u.get(f)) {
                        t[f] = u[f].clone();
                    }
                }
                if crate::py::truthy(u.get("locations")) {
                    t["locations"] = json!(u["locations"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .map(|x| x.get("path").cloned().unwrap_or(Value::Null))
                        .collect::<Vec<_>>());
                }
                let (mut diffs, mut texts) = (vec![], vec![]);
                for c in u
                    .get("content")
                    .and_then(|x| x.as_array())
                    .into_iter()
                    .flatten()
                {
                    if c["type"] == "diff" {
                        diffs.push(json!({"path": c.get("path"), "old": events::trunc(c["oldText"].as_str().unwrap_or(""), 4000), "new": events::trunc(c["newText"].as_str().unwrap_or(""), 4000)}));
                    } else if c["type"] == "content"
                        && c.pointer("/content/type") == Some(&json!("text"))
                    {
                        texts.push(c["content"]["text"].as_str().unwrap_or("").to_string());
                    }
                }
                if !diffs.is_empty() {
                    t["diffs"] = json!(diffs);
                }
                match u.get("rawOutput") {
                    Some(Value::Object(ro)) => {
                        let o = ["formatted_output", "output", "stdout", "aggregated_output"]
                            .iter()
                            .filter_map(|k| ro.get(*k))
                            .find(|v| crate::py::truthy(Some(v)))
                            .or_else(|| {
                                ["formatted_output", "output", "stdout", "aggregated_output"]
                                    .iter()
                                    .filter_map(|k| ro.get(*k))
                                    .last()
                            });
                        if let Some(o) = o {
                            if !o.is_null() {
                                t["output"] = json!(events::trunc_any(o, 6000));
                            }
                        }
                        if let Some(e) = ro.get("exit_code") {
                            t["exit"] = e.clone();
                        }
                    }
                    Some(Value::Null) | None => {}
                    Some(ro) => {
                        t["output"] = json!(events::trunc_any(ro, 6000));
                    }
                }
                if !texts.is_empty() && t.get("output").is_none() {
                    t["output"] = json!(events::trunc(&texts.join("\n"), 6000));
                }
                if let Some(cmd) = u
                    .get("rawInput")
                    .and_then(|r| r.as_object())
                    .and_then(|o| o.get("command"))
                    .filter(|c| crate::py::truthy(Some(c)))
                {
                    let s = match cmd {
                        Value::Array(a) => {
                            a.iter().map(crate::py::pystr).collect::<Vec<_>>().join(" ")
                        }
                        x => crate::py::pystr(x),
                    };
                    t["cmd"] = json!(events::trunc(&s, 600));
                }
                let out = t.clone();
                drop(tools);
                bus.emit(&self.sid, "tool", out);
            }
            "plan" => {
                bus.emit(&self.sid, "plan", json!({"entries": u["entries"].as_array().into_iter().flatten().map(|e| json!({"content": e.get("content"), "status": e.get("status")})).collect::<Vec<_>>()}));
            }
            "session_info_update" => {
                if let Some(t) = u
                    .get("title")
                    .and_then(|x| x.as_str())
                    .filter(|s| !s.is_empty())
                {
                    crate::set_title(&self.sid, t);
                }
            }
            _ => {}
        }
    }
}
impl AgentLike for AcpAgent {
    fn sid(&self) -> &str {
        &self.sid
    }
    fn alive(&self) -> bool {
        self.rpc.alive()
    }
    fn running(&self) -> bool {
        self.running.load(Ordering::SeqCst)
    }
    fn set_running(&self, v: bool) {
        self.running.store(v, Ordering::SeqCst)
    }
    fn backend(&self) -> &'static str {
        "acp"
    }
    fn last(&self) -> f64 {
        *self.last.lock()
    }
    fn touch(&self) {
        *self.last.lock() = now()
    }
    fn perm_ids(&self) -> Vec<String> {
        self.perms.lock().iter().map(|x| x.0.clone()).collect()
    }
    fn asks(&self) -> &Asks {
        &self.asks
    }
    fn answer_perm(&self, rid: &str, option: Option<String>) -> bool {
        let x = {
            let mut g = self.perms.lock();
            let Some(i) = g.iter().position(|x| x.0 == rid) else {
                return false;
            };
            g.remove(i)
        };
        let (_, jid, opts) = x;
        let out = match option.as_deref() {
            None | Some("") | Some("cancel") => json!({"outcome": "cancelled"}),
            Some(o) => json!({"outcome": "selected", "optionId": o}),
        };
        if self
            .rpc
            .send_checked(&json!({"jsonrpc": "2.0", "id": jid, "result": {"outcome": out}}))
            .is_err()
        {
            return false;
        }
        let name = opts
            .iter()
            .find(|o| o["id"].as_str() == option.as_deref() && option.is_some())
            .and_then(|o| o["name"].as_str())
            .unwrap_or("取消")
            .to_string();
        crate::st().bus.emit(
            &self.sid,
            "perm_done",
            json!({"rid": rid, "option": option, "name": name}),
        );
        true
    }
    fn send_answer(
        &self,
        ctx: &Value,
        spec: &Value,
        answers: &Value,
        text: &str,
        skip: bool,
    ) -> Result<(), String> {
        if ctx["type"] == "async" {
            if !skip {
                if let Some(t) = async_text(spec, answers) {
                    crate::queue_prompt(&self.sid, t, spec["rid"].as_str())?;
                }
            }
            return Ok(());
        }
        let jid = &ctx["jid"];
        let m = ctx
            .pointer("/req/method")
            .and_then(|x| x.as_str())
            .unwrap_or("");
        if skip {
            return self.rpc.send_checked(&json!({"jsonrpc": "2.0", "id": jid, "error": {"code": -32000, "message": "user skipped"}}));
        }
        let res = if m.to_lowercase().contains("elicitation") {
            json!({"action": "accept", "content": elicit_content(answers)})
        } else {
            json!({"text": if text.is_empty() { ask::summarize(answers) } else { text.to_string() }, "answers": answers})
        };
        self.rpc
            .send_checked(&json!({"jsonrpc": "2.0", "id": jid, "result": res}))
    }
    fn cancel(&self) {
        self.cancel_perms();
        self.rpc
            .notify("session/cancel", json!({"sessionId": *self.acp_sid.lock()}));
    }
    fn kill(&self) {
        self.rpc.kill()
    }
    fn rpc(&self) -> Option<Arc<Rpc>> {
        Some(self.rpc.clone())
    }
    fn acp_sid(&self) -> Option<String> {
        self.acp_sid.lock().clone()
    }
}
