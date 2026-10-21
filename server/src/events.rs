use crate::db::{now, Db};
use crate::py;
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub struct Bus {
    pub db: Arc<Db>,
    pend: Mutex<HashMap<String, (String, String)>>, // sid -> (type, text)
}
impl Bus {
    pub fn new(db: Arc<Db>) -> Arc<Self> {
        let s = Arc::new(Self {
            db,
            pend: Mutex::new(HashMap::new()),
        });
        let s2 = s.clone();
        thread::spawn(move || loop {
            thread::sleep(Duration::from_millis(40));
            let _ = s2.flush(None);
        });
        s
    }
    fn insert(&self, sid: &str, typ: &str, data: &Value) -> i64 {
        let id = self.db.insert_event(sid, typ, data);
        self.db.notify_all();
        id
    }
    pub fn flush(&self, sid: Option<&str>) {
        let mut p = self.pend.lock();
        let keys: Vec<String> = match sid {
            Some(s) => {
                if p.contains_key(s) {
                    vec![s.to_string()]
                } else {
                    vec![]
                }
            }
            None => p.keys().cloned().collect(),
        };
        for k in keys {
            if let Some((t, text)) = p.remove(&k) {
                self.insert(&k, &t, &json!({"text": text}));
            }
        }
    }
    pub fn emit(&self, sid: &str, typ: &str, data: Value) -> Option<i64> {
        let mut p = self.pend.lock();
        if typ == "msg" || typ == "thought" {
            if let Some(e) = p.get_mut(sid) {
                if e.0 == typ {
                    e.1.push_str(data["text"].as_str().unwrap_or(""));
                    return None;
                }
            }
            if let Some((t, text)) = p.remove(sid) {
                self.insert(sid, &t, &json!({"text":text}));
            }
            p.insert(
                sid.to_string(),
                (
                    typ.to_string(),
                    data["text"].as_str().unwrap_or("").to_string(),
                ),
            );
            return None;
        }
        if let Some((t, text)) = p.remove(sid) {
            self.insert(sid, &t, &json!({"text":text}));
        }
        Some(self.insert(sid, typ, &data))
    }
    pub fn note(&self, sid: &str, kind: &str, title: &str, text: &str) {
        let t: String = text.chars().take(300).collect();
        self.db.exec(
            "insert into notes(ts,sid,kind,title,text) values(?,?,?,?,?)",
            rusqlite::params![now(), sid, kind, title, t],
        );
        self.db.exec(
            "delete from notes where ts<?",
            rusqlite::params![now() - 7.0 * 86400.0],
        );
        self.db.notify_all();
    }
}
pub fn trunc(s: &str, n: usize) -> String {
    let c = s.chars().count();
    if c <= n {
        s.to_string()
    } else {
        format!(
            "{}\n…(截断 {} 字)",
            s.chars().take(n).collect::<String>(),
            c - n
        )
    }
}
pub fn trunc_any(v: &Value, n: usize) -> String {
    let s = if let Value::String(s) = v {
        s.clone()
    } else {
        py::dumps(v)
    };
    trunc(&s, n)
}
