# MURRAY Project Tracking System (PTS)

A small internal web app for MURRAY Mining Services Mongolia: projects, task assignment, the
team roster, forecasts, lessons learned, a calendar, and per-position access control. Built with
Kotlin + Ktor, server-rendered HTML, and a single SQLite file.

This is **v2**. The v1 prototype (JSON file store, plaintext passwords, in-memory sessions) has
been replaced: the app now runs on SQLite through Exposed with Flyway migrations, bcrypt
passwords, DB-backed sessions, and a hardened static-asset/CSP layer. `ARCHITECTURE.md` documents
the module design and `STAGE-HISTORY.md` holds the stage-by-stage record behind it (Stages 6–115,
one section each, indexed at the foot of `ARCHITECTURE.md`); this file is about running it.

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
- Employee numbers are **case-insensitive**: `emp0000`, `EmP0000` and `EMP0000` sign in to the same
  account. Surrounding spaces are ignored too. The login screen says so and shows a lowercase example
  (`emp0009`); it also turns off auto-capitalization and spellcheck on that box, so a phone or a
  remembering keyboard cannot quietly break the typed name. Passwords remain **case-sensitive**; a
  default employee-number password uses capitals.
- A default password is recorded by the `must_change_password` flag, but since **Stage 99** there is
  no forced-change gate. An administrator sets or resets passwords from the Team sheet's popup.

Two optional data loads, both one-shot and both refused unless the database is empty:

```bash
# Import the v1 JSON store (data/pts-data.json by default), then rename it to *.migrated-<ts>
PTS_DATA_FILE=/path/to/pts-data.json ./gradlew run

# Load the demo dataset (6 users, 19 projects, 10 tasks) into a fresh DB — dev only
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
| `PTS_ALERTS_ENABLED` | `pts.alerts.enabled` | `true` | Due-date reminder sweep and the inbox it feeds |
| `PTS_ALERT_LEAD_DAYS` | `pts.alert.lead.days` | `7` | Days before a due date that alerting starts |
| `PTS_ALERT_INTERVAL_MINUTES` | `pts.alert.interval.minutes` | `5` | How often the sweep runs |
| `PTS_ALERT_STARTUP_DELAY_SECONDS` | `pts.alert.startup.delay.seconds` | `30` | Delay after boot before the first sweep |
| `PTS_ALERT_LEDGER_KEEP_DAYS` | `pts.alert.ledger.keep.days` | `90` | Days of `due_date_alerts` history to keep (`0` = never trim) |
| `PTS_NOTIFICATION_KEEP_DAYS` | `pts.notification.keep.days` | `30` | Days of `notifications` inbox rows to keep (`0` = never trim) |
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

## The shell on a small screen

`app.css` has one breakpoint, `@media (max-width: 900px)`. Below it the sidebar becomes a full-width
off-canvas drawer opened by the hamburger beside the page title; the page's card boards — the five status
columns and a project's lane board — fold to two fluid columns a row; tables keep their percentage columns
and wrap rather than scroll sideways. Above 900px nothing changes: the hamburger and its backdrop are
`display: none`.

The open drawer is one class, `body.nav-open`, plus `html.js`, which `app.js` puts on the document before
the first paint. Press the hamburger to slide the sidebar in from the left; press the dimmed backdrop,
press Escape, follow a link, or grow the window past 900px to close it. Opening moves focus into the
drawer and closing returns it to the button, whose `aria-expanded` and `aria-label` follow the state. With
JavaScript off there is no hamburger to press, so the sidebar stays in the flow above the page — a smaller
shell, but a usable one.

Both static files changed in **Stage 119**, so an open tab needs **Ctrl+F5** before the breakpoint or the drawer
will look absent.

## Projects and categories

Every project belongs to exactly one of nine categories, and each category leads its project numbers
with one letter:

| Letter | Category |
| --- | --- |
| `M` | Fabrication/Manufacturing |
| `R` | Repair/Maintenance/other services |
| `S` | Site projects |
| `L` | Labor supply |
| `D` | Distributor product sales |
| `E` | Engineering |
| `F` | Internal works |
| `G` | Goods sales |
| `O` | Other |

The Projects board has one tab per category. `+ New project` opens a popup on the same page and asks
for the category, the project number, a description, the client, start and end dates, a status
(defaults to **Not started**), revenue and a manager — those nine fields, plus since **Stage 109** a currency
picked under the amount (tugrik, US dollar or yuan; tugrik unless you choose otherwise) — and one line about buckets. The status picker reads
as the progression — **Not started, In progress, On hold, Blocked, Completed** — because **Stage 96** took it off the
most-used-choice-first order it had, and renamed the running stage to the word the cards already used: a project and a
card that are both under way now both say **In progress**, so nothing anywhere translates one into the other. It does not
ask *which* of the category's buckets the project has — that question is withdrawn, at Stage 92 and again at Stage 100 —
but since **Stage 104** it asks one that is not the same question: the **Buckets** line prints the chosen category's own
Settings rows, read-only, one row per category with the chosen one shown (or *"— none listed in Settings yet —"*), and
below them a box that adds a column to **this** board. Its hint is the whole rule — *"The board opens with the buckets
above, then any you add here. One added here is this project's own column and changes nothing in Settings."* — and there
is deliberately no *also offer it to new projects of this category* tick on this form, because mid-create that tick would
mean "and every future project of this kind". Settings stays the only place the list itself is edited. The manager is optional, but it is the only place
to name one: Stage 30 removed the picker from the project page, so a project created without a manager
runs without one until management makes it again.

A project arrives with a board cut from its category's **Settings** list and nothing else (**Stage 100**), then
whatever the create popup named after it (**Stage 104**): exactly the rows Settings › Project bucket settings lists for
that category, in that order, written in the same breath as the project row, because every card written onto a project
files itself in one of them (see
[The project page](#the-project-page)). A category that lists no bucket and is not given one at the popup cannot make a
project: the create is refused and prints *"This category has no buckets yet. Add one above, or list them in Settings
under Project bucket settings."* under the Buckets line, because a board with no column has nowhere to file its first
card — and since **Stage 104** the reader can fill that from the popup, which gives that one project its column and
leaves the category as un-listable as it was for the next project. A name typed there that the category already lists is
refused, not quietly dropped (*"$name is already one of <category>'s buckets — this board gets it from Settings."*), and
so is one the form names twice, because a chip that vanished from a board the page says it created is the failure this
line exists to avoid. Adding a bucket in Settings puts the column
on every existing board of the category and on every board made afterwards (**Stage 98**); taking one off stops new
boards getting it and leaves the boards that carry it alone. The ask that got here was plain — *"so we dont have to take
default buckets when we create a new project. As we setting buckets on the settings function"* — and Stage 100 answered
it by making Settings the only source: the earlier history (Stage 48's six, Stage 75's three phases named once as
`DEFAULT_PROJECT_BUCKETS`, Stage 92's tick-list removed from the form) no longer describes a new board. **Since Stage 75
the project page is its bucket columns, before its first card and after it**: the five status columns Stage 52 cut that
board into are gone from it (they are the Assigned tasks page's columns, where they stay), a card's status is set in the
card's own editor, and no column on a project is a status.
A project keeps any lane its manager added on top of the Settings list — the list is what a board opens with, not a
ceiling — and those print after them, in the order they were added. **Stage 104** lets the create form name one of them
before the board exists at all: same place in the order, same absence of a colour slot, and the same reach — that board
alone. `TaskService.addLane` and `removeLane` keep their
rules — a name trimmed and bounded to 40 characters, no two lanes of one name on the same board ignoring case, cards
un-filed rather than deleted, one lane always left — and every lane is an ordinary lane: `LaneError.STANDARD` was
retired at Stage 75, and **Stage 100** removed the last phase-name special case, so a manager may retitle any column to
*Installation*, or take it off a board that has nothing filed in it, exactly as they can an extra.
`POST /app/projects/{id}/buckets` with its `delete` and `rename` twins stay
wired, and are a project's own manager's to send as much as management's — `TaskService.mayEditLanes` is
`mayAddTasks`, which is management **or** the account named on that project. The whole-set write is
`POST /app/projects/{id}/buckets/save`, and since **Stage 79** its only form on a project page is the one inside the
dashed **+ Add bucket** popup: the *Project buckets* panel above the board, which carried a second copy of that form,
is gone. Since **Stage 80** both of that popup's halves also post `colour`, a slot from `0` to `5` into the
stylesheet's six hues — `TaskService.renameLane` stores it on the lane's own `colour_slot` (V17) and reads anything
outside the six as *no choice*; since **Stage 83** a *new* lane or catalogue row is written with no slot, so it wears the
colour of its place in the run. The fold beside it is not a
write at all: `?showdone=<column id>`, repeated, on the project page's own GET. The
`V7__project_task_buckets.sql` migration first gave lanes to the projects that already existed,
`V10__standard_project_lanes.sql` brought those boards onto the six, and `V16__three_phase_buckets.sql` brought every
board that existed at that point onto the three: a legacy lane holding no card is deleted, a later duplicate of one of
the three gives way to the earliest row, the three take positions 0–2 by name, anything else on the board stands after
them, and **no card is touched** — a lane with cards in it keeps its name and its cards rather than being dropped and
un-filing them, which is why a board that was using *Fabrication/Manufacturing* as a working column still shows it, now
as an editable lane like any other. That is history on the live boards: since **Stage 100** no new board follows V16's
rule, and no create path reads `DEFAULT_PROJECT_BUCKETS` — neither the seeding of a board (Stage 100) nor, since
**Stage 111**, the Sub-category list of the general board's `+ New task` popup, which reads the columns of the project
chosen in that popup. What still falls back to the three names is `TaskForm.laneChoices`, which is an *edit* half's
list (a card being refiled by management) and is resolved by name against the board that receives it. The legacy JSON
importer still writes the three for every
project it imports, because an import predates any Settings list for the category it is filing.

The bucket picker now lives on the project page alone — the **+ Add bucket** popup and a column's `⋯` — and two rules
are worth knowing before the first
save. A name typed into its *New bucket* box goes on **this project's board** and, since **Stage 77**, no further: the
box under it asks *"Also make it a default bucket for \<category\>"*, and only when that is ticked does the word also
join the category's catalogue so the next project of the category is offered it. Ticked or not, the save's closing
sentence says which of the two happened. See [The project page](#the-project-page) for the three consequences of that
rule — who is shown the tick, why an offer is not an order, and what a hand-built tick costs.
The popup posts every lane the board already holds plus the new name, which is the shape the save reads, and nothing
about the name is judged in JavaScript — the service owns the rules. A save whose
answer cannot be taken at its word writes **nothing**: the popup comes back open on the same board,
the typed word still in its own box, and the reason printed under it — *"A bucket name
is 40 characters or fewer."*, *"… is already a bucket on this project."*, *"Only management adds a new bucket to the
… catalogue."* (the last one answers Stage 77's ticked box, never the name on its own — an unticked name is simply this
project's column, and a caller who is not management is not shown the box that would ask),
*"… still has cards in it. Move them to another bucket before unticking this one."*, and *"A board keeps at least one
bucket — tick one to leave standing."* None of them asks whether a name is one of the three phases any more: **Stage
100** deleted that last case, so *Engineering* is a name like any other and is refused only when the category already
lists it.

Creating a project, editing or deleting one, choosing who manages one, ticking its buckets and deleting a card are
**management-only** — the archive writes are also open to the account that
project names as its manager, on the same `mayEditLanes` line as its columns, though since **Stage 106** no page
prints their controls (see *The project header* below):
a `Viewer` sees neither the `+ New project` button nor the popup nor an editable header strip nor the
**Delete task** at the end of a card's note line (**Stages 72 and 84**), and `POST /app/projects`,
`POST /app/projects/{id}` (a header-strip save), `POST /app/projects/{id}/delete`,
`POST /app/projects/{id}/manager`, `POST /app/assigned-tasks/delete`, `POST /app/tasks/archive`,
`POST /app/projects/{id}/archive/restore` and `POST /app/projects/{id}/archive/delete` turn a
hand-built request from a `Viewer` away before the form is read. Adding a task is management's, with one
narrow exception: **the account a project names as its manager may write the first card for that project,
from that project's page**. `TaskService.mayAddTasks` compares employee numbers against
`projects.manager_emp_no` — never the manager's display name, so a rename cannot move the right to a
namesake — an unmanaged project grants it to nobody, and the general board grants it to nobody but
management. The card they write is pinned to the page it came from: its project picker is one greyed option,
and `TaskService.create` reads the project from the page rather than from the form, so "add to *this*
project" cannot be turned into "add to any project" by editing the POST. **Stage 49** widened that one exception to
the whole of a project's board: the same `mayAddTasks` answer is also who may **move a card between that
project's buckets** — a drag, and the drop it posts (`TaskService.moveToLane`) — so a project's manager files its work in a different column
without being management. **Stage 77** gives that same answer the board's other half: the account a project names as its
manager may put a **new bucket** on that board too, and it is theirs alone — the tick that would also make the name a
default for the category is drawn for management only, and a hand-built `also_type` from anybody else is refused at the
field rather than written. Re-shaping one board is the manager's; deciding what every future board of that category is
offered stays the company's. **Stage 52** cut that board to *status* columns for a while and a drag there meant a status
change; **Stage 75 put the buckets back**, so on a project page a drag is once again the re-filing it was before, done
in place by `kanban.js` through `POST /app/tasks/bucket`, while the five statuses live on the Assigned board, where
a card's owner drags it along them. What stays open to everybody is the rest of their own work: **dragging** a card they are
assigned to along the statuses — the arrows that were the second path to that same write went in **Stage 103** — ticking the steps on it, and their own To-do lists. Moving is no longer on that list,
on either board — the assignee who is not also a mover prints no `draggable` and cannot put a card in a lane or a
column it was not already in; on a project page that owner's one standing control is the **✓ Done** / **↺ Reopen**
button under the card. The **Other tasks** page is now management's whole function (Stage 51): its `+ Add task`
line, its head `+ Add bucket` button, its `⋮` and its **Bucket** dropdown, and the sidebar links to it and to **Access control**
and **Settings**, all belong to management (`Authorization.canManage`) and are withdrawn from a `Viewer` — the link, the
page and every write behind it are gated, not just the button. The one personal path a `Viewer` keeps: their own
backlog card, which they still reach here through a notification, on a board scoped to nothing but their own cards and
with none of the column tools. Handing work to *another* person — writing a card pinned to them and started as
**Not started** — is management's, done from the **Team** sheet's Assign panel; see [Other tasks](#other-tasks). Since
**Stage 90** any reader may write an Other task **to themselves** from the Assigned page through that section's
`+ Add task` line; the service pins the writer, so a hand-built POST naming a colleague still writes the caller's own card.

An empty board says what is missing instead of showing a row of empty columns of "Nothing here.", and says it only to
somebody who can fill it: when the cards this reader can see number zero and they may write one, the board
prints a `.board-empty` block with the sentence about where the card will land and the same `+ New task`
opener the page head carries — one renderer, one mechanism, no new script. A `Viewer` who manages nothing
gets neither the block nor an opener. **A project page is the one whole-project board** (Stage 56): every card filed
under that project is shown to every reader — management, the project's manager *and* a plain `Viewer` alike — because
standing on a project the question is *what does this project hold and who is doing each card*, which one person's
slice cannot answer. That widening was the **list only, never the rights** until **Stage 86**, when the read half
followed: a `Viewer` now clicks any card on that page and gets a **read-only** popup — its fields, its plan and its
log, with no Save, no tick box and no note box — while the drag handle and the lane board's **✓ Done / ↺ Reopen**
stay with management and the card's holder. Their **own** cards on that same board still open and walk for them.
Every other task
view — the general Assigned board (personal, Stage 53), the sidebar count, the Calendar (personal for every role since
**Stage 64**), the Team drill-down — stays scoped to the reader.

The board has one way in and it is the card itself: clicking its body asks the page for that card's
editor (`?edit=42`) and `app.js` swaps the popup that comes back into `#taskModal`, so opening an editor,
and reopening it after a field is rejected, never reloads the board. `+ New task` is the same mechanism
aimed at a blank form (`?new=1`). Both are GET forms onto the page they sit on, and a board prints exactly
one hidden editor form for the whole column set, whose `edit` field the click fills — which is why forty
cards still ship one set of fields. Stage 24 deleted the menu this used to go through: the `Edit` button
and the status dropdown that used to hang off a card are gone, because a click on the card was already the
way to reach the editor, and a column is a field of that editor rather than a thing to choose separately.
Every card a reader can see carries a click target since **Stage 86**: a colleague's card opens the same popup
**read-only** — heading *Task T-000NN* rather than *Edit task*, every field greyed, the plan drawn stopped and only
the log to read, no Save and a **Close** button instead of *Cancel*. Management edits any card and every field on
it; the person a card is assigned
to opens the same popup and may change its start date, due date and priority, while the title, the assignee and the
status print greyed and are taken from the stored row rather than from the form, so a hand-built POST cannot
reassign a card through it. (The project and the lane no longer print at all on an edit of a card that is already
on a project — **Stage 87** — because where it sits is a fact of the row: the save carries the project in a hidden
field and `updateAssigned` keeps the stored lane when none is posted.) Anybody else who can see the card opens it to read,
and a hand-built save from them is refused with *"Only management or the person this task is assigned to can
change it."* A greyed select posts **nothing at all**, which is
what lets the save tell "this reader may not answer" from "this reader left it blank": a card with no `lane` field
in its POST keeps the lane it was in, and one that posts a blank lane is refused. Deleting a card has a button since
**Stage 72**, and one place only: the right-hand end of the note box's line at the bottom of the popup. It posts to
`/app/assigned-tasks/delete`, which is unchanged and still management-only, and it prints on the same flag that route
checks — so a non-manager is never shown it, and a hand-built POST from one still deletes nothing. The confirmation says
what goes with the card (its steps and its log) and that it cannot be undone, which is exactly why it is asked for; a
create popup has nothing to delete and prints no button, and a delete lands on the board the card came from rather than
reopening the popup on a card that no longer exists. Say so before
opening a board: an assignee cannot retitle their own card, and nothing on a board removes one.

A card can be moved from one board to the other, and it is one picker that does it (**Stage 48**). Management's
edit popup carries a **Where it lives** field above the project and the column — *On a project* or *Other tasks* —
and moving it brings the matching fields up while the other half stays on the page but hidden: a card crossing onto
a project has to name the project **and** the sub-category it lands in, and one crossing onto the backlog has to
name the column, because a card with no column is a card on no board. Everything else rides along — the steps, the
log, the status, the dates and the person it belongs to — because the crossing moves the card, not the work. The
field is management's, like the title: the assignee of a card is not offered it, and a hand-built POST naming the
other board changes nothing. A card that arrived before this rule and sits on **no** project at all is the one
state the popup shows without offering as an answer: its project picker prints *— No project yet —*, and the save
refuses, telling its owner to ask management to choose a world for it.

The popup travels with every board that carries a card, and it is drawn for the reader standing
there: a `Viewer` reading a colleague's card gets a node whose greyed fields list **that card's** assignee and status
rather than the whole roster, and the manager of a project
gets the blank create they are actually entitled to — title, holder and column open, the project printed once
and greyed, the roster present because assigning a card to a colleague is the write they just asked for.
The node is there for `app.js` to swap into, not to be read; a board with no card on it prints
none.

The category leads the number for you: since **Stage 92** the **Project number** box opens with the chosen category's
letter and dash already in it (`G-` for Goods sales), and changing the category carries that lead across while the number
you typed stays (`M-07` becomes `G-07`). The category's next free number still stands behind the box in grey as the
placeholder and the hint (`S-10` when Site projects run up to `S-09`), and a rejected submit brings the code back exactly
as written. On submit the app rejects a number that is already
used, that does not lead with the category letter, or that is malformed (`S-01`, `R-12` and
`M-2026-A` are fine; `SIT-01` is not), and each problem is reported under its own field with the
popup reopening on the same page.

The popup no longer asks which buckets the project has. Every new board opens with exactly the rows **Settings ›
Project bucket settings** lists for its category, in that order, and since **Stage 104** the form may name one further
column of this board's own after them — an addition, never a choice: what is typed there belongs to the project being
created and is not offered to the next one. **+ Add bucket** on the board and a column's `⋯` remain where a board
afterwards grows, retitles or drops one. Pick a category that lists no bucket *and* name nothing here and the create is
refused at the Buckets line — *"This category has no buckets yet. Add one above, or list them in Settings under Project
bucket settings."* — so the first project of a new category waits on management listing a row there, unless this popup
names the column itself.

The board prints one table per category panel, and every one of them is laid out on the same
ten-column grid: ID, Project, Client, Status, Progress — the average of that project's card checklists, or
the figure filed on its row where none of its cards has any — Manager, Start date, End date, Revenue and the
pin button sit at the same x in every group, with Revenue right-aligned and the two dates centred so the
digits stack down the page. Since **Stage 108** the Revenue column is money and says so — `₮ 5,000,000`,
the sign kept beside its figure by a non-breaking space — while a project with no revenue keeps its `—`; since
**Stage 109** that sign is the project's own, so one board can print `$ 5,000,000` or `¥ 4,800,000` beside a
tugrik row. The Invoiced and forecast figures elsewhere stay bare grouped numbers. The ID is the one cell printed larger and bolder than the rest of the row, in the
same three places a project's number shows (the board, a project page's head, an account's project line),
because it is what people say out loud; the smaller chip is kept for employee numbers elsewhere. The widths are percentages that sum to 100, so a narrow window wraps a long
cell instead of pushing the table out of its card — and that is how the shares were split: a date must not
break in half, so both date columns keep a fixed 9% and the free-text columns (Project, Client, Manager)
are the ones that give room to the tenth column and wrap instead. A project with no recorded start date
prints `—`.

Above the tabs sits the search bar with its **Search** button directly beside it, and then the two sort
pickers — one for Status (**Not started first** / **Completed first**) and one for Manager
(**A → Z** / **Z → A**). Since **Stage 96** the Status sort runs the same list the new-project picker offers, in the
same order — the two used to be separate lists, and the board's put Blocked ahead of On hold. They are all
one GET form onto `/app/projects` (`?q=`, `?sort_status=`, `?sort_manager=`), so a filtered, sorted view
is a URL: it survives a reload, can be bookmarked, and can be sent to someone as a link. Searching looks
in the project number, the description, the client, the manager and the category name, and every word you
type has to be found somewhere, so `haul road` narrows to one project while `haul missing` finds nothing.
It filters the tab you are on, and the category tabs carry your search with them. Choosing both sorts
reads as "group by status, and inside each status by manager", with the end date and then the number
breaking ties; a project nobody has taken on yet sorts last whichever way the names run. Leave both
pickers on `—` and the board is what it always was: soonest end date first. Typing filters as you write
(about 400 ms after you stop), and when it does, only the board below the bar is redrawn — the page is never
reloaded, so your cursor, the word you have half typed and the place you had scrolled to all stay exactly
where they were. The address bar still updates as you go, so what you are looking at remains a link you can
copy, bookmark or send to somebody. Nothing depends on JavaScript either: with it switched off, the Search
button and the pickers do an ordinary page load of the same URL, and the board ends up identical.

Four field names worth knowing: what the form and the board call the **end date** is stored in the
`due` column — the Calendar and the due-date alerts both key off it, and nothing else does — the
**start date** is `start_date`, which is recorded when the project is made and has no behaviour
attached to it, **revenue** is a single amount in the project's own currency — `projects.currency` (V20) holds the
ISO code, `MNT` unless the create form chose `USD` or `CNY` (**Stage 109**), and the amount is written and shown
with that currency's sign since **Stage 109** (the tugrik sign arrived in **Stage 108**), though the column stores the
plain number — and
**invoiced** (`projects.invoiced`, V11) is how much of that has actually been billed to the client — it
defaults to `0`, is read by the same parser (grouped or signed input is accepted, though it prints bare), and is entered by management on the project's header strip.

## The project page

Opening a project prints its header and, below it, that project's own task board. **For management the header is an
editable strip** (Stage 60): the top line is a `Projects / S-01` breadcrumb with a **☆ Pin for me** toggle and a
**Delete project** button at the right. (Until **Stage 106** a 📋 copied the page's address and a 🗄 opened the
project's archived tasks; the user asked for both out, and the panel went with its only opener — the archive's rows
and its three routes are still there, but no page prints a control for them.) Under it runs **one flat, edge-to-edge
row** of UPPERCASE-labelled fields — **Description**,
**Client**, **Type**, **Start**, **Due**, **Status**, **Project manager**, **Revenue**, **Invoiced** — each a borderless
input or picker (only the native select caret and the date's calendar glyph show) whose refused message prints small at
the field; **Revenue** shows the project's figure in its own currency ("₮ 5,000,000", or "$ 5,000,000" on a dollar
project) and **Invoiced** a comma-grouped one ("5,000,000",
"0"), and **Progress** reads as a plain
percentage with no bar, then a single **Save changes** button. **For a `Viewer` the header is that same row, read-only**
(Stage 71): the pill line, the name, and one flat line of nine UPPERCASE-labelled cells — **Project number**,
**Category**, **Client**, **Manager**, **Start date**, **End date**, **Status**, **Revenue**, **Progress** — each with
its caption above its value, **Revenue** carrying the same currency-signed figure management's box holds, exactly as management's inputs are, with a plain value where management has a box. Nothing
is dropped or reordered to fit; a value longer than its share is shortened with an ellipsis and the whole string is the
cell's tooltip. **Progress keeps its bar** (management's cell shows only the percentage) because this is where a Viewer
reads how far the project has got. No form, no strip, no delete. The split is by role, never by field: what a Viewer
may see, they see exactly as before; only management gets to change it.

Two of those header fields show a value but are not something you can type. The **project number is never editable** —
the strip prints it in the breadcrumb and the save form sends no `code`, so an existing project keeps its number however
you edit it. **Progress is derived**, not a box: it is the average of what the board below has reached, so it has no
input and prints as a plain percentage (no bar on management's strip); the cell keeps its `project-progress` id, and
ticking a step on a card still moves that figure in place without a reload. **Invoiced** is new (schema **V11**,
`projects.invoiced`): the amount actually billed to the client, entered here by management and stored as a plain number
read by the same parser as revenue (grouping, commas, spaces, the currency's sign — `₮`, `$` or `¥` — and the space after it are all stripped on save,
so a figure copied from a cell can be pasted straight into the box). It is *not* asked when you
create a project — nothing is billed before the project exists. **Delete project** is management-only and asks once ("Delete <code>? Every
task filed under this project goes with it. This cannot be undone.") before removing the project **and every card filed
under it**; a hand-built POST to `/app/projects/{id}/delete` from a non-manager is refused.

**Since Stage 108 revenue is written in money too.** Creating a project and editing the header both ask for it in a
text box whose grey placeholder is `₮ 0` on the create popup, and which opens on the project's own figure
(`₮ 5,000,000`) on the header, with a numeric keypad on a phone. What you type may be `5000000`, `5,000,000`,
`5 000 000`, or the whole thing copied out of a revenue cell — `₮ 5,000,000`, or `$ 2,500,000` / `¥ 4,800,000` off a
project in another currency — because the sign, its space, the ordinary spaces and the commas are stripped before
the number is read. What is stored is the plain number, so the
column and the Forecasts identity read exactly what they read before; only the display and the box ask it in money.
Anything that is not a non-negative amount is refused **at the
Revenue field** — *"Enter revenue as a number, like 1,250,000."* — with your words still in the box and nothing saved. A
project with no revenue prints `—` on the board and *not* `₮ 0`, because no figure and a figure of zero are different
statements; the forecast sheet's own Revenue cells do print `₮ 0` when the project's number is zero, since there the
figure is one cell of an arithmetic identity that must balance.

**Since Stage 109 that money is the project's choice, and tugrik is the pick.** The create popup puts a currency
picker directly under the Revenue box — **Tugrik (₮)**, **US dollar ($)**, **Yuan (¥)** — and it opens on Tugrik,
so a form that touches nothing makes a tugrik project exactly as before. The chosen money's sign is the amount box's
grey placeholder (`$ 0` after picking the dollar), and it is stored as the ISO code in `projects.currency` (V20).
Choosing one **converts nothing**: `2,500,000` in the dollar picker stores the same `2500000` the tugrik picker
would, and only the sign on the page changes. The choice is made once — the header strip has no picker and
`ProjectRepository.update` never writes the column — for the same reason the project number never changes: a
project's money is what its contract was signed in. A value the app cannot name (`EUR`, a hand-made POST) is refused
at the picker — *"Choose a currency: tugrik, US dollar or yuan."* — and no row is written. Because a board and a
Forecasts panel can hold projects in different monies, each revenue cell wears its own project's sign, while the
Invoiced and forecast figures and their totals stay bare — and the sheet's column headings no longer say *", MNT"*,
because a panel no longer has one unit to name.

The board's columns are the project's **buckets** — one column per bucket, on an empty board and on a full one
(**Stage 75**). Each head is the bucket's dot, its name, how many cards stand in it and a `⋯`; each foot is a
`+ Add task` line aimed at that column; and the run of columns is trailed by a dashed **+ Add bucket** box the width of
a column. Every new project opens with the columns its category lists in **Settings › Project bucket settings**, in
that order, and with nothing else (**Stage 100**) — a board made before that rule holds whatever it was born with, which
for most is Engineering, Procurement and Production (Stage 75 replaced the six Stage 48 had put there) — and a card
**written onto a project is filed in one of its buckets as it is
written**: the popup's **Sub-category** field is asked on every create and a blank answer is refused with *"Choose which
part of this project the card is on."* A card's **status** is not a column on this page. **Not started**, **In
progress**, **On hold**, **Blocked** and **Completed** are the five columns of the Assigned tasks page, where the reader
walks their own card along them **by dragging** (the `←`/`→` buttons that used to sit under the card as the second path
were retired in **Stage 103**); on a project the same status is set in
the card's editor, and by the **✓ Done** / **↺ Reopen** button an owner gets on their own card, which posts
`/app/tasks/status` exactly as the drag does. That is the reversal of Stage 52, which had cut this board to statuses on
the reading that a project's work is planned *by kind*, and it is why the buckets are now what the reader shapes: the
columns they are looking at.

What the page carries **besides** the board is nothing (**Stage 79**). Stage 73 had put a *Project buckets* panel above
the board — the create popup's tick-list again, with one **Save buckets** button for the whole set — and the user's word
on it, with the screenshot of that panel attached, was *"no need this part when clicking on the project"*. The panel is
gone, its `#projectBuckets` block and its button with it, and its caption's whole job is now done by two controls that
were already on the board: the dashed **+ Add bucket** box, and each column's `⋯`. **Stage 92** then took the create
popup's copy of the picker off too, and since **Stage 100** a new project opens on the category's Settings list and
nothing else — so this page is where a board is shaped after creation, while the list it starts from lives on Settings.
Nothing was taken away that the board did not already
offer — the panel's tick-list let a reader set the whole column set in one write, and the popup's add half does the same
thing under the hood: it posts the board's every current name as a hidden twin beside the one word typed, so
`TaskService.saveLanes` still receives a whole set and still refuses, renames and appends by exactly the same rules.

Three behaviours the panel used to print are the ones to look for on the board now. A bucket that **still holds cards
cannot be taken off** — the popup for that column says *"This column has 2 cards filed in it. Move them to another
bucket first — taking the column off would leave them filed under nothing."* and its **Remove bucket** button is drawn
shut (greyed, since Stage 79, so a pressable removal in red and a refused one cannot be mistaken for each other); empty
the column and the button is live again. The old locked tick box is gone because the box was the panel's shape, not the
rule's. A bucket named here is **this project's column and nobody else's business** unless the reader ticks otherwise
(**Stage 77**): the box under the name asks *"Also make it a default bucket for Site projects"*, only management are
shown that box at all, and only a ticked one also joins the category's catalogue — so the sentence says
*"Saved: 3 buckets on this project — Dewatering added to the Site projects catalogue, so new projects of that category
are offered it"* when that happened, and simply *"Saved: 3 buckets on this project."* when it did not. And a board
keeps at least one bucket, since a board with no column is a board a card cannot be filed on: the last column standing
has its **Remove bucket** shut and its popup says *"A board keeps at least one bucket, so this one cannot go. Add
another column before removing this one."* — which is the popup's own reading of `LaneError.LAST_LANE`, the same rule the
delete route answers. The save route's wording of it, *"A board keeps at least one bucket — tick one to leave
standing."*, is a hand-built path since Stage 79, because the add form always posts the board's whole set. The answers
arrive **in place**:
each write redirects with its outcome (`?buckets=ok|err&bucketnote=…`), `app.js` fetches that page and swaps
`#projectBoard` together with `#taskLane` — the card popup's **Sub-category** picker, on the popups that still print
it — and `#projectLaneModal` itself,
so a bucket just added is immediately selectable on a new card, a column just removed is gone from the board in the same
repaint, and no reload flashes past the reader.

**A typed bucket is the project's own, and the widening is a separate act** (**Stage 77**). The ask was the plain one —
every project has its own buckets, created on the project — and what used to happen was the opposite by default: a name
typed into any project's picker went onto that board *and* into the category's catalogue, so one job's private weld-repair
phase became an offer to every future job of that category. Now the typed name stops at the board, and the box drawn under
it asks whether it should also join the category's defaults. Three consequences are worth knowing before the first save:

- **The tick is drawn for management only, and it is off by default.** A project's own manager who is not management sees
  no box at all — not a greyed one, because a control that would be refused is worse than no control — yet they can name
  a column for their project as freely as management can. That half of the ask is new too: the rule Stage 73 put in
  place refused a non-management caller a *new* bucket outright, and that refusal is withdrawn.
- **A tick is an offer, not an order.** A tick writes a catalogue row; it does not reshape a board that already
  exists — that still takes an add on Settings itself, which since **Stage 98** does fan the column out. Since
  **Stage 100** a new project of that category does open with the word, because the catalogue *is* the board it is cut
  from: what the tick changes is what the next project is born with, not what any current one holds.
- **A hand-built tick writes nothing and says so.** A POST carrying `also_type` from a caller who is not management is
  refused with *"Only management adds a new bucket to the Site projects catalogue."* printed at the name field rather
  than quietly answered with the project-local version the reader did not ask for, and the popup comes back with their
  word still in the box and their tick still pressed (`?widen=1`), so a refusal costs nobody a second typing.

The same three sentences apply to any name named on this page: onto this project's board, and into the catalogue only
under the tick. `TaskService.saveLanes` is the writer, gated on the posted flag *and* on `canManage`, and it checks
every name before any of them is written, so a refused list cannot leave half of it in the catalogue.
`BucketDefaultsService.chosenFor` remains the project page's half of that rule — its **+ Add bucket** popup posts a set
— while `ProjectService.create` no longer consults it: since **Stage 100** the create seeds the category's whole
catalogue through `TypeDefaultBucketRepository.seedBoard`, and neither `ProjectDraft` nor the create form carries a
bucket field at all.

**The columns are the buckets, and they are the reader's** (**Stage 74** drew them, **Stage 75** made them permanent).
The dot is the bucket's own colour: since **Stage 80** it is a shade somebody *chose* — six swatches on the column's
popup, the same six hues the Settings catalogue and the calendar already paint with — and a column nobody coloured still
wears the colour of its position in the run, which is what every column did before and what every column still does
when the swatch row is left alone. A seventh column is back to the first hue either way. Nothing moves in the data
between an empty board and a full one, because `status` and `project_bucket_id` are separate columns of the same row
and the same list is simply read one way. A card that has no bucket yet — one written before Stage 48 made a lane
mandatory, or one a legacy import left unfiled — is caught by an **Unsorted** column that appears at the left only
while such a card exists, so work is never drawn nowhere. Since **Stage 81** a removed column no longer feeds it:
the cards filed in a column that comes off the board are archived with it, not left to *Unsorted*.

Two controls sit on that board, and both hang on `mayEditLanes` — management or this project's own manager — so a
`Viewer` sees neither. The dashed box is a GET form asking for `?laneadd=1`; a column's `⋯` is the same shape as
`?laneedit=<id>`. Each is answered by `app.js` swapping only `#projectLaneModal`, the page's one bucket popup, which is
why a plain click and a bookmark open the same form and why a reader with scripts off gets the same popup. Since
Stage 75 **every** column carries the `⋯`: the three a board opens with are lanes like any other, so a manager who
calls the first phase *Design* renames the column, and one that has nothing filed in it can come off the board. (What
still cannot is the last bucket standing.) Since **Stage 79** that one popup is the whole of
the page's bucket UI: it adds a column (the dashed box opens it straight at the name field), and it renames **that one
column** and offers to take it off the board. A rename is by **id** and never by name (the name is the thing being
changed), and it touches only this board's heading and not the category's catalogue. **Since Stage 81 a removal takes
the column's work with it**: the popup counts the cards filed there and, instead of refusing an occupied column, it
says what happens to them — *"Removing this column takes it off the board, and its 3 cards leave the board with
it."* — and the write flags every one of those cards archived with the column's name
remembered before the column row goes. The board answers *"That bucket is off the board, and its 3 cards left the board
with it."*, and nothing falls to **Unsorted**.

**A removed column's cards are archived, and since Stage 106 that is all any page says about them.** Stage 81 gave the
archive a panel behind a 🗄 in the project header — every card put away, each row naming the card, the project number
and the column it stood in (*"S-01 · was in \"Production\""*), with **Restore** and **Delete permanently** — and
Stage 106 took that icon out at the user's request; the panel had no other opener and went with it. What remains is the
data and its routes: the rows stay in the database with their steps and their log, `TaskService.archiveCard`,
`restoreCard` and `deleteArchived` still answer `POST /app/tasks/archive`,
`POST /app/projects/{id}/archive/restore` and `POST /app/projects/{id}/archive/delete`, none of them has a control on
any page, and `?archive=1` is no longer read. An archived card is still off every board, its column's count and the
lesson sheet's card picker, and it takes no save or board delete.

That popup can ask **two** questions; since **Stage 83** only its rename half asks both: *what is this column called*
and *what colour is it*. Adding one asks for a name alone, and the app paints the new head at its place in the run —
an empty `colour_slot` in the data, which is free to follow the column's position for as long as nobody chooses
otherwise. Where the swatches do appear, under the name box of a column being renamed, the colour is a row of six dots,
a radio group rather than six buttons, because the choice is one-of-six and the app's rule is that the server renders the
board: a colour that lived only in a script would be gone on reload. **What travels is a number, never a colour
value** — `colour=0` to `colour=5`, a position in the stylesheet's own six-hue cycle — so a hand-built POST cannot
paint a column a shade the theme cannot carry, and a number outside the six is read as *no answer* and leaves the
column wearing its position. The row arrives **pre-checked at the shade the column already wears**, which is what makes
an untouched form safe: pressing **Save** to correct a spelling cannot recolour a column, and a reader who only wants a
new shade can post the form back exactly as it came and change nothing. A refusal sends the swatch back with the word
(`?lanecolour=`), so a name that was too long costs neither the typing nor the colour.

**A column's finished work folds away** (**Stage 80**). Under a column's live cards, only where at least one card in
that column is *Completed*, the board prints one line: `▸ Completed 2`. Press it and the same page answers with the
line turned to `▾ Completed 2` and the finished cards showing beneath it. The state is in the **URL** —
`?showdone=<column id>`, repeated once per opened column — and the line is a plain GET form onto this page, which buys
three things at once: `app.js` swaps `#projectBoard` so the board does not reload and the other columns do not move; a
reader with JavaScript off gets the same opened-and-shut board from the same link; and an opened column is a
bookmarkable, sendable view. Opening one column leaves the others shut, because each press posts the set as it will be
*after* the click and carries the already-open columns along with it. The number in the column head is untouched — it
has always counted every card in the column, folded or not — and a column with nothing finished in it prints no line to
try. The fold is a **bucket-board** feature: the Assigned tasks and Other tasks boards are cut by status, so their
*Completed* column is already the whole of that work and hiding it would hide the point of the page.

Each of the three writes answers with **one sentence, printed once** — over the board when a write landed or when a
refusal came to a page that has no popup up, and under the popup's name field when a refusal came while that popup was
what the reader had pressed (`?bucketsrc=board` says it did, and the form is reopened with their word still in it). The
URL carries the outcome and the text as `?buckets=ok|err&bucketnote=…`, and the board's own forms name `#projectBoard`
as what they redraw, so the columns and the words about them arrive in one repaint. A page that answered the same
refusal twice would read as two problems, which is why the choice is made once, in
`mn.murray.pts.Application.openingLaneForm`, rather than printed in both places.

`POST /app/projects/{id}/buckets` and its `delete` twin still apply the rules they were written with — cards stay
standing when their lane goes, a board never loses its last lane — and since Stage 74 the board's popup reaches
`delete` again, through a route that adds the count check above. `POST /app/projects/{id}/buckets/rename` is new with
it. What Stage 75 took out of those rules is `LaneError.STANDARD`, the branch that refused any write to one of the
opening lanes' names. A `Viewer`'s hand-built save to any of the three is turned away before the form is read: it
redirects to `/app/projects` and changes no board.

A card on this board says the **stage it has reached** on its own bottom line (**Stage 78**): a coloured dot and the word
beside it — *Not started* grey, *In progress* amber, *On hold* orange, *Blocked* red, *Completed* green — the board's own
five hues, the ones its column dots and card edges already use, so no new colour enters the app. Its holder's avatar
stands at the far right of that same line, so the card's last line answers *how far* and *who* together. It is here
because this page's columns name a **phase**, not a stage: since Stage 75 a project board is its buckets, so a card that
said nothing about how far it had got left the page silent on the one thing its own columns cannot show. Above that line
are the title and the priority-and-due line, and above those there is nothing — a project card prints **no code chip**
(**Stage 78**). The number is in the popup the click opens, and on a page that already names its project it was the one
line with no question left to answer. Stage 76 drew this same word as a pill on the top line beside that code; the
picture these cards are being matched to has neither, and the code is what went.

Every card here also **wears its stage's tint** — the whole card, not only a finished one, which is what **Stage 78**
changed. Stage 76 kept the tint binary on the argument that a column of five cards all in progress would paint the same
amber five times; on a lane board that repetition is not a repetition of the heading at all, because the heading names a
phase and the tint is the one thing on the card readable from across the room before any word is.

**Assigned tasks** keeps an identification chip on the card's top line and prints no stage word and no foot line: its
columns *are* statuses, so the heading is the word and the card would only repeat it. Its holder stays on the card's
meta line, where they have always been. Since **Stage 88** that chip names the card's **project code** (`S-01`) — the
fact that tells a run of cards from every project apart — and the reader's own Other-task cards, printed under the same
page, wear the **bucket** they are filed in instead (the number `T-00012` remains only for a legacy row with no
project). Since **Stage 104** the project-code chip is also the way to that project: it is an `<a>` wearing the same
`tcode` class, and it opens `/app/projects/<id>` in the same tab — no new control beside the card, because the chip was
already the line that answered *which project*. Underline comes on hover only, so a board of cards does not read as a
list of links, and the chip is a tab stop. A backlog card's bucket chip stays a plain label — the reader is already on
the board it names — and so does a legacy code with no project behind it, because a link to no project is a dead link. Above that lower board sits a **Bucket** picker: choosing *Finance*, *HR* or any other column narrows the
reader's Other cards to it while they stay cut by the five statuses — the picker narrows, it never becomes bucket
columns. It is one **card wide** since Stage 89, not a toolbar across the page. Since **Stage 90** it is drawn even when
the reader holds no Other-task cards at all, because it is also the chooser for the `+ Add task` line under that board:
the line opens the blank card popup on the bucket the picker shows (the first column under *All buckets*), and a reader
who is not management gets their own name and *Not started* as facts — the card they write is theirs. The choice is a URL key
(`?bucket=<id>`) and rides the card actions' `back`, so opening, saving, stepping,
noting, deleting or answering a refusal returns to the same narrow board. The **Other tasks** board itself prints
neither word nor chip for its cards, because its columns already name their buckets — and since **Stage 95** its cards
wear the same stage tint the assigned board paints: amber *In progress*, orange *On hold*, red *Blocked*, green
*Completed*, grey edge for *Not started*. The slate edge the backlog had of its own since Stage 57 is gone; a stage that
was one colour on one page and another on the next read as two different facts.

Three things are deliberately not on this page any more. The **"Project manager" panel with its Set manager
picker is gone**: there is no second control at the bottom of the page writing the manager the card header already
shows. Since Stage 60 the manager is instead chosen in the editable header strip itself — one field, one place to set
it — and `POST /app/projects/{id}/manager` still exists and is still management-only, but nothing here posts to it; the
strip saves through the ordinary `POST /app/projects/{id}` update route. The ***Add lane* box under the columns and the `×` on each column head are gone** (Stage 35),
and Stage 74 did not bring those back: what the empty board carries instead is a dashed **+ Add bucket** box at the end
of the run of columns and a `⋯` on the head of a bucket the board is allowed to lose, both opening one popup rather than
taking the name on the board itself. And the page prints **no roll-up
strip** above the board — see below.

The **`Viewer`'s nine fields are one line** (Stage 71), and the line is management's: each field is a cell with its
UPPERCASE caption *over* its value — **Project number**, **Category**, **Client**, **Manager**, **Start date**,
**End date**, **Status**, **Revenue**, **Progress** — in that order, with no wrapper, no pair and no second row.
That is the same ask Stage 29 was given, answered differently: nine `CAPTION value` pairs side by side need about
1,600px and the panel gives about 1,100 in an ordinary window, so Revenue and Progress fell onto a line of their own
for everyone who ever opened a project. Putting the caption above the value halves what each field asks for, and every
cell divides the row by weight with no width of its own to fall back on, so the ninth cell is never pushed down; a
window too narrow to show everything shortens the longest fields with an ellipsis — the full text is the cell's
tooltip — rather than wrapping one of them and making the row two lines tall.

The detail card prints **no roll-up of the board underneath it**. It used to: six tiles — **Cards**,
**Open**, **Done**, **Overdue**, **Steps**, **End date** — then a row of dots repeating the five columns
with their counts, then a line naming the people holding open work. That was Stages 21 through 27;
Stage 28 took all of it out, because every number in it was already printed by the thing it described. The
counts live in the board's own column heads, the project's end date is a field of the detail card, "who is
on this" is the name written on each card, and the average is the progress bar above them. Two places for a
figure is one place for it to be wrong.

What remains is the board's own heading — "Tasks on this project — 4 assigned", counted from every card filed under
the project — and, for a project with nothing on it, the empty board's own sentence and its `+ New task`
where the reader is allowed to write one.

Since **Stage 56** the bar and the board underneath it read the **same set**: every card filed under this project.
The bar averages all of them — the same figure the Projects board printed for this row, whoever opens the page — and
the board now shows all of them to every reader, each card carrying its holder's avatar so the page answers "what does
this project have and who is doing it". This is the **list only**: the reader's *role* still decides what they may do
with a card on that board, so a `Viewer` sees the whole project and — since **Stage 86** — opens any card on it to
read, but drags or edits only their own. (Until
Stage 56 the board was scoped to the reader's own cards while the bar measured the project, which is the one
disagreement this stage removed; the general Assigned tasks page keeps that reader-scoped rule.)

A tick on a step still redraws the bar in place: every step form names `#project-progress` as a block the
same send changes (`data-live-extra="#project-progress,#task-body-42"`), so the card's chip, the popup's plan
and the project's percentage update together and no stale number is left on the screen. With JavaScript
switched off those forms post and reload as they always did, and the page that comes back matches what you
just did.

The board's openers behave the same way. A lane's `+ Add task` line
is a GET form onto this same page carrying `new=1` and `into=<lane id>`, `app.js` fetches it and swaps only
`#taskModal`, and the popup lands with that lane already chosen and its
hint saying "A new card lands under Goods sales." An `into` for a lane this project does not have — another
project's, a removed one, a word — is discarded by the route, so it opens the ordinary blank form instead of
filing the card somewhere it cannot be seen. Dropping a card on a lane
is not a reload either, and since **Stage 105** the card moves on the drop itself:
`kanban.js` puts the node in the column it was let go on and recounts both columns *before* it posts
`POST /app/tasks/bucket`, and it tells the browser not to follow that route's 302 (`redirect: 'manual'`), so the
board page behind the redirect is never fetched. It used to be the order the other way round — the reply was
awaited, the redirect downloaded the whole page, and the card sat still through the download and then jumped.
A write that fails is now the one path that reloads the board. A card filed into a column that has finished cards
in it lands above the `▸ Completed N` line, among the live ones — and it does land: that insert used to be anchored
on a button nested inside the line's own form, which threw and was swallowed, so such a drop moved nothing at all.
A lane the
last card leaves stays on the board — an empty lane is still one of the project's lanes — but **Unsorted** is
not: it is a bucket for cards nobody has placed, so the column goes when its last card does (Stage 37), and since
Stage 48 it is not a drop target at all. Neither
case moves anything else, because a lane column is 288px wide whether the board prints three of them or nine.
Before that the columns were `auto-fit` `1fr` tracks, sized by how many of them there were: on a 1592px-wide
board the three that were left went from 389px to 523px when a fourth appeared, so every card on the page grew
by a third because one card moved. The lane listeners are bound on `document` rather than per column so that one
set of handlers covers whichever column a drop lands on.

**Which of the two readers may make that drop is the whole difference between them on this page (Stage 49).** A
lane is a statement about the project's plan rather than about the card, so it belongs to the two accounts that
may write onto the project in the first place — management, and the project's own manager whoever that is — which
is the same `mayAddTasks` answer the `+ Add task` lines hang on, handed to the board a second time as
`canMoveCards`. A card therefore prints `draggable="true"` for those two and for nobody else, and the route
refuses a hand-built `POST /app/tasks/bucket` from anybody who is neither. The card still **opens** for the person
it is assigned to, and their own **✓ Done / ↺ Reopen** button is still there: those hang on whether the reader may
write *this card*, which Stage 49 did not touch. `kanban.js` picks its drag sources with
`.kcard[draggable="true"]`, so withdrawing the attribute withdraws the drag with no script and no stylesheet
change; a card that cannot be dragged is simply a card that opens.

A card has a written **width** and its own **height** (Stage 38): 264px inside its 288px column, and as tall as
what it carries. The width is the half that was ever a complaint, and it stays fixed — the columns are fixed
tracks, so no card's size follows the count of columns or cards on the board. The height is deliberately free: a
card with no plan measures 95px, the same card with an eight-step plan measures 314px, and its column follows it
down. Stage 37 had pinned the height at 168px so that a colleague's twenty-step card did not stand three times as
tall as the one beside it, and the board was asked back the other way the next day; if this is ever restated
again, the shape to reach for is a column of fixed height with its own scrollbar, not another per-card height.
Two things survive that experiment. The plan is still capped at 240px of its own, so a twenty-step list makes a
long card rather than an unbounded one — its steps scroll inside the card, and the popup is still where a plan is
written and read — and an empty column keeps a 190px floor, so a lane with nothing in it reads as a lane rather
than as a strip.

What the board no longer does in place is change its own shape: `#laneArea`, the block that used to be
re-fetched when a lane name was typed or a **×** accepted, went with both controls (Stage 35), and it has not come
back — **Stage 74**'s column popup is a page-level block like every other popup, and it swaps `#projectBoard` and
`#projectLaneModal` rather than a lane area. So a lane's head is its colour, its name and its count, plus a `⋯` for the
reader who may re-shape a bucket the board is allowed to lose, and only while the board is the empty project's bucket
board.

## Assigned tasks and checklists

A task card is a piece of work one person owns, and only two people have a claim on it: the assignee and
management. That split decides what each of them can see and do, and it is the rule on the shared boards — the cards
on the **Other tasks** page and the per-person drill-down on the Team page. A
`Manager` or `Admin` sees the whole board there, because seeing everyone's work is what that role is for. A
`Viewer` sees only the cards assigned to them. There is no setting for this.

Three places, though, are deliberately personal for **every** reader regardless of role: the
**Assigned tasks** board, the number beside it in the sidebar, and the **Calendar** (**Stage 64**). Since **Stage 53**
that board answers one
question — *what is on me* — and never a team-wide list, so a `Manager` who opens `/app/assigned-tasks` sees only
their own cards, exactly as a `Viewer` does, and the sidebar pill counts the same reader's own cards. This is a
change to *who appears*, not to *who can act*: management keeps `+ New task`, the full editor and the drag on that
page, and loses nothing about running the team, because the whole-board overview they used to get there lives on the
Team sheet and the project pages instead. **Stage 64 moved the Calendar onto the same rule**: it used to be the last
view that still handed management the team's month, which meant the page the README had described as personal for
years was in fact filtered by rights, and a manager's plan of the month was mostly bars for other people's work. It
now reads `assignedFor(employeeNumber, canManage = false)`, exactly the call the Assigned board makes — so the grid,
the list underneath it, the sidebar pill and `/app/assigned-tasks` all answer the same question about the same
reader. A manager who wants a colleague's schedule opens the Team sheet's drill-down or that colleague's project
page, where the card is actionable rather than a bar. **The project page is the one deliberate exception to that
personal scoping (Stage 56)**: clicking a project shows *every* card filed under it to *every* reader — management, the
project's own manager, and a plain `Viewer` alike — cut into the **project's own buckets** (Stage 75: the columns are
Engineering, Procurement, Production and whatever else that board carries, not the five statuses the Assigned board
uses), each card carrying its holder's avatar so the page answers "what does this project have and who is doing it".
That widening was the **list only, never the rights** until **Stage 86**, when the read half followed: a `Viewer` now
opens a colleague's card **read-only** — its fields, plan and log, with no Save, no tick and no note box — while
acting on it (dragging, ticking, noting, retitling — the arrowing that used to be on that list went in **Stage 103**)
still hangs on ownership and role exactly as before,
so their own cards are the only ones they can change.
Nothing else about the rule moves: `assignedFor` itself is unchanged — each *caller* decides its scope — and the Team
drill-down and the project pages stay as they were. What did move on the calendar is its wording: the line under the
title and the heading of the list beneath the grid now read the same sentence for a manager as for a `Viewer`, because
there is no team-wide mode left for them to describe. A reader whose month holds nothing keeps the grid and is told
"No assigned tasks yet." rather than being shown other people's bars.

**Stage 65 gave that personal month a way to move.** Until then the calendar was pinned to the server's current month
with nothing on the page to change it: the rule was *whose* cards, and the *when* was never asked. The page now reads
the month the address names — `/app/calendar?month=YYYY-MM` — and gives the reader four ways to change it, all of them
controls of one GET form above the grid: **‹** and **›** step to the month either side, **This month** comes home and
only appears when the reader is away from it, and a **Jump to** month box names any month at all in one move rather
than twelve clicks. The whole month view — title, strip, grid — is one live region, so choosing a month swaps the
month in place and the page behind it does not blink. Two names carry the answer, deliberately: the step buttons
answer `month` and the typed box answers `pick`, because pressing a button also sends whatever the box happens to
hold, and one question with two answers would leave the server guessing. The route believes `month` first — it is the
control that was acted on — and falls back to `pick`, then to this month for anything that is not a `YYYY-MM`. A month
with nothing dated in it is still a month worth drawing: the grid stays and the page says
"Nothing assigned to you is dated in Mar 2027." with a link back to this month.

What the chosen month does **not** move is the list under the grid: it stays the reader's whole workload, dated or
not, because cutting it to the month would silently drop every undated card — the same set of cards the *Assigned
tasks* page and the sidebar pill count. A bar appears only where its span touches the month on screen, and a bar that
runs in from the month before (or out to the month after) is clipped to the edge of the grid and still prints its
title on its first or last *visible* day, so a card spanning months is never an unlabelled strip.

**Stage 66 made that month hold all of the reader's work, and gave each piece of it its own look.** Two gaps were
closed at once. The first was a hole: the month read the reader's *project* cards only, so a backlog card with a real
start date and deadline — work the Assigned page and the sidebar pill both count — simply had no bar anywhere, and an
undated one had no row either. The calendar now reads the same two lists the sidebar pill adds up,
`assignedFor(employeeNumber, canManage = false)` plus `assignedOtherFor(employeeNumber)`, so dated backlog work bars
like project work and undated backlog work keeps its row under the grid. That is the **list widening, not the rights**:
whose cards are read is still exactly the reader's own, for every role, and Stage 64's rule is untouched.

The second was that a month of bars could not be read. Every bar used to be painted by its *priority*, so a month of
High-priority work was a wall of identical red and nothing on the grid said which project a bar belonged to. Now a
bar's **hue says which piece of work it is**: one hue per project, and a backlog card wears the hue of the **bucket**
it sits in on the Other tasks board. Priority did not lose its voice, it just stopped owning the colour — it is a
small **dot** inside the bar, red/amber/green, with the word as its tooltip. Under the month strip a compact **key**
names the hues that are actually on screen (only when the month holds more than one group, because a key that repeats
a single name explains nothing) and states what the dots mean; the same swatch and an `OTHER` tag appear beside each
row of the list underneath, whose column heading became **Where it lives** — "Project" being a word a backlog card
does not answer to. The palette is assigned over the reader's **whole** workload rather than the month's slice, on
purpose: stepping from October to November must not repaint the same project. A card's popup is told the truth too —
for a backlog card its *Project:* line reads `Other tasks · <bucket>` rather than a blank.

The sidebar number beside *Assigned tasks* counts **every** card on that reader's board — the **Completed** column
included. Since **Stage 54** the pill and the board agree exactly: it answers *how many tasks are assigned to me*, and
a finished card is still one of those, so nothing is subtracted and the figure no longer falls as work closes. (Until
Stage 54 this count left the Completed cards out so it would always speak about today; the reader asked for the whole
number instead.) What *does* still separate an open card from a finished one is the Completed column itself and the
board's own per-column counts — and the **Team sheet's OPEN TASKS** figure, which is deliberately a count of *open*
work only, because there the question is what a colleague still has outstanding. `TaskItem.isOpen` is where that
open/finished line is drawn for those, and it is the same property the Team page reads when it counts and sorts a
colleague's cards, so one card cannot be finished on one page and outstanding on another.

Since **Stage 107** that board also carries a **Project** picker above it — the Other-tasks half's Bucket picker's
twin, in the same one-card-wide `board-toolbar` shape: choosing `S-01 · Haul road widening` narrows the reader's own
project cards to that project while they stay cut by the five statuses. It narrows, it never becomes project columns
and never adds a card. The list is every project the Projects page holds, not only the ones this reader has a card on,
because a project with nothing on it yet is a legitimately empty answer rather than a missing option; *All projects*
is option `0`, the absence of a filter, so the URL is the state (`?project=<id>`) and a bookmark or a back button
restores the same narrow view. The two pickers carry each other — each rides in the other's hidden field, so choosing
one never silently widens the other board — and the card actions' `back` token carries both
(`assigned:<bucket>:<project>`), so opening, saving, stepping, noting, deleting or answering a refusal returns to the
doubly narrow board. A stale or deleted project id answers the whole board, exactly as a stale bucket does. A narrowed
board says so under the title: *Only the cards assigned to you on S-01 · Haul road widening are on this board.* This
is the **view's** rule, not a new right: `assignedFor` still answers the reader's own cards for every role.

Since **Stage 55** the *Assigned tasks* page answers a second question below its own board: *what else is on me, outside
the projects*. A reader can hold backlog cards — work assigned to them on the **Other tasks** board, which belongs to no
project — and Stage 51 took that board's sidebar link away from Viewers, so those cards had nowhere left to surface for
them. The assigned page now prints the reader's **own** Other-task cards in their own status board under an
`Other tasks` heading, and when they hold none it says so plainly: *You don't have any other tasks assigned.* This is
scoped to the person reading, never the shared board — management's whole-backlog view stays on the Other tasks page.
Since **Stage 88** each of those cards wears the **bucket** it is filed in, and a **Bucket** picker above the lower
board narrows them to one column — *Finance*, *HR*, whatever the board carries — while the five status columns stay the
board; the choice is a `?bucket=` view key that rides every card action's `back`, so opening, saving, stepping, noting,
deleting or refusing a card comes back to the same narrow board, and a bucket with nothing in it says *You don't have any
other tasks in HR.* rather than the generic line. Since **Stage 90** that lower board is also where the reader **writes
their first card**: a `+ Add task` line under it opens the blank popup on whichever bucket the picker shows, and a reader
who is not management writes a card that is **theirs** and **Not started** — the service pins both, whatever a hand-built
form names. The picker therefore stays on an empty board, since it is the chooser that line uses. The **shared** Other
tasks page is unchanged: its own add line, its popup and its save still belong to management.
The two boards share one editor popup and one opener, so a backlog card clicked here opens the same editor and, on save,
returns to this page; and the sidebar number grew to cover both, so the pill beside *Assigned tasks* is still exactly the
cards this page prints.

The Team page follows the same line. The roster — name, department, position, email — is for everybody,
because knowing who is on the crew is not confidential. What is confidential is what they are working on:
the task list under each row, and the `+ Assign a task` panel, are management-only. A `Viewer` can still
open their **own** row and see their own cards there. That panel can write a card onto **either board**, since
Stage 50: it carries the same **Where it lives** picker the card popup uses — *On a project* or *Other tasks* — and
the half it is not standing on is only put away, never removed, so one POST answers both. On the project half it asks
a **Project** and, from that project's own buckets, the **Sub-category** the card is filed in; on the Other-tasks half it
asks a **Column** from the backlog's own columns, opened on *— Choose a column —* because a card filed on no column of
the only board that prints it is work gone missing. The picker's default is the project half — the card the panel has
always written — so a hand-built POST that names no `board` is the ordinary project card, and it is the service, not the
`required` attribute, that refuses a project card naming no project or an Other card naming no column. (The two project
pickers are deliberately **not** `required`: a `hidden` box is still validated by the browser, and `required` on the
half the reader cannot see would block the submit of the half they can.)

**Stage 67 made every figure on a Team row a count of that person's *cards*, from both boards.** Until now the row's
five status columns and its **Total** were counted from the projects a member *manages*, under headings shaped like
task states — so a colleague who managed three Ongoing projects printed `3` under **Ongoing** while the **Tasks** button
beside it printed the number of cards assigned to them, and the two figures on the same row could not be reconciled with
each other, let alone with the drill-down underneath. Worse, work that belongs to no project was invisible there
entirely: a planner with four backlog cards and no project showed an empty row. The row now reads from
`memberTaskRows(employeeNumber)` — the one list the row's own drill-down prints, which is `assignedFor` and
`assignedOtherFor` unioned and sorted open-first — so the **Tasks** button, the five status columns and **Total** are all
measurements of the same cards and cannot disagree. Because a card's states are *Not started / In progress / On hold /
Blocked / Completed*, the column that used to be headed **Ongoing** is headed **In progress**: the heading now names a
state a card can actually be in, which is also why the five figures always add up to Total. Managed projects did not
leave the page — they are still listed in the member's popup, which is the right place for *what they run* as opposed to
*what is on them*.

Who may read those figures followed the row, not the roster, as **Stage 67** left it: **management saw every row's
figures, and a `Viewer` saw dashes on every row but their own.** The dash was `—`, not `0`, because the two say different
things — *not yours to read* rather than *nobody has any work* — and printing a zero there would tell a Viewer their
colleague is idle.

**Stage 68 took those figures off the gate and added the one number the sheet was missing.** The ask was the row read the
other way round: *every* user able to see how many tasks — and how many projects — everybody else carries. So the split
moved. The **numbers are now public**: Projects, Tasks, the five status columns and Total print on every row for every
signed-in reader, whoever they are. The **cards behind the numbers stay gated** exactly as they were: opening a colleague's
Tasks figure to read which work is on them — the drill-down row, and the task list in their popup — is still management's
and the reader's own. Project *names* in that popup did become public with the new figure, and that costs nothing: a
project's name has been on the Projects board for every reader since Stage 56.

The new **Projects** column answers the second half of the ask, and it is the same set the member's popup lists: every
project they **manage** *plus* every project holding at least one of their cards — everything they are on, whichever way
they are on it. One helper, `memberProjects`, feeds both, so the figure on the sheet and the list behind it cannot
disagree. It sits outside the row's arithmetic on purpose: the five status columns always add up to **Total** because a
card holds exactly one status, while projects and cards are different populations — six cards on two projects read `2`
and `6` on the same row, and a backlog card moves Total without touching Projects.

What a reader may not open, the page stops offering to open. A colleague's Tasks figure is printed as a plain number in
the same pill rather than as the button that would expand it, so there is no control on the sheet that does nothing when
pressed; and the `—` went with it, since a row that always prints a number has no second shape to explain. The sheet is
now **thirteen columns** wide — ID, Name, Department, Position, **Projects**, Tasks, the five statuses, Total and the
details arrow — and the wider heading costs nothing: the counted columns are content-sized, so the header stays on one
line. Nothing else moved: no route, no control, no editor changed hands, and the `+ Assign a task` panel is still
management's.

**Stage 69 made the figures on the Team sheet the figures in the database, at the moment you look at them.** The ask was
that a count did not follow a card: drag one to another status column, and the sheet went on printing the numbers from
before the move until the page was reloaded. The counting was never the problem — every figure is read straight from the
cards each time the page is drawn — the problem was that the page stopped being drawn. Two separate things keep an old
sheet on the screen, and both are now handled. A Team tab that is **already open** when a card is moved elsewhere has no
reason to ask again, so the sheet asks for itself: when the tab comes back on screen, or is restored from the browser's
back cache, `team.js` re-pulls that same address and swaps the sheet panel in place. The numbers change and nothing else
does — the page does not restart, the scroll position holds, the rows you had opened stay opened, the popup you were
reading is repainted from the fresh data rather than left behind, and a tab only flicked past costs no request at all.
The second thing is subtler: pressing **Back** used to hand you a copy the *browser* had kept, drawn before the drag, and
it did so without ever contacting the server, so a script on the page could not have helped. Every page the server draws
for the reader standing in front of it — the home page, the login page, anything under `/app` — now travels with
`Cache-Control: no-store`, which means a revisit reads the database again. The files under `/static` and the uploads under
`/files` are the same bytes for everyone, so they keep being cached and this costs nothing in load. One caveat for the
first visit after this reaches a server: a browser that already holds an old copy of a page keeps it until that page is
reloaded once — the rule governs copies made from then on.

A checklist is a list of small steps that say how a card gets to **Done**. Each step is one row in
`task_checklist_items`, added in order, capped at 120 characters, and refused if it is blank — so a tick
is a single row update rather than a rewrite of the card, and a step keeps its place while the others come
and go. Deleting the card takes its steps with it. A note is one row in `task_notes`: text, its author,
the day it was written, and no editing in place — the log is appended to, and taking an entry off it is a
management-only action. Deleting the card takes its notes with it for the same reason.

The ticks are also the measurement. A card's own percentage is its plan read straight: 1 of 4 steps ticked is
**25%**, printed as a small chip beside the priority and due date on the card, and spelled out in the editor's
plan heading as `1 of 4 done · 25%`. A project's percentage is the **average of its cards**, each card counting
equally — a card with one step moves the project exactly as far as a card with twenty — so four cards at 100,
50, 25 and 0 read 44%. Two cases are handled on purpose rather than rounded away: a card with no steps prints
**no percentage at all** and is left out of its project's average, because an empty plan is not a plan that has
made no progress; and a project with nothing measurable keeps the figure filed on its own row, so no bar ever
goes blank. That derived number is what every page prints — the Projects board, the project page's bar, the
Team popup, the chip on the card itself — from one rule in
`TaskService`, computed in the same read that fetches the cards. The only places still quoting the stored
figure are the outgoing emails and the notification bell, which report what was recorded on the project row; if
a project's mail and its bar ever disagree, that is the reason. A card moved to **Done** keeps printing its
checklist percentage: the steps are the measurement, so no separate "completed means 100" rule was invented — since
**Stage 70** finishing it ticks the steps present at that moment and the ordinary arithmetic reads 100. Since
**Stage 101**, a step added afterwards stays unticked and lowers that percentage even while the card is **Completed**.

Stage 22 took the writing out of the board, and **Stage 41 took the list out with it**. A card now prints one
number about its plan and nothing else: the `%` chip on its own line, no `Checklist` heading, no step rows, no
tick boxes, no × — on every board, for every reader, including the person the card belongs to. The plan itself
lives where the card's other details live: clicking the card opens the popup, and the popup holds the steps,
their boxes, the `n of m done` count and the line that adds a step. One card therefore looks the same to
whoever is looking at it, and the boxes a reader earns by owning the card arrive on the click they already make
to do anything else with it. Ticking follows the rule it always followed — `TaskService.toggleChecklistItem`
accepts the assignee and management — and the popup now asks the server who is looking rather than guessing:
`TaskForm.viewerEmpNo` is set from the session by the route that loaded the card, and `TaskForm.mayTick` is
`toggleChecklistItem`'s own set — management and the card's holder — so anybody who may tick a box is shown one and
nobody who cannot is invited to click a refusal. *(Since **Stage 86** that set is narrower than the readers the card
now opens for: a colleague reads the same plan with every box stopped (`class="chk-box locked"`, the reason on its
`title`), and `TaskService.openableBy` — the renamed read gate — is what hands them the card.)* Moving a card between columns keeps the shape it always had —
management may move any card, anybody else only a card assigned to them.

Stage 23 put the *writing* of those lists inside the editor popup, and Stage 41 made that popup the only place
any of it is. Opening a card shows its plan as it stands — `3 of 7 done · 43%` — and each step is now a row of
box, text, ×: a real tick box on the left where the old ✓/· mark used to sit, the step's words beside it, and an
× to take the row off, and under the list the one-line `New step…` box: type the step, press **Enter**, and it is
on the card — that line carries no button, because the box is the whole control. Writing a plan is split in two —
**adding** a step and **taking one off** — and neither of them is the tick. Since **Stage 89** `/app/tasks/checklist/add`
serves management and the card's own assignee (`TaskForm.mayAddStep`), while `/checklist/remove` stays management's;
both refuse everybody else, so the add line renders on `mayAddStep` and the × on `TaskForm.canEditAll`, while the
boxes render on `mayTick`. An assignee who opens their own card therefore gets every box live, the `New step…` line
and no ×; management gets all three on anybody's card. The log underneath
keeps its **Add note** button, since Enter inside a note means a new line.

Under the `3 of 7 done · 43%` line, before the rows it counts, the popup draws that figure as a bar too — a small
track filled to exactly the card's own percentage, two steps with one ticked filling it to half. It is the one
progress bar the application has (`.progress` holding a `.bar`, the pair a Projects row and a project page's
Progress cell use), so the card's measurement looks like every other measurement on the site rather than starting
a second style. It lives inside `#task-steps-<card>`, the block a tick already swaps, so the fill moves in the
same response as the box and the number above it — nothing to reload, and no bar left holding yesterday's figure.
A card nobody has planned gets no bar at all: an empty plan is not a plan sitting at zero, which is the same rule
the card's percentage chip obeys.

**Stage 70 tied the plan to the card's own status.** Moving a card into **Completed** — by the drag or by management's
**Status** picker in this popup (the ◀ ▶ arrows Stage 70 counted as a third way were retired in **Stage 103**) —
ticks every step of its plan, and moving it back out puts
each step where it stood. Two details make that more than a cosmetic trick. The state each step was in is remembered
**per step** (a `pre_completed` column, one value per row, added by **V15**), and only remembered rows are restored.
Since **Stage 101**, every newly added step lands **unticked**, including one written onto an already-finished card;
it has no remembered state and stays unticked when that card reopens. V15's earlier migration finished the plans that
already existed on Completed cards and remembered where those steps stood; Stage 101 rewrites no existing ticks.
While a card sits in **Completed** all its boxes are stopped: each row keeps its square and shows its actual tick state,
but there is nothing to click, and the route refuses a tick all the same. Move the card back to work its plan.
The `New step…` line stays on a finished card for management and, since **Stage 89**, for the card's own holder;
the × stays management's. Completing a card ticks the plan present at that moment, so it reads 100%; adding a step
afterwards can lower that figure. A project averages its cards, so its progress follows the plan as it actually stands.
Below the plan is the **log** — notes appended against the card, newest first, each signed with its
author's name and the day it was written, capped at `TaskService.NOTE_MAX` (500 characters). Management
and the assignee both write it; only management can take an entry off, so a record cannot be quietly
rewritten by the person who made it. Both sections save without closing the popup or reloading the board:
each form names its own block (`data-live-target="#task-steps-42"`), the response returns that block
rebuilt, and `app.js` puts the caret back where it was — which is what lets a list be written one line at
a time. A step, a note or the card's own `?edit=42` redirect is what puts the popup back after such a
save; the popup's **Save** button is the other control that navigates. A card's move to another column does not:
on a project page it is done in place (Stage 105) and on a status board it posts the change and reloads that board
on purpose. Swapping a board in place no longer takes the drag with it either — since **Stage 94** `kanban.js`
listens on `document`, not on nodes a swap throws away.

Ticking a box does not reload the board and does not close the popup. The step's form names the plan it belongs
to (`data-live-target="#task-steps-42"`) and the two blocks that number depends on
(`data-live-extra="#project-progress,#task-body-42"`), so one post redraws three places at once: the popup's list
comes back with that box ticked and its words struck through and the `n of m done · %` count moved, the card
behind the popup comes back carrying the percentage its steps now add up to, and on a project page the bar above
the board comes back with the project's average. The page underneath does not move, the scroll position and
anything half-typed stay where they were, and the popup stays open so the next step can be ticked without opening
the card again. A missing companion is skipped rather than an error — the general board prints no
`#project-progress`, so the same form works on either page. Adding or removing a step names the same two
companions, because both change what the card's percentage is measured from. The log's forms name only their own
block: a note changes no measurement. With JavaScript switched off every one of these is an ordinary form to the
same URL and a normal page load, and the page that comes back says exactly the same thing; the only difference is
that the whole board redraws.
A reader who does not own the card is not offered a box at all, because markup should never invite a click the
server is going to refuse.

**Stage 72** put card removal at the right-hand end of that same line. The note box and management's **Delete task**
button now sit on one foot row — the box taking the width it always took, the button at the far right of it, their
bottoms aligned — because writing the log and taking the card off are the two things a person does at the end of this
panel, and the popup is the only place a card is ever opened. The button posts to `/app/assigned-tasks/delete`, which
has required management since Stage 17 and until now had no control pointing at it from inside the popup; it renders on
`TaskForm.canEditAll`, the popup's copy of that same route-side rule, so an assignee who is not management and a reader
with no part in the card see the note box with no delete beside it, and a card being created sees neither. It asks
first: the click brings up a confirmation naming the card and saying its steps and notes go with it, and they really do
(`task_checklist_items` and `task_notes` are `ON DELETE CASCADE`), so the one thing that cannot be undone is the one
thing that is never done by accident. Afterwards the popup is gone and the board it came from is reloaded — the project
page, the backlog in whichever width it was being read, or the Assigned tasks page — because a card that has been
deleted is not a card to reopen. It shares nothing with the forms above it: they ask to be repainted in place, and there
is no card left to paint.

**Stage 81 briefly made that foot line answer by the card's world; Stage 84 put it back.** A **project** card now
offers management's **Delete task** again, exactly as a backlog card does — a POST to `/app/assigned-tasks/delete`,
one confirmation (*"Delete Ada? Its steps and its notes go with it. This cannot be undone."*), and the board it came
from reloaded afterwards. The **Archive task** button Stage 81 had put in its place is gone; a card reaches the
archive by **removing the bucket it stands in**, and since **Stage 106** no page lists what is there (the panel and
its 🗄 went; the restore and permanent-delete routes remain, with no control). The delete is management's
(`TaskService.deleteAssigned` refuses anybody else), so a project's own
manager and the card's holder are offered nothing — a manager may archive cards by removing a column, which is what
`TaskService.mayEditLanes` decides, but destroying one is management's call. An archived card is not openable by id
and takes no save.

## Other tasks

`/app/other-tasks` is the backlog: cards that belong to no project, grouped into **buckets** — from the `buckets`
table — and drawn as a vertical run of bucket **groups** (`div.other-groups` with `id="otherBoard"`). Each bucket
prints its **name as a heading on top**, the way a project page prints its project name, and directly under that
heading its cards are cut by the **same five status columns** the **Assigned tasks** page uses — Not started, In progress, On
hold, Blocked, Completed (Stage 57). So a bucket is now read by *where its work has got to*, rather than by sitting in
one nameless column; which bucket a card is in is said by the heading over it, never by the
column it stands under. Note that this is the **backlog's** shape, not a project's: since **Stage 75** a project page cuts
its cards by bucket and prints no status column at all — how far a card has got is said on the card itself there
(**Stage 78**: a dot, the word, and the card's own tint), not a heading — so the two boards read a card's two different facts, which phase
it is in, and how far it has got. It became its own page at Stage 47. Before that the same board sat at the foot of the **Assigned tasks** page, and
`/app/other-tasks` printed a flat read-only list of those cards beside it — two ways of reading one set of work, one
of them a dead end. Now each page holds one board: Assigned tasks prints its five status columns and nothing else,
and the list is gone. The page head carries the name and the sentence that says what the work on it is *not*
(**Other tasks — tasks outside of projects**), with the small `+ Add bucket` button at its right — since **Stage 97**
its only control there — which is where the board's own repeated heading used to be.

Above the groups sits the same **Bucket** dropdown the Assigned tasks page's Other half carries (Stage 93): pick a
bucket and the board shows only that group — the other headings and their columns go, the five statuses stay — or
pick **All buckets** to read the whole run again. A bucket picked while empty is still printed, with its five
*"Nothing here."* columns, because naming a bucket is a question about that bucket. The choice lives in the URL as
`?show=<bucket id>`, so a filtered board can be bookmarked, shared and reloaded, and every card you open, save or
delete from it comes back to the same narrowed board. **All buckets** is the way back: it is option `0`, the absence of
a filter, and the address bar follows the choice because the picker is a live filter form. Since **Stage 97** this
dropdown is the board's *only* view control — the page head's **Show all buckets** button and the wide view it switched
on are gone, and the empty groups it used to print are reached one at a time by naming them, which is what a pick does.
The same stage is why a bucket you have just created does not vanish as you press the box: adding one lands you on
`?show=<its id>`, the new column's own filter, and deleting the column you were filtered to drops the filter instead of
leaving the URL pointing at a column that is gone. The dropdown is
**management's**, like the shared board itself: a Viewer keeps the one bucket their own card stands in and is offered
no picker (Stage 51).

The backlog has **no default buckets**. `buckets` is created empty by `V1__init.sql` and no migration or seeder ever
inserts a row into it — `V10` seeds only `project_buckets` and `V16` only reshapes a project's phases — so a fresh
database opens this page with **no columns at all**, where a new project board opens with its category's Settings list
(the three phases, **Engineering**, **Procurement** and **Production**, on every board made before **Stage 100**). Every
bucket here is one a manager named, and the naming is the only
way a bucket comes to exist. The three project phases are not offered here as a set either: this board's columns are
its own table, compared for duplicates against its own columns, and nothing on Settings' **Project bucket settings**
card can add or remove one of them.

This is a **management** page (Stage 51): the shared backlog and everything you do to it — naming a column, adding a
card, renaming or emptying a column, choosing which column the board reads — belong to management
(`Authorization.canManage`; since **Stage 110** that flag means "any chip except *View teammates' tasks*", while each
individual write is guarded by the chip it is about — *Manage buckets* or *Edit any task*), and the three links that
reach it (**Other tasks**, **Access
control**, **Settings**) are dropped from a Viewer's sidebar. The one path kept personal: a Viewer whose card is on
the backlog can still reach it here, so a Viewer hitting `/app/other-tasks` — from a notification, say — gets the board
**scoped to their own cards**: exactly the bucket their card stands in — its heading and its five status columns — and
none of the others, with no add line, no `+ Add bucket` button, no `⋮`, no bucket popup and no bucket dropdown (Stage
93; and since **Stage 97** that dropdown is the only thing on this page which changes what the board shows). Their own
card still opens in the editor and, since **Stage 103**, carries no move buttons of its own: the drag is the way its
status moves here too. The routes check the role again: the `+ Add task` create popup is not handed to a non-manager, `POST
/app/other-buckets` bounces one, and a column-carrying create posted to the save endpoint by a non-manager is
discarded back to their scoped board rather than saved. For management the page is what it has always been. Every
account can still **read** the backlog in the sense of seeing the whole board — that is now management's view; a
non-management reader sees only their own slice of it.

For management, every card write goes through the `+ Add task` line at the foot of each **bucket** — one line per
bucket, since it creates a card *in that bucket* and its starting status is chosen in the popup — which opens the same
create popup an assigned card uses, filled with that bucket already chosen. Naming a bucket is the dashed
`+ Add bucket` box at the foot of the run of groups; a bucket they name is on the board the moment it is accepted. A
bucket's heading carries its name, how many cards are in it, and a `⋮` menu that opens the one popup the board has
(`#bucketModal`): rename the bucket, or delete it. Deleting a bucket that still holds cards has to say where they go,
so that popup also lists the other buckets to move them to — a bucket with work in it cannot be deleted until the board
has somewhere to send it, and the last bucket standing is not offered for deletion at all. An empty bucket is deleted
without asking, because there is nothing to answer.

Since Stage 57 a card here no longer prints its own status word: the status **column it stands under** *is* its
status, so saying it again on the card would be the two-places-for-one-figure that is only worth it where the columns
cannot say it — which is why a *project's* board prints the word on the card (**Stage 78**, see above) and this one does
not. What the card's last line still carries is the person who picked it up — their avatar, name in
the hover label — and the percentage its own steps add up to, the same `kpct` chip an assigned card prints, on the same
live region, so ticking a step in the popup moves the number on the card behind it without the board moving. A card a
reader may move is a drag source: since **Stage 58** management's copy of every backlog card, and an owner's copy of
their own, carry `draggable="true"`, so dragging one into another status column of its bucket posts `/app/tasks/status`
and reloads — it lands in the column it was dropped in **under the same bucket heading**, because a drag changes
`status`, never the bucket. The owner's `←` / `→` arrows that used to sit underneath as the keyboard/no-JS path to the
same write were **retired in Stage 103**, on this board and on the Assigned one together: the drag is the on-card way to
change a status, and the popup every reader can open shows **Status** greyed to a non-manager (Stage 49). That is the
one loss worth knowing about — a keyboard-only owner asks management. A project's lane board keeps its single
**✓ Done / ↺ Reopen** button, because there is no Completed column to drag into there and finishing work is not the same
act as moving a card. That drag is the list, not the rights: `draggable` is printed for exactly the accounts
`TaskService.updateStatus` accepts — management (any card) or the assignee (their own) — so a card a reader cannot move
does not look grabbable, and a `Viewer`'s scoped board shows their own card draggable and a colleague's not at all.
The drag works on a **narrowed** board too (**Stage 94**): the Bucket dropdown answers by replacing the board in
place, and a replaced board is brand-new markup with nothing attached to it, so `kanban.js` now answers status drops
and the `.kmove-btn` press from `document` and finds the column under the pointer itself — otherwise narrowing to one bucket
was the end of dragging. A project page's `✓ Done` / `↺ Reopen` ride the same delegated press, which is why they
still work after that board has been swapped by a bucket rename. (**Stage 103** retired the arrows those boards used to
print, so the delegated press is the lane button's alone.)
Nothing here is a second editor: clicking the card opens the same popup the project boards use, filled from the database. On
that popup a backlog card answers its **bucket** with a **Column** picker rather than a project and a lane — which is
how a card here changes bucket, because the bucket is said by that field, not by the status column the card happens to
stand under (which is `status`, not the bucket) — and management's copy of the popup carries the **Where it lives**
picker above it, so a card can be handed to a project and filed in one of that project's buckets from the
same click (Stage 48; the bucket list is the project's own since Stage 75).
A card belongs to a column rather than to a person, so the editor accepts it with the *Assign to* field left blank —
the rule is read from the row's `kind`, never from the form, so an ordinary assigned card still cannot be saved by
taking its person off it. Management owns every field of it; the person a card is assigned to gets their own dates
and priority and nothing else, because **which column a card sits in is management's answer on this board too
(Stage 49)**. The **Column** picker is printed to that reader greyed, with the whole board still listed so the card
reads in its context, and the line under it says "Management moves a card between the columns." It used to be the
one field wider than an assigned card's; the exception went rather than the drag. **Stage 58** gave this board a drag
back — but it is a drag along the bucket's five *status* columns, the same write the owner's arrows used to make (those
arrows went in **Stage 103**), so it never
touches this picker: which **bucket** a card belongs to stays management's, said only in the popup. Writing a *new* card onto the board used to be
every account's — the create popup pinned a non-manager's card to themselves and `Not started` and greyed those two
fields before validation. Stage 51 retired that: the `+ Add task` line and its create popup are management's, so on
this page a create names its person and its column freely (management's `createBacklog` pins nothing), and a
non-manager who posts a column-carrying create anyway is bounced back to their scoped board. A card that has to be
pinned to its writer and filed without a choice is written from the **Team** sheet's Assign panel, which is management's
own tool for handing work to a person. *(**Stage 90** reopened the *self-service* create on the reader's own **Assigned
tasks** page — same pinned shape, different door: the shared board's line and popup here are still management's, and a
non-manager's POST carrying `back=other` is still bounced.)*

In the page head, opposite the title, sits the small `+ Add bucket` button — since **Stage 97** the only control the
head offers (Stage 59 —
it used to be a dashed full-height box trailing the board as a fake column, which read as stranded once the board
became a vertical run of groups). It opens that same popup on its add half and
asks one question, the bucket's name. It is management's (Stage 51), as is the `+ Add task` line under each group and the whole
`#bucketModal`: a non-manager's board draws none of them, so the popup — add, rename, delete — only ever prints for the
reader whose routes accept it. A name already on the board, or no name at all, is answered inside that popup —
the word stays where it was typed and the reason prints under the field ("There is already a bucket called that.",
"Give the bucket a name.", "Bucket names are 40 characters or fewer.") — rather than reloading the page quietly and
leaving the writer to guess. A name that is accepted appears as a new bucket group and closes the popup, on the same screen,
with no reload: the form names `#otherBoard` as the block to redraw and `#bucketModal` as the one that comes with it.
Scripts off, the create answers by landing you on the new column's own filter — `?show=<its id>` — because a bucket that
holds nothing would otherwise disappear as you pressed the box (**Stage 97**).
At the foot of each bucket group sits `+ Add task`, and it opens the *card's* popup rather than the bucket's: one GET form
onto this page carrying `new=1` and the bucket to file the card in, swapped into `#taskModal` while the board
underneath stays put — the same mechanism a project page's lane list uses, and the reason it asks nothing about a
project. It is management's line, because the shared backlog is management's board (Stage 51).
The **Bucket** dropdown above the groups is the board's one view control (Stage 93, and the only one since **Stage 97**
took out the head's **Show all buckets** button): empty buckets are hidden by default, because a run of nine headings
where five hold nothing is a list of intentions rather than of work, and the way to see one of those five is to name it
— a bucket picked from the dropdown prints its group whatever it holds, so an empty pick gives you its heading over five
*"Nothing here."* columns. **All buckets** is the option that means no filter, and it is the way back. The dropdown lists
every bucket the board has, including the ones it is not printing, which is what makes naming one possible at all; the
old toggle's own trick — printing every empty column at once — is gone, and no page on this board widens itself. The
control is management's (Stage 51): a Viewer's board is already scoped to the bucket holding their own cards, so they
are offered no picker. What the choice does to the board is what it does to every write from it — a rename, a delete, a
new card, a card saved or moved between columns from the popup all answer on the narrowed board, because the filter
rides inside the form's `back` token (`other:<id>`) rather than in a view flag of its own.
Every write on this page is asked where it came from and answered on the page it says: the card popup carries
`back=other` — `back=other:<bucket id>` when the board is filtered — which is why **Save** — and a refusal, and a
deleted column's *move its cards to* — all land back on
Other tasks rather than dropping the reader onto the Assigned tasks board.
With JavaScript off, every one of these is a plain form to the same routes and the page that comes back says the same
thing. `bucket-modal.js` only knows the ways out: `×`, **Cancel**, the backdrop, and Escape.

## Forecasts

`/app/forecasts` is the money sheet: one row per project, one line per project per month, and the
arithmetic the company reads its month off. It is cut by category the way the Projects board is — a tab
row (**All** plus one tab per project type, each counting what clicking it would leave) and, under it,
**one panel per category**: a type chip and its count above that category's own table and its own totals
line. A category the current search leaves nothing in is not printed at all, so the sheet never describes
a row that is not there. It opens on the current month; the two controls either side of the month's name
step a month at a time (**‹** and **›**), and a third, **This month**, is drawn only
while you are looking somewhere else. The address always names the month on screen (`?month=2026-10`), so
what you are reading is a link you can reload, bookmark or send. A month the page cannot parse is not a
dead end: the sheet falls back to the current one.

Everything on the grid comes from one identity, and each cell is either a figure somebody typed or the
arithmetic of it:

    revenue = invoiced in previous months + this month's forecast + remaining to next months

**Revenue** is the project's own number from the Projects board, printed in that project's currency since
**Stage 108** (`₮ 12,500,000`, or `$ 2,500,000` on a dollar project — **Stage 109**) — the sheet's other money cells,
and its headings, stay bare grouped figures and unit-less words, because the identity's terms are what it adds
up, one panel may hold several currencies, and a sign on every term would only crowd eleven columns. **Invoiced in previous months** is
typed nowhere: it is the sum of that project's ledger lines for every month strictly before the one on
screen, so stepping ‹ changes it, and so does saving a line — write October's invoice and November's
"previous" column already includes it. **Remainant to next months** is what the identity leaves over, and
it is the one derived cell that may go negative: a project planned above its own revenue has spent its
room, and the sheet says so rather than clamping the figure to `0` and hiding the mistake.

Four cells are written: the month's forecast, the invoiced amount, the invoice month and the invoice
status. Each row is its own form, so a cell saves as soon as you leave it, and what comes back is that row
and the totals line under **its own panel**, redrawn in place — no reload, the caret and the scroll stay
where they were. Each panel's line therefore names its own category's money, and typing into a Site
project cannot move the figure under Fabrication. The status is what colours the row, and the key above the
grid says what each colour claims:
**Not invoiced** is the pale yellow of a plan entered, **Partially invoiced** the orange of a month that
has caught up with part of it, **Fully invoiced** the green of a settled one. A row nobody has written in
gets no colour at all — pale yellow would have it claim a plan nobody made, and an untouched row is exactly
what this sheet exists to catch. For the same reason an untouched line opens as empty boxes rather than
zeroes you have to delete before typing. A row that does say something prints its real zeroes, because on a
money grid a `0` beside a plan is a statement, and it is the same figures the totals below add up.

The invoice-month cell answers *when this was raised*, not *which line this is*. A line always belongs to
the month the sheet is reading, so naming November does not move October's money: it stays on October's
row and November stays blank. Blank and "this month's own name" are the same statement, so only a real
difference is stored. Amounts are read the way the Projects board reads money — `8600000`,
`8,600,000` and `8 600 000.50` are one figure, and a blank cell means nothing rather than zero. Anything
else (text, a negative, a number too large to be one month's money) is refused **under the cell that
caused it**, with the characters still in the box and nothing written anywhere.

Typing in those four cells is management's. A Viewer reads the same grid, the same derived figures and the
same row colours, as text: their row carries no form at all, and a hand-built POST to
`/app/forecasts/save` is turned away with a redirect and nothing written. Hiding markup is not the check —
the route is.

The search bar and the **Status** picker above the panels are the Projects board's: the same four fields
searched (number, description, client, manager), every word required, every panel narrowed and each totals
line re-summed over what is left. The open category is a tab, not a picker — a tab says how much of each
category there is to look at, which is the one thing a dropdown narrowing a single table could not. It
travels as `?type=` on every link and as a hidden field on the toolbar's form, so searching or switching
status from inside a panel keeps you in it. A category name the sheet has no panel for — typed into the
address bar, or posted through devtools in a row's hidden `board_type` — reads as *no category at all*: the
whole sheet prints rather than an empty page, and a row's save repaints the totals line that actually holds
it. Every filter rides through each save as hidden fields, so typing a figure into one filtered row cannot
widen the sheet behind your back and change the totals line you are reading.

Finished work prints below the running rows of its own panel, on its own: **Completed projects — invoiced**
drops the columns that only mean something for work still running — there is no forward plan to write for a
job that has ended — and keeps the money it actually raised, with its own count and its own total. A
completed project still has ledger lines, and the month it finished in reads them the same way any other
row does. A category can therefore have two tables and two totals lines, and its tab's pill counts rows in
both.

Under the page, the ledger is a new table: `project_forecasts` (V8), one row per project per month. The old
`forecasts` table is left exactly as the importer wrote it and nothing on this sheet reads it any more, so
the sidebar pill beside **Forecasts** counts projects rather than those legacy rows. With JavaScript
switched off, every cell is an ordinary form to the same routes and the sheet that comes back says the same
thing.

## Lessons

`/app/lessons` is the sheet the company keeps of what a job taught somebody: one row per lesson, and the
columns that make a row findable — the day, the person, the project, the card, the words, and whether
anybody has talked about it yet. It opens newest-first with nothing filtered. The **Date** heading is also
the control that reverses it, and the two pickers above the grid cut the sheet to one project or one
person. The address names the view in use (`?project=3&employee=EMP00005&sort=old`), so what you
are reading is a link you can reload, bookmark or send — and the order, which no picker in that form answers
for, rides along as a hidden field so the first picker change cannot lose it.
**Clear** is drawn only while a picker is set, and it drops the two pickers and nothing else: a control that
widened the list by also jumping it back to newest-first and folding the half you are reading would be two
answers to one press.

Four fields are asked for: the project, the card it came from, the day, and what was learned. **Employee is
not one of them.** A row's person is whoever logged it, taken from the session on the way in, because the
sheet is a record of who learned something and a picker next to it would let a lesson be attributed to
whoever was handy. A blank date means today, and an edit keeps the row's own author — rewriting a lesson
must not move it under the name of whoever happened to open the popup. The same account is the same avatar
colour on every row, which is what makes that column readable at a glance.

The card is optional, and a card names its own project, so the two may not disagree: a row that read
"M-01250 / a card on S-00005" would be a statement the sheet cannot check, and it is refused under the card
field — "That card is on another project — clear it here, or choose its project above." The disagreement is
read off the database, never off what the form chose to say about the card it points at. A blank lesson
("Write what was learned — the sheet has nothing to show without it."), a date the sheet cannot sort by, a
project or a card that has since gone, and text past 500 characters are answered the same way: the reason
under its own field, the popup still open, the characters still in it, nothing written.

Writing rights split once, and the same split answers all three routes: every signed-in account logs a lesson
under its own name and may tick, edit or delete **its own**; only management may change or delete somebody
else's. A reader without that right gets no buttons on the row at all, and the popup their click
opens shows the full text, the project and the card as plain lines with no form inside it — "Logged by … Only
its author and management can change what a lesson says." Hiding markup is not the check:
`LessonService.mayWrite` is asked again by `/app/lessons/save`, `/status` and `/delete`, and each of them
reads the row back rather than trusting the id the page was drawn with — so a lesson that has since been
deleted, or that has changed hands, cannot be written by the stale row still standing in front of you.

The one state a row carries is **Status** — whether the lesson has been talked about, the green
`● Discussed` or the grey `● Not discussed` pill. The tick is the one write that leaves a row exactly where
it is, so `✓`/`↺` replaces that row's own `<tr>` and your place on the sheet is untouched. Delete moves a row
off the sheet, so it redraws the whole sheet and the "3 lessons" line beside the pickers with it. That line
lives outside the live region because it is the toolbar's and the toolbar is never replaced — it is named as
the swap's companion (`data-live-extra`) instead, by the pickers and by the delete button both, since a
filter changes that number exactly as a row leaving the list does. Delete is the one write here with no way
back, which is why it asks first.

**The archive is gone (Stage 116).** The sheet used to carry an **Archived** heading and an archive/restore
button on each row; since projects are not archived in practice, the whole affordance came out rather than
sit there unused. The `archived` column stays on the table so rows put away before then are still data, but
nothing prints them, nothing counts them and no route offers to bring one back.

Under the page this is the same `lessons` table the importer filled, widened (V9) rather than replaced: a V1
row was already the same *kind* of fact — a piece of text about a project — and a second table would have
made the sheet's first column a union of two sources and every write a question about which one a row lived
in. Six columns arrive with three indexes; the old `title` and `summary` stay in the table — `title` is `NOT NULL`
with no default, so leaving them out of an `INSERT` would mean rebuilding the table for no benefit — and the sheet
prints the single `lesson` column their text was joined into once. That join is also held in reserve for a row whose
`lesson` came through blank, which is the only way the old pair is still read.
The carry-overs show with no employee, no card, no date and status `Not discussed`, sorted to the end of
**both** orders rather than given an invented day, and they are unowned, which is management's to tidy and
never a peer's to write. Author, card and project are all `ON DELETE SET NULL`: a lesson outlives the person
who logged it, the card it came from, and the project it was about, because what it says is the most useful
thing on the sheet and not a dependent row to clean up after. The sidebar pill beside **Lesson learnt**
counts that list. With JavaScript switched off, `+ Add lesson`, the tick and the bin are ordinary forms to
the same routes and the page that comes back says the same thing.

## Settings and the Team sheet

`/app/settings` is, for management, the **Team** sheet and your own account card, and below them the
three app-settings panels — **Project types**, **Project bucket settings** and **Email notifications** (Stage 61, laid out and
made editable in Stage 63). A password is set, and reset, from the Team sheet's own popup, where an administrator types it;
this page has carried no password box of its own since **Stage 99**, and no account is stopped because its password is
still the number the system gave it. Since
Stage 51 the Team sheet is management's — the roster block, the toolbar and the employee popup print only for a
Manager or Admin, which since **Stage 110** means a role holding *Manage employees* — so an **Employee**'s Settings is now
a redirect back to `/app` rather than the page that used to print them their own account card, and their sidebar carried
no Settings link from Stage 51 on. For management the sheet is the roster read down — **Name, Employee #, Position, Department, Role, Email, Open tasks** — with a search
box ("name, email, employee #"), a **Position** picker, a **Role** picker and "22 of 22 shown" at the right end. It
opens by name, A→Z; every heading is also the control that sorts that column, and pressing the one already sorted
turns it around. Above the table, management gets a card that reads "Add a new employee account…" with a green
`+ Add employee`; each row ends in a red ✎ and a bin. Your own row wears a green `YOU` pill instead of a bin that
would only refuse you.

A row is a **person**, not a login: colleagues on the roster appear whether or not they have an account yet, and the
pencil on one of those rows *creates* the sign-in rather than editing one. The **Role** column names the one row of the
**Roles & permissions** catalogue that account signs in under, and since **Stage 110** that role's chips are the whole of
what it can do — the position beside it is a job title and decides nothing, which retires the ladder this paragraph
described until then (the position's permission level, or the account's own access word when it had no position). An account with
no role at all is not an account with no rights: `RoleRepository.roleOf` falls back to the catalogue's default (Employee),
the same row a null `role_id` is counted under in that table's EMPLOYEES column.

**Department** is the picker **Stage 117** put between Position and Password in that popup: nine words — Management,
Finance, Human Resource, HSE, Production, Engineering, Sales, Project, Procurement — and only an **Owner or
Administrator** may set or change it, tested as a role that holds **every** chip rather than by its name. On a create
it is required (*"Choose a department from the list."*); on an edit it opens on *— Keep <the stored word> —* when the
person's row carries one of the older free-text values (*General*, *Operations*, *IT / Systems*), so saving the popup
does not clear a row nobody meant to change. A reader with *Manage employees* and not the full set adds accounts
without the picker, and a department posted by hand is refused (*"Only an Owner or Administrator can set a
department."*).

**Open tasks** counts a person's
unfinished cards: management sees everybody's, and anybody else sees their own with a `—` on the rows they cannot
read, so `0` and `—` are different answers and both sort to the bottom. A blank department, a missing role and that
dash all land last whichever way the column is read.

Who may do what is printed and enforced twice over. Since **Stage 110** the two people chips are read apart here:
*Manage employees* adds accounts and may set a name, an address, a position and a password, and *Manage roles &
permissions* is what makes the popup's **Role** select appear at all — a reader with the first chip and not the second is
offered a popup with no role in it, and `UserService.addUser` ignores a hand-built `role=` field rather than honouring
it, filing the newcomer under the catalogue's default. (That is a widening of the old rule, which let only an
**administrator** set a position or a password: those two writes now belong to the people chip and only the role stays
with the roles chip. A position still cannot be invented from here — it has to be on the catalogue, which is filled on
**Access control**.) The bin
shows why it will not press instead of vanishing: "Only somebody who manages employees can delete an account.", "This
person has no account to delete yet — the pencil creates one.", "The Super Admin account cannot be deleted.", "You cannot delete
your own account.", "Only somebody who manages roles can delete that account." — the last of those is Stage 110's
rewording of "Managers cannot delete Admin accounts.", and its rule moved with it: `UserService.deleteUser` refuses the
press when the target's role holds *Manage roles & permissions* and the caller's does not, so a people-manager cannot
delete the role that governs them. Delete is the one write here with no way back, so it
asks first; everything else — a search, a picker, a heading, a save — happens in place, with no page reload and your
scroll position kept, and the address bar still names the view you are looking at (`?q=gan&role=Manager&sort=tasks:desc`)
so it can be reloaded, bookmarked or sent. With JavaScript switched off, `+ Add employee`, the pencil, the save and
the bin are ordinary forms to the same routes and the page that comes back says the same thing.

Two things to know the first time you use it on a fresh database. **The Position picker will be empty**: the
position catalog is its own table and nothing on this page fills it — you add positions on **Access control**, and
only then can the popup file somebody under one. And **an account created without a password signs in with its
employee number** (typed in any letter case); until an administrator types a private one from this sheet, that
employee-number password is what the account has — nothing forces a change since **Stage 99** — so set your own
before you build the team. The whole sheet belongs to *Manage employees* now — since
**Stage 110** a role holding none of the four chips this page is drawn from is redirected to `/app` before any of it
prints, where the old code let a `Viewer` in to read their own account card — and the sheet is the same set of people
Access control lists, from the same two tables.

Below the Team sheet, management gets three app-settings panels (**Project types**, **Project bucket settings**, **Email
notifications**); they print only for the chip each one is written by, and every route behind them re-checks that chip rather than trusting
the markup. **Project types** is the company's list of categories, and since Stage 63 it is a table rather than nine strings in
the code: each row prints its letter badge, its name, how many projects are filed under it, and a ✎ and a bin, and the add row
below asks for a *Code, e.g. P* and a *Description, e.g. Parts supply*. A name may be renamed; **the letter never changes**,
because it is the head of every project number already issued under that category (`S-01`), and a rename carries the old label
across the projects and the lane defaults in the same write — the answer line tells you how many projects moved with it. Retiring
a category that still holds projects is refused with a count and an instruction ("Move them to another category first, then retire
it"), and a letter already in use, a blank or over-long name, or a name another category already has is refused the same way, in
place, with no page reload. **Email notifications** reports the
mailer's real state — the current delivery mode and your own address on file — and gives an **administrator** with an
address a *Send test email to myself* button; it sends one message through the existing SMTP config in `data/.env`
(`PTS_SMTP_*`) to *you*, writes nothing to the alert ledger, and in dry-run mode simply logs, so it is safe to press
against the live database. That panel also carries the **outbound-mail switch** (Stage 62, drawn as a tick box since Stage 63):
an **administrator** sees *☐ Enable email notifications* with a *Save settings* button under it — the box is the current state and
the button is the write, so it works with JavaScript off too — while a Manager sees only a read-only "Outbound mail" line saying
whether PTS can send. The switch is a master gate in front of *every* outbound path (the scheduled due-date sweep,
notification mail and the delivery test alike), so while it is off — the default — nothing leaves the box however the
SMTP config reads. It is a strict tightening: turning it on cannot open a door dry-run or an unconfigured host had
already shut, since those short-circuit ahead of it. Only a super admin may move it, and the `/app/settings/mail-switch`
route re-checks that rather than trusting the button. Enabling real delivery on a live host still needs the deployment
side too — a `PTS_SMTP_PASSWORD` and `PTS_SMTP_DRY_RUN=false` in `.env`. **Project bucket settings** is the per-category list of
the buckets a brand-new project is cut from. Since **Stage 100** it is not a set of extras on top of anything: **it is the
board** — with one addition since **Stage 104**: a create popup may name a column of its own *after* these rows, and this
list is then what every board of the category opens with rather than the whole of what one board holds. Pick a category and its rows print as a coloured dot, a name and a bin, in the order a new board will carry them; a
category with none prints *"… has no buckets yet — add one below, or name one while creating a project."* — Settings'
own framing of the rule, rewritten with **Stage 104** when the popup could name a first column, so the panel and the
popup no longer disagree about whether such a project can be made: it can, one board at a time, and the caption above
the list says so too — *"name one here for every project of this category, or name this project's first one while
creating it"*.
A name that is blank,
too long, or already in this category's list (trimmed, ignoring case) is refused. *Engineering*, *Procurement* and
*Production* are ordinary names here now: a category that wants them lists them, and one that does not keeps a board without
them. Stage 75 made those three read-only rows pinned above the list with *"Always on every board"* at their edge, Stage 82
moved them into the list, and **Stage 100 removed them**, because no board gets a column it was not listed for. Since
**Stage 73** this list was what the create popup **offered** — the project's own picker was where management ticked which of
them that project actually had — since **Stage 92** the popup offers nothing, and since **Stage 98** the list is not an offer
at all.
Pressing **Add** puts the bucket on **every existing project of that category** that does not already carry it (the lane
lands last, in the catalogue's spelling and colour) and on every project created afterwards, and the green line above the
list says how many boards took it — *"Commissioning is a Site projects bucket now — 2 Site projects boards gained the
column, and every new project will open with it."* A board that already held that word, in any capitalisation, keeps its own
spelling and is not given a second column; another category's boards are untouched. Taking a row off is the opposite
direction on purpose: it stops **new** projects getting the column and leaves every board that already carries it alone.
The catalogue can still grow from the other
direction, but since **Stage 77** only on purpose: a bucket typed into a project's own picker —
into the dashed **+ Add bucket** box that trails the board's columns since **Stage 74**, on any bucket
board now rather than only an empty one since **Stage 75** — lands on **that board alone** unless the reader ticks
*"Also make it a default bucket for &lt;Category&gt;"* under the name box, which is drawn for management only. Ticked, the word
lands here too and every project created after that opens with it — the boards that already exist are not reshaped, because a
tick beside one project's name is not a decision about a dozen others. Left clear, no board but this one ever hears about it. That is
what keeps one project's private vocabulary out of nine hundred future ones, and it is why the add box's own caption says
an offer rather than a rule. The add line says which category it means out
loud: *"Adds to &lt;Category&gt; only"* prints beside the **+ Add bucket** button in the open (`span.bucket-add-for`),
because the category picker that decides it sits three panels up and the hidden `lanes` field that carries it is
not something a reader can see — the word typed while Fabrication is on show lands on Fabrication, and nowhere else. What does *not* write here is the other half of
Stage 74: **renaming a column** from its `⋯` changes that board's heading and nothing else, since a lane's cards point at it by id
and the catalogue is the list of columns a category's boards are made of, not a mirror of this board's headings. Since **Stage 98**
adding **here** is the one write on this panel that reaches boards that already exist; a removal here still affects only the
projects that come after — it takes nothing off a board that already exists and renames nothing anywhere — and no card is
ever refiled. Since **Stage 100** the same list is what `ProjectService.create` cuts a new board from, whole and in
order, and since **Stage 104** the create appends after it whatever its popup typed — names that never appear here. So a
row here is the only way **every** project of a category gains a column at once, and an empty list is no longer the end of
that category: its projects can be made, but each one has to be given its first column in the popup, and until management
lists a row here the next project of that category starts empty again.

Since **Stage 83** adding a bucket asks for a **name and nothing else** — the app picks the colour. The new row, and
the column a future board seeds from it, wear the next shade in the run: a hue exists to tell the columns apart, and a
reader naming a bucket is not being asked to make that decision twice. Every row's dot is that answer, so what the
catalogue shows is what the seeded column gets. A row that already carries a shade of its own — one written before
Stage 83, or one copied from a board whose column was recoloured — keeps it. The colour choice itself is not gone,
only moved to where it belongs: a bucket already standing on a project's board can be recoloured from its own `⋯`,
where the six swatches sit beside the column they change. (Stage 80 first put the swatches on the add line and on
the project page's **+ Add bucket** popup; Stage 83 took them off both creation forms and left them on the manage
half.)

Choosing a category is one live refresh of **that panel**, not of the page: the rows, the empty line and the
**+ Add bucket** line change together — its *"Adds to &lt;Category&gt; only"* label and the hidden
category it posts included — so the next add lands on the category on screen and never on the one you just left. Since
**Stage 82** the choice also survives the Team sheet above it: the two views share one address, so filtering or
searching the roster keeps the chosen bucket category (and filtering the roster keeps its own search, pickers and
order when you change category), and a reload brings back exactly the pair you were looking at.

## Access control: the role catalogue

`/app/access-control` is the page where the rights themselves are kept, and since **Stage 110** it is two halves with
separate chips behind them. **Roles & permissions** is the picture's table: a `ROLE / PERMISSIONS / EMPLOYEES` grid, one
row per role, `+ New role` sitting at the right-hand end of the panel's own heading, each row's chips printed in the
app's fixed order and its headcount live — it counts the accounts filed under that role every time the page is drawn, so
the number cannot disagree with the People sheet beside it. Four roles are seeded by `V21`, each one filed from the rank
the account had yesterday, and the rights they hold are the picture's own set — which is why the migration note under
*Data, backups and logs* spells out the one place that set is narrower than the old `Manager` rank:
*Owner* and
*Administrator* hold all thirteen,
*Project Manager* holds the nine about projects, boards, cards and money (it lacks the four about people, categories,
email and roles), and *Employee* — marked **(default)** — holds only *View teammates' tasks*. Any other name is a role
somebody added, and it starts with no chips at all.

**+ New role** and each row's **Edit** open the same popup: a name and thirteen tick boxes. A save is refused for a blank
name ("*A role needs a name.*"), for one the catalogue already carries ("*That role name is taken.*", compared trimmed
and ignoring case, on a create and on a rename alike), and for an id belonging to a row that has gone ("*That role is no
longer on the catalogue.*" — the popup refuses rather than adding a second row for a name the reader thought they were
editing). A refusal comes back as the page itself at 200, with the popup still up, the words still typed and the sentence
under the name box, because that is the only field a role can be refused on; the chips are a set and any subset of them,
empty included, is a legitimate role. **A rename or a re-tick takes effect for everybody holding that role at their next
request** — the set is not copied onto their accounts, it is read from the role, so one press widens or narrows a whole
department and nobody has to sign out. Deleting a role is not offered: `users.role_id` is `ON DELETE SET NULL` and the
app falls back to the default, so a role that disappears hands its holders to *Employee* rather than taking the people
with it.

The other half is the **People sheet**: the whole roster, its **Role** column now naming a row of that catalogue. For a
reader holding *Manage roles & permissions* each row carries a picker posting to `/app/access-control/role`, and one
save moves the account's chip *and* the EMPLOYEES column of the table above it in the same response — the two blocks are
swapped together so the page does not reload under either. Without that chip the role prints as a tinted chip you cannot
press, and `+ New role`, the **Edit** buttons and the pickers are simply not on the page: the routes refuse the POSTs
aloud, but a reader who may not write is not offered the control either. Below that, **Manage positions** is the job-title
catalogue, still reached by *Manage employees*, and it has lost its **permission level** field — nothing on this page
asks what a position is worth any more, because a position is what a person is called and a role is what they may do.

The sheet's **Department** column (Stage 118) works the same way for the other pair: an **Owner or Administrator** — again, a role holding every chip — gets a picker on every roster row, posting to `/app/access-control/department` and swapping only that cell; everybody else reads the stored word as text. It is drawn for people with no login too, since a department belongs to the person and not the account, and the older free-text values open as a selected *— &lt;word&gt; —* that cannot be posted back. The words are the nine **Stage 117** put in the Settings popup, and `EmployeeService.setDepartment` refuses anything else and writes nothing.

Two halves, two chips, one page: a role holding *Manage employees* but not *Manage roles* gets the People sheet with its
password and delete columns and the roles table read-only, and a role holding only *Manage roles* gets the roles table and
no people tools. `/app/access-control` redirects a reader holding neither.

## Data, backups and logs

Everything stateful is one SQLite file plus its WAL sidecars, in `data/` (or `/app/data` in the
container). Flyway applies `src/main/resources/db/migration/V1__init.sql`,
`V2__due_date_alerts.sql`, `V3__project_categories.sql`, `V4__notifications.sql`,
`V5__task_checklists.sql`, `V6__task_notes.sql`, `V7__project_task_buckets.sql`, `V8__forecast_ledger.sql`,
`V9__lesson_record.sql`, `V10__standard_project_lanes.sql`, `V11__project_invoiced.sql`, `V12__type_default_buckets.sql`,
`V13__app_settings.sql`, `V14__project_type_catalogue.sql`, `V15__checklist_completion_memory.sql`,
`V16__three_phase_buckets.sql`, `V17__bucket_colour.sql`, `V18__task_archive.sql`,
`V19__project_status_in_progress.sql`, `V20__project_currency.sql` and `V21__project_roles.sql` on boot.
V1, V2, V4, V5 and V6 are purely additive and
touch no existing row; V5 adds `task_checklist_items`, one row per step under a card, and V6 adds
`task_notes`, one row per entry in a card's log — each with a foreign key onto `tasks` that
cascades, so deleting a card removes its steps and its notes with it. **V8 is additive as well**: it creates
`project_forecasts`, the monthly ledger the Forecasts sheet reads — one row per project per month, keyed by
both, and it writes nothing into it, so a fresh database migrates to the same empty sheet an unmigrated one
shows. **V9 widens the `lessons` table the Lesson learnt sheet reads** — six columns (`lesson`,
`employee_number`, `task_id`, `logged_on`, `discussed`, `archived`), all with a default, plus three indexes,
and one `UPDATE` that copies each legacy row's `title` and `summary` into the new `lesson` column. That
backfill is the only writing V9 does, and it writes into a column that did not exist a moment earlier: no
old value is changed, and it is guarded by `WHERE lesson = ''` so a re-run cannot overwrite a lesson somebody
has since typed. The two old columns stay in the table because `title` is `NOT NULL` with no default, and
dropping them would mean rebuilding the table for no benefit. **V3 is the first migration that rewrites
data** — it adds
`projects.client`, `projects.start_date` and `projects.revenue` (blank/`0` for every existing row),
relabels the four old categories onto the nine current ones, and rewrites legacy project numbers
(`SIT-01` → `S-01`, likewise `MFG-`, `RMS-`, `LAB-`). **V7 creates rows** without changing any: it adds
`project_buckets` and a nullable `tasks.project_bucket_id`, then gives every project already in the database
its three original lanes, leaving each existing card unfiled rather than guessing which lane it belongs to.
**V10 rewrites that lane set and touches no card**: the six shared sub-categories
(`Fabrication/Manufacturing`, `Repair/Maintenance`, `Site projects`, `Labor supply`, `Goods sales`, `Other`) are
inserted for every project that lacks them and numbered 0–5 by **name**, so a board that already had a lane
called *Site projects* keeps that column in that place; an original default that holds **no** card is deleted,
one that holds cards keeps them and stands after the six, along with any lane a manager had added. Nothing is
filed by guesswork — a card with no lane keeps none, and prints under **Unsorted**. (Stage 75 took every board back off
those six onto three phases again — see **V16** below — so this describes what V10 did, not what a board looks like now.)
**V11 is a single additive column**: `ALTER TABLE projects ADD COLUMN invoiced REAL NOT NULL DEFAULT 0` gives every
existing project `0` — the amount actually billed, entered by management on the project header strip (Stage 60) and read
the same way revenue is. It rewrites no value and touches no other table.
**V12 creates one new table and seeds nothing**: `type_default_buckets` (`id`, `type`, `name`, `position`, and
`colour_slot`, added by V17) holds each project category's bucket list. Its original framing — *extra* lanes *appended
on top of the three every board opens with* — is the rule the app ran under from Stage 61 to Stage 99; since
**Stage 100** this table **is** the board a new project opens with, whole and in order, and no create path adds a
default trio over it — and nothing a create popup types goes *into* it: **Stage 104**'s additions are `project_buckets`
rows on one board, which is why a category with no rows here can be built once and is still empty for the next project. Because V12 writes no row, a database migrates to exactly the behaviour it had before, and the
legacy three came from V16 onto the boards that existed then, never from this table. What `ProjectService.create`
seeds is exactly the rows here, in their order, so a bucket added in **Settings → Project bucket settings** (Stage 61)
shapes every project created after it — and a category with no row at all could not make a project until **Stage 104**
let its create popup name one column of that board's own; the list here is still the only way a whole category gains a
column. *(Since **Stage 98**
an add on that panel also puts the column on the boards of that category that already exist — the one write there that
reaches back; a removal still shapes only the projects that come after.)*
**V13 creates one new table and seeds nothing**: `app_settings` (`key`, `value`) is a small key/value store for switches
the app itself owns — as opposed to the deployment secrets in `data/.env`, which the app can read but never write. Its
only key today is `mail_send_enabled`, the master gate on outbound mail (Stage 62). Because no row is seeded, a database
migrates to that switch **off**, so V13 on its own cannot turn the app into a mailer: every send stays log-only until a
super admin flips it on in **Settings → Email notifications**. The SMTP password never lives here — it stays in `.env`.
**V14 creates one new table and seeds it**: `project_types` (`code`, `name`, `position`) is the company's list of project
categories, which until now were nine strings compiled into the domain. Unlike V12 and V13 it *has* to write its rows — an empty
catalogue would leave the New-project form with no category to file under — so it inserts the same nine, with the same letters and
in the same order, and a database that has just been migrated behaves exactly as it did a minute earlier. Nothing else is
rewritten: `projects.type` and `type_default_buckets.type` keep their labels, and no project number changes. From here on
management edits that list in **Settings → Project types** (Stage 63); the letter stays fixed, because it is the head of every
project number already issued under the category.
**V15 adds one nullable column and then finishes the cards that were already finished**:
`ALTER TABLE task_checklist_items ADD COLUMN pre_completed INTEGER` gives each step somewhere to remember where it stood before
its card was completed, and the `UPDATE … SET pre_completed = done, done = 1` that follows runs that one completion over the steps
of every card already in *Completed* — so the rule "a finished card is ticked whole, and reopening it puts the plan back" is true of
work finished last month as well as work finished after the upgrade, rather than of new cards only. It reads the old `done` on the
right-hand side of its own assignment, so remembering and ticking are one pass, and it writes nothing for a card that is still open
(where a step's `pre_completed` stays NULL, which is what "nothing is outstanding on this step" means to the code). Verified on a
copy of a V14 database, not only on a fresh one: the migrated card reads 100% with its boxes stopped, and the first drag back
restores the half-worked plan the person had actually left behind.
**V16__three_phase_buckets.sql moves every board back onto the three phases** (Stage 75), and it is the one migration here
whose rule is *never destroy a card's filing*. An empty sub-category of V10's six is deleted — that is the only case in which a
lane can go without work going with it — while one that still holds cards keeps its name and stands on as an ordinary lane,
because `tasks.project_bucket_id` answers `ON DELETE SET NULL` and dropping the column would silently unfile everything in it.
A later duplicate of one of the three gives way to the earliest row, so no board offers *Engineering* twice; every project that
lacks one of the three is given it; and the three are numbered 0–2 **by name**, which means a project that already had
*Procurement* from V7 keeps both its column and its place at the head of the row. Everything else on that board is numbered
after them, in the order it was added — including a retired family that survived because it holds work. No card is refiled into
a phase by guess, and `type_default_buckets` and the backlog's own `buckets` table are untouched: the catalogue never held one of the six, and
the Other tasks columns belong to different work. Verified against a copy of the live V15 database before the real one was
migrated, and then on it: `Current version of schema "main": 15` → `Migrating ... "16 - three phase buckets"` → `now at
version v16`, with every card still standing in a named column.
**V17__bucket_colour.sql gives a bucket a colour of its own** (Stage 80), and it is two `ADD COLUMN colour_slot
INTEGER` statements — one on `project_buckets`, one on `type_default_buckets` — with nothing else. The value is a
**slot in the stylesheet's six-hue cycle, `0..5`, never a hex string**, so a posted colour can only ever be one of the
shades `app.css` already owns, needs no validation beyond a range, and cannot paint a column unreadable against either
theme. It **backfills nothing**: NULL means *wear the colour of my position*, which is exactly what every row does
today, so no board and no catalogue row changed shade when this shipped — and a backfill would have had to choose
between `position % 6` and the index a reader actually sees, which are not the same on any board that has ever lost a
column. It leaves the backlog's `buckets` table alone, because that board is cut by status and prints no coloured dot
to store one against, and it leaves the three phases a board opens with positional, so *Engineering* is not pinned to
a hue one manager can change and the next one cannot. Verified on a fresh throwaway and then on the real database:
`Current version of schema "main": 16` → `Migrating ... "17 - bucket colour"` → `now at version v17`.
**V18__task_archive.sql makes an archived card possible** (Stage 81), and it is **four columns on `tasks`, not an
archive table**: `archived BOOLEAN NOT NULL DEFAULT 0`, `archived_lane_id INTEGER`, `archived_lane TEXT` and
`archived_on TEXT`. The flag is what a read has to know; the other three remember where the card stood and the day it
left, for the panel's *"was in "Production""* line. `archived_lane_id` is a **plain integer with no foreign key** on
purpose: on the path that matters most — a bucket removed with work in it — the lane row is deleted in the same
operation, and a referencing column would be SET NULL by the very statement that archives the card. `archived_lane`
is a name copy so the line still reads after the lane is gone; restore goes by the id while that lane exists and by
the board's first column otherwise. **An archived card is the same row** — its `task_checklist_items`, its
`task_notes` and its lesson rows keep their foreign keys and their ids — which is what makes *restored as it left*
true rather than a copy. Every existing row gets `archived = 0`, so no board changes shape on upgrade; the reads that
hide an archived card are `TaskRepository.findByKind`, `ProjectBucketRepository.cardCounts`,
`AlertRepository.openTasks` and `LessonRepository.taskChoices`, and the backlog's `buckets` table is untouched, because
Archive is a project-board idea. No index is added: the archived set is small by construction, and a partial index
would be maintained for no measurable read. Verified on a fresh throwaway and on the live file:
`Current version of schema "main": 17` → `Migrating ... "18 - task archive"` → `now at version v18`.
**V19__project_status_in_progress.sql renames one word** (Stage 96):
`UPDATE projects SET status = 'In progress' WHERE status = 'Ongoing';`. Nothing more, because `projects.status` has been
free text with no CHECK constraint since V1 — the five words are enforced at the form, not the schema — so the change is
an `UPDATE`, not a table copy. It leaves `updated_at` exactly as it found it on purpose: the app renamed its own
vocabulary, it did not move a project, and stamping every row would make a job from last month look like today's news.
`tasks.status` is untouched (cards already used the surviving word), and so are `forecasts.invoice_status` and
`notifications.mail_status`, which only share the column name. A status the app cannot name — a hand-edited row, a
legacy `On track` — survives as written; V19 renames one word, it is not a cleaning pass. Verified on a copy of the live
database before it was verified on live: `Current version of schema "main": 18` →
`Migrating ... "19 - project status in progress"` → `now at version v19`, two rows renamed, every other row identical.
**V20__project_currency.sql adds one column** (Stage 109): `ALTER TABLE projects ADD COLUMN currency TEXT NOT NULL
DEFAULT 'MNT'`. Every project already on a board gets `MNT` from the default — the unit its figures were always
written in — so the migration is a label, not a conversion: no amount is divided by a rate, and revenue, invoiced and
`updated_at` are exactly as they were. There is deliberately no CHECK on the code: the three monies are the form's
rule (`Currency.of` in `ProjectService`), the way `projects.status` has been free text since V1, and a row carrying a
code the app cannot name reads as tugrik rather than breaking the board. The forecast ledger (`forecasts`, V8) is
untouched; its rows inherit the project's money when the sheet renders. Verified on a fresh throwaway and on the live
file: `Current version of schema "main": 19` → `Migrating ... "20 - project currency"` → `now at version v20`, all 14
projects `MNT`, and every other column identical.
**V21__project_roles.sql is the first migration since V1 to move rights** (Stage 110): two tables — `roles` (the
catalogue, with `is_default` marking the role a new account is filed under) and `role_permissions` (one row per chip,
`ON DELETE CASCADE` with its role) — plus `users.role_id`, which is nullable and `ON DELETE SET NULL`, so deleting a role
hands its holders back to the default rather than deleting people along with it. It seeds the picture's four roles and
their 13 + 13 + 9 + 1 = 36 chips, then files **every existing account** the way `Authorization` resolved it a moment
before: super admin → Owner; else the position's `permission_level` (admin → Administrator, manager → Project Manager,
anything else → Employee) — that level still wins over the account's own word, exactly as it did; else the `access` word;
else Employee. **Each account lands on the role that matches the rank it had** — which is what made the cross-check on the
live file meaningful (15 accounts, 0 with a null `role_id`, every filing in the old ladder's order). What a role *grants* is
the picture's own set, and there the reach of one rank does change: *Project Manager* holds the picture's nine chips, which
are the projects, boards, cards and money and none of the four people-facing ones, so an account filed there no longer opens
**Settings** or **Access control** or adds an account the way the old `Manager` rank could. That is the sheet the user asked
for, and the remedy is on the page: tick the right onto the role — everybody holding it changes at their next request — or
file the person under *Administrator*. The two administrator roles hold all thirteen, so those three accounts read the same
answer at every route as before; the eight *Employee* accounts had no write anywhere under the old `Viewer` rank either,
and the only thing they lost is a page they could only read — `/app/settings` used to print a `Viewer` their own account
card and now redirects a role with none of the four panel chips, by a link their drawer dropped in Stage 51.
`positions.permission_level` is left in place on purpose: positions became the job title beside a
name, the column stopped being read for authorization, and dropping it would throw away a value the operator can still see
and edit. Unknown chip keys are tolerated the way V1 tolerated an unknown status — `Permission.of` answers null, so a
hand-edited row that names nothing grants nothing. Verified on an empty throwaway and on the live file:
`Current version of schema "main": 20` → `Migrating ... "21 - project roles"` → `now at version v21`, and on live the
distribution after the run is Owner 1 / Administrator 2 / Project Manager 4 / Employee 8.
Take a backup before starting an alerting
build against a database you have not migrated yet, and check the board afterwards. Task titles are
free text and are not rewritten, so a title that quotes an old number (`SIT-01: …`) still says so —
the project link itself is by id and keeps working. Migrations are otherwise additive, so keep
every `V*.sql` that has ever shipped.

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
  password get the employee number, and the `must_change_password` flag records that — but since
  **Stage 99** nothing enforces it: the box that used to clear it is gone, a password is set from the
  Team sheet, and the flag is a note about how the password came to be.
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
  styles except presentation attributes), `frame-ancestors 'none'`, `nosniff`, HSTS in production,
  and **`Cache-Control: no-store` on every page the server draws** (Stage 69): `/`, `/login`, `/logout` and anything
  under `/app`. Those answers are only true for the instant they were rendered, and a page with no `Cache-Control` of its
  own is fair game for the browser's document cache — Back would otherwise hand the reader a sheet drawn before the last
  card they moved, with no request in existence for the app's own script to intercept. `/static` and `/files` are the same
  bytes for every reader, so they keep being cached and this costs nothing in load; the split is `isServerDrawnPage`.
- **Auth** — since **Stage 110** the whole of an account's rights are the permission chips on the one **role** it is
  filed under, and `Authorization.canDo` / `canAny` is what the route and the repositories that write ask. The thirteen
  chips are *Create projects*, *Edit project details*, *Delete projects*, *Manage buckets*, *Edit any task*, *Assign
  tasks to others*, *View teammates' tasks*, *View Forecasts tab*, *Edit forecast amounts*, *Manage employees*, *Manage
  project types*, *Manage email settings* and *Manage roles & permissions*; the catalogue is edited on **Access
  control**. `users.access` and `positions.permission_level` are still stored and read for display, neither is read to
  decide a right — the three-rank ladder they formed (the super-admin flag, then the position's level, then the account's
  word) is what `V21` walked once to file every existing account, so each colleague is on the role that matches the rank
  they had — and the rights themselves are the picture's sets, whose one narrowing is spelled out in the migration note
  above. Both are still *written* — `RoleRepository` keeps each account's access word in step with
  its role so the legacy shape still reads sensibly — and nothing that decides a right reads either one any more.
  `Authorization.canManage` survives as "any chip except *View teammates' tasks*", the one boolean a board renderer
  takes, and `canAdmin` as "*Manage employees* or *Manage roles & permissions*"; a route never
  guards on either — it asks for the chip its own control is about. Where the stage-by-stage prose below still says a
  **Manager** or an **Admin**, read it as "a role holding that chip", and where it says a **Viewer**, as "a role holding
  none of the chips that section is about" — for the four seeded roles both readings give the same answer, which is the
  reason filing the accounts by the old ladder was a no-op for rights.
  Management-only writes: create a project, set a project's manager,
  delete an assigned task, add or delete an account, assign a task from the Team page,
  add or remove a checklist step, and remove a note. (**Stage 72** gave *delete an assigned task* its control inside the
  card popup, at the right-hand end of the note box's line. The route has refused non-managers since Stage 17; the button
  renders on `TaskForm.canEditAll`, the popup's copy of that same check, so a non-manager is never shown it and a
  hand-built POST from one still deletes nothing.) Since **Stage 70** a step cannot be ticked at all while its own card
  sits in *Completed*: `TaskService.toggleChecklistItem` refuses before it asks who is calling, so the finished card's plan
  is settled for management and the assignee alike — and the way to change a step there is to move the card back out of
  *Completed*, which puts every step back where it stood. Stage 51 widened the management-only list to whole *sections*:
  **Settings** (its Team block), **Access control** and **Other tasks** are management's functions, so a `Viewer`
  loses the sidebar link to each and is bounced at the route — `navItemsFor` drops the three ids, `GET
  /app/access-control` redirects a non-manager, `settingsContent` prints a Viewer only their own account card (no
  password box since **Stage 99**), and the Other-tasks board a Viewer reaches is scoped to their own cards with no column tools
  (their own card still opens in the editor). **Stage 110** re-cut those sections on the chips and the shape of the rule
  survived: the four gated links are `NAV_PERMISSIONS` in `DashboardCommon` — *Other tasks* on *Edit any task* or *Manage
  buckets*, *Access control* on *Manage employees* or *Manage roles & permissions*, *Settings* on `Authorization.SETTINGS`
  — the three panels' chips: employees, project types, email — and **Forecasts** joins them on *View Forecasts tab*, which is new:
  the link used to print for everybody and the route did the bouncing, so an account with no money chip now simply is not
  offered a link it would be turned away from. The ids stay in `navItems` whole for the same reason as before — the
  notification bell resolves a stored section back to a path through that list. `/app/access-control` redirects a role
  holding neither people chip and `/app/settings` one holding none of the four panels' chips; within Access control the two
  halves are separate, so a role with *Manage employees* but not *Manage roles* gets the People sheet with its password and
  delete columns and the **Roles & permissions** table read-only — the role printed as a tinted chip instead of a picker, and
  no **+ New role** and no **Edit** anywhere on it. Within Other tasks the whole board is management's: writing a
  card, naming a column, renaming and deleting one and the `⋮` are all management's, and `POST /app/other-buckets` and
  the save endpoint's column branch check the role again before the (still role-permissive) service is reached. A lesson splits the same way (Stage 44), but by authorship rather than by
  field: every account may log one and may tick, edit or delete **the rows logged under its own employee
  number**, while somebody else's lesson is management's alone. `LessonService.mayWrite` decides that once and is
  asked again by all three lesson routes, each of which reads the row back instead of trusting the id the page was
  drawn with — so a lesson that has since been deleted, or that has changed hands, cannot be written by the stale row
  still on the screen. A row with no author (a V1 carry-over) is management's to tidy and never a peer's to claim.
  Editing a task is
  shared since Stage 23: management may change every field, and the person it is assigned to may change
  its start date, due date and priority — `TaskService.updateAssigned` rebuilds every protected field
  from the stored row, so the split holds even when the popup's greyed inputs are left out of a POST.
  **Stage 86** separated *opening* a card from *writing* it: `TaskService.openableBy` (the renamed
  `editableBy`) hands any reader a project card — a backlog card stays management's and its holder's — while
  `updateAssigned`, `toggleChecklistItem` and `addNote` keep the rules above and refuse the stranger the popup now
  admits.
  Stage 48 added two more protected answers to that rebuild, and both are read from the row rather than the form:
  which **board** the card lives on (the *Where it lives* picker is management's, like the title, so a hand-built
  POST from the assignee naming the other board moves nothing) and which **lane** it is filed in. Since **Stage 49**
  the backlog's **Column** picker is protected on the same terms, which retires Stage 42's one exception: the owner's
  half of the board was never a *field* they owned, it was the drag this board cannot offer, and a drag is now
  management's on both boards. The field stays printed for its owner, greyed, with the whole board listed under the
  line *"Management moves a card between the columns."* — a greyed picker that shows one column would read as a
  board that lost its columns.
  Creating one is management's plus Stage 25's one exception: `TaskService.mayAddTasks` also lets the
  account a project names in `manager_emp_no` write a card **for that project**, and `TaskService.create`
  takes the project from the page rather than from the form, so the right cannot be aimed at another one.
  Naming a lane is that same right under another name — `TaskService.mayEditLanes` is `mayAddTasks`, so
  `POST /app/projects/{id}/buckets` is open to management and to the project's own manager and to nobody else,
  and `addLane` refuses a blank name, one longer than 40 characters, and one this project already has
  (trimmed and case-compared, so `" tendering "` cannot make a second *Tendering* column). **Stage 73** changed
  which form asks the question, not who may answer it, and **Stage 79** moved that form onto the board: `POST /app/projects/{id}/buckets/save` is
  the bucket popup's add half — the whole set, every current column as a hidden twin beside the one word typed —
  gated by the same `mayEditLanes`, and its `TaskService.saveLanes` refuses a `Viewer`'s or an unrelated
  reader's hand-built POST by redirecting to `/app/projects` with nothing written. The **opening columns** are not asked
  for anywhere: **since Stage 100** `ProjectService.create` cuts the board from the category's Settings list in the same
  breath as the project row, so no tick-list could leave one out and no create path writes a name the list does not hold.
  What Stage 75 changed is what happens to a *fourth* word that repeats an opening one: it is an ordinary name — the
  Settings catalogue refuses only a repeat of its own row (`BucketDefaultsService.nameProblem` reads one set of name
  rules, with no phase branch since Stage 100), and a project's own picker takes it as the lane it names, because that
  board's columns are its own business. `LaneError.STANDARD`
  is gone with that split, so `removeLane` no longer refuses one of the three before the last-lane count: what still refuses
  is the only column standing (`LaneError.LAST_LANE`). **Stage 81 deleted `LaneError.HAS_CARDS`**, so a bucket holding
  cards comes off too and takes its work with it: `removeLane` and `saveLanes` flag every card in an unticked or removed
  column archived, with that column's name and id remembered, before the lane row goes. The **Unsorted** column is still
  printed after the board's own lanes only while a card with no lane exists — a legacy row, never one a removal produced. Since Stage 35 the
  *Add lane* box and the **×** on each column head stay gone in the shape Stage 35 removed them — what Stage 74 put in their
  place, and Stage 75 left on every board rather than only an empty one, is a dashed **+ Add bucket** box at the end of the
  run of columns and a `⋯` on each head, both opening the page's one popup rather than being a field and a button on the
  board; since **Stage 79** the popup's add half is the one place the whole set is written, and the panel that used to
  sit above the board is gone. What **Stage 77** split is the
  *reach* of a name rather than the right to write one: `saveLanes` and `BucketDefaultsService.chosenFor` both take an
  `alsoForType` flag read from the form's `also_type` field, absent means *this project only*, and the catalogue is
  touched **only** when that flag is on *and* `canManage` is true. A project's own manager therefore has the whole of
  their board to name and nothing shared — which is a widening of `mayEditLanes`, not a narrowing of it, since the build
  before this stage refused them a new column outright. A hand-built `also_type` from a non-management caller comes back
  `LaneError.CATALOGUE_DENIED` with its sentence, having written neither board nor catalogue.
  A lane's `+ Add task` line (Stage 34) is drawn on `mayAddTasks` as well, and the `into=<lane
  id>` it posts is matched against this page's own lanes before the blank popup is built — a foreign id, a
  removed one or a word opens the ordinary form instead of pre-filing the card — and `TaskService.create` still
  insists the lane it finally writes belongs to the project the card is being made on. **Removing** one
  is the same right on the same day (`POST /app/projects/{id}/buckets/delete`, Stage 31), and the lane it names
  is looked up rather than trusted: an id that is not on this project's board — a foreign one, a gone one, none
  at all — comes back `no-lane` with nothing deleted, and a project's last lane refuses to go (`last-lane`)
  because `taskBoardView` still branches on the lane list — with nothing on it a page would fall back to the five
  status columns, the shape Stage 75 took off this board and left on Assigned tasks and **Other tasks**. Since the three
  are no longer a floor, this is the rule that keeps a board a board. Removing a lane that holds cards is **not**
  leaving them unfiled: since **Stage 81** every card in the column is flagged archived with the column's id and name
  remembered, then the lane row goes, so the cards wait in the project's Archive instead of surfacing under
  **Unsorted** — `TaskService.removeLane` writes those rows *before* the delete for exactly that reason, and its
  `LaneRemoval` says how many went. The old `ON DELETE SET NULL` un-filing, and the stale-page count guard that
  refused the press, are gone: the route no longer re-reads the count, because there is nothing left to protect the
  reader from. `POST /app/projects/{id}/buckets/rename` (Stage 74) is where that popup's rename button goes,
  and the dashed add box at the end of the run posts to `save` with `src=board` rather than to the older
  `POST /app/projects/{id}/buckets` — which stays a management-only endpoint nothing on a page sends. The service rule
  behind the route is the same one: `renameLane` is the same lookup with `addLane`'s name rules on top, and since
  **Stage 75** that is all of them — a lane the board opens with is retitled exactly like a lane somebody added,
  because the popup files a card by *this* project's lanes rather than by a catalogue word. A rename *into* one of
  the three phase names needs no special case either: it comes back `LaneError.DUPLICATE` while the board still
  carries that column, and is a plain rename onto a phase name once the manager has taken that one off.
  A rename is by **id** and touches no other row — not the category's catalogue, and not one card.
  Since Stage 74 the codes are read again: `?bucketsrc=board` says which half of the page asked, and the sentence
  lands either over the board or under the popup's name field — never both, and never in the picker as well.
  Filing a card on a lane is **not** the mover `updateStatus` already uses: since **Stage 49** `TaskService.moveToLane`
  asks `mayAddTasks` — management, or the account this card's project names in `manager_emp_no` — and never the person
  the card is assigned to. That one rule is the whole difference between the two readers on a project page, on the
  backlog and in either popup: the mover may drop a card between lanes, the owner may not, and every other field of
  the card splits the same way it always did. The lane still has to belong to the card's own project, so posting a
  foreign lane id moves nothing
  (`TaskField.LANE`, and the card stays where it was). Since Stage 48 the same call refuses `bucket=0`: no lane at
  all is not a place a card can be dragged to. `kanban.js` marks the **Unsorted** column `data-unfiled` and does not
  treat it as a drop target, so the board never highlights a move the route is going to bounce — cards leave that
  column, none arrive. A card **written** onto a project has to name its lane as it is written:
  `TaskService.resolve` asks for one on every create, on a card being sent onto a project from the backlog, and on a
  card being handed to a different project (which leaves its old lane behind rather than filing it on a board it is
  no longer on). The field travels by **name** — `ProjectBucketRepository.findByName` against the project the form
  just resolved — because a popup that has not loaded a project's lane ids can still print this board's own
  headings, and the word it prints is the answer it posts. That lookup rather than trust is what makes Stage 75's
  editable board safe: a column retitled or taken off while the popup sat open simply stops being an answer, exactly
  as a lane id borrowed from another board never was one; a name the project does not carry is refused. And a card
  on no board is refused with it: the *— No project —* option is out of the Project picker and an empty `projectName`
  comes back as *"Choose which project this card is on."* The one blank a popup still prints, *— No project yet —*,
  is what a row that arrived before the rule says about itself, not an answer anybody can choose. Deleting a card has
  one control since **Stage 72**, at the right-hand end of the note line inside the card's popup, and it prints only on
  the same `canEditAll` flag the route checks — so a non-manager is never shown it, and the
  gate still applies to any request that reaches it anyway. A project's bucket controls are the same shape — drawn
  only where `mayEditLanes` is true, since **Stage 73** for the whole-set save and since **Stage 79** for the one popup that
  holds it, and `POST /app/projects/{id}/buckets/save` sends a hand-built request from anybody else to `/app/projects`
  having written nothing. Management-only *reads*: the whole task board — anybody
  else's task cards, a `Viewer` gets only the ones assigned to them, on every view, with one deliberate
  exception (the project page also shows the project's cards to the account that manages it, or a card they
  just assigned to somebody else would disappear from the page they made it on) — and the
  per-person task drill-down on the Team page. Hiding the control is not the
  check — the route answers a hand-built `POST` the same way it answers a click.

Known gaps, deliberately left out of this launch scope:

- `security/Csrf.kt` is implemented and tested but **not wired into any route**. POSTs currently
  rely on the `SameSite=Lax` session cookie. Treat this as the next hardening task before the app
  faces the open internet.
- The rate limiter and session cache are per-process; running more than one instance behind a
  load balancer needs shared state.
- SQLite is a single-writer store. Fine for a few dozen concurrent users on one host.

## Due-date alerts

A sweep runs in-process every `PTS_ALERT_INTERVAL_MINUTES` (5 by default), starting
`PTS_ALERT_STARTUP_DELAY_SECONDS` (30) after boot, and emails the people responsible for work that
is not finished while its due date is close. The interval is minutes rather than a day so the inbox
side of the sweep (see "Notifications") is current; the once-a-day rule below is what keeps that from
multiplying the mail.

What counts as "not finished, and coming":

| Item | Alerted when | Who receives it |
| --- | --- | --- |
| Project | status is not `Completed` **and** progress < 100 **and** the due date is within `PTS_ALERT_LEAD_DAYS` (7) — or already past | the project's assigned manager plus every assignee |
| Task | status is not `Completed` and the due date is within the same window | the task's assignee plus the manager of its project |

Here `progress` is the figure stored on the project row, not the average of its cards' checklists that the
pages print — mail keeps its own rule on purpose, so a project can read 100% on the board and still be
alerted, or be skipped because its stored figure says 100. The pages were moved to the measured number; the
alert sweep and the bell were not.

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

## Notifications

The bell in the top-right of every page is the in-app half of the same idea. Three events raise
something, and each one is also mailed through the SMTP settings above, so a person either sees it in
their inbox or in the dropdown — usually both:

| Event | Raised by | Who hears it |
| --- | --- | --- |
| A task is assigned to you | a manager creating the card, or a save from the editor that **moves** it to you (the assignee's own save cannot; that field is theirs to read) | the assignee |
| A project becomes yours | a project created with you as manager, or a header-strip save (`POST /app/projects/{id}`, Stage 60) that moves the **Project manager** field to you — it notifies only when the manager actually changes hands. `POST /app/projects/{id}/manager` still exists and is management-only, but no page posts to it now | the new manager |
| A due-date reminder goes out | the sweep, on the interval above | the same recipients the email goes to |

Two rules hold everywhere, and both are the reason the feature can run on a five-minute timer without
shouting:

- **nobody is notified about their own action.** Assigning yourself a task writes no row — you just
  pressed the button, and the board you posted to already shows it.
- **at most one row per person, per item, per day.** `notifications` has
  `UNIQUE (recipient_emp_no, kind, item_kind, item_id, dedupe_day)`, the inbox version of the alert
  ledger's key. Six edits to one card this afternoon are one item in one dropdown, and the row is
  claimed *before* the mail is sent, so a retry cannot mail twice either.

Renaming a card, dragging it between columns, and changing a due date notify nobody. Losing an
assignment notifies the person who lost it not at all — only the new holder hears.

The notice depends on **who holds the card, never on its status**. Work is often under way — or already finished — by the time somebody writes it down, so a card entered as *In progress* or *Completed* tells its assignee exactly like one saved as *Not started*, bell row and mail attempt alike, and moving a card that is already under way still tells the person it moved to. Only the status changes; the notice does not.

`read_at = ''` means unread; that single column is the badge count, the dot on each row, and what
"Mark all read" flips. Opening a row is `GET /app/notifications/{id}/open`, which marks it read and
then redirects to the target it stored — and only to a target starting with `/app/`, so a hand-edited
row cannot turn the dropdown into a way out of the app. The ids are matched against your own session,
so an id from someone else's inbox marks nothing and leads nowhere.

Due-date rows are the one kind that never mails from here (the sweep already did that); they carry
`mail_status = 'inbox only'` and exist so a reminder is not lost on somebody with no address on file.
The `mail_status` column is also the debugging answer to "did the bell send an email?": `sent`,
`failed`, `logged` (dry run), or `no-address`, readable straight out of SQLite.

`PTS_NOTIFICATION_KEEP_DAYS` (30) trims the table after each sweep — an inbox is a courtesy, the
ledger is the audit trail — and `0` turns trimming off. Nothing to configure per user: the bell
renders on every page for every signed-in account, and needs no admin rights.

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
2. `./gradlew test installDist` is green (707 tests in 48 classes), on schema v21.
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
7. First sign-in as the admin: the app lands on `/app/projects` (there is no forced password change
   since **Stage 99**), and Settings carries the Team sheet and your account card. Sign out and back in
   once to confirm sessions persist. Then sign in as a
   `Viewer` and confirm `/app/projects` has no `+ New project`, no create popup and, on a project page,
   no lane control of any kind — no **+ Add bucket** box, no `⋯` on any column head and no bucket popup, and since
   **Stage 79** there is no *Project buckets* panel on any page — just the board — and no manager
   control of any kind — while `/app/other-tasks` and `/app/todo` still work
   normally. On that same
   project page there should be no roll-up of the board at all — no **Cards**/**Open**/**Done**/**Overdue**/
   **Steps**/**End date** tiles and no repeated line of column dots; the only counts on that page are the ones
   in the board's own column heads, and since **Stage 75** those columns are the project's **buckets** — a new project
   opens with **Engineering**, **Procurement**, **Production** and nothing else, whatever its category, and its manager
   can rename or take off any of them. Status is neither a column nor a word on a card here: management set it in the
   editor the card opens, and the person it belongs to gets a single **✓ Done / ↺ Reopen** button on the card instead
   (Stage 75 — on a bucket board two arrows would be offering a walk between columns that do not exist; since **Stage
   103** the arrows the *status* boards did print are gone too, so this Done/Reopen button is the only move button
   anywhere in the app). The five
   **status** columns belong to `/app/assigned-tasks` and to **Other tasks**. Since
   **Stage 56** the `Viewer` sees the **whole** project board here — every card filed under the project, each with its
   holder's avatar. Since **Stage 86** every one of those cards carries a click target: a colleague's opens a
   **read-only** popup (fields greyed, plan stopped, log to read, no Save and no note box) while still printing no
   `draggable` and no move button, and only their own cards open in the full editor. The project page is the one place a `Viewer`'s card *list*
   widens past their own; the Calendar, `/app/assigned-tasks` and the Team drill-down stay personal. Then sign in as
   management and click one of their cards: the editor should open over the board without the page
   reloading, holding every field and the plan under it, and there should be no menu, no `Edit` button and
   no status dropdown anywhere on the board — and while no card on that board shows a plan or a box until it is
   clicked, this manager can tick, add and remove steps on a colleague's card from inside its popup, because
   management may work any card's plan. Then check **Stage 70** on a card with a plan of three, two of them ticked: drag it
   into **Completed** — or set **Status** to it in that popup, which is the same write — and every step arrives ticked, the
   heading reads `3 of 3 done · 100%`, and there is nothing left to click: the boxes show ticked and struck, and the only way
   to change them is to move the card back, which puts them at `TT·` again — the two that were done and the one that was not,
   exactly as they stood. Since **Stage 101**, a step typed onto a finished card lands **unticked** and stays unticked
   after the reopen: three finished steps plus a new one reads `3 of 4 done`. All four rows are locked while Completed;
   a tick POSTed at that card is refused by the service. Reopen it to work the new step. The finish still ticks the plan
   present at that moment, while later additions can lower its progress and the project's average. Then open `/app/assigned-tasks` **as that manager**: since **Stage 53** the
   board prints only the cards with *their own* employee number on them — the colleague's cards are gone, exactly as
   on a `Viewer`'s copy of the page — and the line *"Only the cards assigned to you are on this board."* sits above it.
   The manager can still press `+ New task` and open a full editor there; it is the *list* that went personal, not the
   rights. Since **Stage 111** that editor is also the one place a card picks its project **inside the popup**: it opens
   on *— Choose a project —* with **Sub-category** greyed and saying *— Choose a project first —*, and choosing a project
   puts **that project's own columns** into the Sub-category picker while the reader is still typing — no reload, so the
   title they wrote is where they left it. Save it with the project left unchosen and it comes back at **200** with
   *"Choose which project this card is on."* under that picker — and only that one, because the service reads a lane
   against a project and says nothing about the column until a project is named; choose the project and save with the
   column left out, and the same round trip answers at **Sub-category** with the chosen project's own columns listed.
   Then create a project and read what its popup asks. It still holds its nine fields and no lane input —
   no **Task lane 1**, no placeholder naming a default, no sentence about leaving one blank — and since **Stage 92**
   it asks nothing about buckets beyond saying where they come from: no tick-list, no added-bucket box, no
   category-wide tick. What it says instead is *"A new board opens with the buckets this category lists in Settings —
   nothing else"* and *"Change them under Project bucket settings, or from the project page after it is created."* The
   number box opens with the chosen category's letter and dash (`M-`), and switching the category moves that lead while
   the number after it stays. Since **Stage 108** the **Revenue** box is a text box, not a spinner, with `₮ 0` as its
   grey placeholder and a numeric keypad on a phone: type `2500000`, or paste `₮ 2,500,000`, and either is read as the
   same number. Since **Stage 109** a currency picker sits underneath it, opening on **Tugrik (₮)** — pick **US dollar**
   and the placeholder turns `$ 0`; pick **Yuan** and it turns `¥ 0`. Leave it on Tugrik. If that category lists no bucket yet, the create is refused with the sentence under the
   Buckets line — add one there first. Press **Create project** and the new row's Revenue column reads `₮ 2,500,000`
   on the board (a project left blank keeps its `—`), and its page's header box opens on the same figure; put
   `lots` in that box, save, and the refusal sits under the field with your word still in it and nothing stored.
   Make a second project with **US dollar** picked and `$ 2,500,000` pasted in: the board prints `$ 2,500,000` on its
   row while the first still reads `₮ 2,500,000`, and `/app/forecasts` puts both signs on one panel with no `, MNT`
   left in its headings. Open the dollar project's header strip: there is no currency picker there, and saving a new
   amount — `$ 2,750,000` will do — keeps the dollar. **Stage 109 is a CSS and JavaScript change on this popup too, so
   press Ctrl+F5 once** or the picker arrives unstyled and the placeholder does not follow it.
   The page should land on one **bucket** column per
   row its category lists, in that order (how many differs by category), each head a coloured dot, the name and a `0`,
   with a dashed **+ Add bucket** box trailing the run and a `⋯` on every head (**Stage 75**: the box and the menus are
   on every board now, not only an empty one, and every column carries them exactly as an extra lane does).
   Then check **Stage 80** on that board. Open a column's `⋯`: under its name box is a row of six dots captioned
   *Colour*, and the one already checked is the shade that dot is wearing on the column — leave it alone, rename the
   column, and the colour is untouched; pick a different one and the head repaints while the name stays. Type a name
   into the dashed box and the add half asks for that name and nothing else — since **Stage 83** there is no swatch
   row on it, because the app paints the new column's dot at its place in the run: a **QAQC** added after four columns
   wears the fifth shade. Recolour it afterwards from its own `⋯` if you want another. Now give a card the
   *Completed* status and look at the column it stands in: its live cards, then a `▸ Completed 1` line, then nothing —
   press that line and the card appears under it, the arrow turns, and **the page did not reload**; the URL now carries
   `?showdone=<that column's id>`, so opening a second column carries the first along and the address is a link to
   *this board with this work showing*. The head's own count never changed, because it counts the folded card too, and
   a column with nothing finished in it prints no line to press. Drag a card out of an opened *Completed* block and it
   lands **above** the fold line, where it is visible, rather than under it. Then look at **Assigned tasks** and
   **Other tasks**: neither prints a fold line anywhere, because their columns *are* statuses and hiding *Completed*
   would hide the point of the page.
   Then check **Stages 81, 84 and 106** on the same board (**Ctrl+F5** — the header, the stylesheet and `app.js`
   changed). Add a column **QAQC** and file one card in it, then open that column's `⋯`: **Remove bucket** is now
   pressable, and the caption says *"Removing this column takes it off the board, and its 1 card leaves the board with
   it."* Press it: the column is gone, the card is not under **Unsorted**, and the sentence over the board reads
   *"That bucket is off the board, and its 1 card left the board with it."* Now look at the header: **Stage 106** took
   the **🗄** and the **📋** out — what is left of the top line is the breadcrumb, **☆ Pin for me** and **Delete
   project** — and the archived card is on no page at all; it is still a row in the database with the column's name
   remembered, and the restore and delete routes answer only a hand-built POST. Open a live card: its foot offers
   management's **Delete task** (Stage 84), not an **Archive task** button. A **backlog** card offers the same
   **Delete task** — that board has no archive at all — while a `Viewer` sees no **Delete task** anywhere.
   Then add a card from the page head's `+ New task`, leave **Sub-category** on its blank answer, and save:
   nothing is written, and
   *"Choose which part of this project the card is on."* prints at that field with the title still typed
   (Stage 48) — a card onto a project is filed in a bucket as it is written, and since Stage 75 that bucket **is** the
   column it stands in. Choose **Procurement** and the card appears under that heading; its **Status** is a field of the
   popup, not a place on the board. Pick a project's card and move its **Where it lives** picker to *Other tasks*: the
   project half of the popup shuts, the **Column** picker comes up, and the save answers on **Other tasks**
   with the card under that column — its steps, its log, its status and its person intact. Move it back the same
   way and it has to be filed in a bucket again.
   Check the drag files a card into another **bucket**: as management, or as the account this project names in
   **Manager**, drag a card off **Engineering** onto **Production** and drop it — the card is in that column the instant
   you let go, before the write has answered, and the page does **not** reload (`kanban.js` moves the node itself and
   then posts `/app/tasks/bucket`, since **Stage 105** in that order and without following the route's redirect; Stage
   52's reload-and-reappear came back off this board with the bucket columns). Reload by hand and it is still there, still with
   the same status. That is `TaskService.moveToLane` (Stage 49), and it is **not** the person the card belongs to: their
   card carries no `draggable` on this page even when it is their own, and a hand-built POST from them moves nothing. What
   their own card does carry is one **✓ Done / ↺ Reopen** button, which posts `/app/tasks/status` and does reload — finishing
   a card is a status, not a column, and since Stage 75 it is the only move the board lets an owner make. A card with no
   bucket at all — one written before the rule — stands in an **Unsorted** column printed after this board's own, and only
   while such a card exists: `kanban.js` marks it `data-unfiled` and never offers it as a drop target, so cards leave it and
   none arrive, and it carries no `+ Add task` line either.
   And check what is
   **not** there (Stages 35 and 75): nothing under the columns names a lane, no column head carries a **×**,
   and no **status** column stands beside the buckets — the board is buckets only, whether
   or not the project's cards have been filed. That holds for management as much as for a `Viewer`; what a `Viewer` loses is
   the controls, not the columns. A card's
   **height** is its own content
   (Stage 38), and since Stage 41 that content is its title, its meta line and, for an owner, its one button: a card with twenty
   steps on it is exactly as short as a card with none, because a plan is drawn only inside the popup. Then open **Other tasks**
   (`/app/other-tasks`, Stage 47): it is a vertical run of **bucket groups** (Stage 57), one group per bucket. Each
   group prints the bucket's name as a heading on top — the way a project page prints its project name — with how many
   cards it holds and, for management only, a `⋮` menu; directly under that heading the bucket's cards are cut by the
   **same five status columns** the Assigned tasks page prints — the shape Stage 75 took off the project page and left
   here — each headed by its status dot and word. So a bucket is read by
   how far its work has got, not by a nameless column, and this is the only place the backlog is printed — the
   **Assigned tasks** page shows none of it, and the flat list `/app/other-tasks` used to print is gone. Every bucket
   that holds work prints; the empty ones are hidden until you name one in the **Bucket** dropdown above the groups —
   management's control, and since **Stage 97** the board's only one (the head's **Show all buckets** button is gone, so
   nothing reveals every empty column at once).
   Click a bucket's `⋮` as management: one popup opens over the board, holding that bucket's name in a rename field
   and a sentence about how many cards are in it. Rename it and the heading changes in place, the cards under it do
   not move, and the page does not reload. Press **Delete** on a bucket that holds cards while the popup's *move its
   cards to* list is untouched: nothing is deleted, and *"Choose which bucket takes its cards."* prints under that
   list with the destination still chosen. Pick a real bucket and the cards land there, the count follows them, and
   the deleted heading is gone. A bucket that holds nothing deletes without any of that. The last bucket standing
   with work in it is not offered for deletion at all.
   Sign in as the `Viewer` and check the board is theirs alone (Stage 51): they are shown only the bucket their own
   card stands in — with its five status columns — and none of the others, and none of the management tools: no
   `+ Add bucket` button in the head, no `⋮` on any heading, no `#bucketModal`, no per-bucket `+ Add task` line, no **Show all
   buckets** toggle. Their own card still opens in the editor. On that popup their **Column** picker is greyed and
   still lists the board (Stage 49), under *"Management moves a card between the columns."*
   Now change a card's status the way the board means it to be changed: as management, click a backlog card. The
   editor popup opens over the board without the page reloading, filled from the row — and since a backlog card
   belongs to a bucket rather than to a person, **Save** with *Assign to* left blank is accepted: choose a
   **Status**, save, and the card walks into the status column you picked **under its own bucket heading** — the
   bucket does not change (that is the **Column** picker's answer), only the column it stands in does, and the
   buckets you were looking at are not hidden again
   (a `POST /app/assigned-tasks/save` for a card of that kind with no assignee is the same round trip without a
   browser). An assigned card answered the same way must be refused with *"Assign the task to someone on the
   team."*, and the person on it must still be there afterwards.
   Then write a card the way Stage 51 means one to be written — as **management**: press a bucket's `+ Add task` line.
   The card popup comes up over the board with that bucket already chosen, its hint naming it (*"A new card lands under
   <bucket>."*), and no project picker anywhere in it. The title, the person, the status and the dates are all
   management's to fill on that create (`createBacklog` pins nothing). A hand-built `POST /app/assigned-tasks/save`
   from a **non-manager** carrying that board's `column` is not saved at all — it is bounced back to their scoped
   board, because the shared backlog and its create are management's (Stage 51); the way management hands a card to a
   person and pins it there is the **Team** sheet's Assign panel. Post one with `column` missing, or with an id that is
   not a bucket of this board, and nothing is written — the popup comes back with *"Choose which column this card
   sits in."* under the picker and the title still typed. Moving a card between buckets is that same picker as
   management: choose another bucket and **Save**, and the card is under the new heading with both counts moved, on
   the page they were looking at rather than the narrow one. Sign in as the person a backlog card belongs to and open
   it: **Column** is greyed (management's, Stage 49) and so is every other field but their own dates and priority —
   and their card carries no move buttons at all, because **Stage 103** retired the `←` / `→` pair from both status
   boards. What it does carry is **draggable** (Stage 58): drag it into another status column of the same bucket and it
   reloads filed under the new
   status, its bucket heading unchanged — that drag is now the on-card path to `/app/tasks/status`, the write the arrows
   used to post. A colleague's
   card on a `Viewer`'s board carries no `draggable` at all, so a drag there never starts. Management, on the wide
   board, drags any card the same way.
   The two lane routes are still open to a hand-built management `POST`,
   so if you want to confirm that: `POST /app/projects/<id>/buckets` with `name=Workshop` from a management
   session should add a lane to that project's bucket list — and a **new column** to its board, since Stage 75 —
   and `POST /app/projects/<id>/buckets/delete` with that
   lane's `bucket` id should take it off again — and since **Stage 81**, if that lane holds cards, they go to the
   project's Archive with the lane's name remembered (the redirect says how many). A hand-built
   `POST /app/projects/<id>/buckets/rename` with `lane` and `name` renames one
   column by **id** — one of the three included, since Stage 75; and the board column changes with it, because it
   *is* that column; naming a lane that belongs to another project renames nothing.
   **Stage 74** is what a brand-new project looks like — **Stage 75** is what it looks like *after* that — and
   **Stage 104** is what the popup asks before either: its Buckets line prints the chosen category's Settings rows with
   nothing to tick on them, and a box beneath them; type a name and press *+ Add bucket* (Enter in the box is that same
   press, not a submit) and the word becomes a chip carrying its own value, while a name the category already lists or
   one the board has twice is refused in red under the box, and Create still answers 200 with your chips waiting in it.
   So create one with no cards and read the board: every column the category lists is there
   first, in that order — since **Stage 100** exactly the rows **Project bucket settings** keeps for it — then the column
   you named, standing **last**; that page has never heard of your word, so the next project of the category does not get
   it, and a category that lists nothing is refused only when the popup names nothing either. Each
   head a coloured dot, the name, a `0` and a `⋯`, each foot a `+ Add task` line, and a
   dashed **+ Add bucket** box trailing the run at a column's width. Press that box: the popup comes up over the board
   without the page moving. Name a bucket the board already has and the write lands with nothing added — since **Stage 79**
   that popup is the page's only bucket form, and `saveLanes` folds the posted set and the typed name into one
   case-insensitive set, so the duplicate names a column that is already standing: the board keeps its columns, no second
   one appears, and the sentence reads *"Saved: N buckets on this project."* What is refused **at the name field**, with the
   popup still up and your own word in it, is a name the service will not accept at all — blank, longer than
   `PROJECT_BUCKET_MAX`, or carrying an illegal character — and since Stage 79 the sentence can only print in one place,
   because the *Project buckets* panel that used to sit above the board is gone. Change the word to something new and the
   write should land: the popup shuts, the new
   column is there with its dot on the six-colour cycle — the cycle is six shades long and is a *colouring* rule, not a
   list of lanes, so a fourth column simply wraps to the first shade **unless somebody chose its shade**: since
   **Stage 83** the app picks the shade at the column's place in the run and the popup asks for a name only, with no
   swatch row on it — and the sentence sits over the board rather than
   in the picker. Then press the `⋯` on **Engineering**, one of the three the board was born with: since **Stage 75** it
   offers the same rename field and the same **Remove bucket** half as the extra column's does, and both work, and since
   **Stage 80** it offers six swatches, pre-checked at the colour that head is wearing — pick another and press
   **Save** without touching the name, and the column repaints with its heading and its cards where they were. Rename it
   to **Tendering** and the head changes, the cards under it stay filed, and a card created on the board offers
   **Tendering** in its **Sub-category** list rather than the old word (an *edit* no longer asks — Stage 87). Remove
   a column that holds nothing and it
   leaves the run; try to remove the last one standing and the answer is *"A board keeps at least one bucket, so this
   one cannot go."* Then file one card on the project and look again: **the board is still its buckets** — Stage 52's five
   status columns never come back, and every bucket control on it stays — while a create popup still asks the **Sub-category**.
   **Stage 73's** whole-set write is still the route behind that add form, and it is what a hand-built `POST` has to be
   checked against: the popup's form carries a hidden `bucket` twin for **every** lane the board holds now plus the typed
   `new_bucket`, so pressing it saves the board's whole set rather than appending one name — and since **Stage 79** it is
   the only `/buckets/save` on a project page, because the panel's own **Save buckets** form went with the panel. Nothing
   but the board sits above it now; the columns, their `⋯` and the dashed box are the bucket controls. The same
   word should then appear on **Settings › Project bucket settings** for that category **only if the tick under
   the name box was pressed** (Stage 77): leave it clear, which is how it
   comes up, and the column
   belongs to this project alone — check Settings, and the word is not there. Ticked, every project created after this one
   opens with the column as well, because since **Stage 100** a new board is that list whole. Neither the tick nor a create
   reshapes a board that already exists; the one write that does is an add on Settings itself (**Stage 98**). File a card
   in the new bucket, come back and press its `⋯`: the popup counts it,
   and, since **Stage 81**, **Remove bucket** is pressable — the caption says the card leaves the board with its
   column, and since **Stage 106** it promises nothing beyond that — pressing it does exactly that, leaving nothing
   under **Unsorted**. Now strip every
   `bucket` twin off that form and post it by
   hand: nothing changes, and the sentence reads *"A board keeps at least one bucket — tick one to leave standing."*
   That wording still says *tick* because it was written for a tick list, and since **Stage 92** no page prints one —
   on a board the twins mean an empty set, a
   hand-built path rather than a click. The pressable answer is the `⋯` popup: a column with work says where its work
   will go, and a board's last column says so in its own sentence. Since **Stage 75** those are
   board-shape refusals, and no column is a floor a board refuses to lose — a manager who removes **Engineering** and
   changes their mind can name a column that word again on the same board, because a board may be given any name,
   whether or not its category's Settings list carries it. What **Stage 77** decided is who may make a typed name part
   of the category (**Ctrl+F5**
   before checking it —
   the tick and its sentence are drawn in Kotlin and its spacing is new `app.css`, both of which ship inside the jar):
   signed in as management, type
   one with the box clear and it lands on this board and nowhere else; tick the box and the same save says the category
   gained it. Signed in as a project's own manager who is not management, that box is not on the page at all — post it
   by hand anyway and the answer is *"Only management adds a
   new bucket to the <category> catalogue."* at the name field, the popup comes back with the word and the pressed tick
   both intact, and nothing is written; the same manager, same box, name left to themselves, gets the column on their own
   board — which is what a manager is for.
   Then read **the cards themselves** (**Stage 78**, with **Ctrl+F5** — the card's lines are drawn in Kotlin and the dot
   that colours the stage word is new `app.css`, and both ship inside the jar). On a project's lane board a card is three
   lines: its title, then priority · due · its own percentage, then a last line carrying a **coloured dot and the stage
   word** at the left and its holder's avatar at the right — and **no `T-00001` chip anywhere on that page**, because the
   card is already sitting on the page that names its project. The card wears that stage's colour too: *In progress* amber,
   *On hold* orange, *Blocked* red, *Completed* green, and *Not started* a bare grey edge, so the stage is readable from
   across the room before any word is. Move a card by dragging it into another column or by changing Status in the popup
   its click opens, and the word and the wash move with it in the same repaint. Then open **Assigned tasks** (**Ctrl+F5**
   before checking it — the linked chip is drawn in Kotlin and `a.tcode` is new `app.css`, both of which ship inside the
   jar): the same card there wears its **project code** (`S-01`) on its top line since Stage 88, and since **Stage 104**
   that chip is the way to the project — press it and that project's board opens in the same tab, which is the page the
   number was always naming. A card with no project behind it keeps the plain word: an Other-task card's **bucket** chip
   and a legacy row's `T-00012` are still ink, not links, because neither has a page to walk to. The card prints **no**
   stage word and no foot
   line, and its holder sits back on the meta line — its columns *are* statuses, so the heading says what the card would
   only repeat. Your own Other-task cards are printed under it, each wearing the **bucket** it is filed in, and the
   **Bucket** picker above them narrows them to one column while keeping the five status columns; the **Other tasks**
   board itself is unchanged from Stage 57 in what it prints — no chip, no word, because its columns already name the
   buckets — but since **Stage 95** its cards wear the same stage tint as the ones above, so an amber card means
   *In progress* on either page. Since **Stage 107** the upper board has a **Project** picker of its own, the Bucket
   picker's twin: choose `S-01 · Haul road widening` and only your cards on it stay, still in the five status columns,
   with the address carrying `?project=<id>` and the Bucket choice beneath kept in a hidden field (and the other way
   round). It is Kotlin markup and state only — no stylesheet or script changed — so a plain reload shows it.
   Sign in as the `Viewer` used to check the board above: on a project this account does not manage there is
   no `+ Add task` line on any lane, there is no dashed **+ Add bucket** box at the end of the run, there is no `⋯` on
   **any** head — not on the three the board
   opened with and not on an extra (Stage 75 put those menus on every column of every board, so the only thing left that
   keeps one off a page is the reader's right) — and there is no `#projectLaneModal` on the page either; the popup is
   drawn only for the two readers whose writes its forms would pass. **Stage 79** took one more thing off this page for
   *every* reader: the *Project buckets* panel, so a project page now carries its header, its board and its cards, and no
   bucket list above them. Sign in a third time, as an account that manages a project *without* being management, and everything above is present — the dashed box, each `⋯` — except one
   control: **no `also_type` box is drawn anywhere on that page** (Stage 77). That reader names columns for their own
   board and cannot offer one to the category, whether they press a tick or post one by hand. What this reader *does* get is the columns themselves:
   the board is still Engineering, Procurement, Production, and a card is still filed under one of them. The panel going
   changed where a bucket write is drawn, not who may send it: a hand-built `POST /app/projects/<id>/buckets/delete`,
   `…/buckets/save` or `…/buckets/rename` from the **Viewer** who does not manage this project lands on the Projects list
   having written nothing, while the same three from the project's own manager land and change the board — which is what
   Stage 77 agreed, and what `TaskService.mayEditLanes` still answers. Check the header while you are signed in as them (**Stage 71**): the nine fields —
   **Project number** through **Progress** — are on **one** line under the project name, each caption above its
   value in the same flat style management's inputs use, with the progress **bar** still in the last cell and no
   input, picker or **Save changes** anywhere on the card. Narrow the window and the line stays one line: the
   longest fields lose their tails to an ellipsis, and hovering one shows the whole value. Then read the measurement: a card
   with four steps and one ticked carries a `25%` chip beside its due date and no rows or boxes under it, its
   popup's plan heading says `1 of 4 done · 25%`, and the project bar above the board reads the average of its
   cards. Tick a box in that popup and all three move at once — the popup's list, the card's chip, the bar —
   without the page reloading and without the popup closing; the Projects board row for that project prints the
   same figure the page it links to does. A card with no steps prints no chip, and a project whose cards all have no steps
   keeps the percentage filed on its row — that fallback is the only place the old stored number still appears,
   besides the alert and notification emails. Finally check the one task write a `Viewer` may
   have: pick a project whose **Manager** is that account and open it with no cards on it — the board
   should carry the head's `+ New task` and, since Stage 74, a `+ Add task` line at the foot of each of its bucket
   columns, and its popup should hold a writable title, holder and column with the project
   itself printed once and greyed. Write one card for a colleague and confirm it stays on that page. Then
   open a project that account does not manage: no empty-board block, no `+ New task` anywhere, and
   `?new=1` on either the general board or somebody else's project comes back with no popup. On that same page
   (**Stage 56**) the whole project board is shown to this `Viewer` — every colleague's card with its holder's
   avatar, cut into the project's own bucket columns — and since **Stage 86** every card opens: a colleague's as a
   read-only popup with no Save, no tick and no note box, a card assigned to them as the full owner's editor. The
   drag stays management's on this page and the holder's **✓ Done / ↺ Reopen** stays theirs; there are no move arrows to
   look for on any board, since **Stage 103** took them off both status boards — and that stage shipped a stylesheet and
   a script change, so *there* **Ctrl+F5** is part of the check, unlike this Kotlin-only step where it is not; the
   read is what widened, not the rights. Then watch the
   number beside **Assigned tasks** in the sidebar while you finish a card: since **Stage 54** it counts **every**
   card on your board, so the number does **not** drop as you close one — it stays the same and the card simply moves
   into the **Completed** column where you dropped it (the pill and the board now agree exactly). What still moves that
   figure is only adding or deleting a card. The `**Completed**` card keeps counting because it is still assigned to
   you; and to see a genuinely open-only workload number, look at the Team sheet's **OPEN TASKS** column, which
   deliberately ignores the finished ones.
   Then check the reader's own backlog on this page (Stage 55): with **Ctrl+F5** (its heading and message are new CSS),
   open `/app/assigned-tasks` as a `Viewer` who owns at least one Other-tasks card. Below the project board an
   `Other tasks` heading should print their own backlog cards in the five status columns — and none of a colleague's, and
   no shared-board chrome (`⋮`, *Add bucket*) since that still lives only on the Other tasks page. Since **Stage 88** the
   column names do appear in two places — each card's chip names its bucket, and the **Bucket** picker lists every column —
   but neither is a column heading: pick one and the cards narrow to that bucket, still cut by the five statuses, and the
   URL carries `?bucket=<id>` so a save comes back to the same narrow board.
   Click one of those cards: the same editor opens and, after a save, returns here rather than to the backlog. Now sign
   in as a reader who owns **no** Other-tasks card: the heading prints *You don't have any other tasks assigned.* and no
   second board is drawn — but since **Stage 90** the **Bucket** picker and a `+ Add task` line stay, already aimed at
   the first column. Click the line and the blank popup opens with the assignee and *Not started* printed as facts;
   press **Create task** and the card lands on this page as yours. Choose a bucket first and the line writes there and
   the save returns to `?bucket=<id>`. In both cases the sidebar pill equals the cards on the page (project cards + their own
   backlog), because `visibleTaskCount` sums the same two lists the page reads.
   Then open **Calendar** with **Ctrl+F5** (Stage 66 put new rules in `app.css`). A reader who holds both project cards
   and Other-tasks cards should see *both* on the month: a dated backlog card draws its bar exactly like a project card,
   an undated one takes a row in the list under the grid and no bar, and the strip's `N cards dated here` counts the
   cards rather than the days. Each project — and each bucket — is its own hue, with a small dot inside the bar carrying
   the priority and the key under the strip naming the hues (only when the month holds more than one). Step **›** to a
   month a project's only card falls in and confirm that project's hue did not change, then click a backlog bar and read
   its popup's *Project:* line: `Other tasks · <bucket>`, never blank. A colleague's card is on nobody's month but its
   holder's, and the grid's seven columns stay equal even when a card's title is long enough to need an ellipsis.
   Then read the money sheet as management (Stage 43). `/app/forecasts` opens on this month and every project is a
   row, grouped into one panel per category (Stage 45): the tab row above shows **All** and every project type with
   the count clicking it would leave, and only the categories the month actually has rows for are printed below it.
   One with no line yet sits there uncoloured, its forecast and invoice boxes empty. Type a forecast, leave the
   cell, and that row turns pale yellow while the **Forecast total** under *that panel* takes the figure — the row and
   the totals are redrawn and the page does not reload. Type into a second category's row and confirm the first
   panel's total did not move. Press **›**: the same invoice now
   shows in *Invoiced in previous months* on next month's sheet, and **This month** appears to walk you back. Put
   `about a billion` in a money cell and the sheet comes back with those words still in the box, *"Enter the amount
   as a number, like 8600000."* under it, and no figure changed anywhere. Search one project's client, and every
   panel's totals re-sum over the rows left — then click a tab, search from inside it and save a row, and confirm the
   sheet is still that one category rather than the whole board. Type a category the sheet does not know straight
   into the address (`?type=Not%20a%20category`) and it opens as **All**, not as an empty page. Sign in as the
   `Viewer` on the same month: the tabs, the rows, the derived columns and the row colours are all
   there, printed as text, with no form on any row, and a hand-built `POST /app/forecasts/save` from that account
   comes back to the same sheet having written nothing. Create one project with **Status** set to `Completed` and it
   is not in its panel's grid at all: it prints below those rows, in **Completed projects — invoiced**, carrying
   whatever its ledger actually holds, and its tab's pill counts it.
   Then read the lesson sheet (Stage 44). `/app/lessons` lists every lesson newest first, each under its
   author's initials, the project's number and the card it came from. `+ Add lesson` opens the popup, which asks
   four things and never asks *who* — save one and it stands at the top of the sheet under your own name, the list
   and the "N lessons" line redrawn and the page not reloading. Press `✓` on your row and that row alone
   redraws, its pill green and nothing else moved; the bin asks and then takes the row off the sheet, the count
   moving with it. There is no Archived heading any more (Stage 116), and no row carries an archive button.
   Choose a card that belongs to another project and the popup comes back open, holding your words, with the
   reason under the card field and nothing written. Sign in as the `Viewer` on the same sheet: their own row carries its buttons, a lesson logged
   by somebody else carries none and opens to be read rather than written, and a hand-built
   `POST /app/lessons/delete` naming that row comes back to the same sheet having deleted nothing. The rows the
   importer carried over print with no employee, no date and no card, and sit at the end of the list whichever way
   the **Date** heading runs.
   Then read the team sheet (Stage 46). `/app/settings` is now that sheet first: eight columns, the search box, the
   two pickers and "N of M shown" at the right, every colleague from the roster whether or not they have an account
   yet, and your own row carrying a green `YOU` instead of a bin. Type three letters and the table swaps in place —
   no reload, no lost caret — with the count reading e.g. **3 of 22 shown**; click **NAME** and the arrows turn; click
   **OPEN TASKS** and the sheet re-orders by it, `—` rows (a `Viewer` cannot read them) sitting at the end either way.
   Press `+ Add employee` as management: the popup asks a number, a name, an address — and, since **Stage 110**, a
   position and a first password for any reader with *Manage employees*, plus a **Role** select only for one who also holds
   *Manage roles & permissions*. Save one with a number that already exists and the popup comes back open,
   holding your words, with *"There is already an account with that number."* under the field and no row added. Open a
   colleague's ✎: the number and the name come back read-only, a blank password means *unchanged*, and an edit that
   leaves the roster's person alone cannot invent a second account. The bins that will not press say why in their
   tooltip ("Only somebody who manages roles can delete that account.", "This person has no account to delete yet — the pencil creates
   one."). Sign in as the `Viewer`: no add card, no pencil, no bin, and `?new=1` in the address bar comes back as the
   plain sheet with no form in it; then hand-build a `POST /app/settings/employee/save` from the `Viewer` carrying a
   position and a role and confirm it is **refused**, not trimmed and written. One trap on a genuinely fresh database:
   the Position picker is empty until you add positions on **Access control**, and an account saved without a password
   signs in with its own employee number (any letter case) — and keeps that password until an administrator sets one,
   since **Stage 99** retired the forced change — so set your own password first.
   Then read the roles table (Stage 110). `/app/access-control` opens with **Roles & permissions**: four rows, their chips
   in the app's order, a live **Employees** count, and `+ New role` at the right-hand end of the heading. Press it and the
   popup is a name plus thirteen boxes. Save a blank name for *"A role needs a name."*, save one the catalogue already has
   for *"That role name is taken."* — both times the page comes back with the popup still up and your words in the box, and
   nothing written — then make a role of your own with two chips and watch it appear last in the table holding exactly
   those two (a role with none is a legitimate row and prints **No permissions**). File a colleague under it in the People
   sheet's **Role** picker:
   the count above moves in the same response, without a reload. Sign in as that colleague — the drawer loses every link
   their role has no chip for, `/app/settings` and `/app/access-control` send them back to `/app`, and a hand-built
   `POST /app/roles/save` from them is turned back at the door. Then, **in that same live session**, re-file them onto
   *Project Manager* and reload: `/app/forecasts` answers 200 and the drawer has grown the link, with no sign-out in the
   middle of it — that is the whole promise of the page, one role read by everybody who holds it. Finally check the
   **Manage positions** card below the people table: it asks a name and a description and nothing else, because since this
   stage a position is a job title and grants nothing. **Stage 118** puts the **Department** column beside the role: as
   the Owner move a colleague to *Finance* and the one cell changes without a reload, then sign in as a role holding
   only *Manage employees* and the same column prints words with no picker, its hand-posted department refused.
   Then read the **Team** page's workload row (Stage 67). Give one colleague a card on a project *and* a card on
   **Other tasks**, and give another colleague a project to manage with no card assigned to them. The first row's
   **IN PROGRESS / ON HOLD / NOT STARTED / BLOCKED / COMPLETED** and **Total** should count both of those cards — the
   backlog one included, which is what the page left out before — and the five figures should add up to the **Total** on
   the same row, while their **Tasks** button reads Total minus the finished ones. The manager-with-no-cards row should
   read `0` across all seven, *not* a `1` under a status column: managing a project is not having work on it, and their
   projects are still listed in the popup their row opens. Confirm the heading over the second column reads
   **In progress** and not **Ongoing** — the old word was a project's state printed over a column of cards, and
   **Stage 96** renamed it for projects as well, so the two boards, the popups and the Team row now spell the running
   stage one way.
   Then check the same sheet as a `Viewer` (Stage 68), with **Ctrl+F5** because this stage put three rules in `app.css`.
   Every colleague row should print its full set of figures — **Projects**, **Tasks**, the five statuses, **Total** — the
   same numbers management sees, and **no `—` anywhere on the page**. What must still be missing is the *list*: clicking a
   colleague's row opens their projects but no task titles, and their Tasks figure is a plain number rather than the
   button that expands a row, while the reader's **own** row keeps its button, its drill-down and the popup's task list.
   A colleague with cards on two projects both run by somebody else should read `2` under **Projects**, one who manages a
   project and holds no card at all `1` there and `0` across the six card figures, and a member whose work is all backlog
   `0` Projects against a non-zero **Total** — the two columns measure different things and are not meant to add up.
   Confirm the thirteen headings sit on one line at a normal window width.
   Then check that the sheet is never left printing the past (Stage 69). Open **Team** in one tab, leave it there, and in
   another tab drag one of your cards to a different status column; come back to the first tab and its **Tasks**, status
   and **Total** figures should have updated themselves, with the page **not** having reloaded and your scroll position
   kept. Then press the browser's **Back** button into a Team page you visited before a move and read the same row again —
   it must print the current figures without an F5. `curl -sS -o /dev/null -D - http://127.0.0.1:8080/` (HEAD is refused,
   so dump the headers off a GET) and the same for `/app/team` should both show
   `Cache-Control: no-store`, while `…/static/js/team.js` shows **no** `Cache-Control` at all; if the two page answers
   lack that header, the browser is still entitled to hand back an old sheet, and no script on the page can fix it.
   Then check **Stages 72 and 84**, signed in as management, with **Ctrl+F5**: open a **backlog** card and
   look at the bottom of the popup. **Add note** and **Delete task** should sit on **one** line, the note box taking the
   width and the delete at the far right of it, their bottoms level and the two buttons the same height. Press it and the
   confirmation names the card and says its steps and notes go with it; accept, and the card, its plan and its log are
   gone and you are back on the backlog under the same bucket filter you were reading it under (`?show=<id>` survives
   the delete, since **Stage 97** — before that it was `?allbuckets=1`). A
   **project** card's foot offers that same **Delete task**, posting `/app/assigned-tasks/delete` with `back=project`:
   the confirmation is the same, accepting removes the card, its plan and its log, and you land back on the project
   board. There is no **Archive task** on the card any more (Stage 84) — a card reaches this project's Archive by
   **leaving with its bucket**, and the panel's **Restore** and **Delete permanently** are what an archived card gets.
   The popup must **not** reopen on a card that is gone. Then sign in as the `Viewer` and open their own card:
   the note box is there with no delete beside it, and a hand-built `POST /app/assigned-tasks/delete` for
   that account leaves the card on the board. Deleting a card moves one figure you can check afterwards —
   the sidebar pill beside **Assigned tasks**, and on the Team sheet that holder's **Total** — both of which count
   cards, so both drop by one.
   Then check **Stage 119** with the window, not the tab: drag it narrower than 900px (or use the browser's
   responsive mode). The sidebar should disappear and a hamburger appear beside the page title; press it and
   the menu should slide in from the left, full width, with the page dimmed behind it. Press the dimmed area,
   press Escape, and follow a link — each should close it, and Escape should put focus back on the hamburger.
   The five status columns and a project's lane board should now read as two columns a row, with no sideways
   scrolling; grow the window back past 900px and the desktop sidebar should return with the hamburger gone,
   and tables should still fit their panels. Use **Ctrl+F5** first if the tab predates the deploy.
8. `PTS_SEED_DEMO` is `false` and `data/pts-data.json` is either imported or absent — no demo
   accounts in the live system.
9. Backup job exists and you have restored from it once (`logs/` and `data/` both covered). Take one
   **immediately before the first boot of the categories build**: `V3__project_categories.sql` is the
   first migration that rewrites rows (`SIT-01` → `S-01` and the four old labels onto the nine
   categories), so that snapshot is the undo. After the boot, open `/app/projects` and confirm the nine
   tabs and that every existing project kept its number's tail.
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
| An account still signs in with its employee number | No password was set for it. An administrator sets one from the Team sheet's popup; nothing forces a change on the next sign-in since **Stage 99** |
| A new project is refused with *"This category has no buckets yet"* | Expected since **Stage 100**, and narrower since **Stage 104**: a board is its category's Settings list, so an empty list with nothing typed in the popup has no column to file a card in. Add one in the popup for this project alone, or in **Settings › Project bucket settings** for every project of that category |
| The popup's **Position** picker has no choices | The position catalog is its own table, filled on **Access control** (or by the legacy import). A fresh database has none, and the sheet falls back to `Team Member` |
| A colleague has no **Settings** / **Access control** / **Forecasts** link, or one of those pages sends them back to `/app` | Their **role** does not hold the chip that section is written from (**Stage 110**): Settings needs one of *Manage employees* / *Manage project types* / *Manage email settings*, Access control one of *Manage employees* / *Manage roles & permissions*, Forecasts *View Forecasts tab*. The seeded *Project Manager* deliberately holds neither people chip — tick the right onto the role on **Access control** (every holder changes at their next request, no re-login) or file that person under *Administrator* |
| Pages show unstyled HTML after an upgrade | Static assets are cached by path — hard-reload; check `/static/js/app.js` returns 200 |
| A card drags fine when the page opens, but stops dragging after you use a dropdown or a toggle on the same board | The board was replaced in place, and a replaced board is new markup with no listeners attached. Since **Stage 94** `kanban.js` answers from `document`, so this no longer happens on a stale build: hard-reload (Ctrl+F5) and the drops come back |
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

707 tests in 48 classes — 41 more than **Stage 109** closed with (666 in 44 classes). Three classes arrived with
**Stage 110**'s role model; the later growth is the Assigned create's lane cases (**Stage 111**), the tab icon's
one render test (**Stage 112**), the tab's title case (**Stage 115**), the assignment notice's two status-blind cases
(**Stage 116**) and the department picker's five cases (**Stage 117**) and the People sheet department picker's two cases (**Stage 118**) and the responsive shell's three cases (**Stage 119**).
`db/RoleMigrationTest` (5, on temp databases built to V20 and then migrated) is the migration's own proof: the seeded
catalogue in the picture's order with its 13/13/9/1 chip counts; **every account already on file keeps the rights it had
yesterday**, which is the `CASE` in `V21` and the check that makes the deploy safe; a chip key the app cannot name
granting nothing and costing nothing; a role taken off the catalogue handing its holders back to the default through the
`SET NULL`; and an account added *after* the migration being filed the same way. `service/RoleServiceTest` (4) holds the
catalogue's order and `byId`'s nulls, all three refusals in the popup's exact words with no row written, a new role
appended last rather than default and holding exactly its ticks (an empty set landing with zero is a legitimate role), and
a save rewriting its own row, keeping its place, and moving everybody who holds it — seen through `roleOf`, because the
set is read from the role, not copied onto the account. `AccessControlRenderTest` (8) is the picture: the four rows with
their chip counts, the read-only reading (no `+ New role`, no **Edit**, a tinted chip where the picker would be),
`roleFormOf`'s gating of both openers and of an id that has gone, the popup's markup on create and on edit, a refusal
keeping its sentence under the box with the words still typed, and the People sheet's **Role** column and per-account
picker, and its **Department** column and all-chip picker. `security/SecurityTest.kt` was rewritten around the chips rather than the ranks: `PermissionTest` (2 — thirteen
keys and labels in the picture's order, tolerant parsing) and `AuthorizationTest` (9 — the role decides and neither the
old `access` word nor the position's level does; widening a role moves everybody on the next request; a null `role_id`
falls back; a custom role holds exactly its ticks; the EMPLOYEES count includes roleless accounts). Settings' sheet and
the sidebar follow — `SettingsRenderTest` is 46 with its role-column and picker cases, `EmployeeServiceTest` 32 (a
reader without *Manage roles* is offered no role field, and a hand-built `role=` on their POST is **refused** under
*"Only somebody who manages roles can set a role."* rather than trimmed and written), `SidebarTaskCountTest` 7 (the pill
is the reader's own cards for **every** role). **Stage 109** adds nine. `ProjectCurrencyMigrationTest` (three, on temp databases built to
V19 and then migrated): every project already on a board is `MNT` and keeps its revenue, invoiced and `updated_at`; a
row inserted without a currency lands in `MNT`, pinning the column's DEFAULT, and the forecast ledger is untouched; and
a hand-written `EUR` survives in the row while the repository reads it as tugrik, the way an unknown status does.
`ProjectServiceTest` gains three: a create is tugrik when the form posts nothing, posts a blank, or omits the field,
while `USD` / `CNY` (and a lowercase `cny`) store the number typed with its own currency — no conversion — and an
unknown code is refused under `ProjectField.CURRENCY` with no row written; and an edit leaves the currency the create
chose alone. `ProjectsPageRenderTest` gains two: a dollar project's header box, Viewer line and board cell all wear `$`
while a tugrik row on the same board keeps `₮`, and the create popup's picker offers exactly Tugrik, US dollar and Yuan,
opens on Tugrik, carries each option's sign for the script, and opens on the currency a refused create was posted with
(placeholder included). `ForecastsRenderTest` gains one — two currencies on one panel, each row signed its own way, and
no `Revenue, MNT` / `Invoiced amount, MNT` heading — and three existing cases moved with the stage: the two heading
assertions above, and `ProjectServiceTest`'s refusal now pins *"Enter revenue as a number, like 1,250,000."*

**Stage 117** adds five. Four in `EmployeeServiceTest`: an owner or administrator files a new person under a chosen
department (the roster row and the sheet's cell both read it back); the create's blank and off-list refusals land under
`DEPARTMENT` with no account written, while an edit's blank picker keeps an old off-list word; a reader who is not an
owner or administrator adds a person without one and a hand-posted `department=` is refused under `PERMISSION` on a
create and an edit alike; and an edit moves a stored word while a pencil-create for somebody already rostered writes
onto their existing row. The fifth is `SettingsRenderTest`'s picker case: exactly the nine options after the blank,
`required` on a create and absent on an edit, the `— Keep … —` line selected for an off-list value and the stored one
selected after it changes, and no `department` anywhere in a manager's popup.

**Stage 118** adds two. `AccessControlRenderTest`'s department case: the heading between Position and Role, a picker on every roster row (the row with no login included), the stored off-list word selected and disabled with the nine after it, the form's live target, hidden number and autosubmit, the picker following the all-chip pair rather than *Manage employees*, no picker for a people-only reader who still reads the words, and a row already on the list opening on its own word with no placeholder. `EmployeeServiceTest`'s `setDepartment` case: the move writes the roster row and leaves the job title, *Projects*, blank, a lowercase word and a stranger's number all answer false and write nothing, and re-picking the current word is a no-op that answers true.

**Stage 119** adds three, all in `ShellRenderTest` (a temp database, the whole shell rendered through `appPage`): the page head's opener (aside `#appSidebar`, a `button#navToggle` with its `aria-controls` / `aria-expanded` / `aria-label` and three bars, the title wrapper holding it and the `<h1>`, and the `#navBackdrop` with `aria-hidden`), the single 900px `@media` block (each named rule, the hidden-by-default pair, and no unscoped sidebar translation above it), and the script's `html.js` class, ids, `nav-open` toggle, aria label swap, Escape and 901px query.

**Stage 108** adds one, `ProjectsPageRenderTest`'s *revenue is written and shown in tugrik on
every surface that carries it*: the header box holds `₮ 12,500,000` and still asks for a numeric keypad, the Viewer's
read-only line carries the same figure, the board's column prints it as `<span class="money">₮ 12,500,000</span>` while a
project with no revenue keeps its em dash, and the create popup's box still opens on the grey `₮ 0`.
*(**Stage 109** rewrote this case as *revenue is written and shown in the project's own currency on every surface that
carries it*; every tugrik figure above survives inside it.)* Three existing cases
moved with it — the same class's money case now expects `value="₮ 5,000,000"` (Invoiced still `0`),
`ForecastsRenderTest` pins the completed and running sheets' revenue cells at `₮ 8,000,000` / `₮ 12,500,000` while their
derived neighbours stay bare — 3,000,000, 5,500,000 — and `ProjectServiceTest` gained the signed case inside its revenue
test: `₮ 3,500,000` saves as `3500000.0`, and the refusal still names the unit. **Stage 107** adds seven, all in `AssignedTasksRenderTest`: the picker lists every project
and opens on *All projects*; choosing one narrows the upper board while the Other board beneath it stays whole and the
scope line names the project; a stale id answers the whole board; the two pickers carry each other's choice (and the
`+ Add task` line carries both); a project-only filter survives a card action through `?project=` and the
`assigned:0:<id>` token; both filters survive through `?bucket=…&amp;project=…` and `assigned:<bucket>:<project>`; and
the token helpers round-trip, including a malformed `assigned::5` normalising to `assigned:0:5`. **Stage 106** moved
the total one the other way: `ProjectsPageRenderTest` lost the Archive panel helper and its three panel cases and
gained two — *no page prints the Archive opener, its panel or the copy-link button*, and *an archived card is on no
page while its row waits for the routes that still answer* — its management-header case flipped `data-copy-link` from
present to absent, and `TaskServiceTest`'s two bucket-picker sentences now assert *one card left the board* and that
the word *Archive* is absent.
**Stage 104** adds eight net, of which five are `ProjectServiceTest`'s for what the create
form itself names — a typed bucket lands **after** the category's own list and carries no colour slot, a typed bucket is
this board's alone (the catalogue unchanged, a sibling project never seeing it), a name the category lists is refused
rather than dropped, a name twice on one form is refused while two distinct names succeed, and a 41-character name is
refused while a blank one is dropped — plus three in `ProjectsPageRenderTest` for the popup's markup (one preset row per category with
exactly one `.on`, the Add bucket line with no `also_type` and no bucket `value=` at all, and a refused create's chips
each carrying their own hidden `new_bucket`) and one in `AssignedTasksRenderTest` that the project chip is
`<a class="tcode" href="/app/projects/<id>">` while the backlog chip is still a `<span>`; two Stage-100 cases were
replaced by them, and the chip assertions read the anchor by attribute because kotlinx prints an `<a>`'s attributes in
map order. **Stage 103** moved the total by nothing and changed the cases that had been requiring the
arrows: `TaskBoardRenderTest` now asserts the prev/next pair is **absent** for management, the holder and a stranger
alike, on the Assigned board and on the backlog board, while keeping the lane board's single **✓ Done / ↺ Reopen**
pinned (one of its cases reads `assertFalse(html.contains("← "), "the status arrows came back")`), and
`OtherTasksRenderTest`'s Stage 58 case became *a backlog card's status is moved by the column, and the board prints no
button for anybody*, with the `Viewer` case reading the card tag itself for `draggable="true"` plus no `kmove` anywhere.
**Stage 102** adds two
cases for the login spelling rule: `AuthServiceTest` signs in four ways — `emp0100`, `EmP0100`, `EMP0100` and
`"  emp0100  "` — and proves each lands on `EMP0100` (session included) while a lowercased password is still refused,
and the new `LoginPageRenderTest` reads the form: lowercase example, `autocapitalize="none"`, `spellcheck="false"`, no
`pattern=` on the employee-number box, the case guidance present and the retired forced-change promise absent.
**Stage 112** adds the tab icon's one case to the same class: one `<link rel="icon">`, `type="image/png"`, the exact
`/static/assets/favicon.png` href, and its position before the first stylesheet. **Stage 113** changed only the PNG's
pixels — the tab then wore the wordmark's emblem instead of its M — and **Stage 114** changed them again, to a
transparent crop of the logo cut like murrays.mn's own favicon — so that case, and the count, still pass unchanged.
**Stage 115** adds the tab's title case: `<title>MPTS</title>` on every page, so neither the long system name nor
a per-page suffix can quietly return to the tab.
**Stage 101** adds the late-step render regression and changes the finished-card service
case to prove new steps stay unticked until worked or finished. **Stage 100** moved the total by nothing and rewrote the bucket tests around the new rule: a
shared `TestBoards.seedOpeningThree` / `seedOpeningThreeFor` fixture writes the legacy trio into a board or a catalogue
where a case needs the old shape, so the Stage 75–98 assertions keep their meaning. `ProjectServiceTest` gained *a
category with no buckets refuses the create until Settings lists one*, renamed its catalogue case to *a new project
opens with its category's whole Settings list, in that order*, and expects the trio **plus** `Commissioning` where a
tick wrote a row; `BucketDefaultsServiceTest` proves the three phases are ordinary names and that a category's first
added row starts at position 0; `SettingsRenderTest` rewrote the panel case with absences (no *"Always on every board"*,
no phase rows, `dot-0` on the first row) and turned its phase-row CSS checks into absence checks;
`ProjectsPageRenderTest` rewrote the create-popup case for the new hint and the refusal; and `TaskServiceTest`'s
phase-name case now proves a phase name put back on a board is an ordinary bucket. **Stage 99** had taken the total from
641 to 639 by replacing `MustChangePasswordTest` with the two-case `DefaultPasswordFlagTest`. **Stage 97** moves the
total by nothing and the board by one control: the six
`OtherTasksRenderTest` cases that pinned the head's **Show all buckets** toggle (`the head's toggle is asked of the same
read the board prints`, `the all-buckets toggle swaps the board and its own label together`, `the toggle is not drawn
when every column is already on the board`, `a bucket that has just been named is shown on the board that answers`,
`the view the reader is standing on travels with every write`, `the toggle drops the filter and keeps the wide view`)
are gone, and six pin the other side — *the head offers one control and the picker is the board's only view*,
*the picker reveals a bucket the board is hiding*, *a board with nothing hidden says so and grows no extra control*,
*a bucket that has just been named lands on its own filter*, *no write on this page carries a view state any more*, and
*All buckets is the way back and the address bar follows it*. `TaskBoardRenderTest` retargeted two of its own (the wide
board's step-write and delete cases now read for the retired `allbuckets` field and require its absence, and the delete
asserts `taskRedirect("other:7", 0) == "/app/other-tasks?show=7"`), and its `edit`/`editAsOwner` helpers lost the
parameter the toggle's state travelled in. Stage 96 adds five. A new `db/ProjectStatusMigrationTest` runs V19 over a database built to
V18 and pins three things: the renamed project's status is a word the picker offers, nothing outside `projects.status`
moves (including a card that literally holds the retired word), and `updated_at` is byte-identical across the migration.
`ProjectsPageRenderTest` gained the ask itself — the picker's five option values in order with **Not started** selected,
the same markup re-printed on a rejected create, and one project per stage under the same end date sorting in the
picker's order forwards and backwards — and ~50 existing project-status literals were re-pointed across 21 files, the
Forecasts URL assertions among them (`status=In+progress`: that encoder turns a space into a `+`). Stage 95 added one: a
backlog card wears the same `s-*` tint the assigned board paints, at
every one of the five statuses, and the board denies `s-other` and `kstat` while requiring it. Its colour claim is a
CSS specificity claim, so it is also read off pixels rather than argued: `scratch/s95-render-check.ps1` inlines the
shipped stylesheets into a fixture holding both boards and shoots it in the light and dark themes with headless Chrome.
Stage 94 changed no Kotlin and added no test — its fix is in `kanban.js`, which no render
test can reach (the cases that pin `.kcol[data-status]`, `.kcard[draggable]` and `.kmove-btn` all passed on the
broken build, because the markup was never wrong). It is verified by a throwaway harness instead —
`scratch/s94-kanban-harness.cjs` loads the shipped file into a stub DOM, presses a board, replaces it the way a live
region's swap does, presses again, and `--prefix` runs the identical scenario against the pre-fix bindings as the
control. Stage 93 added six (the shared backlog carries the Assigned page's Bucket picker, a chosen bucket prints only its group, a Viewer's page keeps its one bucket and grows no picker, the filter survives every way off the board, the toggle drops the filter and keeps the wide view — *that* one of the six
is the case **Stage 97** deleted with the control, and its replacement is *All buckets is the way back and the address
bar follows it* — and the `other:<id>` token itself), Stage 92 deleted two (a rejected create no longer brings bucket ticks back, and the create popup no longer asks whether a typed bucket is for the category — the column popup's version stays), re-pointed one (the popup names the three phases and prints no bucket control) and flipped Stage 91's (the number box opens on the category's lead, `M-`, with the next free code still the placeholder), Stage 90 added three (an empty Other-tasks board still prints its Bucket picker and a
`+ Add task` line, the line follows the chosen bucket and brings the save back to it, and the popup it opens pins a
non-manager's writer and starting status), Stage 89 added one (the Other-tasks select sits inside its label) and re-pointed the plan's
writing right on both sides (the assignee adds to their own card, a stranger is refused, the × stays management's),
Stage 88 added eight around the card's chip (project code, backlog bucket, the legacy
fallback), the Other-tasks bucket picker and the `assigned:<id>` filter token that survives opening, saving and a
refusal, Stage 87 added two, deleted one and re-pointed two more around the card editor's field
list (the project and sub-category pickers left the edit, the hidden project and the refusal that brings the
pickers back are pinned in both directions), Stage 86 added three and rewrote the card-popup read cases, Stage 84 rewrote the card-popup delete cases rather than adding any, Stage 83 took the colour
off both creation forms and pinned the add lines as name-only, Stage 81 added `TaskArchiveMigrationTest` for V18 and the
Archive cases across the service, the project page and the card popup, after Stage 80 added
`BucketColourMigrationTest` for V17 and the colour-row and
fold-line cases, after Stage 79 moved the project page's bucket pins onto the column popup and the panel's own cases
went with the panel — on Flyway-on-SQLite migration + FK
enforcement + idempotent migrate (the table list now
includes `project_forecasts`, `lessons`' six V9 columns, the empty four-column `type_default_buckets` V12 creates
without seeding, the empty two-column `app_settings` of V13, V14's `project_types` — which by contrast *must* seed its
nine rows, since an empty category catalogue would leave the New-project form with nothing to file under — **V15**'s
one nullable `pre_completed` column plus the completion it runs over the steps of cards that were already marked
*Completed*, and **V16**'s re-cut of `project_buckets`, which adds no table and no column at all and is therefore
pinned by what it leaves alone as much as by what it writes, and **V17**'s two nullable `colour_slot` columns, which are
pinned the same way — a fresh database has them empty and every board paints exactly as it did, because NULL is the
answer *wear the colour of my position*, and a second boot changes nothing at all — and **V18**'s four `tasks` columns,
pinned the same way — an existing row reads `archived = 0`, its three snapshot columns stay NULL, no card's lane moves,
and a second boot changes nothing — all tested against a database built
up to the version
before each one: a half-worked plan there comes back ticked *and*
remembered, an open card's steps are left exactly as they were with nothing remembered, no row ends up unticked while
still carrying a memory, and a second boot does not run that completion again), the V3
category migration against a database built up to V2 only (legacy `SIT-04`/`Manufacturing` rows
rewritten, new columns arriving blank/`0`, current and hand-edited values surviving a re-run), the V7 lane
migration and the V10 and V16 ones against a database built up to V6 only (every project that already existed given its
lanes in board order, each project getting *its own* rows rather than a shared set, an existing card left unfiled rather
than guessed at, deleting a lane un-filing its cards while deleting a project takes its lanes with it, and the
backfill not replayed on the second boot; then V16's own half — **the three phases written for every project that
existed and numbered 0–2 in the order the board draws them, in the order the picker lists them**, V10's empty
sub-categories taken off, one that holds a card under a phase name keeping **its** row rather than being given a twin,
a retired sub-category that holds work surviving as an ordinary lane after the three, a lane the manager added for
the project standing on, no card's `project_bucket_id` moved and
no second copy of anything on the second boot), the legacy JSON importer — which still writes the three lanes for
every project it imports, a deliberate exception to Stage 100's rule because an import predates any Settings list for
its category — role resolution and authorization, CSRF token forgery, the login throttle
window, the full auth path against a real SQLite file (bcrypt login, session issue/resolve/revoke,
revoke-on-password-change, expiry), the `must_change_password` flag (recorded, not enforced since Stage 99 — a flagged
account signs in and works), bootstrapping an empty
database (super admin exists, is flagged when no bootstrap password was given, repeat boots are idempotent), the due-date alert rules
against a real SQLite file with SMTP forced into dry run — in-window alerting to manager and
assignees, task alerts copied to the project manager, silence at 100%/`Completed`, the lead-window
edge, overdue wording, blank and malformed due dates, items with nobody attached, recipients with
no email address, the same-day dedupe, the ledger's UNIQUE index rejecting a duplicate row, and the
delivery test (dry run sends nothing and writes no ledger row, its body reports the configuration it
used without the password, `emailFor` reads the address on file) — and project creation: each rule of
`ProjectService.create` (duplicate number, wrong category letter, malformed number, reversed dates,
blank description, unknown category or manager, revenue that is not an amount) plus the category and
number-suggestion helpers, **what the create popup may ask about the board** (a bucket answer is `ProjectField.BUCKETS`
and nothing else, so the picker's complaint can only come back under the picker; the board a create writes is the **three**
opening lanes and then *only* the ones ticked, in catalogue order; a create with nothing ticked takes the three alone even
when the category offers more; a typed name goes on the board and into the
category's catalogue **only when the form's `also_type` is ticked** — left clear the next project of that category is still
not offered it, ticked it is offered but still only taken when *that* form ticks it, because the catalogue is an offer and not
an order (**Stage 77**); a tick on a name the catalogue already has writes one row, not two; a tick and a typed word naming the same bucket make one lane, not two; and one of the **three** typed
or ticked into the tick-list refuses the whole create with **no project written** — an offer the board opens with is not an
extra, and a refused create leaves no lanes behind), and the page picker's own rules
(`TaskService.saveLanes`: a ticked category bucket joins the board and an emptied unticked one leaves it; **since
Stage 81** an unticked bucket that still holds cards leaves too, and its cards go to the Archive with the lane's
name remembered, while an empty one unticked in the same press leaves with nothing to move, **the three a board opens
with are the picker's to untick** (Stage 75) with one lane always staying, a name the catalogue does not know is
**this project's own column** for anybody whose board it is and joins the category's defaults only on a ticked box from
management — a tick from a caller who is not management is refused and writes nothing, not even the column
(**Stage 77**), while a phase name put back on a board is neither a refusal nor a catalogue write, a rightless press changes nothing, a hand-built double tick is
one column, and the sentence says how many cards went with an unticked column), and the notification rules behind
the bell: the assignee of a new card is
told and the manager who made it is not, a rename is silent where a move speaks, one item is one inbox
row per day however often it is touched, dry-run delivery is recorded on the row with or without an
address on file, `markRead` / `markAllRead` / `safeLink` are scoped to the owner and to `/app/`
targets only, an outside link is refused rather than followed, and the inbox trim honours `0` as off — and
the backlog's own rules: a column's name is trimmed and length-capped, a duplicate is refused against every
other column but not against its own stored spelling, and a delete hands its cards to a named column first
(`LAST_BUCKET` when there is nowhere to send them, `NO_DEST` when the answer is missing or points at itself),
while a column holding nothing deletes with no destination asked. Two cases pin who a backlog card may be
saved with: one whose row is `OTHER` is accepted with *Assign to* left blank — the card belongs to a column, not
to a person — while a name that is not on the roster is still refused, and an `ASSIGNED` card cannot have its
assignee stripped by a form that leaves the field out, because `TaskService.updateAssigned` reads the row's
`kind` through `TaskRepository.kindOf` rather than trusting what the popup posted. Stage 42 adds seven more over
the same two routes: an ordinary account's `createBacklog` rebuilds the draft before it validates it, so the card
lands as the writer's own and `Not started` however the form was posted, while management's named person and status
are kept as named; a create that names no column, or one that is not on this board, is refused rather than filed
nowhere — `tasks.bucket_id` going null takes a card off every column of the board and there is no *Unsorted* column
here to fall back to — while an edit that posts no column at all leaves the card standing where it is, because a GET
opener drops the query and not every form on that board is a re-filing. **Stage 49** retunes that last one: the
person a backlog card belongs to writes their own dates and priority and cannot move it between columns at all —
`updateAssigned` now rebuilds `columnId` from the row for anybody who is not the mover — while management's copy of
the very same form does move it, and a stranger's card stays a stranger's.
And the measurement the ticks add up to, in the service layer and in the markup together: one tick in four
steps is 25% on the card, a card nobody wrote steps for is unmeasured rather than at zero and prints no
percentage, a project averages its cards equally (not their steps) and leaves unplanned cards out of that
average entirely, each project is measured from its own cards and no other, a project nothing can measure
keeps the figure filed on its row, and ticking a step moves that project's number on every page that prints
it — the board row, the project page's bar, the popup's plan heading and the Team popup's data.
The card's own figure is drawn twice over now, and the tests hold the two together: the popup that opens from a
card prints a bar under its `1 of 2 done · 50%` heading and fills it to that same half, in the `.progress`/`.bar`
pair every other measurement in the app uses, sitting above the rows it measures and inside `#task-steps-<card>`
so one response moves the box, the number and the fill; a card nobody planned opens with no bar, exactly as it
prints no chip.
And the sidebar number, against the same real-schema harness: since **Stage 54** a card dragged into `Completed`
*stays* in the figure beside **Assigned tasks** — the pill counts every card on the reader's board and now agrees
exactly with the board it sits beside (five columns all count, none subtracted), so closing your own card no longer
drops the number. The figure is still scoped to the reader exactly as the board is — and since **Stage 53** both are
the reader's *own* cards for every role, so a `Manager` assigned no cards reads `0` even while colleagues' cards sit
open, and closing somebody else's card cannot move a colleague's number. The pill is the reader's whole board read
straight off the cards, with no project-level strip left to agree with. And since **Stage 55** the figure covers both
boards the page now prints — `assignedFor(empNo, false).size + assignedOtherFor(empNo).size` — so the reader's own
Other-tasks cards raise the number exactly as they raise the page, and `SidebarTaskCountTest`'s new case proves a reader
with one project card plus one own backlog card reads `2` while a colleague who owns only a backlog card reads `1` and a
reader who owns neither reads `0`. (The Team sheet's OPEN TASKS column is the one
figure that still counts open cards only, and this suite never reads it.)
The four render suites pin what the markup promises: on a board, a card the reader may open — for anybody
who manages or is assigned to it, and for nobody else — which is a plain clickable card carrying its id,
a tab stop and an aria label, with no menu, no `Edit` button, no status dropdown and no standing buttons
of its own, and one hidden GET form per board that the click fills with that card's id; a move to another
column is a drag or the editor's own Status field, and since **Stage 103** that is the whole of it — no status board
prints a move button, so the only button a card carries is a lane board's
**✓ Done / ↺ Reopen**. Since **Stage 49** the two are pinned apart on the same node: `draggable="true"` is printed for the mover
and for the project's own manager only, while the owner of the card — who now prints no `draggable` at all — keeps the
click that opens the popup and their own **✓ Done / ↺ Reopen** button, so a card that cannot be dragged is a card that
opens rather than a card that is dead. A `Viewer` whom the project names as its manager is asserted to drag, and a
mover who does not hold the work is asserted to drag without being able to open it. And no card printing a plan of its
own — no `Checklist` heading, no step row, no box and no × anywhere on
a board, for its owner included, while the same card's popup prints those boxes for exactly the readers the
service lets tick, its add line for exactly the readers who may add a step (management and, since **Stage 89**, the
card's own holder) and its × for exactly the readers who may shorten the plan (management); on the Projects pages, that a `Viewer` with no rights of their own is served
neither the create popup nor the "Set manager" picker nor any way to open a card, while a manager gets all
three with the roster grouped by department, that every category panel prints the same nine `<col>`s in
the same order with a `<th>` and a `<td>` carrying each column's class — which is what lines the panels
up under one another instead of leaving "Project" at a different x in each group — and that the search bar
with its two sort pickers is one GET form whose values come back selected, that a search narrows the panel
it is on while the pills, the tab links and the empty-board message follow it, that status sorts in working
order and reverses, and that a project nobody has taken on still sorts last whichever way the names run;
on the Team page, one
heading row per department in
alphabetical order with people sorted by name inside it and the same grouping as `<optgroup>`s in every
member picker; that the **Assign a task** panel (Stage 50) asks the board question on one `data-board-toggle` with the
project half's two fields and the backlog's one column picker, opens on *On a project* with the other half hidden, and
carries **no** `required` on the two project pickers while **Team member** keeps its own, that its column picker lists
the board's columns and opens on `""` and an empty board says *"has no columns yet"* instead of printing a blank box,
that a refused assign keeps the typed title, due date, person and priority, flips to the *other* half and prints the
reason under its field — and its service pair, that a no-project assign files the card by its column and drops the
project fields the hidden half posted while a project assign keeps its lane and takes no column; and the bell,
shipping closed with its badge counting unread rows and every item routed through the marking endpoint.

Forecasts are tested the same two ways, and the arithmetic is the feature, so most of it is arithmetic.
`ForecastServiceTest` runs against a real `project_forecasts` table: the previous-months column is the sum of a
project's lines for months *strictly before* the one being read (its own month's invoice excluded, and a later
month's too), the identity holds on two different months of the same project, an over-forecast prints a negative
remaining rather than a clamped `0`, a project with no line still appears as a blank row with no colour and writes
nothing when it is saved, the row's colour follows the status management stated and not the figures beside it, the
search and the status picker narrow each half of the sheet and the totals with it, the sheet groups into one panel
per category in the project list's order with no panel for a category holding nothing, each panel's totals summing
only its own rows so the panels add back up to the board's figures, a tab's pill counting what clicking it would
leave rather than what the open tab is showing — so it counts both tables of its category — and an unknown category
read as no category at all rather than as an empty sheet, a `Viewer`'s write is refused before
anything is parsed, an amount is accepted as `8600000`, `8,600,000` or `8 600 000.50` and refused as text, as a
negative or as a magnitude no month can hold — each under the cell that said it, with both cells reporting when
both are wrong — a line is keyed on the month the page reads and never on the invoice-month cell, so naming
November there leaves October's row where it is while November's own `previous` still counts October's money, a line
emptied of everything is deleted whereas `0 / 0 / Fully invoiced` survives as the claim it states, and one saved
line moves the next month's figures without a second write. `ForecastsRenderTest` pins the sheet as markup: the
eleven headers in the order and the spelling the picture uses, over eleven `<col>`s carrying the same classes; a
completed table of eight, where the three questions about a plan — this month's forecast, what is left for next
months, and the invoice status — are simply gone and "previous months" becomes the total the ledger holds; an
untouched row printed with no tint, its boxes empty for a writer and its figures as plain text for a reader; the tab
row as the Projects board draws it — **All** plus one tab per category, each carrying the lettered label and the count
of its own panel's rows in both tables — with the panels printed beneath in the project list's order, each one a chip
and a pill above its own table, no panel for a category the search left empty, and one open tab at a time; one
hidden form per row with its four writers bound to it by `form=`, each naming the row as the block to redraw and
**that panel's** totals line as the companion that moves with it — which is why the ids carry the category letter
rather than being one shared `#fc-totals`; the ‹ / › and **This month** controls naming the absolute months they
go to, the last of them drawn only while the page is looking somewhere else; the filter's `board_*` companions
riding on every save so a row cannot widen the sheet it was filtered into, and an open category riding as a hidden
field rather than a picker the tabs already answer; a category the sheet has no panel for coming back as no category,
in the address and in the posted form alike; and a refused line coming back with the
characters still in its box, one message under the field that caused it, and the totals still reading the ledger
rather than what was typed.

Lessons are tested the same three ways, over the migration, the rules and the markup.
`LessonMigrationTest` runs V9 against a database built up to V8 only: the six columns arrive and the rows the
importer wrote keep their words — `title` joined to `summary` in the one `lesson` column the sheet reads, and a
title-only note keeping just its title rather than a dangling separator — while the three things those rows
never had (who, when, which card) arrive blank instead of guessed at; a lesson outlives the project, the person
and the card it points at; the backfill is not replayed on the second boot; and the two lists and the two
pickers are indexed. `LessonServiceTest` holds the rules behind the sheet: a new row is logged under the account
that wrote it and an edit leaves it under the name it was logged with, an ordinary account may write its own row
and nobody else's while a row nobody owns is management's alone, a write on somebody else's row or on one that
has since gone is refused where it stands, a lesson needs words and is one cell of 500 characters at most, a
popup with no date means today, a card cannot be filed under another project, the two pickers cut the sheet and
nothing else, the undated carry-overs sit at the end of *both* orders, archiving moves a row between the lists
and bringing it back moves it again, the discussed pill is a state and not a judgement on the text, and each row
knows whether the reader looking at it may write it. `LessonsRenderTest` pins what the page promises: the seven
columns in the order the picture reads them, the employee column as the person who logged it in their own colour,
the project as the job's number and the task column as its card, the lesson's own pill beside a date column that
is also the button reversing it, the two pickers as one live GET form whose companion is the count line, the date
header and the archived heading living inside the region while belonging to the toolbar's form, the order and the
open archive riding with every control on the sheet, nothing nesting a form inside a form, a row that only changes
state staying exactly where it is while a row that leaves takes the sheet and the count with it, only the author
and management offered a row's buttons, the popup arriving drawn but closed and opening without moving the sheet,
a colleague's lesson opening to be read and not written, and a refused save coming back with the words and the
reason under the field that caused it. Two of them read `app.css` itself rather than the markup, because the sheet's
two overlap bugs were cascade bugs the page gives no sign of: the lesson cell's form is stated as a block so it cannot
lose to the shared inline-flex opener rule on source order, and the seven column shares add to 100 with the date, the
employee code, the status pill and the three action buttons each given at least the width they have to spell.

The team sheet is tested on both sides too, since Stage 46 moved account management onto it.
`EmployeeServiceTest` holds the read and the rules: the sheet is every person on the roster, account or none, with an
account whose number left the roster still standing as a row; a colleague with no login printed with no role and no
bin, because the pencil is the control that has an answer for that row; the position column read off the catalog entry
the account is actually filed under; the role column printing the effective role rather than the stored level while the
popup writes the stored level, and one test stating plainly what that pair of facts adds up to for an account filed
under a Viewer position; management counting every row and anybody else counting their own with a dash elsewhere, and
the count line counting only what the reader is shown; the search box reading name, number and address while the two
pickers match exactly and combine; the order — default name-first, a header asking for the order it names rather than
the one on screen, one head reversing its own column and leaving the rest alone, the task column ordering as numbers
not text, and a row with nothing in that column landing last in **both** directions; the pencil an administrator's
alone; the bin carrying the exact rule that refuses it; `save` refusing a duplicate number, a nameless account, a role
off the three, a position the catalog no longer has, a password under four characters, and a manager's hand-built POST
carrying a position or a password — each under its own field, with the typed words kept and nothing written; an edit
changing the account while keeping its name, and a blank password leaving the current one alone; and a number matched
the way the sheet writes it, lower-case in the address and upper-case on the row. `SettingsRenderTest` pins the page:
the eight headings in the picture's order over the eight shared `<col>`s, the toolbar's three controls and the count
outside the live region it feeds, the seven headers as buttons of a carriage form that carries the search and the two
pickers and **never** a `sort` of its own, one form per row for the pencil and the bin with no form ever inside a form,
the add card printed for management alone and its opener carrying the filtered view, `?new=1` opening a blank popup and
`?edit=` opening the same one read-only at the top with the row's position and role already chosen, an unknown `?edit=`
answering with the sheet and no form, a refused save re-printing the popup with its messages under their fields, a
bin that cannot press being `disabled` with its reason as the tooltip while the live ones ask to confirm, and a row that
leaves the sheet taking the "N of M shown" count with it.

Task visibility and the card checklist are covered on both sides. In `TaskServiceTest`: one manager and one
engineer on the same database, so `assignedFor` with the switch off still returns only the caller's own
cards, an account with nothing assigned gets an empty list rather than somebody else's work, and the
checklist rules are exercised as written — management appends in order, the assignee may add to their own plan
since **Stage 89** but cannot remove a step, the assignee may tick, a second tick unticks, blank and over-long labels are
refused, removing one step leaves the rest numbered in place, and deleting the card deletes its steps
through the V5 foreign key. **Stage 70** adds seven more over the same rules — that moving a card into
*Completed* ticks every step so the card reads 100%, that the popup's *Status* picker and the board's drag do it
identically (the case read the arrows as well until **Stage 103** retired them), that moving it back restores each step to where it stood before the finish and a *second*
completion remembers the plan as it stands the second time, that no box moves on a finished card whichever
account reaches for it, that since **Stage 101** a step added to a finished card lands **unticked**, stays unticked
through the next reopen and is ticked by the next finish, that a save which leaves the status alone leaves the plan alone,
and that a card with no steps
finishes and reopens with nothing to remember. `TaskBoardRenderTest` pins the markup that goes with those rules, and since
Stage 41 all of it is popup markup: a step row *is* its toggle form, carrying the card id and the step id
with the box, and the whole row is the label you click; a ticked step comes back `checked` and struck
through; a step write names the popup's plan block as its target and the card's own block plus
`#project-progress` as the companions it moves with it; the add line prints on the right to add and the × on the right
to shorten, so an owner's popup holds live boxes and the add line with no × (**Stage 89** gave the owner the add line;
before it they had neither); and a board — status,
lane or backlog — carries no plan markup at all, not one box, for the person the card belongs to included.
The three halves of the split are tested separately: a card's own `kpct` chip lives inside the block a tick
swaps, a colleague's card on the same board prints its percentage with no boxes beside it, and the boxes
follow the right to tick rather than the page the card is on — management gets them on anybody's card, and a
card with nobody on it gets them for the manager alone. Stage 70's locked rows are pinned beside them: a
finished card still prints its plan struck through and counted at `2 of 2 done · 100%`, but the toggle form is
**gone** rather than merely inert — no `action="/app/tasks/checklist/toggle"`, no `data-autosubmit`, each
square `disabled` and `checked` inside `class="chk-box locked"`, the reason carried on that box's `title`
("This card is Completed, so its steps are settled — move the card back to change them") — while the add line
stays for management and the card's holder and the × for management, the same card seen by its assignee keeps the
plan and the add line and loses the boxes with it, and *Blocked* is tested as an ordinary open column: only *Completed* locks a
plan. **Stage 72**'s four cases pin the delete and its row: the form posting to `/app/assigned-tasks/delete` carries
exactly `taskId`, `back` and `projectId` — and since **Stage 97** that is the whole list, the wide view's `allbuckets=1`
having gone with the toggle (it rode on the form only when the reader was standing on the widened board, and the narrow
view was never handed it) —
and carries **no `edit`** — the way back is the board, because there is no card left to reopen; a non-manager's popup,
in three shapes (the card's own holder, a reader with no part in it, a card being created), prints the note box with no
delete anywhere near it; and the two buttons really do share one line, which is read out of `app.css` as three exact
`.note-foot` declarations plus the delete's own metrics block, and out of the markup as *Add note* appearing before
`>Delete task<` inside the same `class="note-foot"` — the
metrics matter because `.ghost-btn` is
written after `.btn-sm`
at equal specificity and would otherwise leave a taller button standing beside a shorter one. All four read the whole
`<form>` through a `formPosting` helper, since a tag-only match stops at the opening `>` and every field this route
reads lives past it. **Stage 84** put that one button back on both worlds and took the archive button off the popup:
`TaskBoardRenderTest` asserts a project card's popup posts `/app/assigned-tasks/delete` with `taskId`, `back` and
`projectId`, carries **no `edit`** and no `data-live-target`, and prints a confirmation naming the card and warning that
it cannot be undone; a project's own manager, its assignee and a reader with no part in it are shown neither button,
because the route checks `canManage` whatever the markup offered; and no rendered page carries
`/app/tasks/archive` at all. **Stage 86** put a click target on every card and a third state in the popup:
`TaskBoardRenderTest` asserts that all three readers (manager, holder, stranger) carry
`data-task-card`/`tabindex`/`aria-label` while only the manager and the holder print `draggable` — the move arrows this
case also read went in **Stage 103** — that a stranger's popup is headed `Task T-…` with the holder's sentence, **no** Save, every control
`disabled`, the plan present with `chk-box locked` and no `name="done"`, the log present, no `.note-foot`, and
`Close` instead of `Cancel`, and that an owner keeps Save, the live tick box and the note box while title, assignee
and status stay greyed — the project and the lane pickers left the edit in **Stage 87**, which that suite now pins
in both directions: no `#taskProject`/`#taskLane` on a project card's edit with the row's project posted hidden,
both pickers still on a create and on a backlog card, and both drawn again under their message when a save is
refused; `ProjectsPageRenderTest` counts every card's click target and the single
`#taskEditOpener` on a viewer's project page; and `AssignedTasksRenderTest` gained `a board with no card on it
carries no opener`, because that page's opener now follows its cards rather than its role. The Archive itself keeps its Stage 81 cases, end to end: `TaskServiceTest` covers a lane
removal archiving its cards with the lane's id and name, a restore to the remembered lane and to the first when that
lane is gone, the board's right rather than the assignee's, permanent delete only for an archived card of this project,
and an archived card refusing `openableBy`, `updateAssigned` and `deleteAssigned`; `ProjectsPageRenderTest` pins the
header's 🗄 for a writer and not for a Viewer, the panel's *"was in \"…\""* line, its **Restore** and **Delete
permanently** forms, and an archived card being off the board, off its column's count and off the page's own list;
`TaskArchiveMigrationTest` pins V18 against a database at V17 and on a second boot. Stage 25's author rule is tested on the same two
sides: in `TaskServiceTest`, the account a project names writes its first card, a card they post naming
*somebody else's* project still lands on their own, the right does not carry over to the general board, and
nobody short of management or that project's manager is given a way in — while `TaskBoardRenderTest` builds
the blank popup **through `newFormOf`**, the way the route does, so an empty board hands its `+ New task` to
the reader who may write and to nobody else, a project manager's blank card is open on three fields and one
greyed project, and `canCreate` never opens an existing card. `TeamPageRenderTest` holds the Team split: a `Viewer` is served their own
task list on their own row and nothing else, with no `+ Assign a task` panel anywhere in what they get. Since
**Stage 68** that is the *whole* of the withholding on that page — the **figures** print for every reader on every row,
so the test reads a colleague's numbers as the reader's own and then checks the two things still missing: no
`tasks-toggle` button on that row (its Tasks figure is a `tasks-static` number) and no task title in that member's popup
slice. The counting itself is pinned there too: a row's five status figures are read from that person's **cards on both
boards** — a test gives one member two project cards and three backlog cards and expects `[2, 0, 1, 1, 1, 5]`, and gives
their colleague who *manages* one of those projects and holds no card `[0, 0, 0, 0, 0, 0]`, because managing a project is
not having work on it — and a second test states the arithmetic those six figures have to satisfy, that the five columns
sum to **Total**, which is only true now that every one of them is counted from the same card list. The **Projects**
column is held to a different rule, and the test says so: it is the member's managed projects *plus* the projects their
cards sit on, it does not take part in that sum, and it equals the length of the project list in the same member's popup.

The lane rules (Stage 30) are tested on the same two sides. In `TaskServiceTest`: adding one is management's
or that project's manager's and nobody else's, a blank name, one over the 40-character cap and one the project
already has are each refused with their own code, the duplicate comparison survives the trim and ignores case
so `" engineering "` onto a board that prints **Engineering** is refused rather than becoming a second column
for the same phase, two projects may each call a lane **Engineering** because lanes belong to a project and not
to the board, a new one lands at the end of the running order, and moving a card needs the same person who may
move it anywhere else — while a lane id from another project moves nothing and, **since Stage 48, so does `0`**:
un-filing a card by dragging it onto **Unsorted** is refused, because a card on a project is filed in a
sub-category and that column is only where the cards that pre-date the rule wait. A save from the general board,
which sends no `lane` field at all, still leaves a card sitting in the lane it was in — an absent field, a blank
one and a foreign one are three different answers (Stage 48): absent keeps the row's lane, blank and foreign are
refused with *"Choose which part of this project the card is on."* A **create** can never be the absent case,
because a create is asked for a lane and a card handed to a different project is asked for one on that project
rather than kept on the board it left. `ProjectServiceTest` holds that the board is cut from the category's Settings
list with the project and in that order (its fixtures seed the legacy three for that), that `ProjectField` carries the
nine form fields **and** `BUCKETS` — the last only as the refusal for a category with no Settings rows, never as a
picker, so a lane question cannot quietly reappear in the popup — and that a create the form refused leaves no project
and no lanes behind. `ProjectsPageRenderTest` pins the popup's half of
the same rule: a manager's create form has no lane input, no *Task lane* label and no lane-default sentence, and
it comes back the same way after a rejection, with its nine fields and their values intact. Stage 31's removals are tested against the same two sides: in
`TaskServiceTest`, the right to take a lane off is the right to name one, a lane id that is not on this
project's board — another project's, a deleted one, `0`, none at all — comes back `no-lane` with the lane it
was aimed at still standing, the cards that were filed in a removed lane survive un-filed on the same project
with the same title and the same owner, and a board whose last lane is the one being asked for answers
`last-lane` and keeps that lane, still draggable-into — rules that are still worth pinning because **Stage 74** put a
control back on the page that reaches `delete` (and a new route, `rename`, beside it). `TaskBoardRenderTest` pins the markup,
and since **Stage 75** its project-page half reads the other way round from Stage 52: `a project page is cut by its buckets and
carries no manager panel` asserts exactly one `data-lane` column per lane the project carries, each of the **three** names
standing as a `.kcol-title`, and no `class="kcol" data-status` anywhere on the board — a status is a card's own line and its
owner's one button, not a place on the page. **Stage 76** made that clause literal and **Stage 78** moved the line it made: a lane
card carries its own `<span class="kstat s-notstarted">Not started</span>` on a `kmeta kfoot` line of its own, beside its
holder's avatar and with **no `tcode` chip anywhere on that board**, and the case
`a lane board prints the stage and a status board leaves it to its column` walks all five statuses through it while
denying the general board a `kstat` anywhere — the word is printed once per board, by the card on a bucket board and by the
column head on a status board — and requires `class="kcard s-<stage>"` on the card itself at every one of the five, which is
the other half of Stage 78: the tint left behind Stage 76's binary rule and follows the stage on both kinds of board.
`a lane card carries its stage and its holder on one foot line` holds the split the other way, that the priority line above
the foot carries no avatar and the status board keeps its code chip — which since **Stage 88** names the card's project
(`S-01`) rather than its own `T-` number. The same test holds the add popup's live swap now that the columns *are* what it
edits: the page's single `/buckets/save` form — the `+ Add bucket` half of the column popup, since **Stage 79** deleted
the panel that carried a second copy of it — names `#projectBoard` as its target and `#taskLane,#projectLaneModal` as its
companions, so a column added there is a head on the board, a selectable **Sub-category** and a fresh set of the form's
own hidden twins in one redraw; the test asserts `id="projectBuckets"` and *Save buckets* are nowhere on the page and
that the save route is printed exactly **once**. **Stage 80**'s markup is pinned at both ends of the same popup:
`the colour a reader chose on the popup is the swatch checked when it comes back` in `ProjectsPageRenderTest`, with
`a column that was given a colour wears it and one that was not wears its position` beside it, and four
`TaskBoardRenderTest` cases hold the fold — that a column with finished work grows exactly one `▸ Completed N` line
and a column without grows none, that opening one column leaves the others' blocks `hidden`, that the opened column's
own press posts no `showdone` while a shut one's carries the open column along, and that the unfiled column and both
status boards print no fold line at all. Stage 52's
shape is not gone from the suite, it is just pinned where it still applies — `the general board is still the five status
columns` for `/app/assigned-tasks`, and `the status board has no lane control either` for the same page. The lane board is pinned
through `laneKanban` rendered **directly by the helper** (the retained
lane capability, not a page): on that board a card with no lane shows in the **Unsorted** column — which is the
only thing that
puts that column on a lane board at all (Stage 37). `the board prints Unsorted only while it has a card in it` renders
the same lane board before and after the one card is filed: one head more than the board has lanes, and a counting
**Unsorted** while the card is
unfiled, exactly the project's lanes and no `data-lane="0"`, no *Unsorted* word and no extra set of add
lines once it is not. The
width the column used to borrow from its neighbours is a stylesheet question, measured in the browser rather than
here: `.kanban-lanes` is a run of fixed 288px tracks, so a column arriving or leaving resizes nothing.
`/app/assigned-tasks` alone still renders exactly the five status columns, with no `data-lane` and no bucket control anywhere
in it — the popup included, since a `⋯` and the dashed **+ Add bucket** box print only beside a board that has buckets to
re-shape. A **project page** no longer shares that shape: **Stage 75** is what separated the two, and the denial each side
carries — `data-status` refused on the project board, `data-lane` and `/buckets` refused on the general one — is the pin that
keeps them apart. Stage 35's absence is pinned on the direct-helper lane board and for
every reader: an assignee's board, a manager's board
and a board that carries the `+ Add task` lines each print no `/buckets` action, no `lane-del` form, no
`laneName` field, no `#laneArea` target, no *New lane* label and no refusal line — and a lane head is matched
as a whole element holding one title span and one count span, so a control cannot be added back onto one and
slip past a substring test. The same check confirms the lane columns are still drop targets (`data-lane` on each,
the **Unsorted** one included) and that the add lines are still one per real lane, which is what says the
removal took the controls and not the renderer. Stage 34's
`+ Add task` lines are pinned the same way on that helper-rendered lane board: one per real lane and none on **Unsorted**, each a GET form onto this
project's page carrying `new=1` plus that lane's own `into` id and `#taskModal` as its swap target, drawn only for
the reader whose create would be accepted and never on the status board; and the popup built the way that line
builds it opens with *that* lane selected and its hint naming that column, while an unaimed create still lands on
the project's first lane.

The crossings between the two worlds (Stage 48) are pinned service-side, in `TaskServiceTest`, because what a move
has to prove is what the row looks like afterwards rather than which markup asked for it. A project card sent to
*Other tasks* comes back as an `OTHER` row with its project and its lane cleared, standing in the column the picker
named, and carrying everything that is not a board: its status, its person, both dates and its plan. The same proof
runs the other way for a backlog card brought onto a project — filed in the lane its **name** names, its backlog
column reset to `0`, and no longer counted by the column it left, which is the read the Other tasks page uses to
fill its boxes. A crossing that names nowhere to land is refused and leaves the row exactly where it was: no column
answers *"Choose which column this card sits in."* on the way in, no lane answers *"Choose which part of this
project the card is on."* on the way out, because a card that has left its board cannot be kept in a lane of it. An
owner who posts the whole popup with the board field set — the hand-built POST — gets their own writable fields
written and the board read from the row instead, so a crossing is management's alone in both directions. And the one
state this stage stops creating rather than forbids, an `ASSIGNED` row with no project behind it that prints on
neither board, is answered twice over: its own account is refused with the sentence that names who can fix it
(*"This card is on no project yet — ask management to choose one."*) with no other field touched, management naming
no project for it gets the create's own refusal, and management naming either world lands it whole there.
`TaskBoardRenderTest` holds the markup's half of the same rule: neither picker offers a blank choice that **files** a
card — *— No project —* is gone from the general board's popup and from a project page's, so nothing on a page can leave
a card on no project. Since **Stage 111** the general board's create does print a blank, but only as the question it is
still answering: it opens on *— Choose a project —* with exactly one selected option and a greyed Sub-category saying
*— Choose a project first —*, and every project option carries **its own** board as a `data-lanes` list — including a
board with nothing on it, which says *— This project has no column yet —*, and a name whose characters had to be
escaped, which must still parse back. A project page's create is the unchanged half: the page names the project, so it
opens on the first of the lanes **that** board has (`a new card on a project page lands on its first lane`), prints no
marker and no blank. A create that came back refused comes back **holding the project the reader picked**, that
project's columns listed, the borrowed name absent from the list, the field open and one `p.field-err` under its own
field — and a backlog create is not asked which project it is on. Then a card's own lane is printed, posted and read back
by **name**, with a project's
extra lanes standing after the three both on its board and in its picker. What Stage 75 moved is *whose* names those are:
the popup lists the three **and** the lanes this project added for itself, and a card filed in a lane the general board
never heard of still reads its own lane back. The refusal when a name is not on the project is the service's, in
`TaskServiceTest` — *a lane name that is not on this project's board is refused* — which is what makes a board that has
taken **Procurement** off safe: that name simply stops being one it will accept.

The **Other tasks** page is pinned by `OtherTasksRenderTest` (Stages 40 and 42, on its own page since Stage 47),
thirty-three tests over the board as the picture draws it. The shape half asserts what is drawn where: only the columns
that hold cards are on the board until the reader names one in the dropdown, the `+ Add bucket` control is a
**small button in the page head** — since **Stage 97** the only control the head offers, and not a box trailing the
board (Stage 59) — and every column head carries its own
`⋮` menu form — a GET onto this page naming that column by `bucket` id, with `#bucketModal` as its swap target, so
one mechanism serves however many columns exist. The popup half is the blunt one: rename posts to
`/app/other-buckets/<id>/rename` and carries the column's id in the route rather than in a field, the delete posts
to `…/delete` and names the column its cards are handed to (`moveTo`) with the card count printed in the label
asking for it, a refused rename comes back **typed** into `#bucketName` with the reason under the field rather than
the stored name, a refused delete comes back with the destination still selected, and a column holding nothing is
deleted with no destination offered at all. Two refusals are drawn out of the markup entirely rather than checked
by the reader: the last column on the board while it holds cards prints no delete form, and the
**Bucket** dropdown is a *view*, not a right (Stage 93): it is a GET filter form naming `#addBucketOpener` as its
companion block, so the head's button is redrawn with the columns the choice changes, and *All buckets* — option `0` —
is the absence of a filter and the way back. *(This is where the page head's **Show all / fewer buckets** toggle used to
be pinned: a control that swapped `#otherBoard` and named `#bucketsToggle,#bucketModal` as its companions, drawn or not
on the strength of `otherToggleApplies(showAllBuckets)` — the same read the board prints its columns from, so a button
never appeared over a board with nothing left to show. **Stage 97** deleted both the control and the predicate; the case
that replaces them reads the whole page and finds no `bucketsToggle`, no `buckets-toggle` and no `allbuckets` anywhere,
and a second one proves the empty column the toggle used to reveal in bulk is still reachable, one at a time, by naming
it in the picker.)* The card half asserts that a backlog card's own `kcard-open` form is what puts the
editor up (and since **Stage 103** the owner gets no status arrows with it — the case now requires the board print no
`kmove` for anybody), that a column's `+ Add task` line is a GET form naming
`#taskModal` and carrying that column's id as `column` — the card's popup, on that column, with no `bucket` field and
no text input in the adder — and that the bucket the reader is standing on rides with **every** write from the board,
inside that write's own `back` token (`other:<id>`) rather than in a view flag of its own: *no write on this page carries
a view state any more* reads a card's popup, a column's menu and a refused save for the retired `allbuckets` field and
finds none, and *a bucket that has just been named lands on its own filter* pins the one case the old wide view existed
for — a column holding nothing would otherwise vanish the moment it was created. Stage 42's five card-popup cases pin what that popup asks: a backlog create files the card
in the column it was opened from and prints **no project picker at all**, its Column picker has that column already
selected and its hint naming it; an ordinary account's create has *Assign to* and **Status** printed `disabled` and
pinned to themselves and **Not started**, with the sentence saying so and every other field writable; a saved
backlog card opens on its own column, offers the board's other ones, and gives a non-manager the picker alone; a
refused create comes back as the popup with the reason under the Column field and the typed title still in the title
box; and a card with one of two steps ticked prints `50%` on its own `#task-body-<id>` line while a stepless card
prints no chip at all. The role half is Stage 48's split, and the test is named for it: *a reader without management
names columns but cannot rename or empty one*. A reader without management is served the **add** half of the column
popup as a complete write — `#bucketModal` **as a node** (asserted through `elementWithId`, because the head's
`+ Add bucket` form itself carries `data-live-extra="#bucketModal"` on every page management opens, so a plain substring
test would pass on the attribute), the head's
`+ Add bucket` button, its `newbucket` opener, `/static/js/bucket-modal.js`, and the `formWithAction` that posts the name
with `name="name"` and `id="bucketName"` in it, because a box that could not focus its field would be a dead link.
The reader who notices the kind of work is the reader who names it. What stays management's is everything about a
column that already exists, and that half is asked for as absences on the board and across the page: no `kcol-menu`,
no `⋮`, no `/rename"`, no `/delete"`, no `bucketMove` anywhere, and no `Rename` printed in this reader's popup — while
a `?bucket=<id>` they cannot reach by clicking is answered with **Add a bucket**, with no rename form behind it, so the
markup never promises a write their routes refuse. The board underneath is the same board either way: `otherBoard`
prints, and both column headings and the empty-column message still print for them, because those
describe the board rather than a right — and — since Stage 42 — so does each column's `+ Add task` line: the adders are
there for them too, two of them, with the card popup on the page to open, because writing a card was never
management's alone. The editor on somebody's card still is: a card belonging to nobody, read by an account that does
not manage, prints no `kcard-open` form and no `kmove-btn` (since **Stage 103** no card on this board prints a
`kmove-btn`, whatever the reader's role). Two more cases belong to Stage 47 rather than to
the board: every opener on this page is a GET onto `/app/other-tasks` and no form anywhere in it aims at
`/app/assigned-tasks` except the shared card popup's own **Save**, whose hidden `back` field says `other` — or
`other:<bucket id>` when the board is filtered (Stage 93) — which is
how a card written here lands back here.
`AssignedTasksRenderTest` keeps the page's absence tests (**22** now, four when Stage 47 wrote this line): the page
prints its five status columns and no `#otherBoard`, no `#bucketModal`, no `⋮`, no dashed box, no `kanban-other`, and
not one backlog card's title; and its own popup carries no view flag, because the board underneath it has no view to
keep — which since **Stage 97** is simply true rather than doubly true, the wide view having been retired.

A project page prints no roll-up of its board (Stage 28 took the strip and the `TaskService.summaryOf` /
`ProjectTaskSummary` arithmetic behind it out of the codebase entirely — nothing else ever called them), so
what the suites now pin is the absence and the two figures that remain. `ProjectsPageRenderTest` renders a
project holding an open card, a colleague's card, an unassigned card whose due date the clock has run past and
a finished one with a step ticked, and asserts that not one of `project-summary`, `sum-tiles`, `sum-tile`,
`sum-key`, `sum-val`, `sum-cols`, `sum-col`, `sum-people`, `sum-empty` appears — while the board underneath
still counts its own columns (one `kcol-head` and one `kcol-count` per bucket the project actually has — the
**three** it opened with, since Stage 75 — plus the
**Unsorted** column that a card with
no lane brings with it), still shows all four cards, still prints the project's `End date` as the
project's own field, and still has `id="project-progress"` for a tick to swap. That render is a `Viewer`'s copy of
the page, and it asserts all four cards on it — **Stage 56**'s widening, which made the project page the one board a
plain account reads whole. What that reader keeps losing is the *rights*: since **Stage 86** every card opens, their
own into an editor and a colleague's read-only, and no figure about anybody else's work is printed anywhere on the
page. An empty one is described by the board's
heading and its own call to action, not by a strip. Two of that class's tests are **Stage 71**'s: one slices the
read-only strip out of a Viewer's page and pins nine `.ef` cells, nine captions in the historical order, eight
`title`-carrying values (the ninth cell is the bar), the pin form, and the absence of any `<input`, `<select` or
`field-err` inside that row; the other reads `app.css` and pins the row's guarantee — every `.detail-strip .ef`
weighted with a **zero flex-basis**, the first cell answering management's `:first-child` share with its own, and
both caption and value carrying `overflow: hidden` with `text-overflow: ellipsis` — because a cell that goes back to
a content-based basis is exactly the ninth-field-on-its-own-line bug returning, and that is cheaper to fail in a
build than to notice on a screen. `SidebarTaskCountTest`'s last pill test reads the sidebar number
straight off the reader's whole card set (`assignedFor(...).size`, every card counted since Stage 54, plus the reader's
own Other-tasks cards since **Stage 55**) instead of
against a strip, and
`TaskBoardRenderTest`'s two companion needles name the single `#project-progress`. The overdue rule itself is
not left untested — it lives, unchanged, in the due-date mailer's own suite, which is the place that still
acts on it.

**Stage 61**'s Settings panels are pinned on both sides too, and **Stage 75** re-pointed the bucket half of them at
the three. `SettingsRenderTest` holds that Project types, Email notifications and Bucket defaults print for management
and for nobody else; that the email *Send test to myself* button appears only for an **administrator** who has an
address on file, and is a live swap; that the bucket card prints the **three** opening lanes as the first three fixed rows of the category's list — a dot, a
name and *"Always on every board"*, with no remove form among them (**Stage 82**; before it they were a separate
`.lane-chip` row) — this page is not where one of the three is renamed or taken off, a board's own page is; and for a
category that has them, each extra lane as a dot and a name with
its own confirmed remove, every one of those forms carrying `?lanes=` so the answer lands back on the category being
looked at rather than on the first. Since Stage 75 the extra's dot is the lane's list position **offset by the three** —
the first extra is a new board's fourth column and wears that column's shade — and the add line says out loud, in its
own `bucket-add-for` span beside the button, *"Adds to &lt;Category&gt; only"*, because the whole point of the picker
above it is that a default belongs to exactly one category. Since **Stage 83** the test also reads the add form as a
name and a button: no `colour` field, no `.colour-row` — creation asks nothing the app can answer itself — while
`ProjectsPageRenderTest` keeps the six swatches pinned on the popup's **manage** half, which is now their only home.
The markup half of that promise is pinned too: the list
names itself (`#bucketDefaultsResults`) because Settings has two live regions, and the add line and the roster
toolbar's hidden category are named companions (`#bucketAdd`, `#bucketCategoryKey`) — the fix for a category change
that once moved the team roster while the add line went on posting the category the reader had left. `BucketDefaultsServiceTest` (a real SQLite file + Flyway)
holds the add rules — blank, over-length and a case-insensitive duplicate are each refused with nothing written,
trimmed and compared ignoring case so `"  produCTION "` is the same refusal — while the same word added to a second
category is accepted and both lists hold their own row; since **Stage 100** there is no fourth refusal, because
*Engineering*, *Procurement* and *Production* are catalogue names like any other and a category's first added row starts
at position **0**. It also holds the remove lifecycle and the `?lanes=` fallback to the first category,
including a hand-typed or stale one. `TypeDefaultBucketMigrationTest` proves V12 creates an empty four-column table,
leaves every existing project's lanes and cards untouched across a re-migrate and a second boot, and is not replayed.
`ProjectServiceTest` covers the payoff: the shape of a create's answer (its own fields, with `BUCKETS` back since
**Stage 100** as a refusal field only); a new project of a category with a catalogue opens with that **whole catalogue**,
in its own order, numbered from **0**; a create writes nothing to the catalogue; a category with no defaults **refuses the
create** until Settings lists one; and a bucket added to a category **after** a project exists reaches that board — the
Stage-61 case that pinned the opposite was replaced by that proof. `BucketDefaultsServiceTest`'s
second half (Stage 98) walks the fan-out on real boards: every existing project of the category gains the column and no
other category's does, a board already holding the name in another case gets no second column, the receipt counts 0, 1 and
2 boards, and a removed row leaves the live columns while the next project opens without it.

**Stage 63** moved the categories onto the table and the panels onto the picture, and both halves are pinned.
`ProjectTypeServiceTest` (real SQLite file + Flyway) holds the catalogue rules where they are actually enforced: the nine read
back in shipped order with `PROJECT_TYPES` and `TYPE_PREFIX` agreeing with the rows; `add` upper-casing the letter and trimming
the name, and the new category being immediately usable (`codeMatchesType("P-01", "Parts supply")`); eight refusals — a letter
already in use, an empty/two-character/digit letter, a blank name, a name over the limit, a case-insensitive duplicate, a
non-manager — each with nothing written; a rename carrying the label across `projects.type` and `type_default_buckets.type`
while `S-01` keeps its number; retirement refused while a project holds the label and allowed once it does not, taking that
category's default lanes with it; and an empty catalogue falling back to the shipped nine rather than leaving the app with no
category to file under. `ProjectTypeMigrationTest` pins the seed V14 has to write (nine rows, distinct letters, positions 0–8)
and that a category management changed is not replayed on the next boot. `SettingsRenderTest` pins the markup: every category
row carries both writes and each of the card's five forms names `#projectTypes` as its live target, so no press can reload the
Team sheet; the add line asks for a one-character letter and a bounded name; the pencil opens exactly one row as a field and
leaves the letter a badge; a refusal prints the heading *beside* the service's reason; an extra lane reads as a dot and a name
with a name-only add line; and the mail switch is a checkbox whose `checked` state is the setting, offered to an admin and
printed to a Manager as a read-only line. Six earlier assertions were re-pointed rather than loosened — the shared ✎/bin
classes are now counted inside the Team sheet, because a category row uses the same buttons by design.

**Stage 64** made the Calendar the reader's own month for every role, and `CalendarRenderTest` (6) is the page's first
test file. It opens with the guard that makes the rest mean something — the account under test really resolves to
management through `Authorization`, so a card being absent is the *rule* and not a lack of rights — then pins:
management's grid holding their own bar across each day it spans and never a colleague's; the scope line and the list
heading reading the identical sentence for a `Manager` and a `Viewer`, with the old team-wide wording asserted absent;
a card with no assignee on nobody's month; the list under the grid drawn from the same slice as the bars, including a
card with no dates (no bar, still a row); and a reader with nothing of their own keeping the grid and being told
"No assigned tasks yet."

**Stage 65** takes that file to 13 tests by pinning the month controls rather than the month's contents: the address
decides which month the grid draws; every step button carries the *absolute* month it names (`value="2026-11"`, never
"next"), so a link copied from one month lands on the same month for whoever opens it; the **This month** control
appears only when the reader is away from it; the jump box holds the month already on screen, so its own control and
the address bar can never disagree; a word that is not a month falls back to this month instead of failing; the whole
month view — heading, strip and grid — sits inside one `#calendarMonth` live region, and the task list underneath it
deliberately does not, because that list is the reader's workload rather than their month; and a bar whose span begins
in the month before still prints its title on the first day that is *visible*. The one rule both pages share is tested
where it is decided: `ForecastServiceTest` asserts that a question carrying two answers believes the control that was
acted on.

**Stage 66** takes that file to 24 tests, and they are the month's contents rather than its controls. The hole first:
a backlog card with a real start and deadline now draws bars across exactly the days it spans, and the strip's count
follows it — asserted as *one card dated here* rather than three bar fragments, because the count is of cards, not of
days — while an undated backlog card keeps its row under the grid and gains no bar. Widening the boards is not
widening who they are read for, so a colleague's project card and an unassigned backlog card are both asserted absent.
Then the colours: two projects in one month arrive in two distinct hues and one project's days all carry the same one;
a backlog card wears its *bucket's* hue, and two buckets do not share one; priority is a dot inside the bar, which is
pinned by the old priority fills being gone from the markup as well as the three dot classes being present. The key
under the strip is held to its own rule — it names the groups only when the month holds more than one, and a month
with a single group still explains the dots. The palette is assigned over the whole workload and not the month's
slice, which is what the "hues do not move when the month does" test buys: one card dated in both months has to come
back in the same hue on both pages. The list's new *Where it lives* column, the page's own sentence about which boards
it reads, and the popup's `Other tasks · <bucket>` line are each pinned once. And because two of the rules that keep a
long backlog title from pushing the month out of its panel live in CSS where markup cannot see them, one test reads
`app.css` and asserts the grid is `table-layout: fixed` and the bar's label carries `min-width: 0`.

**Stage 67** takes `TeamPageRenderTest` to 17 tests, and they pin what a row's numbers are *made of*. A member holding
two project cards and three backlog cards has to print `[2, 0, 1, 1, 1, 5]` — the backlog cards included, which is the
ask — and the colleague who **manages** one of those projects while holding no card at all has to print six zeros, which
is the older fault pinned shut: the columns used to count managed projects, so a manager's workload row described other
people's work. A second test states the arithmetic the row promises: the five status figures add up to **Total**, which
holds because every one of them is now counted from `memberTaskRows`, the same list the row's own drill-down prints. A
third reads the seven `<th class="num">` headings in order, so the rename of **Ongoing** to **In progress** cannot
quietly revert to a word no card can hold. The fourth is the readership: a `Viewer` gets seven dashes on a colleague's
row and their own figures on their own. The existing Viewer test was moved onto the new markup rather than relaxed — its
dash count went from one-per-row to seven-per-row.

**Stage 68** takes `TeamPageRenderTest` to 19 tests and turns that fourth one inside out, because the ask was that every
reader see those figures. The dash test is gone with the dash: `a viewer is given a colleague's figures and not their
cards` now asserts a colleague's six figures print exactly what management sees, that the row carries a `tasks-static`
number and **no** `tasks-toggle`, and — the half still gated — that the popup JSON slice for that colleague holds zero
task titles while their own slice holds one. Two helpers make that assertable: `popupSlice` lifts one member's object out
of the `memberDetailData` island (the island is one long line, so it is sliced, not regexed across), and `projectCount`
reads the new column. The other new test pins **Projects** to its definition rather than to a count: cards on two
projects run by others → `2`, a project managed with no card held → `1`, two backlog cards and no project → `0`, a
project both managed and worked → `1` and not `2`. Its last assertion is the one that matters for the page's honesty: the
figure on the row equals the length of the project list in that member's popup, checked for a reader who cannot see any
cards, so the number and the thing behind it are held to one source. The heading test grew to eight `num` columns
starting **Projects, Tasks**, and the two role tests now count toggles, statics and sub-rows.

**Stage 69** takes `TeamPageRenderTest` to 21 tests, and they pin a behaviour that lives in a `.js` file, which no render
test can execute. The first places the hook: exactly one `id="teamSheet"` on the page, the `<table>` and every figure
inside it, and the assign panel **outside** it — read as a `<div>`/`</div>` balance over the slice between the two, not as
an index, so a panel left unclosed or a form swallowed by the swap both fail. The second reads `team.js` off the disk the
way the board's stylesheet is read: it knows the panel by name, listens for `pageshow` **with `persisted`** and for
`visibilitychange`, fetches with `cache: 'no-store'`, carries `openDrills` across the swap and re-`bind()`s — and
`assertFalse(js.contains("location.reload"))`, because the answer to a stale number must never be a page that restarts.
New `ResponseCachingTest` (3 tests) is the other half, and it is where the header is held: `isServerDrawnPage` answers
yes for `/`, `/login`, `/logout`, `/app` and every `/app/…` (the pages whose bytes are only true for the instant they were
drawn), no for `/static/**`, `/files` and `/favicon.ico` (the same bytes for every reader, where caching is a win and
correctness is not at stake — `/favicon.ico` is a path left outside the `no-store` guard, not a file this app serves: it
answers 404, and since **Stage 112** every page names `/static/assets/favicon.png` instead), and the third test pins the *emission* in `SecurityHeaders.kt` by position — the guard, the
`call.request.path()` it runs against, and the `Cache-Control: no-store` it sends, inside a window short enough that a
header moved out of the guarded block fails. It started as a regex and was replaced for a reason worth keeping: matching
`if (isServerDrawnPage(path)) {` needs the two closes of the call *and* the one from `if (`, and the `\s*\{` that follows
them never matched — position arithmetic reads that shape honestly where another backslash would not.

There is still no HTTP-level test: the alert endpoints and the management-only gates, like every other
route, are verified by booting the shipped distribution and driving it with `curl` (see the launch
checklist) — for the gates that means one throwaway database under `%TEMP%` on a spare port (8095–8099, `PTS_DB_PATH`
always passed explicitly, plus `PTS_DATA_FILE` pointed at a path that does not exist so the real legacy JSON is never
read, and the boot banner's `dbPath` read back before anything is asserted), with one *Employee* and one *Administrator*
account filed on **Access control** — since **Stage 110** that is two roles, not two ranks, and the sheet's own table is
part of what is being proved — checking that the *Employee*'s `POST /app/projects` comes back redirected with nothing on
the board, and the same for a `POST /app/forecasts/save` that should not have been accepted or a `POST /app/lessons/delete`
on somebody else's row. Two harness traps this file has been bitten by twice: a gate's whole answer is the **`Location`
header**, so a GET that is not captured with `-D` leaves the *previous POST's* redirect in the variable and every route
check after it passes for the wrong reason; and a `python -c` one-liner inside a double-quoted PowerShell argument cannot
carry quoted SQL literals, so the thirteen permission keys arrive as bare column names — bind `?` placeholders instead.

## Project layout

```
src/main/kotlin/mn/murray/pts/
├── Application.kt              routes, plugins, session guard, startup (incl. the alert scheduler)
├── <Page>.kt                   one kotlinx.html view per page
├── config/AppConfig.kt         every tunable, in precedence order
├── db/                         DatabaseFactory, Migrations, Exposed tables
├── db/migration/               JsonImporter, LegacyNormalizer, DemoSeeder
├── domain/                     plain model classes — `AccessRole.kt` is the thirteen `Permission`s and the
│                               `AccessRole`/`AccessRoles` the role catalogue is built from (Stage 110)
├── repository/                 SQL access, one object per aggregate (+ AlertRepository,
│                               + NotificationRepository, + RoleRepository)
├── security/                   PasswordHasher, Authorization (the role's chips answer `canDo`/`canAny`;
│                               the old three-rank `Role` enum is gone — Stage 110), Csrf, LoginRateLimiter
├── service/                    AuthService, the write-side services, and Mailer +
│                               DueDateAlertService + DueDateAlertScheduler +
│                               NotificationService (who hears what, and where)
└── web/plugin/SecurityHeaders.kt
src/main/resources/
├── db/migration/V1__init.sql   Flyway schema
├── db/migration/V2__due_date_alerts.sql   the alert ledger
├── db/migration/V3__project_categories.sql   client/start/revenue + the nine categories
├── db/migration/V4__notifications.sql    the bell's inbox, keyed once per person per day
├── db/migration/V5__task_checklists.sql  the steps under a task card
├── db/migration/V6__task_notes.sql       the log under a task card
├── db/migration/V7__project_task_buckets.sql   a project's lanes + the backfill that seeds them
├── db/migration/V8__forecast_ledger.sql        the monthly money ledger, one row per project per month
├── db/migration/V9__lesson_record.sql          the lesson sheet's six columns + the title/summary backfill
├── db/migration/V10__standard_project_lanes.sql   the six shared sub-categories on every project board
├── db/migration/V11__project_invoiced.sql         the invoiced flag the detail card's own save writes
├── db/migration/V12__type_default_buckets.sql     the per-category bucket catalogue, seeded empty
├── db/migration/V13__app_settings.sql             the outbound-mail switch and the other app keys
├── db/migration/V14__project_type_catalogue.sql   the project types, off the hardcoded list and onto the table
├── db/migration/V15__checklist_completion_memory.sql  the step's own tick, remembered across a card's reopen
├── db/migration/V16__three_phase_buckets.sql      every board onto Engineering / Procurement / Production, editable
├── db/migration/V17__bucket_colour.sql            one nullable colour_slot on each bucket table, backfilled with nothing
├── db/migration/V18__task_archive.sql             the archived flag and its three snapshot columns on tasks
├── db/migration/V19__project_status_in_progress.sql  Ongoing -> In progress on projects, the word left off everywhere else
├── db/migration/V20__project_currency.sql        one currency column per project, defaulted to MNT
├── db/migration/V21__project_roles.sql           the role catalogue + the backfill that files every account on a role
├── logback.xml                 console + rolling file appenders
└── static/{css,js,assets}/     extracted front-end assets (CSP: no inline)
```

## License

The repository carries an MIT `LICENSE` ("Copyright (c) 2026 MURRAY Mining Services Mongolia"),
inherited from the v1 scaffold. For an internal tool holding employee records, decide before
publishing whether that is what you want — an "unlicensed / internal use only" file is the usual
choice, and I left the existing one untouched.
