//! Consistent backups and explicit offline restoration. Never copy a live WAL database.
use crate::db::Db;
use rusqlite::{Connection, DatabaseName, OpenFlags};
use serde_json::{json, Value};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

fn reserve(path: &Path) -> Result<(), String> {
    std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .map(|_| ())
        .map_err(|e| e.to_string())
}
pub fn backup(db: &Db, base: &Path) -> Result<PathBuf, String> {
    let dir = base.join("backups");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join(format!("bridge-{}.db", uuid::Uuid::new_v4()));
    reserve(&path)?;
    if let Err(e) = db.conn.lock().backup(DatabaseName::Main, &path, None) {
        let _ = std::fs::remove_file(&path);
        return Err(e.to_string());
    }
    std::fs::File::open(&path)
        .and_then(|f| f.sync_all())
        .map_err(|e| e.to_string())?;
    Ok(path)
}
pub fn prepare_restore(source: &Path, staged: &Path) -> Result<Value, String> {
    let source = Connection::open_with_flags(source, OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(|e| e.to_string())?;
    let check: String = source
        .query_row("pragma quick_check", [], |r| r.get(0))
        .map_err(|e| e.to_string())?;
    if check != "ok" {
        return Err("备份数据库完整性检查失败".into());
    }
    reserve(staged)?;
    source
        .backup(DatabaseName::Main, staged, None)
        .map_err(|e| e.to_string())?;
    let db = Db::try_open(staged).map_err(|e| e.to_string())?;
    crate::tasks::reconcile(&db).map_err(|e| e.message)?;
    let epoch = uuid::Uuid::new_v4().to_string();
    {
        let mut c = db.conn.lock();
        let tx = c.transaction().map_err(|e| e.to_string())?;
        tx.execute(
            "update kv set v=? where k='data_epoch'",
            [json!(epoch).to_string()],
        )
        .map_err(|e| e.to_string())?;
        tx.execute(
            "update devices set revoked=coalesce(revoked,?)",
            [crate::db::now()],
        )
        .map_err(|e| e.to_string())?;
        tx.execute("delete from pairings", [])
            .map_err(|e| e.to_string())?;
        tx.execute("update deferred_prompts set state='blocked',reason='database_restored' where state='queued'",[]).map_err(|e|e.to_string())?;
        tx.commit().map_err(|e| e.to_string())?;
        c.execute_batch("pragma wal_checkpoint(truncate)")
            .map_err(|e| e.to_string())?;
    }
    let identity = crate::tasks::identity(&db).map_err(|e| e.message)?;
    drop(db);
    std::fs::File::open(staged)
        .and_then(|f| f.sync_all())
        .map_err(|e| e.to_string())?;
    Ok(identity)
}
pub fn restore(source: &Path, base: &Path) -> Result<Value, String> {
    let _lock = crate::instance::InstanceLock::acquire(base).map_err(|e| e.to_string())?;
    let live = base.join("bridge.db");
    if std::fs::canonicalize(source).ok() == std::fs::canonicalize(&live).ok() {
        return Err("恢复源必须是单独的备份文件".into());
    }
    let staged = base.join(format!("restore-{}.db", uuid::Uuid::new_v4()));
    let result = (|| {
        let identity = prepare_restore(source, &staged)?;
        let previous = if live.exists() {
            let current = Db::try_open(&live).map_err(|e| e.to_string())?;
            let saved = backup(&current, base)?;
            current
                .conn
                .lock()
                .execute_batch("pragma wal_checkpoint(truncate)")
                .map_err(|e| e.to_string())?;
            drop(current);
            Some(saved)
        } else {
            None
        };
        // Disable the legacy bypass before the restored database can become live.
        let mut cfg = crate::config::get().clone();
        cfg.legacy_token_enabled = false;
        cfg.token = crate::config::gen_token();
        crate::config::write(crate::config::path(), &cfg)?;
        for suffix in ["-wal", "-shm"] {
            let path = PathBuf::from(format!("{}{suffix}", live.display()));
            if path.exists() {
                std::fs::remove_file(path).map_err(|e| e.to_string())?;
            }
        }
        std::fs::rename(&staged, &live).map_err(|e| e.to_string())?;
        std::fs::File::open(base)
            .and_then(|f| f.sync_all())
            .map_err(|e| e.to_string())?;
        Ok(
            json!({"ok":true,"identity":identity,"previous_backup":previous,
            "next":"启动服务并重新配对所有手机；核对备份点之后的文件和命令副作用"}),
        )
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&staged);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn restore_rotates_epoch_and_does_not_resurrect_trust_or_pending_work() {
        let folder =
            std::env::temp_dir().join(format!("leoagent-restore-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&folder).unwrap();
        let path = folder.join("source.db");
        let db = Db::try_open(&path).unwrap();
        let before = crate::tasks::identity(&db).unwrap();
        db.conn
            .lock()
            .execute(
                "insert into devices values('device','phone','hash',?,0,null)",
                [before["data_epoch"].as_str().unwrap()],
            )
            .unwrap();
        db.conn.lock().execute("insert into deferred_prompts values('answer','session',null,'text','queued',null,null,0)",[]).unwrap();
        let backup_path = backup(&db, &folder).unwrap();
        let stage = folder.join("restored.db");
        let after = prepare_restore(&backup_path, &stage).unwrap();
        assert_ne!(before["data_epoch"], after["data_epoch"]);
        assert_eq!(before["server_id"], after["server_id"]);
        let restored = Db::try_open(&stage).unwrap();
        assert_eq!(
            restored.q1(
                "select count(*) from devices where revoked is null",
                [],
                |r| r.get::<_, i64>(0)
            ),
            Some(0)
        );
        assert_eq!(
            restored.q1(
                "select state from deferred_prompts where id='answer'",
                [],
                |r| r.get::<_, String>(0)
            ),
            Some("blocked".into())
        );
        drop(restored);
        drop(db);
        std::fs::remove_dir_all(folder).unwrap();
    }
}
