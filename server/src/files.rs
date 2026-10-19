use crate::db::{home, tilde};
use hmac::{Hmac, Mac};
use serde_json::{json, Value};
use sha2::Sha256;
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const CAP: u64 = 200 * 1024 * 1024;
static SKIP: &[&str] = &[".git","node_modules",".venv","venv","__pycache__",".next","dist","build",".gradle",".idea","target",".cache"];
static TEXT: &[&str] = &[".py",".kt",".java",".js",".ts",".tsx",".jsx",".json",".yaml",".yml",".toml",".sh",".go",".rs",".c",".h",".cpp",".swift",".sql",".css",".txt",".log",".ini",".cfg",".xml",".gradle",".kts",".rb",".php",".vue",".csv",".env",".lock",".conf"];

pub fn ptok(token: &str, sid: &str) -> String {
    let mut m = Hmac::<Sha256>::new_from_slice(token.as_bytes()).unwrap();
    m.update(format!("pv:{sid}").as_bytes());
    hex::encode(m.finalize().into_bytes())[..24].to_string()
}
pub fn roots(sess_path: &str, project_paths: &[String]) -> Vec<PathBuf> {
    let mut r = vec![]; let h = home();
    for p in std::iter::once(sess_path.to_string()).chain(project_paths.iter().cloned()) {
        let Ok(rp) = fs::canonicalize(crate::db::expand_tilde(&p)) else { continue };
        let rs = rp.to_string_lossy();
        if rs.starts_with(&(h.to_string_lossy().to_string() + "/")) && rp != h && rp.is_dir() && !r.contains(&rp) { r.push(rp) }
    }
    r
}
pub fn resolve(path: &str, sess_path: &str, r: &[PathBuf]) -> Result<PathBuf, &'static str> {
    if path.is_empty() { return Err("empty") }
    let mut path = path.to_string();
    if let Some(x) = path.strip_prefix("file://") { path = x.to_string() }
    let path = if path.starts_with('~') { crate::db::expand_tilde(&path) }
        else if Path::new(&path).is_absolute() { PathBuf::from(&path) }
        else { PathBuf::from(sess_path).join(&path) };
    let rp = fs::canonicalize(&path).unwrap_or_else(|_| {
        // mirror Python realpath for non-existent: resolve parents
        let mut o = PathBuf::new();
        for c in path.components() { match c { std::path::Component::ParentDir => { o.pop(); } std::path::Component::CurDir => {} x => o.push(x) } }
        o
    });
    for part in rp.components() {
        let pl = part.as_os_str().to_string_lossy().to_lowercase();
        if matches!(pl.as_str(), ".git"|".ssh"|".token"|".netrc"|"id_rsa"|"id_ed25519") || pl.starts_with(".env") || pl.ends_with(".pem") || pl.ends_with(".key") || pl.ends_with(".p12") || pl.ends_with(".keystore") || pl.ends_with(".jks") { return Err("sensitive") }
    }
    for base in r {
        if &rp == base || rp.starts_with(base) { return Ok(rp) }
    }
    Err("path outside project")
}
pub fn mime(p: &Path) -> String {
    let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    let ext_dot = format!(".{ext}");
    let mut m = mime_guess::from_path(p).first().map(|m| m.essence_str().to_string());
    if TEXT.contains(&ext_dot.as_str()) && (m.as_ref().map(|x| !x.starts_with("text/")).unwrap_or(true)) && !matches!(ext.as_str(), "json"|"js"|"svg"|"css") {
        m = Some("text/plain".into());
    }
    m.unwrap_or_else(|| "application/octet-stream".into())
}
pub fn kind(p: &Path) -> &'static str {
    let ext = p.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
    let m = mime(p);
    if matches!(ext.as_str(), "md"|"markdown") { "md" }
    else if matches!(ext.as_str(), "html"|"htm") { "html" }
    else if ext == "pdf" { "pdf" }
    else if m.starts_with("image/") { "image" }
    else if TEXT.contains(&format!(".{ext}").as_str()) || m.starts_with("text/") { "code" }
    else { "other" }
}
pub fn info(p: &Path, base: &Path) -> Value {
    let st = fs::metadata(p).ok();
    let is_dir = p.is_dir();
    let size = st.as_ref().map(|s| s.len()).unwrap_or(0);
    let mtime = st.and_then(|s| s.modified().ok()).and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs() as i64).unwrap_or(0);
    let rel = pathdiff_rel(p, base);
    let abs = p.to_string_lossy().into_owned();
    let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
    let mime_s = if is_dir { String::new() } else { mime(p) };
    let k = if is_dir { "dir" } else { kind(p) };
    json!({"path": tilde(&abs), "abs": abs, "rel": rel, "name": name, "size": size, "mtime": mtime, "dir": is_dir, "kind": k, "mime": mime_s})
}
fn pathdiff_rel(p: &Path, base: &Path) -> String {
    match p.strip_prefix(base) {
        Ok(x) if x.as_os_str().is_empty() => ".".into(),
        Ok(x) => x.to_string_lossy().into_owned(),
        Err(_) => p.to_string_lossy().into_owned(),
    }
}
pub fn listing(d: &Path, recent: bool, limit: usize) -> Vec<Value> {
    if !recent {
        let mut out = vec![];
        let Ok(rd) = fs::read_dir(d) else { return out };
        let mut names: Vec<_> = rd.filter_map(|e| e.ok()).map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        names.sort_by_key(|a| a.to_lowercase());
        for n in names {
            if SKIP.contains(&n.as_str()) || n == ".DS_Store" { continue }
            let fp = d.join(&n);
            if let Ok(_) = fs::metadata(&fp) { out.push(info(&fp, d)) }
        }
        out.sort_by(|a, b| {
            let da = a["dir"].as_bool().unwrap_or(false); let db = b["dir"].as_bool().unwrap_or(false);
            (!da).cmp(&(!db)).then(b["mtime"].as_i64().unwrap_or(0).cmp(&a["mtime"].as_i64().unwrap_or(0)))
        });
        out.truncate(limit); return out;
    }
    let mut L = vec![]; let mut n = 0usize; let t0 = SystemTime::now();
    let mut stack = vec![d.to_path_buf()];
    while let Some(root) = stack.pop() {
        let Ok(rd) = fs::read_dir(&root) else { continue };
        for e in rd.filter_map(|x| x.ok()) {
            let name = e.file_name().to_string_lossy().into_owned();
            let fp = e.path();
            if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                if !SKIP.contains(&name.as_str()) && !name.starts_with('.') { stack.push(fp) }
                continue;
            }
            if name.starts_with('.') { continue }
            if fp.symlink_metadata().map(|m| m.file_type().is_symlink()).unwrap_or(false) { continue }
            if let Ok(st) = fs::metadata(&fp) {
                if let Ok(mt) = st.modified() { L.push((mt, fp)); }
            }
            n += 1;
            if n > 30000 || t0.elapsed().map(|e| e.as_secs() > 3).unwrap_or(true) { break }
        }
        if n > 30000 || t0.elapsed().map(|e| e.as_secs() > 3).unwrap_or(true) { break }
    }
    L.sort_by(|a, b| b.0.cmp(&a.0));
    L.into_iter().take(limit).map(|(_, fp)| info(&fp, d)).collect()
}

pub struct FileBytes { pub code: u16, pub headers: Vec<(String, String)>, pub body: Vec<u8> }
pub fn send_file(p: &Path, range: Option<&str>, head: bool) -> Result<FileBytes, (u16, Value)> {
    let st = fs::metadata(p).map_err(|_| (404, json!({"error":"not found"})))?;
    if !p.is_file() { return Ok(FileBytes { code: 400, headers: vec![("Content-Length".into(),"0".into())], body: vec![] }) }
    if st.len() > CAP { return Err((413, json!({"error":"file too large","size": st.len()}))) }
    let size = st.len(); let mut a = 0u64; let mut b = size.saturating_sub(1); let mut code = 200u16;
    if let Some(rg) = range {
        if let Some(rest) = rg.strip_prefix("bytes=") {
            let part = rest.split(',').next().unwrap_or("");
            let mut ok = true;
            if let Some((x, y)) = part.split_once('-') {
                if !x.is_empty() {
                    a = x.parse().unwrap_or(0);
                    b = if y.is_empty() { size.saturating_sub(1) } else { y.parse().unwrap_or(size.saturating_sub(1)) };
                } else {
                    let n: u64 = y.parse().unwrap_or(0); a = size.saturating_sub(n); b = size.saturating_sub(1);
                }
                a = a.min(size.saturating_sub(1)); b = b.min(size.saturating_sub(1));
                if a > b { ok = false }
                else { code = 206 }
            } else { ok = false }
            if !ok {
                return Ok(FileBytes { code: 416, headers: vec![("Content-Range".into(), format!("bytes */{size}")), ("Content-Length".into(),"0".into())], body: vec![] });
            }
        }
    }
    let mut m = mime(p);
    if m.starts_with("text/") || m == "application/javascript" || m == "application/json" || m == "image/svg+xml" { m += "; charset=utf-8" }
    let mtime = st.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0);
    let lm = httpdate(mtime);
    let mut hs = vec![
        ("Content-Type".into(), m), ("Content-Length".into(), (if size==0 {0} else {b-a+1}).to_string()),
        ("Accept-Ranges".into(), "bytes".into()), ("Cache-Control".into(), "no-cache".into()),
        ("X-Content-Type-Options".into(), "nosniff".into()), ("Last-Modified".into(), lm),
    ];
    if code == 206 { hs.push(("Content-Range".into(), format!("bytes {a}-{b}/{size}"))); }
    let body = if head || size == 0 { vec![] } else {
        let data = fs::read(p).unwrap_or_default();
        data.get(a as usize ..= b as usize).unwrap_or(&[]).to_vec()
    };
    Ok(FileBytes { code, headers: hs, body })
}
fn httpdate(secs: u64) -> String {
    chrono::DateTime::from_timestamp(secs as i64, 0).map(|t| t.format("%a, %d %b %Y %H:%M:%S GMT").to_string()).unwrap_or_default()
}
