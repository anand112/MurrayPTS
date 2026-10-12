# MURRAY Project Tracking System (PTS)

A small internal web app for MURRAY Mining Services Mongolia: projects, task assignment, the
team roster, forecasts, lessons learned, a calendar, and per-position access control. Built with
Kotlin + Ktor, server-rendered HTML, and a single SQLite file.

This is **v2**. The v1 prototype (JSON file store, plaintext passwords, in-memory sessions) has
been replaced: the app now runs on SQLite through Exposed with Flyway migrations, bcrypt
passwords, DB-backed sessions, and a hardened static-asset/CSP layer. `ARCHITECTURE.md` documents
the module design; this file is about running it.

## Requirements

- Java 21 (JDK to build, JRE is enough to run)
- Nothing else — SQLite is embedded, no database server to install

## Run it locally

```bash
./gradlew run          # macOS/Linux
gradlew.bat run        # Windows
```

Then open `http://127.0.0.1:8080`. The default bind address is `0.0.0.0`, so on a shared network
set `PTS_HOST=127.0.0.1` (or `pts.host` system property) to keep it local.

If you start the app from the built distribution (`java -cp "build/install/murray-pts/lib/*"`), stop
it before running `./gradlew installDist` again. `installDist` replaces those jars in place, and the
live JVM loads a page's class only when that page is first opened — so after a rebuild every
never-yet-visited route throws `NoClassDefFoundError` while the ones already loaded keep working.
The symptom is confusing precisely because it is partial. `./gradlew run` is unaffected: it starts a
fresh JVM each time.

First run against an empty database creates the schema and a super admin account:

- Employee number `EMP0000` (change with `PTS_ADMIN_EMPNO`)
- Password: the value of `PTS_ADMIN_PASSWORD` if you set one, otherwise the employee number
  itself
- If the password fell back to the employee number, the account is flagged **must change
  password**: the first login lands on Settings and every other page bounces back to it until a
  new password is saved. Import v1 data or the demo dataset and every imported account that never
  had a real password behaves the same way.

Two optional data loads, both one-shot and both refused unless the database is empty:

```bash
# Import the v1 JSON store (data/pts-data.json by default), then rename it to *.migrated-<ts>
PTS_DATA_FILE=/path/to/pts-data.json ./gradlew run

# Load the demo dataset (6 users, 16 projects, 10 tasks) into a fresh DB — dev only
PTS_SEED_DEMO=true ./gradlew run
```

Environment variables reach the app through `gradlew run`, but `-D` flags do not (Gradle's `-D`
applies to the Gradle JVM, not the forked one), and on Windows cmd set them first:
`set PTS_SEED_DEMO=true` then `gradlew.bat run`. Or just put them in `.env`.

Because there is no `PTS_SMTP_HOST` on a developer machine, the due-date sweep that starts 30 seconds
after boot mails nobody: it renders each message into `logs/pts.log` and records it in
`due_date_alerts` instead. See [Due-date alerts](#due-date-alerts) to drive it by hand.

## Configuration

Every key resolves in this order, first hit wins: **JVM system property** (`-Dpts.port=9000`) →
**OS environment variable** (`PTS_PORT=9000`) → **`.env` file** in the working directory →
built-in default. Copy `.env.example` to `.env` and edit it; `.env` is gitignored because it holds
credentials.

| Variable | System property | Default | Meaning |
| --- | --- | --- | --- |
| `PTS_ENV` | `pts.env` | `development` | `production` enforces a real session secret, sends HSTS, and defaults `PTS_SECURE_COOKIES=true` |
| `PTS_HOST` | `pts.host` | `0.0.0.0` | Bind address. Use `127.0.0.1` behind a proxy |
| `PTS_PORT` | `pts.port` | `8080` | Listen port |
| `PTS_TRUST_PROXY` | `pts.trust.proxy` | `false` | Honour `X-Forwarded-*` from a reverse proxy (see HTTPS below) |
| `PTS_DB_PATH` | `pts.db.path` | `data/pts.db` | SQLite file; parent dirs are created |
| `PTS_DATA_DIR` | `pts.data.dir` | `data` | Directory for the DB and migration backups |
| `PTS_DATA_FILE` | `pts.data.file` | `data/pts-data.json` | Legacy JSON to import once into an empty DB |
| `PTS_SEED_DEMO` | `pts.seed.demo` | `false` | Seed demo data into an empty DB (never into a populated one) |
| `PTS_SESSION_SECRET` | `pts.session.secret` | dev-only string | HMAC key for `security/Csrf` — it does **not** sign the session cookie. Required, ≥32 chars, in production |
| `PTS_SESSION_COOKIE` | `pts.session.cookie` | `PTS_SID` | Session cookie name |
| `PTS_SESSION_TTL_HOURS` | `pts.session.ttl.hours` | `12` | Hours a session lives, counted from login |
| `PTS_SECURE_COOKIES` | `pts.secure.cookies` | `true` in production | Marks the cookie HTTPS-only |
| `PTS_ADMIN_EMPNO` | `pts.admin.empno` | `EMP0000` | Super admin guaranteed on every boot |
| `PTS_ADMIN_PASSWORD` | `pts.admin.password` | unset | Bootstrap password for that admin on a fresh DB |
| `PTS_LOGIN_MAX_ATTEMPTS` | `pts.login.max` | `5` | Failed logins per client IP before throttling |
| `PTS_LOGIN_WINDOW_SECONDS` | `pts.login.window` | `60` | Window for that counter |
| `PTS_LOG_REQUESTS` | `pts.log.requests` | `false` | One INFO line per request |
| `PTS_LOG_DIR` | `pts.log.dir` | `logs` | Rolling log directory |
| `PTS_LOG_LEVEL` | `pts.log.level` | `INFO` | Root level (`TRACE`…`ERROR`) |
| `PTS_ALERTS_ENABLED` | `pts.alerts.enabled` | `true` | Daily due-date reminder sweep (see Due-date alerts) |
| `PTS_ALERT_LEAD_DAYS` | `pts.alert.lead.days` | `7` | Days before a due date that alerting starts |
| `PTS_ALERT_INTERVAL_HOURS` | `pts.alert.interval.hours` | `24` | How often the sweep runs |
| `PTS_ALERT_STARTUP_DELAY_SECONDS` | `pts.alert.startup.delay.seconds` | `30` | Delay after boot before the first sweep |
| `PTS_ALERT_LEDGER_KEEP_DAYS` | `pts.alert.ledger.keep.days` | `90` | Days of `due_date_alerts` history to keep (`0` = never trim) |
| `PTS_SMTP_HOST` | `pts.smtp.host` | empty | Mail server; empty means alerts are logged, not sent |
| `PTS_SMTP_PORT` | `pts.smtp.port` | `587` | Mail server port |
| `PTS_SMTP_SECURITY` | `pts.smtp.security` | `starttls` | `starttls` \| `ssl` (implicit, 465) \| `none` (loopback relay only) |
| `PTS_SMTP_USERNAME` | `pts.smtp.user` | empty | SMTP login; enables `mail.smtp.auth` |
| `PTS_SMTP_PASSWORD` | `pts.smtp.password` | empty | SMTP password. Never logged |
| `PTS_SMTP_FROM` | `pts.smtp.from` | `PTS_SMTP_USERNAME` | Envelope/display sender |
| `PTS_SMTP_DRY_RUN` | `pts.smtp.dry.run` | `false` | Render and record messages, never send |

`PTS_LOG_DIR` and `PTS_LOG_LEVEL` are the two exceptions to the precedence rule: Logback
configures itself before any Kotlin runs, so a `.env` entry cannot reach them. Set them as real
environment variables — `deploy/run.bat`, `deploy/run.sh` and the Dockerfile all export them.

At startup the app prints the resolved configuration (never a secret) plus, in production,
non-fatal advisories for the combinations that break once the host is public: Secure cookies
without proxy trust, `0.0.0.0` without proxy trust, and no admin bootstrap password. Two more
advisories are checked on every boot regardless of environment, because they are the ways this
feature goes quiet: due-date alerts enabled with no `PTS_SMTP_HOST`, and an SMTP host with no
sender address.

## Deploying

Build once, then run the distribution (`build/install/murray-pts/lib/*.jar` plus launchers):

```bash
./gradlew installDist          # or gradlew.bat installDist
```

### The name: murraypts.mn

`murraypts.mn` does not exist in public DNS yet — checked on 2026-10-12, it answers NXDOMAIN — so
making it reachable is a name-resolution choice, never a code change. Three ways to get there, and
the app is identical afterwards either way:

- **This computer only, through the hosts file (installed here on 2026-10-12).** Windows will resolve
  a name for one machine if that machine's own hosts file says so, and no registration is involved:

  ```powershell
  # one UAC prompt; adds "127.0.0.1<TAB>murraypts.mn", backs hosts up, flushes the resolver
  powershell -NoProfile -ExecutionPolicy Bypass -File deploy\use-local-name.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File deploy\use-local-name.ps1 -Action status
  powershell -NoProfile -ExecutionPolicy Bypass -File deploy\use-local-name.ps1 -Action remove
  ```

  It maps to `127.0.0.1`, not to `192.168.1.193`, because "local domain" here means reachable from
  this PC only — and that isolation is already true regardless of the name: `.env` sets
  `PTS_HOST=127.0.0.1` and `netstat` shows `127.0.0.1:8080 LISTENING`, so nothing on the Wi-Fi can
  reach the app at all. Set `PTS_HOST=0.0.0.0` on a machine when you deliberately want LAN access,
  and put that machine's LAN address in each peer's hosts file with `-Address`.

  Three consequences of this shape: a hosts entry is per-machine, so every colleague who wants the
  name runs the script on their own PC; a hosts line cannot carry a port, so the URL keeps `:8080`
  (`http://murraypts.mn:8080/`) — dropping it needs a proxy on 80 or 443 with a certificate for a
  name public DNS has never heard of, i.e. private-CA territory, and `nginx`, `mkcert` and `caddy`
  are none of them installed here; a session cookie belongs to the host that issued it, so the first
  visit through the new name is a fresh sign-in rather than the one you already had at `127.0.0.1`.
  Each run drops `deploy/hosts.before-<timestamp>.bak` before touching anything, and it counts only
  uncommented lines — the `# 127.0.0.1:8080 murraypts.mn` style notes left in this PC's hosts file
  from earlier attempts do nothing at all, which is exactly why a port in a hosts entry never works.
- **Register `murraypts.mn`.** `.mn` second-level domains are open globally with no local-presence or
  trademark requirement, through accredited registrars, in the region of US$37–72 per year. You then
  add an **A record pointing at the server's public IPv4** in the DNS panel the registrar gives you
  (and an AAAA record only if the host has IPv6).
- **Use a subdomain of the zone Murray already runs, for free and immediately.** `murrays.mn` resolves
  (A `103.43.117.40`) and its zone is served from `admi516687.{earth,venus,mercury,mars}.orderbox-dns.com`
  — reseller-branded nameservers, so the DNS editor lives in whatever account the domain was bought
  in. Adding `pts.murrays.mn` there is a single new A record, and it cannot disturb mail: the M365
  `MX`/`TXT` records and the apex stay exactly as they are. Change `server_name` and the certificate
  path in `deploy/nginx.conf` to match whichever name you picked.

For the two DNS routes the record has to point at a machine with a public address, and the
development laptop does not qualify — it sits at `192.168.1.193` behind Wi-Fi, a private address no
public record can resolve to. The hosts route needs no address at all, which is also the whole of its
limit: it is invisible to everyone else.

Sequence matters for the DNS routes, because a certificate cannot be issued for a name that does not
resolve yet:

1. Create the A record, then confirm it: `nslookup murraypts.mn` should answer the server's IP.
2. Open port 80 on that server long enough for the ACME HTTP-01 challenge, and get the certificate
   (certbot, or swap nginx for Caddy in `docker-compose.yml` and it happens automatically).
3. Start the proxy, then the app with `PTS_ENV=production`, a fresh `PTS_SESSION_SECRET`,
   `PTS_TRUST_PROXY=true`, `PTS_HOST=127.0.0.1`, and `PTS_SECURE_COOKIES` left at its production
   default of true.
4. Sign in at `https://murraypts.mn` and walk the launch checklist below.

The app itself never reads the domain name — it binds `PTS_HOST:PTS_PORT` and takes `Host` and
`X-Forwarded-*` from the proxy — so publishing on a good URL does not move it off port 8080: users
type `https://murraypts.mn`, and the proxy reaches the app at `pts:8080` (compose, published to
`127.0.0.1:8081`) or `127.0.0.1:8080` (a proxy on the same host). Exposing 8080 directly
(`http://murraypts.mn:8080`, no proxy) also works and keeps the port you asked for, but it means the
whole app including `/login` is plain HTTP on the open internet: set `PTS_TRUST_PROXY=false` **and**
`PTS_SECURE_COOKIES=false` or login silently fails, and read the two advisories boot prints about
that combination before going ahead.

### Option A — Docker Compose with TLS (recommended for a VPS)

`docker-compose.yml` starts the app plus nginx: nginx terminates TLS on 80/443, the app listens
on `127.0.0.1:8081` on the host so it can never be reached directly, and the data and log
directories are named volumes.

```bash
git clone <repo-url> && cd <repo-dir>
cp .env.example .env            # compose reads this file for the ${...} values below
# set PTS_SESSION_SECRET=$(openssl rand -hex 32), PTS_ADMIN_PASSWORD=...
sudo mkdir -p deploy/certs      # murraypts.mn.pem / .key — or use Caddy for ACME
edit deploy/nginx.conf          # server_name + cert paths already say murraypts.mn; see "The name" below
docker compose up -d --build
docker compose ps               # pts should become "healthy"
```

Plain `docker run` works too, but then you are responsible for TLS and for not publishing the
port. This variant assumes a proxy on the same host forwards to it:

```bash
docker run -d --name pts -p 127.0.0.1:8080:8080 \
  -e PTS_ENV=production -e PTS_SESSION_SECRET="$(openssl rand -hex 32)" \
  -e PTS_TRUST_PROXY=true \
  -v pts-data:/app/data -v pts-logs:/app/logs murray-pts:2.0.0
```

If you would rather open `http://<host>:8080` directly with no proxy, set
`PTS_TRUST_PROXY=false` **and** `PTS_SECURE_COOKIES=false` — production defaults the cookie to
`Secure`, so over plain HTTP the browser never sends it back and login appears to do nothing.

### Option B — Windows server, no Docker

```bat
deploy\run.bat              start detached (minimised window), wait until the login page answers
deploy\run.bat /foreground  stay attached to this window, Ctrl+C to stop
deploy\stop.bat             graceful stop (falls back to force after 5s)
```

To open it as `http://murraypts.mn:8080` instead of `http://127.0.0.1:8080` on this PC alone, run
`deploy\use-local-name.ps1` once — it is the hosts-file route described under "The name:
murraypts.mn" above, and it needs one UAC approval because Windows keeps that file Administrators-only.

`run.bat` builds the distribution if it is missing, then starts the JVM itself:

```
java -Dfile.encoding=UTF-8 -Duser.timezone=UTC -cp "build\install\murray-pts\lib\*" mn.murray.pts.ApplicationKt
```

Do not use `build\install\murray-pts\bin\murray-pts.bat` on Windows. Gradle's generated launcher
expands all ~70 dependency jars into one `set CLASSPATH=` line and cmd's 8191-character limit
turns that into "The input line is too long". The wildcard classpath above lets the JVM expand
the jars, so line length stops mattering. (The POSIX launcher in the same directory is fine and
is what `run.sh` and the Dockerfile use.)

For an unattended Windows boot, schedule `deploy\run.bat` as a Task Scheduler job ("At startup",
run whether or not the user is logged on); it writes to `%PTS_LOG_DIR%\pts.log` so you keep a
record either way.

### Option C — Linux server, no Docker

```bash
./deploy/run.sh                       # foreground, Ctrl+C stops it cleanly
PTS_DETACH=1 ./deploy/run.sh          # nohup, PID in $PTS_LOG_DIR/pts.pid
./deploy/run.sh --build-only          # just refresh build/install
```

Example unit file (`/etc/systemd/system/pts.service`) if you prefer systemd over detaching:

```ini
[Service]
WorkingDirectory=/opt/pts
Environment=PTS_ENV=production
Environment=PTS_LOG_DIR=/var/log/pts
EnvironmentFile=/opt/pts/.env
ExecStart=/opt/pts/deploy/run.sh
Restart=on-failure
User=pts
```

## HTTPS behind a reverse proxy

The app serves plain HTTP; TLS belongs to nginx/Caddy in front of it. Three settings have to
agree, and the combination is what the production warnings check for:

1. `PTS_TRUST_PROXY=true` — only when a proxy you control is the only way in. On a publicly
   reachable port these headers are attacker-supplied, and trusting them lets anyone dodge the
   login rate limiter by changing them. `deploy/nginx.conf` sets the four headers this expects
   (`X-Forwarded-For`, `-Proto`, `-Host`, and `Host`).
2. `PTS_HOST=127.0.0.1` — bind to loopback so the plain-HTTP port cannot be reached directly.
3. `PTS_SECURE_COOKIES=true` (the production default) — requires real HTTPS end-to-end for the
   browser. If you test production settings over `http://localhost`, the browser will never send
   the cookie back and login will appear to do nothing; set `PTS_SECURE_COOKIES=false` for that
   one test.

In production the app also sends `Strict-Transport-Security: max-age=31536000;
includeSubDomains`, so only enable it once the certificate is valid and HTTP redirects to HTTPS.

## Data, backups and logs

Everything stateful is one SQLite file plus its WAL sidecars, in `data/` (or `/app/data` in the
container). Flyway applies `src/main/resources/db/migration/V1__init.sql` and
`V2__due_date_alerts.sql` on boot — the first time an alerting build starts against an existing
database, that is one `CREATE TABLE`, and no existing row is touched. Migrations are additive, so
keep every `V*.sql` that has ever shipped.

Back up with SQLite's own snapshot mechanism rather than copying the file while the app writes to
it:

```bash
sqlite3 data/pts.db "VACUUM INTO '/backups/pts-$(date +%F).db'"          # online, consistent
docker compose cp pts:/app/data /backups/pts-data-$(date +%F)            # containerised
cp -a data/pts.db data/pts.db-wal data/pts.db-shm /backups/manual/       # only while stopped
```

Restore = stop the app, replace `data/pts.db` (delete stale `-wal`/`-shm` first), start it again.
Logs roll at midnight and at 10 MB, keep 14 days, and cap at 200 MB
(`logs/pts.log`, `pts-YYYY-MM-DD.N.log.gz`); tune via `PTS_LOG_DIR`/`PTS_LOG_LEVEL`.

## Security model

- **Passwords** — bcrypt (cost 12) in `users.password_hash`. Legacy JSON accounts with no real
  password get the employee number plus the must-change flag enforced at the route level.
- **Sessions** — random UUID tokens stored in the `sessions` table with an absolute expiry
  (`PTS_SESSION_TTL_HOURS` from login, not from the last click), `HttpOnly`, `SameSite=Lax`,
  `Secure` in production. Logout revokes the current session; a password change revokes every
  session for that account. Expired rows are deleted the first time they are read.
- **Rate limiting** — in-memory per client IP (default 5 failures/60s), and the key is only the IP,
  not the account. Two consequences worth knowing on a real network: everyone behind one office or
  camp NAT shares a single counter, and anonymous failed attempts can therefore temporarily block
  legitimate colleagues from the same address. Raise `PTS_LOGIN_MAX_ATTEMPTS` if that bites.
  Single-node and best-effort: the window resets on restart and is not shared between instances.
- **Headers** — CSP `script-src 'self'` (all app JS/CSS is in `/static`, no inline scripts or
  styles except presentation attributes), `frame-ancestors 'none'`, `nosniff`, HSTS in production.
- **Auth** — position/access level (`Viewer`, `Manager`, `Admin`) is checked on the route and in
  the repositories that write.

Known gaps, deliberately left out of this launch scope:

- `security/Csrf.kt` is implemented and tested but **not wired into any route**. POSTs currently
  rely on the `SameSite=Lax` session cookie. Treat this as the next hardening task before the app
  faces the open internet.
- The rate limiter and session cache are per-process; running more than one instance behind a
  load balancer needs shared state.
- SQLite is a single-writer store. Fine for a few dozen concurrent users on one host.

## Due-date alerts

A sweep runs in-process every `PTS_ALERT_INTERVAL_HOURS` (24 by default), starting
`PTS_ALERT_STARTUP_DELAY_SECONDS` (30) after boot, and emails the people responsible for work that
is not finished while its due date is close.

What counts as "not finished, and coming":

| Item | Alerted when | Who receives it |
| --- | --- | --- |
| Project | status is not `Completed` **and** progress < 100 **and** the due date is within `PTS_ALERT_LEAD_DAYS` (7) — or already past | the project's assigned manager plus every assignee |
| Task | status is not `Completed` and the due date is within the same window | the task's assignee plus the manager of its project |

An item that reaches its due date unfinished switches to overdue wording
(`[PTS] OVERDUE by 12 days - …`) and keeps alerting once a day until somebody finishes it — unfinished
work going quiet was the problem being solved, so the window has no end. Items with a blank or
non-`yyyy-MM-dd` due date are skipped rather than guessed at, and an item with nobody attached is
reported in the sweep output instead of being dropped silently.

Addresses are the `users.email` values from Access control; people are identified in the message by
their roster name. A person on the roster without an email still gets a ledger row saying so, which
is how a missing address becomes visible instead of turning into a silent no-mail.

Delivery needs an SMTP server (`PTS_SMTP_HOST` + credentials). Until one exists the feature is still
verifiable, because the rules never touch the network directly: with no host, or with
`PTS_SMTP_DRY_RUN=true`, every message is rendered in full, written to `logs/pts.log`, and recorded
in the `due_date_alerts` ledger with `delivered = 0`. Boot also says which mode it is in, in the
config summary and as a `Launch check:` advisory.

To check transport alone — one message, no rules, no ledger — there is `POST /app/alerts/test`. See
"Verifying one send" below.

### Sending through Microsoft 365

`murrays.mn` is an Exchange Online domain (MX `murrays-mn.mail.protection.outlook.com`, SPF
`v=spf1 include:spf.protection.outlook.com -all`), so the tenant's own submission endpoint is the
shortest path and no extra DNS is needed — a message submitted as `pts@murrays.mn` already passes SPF
for that domain:

```ini
PTS_SMTP_HOST=smtp.office365.com
PTS_SMTP_PORT=587
PTS_SMTP_SECURITY=starttls
PTS_SMTP_USERNAME=pts@murrays.mn
PTS_SMTP_FROM=pts@murrays.mn
PTS_SMTP_PASSWORD=<that mailbox's password>
PTS_SMTP_DRY_RUN=false
```

Two prerequisites live in the Microsoft 365 admin centre, not in this app:

- **The mailbox has to exist.** `pts@murrays.mn` needs to be a real user or shared mailbox in the
  tenant, or every send is rejected.
- **Authenticated SMTP has to be enabled on it.** Microsoft ships new tenants with it off (per-user
  setting, or `Set-Mailbox pts -SmtpClientAuthenticationDisabled $false`). This mailer speaks plain
  username/password LOGIN over STARTTLS only — there is no OAuth support — so a tenant that enforces
  modern authentication for that mailbox needs an app password, or the alternative below.

The no-password alternative is an Exchange Online **direct-send connector**: create an inbound
connector restricted to the VPS's fixed public IP, point `PTS_SMTP_HOST` at
`murrays-mn.mail.protection.outlook.com` with `PTS_SMTP_PORT=25` and `PTS_SMTP_SECURITY=none`, and
leave the username/password empty. Nothing secret is then stored on the app server, at the cost of
admin-centre setup and a cloud provider that has to allow outbound port 25 (many block it by default,
and it is blocked from a normal office/laptop network — the 587 route above is the one that works from
a developer machine).

With that configured, prove a real send before a sweep mails the whole roster:

```bash
curl -b /tmp/pts.txt -X POST 'https://<host>/app/alerts/test?to=someone@murrays.mn'
```

It answers a short plain-text report — `DELIVERED`, `NOT SENT` (still in log-only mode), or `FAILED`
with the server's error — and it deliberately does not touch `due_date_alerts`, so testing the
transport cannot spend anybody's alert for the day. Omit `?to=` and it sends to the signed-in admin's
own `users.email`, which is the safe default: a mistyped command mails yourself, not a colleague. It
takes the credentials from the running process, so it also proves `.env` was read correctly. Boot
prints an advisory if `PTS_SMTP_HOST` is set while `PTS_SMTP_PASSWORD` is empty, which is the
half-configured state that would otherwise only show up as failed alerts.

The ledger is what makes "once per day" true. Its `UNIQUE (kind, item_id, recipient_emp_no,
alert_date)` index is the rule, so restarting the service, or pressing "run now" five times, cannot
mail anybody twice for the same item on the same day — the later attempts just report
`already sent today`. `PTS_ALERT_LEDGER_KEEP_DAYS` (90) trims it after each sweep; set `0` to keep
everything.

Only Admins can drive it from the outside, and all three endpoints answer in plain text so they work
with `curl` (log in first, keep the cookie):

```bash
curl -c /tmp/pts.txt -X POST -d 'employeeNumber=EMP0000&password=…' https://<host>/login
curl -b /tmp/pts.txt -X POST https://<host>/app/alerts/run          # sweep now + report
curl -b /tmp/pts.txt 'https://<host>/app/alerts/recent?limit=20'    # last ledger rows
curl -b /tmp/pts.txt -X POST https://<host>/app/alerts/test         # one test mail, no ledger
```

Everything about it is per-process: the timer lives in the app, and it reads the same SQLite file the
app owns. Run one instance. If you ever scale to several, set `PTS_ALERTS_ENABLED=false` on all but
one (or everywhere, and trigger sweeps through the admin endpoint); the ledger would keep duplicate
mailing safe either way, but only one instance should be doing the work.

## Launch checklist

Work through this before pointing users at the box. Item 1 is the only one that is not just
configuration, and it is the one to settle first: it decides what you can safely push.

1. **Git hygiene (settled on 2026-10-12 for `github.com/anand112/MurrayPTS`).** What was done, and
   what is still open:
   - The project was published as a **fresh single-commit tree** in a private repo on a new remote
     (`github`, branch `main`); `origin` still points at `anand112/MMS` and local `main` still tracks
     it. Nothing credential-bearing is in the new commit: `data/pts.db*`, every `data/*.json.*` backup
     (`.backup-predelete`, `.pre-run-backup-*`, `.migrated-*`, `.pre-synthetic`), `.env`, `logs/`,
     `build/`, the `deploy/` hosts snapshots and the nested `MMS/` clone are all ignored and were never
     added. Check with `git ls-tree -r --name-only github/main -- data/`, which must list only
     `.gitkeep` and `pts-data.json.example`.
   - `data/pts-data.json.example` **is now synthetic**: same shape and same counts (16 projects, 5
     tasks, 23 team, 22 positions, 6 accounts, 3 forecast/lesson/todo-list rows), invented names,
     departments, roles, project titles and free text, with the `EMP0000…` employee-number series kept
     so the importer's foreign keys still resolve. `JsonImporterTest` reads the shipped file and is
     green. The real legacy export it was derived from stays on disk, untracked, as
     `data/pts-data.json.example.pre-synthetic`.
   - **Still true of the old repo:** `origin` keeps commit `69397ad`, which contains
     `data/pts-data.json.backup-predelete` with legacy account rows including their `password` values.
     That history was not rewritten and nothing was force-pushed anywhere. If MMS is ever shared or
     made public, rotate those accounts first.
   - `.gitignore` gained `MMS/` and `build_old/`, and `!gradle/wrapper/gradle-wrapper.jar` moved below
     the `*.jar` rule — in gitignore the last match wins, so as it sat before line 44 it could never
     take effect, which is why the wrapper jar had never been committed and a fresh clone could not run
     `./gradlew`.
2. `./gradlew test installDist` is green (38 tests).
3. `.env` exists on the host, is not world-readable, and sets:
   - `PTS_ENV=production`
   - `PTS_SESSION_SECRET` — fresh `openssl rand -hex 32`, unique per environment
   - `PTS_ADMIN_PASSWORD` — otherwise the super admin's password is `EMP0000` until someone logs in
4. The name resolves before anything asks for a certificate. Public route: `nslookup murraypts.mn`
   (or `pts.murrays.mn`, if you used the free subdomain) answers the server's public IP, and
   `deploy/nginx.conf`'s `server_name` matches it exactly. Hosts route (this PC only, no certificate
   involved): `deploy/use-local-name.ps1 -Action status` prints an active `127.0.0.1 murraypts.mn`
   entry, `Resolve-DnsName` agrees, and `http://murraypts.mn:8080/` answers 200.
5. On the public route, TLS is actually terminated: nginx or Caddy points at a valid certificate,
   HTTP redirects to HTTPS, and `PTS_TRUST_PROXY=true` with `PTS_HOST=127.0.0.1`.
6. Only 443 (and 80 for the redirect) is reachable from outside; host firewall and cloud security
   group both closed on 8080/8081. If you publish `http://murraypts.mn:8080` directly with no proxy
   instead, this item inverts on purpose: 8080 open, every other port closed, `PTS_TRUST_PROXY=false`
   and `PTS_SECURE_COOKIES=false` — and then the login form, the rate limiter and the admin alert
   endpoints are all reachable by anybody on the internet over plain HTTP, which is exactly what the
   two boot advisories about that combination are for.
7. First sign-in as the admin: the app lands on Settings, forces a password change, and then
   `/app/projects` opens. Sign out and back in once to confirm sessions persist.
8. `PTS_SEED_DEMO` is `false` and `data/pts-data.json` is either imported or absent — no demo
   accounts in the live system.
9. Backup job exists and you have restored from it once (`logs/` and `data/` both covered).
10. Due-date alerts are in a deliberate state. Below, `<host>` is the name you settled on in item 4,
    with its port — `https://murraypts.mn` behind the proxy, or `http://murraypts.mn:8080` on a
    hosts-file PC. Either:
   - **sending** — `PTS_SMTP_HOST`/`PTS_SMTP_USERNAME`/`PTS_SMTP_PASSWORD`/`PTS_SMTP_FROM` set,
     `PTS_SMTP_DRY_RUN=false`, and `curl -b jar -X POST https://<host>/app/alerts/test` reports
     `DELIVERED` with the test landing in that mailbox — that is the transport settled, independent of
     any rule; then `curl -b jar -X POST https://<host>/app/alerts/run` reports `delivered` for at
     least one alert that a real mailbox received; or
   - **not sending** — `PTS_ALERTS_ENABLED=false`, so nobody is waiting for mail that will never come.

   Rehearse first with `PTS_SMTP_DRY_RUN=true`: the report and the `due_date_alerts` ledger fill in,
   nothing touches the network. Run it twice — the second report must say `already sent today`, which
   is the once-per-day rule working. Also confirm `GET /app/alerts/recent` reads back those rows, and
   that the two accounts you alert (manager, assignee) have `users.email` set in Access control.
11. `curl -s -D - -o /dev/null https://<host>/` shows HSTS and a CSP whose `script-src` is `'self'`
    with no `unsafe-inline`. (Use GET, not `curl -I`: the routes are GET-only, so `HEAD` answers 405.
    On the hosts-file route there is no TLS, so this item and HSTS do not apply — that gap closes only
    when the name moves to real DNS with a certificate.)
12. Disk: log rotation is on, so plan for the database file's growth instead — the alert ledger is
    trimmed to `PTS_ALERT_LEDGER_KEEP_DAYS` (90) after each sweep, so it is the project data that grows.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Login form submits and the page just redraws | Secure cookie over plain HTTP. Use HTTPS or `PTS_SECURE_COOKIES=false` |
| Boot aborts with "Refusing to start in production" | `PTS_SESSION_SECRET` missing or shorter than 32 chars |
| "The input line is too long" from `murray-pts.bat` | cmd classpath limit — use `deploy\run.bat` |
| `SQLITE_BUSY`, or the app will not open the DB after a crash | Remove stale `data/pts.db-wal`/`-shm` **only when the app is stopped** |
| Everyone is locked out after failed logins | Throttle: wait 60s (window), or raise `PTS_LOGIN_MAX_ATTEMPTS` |
| Login throttle never triggers on a proxied host | Real client IP needs `PTS_TRUST_PROXY=true` and proxy headers |
| A user cannot open any page except Settings | Must-change-password gate: they need to save a new password in Settings |
| Pages show unstyled HTML after an upgrade | Static assets are cached by path — hard-reload; check `/static/js/app.js` returns 200 |
| Some pages 500 with `NoClassDefFoundError` / `ClassNotFoundException`, others fine | The running JVM was started from `build/install/…` and a later `./gradlew installDist` replaced those jars underneath it, so any class not yet lazy-loaded is gone. Restart the app from the fresh dist; don't rebuild over a live instance |
| No alert mail arrives, but the log shows the messages | Expected with no `PTS_SMTP_HOST` or with `PTS_SMTP_DRY_RUN=true`. Boot logs a `Launch check:` line naming this |
| `POST /app/alerts/run` answers 403 | The session is not an Admin. Alerts are operator-only by design |
| `POST /app/alerts/run` reports `0 attempted, N already sent today` | The dedupe is working: that person already got that item's alert today. Wait for the next day, or trim the ledger row deliberately |
| Mail sends fail with `AuthenticationException` | `PTS_SMTP_USERNAME`/`PTS_SMTP_PASSWORD` are wrong, or the provider needs OAuth instead of plain auth (not supported here — use a relay that accepts it) |
| Mail sends fail with a TLS/handshake error | `PTS_SMTP_SECURITY` does not match the port: `ssl` for 465, `starttls` for 587 |
| `POST /app/alerts/test` answers `NOT SENT — SMTP dry run` | Expected until `PTS_SMTP_DRY_RUN=false`; the report is proof the config is being read, not that mail failed. Boot also warns when `PTS_SMTP_HOST` is set with an empty `PTS_SMTP_PASSWORD` |
| M365 rejects the login (`535 5.7.3 Authentication unsuccessful`) | Authenticated SMTP is disabled on the `pts` mailbox, or the tenant enforces modern authentication. This mailer speaks plain LOGIN over STARTTLS only — enable `Set-Mailbox pts -SmtpClientAuthenticationDisabled $false`, use an app password, or switch to the direct-send connector |
| Connecting to `smtp.office365.com:587` hangs or is refused | Outbound 587 is blocked from that network. 587 works from an office/VPS and 25/465 usually do not from a developer machine — test the port before blaming the credentials |
| `murraypts.mn` will not connect right after the hosts edit | Lookups get cached twice, by the OS and by the browser. `deploy\use-local-name.ps1 -Action status` must show the active entry and the same IP under `resolves`; then flush (`ipconfig /flushdns`, done by the script) and retry in a fresh tab |
| The hosts edit "access denied", or the file will not save | Windows grants `BUILTIN\Users` read-only on `System32\drivers\etc\hosts`. Run the script so it asks for elevation once instead of editing by hand |
| A hosts line was added but nothing changes | It is either commented out (`#` starts a comment) or it carries a port: hosts has no port field, so `127.0.0.1:8080 murraypts.mn` can never match. The entry is `127.0.0.1<TAB>murraypts.mn` and `:8080` belongs in the URL |
| The name works here but a colleague cannot open it | Expected: a hosts entry is per-machine, and `127.0.0.1` means "your own PC". Give them real DNS, or their own entry for this PC's LAN address plus `PTS_HOST=0.0.0.0` here — the app currently binds loopback, so it is unreachable from the Wi-Fi by design |
| A manager or assignee gets nothing | Their account has no `users.email`, or the item's due date is blank/malformed. Both are recorded in `due_date_alerts` with `delivered = 0` |
| Alerts stopped after moving to a second instance | The scheduler is in-process and single-node. Keep one alerting instance, or `PTS_ALERTS_ENABLED=false` and use the admin endpoint |

## Tests

```bash
./gradlew test
```

38 tests in 10 classes: Flyway-on-SQLite migration + FK enforcement + idempotent migrate, the legacy
JSON importer, role resolution and authorization, CSRF token forgery, the login throttle window,
the full auth path against a real SQLite file (bcrypt login, session issue/resolve/revoke,
revoke-on-password-change, expiry), the must-change-password gate, bootstrapping an empty
database (super admin exists, is gated, repeat boots are idempotent), and the due-date alert rules
against a real SQLite file with SMTP forced into dry run — in-window alerting to manager and
assignees, task alerts copied to the project manager, silence at 100%/`Completed`, the lead-window
edge, overdue wording, blank and malformed due dates, items with nobody attached, recipients with
no email address, the same-day dedupe, the ledger's UNIQUE index rejecting a duplicate row, and the
delivery test (dry run sends nothing and writes no ledger row, its body reports the configuration it
used without the password, `emailFor` reads the address on file).

There is still no HTTP-level test: the alert endpoints, like every other route, are verified by
booting the shipped distribution and driving it with `curl` (see the launch checklist).

## Project layout

```
src/main/kotlin/mn/murray/pts/
├── Application.kt              routes, plugins, session guard, startup (incl. the alert scheduler)
├── <Page>.kt                   one kotlinx.html view per page
├── config/AppConfig.kt         every tunable, in precedence order
├── db/                         DatabaseFactory, Migrations, Exposed tables
├── db/migration/               JsonImporter, LegacyNormalizer, DemoSeeder
├── domain/                     plain model classes
├── repository/                 SQL access, one object per aggregate (+ AlertRepository)
├── security/                   PasswordHasher, Authorization, Csrf, LoginRateLimiter
├── service/                    AuthService, the write-side services, and Mailer +
│                               DueDateAlertService + DueDateAlertScheduler
└── web/plugin/SecurityHeaders.kt
src/main/resources/
├── db/migration/V1__init.sql   Flyway schema
├── db/migration/V2__due_date_alerts.sql   the alert ledger
├── logback.xml                 console + rolling file appenders
└── static/{css,js,assets}/     extracted front-end assets (CSP: no inline)
```

## License

The repository carries an MIT `LICENSE` ("Copyright (c) 2026 MURRAY Mining Services Mongolia"),
inherited from the v1 scaffold. For an internal tool holding employee records, decide before
publishing whether that is what you want — an "unlicensed / internal use only" file is the usual
choice, and I left the existing one untouched.
