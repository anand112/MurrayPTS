# Murray PTS — Target Architecture (v2)

This document describes the restructured layout that Stages 2–9 built, and records what actually
shipped in each stage. Stages 1–9 are complete and the app is launch-ready (see README →
Launch checklist); the "as built" notes below override the original plan wherever they differ,
and the plan sections that were never taken are labelled. Stage 10 (due-date alert emails) is a
post-launch feature added on top of that and is recorded in its own section below.

Note: the package layout below is the *target* from Stage 1. The shipped tree collapsed it
deliberately — the one-file-per-entity `db/tables/`, `web/route/`, `web/view/` and `util/`
packages did not earn their keep for an app this size, so tables live in a single `db/Tables.kt`,
repositories in three grouped files, services in `service/`, and the page views stayed as
top-level `<Page>.kt` files next to `Application.kt`. Run `find src/main -name '*.kt'` for the
truth.

## Goals

1. **Layered architecture**: routes → services → repositories → DB. No route touches SQL directly. No repository renders HTML.
2. **Real persistence**: SQLite via Exposed ORM + Flyway migrations, replacing single-file JSON.
3. **Hardened auth**: bcrypt password hashes, DB-backed session tokens (as built — see Security → Sessions, not the signed-cookie `Sessions` plugin originally assumed), first-login password change enforced, per-route guards through one `call.requireSession()` helper (as built — a helper on `ApplicationCall`, not a Ktor plugin).
4. **Preserved behavior**: every current URL, cookie name, flash-banner protocol, and kanban drag-drop endpoint keeps working.
5. **Understandable later**: consistent package layout, one file per feature area, `ARCHITECTURE.md` + `MIGRATION.md` kept current.

## Package layout

```
src/main/kotlin/mn/murray/pts/
├── Application.kt                 ← slim: installs plugins, mounts route modules
├── config/
│   ├── AppConfig.kt               ← env + .env loader (port, host, dbPath, sessionSecret, adminBootstrap)
│   └── Logging.kt                 ← logback config hook
├── db/
│   ├── DatabaseFactory.kt         ← Hikari + Exposed connect, SQLite file
│   ├── tables/                    ← one Exposed Table object per entity
│   │   ├── Users.kt  Projects.kt  Tasks.kt  TeamMembers.kt
│   │   ├── Positions.kt  Forecasts.kt  Lessons.kt  TodoLists.kt
│   │   ├── TodoItems.kt  Pins.kt  Buckets.kt  Sessions.kt
│   └── migration/
│       ├── V1__init.sql           ← Flyway baseline
│       └── JsonImporter.kt        ← one-shot: reads data/pts-data.json → DB, renames file to .migrated
├── domain/                        ← pure Kotlin data classes (no Ktor, no Exposed)
│   ├── Project.kt  Task.kt  Member.kt  User.kt  Position.kt
│   ├── Forecast.kt  Lesson.kt  Todo.kt  Pin.kt
│   └── enums.kt                   ← Status, Priority, Access, PasswordStatus
├── repository/                    ← Exposed queries, returns domain objects
│   ├── ProjectRepository.kt  TaskRepository.kt  TeamRepository.kt
│   ├── UserRepository.kt  PositionRepository.kt  TodoRepository.kt
│   ├── ForecastRepository.kt  LessonRepository.kt  PinRepository.kt
│   └── BucketRepository.kt
├── service/                       ← business rules, transactions, validation
│   ├── ProjectService.kt          ← code generation, assignees, pinning
│   ├── TaskService.kt             ← status transitions, ownership rules
│   ├── TeamService.kt             ← roster, member↔user linking
│   ├── UserService.kt             ← bcrypt hash/verify, role checks, email defaults
│   ├── TodoService.kt             ← list ordering, ownership
│   └── PositionService.kt
├── security/
│   ├── PasswordHasher.kt          ← jBCrypt wrapper
│   ├── SessionPrincipal.kt        ← @Serializable data class stored in Ktor session
│   ├── AuthPlugin.kt              ← install(Sessions) + authenticate("user") blocks
│   ├── RoleGuard.kt               ← requireRole("Admin"), requireManagerOrOwner(taskId)
│   ├── Csrf.kt                    ← token filter for POST/PUT/DELETE
│   └── SecurityHeaders.kt         ← HSTS, X-Frame-Options, CSP, Referrer-Policy
├── web/
│   ├── plugin/
│   │   ├── Routing.kt             ← installs all route modules
│   │   ├── StatusPages.kt         ← 404/500 pages
│   │   ├── CallLogging.kt
│   │   ├── Compression.kt
│   │   └── StaticFiles.kt         ← serves /static from resources/static
│   ├── route/                     ← one file per feature; route() extensions on Application
│   │   ├── AuthRoutes.kt          ← GET /, POST /login, GET /logout, GET /app
│   │   ├── ProjectRoutes.kt
│   │   ├── TaskRoutes.kt          ← assigned + other + kanban status
│   │   ├── TeamRoutes.kt
│   │   ├── CalendarRoutes.kt
│   │   ├── ForecastRoutes.kt
│   │   ├── LessonRoutes.kt
│   │   ├── TodoRoutes.kt
│   │   ├── AccessControlRoutes.kt
│   │   ├── PositionRoutes.kt
│   │   └── SettingsRoutes.kt
│   └── view/                      ← kotlinx.html builders, no logic
│       ├── Layout.kt              ← shared <head>, nav, flash banner, theme toggle
│       ├── LoginView.kt
│       ├── DashboardView.kt  ProjectsView.kt  ProjectDetailView.kt
│       ├── AssignedTasksView.kt  OtherTasksView.kt  TeamView.kt
│       ├── CalendarView.kt  ForecastsView.kt  LessonsView.kt
│       ├── TodoView.kt  AccessControlView.kt  SettingsView.kt
│       └── Fragments.kt           ← taskChip, projectRow, memberPopup, etc.
└── util/
    ├── Format.kt                  ← initials, formatDue, MONTHS, taskCode
    ├── Colors.kt                  ← prioClass, taskColor, chipClass
    └── Referrer.kt                ← safe Referrer redirect helper
```

`src/main/resources/`
```
├── static/                        ← served by staticResources("/static", "static") (Stage 7, as built)
│   ├── css/base.css               ← extracted from PAGE_CSS (login page + shared design tokens)
│   ├── css/app.css                ← extracted from APP_CSS (app shell + page components)
│   ├── js/theme.js                ← persisted theme/accent (now a real IIFE — see Stage 7 progress)
│   ├── js/app.js                  ← delegated [data-autosubmit] / [data-confirm] handlers
│   ├── js/kanban.js               ← Assigned Tasks board drag-drop + move buttons
│   ├── js/todo.js                 ← to-do board reorder + date-field save behaviour
│   ├── js/team.js                 ← team member popup (reads the memberDetailData JSON island)
│   ├── js/settings-accounts.js    ← settings workload detail sub-row toggling
│   ├── js/calendar-modal.js       ← calendar task-detail modal (delegated, no globals)
│   ├── js/access-control.js       ← positions add/update/delete via fetch <tr> fragments
│   └── assets/mms-logo.png        ← moved from static/; 301 from the old path
├── db/migration/V1__init.sql      ← Flyway picks up from classpath
├── logback.xml
└── application.conf               ← optional HOCON for Ktor
```

## Data model (SQLite)

Real foreign keys replace the current string-name joins. **This is a semantic change** — flag if you want to keep name-based joins instead.

| Table | Key columns | Notes |
|---|---|---|
| `users` | `employee_number` PK, `name`, `password_hash`, `access` (Viewer/Manager/Admin), `email`, `position_id` FK NULL, `is_super_admin` BOOL, `created_at`, `updated_at` | Replaces `AccessRow`. EMP0000 seeded with `is_super_admin=true`. |
| `team_members` | `employee_number` PK, `name`, `department`, `position` | 23-person canonical roster. |
| `positions` | `id` PK, `name` UNIQUE, `permission_level` INT, `description` | Catalog only, not enforced (matches current behavior). |
| `projects` | `id` PK AUTOINCREMENT, `code` UNIQUE, `name`, `status`, `progress` INT, `manager_emp_no` FK→team_members, `due` TEXT, `type`, `created_at` | Manager stored as FK, not name. |
| `project_assignees` | `project_id` FK, `employee_number` FK, PK(`project_id`,`employee_number`) | Replaces `List<String>`. |
| `tasks` | `id` PK, `title`, `project_id` FK NULL, `due`, `priority`, `assignee_emp_no` FK NULL, `status`, `bucket`, `started`, `kind` (`ASSIGNED`/`OTHER`), `display_code` | Merges assignedTasks + otherTasks (they're the same shape). |
| `buckets` | `id` PK, `name`, `position` INT | Kanban columns for other-tasks. |
| `forecasts` | `id` PK, `project_id` FK, `period`, `hours`, `note` | Read-only seed. |
| `lessons` | `id` PK, `title`, `project_id` FK NULL, `summary` | Read-only seed. |
| `todo_lists` | `id` PK, `name`, `done` BOOL, `owner_emp_no` FK, `due`, `position` INT | Explicit `position` column replaces array-order. |
| `todo_items` | `id` PK, `title`, `done` BOOL, `list_id` FK, `owner_emp_no` FK, `due`, `position` INT | |
| `pins` | `employee_number` FK, `project_id` FK, PK(both) | Replaces `Map<empNo, List<projectId>>`. |
| `sessions` | `id` PK (UUID), `employee_number` FK, `created_at`, `expires_at`, `user_agent`, `ip` | Persistent sessions survive restarts. Ktor Sessions plugin signs the cookie; DB row enables admin revocation. |
| `due_date_alerts` | `id` PK, `kind` (`PROJECT`/`TASK`), `item_id`, `recipient_emp_no` FK, `alert_date` (UTC `yyyy-MM-dd`), `subject`, `body`, `due`, `delivered`, `channel`, `error`, `sent_at`, UNIQUE(`kind`,`item_id`,`recipient_emp_no`,`alert_date`) | Stage 10 ledger of alert *attempts*; the UNIQUE key is the once-per-day rule. See Stage 10. |

Migrations live in `src/main/resources/db/migration/` — `V1__init.sql` and `V2__due_date_alerts.sql`
(Stage 10, one `CREATE TABLE` + index, no existing row touched). Flyway runs at startup.

## Security

| Concern | Approach |
|---|---|
| Passwords | jBCrypt cost 12 (`security/PasswordHasher`). On first-run import, existing plaintext passwords are hashed and the JSON is renamed `.migrated`. `AuthService.changePassword` rehashes and revokes all of the user's sessions. |
| Sessions | **Opaque-token model (as built), not the signed-cookie `Sessions` plugin.** `PTS_SID` carries a random UUID; the authoritative record lives in the `sessions` table (`repository/SessionRepository`): employee_number FK, created_at, expires_at, user_agent, ip. Cookie stays `httpOnly`, name **preserved as `PTS_SID`**, 12h expiry from `AppConfig.sessionTtlHours` — **absolute, stamped at login** (`create` writes `expires_at`; nothing slides it on activity). DB rows survive restart and allow admin revocation (`revokeAllFor`). Expiry is enforced at read (`findValid`) and swept by `purgeExpired`. Chosen over the Ktor `Sessions` signed-cookie plugin because it keeps revocation and audit server-side and matches the legacy token-in-cookie shape. |
| Authorization | `security/Authorization` (RoleGuard). Effective role resolves **super-admin → Admin, else the linked position's `permissionLevel`, else the account `access`, else Viewer** (`security/Role` ordered enum). This is the v2 change: `permissionLevel` is now enforced. `canManage`/`canAdmin`/`isManagerOrAdmin` replace the ~30 inline `if (access in MANAGE_ROLES)` checks (route call-sites migrated in Stage 6). |
| CSRF | Stateless session-bound token: `security/Csrf` = base64url(HMAC-SHA256(secret, sessionId)), validated in constant time. Rotates automatically with the session; no extra storage. **Not wired into any route (known gap, deliberately out of launch scope).** POSTs currently rely on the `SameSite=Lax` session cookie as the CSRF defence; `Csrf` is implemented and unit-tested (`CsrfTest`) and the production secret requirement in `AppConfig.validate()` exists precisely so turning it on later needs no config work. |
| First login | `users.must_change_password` is enforced at the route level: `call.requireSession()` (in `Application.kt`) redirects a flagged account to `/app/settings` for every path except the Settings allowlist (`/app/settings`, `/app/settings/password`), so the user can only set a new password. Settings renders the reason inline in the Change Password card. Flagged by `AuthService.ensureSuperAdmin()` when no `PTS_ADMIN_PASSWORD` was configured, by `JsonImporter` for every legacy account that had no explicit password, and by `UserService.addUser`. Saving a password through `AuthService.changePassword` clears the flag and re-issues the session, so the user stays signed in. |
| Headers | `web/plugin/SecurityHeaders` (installed): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`, `Permissions-Policy`, `Content-Security-Policy` (`default-src 'self'; script-src 'self'; style-src 'self' + style-src-attr 'unsafe-inline'` — tightened in Stage 7 once the assets were extracted, see "Stage 7 progress"), `Strict-Transport-Security` in production. |
| Secrets | `.env` (gitignored) + `.env.example`. `AppConfig` reads system property → env → `.env` → default. `AppConfig.validate()` refuses production boot with the default/short session secret. Secret never logged (`summary()` masks it). |
| Rate limit | `security/LoginRateLimiter` — in-memory sliding window on `POST /login`, bounds from `AppConfig.loginMaxAttempts`/`loginWindowSeconds` (default 5/60s). The key is the client IP from `X-Forwarded-For` **only when `PTS_TRUST_PROXY=true`**; otherwise it is the socket peer, because on a directly exposed port a forged header would otherwise be a free way to dodge the limiter. Verified live both ways. |
| Outbound mail (Stage 10) | `PTS_SMTP_*` credentials come from the same env/`.env` chain and are never logged — `Mailer` logs recipient + subject (plus the body, in log-only mode) and `AppConfig.summary()` prints host/port/sender, not the password. `AppConfig.validate()` does not gate on them (log-only is a valid mode), and `Mailer.send` never throws so a bad credential produces a ledger row with the reason, not a crash. Alerts are plain-text, sent over STARTTLS by default (`PTS_SMTP_SECURITY=ssl` for implicit TLS on 465, `none` only for localhost testing). No inbound mail, so nothing here parses untrusted input. |

### Stage 5 status (security layer)

Built, unit-tested and wired: `Role`, `Authorization`, `SessionRepository`, `LoginRateLimiter`, `Csrf`, `SecurityHeaders` plugin, `AuthService` (bcrypt verify + DB session lifecycle + `ensureSuperAdmin` bootstrap). The **headers plugin, login rate-limiting and super-admin bootstrap are live in `Application.kt` now**. 16 tests pass (role resolution, throttle window/recovery, CSRF forgery rejection, and an end-to-end `AuthServiceTest` against a real SQLite file covering bcrypt login, session resolve/revoke, revoke-on-password-change, and expiry).

**Deferred to Stage 6 (deliberate):** flipping `POST /login` from the legacy plaintext `DemoData` check to `AuthService.authenticate`, switching `currentSession()` from the in-memory `SessionStore` to `SessionRepository`, replacing inline `MANAGE_ROLES` checks with `Authorization`, and rendering/validating CSRF tokens. Reason: those routes still read/write user data through the legacy `JsonStore`. Moving auth onto the DB before the user-management routes move would create a split-brain (a user added or edited via the UI would exist in JSON but not be able to log in). Auth and the user-data persistence flip together in Stage 6 so the app is never internally inconsistent. The full security layer is already implemented and tested — Stage 6 only swaps the call-sites over.


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
- **6a Projects + pins** — `service/ProjectService` wraps `ProjectRepository` + `PinRepository` and owns the legacy rules (`PROJECT_TYPES`/`PROJECT_STATUSES` whitelists with fallbacks, `SIT|MFG|RMS|LAB-NN` code generation, manager **name→empNo** resolution, roster membership guards). `DemoData.projects` and the `pinnedIds()` helper now read from the DB; the create / manager / assignees-add / assignees-remove / pin routes were converted off `JsonStore.update`. Projects now store manager and assignees as real FKs.
- **6b Tasks + buckets** — `service/TaskService` wraps `TaskRepository` + `BucketRepository`, discriminating the single `TaskItem` shape by the DB `kind` column (`ASSIGNED` | `OTHER`). It owns priority/status whitelists, assignee roster checks, project-name→id resolution (with the stricter `/app/team/assign` "project must exist" guard vs. the lenient `/app/assigned-tasks` one), and the kanban "manager-or-own-task" authorization for `POST /app/tasks/status`. `DemoData.assignedTasks` / `.otherTasks` / `.buckets` read from the DB.
- **6c Todo** — `service/TodoService` wraps `TodoRepository`; every mutation re-checks row ownership against the acting employee (the invariant the legacy handlers enforced inline). List delete cascades to its items via the FK. `POST /app/todo/lists/reorder` still accepts the `order=` CSV (ids not owned by the caller are ignored by the owner-scoped update); `move?dir=up|down` preserved. `DemoData.todos` / `.todoLists` read all-owner rows so the existing views' `filter { owner == session }` is unchanged.
- **6d Forecasts / lessons + legacy removal** — `DemoData.forecasts` / `.lessons` now read `ForecastRepository` / `LessonRepository`. The legacy `JsonStore` (`Store.kt`) and the unused `MANAGE_ROLES` set were **deleted**; `PtsData` / `AccessRow` survive only as the importer's decode target. `DemoData.seed()` remains solely as the demo dataset the importer's normalizer borrows its default position catalog from — nothing auto-seeds the live DB.
- Live boot verification (throwaway DB + a copy of `pts-data.json.example`; the real `data/pts-data.json` was confirmed byte-identical afterwards): import of 6 users / 16 projects / 5 tasks / 23 team / 3 todo lists; login (302 + `PTS_SID`); all ten `/app/*` pages render 200; project create generated `MFG-03`; manager name round-tripped through the FK; assignee add/remove; todo list+item create, toggle, CSV reorder; assigned-task create; kanban status transitions with the invalid-status case correctly rejected; bucket + other-task create; pin toggle. All 18 unit/integration tests pass.

## Behavior preservation checklist

Every item below must still work after Stage 9:

- [x] `PTS_SID` cookie name
- [x] Login at `/`, `POST /login`, `GET /logout`, `/app` → `/app/projects`
- [x] All 35 route paths exactly as today
- [x] `POST /app/tasks/status` form-encoded `id`+`status`, Referrer redirect
- [x] `POST /app/todo/lists/reorder` with `order=csv`
- [x] Positions endpoints return HTML `<tr>` fragments when `X-Requested-With: fetch`, 204 on delete
- [x] `?type=` filter on projects, `?pw=PasswordStatus&who=emp` flash banner
- [x] First-run seed data — now gated behind `PTS_SEED_DEMO` (opt-in seeder wired in Stage 8: `db/migration/DemoSeeder`), EMP0000 super-admin always ensured ✓, `MIN_PASSWORD_LENGTH=4` ✓
- [x] Password defaults to employee number for legacy users (only during import; new users must set one)
- [x] Auto-email `first.last@murray.mn`
- [x] Project code generation `PREFIX-NN` (verified `MFG-03`), task display code `T-%05d`
- [x] Dark mode + accent toggle persisted in `localStorage` keys `pts-theme` / `pts-accent` (verified in a real browser in Stage 7 — see below; the old inline `THEME_JS` was dead code and never ran)
- [x] Per-user pins, per-user todo ownership and list ordering
- [x] `pts.port` / `PTS_PORT` (8080), `pts.host` / `PTS_HOST` (0.0.0.0), LAN-address startup banner
- [x] Logo served from `/static/assets/mms-logo.png`, with a 301 from the legacy `/static/mms-logo.png`
- [x] Read-only Forecasts and Lessons pages with seed content (now DB-backed)
- [x] Team member-popup JSON payload shape unchanged (`/app/team` still emits the `memberDetailData` island, keys = employee number, same fields; verified live)

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
number" hint.

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

## Migration plan (JSON → SQLite)

`JsonImporter` runs once at startup if `users` table is empty AND `data/pts-data.json` exists:

1. Read JSON via existing `PtsData` kotlinx-serialization classes (kept in `db/migration/legacy/`).
2. Replay the 11 `migrate()` steps in-memory so the importer sees normalized data.
3. Insert in FK-safe order: positions → team_members → users (hash plaintext passwords) → projects → project_assignees → tasks → buckets → forecasts → lessons → todo_lists → todo_items → pins.
4. Rename `data/pts-data.json` → `data/pts-data.json.migrated-<timestamp>`.
5. Log a summary: counts per table, any skipped rows.

If the JSON is corrupt, the importer logs and falls back to `DemoData.seed()` (same as today) rather than silently overwriting.

## Testing strategy (Stage 9)

As planned vs. as shipped (24 tests then, all against real SQLite files — the app is small enough
that a fake DB layer would test less than it saved; Stage 10 added 14 more for the alert rules and
the delivery test, bringing the suite to 38 in 10 classes, same real-DB approach):

| Planned | Shipped |
|---|---|
| `JsonImporterTest` | ✓ — imports `pts-data.json.example`, checks counts and that EMP0000's password is hashed |
| `AuthFlowTest` (Ktor `testApplication`) | replaced by `AuthServiceTest` + `MustChangePasswordTest` + `FreshDatabaseBootstrapTest` — the same paths (bcrypt login, resolve/revoke, revoke-on-change, expiry, the first-login gate, empty-DB bootstrap) driven straight through the services against a real DB, which is where the launch-blocking FK bug actually lived |
| `ProjectRepositoryTest` | not written — project rules are covered indirectly by the Stage 6 live boots |
| `TaskStatusRouteTest` | not written — the whitelist and manager-or-owner rules were verified live (invalid status rejected) rather than by a committed test |
| — | added as needed: `MigrationSmokeTest` (Flyway 10 on SQLite + FK enforcement + idempotent migrate), `AuthorizationTest`, `RoleTest`, `CsrfTest`, `LoginRateLimiterTest` |

Remaining test debt worth knowing: no test exercises the HTTP route layer end-to-end, so a route
that loses its `requireSession()` guard would only be caught by the manual checklist — including the
three Stage 10 alert routes, whose 403 / 302 / 400 / "already sent today" behaviour was verified live
but is not covered by a committed test.

## Out of scope (flag if you want any of these)

- ~~HTTPS termination~~ — the app still terminates nothing itself, but the launch scope added the
  proxy half: `deploy/nginx.conf`, the compose topology, `PTS_TRUST_PROXY`, and the README's
  TLS/bind/cookie matrix. A certificate and DNS are still the operator's job.
- Wiring `Csrf` into the forms and fetch handlers (implemented, tested, unused)
- Multi-tenancy
- Audit log beyond the `sessions` table — the Stage 10 `due_date_alerts` ledger is an audit trail for
  *alerts*, not for user actions
- Email/password reset flow (Stage 10 added the outbound SMTP transport for alerts; nothing in auth
  uses email, and a reset still means an admin setting the password)
- A UI for the alert ledger — it is read through the admin routes and SQLite today
- API versioning or a JSON API for a future SPA
- Replacing kotlinx.html with a template engine
- Changing the domain semantics (string-name joins → FKs is the one exception, called out above)

## Open questions before Stage 2 — how they resolved

1. **Frontend A or B?** → A, in full: everything external under `/static`, CSP without
   `unsafe-inline` for scripts (Stage 7).
2. **FK-based joins OK?** → yes, and they paid for themselves: the fresh-DB FK crash (Stage 8) is
   only possible because the schema is real relational. Renames now cascade correctly; the importer
   does the name→id lookup pass.
3. **Keep `DemoData.seed()` on empty DB?** → no. Empty DB stays empty; seeding is explicit
   `PTS_SEED_DEMO=true` and refuses to touch a populated database (Stage 8).
4. **Positions enforcement?** → wired: `security/Authorization` resolves the position's
   `permissionLevel` into the effective role, so `Position.permissionLevel` is now enforced
   (Stages 5–6).
