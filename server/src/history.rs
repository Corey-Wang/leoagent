use crate::db::Db;
use crate::py;
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::OnceLock;

const TOOL_CAP: usize = 240;
static HCACHE: OnceLock<Mutex<HashMap<String, (i64, Vec<Value>)>>> = OnceLock::new();

fn cut_tool(d: &Value) -> Value {
    let mut d = d.clone(); let mut full = false;
    let take = |s: &str| -> String { s.chars().take(TOOL_CAP).collect() };
    if let Some(o) = d.get("output").and_then(|x| x.as_str()) {
        if o.chars().count() > TOOL_CAP { d["output"] = json!(format!("{}\n…", take(o))); full = true; }
    }
    if let Some(diffs) = d.get("diffs").and_then(|x| x.as_array()).cloned() {
        let mut dd = vec![];
        for x in diffs.into_iter().take(6) {
            let mut x = x.clone();
            for k in ["old","new"] {
                if let Some(s) = x.get(k).and_then(|v| v.as_str()) {
                    if s.chars().count() > TOOL_CAP { x[k] = json!(format!("{}\n…", take(s))); full = true; }
                }
            }
            dd.push(x);
        }
        d["diffs"] = json!(dd);
    }
    if full { d["more"] = json!(true); }
    d
}

pub fn compact(db: &Db, sid: &str) -> (Vec<Value>, i64) {
    let mx: i64 = db.q1("select max(seq) from events where sid=?", rusqlite::params![sid], |r| r.get::<_,Option<i64>>(0).map(|x| x.unwrap_or(0))).unwrap_or(0);
    let cache = HCACHE.get_or_init(|| Mutex::new(HashMap::new()));
    if let Some((m, v)) = cache.lock().get(sid) { if *m == mx { return (v.clone(), mx) } }
    let rows: Vec<(i64,String,String,f64)> = db.q("select seq,type,data,ts from events where sid=? order by seq", rusqlite::params![sid],
        |r| -> rusqlite::Result<(i64,String,String,f64)> { Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)) });
    let mut out: Vec<Value> = vec![]; let mut tpos: HashMap<String, usize> = HashMap::new();
    for (seq, typ, data, ts) in rows {
        let d: Value = serde_json::from_str(&data).unwrap_or(json!({}));
        if (typ == "msg" || typ == "thought") && !out.is_empty() && out.last().unwrap()["type"] == typ {
            let last = out.last_mut().unwrap();
            let t = format!("{}{}", last["data"]["text"].as_str().unwrap_or(""), d.get("text").and_then(|x| x.as_str()).unwrap_or(""));
            last["data"]["text"] = json!(t); last["seq"] = json!(seq); continue;
        }
        if typ == "tool" {
            let id = d.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
            if let Some(&i) = tpos.get(&id) {
                out[i]["data"] = cut_tool(&d); out[i]["full_seq"] = json!(seq); continue;
            }
            tpos.insert(id, out.len());
            out.push(json!({"seq":seq,"type":typ,"data":cut_tool(&d),"ts":ts,"full_seq":seq})); continue;
        }
        if typ == "turn" && d.get("state").and_then(|x| x.as_str()) == Some("running") { continue }
        out.push(json!({"seq":seq,"type":typ,"data":d,"ts":ts}));
    }
    {
        let mut c = cache.lock();
        c.insert(sid.to_string(), (mx, out.clone()));
        if c.len() > 30 { let k = c.keys().next().cloned(); if let Some(k) = k { c.remove(&k); } }
    }
    (out, mx)
}
pub fn history(db: &Db, sid: &str, turns: usize, before: Option<i64>) -> Value {
    let (mut ev, mx) = compact(db, sid);
    if let Some(b) = before { ev.retain(|e| e["seq"].as_i64().unwrap_or(0) < b); }
    let starts: Vec<usize> = ev.iter().enumerate().filter(|(_,e)| e["type"]=="user").map(|(i,_)| i).collect();
    let cut = if starts.len() >= turns { starts[starts.len()-turns] } else { 0 };
    let pg = &ev[cut..];
    json!({"events": pg, "last": mx, "first": pg.first().and_then(|e| e["seq"].as_i64()).unwrap_or(before.unwrap_or(0)), "more": cut > 0})
}
