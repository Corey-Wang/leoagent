//! Small app-side endpoints (no trading / stock features):
//!   GET  /api/app/version  -> <data_dir>/app/version.json  (written by deploy/publish-apk.sh)
//!   GET  /api/app/apk      -> <data_dir>/app/leoagent.apk
//!   POST /api/app/logs     -> zip upload from the phone, stored in <data_dir>/applogs (keeps last 50)
//!   POST /api/stt          -> proxied to the optional local STT server (config.stt_url)
use crate::config;
use axum::http::HeaderMap;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{Read, Write};

pub enum Out { Json(u16, Value), Bytes(u16, String, Vec<u8>, Vec<(String, String)>) }

fn hdr(h: &HeaderMap, k: &str) -> String { h.get(k).and_then(|v| v.to_str().ok()).unwrap_or("").to_string() }

pub fn handle(m: &str, p: &[&str], qs: &HashMap<String, String>, h: &HeaderMap, body: &[u8]) -> Option<Out> {
    let app = config::data_dir().join("app");
    Some(match (m, p) {
        ("GET", ["app", "version"]) => {
            let mut v: Value = std::fs::read_to_string(app.join("version.json")).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(json!({}));
            if let Some(o) = v.as_object_mut() { o.insert("url".into(), json!("/api/app/apk")); }
            Out::Json(200, v)
        }
        ("GET", ["app", "apk"]) => match std::fs::read(app.join("leoagent.apk")) {
            Err(_) => Out::Json(404, json!({"error": "no apk published"})),
            Ok(b) => Out::Bytes(200, "application/vnd.android.package-archive".into(), b,
                vec![("Content-Disposition".into(), "attachment; filename=\"leoagent.apk\"".into())]),
        },
        ("POST", ["app", "logs"]) => logs(h, body),
        ("POST", ["stt"]) => stt(qs, h, body),
        ("GET", ["stt"]) => Out::Json(200, json!({"enabled": !config::get().stt_url.is_empty()})),
        _ => return None,
    })
}

fn logs(h: &HeaderMap, body: &[u8]) -> Out {
    if body.is_empty() || body.len() > 30 * 1024 * 1024 { return Out::Json(if body.is_empty() { 400 } else { 413 }, json!({"error": "bad size"})) }
    if !body.starts_with(b"PK") { return Out::Json(400, json!({"error": "not a zip"})) }
    let d = config::data_dir().join("applogs");
    if let Err(e) = std::fs::create_dir_all(&d) { return Out::Json(500, json!({"error": e.to_string()})) }
    let ts = chrono::Local::now().format("%Y%m%d-%H%M%S").to_string();
    let kind: String = hdr(h, "x-kind").chars().filter(|c| c.is_ascii_lowercase()).take(10).collect();
    let kind = if kind.is_empty() { "manual".to_string() } else { kind };
    let name = format!("{ts}-{kind}.zip");
    if let Err(e) = std::fs::write(d.join(&name), body) { return Out::Json(500, json!({"error": e.to_string()})) }
    let note: String = urlencoding::decode(&hdr(h, "x-note")).map(|s| s.into_owned()).unwrap_or_default().chars().take(500).collect();
    let meta = json!({"ts": ts, "kind": kind, "note": note, "size": body.len(), "ver": hdr(h, "x-app-ver")});
    let _ = std::fs::write(d.join(format!("{ts}-{kind}.json")), meta.to_string());
    let mut zs: Vec<String> = std::fs::read_dir(&d).map(|it| it.filter_map(|e| e.ok()).map(|e| e.file_name().to_string_lossy().into_owned()).filter(|f| f.ends_with(".zip")).collect()).unwrap_or_default();
    zs.sort();
    if zs.len() > 50 { for f in &zs[..zs.len() - 50] { for e in [".zip", ".json"] { let _ = std::fs::remove_file(d.join(format!("{}{e}", &f[..f.len() - 4]))); } } }
    Out::Json(200, json!({"ok": true, "file": name}))
}

/// Minimal HTTP/1.0 forwarder to a localhost STT server (plain http only).
fn stt(qs: &HashMap<String, String>, h: &HeaderMap, body: &[u8]) -> Out {
    let base = config::get().stt_url.trim().trim_end_matches('/').to_string();
    if base.is_empty() { return Out::Json(503, json!({"error": "语音识别未启用 (config: stt_url)"})) }
    let Some(hostport) = base.strip_prefix("http://") else { return Out::Json(500, json!({"error": "stt_url must be http://host:port"})) };
    let hostport = hostport.split('/').next().unwrap_or(hostport);
    let q: Vec<String> = qs.iter().map(|(k, v)| format!("{}={}", urlencoding::encode(k), urlencoding::encode(v))).collect();
    let path = if q.is_empty() { "/api/stt".to_string() } else { format!("/api/stt?{}", q.join("&")) };
    let r = (|| -> std::io::Result<(u16, String, Vec<u8>)> {
        let mut s = std::net::TcpStream::connect(hostport)?;
        s.set_read_timeout(Some(std::time::Duration::from_secs(120)))?;
        let ct = { let c = hdr(h, "content-type"); if c.is_empty() { "application/octet-stream".into() } else { c } };
        write!(s, "POST {path} HTTP/1.0\r\nHost: {hostport}\r\nX-Token: {}\r\nContent-Type: {ct}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", config::get().token, body.len())?;
        s.write_all(body)?;
        let mut buf = vec![]; s.read_to_end(&mut buf)?;
        let split = buf.windows(4).position(|w| w == b"\r\n\r\n").ok_or_else(|| std::io::Error::other("bad response"))?;
        let head = String::from_utf8_lossy(&buf[..split]).into_owned();
        let code: u16 = head.split_whitespace().nth(1).and_then(|x| x.parse().ok()).unwrap_or(502);
        let ctype = head.lines().find_map(|l| l.split_once(':').filter(|(k, _)| k.eq_ignore_ascii_case("content-type")).map(|(_, v)| v.trim().to_string())).unwrap_or("application/json".into());
        Ok((code, ctype, buf[split + 4..].to_vec()))
    })();
    match r { Ok((c, t, b)) => Out::Bytes(c, t, b, vec![]), Err(e) => Out::Json(502, json!({"error": format!("STT 服务不可达: {e}")})) }
}

pub fn pair_uri() -> String {
    let c = config::get();
    format!("leoagent://pair?url={}&token={}", urlencoding::encode(c.public_url.trim_end_matches('/')), urlencoding::encode(&c.token))
}

pub fn print_pair(svg: Option<&str>) -> Result<(), String> {
    let c = config::get();
    if c.public_url.is_empty() { return Err(format!("public_url is empty — set it in {} (e.g. https://agent.example.com) first", config::path().display())) }
    let uri = pair_uri();
    let code = qrcode::QrCode::new(uri.as_bytes()).map_err(|e| e.to_string())?;
    let s = code.render::<qrcode::render::unicode::Dense1x2>().dark_color(qrcode::render::unicode::Dense1x2::Light).light_color(qrcode::render::unicode::Dense1x2::Dark).quiet_zone(true).build();
    println!("{s}\n");
    println!("Scan with your phone camera (or copy this link into LeoAgent → 设置 → 服务器与 Token → 粘贴配对链接):\n{uri}\n");
    println!("Server URL: {}\nAnyone with this token can run agents on this machine — keep it private.", c.public_url);
    if let Some(p) = svg {
        let img = code.render::<qrcode::render::svg::Color>().min_dimensions(320, 320).build();
        std::fs::write(p, img).map_err(|e| e.to_string())?;
        println!("QR code written to {p}");
    }
    Ok(())
}
