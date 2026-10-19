use crate::events::trunc_any;
use serde_json::{json, Value};

pub const TAG_A: &str = "<send_user_message_question_reply>";
pub const TAG_B: &str = "</send_user_message_question_reply>";

pub fn qid(item_id: &str, i: usize) -> String {
    serde_json::to_string(&json!(["request_user_input_async", item_id, i])).unwrap()
}
pub fn parse_reply(txt: &str) -> Option<Value> {
    let t = txt.trim();
    if !(t.starts_with(TAG_A) && t.ends_with(TAG_B)) { return None }
    let inner = &t[TAG_A.len()..t.len()-TAG_B.len()];
    let v: Value = serde_json::from_str(inner).ok()?;
    Some(if v.is_array() { v } else { json!([v]) })
}
pub fn reply_text(answers: &Value) -> String {
    format!("{TAG_A}\n{}\n{TAG_B}", serde_json::to_string(answers).unwrap())
}
pub fn ask_from_async(it: &Value) -> Value {
    let mut qs = vec![];
    for (i, q) in it.get("questions").and_then(|x| x.as_array()).into_iter().flatten().enumerate() {
        let q = if q.is_string() { json!({"title": q}) } else { q.clone() };
        let opts: Vec<Value> = q.get("options").and_then(|x| x.as_array()).into_iter().flatten()
            .map(|o| if o.is_object() { o.clone() } else { json!({"label": o}) }).collect();
        qs.push(json!({
            "id": qid(it.get("id").and_then(|x|x.as_str()).unwrap_or(""), i),
            "title": q.get("title").or_else(|| q.get("question")).and_then(|x|x.as_str()).unwrap_or(""),
            "options": opts.iter().map(|o| json!({"label": o.get("label").or_else(||o.get("title")).and_then(|x|x.as_str()).unwrap_or(""),
                "desc": o.get("description").and_then(|x|x.as_str()).unwrap_or("")})).collect::<Vec<_>>(),
            "multi": q.get("multiSelect").or_else(||q.get("multiple")).and_then(|x|x.as_bool()).unwrap_or(false),
            "other": true
        }));
    }
    json!({"kind":"async","title":"Codex 有个问题想问你","questions":qs,"note":"Codex 会继续工作；回答后会作为补充信息发给它。"})
}
pub fn ask_from_request(req: &Value) -> Value {
    let m = req.get("method").and_then(|x| x.as_str()).unwrap_or("");
    let p = req.get("params").cloned().unwrap_or(json!({}));
    if m.contains("requestUserInput") {
        let qs: Vec<Value> = p.get("questions").and_then(|x| x.as_array()).into_iter().flatten().enumerate().map(|(i,q)| {
            json!({"id": q.get("id").cloned().unwrap_or(json!(i.to_string())),
                "title": format!("{}{}", if let Some(h)=q.get("header").and_then(|x|x.as_str()) { format!("{h}：") } else { String::new() }, q.get("question").and_then(|x|x.as_str()).unwrap_or("")),
                "options": q.get("options").and_then(|x|x.as_array()).into_iter().flatten().map(|o| json!({"label":o.get("label").and_then(|x|x.as_str()).unwrap_or(""),"desc":o.get("description").and_then(|x|x.as_str()).unwrap_or("")})).collect::<Vec<_>>(),
                "multi": q.get("multiSelect").and_then(|x|x.as_bool()).unwrap_or(false),
                "other": q.get("isOther").and_then(|x|x.as_bool()).unwrap_or(true) || q.get("options").and_then(|x|x.as_array()).map(|a|a.is_empty()).unwrap_or(true),
                "secret": q.get("isSecret").and_then(|x|x.as_bool()).unwrap_or(false)})
        }).collect();
        return json!({"kind":"input","title":"Codex 需要你的输入","questions":qs});
    }
    if m.to_lowercase().contains("elicitation") {
        let sch = p.get("requestedSchema").or_else(|| p.get("schema")).cloned().unwrap_or(json!({}));
        let mut qs = vec![];
        if let Some(props) = sch.get("properties").and_then(|x| x.as_object()) {
            for (k, v) in props {
                let mut en: Vec<Value> = v.get("enum").and_then(|x| x.as_array()).cloned().unwrap_or_default();
                if en.is_empty() {
                    if let Some(one) = v.get("oneOf").and_then(|x| x.as_array()) {
                        en = one.iter().filter_map(|x| x.get("const").cloned()).collect();
                    }
                }
                if v.get("type").and_then(|x| x.as_str()) == Some("boolean") { en = vec![json!("true"), json!("false")]; }
                qs.push(json!({"id":k,"title":v.get("title").or_else(||v.get("description")).and_then(|x|x.as_str()).unwrap_or(k),
                    "options": en.iter().map(|e| json!({"label": e.to_string().trim_matches('"'), "desc":""})).collect::<Vec<_>>(),
                    "multi": v.get("type").and_then(|x|x.as_str())==Some("array"), "other": en.is_empty()}));
            }
        }
        let mut txt = p.get("message").and_then(|x| x.as_str()).unwrap_or("").to_string();
        if let Some(u) = p.get("url") { txt.push('\n'); txt.push_str(&u.to_string()); }
        return json!({"kind":"elicit","title": format!("{} 请求信息", p.get("serverName").and_then(|x|x.as_str()).unwrap_or("MCP")), "text": txt, "questions": qs});
    }
    let title = if m.matches('/').count() >= 2 { m.rsplit('/').nth(1).unwrap_or(m) } else { m };
    json!({"kind":"generic","title": format!("Codex 请求：{title}"), "text": trunc_any(&p, 2500), "questions":[{"id":"text","title":"回复内容","options":[],"other":true}]})
}
pub fn summarize(ans: &Value) -> String {
    let mut out = vec![];
    if let Some(m) = ans.as_object() {
        for (_, v) in m {
            let vv: Vec<String> = match v { Value::Array(a) => a.iter().map(|x| pystr(x)).collect(), x => vec![pystr(x)] };
            out.push(vv.into_iter().filter(|x| !x.trim().is_empty()).collect::<Vec<_>>().join("、"));
        }
    }
    let s = out.into_iter().filter(|x| !x.is_empty()).collect::<Vec<_>>().join("；");
    if s.is_empty() { "（空）".into() } else { s }
}
fn pystr(v: &Value) -> String { match v { Value::String(s)=>s.clone(), x=>x.to_string() } }

pub static DEC_NAME: &[(&str,&str)] = &[("accept","允许"),("acceptForSession","本会话内允许"),("decline","拒绝"),("cancel","取消并停止"),("acceptWithExecpolicyAmendment","总是允许此命令")];
pub fn opts(decs: &[Value]) -> Vec<Value> {
    let decs = if decs.is_empty() { vec![json!("accept"), json!("decline")] } else { decs.to_vec() };
    decs.iter().enumerate().map(|(i,d)| {
        let k = if let Value::String(s)=d { s.as_str() } else { d.as_object().and_then(|m| m.keys().next()).map(|s| s.as_str()).unwrap_or("") };
        let name = DEC_NAME.iter().find(|(a,_)| *a==k).map(|(_,b)| *b).unwrap_or(k);
        let kind = if k=="accept" {"allow_once"} else if k.starts_with("accept") {"allow_always"} else {"reject_once"};
        json!({"id": i.to_string(), "name": name, "kind": kind})
    }).collect()
}
pub fn perm_view(req: &Value) -> (String, String) {
    let p = req.get("params").cloned().unwrap_or(json!({}));
    let m = req.get("method").and_then(|x| x.as_str()).unwrap_or("");
    let home = std::env::var("HOME").unwrap_or_default();
    if m.contains("commandExecution") {
        let cwd = p.get("cwd").map(|c| format!("\n(cwd: {})", c.as_str().unwrap_or("").replace(&home, "~"))).unwrap_or_default();
        let reason = p.get("reason").map(|r| format!("\n{r}")).unwrap_or_default();
        return ("运行命令".into(), format!("$ {}{}{}", p.get("command").map(|x| x.as_str().unwrap_or("")).unwrap_or(""), cwd, reason).trim().into());
    }
    if m.contains("fileChange") {
        let gr = p.get("grantRoot").map(|g| format!("\n{g}")).unwrap_or_default();
        return ("修改文件".into(), format!("{}{gr}", p.get("reason").and_then(|x|x.as_str()).unwrap_or("")));
    }
    (m.rsplit('/').next().unwrap_or("审批").into(), trunc_any(&p, 1500))
}
