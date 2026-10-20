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
(defaults to **Not started**), revenue and a manager — nine fields, and nothing about the board.
The manager is optional, but it is the only place
to name one: Stage 30 removed the picker from the project page, so a project created without a manager
runs without one until management makes it again.

A project arrives with a board already cut: **Fabrication/Manufacturing**, **Repair/Maintenance**,
**Site projects**, **Labor supply**, **Goods sales**, **Other**, in that order — the six sub-categories
every project is planned under, listed once as `DEFAULT_PROJECT_BUCKETS` (Stage 48) so the same six names
stand on every board. They are written in the same breath as the project row, because every card written onto a
project files itself in one of them (see [The project page](#the-project-page)). The
create popup asks nothing about these names (Stage 33; Stage 32 briefly put three lane inputs in it and the
user asked for them back out), and neither does the page any more: Stage 35 took away the *Add lane* box
under the columns and the **×** on each column head, so those six **are** the sub-categories a project starts with.
Since Stage 52 the project board's columns are the five statuses, not these lanes — the six are what a card is filed
under in its editor, no longer what a column is made of. A project keeps any
lane its manager added on top of them — the six are a floor, not a ceiling — and those print after the six,
in the order they were added. `TaskService.addLane` and `removeLane` keep their
rules — a name trimmed and bounded to 40 characters, no two lanes of one name on the same board ignoring
case, cards un-filed rather than deleted, one lane always left — and since Stage 48 neither can take one of
the six away (`LaneError.STANDARD`, checked before the last-column rule). `POST /app/projects/{id}/buckets` with
its `delete` twin stay wired and management-only, but nothing on a project page posts to them. The
`V7__project_task_buckets.sql` migration first gave lanes to the projects that already existed, and
`V10__standard_project_lanes.sql` brought those boards onto this list: an old default holding no card is
deleted, the six take positions 0–5 by name, anything else on the board stands after them, and **no card is
touched** — a lane with cards in it keeps its name and its cards rather than being dropped and un-filing
them. The legacy JSON importer writes the six for every project it imports, so no kind of project opens on a
board nobody built.

Creating a project, editing or deleting one, choosing who manages one, and deleting a card are **management-only**:
a `Viewer` sees neither the `+ New project` button nor the popup nor an editable header strip, and `POST /app/projects`,
`POST /app/projects/{id}` (a header-strip save), `POST /app/projects/{id}/delete`,
`POST /app/projects/{id}/manager` and `POST /app/assigned-tasks/delete` turn a
hand-built request from a `Viewer` away before the form is read. Adding a task is management's, with one
narrow exception: **the account a project names as its manager may write the first card for that project,
from that project's page**. `TaskService.mayAddTasks` compares employee numbers against
`projects.manager_emp_no` — never the manager's display name, so a rename cannot move the right to a
namesake — an unmanaged project grants it to nobody, and the general board grants it to nobody but
management. The card they write is pinned to the page it came from: its project picker is one greyed option,
and `TaskService.create` reads the project from the page rather than from the form, so "add to *this*
project" cannot be turned into "add to any project" by editing the POST. **Stage 49** widened that one exception to
the whole of a project's board: the same `mayAddTasks` answer is also who may **move a card between that
project's lanes** — a drag, and the drop it posts (`TaskService.moveToLane`) — so a project's manager files its work in a different column
without being management. **Stage 52** later cut the project board to *status* columns, so a lane drag is no longer
what that page shows: the `moveToLane` right and the lane renderer stay (the popup's Sub-category filing and the
lane tests still read them), but on the project page itself a drag now moves a card along the five statuses, exactly
as on the Assigned board. What stays open to everybody is the rest of their own work: **arrowing** a card they are
assigned to along the statuses, ticking the steps on it, and their own To-do lists. Moving is no longer on that list,
on either board — the assignee who is not also a mover prints no `draggable` and cannot put a card in a lane or a
column it was not already in. The **Other tasks** page is now management's whole function (Stage 51): its `+ Add task`
line, its head `+ Add bucket` button, its `⋮` and its show-all toggle, and the sidebar links to it and to **Access control**
and **Settings**, all belong to management (`Authorization.canManage`) and are withdrawn from a `Viewer` — the link, the
page and every write behind it are gated, not just the button. The one personal path a `Viewer` keeps: their own
backlog card, which they still reach here through a notification, on a board scoped to nothing but their own cards and
with none of the column tools. Handing work to a person — writing a card pinned to them and started as **Not started**
— is management's, done from the **Team** sheet's Assign panel; see [Other tasks](#other-tasks).

An empty board says what is missing instead of showing a row of empty columns of "Nothing here.", and says it only to
somebody who can fill it: when the cards this reader can see number zero and they may write one, the board
prints a `.board-empty` block with the sentence about where the card will land and the same `+ New task`
opener the page head carries — one renderer, one mechanism, no new script. A `Viewer` who manages nothing
gets neither the block nor an opener. **A project page is the one whole-project board** (Stage 56): every card filed
under that project is shown to every reader — management, the project's manager *and* a plain `Viewer` alike — because
standing on a project the question is *what does this project hold and who is doing each card*, which one person's
slice cannot answer. That widening is the **list only, never the rights**: a `Viewer` reads a colleague's card and the
holder's avatar beside it, but the card still carries no click target, no drag handle and no editor, exactly as any
other reader's card does here. Their **own** cards on that same board still open and walk for them. Every other task
view — the general Assigned board (personal, Stage 53), the sidebar count, Calendar, Team — stays scoped to the reader
as it was.

The board has one way in and it is the card itself: clicking its body asks the page for that card's
editor (`?edit=42`) and `app.js` swaps the popup that comes back into `#taskModal`, so opening an editor,
and reopening it after a field is rejected, never reloads the board. `+ New task` is the same mechanism
aimed at a blank form (`?new=1`). Both are GET forms onto the page they sit on, and a board prints exactly
one hidden editor form for the whole column set, whose `edit` field the click fills — which is why forty
cards still ship one set of fields. Stage 24 deleted the menu this used to go through: the `Edit` button
and the status dropdown that used to hang off a card are gone, because a click on the card was already the
way to reach the editor, and a column is a field of that editor rather than a thing to choose separately.
Cards you cannot open — a colleague's, for anybody who is not management — carry no click target at all.
Management edits any card and every field on it; the person a card is assigned
to opens the same popup and may change its start date, due date and priority, while the title, the
project, the lane, the assignee and the status print greyed and are taken from the stored row rather than from the
form, so a hand-built POST cannot reassign a card through it. A greyed select posts **nothing at all**, which is
what lets the save tell "this reader may not answer" from "this reader left it blank": a card with no `lane` field
in its POST keeps the lane it was in, and one that posts a blank lane is refused. Deleting a card still has **no button
anywhere in the app** — the route (`/app/assigned-tasks/save` with a real `taskId`, `/app/assigned-tasks/delete`)
is unchanged and still management-only, so the action is unavailable rather than permitted. Say so before
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

The popup travels with every board where somebody can open one, and it is drawn for the reader standing
there: a `Viewer` with no rights of their own gets a node whose four greyed pickers list only their own
card's project and assignee rather than the whole roster and every project name, and the manager of a project
gets the blank create they are actually entitled to — title, holder and column open, the project printed once
and greyed, the roster present because assigning a card to a colleague is the write they just asked for.
The node is there for `app.js` to swap into, not to be read; a page where nobody can open anything prints
none.

The number is editable, not auto-assigned: the form offers the next free one for the chosen category
(`S-10` when Site projects run up to `S-09`), switching category replaces an offer you have not
touched but never overtypes something you typed. On submit the app rejects a number that is already
used, that does not lead with the category letter, or that is malformed (`S-01`, `R-12` and
`M-2026-A` are fine; `SIT-01` is not), and each problem is reported under its own field with the
popup reopening on the same page.

The board prints one table per category panel, and every one of them is laid out on the same
ten-column grid: ID, Project, Client, Status, Progress — the average of that project's card checklists, or
the figure filed on its row where none of its cards has any — Manager, Start date, End date, Revenue and the
pin button sit at the same x in every group, with Revenue right-aligned and the two dates centred so the
digits stack down the page. The ID is the one cell printed larger and bolder than the rest of the row, in the
same three places a project's number shows (the board, a project page's head, an account's project line),
because it is what people say out loud; the smaller chip is kept for employee numbers elsewhere. The widths are percentages that sum to 100, so a narrow window wraps a long
cell instead of pushing the table out of its card — and that is how the shares were split: a date must not
break in half, so both date columns keep a fixed 9% and the free-text columns (Project, Client, Manager)
are the ones that give room to the tenth column and wrap instead. A project with no recorded start date
prints `—`.

Above the tabs sits the search bar with its **Search** button directly beside it, and then the two sort
pickers — one for Status (**Not started first** / **Completed first**) and one for Manager
(**A → Z** / **Z → A**). They are all
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
attached to it, **revenue** is a single amount with no currency, in the company's reporting unit, and
**invoiced** (`projects.invoiced`, V11) is how much of that has actually been billed to the client — it
defaults to `0`, is read exactly like revenue, and is entered by management on the project's header strip.

## The project page

Opening a project prints its header and, below it, that project's own task board. **For management the header is an
editable strip** (Stage 60): the top line is a `Projects / S-01` breadcrumb with a **☆ Pin for me** toggle, a small
**copy-link** icon button (📋 — it copies the page's address to the clipboard, no navigation), and a **Delete project**
button at the right. Under it runs **one flat, edge-to-edge row** of UPPERCASE-labelled fields — **Description**,
**Client**, **Type**, **Start**, **Due**, **Status**, **Project manager**, **Revenue**, **Invoiced** — each a borderless
input or picker (only the native select caret and the date's calendar glyph show) whose refused message prints small at
the field; **Revenue** and **Invoiced** show comma-grouped ("5,000,000", "0"), and **Progress** reads as a plain
percentage with no bar, then a single **Save changes** button. **For a `Viewer` the header is the read-only card it has
always been** — the same nine `CAPTION value` fields in one wrapping run, its Revenue/Progress bar intact, no form, no
strip, no delete. The split is by role, never by field: what a Viewer may see, they see exactly as before; only
management gets to change it.

Two of those header fields show a value but are not something you can type. The **project number is never editable** —
the strip prints it in the breadcrumb and the save form sends no `code`, so an existing project keeps its number however
you edit it. **Progress is derived**, not a box: it is the average of what the board below has reached, so it has no
input and prints as a plain percentage (no bar on management's strip); the cell keeps its `project-progress` id, and
ticking a step on a card still moves that figure in place without a reload. **Invoiced** is new (schema **V11**,
`projects.invoiced`): the amount actually billed to the client, entered here by management and stored as a plain number
read the same way Revenue is (the comma-grouped text is stripped back to a number on save). It is *not* asked when you
create a project — nothing is billed before the project exists. **Delete project** is management-only and asks once ("Delete <code>? Every
task filed under this project goes with it. This cannot be undone.") before removing the project **and every card filed
under it**; a hand-built POST to `/app/projects/{id}/delete` from a non-manager is refused.

The board's columns are the card's **status** — **Not started**, **In progress**, **On hold**, **Blocked**,
**Completed** — the same five the general Assigned tasks page draws (**Stage 52**). The reader asked to see a
project's work "in progress, not in sub-category", so the page that used to cut its columns by sub-category (Stage
30) now cuts them by how far along each card is; a card's owner drags it between these columns the same way they
drag on the Assigned board, which posts `/app/tasks/status`. Every new project still starts with the six
sub-categories — **Fabrication/Manufacturing**, **Repair/Maintenance**, **Site projects**, **Labor supply**,
**Goods sales**, **Other** (Stage 48) — and a card **written onto a project is still filed in one of them as it is
written**: the popup's **Sub-category** field is asked on every create and a blank answer is refused with *"Choose
which part of this project the card is on."* What changed is only that the sub-category is no longer what the columns
are made of — it is set in the card's editor, not by which column it sits in, and the board prints no lane head.
The create popup asks nothing about the lane names (Stage 33) and the page lets nobody change them (Stage 35): there
is no box under the columns naming a seventh and no **×** on a head taking one off, management included.
A lane a project has outgrown, or one a project gained while the box was open, is therefore not this page's to fix:
`POST /app/projects/{id}/buckets` and its `delete` twin still answer a hand-built request from management and still
apply the rules they were written with — cards stay standing when their lane goes, a board never loses its last
lane, and none of the six can be taken off at all (Stage 48) — but nothing on the page sends them anything, and the
refusal codes they redirect back with (`duplicate`, `too_long`, `last_lane`, `no_lane`, `standard`, `denied`) have
no sentence printed from them any more.

**In progress** is back on the page as a column head (Stage 52), the way it always was on the Assigned board. A
card's sub-category still travels with it behind the popup; the board simply no longer groups on it, and no card
prints its lane word on its face.

Three things are deliberately not on this page any more. The **"Project manager" panel with its Set manager
picker is gone**: there is no second control at the bottom of the page writing the manager the card header already
shows. Since Stage 60 the manager is instead chosen in the editable header strip itself — one field, one place to set
it — and `POST /app/projects/{id}/manager` still exists and is still management-only, but nothing here posts to it; the
strip saves through the ordinary `POST /app/projects/{id}` update route. The ***Add lane* box under the columns and the `×` on each column head are gone** (Stage 35),
and `POST /app/projects/{id}/buckets` with its `delete` twin are in the same position the manager route is
in — still there, still role-checked, nothing on the page reaching them. And the page prints **no roll-up
strip** above the board — see below.

On the **read-only card a `Viewer` sees**, all nine fields are the same kind of cell: `CAPTION value` on one line,
laid out in one run that wraps —
**Project number**, **Category**, **Client**, **Manager**, **Start date**, **End date**, **Status**, then
**Revenue** and **Progress** (Stage 29). None of them has a row or a column of its own; the last two take
whatever room is left on the line the others end on, so a wide card puts them beside **Status** and a narrow
one moves them down like any other field. Revenue and Progress travel as one unit inside that run, so a card
with room for the amount but not for the bar moves both down rather than leaving the bar alone; they only
split when the card genuinely cannot hold both. Used to be that the seven short fields were label-over-value
cells and Revenue and Progress stacked their values under their captions — the ask was for the two to read
like the seven, not like a section.

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
with a card on that board, so a `Viewer` sees the whole project but can open, drag or edit only their own cards. (Until
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
is not a reload either:
`kanban.js` posts `POST /app/tasks/bucket` and moves the card node itself, recounting both columns. A lane the
last card leaves stays on the board — an empty lane is still one of the project's lanes — but **Unsorted** is
not: it is a bucket for cards nobody has placed, so the column goes when its last card does (Stage 37), and since
Stage 48 it is not a drop target at all. Neither
case moves anything else, because a lane column is 288px wide whether the board prints six of them or nine.
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
re-fetched when a lane name was typed or a **×** accepted, went with both controls (Stage 35), so a lane's
head is now just its name and its count and the columns a reader sees are the columns the page was built with.

## Assigned tasks and checklists

A task card is a piece of work one person owns, and only two people have a claim on it: the assignee and
management. That split decides what each of them can see and do, and it is one rule applied everywhere a
task appears — the cards on a project page, the Calendar and the per-person drill-down on the Team page. A
`Manager` or `Admin` sees the whole board there, because seeing everyone's work is what that role is for. A
`Viewer` sees only the cards assigned to them. There is no setting for this.

Two places, though, are deliberately personal for **every** reader regardless of role, and both are the same thing:
the **Assigned tasks** board and the number beside it in the sidebar. Since **Stage 53** that board answers one
question — *what is on me* — and never a team-wide list, so a `Manager` who opens `/app/assigned-tasks` sees only
their own cards, exactly as a `Viewer` does, and the sidebar pill counts the same reader's own cards. This is a
change to *who appears*, not to *who can act*: management keeps `+ New task`, the full editor and the drag on that
page, and loses nothing about running the team, because the whole-board overview they used to get there lives on the
Team sheet, the project pages and the Calendar instead. **The project page is the one deliberate exception to that
personal scoping (Stage 56)**: clicking a project shows *every* card filed under it to *every* reader — management, the
project's own manager, and a plain `Viewer` alike — cut into the same five status columns and read like the Assigned
board, each card carrying its holder's avatar so the page answers "what does this project have and who is doing it".
That widening is the **list only, never the rights**: a `Viewer` reads a colleague's card but cannot open, drag or edit
it — those still hang on ownership and role exactly as before, so their own cards are the only ones they can act on.
Nothing else about the personal rule moves: `assignedFor` is unchanged, and the Calendar, the Assigned board and the
Team drill-down stay scoped as they were.

The sidebar number beside *Assigned tasks* counts **every** card on that reader's board — the **Completed** column
included. Since **Stage 54** the pill and the board agree exactly: it answers *how many tasks are assigned to me*, and
a finished card is still one of those, so nothing is subtracted and the figure no longer falls as work closes. (Until
Stage 54 this count left the Completed cards out so it would always speak about today; the reader asked for the whole
number instead.) What *does* still separate an open card from a finished one is the Completed column itself and the
board's own per-column counts — and the **Team sheet's OPEN TASKS** figure, which is deliberately a count of *open*
work only, because there the question is what a colleague still has outstanding. `TaskItem.isOpen` is where that
open/finished line is drawn for those, and it is the same property the Team page reads when it counts and sorts a
colleague's cards, so one card cannot be finished on one page and outstanding on another.

Since **Stage 55** the *Assigned tasks* page answers a second question below its own board: *what else is on me, outside
the projects*. A reader can hold backlog cards — work assigned to them on the **Other tasks** board, which belongs to no
project — and Stage 51 took that board's sidebar link away from Viewers, so those cards had nowhere left to surface for
them. The assigned page now prints the reader's **own** Other-task cards in their own status board under an
`Other tasks` heading, and when they hold none it says so plainly: *You don't have any other tasks assigned.* This is
scoped to the person reading, never the shared board — management's whole-backlog view stays on the Other tasks page.
The two boards share one editor popup and one opener, so a backlog card clicked here opens the same editor and, on save,
returns to this page; and the sidebar number grew to cover both, so the pill beside *Assigned tasks* is still exactly the
cards this page prints.

The Team page follows the same line. The roster — name, department, position, email — is for everybody,
because knowing who is on the crew is not confidential. What is confidential is what they are working on:
the task list under each row, and the `+ Assign a task` panel, are management-only. A `Viewer` can still
open their **own** row and see their own cards there. That panel can write a card onto **either board**, since
Stage 50: it carries the same **Where it lives** picker the card popup uses — *On a project* or *Other tasks* — and
the half it is not standing on is only put away, never removed, so one POST answers both. On the project half it asks
a **Project** and, from the six sub-categories, the **Sub-category** the card is filed in; on the Other-tasks half it
asks a **Column** from the backlog's own columns, opened on *— Choose a column —* because a card filed on no column of
the only board that prints it is work gone missing. The picker's default is the project half — the card the panel has
always written — so a hand-built POST that names no `board` is the ordinary project card, and it is the service, not the
`required` attribute, that refuses a project card naming no project or an Other card naming no column. (The two project
pickers are deliberately **not** `required`: a `hidden` box is still validated by the browser, and `required` on the
half the reader cannot see would block the submit of the half they can.)

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
checklist percentage: the steps are the measurement, so no separate "completed means 100" rule was invented.

Stage 22 took the writing out of the board, and **Stage 41 took the list out with it**. A card now prints one
number about its plan and nothing else: the `%` chip on its own line, no `Checklist` heading, no step rows, no
tick boxes, no × — on every board, for every reader, including the person the card belongs to. The plan itself
lives where the card's other details live: clicking the card opens the popup, and the popup holds the steps,
their boxes, the `n of m done` count and the line that adds a step. One card therefore looks the same to
whoever is looking at it, and the boxes a reader earns by owning the card arrive on the click they already make
to do anything else with it. Ticking follows the rule it always followed — `TaskService.toggleChecklistItem`
accepts the assignee and management — and the popup now asks the server who is looking rather than guessing:
`TaskForm.viewerEmpNo` is set from the session by the route that loaded the card, and `TaskForm.mayTick` is the
same set of readers `TaskService.editableBy` hands a card to, so anybody who may tick a box is shown one and
nobody who cannot is invited to click a refusal. Moving a card between columns keeps the shape it always had —
management may move any card, anybody else only a card assigned to them.

Stage 23 put the *writing* of those lists inside the editor popup, and Stage 41 made that popup the only place
any of it is. Opening a card shows its plan as it stands — `3 of 7 done · 43%` — and each step is now a row of
box, text, ×: a real tick box on the left where the old ✓/· mark used to sit, the step's words beside it, and an
× to take the row off, and under the list the one-line `New step…` box: type the step, press **Enter**, and it is
on the card — that line carries no button, because the box is the whole control. Writing a plan is
management's and ticking it is not, so the two are printed on their own rights: `/app/tasks/checklist/add` and
`/checklist/remove` still refuse anybody else, which is why the × and the add line render only on
`TaskForm.canEditAll` while the boxes render on `mayTick`. An assignee who opens their own card therefore gets
every box live with no × and no add line, and management gets all three on anybody's card. The log underneath
keeps its **Add note** button, since Enter inside a note means a new line.

Under the `3 of 7 done · 43%` line, before the rows it counts, the popup draws that figure as a bar too — a small
track filled to exactly the card's own percentage, two steps with one ticked filling it to half. It is the one
progress bar the application has (`.progress` holding a `.bar`, the pair a Projects row and a project page's
Progress cell use), so the card's measurement looks like every other measurement on the site rather than starting
a second style. It lives inside `#task-steps-<card>`, the block a tick already swaps, so the fill moves in the
same response as the box and the number above it — nothing to reload, and no bar left holding yesterday's figure.
A card nobody has planned gets no bar at all: an empty plan is not a plan sitting at zero, which is the same rule
the card's percentage chip obeys.
Below the plan is the **log** — notes appended against the card, newest first, each signed with its
author's name and the day it was written, capped at `TaskService.NOTE_MAX` (500 characters). Management
and the assignee both write it; only management can take an entry off, so a record cannot be quietly
rewritten by the person who made it. Both sections save without closing the popup or reloading the board:
each form names its own block (`data-live-target="#task-steps-42"`), the response returns that block
rebuilt, and `app.js` puts the caret back where it was — which is what lets a list be written one line at
a time. A step, a note or the card's own `?edit=42` redirect is what puts the popup back after such a
save; only the popup's **Save** button and a card's move to another column navigate, because `kanban.js`
binds its
drag listeners per card node and swapping a board out from under them would leave the columns
undraggable.

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

## Other tasks

`/app/other-tasks` is the backlog: cards that belong to no project, grouped into **buckets** — from the `buckets`
table — and drawn as a vertical run of bucket **groups** (`div.other-groups` with `id="otherBoard"`). Each bucket
prints its **name as a heading on top**, the way a project page prints its project name, and directly under that
heading its cards are cut by the **same five status columns** a project board uses — Not started, In progress, On
hold, Blocked, Completed (Stage 57). So a bucket is now read by *where its work has got to*, exactly like a project,
rather than by sitting in one nameless column; which bucket a card is in is said by the heading over it, never by the
column it stands under. It became its own page at Stage 47. Before that the same board sat at the foot of the **Assigned tasks** page, and
`/app/other-tasks` printed a flat read-only list of those cards beside it — two ways of reading one set of work, one
of them a dead end. Now each page holds one board: Assigned tasks prints its five status columns and nothing else,
and the list is gone. The page head carries the name and the sentence that says what the work on it is *not*
(**Other tasks — tasks outside of projects**), with **Show all buckets** at its right, which is where the board's own
repeated heading used to be.

This is a **management** page (Stage 51): the shared backlog and everything you do to it — naming a column, adding a
card, renaming or emptying a column, showing all the columns — belong to management (`Authorization.canManage`, i.e.
Manager or Admin), and the three links that reach it (**Other tasks**, **Access control**, **Settings**) are dropped
from a Viewer's sidebar. The one path kept personal: a Viewer whose card is on the backlog can still reach it here,
so a Viewer hitting `/app/other-tasks` — from a notification, say — gets the board **scoped to their own cards**:
exactly the bucket their card stands in — its heading and its five status columns — and none of the others, with no
add line, no `+ Add bucket` button, no `⋮`, no
bucket popup, and no show-all toggle; their own card still opens in the editor and still carries the owner's status
arrows. The routes check the role again: the `+ Add task` create popup is not handed to a non-manager, `POST
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
status, exactly as on a project board, so saying it again on the card would be the two-places-for-one-figure that the
project board gave up. What the card's last line still carries is the person who picked it up — their avatar, name in
the hover label — and the percentage its own steps add up to, the same `kpct` chip an assigned card prints, on the same
live region, so ticking a step in the popup moves the number on the card behind it without the board moving. A card a
reader may move is a drag source: since **Stage 58** management's copy of every backlog card, and an owner's copy of
their own, carry `draggable="true"`, so dragging one into another status column of its bucket posts `/app/tasks/status`
and reloads — it lands in the column it was dropped in **under the same bucket heading**, because a drag changes
`status`, never the bucket. The owner gets the board's `←` / `→` arrows underneath as the keyboard/no-JS path to the
same write. That drag is the list, not the rights: `draggable` is printed for exactly the accounts
`TaskService.updateStatus` accepts — management (any card) or the assignee (their own) — so a card a reader cannot move
does not look grabbable, and a `Viewer`'s scoped board shows their own card draggable and a colleague's not at all.
Nothing here is a second editor: clicking the card opens the same popup the project boards use, filled from the database. On
that popup a backlog card answers its **bucket** with a **Column** picker rather than a project and a lane — which is
how a card here changes bucket, because the bucket is said by that field, not by the status column the card happens to
stand under (which is `status`, not the bucket) — and management's copy of the popup carries the **Where it lives**
picker above it, so a card can be handed to a project and filed in one of that project's six sub-categories from the
same click (Stage 48).
A card belongs to a column rather than to a person, so the editor accepts it with the *Assign to* field left blank —
the rule is read from the row's `kind`, never from the form, so an ordinary assigned card still cannot be saved by
taking its person off it. Management owns every field of it; the person a card is assigned to gets their own dates
and priority and nothing else, because **which column a card sits in is management's answer on this board too
(Stage 49)**. The **Column** picker is printed to that reader greyed, with the whole board still listed so the card
reads in its context, and the line under it says "Management moves a card between the columns." It used to be the
one field wider than an assigned card's; the exception went rather than the drag. **Stage 58** gave this board a drag
back — but it is a drag along the bucket's five *status* columns, the same write the owner's arrows make, so it never
touches this picker: which **bucket** a card belongs to stays management's, said only in the popup. Writing a *new* card onto the board used to be
every account's — the create popup pinned a non-manager's card to themselves and `Not started` and greyed those two
fields before validation. Stage 51 retired that: the `+ Add task` line and its create popup are management's, so on
this page a create names its person and its column freely (management's `createBacklog` pins nothing), and a
non-manager who posts a column-carrying create anyway is bounced back to their scoped board. A card that has to be
pinned to its writer and filed without a choice is written from the **Team** sheet's Assign panel, which is management's
own tool for handing work to a person.

In the page head, opposite the title and beside `Show all buckets`, sits the small `+ Add bucket` button (Stage 59 —
it used to be a dashed full-height box trailing the board as a fake column, which read as stranded once the board
became a vertical run of groups). It opens that same popup on its add half and
asks one question, the bucket's name. It is management's (Stage 51), as is the `+ Add task` line under each group and the whole
`#bucketModal`: a non-manager's board draws none of them, so the popup — add, rename, delete — only ever prints for the
reader whose routes accept it. A name already on the board, or no name at all, is answered inside that popup —
the word stays where it was typed and the reason prints under the field ("There is already a bucket called that.",
"Give the bucket a name.", "Bucket names are 40 characters or fewer.") — rather than reloading the page quietly and
leaving the writer to guess. A name that is accepted appears as a new bucket group and closes the popup, on the same screen,
with no reload: the form names `#otherBoard` as the block to redraw and `#bucketModal` as the one that comes with it.
At the foot of each bucket group sits `+ Add task`, and it opens the *card's* popup rather than the bucket's: one GET form
onto this page carrying `new=1` and the bucket to file the card in, swapped into `#taskModal` while the board
underneath stays put — the same mechanism a project page's lane list uses, and the reason it asks nothing about a
project. It is management's line, because the shared backlog is management's board (Stage 51).
At the right of the page head, **Show all buckets** puts the empty buckets on the board and the same control comes back
labelled **Show fewer buckets**; empty buckets are hidden by default, because a run of nine headings where five hold
nothing is a list of intentions rather than of work. The control is asked of the same read that decides which
buckets to print, so it never appears over a board that has nothing left to show — a board whose every bucket holds
work draws no toggle at all. It is drawn for management only (Stage 51): a Viewer's board is already scoped to the
buckets holding their own cards, so they have no empty buckets to reveal and no toggle is printed. The view rides along
with every management write from that board (a rename, a delete, a new column, a new card, a card saved or moved between
columns from the popup) so the columns somebody opened up do not vanish behind their own click.
Every write on this page is asked where it came from and answered on the page it says: the card popup carries
`back=other`, which is why **Save** — and a refusal, and a deleted column's *move its cards to* — all land back on
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

**Revenue, MNT** is the project's own number from the Projects board. **Invoiced in previous months** is
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
difference is stored. Amounts are read exactly the way the Projects board reads revenue — `8600000`,
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
person. The address names the view in use (`?project=3&employee=EMP00005&sort=old&archived=1`), so what you
are reading is a link you can reload, bookmark or send — and the order and the open half of the sheet, which
no picker in that form answers for, ride along as hidden fields so the first picker change cannot lose them.
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

Writing rights split once, and the same split answers all four routes: every signed-in account logs a lesson
under its own name and may tick, archive, edit or delete **its own**; only management may change or delete
somebody else's. A reader without that right gets no buttons on the row at all, and the popup their click
opens shows the full text, the project and the card as plain lines with no form inside it — "Logged by … Only
its author and management can change what a lesson says." Hiding markup is not the check:
`LessonService.mayWrite` is asked again by `/app/lessons/save`, `/status`, `/archive` and `/delete`, and each
of them reads the row back rather than trusting the id the page was drawn with — so a lesson that has since
been deleted, or that has changed hands, cannot be written by the stale row still standing in front of you.

Two columns answer two questions and are deliberately not one status. **Status** is whether the lesson has
been talked about, the green `● Discussed` or the grey `● Not discussed` pill; the archive is whether it is
still in the way. A lesson can be discussed *and* archived, or neither. The tick is the one write that leaves
a row exactly where it is, so `✓`/`↺` replaces that row's own `<tr>` and your place on the sheet is
untouched; archiving and deleting move a row between lists, so they redraw the whole sheet and the
"3 active, 0 archived" line beside the pickers with it. That line lives outside the live region because it is
the toolbar's and the toolbar is never replaced — it is named as the swap's companion (`data-live-extra`)
instead, by the pickers and by the row buttons both, since a filter changes that number exactly as a row
leaving the list does. An archived row gets its `↩` and the bin and no tick box: a lesson somebody put away
is not also being discussed. Delete is the one write here with no way back, which is why it asks first.

The **Archived** heading prints even with nothing under it, because it is where archived lessons live and a
sheet that only admits the place exists once something has been put in it reads as a sheet with a hidden
half; what is *not* printed when that list is shut, or open and empty, is a second table of nothing — the
toggle says so in words instead. The heading is a button of the toolbar's own form (`form="llFilter"`, the
same arrangement the date column's control uses), which is what lets it live inside the region it re-renders
without nesting a form in a form, and it puts `?archived=1` in the address bar like any other view key.

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
counts the active list, so a lesson somebody archived has stopped asking for attention. With JavaScript
switched off, `+ Add lesson`, the tick, the archive and the bin are ordinary forms to the same routes and the
page that comes back says the same thing.

## Settings and the Team sheet

`/app/settings` is, for management, the **Team** sheet, your own account card, **Change Password**, and below them the
three app-settings panels Stage 61 added — **Project types**, **Email notifications** and **Bucket defaults**. Since
Stage 51 the Team sheet is management's — the roster block, the toolbar and the employee popup print only for a
Manager or Admin — so a **Viewer**'s Settings is just their own account card and **Change Password**, and their
sidebar carries no Settings link; the route stays open to every role precisely so a must-change-password account can
still reach the password form. For management the sheet is the roster read down — **Name, Employee #, Position, Department, Role, Email, Open tasks** — with a search
box ("name, email, employee #"), a **Position** picker, a **Role** picker and "22 of 22 shown" at the right end. It
opens by name, A→Z; every heading is also the control that sorts that column, and pressing the one already sorted
turns it around. Above the table, management gets a card that reads "Add a new employee account…" with a green
`+ Add employee`; each row ends in a red ✎ and a bin. Your own row wears a green `YOU` pill instead of a bin that
would only refuse you.

A row is a **person**, not a login: colleagues on the roster appear whether or not they have an account yet, and the
pencil on one of those rows *creates* the sign-in rather than editing one. The **Role** column tells you what that
account can actually do — the effective role the app checks before every write, which is the permission level of the
position it is filed under when it has one, and its own access level otherwise. **Open tasks** counts a person's
unfinished cards: management sees everybody's, and anybody else sees their own with a `—` on the rows they cannot
read, so `0` and `—` are different answers and both sort to the bottom. A blank department, a missing role and that
dash all land last whichever way the column is read.

Who may do what is printed and enforced twice over. **Management** (`Manager` or `Admin`) adds accounts; only an
**administrator** may set a position, a role or a password, so a manager's popup asks for a number, a name and an
address and nothing else — and a hand-built form carrying the other three is refused, not quietly trimmed. The bin
shows why it will not press instead of vanishing: "Only management can delete an account.", "This person has no
account to delete yet — the pencil creates one.", "The Super Admin account cannot be deleted.", "You cannot delete
your own account.", "Managers cannot delete Admin accounts." Delete is the one write here with no way back, so it
asks first; everything else — a search, a picker, a heading, a save — happens in place, with no page reload and your
scroll position kept, and the address bar still names the view you are looking at (`?q=gan&role=Manager&sort=tasks:desc`)
so it can be reloaded, bookmarked or sent. With JavaScript switched off, `+ Add employee`, the pencil, the save and
the bin are ordinary forms to the same routes and the page that comes back says the same thing.

Two things to know the first time you use it on a fresh database. **The Position picker will be empty**: the
position catalog is its own table and nothing on this page fills it — you add positions on **Access control**, and
only then can the popup file somebody under one. And **an account created without a password signs in with its
employee number** and is sent straight to Settings to change it on first use; while that flag is up, the account is
not yet allowed to change anybody else's, so set your own password before you build the team. Everything here is
read-only for a `Viewer`, and the sheet is the same set of people Access control lists, from the same two tables.

Below the Team sheet, management gets three app-settings panels (**Project types**, **Email notifications**, **Bucket
defaults**); a `Viewer`'s Settings shows none of them, and every route behind them re-checks the role rather than trusting
the markup. **Project types** is a read-only mirror of the nine categories the app can file a project under — each with
its letter, a coloured name and how many projects use it; it edits nothing, because a category and its letter are defined
in code and changing one here would only desync the board from the New-project form. **Email notifications** reports the
mailer's real state — the current delivery mode and your own address on file — and gives an **administrator** with an
address a *Send test email to myself* button; it sends one message through the existing SMTP config in `data/.env`
(`PTS_SMTP_*`) to *you*, writes nothing to the alert ledger, and in dry-run mode simply logs, so it is safe to press
against the live database. That panel also carries the **outbound-mail switch** (Stage 62): a read-only line saying
whether PTS can send, and — for an **administrator** only — an *Enable sending* / *Switch sending off* button that sets
the `mail_send_enabled` key. The switch is a master gate in front of *every* outbound path (the scheduled due-date sweep,
notification mail and the delivery test alike), so while it is off — the default — nothing leaves the box however the
SMTP config reads. It is a strict tightening: turning it on cannot open a door dry-run or an unconfigured host had
already shut, since those short-circuit ahead of it. Only a super admin may move it, and the `/app/settings/mail-switch`
route re-checks that rather than trusting the button. Enabling real delivery on a live host still needs the deployment
side too — a `PTS_SMTP_PASSWORD` and `PTS_SMTP_DRY_RUN=false` in `.env`. **Bucket defaults** is the one panel that writes: it lists the extra lanes each category adds
to a *brand-new* project on top of the six every board already starts with, lets management add or remove them per
category, and refuses a name that is blank, too long, a duplicate, or one of the six (those are the filing floor and are
not editable). A default shapes only the *next* project of that category — no lane is added to, renamed on, or taken off
a board that already exists, and no card is ever refiled.

## Data, backups and logs

Everything stateful is one SQLite file plus its WAL sidecars, in `data/` (or `/app/data` in the
container). Flyway applies `src/main/resources/db/migration/V1__init.sql`,
`V2__due_date_alerts.sql`, `V3__project_categories.sql`, `V4__notifications.sql`,
`V5__task_checklists.sql`, `V6__task_notes.sql`, `V7__project_task_buckets.sql`, `V8__forecast_ledger.sql`,
`V9__lesson_record.sql`, `V10__standard_project_lanes.sql`, `V11__project_invoiced.sql`, `V12__type_default_buckets.sql` and `V13__app_settings.sql` on boot. V1, V2, V4, V5 and V6 are purely additive and
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
filed by guesswork — a card with no lane keeps none, and prints under **Unsorted**.
**V11 is a single additive column**: `ALTER TABLE projects ADD COLUMN invoiced REAL NOT NULL DEFAULT 0` gives every
existing project `0` — the amount actually billed, entered by management on the project header strip (Stage 60) and read
the same way revenue is. It rewrites no value and touches no other table.
**V12 creates one new table and seeds nothing**: `type_default_buckets` (`id`, `type`, `name`, `position`) holds the
*extra* lanes each project category appends to a brand-new board, on top of the six every board already starts with.
Because it writes no row, a database migrates to exactly the behaviour it had before — a new project still gets just the
six — until management adds a default in **Settings → Bucket defaults** (Stage 61). A default is additive and is read
once, by `ProjectService.create`, so changing one shapes the *next* project of that category and never touches a board
that already exists.
**V13 creates one new table and seeds nothing**: `app_settings` (`key`, `value`) is a small key/value store for switches
the app itself owns — as opposed to the deployment secrets in `data/.env`, which the app can read but never write. Its
only key today is `mail_send_enabled`, the master gate on outbound mail (Stage 62). Because no row is seeded, a database
migrates to that switch **off**, so V13 on its own cannot turn the app into a mailer: every send stays log-only until a
super admin flips it on in **Settings → Email notifications**. The SMTP password never lives here — it stays in `.env`.
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
  the repositories that write. Management-only writes: create a project, set a project's manager,
  delete an assigned task, add or delete an account, assign a task from the Team page,
  add or remove a checklist step, and remove a note. Stage 51 widened the management-only list to whole *sections*:
  **Settings** (its Team block), **Access control** and **Other tasks** are management's functions, so a `Viewer`
  loses the sidebar link to each and is bounced at the route — `navItemsFor` drops the three ids, `GET
  /app/access-control` redirects a non-manager, `settingsContent` prints a Viewer only their own account and the
  change-password form, and the Other-tasks board a Viewer reaches is scoped to their own cards with no column tools
  (their own card still opens in the editor, and the change-password form stays open to every role because
  `MUST_CHANGE_ALLOWLIST` sends flagged accounts there). Within Other tasks the whole board is management's: writing a
  card, naming a column, renaming and deleting one and the `⋮` are all management's, and `POST /app/other-buckets` and
  the save endpoint's column branch check the role again before the (still role-permissive) service is reached. A lesson splits the same way (Stage 44), but by authorship rather than by
  field: every account may log one and may tick, archive, edit or delete **the rows logged under its own employee
  number**, while somebody else's lesson is management's alone. `LessonService.mayWrite` decides that once and is
  asked again by all four lesson routes, each of which reads the row back instead of trusting the id the page was
  drawn with — so a lesson that has since been deleted, or that has changed hands, cannot be written by the stale row
  still on the screen. A row with no author (a V1 carry-over) is management's to tidy and never a peer's to claim.
  Editing a task is
  shared since Stage 23: management may change every field, and the person it is assigned to may change
  its start date, due date and priority — `TaskService.updateAssigned` rebuilds every protected field
  from the stored row, so the split holds even when the popup's greyed inputs are left out of a POST.
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
  (trimmed and case-compared, so `" tendering "` cannot make a second *Tendering* column). The **first six**
  names are not asked for anywhere: `ProjectService.create` writes `ProjectBucketRepository.seedDefaults` in the
  same breath as the project row, so a hand-built POST to `/app/projects` cannot name a lane, extra `lane1` /
  `lane2` / `lane3` fields in one are simply not read, and every board opens on the same six columns whoever
  made the project. Since Stage 48 those six cannot be taken off either: `removeLane` answers
  `LaneError.STANDARD` for a name in `DEFAULT_PROJECT_BUCKETS` **before** the last-lane count, so a hand-built
  delete cannot empty the cards filed in a shared column by removing it. Since Stage 35 **no lane name is asked
  for or changed anywhere in the interface**: the
  *Add lane* box and the **×** on each column head are gone, which puts these two routes in the same position
  card deletion is in — the gate still applies to a hand-built request, and there is no control that a reader
  can be talked into using. A lane's `+ Add task` line (Stage 34) is drawn on `mayAddTasks` as well, and the `into=<lane
  id>` it posts is matched against this page's own lanes before the blank popup is built — a foreign id, a
  removed one or a word opens the ordinary form instead of pre-filing the card — and `TaskService.create` still
  insists the lane it finally writes belongs to the project the card is being made on. **Removing** one
  is the same right on the same day (`POST /app/projects/{id}/buckets/delete`, Stage 31), and the lane it names
  is looked up rather than trusted: an id that is not on this project's board — a foreign one, a gone one, none
  at all — comes back `no-lane` with nothing deleted, and a project's last lane refuses to go (`last-lane`)
  because its page would otherwise fall back to the five status columns. Removing a lane that holds cards is
  not removing those cards: the `ON DELETE SET NULL` key un-files them and they appear under **Unsorted**.
  Those refusal codes are written into the redirect but no longer read by anything: the page that answers
  `?lane=duplicate` renders the ordinary board, because the box those sentences used to sit under is gone.
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
  just resolved — because the six sub-categories are the same six on every board, which is what lets a popup that has
  not loaded a project's lane ids ask the question at all; a name that project does not carry is refused. And a card
  on no board is refused with it: the *— No project —* option is out of the Project picker and an empty `projectName`
  comes back as *"Choose which project this card is on."* The one blank a popup still prints, *— No project yet —*,
  is what a row that arrived before the rule says about itself, not an answer anybody can choose. Deleting a card is the one management-only write with **no control anywhere in the interface** — the
  gate still applies to any request that reaches it. Management-only *reads*: the whole task board — anybody
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
2. `./gradlew test installDist` is green (486 tests).
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
   `/app/projects` opens. Sign out and back in once to confirm sessions persist. Then sign in as a
   `Viewer` and confirm `/app/projects` has no `+ New project`, no create popup and, on a project page,
   no lane control of any kind and no manager control of any kind — while `/app/other-tasks` and `/app/todo` still work
   normally. On that same
   project page there should be no roll-up of the board at all — no **Cards**/**Open**/**Done**/**Overdue**/
   **Steps**/**End date** tiles and no repeated line of column dots; the only counts on that page are the ones
   in the board's own column heads, and since **Stage 52** those columns are the five **status** columns —
   **Not started**, **In progress**, **On hold**, **Blocked**, **Completed** — not the six sub-categories. Since
   **Stage 56** the `Viewer` sees the **whole** project board here — every card filed under the project, each with its
   holder's avatar — but reads a colleague's card as a dead card: it carries no click target, no `draggable` and no
   move button, and only their own cards open in the editor. The project page is the one place a `Viewer`'s card *list*
   widens past their own; the Calendar, `/app/assigned-tasks` and the Team drill-down stay personal. Then sign in as
   management and click one of their cards: the editor should open over the board without the page
   reloading, holding every field and the plan under it, and there should be no menu, no `Edit` button and
   no status dropdown anywhere on the board — and while no card on that board shows a plan or a box until it is
   clicked, this manager can tick, add and remove steps on a colleague's card from inside its popup, because
   management may work any card's plan. Then open `/app/assigned-tasks` **as that manager**: since **Stage 53** the
   board prints only the cards with *their own* employee number on them — the colleague's cards are gone, exactly as
   on a `Viewer`'s copy of the page — and the line *"Only the cards assigned to you are on this board."* sits above it.
   The manager can still press `+ New task` and open a full editor there; it is the *list* that went personal, not the
   rights. Then create a project and watch what the popup
   does **not** ask: it holds nine fields and no lane input — no **Task lane 1**, no placeholder naming a
   default, no sentence about leaving one blank — and pressing **Create project** should land on a page whose
   board is the five **status columns** (Stage 52). The six sub-categories are still
   written for the project and are what a card is filed under in its editor; they are simply no longer the
   columns. Nothing about the board is a condition of making a project.
   Then add a card from the page head's `+ New task`, leave **Sub-category** on its blank answer, and save:
   nothing is written, and
   *"Choose which part of this project the card is on."* prints under that picker with the title still typed
   (Stage 48) — a card onto a project is filed in a sub-category as it is written, even though the board it lands
   on is cut by status. Choose one and the card appears in its **status** column, with its sub-category set behind
   it in the editor. Pick a project's card and move its **Where it lives** picker to *Other tasks*: the
   project half of the popup shuts, the **Column** picker comes up, and the save answers on **Other tasks**
   with the card under that column — its steps, its log, its status and its person intact. Move it back the same
   way and it has to be filed in a sub-category again.
   Check the board behaves like the general one: drag a
   card from **Not started** to **In progress**, drop it, and after a reload it should still be there — the drop
   posts `/app/tasks/status`, the same write the **✓ Done / ↺ Reopen** arrows make. On this status board a card's
   **owner** walks their own card along the columns exactly as they do on the Assigned page (Stage 52), so the
   lane-mover right that **Stage 49** put on this page (`TaskService.moveToLane`, management or the project's
   manager) is no longer what its drag reaches — that capability and its lane renderer stay for the popup's
   filing and the lane tests, but the project page itself draws no lane column to drag into. And check what is
   **not** there (Stages 35, 37 and 52): nothing under the columns names a lane, no column head carries a **×**,
   and no lane column or **Unsorted** bucket stands beside the five statuses — the board is statuses only, whether
   or not the project's cards have been filed. That holds for management as much as for a `Viewer`. A card's
   **height** is its own content
   (Stage 38), and since Stage 41 that content is its title, its meta line and its arrows: a card with twenty
   steps on it is exactly as short as a card with none, because a plan is drawn only inside the popup. Then open **Other tasks**
   (`/app/other-tasks`, Stage 47): it is a vertical run of **bucket groups** (Stage 57), one group per bucket. Each
   group prints the bucket's name as a heading on top — the way a project page prints its project name — with how many
   cards it holds and, for management only, a `⋮` menu; directly under that heading the bucket's cards are cut by the
   **same five status columns** a project board prints, each headed by its status dot and word. So a bucket is read by
   how far its work has got, not by a nameless column, and this is the only place the backlog is printed — the
   **Assigned tasks** page shows none of it, and the flat list `/app/other-tasks` used to print is gone. Every bucket
   that holds work prints; the empty ones come and go with the **Show all buckets** control at the right of the page
   head, drawn for management only (Stage 51) and not drawn at all when no bucket is hidden.
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
   and the `←` / `→` arrows still printed on their card walk it between the five status columns under its bucket
   heading, because a status is the one thing the board lets its owner change. That owner's own card is also
   **draggable** (Stage 58): drag it into another status column of the same bucket and it reloads filed under the new
   status, its bucket heading unchanged — the mouse path to the same `/app/tasks/status` the arrows post. A colleague's
   card on a `Viewer`'s board carries no `draggable` at all, so a drag there never starts. Management, on the wide
   board, drags any card the same way.
   The two lane routes are still open to a hand-built management `POST`,
   so if you want to confirm that: `POST /app/projects/<id>/buckets` with `name=Workshop` from a management
   session should add a fourth column to that board, and `POST /app/projects/<id>/buckets/delete` with that
   column's `bucket` id should take it off again with its cards left standing under **Unsorted**. The page that
   comes back prints no sentence about either — the refusal codes those routes redirect with
   (`duplicate`, `too_long`, `last_lane`, `no_lane`) lost their message when the box under the columns went.
   Sign in as the `Viewer` used to check the board above: on a project this account does not manage there is
   no `+ Add task` line on any lane, and
   a hand-built `POST /app/projects/<id>/buckets/delete` for that account should land on the Projects list with
   the lane still there. Then read the measurement: a card
   with four steps and one ticked carries a `25%` chip beside its due date and no rows or boxes under it, its
   popup's plan heading says `1 of 4 done · 25%`, and the project bar above the board reads the average of its
   cards. Tick a box in that popup and all three move at once — the popup's list, the card's chip, the bar —
   without the page reloading and without the popup closing; the Projects board row for that project prints the
   same figure the page it links to does. A card with no steps prints no chip, and a project whose cards all have no steps
   keeps the percentage filed on its row — that fallback is the only place the old stored number still appears,
   besides the alert and notification emails. Finally check the one task write a `Viewer` may
   have: pick a project whose **Manager** is that account and open it with no cards on it — the empty board
   should carry `+ New task`, and its popup should hold a writable title, holder and column with the project
   itself printed once and greyed. Write one card for a colleague and confirm it stays on that page. Then
   open a project that account does not manage: no empty-board block, no `+ New task` anywhere, and
   `?new=1` on either the general board or somebody else's project comes back with no popup. On that same page
   (**Stage 56**) the whole project board is now shown to this `Viewer` — every colleague's card with its holder's
   avatar and the five status columns — but each of a colleague's cards is dead: no click target, no drag, no editor,
   and only a card assigned to them opens. Kotlin-only, so **no Ctrl+F5** is needed here; the read is what widened,
   not the rights. Then watch the
   number beside **Assigned tasks** in the sidebar while you finish a card: since **Stage 54** it counts **every**
   card on your board, so the number does **not** drop as you close one — it stays the same and the card simply moves
   into the **Completed** column where you dropped it (the pill and the board now agree exactly). What still moves that
   figure is only adding or deleting a card. The `**Completed**` card keeps counting because it is still assigned to
   you; and to see a genuinely open-only workload number, look at the Team sheet's **OPEN TASKS** column, which
   deliberately ignores the finished ones.
   Then check the reader's own backlog on this page (Stage 55): with **Ctrl+F5** (its heading and message are new CSS),
   open `/app/assigned-tasks` as a `Viewer` who owns at least one Other-tasks card. Below the project board an
   `Other tasks` heading should print their own backlog cards in the five status columns — and none of a colleague's, and
   no shared-board chrome (`⋮`, *Add bucket*, the column names) since that still lives only on the Other tasks page.
   Click one of those cards: the same editor opens and, after a save, returns here rather than to the backlog. Now sign
   in as a reader who owns **no** Other-tasks card: the heading prints *You don't have any other tasks assigned.* and no
   second board is drawn. In both cases the sidebar pill equals the cards on the page (project cards + their own
   backlog), because `visibleTaskCount` sums the same two lists the page reads.
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
   Then read the lesson sheet (Stage 44). `/app/lessons` lists every active lesson newest first, each under its
   author's initials, the project's number and the card it came from. `+ Add lesson` opens the popup, which asks
   four things and never asks *who* — save one and it stands at the top of the sheet under your own name, the list
   and the "N active, M archived" line redrawn and the page not reloading. Press `✓` on your row and that row
   alone redraws, its pill green and nothing else moved; press `⏸` and the row leaves the list for the
   **Archived** heading below it, whose count moves with it, and `↩` brings it back. Choose a card that belongs to
   another project and the popup comes back open, holding your words, with the reason under the card field and
   nothing written. Sign in as the `Viewer` on the same sheet: their own row carries its buttons, a lesson logged
   by somebody else carries none and opens to be read rather than written, and a hand-built
   `POST /app/lessons/delete` naming that row comes back to the same sheet having deleted nothing. The rows the
   importer carried over print with no employee, no date and no card, and sit at the end of the list whichever way
   the **Date** heading runs.
   Then read the team sheet (Stage 46). `/app/settings` is now that sheet first: eight columns, the search box, the
   two pickers and "N of M shown" at the right, every colleague from the roster whether or not they have an account
   yet, and your own row carrying a green `YOU` instead of a bin. Type three letters and the table swaps in place —
   no reload, no lost caret — with the count reading e.g. **3 of 22 shown**; click **NAME** and the arrows turn; click
   **OPEN TASKS** and the sheet re-orders by it, `—` rows (a `Viewer` cannot read them) sitting at the end either way.
   Press `+ Add employee` as management: the popup asks a number, a name, an address — and, for an administrator only,
   a position, a role and a first password. Save one with a number that already exists and the popup comes back open,
   holding your words, with *"There is already an account with that number."* under the field and no row added. Open a
   colleague's ✎: the number and the name come back read-only, a blank password means *unchanged*, and an edit that
   leaves the roster's person alone cannot invent a second account. The bins that will not press say why in their
   tooltip ("Managers cannot delete Admin accounts.", "This person has no account to delete yet — the pencil creates
   one."). Sign in as the `Viewer`: no add card, no pencil, no bin, and `?new=1` in the address bar comes back as the
   plain sheet with no form in it; then hand-build a `POST /app/settings/employee/save` from the `Viewer` carrying a
   position and a role and confirm it is **refused**, not trimmed and written. One trap on a genuinely fresh database:
   the Position picker is empty until you add positions on **Access control**, and an account saved without a password
   signs in with its own employee number and is held on Settings until it changes it — so set your own password first.
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
| A user cannot open any page except Settings | Must-change-password gate: they need to save a new password in Settings |
| On a first sign-in the Team sheet shows `+ Add employee` but saving jumps back to the top of Settings | Same gate: a flagged account may not write other accounts until it has its own new password. Set yours first — the reason is printed in the Change Password card |
| The popup's **Position** picker has no choices | The position catalog is its own table, filled on **Access control** (or by the legacy import). A fresh database has none, and the sheet falls back to `Team Member` |
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

478 tests in 32 classes: Flyway-on-SQLite migration + FK enforcement + idempotent migrate (the table list now
includes `project_forecasts`, `lessons`' six V9 columns, and the empty four-column `type_default_buckets` V12 creates
without seeding), the V3
category migration against a database built up to V2 only (legacy `SIT-04`/`Manufacturing` rows
rewritten, new columns arriving blank/`0`, current and hand-edited values surviving a re-run), the V7 lane
migration and the V10 one against a database built up to V6 only (every project that already existed given its
lanes in board order, each project getting *its own* rows rather than a shared set, an existing card left unfiled rather
than guessed at, deleting a lane un-filing its cards while deleting a project takes its lanes with it, and the
backfill not replayed on the second boot; then V10's own half — the six shared sub-categories inserted for every
project that lacked them and numbered 0–5 by name, an original default that holds no card deleted, one that holds
cards kept and standing after the six beside a lane the manager had added, no card's `project_bucket_id` moved and
no second copy of the six on the second boot), the legacy JSON importer — with the new rule that every project it
writes arrives holding the same six lanes a new one starts with, so no kind of project opens on a different
board from another — role resolution and authorization, CSRF token forgery, the login throttle
window, the full auth path against a real SQLite file (bcrypt login, session issue/resolve/revoke,
revoke-on-password-change, expiry), the must-change-password gate, bootstrapping an empty
database (super admin exists, is gated, repeat boots are idempotent), the due-date alert rules
against a real SQLite file with SMTP forced into dry run — in-window alerting to manager and
assignees, task alerts copied to the project manager, silence at 100%/`Completed`, the lead-window
edge, overdue wording, blank and malformed due dates, items with nobody attached, recipients with
no email address, the same-day dedupe, the ledger's UNIQUE index rejecting a duplicate row, and the
delivery test (dry run sends nothing and writes no ledger row, its body reports the configuration it
used without the password, `emailFor` reads the address on file) — and project creation: each rule of
`ProjectService.create` (duplicate number, wrong category letter, malformed number, reversed dates,
blank description, unknown category or manager, revenue that is not an amount) plus the category and
number-suggestion helpers, **that the create popup asks nothing about the board** (`ProjectField` is exactly the
nine form fields, so a lane question cannot come back as an enum entry, and a successful create still lands the
six standard lanes in board order on the project it wrote), and the notification rules behind
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
column is a drag or the editor's own Status field, so an assignee who cannot move a card keeps their move
arrows, and since **Stage 49** the two are pinned apart on the same node: `draggable="true"` is printed for the mover
and for the project's own manager only, while the owner of the card — who now prints no `draggable` at all — keeps the
click that opens the popup and their own **✓ Done / ↺ Reopen** button, so a card that cannot be dragged is a card that
opens rather than a card that is dead. A `Viewer` whom the project names as its manager is asserted to drag, and a
mover who does not hold the work is asserted to drag without being able to open it. And no card printing a plan of its
own — no `Checklist` heading, no step row, no box and no × anywhere on
a board, for its owner included, while the same card's popup prints those boxes for exactly the readers the
service lets tick and its × and add line for exactly the readers who may write the plan; on the Projects pages, that a `Viewer` with no rights of their own is served
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
checklist rules are exercised as written — management appends in order, a `Viewer` cannot add or remove a
step even on their own card, the assignee may tick, a second tick unticks, blank and over-long labels are
refused, removing one step leaves the rest numbered in place, and deleting the card deletes its steps
through the V5 foreign key. `TaskBoardRenderTest` pins the markup that goes with those rules, and since
Stage 41 all of it is popup markup: a step row *is* its toggle form, carrying the card id and the step id
with the box, and the whole row is the label you click; a ticked step comes back `checked` and struck
through; a step write names the popup's plan block as its target and the card's own block plus
`#project-progress` as the companions it moves with it; the add line and the × print on the right to write
and on nothing else, so an owner's popup holds live boxes with no × and no add line; and a board — status,
lane or backlog — carries no plan markup at all, not one box, for the person the card belongs to included.
The three halves of the split are tested separately: a card's own `kpct` chip lives inside the block a tick
swaps, a colleague's card on the same board prints its percentage with no boxes beside it, and the boxes
follow the right to tick rather than the page the card is on — management gets them on anybody's card, and a
card with nobody on it gets them for the manager alone. Stage 25's author rule is tested on the same two
sides: in `TaskServiceTest`, the account a project names writes its first card, a card they post naming
*somebody else's* project still lands on their own, the right does not carry over to the general board, and
nobody short of management or that project's manager is given a way in — while `TaskBoardRenderTest` builds
the blank popup **through `newFormOf`**, the way the route does, so an empty board hands its `+ New task` to
the reader who may write and to nobody else, a project manager's blank card is open on three fields and one
greyed project, and `canCreate` never opens an existing card. `TeamPageRenderTest` holds the Team split: a `Viewer` is served their own
task list on their own row and nothing else, with no `+ Assign a task` panel anywhere in what they get.

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
rather than kept on the board it left. `ProjectServiceTest` holds that the six standard lanes
are written with the project and in that order, that `ProjectField` carries only the nine form fields — which is
what keeps a lane question from being re-added as an error key and quietly reappearing in the popup — and that a
create the form refused leaves no project and no lanes behind. `ProjectsPageRenderTest` pins the popup's half of
the same rule: a manager's create form has no lane input, no *Task lane* label and no lane-default sentence, and
it comes back the same way after a rejection, with its nine fields and their values intact. Stage 31's removals are tested against the same two sides: in
`TaskServiceTest`, the right to take a lane off is the right to name one, a lane id that is not on this
project's board — another project's, a deleted one, `0`, none at all — comes back `no-lane` with the lane it
was aimed at still standing, the cards that were filed in a removed lane survive un-filed on the same project
with the same title and the same owner, and a board whose last lane is the one being asked for answers
`last-lane` and keeps that lane, still draggable-into — rules that are still worth pinning because the routes
are still wired, even though nothing on a page can reach them since Stage 35. `TaskBoardRenderTest` pins the markup. Since **Stage 52**
a **project page's** columns carry `data-status` and never `data-lane` — `a project page is cut by status and
carries no manager panel` asserts the five status heads, and that no `DEFAULT_PROJECT_BUCKETS` name is a column
head there. The lane board is now pinned through `laneKanban` rendered **directly by the helper** (the retained
lane capability, not a page): on that board a card with no lane shows in the **Unsorted** column — which is the
only thing that
puts that column on a lane board at all (Stage 37). `the board prints Unsorted only while it has a card in it` renders
the same lane board before and after the one card is filed: one head more than the board has lanes, and a counting
**Unsorted** while the card is
unfiled, exactly the project's lanes and no `data-lane="0"`, no *Unsorted* word and no extra set of add
lines once it is not. The
width the column used to borrow from its neighbours is a stylesheet question, measured in the browser rather than
here: `.kanban-lanes` is a run of fixed 288px tracks, so a column arriving or leaving resizes nothing.
`/app/assigned-tasks` and, since Stage 52, a **project page** both render exactly the five status columns with no
`data-lane` and no lane form anywhere in them. Stage 35's absence is pinned on the direct-helper lane board and for
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
`TaskBoardRenderTest` holds the markup's half of the same rule: neither picker offers a blank choice any more —
*— No project —* is gone from the general board's popup and from a project page's, so nothing on a page can leave a
card on no project — a create opened on the general board lists the projects and opens on the
first of the six sub-categories, and a card's own lane is printed, posted and read back by **name**, with a project's
extra lanes standing after the six both on its board and in its picker.

The **Other tasks** page is pinned by `OtherTasksRenderTest` (Stages 40 and 42, on its own page since Stage 47),
twenty-six tests over the board as the picture draws it. The shape half asserts what is drawn where: only the columns that hold cards are on the board
until the reader asks for the rest, the `+ Add bucket` control is a **small button in the page head** beside
`Show all buckets` — not a box trailing the board (Stage 59) — and every column head carries its own
`⋮` menu form — a GET onto this page naming that column by `bucket` id, with `#bucketModal` as its swap target, so
one mechanism serves however many columns exist. The popup half is the blunt one: rename posts to
`/app/other-buckets/<id>/rename` and carries the column's id in the route rather than in a field, the delete posts
to `…/delete` and names the column its cards are handed to (`moveTo`) with the card count printed in the label
asking for it, a refused rename comes back **typed** into `#bucketName` with the reason under the field rather than
the stored name, a refused delete comes back with the destination still selected, and a column holding nothing is
deleted with no destination offered at all. Two refusals are drawn out of the markup entirely rather than checked
by the reader: the last column on the board while it holds cards prints no delete form, and the
**Show all / fewer buckets** toggle is a view for every reader — it swaps `#otherBoard` and names its own
`#bucketsToggle` block plus `#bucketModal` as its companions, so the label flips in the same redraw as the columns it
describes. Whether it is drawn at all is asked of the same read the board prints its columns from
(`otherToggleApplies`), so a button never appears over a board that has nothing left to show and never vanishes while
a column is still hidden. The card half asserts that a backlog card's own `kcard-open` form is what puts the
editor up (the owner also gets the board's status arrows), that a column's `+ Add task` line is a GET form naming
`#taskModal` and carrying that column's id as `column` — the card's popup, on that column, with no `bucket` field and
no text input in the adder — and that the view the reader is standing on rides with **every** write from the board:
five `allbuckets` fields on the wide board plus the head's `+ Add bucket` button carrying it, none on the narrow one, since a write that landed would otherwise hide
the column it just made. Stage 42's five card-popup cases pin what that popup asks: a backlog create files the card
in the column it was opened from and prints **no project picker at all**, its Column picker has that column already
selected and its hint naming it; an ordinary account's create has *Assign to* and **Status** printed `disabled` and
pinned to themselves and **Not started**, with the sentence saying so and every other field writable; a saved
backlog card opens on its own column, offers the board's other ones, and gives a non-manager the picker alone; a
refused create comes back as the popup with the reason under the Column field and the typed title still in the title
box; and a card with one of two steps ticked prints `50%` on its own `#task-body-<id>` line while a stepless card
prints no chip at all. The role half is Stage 48's split, and the test is named for it: *a reader without management
names columns but cannot rename or empty one*. A reader without management is served the **add** half of the column
popup as a complete write — `#bucketModal` **as a node** (asserted through `elementWithId`, because the toggle's own
`data-live-extra="#bucketsToggle,#bucketModal"` carries that string on every page for every role), the head's
`+ Add bucket` button, its `newbucket` opener, `/static/js/bucket-modal.js`, and the `formWithAction` that posts the name
with `name="name"` and `id="bucketName"` in it, because a box that could not focus its field would be a dead link.
The reader who notices the kind of work is the reader who names it. What stays management's is everything about a
column that already exists, and that half is asked for as absences on the board and across the page: no `kcol-menu`,
no `⋮`, no `/rename"`, no `/delete"`, no `bucketMove` anywhere, and no `Rename` printed in this reader's popup — while
a `?bucket=<id>` they cannot reach by clicking is answered with **Add a bucket**, with no rename form behind it, so the
markup never promises a write their routes refuse. The board underneath is the same board either way: `allbuckets`
rides, `otherBoard` prints, and both column headings and the empty-column message still print for them, because those
describe the board rather than a right — and — since Stage 42 — so does each column's `+ Add task` line: the adders are
there for them too, two of them, with the card popup on the page to open, because writing a card was never
management's alone. The editor on somebody's card still is: a card belonging to nobody, read by an account that does
not manage, prints no `kcard-open` form and no `kmove-btn`. Two more cases belong to Stage 47 rather than to
the board: every opener on this page is a GET onto `/app/other-tasks` and no form anywhere in it aims at
`/app/assigned-tasks` except the shared card popup's own **Save**, whose hidden `back` field says `other` — which is
how a card written here lands back here.
`AssignedTasksRenderTest` keeps four tests, and they are all absence tests: the page prints its five status columns
and no `#otherBoard`, no `#bucketModal`, no `⋮`, no dashed box, no `allbuckets` field, no `kanban-other`, and not one
backlog card's title; and its own popup carries no view flag, because the board underneath it has no view to keep.

A project page prints no roll-up of its board (Stage 28 took the strip and the `TaskService.summaryOf` /
`ProjectTaskSummary` arithmetic behind it out of the codebase entirely — nothing else ever called them), so
what the suites now pin is the absence and the two figures that remain. `ProjectsPageRenderTest` renders a
project holding an open card, a colleague's card, an unassigned card whose due date the clock has run past and
a finished one with a step ticked, and asserts that not one of `project-summary`, `sum-tiles`, `sum-tile`,
`sum-key`, `sum-val`, `sum-cols`, `sum-col`, `sum-people`, `sum-empty` appears — while the board underneath
still counts its own lanes (one `kcol-count` per lane the project actually has — six, since Stage 48 — plus the
**Unsorted** column that a card with
no lane brings with it), still shows all four cards, still prints the project's `End date` as the
project's own field, and still has `id="project-progress"` for a tick to swap. A `Viewer`'s copy of the same
project shows only their card and no figure about anybody else's. An empty one is described by the board's
heading and its own call to action, not by a strip. `SidebarTaskCountTest`'s last pill test reads the sidebar number
straight off the reader's whole card set (`assignedFor(...).size`, every card counted since Stage 54, plus the reader's
own Other-tasks cards since **Stage 55**) instead of
against a strip, and
`TaskBoardRenderTest`'s two companion needles name the single `#project-progress`. The overdue rule itself is
not left untested — it lives, unchanged, in the due-date mailer's own suite, which is the place that still
acts on it.

**Stage 61**'s Settings panels are pinned on both sides too. `SettingsRenderTest` holds that Project types, Email
notifications and Bucket defaults print for management and for nobody else; that the email *Send test to myself* button
appears only for an **administrator** who has an address on file, and is a live swap; that the bucket card shows the six
floor lanes and, for a category that has them, each extra lane with its own remove form. `BucketDefaultsServiceTest` (a real
SQLite file + Flyway) holds the add rules — blank, over-length, a case-insensitive duplicate, and a name that is one of the
six are each refused with nothing written, while a lane added to one category does not collide with the same name on
another — plus the remove lifecycle and the `?lanes=` fallback to the first category. `TypeDefaultBucketMigrationTest`
proves V12 creates an empty four-column table, leaves every existing project's six lanes and its cards untouched across a
re-migrate and a second boot, and is not replayed. `ProjectServiceTest` covers the payoff: a new project of a category with
defaults starts with the six then its extras numbered from 6, a category with no defaults gets exactly the six, and a
default added *after* a project exists does not reach that board.

There is still no HTTP-level test: the alert endpoints and the management-only gates, like every other
route, are verified by booting the shipped distribution and driving it with `curl` (see the launch
checklist) — for the gates that means one throwaway database under `%TEMP%` on a spare port, with one
`Viewer` and one `Manager` account created through `/app/access-control`, checking that the `Viewer`'s
`POST /app/projects` comes back redirected with nothing on the board, and the same for a
`POST /app/forecasts/save` that should not have been accepted or a `POST /app/lessons/delete` on somebody
else's row.

## Project layout

```
src/main/kotlin/mn/murray/pts/
├── Application.kt              routes, plugins, session guard, startup (incl. the alert scheduler)
├── <Page>.kt                   one kotlinx.html view per page
├── config/AppConfig.kt         every tunable, in precedence order
├── db/                         DatabaseFactory, Migrations, Exposed tables
├── db/migration/               JsonImporter, LegacyNormalizer, DemoSeeder
├── domain/                     plain model classes
├── repository/                 SQL access, one object per aggregate (+ AlertRepository,
│                               + NotificationRepository)
├── security/                   PasswordHasher, Authorization, Csrf, LoginRateLimiter
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
├── logback.xml                 console + rolling file appenders
└── static/{css,js,assets}/     extracted front-end assets (CSP: no inline)
```

## License

The repository carries an MIT `LICENSE` ("Copyright (c) 2026 MURRAY Mining Services Mongolia"),
inherited from the v1 scaffold. For an internal tool holding employee records, decide before
publishing whether that is what you want — an "unlicensed / internal use only" file is the usual
choice, and I left the existing one untouched.
