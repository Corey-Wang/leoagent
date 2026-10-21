//! Bounded inbox queries. Incremental pages are oldest-first to avoid skipping a burst.
use crate::db::Db;
use rusqlite::OptionalExtension;
use serde_json::{json, Value};
use std::collections::HashMap;

pub fn last(db: &Db) -> i64 {
    db.q1("select coalesce(max(seq),0) from notes", [], |r| r.get(0))
        .unwrap_or(0)
}

pub fn after(db: &Db, since: i64, limit: i64) -> Vec<Value> {
    query(
        db,
        &HashMap::from([("since".into(), since.to_string())]),
        limit,
    )
}

pub fn query(db: &Db, qs: &HashMap<String, String>, limit: i64) -> Vec<Value> {
    let since = qs.get("since").and_then(|x| x.parse::<i64>().ok());
    let before = qs
        .get("before")
        .and_then(|x| x.parse::<i64>().ok())
        .unwrap_or(i64::MAX);
    let today = if qs.get("date").map(String::as_str) == Some("today") {
        chrono::Local::now()
            .date_naive()
            .and_hms_opt(0, 0, 0)
            .and_then(|t| t.and_local_timezone(chrono::Local).earliest())
            .map(|t| t.timestamp() as f64)
            .unwrap_or(0.0)
    } else {
        crate::db::now() - 7.0 * 86400.0
    };
    let order = if since.is_some() { "asc" } else { "desc" };
    db.q(&format!("select seq,ts,sid,kind,title,text from notes where seq>? and seq<? and ts>=? order by seq {order} limit ?"),
        rusqlite::params![since.unwrap_or(0), before, today, limit.clamp(1, 500)],
        |r| Ok(json!({"seq":r.get::<_,i64>(0)?,"ts":r.get::<_,f64>(1)?,"sid":r.get::<_,Option<String>>(2)?,
            "kind":r.get::<_,String>(3)?,"title":r.get::<_,String>(4)?,"text":r.get::<_,String>(5)?})))
}

/// Write the reminder in the same SQLite transaction as the fact it describes.
pub fn insert(
    tx: &rusqlite::Transaction<'_>,
    sid: &str,
    kind: &str,
    title: &str,
    text: &str,
) -> rusqlite::Result<()> {
    tx.execute(
        "insert into notes(ts,sid,kind,title,text) values(?,?,?,?,?)",
        rusqlite::params![
            crate::db::now(),
            sid,
            kind,
            title,
            text.chars().take(300).collect::<String>()
        ],
    )?;
    tx.execute(
        "delete from notes where ts<?",
        [crate::db::now() - 7.0 * 86400.0],
    )?;
    Ok(())
}

pub fn terminal(tx: &rusqlite::Transaction<'_>, sid: &str, data: &Value) -> rusqlite::Result<()> {
    let (kind, head, fallback) = match data["state"].as_str().unwrap_or("") {
        "succeeded" => ("done", "完成", "本轮已完成"),
        "failed" => ("error", "执行失败", "本轮执行失败，请打开对话查看原因"),
        "cancelled" => (
            "cancelled",
            "已停止",
            "Agent 已停止，本轮可能已修改项目文件",
        ),
        "unknown" => (
            "unknown",
            "结果未知，需核对",
            "请核对项目文件和任务结果；核对前项目保持占用，不会自动重发",
        ),
        _ => return Ok(()),
    };
    let title: String = tx.query_row(
        "select coalesce(nullif(title,''),'对话') from sessions where id=?",
        [sid],
        |r| r.get(0),
    )?;
    let text = if kind == "done" {
        let raw: Option<String> = tx
            .query_row(
                "select data from events where sid=? and task_id=? and type='msg' order by seq desc limit 1",
                rusqlite::params![sid, data["task_id"].as_str()],
                |r| r.get(0),
            )
            .optional()?;
        raw.and_then(|raw| serde_json::from_str::<Value>(&raw).ok())
            .and_then(|o| o["text"].as_str().map(String::from))
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| fallback.into())
    } else {
        fallback.into()
    };
    insert(tx, sid, kind, &format!("{head} · {title}"), &text)
}
