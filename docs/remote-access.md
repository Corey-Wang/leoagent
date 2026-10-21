# Reaching the server from your phone

`leoagent-server` listens on `127.0.0.1:8737` by default and speaks plain HTTP. **Do not expose it directly to the internet.** Put it behind something that provides TLS, for example:

* **Cloudflare Tunnel** — `cloudflared tunnel --url http://127.0.0.1:8737` (quick test) or a named tunnel with your own hostname.
* **Tailscale** — keep `listen = "127.0.0.1:8737"` and use `tailscale serve --https=443 http://127.0.0.1:8737`, or bind to the tailnet IP.
* A reverse proxy (Caddy / nginx) with HTTPS.

Then set `public_url` in the config to the HTTPS URL and run `leoagent-server pair`. The QR/link is a five-minute one-time invitation; the computer prints a verification code and must confirm it before the phone receives a device credential. Server-Sent Events are used for live streaming, so disable response buffering in your proxy (`X-Accel-Buffering: no` is already sent).

The device credential grants full control of coding agents on your machine. Treat it like an SSH key. List and revoke devices with `leoagent-server devices` and `leoagent-server revoke <device-id>`. Do not put credentials in browser URLs.
