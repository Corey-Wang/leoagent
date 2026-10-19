//! Codex desktop IPC (= desk.py): IPC client, Track, Desk follower, FirstTurn via app-server
#![allow(dead_code)]
use crate::ask;
use crate::events::{trunc, trunc_any};
use crate::py::{pystr, truthy};
use crate::runtime::{log as rlog, now, rid12, AgentLike, Asks, Rpc};
use parking_lot::{Mutex, ReentrantMutex};
use serde_json::{json, Value};
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, OnceLock, Weak};
use std::time::Duration;

pub const CODEX: &str = "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex";
fn sock() -> std::path::PathBuf { crate::db::home().join(".codex/ipc/ipc.sock") }
fn log(m: &str) { rlog(&format!("[desk] {m}")) }
fn ver(m: &str) -> i64 { match m { "thread-follower-start-turn" => 2, "thread-follower-interrupt-turn" => 4, _ => 1 } }
fn bus() -> &'static Arc<crate::events::Bus> { &crate::st().bus }

pub fn desktop_running() -> bool {
    std::process::Command::new("pgrep").args(["-f", "ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex .*app-server"])
        .stdout(std::process::Stdio::null()).status().map(|s| s.success()).unwrap_or(false) && sock().exists()
}

// ───────── IPC ─────────
pub struct Ipc { s: Mutex<Option<(u64, UnixStream)>>, gen: Mutex<u64>, wait: Mutex<HashMap<String, mpsc::Sender<Value>>>, pub subs: Mutex<HashMap<String, Weak<Desk>>>, pub cid: Mutex<Option<String>>, conn_lk: Mutex<()> }
pub fn ipc() -> &'static Ipc { static I: OnceLock<Ipc> = OnceLock::new(); I.get_or_init(|| Ipc { s: Mutex::new(None), gen: Mutex::new(0), wait: Mutex::new(HashMap::new()), subs: Mutex::new(HashMap::new()), cid: Mutex::new(None), conn_lk: Mutex::new(()) }) }
impl Ipc {
    pub fn connected(&self) -> bool { self.s.lock().is_some() }
    pub fn ok(&self) -> bool {
        if crate::runtime::no_desk() { return false }
        if self.connected() { return true }
        let _g = self.conn_lk.lock();
        if self.connected() { return true }
        if !desktop_running() { return false }
        match self.connect() { Ok(()) => true, Err(e) => { log(&format!("connect fail {e}")); *self.s.lock() = None; false } }
    }
    fn connect(&self) -> Result<(), String> {
        let s = UnixStream::connect(sock()).map_err(|e| e.to_string())?;
        let rd = s.try_clone().map_err(|e| e.to_string())?;
        let g = { let mut g = self.gen.lock(); *g += 1; *g };
        *self.s.lock() = Some((g, s));
        std::thread::spawn(move || ipc().read(rd, g));
        let r = self.request("initialize", json!({"clientType": "leoagent-server"}), Some(1), 5);
        if r["resultType"] == "error" && r["error"] == "timeout" { return Err("initialize timeout".into()) }
        *self.cid.lock() = r.pointer("/result/clientId").and_then(|x| x.as_str()).map(|s| s.to_string());
        log(&format!("ipc connected {:?}", self.cid.lock()));
        let tids: Vec<String> = self.subs.lock().keys().cloned().collect();
        for t in tids { self.follow(&t, true); }
        Ok(())
    }
    fn send(&self, o: &Value) {
        let b = serde_json::to_vec(o).unwrap();
        let mut g = self.s.lock();
        if let Some((_, s)) = g.as_mut() { let mut f = (b.len() as u32).to_le_bytes().to_vec(); f.extend(b); if s.write_all(&f).is_err() { log("send failed"); } }
    }
    fn read(&self, mut s: UnixStream, g: u64) {
        let mut buf: Vec<u8> = vec![]; let mut chunk = vec![0u8; 1 << 20];
        loop {
            match s.read(&mut chunk) { Ok(0) | Err(_) => break, Ok(n) => buf.extend_from_slice(&chunk[..n]) }
            while buf.len() >= 4 {
                let n = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
                if buf.len() < 4 + n { break }
                let m: Option<Value> = serde_json::from_slice(&buf[4..4 + n]).ok(); buf.drain(..4 + n);
                if let Some(m) = m { if std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| self.on(m))).is_err() { log("on err (panic)"); } }
            }
        }
        log("ipc read end");
        { let mut cur = self.s.lock(); if cur.as_ref().map(|x| x.0) == Some(g) { *cur = None; } }
        for (_, tx) in self.wait.lock().drain() { let _ = tx.send(json!({"resultType": "error", "error": "ipc-closed"})); }
        let subs: Vec<Arc<Desk>> = self.subs.lock().values().filter_map(|w| w.upgrade()).collect();
        for d in subs { d.on_lost(); }
    }
    fn on(&self, m: Value) {
        match m["type"].as_str().unwrap_or("") {
            "response" => { if let Some(tx) = m["requestId"].as_str().and_then(|r| self.wait.lock().remove(r)) { let _ = tx.send(m); } }
            "client-discovery-request" => self.send(&json!({"type": "client-discovery-response", "requestId": m["requestId"], "response": {"canHandle": false}})),
            "request" => self.send(&json!({"type": "response", "requestId": m.get("requestId"), "resultType": "error", "error": "not-supported"})),
            "broadcast" if m["method"] == "thread-stream-state-changed" => {
                let p = &m["params"]; let cid = p["conversationId"].as_str().unwrap_or("");
                let d = self.subs.lock().get(cid).and_then(|w| w.upgrade());
                if let Some(d) = d { d.on_change(p.get("change").cloned().unwrap_or(json!({})), m.get("sourceClientId").cloned()); }
            }
            _ => {}
        }
    }
    pub fn request(&self, method: &str, params: Value, v: Option<i64>, timeout: u64) -> Value {
        let rid = uuid::Uuid::new_v4().simple().to_string(); let (tx, rx) = mpsc::channel();
        self.wait.lock().insert(rid.clone(), tx);
        self.send(&json!({"type": "request", "requestId": rid, "method": method, "params": params, "version": v.unwrap_or(ver(method))}));
        match rx.recv_timeout(Duration::from_secs(timeout)) { Ok(r) => r, Err(_) => { self.wait.lock().remove(&rid); json!({"resultType": "error", "error": "timeout"}) } }
    }
    pub fn follow(&self, tid: &str, on: bool) {
        self.send(&json!({"type": "broadcast", "method": "thread-stream-following-changed", "params": {"conversationId": tid, "hostId": "local", "following": on}, "version": 1}))
    }
    pub fn owned(&self, tid: &str) -> bool {
        if !self.ok() { return false }
        self.request("thread-follower-read-model-settings", json!({"conversationId": tid}), None, 8)["resultType"] == "success"
    }
}
pub fn ensure_owner(tid: &str, wait: u64) -> bool {
    if ipc().owned(tid) { return true }
    if !ipc().ok() { return false }
    let _ = std::process::Command::new("open").args(["-g", &format!("codex://threads/{tid}")]).status();
    let end = now() + wait as f64;
    while now() < end { std::thread::sleep(Duration::from_secs(1)); if ipc().owned(tid) { return true } }
    false
}

// ───────── Track ─────────
fn txt(c: Option<&Value>) -> String { c.and_then(|x| x.as_array()).map(|a| a.iter().filter(|x| x.is_object()).map(|x| x["text"].as_str().unwrap_or("")).collect()).unwrap_or_default() }
fn st_map(s: &Value) -> Value { match s.as_str() { Some("inProgress") => json!("in_progress"), Some("completed") => json!("completed"), Some("failed") | Some("declined") => json!("failed"), _ => if truthy(Some(s)) { s.clone() } else { json!("in_progress") } } }
fn basename(p: &str) -> &str { p.rsplit('/').next().unwrap_or(p) }
fn plen(s: &str) -> usize { s.chars().count() }
fn sorted(v: &Value) -> Value { match v { Value::Object(m) => { let mut k: Vec<_> = m.iter().collect(); k.sort_by(|a, b| a.0.cmp(b.0)); Value::Object(k.into_iter().map(|(a, b)| (a.clone(), sorted(b))).collect()) } Value::Array(a) => Value::Array(a.iter().map(sorted).collect()), x => x.clone() } }
pub enum Sig { N(usize), S(String) }
pub struct Track { sid: String, seen: HashMap<String, Value>, pub sent: HashSet<String>, pub no_user: bool, asked: HashSet<String>, replied: HashSet<String> }
pub struct AskOut { pub elsewhere: Vec<(HashSet<String>, Value)>, pub add: Vec<(Value, Value)> }
impl Track {
    pub fn new(sid: &str) -> Self { Self { sid: sid.into(), seen: HashMap::new(), sent: HashSet::new(), no_user: false, asked: HashSet::new(), replied: HashSet::new() } }
    fn key(it: &Value) -> String { pystr(it.get("id").unwrap_or(&Value::Null)) }
    pub fn baseline(&mut self, items: &[Value], pending: bool, o: &mut AskOut) {
        for it in items { let s = self.sig(it); self.seen.insert(Self::key(it), s); self.replies(it, o); }
        if pending { for it in items { if it["type"] == "agentMessage" && truthy(it.get("questions")) && it["delivery"] == "async" { self.ask(it, o) } } }
    }
    fn replies(&mut self, it: &Value, o: &mut AskOut) -> bool {
        let ty = it["type"].as_str().unwrap_or("");
        if ty != "userMessage" && ty != "steeringUserMessage" { return false }
        let t = txt(it.get("content").filter(|x| truthy(Some(x))).or(it.get("input")));
        let Some(r) = ask::parse_reply(&t) else { return false };
        let ids: HashSet<String> = r.as_array().into_iter().flatten().filter(|x| x.is_object()).map(|x| pystr(x.get("questionItemId").unwrap_or(&Value::Null))).collect();
        self.replied.extend(ids.iter().cloned()); o.elsewhere.push((ids, r)); true
    }
    fn ask(&mut self, it: &Value, o: &mut AskOut) {
        let k = Self::key(it); if self.asked.contains(&k) { return } self.asked.insert(k);
        let spec = ask::ask_from_async(it);
        if spec["questions"].as_array().map(|q| q.iter().all(|q| self.replied.contains(q["id"].as_str().unwrap_or("")))).unwrap_or(true) { return }
        o.add.push((spec, json!({"type": "async", "item": it})));
    }
    fn sig(&self, it: &Value) -> Value {
        match it["type"].as_str().unwrap_or("") {
            "agentMessage" | "plan" => json!(plen(it["text"].as_str().unwrap_or(""))),
            "reasoning" => json!(plen(&summary(it))),
            "userMessage" => json!(1),
            _ => json!(serde_json::to_string(&sorted(&self.tool(it))).unwrap()),
        }
    }
    pub fn tool(&self, it: &Value) -> Value {
        let ty = it["type"].as_str().unwrap_or("");
        let mut t = json!({"id": it.get("id"), "status": st_map(it.get("status").unwrap_or(&Value::Null))});
        match ty {
            "commandExecution" => {
                let cmd = it["command"].as_str().unwrap_or("").to_string();
                let short = it["commandActions"].as_array().and_then(|a| a.first()).and_then(|a| a.get("command")).filter(|c| truthy(Some(c))).map(pystr).unwrap_or(cmd);
                t["title"] = json!(format!("运行 {short}").chars().take(80).collect::<String>()); t["kind"] = json!("execute"); t["cmd"] = json!(trunc(&short, 600));
                if let Some(o) = it.get("aggregatedOutput").filter(|x| !x.is_null()) { t["output"] = json!(trunc_any(o, 6000)); }
                if let Some(e) = it.get("exitCode").filter(|x| !x.is_null()) { t["exit"] = e.clone(); }
            }
            "fileChange" => {
                let ch: Vec<Value> = it["changes"].as_array().cloned().unwrap_or_default();
                t["title"] = json!(format!("编辑 {}", ch.iter().take(3).map(|c| basename(c["path"].as_str().unwrap_or(""))).collect::<Vec<_>>().join(", ")));
                t["kind"] = json!("edit"); t["locations"] = json!(ch.iter().map(|c| c.get("path").cloned().unwrap_or(Value::Null)).collect::<Vec<_>>());
                t["diffs"] = json!(ch.iter().take(10).map(|c| json!({"path": c.get("path"), "old": "", "new": trunc(c["diff"].as_str().unwrap_or(""), 4000)})).collect::<Vec<_>>());
            }
            "mcpToolCall" => {
                t["title"] = json!(format!("{}.{}", pystr(&it["server"]), pystr(&it["tool"]))); t["kind"] = json!("other");
                if let Some(r) = it.get("result").filter(|x| !x.is_null()) { t["output"] = json!(trunc_any(r, 6000)); }
                if truthy(it.get("error")) { t["output"] = json!(trunc_any(&it["error"], 6000)); }
            }
            "webSearch" => { t["title"] = json!(format!("搜索 {}", if truthy(it.get("query")) { pystr(&it["query"]) } else { String::new() })); t["kind"] = json!("fetch"); }
            _ => { t["title"] = json!(if ty.is_empty() { "工具" } else { ty }); t["kind"] = json!("other"); }
        }
        t
    }
    pub fn item(&mut self, it: &Value, o: &mut AskOut) {
        let k = Self::key(it); let ty = it["type"].as_str().unwrap_or(""); let old = self.seen.get(&k).cloned();
        let n = old.as_ref().and_then(|x| x.as_u64()).unwrap_or(0) as usize; let sid = self.sid.clone();
        match ty {
            "userMessage" | "steeringUserMessage" => {
                if old.is_none() {
                    self.seen.insert(k, json!(1));
                    if self.replies(it, o) { return }
                    let t = txt(it.get("content").filter(|x| truthy(Some(x))).or(it.get("input")));
                    let cid = pystr(it.get("clientId").unwrap_or(&Value::Null));
                    if !self.no_user && !self.sent.contains(&cid) && !t.trim().is_empty() {
                        let imgs = it["content"].as_array().map(|a| a.iter().filter(|x| matches!(x["type"].as_str(), Some("image") | Some("localImage"))).count()).unwrap_or(0);
                        bus().emit(&sid, "user", json!({"text": t, "images": imgs, "from": "desktop"}));
                    }
                }
            }
            "agentMessage" => {
                let s = it["text"].as_str().unwrap_or("");
                if it["delivery"] == "async" && truthy(it.get("questions")) { self.seen.insert(k, json!(plen(s))); self.ask(it, o); return }
                if plen(s) > n { bus().emit(&sid, "msg", json!({"text": s.chars().skip(n).collect::<String>()})); self.seen.insert(k, json!(plen(s))); }
            }
            "plan" => {
                let s = it["text"].as_str().unwrap_or("");
                if plen(s) > n { bus().emit(&sid, "msg", json!({"text": format!("{}{}", if n == 0 { "\n\n📋 **计划**\n\n" } else { "" }, s.chars().skip(n).collect::<String>())})); self.seen.insert(k, json!(plen(s))); }
            }
            "reasoning" => {
                let s = summary(it);
                if plen(&s) > n { bus().emit(&sid, "thought", json!({"text": s.chars().skip(n).collect::<String>()})); self.seen.insert(k, json!(plen(&s))); }
            }
            "contextCompaction" | "hookPrompt" | "imageView" | "steered" => {}
            _ => { let sg = self.sig(it); if Some(&sg) != old.as_ref() { self.seen.insert(k, sg); bus().emit(&sid, "tool", self.tool(it)); } }
        }
    }
}
fn summary(it: &Value) -> String { it["summary"].as_array().map(|a| a.iter().map(|x| x.as_str().unwrap_or("").to_string()).collect::<Vec<_>>().join("\n\n")).unwrap_or_default() }
fn dispatch(sid: &str, asks: &Asks, o: AskOut) {
    for (ids, r) in o.elsewhere { asks.answered_elsewhere(sid, &ids, &r); }
    for (spec, ctx) in o.add { asks.add(sid, spec, ctx); }
}
fn dec_of(decs: &[Value], option: Option<&str>) -> Value {
    match option { None | Some("") | Some("cancel") => json!(if decs.iter().any(|d| d == "cancel") { "cancel" } else { "decline" }),
        Some(o) => o.parse::<i64>().ok().and_then(|i| if i < 0 { decs.get((decs.len() as i64 + i) as usize) } else { decs.get(i as usize) }).cloned().unwrap_or(json!("decline")) }
}
fn dec_name(d: &Value) -> String {
    let k = match d { Value::String(s) => s.clone(), Value::Object(m) => m.keys().next().cloned().unwrap_or_default(), x => pystr(x) };
    ask::DEC_NAME.iter().find(|x| x.0 == k).map(|x| x.1.to_string()).unwrap_or_else(|| match d { Value::String(s) => s.clone(), x => crate::py::dumps(x) })
}
fn rq_of(k: &str) -> Value { if !k.is_empty() && k.chars().all(|c| c.is_ascii_digit()) { json!(k.parse::<i64>().unwrap()) } else { json!(k) } }
fn imgs_input(images: &[Value]) -> Vec<Value> { images.iter().filter(|i| truthy(i.get("data"))).map(|i| json!({"type": "image", "url": format!("data:{};base64,{}", i["mime"].as_str().unwrap_or("image/jpeg"), pystr(&i["data"]))})).collect() }

// ───────── Desk (follower of a desktop-owned thread) ─────────
struct DS { tr: Track, state: Option<Value>, rev: Value, done: HashSet<String>, perms: Vec<(String, (String, Value, Vec<Value>))>, owner: Option<Value> }
pub struct Desk { pub sid: String, pub tid: String, d: ReentrantMutex<RefCell<DS>>, running: AtomicBool, last: Mutex<f64>, dead: AtomicBool, asks: Asks, base: (Mutex<bool>, parking_lot::Condvar) }
impl Desk {
    pub fn new(sid: &str, tid: &str) -> Arc<Self> {
        let d = Arc::new(Self { sid: sid.into(), tid: tid.into(), d: ReentrantMutex::new(RefCell::new(DS { tr: Track::new(sid), state: None, rev: Value::Null, done: HashSet::new(), perms: vec![], owner: None })),
            running: AtomicBool::new(false), last: Mutex::new(now()), dead: AtomicBool::new(false), asks: Asks::default(), base: (Mutex::new(false), parking_lot::Condvar::new()) });
        ipc().subs.lock().insert(tid.into(), Arc::downgrade(&d)); ipc().follow(tid, true);
        let mut g = d.base.0.lock(); if !*g { d.base.1.wait_for(&mut g, Duration::from_secs(6)); } drop(g);
        d
    }
    pub fn has_state(&self) -> bool { self.d.lock().borrow().state.is_some() }
    fn on_lost(&self) {
        if self.running() { bus().emit(&self.sid, "error", json!({"msg": "与桌面端的连接断开"})); self.idle("error"); }
        self.dead.store(true, Ordering::SeqCst);
    }
    fn on_change(&self, ch: Value, src: Option<Value>) {
        let g = self.d.lock();
        { let mut s = g.borrow_mut(); s.owner = src;
            match ch["type"].as_str() {
                Some("snapshot") => {
                    let first = s.state.is_none();
                    s.state = Some(ch.get("conversationState").cloned().filter(|x| !x.is_null()).unwrap_or(json!({}))); s.rev = ch.get("revision").cloned().unwrap_or(Value::Null);
                    if first { drop(s); self.baseline(&g); *self.base.0.lock() = true; self.base.1.notify_all(); return }
                }
                Some("patches") => {
                    if s.state.is_none() || ch.get("baseRevision").cloned().unwrap_or(Value::Null) != s.rev { drop(s); ipc().follow(&self.tid, true); return }
                    let st = s.state.as_mut().unwrap();
                    for p in ch["patches"].as_array().into_iter().flatten() { if apply(st, p).is_none() { log("patch apply failed; resync"); } }
                    s.rev = ch.get("revision").cloned().unwrap_or(Value::Null);
                }
                _ => {}
            }
        }
        self.diff(&g);
    }
    fn ents(s: &DS) -> Vec<(String, Value)> {
        s.state.as_ref().and_then(|st| st.pointer("/turnHistory/history/entitiesByKey")).and_then(|x| x.as_object()).map(|m| m.iter().map(|(k, v)| (k.clone(), v.clone())).collect()).unwrap_or_default()
    }
    fn active(s: &DS) -> bool { s.state.as_ref().and_then(|x| x.pointer("/threadRuntimeStatus/type")) == Some(&json!("active")) }
    fn baseline(&self, g: &RefCell<DS>) {
        let mut o = AskOut { elsewhere: vec![], add: vec![] };
        { let mut s = g.borrow_mut(); let ents = Self::ents(&s);
          for (k, e) in ents { let items = e["items"].as_array().cloned().unwrap_or_default(); s.tr.baseline(&items, e["status"] == "inProgress", &mut o);
            if matches!(e["status"].as_str(), Some("completed") | Some("interrupted") | Some("failed")) { let t = e.get("turnId").filter(|x| truthy(Some(x))).map(pystr).unwrap_or(k); s.done.insert(t); } } }
        dispatch(&self.sid, &self.asks, o);
        self.requests(g);
        self.running.store(Self::active(&g.borrow()), Ordering::SeqCst);
    }
    fn diff(&self, g: &RefCell<DS>) {
        *self.last.lock() = now();
        if Self::active(&g.borrow()) && !self.running() { self.running.store(true, Ordering::SeqCst); bus().emit(&self.sid, "turn", json!({"state": "running"})); }
        let ents = Self::ents(&g.borrow());
        for (k, e) in ents {
            let mut o = AskOut { elsewhere: vec![], add: vec![] };
            { let mut s = g.borrow_mut(); for it in e["items"].as_array().into_iter().flatten() { s.tr.item(it, &mut o); } }
            dispatch(&self.sid, &self.asks, o);
            let tid = e.get("turnId").filter(|x| truthy(Some(x))).map(pystr).unwrap_or(k);
            let fin = matches!(e["status"].as_str(), Some("completed") | Some("interrupted") | Some("failed"));
            if fin && truthy(e.get("turnId")) && !g.borrow().done.contains(&tid) {
                g.borrow_mut().done.insert(tid);
                if truthy(e.get("error")) { let m = e["error"].get("message").filter(|x| truthy(Some(x))).cloned().unwrap_or(e["error"].clone()); bus().emit(&self.sid, "error", json!({"msg": pystr(&m).chars().take(500).collect::<String>()})); }
                self.idle(match e["status"].as_str() { Some("completed") => "end_turn", Some("interrupted") => "cancelled", _ => "error" });
            }
        }
        self.requests(g);
    }
    fn requests(&self, g: &RefCell<DS>) {
        let reqs: Vec<Value> = g.borrow().state.as_ref().and_then(|s| s["requests"].as_array().cloned()).unwrap_or_default();
        let reqs: Vec<(String, Value)> = reqs.into_iter().filter(|r| r.get("id").is_some()).map(|r| (pystr(&r["id"]), r)).collect();
        let is_appr = |r: &Value| pystr(r.get("method").unwrap_or(&Value::Null)).contains("requestApproval");
        let kn: HashSet<String> = self.asks.ctxs().iter().filter(|c| c["type"] == "req").map(|c| pystr(&c["rq"])).collect();
        for (k, r) in &reqs { if is_appr(r) || kn.contains(k) { continue } self.asks.add(&self.sid, ask::ask_from_request(r), json!({"type": "req", "rq": k, "req": r})); }
        let all: HashSet<String> = reqs.iter().map(|x| x.0.clone()).collect();
        self.asks.gone(&self.sid, |c| c["type"] == "req" && !all.contains(&pystr(&c["rq"])));
        let cur: Vec<&(String, Value)> = reqs.iter().filter(|x| is_appr(&x.1)).collect();
        let curk: HashSet<&String> = cur.iter().map(|x| &x.0).collect();
        let known: HashSet<String> = g.borrow().perms.iter().map(|x| x.1 .0.clone()).collect();
        for (k, r) in cur { if known.contains(k) { continue }
            let rid = rid12(); let decs = r.pointer("/params/availableDecisions").and_then(|x| x.as_array()).filter(|a| !a.is_empty()).cloned().unwrap_or(vec![json!("accept"), json!("decline")]);
            g.borrow_mut().perms.push((rid.clone(), (k.clone(), r.clone(), decs.clone()))); let (title, detail) = ask::perm_view(r);
            bus().emit(&self.sid, "perm", json!({"rid": rid, "title": title, "detail": detail, "options": ask::opts(&decs), "toolId": r.pointer("/params/itemId")}));
            bus().note(&self.sid, "perm", "Codex 需要审批", &title);
        }
        let gone: Vec<String> = g.borrow().perms.iter().filter(|x| !curk.contains(&x.1 .0)).map(|x| x.0.clone()).collect();
        for rid in gone { g.borrow_mut().perms.retain(|x| x.0 != rid); bus().emit(&self.sid, "perm_done", json!({"rid": rid, "option": null, "name": "已在桌面端处理"})); }
        let s = g.borrow();
        let idle_now = !Self::active(&s) && self.running() && s.perms.is_empty() && Self::ents(&s).iter().all(|(_, e)| e["status"] != "inProgress");
        drop(s); if idle_now { self.idle("end_turn") }
    }
    fn idle(&self, stop: &str) {
        if !self.running.swap(false, Ordering::SeqCst) { return }
        bus().flush(Some(&self.sid)); bus().emit(&self.sid, "turn", json!({"state": "idle", "stopReason": stop})); crate::done_note(&self.sid, stop);
    }
    pub fn start(&self, text: &str, images: &[Value], cfg: &Value) -> Result<Value, String> {
        let cm = uuid::Uuid::new_v4().simple().to_string(); self.d.lock().borrow_mut().tr.sent.insert(cm.clone());
        let mut inp = imgs_input(images); if !text.is_empty() { inp.push(json!({"type": "text", "text": text, "text_elements": []})); }
        let mut req = json!({"threadId": self.tid, "input": inp, "clientUserMessageId": cm});
        if truthy(cfg.get("model")) { req["model"] = cfg["model"].clone(); } if truthy(cfg.get("effort")) { req["effort"] = cfg["effort"].clone(); }
        self.running.store(true, Ordering::SeqCst); self.touch();
        let r = ipc().request("thread-follower-start-turn", json!({"conversationId": self.tid, "turnStart": {"request": req, "context": {"clientUserMessageId": cm}}}), None, 60);
        if r["resultType"] != "success" { self.running.store(false, Ordering::SeqCst); return Err(format!("桌面端拒绝: {}", pystr(r.get("error").unwrap_or(&Value::Null)))) }
        Ok(r)
    }
}
impl AgentLike for Desk {
    fn sid(&self) -> &str { &self.sid }
    fn alive(&self) -> bool { !self.dead.load(Ordering::SeqCst) && ipc().connected() }
    fn running(&self) -> bool { self.running.load(Ordering::SeqCst) }
    fn set_running(&self, v: bool) { self.running.store(v, Ordering::SeqCst) }
    fn backend(&self) -> &'static str { "desktop" }
    fn last(&self) -> f64 { *self.last.lock() }
    fn touch(&self) { *self.last.lock() = now() }
    fn perm_ids(&self) -> Vec<String> { self.d.lock().borrow().perms.iter().map(|x| x.0.clone()).collect() }
    fn asks(&self) -> &Asks { &self.asks }
    fn is_desk(&self) -> bool { true }
    fn answer_perm(&self, rid: &str, option: Option<String>) -> bool {
        let v = { let g = self.d.lock(); let mut s = g.borrow_mut(); let Some(i) = s.perms.iter().position(|x| x.0 == rid) else { return false }; s.perms.remove(i).1 };
        let (k, r, decs) = v; let m = pystr(r.get("method").unwrap_or(&Value::Null));
        let dec = dec_of(&decs, option.as_deref());
        let meth = if m.contains("commandExecution") { Some("thread-follower-command-approval-decision") } else if m.contains("fileChange") { Some("thread-follower-file-approval-decision") } else { None };
        if let Some(meth) = meth { ipc().request(meth, json!({"conversationId": self.tid, "requestId": rq_of(&k), "decision": dec}), None, 15); }
        bus().emit(&self.sid, "perm_done", json!({"rid": rid, "option": option, "name": dec_name(&dec)})); true
    }
    fn send_answer(&self, ctx: &Value, spec: &Value, answers: &Value, _text: &str, skip: bool) -> Result<(), String> {
        if ctx["type"] == "async" {
            if skip { return Ok(()) }
            let Some(t) = crate::runtime::async_text(spec, answers) else { return Ok(()) };
            let cm = uuid::Uuid::new_v4().simple().to_string(); self.d.lock().borrow_mut().tr.sent.insert(cm.clone());
            let inp = json!([{"type": "text", "text": t, "text_elements": []}]);
            if self.running() {
                let cwd = self.d.lock().borrow().state.as_ref().and_then(|s| s.get("cwd")).filter(|x| truthy(Some(x))).map(pystr).unwrap_or_else(|| crate::db::home().to_string_lossy().into_owned());
                let rm = json!({"text": t, "prompt": t, "images": [], "attachments": [], "context": {"cwd": cwd, "workspaceRoots": [cwd], "turnTrigger": "composer", "commentAttachments": [], "attachments": []}});
                let r = ipc().request("thread-follower-steer-turn", json!({"conversationId": self.tid, "input": inp, "clientUserMessageId": cm, "restoreMessage": rm, "attachments": [], "serviceTier": null}), Some(1), 30);
                if r["resultType"] == "success" { return Ok(()) }
                log(&format!("steer failed {}", r["error"]));
            }
            self.start(&t, &[], &json!({}))?; bus().emit(&self.sid, "turn", json!({"state": "running"})); return Ok(())
        }
        let r = &ctx["req"]; let m = pystr(r.get("method").unwrap_or(&Value::Null)); let rq = rq_of(&pystr(&ctx["rq"]));
        let (resp, meth) = if m.contains("requestUserInput") {
            (if skip { json!({"answers": {}}) } else { json!({"answers": answers.as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), json!({"answers": v}))).collect::<serde_json::Map<_, _>>()).unwrap_or_default()}) }, "thread-follower-submit-user-input")
        } else if m.to_lowercase().contains("elicitation") {
            let props = r.pointer("/params/requestedSchema/properties").cloned().unwrap_or(json!({}));
            let mut content = serde_json::Map::new();
            for (k, v) in answers.as_object().into_iter().flatten() {
                let ty = props.get(k).and_then(|p| p.get("type")).and_then(|x| x.as_str()).unwrap_or("");
                let a = v.as_array().cloned().unwrap_or_default();
                let mut val = if ty == "array" { v.clone() } else { a.first().cloned().unwrap_or(json!("")) };
                if ty == "boolean" { val = json!(matches!(pystr(&val).to_lowercase().as_str(), "true" | "是" | "yes" | "1")); }
                else if ty == "number" { if let Ok(f) = pystr(&val).trim().parse::<f64>() { val = json!(f); } }
                else if ty == "integer" { if let Ok(i) = pystr(&val).trim().parse::<i64>() { val = json!(i); } }
                content.insert(k.clone(), val);
            }
            (json!({"action": if skip { "decline" } else { "accept" }, "content": if skip { Value::Null } else { Value::Object(content) }}), "thread-follower-submit-mcp-server-elicitation-response")
        } else { return Err("这种请求暂不支持从手机回复，请在桌面端处理".into()) };
        let x = ipc().request(meth, json!({"conversationId": self.tid, "requestId": rq, "response": resp}), Some(1), 20);
        if x["resultType"] != "success" { return Err(format!("桌面端拒绝：{}", pystr(x.get("error").unwrap_or(&Value::Null)))) }
        Ok(())
    }
    fn cancel(&self) { self.cancel_perms(); ipc().request("thread-follower-interrupt-turn", json!({"conversationId": self.tid, "mode": "user-stop"}), None, 15); }
    fn kill(&self) {
        self.dead.store(true, Ordering::SeqCst);
        { let mut s = ipc().subs.lock(); if s.get(&self.tid).map(|w| w.as_ptr() == self as *const _).unwrap_or(false) { s.remove(&self.tid); } }
        ipc().follow(&self.tid, false);
    }
}

fn idx(cur: &mut Value, k: &Value) -> Option<*mut Value> {
    match cur { Value::Object(m) => m.get_mut(&pystr(k)).map(|x| x as *mut _), Value::Array(a) => { let i = pystr(k).parse::<usize>().ok()?; a.get_mut(i).map(|x| x as *mut _) } _ => None }
}
/// JSON patch subset (= desk._apply). None = failed.
pub fn apply(doc: &mut Value, p: &Value) -> Option<()> {
    let path = p["path"].as_array().cloned().unwrap_or_default(); let op = p["op"].as_str().unwrap_or(""); let val = p.get("value").cloned().unwrap_or(Value::Null);
    if path.is_empty() { if op == "add" || op == "replace" { *doc = if val.is_null() { json!({}) } else { val }; } return Some(()) }
    let mut cur: *mut Value = doc;
    for k in &path[..path.len() - 1] { cur = idx(unsafe { &mut *cur }, k)?; }
    let cur = unsafe { &mut *cur }; let k = &path[path.len() - 1];
    match cur {
        Value::Array(a) => {
            let ks = pystr(k);
            match op {
                "add" => { if ks == "-" { a.push(val) } else { let i: usize = ks.parse().ok()?; if i >= a.len() { a.push(val) } else { a.insert(i, val) } } }
                "replace" => { let i: usize = ks.parse().ok()?; *a.get_mut(i)? = val; }
                "remove" => { let i: usize = ks.parse().ok()?; if i >= a.len() { return None } a.remove(i); }
                _ => {}
            }
        }
        Value::Object(m) => { match op { "add" | "replace" => { m.insert(pystr(k), val); } "remove" => { m.shift_remove(&pystr(k)); } _ => {} } }
        _ => return None,
    }
    Some(())
}

// ───────── FirstTurn (new thread via desktop's bundled app-server) ─────────
pub struct FirstTurn { pub sid: String, cwd: String, cfg: Value, rpc: Arc<Rpc>, tr: Mutex<Track>, items: Mutex<HashMap<String, Value>>, asks: Asks,
    perms: Mutex<Vec<(String, (Value, Value, Vec<Value>))>>, running: AtomicBool, last: Mutex<f64>, pub tid: Mutex<Option<String>>, turn: Mutex<Option<String>>,
    dead: AtomicBool, stop: Mutex<String>, fin: (Mutex<bool>, parking_lot::Condvar) }
impl FirstTurn {
    pub fn new(sid: &str, cwd: &str, cfg: Value) -> Result<Arc<Self>, String> {
        let rpc = Rpc::spawn(&[CODEX.to_string(), "app-server".into()], cwd, None, None, 100, "app-server 退出".into())?;
        let mut tr = Track::new(sid); tr.no_user = true;
        let f = Arc::new(Self { sid: sid.into(), cwd: cwd.into(), cfg, rpc: rpc.clone(), tr: Mutex::new(tr), items: Mutex::new(HashMap::new()), asks: Asks::default(), perms: Mutex::new(vec![]),
            running: AtomicBool::new(false), last: Mutex::new(now()), tid: Mutex::new(None), turn: Mutex::new(None), dead: AtomicBool::new(false), stop: Mutex::new("error".into()), fin: (Mutex::new(false), parking_lot::Condvar::new()) });
        let w = Arc::downgrade(&f); rpc.set_handler(Box::new(move |m| if let Some(f) = w.upgrade() { if m.get("id").is_some() { f.req(&m) } else { f.note(m["method"].as_str().unwrap_or(""), m.get("params").cloned().filter(|x| !x.is_null()).unwrap_or(json!({}))) } }));
        let w2 = Arc::downgrade(&f); rpc.set_on_exit(Box::new(move || if let Some(f) = w2.upgrade() { f.dead.store(true, Ordering::SeqCst); f.finish(); }));
        rpc.call("initialize", json!({"clientInfo": {"name": "Codex Desktop", "title": "LeoAgent phone", "version": "1"}, "capabilities": {"experimentalApi": true}}), 60)?;
        rpc.send(&json!({"jsonrpc": "2.0", "method": "initialized"}));
        Ok(f)
    }
    fn finish(&self) { *self.fin.0.lock() = true; self.fin.1.notify_all(); }
    fn track(&self, it: &Value) { let mut o = AskOut { elsewhere: vec![], add: vec![] }; self.tr.lock().item(it, &mut o); dispatch(&self.sid, &self.asks, o); }
    fn note(&self, me: &str, p: Value) {
        self.touch();
        match me {
            "item/started" | "item/completed" | "item/updated" => {
                let mut it = p.get("item").cloned().unwrap_or(json!({})); let k = pystr(it.get("id").unwrap_or(&Value::Null));
                { let mut items = self.items.lock(); let old = items.get(&k).cloned().unwrap_or(json!({}));
                  if it["type"] == "agentMessage" && !truthy(it.get("text")) && truthy(old.get("text")) { it["text"] = old["text"].clone(); }
                  items.insert(k, it.clone()); }
                self.track(&it);
            }
            "item/agentMessage/delta" => {
                let k = pystr(&p["itemId"]);
                let it = { let mut items = self.items.lock(); let it = items.entry(k).or_insert_with(|| json!({"type": "agentMessage", "id": p["itemId"], "text": ""}));
                    it["text"] = json!(format!("{}{}", it["text"].as_str().unwrap_or(""), p["delta"].as_str().unwrap_or(""))); it.clone() };
                self.track(&it);
            }
            "item/reasoning/summaryTextDelta" => {
                let k = pystr(&p["itemId"]);
                let it = { let mut items = self.items.lock(); let it = items.entry(k).or_insert_with(|| json!({"type": "reasoning", "id": p["itemId"], "summary": []}));
                    if !it["summary"].is_array() { it["summary"] = json!([]); }
                    let i = p["summaryIndex"].as_u64().unwrap_or(0) as usize; let sm = it["summary"].as_array_mut().unwrap();
                    while sm.len() <= i { sm.push(json!("")); } sm[i] = json!(format!("{}{}", sm[i].as_str().unwrap_or(""), p["delta"].as_str().unwrap_or(""))); it.clone() };
                self.track(&it);
            }
            "item/commandExecution/outputDelta" => {
                if let Some(it) = self.items.lock().get_mut(&pystr(&p["itemId"])) { it["aggregatedOutput"] = json!(format!("{}{}", it["aggregatedOutput"].as_str().unwrap_or(""), p["delta"].as_str().unwrap_or(""))); }
            }
            "turn/completed" => {
                let t = &p["turn"];
                if truthy(t.get("error")) { bus().emit(&self.sid, "error", json!({"msg": pystr(t["error"].get("message").unwrap_or(&Value::Null)).chars().take(500).collect::<String>()})); }
                *self.stop.lock() = match t["status"].as_str() { Some("completed") => "end_turn", Some("interrupted") => "cancelled", _ => "error" }.into(); self.finish();
            }
            "thread/name/updated" => { if let Some(n) = p["threadName"].as_str().filter(|s| !s.is_empty()) { crate::set_title(&self.sid, n); } }
            _ => {}
        }
    }
    fn req(&self, m: &Value) {
        let me = m["method"].as_str().unwrap_or("");
        if me.contains("requestApproval") {
            let rid = rid12(); let decs = m.pointer("/params/availableDecisions").and_then(|x| x.as_array()).filter(|a| !a.is_empty()).cloned().unwrap_or(vec![json!("accept"), json!("acceptForSession"), json!("decline"), json!("cancel")]);
            self.perms.lock().push((rid.clone(), (m["id"].clone(), m.clone(), decs.clone()))); let (title, detail) = ask::perm_view(m);
            bus().emit(&self.sid, "perm", json!({"rid": rid, "title": title, "detail": detail, "options": ask::opts(&decs), "toolId": m.pointer("/params/itemId")}));
            bus().note(&self.sid, "perm", "Codex 需要审批", &title);
        } else { self.asks.add(&self.sid, ask::ask_from_request(m), json!({"type": "req", "jid": m["id"], "req": m})); }
    }
    pub fn run(&self, text: &str, images: &[Value], mode: &str) -> Result<String, String> {
        let mut tp = json!({"cwd": self.cwd, "approvalPolicy": if mode == "ask" { "on-request" } else { "never" }, "sandbox": "workspace-write"});
        if truthy(self.cfg.get("model")) { tp["model"] = self.cfg["model"].clone(); }
        let r = self.rpc.call("thread/start", tp, 60)?; let tid = pystr(&r["thread"]["id"]); *self.tid.lock() = Some(tid.clone());
        let mut inp = imgs_input(images); if !text.is_empty() { inp.push(json!({"type": "text", "text": text, "text_elements": []})); }
        let mut tu = json!({"threadId": tid, "input": inp}); if truthy(self.cfg.get("effort")) { tu["effort"] = self.cfg["effort"].clone(); }
        self.running.store(true, Ordering::SeqCst); *self.stop.lock() = "end_turn".into();
        let r = self.rpc.call("turn/start", tu, 60)?; *self.turn.lock() = r.pointer("/turn/id").and_then(|x| x.as_str()).map(|s| s.to_string());
        Ok(tid)
    }
    pub fn wait_done(&self) -> String {
        let mut g = self.fin.0.lock(); let end = std::time::Instant::now() + Duration::from_secs(6 * 3600);
        while !*g { if self.fin.1.wait_until(&mut g, end).timed_out() { break } }
        drop(g); self.running.store(false, Ordering::SeqCst); self.stop.lock().clone()
    }
}
impl AgentLike for FirstTurn {
    fn sid(&self) -> &str { &self.sid }
    fn alive(&self) -> bool { !self.dead.load(Ordering::SeqCst) && self.rpc.alive() }
    fn running(&self) -> bool { self.running.load(Ordering::SeqCst) }
    fn set_running(&self, v: bool) { self.running.store(v, Ordering::SeqCst) }
    fn backend(&self) -> &'static str { "desktop" }
    fn last(&self) -> f64 { *self.last.lock() }
    fn touch(&self) { *self.last.lock() = now() }
    fn perm_ids(&self) -> Vec<String> { self.perms.lock().iter().map(|x| x.0.clone()).collect() }
    fn asks(&self) -> &Asks { &self.asks }
    fn is_desk(&self) -> bool { true }
    fn answer_perm(&self, rid: &str, option: Option<String>) -> bool {
        let v = { let mut g = self.perms.lock(); let Some(i) = g.iter().position(|x| x.0 == rid) else { return false }; g.remove(i).1 };
        let (jid, _m, decs) = v; let dec = dec_of(&decs, option.as_deref());
        self.rpc.send(&json!({"jsonrpc": "2.0", "id": jid, "result": {"decision": dec}}));
        bus().emit(&self.sid, "perm_done", json!({"rid": rid, "option": option, "name": dec_name(&dec)})); true
    }
    fn send_answer(&self, ctx: &Value, spec: &Value, answers: &Value, text: &str, skip: bool) -> Result<(), String> {
        if ctx["type"] == "async" {
            let t = if skip { None } else { crate::runtime::async_text(spec, answers) };
            if let Some(t) = t {
                let turn = self.turn.lock().clone(); let tid = self.tid.lock().clone();
                if self.running() && turn.is_some() {
                    match self.rpc.call("turn/steer", json!({"threadId": tid, "input": [{"type": "text", "text": t, "text_elements": []}], "expectedTurnId": turn}), 20) { Ok(_) => return Ok(()), Err(e) => log(&format!("ft steer {e}")) }
                }
                crate::queue_prompt(&self.sid, t);
            }
            return Ok(())
        }
        let m = pystr(ctx.pointer("/req/method").unwrap_or(&Value::Null)); let jid = &ctx["jid"];
        let res = if m.contains("requestUserInput") { json!({"answers": answers.as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), json!({"answers": v}))).collect::<serde_json::Map<_, _>>()).unwrap_or_default()}) }
            else if m.to_lowercase().contains("elicitation") { if skip { json!({"action": "decline", "content": null}) } else { json!({"action": "accept", "content": crate::runtime::elicit_content(answers)}) } }
            else { if skip { self.rpc.send(&json!({"jsonrpc": "2.0", "id": jid, "error": {"code": -32000, "message": "user skipped"}})); return Ok(()) }
                   json!({"text": if text.is_empty() { ask::summarize(answers) } else { text.to_string() }, "answers": answers}) };
        self.rpc.send(&json!({"jsonrpc": "2.0", "id": jid, "result": res})); Ok(())
    }
    fn cancel(&self) {
        self.cancel_perms();
        let (tid, turn) = (self.tid.lock().clone(), self.turn.lock().clone());
        if let (Some(t), Some(u)) = (tid, turn) { if let Err(e) = self.rpc.call("turn/interrupt", json!({"threadId": t, "turnId": u}), 15) { log(&format!("interrupt {e}")) } }
    }
    fn kill(&self) { self.dead.store(true, Ordering::SeqCst); self.rpc.kill(); }
    fn rpc(&self) -> Option<Arc<Rpc>> { Some(self.rpc.clone()) }
}
