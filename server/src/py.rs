//! Python 兼容: json.dumps 格式、真值、round、str()/int()/float()、parse_qs、quote/unquote、html.escape
use serde_json::{Map, Value};

pub fn dumps(v: &Value) -> String { let mut s = String::new(); w(v, false, &mut s); s }
pub fn dumps_compact(v: &Value) -> String { let mut s = String::new(); w(v, true, &mut s); s }

fn w(v: &Value, c: bool, o: &mut String) {
    match v {
        Value::Null => o.push_str("null"),
        Value::Bool(b) => o.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => {
            if n.is_f64() { o.push_str(&pyfloat(n.as_f64().unwrap())) } else { o.push_str(&n.to_string()) }
        }
        Value::String(s) => o.push_str(&serde_json::to_string(s).unwrap()),
        Value::Array(a) => {
            o.push('[');
            for (i, x) in a.iter().enumerate() { if i > 0 { o.push_str(if c { "," } else { ", " }) } w(x, c, o) }
            o.push(']');
        }
        Value::Object(m) => {
            o.push('{');
            for (i, (k, x)) in m.iter().enumerate() {
                if i > 0 { o.push_str(if c { "," } else { ", " }) }
                o.push_str(&serde_json::to_string(k).unwrap()); o.push_str(if c { ":" } else { ": " }); w(x, c, o)
            }
            o.push('}');
        }
    }
}

/// Python float repr
pub fn pyfloat(f: f64) -> String {
    if f.is_nan() { return "NaN".into() }
    if f.is_infinite() { return if f > 0.0 { "Infinity".into() } else { "-Infinity".into() } }
    let e = format!("{:e}", f);
    let (neg, e) = match e.strip_prefix('-') { Some(r) => (true, r.to_string()), None => (false, e) };
    let (mant, exp) = e.split_once('e').unwrap();
    let exp: i32 = exp.parse().unwrap();
    let digits: String = mant.chars().filter(|c| *c != '.').collect();
    let mut s = String::new();
    if neg { s.push('-') }
    if (-4..16).contains(&exp) {
        let p = exp + 1;
        if p as usize >= digits.len() && p > 0 { s.push_str(&digits); for _ in 0..(p as usize - digits.len()) { s.push('0') } s.push_str(".0") }
        else if p <= 0 { s.push_str("0."); for _ in 0..(-p) { s.push('0') } s.push_str(&digits) }
        else { s.push_str(&digits[..p as usize]); s.push('.'); s.push_str(&digits[p as usize..]) }
    } else {
        s.push_str(&digits[..1]);
        if digits.len() > 1 { s.push('.'); s.push_str(&digits[1..]) }
        s.push_str(&format!("e{}{:02}", if exp < 0 { '-' } else { '+' }, exp.abs()));
    }
    s
}

pub fn round(x: f64, n: usize) -> f64 { format!("{:.*}", n, x).parse().unwrap_or(x) }
pub fn fnum(x: f64) -> Value { serde_json::Number::from_f64(x).map(Value::Number).unwrap_or(Value::Null) }

pub fn truthy(v: Option<&Value>) -> bool {
    match v {
        None | Some(Value::Null) => false,
        Some(Value::Bool(b)) => *b,
        Some(Value::Number(n)) => n.as_f64().map(|f| f != 0.0).unwrap_or(true),
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Array(a)) => !a.is_empty(),
        Some(Value::Object(m)) => !m.is_empty(),
    }
}
/// a or b
pub fn or(a: Option<&Value>, b: Value) -> Value { if truthy(a) { a.unwrap().clone() } else { b } }
pub fn or_opt(a: Option<&Value>, b: Option<&Value>) -> Value { if truthy(a) { a.unwrap().clone() } else { b.cloned().unwrap_or(Value::Null) } }
pub fn get<'a>(v: &'a Value, k: &str) -> Option<&'a Value> { v.as_object().and_then(|m| m.get(k)) }
pub fn obj_or_empty(v: Option<&Value>) -> Value { or(v, Value::Object(Map::new())) }

pub fn pystr(v: &Value) -> String {
    match v {
        Value::Null => "None".into(),
        Value::Bool(b) => if *b { "True".into() } else { "False".into() },
        Value::Number(n) => if n.is_f64() { pyfloat(n.as_f64().unwrap()) } else { n.to_string() },
        Value::String(s) => s.clone(),
        x => x.to_string(),
    }
}
pub fn pyint(v: &Value) -> Result<i64, String> {
    match v {
        Value::Bool(b) => Ok(*b as i64),
        Value::Number(n) => if let Some(i) = n.as_i64() { Ok(i) } else { Ok(n.as_f64().unwrap().trunc() as i64) },
        Value::String(s) => s.trim().replace('_', "").parse::<i64>().map_err(|_| format!("invalid literal for int() with base 10: '{}'", s)),
        Value::Null => Err("int() argument must be a string, a bytes-like object or a real number, not 'NoneType'".into()),
        _ => Err("int() argument must be a string, a bytes-like object or a real number".into()),
    }
}
pub fn pyfloat_of(v: &Value) -> Result<f64, String> {
    match v {
        Value::Bool(b) => Ok(*b as i64 as f64),
        Value::Number(n) => Ok(n.as_f64().unwrap()),
        Value::String(s) => { let t = s.trim().to_lowercase();
            match t.as_str() { "inf" | "+inf" | "infinity" => Ok(f64::INFINITY), "-inf" | "-infinity" => Ok(f64::NEG_INFINITY),
                _ => t.parse::<f64>().map_err(|_| format!("could not convert string to float: '{}'", s)) } }
        _ => Err("float() argument must be a string or a real number".into()),
    }
}
pub fn trunc_chars(s: &str, n: usize) -> String { s.chars().take(n).collect() }

fn hexv(b: u8) -> Option<u8> { (b as char).to_digit(16).map(|d| d as u8) }
pub fn unquote(s: &str) -> String {
    let b = s.as_bytes(); let mut o = Vec::with_capacity(b.len()); let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            if let (Some(h), Some(l)) = (hexv(b[i + 1]), hexv(b[i + 2])) { o.push(h * 16 + l); i += 3; continue }
        }
        o.push(b[i]); i += 1;
    }
    String::from_utf8_lossy(&o).into_owned()
}
/// parse_qs (keep_blank_values=False): 有序 (key, [values])
pub fn parse_qs(q: &str) -> Vec<(String, Vec<String>)> {
    let mut out: Vec<(String, Vec<String>)> = Vec::new();
    for part in q.split(|c| c == '&' || c == ';') {
        if part.is_empty() { continue }
        let Some((k, v)) = part.split_once('=') else { continue };
        let v = unquote(&v.replace('+', " "));
        if v.is_empty() { continue }
        let k = unquote(&k.replace('+', " "));
        if let Some(e) = out.iter_mut().find(|e| e.0 == k) { e.1.push(v) } else { out.push((k, vec![v])) }
    }
    out
}
pub fn qget(q: &[(String, Vec<String>)], k: &str) -> String { q.iter().find(|e| e.0 == k).map(|e| e.1[0].clone()).unwrap_or_default() }
pub fn qhas(q: &[(String, Vec<String>)], k: &str) -> bool { q.iter().any(|e| e.0 == k) }
pub fn quote(s: &str) -> String {
    let mut o = String::new();
    for &b in s.as_bytes() {
        if b.is_ascii_alphanumeric() || b"_.-~/".contains(&b) { o.push(b as char) } else { o.push_str(&format!("%{:02X}", b)) }
    }
    o
}
pub fn html_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&#x27;")
}

#[cfg(test)]
mod t {
    use super::*;
    #[test] fn floats() {
        for (f, s) in [(1.0, "1.0"), (0.1, "0.1"), (1e-5, "1e-05"), (1e16, "1e+16"), (123.456, "123.456"), (-0.5, "-0.5"), (0.0001, "0.0001"), (1234567890123456.0, "1234567890123456.0"), (2.5e-7, "2.5e-07"), (-3.0, "-3.0")] {
            assert_eq!(pyfloat(f), s);
        }
    }
}
