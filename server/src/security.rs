//! Local computer confirmation and revocable device credentials.
//! The control socket is private to the OS user; it is never exposed through HTTP.
use crate::db::{now, Db};
use crate::tasks::Error;
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::io::{BufRead, BufReader, Read, Write};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

pub const SCHEMA_VERSION: i64 = 3;
const PAIR_TTL: f64 = 300.0;
static PAIR_BUDGET: parking_lot::Mutex<(f64, u32, u32)> = parking_lot::Mutex::new((0.0, 0, 0));
fn pairing_limit(poll: bool) -> Result<(), Error> {
    let mut budget = PAIR_BUDGET.lock();
    let time = now();
    if time - budget.0 >= 60.0 {
        *budget = (time, 0, 0);
    }
    let (count, max) = if poll {
        (&mut budget.2, 1200)
    } else {
        (&mut budget.1, 120)
    };
    if *count >= max {
        return Err(error(429, "pairing_rate_limit", "配对请求过多，请稍后重试"));
    }
    *count += 1;
    Ok(())
}
fn secret() -> String {
    format!(
        "{}{}",
        uuid::Uuid::new_v4().simple(),
        uuid::Uuid::new_v4().simple()
    )
}
pub fn digest(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}
fn error(status: u16, code: &str, text: &str) -> Error {
    Error::new(status, code, text)
}
fn socket_path() -> std::path::PathBuf {
    let data = crate::config::data_dir();
    let data = std::fs::canonicalize(&data).unwrap_or(data);
    let key = digest(&data.to_string_lossy());
    std::path::PathBuf::from(format!("/tmp/leoagent-{}", unsafe { libc::geteuid() }))
        .join(format!("{}.sock", &key[..16]))
}
pub fn migrate(conn: &mut Connection) -> rusqlite::Result<()> {
    let tx = conn.transaction()?;
    tx.execute_batch(
        "create table if not exists devices(
            id text primary key, name text not null, credential_hash text not null unique,
            epoch text not null, created real not null, revoked real);
         create table if not exists pairings(
            id text primary key, invitation_hash text not null unique,
            poll_hash text, name text, verification text,
            state text not null, expires real not null);
         create table if not exists deferred_prompts(
            id text primary key, sid text not null, parent_task_id text,
            text text not null, state text not null, task_id text,
            reason text, created real not null);",
    )?;
    tx.execute(
        "insert or ignore into schema_migrations(version,applied) values(?,?)",
        params![SCHEMA_VERSION, now()],
    )?;
    tx.commit()
}
pub fn authenticate(db: &Db, token: &str) -> Result<Option<String>, Error> {
    if token.is_empty() {
        return Ok(None);
    }
    let c = db.conn.lock();
    Ok(c.query_row(
        "select id from devices where credential_hash=? and revoked is null
         and epoch=(select json_extract(v,'$') from kv where k='data_epoch')",
        [digest(token)],
        |r| r.get(0),
    )
    .optional()?)
}
pub fn active(db: &Db, caller: &str) -> bool {
    if caller == "legacy" {
        return crate::config::get().legacy_token_enabled;
    }
    db.conn
        .lock()
        .query_row(
            "select exists(select 1 from devices where id=? and revoked is null
             and epoch=(select json_extract(v,'$') from kv where k='data_epoch'))",
            [caller],
            |r| r.get(0),
        )
        .unwrap_or(false)
}
pub fn invitation(db: &Db) -> Result<Value, Error> {
    let id = uuid::Uuid::new_v4().to_string();
    let code = secret();
    let expires = now() + PAIR_TTL;
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    tx.execute("delete from pairings where expires < ?", [now()])?;
    // Bound pending storage even when local clients repeatedly create invitations.
    let count: i64 = tx.query_row("select count(*) from pairings", [], |r| r.get(0))?;
    if count >= 16 {
        return Err(error(429, "pairing_limit", "待配对请求过多，请等待过期"));
    }
    tx.execute(
        "insert into pairings(id,invitation_hash,state,expires) values(?,?,'invited',?)",
        params![id, digest(&code), expires],
    )?;
    tx.commit()?;
    Ok(json!({"id":id,"code":code,"expires_at":expires}))
}
pub fn claim(db: &Db, body: &Value) -> Result<Value, Error> {
    pairing_limit(false)?;
    let code = body["code"].as_str().unwrap_or("");
    let name = body["device_name"].as_str().unwrap_or("").trim();
    if code.len() != 64
        || name.is_empty()
        || name.chars().count() > 80
        || name.chars().any(char::is_control)
    {
        return Err(error(400, "invalid_pairing", "配对码或设备名称无效"));
    }
    let poll = secret();
    // A comparison code, not an authentication secret. Both screens must match.
    let verification = format!("{:06}", uuid::Uuid::new_v4().as_u128() % 1_000_000);
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    let id: Option<String> = tx
        .query_row(
            "select id from pairings where invitation_hash=? and state='invited' and expires>?",
            params![digest(code), now()],
            |r| r.get(0),
        )
        .optional()?;
    let id = id.ok_or_else(|| {
        error(
            410,
            "pairing_unavailable",
            "配对码已使用或过期，请在电脑上重新配对",
        )
    })?;
    tx.execute(
        "update pairings set state='pending',poll_hash=?,name=?,verification=? where id=?",
        params![digest(&poll), name, verification, id],
    )?;
    tx.commit()?;
    Ok(json!({"pairing_id":id,"poll_secret":poll,"verification":verification,"state":"pending"}))
}
pub fn inspect(db: &Db, id: &str) -> Result<Value, Error> {
    db.conn
        .lock()
        .query_row(
            "select name,verification,state,expires from pairings where id=?",
            [id],
            |r| {
                Ok(json!({"id":id,"device_name":r.get::<_,Option<String>>(0)?,
            "verification":r.get::<_,Option<String>>(1)?,"state":r.get::<_,String>(2)?,
            "expires_at":r.get::<_,f64>(3)?}))
            },
        )
        .optional()?
        .ok_or_else(|| error(404, "pairing_not_found", "配对请求不存在"))
}
pub fn decide(db: &Db, id: &str, approve: bool) -> Result<Value, Error> {
    let changed = db.conn.lock().execute(
        "update pairings set state=? where id=? and state='pending' and expires>?",
        params![if approve { "approved" } else { "denied" }, id, now()],
    )?;
    if changed != 1 {
        return Err(error(409, "pairing_not_pending", "配对已过期或已处理"));
    }
    Ok(json!({"ok":true}))
}
pub fn poll(db: &Db, body: &Value) -> Result<Value, Error> {
    pairing_limit(true)?;
    let id = body["pairing_id"].as_str().unwrap_or("");
    let poll = body["poll_secret"].as_str().unwrap_or("");
    if poll.len() != 64 {
        return Err(error(401, "invalid_pairing_secret", "无效的配对请求"));
    }
    let mut c = db.conn.lock();
    let tx = c.transaction()?;
    let row: Option<(String, String, f64)> = tx
        .query_row(
            "select state,name,expires from pairings where id=? and poll_hash=?",
            params![id, digest(poll)],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?;
    let (state, name, expires) =
        row.ok_or_else(|| error(401, "invalid_pairing_secret", "无效的配对请求"))?;
    if expires <= now() || matches!(state.as_str(), "denied" | "consumed") {
        return Err(error(410, "pairing_unavailable", "配对已结束，请重新配对"));
    }
    if state != "approved" {
        return Ok(json!({"state":"pending","expires_at":expires}));
    }
    let token = format!("ld_{}", secret());
    let device = uuid::Uuid::new_v4().to_string();
    let epoch: String = tx.query_row(
        "select json_extract(v,'$') from kv where k='data_epoch'",
        [],
        |r| r.get(0),
    )?;
    tx.execute(
        "insert into devices(id,name,credential_hash,epoch,created) values(?,?,?,?,?)",
        params![device, name, digest(&token), epoch, now()],
    )?;
    tx.execute("update pairings set state='consumed' where id=?", [id])?;
    let server: String = tx.query_row(
        "select json_extract(v,'$') from kv where k='server_id'",
        [],
        |r| r.get(0),
    )?;
    tx.commit()?;
    // Credentials are delivered once. Lost delivery requires a new locally confirmed pairing.
    Ok(
        json!({"state":"paired","device_id":device,"token":token,"server_id":server,"data_epoch":epoch}),
    )
}
pub fn devices(db: &Db) -> Result<Value, Error> {
    let c = db.conn.lock();
    let mut stmt = c.prepare("select id,name,created,revoked from devices order by created")?;
    let rows = stmt.query_map([], |r| Ok(json!({"id":r.get::<_,String>(0)?,
        "name":r.get::<_,String>(1)?,"created_at":r.get::<_,f64>(2)?,"revoked_at":r.get::<_,Option<f64>>(3)?})))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(json!(rows))
}
pub fn revoke(db: &Db, id: &str) -> Result<Value, Error> {
    let changed = db.conn.lock().execute(
        "update devices set revoked=coalesce(revoked,?) where id=?",
        params![now(), id],
    )?;
    if changed == 0 {
        return Err(error(404, "device_not_found", "设备不存在"));
    }
    db.cond.notify_all();
    Ok(json!({"ok":true,"device_id":id}))
}

fn local_command(db: &Db, body: &Value) -> Result<Value, Error> {
    let id = body["id"].as_str().unwrap_or("");
    match body["command"].as_str().unwrap_or("") {
        "pair" => invitation(db),
        "pair-status" => inspect(db, id),
        "pair-approve" => decide(db, id, true),
        "pair-deny" => decide(db, id, false),
        "devices" => devices(db),
        "backup" => crate::maintenance::backup(db, &crate::config::data_dir())
            .map(|path| json!({"backup":path}))
            .map_err(|e| error(500, "backup_failed", &e)),
        "revoke" => revoke(db, id),
        _ => Err(error(400, "unknown_command", "未知本地管理命令")),
    }
}
pub fn start_control(db: Arc<Db>, _base: &Path) -> Result<(), String> {
    let path = socket_path();
    use std::os::unix::fs::{DirBuilderExt, MetadataExt};
    let parent = path.parent().ok_or("缺少本地管理目录")?;
    match std::fs::DirBuilder::new().mode(0o700).create(parent) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(e) => return Err(e.to_string()),
    }
    let metadata = std::fs::symlink_metadata(parent).map_err(|e| e.to_string())?;
    if !metadata.is_dir()
        || metadata.uid() != unsafe { libc::geteuid() }
        || metadata.mode() & 0o077 != 0
    {
        return Err("本地管理目录必须由当前用户拥有，且仅该用户可访问".into());
    }
    if path.exists() {
        std::fs::remove_file(&path).map_err(|e| e.to_string())?;
    }
    let listener = UnixListener::bind(&path).map_err(|e| e.to_string())?;
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))
        .map_err(|e| e.to_string())?;
    std::thread::spawn(move || {
        for mut stream in listener.incoming().flatten() {
            let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
            let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
            let result = (|| -> Result<Value, Error> {
                let mut text = String::new();
                BufReader::new((&stream).take(8193))
                    .read_line(&mut text)
                    .map_err(|_| error(400, "bad_command", "无法读取本地请求"))?;
                if text.len() > 8192 {
                    return Err(error(413, "bad_command", "本地请求过长"));
                }
                let body: Value = serde_json::from_str(&text)
                    .map_err(|_| error(400, "bad_command", "请求必须是 JSON"))?;
                local_command(&db, &body)
            })();
            let value = match result {
                Ok(v) => v,
                Err(e) => json!({"error":e.message,"code":e.code}),
            };
            let _ = writeln!(stream, "{value}");
        }
    });
    Ok(())
}
pub fn control(body: &Value) -> Result<Value, String> {
    let mut stream = UnixStream::connect(socket_path())
        .map_err(|e| format!("无法连接本机管理入口，请先启动同一配置的电脑服务：{e}"))?;
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| e.to_string())?;
    writeln!(stream, "{body}").map_err(|e| e.to_string())?;
    let mut line = String::new();
    BufReader::new(stream)
        .read_line(&mut line)
        .map_err(|e| e.to_string())?;
    let result: Value = serde_json::from_str(&line).map_err(|e| e.to_string())?;
    if let Some(e) = result["error"].as_str() {
        return Err(e.into());
    }
    Ok(result)
}
pub fn pair_cli(svg: Option<&str>) -> Result<(), String> {
    let url = crate::config::get().public_url.trim_end_matches('/');
    if !url.starts_with("https://") && !url.starts_with("http://") {
        return Err("请先配置 public_url 为手机可访问的服务器地址".into());
    }
    let invite = control(&json!({"command":"pair"}))?;
    let id = invite["id"].as_str().ok_or("缺少配对 ID")?;
    let uri = format!(
        "leoagent://pair?url={}&code={}",
        urlencoding::encode(url),
        invite["code"].as_str().ok_or("缺少配对码")?
    );
    let code = qrcode::QrCode::new(uri.as_bytes()).map_err(|e| e.to_string())?;
    println!(
        "{}\n\n{uri}\n\n配对链接 5 分钟有效。扫描后请核对两端验证码，在此确认。",
        code.render::<qrcode::render::unicode::Dense1x2>().build()
    );
    if let Some(path) = svg {
        use std::fs::OpenOptions;
        use std::os::unix::fs::OpenOptionsExt;
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(path)
            .map_err(|e| e.to_string())?;
        file.write_all(
            code.render::<qrcode::render::svg::Color>()
                .min_dimensions(320, 320)
                .build()
                .as_bytes(),
        )
        .map_err(|e| e.to_string())?;
    }
    while now() < invite["expires_at"].as_f64().unwrap_or(0.0) {
        let status = control(&json!({"command":"pair-status","id":id}))?;
        if status["state"] == "pending" {
            println!(
                "设备：{}\n验证码：{}\n仅在手机显示相同验证码时输入 yes 确认：",
                status["device_name"].as_str().unwrap_or(""),
                status["verification"].as_str().unwrap_or("")
            );
            let mut answer = String::new();
            std::io::stdin()
                .read_line(&mut answer)
                .map_err(|e| e.to_string())?;
            let approve = answer.trim() == "yes";
            control(&json!({"command":if approve{"pair-approve"}else{"pair-deny"},"id":id}))?;
            println!(
                "{}",
                if approve {
                    "已确认，手机将保存设备凭据。"
                } else {
                    "已拒绝配对。"
                }
            );
            return Ok(());
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    Err("配对已过期，请重试".into())
}
