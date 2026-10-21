//! bridge.db: projects/sessions/events/notes/kv
use parking_lot::{Condvar, Mutex};
use rusqlite::{params, params_from_iter, Connection, OptionalExtension};
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs_f64()
}

pub struct Db {
    pub(crate) conn: Mutex<Connection>,
    pub cond: Condvar,
    pub lock: Mutex<()>, // same as Python LK for COND
}

impl Db {
    pub fn open(path: &Path) -> Arc<Self> {
        Self::try_open(path).expect("initialize bridge.db")
    }
    pub fn try_open(path: &Path) -> rusqlite::Result<Arc<Self>> {
        let mut conn = Connection::open(path)?;
        conn.execute_batch(
            "pragma journal_mode=wal; pragma busy_timeout=5000; pragma synchronous=full;",
        )?;
        let exists: bool = conn.query_row(
            "select exists(select 1 from sqlite_master where type='table' and name='sessions')",
            [],
            |r| r.get(0),
        )?;
        if exists {
            let migrated: bool = conn.query_row("select exists(select 1 from sqlite_master where type='table' and name='schema_migrations')",
                [], |r|r.get(0))?;
            let version: i64 = if migrated {
                conn.query_row(
                    "select coalesce(max(version),0) from schema_migrations",
                    [],
                    |r| r.get(0),
                )?
            } else {
                0
            };
            if version > crate::security::SCHEMA_VERSION {
                return Err(rusqlite::Error::InvalidParameterName(
                    "database version is newer than this server".into(),
                ));
            }
            if version < crate::security::SCHEMA_VERSION {
                let backup = path.with_extension(format!(
                    "pre-v{}-{}.db",
                    crate::security::SCHEMA_VERSION,
                    uuid::Uuid::new_v4()
                ));
                conn.backup(rusqlite::DatabaseName::Main, &backup, None)?;
            }
        }
        conn.execute_batch(r#"
create table if not exists projects(id integer primary key, name text, path text unique, created real);
create table if not exists sessions(id text primary key, project_id int, acp_sid text, title text, model text, effort text, mode text, created real, updated real);
create table if not exists events(seq integer primary key autoincrement, sid text, ts real, type text, data text);
create index if not exists ev_sid on events(sid, seq);
create table if not exists notes(seq integer primary key autoincrement, ts real, sid text, kind text, title text, text text);
create table if not exists kv(k text primary key, v text);
"#)?;
        let has_agent = conn
            .prepare("pragma table_info(sessions)")?
            .query_map([], |r| r.get::<_, String>(1))?
            .collect::<rusqlite::Result<Vec<_>>>()?
            .iter()
            .any(|s| s == "agent");
        if !has_agent {
            conn.execute(
                "alter table sessions add column agent text default 'codex'",
                [],
            )?;
        }
        crate::tasks::migrate(&mut conn)?;
        crate::security::migrate(&mut conn)?;
        Ok(Arc::new(Self {
            conn: Mutex::new(conn),
            cond: Condvar::new(),
            lock: Mutex::new(()),
        }))
    }
    pub fn q<T, F>(&self, sql: &str, params: impl rusqlite::Params, map: F) -> Vec<T>
    where
        F: FnMut(&rusqlite::Row<'_>) -> rusqlite::Result<T>,
    {
        let c = self.conn.lock();
        let mut stmt = c.prepare(sql).unwrap();
        stmt.query_map(params, map)
            .unwrap()
            .filter_map(|r| r.ok())
            .collect()
    }
    pub fn q1<T, F>(&self, sql: &str, params: impl rusqlite::Params, map: F) -> Option<T>
    where
        F: FnMut(&rusqlite::Row<'_>) -> rusqlite::Result<T>,
    {
        self.q(sql, params, map).into_iter().next()
    }
    pub fn exec(&self, sql: &str, params: impl rusqlite::Params) {
        let c = self.conn.lock();
        c.execute(sql, params).ok();
    }
    pub fn insert_event(&self, sid: &str, typ: &str, data: &Value) -> i64 {
        let mut c = self.conn.lock();
        let tx = c.transaction().expect("begin event transaction");
        let task = crate::tasks::project_event(&tx, sid, typ, data).expect("project task event");
        let seq =
            crate::tasks::append(&tx, sid, task.as_deref(), typ, data).expect("persist event");
        tx.commit().expect("commit event");
        seq
    }
    pub fn kv(&self, k: &str, d: Option<Value>) -> Option<Value> {
        self.q1("select v from kv where k=?", params![k], |r| {
            let s: String = r.get(0)?;
            Ok(serde_json::from_str(&s).unwrap_or(Value::Null))
        })
        .or(d)
    }
    pub fn setkv(&self, k: &str, v: &Value) {
        self.exec(
            "insert or replace into kv values(?,?)",
            params![k, serde_json::to_string(v).unwrap()],
        );
    }
    pub fn notify_all(&self) {
        let g = self.lock.lock();
        self.cond.notify_all();
        drop(g);
    }
}

pub fn home() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/".into()))
}
pub fn tilde(p: &str) -> String {
    let h = home().to_string_lossy().into_owned();
    if let Some(r) = p.strip_prefix(&(h.clone() + "/")) {
        format!("~/{r}")
    } else if p == h {
        "~".into()
    } else {
        p.into()
    }
}
pub fn expand_tilde(p: &str) -> PathBuf {
    if p == "~" {
        home()
    } else if let Some(r) = p.strip_prefix("~/") {
        home().join(r)
    } else {
        PathBuf::from(p)
    }
}
