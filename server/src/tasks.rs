//! Durable command admission. SQLite owns execution claims; memory is only a cache.
use crate::db::{now, Db};
use rusqlite::{params, Connection, OptionalExtension, Transaction};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

pub const ACTIVE: &str =
    "'queued','running','waiting_approval','waiting_input','cancelling','recovering','unknown'";

#[derive(Debug)]
pub struct Error {
    pub status: u16,
    pub code: String,
    pub message: String,
}
impl Error {
    pub fn new(status: u16, code: &str, message: impl Into<String>) -> Self {
        Self {
            status,
            code: code.into(),
            message: message.into(),
        }
    }
}
impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Self {
        crate::runtime::log(&format!("task storage error: {e}"));
        Self::new(500, "storage_error", "无法持久化任务状态")
    }
}

pub struct Admission {
    pub status: u16,
    pub receipt: Value,
    pub fresh: bool,
}

pub fn migrate(conn: &mut Connection) -> rusqlite::Result<()> {
    let tx = conn.transaction()?;
    tx.execute_batch(
        "create table if not exists schema_migrations(version integer primary key, applied real not null);
         create table if not exists tasks(
            id text primary key, sid text not null, project_id integer not null,
            state text not null, dispatch_started integer not null default 0,
            created real not null, updated real not null, reason text,
            generation text not null, cancel_requested integer not null default 0);
         create index if not exists task_session on tasks(sid,created);
         create table if not exists operation_receipts(
            caller text not null, operation_id text not null, fingerprint text not null,
            status integer not null, receipt text not null, created real not null,
            primary key(caller,operation_id));
         create table if not exists interactions(
            id text primary key, sid text not null, task_id text, kind text not null,
            state text not null, spec text not null, created real not null,
            updated real not null, reason text);
         create index if not exists interaction_session on interactions(sid,state);
         create table if not exists runtime_instances(
            id text primary key, sid text not null, task_id text, pid integer not null,
            process_start text, generation text not null, state text not null,
            created real not null, updated real not null);
         create table if not exists resolution_claims(
            task_id text primary key, caller text not null, operation_id text not null);",
    )?;
    tx.execute_batch(&format!(
        "create unique index if not exists one_task_per_session on tasks(sid) where state in ({ACTIVE});
         create unique index if not exists one_task_per_project on tasks(project_id) where state in ({ACTIVE});"
    ))?;
    let has_task_id: bool = tx
        .prepare("pragma table_info(events)")?
        .query_map([], |r| r.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|s| s == "task_id");
    if !has_task_id {
        tx.execute("alter table events add column task_id text", [])?;
    }
    for key in ["server_id", "data_epoch"] {
        tx.execute(
            "insert or ignore into kv(k,v) values(?,?)",
            params![key, json!(uuid::Uuid::new_v4().to_string()).to_string()],
        )?;
    }
    tx.execute(
        "insert or ignore into schema_migrations(version,applied) values(1,?)",
        [now()],
    )?;
    tx.commit()
}

fn meta(conn: &Connection, name: &str) -> rusqlite::Result<Value> {
    let s: String = conn.query_row("select v from kv where k=?", [name], |r| r.get(0))?;
    Ok(serde_json::from_str(&s).unwrap_or(Value::Null))
}

pub fn identity(db: &Db) -> Result<Value, Error> {
    let conn = db.conn.lock();
    Ok(json!({"server_id": meta(&conn,"server_id")?, "data_epoch": meta(&conn,"data_epoch")?}))
}

pub fn task_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<Value> {
    Ok(
        json!({"id":r.get::<_,String>(0)?, "session_id":r.get::<_,String>(1)?,
        "project_id":r.get::<_,i64>(2)?, "state":r.get::<_,String>(3)?,
        "dispatch_started":r.get::<_,bool>(4)?, "created_at":r.get::<_,f64>(5)?,
        "updated_at":r.get::<_,f64>(6)?, "reason":r.get::<_,Option<String>>(7)?,
        "server_generation":r.get::<_,String>(8)?,
        "cancel_requested":r.get::<_,bool>(9)?}),
    )
}
const COLUMNS: &str =
    "id,sid,project_id,state,dispatch_started,created,updated,reason,generation,cancel_requested";

pub fn latest(db: &Db, sid: &str) -> Result<Option<Value>, Error> {
    Ok(db
        .conn
        .lock()
        .query_row(
            &format!("select {COLUMNS} from tasks where sid=? order by rowid desc limit 1"),
            [sid],
            task_row,
        )
        .optional()?)
}
pub fn live_interactions(db: &Db, sid: &str) -> Result<Vec<Value>, Error> {
    let c = db.conn.lock();
    let mut stmt=c.prepare("select id,kind,spec,state from interactions where sid=? and state in ('pending','responding') order by created")?;
    let rows = stmt.query_map([sid],|r|{
        let spec:String=r.get(2)?;
        Ok(json!({"id":r.get::<_,String>(0)?,"kind":r.get::<_,String>(1)?,
            "spec":serde_json::from_str::<Value>(&spec).unwrap_or(Value::Null),"state":r.get::<_,String>(3)?}))
    })?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}
pub fn get(db: &Db, id: &str) -> Result<Option<Value>, Error> {
    Ok(db
        .conn
        .lock()
        .query_row(
            &format!("select {COLUMNS} from tasks where id=?"),
            [id],
            task_row,
        )
        .optional()?)
}
fn active_id(conn: &Connection, sid: &str) -> rusqlite::Result<Option<String>> {
    conn.query_row(
        &format!("select id from tasks where sid=? and state in ({ACTIVE})"),
        [sid],
        |r| r.get(0),
    )
    .optional()
}

/// All JSON objects are hashed in lexical key order, including nested provider options.
fn canonical(v: &Value) -> Value {
    match v {
        Value::Object(o) => {
            let mut keys: Vec<_> = o.keys().collect();
            keys.sort();
            let mut out = serde_json::Map::new();
            for k in keys {
                out.insert(k.clone(), canonical(&o[k]));
            }
            Value::Object(out)
        }
        Value::Array(a) => Value::Array(a.iter().map(canonical).collect()),
        _ => v.clone(),
    }
}
fn fingerprint(sid: &str, payload: &Value) -> String {
    hex::encode(Sha256::digest(
        canonical(&json!({"kind":"prompt","sid":sid,"payload":payload})).to_string(),
    ))
}

pub fn operation(db: &Db, caller: &str, id: &str) -> Result<Option<(u16, Value)>, Error> {
    let conn = db.conn.lock();
    Ok(conn
        .query_row(
            "select status,receipt from operation_receipts where caller=? and operation_id=?",
            params![caller, id],
            |r| {
                let s: String = r.get(1)?;
                Ok((r.get(0)?, serde_json::from_str(&s).unwrap_or(Value::Null)))
            },
        )
        .optional()?)
}

fn reject_operation(
    tx: Transaction<'_>,
    caller: &str,
    op: &str,
    fingerprint: &str,
    status: u16,
    code: &str,
    message: &str,
) -> Result<Admission, Error> {
    let receipt = json!({"operation_id":op,"status":"rejected","ok":false,
        "code":code,"error":message,"message":message,"request_id":uuid::Uuid::new_v4().to_string()});
    tx.execute(
        "insert into operation_receipts values(?,?,?,?,?,?)",
        params![caller, op, fingerprint, status, receipt.to_string(), now()],
    )?;
    tx.commit()?;
    Ok(Admission {
        status,
        receipt,
        fresh: false,
    })
}

pub fn admit_control(
    db: &Db,
    caller: &str,
    op: &str,
    id: &str,
    kind: &str,
    body: &Value,
    epoch: &str,
) -> Result<Admission, Error> {
    if op.is_empty()
        || op.len() > 128
        || !op
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b':'))
    {
        return Err(Error::new(
            400,
            "invalid_operation_id",
            "操作 ID 格式不正确",
        ));
    }
    let fp = hex::encode(Sha256::digest(
        canonical(&json!({"kind":kind,"task_id":id,"body":body})).to_string(),
    ));
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    if meta(&tx, "data_epoch")?.as_str() != Some(epoch) {
        return Err(Error::new(
            409,
            "data_epoch_changed",
            "电脑数据已变更，请重新同步",
        ));
    }
    let prior:Option<(String,u16,String)>=tx.query_row("select fingerprint,status,receipt from operation_receipts where caller=? and operation_id=?",
        params![caller,op],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
    if let Some((hash, status, receipt)) = prior {
        if hash != fp {
            return Err(Error::new(
                409,
                "idempotency_conflict",
                "同一操作 ID 的参数不同",
            ));
        }
        return Ok(Admission {
            status,
            receipt: serde_json::from_str(&receipt).unwrap_or(Value::Null),
            fresh: false,
        });
    }
    let task = tx
        .query_row(
            &format!("select {COLUMNS} from tasks where id=?"),
            [id],
            task_row,
        )
        .optional()?
        .ok_or_else(|| Error::new(404, "task_not_found", "任务不存在"))?;
    let mut state = task["state"].as_str().unwrap().to_string();
    let mut fresh = false;
    match kind {
        "cancel"
            if matches!(
                state.as_str(),
                "queued" | "running" | "waiting_approval" | "waiting_input"
            ) =>
        {
            state = if state == "queued" {
                "cancelled"
            } else {
                "cancelling"
            }
            .into();
            tx.execute(
                "update tasks set state=?,cancel_requested=1,updated=? where id=?",
                params![state, now(), id],
            )?;
            append(
                &tx,
                task["session_id"].as_str().unwrap(),
                Some(id),
                "task",
                &json!({"task_id":id,"state":state}),
            )?;
            fresh = state == "cancelling";
        }
        "cancel" => {}
        "resolve" => {
            if state != "unknown" {
                return Err(Error::new(
                    409,
                    "task_not_unknown",
                    "仅结果未知的任务需要人工核对",
                ));
            }
            if body["acknowledge_unknown_effects"] != true {
                return Err(Error::new(
                    400,
                    "acknowledgement_required",
                    "请确认已核对文件和命令结果，解除占用不会撤销已有改动",
                ));
            }
            let occupied: bool = tx.query_row(
                "select exists(select 1 from resolution_claims where task_id=?)",
                [id],
                |r| r.get(0),
            )?;
            if occupied {
                return Err(Error::new(
                    409,
                    "resolution_in_progress",
                    "另一项核对操作正在进行，请先查询其回执",
                ));
            }
            tx.execute(
                "insert into resolution_claims values(?,?,?)",
                params![id, caller, op],
            )?;
            fresh = true;
        }
        _ => {
            return Err(Error::new(
                400,
                "unsupported_control",
                "不支持的任务控制操作",
            ))
        }
    }
    let receipt = json!({"ok":true,"operation_id":op,"task_id":id,"session_id":task["session_id"],
        "kind":kind,"status":"accepted","task_state":state});
    tx.execute(
        "insert into operation_receipts values(?,?,?,?,?,?)",
        params![caller, op, fp, 202, receipt.to_string(), now()],
    )?;
    tx.commit()?;
    drop(c);
    db.notify_all();
    Ok(Admission {
        status: 202,
        receipt,
        fresh,
    })
}

pub fn finish_resolution(
    db: &Db,
    caller: &str,
    op: &str,
    id: &str,
    problem: Option<&str>,
) -> Result<Admission, Error> {
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    let saved: String = tx.query_row(
        "select receipt from operation_receipts where caller=? and operation_id=?",
        params![caller, op],
        |r| r.get(0),
    )?;
    let mut receipt: Value = serde_json::from_str(&saved).unwrap_or(Value::Null);
    let status = if let Some(problem) = problem {
        receipt["ok"] = json!(false);
        receipt["status"] = json!("rejected");
        receipt["code"] = json!("runtime_still_unverified");
        receipt["error"] = json!(problem);
        409
    } else {
        let sid: String = tx.query_row(
            "select sid from tasks where id=? and state='unknown'",
            [id],
            |r| r.get(0),
        )?;
        tx.execute("update tasks set state='interrupted',reason='user_acknowledged_unknown_effects',updated=? where id=?",params![now(),id])?;
        append(
            &tx,
            &sid,
            Some(id),
            "task",
            &json!({"task_id":id,"state":"interrupted","reason":"user_acknowledged_unknown_effects"}),
        )?;
        receipt["status"] = json!("resolved");
        receipt["task_state"] = json!("interrupted");
        200
    };
    tx.execute(
        "update operation_receipts set status=?,receipt=? where caller=? and operation_id=?",
        params![status, receipt.to_string(), caller, op],
    )?;
    tx.execute(
        "delete from resolution_claims where task_id=? and caller=? and operation_id=?",
        params![id, caller, op],
    )?;
    tx.commit()?;
    drop(c);
    db.notify_all();
    Ok(Admission {
        status,
        receipt,
        fresh: false,
    })
}

pub fn runtime_evidence(db: &Db, task: &str) -> Result<Vec<Value>, Error> {
    let c = db.conn.lock();
    let mut stmt = c.prepare(
        "select id,pid,process_start,generation,state from runtime_instances where task_id=?",
    )?;
    let rows=stmt.query_map([task],|r|Ok(json!({"id":r.get::<_,String>(0)?,"pid":r.get::<_,i64>(1)?,
        "process_start":r.get::<_,Option<String>>(2)?,"generation":r.get::<_,String>(3)?,"state":r.get::<_,String>(4)?})))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// Observation only: never send a real signal to a PID recovered from disk.
pub fn runtime_may_be_alive(pid: i32, recorded_start: Option<&str>) -> bool {
    if pid <= 0 {
        return true;
    }
    // A dead group leader can leave live tools behind. Observe the old group,
    // but never signal it: after a restart its identity is no longer owned.
    let groups = std::process::Command::new("/bin/ps")
        .args(["-axo", "pid=,pgid=,stat="])
        .output();
    let groups = match groups {
        Ok(o) if o.status.success() => String::from_utf8_lossy(&o.stdout).into_owned(),
        _ => return true,
    };
    if groups.lines().any(|line| {
        let fields = line.split_whitespace().collect::<Vec<_>>();
        fields.len() >= 3
            && fields[1].parse::<i32>().ok() == Some(pid)
            && !fields[2].starts_with('Z')
    }) {
        return true;
    }
    if unsafe { libc::kill(pid, 0) } != 0
        && std::io::Error::last_os_error().raw_os_error() == Some(libc::ESRCH)
    {
        return false;
    }
    let current = std::process::Command::new("/bin/ps")
        .args(["-o", "lstart=", "-p", &pid.to_string()])
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty());
    match (recorded_start, current.as_deref()) {
        (Some(old), Some(new)) if old != new => false,
        _ => true,
    }
}

pub fn admit_response(
    db: &Db,
    caller: &str,
    op: &str,
    rid: &str,
    body: &Value,
) -> Result<Admission, Error> {
    if op.is_empty() || op.len() > 128 {
        return Err(Error::new(
            400,
            "invalid_operation_id",
            "操作 ID 格式不正确",
        ));
    }
    let fp = hex::encode(Sha256::digest(
        canonical(&json!({"kind":"interaction_response","id":rid,"body":body})).to_string(),
    ));
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    let prior:Option<(String,u16,String)>=tx.query_row("select fingerprint,status,receipt from operation_receipts where caller=? and operation_id=?",
        params![caller,op],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
    if let Some((hash, status, receipt)) = prior {
        if hash != fp {
            return Err(Error::new(
                409,
                "idempotency_conflict",
                "同一操作 ID 的参数不同",
            ));
        }
        return Ok(Admission {
            status,
            receipt: serde_json::from_str(&receipt).unwrap_or(Value::Null),
            fresh: false,
        });
    }
    let row: Option<(String, String, String, String, Option<String>)> = tx
        .query_row(
            "select sid,kind,state,spec,task_id from interactions where id=?",
            [rid],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .optional()?;
    let Some((sid, kind, state, spec, task)) = row else {
        return reject_operation(
            tx,
            caller,
            op,
            &fp,
            410,
            "interaction_expired",
            "该交互已过期",
        );
    };
    if state != "pending" {
        return reject_operation(
            tx,
            caller,
            op,
            &fp,
            409,
            "interaction_already_handled",
            "该交互已被处理或已过期",
        );
    }
    if kind == "perm" {
        let option = body.get("option").and_then(Value::as_str);
        if let Some(option) = option.filter(|s| !s.is_empty() && *s != "cancel") {
            let spec: Value = serde_json::from_str(&spec).unwrap_or(Value::Null);
            if !spec["options"]
                .as_array()
                .is_some_and(|a| a.iter().any(|o| o["id"].as_str() == Some(option)))
            {
                return reject_operation(
                    tx,
                    caller,
                    op,
                    &fp,
                    400,
                    "invalid_permission_option",
                    "不是该审批允许的选项",
                );
            }
        }
    }
    let receipt = json!({"ok":true,"operation_id":op,"interaction_id":rid,"session_id":sid,"kind":kind,
        "status":"accepted","delivery":"unknown"});
    tx.execute(
        "update interactions set state='responding',updated=? where id=?",
        params![now(), rid],
    )?;
    append(
        &tx,
        &sid,
        task.as_deref(),
        "interaction_status",
        &json!({"rid":rid,"state":"responding"}),
    )?;
    tx.execute(
        "insert into operation_receipts values(?,?,?,?,?,?)",
        params![caller, op, fp, 202, receipt.to_string(), now()],
    )?;
    tx.commit()?;
    drop(c);
    db.notify_all();
    Ok(Admission {
        status: 202,
        receipt,
        fresh: true,
    })
}

pub fn finish_response(
    db: &Db,
    caller: &str,
    op: &str,
    rid: &str,
    written: bool,
) -> Result<Value, Error> {
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    let saved: String = tx.query_row(
        "select receipt from operation_receipts where caller=? and operation_id=?",
        params![caller, op],
        |r| r.get(0),
    )?;
    let mut receipt: Value = serde_json::from_str(&saved).unwrap_or(Value::Null);
    let deferred: bool = tx.query_row(
        "select exists(select 1 from deferred_prompts where id=?)",
        [format!("followup:{rid}")],
        |r| r.get(0),
    )?;
    receipt["status"] = json!(if !written {
        "unknown"
    } else if deferred {
        "accepted"
    } else {
        "submitted"
    });
    receipt["delivery"] = json!(if !written {
        "unknown"
    } else if deferred {
        "queued"
    } else {
        "written"
    });
    if !written {
        receipt["code"] = json!("delivery_unknown");
        receipt["error"] = json!("无法确认 Agent 收到决定，请查看任务状态，勿重复提交");
    }
    tx.execute(
        "update operation_receipts set status=?,receipt=? where caller=? and operation_id=?",
        params![
            if written { 200 } else { 502 },
            receipt.to_string(),
            caller,
            op
        ],
    )?;
    tx.execute(
        "update interactions set state=?,updated=? where id=? and state='responding'",
        params![if written { "answered" } else { "unknown" }, now(), rid],
    )?;
    tx.commit()?;
    drop(c);
    db.notify_all();
    Ok(receipt)
}

pub fn submit(
    db: &Db,
    caller: &str,
    op: &str,
    sid: &str,
    epoch: Option<&str>,
    payload: &Value,
    generation: &str,
    max_active: usize,
) -> Result<Admission, Error> {
    submit_inner(
        db, caller, op, sid, epoch, payload, generation, max_active, false,
    )
}
fn submit_inner(
    db: &Db,
    caller: &str,
    op: &str,
    sid: &str,
    epoch: Option<&str>,
    payload: &Value,
    generation: &str,
    max_active: usize,
    deferred: bool,
) -> Result<Admission, Error> {
    if op.is_empty()
        || op.len() > 128
        || !op
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b':'))
    {
        return Err(Error::new(
            400,
            "invalid_operation_id",
            "操作 ID 格式不正确",
        ));
    }
    let fp = fingerprint(sid, payload);
    let mut conn = db.conn.lock();
    let tx = conn.transaction()?;
    if deferred {
        let ready:bool=tx.query_row(
            "select exists(select 1 from deferred_prompts d left join tasks t on t.id=d.parent_task_id
             where d.id=? and d.state='queued' and (d.parent_task_id is null or t.state='succeeded'))",[op],|r|r.get(0))?;
        if !ready {
            return Err(Error::new(409, "deferred_not_ready", "后续回答暂不可发送"));
        }
    }
    let current_epoch = meta(&tx, "data_epoch")?;
    if epoch.is_some_and(|e| Some(e) != current_epoch.as_str()) {
        return Err(Error::new(
            409,
            "data_epoch_changed",
            "电脑数据已恢复或重置，请重新同步",
        ));
    }
    let prior: Option<(String,u16,String)> = tx.query_row(
        "select fingerprint,status,receipt from operation_receipts where caller=? and operation_id=?",
        params![caller,op], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
    if let Some((hash, status, receipt)) = prior {
        if hash != fp {
            return Err(Error::new(
                409,
                "idempotency_conflict",
                "同一操作 ID 的参数不同",
            ));
        }
        return Ok(Admission {
            status,
            receipt: serde_json::from_str(&receipt).unwrap_or(Value::Null),
            fresh: false,
        });
    }
    let project: Option<i64> = tx
        .query_row("select project_id from sessions where id=?", [sid], |r| {
            r.get(0)
        })
        .optional()?;
    let project = project.ok_or_else(|| Error::new(404, "session_not_found", "对话不存在"))?;
    let text = payload
        .get("text")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim();
    let images = payload.get("images").and_then(Value::as_array);
    let nimg = images
        .map(|a| {
            a.iter()
                .filter(|i| {
                    i.get("data")
                        .and_then(Value::as_str)
                        .is_some_and(|x| !x.is_empty())
                })
                .count()
        })
        .unwrap_or(0);
    if text.is_empty() && nimg == 0 {
        return Err(Error::new(400, "empty_prompt", "空消息"));
    }
    let busy: i64 = tx.query_row(
        &format!("select count(*) from tasks where project_id=? and state in ({ACTIVE})"),
        [project],
        |r| r.get(0),
    )?;
    let total: i64 = tx.query_row(
        &format!("select count(*) from tasks where state in ({ACTIVE})"),
        [],
        |r| r.get(0),
    )?;
    let (status, receipt, fresh) = if busy > 0 || total >= max_active as i64 {
        if deferred {
            return Err(Error::new(429, "deferred_waiting", "等待项目空闲"));
        }
        let code = if busy > 0 {
            "project_busy"
        } else {
            "capacity_reached"
        };
        (
            409,
            json!({"ok":false,"operation_id":op,"code":code,"error":if busy>0 {"该项目已有运行中或待核对的任务"}else{"电脑任务数已达到上限"},"status":"rejected"}),
            false,
        )
    } else {
        let id = uuid::Uuid::new_v4().to_string();
        let ts = now();
        tx.execute("insert into tasks(id,sid,project_id,state,created,updated,generation) values(?,?,?,'queued',?,?,?)",
            params![id,sid,project,ts,ts,generation])?;
        append(
            &tx,
            sid,
            Some(&id),
            "user",
            &json!({"text":text,"images":nimg,"task_id":id}),
        )?;
        append(
            &tx,
            sid,
            Some(&id),
            "task",
            &json!({"task_id":id,"state":"queued"}),
        )?;
        tx.execute(
            "update sessions set title=? where id=? and coalesce(title,'')=''",
            params![text.chars().take(40).collect::<String>(), sid],
        )?;
        (
            202,
            json!({"ok":true,"operation_id":op,"task_id":id,"status":"accepted","data_epoch":current_epoch,
            "operation_url":format!("/api/v1/operations/{op}"),"task_url":format!("/api/v1/tasks/{id}")}),
            true,
        )
    };
    tx.execute(
        "insert into operation_receipts values(?,?,?,?,?,?)",
        params![caller, op, fp, status, receipt.to_string(), now()],
    )?;
    if deferred {
        tx.execute("update deferred_prompts set state='dispatched',task_id=? where id=? and state='queued'",
            params![receipt["task_id"].as_str(),op])?;
    }
    tx.commit()?;
    drop(conn);
    db.notify_all();
    Ok(Admission {
        status,
        receipt,
        fresh,
    })
}

pub fn defer_prompt(db: &Db, sid: &str, source: &str, text: &str) -> Result<(), Error> {
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    let parent: Option<String> = tx
        .query_row(
            "select task_id from interactions where id=? and sid=?",
            params![source, sid],
            |r| r.get(0),
        )
        .optional()?
        .flatten();
    let id = format!("followup:{source}");
    let prior: Option<(String, String)> = tx
        .query_row(
            "select sid,text from deferred_prompts where id=?",
            [&id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    if let Some((saved_sid, saved_text)) = prior {
        if saved_sid != sid || saved_text != text {
            return Err(Error::new(
                409,
                "followup_conflict",
                "同一回答的后续任务参数不同",
            ));
        }
    } else {
        tx.execute("insert into deferred_prompts(id,sid,parent_task_id,text,state,created) values(?,?,?,?,'queued',?)",
            params![id,sid,parent,text,now()])?;
        append(
            &tx,
            sid,
            parent.as_deref(),
            "deferred",
            &json!({"id":id,"state":"queued","reason":"awaiting_parent"}),
        )?;
    }
    tx.commit()?;
    drop(c);
    db.notify_all();
    Ok(())
}
pub fn deferred_candidates(db: &Db) -> Result<Vec<(String, String, String)>, Error> {
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    let mut stmt = tx.prepare(
        "select d.id,d.sid,t.state from deferred_prompts d join tasks t on t.id=d.parent_task_id
         where d.state='queued' and t.state in ('failed','unknown','cancelled','interrupted')",
    )?;
    let blocked = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(stmt);
    for (id, sid, state) in blocked {
        tx.execute(
            "update deferred_prompts set state='blocked',reason=? where id=?",
            params![state, id],
        )?;
        append(
            &tx,
            &sid,
            None,
            "deferred",
            &json!({"id":id,"state":"blocked","reason":state}),
        )?;
        tx.execute("insert into notes(ts,sid,kind,title,text) values(?,?,'error','后续回答尚未发送','原任务未正常完成，请核对后重新提交回答')",params![now(),sid])?;
    }
    let mut stmt=tx.prepare("select d.id,d.sid,d.text from deferred_prompts d left join tasks t on t.id=d.parent_task_id
        where d.state='queued' and (d.parent_task_id is null or t.state='succeeded') order by d.created limit 64")?;
    let rows = stmt
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(stmt);
    tx.commit()?;
    Ok(rows)
}
pub fn submit_deferred(
    db: &Db,
    id: &str,
    sid: &str,
    text: &str,
    generation: &str,
    max: usize,
) -> Result<Admission, Error> {
    submit_inner(
        db,
        "internal",
        id,
        sid,
        None,
        &json!({"text":text,"images":[]}),
        generation,
        max,
        true,
    )
}

pub fn append(
    tx: &Transaction<'_>,
    sid: &str,
    task: Option<&str>,
    typ: &str,
    data: &Value,
) -> rusqlite::Result<i64> {
    tx.execute(
        "insert into events(sid,ts,type,data,task_id) values(?,?,?,?,?)",
        params![sid, now(), typ, crate::py::dumps(data), task],
    )?;
    let seq = tx.last_insert_rowid();
    if typ == "task" {
        crate::notes::terminal(tx, sid, data)?;
    }
    tx.execute(
        "update sessions set updated=? where id=?",
        params![now(), sid],
    )?;
    Ok(seq)
}

pub fn transition(db: &Db, id: &str, state: &str, reason: Option<&str>) -> Result<bool, Error> {
    let mut conn = db.conn.lock();
    let tx = conn.transaction()?;
    let sid: Option<String> = tx
        .query_row(
            &format!(
                "select sid from tasks where id=? and state in ({ACTIVE}) and state!='unknown'"
            ),
            [id],
            |r| r.get(0),
        )
        .optional()?;
    let Some(sid) = sid else { return Ok(false) };
    tx.execute(
        "update tasks set state=?,reason=?,updated=? where id=?",
        params![state, reason, now(), id],
    )?;
    append(
        &tx,
        &sid,
        Some(id),
        "task",
        &json!({"task_id":id,"state":state,"reason":reason}),
    )?;
    if matches!(state, "succeeded" | "failed" | "cancelled" | "unknown") {
        tx.execute("update interactions set state='expired',reason=?,updated=? where task_id=? and state in ('pending','responding')",
            params![reason.unwrap_or("task_finished"),now(),id])?;
    }
    tx.commit()?;
    drop(conn);
    db.notify_all();
    Ok(true)
}

/// Commit the uncertainty boundary before sending anything to the external process.
pub fn claim_dispatch(db: &Db, id: &str) -> Result<bool, Error> {
    let mut conn = db.conn.lock();
    let tx = conn.transaction()?;
    let changed = tx.execute(
        "update tasks set dispatch_started=1,state='running',updated=? where id=? and state='running' and dispatch_started=0 and cancel_requested=0",
        params![now(),id])? == 1;
    tx.commit()?;
    Ok(changed)
}

pub fn request_cancel(db: &Db, id: &str) -> Result<Option<Value>, Error> {
    let mut conn = db.conn.lock();
    let tx = conn.transaction()?;
    let t = tx
        .query_row(
            &format!("select {COLUMNS} from tasks where id=?"),
            [id],
            task_row,
        )
        .optional()?;
    let Some(t) = t else { return Ok(None) };
    let old = t["state"].as_str().unwrap_or("");
    if matches!(
        old,
        "queued" | "running" | "waiting_approval" | "waiting_input"
    ) {
        let state = if old == "queued" {
            "cancelled"
        } else {
            "cancelling"
        };
        tx.execute(
            "update tasks set state=?,cancel_requested=1,updated=? where id=?",
            params![state, now(), id],
        )?;
        append(
            &tx,
            t["session_id"].as_str().unwrap(),
            Some(id),
            "task",
            &json!({"task_id":id,"state":state}),
        )?;
    }
    tx.commit()?;
    drop(conn);
    db.notify_all();
    get(db, id)
}

pub fn cancel_timeout(db: &Db, id: &str) -> Result<bool, Error> {
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    let sid: Option<String> = tx
        .query_row(
            "select sid from tasks where id=? and state='cancelling'",
            [id],
            |r| r.get(0),
        )
        .optional()?;
    let Some(sid) = sid else { return Ok(false) };
    tx.execute(
        "update tasks set state='unknown',reason='cancel_timeout',updated=? where id=?",
        params![now(), id],
    )?;
    tx.execute("update interactions set state='expired',reason='cancel_timeout',updated=? where task_id=? and state in ('pending','responding')",params![now(),id])?;
    append(
        &tx,
        &sid,
        Some(id),
        "task",
        &json!({"task_id":id,"state":"unknown","reason":"cancel_timeout"}),
    )?;
    tx.commit()?;
    drop(c);
    db.notify_all();
    Ok(true)
}

/// Project persisted interaction facts and terminal turn facts within the event transaction.
pub fn project_event(
    tx: &Transaction<'_>,
    sid: &str,
    typ: &str,
    data: &Value,
) -> rusqlite::Result<Option<String>> {
    let task = match data.get("task_id").and_then(Value::as_str) {
        Some(id) => Some(id.into()),
        None => active_id(tx, sid)?,
    };
    if matches!(typ, "perm" | "ask") {
        if let Some(id) = data.get("rid").and_then(Value::as_str) {
            let inserted = tx.execute(
                "insert or ignore into interactions values(?,?,?,?,?,?,?,?,?)",
                params![
                    id,
                    sid,
                    task,
                    typ,
                    "pending",
                    data.to_string(),
                    now(),
                    now(),
                    Option::<String>::None
                ],
            )?;
            if inserted == 1 {
                let title = if typ == "perm" {
                    "需要你的审批"
                } else {
                    "需要你的输入"
                };
                let text = data
                    .pointer("/questions/0/title")
                    .or_else(|| data.get("title"))
                    .and_then(Value::as_str)
                    .unwrap_or("");
                crate::notes::insert(tx, sid, typ, title, text)?;
            }
        }
    } else if matches!(typ, "perm_done" | "ask_done") {
        if let Some(id) = data.get("rid").and_then(Value::as_str) {
            tx.execute("update interactions set state='answered',updated=? where id=? and sid=? and state in ('pending','responding')",
                params![now(),id,sid])?;
        }
    }
    if let Some(id) = &task {
        if typ == "turn" && data.get("state").and_then(Value::as_str) == Some("idle") {
            // Actual task execution finalizes with precise error/dispatch information.
        } else {
            let approval: bool = tx.query_row("select exists(select 1 from interactions where sid=? and state in ('pending','responding') and kind='perm')",[sid],|r|r.get(0))?;
            let input: bool = tx.query_row("select exists(select 1 from interactions where sid=? and state in ('pending','responding') and kind='ask')",[sid],|r|r.get(0))?;
            let state = if approval {
                "waiting_approval"
            } else if input {
                "waiting_input"
            } else {
                "running"
            };
            tx.execute("update tasks set state=?,updated=? where id=? and state in ('running','waiting_approval','waiting_input')",
                params![state,now(),id])?;
        }
    }
    Ok(task)
}

pub fn snapshot(db: &Db, sid: &str, generation: &str) -> Result<Value, Error> {
    let mut conn = db.conn.lock();
    let tx = conn.transaction()?;
    let task = tx
        .query_row(
            &format!("select {COLUMNS} from tasks where sid=? order by rowid desc limit 1"),
            [sid],
            task_row,
        )
        .optional()?;
    let mut stmt=tx.prepare("select id,kind,spec,task_id from interactions where sid=? and state='pending' order by created")?;
    let interactions=stmt.query_map([sid],|r|{
        let spec:String=r.get(2)?;
        Ok(json!({"id":r.get::<_,String>(0)?,"kind":r.get::<_,String>(1)?,
            "spec":serde_json::from_str::<Value>(&spec).unwrap_or(Value::Null),"task_id":r.get::<_,Option<String>>(3)?}))
    })?.collect::<rusqlite::Result<Vec<_>>>()?;
    drop(stmt);
    let cursor: i64 = tx.query_row("select coalesce(max(seq),0) from events", [], |r| r.get(0))?;
    let mut stmt = tx.prepare(
        "select seq,type,data,ts,task_id from events where sid=? order by seq desc limit 2000",
    )?;
    let mut events = stmt
        .query_map([sid], |r| {
            let data: String = r.get(2)?;
            Ok(
                json!({"seq":r.get::<_,i64>(0)?,"type":r.get::<_,String>(1)?,
            "data":serde_json::from_str::<Value>(&data).unwrap_or(Value::Null),
            "ts":r.get::<_,f64>(3)?,"task_id":r.get::<_,Option<String>>(4)?}),
            )
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(stmt);
    events.reverse();
    let first = events
        .first()
        .and_then(|e| e["seq"].as_i64())
        .unwrap_or(cursor);
    let has_more: bool = tx.query_row(
        "select exists(select 1 from events where sid=? and seq<?)",
        params![sid, first],
        |r| r.get(0),
    )?;
    let mut stmt=tx.prepare("select id,state,reason from deferred_prompts where sid=? and state in ('queued','blocked') order by created")?;
    let deferred = stmt
        .query_map([sid], |r| {
            Ok(
                json!({"id":r.get::<_,String>(0)?,"state":r.get::<_,String>(1)?,
        "reason":r.get::<_,Option<String>>(2)?}),
            )
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(stmt);
    let out = json!({"server_id":meta(&tx,"server_id")?,"data_epoch":meta(&tx,"data_epoch")?,
        "server_generation":generation,"cursor":cursor,"task":task,"interactions":interactions,
        "events":events,"first":first,"more":has_more,"deferred":deferred});
    tx.commit()?;
    Ok(out)
}

pub fn reconcile(db: &Db) -> Result<usize, Error> {
    let mut conn = db.conn.lock();
    let tx = conn.transaction()?;
    let mut stmt = tx.prepare(&format!(
        "select id,sid,dispatch_started from tasks where state in ({ACTIVE}) and state!='unknown'"
    ))?;
    let rows = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, bool>(2)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(stmt);
    for (id, sid, dispatched) in &rows {
        let state = if *dispatched { "unknown" } else { "failed" };
        let reason = if *dispatched {
            "server_restarted_after_dispatch"
        } else {
            "server_restarted_before_dispatch"
        };
        tx.execute(
            "update tasks set state=?,reason=?,updated=? where id=?",
            params![state, reason, now(), id],
        )?;
        append(
            &tx,
            sid,
            Some(id),
            "task",
            &json!({"task_id":id,"state":state,"reason":reason}),
        )?;
        append(
            &tx,
            sid,
            Some(id),
            "turn",
            &json!({"state":"idle","stopReason":state,"reason":reason}),
        )?;
    }
    tx.execute("update interactions set state='expired',reason='runtime_connection_lost',updated=? where state in ('pending','responding')",[now()])?;
    tx.execute(
        "update runtime_instances set state='disconnected',updated=? where state='connected'",
        [now()],
    )?;
    let mut stmt = tx.prepare("select caller,operation_id from resolution_claims")?;
    let claims = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(stmt);
    for (caller, op) in claims {
        let saved: String = tx.query_row(
            "select receipt from operation_receipts where caller=? and operation_id=?",
            params![caller, op],
            |r| r.get(0),
        )?;
        let mut receipt: Value = serde_json::from_str(&saved).unwrap_or(Value::Null);
        receipt["status"] = json!("unknown");
        receipt["code"] = json!("resolution_interrupted");
        receipt["error"] = json!("服务在核对期间重启，请重新检查原进程后发起新的核对操作");
        tx.execute(
            "update operation_receipts set status=502,receipt=? where caller=? and operation_id=?",
            params![receipt.to_string(), caller, op],
        )?;
    }
    tx.execute("delete from resolution_claims", [])?;
    tx.commit()?;
    Ok(rows.len())
}

pub fn record_runtime(db: &Db, sid: &str, pid: u32, generation: &str) -> Result<String, Error> {
    let start = std::process::Command::new("/bin/ps")
        .args(["-o", "lstart=", "-p", &pid.to_string()])
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty());
    let id = uuid::Uuid::new_v4().to_string();
    let conn = db.conn.lock();
    let task = active_id(&conn, sid)?;
    conn.execute(
        "insert into runtime_instances values(?,?,?,?,?,?,?,?,?)",
        params![
            id,
            sid,
            task,
            pid,
            start,
            generation,
            "connected",
            now(),
            now()
        ],
    )?;
    Ok(id)
}
pub fn runtime_exited(db: &Db, id: &str) -> Result<(), Error> {
    db.conn.lock().execute(
        "update runtime_instances set state='exited',updated=? where id=?",
        params![now(), id],
    )?;
    Ok(())
}
pub fn bind_runtime(db: &Db, sid: &str, pid: u32, generation: &str) -> Result<(), Error> {
    let c = db.conn.lock();
    let task = active_id(&c, sid)?;
    c.execute("update runtime_instances set task_id=?,updated=? where sid=? and pid=? and generation=? and state='connected'",
        params![task,now(),sid,pid,generation])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Barrier};

    struct Fixture {
        dir: std::path::PathBuf,
        db: Arc<Db>,
    }
    impl Fixture {
        fn new() -> Self {
            let dir = std::env::temp_dir().join(format!("leoagent-unit-{}", uuid::Uuid::new_v4()));
            std::fs::create_dir(&dir).unwrap();
            let db = Db::try_open(&dir.join("bridge.db")).unwrap();
            db.exec(
                "insert into projects values(1,'test','/isolated-test',0)",
                [],
            );
            for sid in ["s1", "s2"] {
                db.exec(
                    "insert into sessions(id,project_id,created,updated) values(?,1,0,0)",
                    [sid],
                );
            }
            Self { dir, db }
        }
        fn submit(&self, op: &str, sid: &str, payload: Value) -> Result<Admission, Error> {
            submit(
                &self.db,
                "device1",
                op,
                sid,
                None,
                &payload,
                "test-generation",
                4,
            )
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    #[test]
    fn reminders_commit_with_facts_and_repeated_interactions_do_not_duplicate() {
        let f = Fixture::new();
        let accepted = f
            .submit("reminders", "s1", json!({"text":"hello"}))
            .unwrap();
        let id = accepted.receipt["task_id"].as_str().unwrap();
        transition(&f.db, id, "running", None).unwrap();
        let permission = json!({"rid":"reminder-permission","title":"Run fixture","options":[]});
        f.db.insert_event("s1", "perm", &permission);
        f.db.insert_event("s1", "perm", &permission);
        assert_eq!(
            f.db.q1("select count(*) from notes where kind='perm'", [], |r| r
                .get::<_, i64>(0)),
            Some(1)
        );
        {
            let mut conn = f.db.conn.lock();
            let tx = conn.transaction().unwrap();
            project_event(
                &tx,
                "s1",
                "ask",
                &json!({"rid":"rolled-back-question","title":"Question"}),
            )
            .unwrap();
            append(&tx, "s1", Some(id), "task", &json!({"state":"failed"})).unwrap();
            // A failed commit must leave neither an interaction nor its notification behind.
            tx.rollback().unwrap();
        }
        assert_eq!(
            f.db.q1("select count(*) from notes", [], |r| r.get::<_, i64>(0)),
            Some(1)
        );
        assert_eq!(
            f.db.q1(
                "select count(*) from interactions where id='rolled-back-question'",
                [],
                |r| r.get::<_, i64>(0)
            ),
            Some(0)
        );
        assert!(transition(&f.db, id, "unknown", Some("pipe_lost")).unwrap());
        assert!(!transition(&f.db, id, "succeeded", None).unwrap());
        assert_eq!(
            f.db.q1("select count(*) from notes where kind='unknown'", [], |r| r
                .get::<_, i64>(0)),
            Some(1)
        );
        assert_eq!(
            f.db.q1("select count(*) from notes where kind='done'", [], |r| r
                .get::<_, i64>(0)),
            Some(0)
        );
    }

    #[test]
    fn replay_is_durable_and_does_not_duplicate_user_event() {
        let f = Fixture::new();
        let a = f.submit("op1", "s1", json!({"text":"hello"})).unwrap();
        assert_eq!(a.status, 202);
        assert!(a.fresh);
        let db = Db::try_open(&f.dir.join("bridge.db")).unwrap();
        let b = submit(
            &db,
            "device1",
            "op1",
            "s1",
            None,
            &json!({"text":"hello"}),
            "new-generation",
            4,
        )
        .unwrap();
        assert!(!b.fresh);
        assert_eq!(a.receipt, b.receipt);
        assert_eq!(
            db.q1("select count(*) from events where type='user'", [], |r| r
                .get::<_, i64>(
                0
            )),
            Some(1)
        );
        assert_eq!(
            f.submit("op1", "s1", json!({"text":"other"}))
                .err()
                .unwrap()
                .code,
            "idempotency_conflict"
        );
    }

    #[test]
    fn concurrent_sessions_cannot_bypass_project_claim() {
        let f = Fixture::new();
        let barrier = Arc::new(Barrier::new(3));
        let mut threads = vec![];
        for sid in ["s1", "s2"] {
            let db = f.db.clone();
            let b = barrier.clone();
            threads.push(std::thread::spawn(move || {
                b.wait();
                submit(&db, "phone", sid, sid, None, &json!({"text":"x"}), "g", 4)
                    .unwrap()
                    .status
            }));
        }
        barrier.wait();
        let mut statuses: Vec<_> = threads.into_iter().map(|t| t.join().unwrap()).collect();
        statuses.sort();
        assert_eq!(statuses, vec![202, 409]);
    }

    #[test]
    fn caller_scope_and_canonical_fingerprints() {
        let f = Fixture::new();
        let a = f
            .submit(
                "same",
                "s1",
                serde_json::from_str(r#"{"text":"x","options":{"b":1,"a":2}}"#).unwrap(),
            )
            .unwrap();
        assert!(
            !f.submit(
                "same",
                "s1",
                serde_json::from_str(r#"{"options":{"a":2,"b":1},"text":"x"}"#).unwrap()
            )
            .unwrap()
            .fresh
        );
        transition(
            &f.db,
            a.receipt["task_id"].as_str().unwrap(),
            "succeeded",
            None,
        )
        .unwrap();
        assert!(
            submit(
                &f.db,
                "device2",
                "same",
                "s1",
                None,
                &json!({"text":"y"}),
                "g",
                4
            )
            .unwrap()
            .fresh
        );
    }

    #[test]
    fn dispatch_claim_cancel_and_restart_preserve_uncertainty() {
        let f = Fixture::new();
        let a = f.submit("op", "s1", json!({"text":"x"})).unwrap();
        let id = a.receipt["task_id"].as_str().unwrap();
        assert!(!claim_dispatch(&f.db, id).unwrap());
        transition(&f.db, id, "running", None).unwrap();
        assert!(claim_dispatch(&f.db, id).unwrap());
        assert!(!claim_dispatch(&f.db, id).unwrap());
        f.db.insert_event("s1", "perm", &json!({"rid":"r1","options":[]}));
        let snap = snapshot(&f.db, "s1", "g").unwrap();
        assert_eq!(snap["interactions"].as_array().unwrap().len(), 1);
        assert_eq!(snap["task"]["state"], "waiting_approval");
        assert_eq!(
            request_cancel(&f.db, id).unwrap().unwrap()["state"],
            "cancelling"
        );
        reconcile(&f.db).unwrap();
        assert_eq!(get(&f.db, id).unwrap().unwrap()["state"], "unknown");
        assert!(snapshot(&f.db, "s1", "g2").unwrap()["interactions"]
            .as_array()
            .unwrap()
            .is_empty());
        assert_eq!(
            f.submit("new", "s2", json!({"text":"y"})).unwrap().status,
            409
        );
    }

    #[test]
    fn never_dispatched_work_fails_cleanly_and_cancellation_wins_before_start() {
        let f = Fixture::new();
        let a = f.submit("op", "s1", json!({"text":"x"})).unwrap();
        let id = a.receipt["task_id"].as_str().unwrap();
        assert_eq!(
            request_cancel(&f.db, id).unwrap().unwrap()["state"],
            "cancelled"
        );
        assert!(!transition(&f.db, id, "running", None).unwrap());
        let b = f.submit("next", "s1", json!({"text":"y"})).unwrap();
        reconcile(&f.db).unwrap();
        assert_eq!(
            get(&f.db, b.receipt["task_id"].as_str().unwrap())
                .unwrap()
                .unwrap()["state"],
            "failed"
        );
    }

    #[test]
    fn epoch_and_schema_version_fail_closed() {
        let f = Fixture::new();
        assert_eq!(
            submit(
                &f.db,
                "phone",
                "op",
                "s1",
                Some("stale"),
                &json!({"text":"x"}),
                "g",
                4
            )
            .err()
            .unwrap()
            .code,
            "data_epoch_changed"
        );
        f.db.exec("insert into schema_migrations values(99,0)", []);
        assert!(Db::try_open(&f.dir.join("bridge.db")).is_err());
    }

    #[test]
    fn interaction_decisions_are_claimed_once_and_invalid_options_do_not_consume() {
        let f = Fixture::new();
        let a = f.submit("prompt", "s1", json!({"text":"x"})).unwrap();
        transition(
            &f.db,
            a.receipt["task_id"].as_str().unwrap(),
            "running",
            None,
        )
        .unwrap();
        f.db.insert_event(
            "s1",
            "perm",
            &json!({"rid":"request","options":[{"id":"allow"}]}),
        );
        let invalid = admit_response(
            &f.db,
            "device1",
            "invalid",
            "request",
            &json!({"option":"invalid"}),
        )
        .unwrap();
        assert_eq!(invalid.status, 400);
        assert!(!invalid.fresh);
        assert_eq!(invalid.receipt["code"], "invalid_permission_option");
        assert_eq!(
            operation(&f.db, "device1", "invalid").unwrap().unwrap().1,
            invalid.receipt
        );
        let body = json!({"option":"allow"});
        let accepted = admit_response(&f.db, "device1", "response", "request", &body).unwrap();
        assert!(accepted.fresh);
        assert!(
            !admit_response(&f.db, "device1", "response", "request", &body)
                .unwrap()
                .fresh
        );
        assert_eq!(
            admit_response(&f.db, "device2", "other", "request", &body)
                .unwrap()
                .receipt["code"],
            "interaction_already_handled"
        );
        f.db.insert_event(
            "s1",
            "perm_done",
            &json!({"rid":"request","option":"allow"}),
        );
        let receipt = finish_response(&f.db, "device1", "response", "request", true).unwrap();
        assert_eq!(receipt["delivery"], "written");
        // Rejected operations remain rejected after the interaction changes state.
        let replay = admit_response(
            &f.db,
            "device1",
            "invalid",
            "request",
            &json!({"option":"invalid"}),
        )
        .unwrap();
        assert_eq!(replay.receipt, invalid.receipt);
        assert_eq!(replay.status, 400);
        assert_eq!(
            snapshot(&f.db, "s1", "g").unwrap()["task"]["state"],
            "running"
        );
        assert_eq!(
            admit_response(&f.db, "device1", "response", "request", &body)
                .unwrap()
                .status,
            200
        );
    }

    #[test]
    fn cancellation_timeout_cannot_be_overwritten_by_late_success() {
        let f = Fixture::new();
        let a = f.submit("prompt", "s1", json!({"text":"x"})).unwrap();
        let id = a.receipt["task_id"].as_str().unwrap();
        transition(&f.db, id, "running", None).unwrap();
        claim_dispatch(&f.db, id).unwrap();
        request_cancel(&f.db, id).unwrap();
        assert!(cancel_timeout(&f.db, id).unwrap());
        assert!(!transition(&f.db, id, "succeeded", None).unwrap());
        assert_eq!(get(&f.db, id).unwrap().unwrap()["state"], "unknown");
    }

    #[test]
    fn deferred_answer_survives_restart_and_is_admitted_once() {
        let f = Fixture::new();
        let parent = f
            .submit("parent", "s1", json!({"text":"question"}))
            .unwrap();
        let parent_id = parent.receipt["task_id"].as_str().unwrap();
        transition(&f.db, parent_id, "running", None).unwrap();
        f.db.insert_event("s1", "ask", &json!({"rid":"question-one","questions":[]}));
        defer_prompt(&f.db, "s1", "question-one", "answer").unwrap();
        defer_prompt(&f.db, "s1", "question-one", "answer").unwrap();
        assert!(deferred_candidates(&f.db).unwrap().is_empty());
        transition(&f.db, parent_id, "succeeded", None).unwrap();
        let reopened = Db::try_open(&f.dir.join("bridge.db")).unwrap();
        let candidates = deferred_candidates(&reopened).unwrap();
        assert_eq!(candidates.len(), 1);
        let (id, sid, text) = &candidates[0];
        let followup = submit_deferred(&reopened, id, sid, text, "new-generation", 4).unwrap();
        assert!(followup.fresh);
        assert!(deferred_candidates(&reopened).unwrap().is_empty());
        assert_eq!(
            submit_deferred(&reopened, id, sid, text, "new-generation", 4)
                .err()
                .unwrap()
                .code,
            "deferred_not_ready"
        );
        assert_eq!(
            reopened.q1("select count(*) from tasks", [], |r| r.get::<_, i64>(0)),
            Some(2)
        );
    }

    #[test]
    fn deferred_answer_does_not_replay_after_unknown_parent() {
        let f = Fixture::new();
        let parent = f
            .submit("parent", "s1", json!({"text":"question"}))
            .unwrap();
        let id = parent.receipt["task_id"].as_str().unwrap();
        transition(&f.db, id, "running", None).unwrap();
        claim_dispatch(&f.db, id).unwrap();
        f.db.insert_event(
            "s1",
            "ask",
            &json!({"rid":"question-unknown","questions":[]}),
        );
        defer_prompt(&f.db, "s1", "question-unknown", "answer").unwrap();
        reconcile(&f.db).unwrap();
        assert!(deferred_candidates(&f.db).unwrap().is_empty());
        assert_eq!(
            snapshot(&f.db, "s1", "new").unwrap()["deferred"][0]["state"],
            "blocked"
        );
    }

    #[test]
    fn upgrading_a_wal_database_backs_up_committed_history() {
        let dir = std::env::temp_dir().join(format!("leoagent-migration-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&dir).unwrap();
        let path = dir.join("bridge.db");
        let c = Connection::open(&path).unwrap();
        c.execute_batch("pragma journal_mode=wal;
            create table sessions(id text primary key, project_id int, acp_sid text,title text,model text,effort text,mode text,created real,updated real);
            insert into sessions(id,title) values('old','preserved');").unwrap();
        let db = Db::try_open(&path).unwrap();
        assert_eq!(
            db.q1("select title from sessions where id='old'", [], |r| r
                .get::<_, String>(0)),
            Some("preserved".into())
        );
        let backup = std::fs::read_dir(&dir)
            .unwrap()
            .filter_map(Result::ok)
            .map(|e| e.path())
            .find(|p| {
                p.file_name()
                    .unwrap()
                    .to_string_lossy()
                    .contains(&format!("pre-v{}", crate::security::SCHEMA_VERSION))
            })
            .unwrap();
        let saved = Connection::open(backup).unwrap();
        assert_eq!(
            saved
                .query_row("select title from sessions where id='old'", [], |r| r
                    .get::<_, String>(0))
                .unwrap(),
            "preserved"
        );
        drop(saved);
        drop(db);
        drop(c);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
