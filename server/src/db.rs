//! bridge.db: projects/sessions/events/notes/kv
use parking_lot::{Condvar, Mutex};
use rusqlite::{Connection, OptionalExtension, params, params_from_iter};
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now() -> f64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs_f64() }

pub struct Db {
    pub(crate) conn: Mutex<Connection>,
    pub cond: Condvar,
    pub lock: Mutex<()>, // same as Python LK for COND
}

impl Db {
    pub fn open(path: &Path) -> Arc<Self> {
        let conn = Connection::open(path).expect("open bridge.db");
        conn.execute_batch("pragma journal_mode=wal; pragma busy_timeout=5000;").ok();
        conn.execute_batch(r#"
create table if not exists projects(id integer primary key, name text, path text unique, created real);
create table if not exists sessions(id text primary key, project_id int, acp_sid text, title text, model text, effort text, mode text, created real, updated real);
create table if not exists events(seq integer primary key autoincrement, sid text, ts real, type text, data text);
create index if not exists ev_sid on events(sid, seq);
create table if not exists notes(seq integer primary key autoincrement, ts real, sid text, kind text, title text, text text);
create table if not exists kv(k text primary key, v text);
"#).ok();
        let _ = conn.execute("alter table sessions add column agent text default 'codex'", []);
        Arc::new(Self { conn: Mutex::new(conn), cond: Condvar::new(), lock: Mutex::new(()) })
    }
    pub fn q<T, F>(&self, sql: &str, params: impl rusqlite::Params, map: F) -> Vec<T>
    where F: FnMut(&rusqlite::Row<'_>) -> rusqlite::Result<T> {
        let c = self.conn.lock();
        let mut stmt = c.prepare(sql).unwrap();
        stmt.query_map(params, map).unwrap().filter_map(|r| r.ok()).collect()
    }
    pub fn q1<T, F>(&self, sql: &str, params: impl rusqlite::Params, map: F) -> Option<T>
    where F: FnMut(&rusqlite::Row<'_>) -> rusqlite::Result<T> {
        self.q(sql, params, map).into_iter().next()
    }
    pub fn exec(&self, sql: &str, params: impl rusqlite::Params) {
        let c = self.conn.lock(); c.execute(sql, params).ok();
    }
    pub fn insert_event(&self, sid: &str, typ: &str, data: &Value) -> i64 {
        let c = self.conn.lock();
        c.execute("insert into events(sid,ts,type,data) values(?,?,?,?)",
            params![sid, now(), typ, crate::py::dumps(data)]).unwrap();
        c.execute("update sessions set updated=? where id=?", params![now(), sid]).ok();
        c.last_insert_rowid()
    }
    pub fn kv(&self, k: &str, d: Option<Value>) -> Option<Value> {
        self.q1("select v from kv where k=?", params![k], |r| {
            let s: String = r.get(0)?; Ok(serde_json::from_str(&s).unwrap_or(Value::Null))
        }).or(d)
    }
    pub fn setkv(&self, k: &str, v: &Value) {
        self.exec("insert or replace into kv values(?,?)", params![k, serde_json::to_string(v).unwrap()]);
    }
    pub fn notify_all(&self) { let g = self.lock.lock(); self.cond.notify_all(); drop(g); }
}

pub fn home() -> PathBuf { PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/".into())) }
pub fn tilde(p: &str) -> String {
    let h = home().to_string_lossy().into_owned();
    if let Some(r) = p.strip_prefix(&(h.clone() + "/")) { format!("~/{r}") }
    else if p == h { "~".into() } else { p.into() }
}
pub fn expand_tilde(p: &str) -> PathBuf {
    if p == "~" { home() } else if let Some(r) = p.strip_prefix("~/") { home().join(r) } else { PathBuf::from(p) }
}
