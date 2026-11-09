# Murray PTS — stage history

The chronology that used to live inside `ARCHITECTURE.md`: **Stages 6 to 110, one section per
stage** — what was asked, why it was settled the way it was, what shipped, how it was verified,
and the close-out note for the next reader. It moved here on 2026-11-02 so the living description
in [ARCHITECTURE.md](ARCHITECTURE.md) stays small enough to read in one pass. **Nothing was
summarised, rewritten or dropped in the move:** the sections are byte-for-byte what they were, in
the same order, under the same headings; only a one-line `<a id="stage-NN">` anchor was added
above each heading so the index in ARCHITECTURE.md can link straight to it.

For the app as it stands today read [ARCHITECTURE.md](ARCHITECTURE.md) — package layout, data
model, security, the behavior-preservation checklist, testing — and [README.md](README.md) for
running, driving and deploying it. A `Stage NNN` reference anywhere in this repository means the
section of that number in this file.

<a id="stage-6"></a>

## Stage 6 progress (route/service migration)

Stage 6 moves persistence off the legacy `JsonStore`/`DemoData` JSON aggregate and onto the repositories/services, one aggregate at a time, flipping auth last so the app is never internally inconsistent.

**Done — people aggregate + auth core (verified end-to-end against a live boot):**
- `service/UserService` wraps user/team/position repositories with the legacy rules (bcrypt hashing, `EMP0000` undeletable, managers can't delete admins, no self-delete, `MIN_PASSWORD_LENGTH`, auto-email, add-user also rosters the team member, password change revokes sessions).
- `DemoData.team` / `.access` / `.positions` / `.userFor()` now read from the DB via `UserService`, so every view (Team, AccessControl, project/task assignee dropdowns, nav counts) switched over with no call-site changes.
- Auth core flipped: `POST /login` → `AuthService.authenticate` (bcrypt + built-in rate limiting), `GET /logout` → `AuthService.logout`, `currentSession()` → `SessionRepository` (DB-backed `PTS_SID`). Legacy in-memory `SessionStore` removed. Session cookie now `httpOnly` + `SameSite=Lax` + `Secure` when configured.
- Self-service password change re-issues a fresh session (`AuthService.reissue`) so the user stays signed in and sees the success banner.
- People-write routes converted: settings password/email, access-control add/delete/password/update, positions add/update/delete (the `<tr>`-fragment + 204 fetch behaviour is preserved).
- All inline `access in MANAGE_ROLES` / `== "Admin"` gates replaced with `Authorization.canManage` / `canAdmin`, so `Position.permissionLevel` is now enforced.
- Live test confirmed: bcrypt login (302 + cookie), wrong password (401), unauthenticated `/app/*` (302 → `/`), admin adds user → new user logs in → Manager-by-position reaches assigned-tasks → self password change keeps session → new password works → logout revokes session.

**Done — all remaining aggregates (Stage 6a–6d, verified end-to-end against a live boot on a throwaway DB):**
- **6a Projects + pins** — `service/ProjectService` wraps `ProjectRepository` + `PinRepository` and owns the legacy rules (`PROJECT_TYPES`/`PROJECT_STATUSES` whitelists with fallbacks *(the status words themselves became
`Not started / In progress / On hold / Blocked / Completed` in **Stage 96**; the whitelist-and-fallback shape did not
change)*, `SIT|MFG|RMS|LAB-NN` code generation *(the four-category scheme and its auto-generated codes — both replaced in Stage 12)*, manager **name→empNo** resolution, roster membership guards). `DemoData.projects` and the `pinnedIds()` helper now read from the DB; the create / manager / assignees-add / assignees-remove / pin routes were converted off `JsonStore.update`. Projects now store manager and assignees as real FKs.
- **6b Tasks + buckets** — `service/TaskService` wraps `TaskRepository` + `BucketRepository`, discriminating the single `TaskItem` shape by the DB `kind` column (`ASSIGNED` | `OTHER`). It owns priority/status whitelists, assignee roster checks, project-name→id resolution (with the stricter `/app/team/assign` "project must exist" guard vs. the lenient `/app/assigned-tasks` one), and the kanban "manager-or-own-task" authorization for `POST /app/tasks/status`. `DemoData.assignedTasks` / `.otherTasks` / `.buckets` read from the DB.
- **6c Todo** — `service/TodoService` wraps `TodoRepository`; every mutation re-checks row ownership against the acting employee (the invariant the legacy handlers enforced inline). List delete cascades to its items via the FK. `POST /app/todo/lists/reorder` still accepts the `order=` CSV (ids not owned by the caller are ignored by the owner-scoped update); `move?dir=up|down` preserved. `DemoData.todos` / `.todoLists` read all-owner rows so the existing views' `filter { owner == session }` is unchanged.
- **6d Forecasts / lessons + legacy removal** — `DemoData.forecasts` / `.lessons` now read `ForecastRepository` / `LessonRepository`. The legacy `JsonStore` (`Store.kt`) and the unused `MANAGE_ROLES` set were **deleted**; `PtsData` / `AccessRow` survive only as the importer's decode target. `DemoData.seed()` remains solely as the demo dataset the importer's normalizer borrows its default position catalog from — nothing auto-seeds the live DB.
- Live boot verification (throwaway DB + a copy of `pts-data.json.example`; the real `data/pts-data.json` was confirmed byte-identical afterwards): import of 6 users / 16 projects / 5 tasks / 23 team / 3 todo lists; login (302 + `PTS_SID`); all ten `/app/*` pages render 200; project create generated `MFG-03`; manager name round-tripped through the FK; assignee add/remove; todo list+item create, toggle, CSV reorder; assigned-task create; kanban status transitions with the invalid-status case correctly rejected; bucket + other-task create; pin toggle. All 18 unit/integration tests pass.

<a id="stage-7"></a>

## Stage 7 progress (frontend asset extraction + CSP)

Decision **A** from "Frontend approach" below was taken: all CSS/JS is served as external static
files and the Kotlin string constants are gone. Inline `<style>`/`<script>` and inline event-handler
attributes are no longer emitted anywhere, so the CSP no longer needs `'unsafe-inline'` for scripts.

**What changed:**

- `staticResources("/static", "static")` in `Application.kt` serves `src/main/resources/static/`.
  The previously dead `.css`/`.js` files in the resources root were deleted; the live tree is
  `static/css/{base,app}.css` and `static/js/{theme,app,kanban,todo,team,settings-accounts,calendar-modal,access-control}.js`.
- Constants removed: `APP_CSS`, `THEME_JS`, `LOGO_SVG` (`DashboardCommon.kt`) and `PAGE_CSS` (`Views.kt`).
  Every extracted file was diffed against the inline original it replaced and matches verbatim, except
  `theme.js`, which is now a self-invoking function — the old `THEME_JS` constant wrapped its body in
  `(function(){…});` without calling it, so the theme/accent toggle had never actually run.
- `appPage()` links `base.css` + `app.css` and loads `theme.js` + `app.js`; `loginPage()` links
  `base.css` + `theme.js`. Each page links only its own behaviour script.
- **`static/js/app.js` is new.** The legacy views used inline `onchange="this.form.requestSubmit()"`
  and `onclick="…"` attributes, which a strict CSP blocks. Those became data attributes —
  `[data-autosubmit]` and `[data-confirm="message"]` — handled by two delegated listeners on
  `document`, which also covers rows inserted later by fetch (the positions table). The Calendar
  modal's global `openTaskModal()`/`closeTaskModal()` + `window.onclick` became `[data-modal-open]` /
  `[data-modal-close]` markers read by `calendar-modal.js`.
- Server-rendered values that used to be interpolated into JS stay server-rendered: the Team popup
  reads a `<script type="application/json" id="memberDetailData">` island (data, not executable, so
  CSP-safe), and per-element messages live in attributes.
- CSP is now `style-src 'self'; style-src-attr 'unsafe-inline'; script-src 'self'`. The single
  remaining exception is inline *style attributes* — the progress bars (`style="width:42%"`),
  status dots and calendar task-bar colours are computed per row and would need a nonce-per-attribute
  or a class catalogue to remove. Inline `<style>` and `<script>` *elements* are blocked outright.
- `LOGO_SVG` was deleted as unused; the raster logo moved to `static/assets/` with a 301 from the old URL.

**Verified** (throwaway DB in `.stage7-verify/` from a copy of `pts-data.json.example`, port 8099;
the real `data/pts-data.json` and `data/pts.db` were not touched): all ten `/app/*` pages plus `/`
render 200; every `/static/**` asset returns 200 with the correct MIME type; `/static/mms-logo.png`
301s to `/static/assets/mms-logo.png`; zero inline `<script>`/`<style>` elements and zero
`on*=""` handler attributes in any rendered page; no CSP violations or console errors in a real
browser. Behaviour exercised in-browser: dark theme + accent cycle persist to `pts-theme`/`pts-accent`
and survive navigation, kanban move button relocates the card between columns, todo checkbox
auto-submits via `[data-autosubmit]`, todo columns are draggable, calendar modal opens and closes
through the delegated listeners, team popup renders from the JSON island, and the positions
add/delete fetch flow swaps `<tr>` fragments without a reload (204 on delete). `POST /app/tasks/status`
still rejects a status outside the whitelist. All 18 tests pass.

**Left for later stages:** the flash banner and nav still build HTML with `unsafe { +… }` for
inline SVG icons (data, not executable — fine). Config, logging, packaging and the `PTS_SEED_DEMO`
seeder followed in Stage 8; the launch pass is Stage 9 — both recorded below.

<a id="stage-8"></a>

## Stage 8 progress (config, logging, packaging, seeder, first-login gate)

Scope agreed for launch: configuration and logging, the run scripts and container path with
HTTPS/behind-proxy guidance, the `PTS_SEED_DEMO` wiring, and forcing a password change on first
login. All five are built and verified.

**Config (`config/AppConfig.kt`).** Three new knobs — `trustProxy` (`PTS_TRUST_PROXY`, default
false), `logDir`/`logLevel` (`PTS_LOG_DIR`/`PTS_LOG_LEVEL`) and `logRequests`
(`PTS_LOG_REQUESTS`, default false) — plus `warnings()`, which turns the combinations that only
bite once the host is public into startup advisories: Secure cookie without proxy trust, `0.0.0.0`
without proxy trust, no `PTS_ADMIN_PASSWORD`. `summary()` grew the matching banner lines and still
never prints the secret.

Real bug found on the way: `SESSION_COOKIE` was a `const val` in `Application.kt`, so
`PTS_SESSION_COOKIE` had no effect at all (a const is inlined at compile time and cannot be
initialised from config). It is now `private val SESSION_COOKIE = AppConfig.sessionCookieName`.

`PTS_LOG_DIR`/`PTS_LOG_LEVEL` deliberately bypass the `.env` layer: Logback configures itself from
the real environment before any Kotlin runs, so a `.env` value would have changed the startup
banner while the appender kept writing to `logs/`. The run scripts and Dockerfile export them as
actual environment variables, and the `.env.example` comments say so.

**Proxy support.** `install(XForwardedHeaders)` when `trustProxy` is on — not `ForwardedHeaders`,
which Ktor also provides: that one only parses RFC 7239 `Forwarded:`, while nginx (and every other
common proxy) sends `X-Forwarded-*`. `clientIp()` reads the forwarded header only under the same
flag, so the login limiter keys on the real client behind a proxy and on the socket peer when the
port is directly exposed. Both directions were verified: a forged `X-Forwarded-For` is ignored with
the flag off (the 6th attempt throttles) and honoured with it on (the forged IP is not throttled).

**Logging.** `logback.xml` now has STDOUT plus a `RollingFileAppender` at
`${PTS_LOG_DIR:-logs}/pts.log` with `SizeAndTimeBasedRollingPolicy` (daily, split again at 10 MB,
gzipped, 14 days, 200 MB cap) and root level `${PTS_LOG_LEVEL:-INFO}`; Exposed and Hikari are
pinned to WARN so SQL chatter needs a deliberate downgrade. `CallLogging` is installed only when
`PTS_LOG_REQUESTS=true`, with `disableDefaultColors()` (a log file must not carry ANSI codes) and
`disableForStaticContent()`.

**Seeder.** `db/migration/DemoSeeder.seedIfEnabled()` is the missing half of `PTS_SEED_DEMO`, which
until now was read by `AppConfig` and used by nothing. It runs in `bootstrapDatabase()` only after
the JSON import was skipped, and refuses unless the `users` table is empty and no legacy JSON is
waiting: `JsonImporter.databaseIsEmpty()` was extracted so both the importer and the seeder share
one definition of "empty". Verified: an empty DB with the flag on produces 6 users / 16 projects /
10 tasks; a populated DB logs "demo seed skipped" and changes nothing.

**First-login password change.** `UserSession` carries `mustChangePassword`, populated by
`currentSession()` from `AuthService.mustChangePassword`, and 35 authenticated routes moved from
`call.currentSession() ?: return` to `call.requireSession() ?: return`, which redirects a flagged
account to `/app/settings`. The allowlist (`/app/settings`, `/app/settings/password`) is what makes
the gate escapable: without it the only form that clears the flag would itself be blocked. Settings
shows the reason inline in the Change Password card rather than as a page-top banner, and the login
page's footnote now states the real behaviour instead of the legacy "password is your employee
number" hint. *(**Stage 99** retired the whole paragraph: the card, the POST, the allowlist and the
session's copy of the flag are deleted, and `requireSession()` only asks whether there is a session.
The `must_change_password` column is still written and still readable — it records how a password
came to be — but nothing acts on it. See Stage 99.)*

**Packaging.** `Dockerfile` (temurin 21 JDK → JRE, non-root `pts` user, `installDist` directory
copied rather than a fat jar so the SQLite native driver and `logback.xml` keep working, volumes
for `/app/data` and `/app/logs`, `HEALTHCHECK` on the login page), `docker-compose.yml` (app +
nginx TLS, app published on `127.0.0.1:8081` only, `PTS_SESSION_SECRET:?…` so compose refuses to
start without it), `deploy/nginx.conf` (TLS plus exactly the four headers `trustProxy` expects),
`deploy/run.bat` + `stop.bat` + `run.sh`, `deploy/use-local-name.ps1` (idempotent hosts-file
add/remove/status for a one-PC private name, because the hosts file is Administrators-only and hand
editing it is the thing users get wrong), `.env.example`, `.dockerignore`, and `.gitignore` rules
for `.env`, `data/*.db*`, the importer's `.migrated-*` archive, `deploy/certs/`, and the hosts
snapshots that script writes.

**Three findings that changed the scripts:**

1. Gradle's generated `build/install/murray-pts/bin/murray-pts.bat` cannot start this app on
   Windows: it expands all 71 dependency jars into a single `set CLASSPATH=` line, and cmd's
   8191-character limit rejects it with "The input line is too long". Proven by tracing the
   launcher (`set DEBUG=echo`). `deploy/run.bat` therefore launches
   `java -cp "build\install\murray-pts\lib\*" mn.murray.pts.ApplicationKt` — the JVM expands the
   wildcard, so line length stops mattering. The POSIX launcher is unaffected and is what `run.sh`
   and the Dockerfile use (via `sh` because an NTFS copy can lose the `+x` bit).
2. Booting a completely empty database crashed: `users.employee_number` has a real FK to
   `team_members`, and `AuthService.ensureSuperAdmin()` inserted the admin before any roster row
   existed (`SQLITE_CONSTRAINT_FOREIGNKEY`). It now upserts the `team_members` row first, the way
   `UserService.addUser` already did. `FreshDatabaseBootstrapTest` pins this because nothing else
   in the suite ever booted a truly empty DB — every other test seeds first, which is exactly why
   the bug survived until a launch rehearsal.
3. `deploy/use-local-name.ps1` is pure ASCII, and that is load-bearing rather than tidy: Windows
   PowerShell 5.1 decodes a BOM-less `.ps1` as ANSI, so the typographic dashes in the first version
   turned into `Unexpected token 'nothing' in expression or statement` and the script died before it
   wrote a single log line. Two live probes proved elevation itself was fine (`admin: True`, hosts
   `write test: ALLOWED`), which is how the fault narrowed to encoding. The same script logs to an
   absolute path under `deploy/` rather than a `$env:TEMP` default for a reason that showed up during
   this very debugging: the elevated child's cwd is `C:\Windows\system32`, and Git Bash's `$TEMP`
   resolves to `/tmp`, not to `C:\Users\user\AppData\Local\Temp`, so a "read the log back" step failed
   to find a file that was never written for an entirely different reason. A fixed, checked-in-tree
   path has one meaning on every side of the UAC boundary.

<a id="stage-9"></a>

## Stage 9 — launch verification

`./gradlew test installDist` green: **24 tests across 9 classes, 0 failures, 0 errors**
(`MigrationSmokeTest`, `JsonImporterTest`, `FreshDatabaseBootstrapTest`, `AuthorizationTest`,
`CsrfTest`, `LoginRateLimiterTest`, `RoleTest`, `AuthServiceTest`, `MustChangePasswordTest`) and a
71-jar distribution in `build/install/murray-pts/`. The first run of this pass failed to compile —
the rewritten login footnote had been written as `+"A" + "B"`, and inside the kotlinx.html DSL the
first `+` is unary (yielding `Unit`), so the binary `+` had nothing to concatenate; it is now two
`+"…"` statements. Worth remembering as the shape of this file: an edit that looks like ordinary
Kotlin string concatenation is not valid here.

Then the shipped distribution (not the dev classpath) was booted against a throwaway directory on
`127.0.0.1:8095` with `PTS_ENV=production`: Flyway applied V1, the super admin bootstrapped with
`mustChangePassword=true`, exactly the two applicable launch advisories printed (the `0.0.0.0` one
correctly suppressed for a loopback bind), responses carried HSTS + the CSP with `script-src 'self'`,
per-request INFO lines landed in `logs/pts.log`, the gated admin's `POST /login` returned 302 to
`/app` → `/app/projects` → `/app/settings` (two hops before the gate bites, because `/app` is
itself only a forward), `/app/team` and `/app/access-control` bounced to `/app/settings` the same
way while `/app/settings` itself returned 200, and the sixth failed login returned 401 with
"Too many failed attempts. Try again in 40s." The full happy path (change the password in the
browser, watch the notice disappear and `/app/projects` open) was confirmed in a real browser on the
demo-seeded instance.

**Deliberately left as known gaps** (documented in README, not fixed): `Csrf` is built and tested
but wired into no route; the rate limiter and session store are per-process, so one instance only;
`PTS_HOST` still defaults to `0.0.0.0`, which is correct for Docker and wrong for an unproxied
laptop, so the README tells operators to bind loopback; `HEAD /` answers 405 because the routes are
GET-only, so health checks must use GET.

**Repository hygiene to settle before the first push** (found while verifying, not changed):

- `data/pts.db`, `data/pts.db-shm`, `data/pts.db-wal`, `data/pts-data.json.pre-run-backup-*` and
  `data/import-seed.json.migrated-*` are staged in the index. `.gitignore` covers them now, but
  git keeps tracking anything already added — they need `git rm --cached` before a commit.
- `data/pts-data.json.backup-predelete` is **already committed** (`69397ad`, which is also
  `origin/main`), and it contains the legacy account rows with their `password` values. Removing it
  from the index going forward does not remove it from history; treat those credentials as exposed
  and decide between rewriting the published history and rotating the accounts.
- `MMS/` is a nested clone of `https://github.com/anand112/MMS` recorded as a gitlink
  (mode 160000) with no `.gitmodules` entry, so a fresh clone gets an empty directory and
  `git submodule update` cannot work. It also carries a second copy of `data/pts-data.json` on disk.
- `build_old/` holds six stale v1 resource files (the CSS/JS that Stage 7 deleted), still tracked.
- Declared but unused dependencies: `ktor-server-sessions`, `-auth`, `-cors`, `-compression`,
  `-status-pages`, `-content-negotiation`, `kotlinx-serialization-json`, `dotenv-kotlin`
  (`AppConfig` parses `.env` by hand) and `exposed-dao`/`exposed-json`. Dropping them shortens the
  classpath and the Docker layer, but nothing breaks if they stay.

<a id="stage-10"></a>

## Stage 10 — due-date alerts (post-launch feature)

The ask was: when a project or task has a due date arriving and it is not 100% finished, tell the
assigned manager and the assignees — by email, once a day, starting 7 days out. Delivered as
`service/DueDateAlertService` + `service/DueDateAlertScheduler` + `service/Mailer` +
`repository/AlertRepository` on top of the new `due_date_alerts` table (`V2__due_date_alerts.sql`).
Three deliberate design decisions are worth remembering, because they are the parts that are not
obvious from the code:

1. **The ledger is the schedule.** `due_date_alerts` has `UNIQUE (kind, item_id, recipient_emp_no,
   alert_date)`, and `AlertRepository.record` returns `false` only for a `UNIQUE constraint failed`
   on that table — "this person already got this item today" — and rethrows anything else. The first
   draft was `runCatching { insert }.isSuccess`, which would have returned `false` for *any* SQLite
   error and silently dropped alerts; detecting the duplicate means walking the cause chain, because
   Exposed wraps the driver exception and SQLite states the reason only in its message text. With that
   invariant in place, the sweep can be run by the scheduler, by an operator pressing the admin route,
   or by a restart after a crash,
   any number of times a day, and nobody receives a duplicate — no in-process "last run" state is
   needed. `alert_date` is a UTC **calendar day**, not a timestamp, which is what makes the key work
   (`DueDateAlertService.today()` is `LocalDate.now(ZoneOffset.UTC)`), and it is pinned explicitly
   rather than inherited: the launchers do set `-Duser.timezone=UTC` (`applicationDefaultJvmArgs`,
   `deploy/run.bat`, and the generated `installDist` start scripts), but a container started without a
   `TZ` would still write the same day because the service never asks the host. `sent_at` is an
   ISO-8601 instant, so it reads UTC either way.
2. **Rules are separated from transport.** `DueDateAlertService` decides who hears about what and
   renders the text; `Mailer` only knows how to hand a plain-text message to SMTP and never throws
   (`MailResult.Sent | LoggedOnly | Failed`). So with no `PTS_SMTP_HOST` — every developer machine,
   and this project today — the same message is rendered into `logs/pts.log` and stored in the
   ledger with `channel = 'log'`, and the whole feature is verifiable without a mail server.
   That is how the wording defect ("…is due on 2026-10-18, in 6 day(s), and it is not finished yet.")
   was found: it was visible in real swept output before any SMTP existed to blame.
3. **Open-ended window, not a due-date event.** `AlertRepository.openProjects()` / `openTasks()`
   return rows whose status is not `Completed` — that filter is applied in Kotlin after the row is
   mapped, not in SQL, so the status spelling lives in one readable place. The sweep then drops
   anything whose due date is still more than `PTS_ALERT_LEAD_DAYS` away, and drops projects at
   `progress >= 100` even if their status has not been updated. There is no lower bound, so an overdue
   item keeps alerting daily until it is closed — with escalating wording (`Due in N days` →
   `Due today` → `OVERDUE by N days`, built by `labelTiming`, which returns the subject and the body
   phrase together so they cannot drift). Unparseable or blank due dates are skipped rather than
   guessed: legacy rows carry `12/10/2026`, and a wrong deadline is worse than no alert.

Recipients come from the data as it is modelled, not from a new table:
`projects.manager_emp_no` plus every `project_assignees.employee_number`; for a task,
`tasks.assignee_emp_no` plus its project's manager (both blank-tolerant and de-duplicated, so a
manager who is also the sole assignee gets one mail, not two). Addresses come from `users.email` and
display names from `team_members.name`, so a roster member with no account is still recorded
(`delivered = 0`, `error` contains "no email address") instead of vanishing from the audit trail.
A project with nobody attached at all appears in the sweep report as an explicit detail line
("… has no manager or assignees to tell"); an unassigned task is simply skipped, because a task's
only recipients are its assignee and its project manager.

The scheduler is one coroutine on `Dispatchers.IO`, started in `main()` just before
`server.start()` and cancelled by the existing shutdown hook, with a 30-second startup delay so a
boot does not sweep before Flyway and the importer settle. It is intentionally **not**
leader-elected: a second instance would run a second sweep, which the ledger makes safe but wasteful
(README states this under the multi-instance caveats, next to the per-process rate limiter and
session store). The sweep itself is a plain non-suspend `runSweep()` guarded by a `ReentrantLock`, so
the scheduler and the admin trigger serialize instead of racing the dedupe — the first draft had a
coroutine `Mutex` plus a `runSweepBlocking` that *skipped* a concurrent sweep, and that was wrong: a
serialized second sweep is exactly what you want, since the ledger already holds the day and the
second run just reports "already sent today".

Three admin-only plain-text routes drive it by hand: `POST /app/alerts/run` (returns the full sweep
report including per-item detail), `GET /app/alerts/recent` (mode, last sweep, ledger rows) and
`POST /app/alerts/test` (one delivery probe). All three sit behind `Authorization.canAdmin` and
answer 403 to everyone else, 302 to login when unauthenticated.

The test probe exists because "is the mail path configured?" and "would these rules fire?" are
different questions, and answering the first by running the second means mailing everybody who
happens to have open work.
`DueDateAlertService.runDeliveryTest()` calls `Mailer.send` directly and never touches
`AlertRepository`, so it cannot consume an item's once-a-day budget; its body prints the mode,
host/port, security, login and sender it actually resolved — the config precedence rules make "did
`.env` reach the process?" a real question — plus `testSubject()`/`testBody()` are pure functions, so
the wording is unit-testable. `?to=` is optional and falls back to `emailFor(session.employeeNumber)`,
the signed-in admin's own address on file: a mistyped command should mail yourself, not a colleague.
No address on file and no `?to=` is a 400 naming Access control, not a silent no-op.

Config lives in the `PTS_ALERT_*` / `PTS_SMTP_*` block of `AppConfig`. `alertsEnabled` defaults to
true, and `warnings()` prints three non-production-gated advisories (enabled-but-no-host,
host-but-no-sender, and host-set-but-no-password-while-not-in-dry-run) rather than refusing to boot,
because log-only is a legitimate way to run. The third one exists because that is the exact
half-configured state where every alert fails at authentication and the only trace is `delivered = 0`
rows in the ledger. `PTS_ALERT_LEDGER_KEEP_DAYS` (default 90) trims history inside the scheduler loop.

Verified, not asserted: Flyway applied V2 to a copy of the real database ("1 migration(s) applied,
schema at version 2"); a sweep over that copy found 14 open projects / 8 open tasks, 5 in-window
items, 8 alerts attempted, all `logged only`; a **restart** against the same file reported
"0 alert(s) attempted … 8 already sent today", which is the dedupe surviving process death; and 14
`DueDateAlertServiceTest` cases take the suite to **38 tests in 10 classes** — each on its own real
SQLite file, with `pts.smtp.dry.run` forced in `@BeforeEach` so no test can reach the network or
depend on a developer's `.env`. `MigrationSmokeTest` now expects `due_date_alerts` in the table set.
The three routes were then driven with `curl` against a throwaway instance on a temp database:
`/app/alerts/run` and `/app/alerts/recent` as Admin, and `/app/alerts/test` in all four states —
200 with the log-only report, 403 for a Manager, 302 for an anonymous POST, and 400 when the account
has no `users.email` and no `?to=` was given. The ledger row count was identical before and after the
test send, which is the "cannot spend today's alert" claim checked rather than assumed.
Nothing in this work wrote to the real `data/pts.db`; the live instance's own post-restart sweep
added no rows because its 8 items were already recorded for `2026-10-12`. The counter bug the first
test run exposed (bare `Outcome.SKIPPED -> skipped` reads instead of `skipped++`, so every delivery
counter stayed 0) is fixed and covered.

<a id="stage-11"></a>

## Stage 11 — data reset, one-PC name, and publication

Three follow-ups after Stage 10, all on 2026-10-12, none of them code changes to the app:

**Project and task data deleted on request.** `projects`, `tasks`, `project_assignees`, pins and the
`due_date_alerts` ledger were cleared in one transaction with `PRAGMA foreign_keys = ON`, leaving the
roster (15 team members / 15 accounts / 16 positions), buckets, forecasts, lessons and to-do lists
intact. Verified afterwards on an isolated copy that every page still renders and that a sweep reports
`reviewed 0p/0t`. This is why the Stage 10 note about "8 items already recorded for `2026-10-12`" is a
snapshot of that moment rather than current state.

**`murraypts.mn` as a one-machine name.** The hosts route in `deploy/use-local-name.ps1` (its
PowerShell-encoding finding is recorded under Stage 8's packaging notes). Nothing in the app reads the
name, so port 8080 and the loopback bind are unchanged; what was proven is the flow through the name:
login, the must-change gate, `/app/projects` 200 and `POST /app/alerts/run` 200 on a throwaway DB.

**Publication as `github.com/anand112/MurrayPTS`.** Because `origin`'s published history contains
legacy password rows, the new repo was built from a **parentless single commit** rather than the
existing two commits, so no credential-bearing blob ever enters it. The tree was assembled in a
throwaway `GIT_INDEX_FILE` (`git read-tree --empty` + `git add -A`), which makes `.gitignore` apply to
everything at once — with those files already tracked in `main`, the ignore rules had no effect on them
there, which is exactly the trap README item 1 described.

Two things that came out of assembling that tree:

- **`data/pts-data.json.example` was not an example.** A count-only comparison (no values printed)
  showed all 63 of its person names and all 23 `EMP####` numbers present in the real legacy export, so
  the tracked "example" was the production roster with `password` emptied. It is now generated synthetic:
  101 identity values mapped to `Team Member NN` / `Sample Project NN` / `Role NN` / `Department NN` /
  `Bucket NN` / `PRJ-NN` / `type-a…` placeholders, 38 free-text fields replaced, `EMP0000…` numbers kept
  because the importer's FKs resolve through them and `JsonImporterTest` asserts on `EMP0000`. The
  rewrite walks the parsed object and replaces every value, so no string can pass through untouched, and
  it refuses to write unless three independent scans come back empty (exact value equality, substring
  scan on values ≥4 chars, raw quoted-token scan) with the section counts identical before and after:
  23 team / 6 access / 16 projects / 5 tasks / 22 positions / 2 buckets / 3 todo lists + 2 items /
  3 forecasts / 3 lessons, and 32 `EMP` tokens preserved. The real export stays local as
  `data/pts-data.json.example.pre-synthetic`, matching the ignored `data/*.json.*` rule. `./gradlew test`
  after the rewrite: 38 tests, 0 failures, and `build/install/…` untouched so the live instance was
  never at risk.
- **A latent `.gitignore` ordering bug.** `!gradle/wrapper/gradle-wrapper.jar` sat on line 4, above the
  `*.jar` rule on line 44. Last match wins, so the negation was dead and the wrapper jar had never been
  committed — a fresh clone of either repo could not run `./gradlew`. It now sits below `*.jar`, and the
  jar is in the published tree.

<a id="stage-12"></a>

## Stage 12 — nine project categories and the create-project form (2026-10-12)

The board moved from four free-text types to **nine letter-coded categories**, and creating a project
stopped being a bare name+type form. Four questions were settled with the user before writing code:
the form is a **modal on the same page** (not a route), the project number is **typed by the user and
auto-suggested** (not assigned silently), the **description is the project title** (no second name
field), and revenue is **a single amount with no currency**.

| Letter | Category | | Letter | Category |
|---|---|---|---|---|
| `M` | Fabrication/Manufacturing | | `E` | Engineering |
| `R` | Repair/Maintenance/other services | | `F` | Internal works |
| `S` | Site projects | | `G` | Goods sales |
| `L` | Labor supply | | `O` | Other |
| `D` | Distributor product sales | | | |

**Where each piece lives.** `domain/Project.kt` owns the model: `PROJECT_TYPES`, `TYPE_PREFIX`,
`typeLetter`/`typeLabel`, `CODE_PATTERN` + `normalizeProjectCode`/`isValidProjectCode`/
`codeMatchesType`, `nextProjectNumber`/`nextProjectCode`, and the two legacy translators
`migrateType` (`Manufacturing` → `Fabrication/Manufacturing`, `Repair/ Maintenance/ Other Services` →
`Repair/Maintenance/other services`, `Labour supply` → `Labor supply`) and `migrateCode`
(`SIT-01` → `S-01`). `type` still stores the label so the board needs no lookup; the letter is derived
and leads the number. The same three rules are expressed three times on purpose and each has one job:
V3 rewrites the database, `LegacyNormalizer` step 1b rewrites an imported JSON store, and
`nextProjectNumber` normalises defensively so an un-migrated row still counts towards its category.

**Schema (V3).** `projects` gains `client`, `start_date`, `revenue REAL` — all `NOT NULL` with
blank/`0` defaults, because SQLite's `ALTER TABLE ADD COLUMN` cannot add a NOT NULL column without a
constant default — plus `idx_projects_client`, the label/code rewrites above, and it is the first
migration in this project that changes row data rather than only shape.

**`due` is the end date.** There is no `end_date` column. `Calendar.kt`, `AlertRepository.openProjects()`
and `DueDateAlertService` all key off `projects.due`, so renaming it would move the alerting contract for
no benefit; the UI labels the field "End date" and the modal says alerts watch that date. The choice is
documented in `V3__project_categories.sql`, `db/Tables.kt`, the `Project` DTO and `ProjectService.create`.

**Validation and the view.** `ProjectService.create(ProjectDraft)` returns `CreateProjectResult(projectId,
errors: Map<ProjectField,String>, values)` — one message per field (`ProjectField` enum), nothing written
unless all of them pass: category in the whitelist, number non-blank/well-formed/letter-matching
(`existingCodes()`, compared upper-cased)/unique, description non-blank, both dates ISO and not reversed,
manager a roster member or blank, revenue parseable and non-negative. `Projects.kt` renders the modal with
those messages under their fields and re-populates what was typed. Status defaults to **Not started**
server-side (`ProjectService.DEFAULT_STATUS`), so it holds even if the select is bypassed. *(Since **Stage 96** that
default is also the picker's first option, so a bypassed select and a manager who touches nothing get the same word; the
`Ongoing` this form used to lead with is no longer on the whitelist, and a stale page posting it falls back here — proved
over HTTP.)*

**CSP constraints shaped the modal.** The open state is server-rendered (`style="display:block"`, allowed
by `style-src-attr 'unsafe-inline'`), so a rejected submit comes back with the popup already open; the
behaviour is `/static/js/project-modal.js` (`script-src 'self'`, driven by `data-project-modal-open`,
`data-modal-close`, and per-option `data-suggest`/`data-letter`). A category change replaces the offered
number only while it is still untouched (`data-autofilled`), so a hand-typed number survives switching
categories. *(**Stage 91** removed the offer from the **value** altogether: the box now opens empty and the
next free number is only its placeholder, so there is nothing to replace and no `data-autofilled` left. The
input was typed — initial included — in both stages; this only stops the app writing the first characters for
the reader.)*

**Verification.** `./gradlew test` → BUILD SUCCESSFUL, 56 tests in 13 classes, all passing:
`ProjectServiceTest` (12, real SQLite, one case per rule), `ProjectCategoryTest` (3, the pure
category/code helpers), and `ProjectCategoriesMigrationTest` (3, new) which builds the schema up to **V2
only** via `Flyway … .target("2")`, inserts rows in the pre-V3 shape, then runs the real
`Migrations.migrate` — proving a live `data/pts.db` gets `SIT-04`→`S-04`, `Manufacturing`→
`Fabrication/Manufacturing`, neutral defaults on the new columns, and that already-current or
hand-edited values survive a re-run untouched. Browser-driven checks ran against a throwaway instance
(port 8095, `%TEMP%` DB, demo-seeded, alerts off): nine tabs, suggestions continuing the used numbers
per category, a valid POST landing on the new detail page with client/start/end/revenue rendered, and
the multi-error submit rendering all five messages under their own fields with the modal open.

**Not done here.** The live 8080 instance still runs the pre-V3 build — deploying needs the JVM stopped
first (never `installDist` over a running one), and that boot is where V3 rewrites the real rows.
`POST /app/projects` still has no role gate, same as before this stage.

<a id="stage-13"></a>

## Stage 13 — the task board's card menu, and the notification bell (2026-10-13)

Two UI asks, one of them a whole new aggregate: managers stopped seeing Edit/Delete buttons on every
kanban card and instead open them **from the card**, and every signed-in account gained a **bell in the
top-right** that tells them when a task or project has been given to them — in the app and by email.

**Card menu, not card buttons.** `TaskBoard.taskCard()` now carries the card's own data as `data-*`
attributes (`data-task-card` + title/project/assignee/started/due/priority/status) and a `⋯` tab for a
manager; the buttons themselves are gone. One `taskCardMenu()` element is rendered **per board**, not
per card — one hidden `taskId`, one `back`/`projectId` pair, filled in by `task-modal.js` from whatever
card was clicked. That is why deletion became field-based (`POST /app/assigned-tasks/delete`) instead of
`/app/assigned-tasks/{id}/delete`. The menu is `position: fixed` and positioned from
`getBoundingClientRect()`, so it needs neither an offset parent nor scroll maths, and it closes on
outside click, Escape, scroll and resize. `data-confirm` is rewritten from the clicked card's
`data-title` at click time, because `app.js` reads that attribute when the click happens.

**The project page lost its assignees box** (the `project_assignees` table, the domain field and the
alert rule that read it all stay — a manager is now the person a project is assigned to) and its
"Project manager" panel moved **below** the task board.

**Notifications are the alert ledger's shape, applied to an inbox.** `V4__notifications.sql` mirrors
`due_date_alerts`: `UNIQUE (recipient_emp_no, kind, item_kind, item_id, dedupe_day)` is the once-per-day
rule, the row is **claimed before** `Mailer` is called so a retry cannot mail twice, and a UNIQUE
violation is still detected by walking the cause chain for `UNIQUE constraint failed`. `read_at = ''` is
the unread test — no boolean column, and the badge is a `COUNT` on the same predicate. `actor_emp_no`
deliberately has **no FK**: a notification must survive somebody leaving the roster, and it is only ever
used to name the actor. `link` is written by `NotificationService` alone, and the one route that follows
it re-checks the `/app/` prefix anyway.

**Three events, two rules.** `taskAssigned` (create, and only when an edit *moves* the card),
`projectManagerAssigned` (create, and only when `setManager` actually changes hands — compared against
`ProjectRepository.managerOf`), and `dueDate`, the inbox copy of a reminder the sweep already mailed: it
sends no mail of its own and records `mail_status = 'inbox only'`. Everywhere, **nobody is notified about
their own action** (the actor's employee number travels from the route through the service into
`deliver`, which drops `recipient == actor`), and **one row per person, per item, per day**. Renaming a
card, dragging its status, and losing an assignment all notify nobody.

**A notification may never break the write that caused it.** Every event body runs inside
`guard(what){…}`: a failure is logged, the task save still succeeds. `safeLink` keeps the dropdown from
becoming an open redirect, and `markRead` / `markAllRead` carry `recipient_emp_no` in their `WHERE`
clause, so a hand-typed id marks nothing and leads nowhere.

**The scheduler got a shorter interval, not a second timer.** `PTS_ALERT_INTERVAL_HOURS` (24) became
`PTS_ALERT_INTERVAL_MINUTES` (5) — the sweep is now also what feeds the bell, and a due-date note that
arrives tomorrow is not useful. Neither the mail nor the inbox multiplies, because both dedupe on the
calendar day rather than on the run. `NotificationService.purgeOlderThan` (`PTS_NOTIFICATION_KEEP_DAYS`,
30) joins the ledger trim in the same loop, behind one `trimSafely` helper so neither can end it.

**The bell renders with the page.** `DashboardCommon.appPage()` puts `notificationBell(session, section)`
into a new `.page-right` wrapper after that page's own actions, so every authenticated page has it and
none has to remember to. There is no fetch endpoint: the newest 20 rows are server-rendered, the panel
ships with `hidden="true"`, and `/static/js/notifications.js` only flips that attribute (CSP: no inline
handlers, no inline styles needed). "Mark all read" posts a server-rendered hidden `back` — the section's
own nav path — rather than trusting the referrer, and re-checks the `/app/` prefix on the way out.

**Verification.** `./gradlew test` → BUILD SUCCESSFUL, **102 tests in 17 classes**. New:
`NotificationServiceTest` (15, real SQLite with SMTP forced into dry run — the assignee is told and the
manager is not, self-assignment is silent, six touches of one card are one row, a rename is silent where
a move speaks, `logged` vs `no-address` vs `inbox only` recorded on the row, read-marking and links
scoped to the owner and to `/app/`, the 20-row window newest-first, and `0` disabling the trim) and
`NotificationBellRenderTest` (7, the markup contract above). `MigrationSmokeTest` now requires
`notifications` to exist.

**Not done here.** The live 8080 instance still runs the pre-V4 build until it is restarted, which is
where the `notifications` table appears. The bell has been verified by rendering and by the migration,
not yet by a signed-in pair of eyes. `CLASS_DIAGRAM.md` still describes the deleted assignee routes and
`alertIntervalHours`.

<a id="stage-14"></a>

## Stage 14 — the Team page groups by department, and a card has no buttons left (2026-10-13)

Two follow-ups to Stage 13, both from looking at the pages rather than the code.

**The Team page is sorted by department.** The roster is stored in hire order, which reads as shuffled
once fourteen departments are on one screen. `DashboardCommon` now owns the ordering —
`teamByDepartment()` (department A→Z, then name) and `teamGroupedByDepartment()` (`groupBy` preserves
insertion order, so sorting first is what makes the *headings* alphabetical without a second sort of the
keys) — and `departmentLabel()` files a blank department under `UNASSIGNED_DEPARTMENT` so nobody lands in
an empty cell at the end. `Team.teamContent()` prints a full-width `tr.dept-row` above each group with its
headcount, and keeps the per-row Department cell, because the numeric columns' widths come from the member
rows and the grid should not have to change shape. The label is **highlighted**, not just printed: a
`--accent-soft` band with a 3px `--accent-strong` rule on its left, the name in 800-weight uppercase in
`--accent-strong`, and the headcount as a `--card-bg` pill — the same four tokens the active nav item and
the manager role chip use, which is why it needs no `html[data-theme="dark"]` override and re-colours when
the accent cycle changes. Being CSS-only, it is not covered by the render tests. The same grouping drives every member picker through
`kotlinx.html.SELECT.optGroupPerMember(selectedValue, valueOf)`: the Team assign form, the task popup's
Assign to, and both project manager selects. `valueOf` differs per form on purpose — the assign and task
forms post an **employee number**, the manager forms post a **name**, which is what their routes look up.

**A manager's card now carries no standing buttons at all.** Stage 13 took Edit and Delete into the click
menu but left the `← / →` status arrows on the card, so a board still read as a wall of little buttons.
`taskCard()` renders `.kmove` only when `movable && !canManage`: a non-manager assignee has no menu, so
their arrows are their only way to change status without dragging. The manager's move went into
`taskCardMenu()` as the form it already was in the task rows — `POST /app/tasks/status` with a hidden `id`
and a `data-autosubmit` select over `TASK_STATUSES`, which `app.js` submits on `change` by delegation, so
no inline handler and no CSRF surface. `task-modal.js` sets that hidden `id` (a different field name from
the delete form's `taskId`, because the status route was written for the rows and keeps its own) and the
current option from the clicked card's `data-status`.

**Verification.** `./gradlew test` → BUILD SUCCESSFUL, **109 tests in 18 classes**. New:
`TeamPageRenderTest` (6, a five-member roster inserted out of alphabetical order — headings in order,
people under their own group and in name order, `1 person` vs `2 people`, the blank department labelled,
`<optgroup>`s in the assign form, and nobody lost or doubled). `TaskBoardRenderTest` grew two cases: a
manager's board has no `kmove-btn` anywhere and an assignee's still does, and the menu carries
`action="/app/tasks/status"` with `data-autosubmit`, a hidden `name="id"` and all five statuses.
`createHTML()` **indents** its output, so the new adjacency assertions match across whitespace with
`Regex(… \\s* …)` instead of concatenated literals — the older tests only ever checked `indexOf` ordering,
which is why this had not surfaced.

**Not done here.** Nothing in the data changed, so there is no migration and the live DB is untouched by
this stage. Whether the arrows are worth keeping for a manager who prefers one click over
click-then-pick is a taste call the user can reverse by dropping the `&& !canManage`. *(**Stage 103** answered the taste
call the other way: the `← / →` pair is gone from both status boards, and `taskCard()` renders `.kmove` only on a
project's lane board, where the single **✓ Done / ↺ Reopen** is a different act from moving a card.)*

<a id="stage-15"></a>

## Stage 15 — projects, managers and assigned tasks are management's call (2026-10-13)

"Only management can set the manager of a project and create projects and tasks." An audit found the
promise was already kept everywhere except one place, and that the one place was the important one.

**The hole was create-project, not set-manager.** `POST /app/projects/{id}/manager`,
`POST /app/assigned-tasks/save`, `POST /app/assigned-tasks/delete`, `POST /app/team/assign` and the Team
quick-add all begin with `if (!Authorization.canManage(session.employeeNumber)) return@… redirect`, and
`TaskService` repeats the check as `TaskField.PERMISSION`, so a caller that skips the route still cannot
write. `POST /app/projects` had no such line: anyone with a session and a hand-built form could make a
project. It now redirects to `/app/projects` before `call.receiveParameters()`, in exactly the shape the
manager route already used. **Hiding markup was never the check** — the absent `+ New project` button is a
courtesy, the route is the promise.

**Gated at the route rather than in `ProjectService`.** `ProjectField` has no `PERMISSION` value, so
mirroring `TaskService` meant a new field, a new message, modal plumbing to print it and a rework of the
notification tests — all to protect a write with exactly one caller. The route-level redirect is what every
other management-only write here already does, so this one joins them instead of inventing a second style.

**The Viewer's pages lose the writers and keep the readers.** `GET /app/projects` computes `canManage` once
and passes it twice: `actions = { if (canManage) newProjectButton() }` and
`projectsContent(session, type, canManage = …)`, which renders `projectCreateModal` only for a manager — so
the page contains no `id="projectModal"`, no `data-project-modal-open` and no `action="/app/projects"` form
at all. The rejected-submit re-render passes `canManage = true`, which is sound rather than careless: that
branch is reachable only after the route's own role check has passed. The project page already behaved this
way (`newTaskButton`, the card menu, the "Set manager" form and the task popup all sit behind `canManage`),
so `projectDetailContent` needed no change.

**What deliberately stayed personal.** The scope question was asked and answered as *project + assigned
tasks only*: `/app/other-tasks` (the unassigned backlog) and `/app/todo` are a person's own lists and remain
open to every account, an assignee may still drag their own card or use its `← / →` arrows, `POST
/app/tasks/status` still accepts `caller == assignee`, and the ☆ Pin button stays — someone who cannot
create a project can still keep one they read in the sidebar.

**Verification.** `./gradlew test` → BUILD SUCCESSFUL, **114 tests in 19 classes**. New:
`ProjectsPageRenderTest` (5, on the same real-schema SQLite harness — a Viewer's board lists every project
but carries no popup, no opener and no create form while a manager's carries all three, one `data-suggest`
per category and an `<optgroup>` per department; a rejected create comes back open with its message under
its field, its typed number preserved and its chosen manager still selected; a Viewer's project page has no
"Set manager" form but keeps the pin; a manager's has the picker, the current holder pre-selected, with the
"— No manager —" escape). A render test cannot prove a route gate, so the gate was checked over HTTP
against a throwaway database under `%TEMP%` on port 8095 — fresh V1→V4 schema, `PTS_SEED_DEMO=false`, the
legacy JSON path pointed at a file that does not exist, config supplied only as real environment variables,
SMTP in dry run and the alert sweep off, so nothing could reach the mailbox or the real `data/`. The
bootstrapped admin created one `Viewer` and one `Manager` through `POST /app/access-control` and each role
got its own cookie jar. Observed on the wire: a Viewer's `POST /app/projects` → `302 /app/projects` with
nothing appearing on the board, a Manager's → `302 /app/projects/1`; a Viewer's `POST /app/projects/1/manager`
→ `302 /app/projects` with the manager row still `—`, a Manager's → `302 /app/projects/1` and the row reads
the new name; a Viewer's `POST /app/assigned-tasks/save` creates nothing, a Manager's creates the card, and
a Viewer's `POST /app/assigned-tasks/delete` leaves it in place; the same Viewer still opens
`/app/other-tasks` and `/app/todo` and posts a To-do item normally, and their Team page shows no quick-add
button where a manager's shows three. That instance is stopped and its directory deleted; 8080 now serves
the gated build.

**Not done here.** There is still no automated HTTP-level test, so the paragraph above is a manual procedure
worth repeating after any change to these routes. `CLASS_DIAGRAM.md` is out of date in the same two ways
Stage 13 left it (the deleted assignee routes, `alertIntervalHours`) and now also says nothing about the
management-only gates. And a `Manager` still cannot *edit* an existing project's client, dates or revenue —
there is no edit form for projects at all, only create and set-manager.

<a id="stage-16"></a>

## Stage 16 — every category panel prints on one column grid (2026-10-13, live)

"Make the projects display line up with one another" turned out to be a layout bug rather than a request
for a different board: the Projects page renders **one `<table>` per category panel**, and the browser's
automatic table layout sizes each table from its own contents. So the Maintenance group's "Project"
column started at one x and the Site group's at another, and reading two panels down the page meant
following columns that did not line up. Asked which fix they wanted, the user chose **the same column
grid across groups** — keep the panels, make the columns agree — explicitly not a single flat list.

`projectTable()` now emits a `<colgroup>` of nine classed `<col>`s (`c-code c-name c-client c-status
c-progress c-manager c-due c-rev c-pin`) and repeats those classes on every `<th>` and `<td>`. The
widths live in `app.css` as percentages **summing to exactly 100**, on `table.projects-table {
table-layout: fixed }`. Three details that are easy to get wrong from here:

- **The rules are scoped to `.projects-table`,** because `table.grid` is shared with Team, Calendar,
  Access control and Settings. A bare `table.grid { table-layout: fixed }` would have re-laid out those
  four pages on widths chosen for the projects board.
- **Percentages, not pixels,** because this project had no `@media` queries at all when this rule was written — 100% of the card is
  the only responsive mechanism it has (**Stage 119** added the stylesheet's first `@media` rule, the
  900px shell breakpoint, and it deliberately does not restyle this table), and `overflow-wrap: anywhere` on the cells makes a narrow window
  wrap a long client name instead of overflowing the panel. That worked until **Stage 120**, which found that below a certain
  width a cell stops wrapping words and starts breaking them into letters: the sheet now keeps a 1040px floor and its panel
  scrolls instead (see [Stage 120](#stage-120)).
- **`.c-progress` gets 14%** since the bar is a fixed 110 px `inline-block` plus an 8 px margin; a
  narrower column would push the percentage label onto its own line.

Revenue is right-aligned and the end date centred + `nowrap`, so digits and dates stack down the page.

**115 tests / 19 classes**, the new one being `ProjectsPageRenderTest → every category panel shares one
nine-column grid`: two projects in two different categories, asserting two tables, two colgroups, the
same nine `<col class>`s in the same order in both, and — tag-specific, because a bare `class="c-name"`
needle also matches the `<col>` and the `<th>` — exactly one `<th class="c-…">` and one
`<td class="c-…">` per column per panel.

Two kotlinx.html facts that cost a compile cycle and will again: the tag function is **`colGroup`**
(capital G — `colgroup` does not exist and importing it is an unresolved reference) while the element it
emits is `<colgroup>`; and `TR.th`'s **first positional parameter is `scope: ThScope?`**, so
`th("c-code")` fails to compile while `td("c-code")` works, because `TR.td` takes `classes` first. Use
`th(classes = "…")`.

Deployed by the usual stop → `installDist` → `deploy/run.bat` order and verified on the served artefacts:
`/static/css/app.css` byte-identical to the source with all nine `col.c-*` rules present, the shipped
`ProjectsKt.class` carrying the `projects-table`/`c-*` constants, and `GET /app/projects` still `302`
unauthenticated. As with every render change here, the markup itself has not been looked at while signed
in — that is the user's check, and it needs **Ctrl+F5** since a page opened before the restart keeps the
old stylesheet. The same treatment has *not* been applied to the Team or Calendar tables; nobody has asked
for those panels to line up with each other.

<a id="stage-17"></a>

## Stage 17 — project search bar with status and manager sort pickers (2026-10-13, live)

"Add search bar for projects and beside it the user able to sort the projects by status and by project
managers." Four scope questions, four answers, and one of them overrode my recommendation: the user wants
**two separate pickers** — a dedicated Status sort and a dedicated Manager sort sitting beside the search
box — not the single "Sort by" dropdown I proposed. Status sorts in **working order** (Not started →
Ongoing → Blocked → On hold → Completed), which is not `PROJECT_STATUSES`; that list is the *picker* order
for creating a project, so `Project.kt` now also exports `PROJECT_STATUS_ORDER` and `statusRank()`, with an
unrecognised status ranking last rather than at zero. *(Superseded by **Stage 96**: the user asked the picker for the
progression and the sort for the picker, the two lists are now one assignment, and `Ongoing` does not exist anywhere —
`PROJECT_STATUS_ORDER = PROJECT_STATUSES`. The "unrecognised ranks last" half of `statusRank` survives.)* Search is scoped **within the current tab**, and the
box filters **live as you type** with Enter still submitting.

State lives in one value object, `ProjectBoardFilter(text, statusSort, managerSort)`, built by the GET route
from `q` / `sort_status` / `sort_manager`, with `BoardSort.of()` collapsing anything that is not `asc`/`desc`
to `""` — so `?sort_status=bogus` is the default order, not a crash. The view calls three things on it:
`matches()` for the pills, the groups and the empty message, `sorted()` for the rows, `hrefFor(type)` for
the tab links. That single object is why the search survives moving between categories, why a pill counts
matches rather than catalogue size, and why "Nothing here matches …" can name both the term and the tab.

Ordering is composed, not chosen: the two comparators that are actually set are `reduce`d in
status-then-manager order and then `then(byDue).then(byCode)`, so a board sorted by status still reads
earliest-due-first inside each status group, and the old end-date default is what you get when both pickers
read `—`. `managerComparator` is a named function rather than a `thenComparator` for a real reason: the
unassigned project (`Project.manager` is `""`) has to sort last **whichever way the names run**, and a
blank-last tie-break appended after `reversed()` is unreachable whenever two names differ. The blank test
has to happen inside the comparator, before the direction is applied.

The toolbar is a plain **GET `<form>`**, which is the whole reason there is no new endpoint: search and sort
are read-only, every role can use them, the URL is shareable and reproducible, and the filtering logic stays
inside the render tests instead of becoming a JS module nobody can assert on. CSP is `script-src 'self'`, so
the behaviour is markup plus `app.js`: `[data-autosubmit]` (change) already existed for the pickers; this
stage adds `[data-livefilter]` (input, 400 ms debounce), and both call `form.requestSubmit()` rather than
`form.submit()` so the hidden `type` field rides along. Without JS the Search button does the same submit, so
live filtering is an enhancement and not a requirement.

The button then moved **inside `.board-search`, immediately right of the field** ("set search button right
beside the search bar"): at the far end of the row it read as a control for whichever picker happened to sit
next to it. Two things to know before touching that wrapper again — `.board-search` is itself a flex row, and
`base.css` gives every `input` `width: 100%`, which inside a flex container wants the whole wrapper; the
field therefore needs `flex: 1 1 auto; min-width: 0; width: auto` or the button is shoved to the wrapper's
edge. The toolbar's `align-items: flex-end` is mirrored inside the wrapper so the shorter `.btn-sm` shares
the field's bottom edge instead of floating at its top.

Typing then needed repairing ("when typing on the search bar there is a problem fix it"). A live filter is a
page reload every ~400 ms, and a plain reload quietly takes three things away: the **focus**, because
`autofocus` is not honoured on every kind of load — least of all a scripted form submit; the **caret**,
because when `autofocus` *is* applied it parks the cursor at position 0, so the next character lands *in
front of* the term already in the box; and the **scroll position**, because the board jumps back to its top.
`app.js` now carries a small restore block: on `submit` it writes one `sessionStorage` marker
(`pts-keep-position` = `{path, y, focus}`), and on the next page it consumes that marker once — so a bookmark
or a tab change can never restore a stale position — then hands the box back focus with
`focus({preventScroll: true})`, moves the caret to the end **only** when the browser left it at 0, and
re-applies the scroll at `load` and again in a later task, because Chrome applies `autofocus` *after* `load`
and the scroll-into-view that comes with it lands on top of anything restored before it. Three details a
future simplification would break: the "the reader has taken over" flag listens on `pointerdown` and not
`keydown` (a keystroke arriving in the milliseconds before `autofocus` never reaches the box, so counting it
as a caret placement would skip the repair the next character needs); a focused-but-**emptied** box still
counts as worth refocusing, because an unfocused box means the next thing typed goes nowhere; and the
`focusin` path repeats the repair, since that is the load where `autofocus` did fire. The same block skips
two reloads that were pure noise — a GET form whose submitted value already equals the current `?q=` (type a
character, delete it again) and an in-progress IME composition (`e.isComposing`), which would otherwise fire
a submit per keystroke.

Board state also has to survive a **rejected create**, which is a POST round trip back to the same page. The
popup form carries `board_q` / `board_sort_status` / `board_sort_manager` hidden fields; the POST handler
rebuilds the filter from those when it re-renders with the invalid `ProjectForm`, so a typo in a project
code does not silently drop the user back on the unfiltered board.

**120 tests / 19 classes.** `ProjectsPageRenderTest` went from 6 to 11: the bar narrows only the panel it is
on and keeps its own value, every word of a search must match and a miss prints the message with no table at
all, status sorting ignores the end date the board otherwise uses, manager sorting puts names in order with
the unassigned project last in both directions, and the toolbar is one GET form whose picker values come
back `selected` with the right `data-*` counts. Two things nearly shipped broken:

- The first search test looked for `haul`, which is a substring of **"Excavator overhaul"** in the other
  panel — an assertion that a non-matching panel was filtered away was therefore testing nothing about
  scoping. The term is now `widening`, unique to the matching project, and the comment says why.
- The Viewer test asserted "no create form" with `action="/app/projects"`, which the new *search* form also
  contains. The marker is now `name="board_q"`, which only the POST form has.

Verified the usual credential-free way first (`GET /app/projects?q=haul` → 302, served `app.js`/`app.css`
byte-identical with the `.board-toolbar` rules present), then — closing the same gap as Stage 16 — on a
throwaway demo-seeded DB in `%TEMP%` on port 8095, signed in, with the live 8080 instance untouched. The
served board showed the end-date default; `sort_status=asc` gave Not started → Ongoing → Blocked → Completed
and `desc` reversed it; `sort_manager=desc` reversed the names; both keys together gave status groups with
names descending inside each; `q=haul` left 2 rows with the pills reading 2 and 1 against 19 and 8
unfiltered; `q=crusher 2026` printed `Nothing here matches "crusher 2026".` with no table; the Site tab link
came back as `/app/projects?type=Site+projects&q=haul`; `sort_status=bogus` was ignored. That instance and
its directory are deleted. The moved button was then checked on the *rendered* page of the same throwaway,
signed in through the in-app browser: `getBoundingClientRect` gave the field ending at x 472 and the Search
button starting at 480 on the same line, both bottom-aligned, and clicking it produced
`?q=drill&sort_status=&sort_manager=` with the value back in the box, the caret in it, one row and the pills
following. (Screenshots need the in-app browser surface to be visible — with `visibilityState=hidden` the
capture is refused, so layout here is proven by geometry, not by an image.) The typing fix went through the
same throwaway — a fresh demo-seeded DB, signed in as the demo **`EMP0002`**, driving the real page:
typing `haul` came back `value="haul"`, focused, caret at 4, 2 rows; deleting the term to nothing came back focused
with the caret at 0 and all 19 rows, and the next word typed into that re-focused box landed whole
(`sel=7/7`) instead of in front of a stale value; Enter submitted the same form with focus and caret intact; a
picker change made at `scrollY=400` came back at `scrollY=400` with the new sort applied and the one-shot
marker consumed; the console stayed empty. Two results that looked like failures were the test's own doing:
calling `input.focus()` from a measuring script scrolls the toolbar back to the top, so the position it
recorded was 0, and a `delete` with the caret already at the end of the value deletes nothing — which is also
how the harmless `?q=crusher+` trailing space got into the URL (the route trims for matching, and the next
submit cleans the address bar). The public board still needs the
user's own **Ctrl+F5** look, since a page opened before the restart keeps the old stylesheet and the old
markup.

<a id="stage-18"></a>

## Stage 18 — the live search refreshes the board in place (2026-10-13, live)

"Change the search engine, it's not fully functional now — when I stop typing it starts from the start."
Stage 17's restore block made a reload survivable; it did not make it *invisible*, and a reader who is
watching the page come back at its top every 400 ms does not care that the caret was repaired. So the
search no longer navigates at all: `app.js` intercepts the toolbar's submit, fetches the same GET URL, and
**swaps the board in place**.

The split is drawn by one attribute. `projectsContent` now renders everything below the toolbar inside
`div("board-results")` carrying `data-live-region="true"`, and that element alone is replaced — the pills,
the groups, the tables, the "Nothing here matches …" panel. The toolbar is deliberately outside it, because
it holds the box being typed in, and the create popup is outside it too, so a refresh cannot close a form
half filled in. `ProjectsPageRenderTest.the block a live refresh replaces leaves the toolbar and the popup
alone` pins exactly that, with a `liveRegion()` helper that walks the div depth rather than trusting an
adjacency match (`createHTML()` indents — see Stage 14).

Three properties made this worth doing rather than just another AJAX refactor:

- **The server still owns the answer.** `ProjectBoardFilter` is untouched; `q`/`sort_status`/`sort_manager`
  are still the contract, and the region in the response is produced by the same Kotlin that produces the
  whole page. Nothing about matching, ordering or the pills moved into JS, so the render tests still
  describe what the reader sees.
- **It is still a GET form.** `e.preventDefault()` happens only when the page actually has a live region,
  `fetch` exists and no previous refresh has failed. Otherwise — JavaScript off, a network error, a board
  rendered without the region — the browser performs the ordinary page load the markup was written for, and
  Stage 17's focus/caret/scroll restore takes over. One failed refresh sets `refreshBroken` and that page
  never tries again, so a reader cannot be stuck watching a spinner.
- **The URL stays the truth.** After a swap, `history.replaceState` writes the new query. *replace* rather
  than *push*, because a back stack of half-typed phrases traps the reader; the address bar still shows a
  link that can be copied, bookmarked and reloaded, which is the property Stage 17 chose the GET form for.

Out-of-order responses are the one genuinely new hazard — a slow "haul road" answer must not land after a
fast "haul" one — so each refresh takes a `generation` token and only the newest may touch the DOM or the
history. `body.pts-refreshing` dims the region while a request is in flight (the class is on `<body>`
because the region node is replaced on every swap, so a class on the region would be thrown away with it),
and the term is trimmed before the URL is built, which also retires Stage 17's `?q=haul+` trailing-space
quirk. CSP is unchanged: `default-src 'self'` already permits a same-origin `fetch`, so no directive was
loosened and `script-src 'self'` still holds.

**121 tests / 19 classes.** Verified signed-in on a fresh demo-seeded throwaway on 8095, instrumenting the
page with a `window.__alive` marker and node identity on the toolbar and the region, so "did it reload?" is
answerable rather than assumed: typing `haul` left the marker in place, the toolbar the same node, the
region a new node, the caret at 4, the focus in the box, the URL rewritten to `?q=haul` (no trailing space)
and the pills reading 2; a picker change at `scrollY=600` came back at 600 with both sorts in the URL and no
navigation; a nonsense term swapped in the "Nothing here matches “zzznope”" panel with its Clear link; Enter
refreshed in place with the caret still at 7; and deleting the term to nothing left the box focused, so the
next word landed whole. With `window.fetch` sabotaged the form did the ordinary thing — a real navigation,
after which focus, caret (at the end) and the 400 px scroll position all came back. A category tab click
still navigates by design, and came back holding the search term, focused, with the caret at the end of it.
The console logged one *info* line on that navigation,
"Autofocus processing was blocked because a document already has a focused element", which is the browser
explaining the very case the restore code handles; no errors. Instance and directory deleted, 8080 back on
the new build with `app.js` and `app.css` served byte-identical. The user's open page still needs a
**Ctrl+F5** before it runs the new script.

<a id="stage-19"></a>

## Stage 19 — a reader sees their own tasks, and a card carries a checklist (2026-10-13, live)

"For the tasks the user should be able to see only his assigned tasks and the management should be able to
see users assigned tasks through the team as we doing right now. And make tasks able to create checklists by
managements and the user able to check it."

Two rules, and the interesting part of each is where the line was drawn.

**Visibility is one function, not one filter per page.** Before this, every task view read the whole board:
the Assigned-tasks kanban, a project page's board, the sidebar count, the Calendar, the Team drill-down and —
less obviously — the `memberDetailData` JSON island behind the Team popup, which is the same leak through a
different door. All six now go through `TaskService.assignedFor(employeeNumber, canManage)`, which returns
everything to management and only cards carrying the reader's own number to everybody else. A view cannot
"forget" to filter, because there is no other way to ask for the list. `Projects.kt`, `AssignedTasks.kt`,
`Calendar.kt`, `DashboardCommon.visibleTaskCount`, `Team.memberTaskRows` and `Team.buildMemberDetails` are
the six call sites; `assigned()` is still what it was for management and for the alert sweep, which must see
everybody's due dates.

**The Team page keeps its roster and loses its peeking.** The name, department, position and email columns
stay for everyone — a reader needs to know who is who — but the Tasks count, the hidden drill-down row and
the `+ Assign a task` shortcut are management's, plus the reader's own row, because "open my Tasks count" is
the way a normal account finds their work when the board is already filtered. `teamContent(canManage,
viewerEmpNo)` took the second parameter for exactly that, and `TeamPageRenderTest` pins the asymmetry: one
`tasks-toggle` for a viewer (22 dash cells where the counts used to be), 23 for a manager.

**The checklist lives on the card, not in the popup.** `V5__task_checklists.sql` adds `task_checklist_items`
(id, task_id → tasks ON DELETE CASCADE, label, done, position). One row per step rather than a JSON column
because a tick is then a single `UPDATE` — two people ticking two steps of the same card cannot overwrite
each other the way a read-modify-write blob would — and `position` is the same append counter `todo_items`
and `buckets` already use. `ChecklistRepository` is the only writer; `TaskRepository.map` loads the steps
alongside project and bucket names, in one query for the whole board, so a card arrives already carrying its
list (`TaskItem.checklist`, `hasChecklist`, `checklistDone`).

Three rules decide what renders, and each is kept twice — once as markup, once as a refusal:

- a tick box only for someone who may tick (management or the assignee); anybody else reading that card gets
  `✓`/`○` as text, because markup that offers an action the route will refuse is a lie,
- an add box and a `×` per step only for management — `addChecklistItem` / `removeChecklistItem` return null
  and false for a non-manager, which is what the hidden box looks like when the POST is hand-built,
  *(both of these first bullets describe Stage 19 as shipped; Stage 22 took the add box, the `×` and the
  `✓`/`○` text out of the markup entirely and scoped the block by assignment — see that entry; Stage 23
  brought the add box and the `×` back, but inside the editor popup and only for management, where the
  steps print as `✓`/`·` marks with no tick box — see that entry. The service rules in the second bullet
  are unchanged.)*
- a tick is the *card's* rule, not the step's: `toggleChecklistItem` loads the parent task and applies
  manager-or-assignee, so a colleague on the same project page cannot work somebody else's list.

No cap on steps: a long list scrolls inside the block (`max-height: 240px`) instead of refusing the fourth
item, and `CHECKLIST_LABEL_MAX = 120` is enforced in the service and mirrored on the input's `maxlength`, so
the field stops a reader where the service would.

**The tick does not reload the board.** That is Stage 18's mechanism, generalised: `app.js` now understands
`[data-live-target="#id"]` on any form, POSTs the form's own action with `FormData`, parses the response (the
whole page — the route still redirects to the board, which re-renders), and swaps only the element with that
id. Focus inside the block is re-given by element id, which is why every control has a stable one
(`chk-<step>`, `chk-add-<task>`), and generations are counted per block id, so two cards may be in flight at
once. The dim class sits on the block (`.task-checklist.pts-busy`) because the block itself is the node being
replaced. Server-side nothing changed about the shape of the answer: same POST, same 302, same Kotlin
rendering the markup — which keeps the render tests meaningful. Without JavaScript, or after one failure
(`fallBack`), the form is an ordinary POST and the page reloads to exactly the same state; that path was
verified with curl before the browser work.

Two things were tightened on purpose and are worth flagging: the Other-tasks backlog's `+ Add task` and
`+ Add bucket` boxes are now management-only in both the markup and the two routes (`POST /app/other-tasks`,
`POST /app/other-buckets`), consistent with Stage 15's "creating is management's call", while the backlog
itself stays readable by everybody (Stage 39 turned the `+ Add bucket` box into a heading button over a popup,
and gave `/app/other-buckets` a refusal message instead of a silent redirect; Stage 40 moved that box back onto
the board as the row's last column and put a `⋮` rename/delete menu on each head — the management-only half is
unchanged). And a normal account's sidebar "Assigned tasks" count now counts only
their own cards, so the number cannot describe cards they were not shown (Stage 27 later took the Completed
column out of that count too; the scoping half of this promise is what still holds).

**140 tests / 19 classes.** New in `TaskServiceTest`: the scoping rule both ways (management sees 2, the
assignee sees their own, an account with nothing gets an empty board rather than somebody else's) and eight
checklist cases — append order and trimming, a normal account cannot add or remove on its own card but can
tick, blank/over-long/unknown-card steps write nothing, a stranger cannot tick and a refused tick leaves the
row untouched, management may work any list and a second tick unticks, removing a step leaves the rest in
order, and deleting a card takes its steps with it (which is really a test that V5's FK and the harness's
`PRAGMA foreign_keys = ON` agree). New in `TaskBoardRenderTest`: five forms all target the same block, the
tick form posts taskId/itemId/back/projectId, the block and its rows are `draggable="false"` while the card
stays `draggable="true"`, the add box's `maxlength` equals the service constant, a ticked step renders
`checked` and struck while its neighbour renders neither, an assignee gets tick boxes but no add or remove,
an unrelated reader gets static text and no `name="done"` at all, and an empty list is only ever an add box —
for management.

Verified signed-in on a throwaway on 8095 with a fresh demo-seeded DB, as **EMP0002** (Project Manager) and
**EMP0016** (Finance Analyst, Viewer) — and, because the interesting question was "did that actually reload",
with `window.__marker`, `performance.getEntriesByType('navigate')` and a `window.fetch` spy on the page:

- management on `/app/assigned-tasks`: card T-00011 rendered `1 of 2 done` with two tick boxes, two `×`
  marks and the `+ Add a step` box; ticking posted `POST /app/tasks/checklist/toggle` once, the count moved
  to `2 of 2 done`, the marker survived and navigations stayed at 0. Adding typed into the box posted, the
  third step appeared, the box cleared itself, focus returned to `chk-add-11` (so a list can be written one
  line at a time) and still no navigation. The `×` — with `confirm` stubbed to yes — posted
  `/app/tasks/checklist/remove`, the row count fell from 3 to 2, marker intact.
- the checkbox is clickable inside a draggable card. `draggable="false"` on the block and each row, plus a
  hit-test: `document.elementFromPoint` at the centre of `#chk-1`, `#chk-add-11` and `.chk-del` returns those
  elements themselves. The first probe looked like a failure and was not one — the browser window was 589 CSS
  px wide, `.kanban`'s `repeat(5, minmax(0,1fr))` squeezed each column to 40 px, and a 27 px card cannot lay a
  checklist out honestly; at a normal column width all three controls are the topmost node at their own
  centre. The card keeps `draggable="true"`, so dragging from the title still works.
- as the Viewer: its board held exactly one card with the caption "Only the cards assigned to you are on this
  board", no card menu, no `+ New task`, tick boxes on both steps but no add box and no `×`, and no backlog
  add inputs. Its own tick again swapped in place with one POST. `/app/team` listed all 23 members, with the
  Tasks toggle on `EMP0016` alone, 22 `—` cells, no quick-add, no `#assignPanel`, and the JSON island carried
  task lists for exactly one member — the viewer themself. The Calendar heading read "Your assigned tasks" and
  the only titled bar was their own; `/app/projects/19` showed only their card. A hand-built
  `POST /app/tasks/checklist/add` from that session added nothing (`hacked=0`, still 2 steps) while the same
  account's toggle was accepted and visible to the manager as `1 of 2 done`.
- with JS out of the picture entirely (curl, no browser), every one of the three POSTs is an ordinary form
  submit that 302s back to the page it came from and re-renders the same markup, so the fallback is the same
  feature with a page load in it.

`Migrations.migrate` took V5 to the real `data/pts.db` on the restart; verified by metadata only — the table
exists, its foreign key to `tasks` is defined, `flyway_schema_history` maxes at 5, and the row counts
(`tasks_total`, `tasks_with_checklist`, `checklist_done_rows`) rather than any stored value were read. The
public board needs the user's own **Ctrl+F5**: a page opened before the restart still has the old stylesheet,
the old `app.js` and no way to know it is now looking at a filtered board.

Still open, unchanged from before: CSRF remains unwired (the operator's call), the edit form for an existing
project's client / dates / revenue, and the observation above that the five-column kanban has no minimum
column width, so on a narrow window it collapses rather than scrolling — pre-existing, not introduced here.

<a id="stage-20"></a>

## Stage 20 — the board prints both dates (2026-10-13, live)

"on project there are id project client status progress manager revenue and end — add start date and
change end to end date." No migration, no new column: V3 put `start_date` on `projects`, the create form has
asked for a **Start date** ever since, and the board simply never printed it. So the field was being typed
into the database and then kept invisible, which is the cheapest kind of feature to add and the easiest one
to forget exists. `projectTable` gained `col/th/td` for `c-start` beside `c-due`, the `End` header became
`End date` — matching the words the create form (`Projects.kt`), the project page's `detailItem`s and the
To-do lists already use, so the board was the last place still saying "End" — and a project with no recorded
start prints `—`, the same way an unknown client does.

Column order keeps the shipped sequence with the two dates adjacent: ID, Project, Client, Status, Progress,
Manager, **Start date, End date**, Revenue, pin. The request listed Revenue before End, but moving it would
have separated the two dates for no gain, so they sit together and Revenue stays where it was.

The interesting part is the arithmetic, because Stage 16's grid is shared by every category panel and the
widths must still sum to exactly 100. A date is the one thing on this table that must not break in half, so
both date columns take a fixed 9% and the room comes out of the three free-text columns that *can* wrap —
Project 22→18, Client 13→10, Manager 11→9 — while Progress keeps its 14% (the bar is a fixed 110px
inline-block plus a percentage label) and pin gives 1%. The other change is where `white-space: nowrap`
lives: it used to cover `th.c-due` and `td.c-due` together, which was harmless while the header was one
short word and is not now that it is two — a nowrap "End date" would run over Revenue on a narrow window
instead of wrapping like every other header does. So nowrap is now on the two date *cells*, and the two
headers centre and wrap.

Measured on a throwaway demo-seeded database (8095, signed in as the seeded manager, one new project with
`start=2026-09-01`): seven panels each print ten `<col>`s in one order, every header reads `Start date` /
`End date` and no bare `End` is left anywhere, and the row's start cell carries the date. Geometry came from
forcing the table to 1180 / 1000 / 860 px, because the in-app browser window is 589 CSS px wide and every
column collapses at that size — the same pre-existing narrow-viewport problem as the kanban, not something
this change introduced. At 1180 and 1000 px no cell overflows; at 860 px the two date cells overflow by 2 px,
so the board keeps both dates whole down to roughly 880 px of table width, which is better than the old 8%
column needed for the same digits. **140 tests / 19 classes**: `every category panel shares one
nine-column grid` became `… one ten-column grid`, and now also asserts the two header labels, that a real
start date prints, and that a blank one prints the dash rather than an empty cell.

Deployed in the usual order (stop 8080 → `installDist` → `run.bat`); the served `app.css` is byte-identical
to the source (`844ee935…`) and the shipped `ProjectsKt.class` carries the new labels. A page opened before
the restart still needs **Ctrl+F5** — it has the old stylesheet cached and nine columns of markup to show.

One gap this makes visible rather than fixes: there is still no edit form for an existing project, so a
project created without a start date now shows `—` in a column that nobody can fill in afterwards. Same
standing open item as the client / end date / revenue edits.

<a id="stage-21"></a>

## Stage 21 — the project page rolls its board up on top of it (2026-10-14, live; **superseded by Stage 28**, which deleted the strip and the `summaryOf`/`ProjectTaskSummary` machinery behind it — read this section for the reasoning that was in force then, not for markup that exists now)

"when clicking on project from the project showing tasks but i need to show detailed information about the
project on top of the tasks". Measured first: a project page already prints the detail card — nine fields
plus the progress bar — above the kanban, and the **Project manager** panel below it. So the request was not
for those fields again, it was for what the project *amounts to*: the board's own columns counted, open
against done, how many cards are late, how far the checklists have got, who is holding the open work, and
how many days are left. Asked which of the two to change, the answer was to add that roll-up as a live strip
above the tasks and to leave the manager panel where it is.

`TaskService.summaryOf(cards, endDate, today)` does the arithmetic and returns a `ProjectTaskSummary`
(`domain/Task.kt`). Three decisions in it matter more than the counts:

- It takes the **already-filtered card list**, not a project id, and `Projects.kt` hands it the exact list the
  board under it renders (`assignedFor(...).filter { it.project == ... }`, read above the detail card for that
  reason). A `Viewer`'s strip therefore cannot say more about a colleague's work than their own board does —
  the two numbers are the same value twice, not two rules that might drift.
- Overdue reuses the app-wide definition rather than inventing one: not `Completed` and due date strictly
  before `LocalDate.now(ZoneOffset.UTC)`, the same rule `DueDateAlertService` and the card colours use. The
  countdown is `ChronoUnit.DAYS.between(today, end)` on the same clock, and both are wording-tested
  (`in 5 days`, `Due today`, `3 days past`, `No end date`).
- `today` is a parameter with that UTC default, so the eight new `TaskServiceTest` cases assert a fixed
  calendar instead of a suite that quietly fails on the first of a month.

Nothing in Stage 21 is a migration: V5 is still the newest schema, the strip is derived at render time from
rows the board already fetched.

The second half of the choice was *live*, and that needed a new mechanism rather than a new component.
`[data-live-target="#id"]` (Stage 19) swaps exactly one block per response, which is right for a ticked step
and wrong here: the same POST changes that step's checklist **and** the strip's **Steps** tile, and swapping
only the checklist would leave a tile showing the number from before the click. So the markup gained
`[data-live-extra="#id,#id2"]` — companion blocks swapped from the same response under the same generation
token (`extraIds` + three lines in `sendToBlock`, `app.js`). A missing companion is skipped, not treated as a
failure, because a page may carry the tile-less empty-project sentence, and a response that lacks one cannot
be allowed to fall back to a reload. The contract is documented in the `app.js` header alongside the other
three; with JavaScript off the forms post and reload as before, and the reloaded page is consistent.
*(**Stage 93** added a fourth, `[data-live-url]`: on a GET block form whose block is the board itself, the URL the
response was fetched for replaces the one in history, so a view change made in place survives a reload.)*

`statusDot` moved from two private copies (`TaskBoard.kt`, `AssignedTasks.kt`) to one `internal` function so
the strip's dots are the board's dots by identity, not by matching strings — the duplicate first surfaced as
a "conflicting overloads" compile error, which is the honest way to discover a copy-paste.

`151 tests / 19 classes`: 8 new in `TaskServiceTest` (column order with empty columns as zeroes, due
yesterday overdue / due today not, `Completed` + blank + malformed never overdue and an unassigned late card
still counted, steps summed with `hasSteps`, people distinct and open-only, countdown on both sides of today
plus the null, the empty roll-up, and one real-database test proving the roll-up counts the reader's cards —
manager 2 / engineer 1 from the same project), and 3 new in `ProjectsPageRenderTest` (the strip above the
board with its tiles, one warn tile and it being Overdue, five column dots in board order, the people line,
and `id="project-summary"` matched by `data-live-extra` on the step forms; a `Viewer`'s strip scoped to their
own board; an empty project printing a sentence instead of zeroes).

Verified on a throwaway demo-seeded database (port 8095, `%TEMP%`, signed in as the seeded manager): tiles
read `4 / 3 / 1 / 1 / 1 of 2 / in 5 days` and the five dot counts equalled the `kcol-count` values under them
for both the manager (`1 1 0 1 1`) and the `Viewer` (`1 0 0 1 1`), so the strip matches the board it sits on
for each role. Then in the browser, ticking a step left `window.__marker` alive and moved the strip's **Steps**
tile `1 of 2 → 2 of 2` while the card's own counter read `2 of 2 done`, with one `POST /app/tasks/checklist/
toggle` and no document request in the network log; adding a step did the same, `2 of 2 → 2 of 3`. Screenshots
were refused (the in-app surface reports `visibilityState=hidden`), so the evidence is DOM plus network, not a
picture.

Deployed in the usual order — the live 8080 instance was already stopped, so no stop was needed — then
`run.bat`. `/` answers 200, `/app/projects` 302 without a session, and the served `app.css` (`cb631866d917ac44…`)
and `app.js` (`1c3ffef4fcb8f4b2…`) hash byte-identical to their sources; `murray-pts-2.0.0.jar` contains
`domain/ProjectTaskSummary.class`. `data/` was not written by this verification and its projects list is still
the deliberate empty set from 2026-10-12, so the strip has been proven against a throwaway database only: the
first real project created on 8080 is the first time it renders against production rows. **Ctrl+F5** still
applies to any page opened before the restart — it holds the old `app.css` and an `app.js` with no
`data-live-extra` handler.

Unchanged and still open: CSRF (the operator's call), the edit form for an existing project's client / dates /
revenue, and the kanban's missing minimum column width on narrow windows (**Stage 119**'s breakpoint now
gives both card boards two fluid columns below 900px; the desktop five-column board still has no minimum).

<a id="stage-22"></a>

## Stage 22 — the card keeps only its tick boxes and its status mover (2026-10-14, live)

"on tasks no need to show checklist and management and the user able to check it. also on tasks remove
the edit and delete option". Two readings of the first half were live — hide the list from management
entirely, or hide it from everybody but keep the worker's tick boxes — so the scope went out as one
batched question and came back as three settled answers: **workers tick, nobody authors**; **Edit and
Delete both leave the card menu, the status mover stays**; **both boards**, since Assigned tasks and a
project page share `taskKanban`/`taskCard` and one cut covers both.

The rule that replaced the old one is worth stating precisely, because it is not a role rule:
`taskChecklist` prints when `task.assignee == session.employeeNumber`, so **the list follows the
assignment, not the role**. A manager who holds a card gets their own tick boxes on it, and sees nothing
at all on a colleague's card — no `Checklist` heading, no `n of m done`, no `+ Add a step` box, no per-row
×. The static-text variant Stage 19 shipped for readers with no part in a card (`chk-static`) went with
it: a list nobody can act on answers no question, so printing it was noise.

Four things were deleted rather than commented out: the add form, the remove form, the `chk-static`
branch, and the menu's `Edit` button and delete form. The card no longer copies its own
`data-title`/`data-project`/`data-assignee`/`data-started`/`data-due`/`data-priority` into the markup,
which was the payload that made opening an edit cost no page load, and `taskCardMenu()` lost its `back`
and `projectId` parameters because the only form left routes back to the page it was posted from.
`task-modal.js` shrank with it — `fillFromCard`, `[data-menu-edit]`, `heading()`, `actionLabel()`, the
menu's hidden `taskId` write and the delete confirm wording are gone (7343 bytes served); the create
popup, the default-capture hygiene and the menu's positioning math stayed. Ten `.chk-*`/`.task-del-btn`
CSS selectors went, dark-theme overrides included.

**Deliberately not changed: the server.** `TaskService.addChecklistItem`, `removeItem` and `update` keep
their management-only rules, and `POST /app/tasks/checklist/add`, `/checklist/remove`,
`/app/assigned-tasks/save` with a real `taskId` and `/app/assigned-tasks/delete` are all still wired —
their tests still pass, and a hand-built request behaved exactly as before (proven on the throwaway: a
`delete` POST removed the card). So the four actions are *unavailable in the interface*, not permitted.
Two consequences follow and are worth repeating to whoever asks for the app: **a step can no longer be
created from the app at all** — existing steps stay tickable, and a card that needs new ones cannot get
them; and **a card typed wrong stays on the board**, since the create popup is now the only writer it
has. Closing those four routes is a follow-up decision, not this change.

`taskFormModal` still carries its `state.isEdit` branches (the "Edit task T-000NN" heading, "Save task"
label) with no way to reach them from the UI. Left in on purpose: the save route still accepts an edit,
and the branch costs nothing to keep correct.

**152 tests / 19 classes / 0 failures** — two `TaskBoardRenderTest` cases cut and three added. What the
suite now pins: `only the person the card belongs to is offered the tick list`, `nobody writes or deletes
a step from a card, in either role` (both roles' HTML lacks `/checklist/add`, `/checklist/remove`,
`chk-add`, `chk-del`, `chk-remove`, `chk-static`), `a manager looking at somebody else's card sees no list
at all`, `the list follows the assignment, not the role` (a manager with two cards, one their own, gets
exactly one `task-checklist`), and `nothing on the board edits or deletes a card` alongside a menu test
that asks for one `card-menu-status` and no `data-menu-edit`, no `Edit` text and no
`/app/assigned-tasks/delete`. `ProjectsPageRenderTest` moved its `data-live-extra="#project-summary"`
assertion onto the **owner's** render, because after this cut management's board no longer has a step
form to refresh the strip with — the Stage 21 contract is still tested, just from the page that still
carries it.

Verified on a throwaway demo-seeded database (port 8095, `%TEMP%`, both roles signed in through the
change-password gate, one project and three cards built through the routes): management's project page
printed `checklist-13` and nothing else — their own card — with 0 add forms, 0 remove forms, 0
`chk-static`, 0 `data-menu-edit`, 0 delete forms, 0 `data-title`; the worker's page printed `checklist-11`
with no card menu element at all and their two `kmove` arrows intact. In the browser, `card.click()` on a
manager's own card opened the menu showing `T-00013` with `buttons: []` and one form,
`/app/tasks/status`; choosing `Completed` moved the card to that column and the strip read
`3 / 2 / 1 / 0 / 1 of 6`. Ticking a step as the owner left `window.__marker` alive and moved the strip's
**Steps** tile `0 of 4 → 1 of 4` with the box `checked` and struck through — in place, as Stage 19 and 21
promise (the swapped block is a new node, which is the mechanism, not a reload). The create popup still
opens blank: heading `New task`, button `Create task`, `taskId=0`, `back=project`, `projectId=20`. The one
control that still redraws the whole page is the menu's status mover, and it is the pre-existing shape:
`kanban.js` binds `dragstart`/`dragover` per card and per column at load, so swapping the board node from
one response would silently kill dragging. Making that live is a separate piece of work, not a two-
attribute change.

Deployed in the usual order (the live instance was already stopped for the rebuild): `run.bat` on 8080,
`/` 200, `/app/projects` 302 without a session, served `task-modal.js` `8f04458f1496fb34…`, `app.js`
`1c3ffef4fcb8f4b2…` (unchanged since Stage 21) and `app.css` `3c13394cd32411d6…` all byte-identical to
their sources, and `TaskBoardKt.class` in the shipped jar carries `checklist/toggle` and
`card-menu-status` and none of the six deleted markers (`chk-add`, `chk-static`, `task-del-btn`,
`data-menu-edit`, `checklist/remove`, `assigned-tasks/delete`). No migration: V5 is still the newest schema, and
`task_checklist_items` rows written before this stage stay exactly as they were and stay tickable by their
owner. **Ctrl+F5** matters here more than usual — a page opened before the restart still shows the old
menu with `Edit` and `Delete`, and clicking them posts to routes that still answer.

Unchanged and still open: CSRF (the operator's call), the edit form for an existing project's client /
dates / revenue, the kanban's missing minimum column width on narrow windows, and now the four
writer-less routes above.

<a id="stage-23"></a>

## Stage 23 — a card opens its editor, its plan and its log (2026-10-14, live)

"when a user click on the task action leads to able to edit task such as priority start date due date and
who is that the task assigned to(if management) able to add checklist and notes". Every noun in that
sentence had two readings, so the scope went out as one batched question and came back as four settled
answers: **a running list of notes** (append-only, signed, newest first) rather than one blob on the row;
**management every field, the assignee the dates and the priority**; **steps written by management, notes
by management and the assignee**; and the entry point being **the card menu regaining Edit**, not a new
button on the card. *(Stage 24 undid that last answer the same day: the menu is gone and the card body is
the entry point. The three rules above are unchanged.)*

**Schema: `V6__task_notes.sql`.** One row per note — `task_id`, `body`, `author_emp_no`, `position`,
`created` — with `ON DELETE CASCADE` onto `tasks`, the same shape as V5's steps and for the same reason:
a single TEXT column on the card is last-write-wins between the two people who are both allowed to write
it, and one row per note makes every add one INSERT. `author_emp_no` carries **no foreign key**, like
`notifications.actor_emp_no`: a note is history and should still name its author after the account goes,
so the name is resolved at read time and falls back to the number. `created` is `yyyy-MM-dd` in UTC
because a note is a day-level fact — the app never shows a clock time, so it never stores one. `position`
is the append counter the rest of the schema uses, and the board reads `position DESC` so two notes
written in the same second still print in the order they were written.

**Rules: `TaskService`.** `editableBy(id, empNo, canManage)` answers the single question the whole stage
hangs on — may this account open this card at all — returning the task or null, so a hand-typed
`?edit=<somebody else's card>` renders an ordinary board. *(**Stage 86** renamed it `openableBy` and widened the
read: on a project board every reader now opens every card, while a backlog card still opens only for management
and its holder. The write gates described below are unchanged; see the Stage 86 section.)* `updateAssigned` takes the four fields a
non-manager cannot write **from the stored row, not from the form**, which is the entire permission
check: greying an input is presentation, and a POST built by hand that omits `title`, `project`,
`assignee` or `status` still cannot move a card, because the service overwrites them with what the DB
already says. A caller with no part in the card gets `TaskField.PERMISSION` — "Only management or the
person this task is assigned to can change it." — and a rejected field comes back with the protected
values still filled from the row, so the popup reopens showing the card, not an empty form.
`addNote` accepts management and the assignee, refuses blank and over-`NOTE_MAX` (500) text;
`removeNote` is management-only. That last one is a decision worth stating to whoever asks: **the log
cannot be quietly rewritten by the person who wrote it** — an author adds to it, and only management
takes an entry off. A save by management that moves a card to a new assignee raises
`NotificationService.taskAssigned`; an assignee's own save cannot, because that field is theirs to read.

**Markup: `taskFormModal` grows two sections.** The popup is the same node the create form always used
(`#taskModal`), now printed from the DB: heading `Edit task T-000NN`, every field filled, and underneath
it `modalSteps()` and `modalNotes()`. The plan prints **only for management** — `n of m done`, one row
per step with a ✓ or a · and an × on each, and a `+ Add a step` box — and deliberately with **no tick
box**, because ticking is a click on the card and Stage 19's owner rule is unchanged. The log prints for
whoever opened the card, newest first, each entry showing its author's name and its day, with the × only
where removal is allowed. A card with nothing on it still opens both sections with a `section-empty`
line, because "somewhere to write" is the point of letting a non-manager into the popup at all. The
field split is printed as it is enforced: `readOnly` greys title, project, assignee and status, adds the
one-sentence `modal-scope` explanation, and — the fix this stage needed after a test caught it — renders
each greyed picker as a **single option holding the card's own project or assignee** (`onlyOption`) rather
than the full roster and project list. The hidden popup travels with every board so `app.js` has a node
to swap into; it must not also be a directory of the team.

**Mechanism: the URL is the popup's state.** Both openers are GET forms onto the page they sit on —
`?new=1` and `?edit=<card>` — and `openingTaskForm()` in `Application.kt` decides what the page prints,
so `app.js` fetching the same route and swapping only `#taskModal` is the whole in-place behaviour: no new
fetch code, one renderer, and the server still refusing the `?edit` it was not asked for. `?new=1` is
honoured for management alone because that is the only account `/save` would accept a create from. The
two section forms carry a hidden `edit` field so their redirect (`…?edit=42`) puts the popup back open and
gives `swapBlock` an id to find; the response re-renders just that block, `app.js` restores the caret by
id, and `task-modal.js`'s `noticeSwap()` was changed to **keep that focus** instead of always jumping to
the first field — which is what lets a list be written one line at a time. Saving the card itself and the
status mover stay plain POST + 302 *(Stage 23: the menu's dropdown; Stage 24: only the save, since the
dropdown was deleted and a move is a drag or the editor's own Status field)*: `kanban.js` binds
`dragstart`/`dragover` per card node, and swapping a board out from under them would silently kill
dragging.

**CSS, added once the popup grew.** The editor's card measures ~844px with a plan and a log on it, and the
overlay had no scroll rule while `.modal-content { margin: 10% auto }` resolved a *vertical* gap against
the overlay's *width* — 144px of dead air above a popup already overflowing below, so the Save button sat
off-screen with no way to reach it. `.modal` now takes `overflow-y: auto` and the margin is
`clamp(16px, 6vh, 56px) auto 48px`. Measured on the throwaway at a 553px-tall window: `overflowY=auto`,
`scrollHeight=925` against `clientHeight=538`, and scrolling moved the Save button from top 518 to top 131
— reachable. Verified in the same pass that the new scroll container did not cost the caret: a note
written in the open popup came back with `activeId=noteAdd-1`, the block swapped alone, the popup still
open, `location.pathname` unchanged and `window.__s23b` still set.

**167 tests / 19 classes / 0 failures** — nine new service cases and eight new render cases, plus two
older render cases rewritten. The suite now pins `an owner moves their own dates and priority, and nothing
else on the card` (the row keeps its title, project, assignee and status after a POST that tried to change
all four), `a stranger cannot edit a card they can see on a shared page`, `an owner's rejected date comes
back with the greyed fields still filled`, `management opens any card, an owner only their own, and a typed
id opens nothing`, and four log cases — both writers and newest-first, the stranger/blank/over-long refusals,
`only management can take an entry off the log`, and `deleting the card takes its notes with it` against a
real SQLite file with the real Flyway schema. On the markup side: `a card is clickable for its manager and
its owner, and for nobody else`, `the menu opens the editor on both boards, and moves status only for
management` *(retired with the menu by Stage 24)*, `management's editor arrives filled, with the plan and the log under the fields`, `an owner's
editor greys the four fields management owns and keeps theirs open`, `the plan reads as a plan in the
editor, with its tick state and no tick box`, `the log reads newest first, signed with a name and a day`,
`the note box posts one body, its card, and where to come back to`, and `a greyed picker lists the card,
not the whole roster` — the regression test for the leak that `ProjectsPageRenderTest` caught mid-stage,
which was fixed in the product rather than in that assertion.

Verified on a throwaway demo-seeded database (port 8095, `%TEMP%`, both roles through the change-password
gate): an owner POST aimed at title/project/assignee/status left all four untouched while their new dates
and priority went through; the same account was refused a step and a note removal and allowed a note; a
stranger's `?edit=1` came back as a plain board with no popup open. In the browser, Edit opened the editor
in place on both boards (`window.__s23` / `__vw` markers survived, `location.href` unchanged), a step and a
note each swapped only their own block with the caret back in the box that wrote them, closing the popup
stripped `?edit` from the URL, the card's own tick still redrew without a load, and Save was the only
navigation — no console messages at any point.

Deployed in the usual order (the live instance was stopped **before** `installDist`, per the jar-replacement
trap in README): schema went `Current version 5 → Migrating to "6 - task notes" → successfully applied 1`,
and a read-only pass over `data/pts.db` after boot counted 6 migrations, `task_notes` present and **empty**
(noting is opt-in, nobody's card changed), 4 projects / 5 tasks / 2 steps with 1 ticked / 15 accounts with
0 still flagged to change their password, and 0 orphaned tasks or notes. `/` 200, `/app/projects` 302
without a session, served `app.css` `009fd1652ba4…`, `app.js` `08c00489b625…`, `task-modal.js`
`09285ea76751…` (8773 bytes) and `kanban.js` `e39f4bf5dc46…` all byte-identical to their sources.
**Ctrl+F5** matters again: a board opened before the restart carries the old menu, whose Edit is a button
that no longer exists, and the old script that steals the caret back to the title field. *(Stage 24 made
the same point sharper — see below.)*

Unchanged and still open: CSRF (the operator's call), the edit form for an existing project's client /
dates / revenue, the kanban's missing minimum column width on narrow windows, and card **delete**, which
still has no control anywhere while `POST /app/assigned-tasks/delete` stays wired and management-only.

<a id="stage-24"></a>

## Stage 24 — the card itself is the way in (2026-10-14, live)

"take away from the edit button and in progress dropdown a user can edit tasks by clicking on the task not
clicking on the edit button delete edit button", settled by one clarification: **"delete edit button and in
progress dropdown clicking card goes to edit task tab straightaway"**. So Stage 23's entry point was deleted
the same day it shipped: the whole card menu — its `Edit` button *and* management's status dropdown — is
gone, and clicking a card's body opens that card's editor over the board without a reload.

**What came out.** `taskCardMenu()` in `TaskBoard.kt`, the `⋯` tab stop (`.kcard-tab`), the `.card-menu*`
CSS block, every menu branch in `task-modal.js`, and the card's `data-status` attribute. Nothing else was
re-plumbed: the popup, `TaskService`, `V6`, the `?new=1`/`?edit=N` URL state and the whole `app.js` in-place
contract are Stage 23's, unchanged. The count is no accident — Stage 24 deleted a control, and the editor
that control opened already had every field it offered.

**What replaced it: `taskEditOpener()`.** A board prints **one** hidden GET form for its entire column set —
`action` is the page it sits on, `method=get`, `data-live-target="#taskModal"`, and a single hidden `edit`
field valued `0`. `task-modal.js`'s click handler finds the card's id, writes it into that field, and calls
`form.requestSubmit()`. One set of fields for forty cards, and the opener is rendered only when somebody on
that board can actually open something (`canManage || tasks.any { it.assignee == session.employeeNumber }`).
The card gained the hooks that menu used to carry: `data-task-card="<id>"`, `tabindex="0"`,
`aria-label="Open <title>"`, and the existing `draggable` — all of them only on a card this account may open,
so a colleague's card is not merely inert, it has no click target at all and no tab stop. *(**Stage 86** split
those hooks: `data-task-card`, `tabindex` and `aria-label` are now printed on every card because every card opens,
the opener is drawn whenever the board carries a card, and `draggable` is the attribute that stays with the
writers.)*

**Losing the status dropdown costs nothing, and the arrows stay.** A manager has two other ways to move a
card: drag it between columns, or change **Status** in the editor they just opened — a column is a field of
the form, not a separate decision. A non-manager's Status field is greyed (Stage 23's permission split), so
their cards keep the `←`/`→` buttons, which post `/app/tasks/status` from `kanban.js` and reload as they
always have. (**Stage 103** ended this: the arrows are off both status boards, the drag and — for management — the
popup's **Status** field are the two ways a status changes, and a no-JS owner now asks management.) That is also why a board swap is still off the table: `kanban.js` binds `dragstart`/`dragover`
per card node, and `Save` / drag / the arrows remain plain POST + 302.

**Keyboard and stray-click reachability.** Deleting the `⋯` tab stop would have made the editor
mouse-only, so `tabindex="0"` plus an Enter/Space handler on the focused card replace it — the handler fires
only when the card itself is the active element (`here === here.closest('[data-task-card]')`), which keeps
Space working inside the popup. The click handler bails on `button, a, input, select, textarea, label`, so
the tick list, the arrows and the checklist all still do their own thing when clicked; verified in the
browser by injecting a synthetic `.kmove-btn` into a real card: clicking it left `edit=0` and opened
nothing, and the card body then opened it normally.

**Why the address bar stays clean:** the opener is a GET form and `app.js` swaps only `#taskModal` from the
response, so a click-opened popup never writes `?edit=` — it is the same node, the same page, the same URL.
A bookmarked or hand-typed `?edit=4` still opens on load, and Escape closes it *and* strips the query, so a
stale link does not leave a popup that cannot be shut.

**167 tests / 19 classes / 0 failures** — the same total, because four menu tests were replaced by four
card tests: `a card is clickable for its manager and its owner, and for nobody else` (rewritten from the
menu's assertions onto the card hooks), `a card offers no status dropdown anywhere on the board`, `the
board renders one hidden opener for its whole column set`, and `a card click asks its own page for that
card's editor`. `ProjectsPageRenderTest`'s Viewer case now asserts the absence of both `taskEditOpener` and
`data-task-card` rather than the absence of a menu.

Verified on a throwaway demo-seeded database (port 8095, `%TEMP%`) in both roles. As management: 0
`#taskCardMenu`, 0 `.card-menu`, 0 `.kcard-tab`, no Edit or Delete button, 0 forms to `/app/tasks/status`,
0 `[data-autosubmit]`, one `#taskEditOpener`, and 8 clickable cards plus 2 backlog cards that correctly
carry no hook. A card-body click swapped in `Edit task T-00005`, focused `taskTitle`, left `location.search`
empty and a `window.__s24probe` marker alive; Escape hid the popup; Save posted `/app/assigned-tasks/save`
and 302'd back to the clean board; a synthetic `DragEvent` moved a card to `In progress`. As the owner of
one card: 3 cards, exactly 1 clickable, 1 opener, 2 arrows, no menu and no select; the real arrow moved
the card and left the popup closed, while the card body opened the editor with title/project/assignee/status
greyed, **1** project option and **1** assignee option, no `Add step` section, and three notes appended in
place with the caret returning to `noteAdd-4`. `/app/projects/2`, where they hold nothing, printed 0
clickable cards and **no opener at all**. `?edit=0` came back byte-identical to the query-less board, and
as a Viewer `?edit=1` and `?edit=9999` opened nothing. No console messages at any point.

Deployed with **no migration** — schema still `6`, validated 6 migrations, "No migration necessary" — and
`app.css` `96a100c5…`, `app.js` `08c00489…`, `task-modal.js` `8a5522c5…` (8141 bytes) and `kanban.js`
`e39f4bf5…` byte-identical between the live instance and the repo sources. **Ctrl+F5 is mandatory after
this one**: a board opened before the restart still shows the deleted `⋯`, and its Edit button posts an
`?edit=` link to a page whose menu no longer exists — the stale view looks broken in a way the build is not.

Unchanged and still open: CSRF (the operator's call), the edit form for an existing project's client /
dates / revenue, the kanban's missing minimum column width, and card **delete**, which now has even fewer
places it could live than it did under the menu.

<a id="stage-25"></a>

## Stage 25 — an empty board invites the reader who may fill it (2026-10-14, live)

"if there is no task on the project to show to the user the user able to create a new task". Read against
the code first, this was not a missing control: management already got the page-head `+ New task` on a
project with nothing on it. What the *other* account had was no way in at all — five columns of "Nothing
here." and no button, because Stage 19's rule reserves task writes to management. So the request was a
permission change wearing a UI request's clothes, and it was settled as two decisions:

- **Who:** management, *plus the account a project names as its manager*. Not every `Viewer`, and not
  everybody on the roster — the project's own manager, the person already accountable for it.
- **When:** anytime this reader sees an empty board. The board as rendered *for them*, not the board in the
  database: a colleague's card they cannot see must not keep the invitation off their screen.

**The rule lives in `TaskService.mayAddTasks(projectId, callerEmpNo, canManage)`.** Management is `true`
anywhere, on either board. Otherwise a `projectId > 0` is required and the answer is
`ProjectRepository.managerOf(projectId) == callerEmpNo` — the *employee number* column, never
`Project.manager`'s display name, so a rename or a namesake cannot hand the right to the wrong account. An
unmanaged project grants it to nobody, and `projectId = 0` (the general board) grants it to nobody but
management: there is no one project there to grant on.

**`TaskService.create` gained `pageProjectId` and pins to it.** A non-management create whose page names a
project has its `projectName` overwritten from that row before validation, so "add a card to *this* project"
cannot become "add a card to any project" by editing the form's `project` field. The refusal message is
attached to `TaskField.PERMISSION` — "Only management or this project's manager can add tasks." — which is
the popup's own paragraph above its buttons, so a rejected create stays open with what was typed in it.

**Only the project page sees more cards, and only for somebody who may write there.**
`projectDetailContent` now calls `TaskService.assignedFor(empNo, canManage || mayAdd)`. Without that, a
project manager's first card — assigned to a colleague, as most cards are — would vanish from the page they
just made it on, which reads as the write having failed. Stage 19's scoping stays exactly as it was on the
general board, the sidebar count, Calendar and the Team drill-down; this is one page, widened for one right.

**The popup state grew one right, not one field set.** `TaskForm` carries `canCreate`, and two derived
properties do the work: `mayWriteCard = canEditAll || (!isEdit && canCreate)` — the create a project's
manager is about to write needs a title, a holder and a column even though they may not edit anybody else's
card — and `projectPinned = !canEditAll && !isEdit && canCreate`, which turns the project picker into one
greyed option with the sentence "New tasks land on … — this project is yours to write for." `canCreate`
never widens an edit: an existing card keeps Stage 23's split whatever it says.

**`newFormOf(canManage)` was the bug this stage caught.** `?new=1` built its form as
`TaskForm(open = true, canCreate = !canManage)`, and `canEditAll` defaults to `true` — so the project's own
manager got management's blank card: all 19 projects in a picker they were not allowed to use, beside the
hint "pick another project to move one off this page", a sentence the service then contradicted by pinning
their card to the page anyway. The field-level test had passed because it hand-built
`TaskForm(canEditAll = false, canCreate = true)` instead of going through the route. Both are fixed: the
constructor now takes the one fact the route actually knows (`canManage`) and derives `canEditAll` and
`canCreate` from it, and the render test builds the popup **through `newFormOf`** so a form the route hands
the page cannot drift from the form the assertions describe.

**The empty board says it out loud.** `taskKanban(…, canAdd, fixedProject)` prints a `.board-empty` block
under the columns when the board this reader can see holds nothing *and* they may write one: what is missing
("No tasks on this project yet." / "Nothing has been assigned yet."), where the card will land ("The board
is the plan — write the first card and it appears under its column."), and the same `newTaskButton(project)`
the page head carries — no second mechanism, no new script. Dashed border, centred, themed through
`--input-border`/`--input-bg` with a dark-mode override.

*(**Stage 124** supersedes the same-opener claim only for the empty Assigned tasks projects-board panel:
with *Edit any task* and *Manage buckets* it offers `+ New bucket`, the shared Other-tasks column-create popup.
A card-writer without the bucket chip keeps `+ New task`, and the page head's `+ New task` is unchanged.
Stage 124 was verified and deployed 2026-11-06 (726 tests/49 classes, 25 HTTP and 90 JavaScript checks; JavaScript changed → Ctrl+F5).)*

**174 tests / 19 classes / 0 failures** (from 167). Service: `the account a project names as its manager
writes its first card`, `a manager's new card is pinned to the page it came from, not to the form`, `a
project's manager is not management on the general board`, `the right to add a card is management, or that
project's manager, and nobody else`, and the old "normal account" assertion moved onto the new message.
Render: `an empty board hands the first card to the reader who may write it`, `a project manager's blank
card is writable and pinned to its project`, `the right to write a new card never opens an existing one`.

Verified on a throwaway demo-seeded database (port 8095, `%TEMP%`, schema still 6) as three accounts on
`/app/projects/7` — S-06, managed by **EMP0020**, a Viewer: that account and management each got
`.board-empty` with 2 openers and `#taskModal`; **EMP0016**, a Viewer managing nothing, got 0 of all three.
`?new=1` after the `newFormOf` fix: management — picker not disabled, 19 project options, management's hint;
EMP0020 — `disabled` project select with **1** option and the pinned sentence, while their assignee picker
still lists the whole roster, grouped by department, because `resolve()` needs a real holder; EMP0016 — no
popup at all, and no roster. Then the writes: EMP0020's create posted a *different* project's name and still
302'd to `/app/projects/7`, where the card is printed and `.board-empty` is gone — project 7 went 0 → 1 task
while the project they named stayed at 1. The same POST from EMP0016 returned the permission paragraph with
their own title echoed back and left project 7 at 1. EMP0020 with `?new=1` on the general board: no popup,
no `name="new"` button. Not re-run in a browser: the in-place swap for this opener is the same
`newTaskButton` markup Stage 24 proved live, carried by `app.js`'s `document`-level delegation, and the
throwaway session can only be signed in over HTTP (auto-mode blocks typing a password into a page, even on a
throwaway).

Deployed with **no migration** and `app.css` `b9efe57d…` the only asset that moved — `app.js` `08c00489…`,
`task-modal.js` `8a5522c5…` and `kanban.js` `e39f4bf5…` unchanged — all four byte-identical between the live
8080 instance and the repo sources. **Ctrl+F5** before judging it: an old page has neither the empty-board
block nor the pinned picker, and a project manager looking at a stale board will still see no way in.

Still open from the settled rule: a project manager may add a card but may not take one away (delete has no
control anywhere), and the four fields Stage 23 greyed on an *edit* stay greyed for them — this stage only
ever touches the blank card.

<a id="stage-26"></a>

## Stage 26 — the checklists are the measurement (2026-10-14, live)

"Also when clicked on card created checklists have checkbox in front. So those checklists are measurement
for the progress. Meaning if the task has 4 checklist and 1 of them has checked progress shows 25 percent.
That applies if the project has multiple tasks also" — the tick boxes stop being a private to-do list under
a card and become the thing the app measures with. There was already a percentage on every project row, a
number typed in at creation and printed by four different pages; this makes all of them come from the steps
instead. **No migration**: everything derives from `task_checklist_items`, so the schema stayed at 6.

Four answers settled it, and they are the user's, not mine: **each card counts equally** (a project's
percentage is the *mean of its cards' percentages*, not of their steps — a one-step card and a
twenty-step card each move the project by the same amount); **card only, as now** (the popup's plan stays
read-only, so nobody ticks from inside the editor); **replace it where it shows** (every page that printed a
percentage now prints this one); and **a card with no plan contributes nothing** — it prints no percentage of
its own and is left out of its project's average, because 0 steps is not 0% done, it is unmeasured.

**The two rules live in two places, one card each.** `TaskItem.progressPercent` is the card's own figure:
`null` when the list is empty, otherwise `checklistDone` over `checklist.size` rounded to a whole percent
(`roundToInt` rounds half up, so 1 of 3 is 33 and 2 of 3 is 67). `TaskService.progressOf(cards)` is a
project's: `mapNotNull` the cards that have a plan, average them, round — and `null` when nothing on the
project has a plan. `TaskService.progressByProject(cards = assigned())` groups one read of every card by
project and returns only the projects that can be measured, and
`displayedProgress(project, measured)` is the one-line answer every page prints: the measured figure where
there is one, `project.progress` where there is not. Each page computes the map **once** per render and hands
it to every row, so the whole board costs one query set regardless of how many projects it prints.

**The fallback is not cosmetic, and `ProjectRepository.setProgress` has no caller.** A project nobody has
written steps for still has to print something on a bar that would otherwise be empty, so it keeps the number
filed on its row. Nothing in the UI ever set that number since Stage 12 anyway — it comes in with the row at
creation (or through the legacy JSON import) — so "replace the printed number" removed no control anybody
uses, and no stored value was deleted.

**Four print sites, one source.** The Projects board row (`projectTable`), a project page's detail bar
(`projectDetailContent`), the per-account project list on Settings (`acctDetailRow`'s `mprog`), and the Team
popup's JSON (`buildMemberDetails` → `MemberProjectInfo.progress`). The same 25% is therefore on the board
row, the page the row links to, the manager's account card and the people page — four pages, one rule, and no
way for them to disagree.

**The bar measures the project; the strip measures this reader's board.** *(Stage 28 deleted the strip; the
bar rule below still holds.)* That is the one decision I took
while coding rather than asking, and it is deliberate. The Stage 21 strip is scoped to the cards the reader
sees, which is right for "what have I got on this project". A percentage printed on a *row of the Projects
board* cannot be scoped the same way: the board shows one number per project to everybody, so a project page
that reopened that row and printed a different figure would read as a bug, not as privacy. So
`progressByProject()` defaults to `assigned()` — every card on the project — and a `Viewer`'s project page
shows the same bar the board sent them to, while the tiles underneath keep counting only their own cards.

**A card now prints its own percentage, inside a new live region.** `taskCard` wraps its `kmeta` row and its
checklist in `div.kbody` (`id="task-body-<taskId>"`) and adds a `.kpct` chip beside priority and due date —
omitted entirely when the card has no plan. The popup's plan heading grew the same figure: `1 of 4 done ·
25%`, and a plan-less card reads `0 of 0 done` with no claim after it.

Why the region is the card's *body* and not the card: `kanban.js` binds `dragstart` per `.kcard` node and
`dragover`/`drop` per column **at page load**, so replacing a `.kcard` element from a live response would
leave that card undraggable until a reload — a silent regression, exactly the class of thing Stage 24 had to
say out loud about. With `.kbody` inside it, the card node keeps its identity and every handler stays bound;
`data-id`, `draggable="true"`, `data-task-card` and `tabindex` all survived the throwaway check unchanged.
The step forms now name `#task-body-<id>` instead of `#checklist-<id>`, because a tick changes two things
inside the card — the box and the chip — and swapping only the checklist would leave the chip on the old
number.

**The companion contract grew one id.** `PROJECT_COMPANIONS = "#project-summary,#project-progress"` is used
by the card's step forms, and the popup's add/remove forms add `,#task-body-<id>` so a step written in the
editor moves the card's chip on the board behind it. The detail bar's `div` gained `id="project-progress"` so
a tick can redraw it next to the **Steps** tile; `app.js` skips a companion that is not on the page, which is
why the general board can name both ids harmlessly.

**What deliberately stayed on the stored figure: the emails.** `DueDateAlertService.projectBody`, the project
lines in `NotificationService`, and the alert sweep's `p.progress >= 100` skip all read `projects.progress`.
Out of this stage's scope ("where it shows" was about the pages), and changing a mail rule that decides
*whether to mail somebody* is a separate call. The consequence is real and worth stating: a project at a
measured 100% keeps being described by its stored number in mail, and a project whose stored figure is 100 is
skipped by the due-date sweep even if its cards say otherwise. Say so if the user asks why the mail disagrees
with the board. Likewise: a card dragged to **Completed** with unticked steps still prints its checklist
percentage — the user's rule is that the checklists measure progress, so no "Completed means 100" override
was invented.

**192 tests / 19 classes / 0 failures** (from 174 — 18 added, 2 rewritten). Service, the rules themselves:
`one tick in four steps is 25 percent on the card`, `a card nobody wrote steps for is unmeasured, not at
zero`, `a project averages its cards equally, not their steps`, `an unplanned card is left out of a project's
average entirely`, `each project is measured from its own cards and no other`, `a project nothing can measure
keeps the number stored on its row`, `ticking a step on the board moves its project's measurement`. Board
markup: `a card prints the percentage its own steps add up to` (and asserts the chip sits *above* the plan),
`a card nobody wrote steps for prints no percentage at all`, `management reads a colleague's percentage
without their tick list`, `the popup prints the same percentage the card does`, `a popup on a card with no
plan counts nothing and claims nothing`, `a step written in the popup moves the card's number as well`.
Projects page: `a project's bar reads its cards' checklists, not the figure filed on its row`, `a project
nobody has written steps for keeps the figure filed on its row`, `the bar measures the project while the
strip measures this reader's board`, `the projects board measures each project from its own cards`. Team:
`the popup's project figure comes from that project's checklists`. The two rewritten cases are Stage 19's
"only the person the card belongs to is offered the tick list" and Stage 21's strip companion assertion, both
of which now name the new region and the new companion list.

Verified over HTTP on a throwaway demo-seeded database (port 8095, `%TEMP%`, schema still 6; two cookie jars,
`EMP0002` as management and `EMP0016` as a Viewer, signed in with the published demo default after a
throwaway-only Java helper cleared their `must_change_password` flag). Project S-01 begins at the stored 64
with no steps anywhere, and the board prints 64 for it and every other unmeasured project. Four steps written
through `/app/tasks/checklist/add` on the card EMP0002 owns: card `0%`, page bar `width:0%`, and the stored 64
gone from both. One tick: card `25%`, page `25%`, board row `25%`, popup `1 of 4 done · 25%`. A second card on
the same project with one step, ticked to 100%: the two cards print `25%` and `100%` and the project prints
**63%** — the mean of the cards, half rounded up, on the project page, the board row, the Settings account
popup (`mprog-bar` `width:63%`) and the Team JSON (`"progress":63`) — while S-02 and every other unstepped
project still print their stored 38, 44, 58. A third tick took the card to `50%` and the project to `75%`.
The Viewer's project page and board row print the same 63% the management board does, with no card chip and
no tick list on cards they do not hold. The in-place contract was checked on the payload rather than the
browser: the form's own `back=project` posts 302 to `/app/projects/1`, and one fetch of that target carries
`#task-body-1` (`50%`, `2 of 4 done`), `#project-progress` (`75%`) and `#project-summary` (`Steps 3 of 5`) —
the three blocks `app.js` swaps from that one response, so a tick redraws card, bar and strip without a
navigation. Not re-run in a browser: the swap itself is Stage 19/21's proven mechanism, and a throwaway
session can only be signed in over HTTP (auto-mode blocks typing a password into a page).

Deployed with **no migration**, schema still v6, `Flyway: 0 migration(s) applied` against the real
`data/pts.db`. All four served assets are byte-identical to the repo sources: `app.css` `f15ec8fd…` (the
`.kpct` chip and the `.kbody` busy fade — the only CSS this stage needed, since `--input-bg`/`--input-border`
are already themed and `.pin-btn` proves it), `app.js` `1c3ffef4…`, `task-modal.js` `36b6aa66…` (still
8141 bytes) and `kanban.js` `4029e742…`. The last three are quoted as sha256 of the file on disk, which is
the form to compare from here on: earlier stages mixed prefixes from a different digest, so their values do
not line up with these. No JS was edited at all: the live-swap behaviour comes from the attributes the server
now prints. **Ctrl+F5** before judging it — a page opened before the restart
has no percentage chip on its cards and its ticks redraw only the checklist block, leaving the chip and the
project bar on the old number, which looks like the measurement not working.

Still open, and not this stage's to close: the same percentage has no voice in mail or in the bell (above),
and a card's steps can only be authored by management while the *tick* that moves the number belongs to the
assignee — a colleague can therefore take a project's bar from 0 to 100 without management touching a plan.

### Same day, after it: the project ID got bigger and bolder

"on project function project ids are looking too small make them a bit bigger and bold" — one CSS rule, no
markup change: `table.projects-table td.c-code .code, .group-head .code, .mpop-title .code` now sets
`font-size: 14px; font-weight: 700` (the base `.code` is 12px/600). Those three selectors are every place a
*project* code prints — the board's ID column, a project page's head, and an account's project line on
Settings and Team (the latter rendered by `team.js` from the same class). It is deliberately not a change to
`.code` itself: that class is also the **employee-number** chip on the Team and Access control tables
(`AccessControl.kt` / `Team.kt` both do `td { span("code") { +employeeNumber } }`), so widening the base rule
would resize two pages nobody asked about. And no `white-space: nowrap` on the ID cell, for the Stage 20
reason: `col.c-code` is a fixed 5% of a `table-layout: fixed` grid, so a long code has to be allowed to wrap
rather than push into the name column.

<a id="stage-27"></a>

## Stage 27 — the menu counts the work still open (2026-10-14, live)

"on kanban if the tasks of project goes into completed doesnt count show it on menu bar" — the number beside
**Assigned tasks** in the sidebar had been counting every card the reader could see, finished ones included, so
it never moved as work got done. It counts only the open cards now. Nothing else changed: the board still
renders the **Completed** column and every card in it, the project strip's tiles were already split `Open` /
`Done` (that strip is Stage 28's deletion; the `Open`/`Done` split lives on in `TaskItem.isOpen`), and the
choice was made explicitly *not* to touch how progress is measured or to show a second
"Done" figure in the rail.

**The rule lives on the card.** `TaskItem.isOpen` (`status != "Completed"`) is the single expression of "still
counts as work", in the domain model next to `hasChecklist` and `progressPercent`. `DashboardCommon.visibleTaskCount`
counts through it, `TaskService.summaryOf`'s open/done pair filters through it, and the Team page's two inline
`status != "Completed"` compares — the row's open count and the sort that puts finished work last in a member's
drill-down — were moved onto it too, so the codebase has one answer to the question rather than four spellings
of it. A card parked in *On hold* or *Blocked* is open: only the Completed column closes one, which is what
`SidebarTaskCountTest` pins per column.

**What the pill promises, and what it no longer does.** The comment above `visibleTaskCount` used to say the
pill "counts what the page will actually show that reader", inherited from the Stage 19 rule that a number and
its board cannot disagree. That is now true of the *scoping* and false of the *set*: the list still comes from
`TaskService.assignedFor(employeeNumber, Authorization.canManage(...))`, so a `Viewer`'s number is still built
only from cards they would be shown, and then `Completed` is taken back out of it. So a manager whose board
holds nine cards, two finished, sees `7` beside a page with nine cards on it. README's Stage 19 paragraph and
launch-checklist item 7 were corrected in the same edit, and `SidebarTaskCountTest` asserts both halves of the
split — the number drops *and* the card stays on the board — because either half alone reads as a bug.

**Why it stays honest without a refresh.** A column move has always been `POST /app/tasks/status` followed by
`location.reload()` in `kanban.js` (drag-and-drop and the move arrows both), so the rail is re-rendered from the
database on every move. The pill therefore needs no live-swap companion: it is re-derived, not patched. This is
the one place a board action still redraws the whole page — the Stage 26 in-place contract covers checklist
ticks, not status moves.

**Suite and deployment.** 197 tests in 20 classes (new `SidebarTaskCountTest`, 5 tests), no migration — schema
still v6 — no CSS, no JS, so no asset hash moved. Verified signed-in against a throwaway seeded database on
8097: management's pill `8` → `7` on completing a card whose title still printed under
`>Completed</span><span class="kcol-count">1`, a `Viewer` with no cards `0`, then `1` after management assigned
them one through `/app/team/assign`, then `0` again after *they* completed it with the card still on their
board, and the project strip reading `Cards 3 / Open 1 / Done 2` for the two completed plus one open card —
which is the `summaryOf` refactor proving itself a no-op. `deploy/run.bat` printed "still not answering after
30s" again during this deploy: Git for Windows ships its own `timeout.exe` in `usr\bin`, which sits ahead of
`System32` in the PATH a cmd inherits from this shell, so even the spelled-out name found the coreutils program
that rejects `/t`. The wait is now `ping -n 2 127.0.0.1`, a real one-second pause in any shell. The instance on
8080 is the new build (`GET / → 200`, `GET /app/projects → 302`); its log was deliberately not read.

<a id="stage-28"></a>

## Stage 28 — the project page stops reporting its own board (2026-10-14, live)

"when clicking on project no need to display cards open done overdue steps and end date" asked for the
roll-up strip on a project page (Stage 21) to go away. The six names the user listed are exactly the six
tiles in order — `Cards`, `Open`, `Done`, `Overdue`, `Steps`, `End date` — so the tile row is what was
meant; the whole block went with it rather than being left as a strip of two orphan lines.

What was removed, and why nothing else had to change:

- `Projects.kt`: `projectSummaryStrip()`, its helper `sumTile()` and the `andList()` that only the people
  line used; the call at the foot of the detail card; and the `val summary = TaskService.summaryOf(...)`
  read that existed only to feed it. `projectDetailContent` now ends its detail card at the progress bar.
- `TaskService.summaryOf` and `domain.ProjectTaskSummary` went too, deliberately: the project page was
  their **only** production caller, so keeping them would have left a roll-up model that nothing renders
  and that a future reader would assume drives something. `parseDay` (its only users were the overdue and
  countdown arithmetic), the `ChronoUnit` and `ProjectTaskSummary` imports and the `.project-summary` /
  `.sum-*` CSS rules went in the same pass. `isIsoDate` and the rest of `TaskService` are untouched.
- `TaskBoard.kt`: `PROJECT_COMPANIONS` is now `"#project-progress"` alone. The tick forms on a card still
  name the bar they move, so Stage 26's in-place contract is intact — a tick redraws `#task-body-<id>` and
  the project's bar, and there is no third block to chase. `app.js` needed no edit: it already skips a
  companion id that is absent from the response, which is what let the same markup work on the general
  board all along.
- What the user did **not** ask to remove, and what stays: the detail card's own `End date` field (a fact
  about the project, not a countdown of the board), the bar, the board heading's "— n assigned", each
  column's `kcol-count`, and each card's own `n of m done` plus its `%` chip. The board's column heads are
  the same five numbers the dots line used to repeat.
- `TaskItem.isOpen` (Stage 27) is unaffected — it is still what the sidebar pill and the Team sort read;
  only its second consumer, `summaryOf`'s open/done pair, is gone.

**Suite and deployment.** 189 tests in 20 classes, 0 failures: 8 roll-up arithmetic tests left with
`summaryOf` from `TaskServiceTest` (the hand-built `card()` helper stays — the percentage tests use it),
`ProjectsPageRenderTest`'s three strip tests were rewritten as three absence tests plus the surviving
scoping promise, `SidebarTaskCountTest`'s last test now compares the pill against the reader's own card set
instead of against a strip, and `TaskBoardRenderTest`'s two `data-live-extra` needles name one selector.
No migration — schema still v6 — and `kanban.js` is byte-identical, but **`app.css` changed**, so
somebody must Ctrl+F5 before the page they see matches this build. Verified signed-in against a throwaway
seeded database on 8097 (management `EMP0002`, `Viewer` `EMP0016`, demo default passwords, first-login gate
cleared by SQL, nothing from `data/` opened): management's `/app/projects/1` had **0** hits for
`sum-tile|project-summary|sum-cols|sum-people|sum-val|sum-key`, its five column heads read `1 1 0 0 1`, the
heading read "— 3 assigned", `End date` still printed `2026-12-15` as a project field, both step forms
carried `data-live-extra="#project-progress"`, and ticking one step moved the bar `0% → 50%` with the card's
own "1 of 2 done" beside it. The `Viewer`'s copy of the same project had no roll-up either, one card,
`0 1 0 0 0`, "— 1 assigned" and pill `1`. Then stop → `installDist` → `deploy/run.bat`; 8080 answers
`GET / → 200`, `GET /app/projects → 302`, and the served `app.css` hashes to the source file's SHA-256
(`96e92675…`). Note for the next deploy: `run.bat` was started from this shell and the wrapper's own exit
code is still not the check — the port and the two curls are, and the launcher had to be left in the
background because its readiness loop keeps the pipe open.

**Reversible knob:** the arithmetic was deleted, not hidden. If a roll-up is wanted again, the honest shape
is one line read off the board's own `kcol-count`s rather than a second counter that has to be kept equal
to it.

**Comment sweep (same day, after the first deploy).** A grep for the deleted block's vocabulary found five
places still describing it as if it were on the page: `TaskService.progressByProject`'s KDoc ("the per-reader
numbers stay where Stage 21 put them, in the strip under the bar"), `statusDot`'s justification for being
`internal` ("a project's summary strip repeats the board's columns" — the real remaining user is
`AssignedTasks`, which dots each card), a `TaskBoardRenderTest` note about the strip not lagging the card,
`app.js`'s companion contract comment (it named the "n of m steps" tile, so it now names the progress bar),
and README's sidebar paragraph. Three of those five sit in compiled code whose behaviour cannot change, but
the `app.js` edit changes the bytes the browser is served — static assets are linked by plain path, with no
`?v=` query and no cache headers on the response — so the distribution was rebuilt
and 8080 restarted to keep the running instance and the tree on the same asset. Suite re-run after the
edits: still 189 tests in 20 classes, 0 failures.

<a id="stage-29"></a>

## Stage 29 — every field of the detail card reads the same way (2026-10-14, live)

"revenue and progress are showing underlined make them show in one line". On the project page those are the
last two fields of `.detail-grid`, and both were showing their value *under* something twice over: each field
stacked its label and its value (`.detail-item` is `flex-direction: column`), and as the grid's ninth and
tenth cells they were the pair most likely to fall onto a row of their own. The first pass fixed the stacking
and gave the two a full-width row of their own; the answer back was **"i meant in same line as project number
category client manager start date end date and status"** — no row, no exception, just two more
`caption value` fields in the same run. That rejected design is recorded below because the code no longer shows
it and the word "pair" in the markup invites the wrong reading.

- `Projects.kt`: all nine fields are `div("detail-item")` cells holding `span("detail-key")` + value, and the
  last two — `detailItem("Revenue", …)` and the hand-built Progress cell — sit inside one `div("detail-pair")`
  that is **a single item of the run**, not a line of its own. The pair is there so the two travel together:
  a card with room for the amount and not for the bar moves both down instead of stranding the bar. The wrapper
  keeps `id="project-progress"` **on the Progress item inside it**, which is the id `app.js` names in
  `data-live-extra` — the live bar swap is unchanged, and `TaskBoard.kt`'s `PROJECT_COMPANIONS` still reads
  `"#project-progress"`.
- `app.css`: `.detail-grid` is no longer `repeat(auto-fill, minmax(180px, 1fr))` — equal columns are exactly
  what stranded the last two fields whenever nine did not divide into however many tracks the window made — it
  is `display: flex; flex-wrap: wrap; align-items: baseline; gap: 10px 24px`, so each field takes whatever room
  the line has left. `.detail-item { display: flex; align-items: baseline; gap: 8px; min-width: 0 }` puts the
  small caption and the larger value on one text line, with `min-width: 0` so a long client name folds inside
  its own field instead of pushing the next one off the card. `.detail-key` gained `white-space: nowrap`,
  otherwise "Start date" breaks in half inside a flex item and the field is back on two lines.
  `.detail-item .progress { flex: 0 1 110px; min-width: 40px }` lets a narrow card shorten the bar rather than
  overflow. `.detail-pair` repeats the wrapping-flex treatment for its two fields.
- **The rejected shape, for the record:** `.detail-pair { grid-column: 1 / -1 }` plus
  `.detail-pair > .detail-item { flex-direction: row }` and `.detail-pair .pct { margin-left: 0 }`, chosen
  because the old grid's track count depends on the window and a span of all tracks was the only way to make
  the pair one row at *every* width without `@media` queries (Stage 16). It was still their own row, which is
  what the ask objected to. No rule gives the pair a width of its own now, and the test asserts
  `detail-pair" style` never appears so it cannot quietly come back.
- **Suite:** 190 tests / 20 classes / 0 failures — one new `ProjectsPageRenderTest` case,
  `revenue and progress are one-line fields inside the same run as the rest`, counting nine
  `class="detail-item"` cells and exactly one `class="detail-pair"`, insisting both `>Revenue<` and `>Progress<`
  are plain caption spans, that `id="project-progress"` is still inside the pair (so a tick's live bar swap
  lands), and that `End date`'s and `Status`'s values come before the pair — the field order may not move.
  Layout itself cannot be asserted from markup, so it was measured in the in-app browser against a throwaway
  file that links the real `base.css`/`app.css`. Grouping the fields by the y of their `.detail-key` — the
  reliable line test, since baseline-aligned items on one line differ by ~2px in their own boxes — every field
  is 19px tall (one line) at grid widths 1500/1400/1200/900/600/460px, Revenue and Progress share Status's line
  at all six, and the grid's `scrollWidth` never exceeded its `clientWidth`; below ~450px the pair lets its two
  fields split, each still one line, still no overflow. The throwaway was deleted afterwards.
- **Deploy:** no migration (schema still v6), no JS change. `app.css` went
  `96e92675…` → `ff830e03…` (the rejected row) → `ef3ee425…` (what ships); stop → `installDist` → `run.bat`,
  and the `static/css/app.css` extracted from `build/libs/murray-pts-2.0.0.jar` hashes to the source's, so the
  running build carries this CSS. **Ctrl+F5**: assets are served with no cache headers, so a tab open before
  the restart still shows the old layout.

<a id="stage-30"></a>

## Stage 30 — a project's board is cut by its own lanes (2026-10-15, live)

"when a new project is created task types shown as task buckets such as engineering procurement production as
default but the user is able to create new bucket. No need to show task in progress. Dont need to set manager
again so take away that part". Four questions were settled before any code moved, and each answer is load-bearing
below:

- **Per project**, not company-wide: a project is created with **Engineering / Procurement / Production** and its
  own manager adds to that list. A shared list would show a Maintenance job a Procurement column it never has.
- **On project pages only.** `/app/assigned-tasks` keeps its five status columns, because that reader is asking
  "what is on me now" across every project, and there the answer is a progression.
- **The status stays, hidden.** It stops deciding a column and keeps its two real jobs: it closes a card — which
  is what the sidebar pill, the Team counts and the due-date alerts read — and it is still settable in the card's
  editor. Removing it would have removed the definition of "open" from half the app.
- **The project page's "Project manager" panel goes**, the picker with it. `POST /app/projects/{id}/manager` and
  `ProjectService.setManager` are untouched — the field still needs a route for whoever creates the project — but
  nothing on a project page posts to it.

**Schema — `V7__project_task_buckets.sql`.** A new `project_buckets(id, project_id → projects ON DELETE CASCADE,
name, position)` with `INDEX (project_id, position)`, plus `tasks.project_bucket_id INTEGER NULL REFERENCES
project_buckets(id) ON DELETE SET NULL`, then a `CROSS JOIN` backfill that gives every project already in the
database its three lanes in board order. It is a *new table* rather than a `project_id` on the old `buckets`
because `buckets` is the Other-tasks column list and `tasks.bucket_id` points at it for `kind = 'OTHER'`; folding
the two together would mean every read of the shared list — and the importer's name→id map, which assumes names
are unique — had to remember to filter. One column per board is the cheaper promise: an assigned card's lane is
`project_bucket_id`, an other-task card's column is `bucket_id`. `SET NULL` is what makes "delete a lane" one
statement that cannot lose a card, and `position` is the same append counter the checklist, notes, to-do items and
buckets already use. Existing cards keep `project_bucket_id` NULL — the migration cannot guess which phase a piece
of work belongs to, and guessing wrong is worse than leaving it where somebody can see it and drag it out.

**Domain (`Task.kt`).** `ProjectBucket(id, projectId, name, position)` alongside the existing `Bucket`;
`DEFAULT_PROJECT_BUCKETS = listOf("Engineering", "Procurement", "Production")`; `UNSORTED_LANES = "Unsorted"`;
`PROJECT_BUCKET_MAX = 40`. The service-facing `TaskDraft.projectBucketId` is `Int? = null` and the distinction is
the whole rule: **absent ≠ 0** — `0` means the reader chose the Unsorted column, `null` means this board has no
lane picker at all, which is exactly what the general Assigned tasks save sends. So saving a card from
`/app/assigned-tasks` cannot quietly un-file a card that happens to live on a project with lanes. `TaskItem`
carries the resolved `projectBucketId: Int = 0`.

**Rules (`TaskService`).** `lanesOf(projectId)` reads one project's lanes in order; `mayEditLanes` *is*
`mayAddTasks` (management or that project's manager), so what the page hides is only what the POST refuses;
`addLane` returns a `LaneAddResult` with one code per rule and checks them in the order
DENIED → NO_PROJECT → BLANK → TOO_LONG → DUPLICATE, the duplicate compared **after the trim and ignoring case**,
because `" engineering "` onto a board that prints **Engineering** is the same phase with a different initial
letter, not a second column; `moveToLane(id, bucketId, caller, canManage)` uses the mover `updateStatus` already
uses, treats `0` as "unfile it", and refuses a lane that belongs to another project, so a hand-built request with
a foreign id moves nothing. `ProjectService.create` calls `ProjectBucketRepository.seedDefaults(id)` right after the
insert — a project never has to be given a board before it can be used.

**Routes (`Application.kt`).** Two, both twins of something that already existed.
`POST /app/projects/{id}/buckets` hands every decision to `addLane` and only chooses the destination: a board that
took the lane shows it, a refusal comes back as `?lane=<code>` on that same project page so the complaint can print
under the box the name came from (field-level, per the standing rule), and DENIED / NO_PROJECT redirect to
`/app/projects` because a page with no box is not a page to leave a message on. `POST /app/tasks/bucket` is the
drag, deliberately written as a twin of `TaskService.updateStatus` rather than a third way to save a card; it
redirects to the request's `Referrer` (default `/app/assigned-tasks`) — with `Referrer-Policy: same-origin` that is
what makes the no-JavaScript path land back on the page the reader stood on.

**Markup (`TaskBoard.kt`, `Projects.kt`).** `taskKanban` grew a `lanes` list plus `canNameLanes`/`laneNote`; with
lanes it draws `laneKanban` inside `div("lane-area") { id = "laneArea" }`, and the general board still calls
`statusKanban` unchanged. Columns carry `data-lane` instead of `data-status`, and the **Unsorted** column is drawn
only while a card needs one. Two small structural choices: every lane column prints its `Nothing here.` line
*after* its cards — `.kcol .kcard + .kcol-empty { display: none }` in `app.css` is the rule that hides it, so a
column that just received its first card loses the message in the same repaint, with nothing in the script to keep
in step (the status board still writes it conditionally, because it reloads) — and `.kanban-lanes` uses
`repeat(auto-fit, minmax(210px, 1fr))` rather than the status board's `repeat(5, …)`, since the lane count is
whatever that project has been given and `auto-fit` (not `auto-fill`) lets three lanes take the width between them
instead of leaving two empty gaps. A lane board tints a card **only once it is done**: the other palettes would be
printing a status this page decided not to show, and a yellow card with no column to read it against is a colour
nobody can decode. Its non-manager buttons collapse from prev/next arrows to one `✓ Done` / `↺ Reopen`, which still
posts `/app/tasks/status` — so closing a card costs no new script or route. The editor's lane picker puts Unsorted
first (`value="0"`) and defaults an edit to the card's lane, a new card to the project's first one.
`Projects.kt` passes `lanes = TaskService.lanesOf(project.id)` and `canNameLanes = …mayEditLanes(...)`, and the
`Set manager` block is gone from the page; the three `.manager-set` rules went from `app.css` with it.

**In place, not reloaded.** The columns *and* the box that adds one are one swappable block: `laneAddForm` is
`form(action="/app/projects/{id}/buckets")` carrying `data-live-target="#laneArea"`, so `app.js` swaps the whole
lane area and a new column arrives on the board the reader is looking at, the box still under the cursor, the
refusal printing as a `.field-err` inside that same swap. `kanban.js` binds the lane half of its listeners on
`document` rather than per column for exactly that reason — the columns it will be asked to drop onto are not the
ones present at load. A drop posts `/app/tasks/bucket` and then `file()` moves the card node itself in front of the
column's empty line and `count()` rewrites both heads; if the last card has left the Unsorted column, `file()`
removes that column. The status half of the script — the per-column `.kcol[data-status]` binding and the reload on
a move — is untouched, and the Done/Reopen button still reloads, which is how the sidebar pill comes back in step.

**A gap found by verifying, not by testing.** `JsonImporter` writes project rows directly instead of calling
`ProjectService.create`, so a demo- or legacy-seeded database came up with `project_buckets = 0`: every imported
project opened on the status columns while every project created through the app opened on its own lanes. The
importer now seeds the same three inside its project loop, and `JsonImporterTest` asserts it per project through a
real import into a fresh database.

**Suite:** **217 tests / 21 classes / 0 failures.** New coverage: `ProjectBucketMigrationTest` (4) runs V1–V6 on a
harness database and then the real `Migrations.migrate`, checking every pre-existing project got its three lanes in
board order, that each project got *its own* rows rather than a shared set, that an existing card was left unfiled
instead of guessed at, that deleting a lane un-files its cards while deleting a project takes its lanes with it, and
that the backfill does not replay on a second boot. `ProjectServiceTest` (18) holds that a created project arrives
holding the three defaults in order. `TaskServiceTest`'s V7 section (9 of its 51) exercises the rule order, the
case-and-space-insensitive duplicate, two projects each naming a lane **Engineering**, append position, the mover
rule, the foreign lane id, `bucket = 0`, and the absent-field case above. `TaskBoardRenderTest` (46) pins that a
project page emits `data-lane` and never `data-status` for its columns, that `/app/assigned-tasks` still emits
exactly five `class="kcol" data-status` with no `data-lane` and no `/buckets` form on the page, that the lane box
appears only for the reader whose POST would be accepted, that its `data-live-target` names `#laneArea`, and that a
new card from a project page lands on that project's first lane. `ProjectsPageRenderTest` (20) keeps the Stage 28
absence assertions — its four-card project page now counts four lane heads, the project's three plus an Unsorted
column — and adds that no `Set manager` control of any kind reaches the page for either role.

**Verification.** Both roles on a throwaway database under `%TEMP%\pts-stage30`, port 8095, config passed as real
environment variables, the throwaway's own accounts cleared of the first-login gate by a scratch Java program that
flips `must_change_password` and touches no password. Note for future stages: **the in-app browser cannot be used
for signed-in checks any more** — filling a password field is refused outright, demo accounts included, so the
round trips are driven with `curl` and one cookie jar per role. That covered: project pages as management (lane
columns, five status columns absent, lane box present) and as a `Viewer` (lanes visible, no box, and no status word
anywhere in the page as it loads); a lane add answering `?lane=duplicate` with the message inside the swapped
block; a `Viewer`'s hand-built lane POST turned away; the swap response containing `id="laneArea"` with the new
column *and* the box inside it, which is the in-place contract; a foreign lane id moving nothing; `bucket=0`
un-filing; a drag to a lane surviving a reload; and the regression that mattered most — a general-board save with no
`bucket` field leaving the card in the lane it was in.

**Deploy:** `data/pts.db` snapshotted as `data/pts.pre-v7-20261015-0902.db` (gitignored) with the JVM stopped,
`installDist` run only after `netstat` confirmed nothing held 8080, then `deploy\run.bat`. `flyway_schema_history`
now reads versions 1..7 against the real database: 6 projects, 7 cards, **18 lanes**, 0 projects without a board,
5 cards unfiled and 0 filed — what the backfill promises and what the migration comment says it will not guess
past. The jar's assets hash-identical to source: `app.css bf30c594…`, `kanban.js daa75f02…`, `app.js 11a24e8c…` —
all three changed since Stage 29, so the `ef3ee425…` recorded there is stale. **Ctrl+F5** before judging the page:
assets ship with no `?v=` and no cache headers.

<a id="stage-31"></a>

## Stage 31 — a lane can be taken back off the board (2026-10-15, live)

**The ask.** "a user able to delete bucket as bucket has small button on top right". The three questions it
raised were settled by the reader, not here: the right is **whoever can add one**, a lane that still holds
cards is **deleted with its cards moving to Unsorted**, and the scope is **project lanes only** — the Other
tasks board's `buckets` columns are a different table with a different owner and are untouched.

**No migration.** The table, the `ON DELETE SET NULL` key and the `position` counter are V7's already; the
`ProjectBucketRepository.delete(id: Int)` the migration test exercised was already written and already proven
to leave the cards existing with `project_bucket_id` NULL. This stage is a rule, a route, a form and a style.

**The rule** is `TaskService.removeLane(projectId, laneId, callerEmpNo, canManage): LaneError?`, next to
`addLane` and reading in the same order: `mayEditLanes` (so the × is offered to exactly the accounts whose POST
passes — `LaneError.DENIED`), the project existing (`NO_PROJECT`), the lane existing **and belonging to this
project** (`NO_LANE` — a form posts an id, and an id is the browser's word for a column, not proof of one),
and only then the delete. `LaneError` grew `NO_LANE` and `LAST_LANE` beside the codes `addLane` already
returned, and `laneNoteOf` prints them under the box like every other lane complaint: "That lane is no longer
on this board." and "A board keeps at least one lane — this one has nothing left to remove."

**The last-lane guard is my own addition, not the user's ask,** and it is load-bearing: a project with zero
lanes makes `taskKanban` take its `laneBoard = lanes.isNotEmpty()` branch into the **five status columns**,
which is precisely the shape Stage 30 decided a project page does not have, and `#laneArea` would vanish from
the swap response, so `swapBlock` would throw and the page would fall back to a navigation. Refusing the last
one keeps both promises with one comparison and tells the reader why.

**The route** is `POST /app/projects/{id}/buckets/delete` — a distinct literal segment, so it does not collide
with `POST /app/projects/{id}/buckets`, and `receiveParameters()["bucket"]` is read the way the lane-drop route
reads it. `DENIED` and `NO_PROJECT` redirect to the Projects list (that account has no business on this page);
every other refusal redirects back to the same project with `?lane=<code>`, whose underscores `laneNoteOf`
already normalises, so `LAST_LANE` arrives as `last-lane`. Success redirects plain, and the page that comes
back is already the shorter board.

**The markup** is `laneRemoveForm(projectId, lane, cardCount)` inside `laneKanban`'s column head, after the
count: a `form.lane-del-form` carrying `data-live-target="#laneArea"` — the same block the add box names,
because a removal changes the columns — a hidden `bucket` input holding that lane's own id, and a
`button.lane-del` labelled only `×`, with `aria-label="Remove the <name> lane"` and a `data-confirm` that
counts what is about to move: "Remove the Procurement lane? Its 3 cards move to Unsorted." (singular for one
card), and the plain question when the lane holds nothing. Unsorted gets no ×: it is not a lane anybody named
and there is no row to delete. `canNameLanes` was renamed `canEditLanes` through `taskKanban`, `laneKanban`
and `Projects.kt` because it now draws two controls, and the render flag is the same `TaskService.mayEditLanes`
call the POST makes, so the control and the rule cannot drift.

**The style** is six rules for `.lane-del` in `app.css`: 22×22, transparent until hovered, then the same
`#c92a3d` / `rgba(220,53,69,…)` palette `.delete-btn` and `.field-err` already use, with a `data-theme="dark"`
variant and a `:focus-visible` outline. `.kcol-head` is already `display:flex` with `.kcol-count` on
`margin-left:auto`, which is what puts the × at the column's top-right corner and the count just left of it
without a wrapper of its own; `base.css` styles `button.submit` only, so a plain `button.lane-del` inherits
nothing to fight. `.lane-del-form { display:inline-flex; margin:0 }` stops the form from adding a gap of its
own. No JS changed: `app.js` handles `[data-live-target]` and `[data-confirm]` through listeners delegated on
`document`, so a × swapped into `#laneArea` is wired the moment it exists, and `swapBlock` restores focus by id
only when the old node had one — the × has none, and after the swap the removed column is gone, which is the
whole point.

**Suite:** **227 tests / 21 classes / 0 failures** (from 217). Five new `TaskServiceTest` cases: the manager's
own lane going with the order preserved (`position`s stay 0 and 2 rather than being renumbered, and the other
project's board is untouched), the right (`EMP0002`/`EMP0003` refused, management and this project's manager
accepted), the id that is not this board's (foreign, `4242`, `0` for Unsorted, absent) all coming back
`NO_LANE` with **both** lanes still findable, the cards surviving a real removal as the same title on the same
project with the same owner, and the last lane refusing for management and for the manager alike while staying
a lane a card can still be filed into. Five new `TaskBoardRenderTest` cases pin one × per lane and none on
Unsorted (four heads, three buttons), the id in the hidden field and the lane's name in its own prompt, the
swap target count being lanes + 1 (the add box is the other one), nothing at all for a reader whose POST would
be refused, the singular/plural confirmations, and the general board printing neither `/buckets` form.

**Verification.** Both roles on a throwaway database under `%TEMP%\pts-stage31`, port 8095, config as real
environment variables, `PTS_DATA_FILE` pointed at a path that does not exist so the legacy importer stayed out
of it and `PTS_SEED_DEMO=true` gave the sandbox its own 19 projects, with the throwaway's six accounts cleared
of the first-login gate by the scratch flipper (metadata printed, no password touched, no hash read). Three
jars: `EMP0002` (Manager access **and** project 1's `manager_emp_no`), `EMP0020` (Viewer access, project 7's
manager) and `EMP0016` (Viewer, no project). Over `curl`: a card filed into Production by the existing lane-drop
route made the live page print "Remove the Production lane? Its 1 card move to Unsorted." while the two empty
lanes asked plainly; project 1 as its manager carried 3 delete forms with hidden ids 1/2/3 and 4
`data-live-target="#laneArea"` (three × plus the box); the same page as `EMP0016` carried none; `EMP0020` on
**project 1** was turned away (`302 → /app/projects`) and on **project 7** was accepted (`302 →
/app/projects/7`), which is the per-project half of the right proven from both sides. Deleting the lane that
held the card left the board reading `Procurement | Unsorted` with the card still on it, and the throwaway's
own rows showed `project_bucket_id` NULL with the lane rows gone. A foreign id (project 2's lane 4), an id
nobody has (9999) and no field at all all returned `?lane=no_lane` and rendered "That lane is no longer on this
board." inside `#laneArea` with the × controls still drawn — the refusal leaves the reader on the page they
were on. Taking project 1 down to one lane and asking for it returned `?lane=last_lane` and its sentence, with
the column and its × still there. `/app/assigned-tasks` for management printed 5 `class="kcol" data-status`
columns and **zero** occurrences of `/buckets`, the scope answer on the real page. The directory was deleted
afterwards.

**Deploy:** no migration, so no snapshot was needed — `deploy\stop.bat` (force), `installDist` only after
`netstat` confirmed 8080 free, `deploy\run.bat`, then `GET /` → 200. Schema stays **v7**. The jar's assets
hash-identical to source: `app.css **f8fe75b4…**` (changed from Stage 30's `bf30c594…`), `kanban.js daa75f02…`
and `app.js 11a24e8c…` both unchanged, which is the point worth recording: this feature needed no script.
**Ctrl+F5** before judging the board — assets ship with no `?v=` and no cache headers, and the × is invisible to
a browser serving the old `app.css` even though the new page markup is already there.

<a id="stage-33"></a>

## Stage 33 — the create popup gives the lanes back (2026-10-15, live)

"when creating a new project no need to take task lanes. Once a new project created there are three types of task
buckets shown as i attached picture". This **cancels Stage 32**, which was built, tested, deployed and documented
earlier the same day: the New project popup again asks nothing about lanes, and a project's board is simply the
three defaults that already existed when its page opens.

**Do not re-propose naming lanes in the create form.** The user's answer to Stage 32's AskUserQuestion ("name the
lanes at creation") turned out to describe the *result* they wanted — three buckets present — not the *mechanism*
— a form to fill in. Stage 30 already delivered the result. The lesson this section exists to carry: a new field
in that popup is a question the reader has to answer before they can use what they just made, and the popup has a
standing rule against that — it asks for the project, never for the project's contents. When an ask can be read
either as "change the shape" or "change the form", the shape reading is the one Stage 30 already satisfies.

What came back out (the exact inverse of Stage 32's list, which is why that list is worth keeping above):

- `ProjectDraft.lane1/2/3` and `ProjectField.LANE1/2/3` are gone — `ProjectField` is once again exactly the nine
  form fields, which is why the enum is what a regression test can pin.
- `ProjectService.lanePlan(...)` is deleted and `create` writes `ProjectBucketRepository.seedDefaults(id)` again,
  in the same breath as the project row.
- `ProjectBucketRepository.seedNames(projectId, names)` is folded back into `seedDefaults`, which maps
  `DEFAULT_PROJECT_BUCKETS` over `insertRow`. `insertRow` itself stays — `insert(projectId, name)` (the add-lane
  path) uses it too, so there is no drift between the three defaults and a fourth lane named later.
- `projectCreateModal`'s three `div("field")`s, their placeholders, their hint and their `field-err` slots are
  gone; `POST /app/projects` reads its nine parameters again. The popup ends where it did before Stage 32, on the
  line "Assignees and progress are managed on the project page after it is created."
- `domain/Task.kt`'s `ProjectBucket` and `DEFAULT_PROJECT_BUCKETS` docs no longer describe a lane row that does
  not exist.

What deliberately stayed: everything from Stage 30 and 31 — the *Add lane* box, `mayEditLanes`, the lane head's
**×**, `last-lane`, `no-lane`, `TaskDraft.projectBucketId`'s absent ≠ `0` rule, the V7 backfill and the importer's
three defaults. **No migration**; the schema remains **v7**, and projects created while Stage 32 was live already
hold their own `project_buckets` rows — a board whose creator named it keeps those names, because nothing here
re-writes an existing board. Only the asking stopped.

Stage 32's **Unsorted** asymmetry survives as Stage 30's: `addLane` reserves no name, so a board can be given a
lane called **Unsorted** and then print two heads of that word, only one of them removable. With `lanePlan` gone
the fix is a single case-insensitive refusal in one place rather than two, and it is still the user's call.

**229 tests / 21 classes / 0 failures.** Nine cases went with the feature — seven in `ProjectServiceTest` (typed
names becoming the board, a blank slot keeping its own default, the trim, the 41-character refusal on `LANE2`, the
typed-vs-blank duplicate, the duplicate-on-the-second-box rule, and a lane refusal leaving the number free) and
two in `ProjectsPageRenderTest` (three labelled boxes with placeholders and `maxlength="40"`, the complaint printed
between its own box and the next) — and two went with them in the sense of replacing them, both pointing the other
way now: `ProjectServiceTest` asserts `ProjectField.entries` is the nine fields and that a successful create still
writes `DEFAULT_PROJECT_BUCKETS` in order, and `ProjectsPageRenderTest` asserts a manager's popup carries no
`name="lane1/2/3"`, no *Task lane* label and no default-sentence — before and after a rejection, which is the state
that re-renders from `ProjectDraft` and is therefore where a re-added field would surface. The `tagAround()` helper
Stage 32 needed to read one input's attributes is gone with them; the Viewer case lost its `name="lane1"` assertion
because no role is asked now, which the manager-side test says better.

**Verified on a throwaway, not assumed:** booted on 8095 off `build/classes/kotlin/main` + `build/resources/main` +
the dist jar wildcard — no `installDist` over the live JVM — with a fresh DB and every value passed as a real
environment variable, `PTS_SEED_DEMO=false`, alerts off. A manager's `/app/projects` came back with **zero**
occurrences of `name="lane`, of *Task lane* and of "Leave one blank", while all nine field names were present once
each and the closing sentence was there. `POST /app/projects` with the nine fields → `302 /app/projects/1` and that
page printed three `data-lane` columns headed **Engineering**, **Procurement**, **Production**. A second create
carrying the stale `lane1=Survey&lane2=Install&lane3=Handover` — the exact POST an old tab or a hand-built request
would send — was not a hole: the extra fields were simply not read and project 2 opened on the same three defaults.
The project page's own lane controls were confirmed still there (`id="laneArea"`, the *Add lane* box with
`id="laneName"`, four `data-live-target="#laneArea"` markers). The temp directory and the JVM were removed after
checking 8095's PID was not 808's.

**Deploy:** `deploy\stop.bat` (force), `installDist` with 8080 confirmed free, `deploy\run.bat`, then `GET /` → 200.
Schema stays **v7**. The installed jar was proved current by extracting `ProjectsKt.class` and `ProjectService.class`
from it and finding **no** `lane1`/`lane2`/`lane3`, no *Task lane* and no "A lane name is" in their constant pools —
the same check Stage 32 used to prove the strings *were* there. No asset changed in either direction: `app.css
f8fe75b4…`, `app.js 11a24e8c…` and `kanban.js daa75f02…` are Stage 31's digests, because HTML alone carried this
feature in and HTML alone carried it out. A tab opened before the restart still needs **Ctrl+F5** — and this time
the stale view shows *extra* inputs rather than a missing ×, so a reader who reports "the lane boxes are still
there" is looking at the old page, not at a failed revert.

<a id="stage-34"></a>

## Stage 34 — each lane offers to start a card in it (2026-10-15, live)

The reference board picture the user attached to Stage 33 (a kanban whose columns each end in a `+ Add task`
line) was offered back as four separate affordances and exactly one was chosen: **"+ Add task inside each
column"**. Not chosen, and not built: a dashed `+ Add bucket` column at the right of the board, a `…` menu on
a lane head replacing its **×**, and coloured dots beside lane names. **Do not add those three unasked** — the
user was shown them and picked one.

What the chosen one is: a quiet `+ Add task` line at the foot of every **lane** column on a project page. It is
not a new mechanism. It is `[laneAddTaskForm]`, a GET form onto `/app/projects/<id>` carrying `new=1` and
`into=<that lane's id>` with `data-live-target="#taskModal"`, so `app.js` answers the click by fetching this
same page and swapping only the popup — no script was written for it, no fetch was added, and with JavaScript
off it is an ordinary link-shaped form that reloads to the same page with the same popup open. The route's
[openingTaskForm] reads `into` and passes it to `newFormOf(canManage, into)`, which seeds the draft's own
`projectBucketId` — the single place the popup's Lane picker reads from — so the field opens already on that
column and its hint prints "A new card lands under Procurement."

Three decisions worth their own line:

- **`into`, not `lane`.** `/app/projects/<id>` already reads `?lane=` for something else: the code a lane add or
  removal was refused with (Stage 30/31). Two meanings on one key would make a refusal note and an aimed create
  indistinguishable, and one of the two is a page the reader must be able to read.
- **`canAdd`, the page head's own flag.** `taskKanban` already took the reader's right to write a card here and
  forwarded it to the empty-board invitation; `laneKanban` now takes the same value, so a `+ Add task` line is
  printed for exactly the accounts `TaskService.mayAddTasks` accepts a create from — management, and the account
  the project names as its manager. The line is a *prefilled* create, nothing more: `/save` still resolves the
  posted lane through `TaskService.laneOf(projectId, …)`, so an `into` that is not one of this project's lanes
  cannot file a card anywhere (the route drops it to `null` before the form is even built, and the service
  would refuse it again on the way in).
- **No line on Unsorted.** It is where unplaced cards wait, not a place somebody sets out to put one, and a card
  created there would need dragging anyway. The column is rendered by the same loop-free branch that draws it
  only while some card needs it, and it is deliberately outside the `canAdd` branch.

Style: `.kcol-add` + `.kcol-add-btn`, muted 12px text with no border and no background, accent underline on hover,
and the same 2px `:focus-visible` ring a card uses. It is *not* `.submit`, because base.css gives `button.submit`
a filled block and forty of those down a board reads as a form, not as a hint.

Known overlap, disclosed rather than "fixed": a project page with **no cards at all** now prints the page head's
`+ New task`, Stage 25's empty-board `+ New task`, *and* the three lane lines — six `data-live-target="#taskModal"`
markers, measured on the throwaway. Stage 25's invitation was kept because it is the answer to "this board is
empty", which a per-lane line does not make. If the reader later says it is too much, the empty-board copy is the
one to drop, not the lane lines.

**233 tests / 21 classes / 0 failures.** Four cases joined `TaskBoardRenderTest` — one `+ Add task` form per real
lane carrying that lane's own `into` id and `#taskModal` as its swap target while the board still shows its
Unsorted column; Unsorted offered as a destination to nobody (`name="into" value="0"` must not exist); nothing at
all printed for a reader whose create would be refused, with the same board re-rendered for a reader who may; and
the popup built the way that line builds it opening on *that* lane, its hint naming it, while an unaimed create
still lands on the project's first lane. A fifth assertion joined the existing `the general board has no lane to
remove`, because that test is already the status board's "no lane markup here" check. The `laneBoard()` helper
gained the `canAdd` argument the route passes, and a `formTags()` helper reads one class's `<form>` attributes —
the one-tag-one-line property of `createHTML()` is what makes that possible without a parser. One expectation was
wrong on the first run: `new=1` was asserted once, and got three, because **each lane's line is a create of its
own** — the assertion now pins `lanes().size`, which is the better claim.

**Verified on a throwaway, not assumed:** booted on 8095 off `build/classes/kotlin/main` + `build/resources/main`
+ the dist jar wildcard, fresh DB in `%TEMP%`, `PTS_DATA_FILE` pointed at a file that does not exist so the real
legacy JSON stayed out of it, `PTS_SEED_DEMO=false`, alerts off, session secret and admin password generated into
the environment and never printed. A manager's new project page printed **three** `class="kcol-add"` forms with
`into` values 1, 2 and 3 and all three tagged `#taskModal`. `?new=1&into=3` came back with
`<option value="3" selected="selected">Production` and the matching hint; `?new=1&into=9999` fell back to the
first lane exactly as an unaimed `?new=1` did, with no `9999` selection anywhere; `/app/assigned-tasks` printed
zero `kcol-add` and its five `data-status` columns. The JVM and the temp directory were removed afterwards.

**Deploy:** `deploy\stop.bat` (needed `/F`; the polite close event did not take), `installDist` with 8080 confirmed
free, `deploy\run.bat`, then `GET /` → 200 and `GET /static/css/app.css` → 200 carrying the four new rules. Schema
stays **v7** — this stage writes no column and no row. The installed jar was proved current by extracting
`TaskBoardKt.class` from it and finding `+ Add task`, `kcol-add` and `kcol-add-btn` in its constant pool. Two
assets changed: `app.css f8fe75b4… → be094df9…` and `task-modal.js → 0570f93e…`, the latter a header-comment
change only (its opener list now names three, and it still fills no form). `app.js 11a24e8c…` and
`kanban.js daa75f02…` are untouched, so `kanban.js`'s `document`-bound lane listeners needed no change for a
lane added after load — its line arrives with the swapped `#laneArea` and is a plain form. A tab opened before
the restart needs **Ctrl+F5**; the stale view simply lacks the lines, so a reader reporting "there is no Add task
under the column" may be looking at the old CSS and markup rather than at a failed deploy.

<a id="stage-35"></a>

## Stage 35 — a project's lanes stopped being editable from its page (2026-10-15, live, no migration)

The ask was four words: *"take away new lane on the bottom"*. That box was the only control on the page that
adds a lane, so removing it alone would have left a board a reader could grow once and never trim — the **×**
would have stayed on heads whose lane nobody could have added. One AskUserQuestion put the choice as scope, not
as wording, and the answer was **"Take the box and the ×"**: a project's board is exactly **Engineering**,
**Procurement**, **Production**, named by the V7 migration and by nobody, with no lane control anywhere on the
page. This is the second time the lane affordances have been cut back to the reader's actual ask (Stage 32→33
removed the create popup's three lane inputs); the pattern is the same and so is the lesson — an answer about
*what the board should be* is not an invitation to keep adding controls to it.

**What came out.** `TaskBoard.kt`: `laneAddForm`, `laneRemoveForm`, `const val LANE_AREA_ID` and `laneNoteOf`
are deleted, `taskKanban` lost its `canEditLanes` and `laneNote` arguments and with them the
`div("lane-area") { id = LANE_AREA_ID … }` wrapper that made the columns and the box one swappable block, and a
lane head is now `kcol-title` + `kcol-count` and nothing else. `Projects.kt`: `projectDetailContent` lost its
`laneNote` parameter. `Application.kt`: the project page's GET no longer reads `?lane=` at all. `app.css`: the
five `.lane-add` rules and the five `.lane-del*` rules are gone, each replaced by the comment that says why.
`kanban.js` keeps every listener it has and its header comment was rewritten to say so honestly — the `document`
delegation is no longer there for a lane that arrives after load (nothing adds one), it is there because the
script itself removes the **Unsorted** column when its last card leaves. `app.js` needed no change: `#laneArea`
was only ever a `data-live-target` value, never a name it knew.

**What stayed, deliberately.** `POST /app/projects/{id}/buckets` and `/buckets/delete`, `TaskService.addLane`
/ `removeLane` / `mayEditLanes`, the 40-character cap, the case- and trim-insensitive duplicate rule, the
`ON DELETE SET NULL` un-filing and the `LAST_LANE` guard all remain, and so do their `TaskServiceTest` cases.
The user asked for the *controls* back, not for lane arithmetic to stop existing: the routes are the place a
management request can still be answered, and `LAST_LANE` in particular is a service rule, not a UI courtesy —
deleting it would let a board fall through to the five status columns this page deliberately stopped having.
Card deletion is the precedent: a management-only write with no control anywhere in the interface, whose gate
still answers a hand-built request.

**Disclosed, not fixed.** Three consequences of taking the controls out: a project that gained a fourth or
fifth lane while Stages 30–34 were live keeps those columns and the page gives nobody a way to remove them;
the seven refusal codes those routes redirect with (`blank`, `too_long`, `duplicate`, `denied`, `no_project`,
`no_lane`, `last_lane`) now have **no sentence printed anywhere**, because `laneNoteOf` was the only reader of
them and the box they sat under is gone — the redirect still writes `?lane=…` into the URL and the page ignores
it; and a tab opened before this deploy still shows the box and the **×**, whose forms still post to routes
that still work, so a stale reader can genuinely add or delete a lane from old markup. That last one is an
argument for the standing **Ctrl+F5** rule, not a hole in the new page.

**229 tests / 21 classes / 0 failures** (down from 233). Six `TaskBoardRenderTest` cases retired — the add-box
visibility pair, the duplicate-name refusal, the one-×-per-lane shape, the × visibility split, the
"its 1 card move to Unsorted" prompt, and the last-lane refusal — and three expectations inside surviving tests
were turned around: the project-page test now asserts no `/buckets`, no `laneName`, no `laneArea` where it used
to assert all three, `the general board has no lane to remove` became `the status board has no lane control
either` with a `laneName` needle added, and `laneBoard()` lost its `canEditLanes` / `note` arguments. Two new
cases replaced them: `no reader is offered a control over the lanes` runs the same absence check over an
assignee's board, a management board, and a board carrying the Stage 34 add lines (six needles plus
`field-err`, which is the refusal channel's proof), and `a lane head is its name and its count` matches each
head as a **whole element** — `<div class="kcol-head">` holding one title span, one count span, `</div>` —
because an absence test on substrings passes for a control nobody thought to look for, while a shape match
fails the moment a third child appears. Same test keeps `data-lane` at four and `class="kcol-add"` at three,
which is the assertion that the removal took the controls and not the board: the columns are still drop targets
and still aim a create.

**Verified on a throwaway, not assumed:** booted on 8095 off `build/classes/kotlin/main` +
`build/resources/main` + the dist jar wildcard, fresh DB in `%TEMP%`, `PTS_DATA_FILE` pointed at a file that
does not exist, `PTS_SEED_DEMO=false`, alerts off, secret and admin password generated into the environment and
never printed. A manager's new project page printed 3 lane heads (**Engineering / Procurement / Production**),
**zero** matches for `lane-add`, `lane-del`, `laneName`, `laneArea`, `/buckets`, `New lane`, 3 `kcol-add` forms
and `into` values 1/2/3. After one unfiled card a fourth head appeared (**Unsorted**) with still no control on
any of the four. `?new=1&into=3` opened the popup on `<option value="3" selected>Production` with the matching
hint. `?lane=duplicate` and `?lane=last_lane` both returned 200 with `field-err` count 0 and no refusal wording
anywhere — the disclosed silence, measured. The endpoints were confirmed alive: a hand-built
`POST /app/projects/1/buckets` with `name=Workshop` added a fourth lane that rendered with its own add line and
no removal control, and `POST /app/projects/1/buckets/delete` with `bucket=4` took it off again.
`/app/assigned-tasks` stayed 5 status heads, 0 lane markers. The JVM and its temp directory were removed
afterwards.

**Deploy:** `deploy\stop.bat` (again needed `/F` — the polite close event does not take on this JVM), `installDist`
with 8080 confirmed free, `deploy\run.bat`, `GET /` → 200 on PID 14804. Schema stays **v7**: this stage deletes
markup and rules, writes no column and no row. The installed jar was proved current two ways — extracted
`TaskBoardKt.class` has `kcol-add`, `laneAddTaskForm`, `kanban-lanes` and `Unsorted` in its constant pool and
**zero** matches for `laneName`, `New lane`, `lane-del`, `laneArea`, `Remove the`, and the packaged `app.css` /
`kanban.js` carry the Stage 35 comments. Two assets changed: `app.css be094df9… → 999b68da…` (the deleted rules)
and `kanban.js daa75f02… → a1a54ac2…` (header comment only — the paragraph was rewritten once more after its
digest was first written down, so the shipped value is this one). `app.js 11a24e8c…` and
`task-modal.js 0570f93e…` are untouched — the popup's three openers are still three, since the add lines
survived and the box was never one of them. Stage 34's disclosed overlap is likewise untouched: an empty
project page still prints the page head's `+ New task`, Stage 25's empty-board `+ New task` and the three lane
lines; those six `#taskModal` markers were never the lane box's, which targeted `#laneArea` and is now gone
with it.

**Rebuild after the deploy:** both `/buckets` routes' KDocs still promised a refusal "under the box", so they were
corrected once the page was live — comments only, but a jar must not sit behind its source. The instance was
stopped (`/F` again, PID 14804), `test installDist` re-run on a free 8080 (**229 tests / 21 classes / 0 failures**,
BUILD SUCCESSFUL), and restarted on PID 14824 with `GET /` → 200. The four assets the page asks for were then
compared by sha256 between `src/main/resources/static/…` and the served bytes at `/static/css/…` and
`/static/js/…` — `app.css 999b68da`, `app.js 11a24e8c`, `kanban.js a1a54ac2`, `task-modal.js 0570f93e`, all four
matching. Note the URL: a request for `/static/app.css` returns nothing at all, and hashing an empty body gives
`e3b0c442…`, which looks like a mismatched build rather than a wrong path. The only `lane-add` / `lane-del` text
left in the served stylesheet is inside the Stage 35 comment that explains their removal; `kcol` rules still count
5.

<a id="stage-36"></a>

## Stage 36 — a card stopped changing size because a count changed (2026-10-15, live, no migration)

The ask was *"make the kanban static for the tasks"*, and when the choices were put to the user the answer came
back as the symptom, not the mechanism: *"i mean kanban cards dont change size depends on the counts"*. The
mechanism was the **Unsorted** column. `laneKanban` drew it only while a card had no lane, and `.kanban-lanes`
is `repeat(auto-fit, minmax(210px, 1fr))` — a track plan that `auto-fit` sizes from the number of columns it is
given. So the moment a fourth column appeared, all four were redrawn narrower, and dragging that column's last
card away — which `kanban.js`'s `file()` used to answer by removing the column from the DOM — drew the other
three wider again. Measured on the real served page: at a 1592px board a card was **365px with four columns and
499px with three**, and at 1102px **243px against 335px**. Nothing about the card had changed; only the count of
columns had.

**The fix is that the lane board now always draws its full column set**, the way `statusKanban` has always drawn
all five statuses: `laneKanban` prints the **Unsorted** column unconditionally, and `file()` no longer takes an
emptied column out of the DOM. The number of tracks therefore never moves while the reader is on the page, so
`auto-fit` — which was the right rule for a board that filled its width — became stable rather than responsive,
and needed no change itself. The empty **Unsorted** column reads as empty the same way an empty lane does: it
prints its own `p.kcol-empty` line after its cards and `.kcard + .kcol-empty` hides that line whenever a card
precedes it, which is why the line was never written conditionally here. `file()` inserts a dropped card *before*
the note, so the rule keeps working on a drop, and `count()` rewrites the head to `0`.

Chosen over the two other shapes this could have taken: a fixed track count in CSS
(`repeat(4, minmax(0, 1fr))`, which would leave a quarter of the row empty and mis-size any project that already
has more lanes than three) and fixed-width columns with a horizontally scrolling row (the `.todo-board`
pattern — 320px tracks, which measured stable but leaves a wide board mostly empty and scrolls on a 1280px
laptop). Always drawing the fourth column costs nothing on a wide screen, matches the general board the user was
comparing against, and keeps `data-lane="0"` live on the page so a card can be **dragged back into** Unsorted,
which the old markup only allowed while it already held something.

**Untouched on purpose:** the status board (already five fixed tracks), the Stage 34 `+ Add task` lines —
Unsorted still gets none, since a card is never written *for* Unsorted — the lane routes and `TaskService` rules
from Stage 35, and every drag-and-drop rule in `TaskService`. Column *height* still follows the cards: a column
with eight cards is taller than one with one, and `align-items: start` leaves the short ones short. That is the
board's length, not the size of a card; the checklist inside a card was already capped at `max-height: 240px`
for the same reason.

**230 tests / 21 classes / 0 failures.** Two existing pins moved onto the new shape — `a project board is cut by
its lanes and never by status` now asserts the empty Unsorted column **is** there, and `a card nobody has filed
is shown rather than hidden` lost the sentence saying the column goes away — and one was added,
`the board prints the same columns whatever the cards are`: it renders the same project board before and after
its one card is filed into a lane and asks for the same head count, the same number of add lines, an Unsorted
whose `kcol-count` is `0` inside 240 characters of its "Nothing here." line. Both are matched as whole elements
or bounded spans rather than bare substrings, the reasoning Stage 35 established.

**Verified on a throwaway, not only in a test.** A fresh `%TEMP%` DB on 8095 with `PTS_SEED_DEMO=false` (so
`EMP0000` has no `must_change_password` gate), a hand-created project and three cards — two filed into lanes, one
left unfiled. The served page printed `data-lane` 1/2/3/**0** with three add lines and four heads. The page was
then saved, its asset URLs rewritten from `/static/…` to a local copy of the built `static/`, and opened in the
browser: calling the shipped `file()` to move the last Unsorted card left all four columns at 279px and every
card at 255px, with Unsorted still in the DOM counting `0`; deleting that column by hand — what the old code did
— is what moved the others, from 389/365 to 523/499 at 1592px. `getComputedStyle` confirmed the note flips:
Unsorted's "Nothing here." hidden while it held the card, Production's hidden once it had received it.

**Deploy:** `stop.bat` needed `/F` again (PID 14824), `installDist` on a free 8080, `run.bat`, `GET /` → 200 on
**PID 1212**. The packaged `TaskBoardKt.laneKanban` was disassembled with `javap -c`: two `data-lane` writes and
**no** `isEmpty`/`isNotEmpty` call in the method, which is the constant-pool proof that the column is printed
unconditionally. Two assets changed: `app.css 999b68da… → c9480a28…` (the rewritten `.kanban-lanes` comment) and
`kanban.js a1a54ac2… → 3d474640…` (the header paragraph and `file()`); `app.js 11a24e8c…` and
`task-modal.js 0570f93e…` are untouched. All four were re-hashed from the served bytes at
`/static/css/…` and `/static/js/…` and match source, and the served `kanban.js` contains no `removeChild`.
A stale tab still runs the old script, so its Unsorted column still vanishes on the last drag and its cards still
change width — **Ctrl+F5**.

<a id="stage-37"></a>

## Stage 37 — the board's geometry stopped being a function of its counts (2026-10-15, live, no migration)

The ask was three things in one sentence: *"keep the cards size as the picture showing no need unsorted bucket
there also keep the kanban card size unchangeable vertically"*. Nothing was attached to it, and the picture it
points at is the one the user attached to Stage 34 — `OneDrive\Pictures\Screenshots\new_project.png`, read off
disk and measured rather than remembered. A pixel scan of it (`System.Drawing`, a row walked at the columns'
mid-height) says: the capture is **1660px** wide, and its three lane columns run 17–304, 321–608 and 625–912 —
**288px each with 16px between them, and the row stops there**, leaving 700-odd pixels of the page unused at the
right. So the board the user is holding up as the target is a run of *fixed-width, left-packed* columns, not a
row stretched to fill. (Its empty column is 163–276, i.e. 114px tall; `.kcol`'s `min-height: 190px` was left
alone — the ask is about cards.)

The card heights were then measured on a real board, because "unchangeable vertically" needs a number: on the
served page of a throwaway project, a card with no plan is **112px**, one with one step **169px**, one step more
**+23px** each, and a twenty-five-step card **361px** — the last held down only by the checklist's own
`max-height: 240px`. Same card, same column, three times the height of its neighbour, and the board taller every
time somebody wrote a step.

**Three changes, one per count the geometry was reading.** `.kanban-lanes` went from
`repeat(auto-fit, minmax(210px, 1fr))` to `repeat(auto-fill, minmax(min(288px, 100%), 288px))` with `gap: 16px`:
fixed 288px tracks, packed from the left, exactly as measured, with `min(288px, 100%)` as the floor so that a
board narrower than one column shrinks that column instead of spilling out of the page. `.kanban .kcard` became
`height: 168px` — the least a card can be and still show its plan header with a step under it — laid out as a
flex column whose `.kbody` grows and whose `.task-checklist` grows inside *that*, with `max-height: none` so the
band is whatever the card has left (the 240px stays, for the popup, where nothing around the list fixes a height).
And `laneKanban` prints its **Unsorted** column only while the board actually holds a card with no lane, with
`kanban.js`'s `file()` taking the column out of the DOM again when its last card leaves — which is the ask, and
is safe now only because of the first change.

**This reverses Stage 36's mechanism, and keeps its goal.** Yesterday the column *set* was made constant so the
widths could not move; today the *widths* are constant, so the column set is free to follow the data again, which
is what the user asked for. The alternative Stage 36 rejected — fixed-width tracks, a row that does not fill — is
the one now taken, because the user supplied the picture that draws it that way and named it as the target; the
other half of that rejection still holds, so the tracks wrap onto a second row (`auto-fill` plus the
`align-items: start` `.kanban` already had) rather than scrolling sideways. A project with more lanes than the
window fits is therefore the one case where the board grows downward in steps of a row.

**What the fixed tile costs, stated plainly.** A card's plan band is 51px at 168px, about a header and one and a
half rows: a long plan is worked in the popup, not by scrolling inside a card. A card with no plan leaves ~60px
of blank tile under its meta line, which is the price of one size for every card. And a very long title is not
truncated — the title keeps its `flex: 0 0 auto`, so a five-line title squeezes the plan band to its header
before anything is clipped, and the card stays 168px. The general/assigned status board takes the fixed height
too, since `.kanban .kcard` is one rule for both boards; its five `1fr` tracks are untouched, because five
columns never changed count and so never resized a card.

**230 tests / 21 classes / 0 failures** — the same totals as Stage 36, with one pin flipped and one rewritten:
`a project board is cut by its lanes and never by status` now asserts `data-lane="0"` is **absent** from a board
whose one card is filed, and `the board prints the same columns whatever the cards are` became
`the board prints Unsorted only while it has a card in it` — four heads and a counting Unsorted before the card
is filed, then exactly the three lanes, no `data-lane="0"`, no *Unsorted* word, and the same number of add lines
either side. `a card nobody has filed is shown rather than hidden` still passes unchanged, which is the point:
taking the bucket off the empty board hides no card. No CSS claim is tested in Kotlin; it is measured in the
browser, below.

**Verified on a throwaway, then in the shipped stylesheet.** Fresh `%TEMP%` DB on 8095, `PTS_SEED_DEMO=false`,
project `S-37` with five cards carrying 0/5/6/25/1 steps. With every card filed the page printed `data-lane`
1/2/3 and no `0`; `POST /app/tasks/bucket` with `bucket=0` for one card brought the column back carrying it. Then
the saved page, opened at `file://` against a copy of the built `static/`, measured at four board widths:
columns **288/288/288/288** at 1592px, 1102px and 700px and **279** at 279px (the floor, not an overflow), with
every card **264×168** at all four. `scrollHeight == clientHeight == 168` on every card, the 25-step card's list
scrolling at 51px of band over 604px of content; a `.kmove` button cloned onto a card ended at 158px, inside the
tile. Calling the shipped `file()` to move the last Unsorted card back onto a lane removed the column and left
the other three at 288px each.

**Deploy:** `stop.bat` again needed `/F` (PIDs 1212 and 7456), `installDist` onto a free 8080, `run.bat`,
`GET /` → 200 on **PID 26372**. `javap -c` over the packaged `TaskBoardKt` shows the `List.isEmpty` guard in
`laneKanban` and one `Nothing here.` write in the lane loop and none in the Unsorted branch. Three assets moved
where two were expected to: `app.css c9480a28… → c21a5735…` and `kanban.js 3d474640… → d1a4bd58…`, with
`app.js 11a24e8c…` and `task-modal.js 0570f93e…` untouched; all four re-hashed from the served bytes at
`/static/css/…` and `/static/js/…` and match source. A tab opened before this deploy still runs the old script
and the old grid, so its cards still change width and its Unsorted column still stands empty — **Ctrl+F5**.

**Superseded the next day, on one of its three changes (Stage 38).** The `height: 168px` card tile this section
built is **gone**, along with the flex band inside it; a card's height is its content again. The measured picture
that set the **288px tracks** and the **conditional Unsorted column** both still stand, so this section's opening
— "the board's geometry stopped being a function of its counts" — remains true on the width axis, which is where
the complaint started. Read the costs of the tile as history, not as the current trade.

<a id="stage-38"></a>

## Stage 38 — the cards were given their own height back (2026-10-15, live, no migration)

*"make all kanbans static vertically"* was read as a column that does not change height: a fixed, internally
scrolling card run per column. That was built end to end — `div.kcol-cards` between each column's head and its
foot (`kcolCards` in `TaskBoard.kt`, the same wrapper around `inBucket.forEach { otherCard(it) }` in
`AssignedTasks.kt`), `.kcol-cards { height: calc(3 * 168px + 2 * 10px); overflow-y: auto; scrollbar-width: thin;
scrollbar-gutter: stable; }`, `align-items: stretch` on `.kanban-lanes` so a lane's foot line still sat at the
row's bottom edge, `cardsOf()` in `kanban.js` so a drop still found the run rather than the column, and two new
tests in `TaskBoardRenderTest.kt`. It measured green at a 1592px board — columns 288px, every run exactly three
tiles tall, cards **254px** wide instead of 264px because the reserved gutter takes 10px out of every column —
and none of it was ever deployed. The clarification that followed said **"i meant dynamic"**, and the option
chosen from the three readings offered was *cards size to their own content*: a card with no plan is short, a card
with a long one is tall, columns follow their cards, and the page scrolls the way it used to.

So both halves came out: Stage 38's run **and** Stage 37's fixed tile. The grep that proves the reversal is
total is `grep -rn "kcol-cards\|kcolCards\|cardsOf\|168px" src/`, which now answers with the sidebar logo's width
and two sentences of comment that name the history. `git` cannot show it, because the whole tree is dirty from
thirty-odd stages.

**What was kept, because it was never the complaint.** `.kanban-lanes` is still
`repeat(auto-fill, minmax(min(288px, 100%), 288px))` with `gap: 16px` — the width the picture was measured at, and
the axis the Stage 36 complaint was really on ("a fourth column took 134px off each of the other three"). The
`align-items: stretch` the scroll run needed is gone and `.kanban`'s `start` governs again, so a short column
stays short instead of matching the busiest one. **Unsorted** is still printed only while the board holds a card
that has no lane, with `file()` taking the column out of the DOM when its last card leaves. `.task-checklist`
keeps its own `max-height: 240px`, which is what turns a twenty-step plan into a long card rather than an
unbounded one, and `.kcol` keeps `min-height: 190px`, which is what makes an empty lane read as a lane rather
than a strip.

**Where the geometry comes from now: width is written, height is content.** Measured on the saved page at
`file://` against the built `static/`, with the board forced to 1592px: columns **288px** wide, cards **264px**
wide, one card **95px** tall with no plan and **314px** with an eight-step plan, and the four columns **1231 /
280 / 190 / 190**px tall (the last two are the floor, not a rule). Fourteen synthetic step rows later the list
holds at 240px (`scrollHeight 534`, `clientHeight 239`) and the card becomes **344px** — the cap working. The
shipped `window.file(card, col)` still moves a card in place: the destination count went 0 → 1, "Nothing here."
hid, the emptied Unsorted column left the DOM, and no width on the board changed. The general status board took
the same shape back: cards **285×95** and **285×314** inside its five untouched 309px `1fr` tracks.

**230 tests / 21 classes / 0 failures.** The two Stage 38 tests left with the code they tested, so the suite is
back on Stage 37's totals and no pin describes a shape the board no longer has; `the board prints Unsorted only
while it has a card in it` still holds, and the board tests assert markup rather than geometry. No CSS claim is
tested in Kotlin — it is measured in the browser, above.

**Deploy:** `stop.bat` needed `/F` again (PID 26372), `installDist` onto a free 8080, `run.bat`, `GET /` → 200 on
**PID 22816**. All four served assets re-hashed from the served bytes (CR stripped) and match source:
`app.css 500e6981…`, `kanban.js 56a7fccf…`, `app.js 29db0725…`, `task-modal.js d536fbc3…`. A tab opened before
this deploy still has the 168px tile and the scrolling column in its cached stylesheet, so its cards will look
uniformly tall and clipped — **Ctrl+F5**.

**If this is restated.** "Make the board's height fixed again" means the run this stage built and removed —
a column three tiles tall with `scrollbar-gutter: stable` inside it — not another height on `.kcard`; say which
of the two is being asked for before either is written, and ask before building when the sentence admits both
readings, as this one did.

<a id="stage-39"></a>

## Stage 39 — the backlog's bucket comes from its own heading (2026-10-15, live, no migration)

*"i need add bucket button as shown the picture"* arrived with no picture attached, and the board it means is not
obvious: the word **bucket** names two different things in this app — a column of the **Other tasks** backlog
(`buckets` table, Stage 31-era) and a **lane** of one project's board (`project_buckets`, Stage 30, schema v7).
Stage 34 had already declined a dashed `+ Add bucket` column and a lane `…` menu *on the project lane board*, so
guessing a second time against a recorded decline was not available. Three questions were asked in one round and
answered: the control belongs on the **Assigned tasks / Other tasks backlog**, it is a **button in the board's
heading**, and it is **management only**. None of that reopens Stage 34's decline — the reader who asked is the one
who declined it, on a different board, and they asked for a different shape (a heading button, not a column).

**What replaced what.** The dashed `div("kcol addbucket")` column at the end of the backlog row and its CSS are
gone; `grep -rn "addbucket" src/` now answers with the one line of `AssignedTasksRenderTest.kt` that asserts the
word appears nowhere in rendered markup. In the backlog's own heading
(`div("other-head")`, which already carried *Other tasks* and its one-line scope note) management gets
`bucketOpener()`: a `GET /app/assigned-tasks?newbucket=1` form whose button reads `+ Add bucket`, pushed to the
heading's right end by `.bucket-opener { margin-left: auto }` — the same position `+ New task` holds opposite the
page title, which is as close to "as shown the picture" as a page nobody could see allows.

**Opening costs no new JavaScript, on purpose.** `bucketOpener()` is a `form[GET]` carrying
`data-live-target="#bucketModal"`, so `app.js` fetches the query string, takes `#bucketModal` out of the response
— where the server has rendered it with `style="display:block"` because `BucketForm(open = canManage &&
query["newbucket"] == "1")` — and swaps it in place. That is `newTaskButton`'s convention exactly, and it keeps the
scripts-off path honest: with no JS the same form is a plain navigation to a page whose popup is open server-side.
The popup itself is the usual shape (`div.modal#bucketModal > div.modal-content`, `×`, `h3` *Add a bucket*, one-line
`p.modal-scope` saying what a bucket is, the name field, `Add bucket` + ghost `Cancel`), and its form posts to
`/app/other-buckets` naming `data-live-target="#otherBoard"` with `data-live-extra="#bucketModal"`. Block plus
companion from one response is why both outcomes land without a reload: a accepted name brings in the new column
**and** a closed popup in the same swap; a refused name brings back the popup open, over the closed one.

**A refusal now says why, at the field.** `/app/other-buckets` used to answer a duplicate with a silent
`respondRedirect`, so a management account that typed a name already on the board saw the board again and nothing
else. It still redirects on success, and on failure it renders the page with `BucketForm(typed = name, error = …)`
so the popup comes back holding the word and a `p.field-err` under it — "Give the bucket a name." for blank input,
"There is already a bucket called that." for a name `TaskService.addBucket` refused. Two reasons because the service
knows two (`name.isBlank()` and `BucketRepository.idByName().containsKey(name)`); the route does not re-check them,
it just reports what the service returned.

**`bucket-modal.js` only knows how to get out.** `×`, `Cancel` and the backdrop are `[data-bucket-modal-close]`,
Escape closes while the popup is open, and an opener that lands with `?newbucket=1` in the URL strips that one
parameter through `history.replaceState` so refreshing does not resurrect the popup. A `MutationObserver`
(`noticeSwap`) focuses `#bucketName` when `app.js` swaps the popup open, which is the case the plain `onclick`
scripts never have to handle. The marker is named apart from `[data-modal-close]` deliberately: `task-modal.js` and
`project-modal.js` delegate on that name against `document`, so a shared marker would have made the backlog's
bucket `×` close somebody else's popup.

**Management-only in the markup and again in the route.** `assignedTasksContent` prints `bucketOpener()` and
`bucketModal()` only on `canManage`; `GET …?newbucket=1` builds `BucketForm(open = …)` only on `canManage`; and
`POST /app/other-buckets` re-checks `Authorization.canManage` and redirects before reading a name. The route is the
right, the markup is its presentation.

**236 tests / 22 classes / 0 failures**, with `AssignedTasksRenderTest.kt` as the new class — six tests over the
real `createHTML()` markup: the opener sits in the heading, the dashed column is off the board, the popup names both
the board it adds to and itself, it arrives closed until the opener asks, a refused name comes back typed with its
reason under the field (asserted by position: `id="bucketName"` before `field-err`), and a non-management render
contains no `newbucket`, no `bucketModal` and nothing that posts to `/app/other-buckets`. Two traps worth keeping:
`DemoData.buckets.forEach { bucket -> … }` would have shadowed the new `bucket: BucketForm` parameter, so the loop
variable is `bucketName`; and `p("modal-scope") { +"a" + "b" }` does not compile the way it looks — the unary plus
takes the first string and the `+` then adds it to `Unit`, so the concatenation is parenthesised inside `+( … )`.
Attribute assertions use an order-independent slice (`elementWithId`) because `createHTML()` keeps one tag's
attributes on one line but does not promise their order.

**Verified on a throwaway** (8095, `%TEMP%\pts39\gate39.db`, `PTS_SEED_DEMO=false`, throwaway-only password and
session secret read from files so neither appears in chat or in a command line near 8080): the plain backlog is
`#otherBoard` with the five status columns elsewhere and the popup present but closed (`display:block` count 0);
`?newbucket=1` served `<div class="modal" id="bucketModal" style="display:block">` and exactly one open popup;
`POST name="Site works"` → 302 and the column list went 5 → 6 with `Site works` in it; the same name again → **200**,
`field-err` "There is already a bucket called that.", `value="Site works"` on `#bucketName`, popup open, and the
count still 6; `name="   "` → 200 and "Give the bucket a name."; a second distinct name → 302 and 7 columns. A
Viewer jar got a page with 0 `bucket-opener`, 0 `bucketModal`, 0 `newbucket` and 0 occurrences of the label, their
`?newbucket=1` showed nothing, and their `POST name="Viewer Try"` came back 302 with **no** such column on
management's board. `%TEMP%\pts39` was deleted and the JVM stopped before the deploy.

**Deploy:** `stop.bat` again needed `/F` (PID 22816), `installDist` onto the free 8080, `run.bat`, `GET /` → 200 on
**PID 12756**. Five served assets re-hashed from the served bytes against source and all match:
`app.css c4969ce2…`, `app.js 11a24e8c…`, `kanban.js 52bac86b…`, `task-modal.js 0570f93e…`, and the new
`bucket-modal.js 07f02de3…` (served 200, 3684 bytes). Correction to the Stage 38 note: these files are **LF-only**
(`tr -dc '\r' | wc -c` = 0 on all three), so the CR-stripped hash *is* the raw hash — `11a24e8c…` is app.js, and the
`29db0725…` quoted in Stage 38 came from a different pipeline, not from the same bytes. A tab opened before this
deploy has the old stylesheet and no `bucket-modal.js` at all, so its backlog still shows the dashed column and its
heading button will navigate rather than pop — **Ctrl+F5**.

**If this is restated.** "Add bucket" without a board named is the two-meanings case again: ask which of the
backlog's columns or a project's lanes, before drawing either — the lane board's controls were declined once
(Stage 34) and removed once (Stage 35), and a heading button on the backlog is not a licence to put one back on a
project page.

<a id="stage-40"></a>

## Stage 40 — the backlog is a board, and its cards carry a status (2026-10-16, live, no migration)

**The ask.** `other-tasks.png`, the real screenshot this time: *"in other tasks there should be showing kanban
cards on status the user can change task's status down side should show other tasks. Do it just like shown on the
picture."* The picture's Other tasks block is a row of bucket columns — each head with a coloured dot, its name and
a count, a `⋮` at the head's right end, cards underneath showing their own status line, a `+ Add task` line at the
column's foot, a dashed `+ Add bucket` box as the last column of the **row**, and *Show all buckets* at the
heading's right. Three questions settled what the picture cannot say, and all three answers are load-bearing below:
status changes by **clicking the card and editing it in its popup** (not a per-card select), the `⋮` holds
**rename + delete with delete asking which column takes the cards**, and *Show all buckets* **toggles empty
columns** on the board. No schema change: `buckets` and `tasks.bucket_id` are V1's (Stage 7's JSON→SQLite init), V7 is
still the head migration, and nothing here needed a column or a table.
*(**Superseded by Stage 97** in its third answer: the toggle, the `allbuckets` flag and the wide view it switched on are
gone, and the Stage 93 Bucket picker is the board's only view control — an empty column is revealed by naming it, and a
newly created one lands on its own `?show=` filter. Read the two below as what was in force then.)*

**The board draws what it has.** `assignedTasksContent` builds `columns: List<Pair<Bucket, List<TaskItem>>>` from
`TaskService.assignedFor` and the bucket rows, then prints `if (showAllBuckets) columns else columns.filter { it.second.isNotEmpty() }`
— the default view is the columns that hold work, because a row of nine names where five hold nothing is a list of
intentions rather than of work. Hiding is not deleting: the toggle is printed whenever there is something to hide
(`showAllBuckets || shown.size < columns.size`), so a board already showing everything prints no dead link, and a
column that has just been created is shown with the board on the view that can see it (`bucketsBack(showAll,
forceAll = true)` on the create), because an empty column that vanishes the moment it appears reads as a control
that does not work.

**Stage 39's heading button became the picture's dashed box.** `addBucketBox` is now a `kcol addbucket` form **inside
`#otherBoard`**, last in the row, `data-live-target="#bucketModal"` + `data-live-extra="#otherHead"` — the shape of
a column, holding no cards, dragging nowhere, and inside the swapped block so a bucket that lands arrives beside it
in one redraw rather than in a second one. This is the one arrangement the earlier decline covered: the dashed
*Add bucket* **column** was refused on a project's lane board (Stage 34/35) and is asked for by name here, on the
backlog. The heading keeps only *Show all / fewer buckets*.

**One `⋮` per head, one popup for the board.** `bucketMenuForm(row.id, showAllBuckets)` is a GET form onto this page
carrying `bucket=<id>` (and `allbuckets=1` when the reader is on the wide view); `app.js` fetches it and swaps
`#bucketModal`, so the popup that opens is the one the server drew *for that column* over the board the reader is
standing on. `GET /app/assigned-tasks` reads both keys (`?newbucket=1` for the box, `?bucket=<id>` for a column)
and honours either for management only, and `BucketForm` carries `bucketId`/`moveId` so the reopened popup knows
which column it belongs to and which destination to leave selected. `#bucketModal` stays singular on purpose — the
page can only swap one block per id, and one form per column is what makes the mechanism scale to however many
columns exist.

**Rename is cheap and delete is not.** `bucketManageBody` prints the two as **separate forms**, not one form with two
buttons, because `app.js` reads the block list off the form being sent and posts it to that form's own `action` — two
buttons would be one button aimed at the wrong route. Rename posts `/app/other-buckets/{id}/rename`; the id is in the
path and the cards point at the column by that id, so a new name is a new heading over the same work and nothing else
moves. Delete posts `/app/other-buckets/{id}/delete` with `moveTo`, and the field it shows is not "are you sure" but
*which column takes them* — `Tasks.bucketId` answers `ON DELETE SET NULL`, so dropping a column that still holds cards
would take them off **every** column rather than move them, and unlike a project's lane board this one has no Unsorted
column to catch them. The count is in the label ("Deleting this column hands its 4 cards to another.") because the
reader is being asked to account for that much work. `TaskService.deleteBucket` is the rule and the markup merely
matches it: `DENIED` / `NO_BUCKET` come back as the plain board (no field of theirs to print under), `LAST_BUCKET` is
refused when the column holds cards and no other column exists (and the popup prints no delete form at all for it),
`NO_DEST` when the answer is missing, points at the column itself, or names a column that is not on the board, and an
empty column deletes with nothing asked. `moveBucketCards` is issued **before** `BucketRepository.delete` and both are
keyed off the same id, so a column never goes while cards still point at it; they are two separate transactions
(each repository function opens its own), which is why the ordering is the guarantee rather than a rollback.

**A backlog card opens its editor by being clicked.** `openBacklogCard` renders the card's own `kcard-open` GET form
(`?edit=<id>`, plus the reader's `allbuckets`) naming `#taskModal`, which is the same path the status board's cards
take — the popup is `taskFormModal(state ?: TaskForm(canEditAll = canManage), BACK_ASSIGNED, …)`, and the refuse /
`?edit=` plumbing is Stage 24's, not new. The card prints its status line from the stored status, and the owner keeps
the board's `kmove-btn` arrows so status is changeable from either place. *(**Stage 103** took those arrows off this
board too, so the drag and management's popup are the two places a status changes here.)*

**The defect that ask exposed, and its fix.** Clicking a backlog card used to open an editor that could not be saved:
`TaskService.resolve` demanded an assignee for every task, while `createOther` always inserts
`assigneeEmpNo = null` — a backlog card belongs to a *column*, not to a person, so the popup answered
"Assign the task to someone on the team." on a card the board never assigned. The rule now reads the row, not the
form: `resolve(draft, storedLane, requireAssignee)` and `updateAssigned` passes
`requireAssignee = TaskRepository.kindOf(id) != KIND_OTHER`, with the new `TaskRepository.kindOf(id)` answering
`ASSIGNED` / `OTHER` / null because `TaskRepository.map` builds a `TaskItem` that has no `kind` field. Reading it from
the stored row is what closes the hole in both directions: a hand-built POST cannot strip an assigned card's assignee
by leaving the field out, and a backlog card is not forced to invent an owner to be renamed.

**The view is a view, not a right, and it rides with every write.** `?allbuckets` is read once per request by
`showAllOf(form)` and threaded into `assignedTasksContent(…, showAllBuckets)`, `taskFormModal(…, showAllBuckets)` and
`taskRedirect(back, projectId, edit, showAllBuckets)`, which appends `allbuckets=1` **only** when the destination is
`/app/assigned-tasks` — a backlog view on a project page would be a parameter nothing there reads. Every form the
board and its popup print carries the hidden field when the reader is on the wide view: the toggle itself, each
`⋮`, the dashed box, a column's `+ Add task` line, the create popup, the rename and delete forms, the task popup's
save form, and a card's click-to-edit form. Without that last pair the write would answer on the narrow board and
hide the column the reader had opened; six `allbuckets` inputs inside `board(wide)` and none inside `board(narrow)`
is the assertion that keeps them honest. `bucketsBack` / `respondBuckets` are the two helpers that make each route
spell the same redirect.
*(**Superseded by Stage 97**: `showAllOf`, the `showAllBuckets` parameters and `taskRedirect`'s fourth argument no longer
exist, and `bucketsBack(showBucketId)` now builds `/app/other-tasks?show=<id>` instead of a wide-view URL — the only view
state a write carries is the bucket filter inside its `back` token.)*

**Scripts-off stays honest.** Every opener above is a GET form and every writer a POST form to a real route, so with
JS disabled the same clicks load the same page with the same popup open server-side and the same writes land; the
swap is an optimisation on top, not the mechanism.

**257 tests / 22 classes / 0 failures** (from 236). `AssignedTasksRenderTest` went from six tests to eighteen over
the real `createHTML()` markup: columns only when they hold work, the box last inside `#otherBoard`, one `⋮` per head
naming its own `bucket` id, rename by path id, delete naming `moveTo` with the count in its label, a refused rename
coming back **typed** rather than as the stored name, a refused delete coming back with its destination still
selected, the last loaded column printing no delete form, an empty column deleting with no question asked, the toggle
swapping `#otherBoard` and `#otherHead` together, no toggle when everything is already shown, a newly named bucket
shown on the board that answers, a card's `kcard-open` form carrying `edit`, the owner's arrows, the foot add line
targeting the board, the popup arriving closed until an opener asks, and the view travelling with every write. Two
`TaskServiceTest` cases cover the assignee rule from both sides
(`a backlog card saves with nobody assigned to it`, `an assigned card still cannot be saved with the assignee blank`).
Three traps worth keeping: `createHTML()` indents between tags but keeps one tag's attributes on one line, so an
attribute assertion slices the element rather than matching across the page; the status board above the backlog emits
five identical `kcol-head` nodes, so counts come from the `#otherBoard` → `#bucketModal` slice; and a Viewer's page
legitimately contains the string `bucketModal` in the toggle's `data-live-extra="#otherHead,#bucketModal"` — the
assertion therefore tests for **no node** (`elementWithId(html, "bucketModal")` is null), not for a missing substring,
or a real leak hides behind a companion list.

**Verified on a throwaway** (8095, `%TEMP%\pts40`, `PTS_SEED_DEMO=false`, throwaway-only password and session secret
in files so neither appears in a command line or in chat): the wide board's opener put `allbuckets` on the card's
edit form, `POST /app/tasks/update` with the popup's fields and *Assign to* blank came back **302** to
`http://127.0.0.1:8095/app/assigned-tasks?allbuckets=1`, the card's status line printed the new status, and the wide
board was still wide; the narrow path refused the same save's title with `field-err` "Give the task a title." on a
board that stayed narrow. `%TEMP%\pts40` (throwaway DB, gate scripts, cookies, captured pages) was deleted afterwards.

**Deploy:** the dist jar was rebuilt with the Stage 40 code and the packaged classes proved by `javap` rather than by
paths — `TaskRepository.kindOf` present in the jar's bytecode and the three-parameter `resolve` in `TaskService`, with
the old two-parameter call gone. The live 8080 instance had already stopped (no 80xx listener), so no `stop.bat` and no
`installDist` over a running JVM were needed; `run.bat` started it on **PID 23184**, `GET /` → 200,
`GET /app/assigned-tasks` → 302 to `/` as an unauthenticated reader, and `GET /login` with a POST-only verb → 405.
Served CSS and JS were hashed from the **served bytes** against the files on disk and match (`css/app.css ffad907543`,
`js/bucket-modal.js 4f8f8fc014`, `js/kanban.js 35c44db8fe`, `js/app.js 95cecf568a`) — there is no hash or cache-buster
in the markup (`<link href="/static/css/app.css">` in `DashboardCommon.kt`), so `processResources` is the deploy and a
tab opened before it still holds the Stage 39 sheet: **Ctrl+F5**.

**If this is restated.** "Kanban cards on status" reads two ways — cards *grouped by* status (the block above the
backlog already is that) or cards that *each show* a status the reader can change. The picture settles it here, but
ask before drawing a second status board. And a backlog control is not a licence for a lane control: the dashed box,
the `⋮` and the coloured dot exist on this board because the picture of **this** board shows them, and Stage 35's
removal from project pages stands.

<a id="stage-41"></a>

## Stage 41 — a card prints a number, and the plan is in the popup (2026-10-16, live, no migration)

**The ask.** *"cards on assigned tasks no need to show checklists with checkbox user by clicking on the card shows
checklists and checkbox"*. Two questions settled the two readings the sentence allows, and both answers are
load-bearing below. **Where the plan goes:** *"on cards do not have checklists and checkboxes but if the card is
clicked shows info about checkboxes and checklists"* — every card on every board loses its inline checklist, for its
owner included, and the click-opened popup carries the steps and the boxes. **What a box replaces:** *"Tick box in
place of the mark"* — Stage 23's read-only ✓/· spelling in the popup becomes a real `<input type="checkbox">` wired
to `/app/tasks/checklist/toggle`: box on the left, the step's words beside it, the × to its right, the `New step…`
line under the list, and a tick that swaps the popup's plan, the card behind it and the project's bar in one response
while the popup stays open. No schema change: `task_checklist_items` is V5's and V7 is still the head migration.
**The same day, one more ask:** *"take off Add step button it just adds by press enter"* — the add line lost its
button and is now one text field in a form of its own. That is not a JS shortcut: HTML's implicit-submission rule
submits a form with **no submit button and exactly one field that blocks implicit submission**, and this form's other
inputs are all `type="hidden"`, which do not block. So Enter posts it, the `submit` event fires the same way a button
press did, and `app.js`'s live-target branch runs on it — the step lands in the swapped block with nothing navigating.
`sendToBlock` reads the target off the *form*, never off a submitter, so removing the button removed no information.
The note box under it **keeps** its button, because Enter inside a `textarea` is a new line, not a send.
**And one more ask the same day:** *"on the tab when you click on a card there should be a small progress bar that
depends on the checklists. Meaning if there are two checklists and one of the is ticked the progress bar should be
displaying 50%"* — the bar is drawn inside the popup's own plan section, between the `n of m done · p%` heading and
the rows it counts, and it is filled to that same `p`. Two deliberate choices: it is the **one** progress bar the
app has (`div.progress > div.bar`, the pair a Projects board row and a project page's Progress cell already use, at
110px × 8px), so `.kprog` adds only the block it sits on and the popup does not start a second style of bar; and it
prints only where a percentage prints, because `TaskItem.progressPercent` is null on a card with no steps — an
unplanned card gets no bar, exactly as it gets no chip and no `· p%`, since unmeasured is not zero (Stage 26).
The bar was put inside `#task-steps-<id>` rather than beside the popup's fields on purpose: that is the region every
step form already names as its `data-live-target`, so the click that ticks a box returns the box, the heading's
number and the fill in one response, and a bar outside it would sit holding the width from before the tick. No new
right, no new region, no new route and no migration — `modalSteps` draws one more node from the figure it was
already given. `the popup's bar is filled to the figure the card prints` pins the fill, the single bar and its place
above the first `chk-row`, and `two steps with one ticked fill the popup's bar to half, inside the block the tick
swaps` pins the user's own example — `width:0%` before, `width:50%` after, with the tick's own `data-live-target`
named in the same block.

**The card was never the right place for a box; the number is.** A card keeps exactly one thing about its plan — the
`kpct` chip Stage 26 put on its meta line — because that chip is what a board is for: reading how far work has come
at a glance. Working the plan down is a click into the card, which is where Stage 24 already sends every other write.
So `taskChecklist` is deleted, `taskCard` loses the `back`/`projectId` parameters it only ever needed to build step
forms, and a board now contains no `task-checklist` node, no `chk-row`, no `name="done"`, no
`/app/tasks/checklist/toggle` and no `data-autosubmit` input — for the person the card belongs to included. That is
what `a card prints no plan and no boxes, the person it belongs to included` asserts, and the test next to it counts
`draggable="false"`: Stage 19 exempted the checklist block from the card's drag handle, and with no block inside a
card there is nothing left to exempt, so a card is one draggable target again.

**Who sees a box is the service's answer, not the page's.** The popup used to infer it from the role that opened it;
now `TaskForm.viewerEmpNo` is set from the session by the route that loaded the card
(`editFormOf(task, canManage, session.employeeNumber)`) and `mayTick = canEditAll || (task.assignee.isNotBlank() &&
task.assignee == viewerEmpNo)` — which is *exactly* the reader set `TaskService.toggleChecklistItem` accepts and
`TaskService.editableBy` hands a card to. That identity is what made the popup's read-only branch dead code: there is
no reader who is shown a step list they cannot tick, so `modalSteps` prints one row shape and nothing else, and the
`.step-row` / `.step-mark` CSS went with it rather than staying as an untestable style. *(**Stage 86** broke that
identity on purpose: `openableBy` now admits readers who may not tick, `TaskForm.viewOnly`/`mayTick` separate the
right to read from the right to work, and `modalSteps` prints the stopped row shape ([`stepRowLocked`]) beside the
live one — there *is* a reader shown a plan they cannot tick now.)* `viewerEmpNo` is never read
from a form field, so a hand-built POST cannot hand itself somebody else's boxes; and a **refused save** re-renders
through `taskFormOf(…, viewerEmpNo = session.employeeNumber)` as well, because forgetting who is looking would turn
the assignee's own steps into a stranger's read-only list on the very page that just rejected their edit.

**Three blocks, one post.** `cardBodyRegion(task.id)` = `task-body-<id>` is the card's meta line — the chip and
nothing but — and it is named apart from `.kcard` because that node is `kanban.js`'s drag source, so swapping it out
from under a drag would leave the column undraggable. A step form therefore targets `#task-steps-<id>` (the popup's
plan) with `data-live-extra="#project-progress,#task-body-<id>"`, so one click repaints the box, the card's number
and the project's average and moves nothing else; the same string is what `PROJECT_COMPANIONS` plus the card region
compose to on either board. A note form names only its own `#task-notes-<id>`: a note changes no measurement. The
percentage a tick moves is inside the block the tick swaps — `a card prints the percentage its own steps add up to`
slices the page from `id="task-body-…"` and asserts `kpct` is in that slice and ahead of the move arrows, so the
number cannot drift out of the region that keeps it current.

**Stage 40's view still rides every write, and this stage had to keep it riding.** The popup's forms are backlog
writes now like everything else, so `checklistFields` and `noteFields` both take `showAllBuckets` and emit
`<input name="allbuckets">` when the reader is on the wide board, and the five sub-form routes (`checklist/add`,
`checklist/toggle`, `checklist/remove`, `notes/add`, `notes/remove`) each redirect through
`taskRedirect(…, showAllBuckets = showAllOf(form))`. A management popup on a card with one step and no notes carries
**five** of those inputs — toggle, ×, add-step, add-note and the popup's own save form — which is the count
`a step written from the wide backlog board leaves the board wide` asserts, alongside none at all on the narrow
board. Without it a tick would have quietly re-narrowed the board the reader was standing on. With the add step's
button gone that form is still one of the five — the field carries the flag, not the button.

**255 tests / 22 classes / 0 failures** (from 257: four card-level checklist cases retired, popup cases added in
their place; the popup's bar adds two more). `TaskBoardRenderTest` now pins the popup half of the rule: a step row *is* its toggle form (`a step in
the popup is the tick box itself, and the whole row sends it` — the box and the label sit inside one
`<label class="chk-box">`, so clicking the words ticks the step), a tick posts the card id, the step id, the page it
came from and the popup to come back to, a ticked step comes back `checked` with its words struck through, the boxes
follow the right to tick rather than the page (`management reads a colleague's percentage with no boxes to tick`, and
an unassigned card gives the boxes to management alone), and the add line and the × print only for a reader who may
write the plan. *(**Stage 89** moved half of that line: the add line follows the right to work the card too — the
assignee plans their own card — while the × stays management's alone.)* The add line is pinned as **a field and no
button**, with exactly one writable field in its form —
that is what makes Enter the send rather than a convention somebody happens to follow. `a popup on a card with no plan counts nothing and claims nothing`
grew the bar's half of the rule: an unplanned card prints no `· p%` **and** no `class="progress"`.
`ProjectsPageRenderTest` moved with it: the project page prints a card's `0%` and no plan, and the
`n of m done` line and the step's words are read from the popup rendered for that card's owner.

**Verified on a throwaway** (8095, `%TEMP%\pts41`, `PTS_SEED_DEMO=false`, throwaway-only password and session secret
generated into files so neither appears in a command line or in chat, `POST /app/access-control` used to create the
worker account): management's board printed `id="task-body-1"` inside `class="kcard s-inprogress" data-id="1"` with
`class="kpct">0%`, and **0** occurrences of `task-checklist`, `chk-row`, `name="done"`, `checklist/toggle`,
`>Checklist<`, `data-autosubmit` or `step-mark`; `?edit=1` on the same card returned `id="task-steps-1"`,
`class="chk-count">1 of 2 done · 50%"`, `<input type="checkbox" name="done" id="chk-1" checked="checked"
data-autosubmit="">` inside `<label class="chk-box" for="chk-1">`, 5 forms aimed at `#task-steps-1` and 5 naming
`#project-progress,#task-body-1`. `POST /app/tasks/checklist/toggle` came back **302 to the same page with
`?edit=1`** — the popup stays open — and so did `/checklist/add`. A card reassigned to the worker rendered the same
boxless board and, in their popup, both boxes with 2 toggle forms and **0** add and **0** remove forms. The chip
moved `0% → 50%` across a tick. `%TEMP%\pts41` (throwaway DB, passwords, secret, cookie jars, captured pages) was
deleted afterwards.

**Deploy:** the live JVM was stopped before the rebuild — `taskkill` without `/F` first, force only after it stayed
up, so the shutdown hook got its chance to close the SQLite pool — `installDist` ran against the freed `lib\`, and
`deploy/run.bat` started the new build on 8080. The distribution jar is newer than every source file it was built
from, and the served bytes were hashed against the files on disk: `css/app.css cc35de55b570` and `js/app.js
30ab8caeebca`, both identical, with `.task-checklist` and the `.step-row` / `.step-mark` rules gone from the served
sheet and `.chk-row` / `.chk-box` present. `GET /` → 200. There is still no cache-buster
(`<link href="/static/css/app.css">` in `DashboardCommon.kt`), so a tab opened before this deploy still shows the
card's own checklist: **Ctrl+F5**.

**The bar, verified and deployed the same way.** On a throwaway (8095, temp DB under `%TEMP%`, throwaway-only
password and secret generated into files and consumed through the environment so neither was ever typed or shown,
`PTS_SEED_DEMO=false` — with the seeder *on*, `DemoSeeder.seedIfEnabled()` runs before `AuthService.ensureSuperAdmin()`
and the demo password wins, so `PTS_ADMIN_PASSWORD` is silently ignored on a fresh throwaway): a card with no steps
printed no chip and **0** `.progress` nodes on the board; its popup, after two steps were written through
`/app/tasks/checklist/add`, printed `0 of 2 done · 0%` with exactly one `class="kprog"`, one `class="progress"` and
`style="width:0%"` over two `chk-row`s; `POST /app/tasks/checklist/toggle` on the first step brought the same popup
back as `1 of 2 done · 50%` with `style="width:50%"`, still one bar, still targeting `#task-steps-1` — and the card
out on the board read `class="kpct">50%` with the board itself still holding no bar at all. The temp directory was
deleted afterwards. The live JVM was stopped with `deploy/stop.bat` before `installDist` (polite first, forced only
when it stayed up), restarted through `deploy/run.bat`, and the served bytes hashed against the files on disk
(md5, first 12, the same convention as above): `css/app.css efbb5ad8381d` — the `.kprog` rule is in the served sheet
— and `js/app.js 30ab8caeebca`, unchanged, since the bar needed no script. The packaged jar carries `kprog` in
`TaskBoardKt`. `GET /` → 200, `GET /app/assigned-tasks` → 302 for a session-less reader. A tab that was open before
this deploy has the popup without the bar until it is reloaded: **Ctrl+F5**.

**One open thing this stage did not touch.** `kanban.js` answers a status drag with `location.reload()`, which drops
`?allbuckets` and any open popup — pre-existing, out of Stage 41's scope, and the reason a *visible* reload still
exists on the boards. The checklist writes never navigate.

**If this is restated.** "no need to show checklists" is about the *board*, not about the plan existing — the steps
are still the measurement (Stage 26), so taking the chip off a card would silently take the app's headline figure
off it. And the box now lives in one place: if a tick on the card is asked for again, that ask is for a second way to
write to the same route, and the reasoning above — a board is read at a glance, a plan is worked inside the card — is
why it was taken out. If the *bar* is restated — "show the progress on the card too" — the fill belongs inside
`#task-body-<id>` next to the chip, drawn from the same `progressPercent` and the same `.progress`/`.bar` pair, so a
tick keeps moving both in one response; it does not mean bringing the step rows back onto the board. If the answer
arrives as "the bar doesn't move when I tick", the tab is almost certainly showing the sheet from before this deploy
(Ctrl+F5), because the fill and the box are in the block that is swapped together.

<a id="stage-42"></a>

## Stage 42 — the backlog gets the card's whole popup, and every account may write one (2026-10-16, live, no migration)

**The ask.** *"as this is project tracking system those tasks are supposedly related to user and projects… Plus there
are other tasks meaning tasks that are not from the project. User are able to see those on other tasks from Assigned
tasks. Can be created and edited same as project tasks"*. The last clause was the work, and it was put to the user as
four separable ways a backlog card was still not a project card; **all four** were chosen: the full popup for creating
one, ordinary accounts allowed to create one, the percentage printed on the card, and the card moved between columns.
No schema change — `tasks.bucket_id` is V7's and V7 is still the head migration.

**One card route for one card shape.** `POST /app/other-tasks` is deleted. The backlog's `+ Add task` line is now a
GET onto `/app/assigned-tasks` carrying `new=1` and `column=<id>`, which is what [openingTaskForm] turns into
`backlogFormOf(canManage, viewerEmpNo, columns, into)`, and the popup posts the card to
`/app/assigned-tasks/save` like every other card on the app. The route dispatches on the row-shaped half of that:
`taskId == 0 && draft.columnId != null` is a backlog create, and nothing else is. Two boards, two pickers, one write —
which is the point, because the alternative was a second create path with its own validation that would drift from
the first. The key is `column` rather than `bucket` on purpose: `?bucket=` on this URL already means *which column's
popup is up*, and two meanings on one key would make a column's menu and an aimed create indistinguishable to a
bookmark — the same clash the lane list sidesteps with `into`. The id is checked against `TaskService.bucketRows()`
before it reaches the form, so a hand-typed column opens the ordinary blank popup rather than selecting a column that
is not on this board.

**The popup knows it is a backlog popup from one field.** `TaskForm.backlogColumns` being non-empty is the whole test:
it prints the **Column** picker and prints **no** `taskProject` picker, and its hint says *"A new card lands under
<column>."* An edit does not read that from the URL at all — `isBacklog` comes from `task?.isBacklog`, i.e. the row's
`kind` — so a hand-built `?edit=<id>` at a backlog card and at an assigned card get the pickers their rows entitle
them to. Stage 40's rule about *who* may file a card stays exactly where it was: `requireAssignee = !inBacklog`, read
from the row inside `updateAssigned`, is what lets a backlog card save with *Assign to* blank and refuses that for an
assigned one.

**Writing a card is every account's; the two fields that say whose work it is get pinned.** `createBacklog` takes
`(draft, callerEmpNo, canManage)` and rebuilds the draft **before** it validates: for an account that is not
management, `assigneeEmpNo` becomes the caller and `status` becomes `Not started`, and the project fields are cleared
on every role. That is deliberate rather than a post-write normalise, because the popup has to print the truth it is
about to write: `selfPinned` is derived from exactly that condition, so *Assign to* and **Status** come out
`disabled` carrying the writer's own name and `Not started`, with one sentence above them — *"A card written here is
yours and starts as Not started."* A refusal echoes `values`, and an echo of a field the reader could not have set
would be a form arguing with itself. Management keeps whatever it names. The `+ Add task` lines are therefore drawn
for every reader while the dashed `+ Add bucket` box and the `⋮` stay management's: the split on this board is
**card** against **column**, not read against write.

**A backlog card moves between columns in the popup, because nothing on that board drags.** `kanban.js` binds only
`.kcol[data-status]` and `[data-lane]`, and `div.kanban-other` carries neither — its columns are not status columns
and not lanes. So `TaskForm.maySetColumn = isBacklog && (mayWriteCard || isEdit)`: one field wider than the popup's
general writability, and only one, because that is precisely the right `moveToLane` already gives a person on a
project board — your own card, a different place on the board — with the picker standing in for the drag. The service
keeps them in step: `updateAssigned` takes the posted `column` for an `OTHER` card whatever the role and rebuilds every
other protected field from the stored row, then writes `TaskRepository.setBucket` only when the card really changed
column, so a due date saved on a backlog card leaves the board alone. A column that is missing, or names a column the
board does not have, is a **refusal** rather than an un-filing — there is no *Unsorted* on this board, so
`tasks.bucket_id` going null would take a card off every column at once. The one exception is a board with no columns
at all: an edit is still accepted there (its popup prints no picker to answer with, and a saved date must not be held
hostage to the shape of the board) while a create is refused, because a card with no column is a card on no board.

**The card prints its own number, on the same region the tick swaps.** Stage 41 made `cardBodyRegion(id)` public and
gave the assigned boards their `kpct` chip inside `#task-body-<id>`; this stage gives the backlog card the same line,
so `otherCard`'s `kmeta` now carries `prio · due · 50%` and nothing else, and the step form's
`data-live-extra="#project-progress,#task-body-<id>"` moves that number from either board. The general board prints no
`#project-progress` at all, and `app.js` skips a companion that is not on the page — which is why the same form works
on both without a branch. A card nobody has planned prints no chip, as elsewhere: unmeasured is not zero.

**267 tests / 22 classes / 0 failures** (from 255; +7 in `TaskServiceTest`, +5 in `AssignedTasksRenderTest`). The
service side pins: an ordinary account's `createBacklog` lands as its own and `Not started` however the form is
posted, while management's named person and status survive; a create naming no column, or one not on the board, is
refused and a create on a board with no columns is refused while an edit there still saves; the owner of a backlog
card moves it between columns and changes nothing else; a save posting no column keeps the column the card stands on;
and an assigned card's save is untouched by the backlog's `column` field. The render side pins the `+ Add task` line as
a GET form naming `#taskModal` with `new=1` and that column's `column` id and no `bucket` field and no text input, the
create popup filing in that column with no project picker, the pinned create's two `disabled` pickers and the sentence,
a saved card's popup opening on its own column and offering the others with only the picker writable, a refused create
coming back as the popup with the reason **under the Column field** and the typed title still in the box, and a card
with one of two steps ticked printing `50%` inside its own `#task-body-<id>` while a stepless card prints no chip. The
Viewer case was retuned rather than extended: they are still served no box, no `⋮`, no column popup and nothing that
posts to `/app/other-buckets`, and now additionally the **two** `+ Add task` lines and the card popup — because a page
that hides a write the server would accept is the same bug as a page that offers one it would refuse.

**Verified on a throwaway** (8095, throwaway DB under `%TEMP%`, `PTS_SEED_DEMO=false`, throwaway-only admin and worker
passwords and session secret generated into files and consumed through `--data-urlencode "name@<file>"` with the
Windows path from `cygpath -w`, so no credential was ever printed or typed; `POST /app/access-control` created the
`Viewer`; the JVM was stopped and the temp directory deleted afterwards). Management's `POST /app/other-buckets` gave
two columns (`Depot`, `Yard`), and the board printed one `class="kcol-add"` form per column —
`data-live-target="#taskModal"`, hidden `new=1`, `column=1` / `column=2`, `allbuckets=1` — with the dashed
`+ Add bucket` box still the last node. `?new=1&column=1&allbuckets=1` as the `Viewer` returned
`id="taskModal"` with `style="display:block"`, `<select name="column" id="taskColumn">` holding
`<option value="1" selected`, *"A new card lands under Depot."*, `id="taskAssignee" disabled`,
`id="taskStatus" disabled`, the pinning sentence, a **Create task** button and no `taskProject` node at all. That
account's `POST /app/assigned-tasks/save` with `taskId=0`, `assignee=EMP0000`, `status=Completed` and `column=999`
came back as HTML with the popup up, **one** `field-err` reading *"Choose which column this card sits in."* 130 bytes
after the picker, the title still typed into `value="Check the fire exits"` — and no card. The same POST with
`column=1` → 302, and the board then showed that title under **Depot** with
`<span class="dot d-gray"></span> Not started` and `avatar xs" title="Test Worker"`, i.e. the writer and the starting
status, not the ones posted. Management's `POST …/save` with `taskId=1&column=2&allbuckets=1` → 302 to
`?allbuckets=1`, and the board came back wide with **Depot** `kcol-count">0` + *"No tasks in this bucket yet"* and
**Yard** `kcol-count">1` carrying the card. The owner's `?edit=1&allbuckets=1` printed *"The dates, the priority and
the column are yours to set…"* with `<select name="column" id="taskColumn">` carrying **no** `disabled` while
title/assignee/status all did, `id="task-steps-1"` at `0 of 2 done · 0%`, and both tick forms naming
`#project-progress,#task-body-1`. Their `POST /app/tasks/checklist/toggle` → 302 to `?edit=1&allbuckets=1` (popup
still open, board still wide), the popup at `1 of 2 done · 50%` with `style="width:50%"`, and the card out on the
board reading `<span class="kpct">50%</span>` inside `id="task-body-1"`.

**Deploy:** the live JVM was stopped with `deploy/stop.bat` before `installDist` (never the other way round — the
running process holds `lib\`), and `deploy/run.bat` started the new build on 8080. `murray-pts-2.0.0.jar` is newer
than every `.kt` under `src/main`, and the served bytes hashed against the files on disk (md5, first 12):
`css/app.css 45452786b4bf` — the backlog's add-line and popup rules — and `js/app.js 30ab8caeebca`, **unchanged**,
since Stage 42 needed no new script: `app.js` already swaps whatever a form names, and `column` rides as an ordinary
hidden field. `GET /` → 200. There is still no cache-buster in the markup, so a tab opened before this deploy shows
the old backlog: **Ctrl+F5**.

**One open thing this stage did not touch.** `GET /app/other-tasks` is still mounted — the legacy sidebar page that
lists the same rows in a table headed **"Unassigned backlog"**, which Stage 42 has now made a description that is
wrong twice over (those cards can be assigned, and they are editable from the board). It is read-only, it is a second
screen for one thing, and removing a route the sidebar links to is the user's call, so it was left standing rather
than quietly deleted. Also pre-existing: `kanban.js` still answers a status drag with `location.reload()`. The
backlog has nothing to drag, and every write this stage added either swaps a block or lands on the same view it left.

**If this is restated.** "Same as project tasks" is about the **card**, not about the board: a backlog card now opens
the same popup, prints the same chip and is written by the same route, but the columns themselves are not project
lanes — no drag, no per-lane naming, no `Unsorted` — and the dashed `+ Add bucket` box and the `⋮` stay management's
(Stage 39/40). If the ask comes back as "let people drag backlog cards between columns", that is `kanban.js` gaining a
third board shape plus the in-place repaint a drag never had, and the picker already does the write it would want. If
it comes back as "make Other tasks a project", the answer is a project card: `kind = ASSIGNED`, a project id, lanes
under it — not a fourth picker on this popup. And if a card written by an ordinary account is described as "wrong,
it should be assignable", the pinning is the reason: it is what stops that route from being a way to hand work to
anybody, and it is management's **Status**/*Assign to* fields afterwards that move it.

<a id="stage-43"></a>

## Stage 43 — the Forecasts page is a monthly money grid (2026-10-16, live, V8 migration)

**The ask.** `forecast.png`: *"make forecast like this."* The picture is a spreadsheet of money, not a list of
records: eleven columns headed Type / Project # / Description / Client / Revenue, MNT / Invoiced in previous months /
**Forecast (current month)** / Remainant to next months / Invoiced month / **Invoiced amount, MNT** / Invoice status,
rows tinted pale yellow, pale orange and pale green under a legend that names those three states, two totals under
the table, and a second table below it called *Completed projects — invoiced*. Four things the picture cannot
settle were asked and answered, and every one of them is load-bearing below: **"invoiced in previous months" is
derived from a monthly ledger** (not a column typed once), the page reads **the current month, with a ‹ › month
switcher** (not one undated list), **only management may type in the money cells** (a Viewer reads the same figures
as text), and the legacy `forecasts` table is **left alone and unread** while the page that used it is replaced.
The picture's spelling — *Remainant* — is kept: the user's sheet says that, so the header says that.
*(Stage 108 signed the revenue cells and **Stage 109** dropped the `, MNT` from *Revenue* and *Invoiced amount* — one
panel may now mix currencies — so those two headings read as plain words today; the other nine are as the picture has
them.)*

**V8 is the ledger.** `V8__forecast_ledger.sql` creates `project_forecasts`: one row per project per month, keyed
by the pair, holding `forecast_amount`, `invoiced_amount`, `invoiced_month` and `invoice_status`, with a cascading
foreign key onto `projects` like every other child table. It writes nothing, so migrating is not a data event: an
existing database arrives with the same empty sheet it had before. `MigrationSmokeTest`'s expected-table list gained
`project_forecasts`, which is the stage's migration test — there is no backfill to check because there is nothing to
backfill. The old `forecasts` table, its rows, `ForecastRepository` and the importer's path through it all stay
exactly as they were; the only thing that stopped reading them is this page, and the sidebar pill beside
**Forecasts** now counts `DemoData.projects.size` instead of those legacy rows (`DashboardCommon.kt` carries the
comment that says why).

**One identity, and every column is either typed or derived from it.**

    revenue = invoiced in previous months + this month's forecast + remaining to next months

`ForecastService` states it at the top of the file and `ForecastLine` is its only implementation: `previous` is what
the ledger holds for that project in months *strictly before* the one being read, `forecast` is the line the reader
is standing on, and `remaining = revenue − previous − forecast` is what the identity leaves over. `remaining` is the
one derived cell allowed to go **negative**, and it is deliberately unclamped: a project planned above its own
revenue has spent its room, and a `0` there would be the page lying about a figure the reader can see does not add
up. `ForecastLedgerRepository` does the summing — `invoicedBeforeMonth(month)` and `invoicedTotals()` are one
`groupBy`/`sumOf` pass each, so the grid's cost does not grow one query per row — and nothing in the view does
arithmetic, which is what stops the "previous months" column from disagreeing with the line it just saved.

**The row is the form.** Each running row prints one hidden `<form id="fc-line-<project>">` (the `display:none`
box) plus four controls bound to it by the HTML5 `form=` attribute: the forecast box, the invoice-month picker, the
invoiced box and the status select. That is four independent writers in one `<tr>` with no visible button — a cell
sends itself on `change`, which is the only shape of data entry a grid this wide invites. Each names
`data-live-target="#fc-row-<project>"` and `data-live-extra="#fc-totals"`, so the response swaps that row and the
totals line and the page itself never restarts; the completed rows carry `#fc-totals-done` instead, because a
finished job contributes to its own table's total and to nothing else. Every row is `id="fc-row-<project>"` for
exactly that reason.

**`app.js` learned one new thing: a pressed submit button.** The month controls are two `<button name="month">`s in
the same GET filter form whose only difference is the month they name, and a plain `FormData(form)` leaves the
button out of a form's data set. `boardUrl(form, submitter)` now sets `params.set(submitter.name, submitter.value)`
when there is a submitter, so ‹ and › send their own month and any other send keeps the month already in the
address. This is the general rule, documented in the file's markup contract: one form can hold several *different*
sends, and the control that was pressed is the answer.

**A blank line reads as blank.** An untouched row gets **no tint at all** (`ForecastLine.tint` returns `""` when
`isBlank`), because the key above the grid says pale yellow means a forecast has been entered, and tinting a row
nobody wrote would have it claim a plan that does not exist — while an untouched row is precisely what this sheet
exists to catch. For the same reason a writer's empty line opens as **empty boxes**, not prefilled `0`s
(`shown(value)` blanks a zero only when the line is blank): a zero you must delete before typing your own figure is
a cell that fights you. A line that *does* say something keeps printing its real zeroes, and a Viewer — who has no
boxes — always sees the figures. Neither half of the grid is printed as an empty container: a sheet of eleven
headers over nothing, or a *Completed projects* heading whose pill reads `0`, is a page describing a row that is not
there, which is the same rule the board's lanes have followed since Stage 37.

**The invoice-month cell does not move the line.** A line is keyed on the month the *page* is reading, never on what
that cell names: the cell answers *when was this raised*. Letting it choose the row would let one keystroke rewrite
the "previous months" column of every month after it, and `ForecastServiceTest` pins both halves of that — naming
November on October's sheet leaves October's row where it is and creates no `2026-11` row, while November's own
`previous` counts October's invoice. Blank and "this sheet's own month" are the same statement, so only a real
difference is stored (`invoicedMonth = if (invoiceMonth == key) "" else invoiceMonth`), and `shownInvoiceMonth`
reads a blank back as the row's own month. A line that ends up saying nothing (`isBlankLine`) is **deleted** rather
than stored as three zeroes, which is what keeps a year of untouched months out of the ledger.

**Amounts are refused under the cell that said them.** `parseAmount` accepts `8600000`, `8,600,000` and
`8 600 000.50`, reads a blank as nothing, and rejects text, a negative and a non-finite magnitude — and
`amountRefusal` now splits those three into the three different sentences they deserve ("Enter the amount as a
number, like 8600000." / "An amount cannot be below zero." / "That number is too large to put on a forecast."),
because a single message for three different mistakes makes the reader re-type something that was already right.
Errors come back keyed by `ForecastField` and print inside the cell they belong to, the row re-renders with the
characters still in the box, and the sheet's totals still read the ledger — the standing rule from Stage 39's
bucket popup, applied to money. The accepted shapes are deliberately the same ones `ProjectService` takes for
revenue, so the app's two money fields do not disagree about what a number is.

**Writing is management's, at the route.** `POST /app/forecasts/save` asks `Authorization.canManage` before it
parses anything and answers a refusal with a redirect that writes nothing; `ForecastService.save` keeps the same
check as its first branch (`ForecastField.PERMISSION`) so the rule is not one call deep in a form handler. The
Viewer's markup carries no form and no save route at all — hiding markup is not the check, and the check is the
route — but the Viewer keeps the search bar, both pickers, every derived figure, both totals and the row colours,
because none of that is theirs to change but all of it is theirs to read.

**Completed work gets its own table.** `forecastCompleted` prints eight columns — the three questions about a plan
(this month's forecast, what is left for next months, the invoice status) are gone, since a finished job has no
forward plan to write, and "invoiced in previous months" becomes "Total invoiced, MNT", the whole ledger read at once
and capped at the project's own revenue by `fc-covered`. It has its own pill, its own total, and its own swap
companion. A completed project still has ledger lines and the month it closed in reads them like any other row's.

**Filtering is the Projects board's filter.** `ForecastFilter` searches the same four fields (number, description,
client, manager) with the same every-word-must-appear rule and the same two pickers, applied to **both** halves of
the sheet, and `ForecastBoard`'s totals re-sum over what is left rather than over the catalogue. The filter rides
through every save as `board_q` / `board_type` / `board_status` hidden fields and `queryFor(month, path)` rebuilds
the same URL for the reload links, because a row that posted without them would come back as the *whole* grid — a
page whose totals line jumped to every project's money the moment somebody typed in one filtered row would be a page
that contradicts itself. Rows are ordered by project id, which is the one order that cannot shuffle a row out from
under the person typing in it.

**Tests:** `ForecastServiceTest` (17) runs against a real `project_forecasts` table — the strictly-earlier sum with
the row's own month and a later month both excluded, the identity holding on two months of one project, a negative
`remaining` surviving unwrong, a project with no line arriving as an untinted blank that writes nothing, colour
following the stated status rather than the figures, both halves and both totals narrowing under the filter, the
`PERMISSION` refusal writing nothing, an orphan project id refused under its own picker, the three amount shapes
accepted and the three refusals each landing under their cell with both cells reporting when both are wrong, the
line keyed on the sheet's month and not the invoice cell's, a blanked line deleted while `0 / 0 / Fully invoiced`
survives as the claim it states, and one saved line moving the next month's numbers without a second write.
`ForecastsRenderTest` (15) pins the markup against a fixture dated February 2023 rather than "this month", so the
suite does not depend on when it runs: the eleven `<th>`s in the picture's order and spelling over the eleven
`<col>`s that carry the same classes, the eight `d-` columns with `d-forecast`/`d-remain` absent, the row and tint
classes, the empty boxes for a writer against `<span class="money">0</span>` for a reader, one hidden form per row
with all four writers bound to it (`2 + 4 + 4 + 2` autosubmits, not the three a first count assumed), the swap
targets, the `board_*` companions, the Viewer's read-only sheet, the month buttons' absolute values with **This
month** appearing only off the current month, and a refused row echoing what was typed with exactly one
`field-err` and nothing written. **304 tests in 24 classes, 0 failures.**

**Deploy:** the live JVM was stopped before `installDist` (the running process holds `lib\`), the throwaway was
booted on 8095 against a `%TEMP%` database with its own generated session secret and admin password (files, never
printed), and `deploy/run.bat` then started the new build on 8080 after a `data/pts.pre-v8-<ts>.db` copy. On the
throwaway: V8 applied on a fresh database ("Successfully applied 8 migrations"), a blank line arrived untinted with
empty boxes, a written line came back `fc-partial` with its totals moved and its `remaining` unclamped, ‹ › moved
the derived column with them, `about a billion` was refused under its cell with nothing written, a filtered save
came back filtered, and a `Viewer` was served no form and turned away by the route. On the live instance the
`project_forecasts` DDL is in the WAL (the migration ran; the file is not checkpointed yet), `GET /` → 200, and the
served assets re-hashed to `css/app.css be3768c03d81` and `js/app.js c9340709fa5d` — both changed, the CSS for the
grid and the JS for the submitter. The live database holds **no projects**, so the sheet opens on its empty state
until there is a project to put a line on: the authenticated live look is the user's, and there is no cache-buster,
so **Ctrl+F5**.

**If this is restated.** "The previous-months figure is wrong" is usually the month, not the sum: the sheet reads
one month and everything below it is strictly-earlier lines, so check `?month=` before the repository. "Let the
invoice-month cell move the money" is the one change this stage refuses on purpose — it is what keeps a keystroke in
one row from rewriting the arithmetic of every later month. "Tint the empty rows yellow so people see where to
type" contradicts the legend the same page prints; the row that needs catching is the one with no colour. "Let
anybody correct a forecast" is a route change, not a markup change, and the markup half is what the render test
pins. And if the ask comes back as "the numbers should be stored, not derived", the answer is that only
`forecast`, `invoiced`, `invoiced_month` and `invoice_status` are stored, and a page that stored `previous` too would
be a page that could be edited into disagreeing with itself.

<a id="stage-44"></a>

## Stage 44 — the Lesson learnt page is a dated, per-person sheet (2026-10-16, live, V9 migration)

**The ask.** `lessonlearnt.png`: *"make lesson learnt like this."* The picture is a list of records, not a grid
of figures: a toolbar of two pickers (**All projects**, **All employees**) with "3 active, 0 archived" at the far
end, then DATE ▾ / EMPLOYEE (a coloured initials avatar beside the name) / PROJECT # / TASK / LESSON LEARNT /
STATUS (green `● Discussed`, grey `● Not discussed`) / three square icon buttons per row, and a `▾ Archived 0`
heading under the table. Four things the picture cannot settle were asked and answered, and each one is
load-bearing below: the add control is **`+ Add lesson` in the toolbar opening the app's standard popup** (project
→ card → text), rights are **everyone their own row, management any**, the EMPLOYEE column is **the account that
logged it, with the TASK column an optional link to a real card**, and the rows already in the table are
**carried over as undated lessons** rather than dropped or guessed at.

**V9 widens `lessons`; it does not add a table — and that is the opposite of V8's call, for a reason.** The
forecast page got a new table because its old rows were a different *kind* of fact (hours per period) from what the
grid reads (money per month). Here they are the same kind: a V1 row is already a piece of text about a project, and
the sheet keeps it. A second table would have made the page's first column a union of two sources and every write a
question about which one a row lived in. So six columns arrive on the existing table — `lesson`, `employee_number`,
`task_id`, `logged_on`, `discussed`, `archived` — with three indexes, and one `UPDATE` joins each legacy row's
`title` and `summary` into the single text the page prints, guarded by `WHERE lesson = ''` so it cannot re-run over
something typed since. `title` and `summary` stay (`title` is `NOT NULL` with no default; dropping them means
rebuilding the table for no benefit) and the page prints the single `lesson` column instead — with the same join kept
as the row read's fallback for a `lesson` that came through blank, so a database the backfill never ran over still
shows its words. The join is one function,
`domain/lessonTextOf(title, summary)`, so the migration, the JSON importer and the row read in
`LessonRepositories` state it once. `MigrationSmokeTest`'s table list is unchanged — no table arrived — and
`LessonMigrationTest` (5) is this stage's migration test instead, since what has to be proved is the widening and
the carry-over.

**The employee column is not a field.** `LessonService.save` takes the author from the session and ignores whatever
the form claims, and the popup therefore has no Employee control at all — the sheet is a record of *who* learned
something, and a picker beside it would let a lesson be attributed to whoever was handy. An edit keeps the row's own
author rather than moving it to the name of whoever opened the popup. A create whose account is blank writes `NULL`
instead of a foreign key to a member who does not exist, which is the state the sheet already reads as "unowned,
management's to tidy".

**A card pins its lesson to its project.** `LessonDraft` carries both ids and they may not name two different
projects: "M-01250 / a card on S-00005" is a statement the sheet cannot check. The pairing is read off the database
(`LessonRepository.projectOfTask`), never off what the form says about the card it points at, and the refusal prints
under the card field with the sentence naming which of the two has to move. A missing or foreign card is a refusal,
not an un-filing — the same rule Stage 42 states for a backlog card's column. The card picker is grouped by project
(`optgroup`) rather than filtered by the picker above, because a popup that had to be re-rendered to fill its own
second field is a page reload wearing a mask; backlog cards and a card whose project has gone sit under **No
project**, the same words the project picker uses for none.

**Rights are decided once and asked again on every route.** `mayWrite` is management-or-author, stamped onto each
row by `LessonService.board` so the view asks a row rather than re-deriving the rule, and re-checked by `save`,
`setDiscussed`, `setArchived` and `remove`. The three single-field writes go through one private `write(...)` that
reads the row back before touching it, so a page left open since the last edit cannot write a lesson that has gone
(`That lesson is no longer on the sheet.`) or one that has changed hands (`Only management can change someone
else's lesson.`). A reader without the right gets no buttons on the row and a popup that prints the text, the
project and the card as plain lines with no form inside it — but the click is never a dead end, and hiding markup is
still not the check.

**`discussed` and `archived` are two columns, not one four-valued status**, because they answer different questions
and a lesson can be both. The split is what decides the swap granularity: ticking the pill changes a row's state but
not its place, so `✓`/`↺` names `data-live-target="#ll-row-<id>"` and replaces one `<tr>`; archiving or deleting
moves a row between lists, so those two name `#ll-board` and add `data-live-extra="#ll-counts"`. An archived row
gets the `↩` and the bin and no tick — a lesson somebody put away is not also being discussed — and delete carries
`data-confirm`, being the one write here with no way back.

**`app.js` learned one new thing: a filter form may name companions.** `[data-live-extra]` had only ever been read
on block forms, and this page's count line sits on the toolbar beside the two pickers that change it. A filter form
that swapped only the region would leave "3 active" standing over a board filtered down to five rows — the same
half-updated page the attribute exists to prevent, arriving from the other direction. `refresh()` now runs
`extraIds(form)` after the region swap; forms that name nothing are untouched, so every page written before this is
unaffected. The count element itself stays *outside* `#ll-board` deliberately, since it is the toolbar's and the
toolbar is never replaced — which is why the render test asserts the region names it as a target while no
`<span class="ll-counts">` exists inside it.

**`lesson-modal.js` is a fourth modal script, and it only knows the ways out** — `[data-lesson-modal-close]` (the ×,
**Cancel** and **Close**), the backdrop, and Escape — named apart from the task popup's `[data-modal-close]` and the
backlog's `[data-bucket-modal-close]` so one click on a page cannot be read as closing three different popups by three
different files. Opening is `app.js`'s business: both openers are GET forms onto this route (`?new=1`, `?edit=<id>`)
whose only swap target is `#lessonModal`, so a reader with scripts off gets the same popup as a fresh page load. Two
details that are only visible from here: closing strips **only `new` and `edit`** from the address — `project`,
`employee`, `sort` and `archived` are the sheet's own view, and closing a form is no answer to any of them — and the
caret is taken by watching for the node swap (`MutationObserver` on `document.body`, as `bucket-modal.js` does for
`#bucketModal`), because a popup arriving mid-session is a swapped-in node, not a click this file handled. The popup's
date field is deliberately **not** `[data-autosubmit]`, unlike the toolbar's two pickers: choosing a date is half of
writing a lesson, and a page that posted the moment it was picked would refuse on the still-empty text box underneath
it.

**The sheet's view is its address, and the two kinds of form carry it under different names.** GET forms use the
plain keys (`project`, `employee`, `sort`, `archived`), which are the toolbar's own fields and land in the address
bar. A writing form carries the same four under `board_*`, and the prefix is load-bearing rather than decorative:
the popup posts the `project` a lesson is about and a row's archive form posts the `archived` state it is moving to,
so un-prefixed, a restore pressed while the archived list was open would state the same key twice and answer with the
row filed back into the archive it was being brought out of. `lessonFilterOf` and `lessonFilterOfForm` hand both to
the same `LessonFilter.of`, so the two cannot drift. The **Date** header and the **Archived** heading are both
`<button … form="llFilter">`s inside the live region — that is what lets a control re-render the block it lives in
without nesting a form in a form, and `LessonsRenderTest` pins that nothing on this page nests a form at all (the
toolbar is two sibling forms on one flex line; a nested one would be dropped by the browser and the add button would
become a page load). Both buttons carry the value they are *asking for*, not the one on screen — Stage 43's rule, so
a reload cannot flip the sheet back. **Clear** drops the two pickers and keeps `sort` and `archived`.

**Carry-overs print as what they are.** A V1 row shows with no employee, no card, no date and `Not discussed`, and
sits at the end of **both** orders — `compareBy { it.loggedOn.isEmpty() }` newest-first being handled by the empty
string sorting last on its own. `logged_on` is `TEXT NOT NULL DEFAULT ''` for exactly that reason: one comparable
value per row and no NULL special case in the repository. Inventing a date, or hiding a row nobody can date, were
both refused: an undated lesson is the sheet's own backlog. The two empties the page can show are told apart —
nothing logged at all (`Nothing has been logged yet.` plus the add control) against nothing matching the pickers
(`No lesson matches this project and employee.` plus a link back to the whole sheet). The avatar tint comes from the
account (`employeeNumber.sumOf { it.code } % 6`), so one person is one colour on every row.

**Tests: 49 new, 353 in 27 classes, 0 failures.** `LessonMigrationTest` (5) runs V9 against a database built up to
V8 only: the six columns arrive with the old rows keeping their joined words, a title-only note keeps just its title
rather than a dangling separator, a carried row arrives blank rather than guessed at, a lesson outlives the project,
the person and the card it points at, the backfill is not replayed, and the three indexes exist.
`LessonServiceTest` (21) holds the rules: the author comes from the account that wrote it and an edit leaves it
there, an ordinary account writes its own row and nobody else's, an unowned row is management's alone, a write on
somebody else's row or a gone row is refused where it stands, a lesson needs words and is 500 characters at most, no
date means today, a card cannot be filed under another project, the pickers cut the sheet and nothing else,
undated rows sit at the end of both orders, archiving moves a row and restoring moves it back, the pill is a state
and not a judgement, each row knows whether this reader may write it, and the view is the address it states.
`LessonsRenderTest` (21) pins the markup: the seven columns in the picture's order, the employee column as the person
who logged it in their own colour, the project as its number and the task as its card, the date header that is also
the button reversing it (its `<th>` holds the `<button>`, so the heading is asserted as a cell, not as a tag), the two
pickers as one live GET form whose companion is the count line, the order and the open archive riding with every
control, nothing nesting a form in a form, a state-only write swapping one `<tr>` while a row that leaves takes the
sheet and the count, only the author and management offered buttons, the popup arriving drawn but closed, a
colleague's lesson opening to be read, a refused save holding the words with the reason under its field, the archived
heading printed with nothing under it, the two different empties, and — the sheet's first look at its own stylesheet
— that the lesson cell's form is stated as a block that beats the shared opener rule, and that the seven columns'
percentage shares still add to 100 with the four fixed-shape ones at least as wide as what they must spell.

**Deploy:** the live JVM was stopped before `installDist` (the running process holds `lib\`), and the throwaway ran
on 8095 against a fresh `%TEMP%` database with its own generated secret and admin password (files, never printed),
booted with an explicit `-cp` of the compiled classes, resources and dist jars **minus** `murray-pts-2.0.0.jar` so
Flyway could not see two copies of every migration. There it was driven over curl as both roles: the sheet, the
popup's four fields with no author field, every refusal under its own field, the swap targets, the view carriage
through a picker change, the Clear link keeping the order, the sidebar pill matching its comparator, and the rights
split in both directions. On 8080, after `data/pts.pre-v9-20261016-1639.db`, `GET /` → 200 and
`GET /app/lessons` → 302 to `/` unauthenticated; the served assets re-hashed to `css/app.css 090d692030ee`,
`js/app.js 1e0d4f04ec64` and the new `js/lesson-modal.js d95205a3d627`, each byte-identical to source. V9's DDL is
confirmed in `data/pts.db-wal` (the migration's statements are in the WAL until the next checkpoint), checked by
metadata counts only. There is no cache-buster in the markup, so **Ctrl+F5** on any tab open before the deploy. The
overlap fix above went out the same way — live JVM stopped, `installDist`, restarted, `GET /` → 200,
`GET /app/lessons` → 302, and the served `app.css` hashing to `98fb938a5877` with the same md5 as the file in
`src/`; only the stylesheet changed, so **Ctrl+F5** is the whole of the user's side of that deploy.

**Nothing prints over its column.** The first sheet on the live screen wrote its text over the Status and Actions
columns, so the fix was measured on the rendered page rather than reasoned from the markup. The cause was the
cascade, not the width: the lesson cell's own form is a GET opener and therefore carries `.opener-form`, whose rule
sits further down `app.css` as `display: inline-flex`. Two bare classes tie at (0,1,0) and source order decides, so
the cell shrink-wrapped to its button's whole sentence instead of cutting one line. Naming the element —
`form.ll-text-form { display: block }` — is (0,1,1) and wins wherever either rule sits, and the other three
`opener-form` users keep the inline-flex row they want. Measuring the same page then found three more shortfalls the
same picture hides: the date and the status pill each split over two lines and the archive button spilled 7px past
its column, because a `table-layout: fixed` percentage only covers a column if it holds what that column must
**spell** plus the cell's 20px of padding. The seven shares are now 9/14/8/20/24/13/12 against nowrap minimums of
84 for a date, 81 for an employee code, 124 for the dot-and-words pill and 122 for three buttons at 30px with 6px
between them, all taken at the 1030px sheet a 1360px window gives. Rows print one line tall and nothing paints past
its cell.

**If this is restated.** "Put an Employee picker in the popup" is the one change this stage refuses on purpose: it
turns a record of who learned something into a claim about whoever was handy, and the author would stop being the
session. "Show the whole lesson text in the cell" contradicts the picture, which truncates one line; the full text is
one click away in the popup and in the cell's `title`. "One status column with Discussed / Archived in it" merges
two questions that are independent, and today's sheet would lose either a pill or a place to put a row. "Drop the
undated seed rows" is the answer the user did not choose — they stay, blank, at the end of both orders. "Let
management's archive button show for everyone and just reject the POST" breaks the rule the render test pins: the
buttons are printed for readers who may use them, and the route check underneath is the safety, not the display.
And if the ask comes back as "make the count live inside the board", the answer is that it is already a swap target
and must not become a child — a region cannot replace the toolbar that holds it.

<a id="stage-45"></a>

## Stage 45 — the Forecasts sheet is cut by category, the way the board is (2026-10-18, live, no migration)

**The ask.** *"make forecasts sort by type like projects."* The sheet already had the Projects board's search bar
and its live-region rules, but it answered the category question with a **Type picker above one long table** — a
control that hides a panel and says nothing about the other eight. Asked which shape they meant, the user chose
**"Tabs + a panel per type"**: the board's tab row (**All** plus one tab per type, each with a live count) and the
money printed as one panel per category, each with its own totals line, the Status picker and the month strip
untouched. No schema, no migration, no new CSS: this stage is the second page adopting vocabulary the first one
already ships.

**The grouping is the service's, and both halves of the page come out of one read.** `ForecastService.board` now
returns `groups: List<ForecastGroup>` — one per category that has rows, in `PROJECT_TYPES` order, each holding its
own `open` and `completed` lists and deriving `forecastTotal`, `invoicedTotal`, `completedInvoicedTotal` and `count`
from those rows — next to `tabs: List<ForecastTab>` and `allCount`. They are deliberately *not* two ways of slicing
the same list: the tabs are counted over `filter.matchesWithoutType` (search + status, no category) while the panels
are built from the full filter, so a pill always states what clicking that tab would leave, including both tables of
its category, while the panels show only what is open. A test asserts the panels' totals add back up to the board's,
which is what makes the two numbers on the page one statement rather than two that happen to agree today.

**The view is the board's markup, tag for tag, which is why the stylesheet did not change.** `filter-tabs` /
`ftab` / `ftab.active` / `pill` for the row, and `card-panel` > `group-head` > `chip t-<letter>` + `pill` for each
panel — the same classes `Projects.kt` emits, from the same `typeChipClass` now living in `domain/Project.kt` as the
app's one statement of the letter-to-colour pairing (the copy that used to sit privately in `Projects.kt` is gone).
The tabs carry `typeLabel` (`"S — Site projects"`) as the board's do, while the chip carries the bare name, again
exactly as the board does. Two structural decisions came with it:

* The tab row is **inside** `#fc-board`, the live region, because its pills count rows the search moves — a tab that
  stayed outside would be a number that goes stale while the page under it changes, the same lie the Projects board
  refuses. The toolbar stays outside it, as always, so the caret survives.
* The open category is a **hidden field** on the toolbar's GET form rather than a picker, because the tabs that
  choose it are links: searching or switching status from inside a panel has to stay in that panel. This is the
  board's arrangement, picker for picker.

**Each panel owns its totals line, by letter.** The sheet used to swap one `#fc-totals` (and one
`#fc-totals-done`) per page. With a table per category that id would have printed nine times over and `app.js`'s
`data-live-extra` would have repainted *the first panel on the page* — money moving under a category nobody typed
into. So `fcTotalsId(group, done)` gives each line `#fc-totals-<letter>` / `#fc-totals-done-<letter>`, and each row's
hidden form names the line of the panel it stands in. The letter, not the name: it is unique across `PROJECT_TYPES`,
it needs no counter that shifts when a category holds nothing the search can find, and the `tr.fc-totals` rule is a
class selector, so nothing was keyed to the old id's shape.

**`?type=` is a link a reader can edit, so it is validated like one.** `forecastFilterOf` and `forecastFilterOfForm`
take the category only while it is in `PROJECT_TYPES` — otherwise the filter reads as *no category at all*. A foreign
name in the address bar therefore opens the whole sheet rather than cutting it to nothing while its tab row still
prints the word, and a foreign `board_type` in a posted row form cannot answer a save with an empty page or point it
at a totals line that holds no rows. A real name survives, and the rest of the query survives with it.

**What did not move, on purpose.** The per-row **Type** letter column stays: the eleven `<col>`s are shared by every
panel and the footer's `colspan="6"` is counted against them, so removing a column re-tunes the grid for no gain the
reader asked for — the chip above the table and the letter in the row are the board's own arrangement too. A project
whose type is not one of the nine gets no panel, which is the Projects board's long-standing behaviour and not a new
hole: `ProjectService` refuses such a type, so no control can create one. And `if (board.isEmpty)` still chooses the
empty message over the panels, so the two empties the page can show (nothing at all / nothing matching) stay told
apart with the tab row above them.

**Tests: 7 new, 360 in 27 classes, 0 failures.** `ForecastServiceTest` (24) gained: one panel per category in
`PROJECT_TYPES` order with no panel for an empty one, a category's running and finished rows sharing their panel, and
panels covering `open + completed` exactly once; tab pills counting what clicking leaves rather than what is open,
including a search that empties a tab without removing it, the running/finished split behind one pill, and an open
tab narrowing the panels to one; and each panel's totals being its own rows' money with the panels summing to the
board. The address test now pins `hrefFor` — the open tab's link keeps `q`, `status` and the month and swaps only
`type`, and `null` means *All*, its `type=` absent rather than empty. `ForecastsRenderTest` (20) moved its assertions
onto the new shape (writer count 12→11 for the picker that went, viewer 2→1) and pins: the tab row as the board draws
it with every pill equal to its own panel's row count and the printed pills summing to **All**; panels in the list's
order with none for an empty category; an open tab printing its own panel, carrying `?type=` on its href, and putting
the category in the toolbar as a hidden field rather than a picker; a foreign category read as none in both the query
and the posted form; and each row form's `data-live-extra` naming its own panel's totals id, uniquely.

**Deploy:** the live JVM was stopped before `installDist`, and the throwaway ran on 8095 against a fresh `%TEMP%`
database with its own generated secret (files, never printed), booted off an explicit `-cp` of the compiled classes,
resources and dist jars **minus** `murray-pts-2.0.0.jar`. Driven over curl as management: four projects across three
categories, five ledger lines including a previous month's invoice, then the sheet — **All 4** and nine type pills
with the right counts, three panels in board order (Fabrication, Repair, Site), Site holding a running table *and* a
completed table, the four totals lines carrying `#fc-totals-M`, `-done-R`, `-S`, `-done-S` and their own figures
(2,000,000/0; 8,000,000; 4,000,000/1,000,000; 2,000,000), each row form naming its own panel's line, `?type=Site+projects`
drawing one panel with the category as a hidden field, `?type=Not a category&q=haul` reading as **All** with the
search intact, and a refused `12x` coming back under the Site panel with the characters still in the box and the
message under its cell. On 8080, `GET /` → 200 and `GET /app/forecasts` → 302 unauthenticated; the served assets
re-hashed to `css/app.css 98fb938a5877`, `js/app.js 1e0d4f04ec64`, `css/base.css 45f2498ba57a` and
`js/kanban.js 35c44db8fe7b` — `app.css` unchanged from Stage 44, which is the proof this stage added no style. No
cache-buster in the markup, so **Ctrl+F5** on any tab that has the old sheet open.

**If this is restated.** "Put the Type picker back beside Status" is the shape that was replaced: a picker says which
category is hidden, a tab says how much of each there is. "One totals line for the whole sheet, under the last
panel" would be true arithmetic and a lie about the rows — with nine panels on screen, a row save would repaint a
line the typed row is not in. "Let the tabs sit outside the live region so the page feels steadier" costs the pills
their accuracy; the fix for a jumpy tab row is the region's swap being in place, not moving the row out of it. "Make
an unknown `?type=` an error page, or an empty sheet" is what this stage refuses twice over — the address is editable
markup, and both answers tell the reader the company has no money when the database says otherwise. "Group the
completed rows of every category into one finished table at the bottom" splits a category's money across two places
on the page, which is the opposite of what "sort by type" asked for. And if the ask comes back as "give the panels
the shared ten-column grid the board uses", note that the forecast tables already share *their own* eleven-column grid
per category; the two pages' grids are independent by design and harmonising them is a column-width change, not a
grouping one.

<a id="stage-46"></a>

## Stage 46 — Settings is the Team sheet the picture shows (2026-10-18, live, no migration)

**The ask.** *"make settigns like this"* with `settings-pts.png`: a Settings page whose visible section is
**Team** — an "Add a new employee account…" card with a dark-green `+ Add employee`, a toolbar (search "Search
name, email, employee #…", **All positions**, **All roles**, "22 of 22 shown" pushed to the right edge), then a
flat roster — **NAME / EMPLOYEE # / POSITION / DEPARTMENT / ROLE / EMAIL / OPEN TASKS**, each header a sort
control, each row carrying avatars, a role pill and a red ✎ beside a grey 🗑, and the reader's own row wearing a
green `YOU` pill instead of a pressable bin. Four scope answers fixed the shape before anything was written:
**Settings only, the Team page stays as it is**; the personal card and **Change Password** stay below Team in that
order *(**Stage 99** removed the password card by the user's decision, so the order is now Team, the personal card,
then the app panels — see Stage 99)*, and the workload drill-down the old table printed is replaced by the OPEN TASKS column; `+ Add employee`
opens the app's standard popup and the pencil opens **the same popup filled**, a blank password meaning unchanged;
and search, both pickers and all seven headers work **in place**, with "N of M shown" counting what the filter
leaves. No schema, no migration — the app is still at V9.

**The read is a person, not an account, and it is one read for the whole sheet.** `service/EmployeeService.kt` is
new and owns the answer: the roster (`team_members`) first, then any account whose number is not on it — the same
union the Access control table prints — so a colleague with no login is a row with a pencil and no role rather than
an absence. Positions, departments, accounts and open-card counts are each fetched once for the sheet instead of
per row, for the reason the workload table this replaced already gave: two numbers beside each other must not be
able to drift. Three consequences of that one read are pinned by tests:

* The **Role column prints `Authorization.effectiveRole`**, not the raw `access` column, because a sheet a reader
  acts on has to answer the question the app asks before every write. The popup's Role **picker** still writes raw
  `access` — that is `UserService`'s long-standing behaviour, unchanged here — so an account filed under Engineer
  and typed "Admin" prints as *Viewer*. Flagged to the user as the one place the column and the picker disagree.
  *(**Superseded by Stage 110** on both halves of that sentence: `effectiveRole` and the three-rank `Role` enum are
  gone, and the column and the picker now answer to the same row — a picker holding the roles chip writes a
  `roles` id and the column prints that role's name, so the disagreement this bullet flagged cannot be drawn any
  more. The `access` word is still written, in step with the role, for anybody reading the database by hand.)*
* **OPEN TASKS is a nullable count.** Management sees everybody's; anybody else sees their own and a `—` elsewhere,
  and `null` is that dash rather than zero, which is what lets the column sort "not yours to count" last instead of
  mixing it in with people who genuinely have nothing open. `0` and `—` therefore mean different things on screen.
* **Blanks land last in both directions** — an empty department, a row with no account, an unreadable count — for
  the reason the lesson sheet gives for its undated carry-overs: a row with no value has no place *in* that order,
  and hiding it is worse than parking it.

**Rights are decided in the service and re-checked at every route, and the sheet prints the refusal rather than
hiding the button.** `EmployeeRow.deleteBlocked` carries `UserService.deleteUser`'s own four refusals in its own
order (management-only, no account yet, `EMP0000`, yourself, and a manager on an administrator), each shown as the
`title` of a **greyed-out** bin, so the column stays lined up and the reason is there when anybody wonders why it
will not press. A roster row with no account behind it gets no bin at all but the pencil, because `deleteUser`
could only answer `USER_NOT_FOUND` there — the control that has an answer for that row is the one that creates the
login. `save` is the same discipline on the write side: every field is checked before any of them is applied
(`ROW_GONE` for a popup left open through somebody else's delete, `NOT_YOURS_EDIT`/`NOT_YOURS_ADD`, number taken,
number shape, blank name, role off the list, position out of the catalog, password under four characters), and a
hand-built POST from a manager that carries a position, a role or a password is **refused** rather than quietly
dropped — hiding those fields is the view's job, not the permission.

**The live region is one table, and the count and the popup are named from outside it.** `#team-board`
(`div.board-results`) is the only region on the page; the toolbar form targets it with `data-live-target`, and the
popup's save names `#team-counts,#employeeModal` under `data-live-extra` — the count is deliberately **outside**
the region because a region cannot replace its own toolbar, exactly as Stage 44 established for the lesson sheet.
The seven headers are the interesting part: they are `<button form="tsSort">` members of a **carriage form** that
holds only the search and the two pickers as hidden fields (`form.col-sort-form { display: none }`), because a
table's parent must not gain a stray flex child. The header sends `name="sort"` with a value of `column` or
`column:desc` — one key for one answer, so pressing a different header starts that column ascending and re-pressing
the same one turns it — while the toolbar's own form never sends `sort`. A junk value falls back to the sheet's
default order instead of erroring, because the address bar is editable markup.

**The two openers are GETs and the writes are POSTs, and both carry the view.** `?new=1` and `?edit=<number>` open
the popup so a reader with no JavaScript gets the same form as a fresh page load rather than a dead button; `?edit=`
reads the account from the database and never from a form, and a number this reader may not change answers with the
ordinary sheet and no form at all. The write forms post to `/app/settings/employee/save` and `…/delete`, and — as
on every other page — a write carries the reader's current view back under `board_q` / `board_position` /
`board_role` / `board_sort` while a GET opener uses the plain keys, so a search of "gan" survives adding somebody
inside it. A saved write redirects to the filtered address with `note=USER_ADDED|USER_UPDATED|USER_DELETED`; a
refused save answers with **the page itself** at 200, because `app.js` swaps the popup out of that response and the
reason has to arrive under the field that caused it with the words still typed in it.

**What did not move, on purpose.** `/app/team`, Access control and `POST /app/positions` are untouched — this stage
takes the workload table off Settings and puts a roster in its place, it does not move management's catalog page.
The personal card (number, name, position, access level) and Change Password stay below Team, and
`POST /app/settings/password` is the route it has always been. *(**Stage 99** deleted the password card and that route
by the user's decision; the personal card stays below Team and the three app panels now follow it directly.)* The
eight-column grid is one shared `table.team-sheet`
`<col>` set (19/10/13/9/11/19/9/10) sized the way Stage 44 sizes the lesson sheet — each column's nowrap minimum
plus the cell's 20px padding — with the email address the one column allowed to break under
`overflow-wrap: anywhere`. The role pills reuse the existing chip classes rather than a seventh widget, and the
`YOU` badge is `.chip.ontrack`, the app's own green. Two consequences worth knowing before the first launch: a
**fresh database has no positions**, so the popup's Position picker is empty until somebody fills the catalog
through Access control — the roster's fallback is `Team Member`; and an account created **without** a password gets
its employee number as the password and a forced change on first sign-in, which is `UserService`'s rule, captioned
on the add card rather than reinvented.

**One interaction with the first-login gate is worth naming.** `requireSession()` sends a
`mustChangePassword` account to `/app/settings` for every path outside `MUST_CHANGE_ALLOWLIST`, which is still
exactly `{/app/settings, /app/settings/password}` — so the new `…/employee/save` and `…/employee/delete` POSTs are
blocked for a flagged reader while the page still prints the add card and the pencils for them. The press reloads
the sheet and the reason appears in the Change Password card ("Set a new password to unlock the rest of PTS"), but
the typed fields go with the reload. That is deliberately **not** fixed by widening the allowlist: a manager's fresh
account has its employee number as its password and that number is printed on this very sheet, so letting it write
other accounts before the first password change would hand a guessable credential account-management rights. The
open choice (raised with the user, not decided here) is whether to hide the write controls while the flag is set, or
to answer the blocked press with a flash at the top of the sheet naming the reason.

*(**Stage 99** closed the whole question from the other end: the user asked for the Change Password box to be removed,
and the forced change went with it. A flagged account now reaches the sheet like any other, so neither the allowlist
gap nor the open choice above exists any more. The add card says only what is still true — a blank password means the
employee number is the password. See Stage 99.)*

**Tests: 54 new in 2 classes, 414 in 29 classes, 0 failures.** `service/EmployeeServiceTest` (27) pins the read and
the rules: roster-then-accounts union with an account-only number present, the effective role printed where the raw
column differs, `0` against the dash for a non-manager, blanks last in both directions on every column, the filter
three ways and their counts, the position picker listing choices the *unfiltered* roster has, `deleteBlocked`
matching `deleteUser`'s order rule for rule, and `save` refusing each malformed or over-reach draft while writing
nothing. `SettingsRenderTest` (27) pins the markup: section order and the eight headers, the carriage carrying
`q/position/role` but never `sort` while every header's button carries its own key, the toolbar's live hooks and the
count sitting outside the region, one form per row for the pencil and the bin (a `<form>`-inside-`<form>` would not
survive the browser — `maxFormDepth` is 1 on every row), the add card only for management and its opener carrying
the filtered view, an edit popup with two readonly fields and `Viewer`/`Engineer` pre-selected against a create with
all six fields empty, the refusal re-printing the popup with one `p.field-err` per field, the greyed bins counted by
their own disabled attribute rather than by any `disabled` in the page, and a row that leaves the sheet taking the
"N of M" count with it.

**Deploy:** the live JVM was stopped **before** `installDist`, and the throwaway ran on 8095 against a fresh
`%TEMP%` database with its own generated secret (files, never printed), booted off a `CLASSPATH` of the compiled
classes, resources and dist jars **minus** `murray-pts-2.0.0.jar`, with `PTS_DB_PATH` passed explicitly. Driven over
curl as the Super Admin: three positions created through `POST /app/positions` (the catalog is empty on a fresh
database), five accounts added — one of which came back as a 200 refusal with three `p.field-err` lines and the row
unchanged — then the sheet: seven rows in name order, chips reading 1 Admin / 3 Manager / 3 Viewer, `q=gan` → **1 of
7**, `role=Manager` → 3, `position=Engineer` → 2, `sort=emp:desc` reversed with the glyph, `sort=nonsense` falling
back to the default, `q=zzz` → **0 of 7** and "Nobody matches this search", `?new=1` filling the modal and `?edit=`
showing it readonly at the top, `?edit=EMP9999` leaving it shut, and the admin's own bin answering
`note=CANNOT_DELETE_SUPER`. In the browser the table measured `table-layout: fixed` with the eight shares applied,
the count sitting against the right edge, and every asset 200. On 8080, `GET /` → 200 and `GET /app/settings` → 302
unauthenticated, with the served assets re-hashing to `css/app.css 8902f0ad39d9`, `js/app.js c78ba48d1800`,
`js/employee-modal.js 2664987dd30b` and `css/base.css f2c7702b25f2` — `app.css` differs from Stage 45 because this
stage does add the sheet's grid, and `base.css` does not because it adds nothing to the primitives. No cache-buster
in the markup, so **Ctrl+F5** on any Settings tab still showing the old workload table.

**If this is restated.** "Fold the Team page into Settings as well" is the half the user turned down: `/app/team`
still groups by department and still offers its own assign-a-task popup, and replacing it now would take a working
page out of a launch checklist that names it. "Show the role the account was typed with" (the raw `access` column)
is the disagreement mentioned above — it would make the sheet cheaper to reason about for a reader who never opens
Access control, and wrong for anybody who acts on what the column says. "Put the count inside `#team-board`" cannot
work: the region replaces its own contents, so the span would be deleted by the swap that updates it. "Let the
headers be links" loses the view — a link carries what its `href` was printed with, while the buttons inherit the
carriage's hidden fields, so typing a search and then sorting keeps the search. "Give a manager a greyed Position
and Role picker in the popup instead of no picker" would show controls that cannot be used and still refuse the
hand-built POST, which is the arrangement every other page in the app abandoned. And "count completed cards too,
beside the open ones" is the workload table's idea returning in a narrower form; the column is OPEN TASKS because
that is the one number a roster row can act on.

<a id="stage-47"></a>

## Stage 47 — Other tasks is a page, and Assigned tasks keeps only its own board (2026-10-18, live, no migration; **its `allBucketsToggle` / `#bucketsToggle` half is superseded by Stage 97** — the control, its `otherToggleApplies` predicate and the `allbuckets` field every form in this section carries are gone, and `#addBucketOpener` is the head's only block now, so read the companion-id reasoning below as the history of a mechanism that no longer has two halves to keep in step)

**The ask.** *"make other tasks like this"* with `other-tasks=pts.png`: the sidebar item **Other tasks** active and
carrying a count, a page head of **Other tasks** plus the line *tasks outside of projects*, **Show all buckets** at the
head's right, and underneath it a bucket board — **Finance (1)** / **Management (1)**, each head a coloured dot, the
name, the count and a `⋮` — cards showing title, `• Important`, `📅 22 Aug 2026`, `● Not started` and a `JE` avatar, a
`+ Add task` line at the foot of each column and a dashed `+ Add bucket` box as the row's last cell.

The picture is not a redesign of anything: it is the board Stage 40 built, which has been living at the **foot of the
Assigned tasks page**, while `/app/other-tasks` — the page the sidebar item names — printed a flat read-only list of
the same cards beside it. So the one scope question was which page ends up holding the board, and the answer taken was
**"Move it; Assigned tasks keeps only its own board"**: the bucket board becomes `/app/other-tasks` in full — columns,
cards, `+ Add task`, `⋮`, the dashed box, *Show all buckets* — the Assigned tasks page keeps its five status columns
and nothing else, and the read-only list is deleted. One board per page, one read of one set of work. The picture also
settled a detail it did not state: the head carries the page's name **and** the sentence, with the toggle at its
right, which means the section heading the board used to print above itself — naming the page a second time under its
own name — goes rather than being duplicated.

**The move is a relocation, not a second implementation.** `OtherTasks.kt` is the page now (577 lines), and the three
functions the deleted list was built from — `tasksContent`, `taskRow`, `taskStatusChip` — went with it; each had
exactly one caller, the route this stage replaced, so nothing else could still reach them. `AssignedTasks.kt` went from
578 lines to 52: the scope line, `TaskService.assignedFor`, the shared status board, the shared card popup,
`kanban.js`. `otherTasksContent` takes the same arguments the backlog section took (`session`, `canManage`, `state`,
`bucket`, `showAllBuckets`) and `allBucketsToggle` became the one function the page head calls, so the toggle is drawn
by `appPage`'s new `actions` slot rather than by the board. `DashboardCommon.kt` grew the matching `subtitle` argument
for the head, which is where *tasks outside of projects* lives (`OTHER_TASKS_SUBTITLE`, asserted as a constant so the
README and the page cannot drift apart).

**`#otherHead` is gone, and every block that named it names `#bucketsToggle` instead.** That is the part of this stage
a reader cannot see: the old companion id was the board's own heading, and it was listed in six `data-live-extra`
attributes as the block that has to move when the view changes. Deleting the heading without deleting those references
would not have thrown — `app.js` skips a companion missing from the page — it would have left the toggle's label
pointing one way over a board drawn the other, which is exactly the half-updated screen the mechanism exists to
prevent. So the toggle form's **own id** is now its companion (`id="bucketsToggle"` on the *form*, not the button,
because the form is the block swapped out of the response and the label is inside it), and the three bucket POSTs carry
`#bucketModal,#bucketsToggle`. Whether the control is drawn at all is asked of the same read the board prints its
columns from — `otherToggleApplies(showAllBuckets)` — from the route, so a button never appears over a board with
nothing left to show and never vanishes while a column is still hidden.

**A write from this page answers on this page, and one field says so.** `BACK_OTHER = "other"` joins the two `back`
keys the card popup already carried, `taskRedirect` maps it to `/app/other-tasks` (plus `?allbuckets=1`, and only when
both are true, because a narrow save has no wide view to preserve), and `/app/assigned-tasks/save` branches on it to
render the whole Other tasks page with the popup's own refusal still in it. The three bucket routes redirect through
`bucketsBack(showAll)`, and their deny path — a `Viewer`'s hand-built POST — goes to `/app/other-tasks`, where that
account can see the page refusing them. `NotificationService` got the same knowledge: a backlog card's bell link is
`/app/other-tasks` rather than the board the card used to sit on, through `taskLink`, which reads the row's `kind`
rather than the form that posted it. `/app/tasks/status` and `/app/tasks/bucket` were left alone: they redirect on the
`Referrer`, which is how a status arrow on a wide board already round-trips the view.

**CSS: one rule added, four deleted.** `.page-head h1 .page-sub` prints the subtitle in the head at 12px/600 muted with
an 8px lead-in, because the head is shared markup and the sentence belongs to it now. `.other-head`, `.other-title`
and `.other-sub` went with the heading, and `.buckets-toggle { margin-left: auto }` went because the toggle no longer
sits in a flex row that needs pushing — the head's `page-right` puts it there. The class name is still emitted as a
selector hook (`opener-form buckets-toggle`), which is what the tests select on, so removing the *rule* is safe and
removing the *name* is not. `css/app.css` therefore changed on disk (`8902f0ad39d9` → `f563e6a14956`) while
`app.js`, `kanban.js`, `bucket-modal.js` and `base.css` did not: no behaviour and no primitive moved, only which page
draws what.

**Tests: 420 in 30 classes, 0 failures.** `OtherTasksRenderTest` is new (744 lines, 25 tests) and is the old backlog
cases moved onto their own page plus the two the relocation itself needs: every opener on the page is a GET onto
`/app/other-tasks` and `assertEquals(0, occurrences(html, "action=\"/app/assigned-tasks\""))` holds while its
`…/assigned-tasks/save` form is present — the popup's **Save** is the one control that legitimately posts to another
page's route, and the test says so by asserting both halves. The other is the truth table for `otherToggleApplies`:
hidden columns ⇒ drawn, the wide board ⇒ drawn (a way back), nothing hidden and narrow ⇒ not drawn, asserted against
both the function and the page. `AssignedTasksRenderTest` keeps 4 tests and all of them are absence tests: five status
columns and no `#otherBoard`, no `#bucketModal`, no `d-bucket`, no `addbucket`, no `⋮`, no `/app/other-buckets`, no
`buckets-toggle`, no `allbuckets`, no `kanban-other` — and a backlog card's title is not printed on the page at all,
which is the assertion that catches a board re-added under the board rather than a control renamed.

*(**Stage 124** supersedes the no-column-popup and no-`/app/other-buckets` claims for a reader with
*Manage buckets*: Assigned now carries the shared popup's add half, opened by `+ New bucket` in the empty
projects-board panel when the reader also holds *Edit any task*. The shared board, column menus and view toggle
still do not return here; without the bucket chip there is still no bucket popup. Stage 55 already superseded
the absence of the reader's own backlog cards. Stage 124 was verified and deployed 2026-11-06 (726 tests/49 classes, 25 HTTP and 90 JavaScript checks; JavaScript changed → Ctrl+F5).)*

**Verified live before deploying, on a throwaway.** Port 8095, fresh `%TEMP%` DB with `PTS_DB_PATH` passed and seeding
off, own generated secret and admin password consumed through files with the newlines stripped, classpath of
`build/classes` + `build/resources` + the dist jars **minus** `murray-pts-2.0.0.jar` (Flyway reads every migration
twice otherwise). Two buckets through `POST /app/other-buckets` (both 302 to `/app/other-tasks?allbuckets=1`), two
cards through `POST /app/assigned-tasks/save` with `back=other` (one landing with the view dropped because the save
was narrow, one carrying `?allbuckets=1`), `?newbucket=1` and `?bucket=<id>` both opening their popup
(`style="display:block"`) with the stored name in the rename field and the moving card counted in the delete's label, a
duplicate rename and a blank rename each returning **200** with one `p.field-err` and the typed word kept, a delete
with no destination refusing under the picker, a real delete moving the card and its count to the surviving column,
the last column standing printing **no delete form and no `bucketMove`** but the two sentences saying why, an empty
column deleting with *"moves nothing"*, `?edit=1` opening the card popup with `back=other` and its own column selected,
`?new=1&column=1` opening a blank one aimed at that column, a blank title coming back as the popup with *"Give the task
a title."* on this page, and the sidebar pill reading 3 for 3 cards. `/app/assigned-tasks` printed its five
`data-status` columns and zero occurrences of every backlog marker. Then: live JVM stopped, `installDist`, restarted on
8080 (PID 25824), `/` 200, `/app/other-tasks` and `/app/assigned-tasks` both 302 unsigned, and all six served assets
matching their source files byte for byte. **Ctrl+F5** after this stage — `app.css` changed and there is no cache-buster
in the markup.

**If this is restated.** "Keep the read-only list too, as a table above the board" is the duplication this stage was
asked to remove; two views of one read is the reason the sidebar item and the page disagreed. "Put a compact backlog
section back under the Assigned tasks board" is the half the scope answer declined — the picture's point is that the
sidebar item *Other tasks* goes somewhere that is not somebody else's page. "Print the board's own heading again so
the columns have a title" re-adds the sentence the page head now carries. "Put the toggle inside `#otherBoard`" cannot
work for the reason the Team sheet's count span cannot: the swap replaces its own contents, so the control would delete
itself on the click that used it. "Draw the `⋮` and the dashed box for everybody and let the route refuse" is the
arrangement the app rejected in Stage 42 — the columns are management's, and markup must not invite a press the server
will bounce. "Give the Other tasks popup a project picker so a card can be filed later" is Stage 42's deleted field
returning: a backlog create prints no project picker at all, and a card is re-filed by editing it from a page that owns
the project choice. And "make `/app/assigned-tasks/save` a second route at `/app/other-tasks/save`" would be a second
copy of every rule in `TaskService.create`/`updateAssigned` — one route, one validation, and the `back` field saying
which page gets the answer.

<a id="stage-48"></a>

## Stage 48 — A card is filed in one of a project's six sub-categories, or it is on Other tasks (2026-10-19, live, V10)

*Stage 75 renamed the floor: the shared list is the **three** phases Engineering / Procurement / Production, and they are
ordinary editable lanes rather than a protected set. Everything else on this page — a card belonging to exactly one of
the two boards, filing in a lane being compulsory, and the empty-string exception being closed — is still the live rule.
And since **Stage 111** there is no shared list left on the general board's create at all: its Sub-category picker shows
the columns of the project the reader named in the popup, read from `project_buckets`, so the three names below are what
a board is *typically* drawn with, not a catalogue any create falls back to.*

**The ask.** *"the structure is tasks are have to have either way assigned to a project or the other tasks. A project
means have to be assigned on six sub categories as have on projects. Other tasks could be anything that the user can
create a bucket"*

Three sentences, and each one closes a gap the app had left open. A card has to belong to **one of two boards** — the
row already said so through `tasks.kind`, but nothing enforced it: a card could be written with no project, and the
Project picker carried a *— No project —* option that meant exactly that. A project's lanes had to be **six named
sub-categories** — V7 had instead given each project Engineering / Procurement / Production and let its manager edit
that set, so two boards on the same instance could have nothing in common and a card could sit on a project with no
lane at all. And **Other tasks is what the account names** — Stage 40 had made the columns management's, and Stage 42
had made writing a card there every account's right, which is the contradiction this sentence settles.

Five decisions were taken before any code moved, four of them because the ask reads two ways. The six are **one
shared list**, and the reader typed it: *Fabrication/Manufacturing, Repair/Maintenance, Site projects, Labor supply,
Goods sales, Other* — the same families the Projects board cuts itself by, shortened to the six work is planned under
(it is not `PROJECT_TYPES`, which has nine entries). A project's own extra lanes are **kept**, printed after the six,
nothing deleted and no card unfiled — the six are a floor, not a ceiling. *— No project —* is **removed and the write
refused**, rather than kept and quietly filed elsewhere. A card can be **re-filed between the two worlds in both
directions, by management**, from one picker in the card's own popup. And **any signed-in account may add a column**
to the Other tasks board, while renaming one, emptying one and the `⋮` that offers both stay management's.

**The six are shared, which is what lets a lane be posted by name.** `DEFAULT_PROJECT_BUCKETS` (domain/Task.kt) is
the list, in print order, and `isStandardLane` is the trim-and-ignore-case test the delete path uses. Three writes
read it: `ProjectService.create` calls `ProjectBucketRepository.seedDefaults` as the project row is made, so a project
born from here has six columns without anybody touching a lane control (Stage 33's rule — the create form asks nothing
about lanes — is untouched); `JsonImporter` does the same for imported projects; and `TaskBoard.kt`'s `laneChoices`
answers from it whenever the page drawing the popup is not a project page. `TaskForm.lanes` is empty off a project
page, so the general board's picker lists the six, and a project page's lists that project's own lanes in board order.
Because the same six names stand on every board, `name="lane"` can carry the lane's **name** rather than its id:
`ProjectBucketRepository.findByName(projectId, posted)` resolves it against the project the form just resolved, and a
name that project does not have is refused the way a borrowed lane id always was. Ids remain the drag route's currency
(`/app/tasks/bucket`) and the hidden `into` on a lane's `+ Add task` line — those are posted from a column that is
already on the page drawing them.

**V10 gives the six to the projects that already existed, and touches nothing else.** Five statements: delete an
original V7 default (Engineering/Procurement/Production) that holds **no** card; collapse a duplicate of one of the
six names to the first row that carries it, again only while empty; insert the six each project lacks; renumber the
six to positions 0–5 **by name**, so a manager's pre-existing *Site projects* keeps both its column and its place;
then number everything else after them **by id**, counting the lower ids on the same board rather than the positions,
so a row's place cannot shift under the statement computing it. The two deliberate omissions are the ones a reader
would otherwise reach for: a lane that holds a card is never deleted (V7 wrote `ON DELETE SET NULL`, so dropping that
row would unfile real work — it keeps its name and stands after the six), and a card with `project_bucket_id IS NULL`
is not filed by guesswork — the Unsorted column is the legacy cards' resting place and stays exactly that.

**Filing a card in a lane is compulsory, and Unsorted stopped being a destination.** `resolve` asks for a lane whenever
the card is on a project and has no lane to keep: a create (`laneRequired = true` from `TaskService.create`), a card
being moved onto a project (`moving && !goesBacklog`), or a card being handed to a different project
(`changingProject`, which also leaves the old board's lane behind rather than filing the card under an id from another
board). Absent, blank and foreign are three separate answers — no `lane` field at all keeps the stored lane (a greyed
select posts nothing, so an owner booking a due date is not made answerable for management's column), a blank one is
always refused, and so is a name not on that project. `moveToLane` refuses `bucketId == 0`, and `kanban.js`'s `laneOf`
returns null for the column marked `data-unfiled`, so the board never highlights a drop the route will bounce: cards
leave Unsorted by drag, none arrive. `removeLane` checks `LaneError.STANDARD` **before** the count-of-columns rule, so
a hand-built POST cannot delete one of the six out from under the cards filed in it. *(**Superseded by Stage 75**: the
six became the three, `LaneError.STANDARD` is deleted, and what protects a board now is `LaneError.LAST_LANE` plus the
card-count guard — a filing rule Stage 75 left exactly as written here.)*

**A card that names neither world is refused, and the picker that used to offer it says so.** *— No project —* is out
of the Project list, and `resolve` answers an empty `projectName` with *"Choose which project this card is on."* The
one blank that can still print is `NO_PROJECT_YET` (*— No project yet —*), which is not an option anybody can choose:
it is what the field says when the row arrived before this rule and has nothing to say, and the save that receives it
refuses. A card's **owner** in that state gets a different sentence — *"This card is on no project yet — ask
management to choose one."* — because the field that would fix it is one they may not write; `updateAssigned` prints
it from the row, not from the form. *(**Stage 111 puts one more blank back, and it is a different kind.** On the general
board's create the Project picker opens on `CHOOSE_A_PROJECT` (*— Choose a project —*), and the Sub-category under it
opens shut with *— Choose a project first —*. Neither is an option that files a card: a popup posted on either is
refused at the field, so the rule above — no card is written against an unanswered project — is exactly what those
placeholders exist to enforce. What Stage 48 retired was *— No project —*, an answer that meant "keep it off both
boards"; Stage 111's blanks mean "not answered yet".)*

**One popup, two halves, and the hidden half is not disabled.** `TaskForm.mayRefile` is `isEdit && canEditAll`: the
*Where it lives* picker (On a project / Other tasks, `id="taskBoard"`, `data-board-toggle`) appears on an edit by
management and nowhere else, and `boardAnswer` / `boardOf` give three answers — the project, the backlog, or "the board
the row is already on" for a form that prints no picker. The two field groups carry `data-board-half="project"` /
`"other"` and `task-modal.js` swaps their `hidden` attributes on the change, so a management popup ships **both**
boards' fields and shows one. The step list, notes, status, assignee and dates ride across — the crossing moves the
card, not the work. That shape is why `maySetColumn` is one right wider than `mayWriteCard`: it already covered a
backlog card's owner (the board has nothing draggable, so the picker stands in for the drag), and Stage 48 adds
`mayRefile`, because a disabled select posts nothing and management would otherwise be able to send a card to the
backlog but never able to say **which column** it lands in — the one thing a card there has to answer. Hiding by CSS
keeps the field posting; disabling it does not, and this was found on the throwaway, not in a test.

**Any account can name a column; only management can rename or empty one.** `TaskService.addBucket` consults neither
`callerEmpNo` nor `canManage` — both stay in the signature so every backlog write reads the role the same way — so
`BucketError.DENIED` can no longer come back from that call; the enum keeps it for the rename and delete routes, which
do check. `OtherTasks.kt`'s bucket popup splits along the same line: the add half and the dashed `+ Add bucket` box
draw for every signed-in account, while `bucketMenuForm` (the `⋮`, the rename field, the move-and-delete form) is
gated on `canManage`. Stage 40's rule that markup must not invite a press the server will bounce still holds — the
server and the page now agree that the press is allowed.

**The Team sheet's assign panel asks the same two questions.** `/app/team/assign` gained a required Project select and
a required Sub-category select over `DEFAULT_PROJECT_BUCKETS`, next to the member, title, dates and priority it already
took: a page that writes a card onto a board has to name that board, and the six being shared is what lets it offer
the lanes without knowing the project's lane ids.

**No CSS, and two scripts.** The hidden half uses the `hidden` attribute and the hint lines reuse the existing
`.field-hint` rule, so `app.css` and `base.css` did not change at all; `task-modal.js` (the board switch) and
`kanban.js` (Unsorted as a non-target) did, and both are served from 8080 matching their source files byte for byte.

**Tests: 432 in 30 classes, 0 failures.** `ProjectBucketMigrationTest` now covers V7 *and* V10 in one file (six
tests), and the two new ordering cases had to be built the way a live database actually is — project inserted before
`migrateTo(ds, "7")`, card filed, then the real `Migrations.migrate` — because a test that inserted the project after
V7 passed for the wrong reason and proved nothing about a lane that survives a migration. Its assertions are the
board's own read: names in `position` order, the three originals that hold cards standing after the six, a custom lane
keeping its place, the card's `project_bucket_id` unchanged, and no second copy of the six on the second boot.
`LessonMigrationTest`'s "newest migration" pin moved off the last row of `flyway_schema_history` (which is V10 now)
onto a count of the V9 rows, so it stays a check that the lesson backfill is not replayed. `OtherTasksRenderTest`'s
Viewer case was rewritten for the widened add: columns named and a column to create present
(`#bucketModal`, `newbucket`, `bucketName`), rename and delete and `⋮` and `bucketMove` absent — and a refused backlog
create had to be given `backlog = true` on the hand-built `TaskForm`, because without the board answer the refusal
came back printed under the *project* half of the form.

**Verified live before deploying, on a throwaway and against the real data.** Port 8095, fresh `%TEMP%` DB with
`PTS_DB_PATH` passed and seeding off, own generated secrets consumed through the environment with the CRLFs stripped,
classpath of `build/classes` + `build/resources` + the dist jars **minus** `murray-pts-2.0.0.jar`. Both roles: the six
lanes on a new project, a create with no lane coming back as *"Choose which part of this project the card is on."*, a
create filed in a named lane landing in that column, a foreign lane name refused, a legacy card's popup printing
*— No project yet —* and its save refusing, a management popup shipping both halves with the hidden one still posting
(the fix above), a card crossing to the backlog and back with its steps and status intact, a drag onto Unsorted
refused on the board and at `/app/tasks/bucket`, an ordinary account adding a column and renaming nothing, and the
Team assign panel's two required picks. Then V10 was dry-run against a **copy** of the live database, metadata only:
schema 9 → 10, 8 projects, lanes 23 → **54** (48 of them the six, plus the three original defaults that still hold
cards and the three columns managers had added), lanes per project 6/6/6/6/6/7/8/9 in position order 0–5 then 6+,
cards 12 unchanged, assigned filed 6 and unfiled 2 **unchanged** — no card lost its lane — and Other-tasks cards
4 in 2 columns untouched. Then: live JVM stopped, `installDist`, restarted on 8080 (PID 16264), `/` 200, the four
scripts and `app.css` served with matching checksums. **Ctrl+F5** after this stage — `kanban.js` and `task-modal.js`
changed and there is no cache-buster in the markup.

**If this is restated.** "Put the six back on the create-project form" is Stage 33's prohibition and this stage did not
lift it — a project starts with the six and its manager adds to them later. "Give each project its own six names
again" is V7's rationale, overturned here on purpose: shared names are the only reason the general board's popup, the
Team assign panel and the Other-tasks page can ask "which part of the project" without first loading a project's lane
ids. "Let the reader pick Unsorted, it is quicker than a refusal" puts the legacy column back into the flow of new
work, which is what the second sentence of the ask ruled out; the column stays as a place cards leave, drawn only while
it holds one (Stage 37). "Keep *— No project —* and file those cards under Other" merges the two boards the ask split
apart: a backlog card belongs to a column and to no project, and a migration cannot know which of the two a legacy row
wanted. "Give the assignee the *Where it lives* picker too" moves work between boards by the person the card happens to
be on, which is the line the title and status already draw. "Disable the hidden half of the popup" is the bug found on
the throwaway: a disabled select posts nothing, so the crossing would land cards in no column. ~~"Let management delete
one of the six from the board" is answered before it is asked — `LaneError.STANDARD`, checked before the count rule~~ —
**Stage 75 retired the protected list**: the opening lanes are three ordinary lanes and one of them can be renamed or
taken off from the board, with `LaneError.LAST_LANE` and the card-count guard as the remaining refusals. "delete the
extra lanes so every board is exactly six" is the one thing the reader explicitly refused. "Give
anybody the `⋮` so they can rename their own column" takes in a shared column the work of everybody else.

<a id="stage-49"></a>

## Stage 49 — Moving a card between columns is the mover's, on both boards (2026-10-19, live, no migration)

**The ask.** *"if non management user click on projects it just shows cards that where the project is related buckets
such as Engineering, Procurement and Production etc, if management clicks on the project management is able to move
cards between buckets such as Engineering to Production. This is the only difference between management and user when
click on the projects. If the user is clicked on assigned tasks system shows cards in status depends on if the tasks
are related to projects. if the tasks are not related to the projects system shows tasks between buckets such as
finance or management etc. Other tasks shows what assigned tasks showing not related to the projects"*

Read against the app, the last three sentences describe what is already built — Assigned tasks is the status board,
the cards with no project are the ones that stand on Other tasks, and that page is a board of the columns the account
named. The first two are the change, and the third of them is the whole stage: *"This is the only difference between
management and user when click on the projects."* One sentence that both narrows the work and asks a question the code
had answered the other way round, because Stage 30 had made a lane move the assignee's right, on the reasoning that a
lane is a place on the board and moving a card to a place is what an owner has always been allowed to do.

Two answers were taken before any code moved. **Who the mover is:** management *and* the account that project names as
its manager — the same right Stage 25 invented for writing the project's first card, not company-wide management
alone. **How far that reaches:** the user chose to move the backlog's columns under the same rule rather than keep the
assignee's **Column** picker, so Stage 42's exception — the picker standing in for a drag this board does not have —
is retired, and a card's column is management's on *both* boards. The one that surprised me was the second: I had
recommended keeping the owner's picker, and it was overruled on the same principle the ask states.

**The rule lives in one call.** `TaskService.moveToLane` now asks `mayAddTasks(cardProjectId, callerEmpNo, canManage)`
instead of `updateStatus`'s "management or the assignee", and it asks it about the **card's project**, which is read
off the row rather than from the posted lane. That is what makes the existing lane-ownership check load-bearing for
permission rather than only for tidiness: the lane must belong to the project the card is on, and the project the lane
belongs to is the project the mover must have a right to write for — so a Viewer who manages A moves A's cards and
cannot move A's neighbour on B, even when both print on a page they can reach. `bucketId == 0` and a foreign or absent
lane are still refused first, in the same order, and nothing else about the method moved.

**The board prints the same rule as an attribute.** `Projects.kt` hands the project's `mayAddTasks` answer to
`taskKanban` a second time as `canMoveCards`, which reaches `taskCard` as `mayDrag`. The card had printed
`draggable="true"` and its click openers from one condition; those are now two — `val draggable = mayDrag ?: opens`
and `opens = canManage || mine`. The split is deliberately tiny, because `kanban.js` picks its drag sources with
`.kcard[draggable="true"]`: withdrawing the server-printed attribute withdraws the drag with **no script change and no
stylesheet change**, and a card that cannot be dragged is simply a card that opens. The owner keeps the click, the
hidden GET form and their own **✓ Done / ↺ Reopen** button, because those say who may write *this card* and this stage
did not touch that.

**The backlog's picker is greyed, not removed — and a greyed `<select>` posts nothing.** `TaskForm.maySetColumn`
narrows from `(showsBacklog || mayRefile) && (mayWriteCard || isEdit)` to `… && canEditAll`, and a second flag appears
beside it. The reason a create needs one is the trap this stage was built around: an ordinary account's backlog card is
pinned to their own column, the field is printed greyed, a disabled select is left out of the POST, and the service
reads an **absent** `column` as "keep the row's column" — which for a card that has no row yet meant `bucket_id` null,
i.e. the card written and then invisible on every column of the board. Hence `columnPinned = isBacklog && !isEdit &&
!canEditAll`: a pinned create offers exactly one option (the column its `+ Add task` line was pressed in) **and**
carries `input(type = hidden, name = "column")` so the answer still travels; an edit keeps the whole board listed and
greyed, because there the absent field is the right answer — the card stays where it is. `updateAssigned` mirrors the
same narrowing on the server: an owner's rebuild now sets `projectLane = null` as well as `columnId = null`, so the
field is refused whether it is posted disabled, posted by hand, or left out.

**What did not move:** the five status columns, the arrows, the assignee, the dates, the priority, the steps and the
notes — the ask calls the difference *"the only difference"*, and every test that pinned one of those still passes
unchanged. No migration (schema stays at v10), no CSS, and none of the five scripts: `kanban.js`, `task-modal.js`,
`app.js`, `bucket-modal.js` and `app.css` were re-verified byte-identical against what 8080 serves.

**Tests: 434 in 30 classes, 0 failures** (two net of the previous 432). Four existing setups had to change hands
rather than be deleted — `TaskServiceTest` and `TaskBoardRenderTest` filed a card in a lane before testing something
else about it, and the new rule bounced their assignee, so those moves are made by `"EMP0001"`, the project's manager
in both harnesses, with the reason in a comment where the call sits. One new case is
`the project's manager moves its cards without being management`, which has to clear the project's *management* flag
first: `ProjectRepository.setManager(projectId(), null)` — **`null`, not an empty string**, because
`manager_emp_no` is a key into the roster and SQLite's foreign keys read `""` as a person who is not in it. The other
is `the drag is the mover's and the click is the owner's`, which pins all three readers on one node: the owner prints
no `draggable` but keeps `data-task-card` and the move button; management and the Viewer-role project manager print
`draggable="true"`; and a mover who does not hold the work drags without being able to open the card. Stage 42's owner
test was renamed and rewritten rather than dropped — `the owner of a backlog card writes their own fields but not its
column` posts a different column and asserts the card is still standing under the one it was in, then posts the same
form as management and watches it move. `OtherTasksRenderTest` pins the markup the narrowing produces: management's
picker is **not** greyed, an owner's is greyed and still lists the whole board under *"Management moves a card between
the columns."*, and a pinned create prints one option, carries the hidden field, and hints *"New cards land under Site
works"*.

**Verified live before deploying, on a throwaway and then on 8080.** Port 8095 on its own `%TEMP%`
database, seeding off and `PTS_DB_PATH` passed — the first boot of it left that variable out and opened the **live**
`data/pts.db`, which was caught by reading the startup line and killed before a single request reached it; 8080
answered 200 and its rows were untouched. Over HTTP, as both roles: a Viewer whom the project names as its manager
drags one of its cards to another lane and it holds after a reload; the card's assignee, not a mover, gets a refusal
from `POST /app/tasks/bucket` and finds the card where it was, while their own click still opens the popup and their
Done button still writes; a backlog card's popup arrives with its **Column** picker greyed, its own column still
selected, and the save that names another one leaves it alone; a card written from a column's `+ Add task` line by that
same account lands in that column rather than on no column. Then the live JVM was stopped, `installDist` rerun, and
8080 restarted (PID **23196**), `/` 200, and `javap -c` on the new jar used to prove the packaged code rather than the
source tree: `moveToLane(int, int, java.lang.String, boolean)` and `TaskForm`'s `columnPinned` / `maySetColumn`
accessors are both in the shipped class. **No Ctrl+F5 list for this stage** — no static asset changed.

**If this is restated.** "Let the assignee move their own card between lanes, they know where the work is" is the
reasoning this stage was created to retire — a lane is the project's structure, and the project's plan is not the card
holder's to redraw. "Same for the backlog column" and "give the owner a read-only Column field with no picker" are the
two answers the user did not take; the field stays printed and greyed so the board is visible to the person whose card
it is. "Make the mover management only, drop the project-manager half" would break the symmetry with the `+ Add task`
lines the same account already has, and with the check that makes a foreign lane id harmless. "Leave the disabled
Column picker as it was and just not read it" is the one that silently loses every backlog card an ordinary account
writes, because a disabled select posts nothing and an absent `column` on a create means nowhere to file it — if this
code is ever "tidied", keep `columnPinned` and its hidden field together. "Hide the picker for the owner" is a third
answer nobody asked for: the same sentence printed for management and never for the person looking at the board reads
as a page that lost its columns.

<a id="stage-50"></a>

## Stage 50 — the Team sheet's Assign panel can write an Other task (2026-10-19, live, no migration)

**The ask.** The **+ Assign a task** panel at the foot of the Team sheet had, since the beginning, written one kind of
card: a task on a project. Stage 48 made that split into the whole model — a card is either filed in one of a
project's six sub-categories **or** it is an Other task — but it left this one screen on the old side of it, so the
only way management could hand somebody a task with no project was to write it from the Other tasks board and then
assign it there. The ask is to let the panel say *which board* the card belongs to, the way the card popup does.

**Two halves, one picker, the same field the popup uses.** The panel grows a *Where it lives* select on the field name
`board` and the same `data-board-toggle` / `data-board-half` markers the card popup carries, so the one script that
swaps a popup's halves swaps this panel's too. The project half keeps its **Project** and **Sub-category** fields; the
backlog half is a single **Column** picker fed by `TaskService.bucketRows()` — the backlog's own columns, not a
project's six lanes, which is why this page can ask the question without knowing any project's lane ids. The panel
opens on *On a project*, which is what it has always written, so nothing an existing bookmark or hand-built POST does
changes shape: `null` and `"project"` are the same answer, and only `"other"` (constant `BOARD_OTHER`) opens the second
half.

**A half that is hidden still posts — so the route reads one answer, not two.** The two halves are put away with
`hidden`, never taken out of the form, which is what lets a browser without JavaScript still submit the whole panel.
That has one consequence worth stating: a field hidden by `hidden` is **still validated by the browser**, so the
**Project** and **Sub-category** pickers lost their `required` when they became the half that is sometimes put away —
a `required` on a box the reader cannot see is a form that can never be submitted. The `required` that matters, on
**Team member** and **Task**, stayed. The rule is not the attribute's to keep anyway: `TaskService.create` refuses a
card that names no project and `createBacklog` refuses a card that names no column, so a hand-built POST gets the same
answer as the visible form.

**The branch is one field, decided before the write.** `/app/team/assign` reads `boardOf(form)` and runs
`TaskService.createBacklog` for the backlog half, `TaskService.create` for the project half — both as management,
because the panel is `if (canManage)` and the route bounces a non-manager to `/app/team` before it reads the form. A
backlog card written here is `kind = OTHER`, filed by its column, and carries the person the roster picker named even
though `createBacklog` pins a *non*-management create to the writer — this route always passes `canManage = true`, so
"hand this work to somebody" stays management's call, as it is everywhere else. A project card from the same panel
still drops the stray `column` the hidden half posts, exactly as the popup's crossings do (Stage 48): a row points at a
lane **or** a column, never both.

**A refusal comes back as this page, at 200, not a redirect.** The panel is not a popup, so a redirect after a
refusal would land on an empty sheet and leave the reason nowhere to print — the reader would retype the whole card to
learn its column was never chosen. So the route calls `respondTeam(session, AssignForm(result.values, result.errors))`
and every field prints its own reason under itself (`field-err`), with the words the reader typed kept and the half the
refusal belongs to shown while the other is put away. The `AssignForm` is new to this file for exactly that: the plain
`TaskDraft()` default is the blank panel, and `values` + field-keyed `errors` is the refused one.

**What did not move:** the roster sheet above the panel, the member popup, the drill-down, the six sub-categories, the
Other tasks board, the card popup. No migration (schema stays at v10). **`task-modal.js` changed** — the board toggle
that used to only swap nodes inside a popup was widened to drive a plain form as well, so a static reload is the whole
of the reader's side (see below).

**Tests: 440 in 30 classes, 0 failures** (6 net of Stage 49's 434). `TeamPageRenderTest` gained four cases on the panel
as markup: it asks the board question on one `data-board-toggle` with the project half's two fields and the backlog's
one column picker, opens on a project with the other half hidden, and — the pin that guards the `required` trap above —
neither **Project** nor **Sub-category** is `required` while **Team member** still is; the column picker lists the
board's own columns and opens on `""`; an empty board says *"The Other tasks board has no columns yet"* rather than
printing a blank box; and a refused assign keeps the typed title and due date, the chosen person and priority, flips to
the *other* half, and prints the reason under the field. `TaskServiceTest` gained the pair the render tests point at:
*a no-project assign files the card by its column and drops the project fields it also posted*, and *a project assign
keeps its lane and takes no column from the panel*.

**Verified live before deploying, on a throwaway and then on 8080.** Port 8095 on its own `%TEMP%` database — a stale
throwaway from the previous run was found holding `8095` and killed by its PID, not through `stop.bat` (which matches
the main class and would have taken 8080 with it). Over HTTP, as both roles: management's `/app/team` prints the panel
with both board answers, the column picker lists the two columns once they exist and opens on *— Choose a column —*; a
`POST /app/team/assign` with `board=other` and a real column redirects and the card shows on `/app/other-tasks` and not
on the assigned board; a blank column and a foreign column id (99) are both refused with the **page** at 200, the title
kept and the *other* half still selected, and neither writes a row. A Viewer created through the Settings panel (after
a `Viewer` position was added first — the position picker is empty on a fresh database until one exists) gets
`/app/team` with no `assignPanel`, no board toggle, no `task-modal.js`, and their `POST /app/team/assign` is bounced to
`/app/team` and writes nothing. Then the live JVM was stopped before `installDist` (PID **23196**), the dist rebuilt,
and 8080 restarted on PID **14116**, `/` 200. `javap` on the shipped jar proves `teamContent(kotlinx.html.DIV,
boolean, java.lang.String, mn.murray.pts.AssignForm)` and `AssignForm.class` are packaged, and the served
`/static/js/task-modal.js` is byte-identical to source and carries the `data-board-toggle` handling. **Ctrl+F5** on
`js/task-modal.js` — no cache-buster, and it is the one asset that changed.

**If this is restated.** "Let ordinary accounts use the Assign panel" was never on the table — the panel is
management's on the sheet and the route bounces everyone else, and widening it would hand every account the right to
file work under another person. "Redirect after a refused assign so the page is a fresh one" is the shape that loses
both the typed words and the reason, which is why the refusal returns the page at 200 instead. "Put `required` back on
the Project and Sub-category pickers now that they are the default half" is the trap the hidden-half design sets: a
`required` field the browser cannot see blocks the submit of the *other* half, so the `required` stays off and the
service is what refuses a card that names no project. "Accept a blank column and leave the card unfilled, the way
Stage 48's legacy rows are" is refused here on purpose: this board prints only cards that name a column, so a card
written without one is work that has gone missing, not work not yet filed. "Give the backlog half its own Project-less
route" is the second POST this stage deliberately did not add — one endpoint reading one field is what keeps the two
boards from drifting apart.

<a id="stage-51"></a>

## Stage 51 — Settings, Access control and Other tasks are management's functions (2026-10-19, live, no migration)

Three sections of the app are a *management job*, not a personal one: the roster sheet behind **Settings**, the
account-rights table behind **Access control**, and the shared backlog board of **Other tasks**. Since Stage 48 the
backlog's create and the bucket box had been widened to every signed-in account, and Settings had become the Team
sheet everybody could open. This stage walks that back: those three are for management (Manager or Admin —
`Authorization.canManage`) alone. A Viewer is the only role below Manager, so in practice "management and admin" is
the whole set and the change is a Viewer exclusion. Two things were deliberately kept personal and are **not** taken
away: a Viewer can still open the *own* Other-task card a notification points them at, and any account can still
reach Settings' change-password form. The agreed enforcement was **hide the links and lock the routes** — a Viewer
loses the three sidebar entries *and* is bounced when they hit the URL directly.
*(**Stage 110** keeps this paragraph's shape and changes its subject: "management" is no longer a rank above a
`Viewer` but whichever roles hold the chip each control is about, so an account may now be given the backlog board and
not Settings, or the People sheet and not the roles table. What survives intact is the second half — hide the link,
lock the route, and the route is the one that decides.)*

**The sidebar** (`DashboardCommon.kt`). `navItems(employeeNumber)` is left whole — all ten items — because the
notification bell resolves a stored item's section back to a path *through that list* (see
`mn.murray.pts.notificationBell`); trimming the list would silently send a Viewer's own backlog-card notification to
`/app/projects`. The filter lives one layer up: `MANAGEMENT_ONLY_NAV = setOf("other-tasks", "access-control",
"settings")`, and `navItemsFor(employeeNumber)` returns the whole list for management and drops those three ids for
everyone else. The sidebar render now iterates `navItemsFor`, so the menu is what moves and the resolver's list never
changes. *(**Superseded by Stage 110** in its named constant and its one-rank answer, not in its arrangement:
`MANAGEMENT_ONLY_NAV` is gone and `NAV_PERMISSIONS` maps each gated id to the chips that open it — forecasts→
*View Forecasts tab*, other-tasks→*Edit any task*/*Manage buckets*, access-control→*Manage employees*/*Manage roles &
permissions*, settings→the three panel chips — so `navItemsFor` now keeps an item when its role holds **any** of that
set. `navItems` is still whole for exactly the reason given above, and the bell still resolves through it.)*

**Access control** (`Application.kt` `GET /app/access-control`). A fresh `if (!canManage) return@get
call.respondRedirect("/app")` sits right after `canManage` is computed, before the roster is read. The POST already
checked `canManage`; only the GET page was open. *(**Superseded by Stage 110**, which turned this page into two
functions with a chip each: the GET lets either *Manage employees* or *Manage roles & permissions* through and the
People sheet's controls appear per chip — picker for the roles one, password/delete/add for the people one — while
`/app/roles/save` and `/app/access-control/role` are the roles chip's alone.)*

**Settings** (`Settings.kt` `settingsContent`, `Application.kt` `respondSettings`). `settingsContent` grows a
required `canManage: Boolean` argument (passed from the session in `respondSettings`). When it is false the whole
Team block — the `Team` heading, the flash, the `+ Add employee` card, the toolbar, `#team-board` and the roster
table — is not drawn, and neither is the employee popup (`#employeeModal`) nor its `employee-modal.js` script (a
landing point for a control that no longer exists). What still prints for a Viewer is their own `accountCard`
(*Your account*) and the **Change Password** form — the personal path that must stay open, and the one `requireSession`
routes must-change-password accounts to via `MUST_CHANGE_ALLOWLIST = {"/app/settings", "/app/settings/password"}`. So
the page is reachable by every role; only its management half is withheld. *(**Stage 99** removed the password form by
the user's decision; a Viewer's page is now the account card alone, and the page stays reachable by every role.)* *(**Stage 123** reopens the page to every account and restores the form: the sidebar link prints again for a Project Manager and an Employee, neither of whom holds a panel chip, and each of them opens Settings to exactly that account card and its change-password form. What Stage 51 keeps is the roster half — only a reader whose role holds *Manage employees* sees the Team block.)*

**Other tasks** (`OtherTasks.kt`, `Application.kt`). The board is management's, but a Viewer keeps the personal path,
so it is *scoped* rather than bounced. `otherColumns(canManage, viewerEmpNo)` filters a non-manager's column list to
the cards whose `assignee` is them, and drops any column that then holds nothing — so a Viewer sees exactly the column
their own card stands in, and none of the rest of the board. The head's show-all toggle, the per-column `+ Add task`
line, the dashed `+ Add bucket` box, the column menu (`⋮`) and the whole bucket popup (`#bucketModal`) are gated on
`canManage`; the shared card popup (`taskFormModal`) stays for every reader because it is where a Viewer's own card
opens, and their card keeps the owner's status arrows. The routes check it again: `respondOtherTasks` draws the toggle
only `if (canManage && otherToggleApplies(...))`; `POST /app/other-buckets` bounces a non-manager to the board before
reading the name; `openingTaskForm` no longer hands a Viewer the `?new=1&column=` create popup; and
`POST /app/assigned-tasks/save` discards a column-carrying create from a non-manager back to their scoped board rather
than saving it. `TaskService.createBacklog` itself stays permissive because the Team assign panel (Stage 50,
management-only) calls it with `canManage = true`.

*(**Stage 90** restored the **self-service half** of that create on the reader's own **Assigned tasks** page, at the
user's request: an always-drawn **Bucket** picker and a `+ Add task` line under the reader's Other board open the blank
popup, and a non-manager's card lands as theirs and *Not started*. What Stage 51 keeps is the **shared** board — its add
line, its popup and its save branch are still `canManage`'s — and the assigned page's **projects** half still grants no
create. See Stage 90.)*

No migration (schema stays V10) and **no static asset changed** — every edit is Kotlin — so there is nothing to
Ctrl+F5. `./gradlew test installDist` is green at **442 tests / 30 classes / 0 failures**, and 8080 restarted on PID
**25188** with `/` 200 and `/app/settings`, `/app/access-control`, `/app/other-tasks` all answering 302 (to login)
rather than erroring. `javap` on the shipped jar proves `navItemsFor`, `getMANAGEMENT_ONLY_NAV`,
`settingsContent(… EmployeeForm, boolean, …)` and `otherColumns(boolean, java.lang.String)` are packaged.

**If this is restated.** "Hide the three links by removing them from `navItems`" breaks the notification bell, which
reads a section back to a path off that very list — filter at `navItemsFor`, the render, never the resolver. "Bounce a
Viewer off `/app/other-tasks` entirely" kills the agreed personal path: their own card arrives through a notification
that points at that URL, so the board is scoped to their own cards, not refused. "Take the change-password form off
Viewer Settings too" strands a must-change-password account — `MUST_CHANGE_ALLOWLIST` sends them here precisely because
it is open to every role; hiding the roster is right, hiding the password box is not. *(**Stage 99** retired the premise
as well as the form: at the user's request the password box and the gate behind it are both gone, so there is no
must-change account left to strand. The rule still holds for any future self-service form: a form a role is *sent* to
must not be hidden from that role. See Stage 99.)* "Let a Viewer still create a
backlog card since Stage 42 made the box universal" is the exception this stage retires: writing a *new* card onto the
shared board is one of the Other-tasks functions management keeps, so the add line, the create popup and the save-route
column branch all check the role now. *(**Stage 90** later gave the reader a create again, but **on the Assigned page**
and for **their own** card only — the shared board's add line stays management's.)* "Lock a page by hiding its link only" is presentation, not the right — the link,
the GET and every POST behind it are gated, because a hidden link still leaves the URL.

<a id="stage-52"></a>

## Stage 52 — A project's board is cut by status, not by sub-category (2026-10-19, live, no migration)

Opening a project (`/app/projects/{id}`) put its cards into columns made of the six sub-categories (V7, Stage 30).
The reader asked for the opposite grouping: *"when click on project tasks should be shown in progress not in sub
category meaning, Not started, in progress, on hold, blocked, or completed."* A project's board is now the same five
**status** columns the general Assigned tasks page draws — `taskKanban` falls to `statusKanban` when it is handed no
lanes, so the whole change is one argument dropped at the call site. `Projects.kt` passes `tasks, session, canManage,
BACK_PROJECT, project.id, canAdd = mayAdd, fixedProject = project` and leaves `lanes` and `canMoveCards` off; the
`lanes` value is still computed, because the popup below uses it.

**What stayed.** This is a display change only. A card still carries its sub-category: `TaskService.lanesOf` fills the
popup's **Sub-category** picker, Stage 48's compulsory filing on create is untouched, and a new card on this page still
lands on its project's first lane — it simply no longer *names the column it sits in*. The board's owner-facing drag
follows the general board now: `taskCard` derives `draggable` from `opens` (`canManage || mine`) when no `mayDrag` is
passed, so an owner walks their own card along the status columns exactly as on the Assigned page, and `kanban.js`
already binds `.kcol[data-status]` to the `/app/tasks/status` write — no JS, no CSS. `laneKanban` and `taskKanban`'s
`lanes`/`canMoveCards` params are **retained**: the lane capability behind the popup picker is real, and the lane-
permission and lane-create tests (`TaskServiceTest`, and `TaskBoardRenderTest`'s direct-helper cases for a lane board)
still exercise the renderer, so pulling it out would be a much wider cut than the reader asked for. So the word
"In progress" is back on the project page, as a column head.

**Tests.** Two project-board cases move onto the new shape, count unchanged. `TaskBoardRenderTest`'s
`a project page is cut by its lanes…` becomes `a project page is cut by status and carries no manager panel`: it
asserts five `.kcol[data-status]` columns, each `TASK_STATUSES` name as a `kcol-title`, and that no `data-lane` column
and no `DEFAULT_PROJECT_BUCKETS` name is a column head (the `/buckets`, `laneName`, `laneArea` negatives stay).
`ProjectsPageRenderTest`'s roll-up case swaps its lane-column count for `assertEquals(TASK_STATUSES.size, occurrences(
html, "class=\"kcol-count\""))` and drops the `data-lane="0"` assertion. **442 tests / 30 classes / 0 failures**
(the same 442 Stage 51 shipped, since existing tests were re-pointed rather than added). No migration (schema stays
V10) and no static asset changed, so nothing to Ctrl+F5. 8080 restarted on PID **640** with `/` 200; both affected
render suites re-run green after the final comment fix.

**If this is restated.** ~~"Revert the project page to lane columns" is the change this stage retires at the user's
request.~~ **Stage 75 reverted it in the other direction**: a project's page is its **bucket** columns again and
permanently, and the five status columns Stage 52 moved there now live only on **Assigned tasks** and **Other tasks**.
What Stage 52 did keep is the separation of the two shapes — `taskKanban` still falls to `statusKanban` when handed no
lanes, which is exactly how the general boards are still drawn.
"Rip out `laneKanban` and the `lanes` param since nothing renders lanes any more" over-reaches: the popup's
Sub-category picker, `lanesOf`, compulsory filing and the lane tests all still read that capability, and removing the
renderer would break them for a display change. "Stop filing cards into sub-categories because the board no longer
shows them" would drop a card's lane and unravel Stage 48 — the field is set, just not the column. ~~Whether the
Sub-category *picker* should also leave the project page's popup is a follow-up the reader has not asked for, so it
stays.~~ **Stage 87 answered it**: the pair left the editor of a card already on a project (both Project and
Sub-category), while create, the backlog half and a refusal keep them. The lane itself is still filed and still read.

<a id="stage-53"></a>

## Stage 53 — The Assigned tasks board is personal for every role (2026-10-19, live, no migration)

The reader's ask: *"on assigned tasks it does not matter if the user clicks or the management click only show
assigned task to the person who clicked."* Until now `/app/assigned-tasks` answered two different questions at once:
a normal account saw only their own cards, but management saw the **whole** board. Stage 53 makes the page ask one
question — *what is on me* — for every reader. Management's overview of the team was never this page's only job, and
it is not lost: the Team sheet's OPEN TASKS drill-down, a project's board and the Calendar all still read
`TaskService.assignedFor` with management's wider view.

**The change is the list, not the rights.** `assignedTasksContent` now calls
`TaskService.assignedFor(session.employeeNumber, canManage = false)` unconditionally, so the `cards` the board is
built from are the viewer's own regardless of role. `canManage` is still handed to `taskKanban` and to
`taskFormModal(state ?: TaskForm(canEditAll = canManage), …)` untouched: a manager standing on their (possibly empty)
personal board keeps `+ New task`, the full editor and the drag. The scope line — `p("board-scope") { "Only the cards
assigned to you are on this board." }` — left its old `if (!canManage)` guard and now prints for management too,
because the sentence is now true of every reader. The service method itself is **not** changed: this is one call site
passing `false`, so the Calendar, project boards and Team drill-down are untouched by construction.

**The sidebar pill follows it.** `visibleTaskCount` in `DashboardCommon.kt` dropped its `Authorization.canManage(...)`
argument for a literal `canManage = false` too, so the number beside *Assigned tasks* is exactly the open cards the
page then prints for that reader — its documented promise is that the pill and the board agree, and the whole board
is personal now. (`Authorization` stays imported and used; `navItemsFor` still reads it for the nav filter.) The two
`taskKanban` controls that could strand a manager on an empty personal board — the `#taskEditOpener` and the empty
invitation — are both driven by `canManage`/`canAdd`, not by the card list, so a manager with zero assigned cards
still gets the `+ New task` opener rather than a dead page.

**Tests.** **443 / 30 classes / 0 failures** (+1 over Stage 52's 442). `AssignedTasksRenderTest` gains
`the board is the viewer's own cards for every role, management included`: a card on EMP0001 and a card on EMP0002,
then the manager's page contains its own and **not** the colleague's, the engineer's vice versa, and the scope line
prints once on each. The two cases that render the *manager's* page with a card (`the page draws exactly one board…`,
`the backlog is not drawn here…`) now assign their card to EMP0001 so it survives the personal filter.
`SidebarTaskCountTest` renames `the pill is scoped to the reader the same way the board is` to
`the pill is the reader's own open cards for every role, management included` and re-pins it: with EMP0002 assigned
two open cards and EMP0001 assigned none, the manager's pill is **0**, not the team total — closing a card moves only
its own owner's number. `TaskServiceTest`'s `management sees the whole board and everybody else only their own cards`
is left **as-is**; it exercises the service's `canManage=true` branch directly, which Stage 53 deliberately keeps for
the other screens. No migration (schema stays V10), no static asset changed, so nothing to Ctrl+F5. 8080 restarted on
PID **10776** with `/` 200 and `/app/assigned-tasks` 302 unauthenticated.

**If this is restated.** "Make the service drop the `canManage` branch" over-reaches — the Calendar, project boards and
Team drill-down depend on management's wider read, and only this page's *call* needed to change. "Strip management's
create/edit/drag so the page is truly personal" is wrong: the ask was who *appears*, not who can *act*, and a manager
must still be able to write a card from here. "Then hide the `board-scope` line from management since they are used to
the whole board" reintroduces the silent two-of-nine confusion the sentence exists to prevent.

<a id="stage-54"></a>

## Stage 54 — The Assigned tasks pill counts Completed cards too (2026-10-19, live, no migration)

The reader's ask: *"make assigned tasks count even completed tasks."* Since Stage 27 the sidebar number beside
*Assigned tasks* had deliberately **excluded** the cards sitting in the Completed column — a menu count that drops as
work finishes, so it always said something about today. Stage 54 retires that exception: the pill now counts **every**
card on the reader's own board, finished ones included, so it answers *how many tasks are assigned to me* and the
number beside the link is exactly the count of cards that reader's board prints. No card is subtracted any more.

**One line in `visibleTaskCount`.** `DashboardCommon.kt` changed `.count { it.isOpen }` to `.size` on the very same
`TaskService.assignedFor(employeeNumber, canManage = false)` list Stage 53 introduced — the personal scope is
untouched, only the filter came off. The pill and the Assigned board, which were "allowed to disagree by exactly the
completed cards", now agree exactly: both are that whole list. No service, no route, no JS, no CSS, no migration.

**What deliberately stays open-only.** `TaskItem.isOpen` is not dead — it still drives the Completed column and its
per-column count on the board, the card's `✓ Done` / `↺ Reopen` move button (`TaskBoard.kt`), and, importantly, the
**Team sheet's OPEN TASKS** figure (`Team.kt`, `EmployeeService.assigned().filter { it.isOpen }`). The reader asked
about the *Assigned tasks* sidebar number, not about what a manager sees as a colleague's outstanding workload, so the
Team column is left counting open cards. `SidebarTaskCountTest` reads the sidebar's `navItems`, never the Team sheet,
so it pins only the pill.

**Tests.** **443 / 30 classes / 0 failures** — the same 443 Stage 53 shipped; `SidebarTaskCountTest`'s five pill cases
were re-pointed onto the new figure, none added or removed: `a finished card leaves the number but stays on the board`
→ `a finished card stays in the number and on the board` (pill holds **3** across the completion, now equal to the
board's 3), `every column but Completed still counts` → `every column including Completed counts` (**5**, not 4),
`the pill is the reader's own open cards…` → `the pill is the reader's own cards…` (EMP0002 stays **2** after closing
one, EMP0001 still 0 — management assigned none), `a reader with nothing left to do reads zero` → `a reader whose only
card is finished still reads one` (**1**, not 0), and `the pill is the reader's own board minus the cards it has
closed` → `the pill is the reader's whole board, closed cards included` (asserts `pill == cards.size`). No migration
(schema stays V10) and no static asset changed, so nothing to Ctrl+F5. 8080 restarted on PID **15748** with `/` 200;
`javap` shows `assignedFor` followed directly by `List.size()` with no `isOpen` filter in the packaged jar.

**If this is restated.** "Revert the pill to open-only" is the Stage 27 behaviour this retires at the reader's
request. "Then make Team OPEN TASKS count Completed too" over-reaches — that column is a manager's read of outstanding
workload and was not what was asked. "Drop `TaskItem.isOpen`" would break the board's Completed column, its move
button and the Team count; the property stays, only this one consumer stopped using it.

<a id="stage-55"></a>

## Stage 55 — The Assigned tasks page also shows the reader's own Other tasks (2026-10-19, live, no migration)

The reader's ask: *"when user click on assigned tasks there should be other tasks shown also, because that person can
have assigned tasks outside of the project… If they don't have assigned tasks just show them you don't have other tasks
assigned."* Stage 47 had moved the whole backlog to its own page and Stage 51 took that page's sidebar link away from
Viewers, so a reader who owned backlog cards but could not reach the Other tasks page could no longer see them at all.
Stage 55 puts a **personal slice** of that board back under the assigned one — the reader's *own* Other-task cards
(`kind = "OTHER"`), not the shared board — and, when they hold none, prints `You don't have any other tasks assigned.`
(a filtered board names its bucket instead — *"You don't have any other tasks in HR."* — since **Stage 88**).
It is deliberately scoped to one person: it reuses the assigned page's personal rule, never management's wider read.

**One new read in the service layer.** `TaskService.assignedOtherFor(employeeNumber)` returns `other().filter { it.assignee
== employeeNumber }` — the backlog mirror of `assignedFor(..., canManage = false)`. It is the single place the personal
backlog rule lives, so the page, its empty message, and the sidebar pill all read through it and cannot disagree.

**A second status board under the first, sharing one popup.** `AssignedTasks.kt` now draws two stacked kanbans: the
`assignedFor` cards (unchanged) and, under an `Other tasks` heading, the `assignedOtherFor` cards cut into the same five
status columns via the existing `taskKanban`/`statusKanban`/`taskCard` path — so `kanban.js`, the move buttons and the
editor all work on them for free. To keep the page's "exactly one `#taskEditOpener`" invariant (which `task-modal.js`
relies on — it binds a single global `[data-task-card]` click handler), `taskKanban` gained a `drawOpener` flag: the top
board suppresses its opener when the reader has own-Other cards, so the lower board draws the one opener instead; a
reader with only backlog cards still gets a working editor. One `#taskModal` serves both boards; a backlog card clicked
anywhere posts `?edit=<id>` to `/app/assigned-tasks`, whose `openableBy` (then `editableBy`) loads either `kind`, and the edit form's
`back = assigned` only steers the redirect, never the row's kind.

*(**Stage 88** added a **Bucket** picker above that second board: it narrows the reader's own Other-task cards to one of
the board's columns while the five status columns stay as they are, and the chosen filter rides `back` as
`assigned:<bucket id>` so opening, saving, stepping, noting, deleting and refusing all return to the same narrow view.
**Stage 89** made that picker one card wide — see Stage 89. **Stage 90** draws it whenever the board has columns, even
when the reader holds no cards, because it is also the chooser for the `+ Add task` line that stage put under the board.)*

**The pill follows the page.** `visibleTaskCount` now sums `assignedFor(empNo, false).size + assignedOtherFor(empNo).size`,
preserving the Stage 54 invariant that the number beside *Assigned tasks* is exactly the cards that page prints — which
now includes the reader's own backlog. No route, no migration (schema stays V10).

**CSS — Ctrl+F5 required.** Three classes were added to `app.css` (`.assigned-other-head`, `.assigned-other-sub`,
`.assigned-other-empty`) for the heading, its one-line subtitle, and the empty message. Unlike Stages 51–54 this stage
changed a static asset, so a hard refresh is needed to see it.

**Tests.** **448 / 30 classes / 0 failures** — Stage 54's 443 plus 5. `AssignedTasksRenderTest`'s
`the shared backlog board is not drawn here in any of its forms` (renamed from the Stage 47 case) now also asserts the
empty message is present while the shared board's `otherBoard`/`bucketModal`/`⋮` markup is still absent, and four new
cases pin the ride-along (own backlog below the projects board, 2×`kcol-head` columns, no other column's name), the
plain empty message, one shared popup + one opener across both boards, and management's own backlog surfacing while a
colleague's never does. *(**Stage 124** supersedes only the no-add-bucket/no-`bucketModal` part for *Manage buckets*:
the empty projects panel offers `+ New bucket` when *Edit any task* also makes it visible, and the shared popup's add
half now lives on Assigned. The shared board and its `⋮` stay absent and the lower cards stay personal; without the
bucket chip the popup stays absent. Stage 124 was verified and deployed 2026-11-06 (726 tests/49 classes, 25 HTTP and 90 JavaScript checks; JavaScript changed → Ctrl+F5).)*
Column counts assert `kcol-head` rather than `data-status="` because a card's move-button arrows
also carry `data-status="` and inflate that token. `SidebarTaskCountTest` adds
`the pill counts the reader's own other tasks alongside their project cards` (own project card + own backlog card = 2; a
further reader with one backlog card = 1; a reader with neither = 0). 8080 restarted on PID **9084** with `/` 200;
`javap` on the packaged jar shows `assignedFor(String, boolean)` followed by `assignedOtherFor(String)`.

**If this is restated.** "Merge the two boards into one" would lose the separate empty message the reader asked for.
"Show management the whole backlog here" over-reaches — this is the reader's *own* cards, and management's wide read of
all backlog lives on the Other tasks page, not here. "Bring the flat backlog list back" is the pre-Stage-47 shape this
replaces with a status board; the shared column structure the reader already knows is the point. *(**Stage 90** added one
`+ Add task` line under this personal board — the reader writing their **own** first card into a bucket — which does not
widen the read: the board still prints only their cards, and the shared board's own create stays management's.)*

<a id="stage-56"></a>

## Stage 56 — A project's page shows the whole project board to every reader (2026-10-20, live, no migration)

The reader's ask: *"every user click on project from projects — what tasks the project has and who is doing — just like
assigned tasks in kanban."* A project is a shared thing, and the question standing on one project page is not *what is
on me* (that is the general Assigned tasks page) but *what work does this project hold, and who holds each card*.
Before this stage a plain Viewer opening a project had seen only their **own** cards on it — which is exactly the wrong
question for a page about a project, and left a colleague's work invisible where the reader most wants to see it.
Stage 56 makes the project board a **whole-project board for every role**: management, that project's manager, and a
plain Viewer all see **all** the cards filed under the project, each carrying its holder's avatar (as-is, no new
label), so *who is doing* reads straight off the card. The board is the same five-status kanban Assigned tasks draws
(Stage 52), just no longer narrowed to one person.

**The list, not the rights.** The change is one line in `Projects.kt`: the card list built with
`TaskService.assignedFor(session.employeeNumber, canManage || mayAdd)` (a Viewer's own slice) becomes
`TaskService.assigned().filter { it.project == project.name }` — every card on the project, for whoever is reading. The
account's *powers* are untouched and still come from the flags handed to the render, not from the list: `canAdd = mayAdd`
stays management-or-project-manager, and per-card `opens = canManage || mine` in `taskCard` stays the gate for the
editor, the click and the drag. So a Viewer standing on a shared board **reads** every colleague's card but cannot open,
move, drag or add one that is not theirs — a colleague's card prints with no `data-task-card`, no `draggable`, and no
move arrows, while their own card on that same board still opens and walks for them. The card popup is now drawn for the
same condition that draws the opener (`canManage || mayAdd || state != null || tasks.any { it is mine }`) rather than on
`tasks.isNotEmpty()`, so a Viewer who can only read others' cards gets no dead form. *(**Stage 86** reversed the
**click** half at the user's request: every card on that shared board now carries `data-task-card`, `tabindex` and
`aria-label`, a colleague's opens as a read-only popup, and the opener/popup conditions drop to *the board carries a
card*. The drag, the arrows and **✓ Done / ↺ Reopen** stay with `canManage || mine`; see the Stage 86 section.)* The empty-line wording moved from
*"nothing assigned to you here yet"* to *"nothing assigned to this project yet"* to match the shared board. `assignedFor`
is **not** changed and keeps its `canManage` branch — only this page stopped using its personal filter; the Calendar,
the general Assigned tasks page and the Team sheet's OPEN TASKS drill-down still scope a non-manager to their own cards
through it. **`taskCard` already printed the holder's avatar unconditionally**, so "who is doing" needed no new code —
widening the list is what surfaced it.

**Tests.** **448 / 30 classes / 0 failures** — the same count Stage 55 shipped, two project-page cases **re-pointed**
(not added or removed). `a viewer's project page shows their own cards and no figures about the rest` → `a viewer's
project page shows every card of the project but edits only their own`: the colleague's cards and their holder (Naran)
are now asserted **present**, while a `data-task-card` count asserts exactly **one** opener (the Viewer's own card).
*(**Stage 86** re-pointed both cases again: every card carries `data-task-card` now, so the count asserts every card,
the colleague's is asserted *read-only* rather than *not openable*, and the popup's one hidden `#taskEditOpener` is
asserted once.)*
`the bar measures the project while the board shows only this reader's cards` → `…while the board shows every card
read-only to a viewer`: the colleague's card title is asserted present and not openable, the project bar still reads 50
(whole-project) — the point that the bar always measured the project while only the board was personal. The empty-line
assertion moved to the new wording. `ProjectsPageRenderTest` + `TaskBoardRenderTest` re-run green; no migration (schema
stays V10) and **no static asset changed** (Kotlin-only), so no Ctrl+F5 is required. 8080 restarted on PID **27700** with
`/` 200.

**If this is restated.** "Re-narrow the project board to the reader's own cards" reverts the Stage 56 scoping this
deliberately lifted for the *project page* — the reader asked to see the whole project and who is on it. "Give management
a separate whole-board project view and keep Viewers personal" recreates the exact split the ask rejects. "Print the
assignee's name on the card, not just the avatar" over-reaches the answer to Q2 (avatar as-is) and would make this page
disagree with Assigned tasks, which prints the same avatar. And the rights must not widen with the list — a Viewer being
able to move a colleague's card from a board they can now see was never asked for and is refused by `opens`/`mayAdd`.
*(**Stage 86** kept that sentence true but moved the line: the *click* is everybody's now, while moving stays
`canManage || mine` (or `mayDrag` on a lane board), so what a Viewer may not do to a colleague's card is move or
write it, not read it.)*

<a id="stage-57"></a>

## Stage 57 — The Other tasks board is cut by status, one group per bucket (2026-10-20, live, no migration)

The reader's ask: *"on other tasks there are tasks categorized by buckets — I want them to show in status same as
project tasks, but I need all categorized bucket names on top, same as project name."* Until now the backlog was a
**horizontal row of bucket columns**: one `.kcol` per bucket, its cards stacked in one nameless stream, each card
printing its own status word on its last line. That read the bucket as the *grouping axis* and buried where the work
had actually got to — the opposite of a project page, whose board is cut by the five statuses (Stage 52) under its
project name. The user wanted the two to agree: the **bucket** becomes the heading (what a project name is on a project
page) and the **status** becomes the column (what it already is everywhere else).

Resolved the one ambiguity ("all categorized bucket names on top") with the user before building: **one status board
per bucket, stacked** — for each bucket print its name as a heading, then a full five-column status kanban of that
bucket's cards, groups running down the page.

**The layout, not the rights.** `OtherTasks.kt`'s `otherTasksContent` board block changed shape only: the old
`div("kanban kanban-other") { id = "otherBoard"; shown.forEach { … one .kcol per bucket … } }` becomes
`div("other-groups") { id = "otherBoard"; shown.forEach { (row, inBucket) → div("bucket-group") { heading + div("kanban")
{ TASK_STATUSES.forEach { status → .kcol[data-status] } } } } }`. Each group's `.bucket-group-head` carries the accent
`dot d-bucket`, the bucket name in `.bucket-group-title`, the card count, and management's `⋮`; under it the same five
`.kcol[data-status]` columns a project and the Assigned board print, each empty one saying *"Nothing here."* — the
`.kcol-empty`-before-cards order `statusKanban` uses, so `.kcol .kcard + .kcol-empty { display:none }` behaves. The
swap target is still the whole board under **one `#otherBoard` id**, so every existing live-swap contract is untouched.
`otherCard` lost its own status word (Stage 57): the column it stands under *is* its status, so printing it again was
the two-places-for-one-figure the project board already gave up — its `.kfoot` now prints only the holder's avatar and
the `kpct` chip, and the dead `.ostatus` CSS rule was removed. The `+ Add task` line moved from per-status-column to
**once per bucket** (a card is created *in the bucket*, its status chosen in the popup); the dashed `+ Add bucket` box
stays the last thing in the run. `kanban.js` needed no change: an owner's `.kmove-btn` arrows POST `/app/tasks/status`
and reload, and the server re-groups by bucket then re-files by status, so several `[data-status]` columns across
stacked groups are safe — and a backlog card carries no `draggable`, so nothing drags between buckets here. `otherColumns`
(Stage 51 scoping), every route, every role check and the popup fields are **byte-for-byte unchanged** — the Viewer is
still shown only the bucket their card stands in, still gets none of `+ Add bucket` / `⋮` / `#bucketModal` / `+ Add task`
/ the show-all toggle; management still sees every bucket. Only the *shape* of a bucket widened into a group of columns.
*(**Superseded in part by Stage 97**, which retired the show-all toggle and its `allbuckets` flag outright; the narrow
rule this stage kept — a board prints the buckets that hold work — is the half that survives, and the Stage 93 picker's
`?show=` is now what reveals an empty one.)*

**CSS.** `app.css` gained `.other-groups` (flex column, `gap:24px`), `.bucket-group-head`, `.bucket-group-title`,
`.bucket-group-count`, and dropped `.kanban-other` and `.ostatus`. A **static asset changed → Ctrl+F5** before judging
the board.

**Tests.** **448 / 30 classes / 0 failures** — the same count Stage 56 shipped, `OtherTasksRenderTest` cases
**re-pointed** rather than added: `the backlog prints a column for every bucket that holds work` →
`each bucket prints its name on top and its cards cut by the five statuses`, now asserting one `.bucket-group-head` per
bucket, the name in `.bucket-group-title`, **five** `kcol-head` nodes carrying every `TASK_STATUSES` word, and no
`ostatus` on the card. The empty-column checks moved from *"No tasks in this bucket yet"* to the per-status *"Nothing
here."*; the create-popup test asserts the `#otherBoard` node is `other-groups`, not `kanban kanban-other`; the wide-
toggle and viewer-scoped cases count `bucket-group-head`. The `allbuckets` round-trip count on the wide board stays 6
(two menus + two bucket add lines + the add-bucket box + one card opener), the same forms by a new grouping. No
migration (schema stays V10). 8080 restarted on PID **25052**, `/` 200, served `app.css` carries the new rules.

**If this is restated.** "Put the buckets back as columns of the row" reverts the Stage 57 ask — the user explicitly
wanted the bucket as a heading and status as the columns. "Cut the backlog by bucket and by status in one two-axis grid"
over-reaches: the ask was one status board *per* bucket, not a matrix. "Give the backlog cards their own status word
back" re-introduces the duplicate the change removed (the column already says it). "Let a card drag between buckets" is
not this stage and not asked — buckets are changed only through the popup's Column picker, management's (Stage 49).
(Stage 58 later gave these cards a `draggable`, but it drags along the bucket's five status columns and never between
buckets, so this prohibition stands.) And the rights must not move with the layout: a Viewer seeing their bucket
as a five-column board is the same scoped single-bucket view, not a widened one.

**Stage 93** later made `#otherBoard` the page's `[data-live-region]` and put a **Bucket** picker above the groups
(`?show=<id>`); the stacking, the group-per-bucket reading and the one-id swap described here are unchanged.

<a id="stage-58"></a>

## Stage 58 — A backlog card drags between its bucket's status columns (2026-10-20, live, no migration)

**The ask.** *"since the cards shown in progress we should be able to move cards in status like not started to in
progress"* — on the Other tasks board, the cards sit in status columns since Stage 57 but could only be moved by the
owner's `←`/`→` arrows or by management's popup Status field; a mouse user had no way to drag one from, say, Not
started to In progress. **Stage 58** makes the backlog card a drag source on its own status row.

**The layout, not the rights.** Stage 57 already gave the backlog columns `data-status`, and `kanban.js` binds its
status-drop listeners to *every* `.kcol[data-status]` globally and only starts a drag on a `.kcard[draggable="true"]` —
so the one missing half was the drag source. (**Stage 94 supersedes the binding, not the behaviour:** "binds to every
`.kcol`" was a `querySelectorAll` at page load, and once Stage 93 made this board a live region a *narrowed* board was
a fresh set of columns with nothing attached; the handler now lives on `document` and finds the column with
`closest('.kcol[data-status]')`. The contract this stage relied on — that a backlog drop is a status write — is
unchanged.) `otherCard` now sets `draggable="true"` on the card for
`canManage || mine`, which is [TaskService.updateStatus]'s rule (`!canManage && task.assignee != callerEmpNo → false`)
read as presentation and the exact flag the Assigned and project status boards already print. Nothing else moves: no
route, service, migration, CSS or script change. A drop posts `/app/tasks/status` and reloads; the server re-groups by
bucket→status, so the card comes back filed in the column it landed in **under the same bucket heading** — a status
move never changes the bucket, which stays the popup's management-only Column picker's answer (Stage 49). There is no
`[data-lane]` column on this board, so a backlog drop can only ever be a status write.

**Tests.** **449 / 30 classes / 0 failures** — one new case, `a backlog card drags between status columns, never a
bucket column`: management's board prints `draggable="true"` on every backlog card *and* on each card's whole-card
opener button (the handle), five `kcol-head` status columns per bucket, and no `data-lane` anywhere so a drop is a status
write, not a re-file. The two existing render cases gained the card-level flag directly — management's card and the
owner's own card each assert `draggable="true"`. Migration stays V10. 8080 restarted on PID **3568**, `/` 200. No
static asset changed (Kotlin only), so **no Ctrl+F5** is needed.

**Stage 58 fix — the drag did not actually start.** The stage shipped the `draggable` flag on the `.kcard` and the
suite passed, but the board the reader saw never dragged. The cause was markup the tests did not cover: a backlog card's
editor opens through a **whole-card overlay** (`openBacklogCard`, Stage 40) — `form.kcard-open { position:absolute;
inset:0 }` with a `button.kcard-open-btn` filling 100%. That button is the topmost node under the pointer, and a
`<button>` is **not a native HTML5 drag origin**, so the mousedown that began on it never fired `dragstart` on the card
behind — the flag was there but unreachable. The fix puts a second `draggable="true"` on the opener button (gated on the
same `canManage || mine`): the drag now starts on the handle, `kanban.js`'s `cardOf()` climbs via
`closest('.kcard[draggable="true"]')` to the card that carries the `data-id`, the drop still posts that card's status, and
a plain click on the same button still submits the form and opens the popup — no JS, no CSS, no route change. The new
test was rewritten to assert **both** halves (the draggable card *and* a draggable opener per card), because a test that
only looked at the `.kcard` tag passed on the broken build. 8080 restarted on PID **23004**, `/` 200, schema V10, still
Kotlin-only.

**If this is restated.** "Make a backlog card draggable to change its *bucket*" is not asked and must be refused —
the drag writes `status` via `.kcol[data-status]` only; bucket changes stay the popup's Column picker, management's
(Stage 49). "Give management standing `←`/`→` arrows now that they drag" would re-add what Stage 49 took away: management
moves by dragging or the popup, the owner by dragging *or* the arrows — and **Stage 103** took the second half of that
sentence too, so today the owner moves by dragging as well, and re-proposing an arrow on any status board is the ask the
user has already answered once. And the drag must mirror the route exactly — a
`Viewer` who is not the assignee gets no `draggable` on a colleague's card; widening the flag past `canManage || mine`
would show a grab-handle the server refuses to move.

<a id="stage-59"></a>

## Stage 59 — The Add bucket control is a small button in the page head (2026-10-20, live, no migration)

**The ask.** *"make add bucket button small and put it on top right."* The `+ Add bucket` opener had been the dashed
full-height box trailing `#otherBoard` as a fake column (Stage 40's picture, kept when Stage 57 turned the board into a
vertical run of bucket groups). Once the board is a column-of-groups stack, a lone column-shaped box stranded at the foot
reads as a placeholder, not an offer — and the reader who wants to add one looks up, at the head, where `Show all buckets`
already sits. **Stage 59** shrinks it and moves it there.

**The layout, not the mechanism.** `addBucketBox` (`kcol addbucket`, drawn last inside `#otherBoard`) became
`addBucketButton` (`opener-form addbucket-form`, drawn into the head's `.page-actions` slot from `respondOtherTasks`, beside
`allBucketsToggle`). It is the *same* GET form onto this page carrying `newbucket=1` and the same `#bucketModal` swap target,
so the create mechanism the bucket route and `bucket-modal.js` drive is untouched — only where the button lives and how big
it is. The button is `.ghost-btn.btn-sm`, exactly the show-all toggle's and the head's `+ New project` shape, so the two head
controls are indistinguishable. `data-live-extra` widened from `#bucketsToggle` to `#bucketsToggle,#bucketModal` so that
pressing it with the popup up redraws both the toggle and the modal, matching the toggle's own companion list.
`allbuckets` still rides when the wide view is on, so the button answers on the board the reader was looking at. Management-only
(Stage 51) and gated like the toggle in `respondOtherTasks`; a `Viewer` gets neither. The `.addbucket`/`.addbucket-btn`
dashed-box CSS and its explanatory comment were retired (the `.dot.d-bucket` rule and its own comment moved up to keep the
bucket heading colour).
*(**Superseded in part by Stage 97**: the head now holds **one** control — `addBucketButton`, `id="addBucketOpener"` —
because `allBucketsToggle` went, so there is no second button to be indistinguishable from, no companion list to widen,
and no `allbuckets` field riding anything. What Stage 59 settled and this stage kept is the button's place: the head, over
the board it changes, not a box trailing it.)*

**Tests.** Suite stays green at **449 / 30 classes / 0 failures**. Two `OtherTasksRenderTest` cases were re-pointed, not
added to. *the add bucket control is a small button in the page head, not a box on the board* (was *the dashed add bucket
box is the last thing in the board's row*) now counts the `opener-form addbucket-form` in the head, asserts its button is
`ghost-btn btn-sm`, that its `newbucket` field and `+ Add bucket` label are present, and — the inverted half — that the board
slice carries no `newbucket` and the page carries no `kcol addbucket` node. *the view the reader is standing on travels with
every write* moved from six to **five** `allbuckets` fields on the wide board (the box left the board), and asserts the head
button carries the field in the wide view and not in the narrow one. The `page` helper now mirrors `respondOtherTasks` by
drawing `addBucketButton(showAll)` for management. Migration stays V10.

**Deploy note.** This touched `app.css` (and `bucket-modal.js`'s header comment), so a **Ctrl+F5** is required to pick up the
asset change — the markup alone would have served the old dashed-box styling from cache. 8080 restarted on PID **11884**,
`/` 200.

**If this is restated.** "Put `+ Add bucket` back at the foot of the board as a column" reverts the ask — the board is a
vertical run of groups (Stage 57), so a column-shaped box there is precisely what was stranded. "Give a `Viewer` the button
now that it sits beside the toggle" is refused: the toggle is management-only too, and naming a column is management's
(Stage 51). And the move must not change the write — the control is still a GET carrying `newbucket=1` that swaps
`#bucketModal`; re-pointing it at a different target or making it a typed field on the page would lose the popup where a
refusal prints its reason.



<a id="stage-60"></a>

## Stage 60 — Management edits the project header as a strip, and can delete the project (2026-10-20, live, V11)

**The ask.** *"when management click on a project from the projects on top should be displaying like this"* — with a
picture of the desired header: a `Projects / M-01248` breadcrumb top-left; `☆ Pin for me` and a `Delete project` button
top-right; then one horizontal strip of UPPERCASE-labelled fields — DESCRIPTION, CLIENT, TYPE, START, DUE, STATUS,
PROJECT MANAGER, REVENUE, INVOICED, PROGRESS. The detail page had been the Stage 29 read-only nine-field card for every
reader. **Stage 60** turns the *top of that page* into an editable strip for management alone.

**The layout, not the rights — and the split is by role, not by field.** Management's copy renders
`div.card-panel.edit-panel` holding a `<form action="/app/projects/{id}" method=post>` whose `.edit-strip` prints one
`.ef` cell per field (caption + control), each error a `.field-err` at its own field (the inline-validation rule, never a
page-top banner). A `Viewer` keeps the **exact** Stage 29 read-only card untouched — same `.detail-grid`, same nine
`.detail-item`s, no form, no strip, no delete. The two branches share the header line: the breadcrumb (`.crumb-row`)
and the pin form are drawn for both; only management additionally gets the `Delete project` button and the editable body.
Both new routes re-check `Authorization.canManage` before touching anything, so the widened *presentation* never widens
the authority.

**INVOICED is the new column (V11); CODE and PROGRESS are deliberately not boxes.** `invoiced REAL NOT NULL DEFAULT 0`
is added by `V11__project_invoiced.sql` (Exposed mirror: `Projects.invoiced`, threaded through `map`/`insert`/`update` and
the `Project` domain type). It is a field management enters **on the detail strip only** — the create popup builds its
boxes field by field and offers no invoiced box, because nothing is billed before the project exists. `ProjectField` grew
`INVOICED` for the strip's save messages. Two fields the strip shows but never lets a person type: the **project number**
(`code`) is not in the form at all (the route never reads one, so a stored number stands — asserted), and **PROGRESS** is a
read-only `.ef.progress-ef` cell, not an input, because it is derived from the board below. That cell keeps `id="project-progress"`,
so the tick-a-step live-swap contract still names it and the figure updates in place.

**The delete takes its cards because the FK would not.** `tasks.project_id` is `ON DELETE SET_NULL`, so relying on the
database would leave every filed card standing with no project. `ProjectRepository.deleteWithTasks(id)` therefore deletes
the project's `Tasks` rows first (their checklist/notes cascade off `task.id`; buckets, assignees, pins and forecasts
cascade off `projects.id`), then the project. `ProjectService.delete(id, canManage)` returns false for a non-manager and
for an id already gone. The button posts `/app/projects/{id}/delete` carrying `data-confirm` — CSP forbids inline
`onclick`, so the confirmation is delegated through `app.js` and the text names the project code and the card loss.
`ProjectService.update` mirrors create's per-field rules (type whitelist, name required, ISO dates + end≥start, manager on
the roster, revenue and invoiced parsed as numbers), keeps `progress` from the existing row, notifies only when the manager
changes hands, and on a refused save writes nothing and reports each field.

**Tests.** Suite green at **458 / 30 classes / 0 failures** (was 449). Three new `ProjectsPageRenderTest` cases —
management's editable header carries the nine fields with `name=`/`action=`/pin/`data-confirm`/`crumb-code` present, no
`name="progress"`, no `.bar` in the management cell, and the copy-link icon present; management edits the manager *in the
strip* not a bottom panel; and a `Viewer` stays the read-only card with no strip or delete. A fourth —
`management's money fields read as comma-grouped text, not bare numbers` — inserts a project at 5,000,000 revenue and
asserts the strip prints `value="5,000,000"` and `value="0"` for the un-invoiced amount. Six new `ProjectServiceTest`
cases — a good save rewrites fields, keeps derived progress and stores invoiced; an edit never touches the code; a refused
save reports each field and writes nothing; a `Viewer` cannot delete; deleting takes the cards; deleting a gone project
says so. Two pre-existing assertions were stale proxies for the markup Stage 60 intentionally changes and were re-pointed,
not weakened: `a create answers the form's own fields` now lists `INVOICED` (the popup renders boxes explicitly and still
offers no invoiced field), and `a project page ... carries no manager panel` guards the *panel* by its unique `/manager`
route and `Set manager` control rather than the bare substring "Project manager", which is now a legitimate `.ef-label`.

**The image-match refinement (the restated "make it just like shown as the image").** The first pass was structurally
right but not visually faithful, so four things changed to make the management header indistinguishable from the picture:
(1) the `.edit-strip` is one **flat, spread row** — borderless controls (`.edit-strip input/select { border:none;
background:transparent }`, keeping only the native select caret and the date calendar glyph), the description cell widened
and the money/progress cells narrowed, so it reads as the screenshot's edge-to-edge line rather than a boxed grid;
(2) **REVENUE and INVOICED are text inputs carrying a comma-grouped value** ("5,000,000", "0"), not `type=number` boxes —
safe because `ProjectService` strips commas on the way in; (3) **PROGRESS is a bare percentage**, the `.bar` div removed
from the management cell (the `id="project-progress"` and the `.pct` span the live-swap and the tests read are kept — the
Viewer's card still shows its bar); (4) the screenshot's small **icon button** between the pin and the delete is now
present as a **copy-link** action — `.icon-btn.head-copy` (📋, `aria-label`/`title`), a plain `button` (never a submit)
answered by a delegated `[data-copy-link]` handler in `app.js` that copies `location.href` and flashes a ✓, because the CSP
forbids an inline `onclick` and the button has no write of its own.

**Deploy note.** This added **V11** and changed `app.css` **and `app.js`**, so a **Ctrl+F5** is required. The refinement
changed `app.css` + `app.js` again (no new migration). 8080 restarted on PID **6324** (PID 27984 stopped first for
`installDist`), `/` 200, real log: *"Successfully applied 1 migration to schema main, now at version v11"*.

**If this is restated.** "Make the viewer's header editable too" reverts the role split — the ask named *management*; a
Viewer keeps the read-only card. "Put an invoiced box in the New project popup" is refused: it is entered after the row
exists, and the pinned enum test guards exactly that. "Let a save move the project number or type a progress percentage"
is refused — code is immutable, progress is derived. "Delete should let the FK cascade the tasks" is the bug, not the fix
(SET_NULL orphans them); cards go explicitly. The icon button is now the **copy-link** action; if the picture meant a
different function, change its handler and glyph, do not remove the button again (a restated UI ask means make it
indistinguishable from the reference, which includes the button). Keep the management strip a **single flat row** — the
boxed/wrapping grid and the management progress *bar* are what the user rejected on restatement. (The Viewer's Stage 29
card is unchanged: its `.detail-pair` bar stays.)

<a id="stage-61"></a>

## Stage 61 — Settings gains three management panels: project types, email notifications, bucket defaults (2026-10-20, live, V12)

**The ask.** *"add this on bottom of the settings"* — a picture of three panels below the existing account settings: a
**Project types** list, an **Email notifications** panel with a *Send test email* action, and a **Bucket defaults** panel
with a category picker, a read-only "always on" row and an add/remove area. Scope was resolved with the user before
building: keep the **existing SMTP mailer** (not EmailJS) and only add a UI for it; make **Project types read-only**
(no add/rename/delete — the nine categories are code-defined); make **Bucket defaults a real per-category catalogue**
that a new project seeds from.

**The layout, not the rights — all three panels are management's, and the email *send* is admin's.** `settingsContent`
gained a `canManage` and a `canAdmin` parameter; the three panels print only `if (canManage)`. The one control that makes
an outbound connection — *Send test email to myself* — is additionally gated on `canAdmin` **and** on the reader having an
address on file, exactly matching the route, which re-checks `Authorization.canAdmin` and redirects a non-admin away. A
Viewer's Settings stays the account + password page with none of these panels. This is the same split-by-role rule as
Stage 60: widening the presentation never widens the authority.

**Project types is a read-only mirror of the code.** `projectTypesPanel()` prints one `.type-row` per `PROJECT_TYPES`
entry — its letter (`.type-letter`, `typeLetter`), a coloured name chip, and `projectCountByType` ("no projects yet" /
"1 project" / "N projects"). It writes nothing and offers no control; the categories and their letters live in the domain
(`PROJECT_TYPES`, `typeLetter`), so a Settings edit could only desync the board from the create form.

**Email notifications reports the mailer's real state; the test writes no ledger.** `emailPanel` shows the current
`DueDateAlertService.currentMode()` (`.email-mode`) and the reader's own `emailFor(empNo)` with an explicit "(no email
on file … set one on the Team sheet)" fallback. The button POSTs `/app/settings/email-test`, which runs
`runDeliveryTest(to)` on `Dispatchers.IO` and maps the result to a flash status (`MAIL_SENT` / `MAIL_LOGGED` when dry-run
/ `MAIL_FAILED` + error detail; `MAIL_NO_ADDRESS` when the account has none). The recipient is always the caller's own
address — it cannot mail a colleague — and a delivery test writes no alert-ledger row, so it cannot spend the day's
alert budget. With `PTS_SMTP_*` unset the mailer is in dry-run (log only), so this is safe to press against production.

**Bucket defaults are additive, and a catalogue edit never touches a live board.** New table `type_default_buckets`
(**V12**: `id, type, name, position`) is seeded by nothing — so today's behaviour is unchanged until management adds a
default. `BucketDefaultsService.add` refuses a name that is blank, over `PROJECT_BUCKET_MAX`, a duplicate of this
category's set (trimmed, ignore-case), or **one of the six standard lanes** (`isStandardLane`) — a card is filed in a
standard lane or it is not on a project (Stage 48), so offering a standard lane as an *extra* would put one column on a
board twice. `ProjectService.create` calls `TypeDefaultBucketRepository.seedExtras(id, type)` right after the six are
seeded, copying each default's name and position onto `ProjectBuckets`. A write here shapes the *next* project of that
category only; it adds, renames or removes nothing on a board that already exists, and never re-files a card. *(**Stage 98**
rewrote the first half of that sentence: an add now puts the column on every existing board of the category as well as on
every future one, and a new project opens with the category's whole catalogue rather than an opt-in tick. Only the
*removal* still stops at the catalogue, and nothing still re-files a card. Read 98 for the live rule.)* The panel
(`bucketDefaultsPanel`) prints the six as the always-on floor (`.lane-chip`), a `data-autosubmit` category picker carrying
`?lanes=`, the category's current extras with a per-lane `buckets/delete` form, and a `buckets/add` form; both routes carry
`?lanes=` back so the picker stays on the category just edited. *(Stage 75 cut the floor to the three phases; **Stage 82**
moved those three into the list's first rows, so `.lane-chip` no longer dresses this panel at all.)*

**Tests.** Suite green at **478 / 32 classes / 0 failures** (was 458 / 30). `SettingsRenderTest` grew seven cases — the
three panels are management's not a Viewer's; the types panel prints the nine categories with letters and counts; the
email panel reports mode + address for management; only an admin *with an address* gets the test button and it is a live
swap (`data-live-target`); the bucket card shows the six floor lanes and the add/remove controls; a seeded default renders
with its own remove form; the panels use the classes the stylesheet styles. Two pre-existing Settings assertions were
re-pointed, not weakened — the management page now prints **six** `.set-section`s (was three) and the `data-autosubmit`
count is scoped to the toolbar (the bucket category picker is a second autosubmit control). `ProjectServiceTest` grew three
create-seeding cases (six-then-extras numbered from 6; a no-default category gets exactly the six; a default added after a
project exists does not reach that board). New `BucketDefaultsServiceTest` covers add/read-back, the non-manager refusal,
the standard-lane refusal (all six + case-insensitive/padded), blank/too-long/duplicate/other-category-reuse, the `typeFor`
fallback and the remove lifecycle. New `TypeDefaultBucketMigrationTest` pins that V12 creates an empty four-column table,
that an existing project's six lanes are untouched by the re-migrate, and that the catalogue is not replayed on reboot.

**Deploy note.** This added **V12** and changed `app.css` (`.lane-chip`, `.type-letter/.type-count/.lane-name`,
`.email-mode`), so a **Ctrl+F5** is required. Live 8080 restarted on PID **24804** (PID 26872 stopped first for
`installDist`); real log: *"Current version of schema main: 11 … Migrating to version 12 - type default buckets …
now at version v12"*, `/` 200, served `app.css` carries the new rules. Verified end-to-end on a throwaway (port 8095,
seed off, temp DB): add → list → standard-lane refusal → remove → admin email-test (MAIL_LOGGED dry-run) all behave, and a
created Fabrication project's board reads the six lanes 0–5 then `Commissioning` at position 6.

**If this is restated.** ~~"Add a new project category from Settings" is out — the nine types are code-defined; a catalogue
edit there would desync the board from the create form, so the panel is read-only by design.~~ **Superseded by Stage 63**:
the categories moved to a `project_types` table (V14) and the panel edits them for real, with the desync that prohibition was
guarding against solved by having the repository republish the domain's view of the catalogue on every read. "Wire EmailJS / a
browser-sent mail" was declined in favour of the existing SMTP mailer. "Send the test to a colleague / another address"
is refused — the recipient is the caller's own address and it is admin-gated. "Let Bucket defaults edit the six, or push a
new default onto a live board" is refused: the six are the filing floor and are not editable, and a catalogue row shapes
only the *next* project.

<a id="stage-62"></a>

## Stage 62 — A super-admin master switch gates every outbound email (2026-10-20, live, V13)

**The ask.** *"make system able to send email from pts@murrays.mn when only super admin can send email."* The sender is
already `pts@murrays.mn` (`PTS_SMTP_USERNAME`/`PTS_SMTP_FROM`), so the ask resolved to a control, not a config change:
a persisted, **super-admin-only master switch** that must be ON before *any* message leaves the box. While it is OFF —
the default — the scheduled due-date sweep, notification mail, and the Settings delivery test alike stay log-only. Real
delivery on a live host additionally still requires the operator's `.env` (`PTS_SMTP_PASSWORD` set, `PTS_SMTP_DRY_RUN=false`);
the switch is an in-app gate layered on top of that, and neither loosens the other.

**The gate is one choke point, and it only ever tightens.** `Mailer.live` — the single property every send path reads — became
`AppConfig.smtpConfigured && MailSwitch.enabled()`. Because `&&` short-circuits, the DB read is only reached once SMTP is
actually configured, and dry-run / no-host still short-circuit ahead of the switch, so a fresh database (switch off, no row)
behaves exactly as it did before V13. Scheduler, `NotificationService.mail`, `runDeliveryTest` and `/app/alerts/test` all route
through `Mailer`, so one gate covers them all — there is no second send path to lock. `DueDateAlertService.currentMode()` and the
sweep's own `mode()` gained a distinct state, *"switched off on Settings (a super admin must enable sending)"*, so the log and the
Settings panel name the real reason mail is quiet rather than blaming dry-run when the switch is the cause.

**The switch is an app setting, not a deployment secret.** New table `app_settings` (**V13**: `key TEXT PRIMARY KEY, value TEXT`)
is seeded by nothing, so the gate reads OFF out of the box — V13 alone cannot arm the mailer. `MailSwitch` owns the one key,
`mail_send_enabled`, and treats only the exact string `"true"` as ON; `setEnabled(on, canAdmin)` refuses a non-admin and writes
nothing. It is read live (no cache) so the panel, the mode line and the mailer never disagree, and across tests never leak state.
The SMTP password deliberately does *not* live here — secrets stay in `.env`, reachable only to the operator; `app_settings` holds
only switches the app itself both reads and writes.

**Layout, not the rights — management sees the state, only an admin moves it.** `emailPanel` prints a read-only "Outbound mail"
line (`Enabled — PTS can send` / `Switched off — PTS will not send`) for management, and the *Enable sending* / *Switch sending off*
form only `if (canAdmin)` — the same split-by-role rule as Stage 60/61: a Manager reads the setting but cannot change it, and the
`POST /app/settings/mail-switch` route re-checks `Authorization.canAdmin` rather than trusting the button, redirecting a refused
write to `?mail=MAIL_REFUSED`. The press swaps `#emailBoard` in place (`data-live-target`) and answers with a `MAIL_ENABLED` /
`MAIL_DISABLED` flash, not a navigation. A Viewer's Settings shows none of it.

**Tests.** Suite green at **486 / 34 classes / 0 failures** (was 478 / 32). New `MailSwitchTest` pins that an unset key reads OFF,
that only an admin can enable and the value round-trips, and that only the exact string `"true"` reads ON (TRUE/1/on/yes all stay
off). New `AppSettingsMigrationTest` pins that V13 creates an empty two-column store and that a value an admin set survives a
second `Migrations.migrate` (not replayed or doubled). `SettingsRenderTest` grew three cases: the "Outbound mail" line prints for
management but only an admin gets the toggle form; enabling it flips the label, the button word and the hidden `on` value; and the
`MAIL_ENABLED`/`MAIL_DISABLED`/`MAIL_REFUSED` flash lines render. The Stage 61 invariants still hold — the management page is still
six `.set-section`s (the switch lives *inside* the email card, not a new section) and the page stays at one form of nesting depth.

**Deploy note.** This added **V13** and touched only Kotlin — `app.css`/`app.js` unchanged, so **no Ctrl+F5 is required**. Verified
on a throwaway (port 8095, seed off, temp DB) with SMTP *fully configured and dry-run off* (`PTS_SMTP_HOST=mail.invalid`,
`PTS_SMTP_DRY_RUN=false`): the app boots through V13, the scheduler reports *"switched off on Settings"* even so, and the round-trip
behaves — admin `POST mail-switch on=true` → `?mail=MAIL_ENABLED`, the page reads *"Enabled — PTS can send"*, `currentMode()` flips
to *"email via mail.invalid:2525"* (`Mailer.live` true, DB-driven), and `off` returns it to the gate; an unauthenticated `POST` is
bounced to `/` and writes nothing. Live 8080 restarted (8080 free, `installDist` rebuilt with Stage 62 classes); real log: *"Current
version of schema main: 12 … Migrating to version 13 - app settings … now at version v13"*, `/` 200, scheduler line
*"dry run (log only)"* — correct, because on live `PTS_SMTP_DRY_RUN=true` short-circuits ahead of the switch.

**If this is restated.** "Make the switch an `.env` value" is out — a deployment secret the app can read but not write cannot back a
button a super admin presses in the browser; that is exactly what `app_settings` is for. "Let a Manager (not just a Super Admin)
arm mail" was declined — the ask names the super admin, and the route enforces `canAdmin`. "Turning the switch on should force real
sending" is refused — the gate is strictly additive-tightening: dry-run and an unconfigured host still win, so arming it on a host
that is not configured does nothing but log. "Cache the switch for speed" is declined — it is read once per send and once per page,
and a live read is what keeps the panel, the mode line and the mailer agreeing.

<a id="stage-63"></a>

## Stage 63 — The project categories come off the table, and Settings' three panels become the picture (2026-10-20, live, V14)

**The ask.** *"make settings bottom and interface like this"* — a picture of the three panels drawn as a bordered list
per row (round letter badge, name, pencil and bin at the right edge), an add row of *Code, e.g. P* + *Description, e.g.
Parts supply* + a green `+ Add type`, a *Project bucket settings* card whose extras read as coloured dot + name + bin, and
an email card whose switch is a tick box with a *Save settings* button under it. The picture left two things open and the
user resolved them before anything was built: the types panel should **really edit** the categories (Stage 61's read-only
mirror is superseded — see its own *If this is restated*), and the mail half stays **SMTP**, restyled rather than rewired
to the picture's EmailJS keys.

**The catalogue is now a table, and the domain reads it without either layer knowing.** `project_types(code, name, position)`
(**V14**) holds what nine strings compiled into `domain/Project.kt` used to hold. The 35 call sites that ask for
`PROJECT_TYPES` / `TYPE_PREFIX` were not touched: both became **properties with custom getters** over a `@Volatile`
`TypeCatalogue` snapshot, and `ProjectTypeRepository.all()` republishes that snapshot on every read (`installProjectTypes`),
so the board's grouping, the chip colour, the Forecasts tabs and the create picker all follow the table with no lookup and no
second source of truth. `SHIPPED_PROJECT_TYPES` remains as the fallback — `installProjectTypes(emptyList())` reinstates the
nine — which is what keeps a database-less unit test from reading an empty catalogue and a half-built board.

**V14 is the first seed-mandatory migration, on purpose.** V12 and V13 create empty tables so nothing changes until somebody
chooses; an empty `project_types` would leave the create-project form with no category to file under, so V14 inserts the same
nine, in the same order, with the same letters. A database that has just been migrated behaves precisely as it did a minute
earlier — verified by `ProjectTypeMigrationTest` reading the rows back, and by the throwaway boot logging *"Project categories:
9 loaded from the catalogue."*

**A letter is forever; a label travels.** `code` is the PRIMARY KEY and the first token of every project number (`S-01`), so
it is issued once, never reused and never editable — `ProjectTypeService.add` takes it as one character `A-Z` (a letter outside
that range would hand out numbers the create form's own validator refuses), and `rename` accepts a new **label** only. Because
`projects.type` and `type_default_buckets.type` store the label rather than the letter, `ProjectTypeRepository.rename` carries
the old words across all three tables **in one transaction** and returns how many projects moved — which is the number the
answer line prints, because that is what a rename actually costs. A rename that touched only the catalogue would drop every
project filed under the old words out of its panel on the Projects board and stop counting its money on the Forecasts sheet.
`remove` is the one place the app refuses: while any project still holds the label it answers with a count and an instruction
("Move them to another category first, then retire it") rather than orphaning a portfolio.

**Layout, not rights — unchanged from Stage 61, and the panel still prints nothing the route would bounce.** All three panels
stay `if (canManage)`; the three new routes (`/app/settings/types/add|rename|delete`) each `requireSession()` and re-check
`Authorization.canManage`, and the service refuses a third time. The mail switch is the one control an **admin** alone may
move, and the checkbox shape did not change that: the box carries the state, the button is the no-JavaScript path to the same
write, an absent `on` parameter means off, and a Manager still gets only the read-only "Outbound mail" line.

**What the picture changed about the answers.** Every types write replies by swapping `#projectTypes` in place
(`data-live-target` on all five forms — pencil, bin, rename, cancel, add), so the Team sheet above never moves and the reader's
scroll position is kept. The pencil is a GET back to `/app/settings?edittype=<label>` that re-renders the card with that one
row open as a field — no popup for a single input. A refusal comes back as the card's own flash line, and `PasswordStatus`
gained `TYPE_ADDED` / `TYPE_RENAMED` / `TYPE_REMOVED` / `TYPE_REFUSED` whose `message(detail)` prints the service's sentence
beside the generic heading when there is one (`?cat=` + `?caterr=`, URL-encoded so a sentence with spaces round-trips). The
bucket panel's extras became the picture's dot + name (`bucket-dot dot-N`, colour by position cycled over six swatches) and
its add line asks for a name only; the six standard lanes still print as the `.lane-chip` floor, because Stage 48's filing
rule is not editable.

**Tests.** Suite green at **501 / 36 classes / 0 failures** (was 486 / 34). New `ProjectTypeServiceTest` (8) drives the rules
against a real temporary database: the nine read back in shipped order with the domain's views agreeing; add upper-cases the
letter and the new category is immediately usable (`codeMatchesType("P-01", …)`); eight refusal tuples each writing nothing; a
rename carrying `projects.type` and `type_default_buckets.type` while `S-01` keeps its number; retirement refused while a
project exists and allowed after it is deleted; retirement taking its default lanes with it; the empty-catalogue fallback. New
`ProjectTypeMigrationTest` (2) pins the seed — nine rows, distinct letters, positions 0–8 — and that a value management changed
is not replayed on the next boot. `SettingsRenderTest` grew five cases (every row offers both writes and each answers the card
alone; the add line's two bounded fields; the pencil opening exactly one row and leaving the letter a badge; a refusal printing
heading + reason; an extra lane as dot + name with a name-only add line) and had six re-pointed, not weakened: page-wide ✎/
counts are now scoped to the sheet via a `sheet()` helper (a category row reuses the same shared button classes, so a page-wide
count stopped being a count of roster rows), the panel order test reads *Project types → Project bucket settings → Email
notifications*, and the mail-switch read-back now asserts the checkbox's `checked` state instead of the words it replaced.

**Deploy note.** This added **V14** and changed `app.css` (the `.type-row`/`.type-add`/`.lane-row`/`.bucket-dot`/`.check-row`
rules), so a **Ctrl+F5** is required. Verified end-to-end on a throwaway (port 8095, seed off, temp DB, all 14 migrations):
add `P` → `?cat=TYPE_ADDED&caterr=P — Parts supply is now one of the categories…`; the same letter again, a two-character and
a digit letter, a blank name and a case-swapped duplicate name each refused with the service's sentence and nothing written;
`P` renamed with a live project under it → *"1 project now reads as Parts supply"*, the board's `?types=` panel printing the
project and its number still `P-01`; retiring `P` refused with *"1 project is filed under…"*, retiring the empty `T` accepted
and its tab gone from Forecasts a request later; a default lane added to the new category landing on the *next* project's board
and on no existing one; the pencil printing one `editing` row and nine plain ones; the mail checkbox arming (`MAIL_ENABLED`,
`checked="checked"`) and disarming; and an unauthenticated `POST` to `/app/settings/types/add` bounced to `/` with the list
unchanged.

**If this is restated.** "Make the letter editable" is out — it is the primary key and the head of every project number already
issued, so changing one would renumber a portfolio's history; the label is what renames. "Delete a category that still has
projects" is refused by design (see above). "Print the types panel to a Viewer / let a Manager arm mail" is refused — the gates
are unchanged from Stage 61/62 and re-checked on every route. "Copy the picture's EmailJS PUBLIC KEY / SERVICE ID / TEMPLATE ID
fields" was declined in favour of the SMTP config in `data/.env`: a password typed into a web form is a password written into a
database. ~~"Drop the six lanes from the bucket panel because the picture has no floor row" is refused — those six are Stage 48's
filing floor, and a board without them cannot file a card.~~ **Superseded by Stage 75**, which did exactly that: the six are
gone and the three phases that open a board are *an opening set, not a floor* — a board without a lane is refused by
`LaneError.LAST_LANE`, not by a protected list. What still stands here is the panel's shape: the opening lanes print as
a read-only line, because renaming or removing one is the project's own page's business and never this catalogue's.
**Stage 82 moved that line into the list's first three rows** — a dot, a name and *"Always on every board"* — so it is
still read-only, and no longer a chip strip of its own.
"Sort the panel by the catalogue's own order" was declined in favour
of the letters: a reader hunting the S knows the letter, not the position somebody added the row at.




`JsonImporter` runs once at startup if `users` table is empty AND `data/pts-data.json` exists:

1. Read JSON via existing `PtsData` kotlinx-serialization classes (kept in `db/migration/legacy/`).
2. Replay the 11 `migrate()` steps in-memory so the importer sees normalized data.
3. Insert in FK-safe order: positions → team_members → users (hash plaintext passwords) → projects → project_assignees → tasks → buckets → forecasts → lessons → todo_lists → todo_items → pins.
4. Rename `data/pts-data.json` → `data/pts-data.json.migrated-<timestamp>`.
5. Log a summary: counts per table, any skipped rows.

If the JSON is corrupt, the importer logs and falls back to `DemoData.seed()` (same as today) rather than silently overwriting.

<a id="stage-64"></a>

## Stage 64 — The Calendar is the reader's own month, for every role (2026-10-21, live, no migration, Kotlin-only)

**The ask.** "on calendar should be showing tasks for only who logged in." One line, and it exposed a divergence
between what the README had claimed for two stages and what `Calendar.kt` actually did. The docs listed the Calendar
among the views "personal for every reader"; the page called
`TaskService.assignedFor(session.employeeNumber, canManage)`, and `canManage` in that function means *hand back the
whole board* — so a `Manager` or `Admin` opening `/app/calendar` got every card in the company as bars, while a
`Viewer` got only their own. The calendar was the last task view still running the pre-Stage-53 rule.

**The change, and why it is one line.** The page now passes `canManage = false`, the same call the Assigned board
has made since Stage 53 and the same one the sidebar count makes. Deliberately the *caller* decides: `assignedFor`
keeps both behaviours because the Team sheet's per-person drill-down genuinely needs the unfiltered list, and the
rule for who sees which card stays in one function rather than being re-derived per page. Nothing else about the
rule moves — this is the **list**, not the **rights**: no control, editor or route changed, and a manager keeps every
way they had of reaching a colleague's work (the Team sheet, the project page, which Stage 56 already shows whole).
The captions follow the scope rather than the reader: the line under the title and the heading of the list under the
grid say the same thing to a `Manager` as to a `Viewer`, and the `Authorization.canManage` call the page made for
that wording is gone with the branch it fed.

**What a bar is, unchanged.** A card becomes a bar only when it carries *both* a start and a due date that parse and
`start <= due`; the packing loop still gives each card the first free row so overlapping spans sit side by side, and
the title prints once, on the start day. A card with no dates is not a bar but still belongs in the list underneath,
which is drawn from the same personal slice — the two halves of the page cannot now disagree about whose month it is.
Cards with nobody on them belong to nobody's month: the filter is on `assignee == employeeNumber`, so an unassigned
card simply stops appearing (it had only ever appeared for management).

**Tests.** Suite green at **507 / 37 classes / 0 failures** (was 501 / 36). New `CalendarRenderTest` — the calendar's
first test file — is built so the absence of a card means something: it seeds a real `Manager` account and asserts
`Authorization.canManage` *before* asserting anything about the page, then pins their own card drawn across each day
of its span, a colleague's card absent, the unassigned card absent from every page, the two roles reading the same
scope sentence with the old team-wide wording asserted gone, an undated card listed but not barred, and a reader with
nothing of their own keeping the grid and reading "No assigned tasks yet."

**Verified on the throwaway, not on live data.** The shipped distribution booted on port 8095 against a temp
database (`PTS_SEED_DEMO=false`, `PTS_DB_PATH` under `%TEMP%`, a disposable bootstrap password), four dated cards
were inserted with a one-off JDBC program against the running WAL database — one for the super admin `EMP0000`, two
for other members, one unassigned — and the admin's `GET /app/calendar` after a real `POST /login` carried only
their own: four `calendar-task-bar` cells for the one card that spans four days, zero mentions of the two colleagues'
cards or the unassigned one, and no `All Assigned Tasks Summary` / "View all assigned tasks" wording anywhere. The
live instance was then stopped, rebuilt and restarted (no migration; schema stays at **V14**) and the same page will
now show each person their own month. Kotlin-only: `app.css` and `app.js` are untouched, so **no Ctrl+F5 is needed**.

One deployment note that has nothing to do with the feature and everything to do with how this instance gets started: the
first 8080 boot after the rebuild died silently, because `run.bat` was launched from a backgrounded tool task and the JVM
it spawns through `start "MURRAY PTS" /min cmd /c java …` belongs to that task's process tree, which was torn down when
the task finished. `logs/pts.log` is the tell — the last line is a routine scheduler sweep, not a shutdown, and the port
goes connection-refused. The instance was relaunched detached (a `Start-Process cmd.exe '/c',deploy\run.bat` outside the
calling shell) and answers on 8080 again, same distribution jar, so the `javap` proof above still describes what is
running. This is a harness hazard, not an operator one: `deploy\run.bat` from a normal console window is unaffected.

**If this is restated.** Two readings the current wording does not cover, and both would be separate asks: drawing the
reader's **Other-task** cards on the calendar too (the Assigned page has shown them since Stage 55, the calendar has
never read `assignedOtherFor`, and the sidebar pill counts both lists while this page counts one), and a management
*option* to see the team's month behind a control. Do not re-introduce the unconditional team-wide month; that is
what this stage removed.

<a id="stage-65"></a>

## Stage 65 — Any month the reader names, swapped in place (2026-10-21, live, no migration, Kotlin + CSS)

**The ask.** "on calendar should be able choose all the months." Two readings were possible — a chooser that reaches
*any single month*, and a view of *all twelve at once*. The first was built: the page had been hard-pinned to
`LocalDate.now()` with no control on it at all, so the missing thing was a way to leave the current month and come
back, not a second calendar. The year-at-a-glance grid is a different page and is left un-built; if that is what was
meant, it is one word away.

**The rule moved to the domain, once.** `domain/Forecast.kt` already owned the month vocabulary a page needs — the
`^\d{4}-(0[1-9]|1[0-2])$` key, `isValidMonthKey`, `shiftMonth`, `monthLabel` — because the Forecasts sheet is a month
page too. Stage 65 added the two pieces it was missing: `monthKey(YearMonth)`, and `monthKeyOf(vararg named)`, which
takes the candidate answers *in the order the page trusts them* and falls back to this month when none parses.
`ForecastService.monthOr` — which had been the Forecasts' own private version of exactly that rule — now delegates to
it, so "which month is this page reading" has one implementation shared by both month pages rather than two that could
drift.

**The page reads the address, so the route had to.** `sectionRoute(path, section, title, content)` hands its block a
session and nothing else, which is why Forecasts, Team and Calendar are plain `get(...)` calls with a
`respondCalendar(session, month)` helper: the route pulls `month` and `pick` out of the query, resolves them through
`monthKeyOf`, and `calendarContent(session, month)` draws that month. The parameter is defaulted
(`month = currentMonthKey()`) so the six earlier `CalendarRenderTest` call sites still compile and still describe the
pre-Stage-65 page rather than being re-pointed to say "no month" — a defaulted parameter is how a test keeps the
history honest.

**Two names on one form, and why.** All four controls are submits of one GET form onto the page, and a pressed `‹` /
`›` also carries whatever the jump box happens to hold. If both answered `month`, the answer would depend on the
order the browser appends them — with JavaScript the swap hides it, without JavaScript the server would silently read
the wrong month. So the steps answer `month` and the box answers `pick`, and `monthKeyOf(params["month"],
params["pick"])` believes the control that was *acted on* first. The box is filled with the month already on screen,
so the browser's own month control and the address can never disagree, and pressing Enter in the box cannot submit the
form's default button (`‹`) because the Go button is the only unnamed submit — an unnamed submit adds nothing to the
question. `app.js` needed no change: one `[data-autosubmit]` control inside a GET form makes `isFilterForm` true, the
live region is `#calendarMonth`, and `boardUrl` overwrites the params with the pressed button's own value, so the same
markup is a fetch-and-swap with JavaScript and an ordinary GET navigation without it.

**What moves with the month, and what does not.** The heading, the strip's count, the grid, and the day cells'
"today" tint are all inside `#calendarMonth`, so choosing a month replaces the month rather than blinking the page —
the standing rule that a visible reload counts as broken. The card list under the grid is deliberately *outside* the
region and deliberately *not* cut to the month: it is the reader's workload, dated or undated, the same set the
Assigned page and the sidebar pill count, and filtering it to the chosen month would quietly drop every card with no
dates. That is the one judgement a restatement could reverse.

**A bar that starts off-screen still says what it is.** The old grid printed a card's title only on its start day, so
a span running in from the previous month arrived as an unlabelled strip. The rule is now stated once, in `spanOf`
(the pair of parseable dates, `start <= due`) and `overlaps` (the span touching the month on screen), and a bar labels
itself on its first *visible* day and rounds off on its last — `isFirstDay = date == span.first || date ==
firstDayOfMonth`. Four inline `try { LocalDate.parse(…) }` blocks became those two functions, so the grid and the
count beside the month title cannot disagree about what a date means.

**Tests.** Suite green at **515 / 37 classes / 0 failures** (was 507 / 37). `CalendarRenderTest` goes from 6 to 13:
the address decides the drawn month; the step buttons carry absolute month values (a copied link lands on the month it
showed, not one step from wherever the reader is now); **This month** appears only away from home; the jump box holds
the month on screen; a word that is not a month opens this month; heading, strip and grid are one live region while the
list under it stays out; and the clipped bar keeps its label. The precedence rule is asserted where it is decided — a
new `ForecastServiceTest` case puts `monthKeyOf` under both orders and both garbage inputs, and checks the Forecasts
page's own `monthOr` still answers identically, so the two pages cannot drift apart later.

**Verified on a throwaway, not on live data.** The new classes booted on port 8095 against an empty database under
`%TEMP%` (`PTS_SEED_DEMO=false`, `PTS_DB_PATH` in the temp tree, a disposable bootstrap password, the scheduler
switched off). After a real `POST /login`, `GET /app/calendar` read October with no **This month** control, and
`?month=2026-12`, `?pick=2026-03`, `?month=2027-01`, `?month=2026-05&pick=2026-08` and `?month=banana` each produced
the heading, the month label, the picker's value and the step values of exactly one month — the paired case proving
`month` wins, `banana` proving an unreadable word falls home. Three cards were then created through the app's own
routes (one wholly inside December, one running 25 Nov to 8 Jan, one with no dates at all): December counted "2 cards
dated here" and labelled both bars, November and January each counted "1 card" and still printed the long card's name
at the clipped edge, the undated card appeared in the list under the grid and never as a bar, an empty month printed
"Nothing assigned to you is dated in Dec 2026." with its way home, and an unauthenticated request was still redirected
off the page. The live instance was rebuilt (`installDist`, `javap` proof of `calendarMonthStrip` / `monthKeyOf` in the
packaged jar) and restarted on 8080 detached; schema unchanged at **V14**. `app.css` gained `.cal-strip`, `.cal-pick`
and `.cal-empty` — base.css writes `label` as an uppercase block and `input` at `width:100%`, so the box beside the
steps needs both overridden — which means the page needs **Ctrl+F5** once before the strip lays out as drawn.

**If this is restated.** The two decisions above are the ones to reverse if the wording changes: a year-at-a-glance
view of all twelve months (a different page, not this one wider), and cutting the list under the grid to the chosen
month. A third reading the current page does not serve is management's month — Stage 64 made the calendar personal for
every role, and Stage 65 moves that personal month, so a "show my team in December" would be a control with its own
rights check, not a wider filter. Never re-propose: a single `month` name shared by the steps and the box (two
controls would answer one question, and the wrong one would win without JavaScript); a page-top error when the URL
names no real month (a bad word opens this month, quietly); navigating instead of swapping the region.

<a id="stage-66"></a>

## Stage 66 — The month holds both boards, and every group of work wears its own colour (2026-10-21, live, no migration, Kotlin + CSS)

**The ask.** *"on calendar the user should be able to see all of their assigned tasks, make it look neat but differ
from one another."* Two complaints in one sentence, and both were real. The month read
`TaskService.assignedFor(…)`, which is the reader's **project** cards only — so a backlog card with a genuine start
date and deadline had no bar anywhere, and with no dates it had no row either, even though the Assigned page (Stage
55) and the sidebar pill both count it. And every bar was painted by its own **priority**, so a month of High work was
a wall of one red with nothing on the grid saying which project a bar belonged to.

**Two decisions, asked before building.** *Scope*: the month reads **both boards, dated and undated** — dated cards
become bars, undated ones keep a row in the list under the grid, which is what makes the page agree with the pill at
last rather than disagreeing quietly. *Distinction*: **one hue per group**, where a group is a project or — for a
backlog card, which has no project by definition — the **bucket** it sits in on the Other tasks board; priority keeps
a voice as a small **dot** inside the bar, and a compact **key** under the strip names the hues.

**What reads the cards.** `calendarContent` now builds one list,
`assignedFor(employeeNumber, canManage = false) + assignedOtherFor(employeeNumber)`, and every downstream decision —
the packing loop, the bar lookup, `datedHere`, the palette, the list under the grid — reads that one list, so the strip
cannot count a set the grid does not draw. This is the **list widening, not the rights**: both halves are personal
slices by construction (`assignedOtherFor` is `other().filter { it.assignee == employeeNumber }`), `assignedFor`'s own
signature is untouched because the Team drill-down still needs its unfiltered form, and no control, editor or route
moved. Stage 64's rule — the calendar is the reader's own month for every role — is exactly what makes adding the
second board safe: a wider set of *my* cards, never a set of *our* cards.

**How the hues are chosen.** `groupKey` keys a card as `project::<name>` or `other::<bucket>`, lowercased and
trimmed, and the two prefixes share no letters, so a project that happens to be named like one of the six sub-categories
still keeps its own colour rather than merging into that bucket's. `hueByGroup` then walks the **distinct, sorted keys
over the reader's whole workload** and hands each one `CALENDAR_HUES[index % size]`. Assigning over the whole workload
rather than the month's slice is the load-bearing part: on a per-month assignment, stepping from October to November
would repaint the same project, which is the opposite of what a colour is for. That rule has its own test, comparing
one card that is a bar in both months. Priority became `priorityDot` (`cal-dot-high|medium|low`) with the word as its
tooltip, so the dot is legible on any hue and the old priority fills are gone from the markup entirely.

**Where the key lives, and when it speaks.** `calendarColourKey` sits **inside** `#calendarMonth`, because which
groups are on screen is a fact about the month the reader is looking at — outside the live region it would keep
describing the month they left. It prints its group swatches only when the month holds more than one group (a key that
repeats a single name explains nothing), and always prints the priority line when any bar exists, because the dots are
new and a reader who has to guess them will not trust the colours either. The list under the grid gained a per-row
swatch, an `OTHER` tag on backlog rows, and a heading change from *Project* to **Where it lives** — "Project" being a
word a backlog card does not answer to. A backlog card's popup is told `Other tasks · <bucket>` rather than a blank.

**The CSS, and the one bug the screenshot caught.** `app.css` gained `.cal-key`, `.cal-key-item` / `.cal-group`,
`.cal-key-prio`, `.cal-swatch`, `.cal-dot` (each with an inset ring so it reads on a pale page and on a dark bar) and
`.cal-tag`. Rendering the page rather than only the markup also caught a layout fault the markup could not show: a
bar's label is `white-space: nowrap`, and under a table's default **auto** layout that nowrap text is a *minimum*
content width — so one long card title widened its whole column, the seven columns stopped being seven equal sevenths,
and the month spilled past the right edge of the panel it sits in. Backlog titles are long, so Stage 66 made that
visible on the first screenshot. The fix is two declarations: `.calendar-grid { table-layout: fixed }` (the columns are
equal whatever the labels say) and `.calendar-task-link { min-width: 0 }` (the label can give up its own width, which
is what finally lets the existing `text-overflow: ellipsis` clip it). Both are pinned by a test that reads `app.css`,
because markup cannot see either.

**Tests.** Suite green at **526 / 37 classes / 0 failures** (was 515 / 37). `CalendarRenderTest` goes from 13 to 24:
a dated backlog card drawing its bar across exactly its days with the strip counting *one card* (cards, not days) and
three fragments; an undated backlog card in the list and never on the grid; a colleague's project card and an
unassigned backlog card both absent, so widening the boards did not widen the readership; two projects in two hues and
one project's days in one hue; a backlog card wearing its bucket's hue; the priority dot present and the old priority
fills asserted gone; the key naming groups only above one group; the hues not moving between months; the new list
column and the page's own sentence about which boards it reads; and the popup's `Other tasks · <bucket>` line.

**Verified on a throwaway, not on live data.** The new classes booted on port 8095 against an empty database under
`%TEMP%` (`PTS_SEED_DEMO=false`, `PTS_DB_PATH` in the temp tree, a disposable bootstrap password, the scheduler off),
and everything was made through the app's own routes: two projects (`POST /app/projects` — note its manager field
wants a team member or nothing at all, and a hand-typed name is refused inline rather than saved), five project cards
and two buckets plus five backlog cards (`POST /app/assigned-tasks/save`, the backlog ones carrying `column=<id>` and
no `taskId`, which is the Stage 51 management path). October came back with 36 bar fragments in **four** distinct hues
— two projects and two buckets — `7 cards dated here`, a key of four group swatches plus the priority line, seven
`OTHER` tags, and popup lines of `Other tasks · Yard work` / `Other tasks · Workshop`. The November page drew the one
November card in `#c8681f`, the same hue that card's October **list row** carried, which is the whole-workload palette
doing its job; September drew zero cards and no group key. A headless-Chrome screenshot was read, and a DOM probe over
the same page measured the grid: table right edge 1371, seven cells of 150.4px, and the worst bar overflow across all
36 bars `-0.50px` — no bar leaves its cell, which is the fix above holding. The live instance was then rebuilt
(`installDist`; `javap -p` on the packaged `CalendarKt` showing `CALENDAR_HUES` / `hueByGroup` / `groupKey` /
`modalProject` / `priorityDot`, and `cal-key` present in the jar's `app.css`) and restarted on 8080 detached — schema
still **V14**, "Project categories: 9 loaded", scheduler in dry run, `/` 200, an unauthenticated `/app/calendar` still
302 to `/`. `app.css` changed, so the page needs **Ctrl+F5** once before the key and the equal columns appear.

**If this is restated.** The two readings the page does *not* serve are worth naming before they are asked for: a
management view of the team's month (Stage 64's rule, and it would need its own rights check rather than a wider
filter), and a per-person legend (the key is by group, not by holder, because the grid is one person's plan). A month
whose bars all come from one project deliberately shows no group key — if a reader asks to see it anyway, that is the
one-line condition to lift, not the palette. Never re-propose: colouring bars by priority (it is what made the month
unreadable, and priority now has the dot); assigning the palette per month (a project would change colour as the reader
steps through the year); leaving the key outside `#calendarMonth` (it would describe the month the reader left);
putting a backlog card's bucket in the *Project* column of the list (the heading is *Where it lives* for exactly that
reason); or dropping `table-layout: fixed` / `min-width: 0` again, which is the difference between a month that fits
its panel and one that spills out of it.

<a id="stage-67"></a>

## Stage 67 — The Team row counts a person's cards, from both boards (2026-10-21, live, no migration, Kotlin)

**Ask.** *"when count tasks on team count tasks from projects and other tasks outside of projects"*.

**What the page actually did.** Two separate things were wrong with the same row, and only one of them is what the ask
named. The Team row's five status columns and its **Total** were counted from `DemoData.projects.filter { it.manager ==
member.name }` — the projects a person **manages**, grouped by *project* status — while the **Tasks** button beside them
counted that person's **cards**. So one row printed two incompatible measurements under headings that looked like one:
a manager of three Ongoing projects read `3` under **Ongoing** and, next to it, a Tasks figure that had nothing to do
with it, and nothing on the row could be reconciled with the drill-down underneath. That is the older, untested bug, and
it is why the ask was not simply about backlog cards. The second half is the ask itself: work that belongs to no project
was invisible on this page, so a planner holding four **Other tasks** cards and no project printed an empty row while the
sidebar pill and the *Assigned tasks* page both counted four.

**The decision, and the two answers that fixed it.** The row now counts **cards from both boards**, and the header
vocabulary follows the card rather than the project: **Ongoing** became **In progress**, because a card's states are
`TASK_STATUSES` — Not started, In progress, On hold, Blocked, Completed — and *Ongoing* is a word a card cannot answer
to. *(**Stage 96** finished the convergence this chose: projects were renamed onto the card's word too, so the heading is
no longer the card's vocabulary *rather than* the project's — it is the one vocabulary both wear.)* Managed projects were not removed from the page, only from the arithmetic: they are still listed in the member's
popup, which is where *what they run* belongs as against *what is on them*. Second answer: **management reads every
row's figures, and a Viewer reads dashes on every row but their own** — the previously-visible colleague counts came
out rather than staying, because the widened figure is a workload number and Stage 51 already made the row's task list
management's.

**One list, so the figures cannot disagree.** `memberTaskRows(employeeNumber)` was already the union of
`TaskService.assignedFor(empNo, canManage = false)` and `TaskService.assignedOtherFor(empNo)`, sorted open-first then by
due date, and it is what the row's own drill-down prints. The row's cells now read from it:
`val tasks = if (seesTasks) memberTaskRows(...) else emptyList()`, `val cards = tasks.map { it.first }`, then
`count(status) = cards.count { it.status == status }` for each of the five and `cards.size` for **Total**. Because
`TaskService` refuses a status outside the five and defaults to one on create, the five columns *necessarily* sum to
Total — which was not true of the old project-status columns and is now a test rather than an accident. `val projects =
DemoData.projects` came out of `teamContent` with the last use gone; the project picker in the assign panel reads
`DemoData.projects` where it needs it.

**Layout, not rights.** Nothing moved in who may act: no route, no control, no editor changed hands, and the assign
panel is still management's. The only visibility change is the one the user approved — a Viewer's colleague rows lose the
counts they used to print. `seesTasks = canManage || member.employeeNumber == viewerEmpNo` is the same predicate that
already gated the row's drill-down and its `tasks-toggle`, so the row now withholds one thing rather than two. Two
helpers carry it: `TD.notYours()` prints the `—`, and `TR.counted(sees, kind, n)` draws one status cell either way, so
the seven cells cannot drift into disagreeing about who may read them. `TR` is the receiver because a row helper has to
emit its own `<td>` — the precedent is `TR.positionRow` in `AccessControl.kt` and `TR.identityCells` in `Forecasts.kt`.

**Why a dash and not a zero.** The two say opposite things. `0` on a colleague's row tells a Viewer *that person has
nothing on them*, which is a claim about the workload the page has just refused to show; `—` says *not yours to read*.
The distinction is already the sheet's own — Stage 46 made `—` sort to the bottom of **Open tasks** on the Settings
sheet for the same reason — and `notYours()`'s KDoc keeps it on the record.

**Tests.** Suite green at **530 / 37 classes / 0 failures** (was 526 / 37). `TeamPageRenderTest` goes 13 → 17. Four
new: a member's figures coming from their cards on **both** boards (two project cards and three backlog cards →
`[2, 0, 1, 1, 1, 5]`, and the colleague who *manages* that project while holding no card → `[0, 0, 0, 0, 0, 0]`, which
is the old bug pinned shut); the five status figures summing to the row's Total; the seven `<th class="num">` headings
reading exactly *Tasks, In progress, On hold, Not started, Blocked, Completed, Total*; and a Viewer being withheld all
seven figures on a colleague's row while their own row keeps them. One existing test was **re-pointed, not loosened**:
`a viewer opens the task list on their own row only` counted `roster.size - 1` dashes, and now counts
`(roster.size - 1) * 7`, since each withheld row lost seven cells rather than one.

**Verified on a throwaway, not on live data.** Port 8096 against an empty `%TEMP%` database, everything made through the
app's own routes: two accounts (`POST /app/settings/employee/save` — its **Position** picker is the position catalogue,
so a hand-typed position is refused with *"That position is no longer in the catalog."* and the field has to be left
blank to follow the roster), a project (`POST /app/projects`), its manager set (`POST /app/projects/1/manager`), a bucket
(`POST /app/other-buckets`), and six cards (`POST /app/team/assign`, the backlog three carrying `board=other&column=1`,
the project two carrying `board=project&project=<name>&lane=<sub-category>`). The management page read exactly:
EMP0011 `[2, 0, 1, 1, 1, 5]` with **Tasks** `4` (Total minus the one Completed card), EMP0012 `[0, 1, 0, 0, 0, 1]`, and
EMP0000 — who manages the project and holds nothing — `[0, 0, 0, 0, 0, 0]`. Signed in as the Viewer, both colleague rows
printed seven `mtd-empty` dashes, zero `tasks-toggle` and no `quick-add`, while their own row kept its figures. A
headless-Chrome screenshot confirmed the wider heading costs nothing: eleven columns, one header line, no wrap, and
`Other tasks` in the sidebar agreeing with the row's backlog cards at 4. Live then rebuilt (`installDist`; `javap -p` on
the packaged `TeamKt` showing `counted(TR, boolean, String, int)` and `notYours(TD)`) and restarted detached on 8080 —
schema still **V14** ("Schema main is up to date. No migration necessary"), "Project categories: 9 loaded", scheduler in
dry run, `/` 200. Kotlin-only, so **no Ctrl+F5**.

**If this is restated.** The row is now a *workload* reading and the popup is the *what they run* reading; if a reader
asks for managed projects back in the columns, that is asking for the two incompatible measurements again. Never
re-propose: counting a member's managed projects in the status columns or Total (the bug this stage removed, and the
reason the five-figures-sum-to-Total test exists); the word **Ongoing** as a column heading (no card can hold that
state); a `0` where a Viewer may not read (it is a claim about the work, not about the readership); widening a Viewer's
view of a colleague's workload to keep the old page looking the same (the ask was about *both boards*, and the approved
answer withdrew the colleague figures rather than keeping them); or moving the counting into `EmployeeService`/the
repository, since the row and its drill-down are required to be the same list and that is what `memberTaskRows` is for.

<a id="stage-68"></a>

## Stage 68 — Every reader sees the workload figures, and a Projects column joins the row (2026-10-21, live, no migration, Kotlin + CSS)

**Ask.** *"and the user able to see all users how many tasks and projects they have"* — the sentence straight after
Stage 67 made the row's figures count cards from both boards, and it loosens exactly the one thing that stage tightened:
the figures themselves. Two questions are in it, and they were answered separately. *How many tasks* — the six card
figures Stage 67 built, which a Viewer could see only on their own row. *How many projects* — a number the sheet did not
print at all.

**The two answers, and the line they draw.** Figures became public; the **cards behind them stayed gated**. Every reader
now gets every row's Projects, Tasks, five status columns and Total, while the thing that names individual work — the
drill-down row under a **Tasks** figure, and the task list inside the popup — remains `canManage || own row`, as Stage 51
and 67 left it. That is the approved wording: *"counts only, the list stays gated"*. Project **names** in the popup did
become public with the new figure, because a project's name is already public on the Projects board since Stage 56 — the
popup holds no secret the sheet has not already printed.

**The Projects figure, and why it is not a seventh status column.** It is the same set the member's popup lists, from one
helper: `memberProjects(name, cards)` returns the projects a member **manages** *plus* the projects holding at least one
of their cards — "everything they're on", which is the reading the ask's own page supports. Because the row and the popup
call the same function, the number on the sheet and the list behind it cannot disagree; that is the same argument Stage
67 made about the card figures, applied to the second question. It is deliberately **outside the arithmetic**: five
statuses sum to Total because every card holds exactly one of them, while a member's projects and their cards are
different populations — a colleague with six cards on two projects reads `2` and `6` on the same row, and a backlog card
moves Total without touching Projects at all. So it is `span("proj-count")`, not a `count` pill, which keeps it out of
the `figures()` test regex and out of the sum.

**A number nobody may open is not a button.** `seesDetail = canManage || member.employeeNumber == viewerEmpNo` now
decides the *control*, not the *figure*: an openable row prints the `.tasks-toggle` button, a colleague's row prints
`span.tasks-static` with the same number in the same pill shape. A button that opens nothing reads as a broken page
rather than as somebody else's list, and the sheet's rule about visible dead ends is older than this stage. The `—`
disappeared with it: `TD.notYours()` is deleted and `TR.counted` lost its boolean flag, because a cell that always
prints a number has no second shape to keep in step. Both static variants are styled to sit beside the button
indistinguishably (`.tasks-static`, plus `.zero` for the muted empty case) and the sheet grew to **thirteen columns**,
which `departmentRow` and `memberTasksRow` carry in their `colSpan`.

**Tests.** Suite green at **532 / 37 classes / 0 failures** (was 530 / 37). `TeamPageRenderTest` goes 17 → 19. The
withheld-figures test was **replaced rather than edited**: `a viewer is given a colleague's figures and not their cards`
now asserts the colleague's six figures print exactly as management sees them, that the row carries `tasks-static` and no
`tasks-toggle`, and — the half that stayed gated — that the popup JSON slice for that colleague holds zero task titles
while the reader's own slice holds one. The other new test pins the Projects figure to its definition: cards on two
projects managed by others → `2`, a managed project with no cards → `1`, two backlog cards and no project → `0`, a
project both managed and worked → `1` and not `2`; and a third assertion states the cross-check outright — the row's
figure equals the length of the popup's project list *for a reader who cannot see the cards*. The heading test now
expects eight `num` headings starting **Projects, Tasks**, and the two role tests count toggles, statics and sub-rows
instead of dashes.

**Verified on a throwaway, not on live data.** Port 8096 on a fresh `%TEMP%` database, everything written through the
app's own routes. Two gotchas cost an hour and are worth keeping: a genuinely fresh database has **no backlog column**,
so the four `POST /app/team/assign` calls carrying `column=1` were silently refused until one was created with
`POST /app/other-buckets` (management-only since Stage 51) — and `| Out-Null` on those calls hid the refusal, so the
follow-up script prints every response status. The figures then read, identically for management and for the signed-in
`Viewer`: EMP0011 Projects `2` with `[2, 1, 1, 1, 1, 6]`, EMP0012 Projects `1` with `[0, 1, 0, 0, 0, 1]`, and EMP0000 —
who manages a project and holds no card — Projects `1` with `[0, 0, 0, 0, 0, 0]`, which is the case the ask was really
about. The Viewer's page carried zero `—`, zero `tasks-toggle` and one `tasks-static` per colleague row, one
`member-tasks` sub-row (their own), zero `quick-add`, and a colleague popup of two project names with no task titles.
`app.css` changed, so the live page needs **Ctrl+F5**. A headless-Chrome render of both saved pages confirmed the
thirteen-column header stays on one line inside its panel at 1680px, and that the static pill is not visibly a
disabled button. Live then rebuilt (`installDist`; `javap -p` on the packaged `TeamKt` showing `memberProjects(String,
List)` present, `counted(TR, String, int)` with the flag gone, `notYours` gone, and `proj-count` / `tasks-static` in the
constant pool) and restarted detached on 8080 — schema still **V14**, "Project categories: 9 loaded", scheduler in dry
run, `/` 200, `/app/team` 302 to the login page when signed out.

**If this is restated.** The gate is now between *the number* and *the list*, and that is the line to hold: a reader who
asks to "see what the others are doing" is asking for the card titles back, which is a different decision from the one
this stage made. Never re-propose: a `—` on any row (the ambiguity it created is why it is gone, and Stage 67's
prohibition against a `0` there died with it — the two were a pair); counting projects inside the five-status arithmetic
or as a `count` pill (a project is not a card's state, and the sum-to-Total test is the guard); a `.tasks-toggle`
pointing at a drill-down that is not printed; or moving `memberProjects` off the popup's list so the row and the popup
can each have their own idea of what somebody is on.

<a id="stage-69"></a>

## Stage 69 — A card moved elsewhere never leaves the Team sheet printing the past (2026-10-22, live, no migration, Kotlin + JS)

**Ask.** *"when card is moved on team count is not working"* — three answers pinned it: the move is a card **dragged
between status columns** on a board, the figure that reads wrong is **Tasks** (the open cards), and it **fixes itself
after F5**. That third answer is the whole diagnosis. A number that comes right the moment the page is asked again was
never a counting bug: `DemoData` is a live view and `findByKind` is an unfiltered `selectAll()`, so the arithmetic Stages
67 and 68 built cannot go stale on its own. What goes stale is the **page**, and it goes stale on its way to the reader
two different ways, which need two different fixes.

**Half one — the tab that never navigated.** A Team sheet already open in a tab has no reason to ask again when a card is
moved in another tab, so it keeps printing the counts from before the move. `Team.kt` now wraps the caption and the sheet
in `id = "teamSheet"` — that panel and nothing else, deliberately excluding the popup's data island, which the script
refreshes by itself, and the assign form below, where the reader may be mid-sentence in a task title. `team.js`
`recount()` pulls **this very URL** with `cache: 'no-store'` (the one answer wanted is what the server says *now*, and a
copy out of the browser's own cache would re-paint the very numbers the request exists to replace), parses it, and
`paint()` swaps the panel by name. The reader's own state rides through: `openDrills()` / `reopen()` put back every row
they had expanded, `bind()` re-listens to the swapped rows, the island is copied over and the open popup re-rendered, so a
fresh table never sits under a stale popup. It fires on `pageshow` with `persisted`, on `visibilitychange`, and on `focus`
(a second window over the same page never changes this document's visibility), all behind `QUIET_MS = 3000` so a tab
flicked past costs no request. Nothing here navigates: the answer to a stale number is not allowed to be a page that
restarts.

**Half two — the page the browser never asked for.** Half one shipped, and the Back button *still* printed the pre-drag
figures on the throwaway. Because for that Back there is no request to intercept: an HTML response with no
`Cache-Control` of its own is fair game for Chrome's document cache, so the sheet as it was drawn before the drag is
re-parsed — and `pageshow` fires with `persisted` **false**, so no listener above ever ran. The evidence keeps this
distinct from bfcache, which the two must not be confused with: `transferSize: 0` and a planted `window.__loadToken`
*gone* is a cached document re-parsed, while bfcache keeps the JS heap, so the token would survive and `persisted` would
be true. The fix therefore belongs on the response, not in the script: `isServerDrawnPage(path)` in `SecurityHeaders.kt`
answers yes for `/`, `/login`, `/logout`, `/app` and every `/app/…`, and those pages now go out as
`Cache-Control: no-store`, so every revisit reads the database again. `/static` and `/files` are the same bytes for every
reader and keep caching, which is why the helper exists instead of a blanket header. The two halves are complementary and
neither substitutes for the other: `no-store` does not disable bfcache, and a tab that never navigated at all is exactly
what `recount()` is for.

**Tests.** Suite green at **537 / 38 classes / 0 failures** (was 532 / 37). `TeamPageRenderTest` goes 19 → 21: one test
places the hook — one `#teamSheet` on the page, the `<table>` and every figure inside it, and the assign form *outside*
it, read off the markup as a `<div>`/`</div>` balance over the slice rather than off an index — and the other pins the
behaviour onto `team.js` the way the board's stylesheet is pinned: it knows the panel by name, listens for both
`pageshow(persisted)` and `visibilitychange`, fetches with `cache: 'no-store'`, carries `openDrills` across the swap and
re-`bind()`s, and `assertFalse(js.contains("location.reload"))` makes a visible reload a failing build. New
`ResponseCachingTest` (3): the page set is `no-store`'d, the asset set is not, and the *emission* is pinned inside the
plugin file by position — the guard, the `call.request.path()` it tests, and the header it sends, all within a 160-byte
window that closes before the block ends. That last one started life as a regex and failed for a reason worth keeping:
`if (isServerDrawnPage(path)) {` already spends three `)` on `if (` plus the call, so the pattern's two closes never
reached the brace — position arithmetic, not another backslash.

**Verified on a throwaway, not on live data.** Port 8097 on a fresh `%TEMP%` database, everything written through the
app's own routes. The user's repro exactly: the Team sheet read Projects `1`, Tasks `1+`, Not started `1`, Completed `1`,
Total `2`; card 5 was dropped into **Completed** on `/app/assigned-tasks`; Back then printed Tasks `0`, Completed `2`,
Total `2` — with `navType: "back_forward"` and `transferSize: 19351`, i.e. a real fetch where the bug had been a `0`.
The other half needs a page that never leaves the tab: with the Team sheet sitting there, card 4 was POSTed to *Not
started* through `/app/tasks/status` and the row read **unchanged** (correct — nothing has asked yet), then the tab's
visibility flipped and the same row reprinted itself Tasks `1+`, Not started `1`, Completed `1` with `location.href`
untouched. Four harness gotchas cost time: browser-use's `drag` times out against HTML5 drag-and-drop, so the move is
synthesised (`new DragEvent('dragstart'/'dragover'/'drop', {dataTransfer: new DataTransfer()})`) and runs `kanban.js`
verbatim — after which the eval dying with *"Inspected target navigated or closed"* **is** the board's reload, not a
failure; a browser-use tab reports `visibilityState: 'hidden'`, so `comeBack()` rightly bails and must be driven with
`Object.defineProperty` (a harness limitation, not a pass); `GET /login` is a 405 with an empty body because the form
lives at `/`; and a genuinely fresh database has **no backlog column**, so cards filed there are silently refused.
Live then rebuilt (`installDist`; `javap -p` on the packaged `SecurityHeadersKt` showing `isServerDrawnPage(String)`, and
the `static/js/team.js` inside the jar sha-equal to the source) and restarted detached on 8080 — schema still **V14**,
"Project categories: 9 loaded", scheduler in dry run, `/` 200 **with `Cache-Control: no-store`**, `/app/team` 302 with the
same header, `/static/js/team.js` 200 with **no** `Cache-Control` at all. One honest caveat: a reader who already holds a
stale copy in their browser keeps it until they reload once — the header governs copies made from now on.

**If this is restated.** Hold the split between the two halves: whoever asks to "make the Team numbers live" is asking
either about a tab that is open or about a page the browser never requested, and only the first is a JS problem. Never
re-propose: fixing the Back button in JavaScript alone (there is no request there to intercept, which is precisely what
this stage discovered); `no-store` on `/static` or `/files`, or a blanket header; `location.reload()` in `team.js` as the
answer to a stale figure (the standing rule, now asserted by a test that fails on the string); a re-count that clears the
assign panel; or two panels named `teamSheet`. Flagged and deliberately left alone: `POST /app/tasks/status` discards
`updateStatus`'s Boolean refusal and redirects anyway, so a *refused* move reloads into the column it was refused — a
different decision from the one this stage made.

<a id="stage-70"></a>

## Stage 70 — Finishing a card ticks its whole plan, and reopening it puts the plan back (2026-10-22, live, V15)

**Stage 101 supersedes the late-step rule in this historical section:** a new step always lands unticked, even on
Completed or a card created directly into Completed. The old ticked insert argument, late-step 100% guarantee and
late-step verification figures describe Stage 70 only. Finishing still ticks the plan present at that moment;
reopening restores remembered rows only, and Completed rows remain locked.

**Ask.** *"when task card is completed checklists should be checked auto and when user drag it back checklists come back
to the last status"*. Three answers pinned it, and each one is a decision a later reader can easily undo: **the boxes
lock while the card is Completed** (a finished card's plan is readable, not clickable, and the only way to change it is
to move the card back); **every status-change path ticks and restores**, not just the drag — the ◀ ▶ arrows and
management's **Status** picker in the editor (**Stage 103** retired those arrows from both status boards, so the paths
today are the drag and the picker, and each still ticks and restores); and, at this stage, **a step added to a finished card landed ticked**,
because a card that said it was done was not to read 75% because somebody wrote another step on it. **Stage 101
supersedes only that late-step rule**: every new step now starts unticked, including on a card born *Completed*;
the finish/restore cycle and the Completed lock remain.

**The rule lives in the service, once.** `syncPlanWithStatus(taskId, from, to)` in `TaskService` ticks the existing
plan on a real crossing into *Completed* and restores it on the way out. It is called from `updateStatus` (the drag
and the arrows, which both arrive there) and from `updateAssigned` right after the row is written — the picker being
the third way a card changes status. At Stage 70, `addChecklistItem` read the card's status to pick its starting tick;
**since Stage 101** it always calls `ChecklistRepository.insert(taskId, text)`, whose insert writes `done = false`
and accepts no `ticked` argument. `toggleChecklistItem` still refuses while the card sits in *Completed*, before it
looks at who is asking. A POST built by hand finishes exactly the plan a drag finishes; adding a row is not finishing it.

**What "the last status" is stored as.** `task_checklist_items.pre_completed INTEGER NULL` (**V15**), one remembered
value per step rather than a snapshot of the plan on the card. The column is nullable with no default and NULL is the
answer to "is a completion outstanding on this step?", which is why `restoreAll` moves *only* the rows that carry a
memory. At Stage 70 a fourth step added while finished kept its tick on reopening; **Stage 101 supersedes that starting
state**, not the selective restore: a new row has `done = false`, `pre_completed = NULL`, and reopens still unticked.
The alternative — a JSON blob of the plan on `tasks` — was dropped before it was written: a tick is one UPDATE today,
and a blob would make every tick a read-modify-write of somebody else's whole list. `finishAll` stamps each step's own
`done` into its own `pre_completed` before ticking, so a second completion remembers the plan as it stood the second
time (a test pins exactly that, because the first memory is the obvious thing to keep wrong).

**V15 also makes the rule true for work already finished.** `ALTER TABLE … ADD COLUMN` alone would leave every card
already in *Completed* holding a half-ticked plan that the new code has no memory of — an exception carved into the
live data by the migration itself. So V15 runs one completion over those cards: `SET pre_completed = done, done = 1`
where the task's status is *Completed*, which works in one pass because SQLite reads the pre-update value of `done` on
the right-hand side. Nothing else in the database is touched, and the migration is not re-runnable by design (Flyway
runs it once); a card *finished after* the upgrade is finished by the service, not by the backfill.

**The lock is a row, not a section.** The obvious implementation — take `mayTick` away while the card is Completed —
would have hidden the whole plan, because `modalSteps` is only called when that flag is set, and answer (1) is that a
finished card's plan stays worth reading. So `modalSteps` picks between two renderings of the same row:
`stepTickForm` (the form, the label, the `data-autosubmit` box) and `stepRowLocked`, which prints the same square and
the same struck label with the form taken out from under it — `disabled` on the input, the reason on its `title`. It is
deliberately not the old ✓/· mark come back, and management keeps the add line and the × on a finished card, because
those two write the plan rather than work down it, and their routes still check the right.

**Consequence worth naming: the project's bar moves.** A Completed card now reads 100% of its own plan, and a
project's progress averages its measurable cards, so dropping a card into *Completed* raises the project figure and
dragging it back lowers it again. Measured on the throwaway: 88% → 100% → 88%. That is Stage 26's arithmetic doing its
job, not a second behaviour, but it is the first thing a manager will notice.

**Tests.** Suite green at **549 / 39 classes / 0 failures** (was 537 / 38). `TaskServiceTest` goes 83 → 90 with a
block titled for the promise rather than the method: the tick-and-restore cycle, the picker behaving like the drag, a
second completion remembering the second state, no box moving on a finished card for either account, a step added to a
finished card keeping its tick across the reopen, a save that leaves the status alone leaving the plan alone, and a
card with no steps finishing with nothing to remember. New `ChecklistCompletionMigrationTest` (3) builds a real V14
database in a temp file, puts a Completed card's steps at `[1,0,1]`, runs the real `Migrations.migrate`, and asserts
the column arrives last and nullable with no default, that the backfill ticks-and-remembers in one pass while an *In
progress* card's steps stay untouched and NULL, and that no row ends up `done = 0 AND pre_completed IS NOT NULL`. The
middle test failed once and the failure is worth keeping: the case that should have started from *In progress* started
from a card created *Completed*, so both steps were inserted ticked and the "restore" had nothing to undo.
`TaskBoardRenderTest` goes 53 → 55: a finished card's rows are locked for the manager and for the owner alike (two
`chk-box locked`, zero toggle forms, no `data-autosubmit`, `disabled` and `checked` on the input, add ×1 and remove ×2
still present), and a *Blocked* card keeps its clickable boxes — the lock is about *Completed* and no other status.

**Verified on throwaways, not on live data.** Seventeen HTTP checks on a fresh database at 8095 (`PTS_SEED_DEMO=false`,
`PTS_DB_PATH` in `%TEMP%`, everything written through the app's own routes): three steps with two ticked → the drag to
*Completed* prints `TTT` with three locked rows, zero toggle forms and `3 of 3 done · 100%`; a hand-built toggle POST
for step 3 changes nothing; a fourth step added while finished arrives ticked; the drag back reads `TTfT` with four
clickable boxes; a second completion and reopen gives the same `TTfT`; the picker gives the same; `Blocked → Completed
→ Not started` gives the same; a card born *Completed* prints no boxes at all until a step is added, and that step
arrives ticked and locked; and the project roll-up moves 88 → 100 → 88. Then the upgrade case on a **copy** of that
database at 8096, put back into the shape a live file holds with `jshell --class-path <sqlite-jdbc jar>` (the
`pre_completed` column dropped, its `flyway_schema_history` row deleted, one card left *Completed* with its plan at
`[1,0,1,0]`): the boot logs `Flyway: 1 migration(s) applied, schema at version 15`, the card reads `TTTT` settled, and
the first drag back restores `TfTf` — the state the person had actually left it in, nine checks green. Three gotchas
cost time: a killed JVM leaves its data in the `-wal`, so copying the 4 KB main file alone copies an empty database —
take the `-wal` with it; `jshell` treats `exit;` as a symbol rather than a directive, so the script runs and then sits
at a prompt; and PowerShell reads a UTF-8 `.ps1` without a BOM as ANSI, so the `·` in an expected `chk-count` string
never matched the page's — compare with `-like '3 of 3 done*100%'` rather than typing the middle dot into a harness.

**Deploy note.** `data/pts.db` was copied to `pts.pre-v15-20261022-093425.db` (with its `-wal`/`-shm`) before the boot
that applied **V15**; `stop.bat`'s polite `taskkill` was refused on this machine (`/F` only), so the live JVM is
force-stopped and the WAL copy is the consistent snapshot. Rebuilt with `installDist`, and the packaged jar proved by
`javap` (`ChecklistRepository.finishAll(int)` / `restoreAll(int)`, `TaskService.syncPlanWithStatus(int, String, String)`,
`V15__checklist_completion_memory.sql` in the jar). Restarted detached on 8080: `Flyway: 1 migration(s) applied, schema
at version 15` against the real `data/pts.db`, `/` 200, and `/static/css/app.css` already serving the two
`.chk-box.locked` rules from disk. **A reader needs Ctrl+F5 once** — `app.css` is cached and there is no cache-buster in
the markup, and without it a finished card's locked rows render as ordinary boxes.

**If this is restated.** The two halves are one rule with one memory: an ask to "auto-complete the checklist" that
comes back without a restore is a different feature, and the restore is why the column exists. Never re-propose:
narrowing `TaskForm.mayTick` for a Completed card (it hides the entire plan section, which is answer (1) broken); a
per-card JSON snapshot of the plan (a tick would become a read-modify-write of somebody else's list); ticking the boxes
in `kanban.js` or `task-modal.js` instead of the service (the arrows and the picker would then not tick, and a
hand-built POST would not either); restoring *every* step on the way out instead of only the remembered ones (it would
untick a step added while the card was finished, breaking answer (3) in the one place it matters); or dropping the
backfill and leaving cards finished before the upgrade as an exception to a rule that says they are ticked. Flagged and
deliberately untouched, as in Stage 69: `POST /app/tasks/status` still discards `updateStatus`'s Boolean refusal and
redirects anyway, so a refused drag reloads as though it happened.

<a id="stage-71"></a>

## Stage 71 — A Viewer's project header is the same one line management's is (2026-10-22, live, no migration, Kotlin + CSS)

**The ask.** *"when the user click on project it looks like this should be looking in one line"*, with
`non-manager.png` attached — a 1,591 × 142 crop of a project page read as a Viewer: the pill row
(`Fabrication/Manufacturing` · `M-03111` · `Not started` · `★ Pinned`), the name, then **seven** `CAPTION value`
pairs on one line and Revenue + Progress on a **second** one. Standing feedback says a restated UI ask means
removing the exception, and the exception here was the second line — so this is Stage 29's nine fields asked for
again, with the answer being that the row cannot be nine pairs and one line at the same time.

**Two answers, both pinned before anything was written.** (1) *Copy management's strip* — make the read-only
header the twin of Stage 60's `.edit-strip`: caption **over** value, all nine fields across one row in the same
flat borderless style. Rejected: keep the inline pairs and squeeze them (fonts and gaps, which is what the ask
would have come back as), and drop Project number and Status because the pills above already say them — answer
(1) says copy the strip, so nothing is dropped, shortened or reordered. (2) *Keep the bar* — the Progress cell
keeps its bar plus the percentage even though management's cell shows a bare `%`, because this is the only place
a non-reader-of-numbers sees what the project has reached.

**The arithmetic, measured rather than guessed.** Nine side-by-side `CAPTION value` pairs ask roughly 1,600px.
The panel they sit in measures **1,114px** in a 1,500px window (viewport − 264 sidebar − 64 page padding − 40 card
padding − 2 border), which is why two fields have fallen onto their own line in every window anyone has used. A
caption over its value halves what a field asks for: the nine read-only cells' minimum legible widths came to
~1,000px of text plus 112px of gaps, which fits 1,114 with a little to spare. Stage 60's lesson is what makes it
a promise instead of a hope — every cell carries a **zero flex-basis** (`W 1 0`), so it has no intrinsic width for
`flex-wrap` to fold on and nine cells always divide the row between themselves; measured one row at every viewport
from 1,064 to 2,084.

**What changed.** `Projects.kt`: the Viewer branch's `detail-grid` of seven `detailItem`s plus its `detail-pair`
wrapper became one `div("edit-strip detail-strip")` of nine cells, and `detailItem` became
`detailCell(caption, value, extra)` — an `.ef` div with an `.ef-label` caption and a `.detail-val` span carrying
the whole string as its own `title`. The Progress cell keeps `id = "project-progress"`, so the `data-live-extra`
swap in `app.js` still repaints it from a step form's response; it now holds a `.detail-line` (bar + percentage)
where the other eight hold text. `app.css`: `.detail-grid`, `.detail-item`, `.detail-pair`, `.detail-key` and
`.detail-val`'s old inline rule are gone, replaced by the Stage 71 block. Management's strip is untouched.

**Two traps the first pass fell into.** `.edit-strip .ef:first-child { flex: 2.2 1 0 }` is management's
Description share, and it ties with `.detail-strip .ef.num-ef` on specificity (three class-level selectors each),
so the *later* rule wins — the read-only block answers it with its own `:first-child` weight rather than inheriting
the widest cell on the row. And `.ef-label` is `white-space: nowrap` with no overflow rule of its own, so a caption
that outgrows its share does not shorten — it **paints across its neighbour**: at the first set of weights
"PROJECT NUMBER" overflowed by 31px and Category by 44 at a 1,114 panel. Hence the measured weights (number 1.45,
category 2.75, manager 2, client and status 1.15, dates 1.15, money 1.1, progress 1.4), a narrower 14px column gap,
and `.detail-strip .ef-label { overflow: hidden; text-overflow: ellipsis }`. A value that still outgrows its share
is ellipsised, never wrapped — a wrapped field is two lines tall and takes the row with it — and the full text rides
on the cell's `title`, which is the same bargain an input makes when its text scrolls.

**Tests.** `ProjectsPageRenderTest` 25 → 26, suite **550 / 39** (one test replaced by two). The Viewer test now
slices the strip out of the page with a div-balance helper and asserts no `<input`, no `<select`, no `field-err`
inside it, while keeping the pin form. `the nine read-only fields are one row of caption-over-value cells…` pins
nine `.ef`, nine captions in the exact historical order, eight `.detail-val` spans (the ninth cell is the bar) each
carrying a `title`, and no `detail-pair` in the markup *or* in `app.css`. `the read-only row is weighted to hold
nine cells on one line` reads `app.css` and pins the zero-basis weights, the `:first-child` answer and the three
ellipsis declarations, so a future `1 1 120px` fails the build instead of the layout. The roll-up test's End date
assertion became a regex, because every value gained a `title`.

**Verified by rendering, not by logging in.** A throwaway test (deleted after the check) rendered the Viewer header
with the screenshot's own values into a standalone file with `base.css` + `app.css` inlined, and headless Chrome
measured it through an inline probe: `LINES=1` at 1,064 / 1,204 / 1,344 / 1,484 / 1,839 / 2,084 viewport, and at
1,484 every cell clip-free (109 / 207 / 87 / 151 / 87 / 87 / 87 / 83 / 105px). Screenshots at 1,220, 1,360 and 1,500
show the row and its graceful shortening. Four harness gotchas: a PowerShell `function` cannot assign to `$args` —
it is automatic — so the first harness "ran" seven widths and launched nothing; a *shared* `--user-data-dir` makes
every headless call after the first write nothing, so the profile has to be fresh per invocation; `sed -n
'/<pre id="probe">/,/<\/pre>/p'` also catches the probe's own source, so filter the emitted lines by prefix; and an
unauthenticated `curl` to 8080 is still refused by this machine's classifier, so the live check stops at `/` → 200
plus proof read out of the packaged jar.

**Deploy note.** No migration — markup and CSS only, so no `data/pts.db` copy was taken. Live JVM 26468 stopped,
`installDist`, the packaged jar proved with `unzip -p … static/css/app.css` (`.detail-strip .ef.cat-ef { flex: 2.75
1 0; }`) and `javap` (`detailCell(DIV, String, String, String)`), then restarted detached through
`Start-Process cmd.exe '/c', deploy\run.bat -WindowStyle Hidden`: new PID **25564** on 8080, `/` 200, log clean at
`Responding at http://127.0.0.1:8080`. **A reader needs Ctrl+F5 once** — `app.css` is cached and there is no
cache-buster in the markup; without the reload a Viewer's header keeps rendering the old two-line card.

**If this is restated.** Never re-propose: putting a `detail-pair` (or any wrapper) back around two fields — that
wrapper is the exception the ask was about; giving a `.detail-strip .ef` a content-based flex-basis, which
re-enables wrapping and puts the ninth cell on a line of its own; dropping Project number or Status because the pills
duplicate them (answer (1)); letting a long value wrap instead of ellipsising; replacing the Viewer's bar with
management's bare `%` (answer (2)); or shrinking fonts and gaps to squeeze the old inline pairs, which is the same
1,600px ask wearing a smaller typeface. Deliberately untouched, as ever: the rights split — a Viewer is still served
no edit form, no delete and no `field-err`, and the routes refuse those writes again behind the markup.

<a id="stage-72"></a>

## Stage 72 — Management can delete the card from inside the card (2026-10-22, live, no migration, Kotlin + CSS)

**The ask.** *"for managements when click on any card right corned of the tab beside add note button add delete task
button"* — after a click opens a card, management gets **Delete task** at the right corner of the same line that
carries *Add note*. "The tab" is the card popup, `#taskModal`, and that reading needed no question: the popup is the
only place in the app with an *Add note* button, so it is the only place the ask can be anchored to.

**Why the delete lives at the bottom of the panel.** `POST /app/assigned-tasks/delete` has existed since Stage 17 and
has always checked `Authorization.canManage`, but the popup — the only surface a card is ever opened on — had no
control pointing at it; card removal was reachable only from places a click had already left behind. Writing a note
and taking the whole card off are the two things a person does at the *end* of this panel, so the button goes on the
note box's own foot line, at its far right: the destructive action kept away from the button that adds to the log, and
reachable from the card it destroys. Rejected: a delete out on the board's card (Stage 22 took the buttons off cards,
and Stage 24 made the card itself the open gesture — there is no room that is not the whole card), and a delete in the
popup's header next to Save (the popup's header belongs to writing the card, not to destroying it).

**Rendered on the flag the route already checks.** The form is drawn on `TaskForm.canEditAll`, which *is* management
and is the same line `Authorization.canManage` draws behind the route, so nobody is ever offered a button that would
silently redirect. The card's owner who is not management, and a reader with no part in the card, get the note box and
no delete; a card being created has nothing yet to delete, and `modalNotes` returns before the foot row exists at all
(`task ?: return`). Rights were tested from both directions on a throwaway instance: the Viewer's own popup prints no
`/app/assigned-tasks/delete`, and a hand-built Viewer POST to that route came back with the card still on the board.

**The fields, and the one field deliberately missing.** `taskDeleteForm` posts exactly what the route reads —
`taskId`, `back`, `projectId`, plus `allbuckets=1` when the popup was opened from the wide backlog view. It carries
**no `edit`**, unlike every other form in this panel: those come back with the popup still open on the card they just
wrote, and after this one there is no card to be open on, so `edit=<deleted id>` would reopen the popup on a
non-existent task. `taskRedirect` then lands the delete on whichever board the card came from — `/app/projects/{id}`,
`/app/other-tasks?allbuckets=1` when that view was the one in use, otherwise `/app/assigned-tasks`. The button is the
app's existing destroy shape (`ghost-btn btn-sm danger` + a `data-confirm` naming what else goes with the card and that
it cannot be undone), and it is deliberately **not** a `data-live-target` block form: the card it names is leaving the
page, so there is nothing left to repaint in place.

**Two sibling forms, one row.** A form cannot live inside the note form, so `.note-foot` lays them out as siblings:
`display: flex; align-items: flex-end; gap: 10px`, the note box `flex: 1 1 auto; min-width: 0` and the delete
`flex: 0 0 auto`. The bottom alignment is what makes the pair read as one line — a two-row textarea and a one-line
button meet on their baselines rather than their tops. **The specificity trap:** `.ghost-btn` is written after
`.btn-sm` in this file at equal specificity, so it wins both `padding` and `font-size`, and a delete built from the two
classes alone stands taller than the *Add note* button beside it. Hence `.note-foot .task-del .ghost-btn` spells the
button's metrics out (`padding: 5px 12px; font-size: 12.5px; font-weight: 600; line-height: 1.5`) instead of inheriting
them — the same lesson Stage 41 and Stage 71 each had to learn about this stylesheet's ordering.

**What the delete takes with it.** `task_checklist_items` and `task_notes` are `ON DELETE CASCADE` and the connection
turns `PRAGMA foreign_keys = ON`, so a card's plan and its log go with it. That was proved rather than assumed: after
stopping a throwaway JVM, the database (snapshotted together with its `-wal`) was read with raw SQL through `jshell` —
before the delete the popup showed `0 of 2 done` with both step labels and a note body, and after it
`tasks=0 steps=0 notes=0` with the project, the roster and the six lanes still standing.

**Tests.** `TaskBoardRenderTest` 55 → 59, suite **554 / 39** classes, 0 failures. `formPosting(html, action)` was added
because a tag-only match stops at the opening `>` — exactly where every field this route reads lives — so the helper
returns the whole `<form>` element. The four cases pin: the button and its three fields with no `edit` and no
`data-live-target`; `allbuckets` carried from the wide view and *not* handed to the narrow one, plus the route's half
of that promise asserted straight off `taskRedirect`; a non-manager offered no delete in three shapes (owner, reader,
fresh create); and the row itself — three exact CSS declarations, the metrics block read out of `app.css`, and the
markup sliced from `class="note-foot"` proving *Add note* really is on the delete's own line. Stage 40's count of
`allbuckets` occurrences moved 5 → 6 for the new form.

**Verified over real HTTP, on throwaway instances only.** Two scripts against `%TEMP%` databases on 8095, seeded
managing and reader accounts through the app's own routes: **33 checks, FAILS 0** for the markup, the rights, the three
landings and the wide view; **FAILS 0** again for the create-a-plan-then-delete cycle whose rows were then counted in
SQL. Five harness traps came out of it, all recorded in project memory: PowerShell 5.1 swallows `$P?edit` inside a
double-quoted string (`"/app/projects/$P?edit=$BID"` became `/app/projects/=2` and looked like a broken route), so
interpolation next to a `?` needs `${P}`; `'column=' + $v` written *inside* an `@(...)` element splits into two curl
arguments; a function's `Write-Output` lines vanish when its return value is assigned; the Other tasks page has no
`data-task-card` — its cards open through `<form class="kcard-open">` with a hidden `edit`; and a Viewer's card cannot
be read off management's `/app/assigned-tasks` at all, because that board has been personal since Stage 53, so the
"the other reader's card survives" check has to be fetched as that reader.

**Deploy note.** No migration: markup and CSS only. Live JVM **25564** stopped, `data\pts.db` copied to
`pts.pre-stage72-20261022-115017.db` (+ its `-wal`) as insurance, `installDist`, and the packaged jar proved with
`unzip -p … static/css/app.css | grep -n note-foot` plus the same grep on `mn/murray/pts/TaskBoardKt.class`.
Restarted detached through `Start-Process cmd.exe '/c', deploy\run.bat -WindowStyle Hidden` — never from a backgrounded
task — new PID **6792** on 8080, log clean at `Responding at http://127.0.0.1:8080` with `foreign_keys=ON`,
`journal_mode=WAL` and `0 migration(s) applied`. **A reader needs Ctrl+F5 once**: `app.css` is cached and the markup
carries no cache-buster, so without the reload *Add note* keeps rendering alone on its line.

**If this is restated.** Never re-propose: putting the delete back out on the board's card, or in the popup's header
beside Save; nesting the delete form inside the note form (impossible in HTML, and the reason the row exists at all);
letting `.ghost-btn` set the delete's own padding and type size, which is what makes an unscoped button taller than its
neighbour; posting `edit` from this form, which would reopen the popup on a card that no longer exists; drawing the
button on anything looser than `canEditAll`, or on the card's owner — the route refuses a non-manager whatever the
markup offers, and an offered-but-refused button is the bug Stage 51 was built to remove; or `data-live-target` on it,
since there is no card left for a repainted block to describe. Deliberately untouched, as ever: the route itself, the
service rules behind `TaskService.deleteAssigned`, and the cascade — all three predate this stage and this stage only
connected the popup to them.

<a id="stage-73"></a>

## Stage 73 — A project's buckets are chosen when it is made, and on its own page afterwards (2026-10-23, live, no migration, Kotlin + CSS + JS)

**The ask.** *"for these buckets for cards when create a new project the management have to choose from buckets to
assign what kind of tasks the project have such as Engineering Procurement Production etc. Plus user can add new bucket
from the same page. On the setting function there is project bucket settings."* Four things were settled before any
code was written, because the ask reads two ways on each of them: the tick-list comes from **that category's
*Settings › Project bucket settings* catalogue**, a name typed on the create page lands on **the project *and* that
catalogue**, the picker **is** available after creation — *"Yes, same picker on the page"* — and **a bucket that still
holds cards cannot be unticked**. The fourth scoping answer, given descriptively rather than as a choice, is what fixed
the model: *"So we have types of projects such as labor supply site projects etc. These sub categories have buckets
that related to each own act"* — the offer is cut by **category**, not global.

**No migration, and that is the point.** The schema stays at **v15**. `project_buckets` (V7) already holds a project's
lanes and `type_default_buckets` (V12) already holds a category's catalogue; what was missing was only the choice
between them. The picked set is **additive on top of the six compulsory sub-categories** — Stage 48's floor, which
Stage 63 recorded as never-to-be-dropped — so `ProjectBucketRepository.seedDefaults` still writes the six at positions
0–5 and `seedExtras` appends the ticked ones from 6 onwards in the catalogue's own order. Rejected on those grounds: a
V16 that seeds a starter catalogue, and any table change, since neither question was actually about storage.

**The rules are one object, read by two pages.** `BucketDefaultsService.chosenFor(type, posted, canManage)` resolves
the create popup's tick-list, and `TaskService.saveLanes(projectId, posted, newBucket, callerEmpNo, canManage)`
resolves the page's picker in a single write. Both check every name through the shared `nameProblem` **before writing
any of them** — a picker that applied its first three ticks and then refused the fourth would leave a board matching
nothing on the screen — and both treat a typed name as one more tick, which is the whole point of offering both on one
page. `saveLanes`'s order is **DENIED → NO_PROJECT → BAD_NAME → CATALOGUE_DENIED → HAS_CARDS → write**. The refusal a
reader actually sees is one of four sentences: a name blank or over `PROJECT_BUCKET_MAX`, one of the six
(*"… is one of the six every project board already starts with — it is not a bucket to choose."*), a new name offered
by a caller who is not management (*"Only management adds a new bucket to the Site projects catalogue."*), or the
untick that would hide work (*"QA hold still has cards in it. Move them to another bucket before unticking this
one."*). A name the category does not know is not a refusal for management — inventing it *is* the ask, and it goes
into `type_default_buckets` so the next project of that category is offered it too.
*(Both sentences in that paragraph were re-cut by **Stage 77**: the reach of a
typed name is now behind an explicit `also_type` tick rather than automatic, and the sentence above about a
non-management caller is now about the tick alone — a project's own manager may add a project-local column freely.
Read Stage 77 for the rule that is live.)*

**HAS_CARDS is the new rule, and it is deliberately stricter than the route it sits beside.** `removeLane` has always
let a lane be deleted with its cards falling to Unsorted under `tasks.project_bucket_id`'s `ON DELETE SET NULL`. That
answer predates Stage 52 cutting the project board to status columns: there is no Unsorted column on the page a reader
is standing on, so unticking an occupied bucket would **hide** work rather than move it. The picker is the path a
person can actually reach, so there the cards are the reason for the refusal — while `removeLane` keeps its older,
wider answer for the hand-built route that is all that is left of it. `ProjectBucketRepository.cardCounts` answers this
by lane id and returns **only the occupied ones** (a lane with nothing in it is absent, not zero), which is what lets
`saveLanes` and the panel both read `counts.containsKey(id)` / `counts[lane.id] ?: 0`.

**The popup renders every category's set and switches the others off.** `bucketSet` is drawn once per category
(`data-bucket-type`), and only the chosen one is enabled. That is because the popup is one `<form>` and the category is
a `<select>` the reader may change a dozen times before pressing Create — asking the server each time would restart the
page under them — and because a checkbox behind `display:none` **still rides on the post**: `project-modal.js`'s
`refreshBuckets()` therefore sets `disabled`, not merely visibility, so a project is never created with a bucket nobody
can see. A fresh popup opens on `PROJECT_TYPES.first()` (`selectedType` in `projectCreateModal`), independent of the
board's `?type=` — a fact the verification had to be re-pointed at, since the live set is the one the select names.
*(**Deleted by Stage 92**: `bucketSet`, `bucketOption`, `bucketNewLine` and `refreshBuckets` are gone, because the create
popup no longer asks which buckets a project has. Every board opens with the same three phases; the popup names them and
says where a fourth is added. Read Stage 92 for the rule that is live.)*

**A locked bucket is ticked, disabled, and still stated to the save.** `bucketOption(name, ticked, off, locked)` prints
the third state the page needs: `checked` **and** `disabled`, plus a hidden twin `<input type=hidden name="bucket">`
carrying the same name, since a disabled control posts nothing and the save would otherwise read the box's silence as
an untick. The label wears `bucket-opt locked`, and `span("bucket-cards")` prints *"— N card(s) in it"* beside the name
so the reason shows before the save is refused rather than after.
*(**Retired by Stage 79**: this third state went with the panel that needed it, and `bucketOption` is down to
`ticked` and `off`; the card-counting lock is printed by the column popup as a count line over an unpressable
**Remove bucket**. The hidden-twin lesson stands, and the popup's add form still uses it — every current lane goes into
the POST as a hidden `bucket`.)*

**One panel, one save, and its own answer.** The project page's `projectBucketPanel` is the same object, not a second
design — the six as `span.chip.lane-chip` on a `bucket-floor` line, this project's buckets ticked, the category's other
buckets offered unticked, one `new_bucket` box, and a single **Save buckets**. It hangs on
`TaskService.mayEditLanes` (management or this project's own manager, the same answer as `mayAddTasks`): a `Viewer`
gets **no panel at all** rather than a form that cannot save, and `POST /app/projects/{id}/buckets/save` bounces a
hand-built `DENIED`/`NO_PROJECT` to `/app/projects` having written nothing. The form is
`data-live-target="#projectBuckets"` with `data-live-extra="#taskLane"`, and the route answers by naming its outcome in
`?buckets=ok|err` and carrying the sentence in `?bucketnote=`; the note prints **inside** `#projectBuckets` because that
is the block the swap replaces — a note above it would be left stating yesterday's outcome under re-ticked boxes. The
`#taskLane` companion is not decoration: a bucket added here is a place a card can be filed the moment the save lands,
and a popup still listing the old set would refuse the reader's own first card.
*(**The panel is gone since Stage 79** — `projectBucketPanel`, its `#projectBuckets` block and its **Save buckets**
button were deleted from the project page on the user's instruction. Everything else in this paragraph still describes
the live code: the route, `mayEditLanes`, the bounced hand-built `DENIED`, the whole-set save, and the `?buckets=` +
`?bucketnote=` answer. What moved is the target and the companions: the one form the page prints now is the column
popup's add half, and it names `data-live-target="#projectBoard"` with `data-live-extra="#taskLane,#projectLaneModal"`,
because since Stage 75 the columns *are* the buckets.)*

**The typed bucket becomes a tick in the page, not a second kind of answer.** `app.js` owns `[data-bucket-add]` rather
than `project-modal.js`, because the project page loads the picker but not the popup's script. The button is
`type=button` — a form cannot live inside a form, so the popup's Create and the page's Save cannot be given a second
submit — and pressing it (or pressing Enter in the box) inserts the typed name into the set as one more checked
`input[name="bucket"]`, which is the only shape either save reads. If the name is already offered, the existing box is
ticked instead of duplicated. No validation happens in JavaScript on purpose: the service owns the rules and a refused
name comes back as a sentence under the picker, not as a browser's opinion about spelling.
*(**Deleted by Stage 92**, with the create picker that was its only remaining producer: no page prints `[data-bucket-add]`
now, and the project page's add form is a plain submit carrying every current lane as a hidden `bucket`.)*

**Rights, and the round trip.** A create's refusal still answers 200 with the popup open on the filtered board the
reader was on (the `board_*` hidden fields, Stage 17), with the ticked set back as **ticks** and the typed word back in
its own `new_bucket` box — `fields.getAll("bucket")` versus `fields["new_bucket"]` is what lets the two be put back
apart. `manager` on this form is a **name** from the roster, not an employee number, and empty is legal.

**Tests.** Suite **571 / 39** classes, 0 failures (554 → 571). The seventeen new cases sit in `ProjectsPageRenderTest`
(the popup's catalogue over the six floor, a rejected create returning the ticks as ticks, the page's picker locking an
occupied bucket, the category's un-ticked offers still listed, and the picker being the project manager's and nobody
else's), `ProjectServiceTest` (the board is the six then only the ticked ones, in catalogue order; a typed name goes on
the board *and* into the catalogue; a ticked and a typed name that are the same bucket make one box; a six-floor name
refused with **no project written**; a catalogue addition after the fact does not reach an existing board),
`TaskServiceTest` (tick-joins / empty-untick-leaves, an occupied bucket cannot come off, the six are not the picker's to
choose, an unknown name is management's to invent, a rightless press changes nothing, a hand-built double tick is one
column, and a bucket is locked by its card count and an emptied one is not) and `SettingsRenderTest`.
`TaskBoardRenderTest` 291-305 moved onto the new page: the board's **one** `/buckets` action is the picker, and what
stays gone is the old per-lane plumbing — `/buckets/delete`, `?lane=`, `laneName`, `laneArea`.

**Verified over real HTTP, on a throwaway only.** `verify73.ps1`, 62 checks against a fresh `%TEMP%` database on 8095
at **v15** (`Successfully applied 15 migrations` read off the boot log): **FAILS 0**, covering the popup's markup,
Settings' add line, a create that ticks one bucket and types another, the refusal round trip, the page's panel, the
catalogue widening, a card filed in a typed bucket, the occupied-bucket lock, the untick once emptied, the `Viewer`'s
missing panel and bounced POST, the six-floor and 41-character refusals, and `/static/js/app.js` serving the add hook.
Two smaller scripts proved the pieces the driver first misread: one for the length refusal standing alone (200 +
*"A bucket name is 40 characters or fewer."*), and a seven-check contract for the live save — `?buckets=ok&bucketnote=…`
landing on a page that prints both `#projectBuckets` and `#taskLane`, the popup's option list carrying the new bucket,
and three boxes ticked in the panel. Harness lessons this stage added to memory: **browser-use's `fill` of login
credentials is classifier-blocked even against a throwaway instance**, so a signed-in browser check could not be driven
that way and the in-place-save claim had to be made server-side instead; and `'new_bucket=' + $v` written *inside* an
`@(...)` element splits into two curl arguments, which is what made a 41-character name look like a pass — the app had
received an empty name.

**Deploy note.** No migration: Kotlin, `app.css`, `app.js` and `project-modal.js` only. `installDist`, and the packaged
jar proved before launch — `javap -p` showing `saveLanes` / `cardCounts` / `mayEditLanes` / `chosenFor` /
`nameProblem` / `projectBucketPanel`, and `unzip -p` counting `data-bucket-add` (3), the bucket CSS rules (10) and
`refreshBuckets` (3). The live JVM was **found down** when this stage came to deploy, so nothing had to be stopped;
`data\pts.db` was copied to `pts.pre-stage73-20261023-103610.db` (327,680 bytes, WAL already checkpointed into it) as
insurance, and the new build was launched detached through `Start-Process cmd.exe '/c', deploy\run.bat -WindowStyle
Hidden` — never from a backgrounded task. New PID **9572** on 8080, log at `Current version of schema "main": 15` /
`Schema "main" is up to date. No migration necessary.` and `Responding at http://127.0.0.1:8080`; the live check reads
`/` → 200, `/static/js/app.js` 24,882 bytes with the three `data-bucket-add` hits, `/static/css/app.css` 98,194 bytes
with the ten bucket rules. **A reader needs Ctrl+F5 once**: `app.css` and `app.js` are cached and the markup carries no
cache-buster, so without the reload the picker's boxes and its *+ Add bucket* button render unstyled and inert. Server-
drawn pages ship `no-store` already, so the page itself is never the stale half.

**If this is restated.** **Stage 75 supersedes the floor half of this paragraph**; read the first clause as history. Never re-propose: ~~dropping the six-lane floor in favour of the picked buckets, or letting a
posted list that omits one take it off (Stage 48 and Stage 63 both stand, and `LaneError.STANDARD` is the reason a
six-floor name is *refused* rather than quietly dropped)~~ — Stage 75 retired the six, `LaneError.STANDARD` is deleted, and
the three a board opens with are the picker's to untick like any other bucket; what the picker still may not do is take a
board to **zero** lanes (`LaneError.LAST_LANE`) or empty a bucket by unticking it out from under its cards (`HAS_CARDS`,
counted across every lane the press would remove before any of them is deleted). A V16 was in fact needed for the re-cut,
though no new table and no new column: ~~a V16, a new table, or a seeded starter catalogue, since
nothing here was a storage question~~ — the storage question was always *which lanes exist*, not *how they are stored*.
What stands unchanged from here:
unticking a bucket that holds cards and letting its cards fall to Unsorted, which
after Stage 52 means hiding them; rendering only the chosen category's set server-side, which restarts the create popup
under the reader on every category change; leaving the other sets merely `display:none`, since their ticks still post;
a `Viewer` given a greyed panel instead of no panel, or a per-lane **×** coming back beside the picker; a note printed
outside `#projectBuckets`, which the live swap leaves behind; *(Stage 79 deleted the page's panel and its note now
prints over the board — the rule this clause guards, one sentence per write and printed inside the block the swap
replaces, is unchanged.)* validating the typed name in JavaScript rather than in the
service; or putting the add control in `project-modal.js`, which the project page does not load. Deliberately untouched:
`addLane` / `removeLane` and the two older `/buckets` routes, `tasks.project_bucket_id`'s `ON DELETE SET NULL`, and
the compulsory filing of a new card in one of the board's own buckets.

<a id="stage-74"></a>

## Stage 74 — An empty project's board *is* its buckets, and each column can be re-shaped from the board (2026-10-23, live, no migration, Kotlin + CSS + JS)

**The ask.** *"when project does not have any bucket tasks or when creating a new projects should be looking like this"*,
with a picture (`Screenshots/buckets.png`): a row of columns — each head a coloured dot, a name, a count and a `⋯` at
the right, each foot a `+ Add task` line — trailing a dashed **+ Add bucket** box the width of a column. Four things
were settled before any code was written, because the ask reads two ways on each of them: the bucket columns show
**"Only while it has no cards"**, the columns are **"All of them: six + extras"**, the `⋯` means **"Rename / remove
that bucket"** (management and this project's manager, with Stage 73's picker still the place that ticks the whole
set), and the dashed box means **"Add to this project + the catalogue"** — the same offer the picker's typed name
makes, reached from the board.
*(**Stage 79** makes the last clause the whole story: the project page's picker — Stage 73's panel — is gone, so the
board's dashed box **is** the place a bucket is named and the whole set is posted, as hidden twins beside the one word
typed. The create popup's tick-list is the remaining picker.)*

**No migration, and again that is the point.** The schema stays at **v15**. `project_buckets` already holds the
columns and `tasks.project_bucket_id` already holds the filing; the whole stage is a *reading* change. The rule is one
line at the board: `lanes = if (tasks.isEmpty()) lanes else emptyList()`. A project with no cards is its buckets, and
from the first card onwards it is Stage 52's five status columns — nothing moves in data, because `status` and
`project_bucket_id` are separate columns of the same row and the same list is simply read two ways. The columns a
reader adds or retitles while the board is empty are still there filing cards once it is not. [taskKanban] takes the
`board-empty` invitation back exactly when this hands it a column set (`tasks.isEmpty() && canAdd && lanes.isEmpty()`),
because then the columns and their own `+ Add task` lines *are* the invitation.

**The head is a dot, a name, a count and — for one kind of lane — a `⋯`.** `laneKanban` prints
`span(bucketDotClass(index))` before the title, the same six-position cycle (`dot-${i % 6}`) the Settings catalogue
uses to name its buckets, so **QAQC** is the same shade on both pages and a seventh column is back to the first hue.
The menu is drawn only `if (mayWriteLanes && !isStandardLane(lane.name))`: the six are neither renamable nor
removable — `renameLane` and `removeLane` both refuse them as `LaneError.STANDARD` — and a `⋯` that opened a form
whose every button answers *no* is the dead control Stage 51 made a rule about. So the picture's six menus came in as
one menu on the one column that could use it, which is what `TaskBoardRenderTest` now pins (`assertEquals(1, …)` on a
board with an extra). The dashed box is a sibling of the columns, not one of them: no `data-lane`, so `kanban.js`
never offers it as a drop target and no card could ever sit in it.

**Both openers are GET forms, and the popup is the page's, not a click's.** `?laneadd=1` and `?laneedit=<id>` each
carry `data-live-target="#projectLaneModal"`, which `app.js` answers by following the URL and swapping that one node
for the copy the server drew already open — so a reader with scripts off gets the identical popup from a plain click,
and a refusal is just the same page again with its reason under the field. `?laneedit=` is a key of its own because
`?lane=` on this URL already names a code a lane request was refused with and `?into=` the lane a new card aims at.
The popup block is **drawn (shut) whenever the board is a lane board** — `laneModalDrawn = mayWriteLanes &&
tasks.isEmpty() && lanes.isNotEmpty()` — because the openers swap that node and a swap needs a target; only its
`display:block` is conditional. `lane-modal.js` is new and owns only the way out (the `×`, **Cancel**, the backdrop,
and the caret into `#laneName`), since opening is decided server-side.

**Two routes came, one route changed shape.** `POST /app/projects/{id}/buckets/rename` is new: the lane is named by
**id** — never by name, since the name is the thing being changed and the only address that survives the write is the
row's own — and checked against the project in the URL exactly as `removeLane` checks it, so a POST naming somebody
else's lane renames nothing. `POST /buckets/save` is Stage 73's picker route and now answers **two** forms, told apart
by the board's own hidden `src=board`: a picker save builds the identical URL it always did, a board save goes back
over the board. And `POST /buckets/delete`, which had no control pointing at it since Stage 35, now has one — so
before deleting it **re-asks the card count** and refuses with *"That bucket has N cards in it now, so it cannot come
off. Move them first."* The guard belongs in the route rather than in the markup because the page the popup was opened
from can be stale: the columns only exist while the project has no cards, and a card arriving between the reader
opening the popup and pressing **Remove bucket** is exactly the case a `⋯` cannot see.

**One sentence, printed once.** A bucket write answers with a single line, and the URL now says where it belongs:
`?bucketsrc=board` plus `?buckets=ok|err` plus `?bucketnote=`, read by `openingLaneForm` into a `LaneEntry`. Over the
board when a board write landed (inside `#projectBoard`, so the columns and the words about them arrive in one
repaint); under the popup's name field when a board write was refused, with the popup put back up carrying the word
the reader typed (`?lanetyped=`); in the picker for a write the picker itself made. `noteIsAtTheField = laneModalDrawn
&& lane.nameError != null` is what keeps the picker quiet in the second case — a page that answers the same refusal
twice reads as two problems rather than one — and it is deliberately gated on the popup being *drawn*, because when it
is not (the stale page where the project gained its first card in between) the sentence must fall back to the picker
rather than vanish with the form.
*(**Stage 79 re-cut this read for the panel-less page.** There is no picker on a project page to keep quiet, so
`openingLaneForm` answers one flag — `noteOnBoard = !inPopup`, where `inPopup` is a *refused* board write that names a
popup to open: `bucketsrc=board` with `laneadd=1` or a `laneedit` id. A refusal at the field still carries the word the
reader typed back and still puts the popup up; a landed write, a create-popup answer, and any sentence with no popup to
sit inside are printed over the board as `p.bucket-said ok|err`. The rule underneath — one sentence, placed where the
reader can act on it — is Stage 74's and is unchanged.)*

**Tests.** Suite **587 / 39** classes, 0 failures (571 → 587). `TaskBoardRenderTest` (62) gained the head's
dot-name-count shape, the one-menu rule, the add box trailing the run without being a column, and the rightless reader
given neither opener; `ProjectsPageRenderTest` (37) gained *"a project with no cards is its buckets and a project that
has one is its statuses"*, the dot colours matching the picker's, the popup's two halves, and the
refusal-at-the-field / landed-write-over-the-board pair; `TaskServiceTest` (103) the rename rules. Two assertions moved
onto the markup that is actually true rather than the markup one would expect: a `<form…[^>]*>` match stops at the
**opening** tag, so an ask carried as a hidden field needs a whole-`<form>`-section helper (`formSection`), and a
lane board **always** carries its popup — closed — so "the picker's answer stayed in the picker" is pinned as *the
popup is not `display:block` and no `field-err` was printed*, not as the node being absent.

**Verified over real HTTP, on a throwaway only.** `pts74_verify.ps1`, **56 checks / 0 failures** against a fresh
`%TEMP%` database on 8095 at v15: login, a created project, the six columns with six dots and six `0` counts and six
`+ Add task` lines and exactly one `lane-add-box`, no `kcol-menu` and no `board-empty` and no `data-status`, the
`?laneadd=1` popup, **QAQC** added from the board (column appears, one menu appears, exactly one `bucket-said ok`, the
popup shuts, the dot is `dot-0`, the picker lists it), the `?laneedit=` popup, a rename refused as a duplicate (name
error at the field, **zero** `bucket-said` on the page, popup still up, the typed word back), a rename that lands, a
removal returning the board to six, then the flip: a card saved gives five `data-status` columns, no bucket titles, no
add box, no popup, and a Sub-category picker that survives. A hand-built delete of the now-occupied lane answers *"so
it cannot come off"* with the count in it and the card still filed. Screenshots of the empty board and of the refusal
were rendered off the saved pages with the stylesheets inlined, and read against the picture.

**Deploy note.** No migration: Kotlin, `app.css` and the new `lane-modal.js`. `installDist` at 13:17 with nothing in
`src` newer than the dist, the old JVM stopped, then launched detached through `Start-Process cmd.exe
'/c', deploy\run.bat -WindowStyle Hidden` — never from a backgrounded task. New PID **12292** on 8080; the log reads
`Successfully validated 15 migrations` / `Current version of schema "main": 15` / `No migration necessary` and
`Responding at http://127.0.0.1:8080`, with the first due-date sweep landing normally after it. `/` → 200. **A reader
needs Ctrl+F5 once**: `app.css` and `lane-modal.js` are cached and the markup carries no cache-buster, so without the
reload the dashed box renders unstyled and the popup's `×` is inert.

**If this is restated.** **Stage 75 reversed two of the four answers recorded above** — the six are the three, and the
columns are permanent rather than *"only while it has no cards"* — so read this paragraph with those two clauses struck.
Never re-propose: ~~a `⋯` on any of the six (they refuse as `LaneError.STANDARD`, and Stage 51's
dead-control rule stands)~~ — the three are ordinary lanes and every one of them carries the menu now; what Stage 51's rule
still decides is that the menu and the dashed box hang on **`mayWriteLanes`** alone, a *right* question, so a reader whose
every button would answer *no* is given neither; ~~bucket columns on a board that has cards, which Stage 52 settled the
other way and the scoping answer here kept ("only while it has no cards")~~ — that is now the board's permanent shape, and
the five status columns live only on **Assigned tasks** and **Other tasks**;
a rename addressed by name, which cannot survive its own
write; a rename that retitles the **category's catalogue** as a side effect — a lane's heading is this board's
business, and the catalogue is Settings' and the picker's write; letting a lane with cards be removed silently, or
giving a `Viewer` either opener or the popup (both hang on `mayEditLanes`, management or this project's own manager);
drawing the popup on a status board, where nothing can open it; printing the bucket sentence in both the popup and the
picker; or a `+ Add bucket` that only touches this project — the typed name goes to the category's catalogue too, which
is what the fourth scoping answer asked for. Deliberately untouched: `tasks.project_bucket_id`'s `ON DELETE SET NULL`,
the compulsory filing of a new card in one of the board's buckets, and the picker as the one place the whole set is ticked.
*(**Stage 77** made the widening opt-in, and **Stage 79** deleted this page's picker: the whole set is now ticked only on
the **create** popup, while a board's whole set travels on the add popup's hidden twins. The clause above should be read
as "the one place the whole set is *posted*", which since Stage 79 is the board.)*

<a id="stage-75"></a>

## Stage 75 — Every project is Engineering / Procurement / Production, editable, and its board is always its buckets (2026-10-23, live, V16, Kotlin + CSS + JS)

**The ask.** *"the bucket structure is shown all in kanban and buckets have 3 different default types which is Engineering,
Procurement and Production, also the user can add more as we have on settings, assigned tasks and when clicking on a project.
Means every project types have 3 buckets default as Engineering, Procurement and Production. But the user can edit them once a
project is created. After when user clicks on their assigned tasks user able to see and edit their tasks. But shown in progress
kanban as we have not started in progress on hold blocked and completed"*. Read against the code this ask touches four surfaces
— the domain's opening lane list, a project's board, that board's editor controls, and the Assigned tasks page — and four
questions had to be answered before the first line was written, because the sentence reads two ways on each of them:

* **What happens to Stage 48's six?** — *replaced*. The six come off the board and the three go on, and the three are **not** a
  protected floor: they are an opening set that a project's own manager may retitle, untick or empty and remove exactly like a
  lane somebody added for itself.
* **Which projects get them?** — *"Seed all of them"*. Not only the next project created: the twenty already open are re-cut by
  a migration.
* **When is the board buckets and when is it statuses?** — *"Always, buckets forever"*. The project page is its bucket columns
  whether it holds one card or none; a card's status is a thing its editor says, never a place on that board.
* **Where does Add-bucket live?** — *"Other tasks was meant"*. The bucket controls live on Settings' catalogue, on **Other
  tasks**, and on a project's own page. **Assigned tasks keeps the five status columns** — that page is the reader's own day,
  cut by how far each card has got, which is the one question a bucket cannot answer.

**`DEFAULT_PROJECT_BUCKETS` is three names, in [domain/Task.kt] not in a migration.** The list is what
`ProjectService.create` seeds with the project row, what the create popup prints its tick-boxes from, what the card popup
falls back to when it has no project's lane ids to read, and the three names a migration repeats as literal SQL in its
cross-join — a mirror rather than a read, since Flyway has no Kotlin on the other side of that connection, which is why
`V16__three_phase_buckets.sql` and this list have to be changed together or a fresh board and a migrated one disagree.
[isStandardLane] survives it, but
its one remaining job is a *catalogue* question: `BucketDefaultsService.nameProblem(name, allowStandard)` refuses one of these
names as a category's **extra** bucket — a bucket every board already starts with is not a thing to be offered — and the same
function is deliberately *not* consulted by `renameLane` or `removeLane`. `LaneError.STANDARD` is deleted, which is the whole
stage in one enum entry. What still refuses a removal is work (`HAS_CARDS`) and the last column standing (`LAST_LANE`).

**`V16__three_phase_buckets.sql` adds no table and no column, and its header argues one rule: never destroy a card's
filing.** It deletes an *empty* sub-category of the six (the only case in which a lane can go without work going with it),
leaves one that holds cards standing under its own name as an ordinary lane, dedupes empty copies of the three, cross-joins
the three onto every project that lacks them, then numbers the board: the three take positions 0..2 **by name**, so a project
that already carried *Procurement* from V7 keeps its column, its cards and its place at the head of the row, and everything
else is renumbered `3 + count of lower ids`. Counting ids rather than positions is V10's reasoning carried over — an id is
never rewritten by this migration, so a row's place cannot shift under the statement computing it. It does **not** refile a
card into a phase (that is a guess about the work, and since this stage the phase is a thing its owner sets), does not touch
`type_default_buckets` (the catalogue never held one of the six, because the service refused it) and does not touch
`buckets` — the Other tasks columns V1 created and Exposed mirrors as `Buckets` (a different set for a different kind of
work). V16's own header names that table `backlog_buckets`, which has never been its name; the comment cannot be corrected
without changing the file's Flyway checksum, so the header stands as written and the docs are where the right name appears.
The name comparison is exact and case-sensitive on purpose:
a lane somebody renamed to `engineering` is unusual enough that folding it into a default silently would be a guess, so it
simply stands on after the three.

**The project page's board stopped being read two ways.** Stage 74's rule was `lanes = if (tasks.isEmpty()) lanes else
emptyList()`; now the page hands `taskKanban` its lanes always, and the branch inside the renderer — `val laneBoard =
lanes.isNotEmpty()` — is the only thing left deciding between `laneKanban` and `statusKanban`. That makes
`LaneError.LAST_LANE` (`ofProject(projectId).size < 2`) load-bearing in a new way: it is no longer a guard against a fussy
picker, it is the rule that keeps a project's page a *board*, because a project emptied to zero buckets would fall through to
the five status columns Stage 75 took off it. `Assigned tasks` and `Other tasks` reach `statusKanban` on purpose and print the
five columns; the popup's *Sub-category* select and a drag are now the only ways a card moves on a project.
*(Stage 87 later took that select off the editor of a card already on a project; on this page the drag is now the
only way to move one, and the pickers survive on create, on the backlog half and on a refusal.)*

**A `⋯` on every column, and the gate is a right rather than a name.** `laneKanban` draws the menu and the dashed
**+ Add bucket** box `if (mayWriteLanes)` — management or this project's own manager — and nothing else, so the three carry a
menu like the extras do. Stage 51's dead-control rule still decides *whether* to draw it, but the question it asks is now
"can this reader write buckets here?" rather than "is this lane one the app will let them touch?". Cards on a bucket board are
`draggable = mayDrag ?: opens` with `mayDrag = canMoveCards`, so the person a card belongs to **cannot** drag it between
buckets — that would be filing somebody else's work — and gets instead one `✓ Done` / `↺ Reopen` button (`if (opens &&
!canManage)`), which posts `/app/tasks/status` and does reload. A bucket drag posts `/app/tasks/bucket` and is finished in
place. ~~No status word prints on a card here~~ — **Stage 76** put one on the card's own top line, as a pill, precisely
because this page's columns name a phase and not a stage; a card still tints only when it is done. **Unsorted** prints *after* the board's
own lanes and only `if (unfiled.isNotEmpty())`, carries `data-unfiled` so `kanban.js` keeps it out of the drop targets, and
has no `+ Add task` line.

**The rest of the surfaces followed the list, not the board.** Settings' bucket card now keys its read-only chips
*"What every new board opens with"* with the three names where the six used to stand, and its extras number from **3** —
the first extra is a new board's fourth column and wears that column's dot, which is why the catalogue's dot index is offset
by `DEFAULT_PROJECT_BUCKETS.size`. The create popup's tick-list shows the whole set including the three, because
`ProjectService.create` writes them with the row and the list is what chooses which of them that project keeps. The general
board's and the Team sheet's *Sub-category* pickers list the three **by name** without loading any project's lane ids, and the
name is resolved against the lanes the project the card actually lands on has — so a board that renamed *Engineering* refuses
that name rather than filing the card in a column that is not there.

**A CSS trap worth keeping.** The bucket dot is sized on the bare `.bucket-dot` class rather than under `.lane-row`, and that
is not tidiness. The same span prints on a Settings row and on a column head; sized under `.lane-row` as well, the head's own
rule *tied* it on specificity and **won on order**, so every column painted the quiet grey of an uncoloured dot and the three
buckets lost the one thing that told them apart at a glance.

**Tests.** Suite **589 / 39** classes, 0 failures (587 → 589). `ProjectBucketMigrationTest` (7) pins V16 against a real
SQLite file: every existing project is given the three in the board's order; a lane holding a card under one of the three names
keeps its row and is not given a twin; a retired sub-category that holds work survives as an ordinary lane; a lane the project
added for itself stands on; a card that predates its lane is left unfiled rather than guessed at; removal unfiles and cascade
still works; and the backfill runs once and is not replayed. `TaskServiceTest` (103 → **104**) gained *the three a board opens
with are the picker's to untick, and one lane always stays* and holds *an empty bucket unticked beside an occupied one
**stays** too* — every lane the press would remove is counted before one is deleted, or `ON DELETE SET NULL` silently
un-files somebody's work. `TaskBoardRenderTest` (62) flipped its project half: `a project page is cut by its buckets and
carries no manager panel`, `a project board is cut by its lanes and never by status`, `the general board is still the five
status columns` and `the status board has no lane control either`. `ProjectsPageRenderTest` (37) reads the board as buckets
whatever it holds; `SettingsRenderTest` (43), `BucketDefaultsServiceTest` (7), `TypeDefaultBucketMigrationTest` (3) and
`ProjectServiceTest` (30) carry the catalogue, the dot offset, the per-category isolation and the extras-from-3 numbering.

**Verified over real HTTP, on a throwaway first.** V16 was run against a fresh `%TEMP%` database on 8095 (seed off,
`PTS_DB_PATH` set, `murray-pts-2.0.0.jar` excluded from the dist `lib/*` so Flyway does not see every migration twice), then
read back with `scratch/stage75-v16-read.jsh` — `jshell --class-path <sqlite-jdbc.jar>` is the raw-SQL tool here, there is no
`sqlite3` or `python` on this machine — against a copy of the live database before and after, comparing
`flyway_schema_history`, the lane sets and `tasks.project_bucket_id`, and confirming the project columns come out 0..2 then
3-on. `scratch/stage75-ui-proof.ps1` then drove the app's **own routes** over HTTP on that throwaway — login, a project
created through `POST /app/projects`, its board read **before** and **after** three cards are filed one per opening bucket, a
bucket added through `POST /app/projects/<id>/buckets`, and the reader's Assigned tasks page — and counted the columns out of
the returned markup: `class="kcol" data-lane` on the project page before and after the cards arrive (the same number both
times, which is the whole Stage 75 claim), `class="kcol" data-status` **zero** there and exactly **five** on `/app/assigned-tasks`
with each of *Not started, In progress, On hold, Blocked, Completed* printed as a `.kcol-title`. This is deliberately proven
server-side rather than by driving a browser: `browser-use fill` of login credentials is classifier-blocked even against a
throwaway, and `browser-use drag` times out on this app's HTML5 drag-and-drop, so a drag would have to be synthesised anyway
— and the filing itself is the service's, which `TaskServiceTest` already holds.

**Deploy note.** **Schema v15 → v16**, so the live database was copied to `data/pts.pre-stage75-20261023-155152.db` (+ its
`-wal`) before the write. Kotlin, `app.css`, `kanban.js` and `app.js` changed, so `installDist` with the JVM stopped, then
launched **detached** — `Invoke-CimMethod -ClassName Win32_Process -MethodName Create`, because a `Start-Process` child of a
backgrounded harness task is torn down with that task's process tree and the Stage 64 JVM died exactly that way. The instance
answering on 8080 as PID **17428** logs `Successfully validated 16 migrations` / `Current version of schema "main": 16` /
`Responding at http://127.0.0.1:8080`, and `/` → 200. **A reader needs Ctrl+F5 once**: `staticResources("/static", …)` serves
from the **classpath inside `murray-pts-2.0.0.jar`**, not from disk, so a CSS change only lands with the rebuild — and the
markup carries no cache-buster.

**If this is restated.** Never re-propose: a protected floor of any lane names — `LaneError.STANDARD` is gone and its return
undoes the ask's *"the user can edit them once a project is created"*; the project page as five status columns, populated or
not, which was Stage 52's answer and this stage's reversal of it, so if a status board appears on a project page the fix is
the picker's `LAST_LANE` rule and not the renderer; re-cutting the board by *status* under a bucket heading, or by *bucket*
under a status heading — the two pages answer two different questions and each holds exactly one of them; a bucket drag that
reloads the page (it posts `/app/tasks/bucket` and is finished in place — a visible reload counts as broken here); an owner
given the drag, since dropping a card files somebody else's work; a `⋯` gated on the lane's *name* again; a migration that
deletes a lane holding cards, refiles a card into a phase to " tidy" the board, or rewrites `tasks.project_bucket_id`;
offering *one of the three* as a Settings **extra** (that refusal stands, and it is the catalogue's only one); the
**Assigned tasks** page losing its five status columns, which the fourth scoping answer kept on purpose; or giving the
*Unsorted* column a `+ Add task` line or a drop target. Deliberately untouched: `tasks.project_bucket_id`'s
`ON DELETE SET NULL`, Stage 48's rule that a new card on a project must answer a bucket, the Other tasks `buckets` set, and the
six-position dot cycle (`BUCKET_DOT_COLOURS = 6`) — a colouring rule of six shades was never a list of six lanes, and now
fewer columns wear it.

<a id="stage-76"></a>

## Stage 76 — A project's card prints the stage it has reached (2026-10-26, live, no migration, Kotlin + CSS)

**The ask.** *"when user click on the project should be displaying the tasks in progress"*. Read literally that is a
filter, and the first answer to it was the code's: since Stage 75 a project page is cut by **bucket**, so a card's stage is
nowhere on that page — not a column head, not a word on the card, not in the header. Clicking a project therefore could not
show tasks *in progress* as opposed to any other tasks, because it showed no progress state at all. Asked which half to
change, the user chose **show each task's status on the card**: print the stage every card has reached, in the app's existing
status colours, hiding and reordering nothing.

**What changed.** Two lines in `taskCard` (`TaskBoard.kt`), above the title: the card's code and its stage now share a top
line, `.kcard-top`, and the stage is a `<span class="kstat s-…">` carrying `statusSlug(task.status)` as its class and
`task.status` as its text. It is drawn **on a lane board only** — `if (laneBoard)` — which makes this the one status word a
project page prints and the one word the two status boards do not. In `app.css`, `.kcard-top` is a `space-between` flex row
(the code left, the pill right, both `flex: 0 0 auto` so neither is squeezed) and `.kstat` is a 10.5px/700 pill in the
board's own five hues, light and dark: grey for *Not started*, amber *In progress*, orange *On hold*, red *Blocked*, green
*Completed*.

**Why the status board prints no pill.** Its column head already *is* the stage — `Not started`, `In progress`, … each
printed with the same dot the pill is coloured by — so a word on the card under it would be two places for one fact, on the
one page where the fact is already the heading. This is the same reason Stage 75 gave for keeping the two boards separate:
they answer different questions and each holds exactly one copy of the answer.

**Why the tint stayed binary.** A lane card has worn a left bar and a background only when it is *done* (Stage 75).
Colouring open cards too would paint the same amber five times down a column that is five cards deep in one phase and say
nothing the pills do not, while *green means finished* still reads at a glance with no legend. So the pill took the five-way
palette and the tint kept the one fact — which is why an open card's node is still plain `class="kcard"` and only a
`Completed` one is `kcard s-completed`.

**If this is restated.** Never re-propose: a pill on the **Assigned tasks** or **Other tasks** board, whose status columns
already print the word above every card in them; the card tint widened to all five statuses now that the word is printed
(the two are deliberately split, and the split is the design); a *filter* to in-progress cards as the reading of this ask —
the user was offered that and chose to show every card and name its stage instead, so nothing here hides, reorders or
defaults a board to one status; moving the stage into `.kmeta`, which already carries priority, due date, percentage and the
owner inside a fixed 288px column and would wrap; or a sixth colour — the pill reuses `statusSlug`'s five and `statusSlug`
falls through to `s-notstarted`, so an unknown status word cannot reach an unstyled pill. Deliberately untouched:
`statusDot` and the column heads, Stage 75's always-bucket board, `LaneError.LAST_LANE`, and `.kmove-btn`'s
post-then-`location.reload()` (pre-existing, and it means the pill always repaints).

*Superseded in part by **Stage 78**, on the user's instruction and against two of the clauses above: the word left the
`.kcard-top` line beside the code and became a dot + word on a foot line of its own, the `tcode` chip came off project
cards altogether, and the tint was widened to all five statuses — which is exactly the "never re-propose" line this
stage drew, reversed because a picture to match asked for it. `statusSlug` inside the emitted `kstat` span, the
lane-board-only condition on the word, and the no-word rule for the two status boards all stand.*

**Tests.** Suite **589 → 590** / 39 classes, 0 failures. `TaskBoardRenderTest` (62 → **63**) gained
`a lane board prints the stage and a status board leaves it to its column`, which walks all five `TASK_STATUSES` through
`TaskService.updateStatus` and asserts the exact `<span class="kstat s-…">…</span>` each time, then renders the general
board and asserts it carries no `kstat` at all, and reads the pill's own line to prove code and stage share it. Three
existing cases moved onto the new markup: `a project board is cut by its lanes and never by status` now requires the
*Not started* pill, `an assignee closes a card from a lane board with one button` requires it rather than its absence, and
`a finished card on a lane board offers to reopen itself` asserts the *In progress* **pill** beside an open card while still
denying the *In progress* **tint** — the distinction this stage drew, in one pair of assertions.

**Verified over real HTTP, on a throwaway first.** 34 checks on a fresh `%TEMP%` database on 8095 (seed off, own
`PTS_DB_PATH`): a project created through `POST /app/projects` opens with the three phase columns and **no pill** because it
has no card; three cards filed one per phase print three pills reading *In progress*, *On hold*, *Blocked*, each on the same
line as its `T-000n`; the column count is read **before and after** the cards arrive and is three both times, which is Stage
75's claim held intact by this change; one card then walked through all five statuses, each time asserting its pill and that
the number of *tinted* cards on the whole board is 1 exactly while that card is Completed and 0 otherwise.
`/app/assigned-tasks` still shows five `class="kcol" data-status` columns and **zero** `kstat`, and `/app/other-tasks` none
either. Screenshots of both pages — CSS inlined into the whole saved page, rendered by headless Chrome at 1440px — confirm no
wrap and no overflow.

**Deploy note.** No migration; schema stays **v16**. `installDist` with the JVM stopped (the live database was copied to
`data/pts.pre-stage76-20261026-111602.db` + `-wal` first, as habit rather than need), then launched detached; the instance
answering on 8080 as PID **27212** logs `Responding at http://127.0.0.1:8080`, `/` → 200, and
`GET /static/css/app.css` serves the pill rules out of the jar. **A reader needs Ctrl+F5 once** — the CSS is on the
classpath, not on disk, and the markup carries no cache-buster.

<a id="stage-77"></a>

## Stage 77 — A bucket belongs to its project; widening it to the category is a separate, management-only act (2026-10-26, live, no migration, Kotlin + CSS)

**The ask.** *"when creating a new project project takes type which are M-Manufacturing R-Repair S-Site project
L-Labor D-Distributor E-Engineering F-Internal G-Good sales and O-Other. They all have 3 types of initial buckets
which are engineering, procurement and production. Once a project been created they have 3 initial buckets also user
can create a new buckets for the project. Meaning every project has their own buckets created"*. The first two
sentences were already the app's behaviour (nine categories from the V14 catalogue, three opening lanes seeded at
positions 0–2 by Stage 75's V16), so the stage is about the last two: **a new bucket per project**, and what
*"every project has their own"* does to the rule Stage 61/73 built, where a bucket a project's picker invented was
written into `type_default_buckets` **on its own** and so quietly became the category's offer to every future project.
Two decisions were put to the user and taken there: **per-project, but offer a tick to save it for the type** (the
name a reader types stops at their board unless the box under it is pressed, and that box is **off by default**), and
**project manager too, project-local only** — which withdrew Stage 73's outright refusal of a non-management
`new_bucket`: a manager who cannot name a column for their own board has no board left to manage.

**What changed.** One flag, carried to the two writers of the catalogue and nowhere else:

- `TaskService.saveLanes(projectId, posted, newBucket, callerEmpNo, canManage, alsoForType = false)` — the board
  write is `mayEditLanes` (management **or** the account in `projects.manager_emp_no`) exactly as before, and a new
  name is now legal under that right alone. The `TypeDefaultBucketRepository.insert` that follows it is gated on
  `alsoForType && canManage`, and the refusal order's **`CATALOGUE_DENIED`** — Stage 73's own code, sitting between
  `BAD_NAME` and `HAS_CARDS` in the fixed sequence
  (`DENIED → NO_PROJECT → BAD_NAME → CATALOGUE_DENIED → HAS_CARDS → LAST_LANE`) — now answers **the tick rather than
  the bucket**, with the same one sentence *"Only
  management adds a new bucket to the X catalogue."* A tick from a caller who is not management therefore writes
  **nothing at all** — not the board, not the catalogue — because the check runs before any row is touched.
- `BucketDefaultsService.chosenFor(type, posted, canManage, alreadyOnBoard, alsoForType = false)` is the create
  popup's half of the same rule, and keeps Stage 73's **two passes**: every name is validated (and the tick's right
  checked) before any `insert`, so a refused list cannot leave the first bucket in the catalogue and the second out.
  A name the catalogue already holds is a no-op for the tick — **one row, not two** — and a name ticked twice by a
  hand-built post is one column.
- `ProjectDraft.bucketForType` (default false) feeds `ProjectService.create(… alsoForType = draft.bucketForType)`,
  and both POST routes read the flag from the same field name: `fields["also_type"].orEmpty().isNotEmpty()`.
- `Application.kt`'s `laneRedirect` adds **`widen=1`** to the URL when a save was refused *and* the tick was pressed,
  and `openingLaneForm` reads it back (`forType = query["widen"] == "1"`), so the box comes to the reader still
  ticked instead of making them ask twice. It is not added on a success, because the sentence that landed already
  said whether the category widened.
- `Projects.kt` draws the box. `bucketNewLine(type, newBucket, off, widen, ticked)` (written for the create popup
  **and** the project page's picker — *since **Stage 79** the page's picker is gone, so this call site is the create
  popup alone, and the board's tick is the `laneAddBody` half below*) puts a `label("bucket-widen")` with `input(name = "also_type")` under the new-bucket field,
  reading *"Also make it a default bucket for X"*, plus `p("bucket-widen-why")` saying what each state means;
  `widen` is passed as `canManage`, and the project page's own caption tells a manager who sees no box why:
  *"It belongs to this project; X is only told about it if management tick the box below."* The column popup's add
  body (`laneAddBody`) is the same rule in the other shape — the tick and its `p("modal-scope")` explanation are
  drawn `if (canManage)`, so a non-management reader is never shown a box that would be refused. *(**Stage 79** left
  that caption where it is, inside `laneAddBody` — that sentence and the tick are now the page's only copy of this
  rule.)* In `app.css`,
  `label.bucket-widen` is `flex: 1 1 100%` (the tick takes its own line under the name field, inside the picker's
  wrapping row) and `p.bucket-widen-why` is muted 11.5px. *(**Since Stage 92** the create popup's half is gone with the
  picker it sat in: `laneAddBody` is the tick's only home, and so is this paragraph's caption.)*
- The answer text follows the write rather than the role: `"Saved: $board"` and only a widened name appends
  *" — Dewatering added to the Site projects catalogue, so new projects of that category are offered it"*, so an
  unticked press is never receipted as a change to every future project.

**Also fixed while documenting this (the same day, same deploy).** `Settings.kt`'s bucket card still taught the old
rule — *"A bucket typed into a project's own picker lands here too, which is how the catalogue grows out of real
work rather than out of a form nobody fills in"* — which Stage 77 made false, and a reader who widened nothing here
would have found the page claiming their project's private columns should have appeared in its list. The caption now
says the reach is behind the tick (*"…it lands here only when management tick the box under the name that asks for
it… Left clear, that bucket is one board's own."*), and `bucketDefaultsPanel`'s KDoc names both the tick and
`alsoForType`. `SettingsRenderTest` pins the sentence and **denies** the old one.

**Why a tick, and why management-only.** The catalogue is a company-level statement about a category, and the ask
called for the opposite: boards that differ from each other. Automatic widening made one project's private column a
rule for every future project of that type, which is what the user's *"every project has their own buckets created"*
objects to. So the split is by **reach**, not by the right to write a name: re-shaping one board is the manager's,
deciding what every future board of that category is *offered* stays the company's, and the only thing that crosses
that line is a deliberate tick that is not shown to anyone who cannot make it stick. The catalogue remains an offer,
not an order (Stage 73): a widened name is *offered* to the next project of that category and lands on its board only
if that form ticks it. *(**Stage 98** changed what "the next project" does with the offer — `ProjectService.create` now
ticks the whole catalogue itself, so an offer lands without anybody pressing anything. What the tick still governs is
the *other* boards of the category: a widened name never reshapes them, and that is the half of Stage 77 that stands.)*

**If this is restated.** Never re-propose: a typed bucket widening its category **by itself** — that is the exact
behaviour this stage was asked to stop, and it is now behind `alsoForType && canManage` in both writers; a refusal of
a project's own manager's `new_bucket` (Stage 76's answer is withdrawn — the right to name a project-local column is
`mayEditLanes`); the tick drawn for a non-management reader, or a hand-built `also_type` from one being honoured, or
one that is silently dropped instead of refused at the field; the tick defaulting to **on** (off is the answer the
reader means almost every time); a tick that dedups nothing, so `Engineering`-style names or a name the catalogue
already holds grows a second row or a second column; `CATALOGUE_DENIED` reordered after `HAS_CARDS`/`LAST_LANE`, which
would tell a reader their tick was refused for the wrong reason; or a `widen=1` on a successful save. Deliberately
untouched: V16's three seeded phases, `LaneError.LAST_LANE`, Stage 73's two-pass validation and locked-by-card-count
boxes, the create popup's disabled-not-hidden category sets, and the fact that changing the catalogue never edits an
existing board.

**Tests.** Suite **590 → 598** / 39 classes, 0 failures. `TaskServiceTest` 104 → **107**: `a name the category does
not know is this project's own column` (re-point of the Stage 73 case that read *…is management's to invent* — the
manager's unticked add lands, management's unticked add lands no further either, and a rightless caller is still
refused outright), `the tick under the add box offers a new name to every future project of the type` (board **and**
catalogue, and the receipt says so), `a tick from a caller who is not management is refused and writes nothing`
(`CATALOGUE_DENIED`, board unchanged, catalogue unchanged), and `a phase name put back on a board is neither a
refusal nor a catalogue write` — a manager may take **Engineering** off and put it back, and that lands at the right
end of the run in the domain's own spelling without touching the category's list. `ProjectServiceTest` 30 → **32**:
Stage 73's `a bucket typed on the create page goes on the board and into the category's catalogue` was split into
`a bucket typed on the create page is this project's column alone` (and the next project of that type is not offered
it) and `a typed bucket ticked for the type also joins the category's catalogue`, with
`a tick for the type on a name the catalogue already has writes one row, not two` added — three names written, one
retired, so the file moves by two. `ProjectsPageRenderTest` 37 → **40**: `the create popup asks whether a typed
bucket is for the category as well`, `the project page offers the category tick to management only`, `the column
popup's add asks management, and asks nobody else, about the category`. `SettingsRenderTest` gained two assertions
inside the existing bucket-card case rather than a case of its own.
*(**Stage 79** retired the middle of those three names with the panel it tested. The tick's rights are now pinned where
the tick lives — `the column popup's add asks management, and asks nobody else, about the category` — and the wider
rule, who may see any bucket control at all, by the case Stage 79 wrote for it:
`the bucket controls are the project manager's too, and nobody else's`.)*

**Verified over real HTTP, on a throwaway first.** 43 checks (`TOTAL=43 FAILURES=0`) on a fresh database under
`%TEMP%` on port **8096** (seed off, own `PTS_DB_PATH`, classpath of `build/classes` + `build/resources` + the dist
jars with `murray-pts-2.0.0.jar` excluded so Flyway does not see every migration twice), driven as management and as
a hand-made second account named manager of one project: a plain create opens exactly the three phases; a create with
`new_bucket=QA hold` and **no** `also_type` puts it on that board's fourth column and leaves Settings saying the
category *"offers no buckets of its own"*; a create **with** `also_type` lands the name on its board *and* in the
category's catalogue; a later project of the category is not forced to take it, and takes it when its form ticks it.
On a project page: management see `name="also_type"` and the box names the category and explains off/on; after a save
every tick is left clear; management's ticked add answers *"added to the Site projects catalogue"* and grows the
catalogue to both names. The second account — that project's own manager — reaches its board, is offered
`name="new_bucket"` and the copy that the column *"belongs to this project"*, and is **not** shown `also_type` at all;
their unticked add saves and the answer says nothing about a catalogue, while a **hand-built** `also_type` from them
comes back as *"Only management"* in a `p.field-err` at the field (not a banner), with the board exactly as it was and
the catalogue untouched; a refusal under an otherwise-valid ticked name returns with the tick **still pressed** and
adds nothing. The rows themselves were then read straight out of the throwaway with `jshell` + the sqlite-jdbc jar
(`type_default_buckets`, the boards' `project_buckets`, the user count) after the JVM was stopped, so the HTTP
answers and the stored rows were checked separately.

**Deploy note.** No migration; schema stays **v16** (the live log reads `Current version of schema "main": 16 … No
migration necessary`). The live JVM (PID **27596**) was stopped, `data/pts.db` copied by script to
`data/pts.pre-stage77b-20261026-145110.db` + `-wal` (356,352 bytes; the two earlier `pts.pre-stage77-*` snapshots are
the same live database from the first Stage 77 deploy), then `installDist` — the dist jar is stamped
**2026-10-26 14:51:22** — and the instance was launched **detached** (`Start-Process cmd.exe '/c',deploy\run.bat
-WindowStyle Hidden`, never from a backgrounded task, whose process tree is torn down when it is reaped). It answers
on 8080 as PID **27772**, started **14:54:08** — after the jar — with `Responding at http://127.0.0.1:8080` in
`logs/pts.log` and `/` → 200. The packaged caption was proved off the jar rather than off the source: `jar xf`
extracting `mn/murray/pts/SettingsKt.class` **with its package directories into a scratch dir** (extracting into the
repo root silently leaves a stray `mn/` tree and makes `javap -classpath <scratch>` find nothing), then
`javap -verbose` shows constant `#1569` holding the new sentence and **no** `picker lands here too`. **A reader needs
Ctrl+F5 once** — Kotlin-rendered text and CSS both ship from the classpath inside the jar and the markup carries no
cache-buster.

<a id="stage-78"></a>

## Stage 78 — A project's card is its title, its plan line, and a foot line saying how far and who (2026-10-27, live, no migration, Kotlin + CSS)

**The ask.** *"when the user click on a project if project has tasks it show what bucket has what tasks just like shown
in image"*, with a picture of a three-column bucket board whose cards carry no code, a coloured dot + stage word on
their own bottom line beside the holder's avatar, and a background tinted by that stage. The grouping half of the ask
was already live (Stages 74/75: a project page *is* its buckets, each column headed by its name and count), so the
picture's difference from the running build was entirely inside the card. Asked which half to move, the user chose
**match the picture exactly** — which is a direct instruction to reverse two decisions Stage 76 drew deliberately: the
stage word as a pill on the code line, and a tint reserved for finished cards.

**What changed.** Three lines of `taskCard` (`TaskBoard.kt`), and the card is now read top-to-bottom as
**title → priority · due · percentage → stage word + holder**, on a lane board:

- `div("kcard ${statusSlug(task.status)}")` is **unconditional**, where Stage 75/76 emitted plain `class="kcard"` for
  an open card on a lane board. The five `.kcard.s-*` tint rules already existed for the status boards, so widening the
  tint needed **no new CSS at all** — only removing the `if` that withheld it.
- The code chip is now `if (!laneBoard) { div("kcard-top") { span("tcode") { +taskCode(task.id) } } }`. A project card
  prints no number: the page it sits on already names the project, and the number is in the popup its click opens.
  *(Stage 88 later changed what that chip carries on a status board — the card's project code, or a backlog card's
  bucket — while leaving this lane-board rule and the chip's markup shape as they are.)*
- The avatar moved off `.kmeta` onto a `div("kmeta kfoot")` line of its own, which a lane board emits and a status
  board does not, and the stage word is emitted into that line rather than into `.kcard-top`. Its markup string is
  byte-identical to Stage 76's — `span("kstat ${statusSlug(task.status)}") { +task.status }` — so the exact-markup
  assertions written last stage survived the move untouched; only the line it sits on and its stylesheet changed.
  `.kmeta.kfoot { justify-content: space-between }` already existed for the backlog's avatar line, so the foot needed
  no layout rule either.

In `app.css`, `.kstat` stopped being a pill: it is now the word in `var(--title)` with a `::before` dot, and the five
`… .s-<stage>::before { color: … }` rules carry the board's existing five hues on the **dot alone**. Ten background
rules (five light, five `html[data-theme="dark"]`) were deleted; no dark override replaces them because the dot hues
are the shared palette values that read on both themes. `.kcard-top` and `.kcard-top .tcode` stay, because the
Assigned board still uses them.

**Why the tint reversed rather than the word.** The picture's card has to be readable from across the room, and a
lane board's heading names a *phase*, so the tint is the only thing on that card that says where the work itself got
to — Stage 76's "five cards painting the same amber five times" objection only holds where the heading is the same
word. *Not started* deliberately keeps a bare grey left edge with no wash, which is what its `.kcard.s-notstarted`
rule has always been: an unstarted card is not a problem to be coloured.

**Why the status boards did not move.** **Assigned tasks** keeps its `T-0000n` chip on `.kcard-top`, prints no
`kstat` and no `.kfoot`, and keeps its holder inside `.kmeta`: its five columns *are* the stages, so the word is the
heading. The **Other tasks** board is untouched by this stage — its `otherCard` in `OtherTasks.kt` never had a chip,
still prints no stage word, and keeps `class="kcard s-other"` (a grey edge) rather than a status tint.
*(**Stage 95** keeps the chip and the silence exactly as written here and reverses only the last half: the backlog
card now wears the same status tint every other card wears, and `s-other` — which was that edge colour and the
whole-card opener's layout hook at once — is now `kcard-overlay`, a hook with no colour in it.)*

**If this is restated.** Never re-propose: a `tcode` chip on a project's lane card (this stage took it out on the
user's instruction, and the popup is where the number lives); a stage word on the **Assigned** or **Other tasks**
boards, whose column heads are that word; the pill *background* coming back on `.kstat` (the dot is the idiom the
priority beside it already uses — `.prio::before` — and one card holding two boxed chips is the reason the pill was a
compromise in the first place); a sixth colour, or a tint for *Not started* beyond its grey edge; or moving the
avatar back into `.kmeta` on a lane card, which is what made the foot line crowd — the fixed 288px column fits
priority · due · percentage, and does not fit a fifth cell plus the stage word. Deliberately untouched: `statusDot`
and the column heads, `cardBodyRegion`'s live-swap scope (the foot is **outside** `kbody`, so a step ticked in the
popup repaints the percentage and not the stage — the stage only changes on a move, which reloads), the drag source
`data-task-card` and its `tabindex`/`aria-label`, and Stage 77's `also_type` tick.

**Tests.** Suite **598 → 599** / 39 classes, 0 failures. `TaskBoardRenderTest` (63 → **64**) gained
`a lane card carries its stage and its holder on one foot line`, which denies `kcard-top` and `tcode` on the whole
lane render, slices the card at `<div class="kmeta kfoot">` and requires both the `kstat s-notstarted` span and the
`avatar xs` inside it, then reads the `kbody` line above and requires `prio p-high` while denying it `avatar` — the
holder is on one line, not both — and renders the status board to require `<div class="kcard-top"><span class="tcode">`
and deny `kfoot`. `a lane board prints the stage and a status board leaves it to its column` now asserts the word
**and** `class="kcard $slug"` at each of the five statuses. `a finished card on a lane board offers to reopen itself`
flipped: it had denied `class="kcard s-inprogress"` on an open lane card, and now requires it.

**Verified over real HTTP, on a throwaway first.** 56 checks, 0 failures, on a fresh `%TEMP%` database on 8096 (seed
off, own `PTS_DB_PATH`, boot off `build/classes` + `build/resources` with the dist's own application jar excluded):
two roster people added through `POST /app/settings/employee/save` so a card's avatar has initials to print, a project
through `POST /app/projects`, four cards filed across the three phases with three different priorities and holders.
The board then reads as four `data-task-card`s, **zero** `kcard-top`, **zero** `class="tcode"`, four `kmeta kfoot`,
four `kstat`, four `avatar xs`, and per-status tint counts of 1/2/1 matching the four cards. One card sliced at its
foot proves the order (title and meta above, stage word and holder inside the foot, priority with no avatar above it),
and that card then walked through all five statuses proves word and tint together at every one. `/app/assigned-tasks`
— read as the manager of a card assigned to them, since the board is personal since Stage 53 — keeps five
`class="kcol" data-status`, its `tcode` chip, no `kstat` and no `kfoot`, with its holder back on the meta line. The
served stylesheet was fetched over HTTP and read: `.kstat::before { content:` present, five `.kstat.s-` dot rules,
zero pill backgrounds. Screenshots of both pages (CSS inlined into the **whole** saved page, headless Chrome at
1440px, fresh profile per call) match the picture's card: amber *In progress*, orange *On hold*, red *Blocked*, green
*Completed*, grey-edged *Not started*, no code, one foot line.

**Deploy note.** No migration; schema stays **v16** (the live log reads `Current version of schema "main": 16 … No
migration necessary`). The live JVM (PID **13304**) was stopped, `data/pts.db` + `-wal` + `-shm` copied by script to
`data/pts.pre-stage78-20261027-090930.db*`, then `installDist` — the dist jar is stamped **2026-10-27 08:59:04**, after
both source files — and the instance was launched **detached** (`Start-Process cmd.exe '/c',deploy\run.bat
-WindowStyle Hidden`, never from a backgrounded task). It answers on 8080 as PID **28296** with `/` → 200 and
`GET /static/css/app.css` serving five `.kstat.s-` dot rules and no pill backgrounds. The renderer was proved off the
packaged jar, not the source: `unzip -o -d <scratch>` of `mn/murray/pts/TaskBoardKt.class` from
`build/install/murray-pts/lib/murray-pts-2.0.0.jar` then `javap -verbose` shows the concat recipes `kcard \u0001` and
`kstat \u0001` in the constant pool and the literals `kmeta kfoot`, `kcard-top` and `tcode` — a concatenated class
string leaves no standalone `ldc`, which is why `-v` and not `-c` is the read here. **A reader needs Ctrl+F5 once** —
the card's lines are Kotlin-rendered and the dot's rules are `app.css`, both of which ship from the classpath inside
the jar, and the markup carries no cache-buster.

<a id="stage-79"></a>

## Stage 79 — A project's buckets are its columns, and the page carries no list of them (2026-10-27, live, no migration, Kotlin + CSS)

**The ask.** *"no need this part when clicking on the project"*, with a picture of the panel that sat above a
project's board: caption *PROJECT BUCKETS*, the line "Tick the buckets a card on this project can be filed in…", three
ticked boxes (Engineering, Procurement, Production), a `New bucket, e.g. QAQC` field with **+ Add bucket**, an unticked
*Also make it a default bucket for Fabrication/Manufacturing*, and a **Save buckets** button
(`C:\Users\user\OneDrive\Pictures\Screenshots\buckets1.png`). No question was asked, because the same user's
`project.png` mock already draws a `⋯` on every column head and a dashed `+ Add bucket` box at the end of the board —
so the ask was to delete the *duplicate*, not the right. A tick-list of a board's columns, standing above that board,
asked the reader to hold two pictures of one thing in mind; the picture they sent says the board is the picture.

**What changed.** Only the project page, and only by subtraction:

- `projectBucketPanel` — the `<details>`-style block, its `id="projectBuckets"` caption, its run of
  `label.bucket-opt` boxes and its **Save buckets** form — is **deleted** from `Projects.kt`. `projectDetailContent`
  lost the `bucketForType` plumbing that panel alone consumed, and what it prints below the header is now the board,
  the board's popups, and the card popup. Nothing else.
- `bucketOption(name, ticked, off)` is down to **two** states. Stage 73 gave it a third — *ticked and locked*, a
  checked box wrapped in `disabled` with a hidden `<input type=hidden name="bucket">` twin behind it, because a
  disabled box posts nothing and a bucket that holds cards must survive the save that cannot untick it. That state
  existed **only** for the panel, so it and its twin are gone; the create popup's picker keeps the surviving two.
- `openingLaneForm` answers with `LaneEntry(form, noteOnBoard)` where `noteOnBoard = !inPopup`, and
  `inPopup = refused && bucketsrc == "board" && (add || laneId != 0)`. The consequence is the sentence a bucket write
  leaves behind: a name refused at the field prints inside the popup under the box that typed it (`field-err`), and
  every other answer — *Saved: 3 buckets on this project.*, a `HAS_CARDS` refusal, the last-column refusal from a
  delete — prints over the board as `p("bucket-said ok|err")` (Projects.kt:1203). Before this stage the panel was a
  second home for that line and it needed both.
- `app.css` lost the panel's own rules and gained the button's: `.bucket-panel .panel-caption` and
  `.bucket-panel form` are deleted (`.bucket-panel` matches nothing under `src/` any more), while the picker classes the
  **create popup** still uses — `.bucket-pick`, `.bucket-set`, `.bucket-opt`, `.bucket-new`, `label.bucket-widen` —
  stay untouched, and `.bucket-said` keeps its `flex: 1 1 100%` because that line is still a flex item inside the
  popup's picker. In their place came `.submit.danger-btn`, its `:hover:not(:disabled)` arm, and a **`:disabled`** arm
  drawn as a shut control (`--input-border` / `--muted` / `--input-bg`, `cursor: not-allowed`). See the specificity
  note below.

**What did not change.** All three routes (`/app/projects/{id}/buckets` with its `rename` and `delete` twins, plus the
whole-set `/buckets/save`) stay wired and stay `mayEditLanes` = `mayAddTasks` — management **or** the account this
project names as its manager. `TaskService.saveLanes` is untouched, so the whole-set write still folds
`posted + newBucket` into one case-insensitive set and still refuses `HAS_CARDS` / `LAST_LANE` / `BAD_NAME` /
`CATALOGUE_DENIED` at the service. The column popup's three forms, the dashed `+ Add bucket` box, the create popup's
bucket picker and Stage 77's `also_type` tick all still stand. **No migration**: schema stays **v16**.

**Why the removal cost no capability.** The panel was the page's visible copy of a list the board already *is*. The
whole-set write survives without a tick-list because the popup's add form posts a hidden `bucket` twin for **every**
lane the board currently holds alongside its `new_bucket` — that is how one column is added without losing the others.
The locked box was the panel's way of saying "this column has work in it"; the popup says the same thing where the
column is, from the same source the service consults (`ProjectBucketRepository.cardCounts`): `p.modal-scope` prints
*"This column has 1 card filed in it. Move them to another bucket first…"*, or for the last column *"A board keeps at
least one bucket, so this one cannot go. Add another column before removing this one."*, and **Remove bucket** carries
`disabled = !(held == 0 && !lastLane)`. Both rules remain service-side, so a hand-built POST that bypasses the button
is refused with the same sentence — the button merely stops a click that could not work.

**The one CSS finding.** The selector is `.submit.danger-btn`, **not** `.danger-btn`. `base.css` styles
`button.submit` — a class *and* a type — which beats a lone class selector on the specificity tie, whatever the source
order, so a bare `.danger-btn` red never landed and the button kept the green of a save. The `:hover` arm needs
`:not(:disabled)` for the same reason it needs the doubled class: a hover that repaints a shut button tells the wrong
story.

**Tests.** Suite **599 → 597** / 39 classes, 0 failures, 0 ignored (`build/reports/tests/test/index.html`, confirmed
against the 39 result XMLs). The panel's own cases went with the panel; two `ProjectsPageRenderTest` cases now hold
the shape: `the project page prints no bucket panel and its board holds the only bucket forms` denies
`id="projectBuckets"`, *Save buckets*, *Project buckets* and `class="bucket-opt"` on the project page and requires the
save route **exactly once**, inside the block after `id="projectLaneModal"`; and `the bucket controls are the project
manager's too, and nobody else's` gives that manager the add box, one `kcol-menu` per column and the bucket form, and
gives a Viewer neither the opener nor a form the route would refuse. `TaskBoardRenderTest`'s
`a project page is cut by its buckets and carries no manager panel` pins the same absence plus the live swap —
`data-live-target="#projectBoard"` and `data-live-extra="#taskLane,#projectLaneModal"` on that single form.
`the column popup's add asks management, and asks nobody else, about the category` is where Stage 77's
rights pin moved to, off the deleted panel case.

**Verified over real HTTP, on a throwaway first.** **101 checks, 0 failures**, on a fresh `%TEMP%` database on **8097**
(seed off, own `PTS_DB_PATH`, booted off `build/classes` + `build/resources` with `murray-pts-2.0.0.jar` excluded from
the dist `lib/*` so Flyway cannot see V16 twice), across twelve sections: the panel's absence on a project page;
**QAQC** added through the column popup's dashed box and appearing as a fourth `class="kcol" data-lane`; a name the
board already holds landing with *nothing* added (`saveLanes` dedupes case-insensitively — this is not a refusal,
whereas the older `addLane` route does refuse a duplicate); a 49-character name refused **at the field** with the
popup still open and `bucket-said` at zero; a rename; the delete of an empty column; a delete refused on an occupied
one, with the popup's count line and the unpressable button read off the served markup; the create popup's picker still
offering the category's set; a non-management account that **is** this project's manager getting the controls and using
them; a plain Viewer getting none of them and three hand-built posts landing on `/app/projects` having written
nothing; and the **served** stylesheet, fetched over HTTP and counted — four `submit.danger-btn` hits (the rule, the
comment, the hover arm, the `:disabled` arm), three `bucket-said`, **zero** `bucket-panel`, **zero** `projectBuckets`.
Screenshots at `%TEMP%\pts79shot\project_board.png`,
`column_popup.png` and `empty_column_popup.png` (CSS inlined into the **whole** saved page, headless Chrome, one fresh
profile per call) show a page whose only bucket furniture is the board's own.

**Deploy note.** No migration; schema stays **v16**. The live JVM (PID **28296**) was stopped first, then
`data/pts.db` + `-wal` + `-shm` were copied by script to `data/pts.pre-stage79-20261027-102457.db*`, then
`installDist` — the dist jar is stamped **2026-10-27 10:25**, after every source edit — and the instance was launched
**detached** (`Start-Process cmd.exe '/c',deploy\run.bat -WindowStyle Hidden`, never from a backgrounded task). It
answers on 8080 as PID **4324** (started 10:25:35) with `/` → 200, and `GET /static/css/app.css` over HTTP carries the
four `.submit.danger-btn` hits. The renderer was proved off the packaged jar rather than from disk: `jar.exe xf` of
`mn/murray/pts/ProjectsKt.class` into a scratch directory (the extraction lands in the **current** directory — a
`Push-Location` mistake here writes `mn/murray/pts/…` into the repo root and then reports zero hits) and `javap` shows
no `projectBuckets` / *Save buckets* literal left. **A reader needs Ctrl+F5 once** — the panel is Kotlin-rendered and
the button's rules are `app.css`, and both ship from the classpath inside the jar.

**If this is restated.** Never re-propose: a tick-list of buckets above a project's board (this stage deleted the
second picture of one thing — the columns *are* the list); a locked/greyed tick box as the "this column holds work"
signal, which is now the popup's count line over an unpressable **Remove bucket**; a page-top banner or a `flash` for a
bucket refusal (the field's `field-err`, or one `bucket-said` line over the board — errors live at the thing they
constrain); deleting `also_type` from the **create** popup's typed line, which is where a name is offered to the
category and the only place that tick survives; an unscoped `.danger-btn` selector; or a second `/buckets/save` form
anywhere on the project page. Deliberately untouched: `saveLanes` and its refusal set, the hidden twins the add form
posts, `BucketDefaultsService` and the category catalogue, `#taskLane`'s live-swap scope, and Stage 78's card.

<a id="stage-80"></a>

## Stage 80 — A bucket wears a colour somebody chose, and a column's finished work folds away (2026-10-27, live, V17, Kotlin + CSS + JS)

**The ask.** *"can you copy bucket and project related architecture and interface from this project to ours"*, with
the standalone prototype `C:\Users\user\Downloads\murray_pts.html` attached. One AskUserQuestion settled the two
things that could not be read off the picture: the **direction** (prototype → the Kotlin app, not the other way
round) and the **scope**, answered as a multi-select of four and returned as two — *Bucket / board interface* and
*Bucket data model + rules*. *Project fields* and *Rights model* were left out, so the prototype's project form and
its role rules were not copied at all. Everything after that was worked out without asking: the resume instruction
barred further questions, so the four conflicts listed below are **decisions made in the user's name, not answers
received from them**, and each is restated here with the reason.

**What the prototype actually holds** (read off its own source, since it is the design being copied): a bucket is
`{id, name, color, tasks[]}`; a project is `buckets[]` plus `ARCHIVE`; deleting a bucket pushes its tasks into a
project-wide `ARCHIVE` array (`ARCHIVE.push({task, projectId, bucket.id, bucketName})`) rather than refusing;
`pickBucketColor()` returns `palette[Math.floor(Math.random()*palette.length)]` from **five** hexes
(`#7c5cd6 #e0578f #2e9e5b #c9781f #1c5a91`) — **there is no colour picker anywhere in it**, a bucket's hue is a
lottery; the rename is a `contenteditable` heading and the add is a `prompt()`; and the completed fold is
`state.completedCollapsedByType` — a `▸/▾ Completed N` line per group, kept in browser memory.

**What was ported.** Two things, both of which the app now does its own way:

- **A bucket's own colour.** `V17__bucket_colour.sql` adds a nullable `colour_slot INTEGER` to `project_buckets`
  and to `type_default_buckets`; `Tables.kt:105` and `:128` mirror it, and `domain/Task.kt:173` carries it on
  `ProjectBucket`. **A slot, never a hex** (conflict 1, decided here): `BUCKET_DOT_COLOURS = 6` (Task.kt:237) is
  the count Kotlin agrees with app.css's `.dot-0 … .dot-5` on, `BUCKET_COLOUR_NAMES` names them
  (`blue amber teal violet rose slate`), `effectiveColourSlot(index, colourSlot) = (colourSlot ?: index).coerceAtLeast(0) % 6`
  (:243) is the whole rule — *wear your own colour, else the shade of your position* — and
  `colourSlotOf(raw)` (:256) reads a posted value back as **no answer at all** unless it is an integer in
  `[0, 6)`. NULL is what every existing row has, so **no column on the live board changed shade when this shipped**.
- **The completed fold.** `laneKanban` (TaskBoard.kt:542) splits each lane's cards into live and
  `STATUS_COMPLETED`, prints the live ones, then `laneDoneToggle` (:677), then the finished ones inside
  `div("kcol-done")` which carries `hidden` unless the column is named. A column with nothing finished in it prints
  no line; the head's `kcol-count` still counts **every** card.

**How each is wired server-side** (conflict 2: the prototype keeps both in the browser session, which would be a
second source of truth on a page whose rule is *the server renders the board*):

- The fold lives in the **URL**. `openDoneLanesOf(params)` (TaskBoard.kt:360) reads the repeated
  `?showdone=<lane id>` keys; the route passes the set into the renderer (Application.kt:505), and each toggle is a
  **GET form onto the project page** carrying *the set as it will be after the press* — this column in it when shut,
  out of it when open, every other open column carried along. `app.js` already routes any form with
  `data-live-target` through `sendToBlock`, which builds the URL with `new URLSearchParams(new FormData(form))`
  (repeated names survive) and swaps `#projectBoard`. Scripts off, the same press loads the same page and the same
  block comes back open, and the opened URL is bookmarkable.
- The colour lives in the **form**. `bucketColourPicker(current, fieldName = "colour")` (TaskBoard.kt:718) is one
  row of six `label.colour-opt` radio dots with a `colour-caption`, and it had exactly three callers:
  `laneAddBody` (Projects.kt:728), `laneManageBody` (:828) and Settings' catalogue add line
  (`bucketDefaultsPanel`, Settings.kt:929). The routes read `fields["colour"]` through `colourSlotOf` at
  Application.kt:968 (Settings add), :1184 (board `/buckets/save`) and :1250 (`/buckets/rename`).
  **Stage 83 took the row off both creation forms at the user's request**, leaving `laneManageBody` as its only
  caller and `/buckets/rename` as its only reader; see the Stage 83 section.
- **Every row is pre-checked at the colour the column already wears** — `laneCount % 6` on the add half,
  `effectiveColourSlot(boardIndex, lane.colourSlot)` on the rename half,
  `(DEFAULT_PROJECT_BUCKETS.size + extras.size) % 6` on the catalogue line. An untouched form therefore posts the
  shade that is already painted, so a plain rename cannot recolour a column by accident, and a colour-only post is
  still a legitimate write: `TaskService.renameLane` (:596) computes `recolour = slot != null && slot != lane.colourSlot`
  and calls `ProjectBucketRepository.setColour` (:405) even when the name is unchanged. (Since **Stage 83** only the
  rename half is left; creation has no swatch row to pre-check.)
- A **refusal carries the swatch back**: `lanecolour=<slot>` on the project redirect (Application.kt:241, read at
  :300) and `bucketcolour=<slot>` on the Settings one (:764), for the same reason `lanetyped=` carries the name
  back — a reader should not answer the same question twice. A save that *landed* adds no such key. (**Stage 83**
  removed `bucketcolour` with the Settings swatch; `lanecolour` still carries a recolour back.)
- **One known limit, stated rather than papered over:** the fold does not survive a *bucket* write. The column
  popup's save form names `#projectBoard` as its live target and its redirect carries `?buckets=ok…` with no
  `showdone`, so adding or removing a column redraws the board fully folded. That was accepted because the moment it
  happens is the moment the reader has just re-shaped the columns and is looking at the new head, not at a folded
  block — and because carrying the set through would mean threading the open lanes into `projectRedirect`, which
  answers six other writes that have no fold to lose. A *card* write never touches it: `/app/tasks/status` redirects
  to its Referer, which is the URL that already names the open columns.

**What was deliberately NOT ported** (conflict 3, the data-safety one). The prototype's
**delete-a-bucket-moves-its-tasks-to-`ARCHIVE`** rule was refused, on a live production database with real cards on
it: at the time PTS kept Stage 31's `HAS_CARDS` refusal, so a column with work in it could not go until the work
moved. **Stage 81 superseded this at the user's later request** — a column now leaves with its cards, and they land
in a project Archive panel that is on the page and shows them (see the Stage 81 section below). There is still no
hidden archive *table*: the flag is on the card's own row. Also left behind: `contenteditable` rename and
`prompt()` add (both are client-side writes with nowhere to print a refusal, and this app's four name rules —
blank, `PROJECT_BUCKET_MAX`, duplicate, `CATALOGUE_DENIED` — are service-side), the prototype's **five** hues
(the stylesheet already had six, and matching them would have meant repainting the calendar's and the Settings'
dots for nothing), and its absence of `LAST_LANE` / duplicate / length rules. The prototype's project fields and
rights model were out of the chosen scope and untouched.

**The improvement over the source**, stated plainly because it is not a copy: the prototype has **no colour UI at
all** — `pickBucketColor()` assigns a hue at random, so two buckets can collide and a reader cannot choose. The
swatch row is what that design was missing, and it is the reason the stored value is a slot: a random hex could
paint a column unreadable against either theme, whereas a slot can only be one of the six the stylesheet already
carries. **Stage 83 kept the slot and the rule but moved the choosing**: a new bucket (catalogue or board) is painted
by its place in the run, and the six swatches are offered when an existing column is recoloured from its `⋯`.

**CSS.** Two blocks in `app.css`: `.colour-row / .colour-caption / label.colour-opt / .colour-opt input[type=radio]
/ .colour-opt .bucket-dot` and its `:checked` ring and `:focus-visible` outline (around :770), and `.kdone-btn`
with `.kcol-done[hidden] { display: none }` plus
`.kcol .kcol-done + .kcol-empty, .kcol .kcol-done .kcard + .kcol-empty { display: none }` (around :1148). The radio
is **faded to `opacity: .01`, not removed**, so the accessibility target and its focus ring survive; the fold's
empty-column line steps out for a folded block, because a column holding only finished cards is not a column with
nothing in it. `.colour-row` is `flex: 1 1 100%` with `margin: 2px 0 8px` so it takes a line of its own under the
name box and whatever follows reads as the next question.

**JS.** `kanban.js` changed in one place, and only because the fold moved the DOM: a card's column is no longer its
parent, so `file()` asks `card.closest('.kcol')` instead of `parentNode`, and a drop lands **above** the fold line
(`col.querySelector('.kdone-btn') || col.querySelector('.kcol-empty')`) so a card dragged out of Completed does not
disappear under it. `count()` was already asking for every `.kcard` under the column, so the head's total is right
either way.

**Tests.** Suite **597 → 606**, in **39 → 40 classes**, 0 failures (`build/reports/tests/test/index.html`,
recounted off the 40 result XMLs). The new class is `db/BucketColourMigrationTest`:
*V17 adds one nullable slot to each bucket table and writes no colours* and
*the slot is nullable, holds an integer, and a second boot changes nothing*. `TypeDefaultBucketMigrationTest` moved
onto *the catalogue carries V12's four columns plus the colour V17 added*. The markup is pinned by
`ProjectsPageRenderTest`'s *an empty board colours each bucket the way Settings colours it*,
*a column that was given a colour wears it and one that was not wears its position* and
*the colour a reader chose on the popup is the swatch checked when it comes back*; by
`SettingsRenderTest`'s *a default that was given a colour wears it on the catalogue row*; and by four
`TaskBoardRenderTest` cases — *a lane head is its colour, its name and its count*,
*a column with a finished card folds it behind a count, and a column without prints no line*,
*opening one column leaves the others folded and its own press closes it again*, and
*an unfiled column keeps no fold and the status board keeps none at all*. The last of those is the scope rule
written as a test: the fold is a **lane board** feature, so `#projectBoard`'s unfiled column, both status boards and
the backlog print no line.

**Verified over real HTTP, on a throwaway first.** **76 checks, 0 failures**, on a fresh `%TEMP%` database on
**8097** (seed off, own `PTS_DB_PATH`, booted off `build/classes` + `build/resources` with `murray-pts-2.0.0.jar`
excluded from the dist `lib/*` so Flyway cannot see the migrations twice). The sections cover: V17 on a real boot;
the add half of the popup drawing six swatches pre-checked at the next position; **QAQC** added at `colour=5` and
arriving as a slate dot on the fourth head; a rename to `colour=4` repainting the head with its name unchanged;
`colour=9` and `colour=-1` reading as *no answer* so the column keeps its positional shade; a plain rename not
touching the colour; a 49-character name refused **at the field** with the chosen swatch still checked and the word
still in the box; the fold line drawn only where finished work exists, its `▸`/`▾` glyph, its `method="get"`, its
`data-live-target="#projectBoard"` and its `showdone` hidden twins; `?showdone=<one>` opening exactly one of two
folded columns and `?showdone=abc` folding everything; a Viewer's own board and the backlog carrying no fold at all;
the Settings add line's colour, its refusal keeping the swatch and its panel coming back to the same category; a new
project inheriting the named defaults **in their colours**; and the served stylesheet and `app.js` counted over HTTP.
Screenshots at `%TEMP%\pts80shot\`: `board_folded.png`, `board_open.png`, `column_popup.png`,
`settings_catalogue.png` (CSS inlined into the **whole** saved page, headless Chrome, one fresh profile per call).
Two defects surfaced only in the pictures, and both were fixed before deploy: the add popup's caption under
*Also make it a default bucket…* was written `p("modal-scope")`, whose `margin: -10px` pulls it up under a heading —
on this line it landed **on top of** the bold label, so it now uses `bucket-widen-why`, the class that knows it
follows a row of its own; and `.colour-row` needed a bottom margin so the category tick reads as the next question.

**Deploy note.** **V17 is live.** The live JVM (PID **4324**) was stopped first, then `data/pts.db` + `-wal` + `-shm`
were copied by script to `data/pts.pre-stage80-20261027-123125.db*`, then `installDist` (the dist jar is stamped
**2026-10-27 12:31**, after every source edit), and the instance was launched **detached**
(`Start-Process cmd.exe '/c',deploy\run.bat -WindowStyle Hidden`, never from a backgrounded task). It answers on 8080
as PID **6708** with `/` → 200, and `logs/pts.log` records `Current version of schema "main": 16` →
`Migrating schema "main" to version "17 - bucket colour"` → `Successfully applied 1 migration … now at version v17`.
`GET /static/css/app.css` over HTTP carries the five `.colour-row` / `.kdone-btn` hits. **A reader needs Ctrl+F5
once** — the swatches and the fold line are Kotlin-rendered and their rules are `app.css`, both of which ship from
the classpath inside the jar, and the markup carries no cache-buster.

**If this is restated.** Never re-propose: a **hex** in `colour_slot` or a `<input type=color>` (the hues are the
stylesheet's, and a slot is the only value that cannot paint an unreadable column); storing the fold in
`localStorage` or a script variable (the URL is the state — that is what makes it work with scripts off and be
shareable); `contenteditable` or `prompt()` for a name
(a write with nowhere to print its refusal); folding on a **status** board or the backlog (a status column *is*
Completed, so its fold would hide the point of the page); or five hues to match the prototype. Deliberately
untouched: `saveLanes` and its refusal set, `BucketDefaultsService`'s catalogue rules, Stage 77's `also_type` tick,
Stage 78's card, `kcol-count`'s meaning, and the create popup's picker. **Archiving on delete has since left this
list**: Stage 81 built it, with a panel that shows the cards rather than a bucket that hides them.

<a id="stage-81"></a>

## Stage 81 — A bucket leaves with its work, and the work waits in the project's Archive (2026-10-27, live, V18, Kotlin + SQL + CSS + JS)

**The ask.** *"make buckets work same as murray_pts.html's buckets"*, with the standalone prototype attached — the
same request Stage 80 answered in part. Stage 80 took two of the four things that sentence covers (a bucket's colour,
the completed fold) and **named the rest as deliberately refused**: the prototype's *delete a bucket and push its
cards into a project-wide `ARCHIVE`* was called a data-safety risk on a live database, and PTS kept Stage 31's
`HAS_CARDS` refusal. Stage 81 is that sentence finished, at the user's later instruction: the prototype's archive is
now the app's behaviour, built on PTS's terms rather than copied.

**What the prototype does, and what is different here.** In `murray_pts.html`, removing a bucket filters it out of
the project and pushes each of its tasks, with the bucket's id and name, onto one project-wide `ARCHIVE` array; an
Archive sheet lists every row as *was in "…"* with **Restore** and **Delete**, restore files the card back into the
bucket it remembers or the project's first bucket when that one has gone, and delete drops the entry for good. The
shape of that behaviour is what shipped. What could not ship as written is the prototype's *storage*: its archive is
a second array in browser memory, which on this app would be a second source of truth beside a server-rendered board.
PTS keeps the card's own row instead (below), and every answer a reader gets is still the server's.

**The model: a flag on the card, never a second table** (`V18__task_archive.sql`). Four columns on `tasks`:
`archived BOOLEAN NOT NULL DEFAULT 0`, `archived_lane_id INTEGER` nullable, `archived_lane TEXT` nullable and
`archived_on TEXT` nullable; `Tables.kt:177` mirrors them and `domain/Task.kt:94` carries them on `TaskItem`. The
reason is the card's children. A PTS card owns its plan (`task_checklist_items`, V5), its log (`task_notes`, V6) and
its lesson rows by foreign key; moving a card into an archive table would mean deleting and re-inserting it — which
cascades those children away and hands them new ids — or duplicating three child tables behind an indirection.
Flagging the row leaves every child exactly where it was, so *restored as it left* is not a promise the code has to
keep: it is what the schema cannot avoid. A card that left with three of four steps ticked comes back with three of
four steps ticked, the same notes by the same authors, and the same id — so Lessons, alerts and the forecast ledger
never have to learn that a card was ever away.

`archived` is the only thing a read has to know. `archived_lane_id` is the column, stored as a **bare integer with no
foreign key**, and that is deliberate: the path that matters most deletes the lane row in the same breath, and a
referencing column would be `SET NULL` by the very statement that archives the card — erasing the memory it exists to
keep. It is therefore a **snapshot read as a hint**: restore goes back to that lane while the lane is still on this
project, and otherwise to the project's first lane. `archived_lane` is the lane's *name*, for the panel's *was in "…"*
line after the row is gone; a name can go stale, but it is only ever used to tell a story about the past, never to
file anything. `archived_on` is the day, in the `yyyy-MM-dd` the rest of the app prints (`TaskService.archiveDay`,
UTC), and it is the sort key that makes the panel newest-first. No index: the archived set is small by construction —
work somebody took off a board — and a board reads its cards through `kind`, not through the archive. The card's
`project_id` and `status` are deliberately untouched: an archived card still belongs to its project (that is what the
per-project panel lists) and comes back where it got to, because *how far the work went* is not what archiving hides.

**Every read that shows work hides an archived card, and every read that tells a story does not.** The board's own
query (`TaskRepository.findByKind`, ProjectRepositories.kt:585) filters `archived eq false`, so an archived card is on
no board — including the assigned board, which is the same query. `ProjectBucketRepository.cardCounts` (:334) filters
it too, and in both of its two uses that is now the truthful answer: the head's count is live work, and the removal
confirm's *"Its N card(s) will be moved to Archive"* is what the press will actually move. `AlertRepository.openTasks`
(AlertRepository.kt:106) drops them so a due-date mail is not sent for work nobody is looking at, and
`LessonRepository.taskChoices` (LessonRepositories.kt:120) drops them from the lesson task picker. The lesson *context*
read is left alone: a lesson already written about a card is history, and history does not lose its subject.

**The writers.** `removeLane` (TaskService.kt:577) is the prototype's delete: `mayEditLanes`, project/lane checks,
`LAST_LANE` refusal when it is the board's only column, then `TaskRepository.archiveLane(laneId, lane.name, archiveDay())`
**before** `ProjectBucketRepository.delete(laneId)` — the only order that works, since the delete fires
`ON DELETE SET NULL` on `tasks.project_bucket_id`, and a card nulled first has already lost the column the snapshot is
supposed to remember. It answers `LaneRemoval(archived, error)` (TaskService.kt:1404), and **`LaneError.HAS_CARDS` is
gone**: an occupied column is no longer a refusal anywhere, so the route (`Application.kt:1515`) no longer re-counts
after the page was drawn and the Stage 74 stale-page guard has retired with the error it protected. The board's
answer names the work: *"That bucket is off the board, and its 3 cards went to Archive."* `saveLanes` (:860) is the
same rule through the tick-box path: an unticked occupied lane has its cards archived before its row is dropped, and
the closing sentence appends *"— 1 card went to Archive"* (`LaneSaveResult.message`, asserted in `TaskServiceTest`), so
a reader who just took three cards off the board is told where they went rather than left to find the column empty.
`LAST_LANE` is the one refusal left on that path: *"A board keeps at least one bucket — tick one to leave standing."*

`archiveCard` (:600) is the second door in — one card, from its own popup. It refuses a backlog card (the Other tasks
board is cut by status and has no lanes to come back into, and its cards keep management's hard **Delete task**), a
card already archived (a second write would move the snapshot) and a card with no project; then it applies the same
`mayEditLanes` line. `restoreCard` (:625) clears the flag and names a lane, returning the lane's **name** so the page
can say where the card actually landed: the remembered lane while that row still belongs to this project, otherwise
the board's first column (`ProjectBucketRepository.ofProject(projectId).firstOrNull()`), and null when there is
nothing to restore into. `deleteArchived` (:653) is the panel's permanent delete, gated on the card *actually being
archived and on this project*, so a hand-built POST naming a live card destroys nothing; the children need no
cleanup because the FK cascade and `PRAGMA foreign_keys` do what `deleteAssigned` has always relied on.

**The right is the board's, not the card's.** All five writes go through `mayEditLanes` (TaskService.kt:502, which is
`mayAddTasks`) — management, or the account this project names as its manager. That is deliberately **not**
`openableBy` (renamed from `editableBy` in Stage 86, when every reader could open a project card): an assignee may
book time on a card, tick its steps and write its log, while taking the card off
somebody else's board, putting it back, or destroying it for good is the project's call — the same line
`deleteAssigned` has drawn since Stage 17 — the right Stage 81's archive button was drawn for, before Stage 84 took
that button off the popup.

**An archived card is not a card on any board, and Stage 81 had to say so three more times.** HTTP verification
found it: archiving a card and then hand-typing `?edit=<id>` still opened the card's popup, courtesy of a page that
had already decided the reader may write it. `openableBy` (:303, formerly `editableBy`) returns null for an archived card — still the first guard after Stage 86 widened the read — so the URL
lands on the ordinary page; `updateAssigned` (:339) refuses a hand-built save with a `TaskField.PERMISSION` message
naming the Archive (*"That task is in this project's Archive. Restore it before changing it."*); and `deleteAssigned`
(:477) refuses an archived id even for management, because the panel's *Delete permanently* is the only permanent
delete a project card has now. **Restore is the only way back**, from the panel, deliberately.

**The UI: 🗄 in the project header, and `?archive=1` in the URL.** `archiveOpener` (Projects.kt:905) is drawn in
**both** of the page's headers — management edits the strip above, a project manager who is not management reads it —
and on `mayWriteLanes` alone: a Viewer gets no button, and a Viewer who bookmarks `?archive=1` gets the page with the
panel shut (Application.kt:519 asks the role before it reads the key), which is Stage 74's rule that a control nobody
may use is not drawn at all. The opener is a **GET form onto the project page** carrying a hidden `archive=1` when
closed and none when open, so one press opens and the next closes, `data-live-target="#projectArchive"` swaps the
panel alone, and with scripts off the same press loads the same page with the panel open. `projectArchivePanel`
(Projects.kt:938) is that block: `#projectArchive.modal` with `style="display:block"` only when open, a head naming
*Archived tasks (N)* and a ✕ that is the same GET form without the key, an `.archive-empty` line when there is
nothing, and one `.archive-item` per card carrying its title, `"S-01 · was in \"Production\""` (the project code and
the remembered name; *no bucket* when the snapshot is blank) and the two buttons. **Restore** and **Delete
permanently** are POSTs to `/app/projects/{id}/archive/restore` and `…/archive/delete`, each with a hidden `taskId`,
`data-live-target="#projectArchive"` plus `data-live-extra="#projectBoard"` so one press redraws both the list and
the board, and the delete's `data-confirm` spells out that the steps and notes go with it and that it cannot be
undone. Both redirects carry `?archive=1` (`laneRedirect`'s `archive` flag, Application.kt:236-243, set at :1597 and
:1627), which is what makes the panel feel like a panel instead of a form that throws the reader out of the page on
every press; a restore that had to land in the first column says so in its sentence rather than silently filing the
card somewhere the reader did not ask for.

**The card popup's foot now speaks by world, not by viewer** (TaskBoard.kt:1630-1670). A card filed under a project
puts **Archive task** where **Delete task** used to be — a POST to `/app/tasks/archive` with `taskId`, `back`,
`projectId` and `allbuckets` when the wide view is in use, no `edit` because there is no card to reopen, a confirm
that says it leaves the board and waits in the project's Archived tasks, and the `.task-del` class Stage 72 solved so
the two buttons share the note box's foot line. A **backlog** card keeps management's **Delete task**: the Other
tasks board has no project page to come back from. `TaskForm.mayArchive` (:127) is the gate, and it is false for a
non-edit, an archived card, a backlog card, a card with no project, an empty viewer, or a viewer short of
`mayEditLanes` — so an assignee who opens their own card gets neither button, exactly as they never had the delete.
**Stage 84 undid this split at the user's later request**: the popup offers management's **Delete task** on every card
again, `TaskForm.mayArchive` and `taskArchiveForm` are gone, and a card reaches the Archive only by leaving with its
bucket. The `/app/tasks/archive` route and `TaskService.archiveCard` survive, with no page printing a control for
them; the bucket removal's own path is `TaskService.archiveLane` + `TaskRepository.archiveLane`, untouched.

**CSS and JS.** `app.css` around :1342-1381 carries `.archive-panel` (max-width 560px), `.archive-head` /
`.archive-title` with a dark-theme border, `.archive-item` / `.at-title` / `.at-meta` / `.at-actions` (plus the delete's
metric override so the two small buttons sit level) and `.icon-btn.archive-btn.on`. `archive-modal.js` is the only
script: it closes the panel on a backdrop press or Escape, finds `#projectArchive` afresh on every event because live
swaps replace the node, and on close strips **only** the `archive` key from the address bar with `history.replaceState`
— leaving `buckets`, `bucketnote` and `bucketsrc` untouched — so that shut really stays shut on reload and on Back,
which is the one thing a close must guarantee. `app.js` needed no change: `data-live-target` + `data-live-extra`
already fetch the page and swap both blocks.

**Tests.** Suite **606 → 615**, in **40 → 41 classes**, 0 failures (`build/reports/tests/test/index.html`, recounted
off the 41 result XMLs: 615 `<testcase>`). The new class is `db/TaskArchiveMigrationTest`: *V18 flags no card that was
already on the table and keeps every lane filing* and *the flag defaults to zero, the snapshot is nullable, and a
second boot changes nothing*. `TaskServiceTest` gained or rewrote *removing a lane puts its cards in the archive with
the lane they stood in*, *an archived card comes back to its own lane when the lane is still on the board*, *archiving
a card is the board's right, not its assignee's*, *only an archived card of this project can be deleted for good, and
only by the board's right*, *a card in the archive cannot be opened, saved or deleted from the board*, *a board keeps
its last lane*, and the two `saveLanes` cases about unticked occupied lanes (*a bucket that still holds cards is
unticked with them, and they go to the archive* / *an empty bucket unticked beside an occupied one takes the occupied
one's work to the archive*). `ProjectsPageRenderTest` pins the panel: *the header offers the Archive to the board's
writers and draws it shut*, *the archive panel prints a put-away card with where it came from and the two things it can
do*, and *an archived card is off the board, its column's count and the page's own list*. `TaskBoardRenderTest` pins
the popup's split in four: *a project card's popup carries an Archive task button beside the note box*, *nobody else is
offered the Archive or Delete task button*, *the two buttons share the note box's own foot line*, and *a delete from
the wide backlog view comes back on that view*. *(**Stage 84 rewrote the first, second and third**: a project card's
popup now carries **Delete task**, nobody but management is offered any button, and the foot-line case reads
`>Delete task<` in either world; the wide-backlog case is unchanged.)*

**Verified over real HTTP, on a throwaway first** (own `PTS_DB_PATH`, seed off, booted off `build/classes` +
`build/resources` so Flyway could not see the migrations twice). An occupied lane removed from the board archived its
card with the lane's id and name remembered and the column's count dropping to zero; the card was gone from the board
and from the assigned list while the panel still showed `was in "…"`; restoring it after the original column had been
removed landed it in the board's first column; an archived card's hand-typed `?edit=` came back as the ordinary page,
and a hand-built save of it changed nothing; *Delete permanently* removed the row and the panel came back at
*Archived tasks (0)* / *No archived tasks.*; `/static/js/archive-modal.js` was served 200.

**Deploy note.** **V18 is live.** The live JVM was stopped, `data/pts.db` + `-wal` + `-shm` were copied by script to
`data/pts.pre-stage81-20261027-1616.db*` (368640 / 0 / 32768 bytes), then `installDist`, then the instance was
launched **detached** (`Start-Process cmd.exe '/c',deploy\run.bat -WindowStyle Hidden`, never from a backgrounded
task). It answers on 8080 as PID **13020** with `/` → 200, and `logs/pts.log` records (UTC timestamps) `Current
version of schema "main": 17` → `Migrating schema "main" to version "18 - task archive"` → `Successfully applied 1
migration … now at version v18`, with `GET /static/css/app.css` and `GET /static/js/archive-modal.js` both 200. **A
reader needs Ctrl+F5 once** — the 🗄, the panel and its rules are served from inside the jar and the markup carries no
cache-buster.

**If this is restated.** Never re-propose: an **archive table** or copied rows (the flag is what keeps the steps, the
notes and the id intact, and restoring is one field off); a **foreign key** on `archived_lane_id` (the lane's deletion
is the moment the snapshot must survive); filing an archived card under nothing or deleting it rather than restoring
into the board's first column; **editing or saving an archived card without restoring it** (that is the gap this stage
closed); archiving a **backlog** card (no project page, no archive — it keeps management's delete); storing the panel's
open state in `localStorage` (the URL is the state, which is what makes it survive a reload and work with scripts off).
*(The Stage 81 clause that a live project card must not have a hard **Delete task** was **reversed by Stage 84** at the
user's request: a live card's popup destroys it again, management-only, and the panel's **Delete permanently** is the
answer for a card already in the Archive.)* Deliberately
untouched: `saveLanes`'s name/catalogue rules, toasts, the card popup's field set, the Other tasks board's columns,
and the Stage 80 colour and fold.

<a id="stage-82"></a>

## Stage 82 — The three opening phases stand at the top of the bucket list (2026-10-27, live, Kotlin + CSS)

**The ask.** *"can you copy what this doing on settings project bucket settings oshi no"*, with `murray_pts.html`
attached again — the same prototype, this time pointed at one panel. In the prototype, `renderSettings()` draws
**Project bucket settings** as a category `<select>` and then **one list**: `TYPE_DEFAULT_BUCKETS[type]`, which is the
three base names (*Engineering*, *Procurement*, *Production*) plus whatever has been added, every entry a coloured dot,
its name and a 🗑, followed by a name input and **+ Add**. PTS printed the same three as a separate read-only chip row
above a list that held only the extras, so the two pages disagreed about where the three belonged.

**What copying it as written would have meant.** In the prototype the three are ordinary entries: deleting one takes it
out of that type's array, and the next project's board then opens with only the rest. That removal is *cosmetic* there
— `defaultBucketsForType` falls back to the base three whenever a type's list is empty — so a prototype user can bin
*Engineering*, see the row go, and still get an Engineering column on the next project. PTS's three are Stage 75's
**opening set**: every board is born with them, and renaming or removing one is a project page's job. Making them
bin-able here would promise a change the app does not make, and hiding them would lose the answer to *what does a new
board open with*. The question was put to the user as a choice; the answer was **"Show all three as fixed rows"**.
So the three are now the **first three rows of the same list** — the prototype's layout without its misleading bin.

**The change is markup and two CSS rules, nothing else.** `Settings.kt`'s `bucketDefaultsPanel` lost the
`div("set-row")` carrying the `lane-floor` chips and the `DEFAULT_PROJECT_BUCKETS.forEach` inside it; in its place,
inside the `board-results` live region and above the extras, come three rows of the same shape the catalogue uses:

```
div("set-row phase-row") {  span(bucketDotClass(index))     // dot-0, dot-1, dot-2
                            span("lane-name") { +name }
                            span("phase-fixed") { +"Always on every board" } }
```

They are fixed by construction: no form, no `buckets/delete`, no id to post. The extras keep `dot-(3+index)` unless
their row carries a chosen `colourSlot` (Stage 80), so the first extra is still a new board's fourth column. The
caption was rewritten to say it in the open — *"The three phases at the top are on every board and cannot be changed
here — a project's own page is where one of them is renamed or removed. The rows beneath them are this category's
catalogue…"* — and the empty-catalogue line now points at the rows rather than a chip strip: *"&lt;Category&gt; adds no
buckets of its own — its new projects open with the three phases above."* `app.css` dropped `.lane-floor`,
`.lane-floor .chip` and `.lane-floor .chip.lane-chip` and added `.set-row.phase-row` (one flex line, left-aligned) and
`.set-row .phase-fixed` (`margin-left: auto`, muted, no wrap) so the note sits where the bin does on the rows below.
`.lane-chip` itself is untouched and still dresses the create popup's `bucket-floor` line (`Projects.kt`): that is a
different page making a different promise — which buckets this new board will open with — and Stage 82 did not move it.

**No data change.** `type_default_buckets`, `project_types`, V18's archive columns and the seeded board are all as
Stage 81 left them; the panel still adds and removes catalogue extras through `BucketDefaultsService` with the same
rules and the same `?lanes=` round-trip. A category with no extras still seeds exactly the three on its next project.

**Tests.** Suite green at **615 / 41 classes / 0 failures** — the same count as Stage 81, because one
`SettingsRenderTest` case was rewritten rather than a new one added. It now asserts three `set-row phase-row` rows with
`bucket-dot dot-0…dot-2`, the three names, three `phase-fixed` cells reading *"Always on every board"*, no
`chip lane-chip` anywhere on the page, no `buckets/delete` inside `#bucketDefaults`, the new empty line naming *"the
three phases above"*, the caption's *"three phases at the top are on every board"* and its Stage 77 sentence, and the
category picker unchanged. The CSS half asserts `.set-row.phase-row` and `.set-row .phase-fixed` instead of the
`.lane-floor` rules that are gone.

**Verified.** A throwaway on port 8097 (`PTS_SEED_DEMO=true`, temp DB, schema v18) served the panel with Engineering
dot-0, Procurement dot-1 and Production dot-2 each wearing *"Always on every board"*, then the empty line, then the add
row with its six swatches pre-checked at position 3. Adding **QAQC** at colour slot 3 produced a fourth row
(`dot-3`) with its confirmed bin; removing it returned the panel to the empty state. A full-page screenshot was read
before the instance was stopped.

**Deploy note.** No migration — the schema stays **v18**. `data/pts.db` + `-wal` + `-shm` were backed up as
`data/pts.pre-stage82-20261027-1704.db*` (368640 / 0 / 32768 bytes), then the live JVM (PID 13020) was stopped,
`installDist` run, and the instance launched **detached** on 8080 (PID **14504**). `/` answers 200, `logs/pts.log`
records *"Current version of schema \"main\": 18 … Schema \"main\" is up to date. No migration necessary."*, and the
served `app.css` carries `.set-row.phase-row` and `.set-row .phase-fixed`. **Ctrl+F5 once** — the markup and the rules
ship inside the jar with no cache-buster.

**Follow-up — "when add a new bucket for a different category it saves on that category" (2026-10-28).** The panel
looked right and the server was right; the page was not. Settings renders **two** `[data-live-region]` blocks — the
Team roster (`#team-board`) and the bucket list — and `app.js`'s single-region `liveRegion()` returned the first. The
category picker's `data-autosubmit` therefore fetched `/app/settings?lanes=<chosen>` and swapped the **roster**, which
is identical for both categories; the bucket rows, the add line's hidden `lanes` and its *"Adds to … only"* label went
on naming the category the reader had left. **+ Add bucket** then posted that old category, so the row appeared under
it. The same address had a second seam: the team toolbar's own filter wrote a query with no `lanes`, and choosing a
category wrote one with none of the roster's keys, so either view could silently reset the other on the next refresh or
reload.

The fix is in `app.js` plus named hooks in the markup. `regionFor(form)` climbs from the form to the nearest ancestor
that holds a live region and falls back to the page's first, so the team toolbar (which stands above its board) keeps
its old target while the bucket picker (`#bucketDefaults`) finds its own. `showResults(html, region)` now takes that
region and matches the response's copy **by id** when it has one, because a two-region response carries both. The
bucket list names itself `id="bucketDefaultsResults"`; the add line is `id="bucketAdd"` and the toolbar's hidden
category is `id="bucketCategoryKey"`, both listed in the picker's `data-live-extra` so the hidden category and the
label move with the rows. The team toolbar carries `lanes` and names `#bucketDefaults` in its own `data-live-extra`,
so filtering the roster hands the card fresh hidden view fields; the bucket category form carries `viewCarriage(filter)`,
so changing category keeps the roster's search, pickers and order. Both views now describe one address, and neither
send can re-point the other.

**Tests, again.** Still **615 / 41 / 0** — no case added. `SettingsRenderTest` pins `#bucketDefaultsResults` and both
companions, and the toolbar's field list grew `lanes` (expected `lanes, q, position, role[, sort]`, with the comment
saying why it is a carried key rather than a fourth control); the toolbar's companion assertion reads
`#team-counts,#bucketDefaults`.

**Verified over HTTP, end to end.** On the same throwaway (8097, schema v18), with the demo account's forced-change
flag cleared in that temp DB only: switching **Fabrication/Manufacturing → Repair/Maintenance/other services** updated
the rows, the empty line, *"Adds to Repair/Maintenance/other services only"* and the hidden `lanes` together, and
**+ Add bucket** (`RepairProbe1`, `RepairProbe2`) wrote both rows to `type_default_buckets` under Repair and none under
Fabrication. With the Team **Position** picker on *CEO*, the address read
`?position=CEO&lanes=Repair%2FMaintenance%2Fother+services`; switching to **Site projects** then clearing Position kept
`lanes=Site+projects` and the card on Site, and `SiteProbe1` was written there. Turning the team filter back on kept
the category too — the reverse direction of the same bug.

**Deploy note (follow-up).** No migration; schema stays **v18**. `data/pts.db` + `-wal` + `-shm` were copied to
`data/pts.pre-stage82-fix-20261028-0820.db*` (368640 / 32768 / 531512 bytes), the live JVM (PID 14504) stopped,
`installDist` run, and the instance launched **detached** on 8080 (PID **9704**). `/` answers 200, `logs/pts.log` reads
*"Current version of schema \"main\": 18"*, and the served `app.js` carries `regionFor`. **Ctrl+F5 once** — the JS and
the new ids ship inside the jar.

**If this is restated, again.** Never re-propose: one global "first live region" lookup on a page that draws two;
swapping only the rows while the add line (or the toolbar's hidden `lanes`) keeps the old category; letting the team
filter write an address without `lanes`; or letting the category picker write away the roster's `q/position/role/sort`.
The two views share one address deliberately — that is what makes a reload land on the pair the reader was looking at.

**If this is restated.** Never re-propose: making the three **bin-able from Settings** (the prototype only appears to
do so and its own fallback puts them back; PTS's three are a board's opening set, and a project page is where one is
renamed or removed); **hiding** them to match the old extras-only list; restoring the **chip row** above the catalogue;
renumbering the extras' dots from 0 now that the three are rows (the first extra is still a new board's fourth column);
or copy the prototype's **random** `pickBucketColor()` (Stage 80's chosen-slot rule is the shipped answer). Deliberately
untouched: the add line's six swatches and *"Adds to &lt;Category&gt; only"*, `BucketDefaultsService`'s refusal set,
the create popup's `bucket-floor` chips, and every page that reads a catalogue row. (Stage 83 later took those six
swatches off creation; the label, the refusal set and the chips stand.)

<a id="stage-83"></a>

## Stage 83 — Creating a bucket asks for a name, and the app paints the colour (2026-10-28, live, Kotlin)

**The ask.** *"when creating a new bucket dont need to choose color system chooses it automatically"* — said right
after Stage 82's fixed rows and the category-scoping fix, looking at Settings' **Project bucket settings** add line.

**What it replaces.** Stage 80 put a six-swatch row beside every place a bucket was *named*: the catalogue's add line
and the project page's **+ Add bucket** popup. The swatch was a slot into the stylesheet's six hues, pre-checked at
the position the new row would take — so a reader who did not care could leave it alone and store nothing. It worked,
but it asked a question the app can answer itself, on the one screen where the reader is doing something else
(naming the category's offer). The colour exists to tell columns apart; it is not a decision that belongs in the
act of naming one.

**The change is deletion, not a new rule.** New buckets have always been allowed to carry **no slot**, and a null
slot has always meant *wear the colour of my place in the run*
([`mn.murray.pts.domain.effectiveColourSlot`](src/main/kotlin/mn/murray/pts/domain/Task.kt)). Stage 83 simply stops
filling the slot in on creation:

- `Settings.kt`: `bucketDefaultsPanel` lost its `chosenColour` parameter and the `bucketColourPicker(...)` call at
  the end of the add form; `settingsContent` lost `bucketDefaultColour` and stops passing it down. The panel's
  caption now says the dot is the colour of the row's place and that the app picks it. The add form is a name, a
  hidden `lanes`, and the button.
- `Application.kt`: `GET /app/settings` no longer reads `?bucketcolour=`, `respondSettings` lost the parameter, and
  `POST /app/settings/buckets/add` no longer reads `colour` — the redirect is the same `?lane=…&lanes=…` it always
  was, minus the swatch carry-back.
- `BucketDefaultsService.add` lost `colourSlot` and calls `TypeDefaultBucketRepository.insert(category, name)`.
  New catalogue rows are written `colour_slot = NULL`.
- `Projects.kt`: `laneAddBody` lost the picker and its now-unused `laneCount` argument; the create half is the name,
  the board's hidden `bucket` fields, the management tick and the buttons.
- `TaskService.saveLanes` lost `newColourSlot` (and the `hue` it fed). Every lane this save creates — typed or
  ticked from the catalogue — is written with no slot, so the board paints it by position. `POST
  /buckets/save` no longer reads `colour`.
- **Unchanged on purpose:** the rename half of the column popup ([`laneManageBody`](src/main/kotlin/mn/murray/pts/Projects.kt))
  still draws `bucketColourPicker`, and `TaskService.renameLane` still writes the slot. Recolouring a column that
  is already on the board is a decision about a thing the reader can see, and it is now the picker's only home.
  Stage 80's storage, the six hues in `app.css`, and every existing shaded row are untouched — a catalogue row or
  lane that already carries a slot keeps wearing it.

**Why null rather than a stored "next slot".** A null slot is the app's existing answer for *no choice*, and it is
the one that stays right if the row's place changes (a catalogue grows above it, a column is removed and re-added):
the dot follows the run. Writing an explicit slot at creation would freeze the shade to a number that only happened
to be next that day, and would make two players — position and stored slot — disagree later. The six hues stay the
stylesheet's, and a new bucket cannot invent a seventh.

**No data change.** No migration: `type_default_buckets.colour_slot` and `project_buckets.colour_slot` still exist,
still nullable, and are still read by the same `bucketDotClass(index, slot)` call. Schema stays **v18**.

**Tests.** **615 / 41 classes / 0 failures/errors/skipped** — no case added, three rewritten. `SettingsRenderTest`'s
add-line test now asserts **no** `type="radio" name="colour"` and **no** `.colour-row` inside
`/app/settings/buckets/add` (the Stage 80 assertions about six swatches and a pre-checked one are gone), while its
dot assertions and the labelled-category assertion stand. `ProjectsPageRenderTest`'s empty-board dot count drops from
three heads plus six swatches to **three dots only**, because the closed add half no longer draws a colour row; its
swatch test opens a column's `⋯` (`LaneForm(laneId = …)`) instead of the creation half, and still proves six radios
with the chosen one checked. `BucketDefaultsServiceTest` never passed a colour, so its add rules are unchanged.

**Verified over HTTP, end to end.** Throwaway on 8097 (temp DB, schema v18, demo account). Settings at
`?lanes=Fabrication/Manufacturing` served no `.colour-row` on the page and an add form of name + hidden `lanes` +
button; posting `ColourProbe1` returned `?lane=LANE_ADDED&lanes=Fabrication%2FManufacturing`, printed the row as
`bucket-dot dot-3`, and `type_default_buckets` held `colour_slot = NULL`. Project **3** (M-01, Fabrication) served
no colour row with its popup on the create half; posting `ColourProbeLane` through `/buckets/save` put it on the board
as head `dot-3` with `project_buckets.colour_slot = NULL`. The same column's `⋯` still drew **six** swatches with
`value="3"` pre-checked, and posting `colour=5` with the unchanged name stored **5** and repainted the head `dot-5` —
the recolour half is intact.

**Deploy note.** No migration; schema stays **v18**. `data/pts.db` was backed up as
`data/pts.pre-stage83-20261028-0852.db` (389120 bytes; the live database had no `-wal`/`-shm` sidecars at copy
time), the live JVM (PID 9704) was stopped (force-stopped by `stop.bat` after the polite close was refused),
`installDist` run, and the instance launched **detached** on 8080 from `deploy/run.bat` (PID **15560**). `/` answers
200, `logs/pts.log` records *"Current version of schema \"main\": 18"*, and the shipped jar carries no
`bucketcolour` string in `ApplicationKt` and `colour-row` only in `TaskBoardKt` (the picker the manage half still
calls). **No Ctrl+F5 needed** — the change is server-rendered markup and pages are served `no-store`; no static file
changed.

**If this is restated.** Never re-propose: a swatch row on either creation form (Settings' add line or the project
page's **+ Add bucket**); the `?bucketcolour=` carry-back on a refusal; storing a computed "next slot" at creation;
removing the picker from the **rename** half as well (recolouring an existing column is where the choice belongs);
or a random colour. The rule is one sentence: *creation names it, the run paints it, the column's `⋯` repaints it.*

<a id="stage-84"></a>

## Stage 84 — A card's popup deletes again, and the Archive belongs to the bucket (2026-10-28, live, Kotlin)

**The ask.** *"when clicking on a bucket card add delete task button instead of archive task"* — said about a card
standing on a project's bucket board, looking at the popup Stage 81 had given an **Archive task** button.

**What it replaces.** Stage 81 split the card popup's foot by world: a project card posted to `/app/tasks/archive`, a
backlog card kept management's **Delete task**. Stage 84 reverses the project half at the user's request. The Archive
itself is untouched — it is still where a removed bucket's cards wait, restorable and destroyable from the panel — but
its trigger is now only the **bucket's removal**, never a button on a live card.

**The change is deletion, not a new rule.**
- `TaskBoard.kt`: `TaskForm.mayArchive` is deleted, `taskArchiveForm` with it, and `modalNotes` ends in
  `if (state.canEditAll) taskDeleteForm(task, back, projectId, showAllBuckets)` — one line for either world. The delete
  form itself did not change: `taskId`, `back`, `projectId` (plus `allbuckets` on the wide backlog), no `edit`, no
  `data-live-target`, `class="opener-form task-del"` beside the note form inside `.note-foot`, the confirmation naming
  the card and saying its steps and notes go with it. Its KDoc now records that Stage 84 made it every card's button
  again.
- `Application.kt`: `POST /app/tasks/archive` and `TaskService.archiveCard` **stay** — a hand-built POST still archives
  a card for the board's right — but the route's KDoc says plainly that *no page prints a control for it since Stage
  84*, and `deleteAssigned` still refuses an archived row, so the two paths cannot be confused. The bucket-removal
  path is a different call (`TaskService.archiveLane` + `TaskRepository.archiveLane`) and was not touched.
- `TaskService.deleteAssigned` needed no change: `!canManage` refuses, `archived` refuses, otherwise the row goes with
  its plan and log by the same `ON DELETE CASCADE`.
- `Projects.kt`: `projectArchivePanel`'s KDoc re-tells the story — the panel still destroys an archived card, and the
  live card's popup now carries the delete again.

**Who is offered what.** The delete renders on `TaskForm.canEditAll`, which is management's flag and the same line
`deleteAssigned` checks. A project's own manager — who may write the board and open the 🗄 — is **not** management and
sees no delete; neither does the card's holder or a reader with no part in it. A card being created has nothing to
destroy and prints no foot button. Nothing about the Archive panel's audience changed (management or the project's
manager).

**Why keep the archive machinery.** Removing a column has to put its cards somewhere they can come back from without
losing their steps and log; destroying them in that motion would be a silent cascade on a control that says nothing
about deletion. The flag, the lane snapshot, restore and permanent delete all stay exactly as Stage 81 left them —
what went is only the second button on a live card.

**No data change.** No migration, no new column, no change to `archived` / `archived_lane_id` / `archived_lane` /
`archived_on`. Schema stays **v18**.

**Tests.** **615 / 41 classes / 0 failures/errors/skipped** — no case added; three rewritten under a new Stage 84
header in `TaskBoardRenderTest`, with the wide-backlog delete case moved under it unchanged: *a project card's popup
carries management's Delete task beside the note box* (asserts
`formPosting(html, "/app/assigned-tasks/delete")`, `>Delete task<`, the fields, no `edit`, no `data-live-target`, no
`/app/tasks/archive`, `ghost-btn btn-sm danger`, `data-confirm="Delete <title>?"`, *This cannot be undone*); *nobody
but management is offered the Delete task button* (the card's holder, a reader with no part in it, **and a project's
own manager** opening a project card, plus a card being created); *the delete button shares the note box's own foot
line* (`.note-foot`'s three declarations, the delete's metric block, `>Delete task<` after *Add note*); and the wide
backlog delete case, unchanged. The Stage 81 Archive cases in `TaskServiceTest`, `ProjectsPageRenderTest` and
`db/TaskArchiveMigrationTest` still pass as they stood.

**Verified over real HTTP, on a throwaway** (port 8097, temp DB at v18, booted off `build/classes` + resources).
Management opened `/app/projects/3?edit=5`: the popup carried exactly one destructive form,
`action="/app/assigned-tasks/delete"`, `taskId=5`, `back=project`, `projectId=3`, no `edit`, no `data-live-target`, and
the button text and confirmation above; neither that popup nor the backlog's carried `/app/tasks/archive`. Posting the
delete answered **302 → `/app/projects/3`** and the row was gone. A `Viewer` account opening a project card got no
popup and neither form. The backlog card kept its delete. The Archive still worked end to end: a probe bucket was
added, a card filed in it, the set posted without it → *"… 1 card went to Archive"* with the row flagged and its lane
snapshot written; `?archive=1` showed **Archived tasks (1)**, *"was in \"Probe84\""* and both buttons; **Restore**
answered 302 with `archive=1` and *"That card is back on the board, in …"*, the card unflagged in the first column;
re-archived and **Delete permanently**, the row and its snapshot were gone.

**Deploy note.** No migration; schema stays **v18**. `data/pts.db` + `-wal` + `-shm` were copied while live to
`data/pts.pre-stage84-20261028-0918.db*` (389120 / 41232 / 32768 bytes), the live JVM (PID 15560) was stopped
(force-stopped by `stop.bat` after the polite close was refused), `installDist` run, and the instance launched
**detached** on 8080 from `deploy/run.bat` (PID **9692**). `/` answers 200, `logs/pts.log` records *"Current version
of schema \"main\": 18"*, and the shipped jar has **no** `Archive task` string in `TaskBoardKt` while `Delete task`
is there and `static/js/archive-modal.js` still ships. **No Ctrl+F5 needed** — the change is server-rendered markup
and pages are served `no-store`; no static file changed.

**If this is restated.** Never re-propose: an **Archive task** button on a live card (the popup deletes); an archive
button on *either* world; making the live card's delete archive instead of destroying; letting an assignee or a
project's manager delete a card; a delete without the confirmation naming the card; deleting a **backlog** card by
archiving it; or removing the Archive panel/restore/permanent delete (a removed bucket's cards still have to live
somewhere). The rule is one sentence: *a live card is deleted by management, and a card whose bucket took it away waits
in the Archive until it is restored or destroyed there.*

<a id="stage-85"></a>

## Stage 85 — The navigation drawer is a little narrower (2026-10-28, live, CSS)

**The ask.** *"make the navigation drawer at bit narrow"*, with a screenshot of the drawer at its full height — a
width request, not a restyle.

**What changed.** `.sidebar`'s width and `flex-basis` drop from **264px to 240px** in
[`app.css`](src/main/resources/static/css/app.css); nothing else about the shell moved. The caption under the logo,
*PROJECT TRACKING SYSTEM (PTS)*, had been fitted to the old inner width with `letter-spacing: .14em` — at 240px the
same line wrapped to two — so its tracking tightens to **.06em** at the same 10.5px/600. That is the only typographic
change, and it is what keeps the drawer's head the same height. The comment beside the nine-cell read-only project
header, whose arithmetic had named the 264px sidebar, now names 240px and a 1,138px panel.

**Why 240 and not less.** The drawer scrolls (`overflow-y:auto`), so a Windows scrollbar takes 16px of the 240 before
the content box is measured: 224px, less the 28px of side padding, leaves **196px** for a nav row. At that room every
label is one line (rows measure 39px, the same as before), the `168px` logo still sits inside the padding, and the
longest caption reads on one line with 0.63px tracking. Ten or twenty pixels less would start wrapping the caption or
the badges, which is a restyle the ask did not make.

**Verified on a throwaway** (port 8097, the Stage 84 build with the edited resource copied in): the sidebar measured
**240px**, its client width **224**, `scrollWidth == clientWidth` (no sideways overflow), the caption's box **14px tall**
(one line), all ten nav rows **39px**, and the logo's right edge at 182 against the drawer's 240.

**Deploy note.** CSS only — no migration, no markup, no Kotlin, so no `data/pts.db` copy was taken. Live JVM 9692 was
stopped (force-stopped after the polite close was refused), `installDist` run, and the instance launched **detached**
from `deploy/run.bat` (PID **5280**); `/` answers 200 and `/static/css/app.css` carries `width: 240px`. **Readers need
Ctrl+F5 once** — the stylesheet is a cached static file. The suite is unchanged at **615 / 41 / 0**: no test reads this
width.

**If this is restated.** Never re-propose: a width below 240 without reworking the caption (it wraps), letting the
caption wrap, shrinking the nav type to fit, a collapsible/overlay drawer, or a restyle of the shell. The rule is one
sentence: *the drawer shrank by 24px and the caption's tracking shrank with it; nothing else about the shell moved.*

<a id="stage-86"></a>

## Stage 86 — A card opens for every reader, and the write stays with its own (2026-10-28, live, Kotlin)

**The ask.** *"when non management click on task card able to see the task but not modify"* — one sentence that can
read two ways: open every card read-only, or keep the card shut to anybody but its holder and management. The user
chose **Read any card, owner keeps their work**: every non-management account clicks any card on a board it can see
and reads it — fields, plan, log — with no save, no tick and no note box; the card's own assignee keeps the writes
Stage 23 gave them (start date, due date, priority, ticking steps, adding notes); management is unchanged. Nothing
about *where* a card is listed moved.

**The read gate widened; the write gates did not.** `TaskService.editableBy` is now **`openableBy`**
([`TaskService.kt:303`](src/main/kotlin/mn/murray/pts/service/TaskService.kt)) and answers only "may this account open
this card at all":

```kotlin
fun openableBy(id: Int, callerEmpNo: String, canManage: Boolean): TaskItem? {
    val task = TaskRepository.findById(id) ?: return null
    if (task.archived) return null
    if (canManage) return task
    if (task.isBacklog && task.assignee != callerEmpNo) return null
    return task
}
```

The archive guard stays first — a put-away card is on no board and Stage 81's `?edit=<id>` refusal still holds. A
**project** card is on the project page, the one whole-project board every signed-in account may read since Stage 56,
so any reader may now open it. A **backlog** card is on a board Stage 51 scoped to the reader's own cards, so a
non-manager still opens only a backlog card they hold; management opens both. A typed `?edit=<somebody's backlog id>`
renders the ordinary page (on Other tasks, the reader's own **New task** popup) and never the card. The KDoc says
plainly that this is **only the read** — `updateAssigned`, `toggleChecklistItem` and `addNote` keep their own rules
and refuse the stranger the popup now admits. `Application.kt` calls the rename in the popup load, the refusal
re-render and its own KDoc.

**The popup grows a third state.** `TaskForm` ([`TaskBoard.kt:222`](src/main/kotlin/mn/murray/pts/TaskBoard.kt)) adds
flags beside the existing `mayWriteCard`:

```kotlin
val ownsCard: Boolean = task != null && task.assignee.isNotBlank() && task.assignee == viewerEmpNo
val mayBookTime: Boolean = mayWriteCard || ownsCard
val viewOnly: Boolean = isEdit && !mayBookTime
val mayTick: Boolean = canEditAll || ownsCard
val mayNote: Boolean = mayTick
```

`mayWriteCard` itself is unchanged, so the assignee's existing half is not re-derived. Presentation follows:

- the heading is **`Task T-00008`**, not *Edit task …*, and a `p.modal-scope` sentence names the holder: *"This task
  belongs to Batbayar Nyam. You can read its plan and its log; its fields belong to management and to the person it
  is assigned to."* (`memberName()` resolves the number from the roster, falling back to the number; `noteAuthor()`
  uses the same helper);
- **every** field is disabled — title, project, assignee, status and lane through the existing `readOnly` branch,
  start/due/priority through `!mayBookTime` — and each greyed picker still prints the stored value rather than the
  roster;
- there is **no Save button at all** (not a disabled one), and the button that closes the popup reads **Close**
  rather than *Cancel*, because there is nothing to cancel. The element is still a `<form>`, so Enter posts nothing;
- the plan is **readable and stopped**: `modalSteps` now runs for every edit, and `lockedReason` asks the *Completed*
  rule first (the service's own order) and then `!mayTick`, giving *"This card belongs to somebody else, so its plan
  is yours to read and not to work"*; each row is `stepRowLocked(item, reason)` — same `.chk-row`, same square, the
  reason on its `title` — with no toggle form under it and no ×/*New step…* line (`canEditAll` still owns those);
- the **log is visible** with each entry's author and day, but the × stays management's, and a stranger's popup has
  **no `.note-foot` row at all** rather than an empty one: the box is drawn on `mayNote`, the delete on `canEditAll`.

**Reading a card is not moving it.** Before this stage the click and the drag shared one `opens` flag, because only a
writer could click. Splitting them is the whole safety of the widen: `taskCard` keeps `canMove = canManage || mine`
and `draggable = mayDrag ?: canMove`, while `data-task-card`, `tabindex="0"` and `aria-label="Open …"` are now
emitted for **every** card. A colleague's card on a project board therefore opens read-only but still prints no
`draggable`, no `kmove-btn` and no **✓ Done / ↺ Reopen**; their own card opens, walks, ticks and takes notes exactly
as before. On a project's lane board the drag remains `mayDrag` (Stage 49: management or that project's manager), so
a plain viewer's own card there walks by the two arrows and not by a grab. *(**Stage 103** retired the arrows, so the
lane board's own **✓ Done / ↺ Reopen** is what that reader has — which is what this page was always printing, the
arrows belonging to the status boards.)*

**The openers follow the read, not the card list's owner.** `taskKanban` prints its one hidden `#taskEditOpener`
whenever the board is drawn with cards (`drawOpener && tasks.isNotEmpty()`), and `Projects.kt` draws the popup for
`canManage || mayAdd || state != null || tasks.isNotEmpty()`, so a viewer's project page carries both. The Assigned
page is still personal (Stage 53), so its opener appears only when the reader holds a card; a board with no card on
it prints none — `AssignedTasksRenderTest` gained `a board with no card on it carries no opener` for exactly that
edge, and its existing *the editor this page opens comes back to this page* now seeds a card of the reader's own.

**Tests.** **618 / 41 classes / 0 failures/errors/skipped** — three added and several rewritten, no behaviour asserted
from the old read gate left behind. `TaskServiceTest` replaces the old open test with `any reader opens a project
card, and a typed id opens nothing` and adds `a backlog card opens only for management and its holder`; the archived
case keeps its name and now reads `openableBy`. `TaskBoardRenderTest` rewrites `a card is clickable for everybody and
moves only for its manager and owner`, `the boxes follow the right to tick, not the page the card is on`, `the drag
is the mover's and the click is everybody's` and `nobody but management is offered the Delete task button`, and adds
`a colleague's card opens to be read and not written` (heading without *Edit*, the holder's sentence, no Save, every
control disabled, plan present with `chk-box locked`, log present, no `.note-foot`, `Close` not `Cancel`).
`ProjectsPageRenderTest` rewrites `the bar measures the whole project while the board shows every card read-only to a
viewer` and `a viewer's project page shows every card of the project but edits only their own` to count the cards'
click targets and the single opener; `AssignedTasksRenderTest` gains the empty-board opener case above.

**Verified on a throwaway** (8097, `PTS_SEED_DEMO=true`, the Stage 86 build): signed in as the viewer `EMP0016`
(after changing the seeded first-login password through `/app/settings/password`). Project 8's board carried two
cards, **both** with `data-task-card`/`tabindex` and one `#taskEditOpener`; the colleague's card 8 opened as
`Task T-00008` with the holder sentence, **16** disabled controls, no *Save*, *Close* only, the log visible and the
plan stopped — then a manager added a step and a note, and the viewer re-read them as `chk-box locked` (reason
*"This card belongs to somebody else…"*) with **no** `chk-toggle`, no `name="done"`, no note box. Seven hand-built
writes from the viewer were refused — the save came back as the open popup with *"Only management or the person this
task is assigned to can change it."*, the other six redirected — and the card was untouched (title, `0 of 1`, one
entry, no viewer step or scrawl). With the manager reassigning that card to `EMP0016`, the owner's popup read
*Edit task*, printed **Save**, a live tick box and the note box, kept title/project/assignee/status/lane disabled,
and only start/due/priority open; the owner ticked the step (`1 of 1 done`), appended a note (`2 entries`) and moved
priority to High. The colleague's card 2 still opened read-only, and typed backlog ids 9/10 fell back to the *New
task* popup. Management's popup was unchanged: Save, Delete task, step add/remove and both note controls.

**Deploy note.** Kotlin only — no markup outside the route, no CSS, no JS, so **no Ctrl+F5**. Live `data/pts.db` was
backed up as `data/pts.pre-stage86-20261028-101406.db*` (389120 / 0 / 32768 bytes), live JVM 5280 was stopped
(force-stopped by `stop.bat` after the polite close was refused), `installDist` was run, and the instance was
launched **detached** from `deploy/run.bat` (PID **18196**); `/` answers 200. Schema stays **v18** — no migration.

**If this is restated.** Never re-propose: hiding a colleague's plan or log from the reader a card now opens for;
showing a stranger a Save, a tick box, a note box or the delete; letting a non-manager open a backlog card they do
not hold; taking the click back off a colleague's card on a shared board; narrowing management's popup; or treating
"open" as "move" — the drag, the arrows and **✓ Done / ↺ Reopen** stay with the writers. The rule is one sentence:
*a card every reader can see opens for every reader to read, and only management and the person it is assigned to
may change what is inside it.*

<a id="stage-87"></a>

## Stage 87 — A card's editor no longer asks where the card already is (2026-10-28, live, Kotlin)

**The ask.** A screenshot of the management popup *Edit task T-00012*, with the **PROJECT** row (*Project1*) and
the **SUB-CATEGORY** row (*Fabrication/Manufacturing*) circled in red: *"when clicking on task card delete those
two circled dropdown out."* The click, not the **+ New task** button, so a card's editor loses the pair while a
creation keeps them.

**Where a card sits is a fact of its row, and the board is what changes it.** On an edit of a card that is already
on a project, the popup printed two selects whose answers it had *read* from the row moments earlier. They were a
second, slower way to do what the board behind the popup does better: a drag along the columns. So
[`TaskBoard.kt`](src/main/kotlin/mn/murray/pts/TaskBoard.kt) computes three flags before the modal and uses one for
both blocks:

```kotlin
val projectIsTheRows: Boolean =
    state.isEdit && !state.isBacklog && !state.task?.project.isNullOrBlank()
val projectRefused: Boolean =
    state.error(TaskField.PROJECT) != null || state.error(TaskField.LANE) != null
val printProjectFields = printProject && (!projectIsTheRows || projectRefused)
```

`printProject` (the board-half logic of Stage 42) is untouched; Stage 87 only narrows it. Both the Project field and
the Sub-category field now read `if (printProjectFields)`.

**The project still has to be posted.** Management's save takes the draft as the answer (`allowed = posted` in
`TaskService.updateAssigned`), and a save with a blank project is refused — *"Choose which project this card is
on."* — so simply hiding the select would have made every management edit of a project card fail. The form therefore
carries the row's own name in a hidden field, drawn exactly when the picker is not:

```kotlin
if (projectIsTheRows && !projectRefused) {
    input(type = InputType.hidden, name = "project") { value = selectedProject }
}
```

The lane needs nothing: `updateAssigned` treats an absent `lane` as "keep the stored one", which is the same
semantics Stage 75's bucket moves already rely on. A **backlog** card keeps both pickers in its *where it lives* half,
because crossing onto a project is a real question there; a **create** keeps them because there is no row to read an
answer from; a legacy row with a blank project keeps them because that is the one card a picker still fixes.

**A refusal brings the fields back.** The first cut of this stage hid the pickers unconditionally, and a hand-built
POST without `project` came back as a silent refusal: 200, the popup re-drawn, no message — because `field-err` lives
inside the block that had been skipped. `projectRefused` closes that: if the save returns an error on `PROJECT` or
`LANE`, both pickers print again, populated, with their message under them, and the hidden field is suppressed so
the corrected form posts what the reader chose. That is the only way a project card's save gets here, since the popup
itself posts the row's project.

**The owner's sentence moved with the fields.** The `modal-scope` line for an owner used to name *"the title, the
project, the person it is assigned to and the status"*; the project is no longer printed above it, so an assigned
card's line now reads *"The dates and the priority are yours to set. The title, the person it is assigned to and the
status belong to management."* The backlog branch is unchanged — its pickers are still there.

**Tests.** **619 / 41 classes / 0 failures/errors/skipped** — two added, one deleted, and two re-pointed.
`TaskBoardRenderTest` adds `an edit of a card on a project prints neither the project nor the sub-category picker`
(no `id="taskProject"`, no `id="taskLane"`, no `>Sub-category<`, the hidden `<input name="project">` carrying the
row's name, the remaining fields still printed, while a create and a backlog card's editor both keep the pair) and
`a refused project card's save comes back with the pickers its message belongs under` (a `TaskSaveResult` with a
`TaskField.PROJECT` error draws both pickers, prints the message, and posts no hidden project). The old
`the popup lists the three phases and opens on the lane the card is in` became `a create popup lists the three
phases and opens on the lane it was called from` (it now builds a create form with `lanes()`); `a lane this project
added itself…` likewise uses a create form; `a card filed in a lane the general board does not list still reads its
own lane` was deleted with its concern folded into the create case; and the owner's and colleague's field lists drop
`project`/`lane`. No test asserts the old pair on an edit.

**Verified over real HTTP, on a throwaway** (8097, `PTS_SEED_DEMO=true`): the four popup shapes were read side by
side — management edit (neither picker, hidden project), create (both), backlog edit (*where it lives* half keeps
both), and a legacy row with no project (both). An ordinary management save kept the card's project and bucket; a
hand-built POST without `project` came back with *"Choose which project this card is on."* under a drawn, populated
picker and wrote nothing; a backlog card moved onto a project landed ASSIGNED on project 1 in *Engineering*; and a
Viewer owner's popup showed neither picker, the new sentence, the hidden project, and the four fields they still
own — a save through it kept the project and changed due date and priority.

**Deploy note.** Kotlin only — no CSS, no JS, no markup outside this renderer — so **no Ctrl+F5**. Live
`data/pts.db` was backed up as `data/pts.pre-stage87-20261028-110058.db*`, the live JVM (PID 18196) was stopped,
`installDist` was run, and the instance was launched **detached** from `deploy/run.bat` (PID **17248**); `/` answers
200. Schema stays **v18** — Flyway logs `No migration necessary`.

**If this is restated.** Never re-propose: putting **Project** or **Sub-category** back on an edit of a card that is
already on a project; a save that posts a blank or defaulted project because its picker was hidden; hiding the
pickers on a create or on a backlog card's *where it lives* half; or hiding the pickers so completely that a refusal
has no field to sit under. "Remove the lane from the save too" is wrong for the same reason it was wrong in Stage 52:
the lane is set, it is just not a drop-down on this popup. Moving a card between a project's columns is the drag
(Stage 75) — the popup's job is the fields inside the card.

<a id="stage-88"></a>

## Stage 88 — A card says which project it is for, and the reader can narrow their backlog by bucket (2026-10-28, live, Kotlin + CSS)

**The ask, in the user's words.** *"On assigned tasks there are project task cards showing task id's should be showing
project id, and on assigned tasks showing other tasks in progress that is right but user should see what bucket those
tasks are in add bucket drop down."* Asked which control they meant, they answered: *"do you remember other tasks are in
buckets such as user can add such as finance hr etc. you need to put those hr finance into dropdown when user choose hr
tasks assigned choose hr from dropdown and task card should be displaying in progress kanban."* So: the **chip** on a
card names its project (or, for an Other task, its bucket), and the Assigned page gains a **page-level Bucket dropdown**
over the reader's own Other-task board — which stays a five-status kanban, not a set of bucket columns.

**The chip.** `taskCard`'s top line (`TaskBoard.kt`) used to print `taskCode(task.id)` — `T-00012` — on every status
board. On a run of cards from every project at once, that number tells two cards apart but answers no question anybody
asks; *which project is this work for* does. Since a backlog card has no project, the same slot carries the one fact
that matters for it:

```kotlin
if (!laneBoard) {
    div("kcard-top") {
        when {
            task.projectCode.isNotBlank() -> span("tcode") { +task.projectCode }
            task.isBacklog && task.bucket.isNotBlank() -> span("tcode bucket-chip") { +task.bucket }
            else -> span("tcode") { +taskCode(task.id) }
        }
    }
}
```

`projectCode` is new on `TaskItem` — appended **after** `archivedOn` with a `""` default, the same positional-safety
rule `projectId` follows, because [`DemoData`](src/main/kotlin/mn/murray/pts/Data.kt)'s cards are built positionally.
`TaskRepository.projectNames()` became `projectLabels(): Map<Int, ProjectLabel>` (a private `ProjectLabel(name, code)`)
and `map()` fills both `project` and `projectCode` from the one read, so a board still costs one query for names. A
legacy assigned row with no project keeps the `T-` number — that is the *else*, not a second design. A **lane board**
still prints no chip at all (Stage 78's rule, unchanged). *(**Stage 104** made the first branch an `<a class="tcode">`
pointing at `/app/projects/<id>`, so the chip that names a project is also the way to it, and gated it on
`task.projectId > 0` as well as the code — an href without an id to fill it is not printed. The block above is Stage 88's
shape, kept for the history; the current one is in the Stage 104 section.)*

**The dropdown.** `assignedTasksContent` grew an `otherBucketId: Int = 0` and reads the board's columns once
(`TaskService.bucketRows()`); the reader's own Other cards are filtered by that id while the five status columns stay
exactly as they were:

```kotlin
val buckets = TaskService.bucketRows()
val chosenBucket = buckets.firstOrNull { it.id == otherBucketId }
val ownOther = chosenBucket?.let { chosen -> allOther.filter { it.bucketId == chosen.id } } ?: allOther
```

The picker itself (`otherBucketFilter`) is the projects board's `board-toolbar` idiom: a real GET form onto
`/app/assigned-tasks` with `name="bucket"`, option **0** = *All buckets*, one option per column, `data-autosubmit` so a
picked bucket answers in place, and a **Show** submit button as the scripts-off path. It is drawn only when there is
something to filter **or** the reader arrived already filtered (a stale `?bucket=` must still offer the way back to
*All buckets*). *(**Stage 90** widened that condition to *the board has columns*: the picker is now also the chooser for
the `+ Add task` line under the board, so hiding it on an empty board would leave a reader nowhere to aim their first
card.)* An id the board does not have answers *all buckets*, never an empty board under an empty picker; an
empty chosen bucket says *"You don't have any other tasks in HR."* rather than the generic line. *(**Stage 93** gave
the shared Other tasks page the same picker, keyed `?show=` — on that page `?bucket=` already names a column's
popup — with the token `other:<id>` as this page's `assigned:<id>`.)*

**The filter is a view, so it rides `back`.** Opening a card, saving it, ticking a step, writing a note, deleting it or
answering a refusal are all trips off this page and back onto it, and none of them may quietly widen the board the
reader was reading. The page therefore passes a `back` token that names the filter:
`backForBucket(id)` renders `assigned` or `assigned:<bucket id>`, and every route already carries `back` through
`taskRedirect`. Three small changes close the loop:

- `taskRedirect` reads `bucketOfBack(back)` and appends `bucket=<id>` to `/app/assigned-tasks` (only that page; the
  project page and the shared backlog are never sent one);
- `backOf(form)` **normalises** the posted token through the same pair, so `assigned:7` survives while a hand-built or
  unrecognised value lands on the unfiltered board instead of being echoed;
- the GET route reads `?bucket=` as a view key and the save-refusal branch calls
  `assignedTasksContent(..., otherBucketId = bucketOfBack(back))`.

The popup's opener needed no change at all: it is `taskRedirect(back, projectId)`, so it now points at
`/app/assigned-tasks?edit=N&bucket=<id>` for free.

**CSS.** The chip may now carry a bucket name (up to `BUCKET_NAME_MAX`), so `.kcard-top .tcode` gains
`max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap`, and `.tcode.bucket-chip` wears a
neutral blue-grey tint (light and dark) so a bucket and a project code are not made to look the same. The picker row is
`.other-bucket-filter { margin: 0 0 12px }` on top of `.board-toolbar`. *(**Stage 89** found that row was still a
page-wide select — the select was a sibling of its label and fell through to `select { width: 100% }`; it now sits
inside the label, sized to one card.)* The class name deliberately avoids the substring
`d-bucket` — the Stage 47 absence test asserts `html.contains("d-bucket")` is false, and `assigned-bucket-filter` matched
it mid-word. (That test now also asserts the bucket name is *not* a column heading, only a chip and an option.)

**Tests.** **627 / 41 classes / 0 failures/errors/skipped** — eight added, two re-pointed.
`AssignedTasksRenderTest` adds: a project card wears `S-01` and no `T-` number; an Other card wears its bucket with
`bucket-chip`; the picker lists every column with *All buckets* selected; a chosen bucket narrows the cards and stays
selected while all ten status columns remain; a stale id falls back to all; an empty bucket names itself; the chosen
bucket survives the opener's `?bucket=` and the save form's `back=assigned:<id>`; and a unit case pins
`backForBucket`/`bucketOfBack`/`taskRedirect`/`backOf` including a hand-built junk token normalising to `assigned`. Its
Stage 55 case that asserted *"Site works"* absent was re-pointed: the name now appears as a chip and an option, so it
asserts the absence of a `kcol-title` and the presence of the chip instead. `TaskBoardRenderTest`'s status-board case
now asserts the chip reads `S-01` and that `T-0000` is absent from that render.

**Verified over real HTTP, on a throwaway** (8097, `PTS_SEED_DEMO=true`, seeded; EMP0002's two Other cards moved into
*Management* and a new *HR* column in the throwaway DB only): the unfiltered page printed ten status columns, chips
`S-01` (project card), `T-00006` (legacy projectless row), `HR`, `Management`, and the picker with *All buckets*
selected; `?bucket=2` printed only the *HR* card, the opener at `/app/assigned-tasks?bucket=2`, the save form's
`back=assigned:2` and *HR* selected; a real save answered **302 → `/app/assigned-tasks?bucket=2`**; a refused save
(empty title) re-rendered with *HR* still selected and `back=assigned:2`; `?bucket=9999` showed the whole board with
*All buckets* selected; `/app/projects/1` and `/app/other-tasks` still answered 200.

**Deploy note.** CSS changed, so **Ctrl+F5** is needed for the chip and the picker row. Live `data/pts.db` was backed up
as `data/pts.pre-stage88-20261028-114059.db*`; the live JVM (PID 17248) was stopped, `installDist` run, and the
instance launched **detached** from `deploy/run.bat` (PID **9972**); `/` and `/static/css/app.css` answer 200 and the
served sheet carries the new rules. Schema stays **v18**.

**If this is restated.** Never re-propose: putting the card's own `T-` number back on a status-board chip (a project
card names its project, a backlog card names its bucket; only a row with neither falls back); turning the Assigned
page's Other half into **bucket columns** (the user asked for the status kanban to stay — the picker only narrows it);
a bucket filter that lives in a session or a cookie instead of the URL; a filter that is lost when a card is opened,
saved or refused; or hiding the picker on a filtered empty board, which strands the reader on a bucket they cannot
leave.

<a id="stage-89"></a>

## Stage 89 — An assignee plans their own card, and the bucket picker is one card wide (2026-10-28, live, Kotlin + CSS)

**Stage 101 supersedes this section's Completed-card insert special case:** an assignee's newly added step is
unticked in every status, not automatically done. The planning permission and management-only removal rule below
are unchanged.

**The ask, in the user's words.** *"make non management able to add checklist on own assigned task cards, and make other
tasks dropdown small like length of card."* Two small changes with one line each: the assignee of a card gains the
*New step…* line on it, and the Stage 88 **Bucket** picker stops stretching across the page.

**The plan is the owner's to add to.** Since Stage 41 the popup has been the only place a plan is written, and it drew
the add line on `TaskForm.canEditAll` alone — management writes any card's list, the assignee works it down
(`mayTick`). The user's sentence makes the person doing the work a writer of it too, so the right now follows the
person rather than the role:

```kotlin
fun addChecklistItem(taskId: Int, label: String, callerEmpNo: String, canManage: Boolean): Int? {
    val task = TaskRepository.findById(taskId) ?: return null
    if (!canManage && task.assignee != callerEmpNo) return null
    …
}
```

That is `addNote`'s shape exactly (`!canManage && task.assignee != callerEmpNo`), and the popup reads it back as
`TaskForm.mayAddStep = canEditAll || ownsCard` (which is `mayTick`'s line — the plan is one right now: read, work,
extend). `modalSteps` gates `stepAddForm` on it. **Taking a step off stays `canEditAll`**: the × is still management's,
because knowing the work lets somebody add to the plan, but striking a line somebody else wrote off it is a decision
about the plan. So an owner's rows carry the box, the words and the add line — and no ×. A stranger (`viewOnly`,
Stage 86) is unchanged: the plan drawn stopped, no add line, no box; the service refuses their POST either way. A
**Completed** card is unchanged for both readers: the rows stay stopped, the add line is drawn, and a step written into
a settled plan lands ticked (`syncPlanWithStatus`'s measure).

**The picker was a select across the page.** The Stage 88 picker put the `<select>` *beside* the `label.board-key`.
`.board-toolbar .board-key select` (the projects board's 140px sort pickers) therefore never matched, and the select
fell through to the bare `select { width: 100% }` in `app.css` — a one-dropdown filter that stretched the form to the
toolbar's full width and pushed **Show** onto a second line. Two edits, no route and no JS:

- the select moved **inside** its label, exactly as the projects board's pickers are written, so the field styling
  applies to the pair;
- `form.other-bucket-filter .board-key select { width: 200px; min-width: 0; max-width: 100% }` — one card's length on
  the board underneath (a fifth of the board, less its gaps and the column's padding). The `form.` in the selector
  outranks the toolbar's own picker rule further down the file, and `min-width: 0` on the label and the select is what
  lets the pair shrink below 200px on a narrow window instead of overflowing. The form's `align-items: center` keeps
  the field and **Show** on one line.

**Tests.** **628 / 41 classes / 0 failures/errors/skipped** — one added, three re-pointed. `AssignedTasksRenderTest`
adds `the bucket picker is one labelled field, not a toolbar across the page`: the select is inside
`label.board-key`, the label's own word is *Bucket*, and no stale `for="otherBucket"` points out of it.
`TaskServiceTest`'s management-only case became `the assignee adds to their own plan, a stranger does not, and neither
takes a step off` — the owner's add is written and appended, a colleague's is refused, the owner's × is refused, and
the owner still ticks. `TaskBoardRenderTest`'s owner-popup case now asserts one `checklist/add` and `stepAdd-<id>` with
the popup's `edit`, and **no** `checklist/remove`; its completed-card case asserts the same for the owner of a finished
card. `ProjectsPageRenderTest`'s owner popup flipped from *no add line* to *add line, no ×*.

**Verified over real HTTP, on a throwaway** (8097, `PTS_SEED_DEMO=true`, a card inserted for a **Viewer** account,
EMP0016): that account's card popup carried `stepAdd-11` and one `checklist/add`; `POST /app/tasks/checklist/add` wrote
the row (`Viewer written step`, unticked), the reopened popup printed it with `0 of 1 done`, one `checklist/toggle` and
no `checklist/remove`; `POST /app/tasks/checklist/remove` from the same session left the row in place; a POST adding a
step to another person's card (EMP0002's, task 1) wrote nothing; the same card opened by EMP0016 printed no add line
and `Close`. The picker measured in the in-app browser as a select **inside** the label, 200px where the form has room
and 167px in that narrow window, with no horizontal overflow — before the fix the same measurement read 219px (the
form's whole width) with **Show** below it.

**Deploy note.** CSS changed, so **Ctrl+F5** is needed for the picker. Live `data/pts.db` was backed up as
`data/pts.pre-stage89-20261028-120806.db*`; the live JVM (PID 9972) and the throwaway were stopped, `installDist` run,
and the instance launched **detached** from `deploy/run.bat` (PID **21456**); `/` and `/static/css/app.css` answer 200
and the served sheet carries the new rules. Schema stays **v18**.

**If this is restated.** Never re-propose: the × on an assignee's own plan (management shortens a list, the owner only
adds); a "management only" add line that a hand-built POST still honours for the assignee's own card; or a bucket
picker that stretches to the toolbar (it is one card wide, and the select belongs inside its label — that nesting is
what keeps it off the bare `select { width: 100% }`).

<a id="stage-90"></a>

## Stage 90 — An empty Other-tasks board still shows its picker, and the reader can write the first card (2026-10-28, live, Kotlin)

**The ask, in the user's words.** *"if user dont have other tasks assigned the user still able to see bucket dropdown and
add themself tasks"*. Two halves in one sentence, and the second is a right, not a layout fix: a reader holding **no**
Other-task cards must still see the **Bucket** picker, and must be able to **add an Other task assigned to themselves**
in the bucket it shows.

**Where the right already lived.** [`TaskService.createBacklog(draft, callerEmpNo, canManage)`](src/main/kotlin/mn/murray/pts/service/TaskService.kt)
has always rebuilt a non-manager's draft as `assigneeEmpNo = callerEmpNo`, `status = "Not started"` and no project — its
KDoc says *"Every signed-in account may do this"* — because the Team panel (Stage 50) and Stage 42's universal box called
it with the reader's own identity. Stage 51 then removed the **ways in** on the boards and left the service permissive;
the Assigned page (Stage 55) had no create line at all, and its picker was drawn only when the reader had cards to filter
(Stage 88). So the Ask is not a new service rule: it is a route and one line of markup, after which a hand-built create
lands as the caller's own card exactly as `createBacklog` has always promised.

**The picker stays on an empty board.** `assignedTasksContent` now draws `otherBucketFilter` whenever the board has
**columns at all** — not when the reader has cards — because it has become the chooser for the create line beneath it. The
empty sentence is unchanged (and still names a filtered bucket: *"You don't have any other tasks in HR."*). A stale
`?bucket=` therefore also keeps a picker with a way back to *All buckets*, which is the Stage 88 rule surviving intact.

**One `+ Add task` line under the board.** `otherAddTaskForm` is `bucketAddTaskForm`'s mechanism from the Other-tasks page
— a GET carrying `new=1` and the `column` to open on — aimed at `/app/assigned-tasks`, with
`data-live-target="#taskModal"` so `app.js` swaps the popup in without reloading the board:

```kotlin
form(action = "/app/assigned-tasks", method = FormMethod.get, classes = "kcol-add") {
    attributes["data-live-target"] = "#taskModal"
    input(type = InputType.hidden, name = "new") { value = "1" }
    input(type = InputType.hidden, name = "column") { value = "${aim.id}" }
    if (chosen != null) input(type = InputType.hidden, name = "bucket") { value = "${chosen.id}" }
    button(type = ButtonType.submit, classes = "kcol-add-btn") { +"+ Add task" }
}
```

`aim` is the chosen bucket, else the board's **first** column: a non-manager's popup pins its column (Stage 49), so the
line has to name one even under *All buckets*. It wears the existing `.kcol-add` / `.kcol-add-btn` vocabulary, so there is
**no CSS change**. `bucket` rides only while a filter is on, which is what brings the save back to the same narrow board.

**The popup is the one the page already had, and management gets the full one.** `openingTaskForm` gained
`allowSelfBacklogCreate: Boolean = false`; only `/app/assigned-tasks` passes `true`, so the backlog branch of the GET
opens the blank create form when the column is real and — new — either the caller is management **or** this page asked for
the self-service line:

```kotlin
if (into != null && (canManage || allowSelfBacklogCreate)) {
    return backlogFormOf(canManage, session.employeeNumber, columns, into = into)
}
```

`backlogFormOf` already pins a non-manager's writer and status, so the reader sees *their own name* and *Not started* as
facts while management sees the roster and the status picker. The save route's Stage 51 discard became conditional: a
column-carrying create from a non-manager is still bounced **when `back == BACK_OTHER`** (the shared board, still
management's), and otherwise goes through to `createBacklog`, which pins it to the caller. `/app/other-tasks` keeps its
Stage 51 shape exactly: its board's add line, its popup and its save branch are still `canManage`'s.

**What did not change.** The **projects** half of this page still grants no create to a non-manager (`canAdd = canManage`):
`mayAddTasks` takes a project, and no one project is this page's to grant on. The shared Other-tasks board, its `⋮` and
*Add bucket*, the Team assign panel, and the sidebar link are untouched. Nothing here lets a reader write a card for a
colleague — the service's rebuild makes the draft theirs whatever the POST names.

*(**Stage 124** supersedes the shared-*Add bucket*-untouched claim only as to where its create popup can open:
the empty Assigned projects panel offers that same add half as `+ New bucket` for *Edit any task* plus *Manage buckets*.
Its POST returns to the lower personal picker on Assigned; the shared board, its `⋮`, the task head opener and the
self-assignment rule do not change. Stage 124 was verified and deployed 2026-11-06 (726 tests/49 classes, 25 HTTP and 90 JavaScript checks; JavaScript changed → Ctrl+F5).)*

**Tests.** **631 / 41 classes / 0 failures/errors/skipped** — three added, all in `AssignedTasksRenderTest`:
`a reader with no other tasks still gets the picker and a line to add their own` (picker, `.kcol-add`, action
`/app/assigned-tasks`, `new=1`, `column=<first>`, `data-live-target`, and the empty line still printed);
`the add line follows the bucket the picker shows, and brings the save back to it` (`column=site` and `bucket=site`, not
HR); and `the add line opens a create form pinned to the reader` (`backlogFormOf(false, "EMP0002", …, into = site)`:
`back=assigned:<site>`, assignee select holds EMP0002 and not EMP0001, status `disabled` with *Not started* selected,
the column present, and the button reading *Create task*).

**Verified over real HTTP, on a throwaway** (8097, `PTS_SEED_DEMO=true`; **Viewer** EMP0016, who held no Other cards): the
page printed the picker and the add line with `column=1`; `?new=1&column=1` opened the popup with assignee EMP0016,
column and status **disabled** at *Not started*, and `back=assigned`; a hand-built POST naming **EMP0002** and
**Completed** created row 11 as **EMP0016 / Not started / OTHER / project NULL / bucket 1** — the service's pin, not the
POST's words; a filtered page carried `bucket=1` and its save redirected to `?bucket=1`; a create posted with
`back=other` wrote **no row** (0) and `/app/other-tasks?new=1&column=1` left the popup shut; management EMP0002's popup
stayed fully editable; and a Viewer's bare `?new=1` or `?new=1&column=9999` opened nothing. Throwaway stopped.

**Deploy note.** Kotlin only — **no Ctrl+F5**. Live `data/pts.db` was backed up as
`data/pts.pre-stage90-20261028-132744.db{,-wal,-shm}`; the live JVM (PID 21456) was stopped, `installDist` run, and the
instance launched **detached** from `deploy/run.bat` (PID **24956**, started 13:28:24); `/` and `/static/css/app.css`
answer 200, port 8080 is owned by 24956, Flyway validates **V18** with *"No migration necessary"*. Schema stays **v18**.

**If this is restated.** Never re-propose: a picker hidden on an empty board (it is the chooser for the create line — and
a hidden empty-board control was the bug); a create line that is management's on this page while the service pins a
non-manager's draft anyway (the right is the reader's; only the *shared* board's line is management's); a per-status add
line on the Other board (one line at the foot, aimed at a bucket, exactly as the shared board does it); a reader-visible
writer or status picker on a self-service create (they are facts, and `createBacklog` re-pins them regardless); or a
non-manager create on the projects half of this page.

<a id="stage-91"></a>

## Stage 91 — The new-project number opens empty, for the reader to type (2026-10-28, live, Kotlin + JS)

**The ask, in the user's words.** *"when creating a new project project name takes type code first but i need to put the
type code initial"*. Asked which behaviour they meant, they chose **"Start it blank"**: on the New project popup the
**Project number** box opens empty, with the category's next free code kept only as a placeholder/hint, so the reader
types the whole number — its category initial included — instead of deleting one the app wrote.

*Superseded the same day by **Stage 92**: asked again with the category's letter spelled out, the user wants the initial
written for them and only the number left to type, so the box now opens on `G-`. The placeholder half of this stage
(the next free number is grey, never the value) and the "a refused code comes back as typed" rule still stand; read
Stage 92 for the live behaviour.*

**What the box did.** `projectCreateModal` had `val codeValue = v.code.ifBlank { suggested }` and put that in the input's
`value`, marking it `data-autofilled`; `project-modal.js` then wrote the suggestion in on `DOMContentLoaded` and on
every category change while the box was still "autofilled" or empty. So a reader who wanted their own number had to
clear ours, and a reader who switched category got a different number pushed in. The offer was helpful, but it was in
the wrong place: **the value is the reader's, the offer is a hint.**

**The change.** The input's value is now only what came back on a rejected submit:

```kotlin
input(type = InputType.text, name = "code") {
    id = "projectCode"
    if (v.code.isNotBlank()) value = v.code
    placeholder = suggested.ifEmpty { "$letter-01" }
    required = true
    maxLength = "32"
}
```

The placeholder and the hint still follow the chosen category (`M-03` when Fabrication/Manufacturing runs to `M-02`;
`S-… — type the number yourself, starting with the category letter.`). In `project-modal.js`, `refreshCode` became
`refreshCodeHints`: it writes the placeholder and the hint text from the option's `data-suggest`/`data-letter` and
**never** assigns `code.value`. The `input` listener that dropped `data-autofilled` went with the marker.

**What did not change.** The category still decides which initial is legal (`codeMatchesType`, message *"Start the
number with S- for site projects."*), the format (`S-01`, `M-2026-A`) and uniqueness are still checked on submit, and a
rejected create still re-renders the popup with the number exactly as typed. The next free number is still computed
and still offered — in grey. No route, no schema, no CSS.

**Tests.** **632 / 41 classes / 0 failures/errors/skipped** — one added,
`the create popup leaves the project number for the reader to type` in `ProjectsPageRenderTest`: the `#projectCode`
input carries **no** `value` attribute, its placeholder is the selected category's next free code, and the hint no
longer calls the number an offer. The existing rejected-create case still pins that a typed number comes back as typed.

**Verified over real HTTP, on a throwaway** (8098, demo seed; EMP0002): `/app/projects` rendered
`<input … id="projectCode" placeholder="M-03" required maxlength="32">` — no value, hint *"M-… — type the number
yourself, starting with the category letter."*; a blank submit re-rendered with the popup open, *Enter a project
number.* under the field, still blank, and the placeholder moved to *S-10* after posting Site projects; a typed `S-30`
created project 20. The served `project-modal.js` has `refreshCodeHints` and no `data-autofilled`; no other script
writes the field. (The in-app browser's login still does not stick, so the popup was read over curl as in Stages 88–90;
the script's only DOM writes are `placeholder` and `textContent`, so it cannot refill the box.)

**Deploy note.** JS changed, so **Ctrl+F5** is needed for the popup. Live `data/pts.db` was backed up as
`data/pts.pre-stage91-20261028-135518.db{,-wal,-shm}`; the live JVM (PID 24956) was stopped, `installDist` run, and the
instance launched **detached** from `deploy/run.bat` (PID **18640**, started 13:55:49); `/` 200, the served
`project-modal.js` carries the new function, and Flyway validates **V18** with *"No migration necessary"*.

**If this is restated.** Never re-propose: writing the next free number into the box as a value (it stays the
placeholder, and the reader types the code); a fixed non-editable initial segment before the box (the initial is the
first character the reader types); relaxing the category-letter rule — that was the other offered option and was **not**
chosen, so `codeMatchesType` stands; or auto-inserting the initial on focus/blur, which is the same thing the value
offer was.

<a id="stage-92"></a>

## Stage 92 — The number opens with the category's letter, and a new project no longer chooses its buckets (2026-10-28, live, Kotlin + JS + CSS)

**The ask, in the user's words.** *"when the user create a new project user is choosing project type for example Good
sales so its code is G. So system puts G automatically so user dont have to write. And it changes for every type. Also
when creating a new project dont have to ask new project's buckets as we have already 3 default buckets"*. Two changes,
one form: the **Project number** box carries the chosen category's lead (`G-`) so the reader types only the number, and
the bucket picker of Stages 73–77 is taken off the popup.

**The number: a lead the app writes, a tail the reader owns.** `projectCreateModal` already had `letter` for the
placeholder; it now also seeds the value and marks the lead:

```kotlin
value = v.code.ifBlank { if (letter.isEmpty()) "" else "$letter-" }
placeholder = suggested.ifEmpty { "$letter-01" }
attributes["data-prefix"] = "$letter-"
```

The placeholder is still the next free code, so the lead says what the number *starts with* and the grey text says what it
might be — two different facts, and neither is a promise. `project-modal.js`'s `refreshCodeHints` now does three things on
a category change, in this order: an empty box (or one holding nothing but its lead) gets the new lead; a box holding a
code whose lead is the **old** category's has that lead swapped and **the number after the dash carried across**
(`M-07` → `G-07`); a box whose lead already matches is left alone. The third case is what keeps a refused submit honest:
the server re-renders `v.code` exactly as typed and `data-prefix` as the *chosen* category's lead, so when the two agree
but the code begins with another letter, that letter is the reader's own and the error under the field may be about it —
rewriting it would erase the thing being complained about. The hint reads *"M-… — type the number after the dash; the
letter follows the category."*

**The buckets: the popup says what the board will hold, and posts nothing about it.** Every project is created with the
three phases seeded by `ProjectService.create` (`seedDefaults`), so choosing extras before the board exists meant
answering a question about a board nobody could see, and the category's catalogue offer had already been reduced to an
opt-in tick (Stage 77). The picker is replaced by one line:

```kotlin
div("field full bucket-pick") {
    label { +"Buckets" }
    p("bucket-floor") {
        +"Every new board starts with: "
        DEFAULT_PROJECT_BUCKETS.forEach { lane -> span("chip lane-chip") { +lane } }
    }
    p("field-hint") { +"Add more on the project page after it is created." }
}
```

It is a statement, not a control: the three names are chips, there is no tick, no typed box, no `also_type`, and no
per-category set. `POST /app/projects` stops reading `bucket`/`new_bucket`/`also_type` entirely, so a hand-built POST
naming them creates the same three-phase board as the form — the create has one shape of board, and the board's own column
popup (`laneAddBody`, unchanged) is where it grows.

**What was deleted, and what was kept.** In `Projects.kt`, `bucketSet`, `bucketOption` and `bucketNewLine` (once the create
picker's only callers) are gone, along with the `BucketDefaultsService` import. In `project-modal.js`, `refreshBuckets`
and the `.bucket-set` contract in its header doc are gone. In `app.js`, the whole `[data-bucket-add]` block — `bucketSetOf`,
`addBucketFrom`, its click listener and its Enter listener — is gone, because no page prints that marker any more. In
`app.css`, `.bucket-set`, `.bucket-set-off`, `.bucket-opt`, `.bucket-none` and `.bucket-new` are gone; `.bucket-pick` /
`.bucket-floor` stay for the new line and `.bucket-widen` / `.bucket-widen-why` stay for the column popup. Kept on purpose:
`ProjectDraft.buckets` / `newBucket` / `bucketForType`, `BucketDefaultsService.chosenFor`, `TypeDefaultBucketRepository`
and `ProjectService.create`'s seeding — the service still accepts a bucket list (its own tests and the board's save use
that path), the popup is simply no longer one of its callers. *(**Stage 98** finished the deletion this paragraph left
half-done: the draft's three bucket fields and `ProjectField.BUCKETS` are gone, and `create` seeds the category's whole
catalogue with `seedExtras(id, type, namesOf(type))`. `chosenFor` and the seeding survive because a project page's popup
is their remaining caller. Read 98.)*

**Why not just hide it.** Hiding the picker would have left the form posting ticks from a category set nobody could see —
the exact failure Stage 73's `disabled`-not-hidden rule existed to prevent. The question is withdrawn, not covered up: the
route does not read the fields, and the markup does not print them.

**Tests.** Suite **632 → 630 / 41 classes / 0 failures**. Two cases deleted with the behaviour —
`a rejected create brings the buckets back as they were ticked` and `the create popup asks whether a typed bucket is for
the category as well` (the column popup's version of the latter stays, and is now the tick's only pin). One re-pointed:
`the create popup offers this category's buckets above the three every board opens with` now asserts the bucket line
names the three chips and the "add more on the project page" sentence, and that the page prints **no** `name="bucket"`,
`new_bucket`, `also_type`, `data-bucket-type` or `data-bucket-add`. *(**Stage 104** brought two of those five back —
`new_bucket` and `data-bucket-add` — as the line that adds a column to this one board. `also_type`, `data-bucket-type`
and the tick-list stay absent, and are the part of this sentence that still holds: the popup may name a bucket, it may
still not choose from the category's list or widen it.)* One flipped: Stage 91's
`the create popup leaves the project number for the reader to type` became
`the create popup leads the number with the chosen category's letter` — `value="M-"`, `data-prefix="M-"`, the next free
number still the placeholder, the new hint, and a draft's `Site projects` leading `S-` with `S-02` (the fixture already
holds `S-01`).

**Verified over real HTTP, on a throwaway** (8098, demo seed; EMP0001, with `must_change_password` cleared on the
throwaway only): `/app/projects` served
`<input type="text" name="code" id="projectCode" value="M-" placeholder="M-03" data-prefix="M-" required maxlength="32">`
and the hint *"M-… — type the number after the dash; the letter follows the category."*; the popup held one `bucket-pick`
line, zero `data-bucket*`, zero `new_bucket`/`also_type`. `POST /app/projects` with no bucket fields created project 20
(`G-01`) whose board is exactly **Engineering / Procurement / Production**; the same POST with `bucket=Commissioning`,
`bucket=QA hold`, `new_bucket=Handover` and `also_type=1` created project 21 with the same three columns, and
`type_default_buckets` for *Goods sales* stayed empty. The served `app.js` has no `data-bucket-add`, and the served CSS has
no `.bucket-set`. The lead logic was driven against the shipped file with a DOM stub (no browser: the in-app browser still
will not hold a login): load `M-`/`M-03`; empty → `G-`/`G-01`; `M-07` → `G-07`; refused `S-01` stays `S-01`; `G-07` under
`G` stays `G-07`; bare `07` → `S-07`; lead-only `G-` → `M-`.

**Deploy note.** JS and CSS changed, so **Ctrl+F5** is needed for the popup. Live `data/pts.db` was backed up as
`data/pts.pre-stage92-20261028-142535.db`; the live JVM (PID 18640) was stopped with `deploy\stop.bat` (which also reaped
the throwaway on 8098), `installDist` run, and the instance launched **detached** from `deploy/run.bat` (PID **15008**);
`/` 200, the served `project-modal.js` carries `data-prefix`, and Flyway validates **V18** with *"No migration
necessary"*.

**If this is restated.** Never re-propose: an empty number box (Stage 91's answer, withdrawn by this one — the initial is
written, the number is not); a fixed non-editable prefix control before the box (the reader may still retype the whole
code, and `codeMatchesType` still refuses a wrong initial under the field); pushing the next free number in as a value; a
create-popup bucket picker in any form — tick-list, typed name, or the "also make it a category default" tick (the column
popup is its home); hiding the picker while leaving it on the POST (the route reads no bucket field at all); a "Buckets"
line that is a control rather than a statement about the three seeded phases; or dropping the reader's number when the
category changes (the lead moves, the tail survives).

<a id="stage-93"></a>

## Stage 93 — The shared backlog gets the Assigned page's Bucket picker (2026-10-28, live, no migration, Kotlin + JS)

**The ask, in the user's words.** *"on the other tasks make it same as assigned tasks' other tasks make a dropdown for
bucket choosing"*. Stage 88 had given the Assigned page's Other half a **Bucket** dropdown; the Other tasks page itself
— the shared backlog, whose board is already one group per bucket (Stage 57) — had no way to say *just this bucket*.
This stage puts the same control on it, in the same shape and with the same behaviour.

**The control.** `otherTasksContent` now takes `showBucketId: Int = 0` and, for management on a board with at least
one bucket (Stage 90's rule, unchanged: an empty board's picker is still the only place a first bucket can be
aimed), prints `otherBucketPicker` above the groups:

```kotlin
form(action = "/app/other-tasks", method = FormMethod.get, classes = "board-toolbar other-bucket-filter") {
    id = "otherBucketFilter"
    attributes["data-live-extra"] = "#bucketsToggle,#addBucketOpener"
    if (showAll) input(type = InputType.hidden, name = "allbuckets") { value = "1" }
    label("board-key") {
        +"Bucket"
        select {
            id = "otherBucket"; name = "show"
            attributes["data-autosubmit"] = "true"
            option { value = "0"; selected = chosen == null; +"All buckets" }
            buckets.forEach { option { value = "${it.id}"; selected = it.id == chosen?.id; +it.name } }
        }
    }
    button(type = ButtonType.submit, classes = "submit btn-sm") { +"Show" }
}
```

*(As shipped by **Stage 97** two lines of that block are different: `data-live-extra` names only `#addBucketOpener` —
the toggle it also had to redraw is gone — and the `if (showAll) input … allbuckets` line no longer exists, so the
picker carries one piece of view state, the `show` select. See the Stage 97 section.)*

It is the Assigned page's picker to the letter — label inside `.board-key`, one card wide (Stage 89), *Show* as the
scripts-off path — and it sits **above** the board because `#otherBoard` is now the page's `[data-live-region]`:
`app.js`'s `regionFor` climbs from the form, so the toolbar is never part of what gets swapped, the board redraws in
place, and `refresh` ends with `history.replaceState`, which makes the URL the view. The `⋮` menus, the `+ Add task`
lines and the cards themselves stay exactly where Stage 57 put them.

**`?show=`, not `?bucket=`.** On this page `?bucket=<id>` already names a column's *popup* (the `⋮` opener) and
`?column=<id>` aims the create popup, so the view key had to be a third name: `show`. It is read on the GET route and
passed to `otherTasksContent`; an id the board does not have answers *All buckets* rather than an empty page, and a
chosen bucket prints **its own group even when empty** — five columns saying *"Nothing here."* — because choosing a
column is a question about that column, not a request to hide it.

**Whose page the picker belongs to.** The shared board is management's (Stage 51), so its filter is drawn for
`canManage` alone, and the content ignores `?show=` for anybody else: a Viewer keeps the one-bucket slice their own
card stands in. A dropdown of every bucket on a page scoped to one would be a directory of columns they hold no card
in — and a `?show=` naming one of those would print an empty group carrying its name, which is the leak the gate
closes.

**The wide flag stays orthogonal.** `allbuckets` (Stages 57/90) still only says whether the empty groups are printed;
the picker carries it as a hidden field so picking a bucket keeps the reader's wide view. The head's toggle keeps its
second reading from this stage: on a narrowed board it **drops the filter and keeps the flag**, so *Show all buckets*
means what it says and nothing else; narrowed-and-narrow it posts no `allbuckets` at all, because the board it returns
to is the page's own default.
*(**Superseded by Stage 97**, which removed both the flag and the toggle. The picker is now the board's only view
control and the second reading is its only reading — a choice narrows, *All buckets* (`0`) widens back — and because
the picker is a **filter** form, `refresh()` already rewrites the address bar for it: `[data-live-url]` is still in
`app.js` as a mechanism, with no form on any page setting it.)*

That press is a *view change*, not a popup, so the address bar has to follow it. `app.js` gained a fourth block-form
attribute:

```js
if (method === 'GET' && form.hasAttribute('data-live-url')) {
  window.history.replaceState(null, '', url);
}
```

`[data-live-url]` on a GET block form says *the block this send swaps **is** the board*; the fetched URL replaces the
one in history exactly as a filter form's does. Without it, pressing the toggle while `?show=2` was up would leave
that filter in the URL and a reload would undo what the reader had just done. Only the filtered toggle carries it:
unfiltered, the press is the transient wide view it has always been, and the URL the reader opened still describes
the board they return to.

**The filter rides `back`.** Stage 88's twin token: `backForOtherBucket(id)` renders `other` or `other:<id>`,
`bucketOfOtherBack` reads it, `isOtherBack` recognises it, and `taskRedirect` appends `show=<id>` to
`/app/other-tasks` (beside `allbuckets=1` when the wide view is up). `backOf` normalises the posted token, so a
hand-built or unrecognised value lands on the full board. Everything that opens, saves, ticks, notes, deletes or
refuses a card from this page carries the filter, which on the markup side means one hidden `show` on each of: the
column menu, the `+ Add task` line, the card's own opener, the popup's add / rename / delete forms, and
`+ Add bucket`. The bucket routes read `show` back and `bucketsBack(showAll, forceAll, show)` returns to the narrowed
board — except a **create**, which passes `forceAll` so the bucket just named is visible instead of being hidden by a
filter that predates it.

**Tests.** Suite **630 → 636 / 41 classes / 0 failures**. Six new in `OtherTasksRenderTest`: the board carries the
same picker (one `board-toolbar other-bucket-filter`, `data-autosubmit`, *All buckets* selected, every column
offered); choosing a bucket prints that group and no other, with the picker selected on it and five status columns
still drawn; **a Viewer's page keeps its one bucket and grows no picker** — no `other-bucket-filter`, no bucket
name they hold no card in, and a `?show=` naming another bucket changes nothing; the chosen bucket travels with
every way off the board (hidden `show` on menu, add line, card opener and popup forms, `back=other:<id>` on the save
form, `taskRedirect` returning `/app/other-tasks?show=<id>`); the toggle drops the filter and keeps the wide flag
(`data-live-url`, no `allbuckets` when narrow, `allbuckets=1` when wide, and no rewrite on an unfiltered board); and
the token itself as a unit case (`backForOtherBucket`, `bucketOfOtherBack`, `isOtherBack`, `taskRedirect`, including
a junk token and `otherwise` not being `other`). Existing exact-string assertions were re-pointed for the two new
companions on the picker and the toggle's extra ids.

**Verified over real HTTP, on a throwaway** (8098, demo seed; EMP0001 with `must_change_password` cleared on the
throwaway only): `/app/other-tasks` served exactly one
`<form … class="board-toolbar other-bucket-filter" id="otherBucketFilter" data-live-extra="#bucketsToggle,#addBucketOpener">`
with `select#otherBucket name="show" data-autosubmit="true"` and *All buckets* selected; `?show=1` printed one
bucket group (*Management*) and six hidden `show` fields with `back=other:1`; `?show=2` (an empty bucket) printed its
group's five *"Nothing here."* columns; `?show=999` fell back to *All buckets*; `?allbuckets=1&show=1` kept the flag
and the filter; a real `POST /app/assigned-tasks/save` from the filtered board answered **302 →
`/app/other-tasks?show=1`** and the card appeared there; the toggle carried `data-live-url` only while filtered, and
on the narrow filtered board it printed no `allbuckets` field at all. Signed in as the seeded **Viewer** EMP0016
(with one backlog card in *Management*, on the throwaway only), `/app/other-tasks` printed **no** picker, no bucket
name they hold no card in, their own card, and `?show=2` neither added a picker nor named *Site works*. The served
`app.js` contains the new branch. The click itself was not driven in a browser — the in-app browser still holds no
login on localhost and returns an empty body — so the JS is reviewed and its markup pinned, not observed.

**Deploy note.** JS changed, so **Ctrl+F5** is needed for the toggle's URL rewrite. Live `data/pts.db` was backed up
as `data/pts.pre-stage93-20261028-150426.db*`; the live JVM (PID 15008) was stopped with `deploy\stop.bat` (which
also reaped the throwaway on 8098), `installDist` run, and the instance launched **detached** from `deploy/run.bat`
(PID 25352). The Viewer gate was added after that deploy, so the sequence ran again over a second backup
(`data/pts.pre-stage93b-20261028-151726.db*`). That instance (PID 17844) died with the shell that had launched it —
`run.bat` started from inside a tool shell is reaped with that shell — so it was launched once more **detached** with
`Start-Process cmd.exe -ArgumentList '/c','deploy\run.bat'`; the live instance is now PID **536**, `/` answers 200 and
the served `app.js` carries `data-live-url`. Schema stays **V18** — no migration. **That swap cost the board its
drag-and-drop** — see Stage 94, which found and fixed it the next morning; that stage ran PID **19808** and, after its
comment-only rebuild, PID **8744**.

**If this is restated.** Never re-propose: `?bucket=` as this page's view key (it opens a column's popup); a picker
that also renames the board's shape into one flat status board (Stage 57's group-per-bucket stacking stays); a filter
that is lost when a card is opened, saved or refused; a toggle that clears the filter **and** the wide view in one
press; hiding the picker on a board with no buckets (the first bucket is named from that popup); a view kept in a
session or cookie instead of the URL; or letting a toggle press leave the dropped filter in the address bar.

<a id="stage-94"></a>

## Stage 94 — The backlog board stays draggable after its own picker swaps it (2026-10-29, live, JS only, no migration)

**The ask.** *"on other tasks kanban is not working"* — reported against the page as Stage 93 left it. The board
printed, the picker narrowed it, but a card would not drag between the status columns any more.

**What was actually broken, and when.** Nothing in Stage 93 touched the drag. What it touched was the *node*: making
`#otherBoard` the page's live region is what lets the Bucket picker answer in place, and `app.js` answers a live
region by parsing the response and `replaceChild`-ing a brand-new copy over the old block. `kanban.js` had bound its
status half once, at page load, with `document.querySelectorAll('.kcol[data-status]').forEach(col => col.addEvent…)`
— and a listener belongs to the node it was put on, not to the HTML the node was made from. So the board as it
arrived on a fresh load was draggable (Stage 58's own fix, which the suite pins at the markup level, kept working)
and the *narrowed* board was not: no column highlighted, the drop did nothing, and the `←`/`→` arrows — bound the
same per-element way — were dead with it. This is exactly the trap `kanban.js`'s lane half and `app.js` had already
avoided by delegating on `document`, and it is why Stage 93's own verification missed it: over HTTP the swap is
`app.js`'s work, and the harness could only read markup.

**The fix — `kanban.js`, both halves, no markup and no route change.** `statusCol(node)` is
`node.closest('.kcol[data-status]')` and the three listeners (`dragover`, `dragleave`, `drop`) sit on `document`,
each returning early when the drag is not over a status column. `dragleave` additionally ignores a `relatedTarget`
the column still contains: crossing from a column onto one of its own cards fires `dragleave` with that card as the
target, and clearing the highlight there is the flicker the old per-column binding had too. The `.kmove-btn` press is
now one delegated `click` on `document` (found with `closest('.kmove-btn')`, still `preventDefault` +
`stopPropagation`, still disabling the button so a double-click cannot post the step twice) — which also cures a
second, older instance of the same bug: a project page's `✓ Done` / `↺ Reopen` are `.kmove-btn` inside `#projectBoard`,
and that block has been swapped in place by its own lane writes since Stage 74, so a Done button that arrived with a
renamed or added bucket was dead until the next reload. The two halves stay separated by the attribute they read:
`data-status` here, `data-lane` there, and no `.kcol` carries both, so one drop never posts twice.

**What deliberately did not change.** A status drop still posts `/app/tasks/status` and reloads, exactly as Stage 58
shipped it, and the arrows still do the same write: the reload is what brings the sidebar's open-card count back in
step, and a status move repaints the card's tint and the column counts from the server's own grouping. (**Stage 103**
retired those arrows; the delegated press this stage added is what a lane board's **✓ Done / ↺ Reopen** still rides on,
so the handler stays.) Only the
lane move stays in place (`file()` + `count()`), because that one is a card changing column, not a card changing
what it is. Making the status write in-place would be a separate ask, and would have to answer the sidebar count
first. No Kotlin, no CSS, no schema (V18), no route.

**Verified — the shipped file, in a stub DOM, against its own before-image.** The suite cannot see this: `kanban.js`
is not exercised by a Kotlin render test, and the render tests that pin `.kcol[data-status]` /
`.kcard[draggable][data-id]` / `.kmove-btn[data-*]` all still passed *on the broken build*. So
`scratch/s94-kanban-harness.cjs` loads the real file against a minimal DOM whose `fire()` walks the target's
ancestors the way bubbling does, runs a press, replaces the whole board with a fresh copy (what the swap does), and
runs the same presses again. `--prefix` rebuilds the pre-Stage-94 binding from the same source and runs the
identical scenario as the control:

```
                     shipped   before Stage 94
highlight after swap    true          false
post after swap       1 write        none
arrow after swap      1 write        none
```

Both runs keep the load-time board working, the lane move filing in place with counts `0`/`1` and no reload, and a
status drop posting only `/app/tasks/status`. `./gradlew test` is unchanged at **636 / 41 classes / 0 failures** —
this stage adds no Kotlin test because it changes no Kotlin, and the comment that described the old binding in
`OtherTasks.kt` was corrected rather than left to mislead the next reader.

**Deploy.** `kanban.js` is a packaged resource, so **Ctrl+F5** is needed. Live `data/pts.db` (with its WAL) was backed
up as `data/pts.pre-stage94-20261029-090001.db*`; the Stage 93 instance (PID 536) was stopped with `deploy\stop.bat`
(polite close refused, force-killed after 5 s), `installDist` run, and the instance launched **detached** with
`Start-Process cmd.exe -ArgumentList '/c','deploy\run.bat'` → PID **19808**. `/` answered 200 and the *served*
`/static/js/kanban.js` was byte-for-byte the edited file (11,118 bytes), carrying two `document`-level `drop`
listeners and **no** `querySelectorAll('.kcol[data-status]')`. `app.js`'s contract line was added after that deploy,
so the sequence ran again over a second backup (`data/pts.pre-stage94b-20261029-090647.db*`) and **the live instance is
now PID 8744**: `/` 200, served `kanban.js` still the delegated one, served `app.js` carrying the new
`[data-live-region]` sentence, log shows the catalogue load, the due-date sweep and `Responding at
http://127.0.0.1:8080`. Schema stays **V18**. Not observed in a real browser: the in-app browser still returns an
empty body for localhost, so the click-and-drag was proven against the shipped file in the harness rather than on
screen.

**If this is restated.** Never re-propose: binding a live region's behaviour to nodes collected at page load (any
block that `app.js` can swap needs its handlers on `document` — this is the second stage to be bitten by it, and the
rule to check before adding a script to a board page); a Bucket picker that navigates instead of swapping to dodge
the bug (Stage 93's in-place answer and its URL rewrite stay, and the reader keeps their scroll and their focus);
dropping the reload from a status write (the arrows are the no-drag path and the sidebar count rides on the reload);
`drag-over` cleared whenever the pointer moves inside the column; or letting a status drop also post a bucket change.
"Make the Other tasks drag not reload at all" is the tempting restatement and is **not** this stage — it needs the
sidebar count and the card's own tint solved first.

<a id="stage-95"></a>

## Stage 95 — A backlog card wears the same stage tint as every other card (2026-10-29, live, Kotlin + CSS, no migration)

**The ask.** *"make other tasks kanban cards same colour with assigned task card colour"* — the two boards cut their
cards by the same five statuses, sit one nav-item apart, and were painting one stage two ways: an assigned card wore
its stage's edge and wash (Stage 78), a backlog card wore a slate edge of its own whatever it was.

**The fix is one mapping, not a copy.** `statusSlug` was `private` in `TaskBoard.kt`; it is now `internal`, for the
same reason `statusDot` already is internal — the column dots read it too. `otherCard` calls it, so the backlog's tint
and the assigned board's cannot drift apart, which is the trap this project has hit before when a list was mirrored
instead of shared (`DEFAULT_PROJECT_BUCKETS` and V16). The card prints `kcard <slug> kcard-overlay`, and the five
`.kcard.s-*` rules — four washes and *Not started*'s bare grey edge, plus their dark-theme arms — now apply to the
backlog exactly as they apply to the other boards.

**Why `s-other` had to go rather than sit beside the tint.** That one class was doing two jobs: it was the slate
colour (`.kcard.s-other { border-left-color: #5f6a76 }`) *and* it was the layout hook for the card that carries the
whole-card opener (`position: relative`, and the `z-index` lift for `.kmove` / `.kfoot` so an owner's arrows are
pressed for what they do and not for the popup under them — **Stage 103** retired the arrows and with them this rule's
`.kmove` half; the `.kfoot` lift and the `position: relative` on `.kcard-overlay` remain). Keeping it as a third class would have looked correct in
the markup and painted grey anyway: `.kcard.s-other` and `.kcard.s-inprogress` are both 0,2,0 and the slate rule sat
*later* in the file, so it wins on order — the same tie that hid Stage 79's red button behind `button.submit` and left
Stage 75's bucket dots uncoloured under `.lane-row`. The colour rule is deleted; the layout hook is renamed
`kcard-overlay`, which says what it marks (a card wearing a `.kcard-open` form) and carries no colour at all.

**What did not move.** The backlog card still prints **no stage word** and no chip: its column head *is* the word
(Stage 57), and Stage 78's "two places for one figure" objection holds here even though the tint now repeats it — the
tint is a picture read from across the room, the word is a sentence read at the card. The drag source, the opener
button's `draggable` handle, the owner's arrows, the `#task-body-<id>` live region, Stage 93's picker and Stage 94's
delegated handlers are untouched, and so are the routes, the rights and schema **V18**. `prioClass` / `formatDue` /
`MONTHS` stay file-private copies in `OtherTasks.kt` as they were — text formatting is allowed to drift, a colour is
not.

**Tests.** Suite **636 → 637** / 41 classes / 0 failures. `OtherTasksRenderTest` (32 → **33**) gains
`a backlog card wears the same stage tint the assigned board paints`, which files one card per status in one bucket and
requires `class="kcard <slug> kcard-overlay"` for all five slugs, then denies `s-other` and `kstat` on the same board —
the colour is shared, the word is still the column's. The three pins that found a backlog card by its old class
(Stage 58's draggable node, the owner's arrow case, the open-editor case) now go through a `cardTag()` helper matching
`class="kcard s-[a-z]+ kcard-overlay"`, and the Stage 57 case that counted `s-other` to prove the card was a backlog
card counts that helper instead, so "drawn as a backlog card" now means "carries the opener node and wears its stage".

**Verified.** The stylesheet claim is a specificity claim, so it was read off pixels: `scratch/s95-render-check.ps1`
inlines the shipped `base.css` + `app.css` into a fixture holding both boards side by side (`scratch/s95-tint-body.html`)
and shoots it with headless Chrome in the light and `data-theme="dark"` themes. In progress is amber, Blocked red,
Completed green on **both** sides, in both themes, and the backlog card's arrows and avatar still sit above its overlay.
The packaged build was then proved rather than assumed: `unzip -p` on the dist jar's `static/css/app.css` shows the five
tint rules and **no** `.kcard.s-other` selector, and `javap -v` on `OtherTasksKt.class` carries `kcard-overlay`, **zero**
`s-other`, and two resolved calls to `TaskBoardKt.statusSlug`.

**Deploy.** Both a Kotlin and a static change, so `installDist` + restart + **Ctrl+F5**. Live `data/pts.db` was backed
up in place — `data/pts.pre-stage95-20261029-092541.db`, `-wal` and `-shm` — the Stage 94 instance (PID 8744) was
stopped (polite close refused, force-killed, as always on this box), and the new instance was launched **detached** with
`Start-Process cmd.exe -ArgumentList '/c','deploy\run.bat'` → **PID 19732**, java.exe with `cmd.exe` as its parent and
nothing of this shell's tree above it. `/` answers 200, the *served* `/static/css/app.css` is byte-for-byte the edited
file (`cmp` clean, four `kcard-overlay` occurrences), and the log shows schema 18 up to date, the 9-category catalogue,
the due-date scheduler and `Responding at http://127.0.0.1:8080`. Not observed in a real browser: the in-app browser
still returns an empty body for localhost, so the screen proof is the fixture render plus the packaged markup.

**If this is restated.** Never re-propose: a backlog-only colour, or a "backlog" tint keyed off the bucket instead of
the stage — the ask was parity, and a sixth hue is the thing Stage 78 refused; copying `statusSlug` into
`OtherTasks.kt`'s private set (one mapping, `internal`, is the whole point); re-adding `s-other` as a class on the card
for "backlog styling hooks", which re-creates the tie this stage removed; a stage word or a `tcode` chip on a backlog
card to "make the two boards identical" (Stage 78 took the chip off lane cards, Stage 57 keeps the word in the column
head); or `:has(.kcard-open)` in place of `kcard-overlay` for the opener layout.

<a id="stage-96"></a>

## Stage 96 — One vocabulary for a stage: the project picker reads as the progression (2026-10-29, live, Kotlin + SQL **V19**)

**The ask.** *"when creating a new project choosing status dropdown showing in wrong order should be Not started In
progress On hold Blocked and Completed"*. Two things were being asked at once, and the second is why this stage has a
migration. The order was genuinely wrong — the picker led with the status managers reached for most often, on the theory
that the common choice should be the nearest one. But *In progress* was not a project status at all: it was a **card**
status. A project running at 40% called itself **Ongoing**, and the app had been living with two words for one idea.

**The word "In progress" named two screens**, so the ask went back as two questions before anything was written, and
both answers widened it: **"Rename it to 'In progress' too"** — one vocabulary app-wide, with a `V19` that rewrites the
rows — and **"Follow the picker"** for the board's Status sort.

**What collapsed.** `PROJECT_STATUSES` is now `Not started, In progress, On hold, Blocked, Completed` and
`PROJECT_STATUS_ORDER` is *that same list*, one assignment rather than a second ordering with its own rationale. Retired
with it, in order of how much code they were:

- `Calendar.taskStatusChip` — a whole function whose only job was translating a card's *In progress* into the project
  word *Ongoing* so the calendar's list could colour it. The call site reads `chipClass(task.status)` now; both `chipClass`
  maps (Calendar and Projects) key `"In progress" -> "ontrack"`, which is why the chip class name still says *ontrack*.
- `Team.kt`'s `taskColor` and `team.js`'s `COLORS` each carried an `Ongoing` arm and an `In progress` arm side by side —
  green for the project, amber for the card, one stage. The green arm is gone; the hue table is keyed by five words for
  both of the popup's lists.
- The deliberate divergence between the picker and the sort, which put **Blocked before On hold** on the board because a
  stalled job wants looking at before a parked one. Sound advice; it meant the order a manager read down the Status
  column was not the order the form offered them, and only a comment in `Project.kt` recorded which was which.

**The migration.** `V19__project_status_in_progress.sql` is one statement —
`UPDATE projects SET status = 'In progress' WHERE status = 'Ongoing';`. `projects.status` is free text with no CHECK
constraint since V1 (the five words are enforced at the form, not the schema), which is what makes a single `UPDATE` the
whole change: no table copy, no index rebuild. It deliberately leaves `updated_at` alone — this is the app renaming its
own vocabulary, not a manager moving a project, and stamping every row would make a job from last month look as if it
moved today. It does not touch `tasks.status` (the card word was already the one everything is converging on),
`forecasts.invoice_status`, or `notifications.mail_status` — those merely share the column name. And a status the app
cannot name survives as written: V19 renames one word, it is not a cleaning pass. `OLD_STATUS_MAP` gained the v2 word
alongside the legacy `On track`, so a restored older export lands on the same five words without a second pass.

**What did not move.** Still five statuses, no new one. The board's sort captions — *Not started first* / *Completed
first* — are exact as written and were not re-worded, which is a way of confirming the head of the list and its tail.
Stage 95's `.kcard.s-*` card tints, `statusSlug` and the column dots are untouched: they were always keyed off the card
words, and the projects are what moved. `ProjectService.DEFAULT_STATUS` was already `Not started` and is now the picker's
first option, so the server-side default and the option a manager who touches nothing gets are the same value for the
first time (a stale form posting the retired word still falls through to it — proved on the throwaway, not assumed).
`TASK_STATUSES` and `PROJECT_STATUSES` are now the identical five words; they stay two lists, because a card's columns
and a project's stages are different things that happen to share their spelling.

**Tests.** Suite **637 → 642** / classes **41 → 42** / 0 failures. New `db/ProjectStatusMigrationTest` (3 cases, built
on `ChecklistCompletionMigrationTest`'s *V1–V18 then the real migrate* shape): the renamed row's word is one the picker
offers and the five stages each survive once; a hand-edited status and a legacy `On track` are left alone and **no
`tasks` row changes**, including a card that literally holds `Ongoing`; `updated_at` is byte-identical across the
migration. `ProjectsPageRenderTest` (42 → **44**) gains `the status picker on a new project reads as the progression,
not as the most-used choice` — the five option values in order, `selected` on *Not started*, and the same markup coming
back on a rejected create — and `the board's status sort follows the picker`, which files one project per stage under the
same end date so the status key is the only thing that can move a row, then requires the printed order to be the picker's
order forwards and backwards. The expectation lists are spelled out rather than read from `PROJECT_STATUSES`: a test that
asks the constant whether it matches itself passes whatever the constant says. ~50 status literals were re-pointed across
21 files, including the Forecasts URL assertions (`status=In+progress` — `URLEncoder`, so a space is a `+`) and the
hidden `board_status` value.

**Verified.** The data claim was made over **a copy of the live database** first: `flyway_schema_history` at 18, then the
app's own migration path (Flyway 10.20.1, `classpath:db/migration`, `baselineOnMigrate`, driven from `jshell`) reported
`Migrating schema "main" to version "19 - project status in progress"` → `now at version v19`, `applied=1`. Two rows
(`O-0002`, `S-0013`) went `Ongoing → In progress` with `updated_at` **identical to the pre-migration rows**, the ten
`Not started` rows unchanged, and the tasks tally — 8 On hold, 4 Not started, 4 Blocked, 3 In progress, 2 Completed — the
same on both sides. Then `scratch/stage96-verify.ps1` on a throwaway (8095, empty DB, disposable bootstrap admin,
classes + resources with the Stage 95 jar excluded from `lib/*`): **28 checks, FAILS 0** — the picker's five options in
order with `Not started` selected and no retired word offered, a create posting the retired word landing as *Not started*,
all five words accepted, `chip ontrack` printing *In progress*, `?sort_status=asc|desc` running the progression both
ways, the Forecasts sheet answering `?status=In+progress` and carrying it through its row forms, served `team.js` with no
`'Ongoing':` key, and the Calendar and Team pages printing no retired word anywhere.

**Deploy.** Kotlin, SQL **and** a static file, so `installDist` + restart + **Ctrl+F5**. The Stage 95 instance (PID 19732)
was stopped (polite close refused, force-killed, as always on this box) and `data/pts.db{,-wal,-shm}` copied in place to
`data/pts.pre-stage96-20261029-095604.db*`. The packaged jar was proved before launch: `unzip -l` carries
`V19__project_status_in_progress.sql`, `unzip -p` on its `team.js` shows one keyed `'In progress':` and no keyed
`'Ongoing':`, and `javap -p` on `CalendarKt.class` no longer declares `taskStatusChip`. Launched **detached** → **PID
14592**, java.exe under cmd.exe with nothing of this shell's tree above it; `/` 200, served `team.js` 200 with
keyed-Ongoing=0, and the real log reads `Current version of schema "main": 18` → `19 - project status in progress` →
`now at version v19`. A read-back of the live rows after the boot: `Not started 10 / In progress 2`, `rows still Ongoing =
0`, tasks tally unchanged, schema version 19.

**If this is restated.** Never re-propose a most-used-first picker — the ask was the progression, and the two orders are
one list now. Do not reinstate a separate `PROJECT_STATUS_ORDER` with its own rationale, do not add a
card-word→project-word map back into `Calendar.kt` or a second hue arm back into `team.js`/`Team.kt`, and do not merge
`PROJECT_STATUSES` into `TASK_STATUSES` because they currently agree — they are different workflows that happen to spell
their stages the same way. A `CHECK` constraint on `projects.status` would make a future rename like this one a table
rebuild instead of an `UPDATE`. And if a fifth word is ever genuinely wanted (say *At risk*), it is a picker change, a
sort change, a chip class in two files, a `TASK/PROJECT_STATUSES` decision and a migration — which is exactly the spread
this stage exists to record.

<a id="stage-97"></a>

## Stage 97 — The Bucket picker is the Other-tasks board's only view control (2026-10-29, live, no migration, Kotlin)

**The ask.** A screenshot of the button reading *Show all buckets*, and *"no need this button on other tasks function
since we have dropdown"*. The premise was right and it was worth saying out loud why: Stage 93 put the Assigned page's
**Bucket** picker on this board, and from that day the two controls answer the same question — *which columns am I
looking at?* One of them had to be the exception. The toggle was also the older, awkward half: Stage 57 had already
noted that the two "had to be kept in step", and every write on the page carried a hidden field so the wide view could
survive a save, a step tick, a note, a delete and a refusal.

**The key word read two ways**, so the ask went back as one question before anything was written. Taking the button out
of the page head leaves the empty columns hidden by the board's own rule, and the dropdown as it stood could name only
columns that already held cards on the narrow board. The answer chosen was **"Dropdown becomes the only control
(Recommended)"** — delete the button *and the whole wide view it switched on*, keep the rule that only buckets holding
cards print, and make the dropdown able to reveal an empty one. The other option on the table (keep the wide view
reachable through the picker's *All buckets*) was rejected because it is what this stage already does: *All buckets* is
option `0`, the absence of a filter.

**What went.** `allBucketsToggle()` — the page head's second `opener-form` (`id="bucketsToggle"`, class
`buckets-toggle`, a hidden `allbuckets` and a caption that flipped between *Show all buckets* and *Show fewer buckets*) —
and `otherToggleApplies()`, the predicate that asked whether the control was a choice this reader actually had. With them
went the flag they carried: `showAllBuckets` as a parameter through `otherTasksContent`, `bucketMenuForm`,
`bucketAddTaskForm`, `bucketModal`, `bucketAddBody`, `bucketManageBody`, `bucketDeleteForm` and `otherCard`, the
`showAll` half of `taskFormModal`'s signature, `taskRedirect`'s fourth argument, the hidden `allbuckets` input in nine
forms, and the `?allbuckets=` read in `GET /app/other-tasks`. `respondOtherTasks` now takes one view parameter,
`showBucketId`, and `bucketsBack()` is the single place that turns a column id into a URL.

**What took its place** is one line of behaviour rather than a control. `?show=<id>` already printed the chosen group
even when the choice was empty (`chosen` maps to `listOf(chosen to emptyList())` when nothing is filed there), so naming
a column is now the way to see it; the picker lists **every** bucket, empty included, because it is built from
`TaskService.bucketRows()` and not from the board. Two edges came with that: a bucket that has just been created is
empty and would have vanished as the reader pressed the box, so `POST /app/other-buckets` lands on
`/app/other-tasks?show=<newId>` — the id read back by case-insensitive name lookup, since `TaskService.addBucket`
returns the rule that stopped a name and no id, and names are unique that way — and deleting the column the board is
filtered to drops the filter (`if (showBucketId == bucketId) 0 else showBucketId`), so a redirect never names a dead id.

**What did not move.** The narrow rule (Stage 57): a board prints the buckets that hold cards, and nothing about a
group, its five status columns, its `⋮` menu or its `+ Add task` line changed. Neither did the `other:<id>` back token,
`#otherBoard` as the live region, or the picker's own plumbing — it is a filter form, so `app.js`'s `refresh()` rewrites
the address bar for it and it needed no `data-live-url` to keep the URL honest after the toggle's companion blocks
disappeared. (`[data-live-url]` itself stays in `app.js`: Stage 93 added it for a *block* form, the toggle was its only
user, and with it gone no markup on any page sets the attribute — a mechanism with no caller, not a bug, and deleting it
was not asked.) A stale bookmark or an old tab's `?allbuckets=1` is *unread*, not an error: the route reads `show` alone,
so the retired flag neither widens a board nor comes back in the markup. And no asset moved: `.buckets-toggle` never had
a CSS rule and no script ever selected `#bucketsToggle` — the only files touched outside Kotlin are three comments
(`bucket-modal.js`'s header, `app.css`'s backlog-controls block, `kanban.js`'s live-region sentence) that named the
toggle as the board's other control.

**Tests.** Suite **642 / 42 classes / 0 failures** — the same totals Stage 96 left, because the six cases that pinned
the toggle were replaced by six that pin its absence: `the head offers one control and the picker is the board's only
view`, `the picker reveals a bucket the board is hiding`, `a board with nothing hidden says so and grows no extra
control`, `a bucket that has just been named lands on its own filter`, `no write on this page carries a view state any
more` (a card's popup, a column's menu and a refusal all re-rendered and read for the word `allbuckets`), and
`All buckets is the way back and the address bar follows it`. `TaskBoardRenderTest` retargeted two
(`a step written from the wide backlog board leaves the board wide` → `no popup form carries the backlog's retired wide
view`; the wide-view delete case → `a delete answers on the board the card was opened from`, which now asserts
`taskRedirect("other:7", 0) == "/app/other-tasks?show=7"`), and its `edit`/`editAsOwner` helpers lost the parameter.
`AssignedTasksRenderTest`'s `nothing on this page asks for the other page's view` keeps its `assertFalse` and now records
why the popup it inspects is never handed a wide flag.

**Verified.** `scratch/stage97-verify.ps1` on a throwaway (8095, empty DB, disposable bootstrap admin, classes +
processed resources with the Stage 96 jar excluded from `lib/*`): **60 checks, FAILS 0**. The board prints `+ Add bucket`
and `#otherBucketFilter` and no `bucketsToggle`, `buckets-toggle`, `Show all buckets`, `Show fewer buckets` or
`allbuckets` anywhere; an empty column is off the unfiltered board yet still in the picker's `<option>` list;
`?show=<empty id>` prints its group with five `kcol-empty` panels and selects it in the picker; a create lands on
`?show=<newId>` and a card saved with `back=other:<id>` comes back on that same narrowed board; `?allbuckets=1` answers
200 and changes nothing; a refusal re-opens the card form on the narrowed board with no view flag; deleting the filtered
column 302s to the plain `/app/other-tasks`; and `/app/assigned-tasks`, the served `app.js`, `kanban.js`,
`bucket-modal.js` and `app.css` carry no trace of the removed control. The first run's three FAILs were the harness, not
the app: `Contains('>Idle plant<')` matched the *picker's* option, which is why the assertions were re-scoped to
`OnBoard($html, $name)` — the `bucket-group-title` span — and the discovery became a check of its own. `Group` as a
function name is not usable in PowerShell (built-in alias for `Group-Object`).

**Deploy.** Kotlin only, plus comment-only edits in three static files, so `installDist` + restart + **Ctrl+F5**
(static ships inside `murray-pts-2.0.0.jar`). The Stage 96 instance (**PID 14592**) was stopped (polite close refused,
force-killed, as always on this box) and `data/pts.db{,-wal,-shm}` copied in place to
`data/pts.pre-stage97-20261029-111850.db*`. `jar UP-TO-DATE` says nothing about *which* code got packaged, so the
installed jar was read instead: `unzip -p` over `OtherTasksKt.class`, `ApplicationKt.class` and `TaskBoardKt.class`
finds zero occurrences of each retired string and one/two of `+ Add bucket`, `otherBucketFilter`, `All buckets` and
`addBucketOpener` — the current markup, not the old. Launched **detached** → **PID 23476** (java.exe under cmd.exe,
nothing of this shell's tree above it); `/` 200, schema read-back `Current version of schema "main": 19` with *no
migration necessary* (this stage has none), and the served `app.js`, `bucket-modal.js` and `app.css` are byte-identical
to the jar copies (`54c8436e…`, `a76efb30…`, `a202a1f3…`).

**If this is restated.** Do not put a second view control back in the page head, and do not re-add the wide view as a
hidden state that "only" the picker sets — the ask is the same shape as Stage 33's lane-naming reversal: the user
described the *result* they wanted (three buckets on the board), not the mechanism (a control to press). If an empty
column genuinely needs to be findable in bulk, the change belongs in the picker's option list and the board's filter,
which is one read (`bucketRows()`) rather than two controls kept in step. `addBucket` still returns no id; a future
stage that needs the new column more precisely should make it return the row rather than reach back by name. And the
standing Stage 30 gap is untouched here: `addBucket` reserves no name, so a board can still be given a second column
called *Unsorted* — or, now, one called *All buckets*, which the picker would print twice with only one removable.

<a id="stage-98"></a>

## Stage 98 — A bucket added on Settings is a column on every board of its category (2026-10-29, live, no migration, Kotlin)

**The ask, twice.** *"when add a new bucket on project bucket settings its not adding on project"* — sent first as a
report, and again while the fix was being written. It was an accurate report, and the interesting part is that nothing
was broken: since **Stage 92** the create popup asks nothing about buckets, so `type_default_buckets` had no reader at
all on any screen, and `BucketDefaultsService.add` wrote only that row. The panel had been inert since 2026-10-28 while
its caption still promised the catalogue was what a new project would open with. Stage 61–77 semantics said a catalogue
row is an *offer to the next project*; Stage 92 deleted the only place the offer was shown, which turned every add into
a no-op the reader could see through.

**One question, two answers.** The write had two halves that could move independently, so they went back as one
multiple-choice before any code was written: **add = "Every board of that category (Recommended)"** — the new column goes
onto every existing project of the category that lacks it, and every future project opens with it — and **remove =
"Leave live boards alone (Recommended)"** — taking a row off the catalogue only stops future boards getting the column;
boards that carry it keep it, exactly as removing a lane from one project archives its cards rather than deleting them.
The asymmetry is deliberate and is now stated on the card: a bucket somebody *added* is a column they asked for; a bucket
somebody *took off the list* is one they stopped asking new projects to have.

**`add` writes the row and then the columns.** After the catalogue insert, `BucketDefaultsService.add` walks
`ProjectRepository.findByType(category)` and gives the lane to each project that does not already hold the name,
compared **ignoring case** — a manager may have made a column of their own by that word:

```kotlin
TypeDefaultBucketRepository.insert(category, name)
val boards = ProjectRepository.findByType(category).count { project ->
    ProjectBucketRepository.namesOf(project.id).none { it.equals(name, ignoreCase = true) } &&
        TypeDefaultBucketRepository.seedOne(project.id, category, name) != null
}
return BucketDefaultResult(true, receiptFor(category, name, boards))
```

`seedOne` is the lane's only writer here, so the column arrives through `ProjectBucketRepository.insert` — appended last,
positions 3, 4, 5 with no gap — and carries the **catalogue's spelling** and colour (Stage 80/83), not whatever
capitalisation a board already had. A board that already carried the name keeps its own spelling and gets no second
column; a board of another category is not touched. The receipt counts what actually happened — *"… — 2 Site projects
boards gained the column, and every new project will open with it"*, *"the one … board without it has the column"* for a
single board, and *"every new … project will open with it"* when the catalogue was widened before any project existed —
because a message that only promises *the next project* reads as nothing having happened.

**The receipt reaches the screen, which it did not at first.** `POST /app/settings/buckets/add` used to pass the message
on a refusal only, so `LANE_ADDED` printed "Default lane added." and the count was thrown away. `PasswordStatus.LANE_ADDED`
now has **no text of its own** — the TYPE_ADDED pattern, where the service's sentence is already a whole sentence — and
the route always appends `&lanedetail=`. That is the one visible line a reader standing on Settings has that the add
reached the projects.

**A new project opens with the whole catalogue.** `ProjectService.create` now seeds `seedDefaults(id)` and then
`TypeDefaultBucketRepository.seedExtras(id, type, TypeDefaultBucketRepository.namesOf(type))` — the category's entire
catalogue, in catalogue order — so a board reads the same whether it was made before the add or after it. With that the
create path stopped pretending to accept a bucket list: `ProjectDraft`'s `buckets` / `newBucket` / `bucketForType` and
`ProjectField.BUCKETS` are deleted, and `POST /app/projects` still reads no bucket field (Stage 92), so a hand-built POST
that names one gets the same board as the form.

**What deliberately did not move.** `remove` still stops at the catalogue — that was the second answer, and the KDoc and
the card both say so. A bucket typed into a **project page's own picker** with the `also_type` tick still joins the
catalogue for *new* boards only; it was named for one project, and a tick that quietly adds a column to a dozen other
boards is not what the box says. `BucketDefaultsService.chosenFor` therefore stays — a project page's popup is its one
remaining caller — and nothing anywhere re-files a card: a lane is a `project_buckets` row, not a pointer into the
catalogue. The three fixed rows at the top of the panel and the management-only guard are untouched.

**Copy that had to change with the behaviour.** The panel's caption now says both directions out loud — *"Adding one puts
an empty column on each of this category's projects now, and every project created afterwards opens with it. Taking a row
off stops new projects getting it — the boards that already carry that column keep it."* — and the create popup's bucket
line gained *"…plus this category's extra buckets from Settings"* before *"Add more on the project page after it is
created."*, because a reader who was told the board starts with three chips and then watched a fourth column appear was
being told half the truth. The `TypeDefaultBucketRepository` object KDoc, `seedExtras`, `seedOne`, `add`, `remove`,
`chosenFor` and `BucketDefaultsService`'s class KDoc were rewritten to the new rule; `SettingsRenderTest`'s create-popup
case was renamed (it had said *"offers this category's buckets"* since Stage 92 made that false).

**Tests.** Suite **642 → 641 / 42 classes / 0 failures** (tallied from `build/test-results/test/*.xml`). The eight
Stage-73 create-tick cases in `ProjectServiceTest` were replaced by two: `a new project opens with the three and its
category's whole catalogue, in catalogue order` and `a create writes nothing to the catalogue`. The Stage-61 case that
pinned the old rule — `a bucket added to a category after a project exists does not reach that board` — became
`a bucket added to a category reaches the board of a project that already exists`. `BucketDefaultsServiceTest` grew four:
the fan-out touches every board of the category and no other, appended last at position 3; a board holding the name in
another case gets no second column (and the next board is given the catalogue's spelling); the receipt counts 0, 1 and
2 boards; and a removal leaves the live columns while a board made afterwards does not open with the row.
`SettingsRenderTest` gained `an added lane prints the service receipt, not a status line in front of it`.

**Verified.** `scratch/stage98-verify.ps1` on a throwaway (8095, empty DB, disposable bootstrap admin, compiled classes +
processed resources with the Stage 97 jar excluded from `lib/*`): **35 checks, FAILS 0**. Two Site projects and one Labor
project are created before anything is added; the add answers 302 to `lane=LANE_ADDED` with `lanedetail=` and the Settings
page prints *"2 Site projects boards gained the column"* with no *"Default lane added."* in front of it and the new row in
the list; both existing site boards then print `Commissioning` as their fourth column while the Labor board still has
three; a project created afterwards opens with four; a second add receipt counts **all three** site boards and the first
board's column count goes 4 → 5 with no duplicate; a case-insensitive repeat is refused with *"already adds snag list"*;
removing the row takes it off the catalogue but the board keeps the column and the next project opens with `Snag list`
only; and the log ends with **0 ERROR** lines and *all 19 migrations applied* on the fresh DB. (The one FAIL in the first
run was the harness — it demanded *"No migration necessary"*, which a fresh DB never says.)

**Deploy.** Kotlin only, no migration, so `installDist` + restart. The Stage-97 instance (**PID 23476**) was stopped
(polite close refused, force-killed as usual on this box) and `data/pts.db` copied in place to
`data/pts.pre-stage98-20261029-122223.db` (405,504 bytes; no `-wal`/`-shm` stood beside it — the server had
checkpointed). The installed jar was read rather than trusted: `BucketDefaultsService.class` carries *"boards gained the
column"* and *"board without it has the column"*, `ProjectService.class` carries `seedExtras` and **no** `bucketForType`,
and `PasswordStatus.class` no longer holds *"Default lane added."*. Launched **detached** → **PID 9232** (java.exe under
cmd.exe 19820); `/` 200, `SQLite ready at …\data\pts.db`, `Current version of schema "main": 19` with *no migration
necessary*.

**If this is restated.** Do not turn the add back into a per-board or per-project choice, and do not make it "only future
projects" — that *is* the remove rule, and it is what the user reported as broken. Do not re-add a bucket picker to the
create popup to "explain" where the extra columns come from: the popup's line plus the panel's caption are the
explanation, and a tick-list there would be a second way to choose what a category already decides. Two standing gaps
remain unchanged: `TaskService.addBucket` still returns no id, and a catalogue name may literally be *Unsorted* or one of
the three phases' spellings in another case (the catalogue refuses the exact three, not their twins).

<a id="stage-99"></a>

## Stage 99 — Settings loses its password box, and the first-login gate goes with it (2026-10-29, live, no migration, Kotlin)

**The ask, with a screenshot.** *"delete this part from settings"* — a picture of the **Change Password** card: current,
new and confirm fields and the full-width button. The card was not decoration. Since the launch it was the *only* way an
account whose password the system assigned (a legacy import, a bootstrap admin with no `PTS_ADMIN_PASSWORD`, an employee
added without one) could clear `must_change_password`, and `requireSession()` sent every such account here from every
other route. Deleting the box alone would have stranded those accounts on a page whose only purpose was a form that no
longer existed.

**One question, one answer.** *"What should happen to the forced change?"* went back as one multiple-choice before any
code: **"Drop the forced change too (Recommended)"**. The box and the gate are one feature, so they leave together. A new
account signs in with the password management set for it — or its employee number — and is never blocked. A password is
still set, and reset, from the **Team sheet's** popup, where an administrator types it; a reset still revokes every
session it found.

**What was deleted.**

- `Settings.kt`: the `Change Password` heading, its `card-panel`, the locked-account flash (*"Set a new password to
  unlock the rest of PTS"*) and `passwordForm()` itself — no `currentPassword` / `newPassword` / `confirmPassword`
  field remains on any role's page. `settingsContent` lost its `passwordStatus` parameter.
- `Application.kt`: `POST /app/settings/password` (the form's only endpoint), the `?pw=` flash it redirected with,
  `UserSession.mustChangePassword`, the second arm of `requireSession()` and `MUST_CHANGE_ALLOWLIST`. The guard now
  answers one question — is there a session — and `currentSession()` no longer reads the flag.
- `UserService.changeOwnPassword`, and `PasswordStatus.WRONG_CURRENT` / `MISMATCH`, which only that path produced.
  `AuthService.reissue` went too: the retired route was its one caller and keeping a self-service change signed in was
  its documented purpose.

*(**Stage 123** brings the self-service form back, at the user's request: `PasswordStatus.WRONG_CURRENT` and `MISMATCH` return as field-level refusals, `UserService.changeOwnPassword` returns with the check order `EMPTY → WRONG_CURRENT → MISMATCH → TOO_SHORT`, and `POST /app/settings/password` revokes and reissues the session so the browser that changed the password stays signed in. `AuthService.reissue` itself stays deleted — the route calls `SessionRepository.create` directly. What Stage 99 keeps is the forced change: `must_change_password` is still recorded and still walls nobody off, and no account is stopped at login.)*

**What did not move.** The `must_change_password` column and every write of it stay (`UserService.addUser`,
`AuthService.ensureSuperAdmin`, `JsonImporter`): the flag is still a truthful record of how a password came to be, and
`AuthService.mustChangePassword` / `UserRepository.mustChangePassword` still read it. Nothing enforces it, which is the
whole change. `UserService.setPasswordByAdmin` is untouched, so an administrator's reset still clears the flag and
revokes sessions, and the three flag assertions in `EmployeeServiceTest` keep their meaning. The Team sheet, its add
card, the account card and the three app-settings panels are unchanged; the Settings order is now Team → Your account →
Project types → Project bucket settings → Email notifications, because the box used to sit between the account card and
the panels. A Viewer's Settings page now carries **no form at all** — just the account card.

**Copy that had to change with the behaviour.** The add card said new accounts *"have to change it on first sign-in"*,
and the create popup's manager branch said *"change it on first sign-in, as a Viewer"* — both promises the app no longer
keeps. The add card now reads *"…until you set a password on this sheet the number is the password"*; the admin branch
says *"Leave the password blank and the number is it; you can set a new one here at any time."*; the manager branch says
*"They sign in with their employee number as the password, as a Viewer."* The README's Settings section, its
troubleshooting table and the launch notes were corrected in the same pass.

**Tests.** Suite **641 → 639 / 42 classes / 0 failures** (tallied from `build/test-results/test/*.xml`).
`MustChangePasswordTest` (4 cases: the flag, the self-service clearing path, a wrong current password, an admin reset)
became `DefaultPasswordFlagTest` (2: the flag reads per account and unknown accounts read false; a flagged account signs
in and its session resolves until an administrator sets a password, which clears the record and revokes that session).
In `SettingsRenderTest` the section count drops 6 → 5, the retired form's cases became absences (no heading, no route,
no field names, on both a manager's and a Viewer's page), the form-nesting ceiling became *"never more than one deep"*
because a Viewer's page now has no form at all, and two copy assertions moved with the sentence.

**Verified.** `scratch/stage99-verify.ps1` on a throwaway (8095, empty DB, `PTS_DB_PATH` passed, compiled classes +
processed resources with the app jar excluded from `lib/*`): **24 checks, FAILS 0**. Management's Settings answers 200
with no `Change Password`, no `/app/settings/password` and none of the three field names; `POST /app/settings/password`
answers **404**; an account created with a blank password signs in with its number, gets `/app/assigned-tasks` **200**
instead of a bounce, prints no forced-change banner, and its own Settings is the account card alone; the retired POST is
404 for it too and leaves its password unchanged; an administrator's reset on the Team sheet still answers 302 and that
password is the one that logs in; the log ends with **0 ERROR** lines and all 19 migrations applied on the fresh DB.

**Deploy.** Kotlin only, no migration, so `installDist` + restart. The Stage-98 instance (**PID 9232**) was stopped
(polite close refused again, force-killed) and `data/pts.db` copied in place to
`data/pts.pre-stage99-20261029-131530.db` (405,504 bytes, with its `-wal` and `-shm` — the forced kill left them). The
installed jar was read rather than trusted: `Settings.class` carries the new add-card sentence and holds none of
*Change Password*, `currentPassword` or the unlock line; `ApplicationKt.class` holds neither `/app/settings/password`
nor `MUST_CHANGE_ALLOWLIST` nor `mustChangePassword`. Launched **detached** → **PID 9420** (java.exe under cmd.exe
18932); `/` 200, `Current version of schema "main": 19`, *no migration necessary*.

**If this is restated.** "Put the box back but keep the gate off" is a different feature — self-service password change
— and would need its own decision about where it lives; do not restore `passwordForm()` as a quiet extra on Settings.
"Keep the gate but hide the box" locks every system-assigned account out of the app: the two are one feature. "Stop
writing `must_change_password`" is a data change, not a copy change: leave the column and its writes unless a migration
is intended. A password is set from the **Team sheet** now — `EmployeeService.save` with `password=`, admin-only behind
`maySetRoles`, and `setPasswordByAdmin` behind it; do not re-propose a password field for managers, and do not assume a
reader can change their own password, because no route does that any more.

<a id="stage-100"></a>

## Stage 100 — A new board is exactly its category's Settings list (2026-10-29, live, no migration, Kotlin)

**The ask.** *"so we dont have to take default buckets when we create a new project. As we setting buckets on the
settings function"* — the create popup still named the three opening lanes (Engineering / Procurement / Production)
on every board and the create then added the category's extras after them, while Settings › Project bucket settings
was already where the company maintains that list. Two answers were in force at once: a universal trio nobody chose,
and a catalogue that could only ever add to it.

**Two answers, one rule.** What a new board should start with went back as one multiple-choice before any code:
**"Board follows Settings only"** (no automatic Engineering / Procurement / Production; the board is exactly the rows
Settings lists for its category) and, for what the live catalogues should hold, **"Start from empty / extras only"**
(no migration: a category keeps only the extras it already has — Site projects keeps `Commissioning`, and four
categories have none). Together they mean creation is refused, at the popup's Buckets line, when the category lists no
bucket at all: a board with no column has nowhere to file its first card.

**What changed.**

- `ProjectService.create`: `seedDefaults(id)` + `seedExtras(id, type, namesOf(type))` became one
  `TypeDefaultBucketRepository.seedBoard(id, type, TypeDefaultBucketRepository.namesOf(type))` — the whole catalogue
  in catalogue order, and nothing that is not in it. *(**Stage 104** hands `seedBoard` one more name-set: the rows the
  create form itself typed, appended after the catalogue and carried by `ProjectDraft.newBuckets`. "Nothing that is
  not in it" still holds of the catalogue — Settings' list is still what every project of the category opens with —
  and the third argument is now catalogue-then-typed rather than catalogue alone.)* A type whose `TypeDefaultBucketRepository.of(type)` is empty
  fails the create with `ProjectField.BUCKETS` → *"This category has no buckets yet. Add one in Settings, under
  Project bucket settings."* `ProjectField.BUCKETS` returns after Stage 92 deleted it, but for a refusal rather than
  a picker: `Projects.kt` draws a label-only `Buckets` field with the hint and the error under it — no input, as
  Stage 92 left it. *(**Stage 104** put an Add bucket line back on that popup — as an addition to the list rather than
  a choice from it — so this field has one input again, `ProjectField.BUCKETS` refuses names typed into it, and the
  empty-category sentence now reads *"Add one above, or list them in Settings under Project bucket settings."*)*
- `TypeDefaultBucketRepository`: `seedDefaults` and `seedExtras` are deleted; `insert` now starts an empty category at
  position **0** instead of 3 (`maxOrNull() ?: -1`), because there is no fixed opening set above it any more. The
  object KDoc states the new rule: this table *is* the board a new project gets, its rows are only ever a template,
  and a row's removal still cannot touch a live board (the add/remove asymmetry is unchanged).
- `Settings.kt`: the three fixed phase rows are gone — the rows with *"Always on every board"*, the lock instead of a
  bin, and their own layout. `entries` is now the whole catalogue, every row a normal dot + name + bin, and an empty
  category prints *"$type has no buckets yet — add one below, or name one while creating a project."* (that wording is
  the copy fix that followed Stage 104; the Stage-100 line said *"its projects cannot be created"*). The caption was
  rewritten to say the panel is the board (*"these and nothing else, in this order"*, plus the "at least one bucket
  before a project can be created" sentence), and the dot index now counts from 0 so the catalogue's first row wears
  the same shade as the board's first column.
- `BucketDefaultsService`: `isStandardLane` and the `allowStandard` switch are deleted. Engineering, Procurement and
  Production are ordinary catalogue names now — addable, renamable on a board, removable from a catalogue like any
  other. `nameProblem` keeps only the blank / duplicate / case rules; `chosenFor` stays (a project page's picker is
  still a caller); the add's fan-out and its receipt are untouched.
- `TaskService` / `domain/Task.kt` lost the last phase-name special case; `TaskBoard.kt`'s lane KDocs now say a
  project's lanes come from Settings at creation and that `DEFAULT_PROJECT_BUCKETS` is the legacy trio.
- `Team.kt` — an adjacent fix the new rule forced: the company sheet's **Sub-category** picker used to offer
  `DEFAULT_PROJECT_BUCKETS`. A Stage-100 board need not carry any of them, so it now reads the project's real lanes
  (`laneNamesFor`, via `ProjectBucketRepository.ofProject`).
- `app.css`: `.set-row.phase-row`, `.set-row .phase-fixed` and `.bucket-floor` are deleted (they existed only for the
  fixed rows and the old popup chips). `.bucket-pick` stays as the field's margin.

**What deliberately did not move.** Schema stays **V19** — no migration, and no data rewritten: the live catalogues
and all seven existing boards keep the rows they have. `DEFAULT_PROJECT_BUCKETS` still exists as the legacy trio for
V16's existing boards, `JsonImporter`, the general/Other-board lane fallback and test fixtures — but no create path
reads it any more. `remove` still stops at the catalogue while `add` still fans one column out to every board of the
category, and a bucket typed on a project page joins the catalogue only with the management tick.

**The live consequence, stated plainly.** Four categories have no rows in the live catalogue — **Repair/Maintenance/
other services, Distributor product sales, Engineering, Other** — so creating a project of any of them is refused
until management adds a bucket in Settings. That is the second answer working as chosen, not a fault: each needs at
least one bucket before it can make a project. *(**Stage 104** narrowed the refusal, not the rule: one of those
categories is now creatable when the popup itself names a bucket, and the board it makes holds exactly that name. What
the narrowing did not do is write the catalogue — the next project of the same category is refused again until
Settings lists a row, which is the point of the answer "This project only".)*

**Tests.** Suite **639 / 42 classes / 0 failures** (tallied from `build/test-results/test/*.xml`; the count is
unchanged from Stage 99 — cases were rewritten, none added). A shared fixture, `TestBoards.seedOpeningThree` /
`seedOpeningThreeFor`, writes the legacy trio into a board / catalogue where a case needs the old shape, so the
legacy assertions keep their meaning. New and rewritten: `ProjectServiceTest` — `setUp` seeds the trio per category,
`a category with no buckets refuses the create until Settings lists one`, the catalogue case renamed
`a new project opens with its category's whole Settings list, in that order`, and `a create writes nothing to the
catalogue` now expects the trio *plus* `Commissioning`; `BucketDefaultsServiceTest` — `the three phases are ordinary
bucket names since Stage 100`, and positions `[0, 1]` for a category whose first add is now its first column;
`SettingsRenderTest` — the panel case rewritten with absences (no *"Always on every board"*, no phase rows) and the
new caption, dot colours from `dot-0`, the CSS checks for phase-row/phase-fixed now assert their absence;
`ProjectsPageRenderTest`'s create-popup case rewritten for the new hint and the refusal field;
`TaskServiceTest`'s phase-name case renamed `a phase name put back on a board is an ordinary bucket, not a catalogue
write` (expects `engineering` as typed, with no catalogue row to re-spell it from); `ProjectTypeServiceTest`'s rename
expects the trio plus `Commissioning`.

**Verified.** `scratch/stage100-verify.ps1` on a throwaway (8095, empty DB, `PTS_DB_PATH` passed, compiled classes +
processed resources with the app jar excluded from `lib/*`): **47 checks, FAILS 0**. A category with an empty
catalogue is refused at the popup with the message and writes no project; after one bucket is added in Settings the
same create succeeds and the board holds that one column only — no Engineering, no Procurement, no Production; a
category with two catalogue rows gets both in order; Settings renders no *"Always on every board"* row and no phase
lock; removing a catalogue row leaves the existing board's column; the log ends with **0 ERROR** lines and all 19
migrations applied on the fresh DB.

**Deploy.** Kotlin only, no migration, so `installDist` + restart. The Stage-99 instance (**PID 9420**) was stopped
(polite close refused again, force-killed) and `data/pts.db` copied in place to
`data/pts.pre-stage100-20261029-140137.db` (405,504 bytes, with its `-wal` and `-shm`). The installed jar
(1,830,896 bytes) was read rather than trusted: `SettingsKt.class` carries *"these and nothing else"* and *"has no
buckets yet"* once and neither *"Always on every board"* nor *"Every new board starts with"*; `ProjectService.class`
carries `seedBoard` and the refusal sentence; `ProjectsKt.class` carries the new popup hint; `TeamKt.class` carries
`laneNamesFor`; and no class holds `seedDefaults` or `extrasOf`. Launched **detached** → **PID 4816** (java.exe under
cmd.exe 13668); `/` 200, `Current version of schema "main": 19`, *no migration necessary*.

**If this is restated.** "Put the old trio back for every category" is the opposite answer — it would need a
migration that inserts the trio into every catalogue, and it re-arms the special-casing this stage removed. "Let an
empty category create a board and add columns afterwards" is a different product decision: it means `create` stops
refusing and the project page's add becomes the only way a board gets its first column; do not make that change to
"unblock" the four empty categories — the Settings row is one line and the intent is that a category names its work
before it makes a project. *(**Stage 104** did unblock them from the popup, by management's ask, and it is worth
reading the sentence above as what it became: the category still names what every board opens with, and what is typed
mid-create belongs to one board.)* Do not re-add a bucket **picker** to the create popup: the popup tells the reader
where the list lives, and Settings is the one place it is edited. *(**Stage 104** added a line that adds to the list
shown — never a control that chooses from it. The rows the category lists still print read-only, with no tick, no
cross and no way to leave one out; read that stage before treating this sentence as a prohibition on the box that
names a new column.)* V16's prose about *"every board has three phases"* describes history and existing rows only; new
boards do not follow it.

<a id="stage-101"></a>

## Stage 101 — Newly added checklist steps never tick themselves (2026-10-30, live, no migration, Kotlin)

**Ask.** *"when user click on their own task card on assigned tasks and adds checklist, checklist is ticking automatically stop it"*.

**Change.** `TaskService.addChecklistItem` now calls `ChecklistRepository.insert(taskId, text)` with no Completed-status
special case. `ChecklistRepository.insert` no longer accepts a `ticked` argument and always writes `done = false`.
Every new step is unticked, whether management or the card's assignee adds it and whichever status the card has.
Service, repository and `TaskBoard.stepRowLocked` KDocs now describe that rule. Existing live steps were not rewritten;
schema remains V19 and there is no migration or new JavaScript.

**Unchanged.** Entering Completed still ticks the plan present at that moment and records each row's earlier state.
Leaving Completed restores only rows with `pre_completed IS NOT NULL`; a late step has no remembered state and stays
unticked on reopen. All Completed rows remain locked. A late unticked step is not struck through and can lower card
and project progress; a subsequent finish ticks it normally.

**Tests and verification.** **640 tests / 42 classes / 0 failures**. `TaskServiceTest` proves management and an assignee
add unticked steps to a card created Completed, reopening preserves them, and a subsequent finish/reopen ticks/restores
them. `TaskBoardRenderTest` adds a late-step case: two finished steps plus the owner's addition reads **2 of 3 done**,
with two struck labels, an unticked disabled late box and three locked rows. The isolated HTTP harness
`scratch/stage101-verify.ps1` passes **31 checks** on 8095 with an explicit throwaway `PTS_DB_PATH`: the owner adds from
Assigned tasks, reads 2 of 3 done, reopens without changing the new row, then manually ticks it to 3 of 3. Zero ERROR
lines, schema V19. The initial form-post harness failure was PowerShell comma/plus precedence; interpolating
`"taskId=$card"` fixed the harness, not the route.

**Deployment.** In-place hash-matched backup `data/pts.pre-stage101-20261030-084153.db` (405,504 bytes), its `-wal`
(0 bytes) and `-shm` (32,768 bytes); PTS was already stopped. `installDist` produced the 1,830,803-byte jar. Installed
`TaskService.class` and `ChecklistRepository.class` match the tested compiled bytes; `javap` confirms the add method
calls `insert:(ILjava/lang/String;)I` without reading status. Detached restart: java PID **17788**, cmd parent **16500**,
`/` 200, schema 19, no migration necessary. Metadata-only live/backup counts agree (13 projects, 26 tasks, 23 checklist
rows, 1 note). No commit or push.

<a id="stage-102"></a>

## Stage 102 — Login accepts a username in any letter case, and the screen says so (2026-10-30, live, no migration, Kotlin)

**Ask.** *"when logging in user dont have to use capital letters to log in for username"*.

**What the server already did.** `POST /login` trims and uppercases the employee number before calling
`AuthService.authenticate`, and the service repeats `trim().uppercase()` before `UserRepository.findByEmpNo`; every
account is stored in capitals. Passwords go to `PasswordHasher.verify` exactly as typed. So `emp0009`, `EmP0009`,
`EMP0009` and `"  emp0009  "` were already the same login, and the check was the login screen: it showed
`e.g. EMP0009`, offered a text box a keyboard could auto-capitalize, and its note still promised a forced password
change that Stage 99 removed.

**Change (UI only).** `Views.kt`'s login page: the employee-number placeholder is now `e.g. emp0009`, the input
carries `autocapitalize="none"` and `spellcheck="false"` (no `pattern=` was ever there and none was added), and the note
now reads *"Employee numbers are not case-sensitive. Passwords are case-sensitive. Your starting password, if none was
set, is your employee number in capitals (e.g. EMP0009)."* The retired first-sign-in promise is gone. No route, service,
repository, schema, or JavaScript change; the static assets are untouched.

**Unchanged.** Passwords stay case-sensitive, including a default password typed in capitals. The login throttle,
session cookie and the canonical uppercase employee number inside sessions all behave as before. `must_change_password`
is still recorded and still gates nothing (Stage 99).

**Tests and verification.** **642 tests / 43 classes / 0 failures**. `AuthServiceTest` gains *username letter case and
surrounding spaces do not change login but password case does*: four spellings each succeed, land on `EMP0100`, and
their sessions resolve to `EMP0100`, while `mixedpass42!` is refused. The new `LoginPageRenderTest` reads the form —
lowercase example, `autocapitalize="none"`, `spellcheck="false"`, `autocomplete="username"`, no `pattern=`, the case
sentences present, the forced-change promise absent, and the password box still a password field. The isolated HTTP
harness `scratch/stage102-verify.ps1` boots the packaged build on 8095 against a throwaway `PTS_DB_PATH` and passes
**6 checks**: each of `emp0000`, `EmP0000`, `EMP0000`, `"  emp0000  "` answers 302 to `/app` and opens `/app/projects`
200, a lowercased password answers **401** with *Invalid employee number or password.*, and the database holds four
sessions on `EMP0000`. The harness's first failure was its own: it expected a refused login to answer 200, while
`respondLogin` correctly answers 401 when it shows an error.

**Deployment.** In-place hash-matched backup `data/pts.pre-stage102-20261030-090614.db` (405,504 bytes), `-wal`
(560,352 bytes) and `-shm` (32,768 bytes); the copy passes `pragma integrity_check` and reads the same 13 projects, 26
tasks, 15 users and 23 checklist rows as live, schema 19. `installDist` produced the 1,830,833-byte jar; `ViewsKt.class`
and `AuthService.class` inside it are byte-identical to the compiled classes the suite ran. Detached restart: java PID
**3056**, cmd parent **19892**; `/` 200 serving the lowercase example, `autocapitalize="none"` and the case sentence,
`/app/projects` 302 to `/` without a cookie, schema 19, "No migration necessary". No commit or push.

<a id="stage-103"></a>

## Stage 103 — A status board's card is moved by the drag alone; the prev/next arrows are gone (2026-10-30, live, no migration, Kotlin)

**Ask.** *"assigned tasks card delete those two button since the kanban is working we dont need buttons to move"* — with a
screenshot of one Assigned-tasks card carrying `← In progress` and `Blocked →` underneath it. The ask named the
Assigned card, and the same renderer draws the same pair on the shared **Other tasks** board, so the scope question was
which boards lose them: the answer chosen was **both status boards**, with a project's **lane** board keeping its single
**✓ Done / ↺ Reopen** button, because that button finishes work rather than walking a card along a row of statuses.

**What was removed.** Two places, one control. `TaskBoard.taskCard` drew the prev/next pair from the status branch of
the `if (mine && !canManage)` block — the neighbour statuses read out of `TASK_STATUSES`, each button wearing
`data-status`, `title="Move to <status>"` and the label `← <status>` / `<status> →`. That whole branch is gone, and the
block now stands as `if (mine && !canManage && laneBoard)`, so the only button left on any card is a lane board's Done
or Reopen. `OtherTasks.backlogMoveButtons` — the backlog board's private copy of the same pair, added in Stage 58 — is
deleted outright, together with the call site that ran it under `if (canManage || mine) openBacklogCard(...)`.

**What was kept, and why the page still behaves.** The drag is untouched: `draggable="true"` is still printed for
exactly the accounts `TaskService.updateStatus` accepts (Stage 58's rule on both status boards), `kanban.js` still
answers the drop from `document` (Stage 94) and posts `/app/tasks/status`, and the click that opens the card is
everybody's (Stage 86). No route, service, repository or schema changed and no row was rewritten — this is a control
removed, not a fact: every card keeps the status it had. `.kmove-btn` survives in the markup contract and in
`kanban.js`'s delegated press because the lane board's one button is still a `.kmove-btn`; only its two status-board
producers disappeared.

**Dead style.** `.kcard.kcard-overlay .kmove { position: relative; z-index: 1; }` existed solely so the arrows on a
backlog card were clickable above the whole-card opener behind them. With the arrows gone it matched nothing, so it is
deleted; the sibling `.kcard.kcard-overlay .kfoot` rule (the holder's avatar line) stays, and so do `.kmove`,
`.kmove-btn` and `.kmove-btn.next`, which the lane button still wears. Because the static assets ship inside the jar, a
browser holding the old stylesheet needs a **Ctrl+F5** to see the board without the buttons.

**The consequence worth stating.** On a status board the mouse drag is now the only on-card way to change a status, and
the popup that every reader can open shows **Status** greyed to a non-manager (Stage 49). A keyboard-only or no-JS owner
who previously pressed an arrow now asks management, on a project's lane board they still have ✓ Done / ↺ Reopen, and
everywhere the card's other fields are theirs to edit in their own popup.

**Tests and verification.** **642 tests / 43 classes / 0 failures** — the same tally as Stage 102, because this change
rewrote existing cases rather than adding any. `TaskBoardRenderTest` now asserts the arrows are **absent** for manager,
owner and stranger alike (`assertFalse` on the arrow labels, on `Move to `, and on `kmove` in the card's own block, the
percentage-line case included), while its lane-board cases still require the single Done button and one of them pins
`assertFalse(html.contains("← "), "the status arrows came back")`. `OtherTasksRenderTest`'s Stage 58 case became *a
backlog card's status is moved by the column, and the board prints no button for anybody*, and the Viewer case now reads
the card tag itself for `draggable="true"` plus the absence of `kmove`. The isolated HTTP harness
`scratch/stage103-verify.ps1` boots the just-compiled classes on 8096 against a throwaway `PTS_DB_PATH` and passes
**57 checks / 0 failures** over two boards, two accounts and one backlog column: a plain account's Assigned board and
management's both hold a card of their reader's own and print no `class="kmove"`, no `kmove-btn`, no `Move to ` and
neither arrow label, while both boards still print their five status columns, their card is a click and a drag source;
the project's lane page prints exactly one button row, it is ✓ Done, it names that card and `data-status="Completed"`,
its card wears no drag and management's does (Stage 49, untouched); the owner's and management's copies of the shared
Other-tasks board print the bucket, the card and no buttons at all; and a `POST /app/tasks/status` from the owner still
answers 302 and brings the card back wearing the completed tint. The harness's own first failure was a wrong
assumption, not a regression: it expected a plain account's **lane** card to be draggable, and Stage 49 deliberately
makes lanes management's to arrange. Log clean, schema 19.

**Deployment.** In-place hash-matched backup `data/pts.pre-stage103-20261030-094030.db` (421,888 bytes) with its
`-wal` (86,552) and `-shm` (32,768) sides; the copy passes `pragma integrity_check` (`ok`) and reads the same 13
projects, 26 tasks, 15 users and 23 checklist rows as live. `installDist` produced the 1,828,484-byte jar: all **359**
`mn/murray/pts/*.class` entries are byte-identical to the classes the suite ran, and no shipped class carries the
`Move to ` constant any more. Detached restart: java PID **9776**, cmd parent **11668**; `/` 200, schema 19, "No
migration necessary", the same counts after. `GET /static/css/app.css` 200 with the retired
`.kcard-overlay .kmove` rule gone and `.kcard.kcard-overlay .kfoot` kept, `GET /static/js/kanban.js` 200 still holding
the `.kmove-btn` press and the `/app/tasks/status` post — both read against the live port, since static ships inside the
jar. No commit or push.

<a id="stage-104"></a>

## Stage 104 — The create popup names a bucket again, and a card's code walks to its project (2026-10-30, live, no migration, Kotlin + JS + CSS)

**Ask.** *"add add bucket when user created a new project, also make user able to go to project page by clicking
project code from task card"* — two asks in one line, and the first one read two ways, so it went back as a question
before any code was written. The three answers, in the words chosen:

- **"Add on top of the list"** — what a new board opens with is still the category's Settings rows, in that order and
  whole; anything management types while creating is appended **after** them. The popup never removes a row.
- **"This project only"** — a name typed at creation is that board's column and nothing more. There is no `also_type`
  tick on this form, and no path from the create to `type_default_buckets`.
- **"Same tab, on the card chip"** — the chip a status-board card already wears over its title becomes the link; no new
  control, no new tab.

**The popup's bucket line, in the markup it ships.** `Projects.kt`'s `projectCreateModal` keeps the `div("field full
bucket-pick")` Stage 92 drew and Stage 100 annotated, and fills it with four parts the reader can see working together:

- `div("bucket-preset-rows")#projectBucketPresets` — one `div("bucket-preset-row")` per `PROJECT_TYPES`, marked
  `data-preset-for="<category>"`, the row for the category the popup opens on carrying `.on`. Each row prints that
  category's Settings names as read-only `span("bucket-preset")`, or one `span("bucket-preset none")` saying
  *"— none listed in Settings yet —"*. **Rows, not one list the script rewrites**, because the page a reader with no
  JavaScript gets must still name the columns for the category that form will actually post — the server paints the
  `.on`, and `[data-preset-for]` is what the script switches between when the picker changes.
- `div("bucket-pending")#newProjectBucketList` — what the reader added, one `newBucketChip(name)` per
  `state.values.newBuckets`. A chip is `span("bucket-pending-chip")` carrying `data-bucket-pending`, **its own hidden
  `input(name="new_bucket")`**, the name, and a `button("bucket-pending-drop")` marked `[data-bucket-drop]` with an
  `aria-label` naming the board it leaves. The hidden twin is the chip's value: the text box below is cleared when a
  name becomes a chip, and Stage 73's rule stands — a control that is drawn but disabled posts nothing.
- `div("bucket-add-line")` — `input#newProjectBucketName` (`name="new_bucket"`, `maxlength="$PROJECT_BUCKET_MAX"`,
  autocomplete off, placeholder *e.g. QAQC*) beside `button(ghost-btn btn-sm)` marked `[data-bucket-add]` and reading
  *+ Add bucket*, the same words the project page's dashed box prints.
- `p("bucket-add-said")#newProjectBucketSaid`, then the `p("field-hint")` sentence — *"The board opens with the buckets
  above, then any you add here. One added here is this project's own column and changes nothing in Settings."* — then
  `state.error(ProjectField.BUCKETS)`.

The box's own line is **not** a `.field-err` node even though it wears the same red, and that is deliberate: an
always-printed error line is the first thing a refused create tries to stand the caret in, and this one has nothing to
say until the script gives it a reason.

**What the create writes.** `POST /app/projects` reads the whole set — `newBuckets = fields.getAll("new_bucket")` — so
the chips' hidden twins and the name still sitting in the box arrive alike, in posted order, which is also the answer
`Projects.kt` paints back as chips when the create is refused. `ProjectDraft` gained
`newBuckets: List<String> = emptyList()` (positionally safe, the field's own default) and `ProjectField.BUCKETS` is an
error key with an input behind it again. `ProjectService.create` trims, drops blanks, then takes the **first** name
with a problem, in this order: `BucketDefaultsService.nameProblem` (blank, or over `PROJECT_BUCKET_MAX`), then
*"$name is on this board twice."* against a case-insensitive `LinkedHashSet`, then *"$name is already one of
${type.lowercase()}'s buckets — this board gets it from Settings."* against the category's own rows. A refusal returns
before `ProjectRepository.insert`, so a board is never cut halfway, and the message echoes the spelling the reader
typed rather than the catalogue's. Only when every name passes does the create seed, and it seeds
`seedBoard(id, type, LinkedHashSet(catalogue.map { it.name } + typedBuckets))` — the catalogue first, in catalogue
order, the typed names after it in the order the form posted them, each with no colour slot so the column wears the
shade of its position (Stage 80, Stage 83) exactly like one added later from the board's own box. `seedBoard` and
`insert` already behaved this way for a live board's extra names; the change is that the create is a caller.

**Stage 100's last refusal, narrowed.** An empty catalogue is still a board with nowhere to file work, so it is still
refused — but only when nothing was typed either, and the sentence now offers both ways out: *"This category has no
buckets yet. Add one above, or list them in Settings under Project bucket settings."* The four live categories with no
rows (Repair/Maintenance/other services, Distributor product sales, Engineering, Other) are therefore creatable from
the popup, and remain so for exactly one project each until Settings lists a row: what the popup typed belongs to the
board it made. Nothing on this form can widen a catalogue — that is the column popup's `[data-bucket-widen]` tick
(Stage 77) and Settings' own add (Stage 98), and both are unchanged.

**The card's code is now the way to its project.** `TaskBoard.taskCard`'s status-board branch grew a guard and a tag:

```kotlin
task.projectCode.isNotBlank() && task.projectId > 0 ->
    a(classes = "tcode") {
        href = "/app/projects/${task.projectId}"
        attributes["title"] = "Open project ${task.projectCode}"
        attributes["aria-label"] = "Open project ${task.projectCode} on the Projects board"
        +task.projectCode
    }
```

The `projectId > 0` is not belt-and-braces: the href needs an id to name, and a legacy assigned row whose project is
gone keeps the dead `<span class="tcode">` Stage 88 printed. Same tab, no `target`, no script, and every reader may
follow it — `/app/projects/{id}` has no management gate (Stage 86's read-for-all), so a plain account lands on the
read-only page with no `kcol-menu`. The other three shapes the chip takes are untouched: a legacy code, a backlog
card's `tcode bucket-chip` name and the `T-` fallback all stay `<span>`s; a card's own click still opens its popup, and
one `kcard-top` line is still printed only when `!laneBoard`.

**`a("tcode")` is the trap this stage found.** kotlinx.html's `a` takes its **first positional String as `href`**, not
as `classes` — unlike `span`, `div` and `p`, where the first positional is the class. Writing the chip as
`a("tcode") { href = … }` shipped an anchor with no class at all: the CSS rule and every assertion that looked for
`class="tcode"` failed while the page still *worked*. The file says `a(classes = "tcode")`, with a comment on the spot.
The same pass put `<a>`'s attributes in map order rather than the order written — `LessonsRenderTest` already proved
that — so the chip's tests extract the anchor with `<div class="kcard-top"><a[^>]*>[^<]*</a>` and assert each attribute
separately instead of matching a whole tag.

**The script, and what it proves.** `project-modal.js` grew `showPresetFor` (moves `.on`, returns the row),
`presetNames` (skips `.bucket-preset.none`, so the "nothing listed" line never becomes a name), `pendingNames`,
`say`, and `addBucket`, wired to `[data-bucket-add]`, to `[data-bucket-drop]` — the × removes the chip node, and with
it the hidden value it carries — and to **Enter inside the box**, which is swallowed with `preventDefault` and presses
the button beside it rather than submitting a create whose board the reader meant to finish naming. A category change
re-shows the rows and clears the box's complaint, because the sentence was answering the category that was showing.
The box refuses only the two names the reader would otherwise learn about after the whole create came back — one the
category already offers, one already on this board twice; length, characters and what the board really becomes stay
the service's rules. One old line had to change for the new markup: `show(true)` stands the caret on the first field
with an error, and the selector is now `input:not([type=hidden])` — the Buckets field holds a hidden twin per chip, and
a browser cannot show a caret in one.

**Style.** `app.css` gained `a.tcode` (no underline until hover — a board of permanently underlined chips reads as a
list of links, not a board of cards — plus a `:focus-visible` ring, because the chip is a tab stop), and the Stage-104
block for the popup: `.bucket-preset-rows` / `.bucket-preset-row(.on)` / `.bucket-preset(.none)`,
`.bucket-pending` (`:empty` hides it) / `.bucket-pending-chip` / `.bucket-pending-drop`, `.bucket-add-line`, and
`p.bucket-add-said` (also `:empty { display: none }`). Dark-theme pairs come with the chips. Because the static assets
ship inside the jar, a browser holding the old stylesheet needs **Ctrl+F5**.

**What deliberately did not move.** Schema stays **V19** — `project_buckets`, `type_default_buckets` and every existing
board are untouched, and the create's new names ride the column `seedBoard` already knew how to write. Settings is
unchanged in both directions: its panel is still the only writer of `type_default_buckets`, its add still fans out
(Stage 98), its remove still stops at the catalogue (Stage 100), and the column popup keeps the `[data-bucket-widen]`
tick the create form deliberately lacks. Two sentences in that panel were a shade wide of the truth the moment the
popup could name a first column — *"…its projects cannot be created"* on an empty category and *"before a project of it
can be created"* in the panel caption — and both were **fixed after the deploy**, as a copy-only follow-up with its own
rebuild: the empty line now reads *"$type has no buckets yet — add one below, or name one while creating a project."*
and the caption *"name one here for every project of this category, or name this project's first one while creating
it."* `SettingsRenderTest` asserts the new words and asserts the Stage-100 ones are gone.
`BucketDefaultsService.nameProblem` is shared, not forked: the box, the
board's own add and Settings' add answer with the same length and case rules. `DEFAULT_PROJECT_BUCKETS`, the importer's
legacy trio and every board that already exists are this stage's business for exactly nothing.

**Tests.** Suite **650 / 43 classes / 0 failures** (from `build/test-results/test/*.xml`; Stage 103 left 642, and this
is eight cases net of the two Stage-100 ones it replaces). `ProjectsPageRenderTest` lost Stage 100's *"says where a
new board's buckets come from and
posts no bucket at all"* and gained three, plus a `shownPresetRow` helper that reads which `data-preset-for` wears the
`.on`: the popup shows the first category's rows, prints one row per `PROJECT_TYPES` with exactly one `.on`, names
`Commissioning` for the category that has it and the em-dash line for the eight that have none, prints the box, the
button, the list and the said line, says *changes nothing in Settings*, prints **no** `name="also_type"` and **no**
`value=` for any bucket (a chip is not a control), and gives the field exactly one `<input>`; a refused create echoes
each posted name as a chip carrying `data-bucket-pending`, a hidden `new_bucket` and its own `aria-label`, with the
open box making three `new_bucket` fields in that block; and one refusal prints exactly one `.field-err`, including a
`ProjectField.BUCKETS` refusal. `ProjectServiceTest` gained the five Stage-104 cases — a typed bucket lands **after**
the category's list (positions 0..5, `Commissioning` keeping the colour slot Settings gave it, `QAQC` with none), a
typed bucket is this board's and no other's (the catalogue is unchanged and a sibling project never sees it), a name
the category lists is refused with the message quoted above, a name typed twice on one form is refused while a
two-distinct-name create succeeds, and a 41-character name is refused while a blank is dropped — and rewrote Stage
100's empty-category case for the narrowed sentence. `AssignedTasksRenderTest`'s `chips()` now reads an anchor as well
as a span, and a new case pins the chip as `<a class="tcode" href="/app/projects/$id" …>S-01</a>` while the backlog
chip stays `<span class="tcode bucket-chip">HR</span>`; `TaskBoardRenderTest`'s lane case asserts the same anchor by
attribute.

**Verified.** `scratch/stage104-verify.ps1` boots the just-compiled classes and processed resources on **8097** against
a throwaway `PTS_DB_PATH` (the app jar excluded from `lib/*`, so no `installDist` and the live JVM untouched): **54
checks / 0 failures** — the popup's nine rows and the empty-category line; a Settings add of *Field work* then a create
that types *QAQC* gives one board reading `Field work,QAQC`, while Settings' catalogue reads `Field work` alone and a
sibling project of the same category opens without the typed column; the three refusals (a catalogue name in another
case, a name twice on one form, an empty category with nothing typed) each answer 200 with their sentence and bring the
chips back; a category with no rows becomes creatable by typing `Fabrication` and `Finishing` and its board is exactly
those two in that order; a plain account's card prints the linked chip with its title, following it answers 200 on the
read-only page, that lane board prints no `kcard-top` at all, the backlog card on a status board still wears
`<span class="tcode bucket-chip">Yard</span>`, and the shared Other-tasks board prints no chip line because its columns
are the buckets. Served `app.css` and `project-modal.js` carry every Stage-104 marker; the log ends with **0 ERROR**
lines and schema 19. Three checks failed first, all of them the harness: an em dash typed into the script is read as
ANSI by PowerShell (now built from `[char]0x2014`, Stage 103's lesson), `Contains('QAQC')` matched the Settings add
box's own *placeholder*, and a "no credential in static" check matched `input[type="password"]` in a stylesheet — the
read is now against the run's actual password value. `scratch/stage104-js-harness.cjs` drives the **shipped**
`project-modal.js` against a DOM stub shaped like the rendered popup: **21 checks / 0** — a name becomes a chip that
posts itself, a repeat and a category bucket are refused with their sentences, changing category moves the `.on` row
and re-accepts a name the new category does not list, the × takes the chip and its hidden value together, Enter in the
box is swallowed and adds while Enter elsewhere is left to the form, a blank adds nothing, a refused create focuses the
visible number box rather than a chip's hidden twin, and Escape still closes. Its four first failures were the stub
missing `option.value` (a browser reads it off the attribute; the script asks the property), not the app.

**Deploy.** No migration, no row rewritten, so `installDist` + restart. The live instance (**PID 9776**) was stopped
after 8080 was proved to be `mn.murray.pts.ApplicationKt`, and `data/pts.db` copied in place to
`data/pts.pre-stage104-20261030-105409.db` (421,888 bytes, hash-matched) with its `-wal` (0) and `-shm` (32,768) sides.
The installed jar is 1,840,019 bytes and was read rather than trusted: all **359** `mn/murray/pts/*.class` entries are
byte-identical to the classes the suite ran, and each of three strings only this stage's code writes is in exactly one
of them — `Open project ` in `TaskBoardKt.class`, `bucket-preset-row` in `ProjectsKt.class`, ` is on this board twice.`
in `service/ProjectService.class`; the jar's `app.css` and `project-modal.js` carry the new rules and the new contract.
Launched **detached** through `deploy/run.bat` → java **PID 20004**, cmd parent **7476**; `/` 200,
`Current version of schema "main": 19`, *No migration necessary*, and the live database reads the same 13 projects /
26 tasks / 15 users / 23 checklist rows / 7 catalogue rows / 43 board lanes as before. Evidence in
`scratch/stage104-deploy-evidence.json`. No commit, no push.

**If this is restated.** Never re-propose: an `also_type` tick, or any create-path write to `type_default_buckets` —
*"This project only"* is the answer, and the column popup already owns the widen. A tick-list, checkbox or cross on the
preset rows — the rows are a statement of what the board opens with, and Stage 92's picker is not coming back. Making a
typed bucket also a catalogue row "for convenience" reverses the one decision this stage cannot be read without. Do
not put a status-control on the chip either: it is navigation, and Stage 103's arrows stay retired — a status board's
card moves by the drag, and the lane board's ✓ Done / ↺ Reopen is not this stage's business. On the card, keep
`projectId > 0` in the guard: a code with no project behind it is a label, and an `<a href="/app/projects/0">` is a
dead link that looks alive. If a reader asks for the project page to open in a new tab, that is a `target` and a
rel attribute on one tag — say so rather than adding a second control beside the chip.

<a id="stage-105"></a>

## Stage 105 — A project card lands on the drop, and the write follows it (2026-10-30, live, no migration, JS only)

**Ask.** *"when moving cards on project cards kanban has delay fix it"* — a card dragged between lanes on a project
page did not stay where it was let go: it snapped back to the column it was lifted from, then jumped across after a
pause. No question went back; the defect was in one handler and the answer is the order of two lines.

**The delay, named.** The lane drop handler awaited the write and moved the card in its `.then`:

```js
window.fetch('/app/tasks/bucket', { method: 'POST', body: fd, credentials: 'same-origin' })
  .then(function(){ file(card, col); })
  .catch(function(){ /* the card stays where it was */ });
```

`POST /app/tasks/bucket` (`Application.kt`) calls `TaskService.moveToLane` and then **always** answers
`respondRedirect(referrer)` — a 302 back to the board page. `fetch`'s default `redirect` is `'follow'`, so before the
promise settled the browser fetched that whole project page and rendered it: the move was waiting on a page nobody was
going to see, and the card sat still for however long that took, then jumped. On a board with a full Completed run and
a loaded sidebar that is a visible beat; on a slow link, a long one.

**The fix.** `file(card, col)` runs on the drop, before any request exists — nothing about the move needs the server,
it is three DOM writes — and the POST is sent with `redirect: 'manual'`, so the page behind the 302 is never fetched:

```js
file(card, col);
var fd = new FormData(); fd.append('id', id); fd.append('bucket', bucket);
window.fetch('/app/tasks/bucket', { method: 'POST', body: fd, credentials: 'same-origin', redirect: 'manual' })
  .then(function (r) {
    if (r.type !== 'opaqueredirect' && !r.ok) location.reload();
  })
  .catch(function () { location.reload(); });
```

A `manual` 302 arrives as an opaque redirect — `type: 'opaqueredirect'`, `status: 0`, `ok: false` — and is a success,
not a failure; anything that is neither that nor 2xx, and any rejection, reloads the board. That is the only path that
does, so the board never says one thing while the database says another, and the ordinary case never reloads. The route,
`TaskService.moveToLane`, the status half of the board (whose drop still posts `/app/tasks/status`, waits for the
reply and reloads, because a status change is what the sidebar's open-card count follows) and the `✓ Done` / `↺ Reopen`
press are untouched. Schema stays **V19**.

**The latent defect this uncovered.** `file()` anchored the insert on `col.querySelector('.kdone-btn')` — but that
button sits *inside* the `▸ Completed N` form (Stage 80), and `Node.insertBefore` throws `NotFoundError` when the
reference is not a child of the node it is called on. A drop into a column that had finished cards in it therefore
moved **nothing**, and nobody saw the error because `file()` ran inside a promise's `.then`, whose rejection went to a
`.catch` holding a comment. Since the move happens on the drop now, that throw would have been visible; instead the
anchor is the toggle **form** itself, which is the first node of the finished run and a direct child of the column:

```js
var anchor = col.querySelector('.kcol-done-toggle') || col.querySelector('.kcol-empty');
```

with the `Nothing here.` line as the same fallback as before, and `appendChild` when a column has neither. The header
comment of `kanban.js` was rewritten to say the move precedes the write and why.

**Proof.** No Kotlin changed, so the suite is the record rather than the test of it: **650 / 43 classes / 0 failures /
0 skipped** (`build/test-results/test/*.xml`). The behaviour is pinned by `scratch/stage105-js-harness.cjs`, which
loads the **shipped** `src/main/resources/static/js/kanban.js` against a DOM stub shaped like a lane board (a lane with
one draggable card; a lane with a `form.kcol-done-toggle` above a `div.kcol-done` holding a finished card and a
`p.kcol-empty`; an Unsorted column with `data-lane="0"` and `data-unfiled`; a detached `data-status` column) and a
fetch stub that hands back controllable promises — **21 checks / 0 failures**. It asserts the card is a child of the
new column **before** any reply, above the Completed line and not in the folded block, both counts rewritten, exactly
one POST to `/app/tasks/bucket` carrying `id=101&bucket=12` and `redirect: 'manual'`, no reload yet; that an opaque
redirect is not a failure; that a rejection and a 5xx each reload exactly once; that a drop on the column the card is
already in posts and moves nothing; that a drop on Unsorted posts nothing and leaves the card; and that the status
board still posts `/app/tasks/status`, does not move the card while it waits, and reloads on the reply. The first run
failed one check — *"the emptied Unsorted column is gone"* — and the harness was at fault: its `appendChild` set the
new parent without taking the node out of the old one's `children`, so the emptied column still counted a card. A real
DOM moves the node; the stub now does too. Four earlier failures were the promise queue — a `.then` runs on a
microtask, so each check after `settle()` awaits a `setImmediate` first.

**Deploy.** No migration and no row rewritten, so `installDist` + restart. The live JVM (PID **20512**) was closed and
8080 proved free, then `data/pts.db` was copied in place to `data/pts.pre-stage105-20261030-114719.db` (421,888 bytes)
with its `-wal` (53,592) and `-shm` (32,768) sides — the copy is taken after the stop, so the three files are one
consistent set. The installed jar is **1,840,692 bytes** (Stage 104: 1,840,050) and was read rather than trusted: its
`static/js/kanban.js` carries `querySelector('.kcol-done-toggle')` (1), `file(card, col);` before the POST (1) and
`redirect: 'manual'` on the bucket fetch, and contains **no** `querySelector('.kdone-btn')` and **no**
`then(function(){ file(card, col); })`. Launched **detached** through `deploy/run.bat` → java **PID 8252**, cmd parent
**21672**; `/` 200, `Current version of schema "main": 19`, *No migration necessary*. The served
`/static/js/kanban.js` (13,127 bytes) is the new file, and `POST /app/tasks/bucket` with no session answers
`302 Location: /` — the redirect the old code used to follow and download. Because the script ships inside the jar, a
browser holding the old one needs **Ctrl+F5**; a Kotlin-only change would not. No commit, no push.

**If this is restated.** Never bring back the await-before-move: the write's reply is not a precondition of the card's
column, and a lane drop is the one board action that must not restart the page under the reader's hands. Do not drop
`redirect: 'manual'` — without it the 302 makes the browser download the board page for nothing. Do not reload on
success "to be safe": the counts are already right, and a visible reload is the defect this stage removed. Unsorted is
still not a destination (Stage 48), and a lane a card leaves is still a lane. If a future ask wants the sidebar's
numbers to follow a lane move, compute them locally or ask for the reload explicitly — do not quietly reintroduce it.

<a id="stage-106"></a>

## Stage 106 — The project header loses the Archive and copy-link icons, and the panel goes with its opener (2026-10-30, live, no migration)

**Ask.** *"delete archived tasks button and copy link to this project buttons"* — the two icon controls at the right
of a project page's head were to go. One question went back, because the 🗄 was the **only** way into a panel that held
a real card, and "delete the button" can mean *hide the door* or *retire the room*; the user answered **"Button and
panel"**. So both controls are gone and so is the Archived tasks view, while the archived rows themselves are untouched
in the database.

**What went.** Four surfaces, all of them in one stage:

- `Projects.kt`: the 🗄 opener (a GET form to `?archive=1`) and the 📋 button (its `[data-copy-link]` hook) left the
  `head-actions` row, which now holds the pin form and the delete form and nothing else. `archiveOpen` left
  `projectDetailContent`'s signature, and `archiveOpener`, `projectArchivePanel` and the `archive-modal.js` script tag
  were deleted outright.
- `Application.kt`: the project GET no longer reads `archive=`; `laneRedirect` no longer has an `archive` flag or
  appends `archive=1`, so the bucket routes, restore and delete stop echoing a key nothing reads. A stale bookmark to
  `?archive=1` therefore answers the ordinary page.
- `app.js` / `archive-modal.js`: the delegated copy-to-clipboard handler and the whole backdrop/Escape/`replaceState`
  module are gone; `archive-modal.js` no longer exists to be served.
- `app.css`: `.head-copy` and the Stage 81 Archive block (`.archive-*`, `.at-*`, `.icon-btn.archive-btn.on`) were
  removed. The stylesheet is 1,359 lines and names none of them.

**What stayed, and why.** The `archived` columns (V18), `TaskService.archiveCard`, `restoreCard`, `deleteArchived`,
`archived`, the routes `POST /app/tasks/archive`, `POST /app/projects/{id}/archive/restore` and
`POST /app/projects/{id}/archive/delete` are all still there. Two reasons: removing a bucket still archives its cards
(`TaskService.archiveLane`) and that writes rows every board now leaves out — the data has to keep meaning something —
and the routes are the mechanism's remaining half, kept the way Stage 84 kept `/app/tasks/archive` after its button
went. The right is unchanged: `mayEditLanes` (management or this project's manager). They answer a hand-built POST
and nothing on a page reaches them.

**Copy that followed.** A control's removal makes the sentences written around it untrue, and those were fixed in the
same stage rather than left as documentation drift:

- the column popup: *"its N card(s) leave(s) the board with it"* replaces *"goes to Archive — restore it from the 🗄
  panel"*;
- its confirm: *"Remove "X"? Its N card(s) will leave the board with it."*;
- the bucket-removal answer over the board: *"That bucket is off the board, and its N card(s) left the board with it."*;
- the Settings bucket picker's closing sentence: *"— N card(s) left the board with it"*, not *"went to Archive"* (a
  destination that no longer has a page);
- an archived card's refused save: *"That task is archived, and an archived task cannot be changed."*, not *"Restore
  it before changing it."*.

The KDocs that described "the panel" in the present tense (`TaskService`, `TaskRepository`'s neighbours, `Task.kt`,
`Tables.kt`, `ProjectRepositories.kt`) now say what the flag is and that no page shows it.

**Tests.** `ProjectsPageRenderTest` lost the `archivePanel` helper and the three Stage 81 panel tests, and gained two:
*no page prints the Archive opener, its panel or the copy-link button* (manager and Viewer pages, eight needles each,
plus the pin and delete still present) and *an archived card is on no page while its row waits for the routes that
still answer* (board loses the card and its neighbour stays, the column's count drops to 1, the row keeps
`archivedLane = "QAQC"`, and removing the column archives its live card too — two rows, snapshot intact). The
management-header test flips `data-copy-link` from present to absent. `TaskServiceTest`'s two bucket-picker sentences
now assert *"1 card left the board with it"* and that the word *Archive* is **not** in the answer. The suite is **649
tests / 43 classes / 0 failures / 0 skipped** (`build/test-results/test/*.xml`; 650 before, the three panel tests
replaced by two).

**Proof beyond the render.** `scratch/stage106-verify.ps1` boots a throwaway on **8096** (empty DB, `PTS_DB_PATH`,
`PTS_SEED_DEMO=false`, a random admin password), adds one Settings bucket so Stage 100's create will land, creates a
project over HTTP, and reads the signed-in project page: **16 checks / 0 failures** — no `archive-btn`,
`Show the archived tasks`, `name="archive"`, `id="projectArchive"`, `archive-modal.js`, `Archived tasks`,
`data-copy-link` or `head-copy`; pin and delete still printed; `/static/js/archive-modal.js` **404**; `app.css` matches
none of the retired selectors. The first run failed four checks because the create was refused (Site projects had no
Settings bucket), which also made the eight absence checks pass on a 404 — the fixture, not the app; the check now
asserts the page's 200 before reading it.

**Deploy.** No schema change (schema stays **V19**). The live JVM (PID **8252**) was closed, 8080 proved free, and
`data/pts.db` was copied in place to `data/pts.pre-stage106-20261030-131721.db` (421,888 bytes) with `-wal` (78,312)
and `-shm` (32,768). `installDist` produced **1,829,604 bytes** (Stage 105: 1,840,692); the jar was read back — no
`archive-modal.js` entry, and `app.css`/`app.js`/`kanban.js`/`task-modal.js` inside it contain **0** hits for
`head-copy`, `data-copy-link`, `archive-btn`, `projectArchive` or `archive-modal`. Relaunched **detached** through
`deploy/run.bat` → java **PID 18660**, cmd parent **18544**; `/` 200, `Current version of schema "main": 19`, *No
migration necessary*. Live static: `/static/js/archive-modal.js` 404, `app.css` 110,148 bytes with no retired
selector. Static assets changed, so a browser holding the old CSS/JS needs **Ctrl+F5**. No commit, no push.

**If this is restated.** Do not re-add either icon, do not re-add the panel or `archive-modal.js`, and do not treat
`?archive=1` as a view key — the project GET ignores it now. The archived rows are not dead code: they are what a
removed column leaves behind, and the three routes are the only way they can still be moved or destroyed, by hand.
If the ask comes again as "bring back the archived cards", that is a new view with a new home — say so rather than
restoring this one; and if it comes as "stop archiving on bucket removal", that contradicts Stage 81 and needs its own
decision about where the cards go.

<a id="stage-107"></a>

## Stage 107 — The Assigned tasks projects board gets its own Project picker (2026-10-30, live, no migration)

**Ask.** *"when the user click on the assigned tasks make a dropdown to choose projects just like other tasks has"* —
the upper board on `/app/assigned-tasks` was the reader's own project cards across every project, and the only way
to see one project's work was the Projects page. The lower board already had the shape the user meant: the Stage 88
**Bucket** picker above the reader's Other-task cards. So the ask maps to a twin, not to a new view: a **Project**
picker above the projects board, `?project=<id>` as the state, *All projects* as the absence of a filter.

**What was added.**

- `AssignedTasks.kt` — `assignedTasksContent` takes a fifth argument, `projectFilter: Int = 0`; `ProjectService.all()`
  is read, a stale id answers every project, and `cards` is the reader's own slice filtered by `it.projectId`. The
  picker (`assignedProjectFilter`) is drawn whenever the catalogue holds a project, *above* the projects board and
  *below* the scope line. It is the Bucket picker's shape: a GET form onto this page with
  `class="board-toolbar assigned-project-filter"`, a `label("board-key")` holding the select (the Stage 89 trick that
  keeps a one-field filter card-sized rather than a page-wide toolbar), `name="project"`, `data-autosubmit`, an
  option `0` **All projects**, one option per project labelled `code · name`, and a **Show** submit for a browser
  with no script. It lists **every project**, not only the ones this reader has a card on — the picker is the same
  world the Projects link opens, and a project with nothing on it is a legitimate empty answer, exactly as an empty
  bucket is.
- The scope line now names the filter when one is on: *"Only the cards assigned to you on S-01 · Haul road widening
  are on this board."* An empty tilted board otherwise reads as a reader who has nothing at all.
- The two pickers narrow two different boards on one page, so each carries the other's choice as a hidden field:
  the project form carries `bucket` while the Other-tasks board is narrowed, and the bucket form and the
  `+ Add task` line carry `project` while the projects board is narrowed. Changing one never silently widens the
  other.
- `TaskBoard.kt` — the `back` token grew a second slot. `backForFilters(bucketId, projectId)` writes `assigned`
  (neither), `assigned:<bucket>` (bucket only — what `backForBucket` still writes by delegating), or
  `assigned:<bucket>:<project>` (both, with `0` in either slot meaning *every*). `assignedParts` splits it once;
  `bucketOfBack` reads slot 0 and the new `projectOfBack` reads slot 1, answering `0` for a token without that slot
  rather than guessing. `taskRedirect` reads both and appends `project=<id>` beside `bucket=<id>`, so the opener,
  a save, a step tick, a note, a delete and a refusal all return to the board the reader was reading. `backOf`
  normalises a posted token through `backForFilters`, so `assigned::5` becomes `assigned:0:5` and a hand-built
  token can only name a filter a URL could name anyway.
- `Application.kt` — the GET route reads `project` beside `bucket` and passes both on; the save-refusal branch
  re-renders with `otherBucketId = bucketOfBack(back)` and `projectFilter = projectOfBack(back)`. The route's KDoc
  lost its stale *"no view key"* sentence (Stage 88 had already made it false) and now names both keys.

No new route, no new table, no new script: the picker is the same GET-page mechanism as the bucket one. The
projects board is still the five status columns, and the filter only narrows cards.

**Tests.** `AssignedTasksRenderTest` grew seven: the picker lists every project with its code and opens on *All
projects*; a chosen project narrows the upper board, leaves the lower board whole and names itself in the scope
line; a stale id answers the whole board; each picker carries the other's choice (and the add line carries both);
a project-only filter comes back from the opener and the save as `?project=` / `assigned:0:<id>`; both filters
together come back as `?bucket=..&project=..` / `assigned:<bucket>:<project>`; and the token helpers/`taskRedirect`
round trip, including a half-written `assigned::5` normalising to `assigned:0:5`. The suite is **656 tests / 43
classes / 0 failures / 0 skipped** (`build/test-results/test/*.xml`; 649 before, seven added).

**Proof beyond the render.** `scratch/stage107-verify.ps1` boots a throwaway on **8097** (empty DB, `PTS_DB_PATH`,
`PTS_SEED_DEMO=false`, a random admin password), seeds a Settings bucket, creates two projects and one card on each,
adds an HR backlog column with one Other-task card, and reads the signed-in page: **35 checks / 0 failures** —
the picker and its options, *All projects* selected on a whole board, one project's cards and not the other's, the
Other-task card unaffected, the scope line naming the project, `?project=9999` answering everything, both forms
each carrying the other filter, the opener's `?bucket=..&amp;project=..`, the save form's
`assigned:<bucket>:<project>`, and an edit saved from the doubly-filtered page redirecting back to exactly
`/app/assigned-tasks?bucket=1&project=1`. Four checks failed on the first run only because a BOM-less `.ps1` is read
as ANSI here and the literal `·` in the assertions was mojibake while the page served it correctly — fixed by
building the character from `0x00B7`, per the workspace's Windows trap note.

**Deploy.** No schema change (schema stays **V19**; Flyway: *Current version … 19, no migration necessary*). The live
JVM (PID **18660**) was closed, and `data/pts.db` was copied in place to
`data/pts.pre-stage107-20261030-133534.db` (421,888 bytes) with `-wal` (78,312) and `-shm` (32,768). `installDist`
produced **1,832,849 bytes**; the jar was read back — `AssignedTasksKt` carries `assigned-project-filter` and
`TaskBoardKt` carries `projectOfBack`. Relaunched **detached** through `deploy/run.bat` → java **PID 25592**, cmd
parent; `/` 200 and a signed-out `/app/assigned-tasks` still **302**. No static asset changed, so a plain reload is
enough (pages are `no-store`). No commit, no push.

**If this is restated.** The picker is a view, not a saved setting: `?project=` is the state, and anything not a
live project id is *All projects*. Do not add a second Assigned URL, do not make the project filter also hide
Other-task cards (they have no project), and do not let the two pickers overwrite each other's key — both ride the
one `back` token precisely so a card saved from a doubly-narrowed page returns there.

<a id="stage-108"></a>

## Stage 108 — Revenue is written and shown in tugrik (2026-10-30, live, no migration, Kotlin)

**The ask.** *"Put a tugrik format for revenue writing and showing."* Two halves in one request: every place revenue is
**shown** wears the `₮` sign with comma grouping, and every place revenue is **written** says the unit. The stored value
stays the plain number it always was — the column, the forecast identity and every report arithmetic keeps reading
`Double` — so this is a presentation and parsing change, not a schema one (schema stays **V19**, no migration).

**One formatter, three surfaces.** New `Money.kt` (top-level, package `mn.murray.pts`) holds the only two formatters:

```kotlin
internal fun formatTugrik(value: Double): String = "₮\u00A0" + groupedAmount(value)
internal fun groupedAmount(value: Double): String = DecimalFormat("#,##0").format(value)
```

*(**Superseded in part by Stage 109**: `formatTugrik` is now `formatMoney(currency, value)` — the sign comes from the
project's own `Currency`, tugrik unless the create chose USD or CNY — while `groupedAmount` is unchanged.)*

The space between the sign and the figure is a **non-breaking space** (U+00A0): a narrow Revenue cell can then never
wrap the sign onto a line of its own with its number beneath it, and a cell that copies out carries the whole thing as
one string. `groupedAmount` is deliberately *not* clamped, because the forecast sheet's derived remainder may be
negative and `DecimalFormat` keeps its minus. `formatTugrik` is used by:

- `Projects.kt`'s board cell — `td("c-rev") { span("money") { +formatRevenue(project.revenue) } }` where
  `formatRevenue` answers `—` for a project with no revenue and `formatTugrik` otherwise;
- the Viewer's read-only header cell (`detailCell("Revenue", formatRevenue(project.revenue), "money-ef")`), which
  therefore shows exactly what management's box holds;
- management's header draft, whose default is `revenue = formatTugrik(project.revenue)`;
- the create popup's placeholder, `₮ 0`;
- `Forecasts.kt`'s `f-rev` and `d-rev` cells. There the figure is `formatTugrik(line.project.revenue)` even when the
  project's number is zero — `₮ 0` — because on that sheet revenue is one term of an identity that has to add up.

**What deliberately stayed bare.** Invoiced, the month's forecast, invoiced-in-previous-months, remaining, totals and
the completed sheet's money: `Forecasts.formatMoney` is now just `groupedAmount`. The Forecasts sheet is eleven columns
wide, and a sign on every money cell would be noise repeating what its heading (*Revenue, MNT*, *Invoiced amount, MNT*)
already says. The rule is simple and worth keeping: **revenue carries the sign; the figures derived from it do not.**
*(Stage 109 removed those `, MNT` suffixes — one panel may now mix currencies — but the rule stands.)*

**Writing.** Both revenue inputs stopped being `InputType.number` (a spinner cannot hold a `₮`, and its browser-level
validation would reject the very string the app now accepts):

- create popup — `input(type = InputType.text, name = "revenue")` with `inputmode="numeric"`, `maxLength="32"`,
  `placeholder="₮ 0"`;
- header strip — the same text box with `inputmode="numeric"`, `maxLength="32"`, whose value is the project's
  `formatTugrik` figure. `inputmode` matters: it is what gets a numeric keypad on a phone even though the field is text.

`ProjectService.parseRevenue` now strips the sign and the grouping back off before parsing:
`\u00A0`, `₮`, ordinary spaces and commas are removed; blank answers *no value*; anything left that is not a non-negative
finite number is refused. Both refusals (create and update) now read *"Enter revenue in tugrik, like 1,250,000."* — the
unit is named where the reader is typing, not in a page-top banner.

**Tests.** One new case in `ProjectsPageRenderTest` — *revenue is written and shown in tugrik on every surface that
carries it* — pins the header box at `value="₮ 12,500,000"` with `inputmode="numeric"`, the Viewer's read-only line,
the board's `<span class="money">₮ 12,500,000</span>` beside a no-revenue project's `—`, and the create popup's
`placeholder="₮ 0"`. *(Stage 109 rewrote that case as its currency-aware twin; the tugrik expectations above are kept
inside it.)* Three existing cases moved with it: the same class's money test expects `value="₮ 5,000,000"`
(Invoiced stays `0`); `ForecastsRenderTest`'s completed and running sheets assert `₮ 8,000,000` / `₮ 12,500,000` on the
revenue cells while their derived neighbours (3,000,000 previous, 5,500,000 remaining) stay bare; and the signed case
inside `ProjectServiceTest`'s revenue test saves `₮ 3,500,000` as `3500000.0` and still finds *tugrik* in the refusal.
The suite is **657 tests / 43 classes / 0 failures / 0 skipped** (`build/test-results/test/*.xml`; 656 before, one
added).

**Proof beyond the render.** `scratch/stage108-verify.ps1` boots a throwaway on **8098** (empty DB, `PTS_DB_PATH`,
`PTS_SEED_DEMO=false`, random admin password), adds a Settings bucket, creates one project with a signed revenue and
one with none, and reads the live pages: **25 checks / 0 failures**. It proves the signed create lands, the project
page's box opens on `₮ 2,500,000` and is a text field *with* `inputmode`, the board prints the sign while the empty
project keeps its `—`, the create popup's placeholder says `₮ 0`, the forecast sheet prints revenue in tugrik and keeps
its *Revenue, MNT* heading while other money stays bare, a grouped save (`3,750,000`) and a pasted signed save
(`₮ 4,250,000`) both land and come back in tugrik, and `lots` is refused at the field with the unit named and the words
still in the box. Two runs were needed: PowerShell 5.1 passes native arguments in the ANSI code page, so `₮` and the
NBSP were mangled on the way to `curl --data-urlencode` and the create was refused — fixed by sending pre-encoded
UTF-8 pairs (`revenue=%E2%82%AE%C2%A02%2C500%2C000`). Two checks then failed for real reasons: the header input lacked
`inputmode="numeric"` (added) and a BOM-less `.ps1` read the em dash as ANSI (built from `0x2014`, per the workspace's
Windows trap note).

**Deploy.** No schema change (live DB still at V19; 19 history rows, 14 projects, 26 tasks at deploy time). The live
JVM (PID **25592**) was closed, and `data/pts.db` was copied in place to
`data/pts.pre-stage108-20261030-141318.db` (421,888 bytes) with `-wal` (0) and `-shm` (32,768). `installDist` produced
**1,833,791 bytes**; the jar was read back — `MoneyKt` carries `formatTugrik`, `ProjectsKt` carries `inputmode`, and
`ProjectService` carries the *tugrik* refusal (the string lives in `ProjectService.class`, not its `Kt` facade).
Relaunched **detached** through `deploy/run.bat` → java **PID 14516**, cmd parent; `/` 200 and a signed-out
`/app/projects` still **302**. No static asset changed, so a plain reload is enough (pages are `no-store`). No commit,
no push.

**If this is restated.** "Tugrik format" means *sign + grouped amount* on revenue and the unit named in the box — not a
currency setting, not a second currency, and not the forecast sheet's derived columns. Do not move the formatting into
CSS (`::before`) or the client: the sign has to be in the copyable text and in what the tests can read. Do not let the
parser accept negatives: revenue is money a project is signed for, and a negative is a typo. And keep U+00A0 between the
sign and the figure — an ordinary space is what lets the sign wrap alone in a narrow column.
*(**Superseded in part by Stage 109**, on the currency clause only: the user asked for a picker — "add option USD and
CYN" — so a project may now be counted in MNT, USD or CNY, tugrik defaulted at creation and fixed afterwards. No sign on
derived figures, no CSS/JS formatting, U+00A0 after the sign, no negatives and the board's em dash all still hold.)*

<a id="stage-109"></a>

## Stage 109 — A project's revenue is in its own currency (2026-10-30, live, migration V20, Kotlin + CSS + JS)

**The ask.** *"Add option USD and CYN for choosing but tugrik is default when creating a new project."* The user's
`CYN` is the yuan, whose ISO code is **CNY** — chosen without a question round, per the standing rule that terminology
asks are not worth a turn, and noted in the reply. The shape of the answer follows the ask exactly: a **currency picker
on the create popup**, USD and CNY as the alternatives, **tugrik as the default**, and the project's figures written and
shown in what was chosen.

**What a currency is here.** A fact about the row, not a rule about the app. Stage 108 could hard-code the sign because
there was one money; the choice moves the fact onto the project. It is settled **once, at creation**, for the same
reason the project number is: a contract is signed in a money, and a project whose revenue changes currency after the
fact is a different project wearing the old number. The header strip therefore has no picker and
`ProjectRepository.update` never writes the column. **Nothing converts anything** — the stored amount is the number
that was typed, and the currency only says what it is counted in; there is no rate in the app to divide by, and a rate
that moves would make a saved figure mean something different every morning.

**Schema (V20).** `ALTER TABLE projects ADD COLUMN currency TEXT NOT NULL DEFAULT 'MNT'` — additive, no row rewritten,
every existing project tugrik because that is the unit its figures were always in. No `CHECK`: the three codes are the
form's rule, the way `projects.status` has been free text since V1, and a hand-edited code the app cannot name reads as
tugrik instead of breaking the board. `forecasts` (V8) is untouched; its rows inherit the project's money when the
sheet renders.

**Vocabulary and persistence.** `domain/Project.kt` gains

```kotlin
enum class Currency(val code: String, val symbol: String, val label: String) {
    MNT("MNT", "₮", "Tugrik"), USD("USD", "$", "US dollar"), CNY("CNY", "¥", "Yuan");
    companion object {
        val DEFAULT: Currency = MNT
        fun of(code: String?): Currency? = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
}
```

and `Project.currency: Currency = Currency.MNT`. `Tables.Projects.currency` is `varchar("currency", 8).default("MNT")`;
the repository maps an unknown code with `Currency.of(...) ?: Currency.DEFAULT`, `insert` takes
`currency: Currency = Currency.DEFAULT`, and `update` does not name the column at all. The lowercase tolerance is
deliberate: a hand-made POST saying `usd` names the same money as the form's `USD`, and the refusal is reserved for a
code that names nothing.

**One formatter, now parameterised.** `Money.kt`'s `formatTugrik` became

```kotlin
internal fun formatMoney(currency: Currency, value: Double): String =
    currency.symbol + "\u00A0" + groupedAmount(value)
```

with the non-breaking-space rationale unchanged. Every revenue surface takes the project rather than the number:
`Projects.formatRevenue(project)` answers `—` for nothing and `formatMoney(project.currency, project.revenue)`
otherwise, which the board cell, the Viewer's `detailCell` and the header draft all call; `Forecasts.kt` prints each
row's revenue with that row's project currency. The **Forecasts headings lost their `, MNT`** — *Revenue*, *Invoiced
amount*, *Total invoiced* — because one panel can now hold several monies and the totals under it add figures that do
not share a unit. Invoiced, forecasts, remainders and totals stay bare, as Stage 108 set them: the rule is still
*revenue carries the sign; the figures derived from it do not*.

**The create popup.** Under the Revenue box — which keeps its `inputmode="numeric"` text field — sits
`div("currency-line")` holding `select#projectCurrency name="currency" aria-label="Currency"`, one option per
`Currency.entries` labelled `Tugrik (₮)` / `US dollar ($)` / `Yuan (¥)`, each carrying `data-symbol`, with the chosen
one `selected` (`v.currency.ifBlank { Currency.DEFAULT.code }`, so a refused create reopens on what was picked). The
amount's grey `placeholder` is built from the same choice — `${Currency.of(v.currency)?.symbol ?: Currency.DEFAULT.symbol} 0`
— so a browser with no scripts still says which money it is asking for. `app.css`'s `.currency-line` is six pixels of
margin and a slimmer select (7px/10px padding, 12.5px, muted): the pair must read as one answer, "how much, in what",
not as two unrelated fields. `project-modal.js` gains `syncCurrency()`, which repaints that placeholder from the
selected option's `data-symbol` on `change` of `#projectCurrency` and once on `DOMContentLoaded`; it never rewrites the
amount. **This stage changes CSS and JS, so the live page needs Ctrl+F5** — static ships inside the jar.

**The service.** `ProjectField.CURRENCY` joins the enum under `REVENUE` (it is that box's second half), and a create
reads `Currency.of(draft.currency.ifBlank { Currency.DEFAULT.code })`: blank means tugrik — the header strip's edit and
a minimal test draft post none — while a code that names nothing is refused *"Choose a currency: tugrik, US dollar or
yuan."* under the picker, with nothing written. `ProjectDraft.currency` defaults to `Currency.DEFAULT.code`, so every
pre-Stage-109 construction of the draft still means tugrik. `parseRevenue` now strips every `Currency.symbol` (plus the
NBSP, ordinary spaces and commas) instead of only `₮`, so `$ 2,500,000` and `¥ 4,800,000` paste back as cleanly as a
tugrik figure; both refusals now read *"Enter revenue as a number, like 1,250,000."* — the unit is a choice now, so the
sentence must not name one.

**Tests.** **666 tests / 44 classes / 0 failures / 0 skipped** (657/43 before). New class
`db/ProjectCurrencyMigrationTest` (three, V19 → migrate on temp databases): existing rows become `MNT` with revenue,
invoiced and `updated_at` untouched; a row inserted without a currency lands in `MNT`, pinning the DEFAULT, and the
forecast ledger keeps its amounts; a hand-set `EUR` survives in the row while `ProjectRepository` reads it as
`Currency.MNT`. `ProjectServiceTest` gains three (default/blank/omitted currency is tugrik; `USD` and a lowercase `cny`
store the number typed, with `$`/`¥` input accepted; an unknown code is refused under `CURRENCY` with no row; an edit
leaves the currency alone) and its enum-order case now lists `CURRENCY`; its revenue refusal pins the new sentence.
`ProjectsPageRenderTest` gains two (a USD project on a board beside a tugrik one: header box, Viewer line and board cell
all `$`, and the picker's three labels, its Tugrik default, each option's `data-symbol`, and a refused create reopening
on `CNY` with the `¥ 0` hint) and its field list learned `currency`. `ForecastsRenderTest` gains one (two currencies on
one panel, each row signed its own way, no `, MNT` heading) and three assertions moved: *Revenue* and *Invoiced
amount* lost `, MNT`, *Total invoiced* likewise.

**Proof beyond the render.** `scratch/stage109-js-harness.cjs` drives the shipped `project-modal.js` against the
Stage 104 DOM stub with the picker added: **27 checks / 0 failures** (the 21 bucket checks still pass, and the four
currency checks prove tugrik on load, `$`/`¥`/`₮` as the picker moves, and that a typed amount survives the change).
`scratch/stage109-verify.ps1` boots a throwaway on **8099** (empty DB, `PTS_DB_PATH`, no demo data, random admin) and
runs **46 checks / 0 failures**: projects created with no currency, `USD` and `CNY` all land; the project pages' boxes
wear `₮`/`$`/`¥`; the board prints all three on one page and keeps the em dash for a project with no revenue; the popup
has exactly three options, opens on Tugrik, carries each sign and still says `₮ 0`; the forecast sheet prints both
foreign signs and has lost its `, MNT` headings; an `EUR` create is refused under the picker and writes no row; a
`$ 2,750,000` save from the header keeps the dollar; and Python reading the throwaway DB shows schema **20** with
`('S-01','MNT',2500000.0)`, `('S-02','USD',2750000.0)`, `('S-03','CNY',4800000.0)` and no `S-04`. Two checks failed on
the first run for harness reasons, not app ones: the Viewer cell is only rendered for a non-manager (the manager sees
the edit strip), and the em-dash check had no project without revenue to point at — fixed by dropping the former (the
render test pins it) and creating `S-05`.

**Deploy.** Live DB migrated V19 → **V20** (20 history rows, 14 projects — all `MNT` — and 26 tasks). The live JVM
(PID **14516**) was closed, and `data/pts.db` copied in place to `data/pts.pre-stage109-20261030-144931.db`
(421,888 bytes) with `-wal` (0) and `-shm` (32,768); `installDist` produced **1,842,949 bytes**; relaunched detached
through `deploy/run.bat` → java **PID 12508** with a cmd parent, `/` 200 and signed-out `/app/projects` 302. Jar
read-back: `domain/Currency.class` present, `ProjectsKt` carries `projectCurrency`, `MoneyKt` carries `formatMoney`,
`V20__project_currency.sql` packaged, and the *Choose a currency* refusal sits in `service/ProjectService.class` — the
same correction Stage 108 recorded: strings in a companion/enum live in the outer class, not the `Kt` facade. **Ctrl+F5**
once for `app.css`/`project-modal.js`. No commit, no push.

**If this is restated.** The ask is a picker at **creation** with tugrik defaulted — not an exchange-rate feature, not
an editable currency on an existing project, and not a currency on invoiced (which is read in the project's money but
never asks for one). Do not convert: a project's figure is a number somebody typed. Do not add a fourth money to the
picker without a schema-free path — the enum is the whole vocabulary, and both the refusal and the fallback derive from
it. And keep the sign out of the forecast headings: a panel may mix currencies, and a total over mixed units has no
unit to claim.

<a id="stage-110"></a>

## Stage 110 — Access control gets roles and permissions, and the role decides access (2026-11-02, live, migration V21, Kotlin + CSS + JS)

**The ask.** *"add this on access control with new roles and permissions"*, with `Screenshots/access-control-01.png`
attached. The picture is a **Roles & permissions** panel whose **+ New role** sits top-right of its own heading, over a
table of ROLE / PERMISSIONS / EMPLOYEES with one **Edit** per row: *Owner* — thirteen chips, one employee;
*Administrator* — the same thirteen, one; *Project Manager* — nine (the four it lacks are the people/type/email/roles
chips), six; *Employee* — marked **(default)**, holding only *View teammates' tasks*, fourteen. Two questions were
asked before building, because the ask's key word read two ways and named two screens: the answer came back
**"Yes — roles decide access"** (every employee is filed under a role, the chips are enforced route by route, and the
four built-ins must reproduce today's rights) and **"Role wins; positions become titles"**. The EMPLOYEES column is
drawn as a live count, not the picture's numbers.

**What went away.** `security/Role.kt` — `enum Role(rank) { Viewer, Manager, Admin }` with `atLeast` and the
unknown-falls-to-Viewer `from` — is **deleted**. `Authorization` used to resolve super-admin flag → the account's
position `permissionLevel` → its own `access` word into one of those three ranks; it now answers two questions from one
role row, `canDo(employeeNumber, Permission)` and `canAny(employeeNumber, Collection<Permission>)`, and keeps
`canManage`/`canAdmin` only because a dozen renderers take one boolean for "draw this as an editor". Its KDoc states the
rule the routes follow: **a route guards the chip the control is about, never the convenience flag** — which is what
makes a custom role holding exactly one chip get exactly one power. `Application.kt` carries 95 of those guards
(19 *Edit any task*, 17 *Manage buckets*, 13 *Manage employees*, 11 each *Manage roles* and *Manage project types*,
7 *Manage email settings*, 5 *Edit project details*, 4 *Delete projects*, 2 each *Create projects*,
*Edit forecast amounts* and *Assign tasks*, 1 each *View teammates' tasks* and *View Forecasts tab*).

**Vocabulary.** New `domain/AccessRole.kt` holds `Permission(key, label)` — the thirteen, in the picture's order, with
the picture's words — plus `AccessRole(id, name, permissions, sortOrder, isDefault)` with `may`/`mayAny`, and
`AccessRoles` naming the four seeds and their sets. Key and label are kept apart deliberately: a row and a form say
`assign_tasks` while the sheet says "Assign tasks to others", so a permission's wording can change without a migration.
`Permission.of` trims and ignores case and answers **null** for a key the app does not name; `ofAll` drops those — so a
hand-built `perm=manage_the_moon` grants nothing rather than erroring, exactly as V1 tolerated an unknown
`projects.status`.

**Schema (V21 — `V21__project_roles.sql`).** Two tables and one column: `roles(id, name UNIQUE, sort_order,
is_default)`, `role_permissions(role_id, permission)` with PRIMARY KEY `(role_id, permission)` and
`REFERENCES roles ON DELETE CASCADE`, and `users.role_id INTEGER REFERENCES roles(id) ON DELETE SET NULL` plus
`idx_users_role`. The SET NULL is the point: a role somebody deletes hands its holders back to the default rather than
deleting people along with it. The four seeds land in the picture's order with Employee marked default, and the chips
are inserted by hand from `AccessRoles` (13 / 13 / 9 / 1 — 36 rows). The backfill is a `CASE` in the *old* resolution
order — super admin → the position's `permission_level` (`admin`→2, `manager`→3, else 4) → the `access` word →
Employee — so **every account is filed under the role that grants what its rank granted for the work the rank did**
(see *What the picture narrows* below for the one place the two differ);
`positions.permission_level` stays in the schema,
simply no longer read for authorization, because it is a value the operator can still see and edit on the catalogue.

**Resolution.** `repository/RoleRepository.kt` loads the chips for the whole catalogue in one query (the table's
fourteen rows cost two reads, not fourteen), and `roleOf`/`roleFor` fall back through `defaultRole()`: the `is_default`
row, else the first row, else the in-code Employee — so "no role" is never "no rights" and never "all rights".
`counts()` folds a null `role_id` into the default, because that is the role such an account actually signs in under;
counting it nowhere would print a total smaller than the roster. `legacyAccessWord`/`legacyRoleId` keep the old
`users.access` column written in step with the role (`AuthorizationTest`'s *an account's role is filed by number and the
legacy word is kept in step*), so a hand-read database row agrees with what the app grants.

**The page** (`AccessControl.kt`). `rolesPanel` is the picture: `div("card-panel")#rolesPanel`, a `div("panel-head")`
carrying `h3.set-section` "Roles & permissions" and **+ New role**, `table.grid.roles-grid` with `th` Role /
Permissions / Employees (`role-count-head`) and a fourth empty `th` only for a writer, one `tr` per role
(`span.role-name`, `span.role-default` for "(default)", `div.perm-chips` of `span.chip.perm` in enum order or
`span.task-meta` "No permissions", `td.role-count`, and an **Edit** `button.ghost-btn.btn-sm`), then the caption that
says a position is only the job title beside its name. Both openers are **GET forms onto this page** — `?newrole=1` and
`?roleedit=<id>` — each marked `data-live-target="#roleModal"`, so `app.js` follows the URL and swaps the popup for the
copy the server drew already open: a reader with JavaScript off gets the identical form from a plain click, and
`roleFormOf(query, canRoles)` hands a reader without the chip the ordinary page, because those ids only mean anything
to the account that could save what they open. `roleModal` is `div.modal#roleModal` (inline `display:block` when open)
around `h3` "New role"/"Change role", a `p.modal-scope` that says what a save does to its holders ("Everybody holding
this role picks up the new set at once…"), and the form to `/app/roles/save` naming `#rolesPanel` as its block with
`data-live-extra="#roleModal"`, a hidden `id` only on an edit, the name box `#roleName` with its `p.field-err`, then
`div.perm-picks` of thirteen `label.perm-pick` check boxes in `Permission.entries` order. A refusal answers **the page
at 200**, not a redirect, because a redirect's target is drawn with the popup shut and the reason would have nowhere to
stand; every row keeps its Edit while the popup is up, so a refused reader has a way back into it and a way to any other
role. `static/js/access-control.js` gained the popup's half, following `lane-modal.js`: it does **not** open anything
(the openers live inside the block a save replaces) — it closes on `[data-role-modal-close]`, on the backdrop itself and
on Escape, focuses `#roleName`, and strips only `newrole`/`roleedit` from the address so a reload or Back does not put
a stale form back up. `app.css`'s Stage 110 block styles exactly those classes: `.panel-head` puts the button on the
heading's baseline and the panel's right edge as the picture does, `.perm-chips` wraps with a row gap because thirteen
chips are four lines of text, `.roles-grid` gives the rights column the width and shrinks the two number-shaped ones
(`tabular-nums` on the headcount), and `.perm-picks` is an `auto-fill/minmax(210px)` grid — two columns wide, one on a
phone. **CSS and JS changed, so the live page needs Ctrl+F5.**

**The People sheet keeps its Stage 51 shape and loses its ladder.** Its **Access** column is **Role**: the whole word
"Access" is off the page. A reader holding *Manage roles & permissions* gets a picker per row — `form` to
`/app/access-control/role` with `data-live-target="#role-cell-<empno>"` and `data-live-extra="#rolesPanel"`, so the
picker moves and the EMPLOYEES column moves in one response — and one without it gets `span.chip ${roleChip(role)}`
(Owner/Administrator tinted `atrisk`, Project Manager `planning`, anything else `done`) or `span.assignee none` for a
roster row with no account. The Add-user popup grew a **Role** select, offered only to a chip-holder and opened on the
catalogue's default. Settings' Team sheet reads the same catalogue: its role filter picker, its `roleChip` column and
its popup all come from `RoleService.catalogue()`, `mayEdit = canPeople || canRoles`, and the `/app/settings` door is
the three panel chips *or* the roles chip — while the sidebar link is gated on `Authorization.SETTINGS` alone, which is
the same difference as everywhere else on this page: the route is the right, the link is presentation. The **Manage
positions** card stays *Manage employees*' — all three `POST /app/positions*` routes gate on it — and it lost its
**permission level** field, because a position that grants nothing should not ask what it is worth: a new row is filed
with the lowest legacy word, which is only ever read back as a label. Two sentences moved with the rule they describe:
`PasswordStatus.CANNOT_DELETE_ADMIN` now prints *"Only somebody who manages roles can delete that account."* (it read
"Managers cannot delete Admin accounts.", and `deleteUser`'s bar is now the target's chip rather than its word), and the
delete tooltip's `ONLY_MANAGEMENT_DELETE` *"Only somebody who manages employees can delete an account."*

**Services.** `service/RoleService.kt` is the catalogue's façade and owns the three sentences a name can earn — *"A
role needs a name."*, *"That role name is taken."* (both create and rename, case- and space-insensitively), and *"That
role is no longer on the catalogue."* for an id that has gone, which is refused rather than answered by adding a second
row. It asks nothing about who is calling; the routes hold the chip. `UserService.addUser` took
`canSetRoles` (*Manage roles & permissions*, decides whether the chosen role is honoured) and `canSetProfile`
(*Manage employees*, decides whether a job title and a first password may be set here at all), and `deleteUser` now
refuses to take away an account that itself holds *Manage roles & permissions* unless the caller holds it too.

**Enforcement, page by page.** `/app/forecasts` without *View Forecasts tab* answers the projects board; `/app/settings`
with none of the four management chips answers `/app`; `/app/access-control` with neither people chip answers `/app`
rather than showing a read-only copy of other people's keys; `/app/projects` is everybody's board with the create
opener reserved for *Create projects*. `NAV_PERMISSIONS` in `DashboardCommon.kt` **replaces** Stage 51's
`MANAGEMENT_ONLY_NAV`: forecasts→`VIEW_FORECASTS`, other-tasks→`EDIT_ANY_TASK`/`MANAGE_BUCKETS`,
access-control→`MANAGE_EMPLOYEES`/`MANAGE_ROLES`, settings→`Authorization.SETTINGS`, and `navItemsFor` keeps filtering
`navItems` rather than the list being trimmed — the reason Stage 51 recorded (the notification bell resolves a stored
section back to a path through that whole list) still holds.

**What the picture narrows — said plainly, because it is the one reach that changed.** The `CASE` reproduces yesterday's
*resolution* (which rank this account had), not yesterday's *reach*, because the chip sets come from the picture, and the
picture gives Project Manager nine of thirteen: the four it leaves out are precisely the people-facing ones. Under the
ladder, a **Manager** passed `canManage`, so `GET /app/settings` printed the Team block and the three panels for them,
`GET /app/access-control` opened, `POST /app/access-control` and `/app/access-control/delete` let them add and remove an
account, and the project-type writes were management's. Under the chips all four of those belong to *Manage employees*,
*Manage project types*, *Manage email settings* or *Manage roles & permissions*, a Project Manager holds none of them, and
both pages now answer with a redirect. This is what the user's two answers asked for — "Yes — roles decide access", with
the picture's own sets — and it is the deliberate content of the sheet, not a side effect, but it is worth naming because
**four live accounts sit under Project Manager**. The fix lives on the page rather than in the code: tick the rights onto
the role (every holder changes at the next request, no re-login) or file that person under *Administrator*. Nothing moved
for the other three roles' work: an **Employee** was a Viewer, who never passed `canManage` and so never had a board,
backlog or account write, and the two administrator ranks hold all thirteen, so every route they were on reads the same
answer as before. The one smaller difference on that side is presentation: at this stage `GET /app/settings` was an
ungated page that simply printed a Viewer their own account card, and it now redirects a role holding none of the four
panel chips — a card those accounts could only read, reached by a link their own drawer had already dropped in Stage 51. *(**Stage 123** reopens Settings to every account, at the user's request: the link prints in every drawer again and `GET /app/settings` turns nobody away, but the page a Project Manager or an Employee now reaches is **Your account** alone — the Team sheet and the three panels stay on their chips, which neither of those two roles holds. Access control keeps the redirect this paragraph describes.)*

**Tests.** **687 tests / 47 classes / 0 failures / 0 skipped** (666/44 at Stage 109's close). Three new classes:
`db/RoleMigrationTest` (5 — V20 → V21 on a populated temp database: the seeded catalogue in the picture's order with its
chip counts; *every account already on file keeps the rights it had yesterday*, which is the `CASE` above; a chip key
the app cannot name granting nothing and costing nothing; a role taken off the catalogue handing its holders back to
the default through the SET NULL; and an account added after the migration being filed the same way), `service/RoleServiceTest` (4 — the catalogue's own order and `byId`'s nulls; all three
refusals in the popup's exact words with no row written; a new role appended last, not default, holding exactly its
ticks and an empty set landing with zero; a save rewriting its own row, keeping its place, and moving everybody holding
it through `roleOf`), and `AccessControlRenderTest` (7 — the picture's four rows with chip counts 13/13/9/1, the read-only
reading without `+ New role`/Edit/tint-vs-picker split, `roleFormOf`'s gating of both openers and of a gone id, the
popup's markup on create and on edit, a refusal keeping its sentence under the box with the words still typed, the
People sheet's Role column and per-account picker, and the Add-user popup's role select). `security/SecurityTest.kt` was
rewritten around the chips: `PermissionTest` (2 — thirteen keys and labels in the picture's order, tolerant parsing) and
`AuthorizationTest` (9 — the role decides and neither the old word nor the position does; widening a role moves
everybody on the next request; a null `role_id` falls back; a custom role holds exactly its ticks; the EMPLOYEES count
includes roleless accounts). Settings' sheet and the sidebar follow: `SettingsRenderTest` is 45 with its role-column and
picker cases, `EmployeeServiceTest` 27 (a manager's popup asks nothing about roles and a hand-built post is refused),
`SidebarTaskCountTest` 7 (the pill is the reader's own cards for **every** role).

**Proof beyond the render.** `scratch/stage110-verify.ps1` boots a throwaway on **8099** (empty database,
`PTS_DB_PATH`, `PTS_DATA_FILE` at a path that does not exist, no demo seed, random 18-character bootstrap password) and
runs **101 checks / 0 failures**: the four rows with 13/13/9/1 chips and the counts adding to the accounts on file; both
openers; `?roleedit=3` arriving open on Project Manager with nine ticks and the four people rights unticked; a gone id
drawing the ordinary page; a create landing after the seeded four with exactly its two chips; a duplicate and a blank
name refused in their own sentences with the popup still open and the words still typed; `perm=manage_the_moon`
granting nothing, then that row widened to one chip; an account added under a chosen role and counted; a re-save of a
seeded row narrowing it to one chip and putting all thirteen back; **EMP0021 signed in under a two-chip role** and sent
off `/app/forecasts` (302 → `/app/projects`), off `/app/settings` and off `/app/access-control` (302 → `/app`), kept on
its own board with both links gone from the drawer; its hand-built role write and re-file refused with the Owner
un-moved; the same live session re-filed to Project Manager and reading `/app/forecasts` 200 on the next request with
the drawer gaining Forecasts and still no Access control — no sign-out anywhere in it. Then a **people-only** reader: a
two-chip role, an account filed under it, and the page it gets — the table readable, chips tinted instead of pickers, no
`+ New role`, no Edit, Password and Delete still there, its own role write turned back at the door and the super admin it
aimed at still on the sheet. Python on the throwaway database: schema **21**, `(4,'Employee',4,1,1)`, thirteen chips on
both administrator rows, `('EMP0000','Admin',1)`, `('EMP0021','Manager',3)`, `('EMP0022','Viewer',7)`, zero chip rows
naming a key the app cannot name, zero accounts with a null role. **Four checks failed on the first run, all of them the
harness**: `Get-Page` did not capture response headers, so three redirect assertions were comparing the
*previous POST's* Location — the fix was to give the GET the same `-D`/`Location` read the POST has, which is what made
those route checks mean anything; and a `python -c` one-liner cannot carry quoted SQL literals through PowerShell's
double-quoted argument (the thirteen keys became bare column names), so that query binds `?` placeholders instead.

**Deploy.** Live database migrated V20 → **V21** by the relaunch (log: *Migrating schema "main" to version "21 -
project roles"*, `dbPath = data/pts.db`). The JVM was already stopped and nothing listened on 8080, so `installDist`
ran against a cold tree; `data/pts.db` was copied **in place** first, to
`data/pts.pre-stage110-20261102-100058.db` (430,080 bytes) with `-wal` (655,112) and `-shm` (32,768). The shipped jar is
**1,900,654 bytes**; read-back finds `AccessControlKt`, `service/RoleService`, `domain/Permission`,
`static/js/access-control.js` and `db/migration/V21__project_roles.sql` (22 migration resources). Relaunched detached
through `deploy/run.bat` → **PID 18340** listening on 127.0.0.1:8080, `/` 200, `access-control.js` served at 5,299
bytes, `app.css` carrying the new classes. On the real data: four roles seeded 13/13/9/1 and 36 chip rows; **all 15
accounts carry a role** (Owner 1, Administrator 2, Project Manager 4, Employee 8) and every one of them is explained by
the old ladder's own order — two accounts whose `access` word says *Manager* sit under Administrator and Employee
because their **position level** outranked it, which is precisely what `effectiveRole` did before this stage. Each row is
therefore the role that matches the rank they held; what that role *grants* is the picture's set, which is where the one
real change in reach lives — see *What the picture narrows* above. No commit, no push.

**If this is restated.** The role **decides** — do not restore a rank check beside a chip check, and do not let a
renderer's `canManage` become a route guard. A position is a job title now: `permission_level` is unread decoration and
the honest way to give somebody more power is to widen their role or file them under a wider one. The four built-ins
are seeds, not constants — they can be renamed or widened like any row, and nothing keys off their names except
`roleChip`'s tint and `legacyAccessWord`, both of which read as presentation. `+ New role` belongs top-right of the
**Roles & permissions** heading, as the picture puts it, not at the bottom of the panel. The EMPLOYEES column is a live
count of the accounts on file, so it will not show the picture's 14 here. And a role write refuses at the **name box**
with one sentence: there is no invalid set of chips, so any other complaint would be about the wrong field.

<a id="stage-111"></a>

## Stage 111 — The Assigned page's `+ New task` asks which project, and its columns come from that project (2026-11-02, live, no migration, Kotlin + JS)

**The ask.** *"when manager gets into assigned tasks and click on the new task button that button is working
wrong should be working the same as when manager create a new task from projects project new task"*. It reads two
ways — the *list* on the popup, or the *default* it opens on — so it was asked back as one multiple choice and
answered as two: the symptom is **"Wrong column list"**, and the default is **"Force a choice"** — the Project
picker opens on *— Choose a project —*, and Sub-category stays empty until a project is named.

**What was actually wrong.** `/app/assigned-tasks` names no project, so its create reaches the renderer with
`pageProjectId == 0` and an empty `lanes`. Since **Stage 110** a Project Manager holds *Edit any task*, which makes
that popup management-shaped (`canEditAll`), so it printed the Project picker — and that picker **preselected the
first project in the list** while the Sub-category picker fell back to `DEFAULT_PROJECT_BUCKETS`, the three legacy
names Engineering / Procurement / Production. Those two answers are not one board. A lane travels by **name**
(Stage 48) and is resolved against the project the card actually lands on (Stage 75), so the manager who left the
default project alone and picked the column they were shown got *"Choose which part of this project the card is
on."* at the field, or — if the name did happen to exist on that board — a card filed under a column the project
page they then went to does not have. A project page's create never had this problem: it is drawn from that page's
own `lanes`, so its list and its project are the same board by construction.

**The rule.** On the general board a create **asks which project first, and only then which column of it**. The
column list is not a second question the popup answers out of a shared catalogue; it is the chosen project's own
board, the same rows the project page puts its columns under. Nothing about the rights moved: which fields a caller
may write is still `TaskService.updateAssigned`'s answer, `+ New task` on that page is still management's (and,
since Stage 90, the reader's own backlog line is still a separate popup with no project question in it), and no
board was re-cut — there is **no migration**.

`choosesProject` is the one predicate that decides the shape, and it is deliberately narrow:

```
!state.isEdit && !state.showsBacklog && fixedProject == null &&
    state.mayWriteCard && state.lanesByProject.isNotEmpty()
```

so it is a **create** (never an edit, which knows its row), on the **project** half (never a backlog card, which has
no lanes to name), with **no project pinned by the page** (a project page, an `into=<lane>` line), to a reader who
**may write the card**, and only when the app actually has boards to offer. Every other popup is rendered exactly as
before, which is what makes the negative checks below the interesting half.

**What moved.**

- `ProjectBucketRepository.allByProject()` reads the whole `project_buckets` table once, ordered by project, then
  position, then id, and groups it by project id — one query per page rather than one per project.
- `TaskService.lanesByProjectName()` keys it by **project name**, because that is what the picker's values are and
  what the form posts (Stage 48's naming rule, reused rather than answered twice). A project with no columns stays
  in the map **with an empty list** — "this board has no column yet" is an answer the popup has to be able to give,
  and dropping the row would make the board vanish instead of reporting itself.
- `TaskForm` gained `lanesByProject`, defaulted to empty so every other caller is untouched, and `openingTaskForm`
  fills it only when `pageProjectId == 0`.
- `taskFormModal` computes `choosesProject`, then draws the Project picker with the blank *— Choose a project —*
  option in the selected slot, one `data-lanes` JSON list per project option, `data-lane-fill="true"` on the
  select, and its own hint line. `laneOptions` reads from the chosen project when `choosesProject`, else from
  `laneChoices` as it always did.
- The save route answers a refused create with `lanesByProject` too (`taskId == 0 && !backlogCreate && projectId <= 0`),
  which is the difference between coming back with the right list and coming back with the wrong one again.
- `task-modal.js` grew a second document-level `change` handler that moves the chosen option's list into the
  Sub-category picker **in place** — the same trick the "where it lives" toggle already used for two halves the page
  had already shipped. It keeps the column the reader had picked when the new board has one under that name,
  otherwise lands on that board's first column, re-disables the field when a board has nothing to offer, and
  **fetches nothing and reloads nothing** — a reload would throw away the card being typed.

**The popup's four sentences.** A disabled select is only honest if it says which of the three reasons it is shut,
so the field carries two of them as attributes and the renderer picks the third, and the client picks the same two:
*— Choose a project first —* (nothing named yet), *— This project has no column yet —* (a board with nothing on it),
*— Not a column of this project —* (an answer posted that this board does not have). The last one is the state the
old popup could not express at all — it was the bug's own face. `A new card lands under X.` is **suppressed**
whenever `choosesProject`, because a sentence written by the server would be false the moment the reader changed
project, and no script can walk it back.

**The escaper, which was broken on the first try.** Lane names are free text typed in Settings, so they are escaped
into the JSON attribute rather than trusted. The first version wrote `"\\u0026"` in Kotlin source, and the Kotlin
lexer turned that into a backslash followed by a **raw `&`**, which `kotlinx.html` then printed as `\&amp;` — an
attribute that is not valid JSON, so `JSON.parse` threw and a real board with an ampersand in a column name would
have looked *empty* to the browser. The escapes are therefore built from characters (`append(BACKSLASH)` plus
`code.toString(16)`), never written as an escape sequence in the literal, and the render test asserts on the
**decoded** value: no raw `&`, `u0026` present, brackets intact.

**Tests.** `TaskBoardRenderTest` gained the states this popup can be drawn in, each rendered the way the route
renders it (`newFormOf().copy(lanesByProject = TaskService.lanesByProjectName())`) rather than through a bespoke
helper: the create opens on the choice with exactly one selected option and a greyed Sub-category; every project
option carries **its own** board as JSON, including a board with nothing on it and a name whose characters had to be
escaped; a chosen empty board says so; a refused create comes back with the chosen project still held, that
project's columns listed, the borrowed name absent, the field open, and one `p.field-err` under its own field; a
project page's create keeps the board the page already names; and a backlog create is not asked which project it is
on. Class: **77 tests**. Full suite: **693 tests / 47 classes, 0 failures**.

**The browser half is verified with a stub, not the suite** — the markup was never the part at risk.
`scratch/stage111-lane-fill.cjs` loads the shipped `task-modal.js`, builds two popups out of the same attributes the
renderer prints, and drives **22 checks, 0 failures**: the list moves in place, a shared column name survives the
change, an empty board and a non-JSON list both shut the field with the right sentence, an escaped name parses back
to the column, a popup without the marker is left alone, nothing is fetched, nothing reloads, and the caret lands on
the title rather than in a greyed field. Two of its own bugs came out of the first run, both harness rather than app:
an `<option>`'s `value` was declared as a **bare getter**, so the script's `option.value = name` assignments
vanished in sloppy mode and every list read back as `""`; and `choose()` silently deselected everything when asked
for a label a picker did not offer, which is how a later check was reading a state the harness had never asked for.
Both now fail loudly instead of quietly.

**Over HTTP** — `scratch/stage111-verify.ps1`, an empty `%TEMP%` database on **8096**, `PTS_SEED_DEMO=false` and a
throwaway bootstrap password; two projects of one category (so both boards come from Settings), a **third column
added to one of those two boards only**, and a hand-added **Project Manager** account, because that role is the one
the report is about. **52 checks, 0 failures.** `?new=1` carries `data-lane-fill`, opens on the choice with nothing
pre-picked, and travels `["Design","Build"]` on one project and `["Design","Build","Commissioning"]` on the other —
two lists that differ, which is the whole point. A create with no column and a create with a column borrowed from
the other board are both answered at **200** with the one sentence at the field and **no card written**; the first
comes back open on that project's own columns and lands on its first, the second names the refused answer in its
placeholder. A create aimed at the chosen project's real column redirects and the row is `Stage 111 card | project 1 |
Build`. A **project page's** `?new=1` prints no marker, no *— Choose a project —*, and an open picker that already
knows its column — the unchanged half. With a board emptied out from under it (a direct write; the app refuses the
last removal), the project is **still listed, with `[]`**, and a card aimed at it is refused by a shut field that says
the board has no column. And the harness's own mistake is worth recording: it first joined `tasks.bucket_id`, which is
the **backlog's** column, so a correctly filed card looked unfiled; the project's lane is `tasks.project_bucket_id`.

**Deploy.** No migration; schema stays **21**. The live JVM (PID 18340) needed the force path — `taskkill` reports
"this process can only be terminated forcefully" for it — then `data/pts.db` was copied **in place** to
`data/pts.pre-stage111-20261102-162911.db` (471,040 bytes) with `-wal` (0) and `-shm` (32,768); `installDist` built
**1,905,969 bytes** against a cold tree; relaunched detached through `deploy/run.bat` → **PID 21732** listening on
127.0.0.1:8080 with `/` 200. The served `static/js/task-modal.js` changed from
`5e12c2eaad749ddb983d6d68fdcdd869d85e1471` to `a699b647762995387ade83c08011a50e3a270536` and carries both markers, so
the page needs a **Ctrl+F5**. Read-back on the real data: 15 projects, 46 project buckets, **0 projects with no
bucket** — so the empty-board branch is a defensive state here, not one the live board can show today; 28 tasks, 15
filed on a lane; 14 accounts (count only, no credential read). No commit, no push.

**The shapes this stage left alone, and why each is a different case.** `choosesProject` is a **create** predicate, so
three other popups keep the list they were drawn with, and none of them has this bug:

- The Team sheet's `+ Assign a task` panel asks the same two questions. Its two halves were never at odds:
  `Team.laneNamesFor()` reads the lanes of the project the box names and, while the box is still unanswered, the
  **first** project's — which is the option the browser shows when nothing is marked `selected` — so the list it prints
  is a real board. What it
  lacks is only the live follow: change the project and the columns stay as drawn until the save resolves the name
  against the project that actually gets the card. The Assigned create had a list from *no* project against a
  preselected one, which is what made it a bug rather than a round trip.
- A management **edit** that moves a card with *Where it lives* (Stage 48, narrowed by Stage 87) likewise keeps
  `TaskForm.laneChoices`, which on the general board is `DEFAULT_PROJECT_BUCKETS` with the row's own lane printed after
  it. That is the refile path, where the destination project is chosen in the same breath as the lane and the name is
  still resolved by the service against the board the card lands on; the trio is a list of *names*, so a destination
  that does not carry one refuses it at the field, exactly as Stage 75 left it.
- A project page's create and edit are the untouched half by construction: the page's own `lanes` fill the picker, and
  `lanesByProject` is never passed to it (`pageProjectId == 0` at both call sites).

If this is ever restated as "the Team panel and the refile picker are broken too", the fix is the same
`data-lanes` / `data-lane-fill` pair on those pickers — and for the refile path it needs the marker on the *edit* form,
which is precisely what `!state.isEdit` is there to keep out.

**If this is restated.** Do not put a project back as the popup's **default**: the first project in a list is not an
answer anybody gave, and preselecting it is what made the column list a lie. Do not let the general create fall back
to `DEFAULT_PROJECT_BUCKETS` either — since Stage 100 that list is not what a project's board *is*, and it has not
been read by a create path since. Do not refill the column picker with a fetch or a reload; the reader is mid-card,
and a page that restarts under the caret counts as broken. Do not print `A new card lands under X.` on a popup whose
project is still to be chosen. Do not drop a project from the picker because its board is empty — list it with `[]`
and let the field say which sentence it means. And a create refusal has to carry `lanesByProject` with it, or the
popup comes back listing the wrong board again and the stage is undone in the round trip rather than the render.

<a id="stage-112"></a>

## Stage 112 — The tab carries the brand's M (2026-11-02, live, no migration, Kotlin + asset)

**The imagery is superseded by Stage 113** — the tab now wears the wordmark's ring/crosshair emblem, not its
white M. Everything else below still holds: the named `<link rel="icon">` in both heads, before the stylesheets,
`type="image/png"`, no `/favicon.ico`, opaque full bleed, the single render test, and the Ctrl+F5 caveat. Only
`static/assets/favicon.png`'s pixels (and its size and sha1) changed. **Stage 114 then replaced that tile too**,
with a transparent crop of the logo cut the way murrays.mn cuts its own; the markup, test and Ctrl+F5 rules
still hold.

**The ask.** *"add logo to the favicon"* — the browser tab was the one surface still unbranded. Neither of the
app's two heads named an icon, so a browser fell back to `/favicon.ico`, which this app does not serve (it
answers **404**), and every tab — the signed-out screen and every page behind it — showed the browser's own
generic page glyph instead of the company's mark.

**Why the logo itself would not do.** `static/assets/mms-logo.png` is the shipped wordmark: **3,396 × 1,807**,
MURRAY on its green bar with the corporate ring/crosshair mark at the left. A tab draws an icon at 16–32 px, so
the whole wordmark would be an illegible smear. The obvious next step — crop the left-hand mark — was tried in
four candidate boxes and rejected: the ring overlaps the MURRAY bar, so every crop carried letters of the
wordmark with it, and the previews read **"MU"** rather than a mark. The tab therefore wears the part of the
logo that survives being small: the wordmark's own white **M** on its own green bar, as a square tile.

**How the tile is made.** `src/main/resources/static/assets/favicon.png` — **256 × 256**, 8-bit RGBA,
**3,370 bytes**, every alpha byte **255** (fully opaque, full bleed), sha1
`9ab5cd26367b368c7ece8712350b8b2d8744a7f5`. It is derived from the raster, not hand-drawn:

- the M is cropped at `(925, 770)–(1255, 1025)` — 330 × 255, with a little air — and turned into coverage by
  its lightness: `t = (min(r, g, b) − 45) / (250 − 45)`, clamped, which reads the white letter as 1 and the
  bar's darkest channel (≈40) as 0;
- the field is the bar's own gradient, sampled where no letter sits (rows 745 and 1050): **rgb(67, 98, 18)**
  at x=700 to **rgb(76, 112, 27)** at x=2900, interpolated left to right across the tile;
- the letter is fitted to **66%** of the tile's width — 168 px wide, 129 px tall — and centred, with a 2×2 tap
  reading the raster's anti-aliased edge, then composited white over the gradient.

Two throwaway scripts did it with the **standard library only** — `scratch/favicon.py` decodes the PNG
(unfilters all five scanline types, re-encodes filter-0) and `scratch/favicon-tile.py` composes the tile —
because Pillow is not installed here and the user's Python was not to be changed to get an icon.
`build/favicon/tile-16x8.png` is the 16 px preview magnified 8× without smoothing, and the M still reads at
tab size. No new route, no new dependency, no migration: the file sits beside `mms-logo.png` under
`/static/assets/`, which `staticResources("/static", "static")` has served since Stage 7, and the CSP already
allows it (`img-src 'self' data:`).

**The two heads.** The app draws a page from exactly two heads — `Views.kt`'s `loginPage` and
`DashboardCommon.kt`'s `appPage` — and each now names the icon in the same `unsafe` block that names its
stylesheets, **before** them:

```
<link rel="icon" type="image/png" href="/static/assets/favicon.png">
```

PNG rather than `.ico` because every browser in use takes a PNG icon, and `type="image/png"` says so rather
than making anything guess. Naming the link is also what stops the `/favicon.ico` request: a browser only
falls back to that path when a page names no icon. The **404** at `/favicon.ico` is therefore known and
harmless, and no `.ico` was added — one named asset cannot drift from a second file. (README's cache note
lists `/favicon.ico` among the paths `isServerDrawnPage` says *not* to put `no-store` on; that is about the
header decision, not about a file existing.)

Opaque, not transparent, is deliberate: a transparent PNG is composited against whatever the tab strip is —
dark chrome, a coloured theme, iOS's black home-screen tile — and the white M loses its contrast. A full-bleed
green square with the letter inside keeps the brand's own field at every size, and the favicon does not try to
follow the app's light/dark toggle.

**Tests.** `LoginPageRenderTest` gained one test, *the tab carries the brand icon, named before the
stylesheet*: exactly one `<link rel="icon">`, `type="image/png"`, `href="/static/assets/favicon.png"`, and its
index before the first `rel="stylesheet"`. It pins the markup, not the pixels — the bytes are proved over
HTTP, below, because a test that decoded the PNG would turn a brand asset into a fixture. Full suite: **694
tests / 47 classes, 0 failures** (693/47 at Stage 111's close).

**Over HTTP.** `scratch/stage112-verify.ps1` boots a throwaway on **8097** with an empty `%TEMP%` database,
`PTS_SEED_DEMO=false`, `PTS_DATA_FILE` at a path that does not exist, and a random 18-character bootstrap
password: EMP0000 signs in **302**; `/app` answers **302 → `/app/projects`**; the heads of `/app/projects`
and `/app/assigned-tasks` both carry the exact tag with the icon before the stylesheet; the icon is
**`200 image/png 3370`** and its sha1 matches the committed file; and `/favicon.ico` is noted as **404**.
**8 checks, 0 failures.** One harness assertion was wrong on the first run: it expected `/app` to answer
**200**, but `/app` is the shell's front door and redirects to `/app/projects` — a redirect check has to read
the `Location` of its own response and then read the heads on the pages behind it, which is what the re-run
does.

**Deploy.** No migration; schema stays **21**. The live JVM (PID 21732) was stopped and `data/pts.db` copied
**in place** first, to `data/pts.pre-stage112-20261102-165737.db` (**471,040** bytes) with `-wal`
(**288,432**) and `-shm` (**32,768**); `installDist` built the **1,908,668-byte** jar against a cold tree;
the app was relaunched detached through `deploy/run.bat` → **PID 7332** listening on 127.0.0.1:8080, `/`
**200**. Live `/` carries the tag before the stylesheet, and `/static/assets/favicon.png` answers
**`200 image/png 3370`** with the sha1 above on both sides. Read-back on the real data (metadata only):
schema 21, 15 projects, 45 project buckets, 30 tasks, 14 accounts. No commit, no push.

**Seeing it.** The page's HTML changed, but the **tab icon is one of the stickiest caches a browser keeps** —
a normal reload can keep showing the old blank tab. **Ctrl+F5** (or a brand-new tab/window) is what asks for
it again. Whether the tab now looks right is the user's check, not mine: this is the one surface I cannot read
back from the server. Nothing else about the logo moved — `mms-logo.png` is still the in-page wordmark.

**If this is restated.** Do not point `rel="icon"` at the wide `mms-logo.png`; a wordmark at 16 px is a blur,
and the tab wants one letter. Do not add transparency, a white margin or a rounded-corner mask — the tile is
opaque and full bleed on purpose, so no tab strip or home-screen compositor chooses its background for it. Do
not add a `/favicon.ico` file beside the named PNG link; two assets are two things to keep in step, and a
browser that sees the link never asks for the fallback. Do not make the render test decode pixels or pin the
PNG's sha1 — the markup is what a later stage can break, and the bytes are checked over HTTP. And if the
wordmark or the bar's colours ever change, regenerate the tile from the new raster with
`scratch/favicon-tile.py` rather than editing the PNG by hand, so the icon stays the logo's own letter.

<a id="stage-113"></a>

## Stage 113 — The tab wears the wordmark's emblem (2026-11-02, live, no migration, asset only)

**The imagery is superseded by Stage 114** — the tab now shows the logo cropped the way murrays.mn crops its
own favicon (the emblem, the start of the MURRAY bar with "MUR", a sliver of MINING SERVICES) on a transparent
field, not the white-on-green emblem tile. Everything else below still holds: the named link in both heads, the
single render test, the CSP, the `/favicon.ico` 404 and the Ctrl+F5 caveat.

**The ask.** *"add it on favicon"* — sent with the **wordmark itself** attached (`image.png`, 3396 × 1807), and
byte-identical to the shipped `src/main/resources/static/assets/mms-logo.png` (sha1
`e4fbe959810519266f8ad4e44c0c7aefbfbf64d4`). Stage 112 had chosen the wordmark's white **M** because the whole
3396-px wordmark is an illegible smear at 16 px and a crop of the left-hand ring still carried the letters
**"MU"**. The user looked at that tab and asked for the logo — meaning its **ring/crosshair emblem**, the part of
the mark that is not a letter. So this stage replaces the tile's **pixels only**: the named `<link rel="icon">`
in both heads, `type="image/png"`, its position before the stylesheets, the single `LoginPageRenderTest` case,
the CSP, the routes and schema 21 are all untouched. Stage 112's section says the same in its heading.

**What the emblem is, measured.** It is not a free-standing glyph in the raster; it is the mark at the left of
the green MURRAY bar, drawn in thin strokes and **centred on the crosshair at (401, 898)**:

- an **outer arc**, mid-radius **651.5**, stroke **26** (r 639–665), and an **inner arc**, mid-radius **404**,
  stroke **25** (r 391.5–416.5); both are **C-shapes open to the left**, running from ~104° to ~256°;
- a vertical axis **9 px** wide, y **163–1632**, and a horizontal axis y **894–902**, x **188–616**, which stops
  before the bar and is symmetric about x = 402;
- two short diagonals (~10 px) through the crosshair, small crosshair circles on the vertical axis at ±90° and
  ~±36° (~170 px out), and two dots at ~±60° (~165 px out).

The bar covers only the **right cap** of each arc — outer ±16°, inner ±26° — so masking the bar would leave two
C-shapes visibly cut at 4 o'clock. Both caps are therefore **redrawn**: `scratch/favicon-emblem.py` copies the
raster, zeroes the bar's rows from x 651, then for each pixel over the bar region tests 2 × 2 sub-samples against
each annulus and paints the measured stroke colours — **rgb(99, 153, 50)** for the outer arc, **rgb(99, 154, 50)**
for the inner — with alpha equal to coverage. The resulting emblem's content box is **(138,163)–(1065,1632)**,
centre **(601.5, 897.5)**; the mark now reads as one continuous device instead of a C with a green wedge in it.

**How the tile is made.** `scratch/favicon-emblem-final.py` (standard library only, borrowing `favicon.py`'s
decoder and encoder — Pillow still is not installed) takes one **1400 × 1400** square centred on that content
box, which clips the vertical axis's tips but nothing of the rings, dots or diagonals. It box-averages the square
to **256 × 256** as ink coverage (alpha > 24), applies a **one-pixel max filter** (`sum > 0.5` in a 3 × 3 window,
via a summed-area table) so the 26-px strokes survive a 16-px tab, and composites **white over the bar's own
gradient** — **rgb(67, 98, 18)** at x = 700 to **rgb(76, 112, 27)** at x = 2900, interpolated left to right, the
same two samples Stage 112 used. Every pixel's alpha is **255**: the field is opaque and full bleed so no tab
strip, dark theme or home-screen compositor gets to choose a background behind a white line drawing.

The installed file is `src/main/resources/static/assets/favicon.png` — **256 × 256**, 8-bit RGBA, **4,214
bytes**, sha1 **5feeeb10901b9ed5bdfc5d4f8600f4b7d0ccde9a**. `build/favicon/emblem-favicon-16x8.png` is the 16-px
face magnified 8 × without smoothing; the ring, crosshair and dots all read at tab size, though a hairline
emblem is lighter at 16 px than a solid letter was — that is the ask's own trade, and the white-on-green field
keeps it from disappearing.

**Tests.** No Kotlin and no test changed; the asset is the only product change. Full suite after installing the
PNG: **47 classes, 694 tests, 0 failures, 0 skipped** (BUILD SUCCESSFUL in 4m56s) — the same counts as Stage
112's close, because the render case pins the markup and not the pixels.

**Over HTTP.** `scratch/stage113-verify.ps1` boots a throwaway from the new distribution on **8097** with an empty
`%TEMP%` database, `PTS_SEED_DEMO=false`, `PTS_DATA_FILE` at a path that does not exist and a random 18-character
bootstrap password: EMP0000 signs in **302**; `/app` answers **302 → `/app/projects`**; both `/app/projects` and
`/app/assigned-tasks` carry the exact tag before the first stylesheet; the icon is **`200 image/png 4214`** and
its sha1 is the committed one; `/favicon.ico` is still **404** and still harmless. **8 checks, 0 failures.**

**Deploy.** No migration; schema stays **21**. The live JVM (PID 7332) was stopped gracefully, then force-killed
because `taskkill` alone refused; `data/pts.db` was copied **in place** first to
`data/pts.pre-stage113-20261102-172704.db` (**471,040** bytes) with `-wal` (**288,432**) and `-shm`
(**32,768**). `installDist` rebuilt against the cold tree — **BUILD SUCCESSFUL in 3s**, jar
**1,909,885 bytes** (Stage 112's was 1,908,668; the asset's 844 extra bytes are most of it). The app was
relaunched detached through `deploy/run.bat` → **PID 19232**, parent `cmd.exe`, listening on
127.0.0.1:8080, `/` **200**. Live `/` names the icon **once**, before the first stylesheet, and
`/static/assets/favicon.png` answers **`200 image/png 4214`** with sha1
`5feeeb10901b9ed5bdfc5d4f8600f4b7d0ccde9a` on both sides. Read-back on the real data (metadata only):
schema **21**, 15 projects, 30 tasks.

One harness assertion was wrong, not the build: the deploy script first grepped `ViewsKt.class` and
`DashboardCommonKt.class` for the icon path and printed **0**. Kotlin compiles those string constants into the
head lambdas, so the strings live in `ViewsKt$loginPage$1$3.class` and `DashboardCommonKt$appPage$1$3.class`; a
follow-up grep found **1** in each, and the jar lists `static/assets/favicon.png` at **4,214** bytes. Check the
constant where the compiler puts it, not where the source function is named.

**Seeing it.** This is the one surface no server check can see. A tab icon is among the stickiest caches a
browser keeps, and the old M can survive a normal reload: **Ctrl+F5**, or a brand-new tab/window, is what asks
for the new tile again. Whether the tab now shows the emblem is the user's check.

**If this is restated.** Do not point `rel="icon"` at the wide `mms-logo.png` — the wordmark cannot be read at
16 px, and the tab wants the emblem alone. Do not crop the emblem without masking the bar and rebuilding the
arcs' right caps: a crop leaves **"MU"** in the tile, and a masked bar without caps leaves two cut C-shapes. Do
not hand-edit the PNG, and do not rebuild it from a screenshot or a resized logo — regenerate with
`scratch/favicon-emblem-final.py` from `mms-logo.png`, so the radii, stroke colours and gradient stay the
raster's own. Do not add transparency, a margin or rounded corners. Do not resurrect Stage 112's M as a
fallback or keep two icons; one named PNG is the whole rule. And do not make the render test decode pixels or
pin the sha1 — the markup is what a later change can break, and the bytes are checked over HTTP.

<a id="stage-114"></a>

## Stage 114 — The tab wears the logo the way murrays.mn's tab does (2026-11-03, live, no migration, asset only)

**The ask.** *"take this image make it look like https://www.murrays.mn/ favicon"* — sent with the wordmark
attached again (`image.png`, byte-identical to `src/main/resources/static/assets/mms-logo.png`, sha1
`e4fbe959810519266f8ad4e44c0c7aefbfbf64d4`). Stages 112 and 113 had both *redrawn* the mark into an opaque
green tile; the user pointed at the company site's own tab and asked for that look instead. It is a different
idea: the site's favicon is **not a redrawn tile at all**, it is the logo itself, cropped.

**What the site actually serves.** `https://www.murrays.mn/` declares
`<link rel="shortcut icon" href="templates/images/icon.png">` (and the same file as its `apple-touch-icon`);
`/favicon.ico` there answers **404**. That icon is a **50 × 50**, 8-bit RGBA PNG, **5,741 bytes**, sha1
`373b40a3cd2790f59bdcc02b03bb3f622f54c824`, with a **transparent** field. Measured against the site's own
`logo.png` (**500 × 298**), it is exactly the logo's **left square** — x 0–298, y 0–298, side = the logo's
height. That single rule produces everything visible in it: the emblem at the left, the green bar entering at
**35%** of the width (bar rows 113–183 of 298), the white **M** at 44%, the "MUR" run cut by the right edge, and
the top of **MINING SERVICES** at 65% — the "MININ" that shows in their tab. No element is redrawn; the crop
does it.

**The same rule on our raster.** Our logo is **3396 × 1807** and wider than theirs (1.879 against 1.678), so a
left square of side = height reaches x 1807 and shows less of the wordmark. `scratch/favicon-site.py` renders
that literal crop and seven neighbours at 1600–1807 px sides; the shipped one is **site-g**, the square
**(80, 120)–(1680, 1720)** — side **1600**. It puts the emblem at **92%** of the frame's height (the mark spans
y 163–1632), the bar's left edge at **36%**, "MUR" with the **R** cut by the right edge, and the first **M** of
MINING at the bottom right — the closest square analogue of the site's tile. The brand raster is otherwise
untouched: no masking, no cap rebuilding, no ink growth.

The resample is a **premultiplied-alpha box average** — each output pixel's colour is weighted by source alpha
and its alpha is the mean of the window — so a transparent edge does not bleed white halos into the green. The
script also writes the **50-px face** (the site's own size) and a 16-px preview.

The installed file is `src/main/resources/static/assets/favicon.png` — **256 × 256**, 8-bit RGBA, **18,842
bytes**, sha1 **1aad188d864b4793d50bda85a95e75871f2c5158**, every **corner alpha 0**, content box
**(9, 7)–(255, 242)**; the crop cuts the bar at the right edge exactly as the site's does. The markup that names
it, the render case, the CSP, the `/favicon.ico` 404 and schema 21 are Stage 112's and do not move.

**The honest trade.** This tile is transparent and hairline where Stage 112's M and Stage 113's emblem were
opaque and solid, so at 16 px the arcs are lighter than either — the site's icon has the same property, and at
50 px (its own size) ours reads the same way: emblem, bar, "MUR". The crop only changes composition; it cannot
make the logo's 26-px strokes heavier at 16 without redrawing the mark, which is what the user asked to stop.

**Tests.** No Kotlin and no test changed. Full suite after installing the PNG: **47 classes, 694 tests, 0
failures, 0 skipped** (BUILD SUCCESSFUL in 6m54s).

**Over HTTP.** `scratch/stage114-verify.ps1` boots a throwaway from the new distribution on **8097** with an
empty `%TEMP%` database, `PTS_SEED_DEMO=false`, `PTS_DATA_FILE` at a path that does not exist and a random
18-character bootstrap password: EMP0000 signs in **302**; `/app` answers **302 → `/app/projects`**; both
`/app/projects` and `/app/assigned-tasks` carry the tag before the first stylesheet; the icon is **`200
image/png 18842`** with the committed sha1; the served PNG's three tested corners are **alpha 0**; `/favicon.ico`
is **404**. **9 checks, 0 failures.**

**Deploy.** No migration; schema stays **21**. The live JVM (PID 10284, the Stage 113 restart from the same
morning) was stopped by the force path; `data/pts.db` was copied **in place** first to
`data/pts.pre-stage114-20261103-082334.db` (**471,040** bytes) with `-wal` (**589,192**) and `-shm`
(**32,768**). `installDist` rebuilt in 2s — jar **1,924,793 bytes** (Stage 113's was 1,909,885; the icon's
14,628 extra bytes are most of it). Relaunched detached through `deploy/run.bat` → **PID 6832**, parent
`cmd.exe`, 127.0.0.1:8080, `/` **200**; live `/` names the icon **once**, before the stylesheet, and
`/static/assets/favicon.png` answers **`200 image/png 18842`** with sha1
`1aad188d864b4793d50bda85a95e75871f2c5158` on both sides; both head lambdas in the jar name it once. Read-back
on the real data (metadata only): schema **21**, 15 projects, 30 tasks.

**Seeing it.** The tab is the one surface no server check can see, and a tab icon is among the stickiest caches
a browser keeps: **Ctrl+F5**, or a new tab/window, is what asks for this tile again. Compare it with
murrays.mn's own tab if you want the reference side by side.

**If this is restated.** Do not redraw the mark — no masking the bar, no rebuilt caps, no white-on-green box,
no letter-only tile. Do not point `rel="icon"` at the wide `mms-logo.png`: it must stay a **square crop**, the
site's own rule. Keep the field **transparent**, as the site's icon is; do not add a background, a margin or
rounded corners. Keep one named PNG and no `/favicon.ico`. Do not make the render test decode pixels or pin the
sha1. If the logo raster changes, re-render with `scratch/favicon-site.py` (the shipped square is `site-g`,
side 1600 at (80, 120)) rather than editing the PNG; and read the site's icon as *the logo's left square*, not
as a separate design to copy.

<a id="stage-115"></a>

## Stage 115 — The tab is titled MPTS (2026-11-03, live, no migration, Kotlin + test)

**The ask.** *"make web title MPTS"* — then, the same day, *"web title dont have to change as go to the
different function"*. The browser tab's text, the line beside the Stage 114 icon, had carried the long system
name: `MURRAY Project Tracking System (PTS)` on sign-in and `MURRAY PTS — <page>` signed in. A short per-page
form (*MPTS — Projects*) was the first cut; the user withdrew it, and the tab is now the one word **MPTS on
every page**, sign-in included.

**The change, two lines and one test.** `Views.kt:35` and `DashboardCommon.kt:206` both call `title("MPTS")`.
The page's own name did not go anywhere: it is the **on-screen heading**, printed by `appPage` from the `title`
parameter it still takes (`+title`), and a project page still heads with the project's name. The sign-in page's
**h1** still prints the long system name — that is the page, not the tab — and nothing else moved: README's own
title, the docs and the `start "MURRAY PTS"` window title named in the deploy note are untouched.
`LoginPageRenderTest` gained *the tab is titled MPTS*, which reads the `<title>` and compares it exactly, so the
long name cannot quietly come back; the throwaway check pins the signed-in pages too, including that the tab
does not follow the page.

**Verification.** Full suite **695 tests / 47 classes / 0 failures** (694 at Stage 114; the new case is the one
added). `scratch/stage115-verify.ps1` (throwaway **8097**, empty `%TEMP%` DB, `PTS_SEED_DEMO=false`, nonexistent
`PTS_DATA_FILE`, random bootstrap password): the sign-in tab is **MPTS**; `/app/projects`,
`/app/assigned-tasks` and `/app/settings` all read plain **MPTS**; the signed-in head no longer contains
`MURRAY PTS`; and no page's title carries the `MPTS — ` prefix — **7 checks / 0 failures**.

**Deploy.** No migration, schema **21**; stopped PID 6012 (graceful, then force), backup
`data/pts.pre-stage115-20261103-095425.db` (471,040 bytes, `-shm` 32,768, `-wal` 0); `installDist` jar
**1,929,128 bytes**; relaunched detached → **PID 18824**, `/` **200**, live title **MPTS**, icon still named
before the stylesheet. Read-back (counts only): 15 projects, 30 tasks, 14 accounts. **No commit, no push.**
Harness traps: the titles compile into the **outer** classes (`DashboardCommonKt.class`, `ViewsKt.class`) —
`title()` is not inside `unsafe{}`, unlike Stage 112's icon — so grepping the `$appPage$1$3`/`$loginPage$1$3`
lambdas prints 0 and looks like a missing title; and PowerShell 5.1 hands a native `grep` its pattern in the
ANSI code page, so an em dash typed into the pattern never matches the class file's UTF-8 — match `MPTS` and
read the prefix with `grep -o` from bash. The Stage 114 count snippet was also missing a `)`
(`max(cast(version as integer)` where the working form is `max(cast(version as integer))`).

**If this is restated.** Do not put the long system name back in `<title>` — it is the sign-in page's **h1**,
not the tab. Do not re-add a per-page suffix (`MPTS — Projects`): the tab stays one word everywhere, and the
page's name belongs to the on-screen heading. No other copy, route, schema or static asset belongs to this
stage.

<a id="stage-116"></a>

## Stage 116 — Lesson learnt drops its archive, and the assignment notice is verified end to end (2026-11-03, live, no migration, Kotlin + CSS + test)

**The ask:** *"since we are not archiving projects we dont need archived button on lesson learnt, also when user gets assigned task send notification also"*. Two asks: the first is a removal this stage makes; the second was already built, and this stage proves it rather than rebuilding it.

**The archive came out whole; the column stayed.** Removed: the `▾ Archived N` heading with its caret and pill, the archive and `↩` restore buttons on a row, the `?archived=1` view key and its hidden carriage on every write, the "3 active, 1 archived" halves of the toolbar count, `POST /app/lessons/archive`, `LessonService.setArchived`, `LessonRepository.setArchived`, and `LessonBoard.archived`/`archivedCount` with `LessonFilter.showArchived`. The count now reads "3 lessons" / "1 lesson" / "0 lessons". What did **not** move is `lessons.archived`: a row put away before this stage is data, not a lesson, so `LessonService.board` still drops it (`filter.matches(it) && !it.archived`) and `LessonRepository.countActive` still counts only `archived = 0`. No route or control can bring one back. That is the stored rule — remove the control, not its data — with the column kept as the data.

**What the sheet still does.** An ordinary account sees two buttons on its own row (discussed, delete) and none on anybody else's; management sees those two on every row. `✓`/`↺` still swaps only its own `<tr>`; delete still swaps `#ll-board` plus `#ll-counts`. The retired key is ignored if typed into the address (`LessonFilter.of` reads project/employee/sort only), and no writing form carries `archived` any more.

**Assignment notifications: already there, now proven through the real route.** `TaskService.create`, `createBacklog` and `updateAssigned` each call `NotificationService.taskAssigned`; the bell has carried `TASK_ASSIGNED` items since Stage 13, and an actor is never told about their own action. On throwaway 8096 (demo-seeded, 2026-11-03) a management create assigned to EMP0002 answered **302**; the database held one `TASK_ASSIGNED` row for EMP0002 with `mail_status=logged` and no `read_at`; EMP0002's bell printed *"T-00011 S116 notify check"* with *"Super Admin assigned this task to you on S-01 - Tavan Tolgoi East Expansion. Due 2026-11-10."*; and the log recorded the composed mail and `Notification mail for EMP0002 (TASK_ASSIGNED): logged`. The notice is **status-blind**, which this stage pinned when the user restated the ask (*"it does not matter if the task is started early before put in the system"*): `taskAssigned` reads who holds the card and never consults its status, so a create saved as *In progress* — or *Completed* — writes the same inbox row and the same mail, and moving an in-progress card still tells the person it moved to; `NotificationServiceTest` now holds both cases. **Mail is log-only by deployment** — `PTS_SMTP_DRY_RUN=true` in `data/.env` and no `PTS_SMTP_PASSWORD` — so turning on real email is an operator decision (a credential, the flag, and the Settings Email switch), not something this stage changed.

**Sandbox note.** `DemoSeeder` writes users through the legacy importer and leaves `users.role_id` NULL, so a freshly seeded throwaway's EMP0000 holds no permission chip and its first create is refused. The throwaway proved the flow after setting EMP0000 → Owner and EMP0002 → Employee in that temp DB only. Live accounts have roles (V21's backfill); the gap is the demo seed's.

**Verified:** **697 tests / 47 classes / 0 failures** — the archive cases were replaced one-for-one, and two new cases pin the assignment notice as status-blind. Throwaway 8096: 3 rows, 1 table, `ll-counts` `"3 lessons"`, and **0** hits for `ll-arch`, `ll-arch-toggle`, `/app/lessons/archive`, `Archive this lesson` and `Nothing has been archived`.

**Deploy.** No migration, schema **21**; stopped PID 18824 (force — it refused the close event), backup `data/pts.pre-stage116-20261103-102042.db` (471,040 bytes, `-wal` 214,272, `-shm` 32,768); `installDist` jar **1,920,128 bytes**; relaunched detached → **PID 23460**, `/` **200**, boot log "Schema "main" is up to date. No migration necessary", 9 categories. Read-back (counts only): 16 project rows, 30 tasks, 15 accounts, 3 lessons (0 archived), 130 notifications of which 28 are `TASK_ASSIGNED`. `POST /app/lessons/archive` now answers **404**, and the built `LessonsKt`/`LessonService`/`ApplicationKt` classes contain none of the retired strings. **No commit, no push.**

<a id="stage-117"></a>

## Stage 117 — A new employee is filed under a department, and only an owner or administrator chooses it (2026-11-03, live, no migration, Kotlin + test)

**The ask:** *"when adding a new employee need to choose department from dropdown and the dropdown has Management, Finance, Human Resource, HSE, Production, Engineering, Sales, Project and Procurement and user who has administrator and owner role can edit or set"*. The Settings People sheet's Add/Change popup gains one picker with exactly those nine words in that order, and the right to move it belongs to the two all-chip roles.

**The right is the chips, not the word.** `Authorization.isOwnerOrAdministrator` answers true when the reader's role holds **every** `Permission` — which the catalogue's Owner and Administrator both do (13/13). A role renamed away from *Owner* keeps the right because it kept the chips, a custom role ticked to all thirteen earns it, and a twelve-chip role such as Project Manager does not. Both routes ask it themselves: GET `/app/settings` puts it on the form as `maySetDepartment`, and POST `/app/settings/employee/save` passes it into `EmployeeService.save` and into the refusal's rebuilt form. A reader with *Manage employees* but not the full set still adds accounts; the popup simply carries no Department picker, and a hand-built `department=` field is refused rather than ignored.

**The picker.** `select#employeeDepartment` sits between the position and password blocks. On a create it opens on `— Choose a department —` and is `required`; on an edit it gains one leading option — `— Keep <the stored word> —`, or `— No department yet —` — selected whenever the stored value is blank or not one of the nine. That is what protects the free-text departments the old create path wrote (the live roster's *General*, *Executive*, *IT / Systems*): opening somebody's popup and pressing save does not silently clear their row. Choosing one of the nine replaces it.

**The rules, all server-side.** `EmployeeService.DEPARTMENTS` is the one list (`departments()` hands it to the form). A department typed by somebody without the right is `EmployeeField.PERMISSION` — *"Only an Owner or Administrator can set a department."*; a word that is not on the list is `EmployeeField.DEPARTMENT` — *"Choose one of the departments on the list."*; and a setter's **create** with nothing chosen is *"Choose a department from the list."* — an edit is not required, because every legacy row would otherwise be un-saveable. A refusal writes nothing, as usual. `EmployeeDraft` carries `department` among its `KEYS` and `of()` reads it like the other fields.

**Where it lands.** The department lives on the person's roster row (`team_members.department`), not on the account; the sheet's **Department** column and the Team page's grouping already read that column. `UserService.addUser` now writes the chosen word into the roster row — a person already on the roster without an account (the pencil on their row) keeps their row and gains the department — and the old hardcoded `"General"` default is gone, so a manager's create, which never names a department, writes a blank one. `UserService.updateUserProfile` folds the position and department into one roster upsert, so an edit that changes only the department does not blank the job title.

**Verified:** **702 tests / 47 classes / 0 failures** (from 697/47). Four cases in `EmployeeServiceTest` — an owner or administrator filing a person under a chosen department; the create's blank and off-list refusals with an edit's blank picker keeping *Operations*; a manager adding blank while a hand-posted department is refused on both a create and an edit; and an edit moving *Workshop* → *Production* while a create for somebody already rostered writes onto their row — plus one in `SettingsRenderTest` pinning the nine options in order, the create placeholder and `required`, the edit's `— Keep … —` line for an off-list value, the stored value selected after a roster change, and a manager's popup with no picker at all. The "two pickers" case became **the three** (positions, roles, departments).

**Proved over HTTP on throwaway 8095** (empty `%TEMP%` DB, demo seed off, a random throwaway Super Admin password that was never printed): the owner's popup rendered `<select name="department" id="employeeDepartment" required="required">` with exactly the nine after the blank; a create choosing Engineering answered 302 and the sheet printed Engineering; a blank answered 200 with *"Choose a department from the list."* and wrote no account; *Projects* answered 200 with *"Choose one of the departments on the list."*; an edit moved it to Finance; a role holding only *Manage employees* was served a popup with no `department` field at all, and its hand-posted department answered 200 with *"Only an Owner or Administrator can set a department."* and wrote nothing. The live 8080 JVM and `data/` were untouched while this ran.

**Deploy.** No migration, schema **21**, no CSS or JavaScript changed (the picker is server-rendered Kotlin, so no Ctrl+F5 is owed). Stopped PID 23460 (force; the polite close does not take on the detached window), backed up in place with the JVM stopped — `data/pts.pre-stage117-20261103-1205.db` (471,040 bytes, `-wal` 0, `-shm` 32,768); `installDist` jar **1,924,100 bytes**; relaunched detached through `deploy/run.bat` → **PID 7980**, `/` **200**, `/app` and `/app/settings` **302** outside a session, boot log "Schema "main" is up to date. No migration necessary", 9 categories. Read-back on the real data (metadata only): **21 accounts**, 21 roster rows all carrying a department under **3** labels (*General* 19, *Executive* 1, *IT / Systems* 1 — the old free-text words, deliberately not rewritten), 15 projects, 31 tasks (1 archived), 4 roles, 36 role-permission rows. The roster is larger than Stage 116's read-back (15 accounts then) because the app has been used between stages, not because of this one. The deployed jar's `EmployeeService` and `SettingsKt` classes carry `Procurement` and `employeeDepartment`. **No commit, no push.**

**Never re-propose:** migrating or blanking the old free-text departments (a setter changes one deliberately, by opening that person and choosing); letting *Manage employees* alone set a department; making the department `required` on an edit; a free-text department field; or deciding the right by the role's *name* rather than its chips.

<a id="stage-118"></a>

## Stage 118 — Access control's People sheet files a person under a department (2026-11-03, live, no migration, Kotlin + test)

**The ask:** *"the owner and the administrator can manage department from access control people"* — the Stage 117 department rule, moved to the page where people are actually filed. The People sheet gains a **Department** column between Position and Role, and each roster row carries a picker for the Owner/Administrator pair.

**The picker follows the pair, not the people chip.** `Authorization.isOwnerOrAdministrator` is the same test Stage 117 used — the reader's role holds **every** chip — so an Owner, an Administrator, a renamed all-chip role and a custom role ticked to all thirteen see a select; a role holding *Manage employees* but not the full set (the sheet's ordinary manager) sees the column as words. The column itself prints for everybody who may open the page, because a department is what the Team page groups by. It is drawn on **every roster row**, the row with no login included: a department belongs to the person, not to their account. `accessContent` takes the flag as `canDepartments`; the route asks the session, never the posted form.

**The cell and the route.** Each picker is a form posting to `POST /app/access-control/department` with a hidden `employeeNumber`, naming `#department-cell-<emp>` as its `data-live-target` and carrying `data-autosubmit` — the arrangement the Role and Position cells already use, so a move swaps that one cell and leaves the sheet where it was. The nine words are `EmployeeService.DEPARTMENTS` in the request's order. A stored word that is not one of them — the live roster's *General*, *Executive*, *IT / Systems* — opens as a selected **disabled** first option (`— General —`), or `— No department —` when the cell is blank: it prints the row as it is, and cannot be posted back as a department. The route re-checks the pair itself, so a hand-built POST from a people-chip reader answers the page unchanged. The caption under the sheet (shown to a roles-only reader) now names the department's pair beside the role's.

**Where it lands.** `EmployeeService.setDepartment(employeeNumber, department)` trims and upper-cases the number, refuses anything not in `DEPARTMENTS`, looks the person up on the roster and upserts a copy with the new department. It writes nothing else — not the account, not the job title — and answers false for a blank word, an off-list word and a number nobody is rostered under. Re-picking the current word is a no-op that still answers true. The Add user card is deliberately unchanged: it creates logins for people already rostered, and the department is a roster property that the Settings popup (Stage 117) and this picker now both write.

**Verified:** **704 tests / 47 classes / 0 failures** (from 702/47). `AccessControlRenderTest` gained one case: the heading between Position and Role, a picker on all seven roster rows (the no-login row included), the stored off-list word selected and disabled with the nine after it, the form's live target, hidden number and autosubmit, seven pickers for a reader with every chip but *Manage employees*, none for a people-only reader who still reads the column as words, and a row already on the list opening on its own word with no placeholder. `EmployeeServiceTest` gained one: the move writes the roster row and leaves the job title, and *Projects*, blank, a lowercase word and a stranger's number all answer false and write nothing.

**Proved over HTTP on throwaway 8095** (empty `%TEMP%` DB, demo seed off, a random throwaway Super Admin password never printed): the owner's People sheet printed the Department heading and one picker for EMP0000, with *— IT / Systems —* selected and disabled after the row's stored word and the nine in order; creating EMP0100 under Engineering answered 302 and its picker then opened on Engineering with no placeholder; posting Finance answered 302 and the re-read picker held Finance; *Projects*, a blank and `EMP9999` each answered 302 and changed nothing. A role holding only *Manage employees* was served the page with the Department column as words (*Finance* in EMP0100's cell), no picker and no route on it; its hand-posted Sales answered 302 and the owner's re-read still held Finance. The live 8080 JVM and `data/` were untouched while this ran.

**Deploy.** No migration, schema **21**, no CSS or JavaScript changed (the picker is server-rendered and uses the live-region script already loaded, so no Ctrl+F5 is owed). Stopped PID 7980 (force), backed up in place with the JVM stopped — `data/pts.pre-stage118-20261103-1241.db` (471,040 bytes, `-wal` 0, `-shm` 32,768); `installDist` jar **1,930,899 bytes**; relaunched detached through `deploy/run.bat` → **PID 14788**, `/` **200**, boot log "Schema "main" is up to date. No migration necessary", 9 categories. Read-back on the real data (metadata only): 21 accounts, 21 roster rows still under their **3** old labels (*General* 19, *IT / Systems* 1, *Executive* 1 — deliberately not rewritten), 15 projects, 31 tasks, 4 roles. **No commit, no push.**

**Never re-propose:** gating this picker by *Manage employees*; printing it in the Add user card (a department belongs to the roster row, not a login); clearing or migrating the old free-text words (a setter moves one deliberately); a free-text department box; deciding the right by a role's name rather than its chips; or writing the department on `users` instead of `team_members`.

<a id="stage-119"></a>

## Stage 119 — The shell folds to a drawer below 900px, and a card board keeps two columns (2026-11-03, live, no migration, Kotlin + CSS + JS + test)

**The ask:** *"Apply responsive behaviour Add a breakpoint at 900px in app.css: hide the sidebar and replace it with a hamburger button that slides it in, make the side panel full-width on small screens, and stack the dashboard cards 2 per row."*

**There is no dashboard page, and "the cards" are the boards.** PTS's home page is the projects board; the things laid out as cards are the five status columns on a card board (`.kanban`) and a project's lane board (`.kanban-lanes`). So "stack the dashboard cards 2 per row" is read as those two boards: below 900px each becomes a grid of two fluid tracks instead of five status columns across or auto-filled 288px lanes. Tables — the projects board, Team, Calendar, Access control, Settings — keep their percentage columns; the breakpoint deliberately does not restyle a table — the shrink that made words break at narrow widths was answered outside the breakpoint in [Stage 120](#stage-120).

**The markup: one hamburger in the page head, one backdrop on every page.** `appPage` (`DashboardCommon.kt`) now wraps the heading in `.page-title` and prints `#navToggle` before it, so the page's actions and the bell keep `.page-head`'s far edge. The opener is a real `<button type="button" class="nav-toggle">` with `aria-controls="appSidebar"`, `aria-expanded="false"` and `aria-label="Open navigation"`, and three `.nav-bar` spans for the bars — no icon font and no inline SVG. The sidebar gets `id="appSidebar"`; a `.nav-backdrop` with `id="navBackdrop"` and `aria-hidden="true"` closes the drawer when pressed. Both are printed on every page, and the stylesheet decides whether they can be seen.

**The breakpoint: the first `@media` rule in `app.css`.** `@media (max-width: 900px)` takes `.app` to block layout, narrows `.main` to 16px side padding, gives `.sidebar` the full width, and makes it an off-canvas drawer — `position: fixed; width: 100%; transform: translateX(-100%)`, a `.2s` transition, `z-index: 60` above a `z-index: 55` backdrop. `body.nav-open` slides it to `transform: none`, locks the page's scroll, shows the backdrop and tints the button. `.kanban, .kanban-lanes { grid-template-columns: repeat(2, minmax(0, 1fr)); }` is the "two per row" half; the lane board's fixed 288px tracks are overridden so both boards agree at this width. Above the breakpoint `.nav-toggle, .nav-backdrop { display: none; }`, which is what leaves a desktop unchanged.

**One class, `body.nav-open`, and a script that only sets it.** `app.js` adds `html.js` to the document before the first paint, and the off-canvas rules are scoped to it — with JavaScript off the sidebar stays in the flow and stacks above the page, a smaller shell but one that still navigates. With it, the toggle flips `nav-open` and `aria-expanded` and swaps the label between *Open navigation* and *Close navigation*. Opening moves focus to the drawer's first `a, button`; closing (the toggle again, the backdrop, Escape, a drawer link, or the window growing past 900px through `matchMedia('(min-width: 901px)')`) removes the class, and every close except the grow returns focus to the button. The grow closes quietly without stealing focus, because the reader has just been handed the desktop layout.

**Verified:** **707 tests / 48 classes / 0 failures** (from 704/47). `ShellRenderTest` (3) renders the whole shell on a temp database: the opener's id, classes, `type`, `aria-controls`, `aria-expanded="false"`, `aria-label` and three bars; the title wrapper holding the toggle and `<h1>Projects</h1>`; the backdrop's id and `aria-hidden`; exactly one `@media` at-rule, the 900px block's named rules, the hidden-by-default pair, and no unscoped `.sidebar` translation above it; and the script's `html.js` class, ids, `nav-open` toggle, `aria-expanded`, Escape and 901px query. The live behaviour was exercised with `scratch/stage119-js-harness.cjs` (15 checks, 0 failures) driving the shipped `app.js` through a DOM stub: class added, open state, aria and label, focus into the drawer, Escape closing and returning focus, backdrop closing, a drawer link closing, widening closing without moving focus, and Escape as a no-op when already closed.

**Deploy.** No migration, schema **21**; stopped PID 14788 (force), backed up in place with the JVM stopped — `data/pts.pre-stage119-20261103-1309.db` (471,040 bytes, `-wal` 86,552, `-shm` 32,768); `installDist` jar **1,934,345 bytes**; relaunched detached → **PID 12764**, `/` **200**, boot log "Schema "main" is up to date. No migration necessary", 9 categories. Read-back on the real data (metadata only): schema 21, 21 accounts, 21 roster rows, 15 projects, 31 tasks, 4 roles and 36 role-permission rows; the roster's departments still add up to 21 (Project 5, Engineering 5, Management 3, Production 2, and six one-person labels including the off-list *IT / Systems*). CSS and JavaScript changed, so every open tab needs **Ctrl+F5**. **Committed later the same day** when asked — *“commit this and push”*: `4a024b1` (the push `9b7cbd5..4a024b1` to the **github** remote also published `e05e42f`, Stages 117–118; `origin` untouched).

**Never re-propose:** inventing a dashboard page to hold "the cards"; an always-visible hamburger on a desktop; a second breakpoint for the drawer and the boards; reopening the drawer when the window grows back; a drawer that exists only with JavaScript (the `html.js` gate is the scripting-off fallback); or restyling the tables at 900px, whose percentage columns are the projects board's own responsive rule.

<a id="stage-120"></a>

## Stage 120 — A dense sheet keeps a readable width and scrolls instead of breaking words into letters (2026-11-03, live, no migration, CSS + test)

**The ask:** *"when shrinking not get letter by letter"* — narrow the window and some tables stopped wrapping words and started printing them one letter a line.

**What was actually shrinking.** The dense sheets are `table-layout: fixed` with percentage `<col>` widths that add up to 100 and `overflow-wrap: anywhere` on every cell; that was the projects board's only responsive rule (Stage 16) and the forecast ledgers copied it. Below a certain width the free-text columns run out of room and `anywhere` does what it says: at 520px the board's code cell printed `S 0 1` under a heading reading `C O D E`, names split mid-word and the dates overlapped. The squeeze is not only a small-screen fault: at 1000px the 240px sidebar is back and the panel is still narrower than the sheet wants, so a manager's name broke inside itself there too. Stage 119's 900px rule moves the drawer and the card boards and deliberately leaves tables alone, which is why the fault survived it.

**The answer: a floor per sheet, and a panel that scrolls to reach it.** Outside the breakpoint — so the stylesheet still has exactly one `@media` rule — each dense sheet gets a `min-width`: projects `1040px`, Team `1000px`, the running forecast `1280px`, its completed half `1100px` and Lesson learnt `1000px`. `table.grid { width: 100% }` still stretches them on a wide window; below the floor the panel around the table becomes the scroll container: `.card-panel:has(table.grid), .board-results:has(table.grid) { overflow-x: auto; }`. `:has` is what keeps the scroll on panels that actually hold a sheet — the popups (`.mpop`), the fixed modals and the notification panel are not `.card-panel`s, and a card-panel holding only a caption or a form is not turned into a scroll box. Calendar, Access control's People sheet and the permission sheet are not listed: their three and four columns fit, and they still wrap as before.

**Column shares traded where the floor still pinched.** At the floor the projects board's old 5% code column was 52px, still too narrow for `S-01` beside its padding, and the manager's 9% was about 94px, which still broke a long name — the sheet was wider but not yet readable. The Stage 120 block rebalances four projects columns (code 6%, name 16%, manager 11%, pin 7%), two Team columns (department 11%, email 17%), four forecast columns (type 4%, code 6%, description 14%, previous 8%) and two completed-ledger columns (type 5%, description 23%), each pair trading width with the free-text column beside it so every sheet still sums to 100. The percentages are unchanged in kind: the floor decides how many pixels a share is worth.

**Verified:** **708 tests / 48 classes / 0 failures** (from 707/48). `ShellRenderTest` gained a fourth case: the `:has` scroll rule verbatim, each of the five floors, the projects board's ten column shares still summing to 100 (parsed back out of the CSS), the code column at 6% and the manager column at 11% — the two that broke at the floor. The fault was reproduced first with headless Chrome against a static preview (`scratch/projects-table-preview.html`, sourcing the real `base.css`/`app.css` and the board's markup): at 520px before the change the screenshot printed `C O D E` vertically and `S 0 1` over three lines; after it the columns read CODE / PROJECT / CLIENT / STATUS with the remainder reachable through the panel's scrollbar; at 1000px the manager column then fitted after the rebalance. The signed-in pages themselves are the reader's to judge — the preview proves the stylesheet, not the live data.

**Deploy.** No migration, schema **21**. Stopped PID 12764 (force) and confirmed the port free; backed up in place with the JVM stopped — `data/pts.pre-stage120-20261103-1439.db` (471,040 bytes, `-wal` 0, `-shm` 32,768); `installDist` jar **1,935,072 bytes**; relaunched detached through `deploy/run.bat` → **PID 6788**, `/` **200**, and the served `/static/css/app.css` carries the `:has` rule and `table.projects-table { min-width: 1040px; }`. Read-back on the real data (metadata only): schema 21, 22 accounts, 22 roster rows, 1 project, 2 tasks, 4 roles and 36 role-permission rows — the board held 15 projects at Stage 119 and one at this deploy, and the roster 21 → 22; both are the operator's own data, read only. CSS and the test changed, so every open tab needs **Ctrl+F5**. **No commit, no push.**

**Never re-propose:** letting a dense sheet shrink until `overflow-wrap: anywhere` splits words into letters; a second `@media` rule for the floors (they apply at every width, and the one breakpoint is the shell's); applying `overflow-x` to every `.card-panel` rather than the ones holding a `table.grid`; hiding columns or shrinking the font instead of scrolling; or replacing the percentage shares with fixed pixel columns (the shares are what keeps every category panel lined up).

<a id="stage-121"></a>

## Stage 121 — The login password box gets a Show/Hide button (2026-11-03, live, no migration, Kotlin + CSS + JS + test)

**The ask:** *"when user login add unhide password button"* — a reader typing a password at the sign-in card had no way to check what they had typed.

**The control.** The password input is wrapped in `.password-wrap` and followed by a real `<button type="button" class="password-toggle" id="passwordToggle">Show</button>`, so pressing it can never submit the form. It carries `aria-controls="password"`, `aria-pressed="false"` and `aria-label="Show password"`, and sits pinned to the input's right edge; the input reserves `74px` on that side so a long password never types under the word. The button reads **Show** / **Hide** rather than carrying an icon — the login card has no icon set of its own, and a word is unambiguous on a phone, where the button is the same size as on a desktop.

**The script, and the scriptless page.** `login.js` is the login page's second script (after `theme.js`); the app shell does not load it, and no other password box is touched. It adds `html.js` to the document first, and `base.css` keeps `.password-toggle { display: none }` until that class is present — so with JavaScript off the reader gets the plain password box, not a button that cannot reveal anything. With it, a press records the caret, swaps the input between `type="password"` and `type="text"`, returns focus, restores the caret, and moves the button's word, `aria-label` and `aria-pressed` together. Enter still submits, and a failed sign-in reloads the page with the password hidden again.

**Verified:** **709 tests / 48 classes / 0 failures** (from 708/48). `LoginPageRenderTest` gained a fourth case: the button's id, `type="button"`, class, `aria-controls`, `aria-pressed="false"`, `aria-label` and its *Show* word; the `.password-wrap` that pins it; the `login.js` script tag; the sheet's hidden-until-`html.js` rule and the 74px reservation; and a read of `login.js` for the `js` class, both ids, the type swap, the two labels, `aria-pressed` and the focus return. The behaviour itself was exercised by `scratch/stage121-login-harness.cjs` (12 checks, 0 failures) driving the shipped `login.js` through a DOM stub: the class added, one click revealing the password, the word/aria/focus/caret all moving, a second click hiding it again. Headless Chrome on the live login page at 1280px and 520px shows **SHOW** inside the password box at both widths; the signed-in surface was not touched.

**Deploy.** No migration, schema **21**. Stopped PID 6788 (force) and confirmed the port free; backed up in place with the JVM stopped — `data/pts.pre-stage121-20261103-1519.db` (471,040 bytes, `-wal` 0, `-shm` 32,768); `installDist` jar **1,937,377 bytes**; relaunched detached through `deploy/run.bat` → **PID 20980**, `/` **200**, and the served `/` carries the button markup and the `login.js` tag, `/static/js/login.js` answers 200, and `base.css` carries the `html.js` rule. Read-back on the real data (metadata only): schema 21, 22 accounts, 22 roster rows, 1 project, 2 tasks, 4 roles and 36 role-permission rows. CSS and JavaScript changed, so every open tab needs **Ctrl+F5**. **No commit, no push.**

**Never re-propose:** showing the password by default; a checkbox instead of a button; putting the control outside the input; a button that works only with JavaScript visible (the `html.js` gate is the fallback); using an inline `<script>` (the CSP allows `script-src 'self'` only); or adding the same toggle to the Settings and Access control password popups without an ask.

<a id="stage-122"></a>

## Stage 122 — A project card can be assigned to two people (2026-11-04, live, migration V22, Kotlin + test)

**The ask:** *"for the project tasks make it able to assign 2 person"* — a project card named exactly one holder, and work that two people share had to be split into two cards or left with one name on it.

**The column, not a join table.** Every card carried one `assignee_emp_no`: the person whose day it books, whose popup opens writable, whose initials the card prints and whose inbox hears about it. V22 adds `tasks.assignee2_emp_no`, nullable, referencing `team_members(employee_number)` with `ON DELETE SET NULL`, so a removed account leaves the card with its other person rather than taking the work with it. It is a second column on `tasks` rather than a `task_assignees` join table on purpose: two is the number the user asked for, while a join table would promise N people the popup never offers; and every rule in the app is written around *the holder* — the three date and priority fields, the ✓ Done button, the checklists, the log — so a second person is another holder of the same card, not a new kind of row. Every row written before the migration keeps exactly the one owner it had, because the column is nullable and reads back blank.

**The second name is project-only.** A card on the **Other tasks** board is filed under a column and belongs to one person at a time, so that popup prints no second picker and posts nothing for it; `TaskService.createBacklog` blanks the name whatever a form posts, and a card crossing from a project onto that board drops the second person in the same breath that it drops the project and the lane, keeping the person who held it. On the project side `TaskItem.assignees` (primary first, then the second, blanks dropped) and `assignedTo(employeeNumber)` are the one read every rule now uses, so a second name is a second holder everywhere at once rather than a display-only line: the Assigned board and its sidebar pill, `assignedFor`, the Team sheet's per-person drill-down, the Calendar's grid and list, the two avatars on a card, the ✓ Done button and the status move, adding and ticking a checklist step, and the log's author check.

**Both people are holders; both names stay management's.** The popup's second picker is labelled **Also assign to** (`name="assignee2"`, `id="taskAssignee2"`), opens on **— Nobody else —** and lists the roster in the same groups the first picker uses, under the hint *"Optional. Both people share the card's plan, its log and its dates."* It is printed on every popup that files a project card — a project page's editor and the Assigned page's `+ New task`, which draw the same form — and never on the Other tasks popup. In an owner's popup both pickers are fixed options, and a non-manager's save rebuilds both names from the stored row, so the pair remains management's to set: either holder may move their own dates and priority, tick steps, add a note and close the card, but cannot add, swap or remove either person. A typed second name is held to the same two facts as the first and refused under its own `TaskField.ASSIGNEE2`, with the sentence at its field — *"Choose somebody different from the first assignee."* when it repeats the first, *"That employee is not on the team roster."* when nobody on the roster answers to it.

**The two notices.** A create tells both people, each a separate inbox row and mail attempt, and skips whoever pressed the button. An edit tells only the people it has **added** — a set difference between the card's old two names and its new two — so putting a second person on work that already had an owner mails that person alone rather than mailing the first one all over again, and a rename or a date change mails nobody. The due-date sweep reads both names: both holders and the project's manager each get their own row, and the body's role line still says *the task assignee*, *this project's manager*, or both when one person is both.

**Verified:** **719 tests / 48 classes / 0 failures** (from 709/48). `TaskServiceTest` gained five cases (112 → 117): a project card may name a second person and both names come back in order, with `assignees` / `assignedTo` reading the pair; a duplicate or unknown second is refused under `TaskField.ASSIGNEE2`; the second person is a full holder (their own Assigned visibility, dates and priority, checklist add and tick, note, status move); neither holder may rewrite either name while management can remove the second; and a card crossing to the backlog leaves its second person behind, with a backlog create ignoring a posted one. `TaskBoardRenderTest` gained two (77 → 79): the project popup's second picker with its blank option, hint and the backlog popup's lack of any `assignee2`; and both holders' editors opening filled, with a lane card printing two avatars on its foot line. `NotificationServiceTest` gained two (17 → 19): a card with two people tells both and not the actor, and an edit that adds a second tells only the new person. `DueDateAlertServiceTest` gained one (14 → 15): two holders alert both, and a holder who is also the project's manager hears once with the combined role. The throwaway proved the surface over HTTP (port 8095, an empty `%TEMP%` database, demo seeding off, dry-run mail): V22 applied with the column present and Flyway's row 22 recording success; the project and Assigned editors served and pre-filled the second picker; the true backlog popup (`?new=1&column=1`) printed none; a create naming two people stored both; the lane board painted both avatars; a holder's save moved their own due date and priority while forged title, assignee and status posts were ignored; a stranger's save returned the page with *"Only management or the person this task is assigned to can change it."* and left the row untouched; and the holder's Calendar carried the card with both names. The throwaway directory was removed afterwards.

**Deploy.** Migration **V22**, schema **22**. Stopped PID 17068 (force) and confirmed the port free; backed up in place with the JVM stopped — `data/pts.pre-stage122-20261104-0911.db` (471,040 bytes, `-wal` 0, `-shm` 32,768); `installDist` jar **1,946,446 bytes**; relaunched detached → **PID 7092**, `/` **200**, boot log "Successfully applied 1 migration to schema main, now at version v22". Read-back on the real data (metadata only): schema 22, the `assignee2_emp_no` column present, 22 accounts, 22 roster rows, 1 project, 3 tasks, 4 roles and 36 role-permission rows; `/app/assigned-tasks` still redirects to `/app/`, GET `/login` still answers 405 (it is the form's POST target), and the served `app.css` hashes to `af81ae24…`, unchanged. No CSS or JavaScript changed, so no tab needs a reload. **No commit, no push.**

**Never re-propose:** a `task_assignees` join table, or a third and further name, without an ask; a second person on an Other-tasks card (that board is one person's work by design); making the second person a watcher with fewer rights than the first; a separate co-assignee permission or role; mailing the existing holder again when a second is added (the rule is the set difference, not "tell everyone"); the second picker on the backlog popup; letting either holder set either name (both are management's, like the title); or dropping one avatar from a shared card's board, foot line or calendar bar.

<a id="stage-123"></a>

## Stage 123 — Settings opens to every account, your own password changes there, and only the super admin sets anyone else's (2026-11-05, live, no migration, Kotlin + test)

**The ask:** *"everyone has settings function but when project manager and employees are should have only your account section also add change password part. And only super admin able to see others' password"* — a Project Manager and an Employee were turned away from Settings, and no account could change its own password at all since Stage 99 retired the form.

**Two gates came off, not one.** Settings had been narrowed twice: Stage 51 dropped its sidebar link and bounced a `Viewer`, and Stage 110 re-cut the gate onto the three panel chips, so a **Project Manager** (nine chips, none of the four that draw the page) and an **Employee** (one chip) both got a redirect even though the page had always carried a personal card. `DashboardCommon.kt` now leaves `settings` out of `NAV_PERMISSIONS` entirely, so every signed-in drawer prints the link; `GET /app/settings` redirects nobody and simply draws whatever the reader's chips allow. The four panel chips still decide the rest of the page: `canPeople`, `canRoles`, `canTypes` and `canEmail` print the Team sheet, its popup and the three app-settings panels exactly as before, so a custom role holding one of them still gets that panel. What a Project Manager or an Employee sees is therefore one card — **Your account** — and nothing else: no roster (`#team-board`), no employee popup, no Project types, Project bucket settings or Email notifications. `SidebarTaskCountTest` pins the result: an Administrator's drawer keeps all ten links, a Project Manager's nine (no Access control), an Employee's seven (no Forecasts, Other tasks or Access control), and every id in `NAV_PERMISSIONS` still appears in an Administrator's menu.

**The form that came back.** The account card is four `set-row`s — employee number, display name, position, role — then **Change password**: a caption (*"Type the password you sign in with, then the new one twice. Your other sessions are signed out; this browser stays signed in."*) and three fields, `currentPassword` (`#accountCurrentPassword`, `autocomplete="current-password"`), `newPassword` (`#accountNewPassword`, `minlength=4`, `autocomplete="new-password"`) and `confirmPassword` (`#accountConfirmPassword`), under the button **Change password**. It posts as an ordinary form to the new `POST /app/settings/password`, whose `UserService.changeOwnPassword` checks in the fields' own order: an empty field returns `EMPTY`; a current password that does not verify returns `WRONG_CURRENT`; two new passwords that differ return `MISMATCH`; a new one under `MIN_PASSWORD_LENGTH` returns `TOO_SHORT`; otherwise it calls `AuthService.changePassword` and returns `CHANGED`. The route answers with `303` to `/app/settings?pw=<status>`, and the page reads the code back into the field each refusal belongs under — *"Current password is not correct."*, *"The two new passwords do not match."*, *"A password is 4 characters or more."* — or prints the success line **Password updated successfully.** `PasswordStatus` regains `WRONG_CURRENT` and `MISMATCH`, both field-level, and `EMPTY` keeps its full-width sentence *"Please fill in all password fields."*

**The browser that changes its password stays signed in.** `AuthService.changePassword` revokes every session the account has, which is the right rule but would have logged the reader out in the act of obeying the form. On `CHANGED` alone the route therefore creates a fresh session with `SessionRepository.create` (same TTL, user agent and client IP as a login) and writes the new `PTS_SID` cookie, so every other browser is signed out and this one is not. The revoked-cookie case is still the one the tests pin: after a change, the old token no longer resolves.

**"See others' password" means the control, and it is the super admin's alone.** `users.password_hash` is bcrypt (cost 12); no page stores, returns or prints a readable password, so the honest reading of the ask is *who may see and set the password control for somebody else's account*. That is now the super admin — `session.employeeNumber == UserService.SUPER_ADMIN` (EMP0000), checked at the route and again when the page is drawn — and **not** a permission chip. An **Owner** or **Administrator** holds all thirteen chips and still loses every one of these controls, which is the difference the ask names. Three surfaces moved:

- **Access control's People sheet.** The **Password** column and its per-row setter now follow `canSetPassword`, not `canPeople`; `POST /app/access-control/password` redirects anyone who is not the super admin before it reads a parameter. **Delete** stays on `canPeople`, so an Administrator keeps the roster and its bins and loses only the password pair.
- **The add-account card on that page.** Its **Password** box is drawn only for the super admin; `UserService.addUser` takes `canSetPassword`, and when it is false it ignores a posted value and falls back to the employee number (`mustChange = true`, as before).
- **The Settings employee popup.** `EmployeeForm.maySetPassword` draws the Password box and chooses the caption; a hand-built `password=` field is refused by `EmployeeService.save` under `EmployeeField.PERMISSION` with *"Only the super admin can set another person's password."*, and nothing is written. An Administrator's popup now says *"They sign in with their employee number as the password; only the super admin sets another one."*

**Copy that had to change with the behaviour.** Access control's captions now say who holds what: when the reader cannot manage people, *"Manage employees is what adds and removes accounts. The role beside each name is set by whoever manages roles & permissions, the department by an Owner or Administrator, and a password only by the super admin."*; when they can manage people but are not the super admin, *"A new account signs in with its employee number as the password, and only the super admin can set another one. …"*. The README's sign-in bullets, Settings section, Access control paragraphs, security model and launch walkthrough were corrected in the same pass, and the project memory's Stage 99 and Stage 110 notes gained their supersession lines.

**Verified:** **723 tests / 49 classes / 0 failures** (from 719/48). `OwnPasswordTest` is new and holds two cases: the check order (`EMPTY`, `WRONG_CURRENT`, `MISMATCH`, `TOO_SHORT`, the stored hash untouched by each refusal, and an unknown account) and a real change writing the new hash while the old session stops resolving. `SettingsRenderTest` rewrote the viewer case into *"a viewer's settings page is their own account and its change-password form, nothing of the roster"* and added *"the change-password form prints success and each refusal under its own field"* (every sentence, including the full-width empty-fields line), with the popup cases now covering the super admin's Password box and an Administrator's lack of it. `AccessControlRenderTest` asserts an Administrator gets no Password heading, no `/app/access-control/password`, no add-card `name="password"`, but keeps Delete and the new caption. `SidebarTaskCountTest` pins the three menus above. `EmployeeServiceTest` adds *"an administrator who holds every chip still cannot set another person's password"* — an edit refusal leaves the hash alone and a create refusal writes no row. The throwaway (port **8095**, an empty `%TEMP%` database, `PTS_SEED_DEMO=false`, a nonexistent data file, dry-run mail, random throwaway-only credentials) ran **34 checks, 0 failures**: a Project Manager and an Employee each got `/app/settings` **200** with the Settings link, *Your account* and the form, and no roster, popup or panels; an Administrator saw the roster and the form but no Password column, setter or add-card box, and their `POST /app/access-control/password` was turned away with the Employee's password unchanged; the Employee's own change answered `?pw=CHANGED`, kept the browser signed in and printed the success line, the new password signed in and the old one did not; `WRONG_CURRENT`, `MISMATCH` and `TOO_SHORT` came back as their own codes with the wrong-current sentence under its field; and the super admin's write through Access control took effect and signed in.

**Deploy.** No migration, schema stays **22**. Stopped PID **12184** (force) and confirmed 8080 free; backed up in place with the JVM stopped — `data/pts.pre-stage123-20261105-162800.db` (471,040 bytes, `-wal` 0, `-shm` 32,768); `installDist` jar **1,956,235 bytes**; relaunched detached through `deploy/run.bat` → **PID 3096**, `/` **200**, boot log *"Schema "main" is up to date. No migration necessary."* Read-back on the real data (metadata only): schema 22, 22 accounts, 22 roster rows, 1 project, 2 tasks, 4 roles and 36 role-permission rows; GET `/login` still answers **405** (it is the form's POST target). Kotlin only — no CSS or JavaScript changed, so no tab needs Ctrl+F5. **No commit, no push.**

**Never re-propose:** printing or returning an existing password (it is a bcrypt hash — "see" can only mean the control); giving Owner or Administrator the other-password controls because they hold every chip; making the super-admin check a fourteenth chip; restoring the first-login gate or forcing a change on employee-number passwords; a separate, poorer Settings page for Project Managers; letting a reader rewrite their own name, position or role from *Your account*; showing a password column to the account that owns it (the owner changes theirs through the form, never by reading a stored one).

<a id="stage-124"></a>

## Stage 124 — The empty Assigned panel offers a new bucket, the shared Other-tasks column (2026-11-06, live, no migration, Kotlin + JS + test)

**The ask:** *"change this button’s purpose name it new bucket and make it same as a new column"* — the screenshot circled the button inside the empty Assigned tasks projects-board panel, not the page head's task opener. That panel repeated `+ New task`; the request makes this one control name a column instead, using the Other tasks board's existing mechanism.

**The panel changes purpose, not the head or the rights.** `AssignedTasks.kt` supplies `taskKanban`'s `emptyOffer` and `emptyHint` rather than changing its shared default. The empty `.board-empty` panel is still visible only to a reader who holds *Edit any task*. With *Manage buckets* too, it now offers **+ New bucket** and the hint *"Write the first card from the page head, or name a bucket for work that is not on a project."* A card-writer without the bucket chip keeps **+ New task** and the old hint; the page head's **+ New task** is unchanged. A new bucket is a **shared Other-tasks column**, not a project lane, a new status, or a change to a project's board.

**One popup, aimed back at this page.** `newBucketButton` is a GET onto `/app/assigned-tasks` carrying `newbucket=1` and the current `bucket` and `project` hidden fields, with `data-live-target="#bucketModal"`. It reuses `OtherTasks.kt`'s `bucketModal` **add half**: **Add a bucket**, **Bucket name**, **Add bucket**, **Cancel**, and the existing `bucket-modal.js` close/focus mechanism. No rename or delete half is brought onto Assigned. Its form posts `/app/other-buckets` with `back=assigned-tasks` and both filter ids, `data-live-target="#assignedOther"` and `data-live-extra="#bucketModal"`. `#assignedOther` now wraps the lower personal Other-tasks picker, cards or empty line, and `+ Add task`, so the response refreshes everything a new column can affect there without importing the shared board.

**The route keeps the context on every answer.** The bucket POST checks *Manage buckets* again, and denial returns Assigned without creating a column. A refusal answers **200** on Assigned, keeping the typed name, the field error and both filters; success answers **200**, selecting the new bucket in the lower picker, preserving the project filter and closing the popup. `Application.kt`'s `respondAssignedTasks` centralizes GET, bucket-create and refusal rendering so a task-save refusal also retains the new empty-panel offer. The lower cards remain the reader's own: naming a shared column does not widen the personal read. No migration — schema stays **22** — and no CSS changed.

**Closing knows which page owns the key.** `bucket-modal.js` removes `newbucket` on Cancel, Escape or backdrop close. It removes `bucket` only off `/app/assigned-tasks`: on Other tasks that key opens the column menu, but on Assigned it is the personal Bucket filter and must survive. Project, show, edit and new remain untouched. JavaScript changed, so **Ctrl+F5** once; `app.js` is unchanged.

**Verified: 726 tests / 49 classes / 0 failures, errors or skipped.** Three added cases in `AssignedTasksRenderTest`: `an empty board offers a new column to the reader who may name one` pins the offer, GET key, shared popup, return field, live target/companion and script; `a refused new column keeps both filters and its typed name` pins both forms' normalized filter fields, the open popup, typed name and field error; `a card-writer without the bucket right keeps the new-task opener` pins the fallback and absence of the bucket popup. The old shared-board absence test scopes its no-`bucketModal` claim to a reader without the bucket chip. Two overlapping test runs collided in Gradle's report writer; after they had ended, one standalone `test installDist` run succeeded in **5m 39s**, and its XML totals above are the final evidence.

**Final throwaway HTTP: 25 checks, 0 failures.** A fresh empty `%TEMP%` database on **8095**, explicit `PTS_DB_PATH`, demo seed off, nonexistent data file, dry-run mail and internally generated throwaway-only credentials verified the opener, preserved head task button, popup targets, create selecting the new column and aiming `+ Add task` at it, duplicate refusal and retry with both filters, Employee denial without a row, and filtered task-save refusal. A generic opener-form selector initially matched the page head rather than the circled panel; the verifier was corrected to the unique new-bucket control and rerun against a fresh database. Throwaway PID **19740** was stopped before `installDist`.

**Actual scripts: 90 checks, 0 failures.** `scratch/stage124-js-harness.cjs` executes the shipped `app.js` and `bucket-modal.js` through a synthetic DOM, iterable FormData, response parsing, node imports/replacements and MutationObservers. Both the empty-line and personal-card branches verify delegated opener/create/refusal/retry, popup focus, two-block swaps, Cancel/Escape/backdrop, unchanged upstream content and no navigation, native fallback or reload; explicit close-query checks distinguish Assigned's filter from Other tasks' popup key. Browser credential entry remains blocked, so this is HTTP and synthetic-DOM evidence, **not a signed-in visual sign-off**.

**Deployed 2026-11-06.** Earlier Stage 123 PID **3096** was already absent when checked; no claim is made that this pass stopped it. With 8080 absent, backed up in place: `data/pts.pre-stage124-20261106-091529.db` **471,040 bytes**, `-wal` **0**, `-shm` **32,768**. The successful single run installed the **1,962,188-byte** jar, then `deploy/run.bat` was launched detached through PowerShell: live PID **16344**, root **200**, boot says *Schema "main" is up to date. No migration necessary.* The served `/static/js/bucket-modal.js` answers **200** and is byte-identical to both source and packaged entry, including page-aware close. Metadata-only live read-back: schema **22**, **22** accounts, **22** roster rows, **1** project, **2** tasks, **4** roles and **36** role-permission rows, unchanged. **No commit, no push.**

**Never re-propose:** replacing the page head's `+ New task`; offering a column write without *Manage buckets*; using a project lane or status as the new bucket; copying the bucket popup or adding a second create route; bringing the shared backlog board or its `⋮` onto Assigned; losing either filter on refusal; or leaving the popup open after a successful create.
<a id="stage-125"></a>

## Stage 125 — A blank email becomes firstname.surname@murrays.mn (2026-11-09, live, no migration, Kotlin + test)

**The ask:** *"when creating a new user if the emails left empty call it firstname.surname@murrays.mn"*. The app already
invented an address for a blank one, but two things about it were wrong: the domain was the singular `@murray.mn`, and the
local part joined **every** word of the name with dots, so a three-word name produced a three-part address. The ask names
both halves — first word, dot, last word, at the domain Murray actually runs.

**One function, every create path.** `domain/User.kt`'s `autoEmail(name)` is the only generator: it lowercases, splits on
anything that is not a letter, digit or hyphen, keeps **the first and the last word** (one word stands alone, a middle word
is dropped), rejoins them with a single dot and appends `@murrays.mn`; a name with no word in it yields no address at all,
so nobody gets `@murrays.mn` glued to an empty local part. Nothing else was touched, because nothing else generated one:
`UserService.addUser` (Access control's add card and the Settings employee popup), `UserService.updateEmail` (a blank
address on an edit regenerates it), `AuthService.ensureSuperAdmin` (the bootstrap account), `Data.kt`'s roster email,
`AccessRow.displayEmail`, `EmployeeService`'s sheet display, `LegacyNormalizer` and `JsonImporter` all call it. A typed
address is still kept, trimmed and lowercased — the ask is about the blank box, not about overriding a real mailbox.

**Copy that said the old thing.** The add card's Email placeholder is now *"Auto: firstname.surname@murrays.mn"* and its
caption *"A blank email is generated as firstname.surname@murrays.mn."* (`AccessControl.kt`), and `UserService`'s KDoc
bullet names the same form.

**Verified: 734 tests / 50 classes / 0 failures, errors or skipped** (from 726/49). `AutoEmailTest` is new and holds
eight cases — the two-word join with case and spacing normalised, the middle word dropped, the one-word name, a hyphen
surviving while punctuation splits (`Bat-Erdene Tudev` → `bat-erdene.tudev@murrays.mn`, `N. Enkhtuyaa` →
`n.enkhtuyaa@murrays.mn`), a name with no words, a create through `UserService.addUser` storing the generated address, a
typed address kept lowercased, and a blank on edit regenerating it. `SettingsRenderTest`'s one-word roster fixture now
expects `nyambaapurev@murrays.mn`. **20 throwaway HTTP checks, 0 failures** on an empty `%TEMP%` database on
**8095**: the bootstrap account reads `super.admin@murrays.mn`; five creates (two-word, three-word, hyphen, typed
lowercase, one-word) each verified against `users.email` read-only; the generated address prints on the Settings Team
sheet and, as the harness first wrongly asserted, **not** on Access control's People sheet, which has no Email column; the
popup create with a blank address; and an edit that blanks a stored address. **Stage 126 superseded this stage's scope, not
its rule:** the rule was written at create time, so the accounts already in the database kept what the older rule had
stored.

**Deployed 2026-11-09.** Schema stays **22** (no migration), Kotlin only, so a plain reload shows it — **no Ctrl+F5**. The
previous live PID **16344** was stopped, the database copied in place as `data/pts.pre-stage125-20261109-081711.db` (**471040** bytes, `-wal` 0,
`-shm` 32,768), `installDist` produced the **1962888-byte** jar, and the detached relaunch came up as PID **23544**:
`/` **200**, boot *"Schema "main" is up to date. No migration necessary."* Metadata-only read-back: schema 22, **22**
accounts, **22** roster rows, **1** project, **2** tasks, **4** roles,
**36** role-permission rows. HTTP and markup evidence — **no signed-in visual sign-off**. **No commit, no push.**

**Never re-propose:** a second generator beside `autoEmail`; keeping the singular `@murray.mn` "because mail still routes
there"; joining every word of a name; inventing an address for a name with no word in it; overwriting an address somebody
typed at create time; or editing stored rows from the creation rule and calling it the same change.


<a id="stage-126"></a>

## Stage 126 — The accounts already stored get the same address, once, at boot (2026-11-09, live, no migration, Kotlin + test)

**The same ask, restated.** *"when creating a new user if the emails left empty call it firstname.surname@murrays.mn"* came
back after Stage 125 was live, which is what a sheet full of old addresses does when only tomorrow's hires wear the new
one. Reading the real database's aggregates said how wide that was: of **22 accounts, one** already matched the rule,
**19** still carried the singular `@murray.mn`, and none was blank. The ask plainly means *the address this app writes for
a person*, and the creation-only scope was Stage 125's exception, not its rule — so the exception went. The offered choice
was normalise all 22 or leave the rows alone, and normalising all 22 was chosen; a collision check first confirmed the
rewrite was safe (22 names → 22 **distinct** addresses, none of them already somebody else's stored value).

**One rule, one implementation, run once.** `db/migration/UserEmailBackfill.applyOnce()` recomputes every account's
address through the **same `autoEmail`** the create paths call and writes it only where it differs. It is deliberately not
a `V*.sql` file: SQLite has no regex, so the migration would have to be a hand-port of the tokenizer — a second copy of one
rule, free to drift from the first, and it would still have run before any legacy JSON import or bootstrap account existed.
Instead `Application.bootstrapDatabase()` calls it after the import, the demo seed and `ensureSuperAdmin`, and an
`app_settings` row (`email_rule_version = 1`) records that this database has been through it. **One row of data, no schema
change** — schema stays **22**, and the marker means a second boot reads one key and touches nothing.

**What it overwrites, and what that costs.** The pass rewrites any address that is not exactly what the person's own name
produces, **including one an administrator typed** — that is what "all 22" means, and it is one pass, not a standing rule:
an address typed *after* it stays typed (pinned by test and by the third throwaway boot). A name with no word in it yields
no address, so the pass leaves that row's value alone rather than blanking it. Outbound alerts read `users.email`, so the
day this ran, the address every notice is posted to changed for 21 accounts.

**Verified: 738 tests / 51 classes / 0 failures, errors or skipped** (from 734/50). `UserEmailBackfillTest` is new
and holds four cases: two old-rule rows and one uppercase row rewritten to the rule with the marker left behind; a second
`applyOnce` returning 0 while a later-typed foreign address survives; a row the rule already matches not counted as a
rewrite; and a no-word name keeping its stored address. **24 throwaway HTTP checks, 0 failures** across **three boots
of one database**: seed four accounts on the old form over HTTP, clear the marker to simulate a pre-Stage-126 file, boot
again, then read the throwaway back — every address on the rule, no `@murray.mn` left, the boot log carrying
*Company emails: 4 account address(es) rewritten…*, the Settings Team sheet printing the rewritten address and no longer
the old one, Access control's sheet intact; a fifth account typed with a foreign address on the second boot, and a third
boot proving no second pass ran and that address untouched.

**Deployed 2026-11-09.** No migration, schema **22**, Kotlin only → **no Ctrl+F5**. Live PID **23544** stopped with 8080
confirmed free; `test installDist` run standalone; database backed up in place as `data/pts.pre-stage126-20261109-092654.db` (**471040** bytes, `-wal`
0, `-shm` 32768) — the rewrite is reversible only from that file; jar **1965925** bytes; detached relaunch → PID
**20752**, `/` **200**, boot *"No migration necessary"* and the rewrite line naming the accounts it changed. Read-back
after the pass: **22** accounts, **22** roster rows, **1** project, **2**
tasks, **4** roles, **36** role-permission rows, schema **22**, and every account's
address now the one its own name produces. **No commit, no push.**

**Never re-propose:** a regex port of the rule inside a SQL migration; re-running the pass on every boot (it would quietly
overwrite a typed address forever); a per-account exception list; blanking an address because the name has no word in it;
rewriting the roster's or a colleague's *display* name to make a nicer address; or claiming the sheet looks right from a
boot log — the visible half is the Team sheet's Email column, which only a signed-in reader can see.

