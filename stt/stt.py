#!/usr/bin/env python3
# LeoAgent local speech-to-text: SenseVoice-small (offline) + streaming Paraformer (live partials), via sherpa-onnx.
# leoagent-server proxies POST /api/stt here (config: stt_url = "http://127.0.0.1:8791").
#   POST /api/stt            body = audio in any ffmpeg-readable format -> {"text": ...}
#   POST /api/stt?op=chunk&sid=..  body = 16 kHz mono s16le PCM chunk  -> {"partial": ...}
#   POST /api/stt?op=final&sid=..                                      -> {"text": ...}
# Auth: X-Token must equal the token in the leoagent config file.
# Models are NOT in the repo: run ./download-models.sh first (see NOTICE for model licenses).
import os, json, time, subprocess, hmac, threading, shutil, sys
import numpy as np, sherpa_onnx
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
try: import tomllib
except ImportError: tomllib = None
CFG_PATH = os.environ.get("LEOAGENT_CONFIG") or os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "leoagent", "config.toml")
def _cfg():
    if tomllib is None: sys.exit("python >= 3.11 required (tomllib)")
    with open(CFG_PATH, "rb") as f: return tomllib.load(f)
CFG = _cfg()
TOKEN = CFG["token"].strip()
_u = (CFG.get("stt_url") or "http://127.0.0.1:8791").rsplit(":", 1)
HOST, PORT = "127.0.0.1", int(os.environ.get("LEOAGENT_STT_PORT") or _u[1].split("/")[0])
B = os.environ.get("LEOAGENT_STT_MODELS") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "models")
M = B + "/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
FFMPEG = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
REC = sherpa_onnx.OfflineRecognizer.from_sense_voice(model=M+"/model.int8.onnx", tokens=M+"/tokens.txt", num_threads=4, use_itn=True, language="auto")
LK = threading.Lock()
P = B + "/sherpa-onnx-streaming-paraformer-bilingual-zh-en"
ON = sherpa_onnx.OnlineRecognizer.from_paraformer(tokens=P+"/tokens.txt", encoder=P+"/encoder.int8.onnx", decoder=P+"/decoder.int8.onnx", num_threads=2, enable_endpoint_detection=True, rule1_min_trailing_silence=1.2, rule2_min_trailing_silence=0.8, rule3_min_utterance_length=20)
SESS = {}; SL = threading.Lock()
def fix(txt):
    import re; return re.sub(r'[A-Z][A-Z ]*[A-Z]', lambda m: m.group(0).lower() if len(m.group(0))>3 else m.group(0), txt)
def sv(a):
    with LK:
        s = REC.create_stream(); s.accept_waveform(16000, a); REC.decode_stream(s); return s.result.text.strip()
def long_sv(a):
    if len(a) <= 16000*25: return sv(a)
    cfg = sherpa_onnx.VadModelConfig(); cfg.silero_vad.model = B+"/vad.onnx"; cfg.silero_vad.min_silence_duration = 0.4; cfg.silero_vad.max_speech_duration = 20; cfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=700); out = []
    def drain():
        while not vad.empty(): t = sv(vad.front.samples); vad.pop(); out.append(t) if t else None
    for i in range(0, len(a), 512): vad.accept_waveform(a[i:i+512]); drain()
    vad.flush(); drain(); return "".join(out)
def chunk(sid, data):
    a = np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768
    with SL:
        now = time.time()
        for k in [k for k, v in SESS.items() if now - v["t"] > 900]: SESS.pop(k)
        S = SESS.get(sid)
        if S is None: S = SESS[sid] = {"st": ON.create_stream(), "done": "", "buf": [], "t": now, "lk": threading.Lock()}
    with S["lk"]:
        S["t"] = time.time(); S["buf"].append(a); st = S["st"]; st.accept_waveform(16000, a)
        while ON.is_ready(st): ON.decode_stream(st)
        cur = ON.get_result(st).strip()
        if ON.is_endpoint(st):
            if cur: S["done"] += cur + "，"
            ON.reset(st); cur = ""
        return {"partial": fix(S["done"] + cur), "duration": round(sum(len(x) for x in S["buf"])/16000, 2)}
def final(sid):
    with SL: S = SESS.pop(sid, None)
    if S is None: return {"error": "no session"}
    with S["lk"]: a = np.concatenate(S["buf"]) if S["buf"] else np.zeros(0, np.float32)
    t = time.time(); txt = fix(long_sv(a)) if len(a) else ""
    return {"text": txt, "duration": round(len(a)/16000, 2), "decode_ms": int((time.time()-t)*1000)}
def pcm(data):
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".bin") as f:
        f.write(data); f.flush()
        p = subprocess.run([FFMPEG,"-v","error","-i",f.name,"-ac","1","-ar","16000","-f","f32le","pipe:1"], capture_output=True, timeout=30)
    if p.returncode: raise ValueError(p.stderr.decode()[:200])
    return np.frombuffer(p.stdout, dtype=np.float32)
def stt(data):
    a = pcm(data); t = time.time()
    txt = fix(long_sv(a))
    return {"text": txt, "duration": round(len(a)/16000, 2), "decode_ms": int((time.time()-t)*1000)}
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _j(self, c, o):
        b = json.dumps(o, ensure_ascii=False).encode(); self.send_response(c)
        self.send_header("Content-Type","application/json; charset=utf-8"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self): self._j(200, {"ok": True, "name": "stt", "model": "sensevoice-small-int8"})
    def do_POST(self):
        t = self.headers.get("X-Token", "")
        if not (t and hmac.compare_digest(t, TOKEN)): return self._j(401, {"error": "bad token"})
        n = int(self.headers.get("Content-Length") or 0)
        if not 0 < n <= 40*1024*1024: return self._j(400, {"error": "empty or too large"})
        t0 = time.time()
        from urllib.parse import urlparse, parse_qs
        q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}; op = q.get("op"); sid = q.get("sid", "")[:64]
        try:
            if op == "chunk": return self._j(200, chunk(sid, self.rfile.read(n)))
            if op == "final": self.rfile.read(n); r = final(sid)
            else: r = stt(self.rfile.read(n))
        except Exception as e: return self._j(400, {"error": str(e)})
        r["total_ms"] = int((time.time()-t0)*1000); print(json.dumps(r, ensure_ascii=False), flush=True); self._j(200, r)
if __name__ == "__main__":
    print(f"stt on {HOST}:{PORT} (models: {B})", flush=True); ThreadingHTTPServer((HOST, PORT), H).serve_forever()
