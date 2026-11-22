# marquee-resolver

Requires **Node 22.13 or later**. Optional per-profile recommendations use durable
SQLite state and a server-only `TMDB_READ_TOKEN`. Read the
[shared recommendation API and setup](../docs/recommendations-api.md) for Android
and desktop integration, memory limits, privacy and persistence across reboots.

A small self-hosted API that answers one question: **"what URL can I play right
now?"** Marquee ships with an IPTV catalogue, and that catalogue does not carry
everything. Point the app at this service and it can fill the gaps from the open
web instead of from somebody else's server.

```
GET /health
GET /search?q=apartment%2023
GET /show?id=/tYOM5N
GET /resolve?id=/tYOM5N&season=2&episode=3      -> { url, container, host, ... }
```

`/resolve` returns a **direct, signed video URL** — usually an MP4 on a CDN, which
ExoPlayer plays and seeks natively. Nothing is transcoded, nothing is stored.

> **Read this before you deploy.** The links this service mints are bound to the IP
> that minted them. A URL resolved on one network plays only from that network —
> verified both directions (`206` from the minting IP, `403` from any other). So the
> resolver must run **somewhere behind the same public IP as the TV**. Run it at
> home. Do not put it on a VPS "for convenience": it will mint links your TV cannot
> play. See [Where to run it](#where-to-run-it).

## What it is not

It does not host, index, or store video. It is the same scraping a browser does when
you open a link site yourself, done server-side so your TV only has to play a plain
URL. You are responsible for what you point it at and for the law where you live.

## Requirements

- Node 18+ (Docker image provided, so this is optional)
- A Chromium to drive: the distro package, or Playwright's own download
- ~400 MB RAM per resolve while a browser is open (it closes when idle)
- An always-on machine on your home network: a Raspberry Pi 4, a mini PC, a NAS, an
  old laptop, or a PC that stays awake

## Quick start (Docker)

```bash
git clone https://github.com/TheophilusChinomona/marquee.git
cd marquee/resolver
docker compose up -d --build
curl -s http://localhost:8099/health
```

Then check it actually resolves:

```bash
curl -s "http://localhost:8099/resolve?id=/tnkxGv&season=1&episode=1"
```

The image is ~1.4 GB because it contains Chromium; it idles at ~70 MB.

## Quick start (no Docker)

```bash
cd resolver
npm install

# Either use a browser you already have...
export MARQUEE_CHROME=/usr/bin/chromium

# ...or let Playwright fetch one (~150 MB, Linux deps included):
npm run browser:install -- --with-deps

npm start
```

On Arch there is no `npm` package alongside `nodejs`; install `npm` yourself or use
the Docker route. On Debian/Ubuntu: `sudo apt install nodejs npm chromium`.

### As a service

`deploy/install.sh` copies the folder over SSH, installs dependencies and drops in a
systemd **user** unit with linger enabled, so it starts at boot rather than at login:

```bash
# on the machine itself
./deploy/install.sh

# or push to another machine
REMOTE=you@homebox ./deploy/install.sh
```

`deploy/marquee-resolver.service` is the unit it installs; edit `MARQUEE_CHROME` in
it if your browser path differs.

## Where to run it

| Placement | Works? | Why |
|---|---|---|
| A machine at home (Pi, mini PC, NAS, always-on PC) | ✅ | mints from the same public IP the TV watches from |
| The same LAN, another room | ✅ | as above |
| A VPS anywhere else | ❌ | links it mints return `403` to your TV |
| Behind a VPN/proxy that changes your egress IP | ❌ | same reason |

If the box is on your home network, the TV can reach it over the LAN. If you want it
to work away from home too, see [A stable URL from outside](#a-stable-url-from-outside)
— the tunnel keeps the minting IP unchanged, which is the whole point.

## Pointing Marquee at it

In the app: **Profiles → Server**. Accepts any of:

```
192.168.1.50:8099
http://192.168.1.50:8099
https://resolver.example.com
```

Leave it blank and the app simply ignores this service (the built-in public fallback
still works). The **Online sources** toggle next to it turns the whole online
fallback off.

If you set `RESOLVER_TOKEN`, the app needs no extra field: put the token in the path
(see [Protecting a public endpoint](#protecting-a-public-endpoint) for the stronger
Cloudflare Access option, which needs the Key field instead).

```
Profiles -> Server:   https://resolver.example.com/t/<token>
```

Every endpoint also accepts `Authorization: Bearer <token>` and `?token=<token>`,
which is what scripts and `curl` should use. A reverse proxy needs no knowledge of
it, because it just forwards the path.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `RESOLVER_PORT` | `8099` | listen port |
| `RESOLVER_HOST` | `0.0.0.0` | listen address (`127.0.0.1` behind a proxy) |
| `RESOLVER_TOKEN` | unset | require `Authorization: Bearer …`, `?token=…`, or `/t/<token>/…` |
| `RESOLVER_RATE_MAX` | `60` | requests per IP per window |
| `RESOLVER_RATE_WINDOW_MS` | `600000` | window length (10 min) |
| `RESOLVER_CACHE_MS` | `1200000` | reuse a resolved URL this long (20 min) |
| `RESOLVER_TIMEOUT_MS` | `90000` | hard deadline per request |
| `RESOLVER_CONCURRENCY` | `2` | browser contexts in flight |
| `RESOLVER_IDLE_MS` | `120000` | close Chromium after this idle time |
| `MARQUEE_CHROME` | unset | explicit Chromium binary |
| `GOOJARA_BASE` | `https://ww1.goojara.to` | mirror to scrape |

## Exposing it

### On the LAN

Nothing to do; it binds `0.0.0.0:8099`. If the host runs a firewall that denies by
default, open the port for your subnet only:

```bash
sudo ufw allow from 192.168.1.0/24 to any port 8099 proto tcp
```

### A stable URL from outside

A Cloudflare Tunnel is the least invasive option: no port forwarding, no open port on
your router, and — importantly — the resolver keeps minting from your home IP.

```bash
cloudflared tunnel create marquee-resolver
cloudflared tunnel route dns marquee-resolver resolver.example.com
cloudflared tunnel run --url http://127.0.0.1:8099 marquee-resolver
```

If the tunnel is managed from the Cloudflare dashboard (token-based), add it there
instead: **Zero Trust → Networks → Tunnels → your tunnel → Public Hostname**, service
`HTTP://localhost:8099`.

Tailscale works equally well if every device you watch on is on your tailnet — and
it needs no DNS at all:

```
http://<your-box>.<tailnet>.ts.net:8099
```

Any reverse proxy works too; Caddy is a two-liner:

```
resolver.example.com {
    reverse_proxy 127.0.0.1:8099
}
```

### Protecting a public endpoint

A public resolver is an open browser on your machine: every request can start a
Chromium. Three layers, cheapest first.

**1. Put Cloudflare Access in front (strongest).** Requests without valid credentials
are rejected at the edge, so nothing reaches your house at all.

- Zero Trust -> Access -> **Service Auth** -> Create Service Token. Copy the
  `Client ID` and `Client Secret` (shown once).
- Zero Trust -> Access -> **Applications** -> Add an application -> Self-hosted ->
  domain `resolver.example.com` -> policy action: **Service Auth**, Include -> **Service Token** ->
  the token you just made.
- In the app: **Profiles -> Server** = `https://resolver.example.com`, and **Key** =
  `clientId:clientSecret` (that is the colon-separated pair, one field).

The app sends `CF-Access-Client-Id` / `CF-Access-Client-Secret`, which is exactly what
Access expects. `curl` can do the same:

```bash
curl -H "CF-Access-Client-Id: $ID" -H "CF-Access-Client-Secret: $SECRET" "https://resolver.example.com/health"
```

**2. Rate limit at the edge.** Cloudflare -> Security -> WAF -> Rate limiting rules:
match hostname `resolver.example.com`, 30 requests per minute per IP, action Block for
1 minute. On the Free plan, use 5 requests per 10 seconds per IP with a 10-second block instead (the available window depends on your plan). This helps contain a leaked token or a runaway loop.

**3. Cap the host.** The systemd unit shipped in `deploy/` already does:

```ini
MemoryMax=1500M      # a resolve peaks around 250 MB; this is the ceiling
CPUQuota=200%
TasksMax=256
NoNewPrivileges=yes
PrivateTmp=yes
```

On top of that the resolver has its own per-IP limiter (`RESOLVER_RATE_MAX`, default
60 per 10 minutes) and closes Chromium after two idle minutes.

**If you skip Access**, you still have `RESOLVER_TOKEN`. Header form is safest; the
`/t/<token>/` path form is supported because a TV remote cannot type headers, but note
that URLs land in access logs, so treat that token as semi-public and rotate it. To
rotate: change the token on the box, update the app's Key field, done.

**Kill switch:** `systemctl --user stop marquee-resolver`, or delete the tunnel's
public hostname. Either one takes the endpoint off the internet in seconds.

## API

| Endpoint | Returns |
|---|---|
| `GET /health` | `{ status, service, version, uptimeS, cached }` |
| `GET /search?q=` | `{ results: [{ id, name, year, kind, poster }] }` |
| `GET /show?id=` | `{ id, kind, name, year, poster, plot, seasons: [{ number, episodes: [...] }] }` |
| `GET /resolve?id=[&season=&episode=]` | `{ id, label, host, url, status, container, contentType, referer }` |
| `GET /resolve?id=&host=wootly` | same, host preference |

Ids are the site's own page ids (`/t…` series, `/m…` movie, `/e…` episode). `season`
and `episode` are 1-based; omitting them resolves the title itself.

Errors are honest: `410` from upstream becomes
`{"error":"host reports the file is gone (410)"}`, not a dead link handed to the TV.

## Command line

```bash
node tools/probe.js search "the boys"
node tools/probe.js show /tnkxGv
node tools/probe.js resolve /tnkxGv 1 1
```

Same code, no HTTP layer — the fastest way to find out whether a title works before
blaming the app.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `{"error":"no implemented host on this page"}` | the page offers only hosts that are not implemented | try another title |
| `host reports the file is gone (410)` | upstream file removed — common on old catalogue | nothing to fix; use another source |
| `no media URL surfaced from any host` | the player never loaded (slow host, ad gate) | retry; the service already retries once per host |
| resolution works in `curl` but playback fails in the app | TV and resolver are on **different** public IPs | run the resolver at home |
| `403 text/html` when opening a resolved URL | same as above, or the link expired | re-resolve |
| `too many requests` | rate limit | wait for the window, or raise `RESOLVER_RATE_MAX` |
| Browser will not start | no Chromium, or missing libs | set `MARQUEE_CHROME`, or use the Docker image |
| Cold resolve takes ~15 s | it is launching a browser and the site's player | expected; cached repeats are instant |

## Limits

- **~15 s for a cold resolve**, instant while cached (20 minutes).
- **Licensing is per-IP**: see the warning at the top.
- **One source is implemented.** Wootly works end to end; the site's other host is
  opt-in (`?host=vidsrc`) and currently falls back to Wootly.
- **Old catalogue is patchy.** Files from years ago are often gone upstream. Recent
  releases are near-universal.
- **A resolve is a browser session.** Treat this as a household service, not a public
  one; two concurrent sessions are the default for a reason.
