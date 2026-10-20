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
│   ├── js/kanban.js               ← Assigned Tasks board drag-drop (move buttons are an
│   │                                 assignee-only affordance now — see Stage 14)
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
| `projects` | `id` PK AUTOINCREMENT, `code` UNIQUE, `name`, `status`, `progress` INT, `manager_emp_no` FK→team_members, `due` TEXT (**the end date** — V3), `type` (one of nine category labels — V3), `client`, `start_date`, `revenue` REAL, `created_at` | Manager stored as FK, not name. `code` leads with the category letter (`S-01`). Client/start/revenue added by V3; `due` kept instead of an `end_date` column because the Calendar and the alerts already key off it. See Stage 12. |
| `project_assignees` | `project_id` FK, `employee_number` FK, PK(`project_id`,`employee_number`) | Replaces `List<String>`. |
| `tasks` | `id` PK, `title`, `project_id` FK NULL, `due`, `priority`, `assignee_emp_no` FK NULL, `status`, `bucket`, `project_bucket_id` FK NULL (V7), `started`, `kind` (`ASSIGNED`/`OTHER`), `display_code` | Merges assignedTasks + otherTasks (they're the same shape). |
| `buckets` | `id` PK, `name`, `position` INT | Kanban columns for other-tasks. |
| `project_buckets` | `id` PK, `project_id` FK cascade, `name`, `position` INT | V7, Stage 30: one **project's** lanes — the phases its own cards are filed under. Separate from `buckets` on purpose (see Stage 30). `tasks.project_bucket_id` is `ON DELETE SET NULL`, so removing a lane un-files its cards rather than deleting them. |
| `forecasts` | `id` PK, `project_id` FK, `period`, `hours`, `note` | V1's legacy row set: hours per project per period, kept for the importer. Since Stage 43 **nothing reads it** — the Forecasts page is `project_forecasts` now, and the sidebar pill counts projects instead of these rows. |
| `lessons` | `id` PK, `title`, `project_id` FK NULL, `summary`, `lesson`, `employee_number` FK NULL, `task_id` FK NULL, `logged_on` (`yyyy-MM-dd`, `''` = undated), `discussed` BOOL, `archived` BOOL (all six since V9) | V1's row set widened in place by V9, Stage 44: the Lesson learnt sheet's own record — the one text the page prints, who logged it, which card it came from, the day, and its two states. `title`/`summary` stay (`title` is `NOT NULL`, no default) and the page prints `lesson`; the repository falls back to the old pair only if `lesson` came through blank; the backfill joins each old pair into `lesson` once, guarded by `WHERE lesson = ''`. All three FKs are `ON DELETE SET NULL`, so a lesson outlives the account, the card and the project it names — an unowned row is management's to tidy. See Stage 44. |
| `todo_lists` | `id` PK, `name`, `done` BOOL, `owner_emp_no` FK, `due`, `position` INT | Explicit `position` column replaces array-order. |
| `todo_items` | `id` PK, `title`, `done` BOOL, `list_id` FK, `owner_emp_no` FK, `due`, `position` INT | |
| `pins` | `employee_number` FK, `project_id` FK, PK(both) | Replaces `Map<empNo, List<projectId>>`. |
| `sessions` | `id` PK (UUID), `employee_number` FK, `created_at`, `expires_at`, `user_agent`, `ip` | Persistent sessions survive restarts. Ktor Sessions plugin signs the cookie; DB row enables admin revocation. |
| `due_date_alerts` | `id` PK, `kind` (`PROJECT`/`TASK`), `item_id`, `recipient_emp_no` FK, `alert_date` (UTC `yyyy-MM-dd`), `subject`, `body`, `due`, `delivered`, `channel`, `error`, `sent_at`, UNIQUE(`kind`,`item_id`,`recipient_emp_no`,`alert_date`) | Stage 10 ledger of alert *attempts*; the UNIQUE key is the once-per-day rule. See Stage 10. |
| `notifications` | `id` PK, `recipient_emp_no` FK, `kind` (`TASK_ASSIGNED`/`PROJECT_MANAGER`/`DUE_DATE`), `item_kind`, `item_id`, `dedupe_day`, `title`, `body`, `link`, `actor_emp_no` (**no FK, on purpose**), `mail_status`, `created_at`, `read_at` (`''` = unread), UNIQUE(`recipient_emp_no`,`kind`,`item_kind`,`item_id`,`dedupe_day`) | Stage 13 inbox behind the bell. The same once-per-day shape as the ledger, and the same claim-before-deliver order, so a retry cannot mail twice. See Stage 13. |
| `task_checklist_items` | `id` PK, `task_id` FK cascade, `label`, `done` BOOL, `position` INT | V5, Stage 19: the plan under a card. One row per step so a tick is a single-row update; the list follows the assignment, not the role. See Stage 19, 22 and 23. Since Stage 41 the rows are unchanged and only the **popup** draws them — a card prints the percentage they add up to and no list. |
| `task_notes` | `id` PK, `task_id` FK cascade, `body`, `author_emp_no` (**no FK, on purpose**), `position` INT, `created` (UTC `yyyy-MM-dd`) | V6, Stage 23: the running log under a card, newest first. One row per note so two people can write at once, and the entry still names its author after that account is deleted. See Stage 23. |
| `project_forecasts` | `project_id` + `month` (`yyyy-MM`) composite key, `forecast_amount`, `invoiced_amount`, `invoiced_month` (`''` = the row's own month), `invoice_status` | V8, Stage 43: the monthly money ledger the Forecasts grid reads — one row per project per month, and the only stored figures on that page. `previous`/`remaining` are summed and subtracted from these rows on every read, never written, because a stored derived column is a number the page could be edited into disagreeing with. A line that says nothing is deleted rather than stored as zeroes. See Stage 43. |

Migrations live in `src/main/resources/db/migration/` — `V1__init.sql`, `V2__due_date_alerts.sql`
(Stage 10, one `CREATE TABLE` + index, no existing row touched) and
`V3__project_categories.sql` (Stage 12, three added columns plus the first migration that **rewrites
rows**: old category labels and `SIT-/MFG-/RMS-/LAB-` codes are mapped onto the nine letter-coded
categories). `V4__notifications.sql` (Stage 13, one `CREATE TABLE` + two indexes, no row touched) is the
bell's inbox. `V5__task_checklists.sql` (Stage 19) and `V6__task_notes.sql` (Stage 23) each add one child
table of `tasks`, both `ON DELETE CASCADE`, both additive. `V7__project_task_buckets.sql` (Stage 30) adds the
per-project lane table, one nullable FK column on `tasks` (`ON DELETE SET NULL`), and **backfills** — every project
already in the database is given the three default lanes, while its cards stay unfiled. `V8__forecast_ledger.sql`
(Stage 43) adds `project_forecasts`, the monthly money ledger the Forecasts grid reads: one row per project per
month, and it writes nothing into it, so no existing row is involved and no backfill is needed.
`V9__lesson_record.sql` (Stage 44) is the first migration since V3 to touch an existing table's rows, and it is
gentler than V3 was: six columns on `lessons`, all with defaults, three indexes, and one `UPDATE` that copies each
legacy row's `title` and `summary` into the new `lesson` column — no old value is changed, and the guard means the
backfill cannot re-run over a lesson somebody has since typed. Flyway runs at startup.

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
- **6a Projects + pins** — `service/ProjectService` wraps `ProjectRepository` + `PinRepository` and owns the legacy rules (`PROJECT_TYPES`/`PROJECT_STATUSES` whitelists with fallbacks, `SIT|MFG|RMS|LAB-NN` code generation *(the four-category scheme and its auto-generated codes — both replaced in Stage 12)*, manager **name→empNo** resolution, roster membership guards). `DemoData.projects` and the `pinnedIds()` helper now read from the DB; the create / manager / assignees-add / assignees-remove / pin routes were converted off `JsonStore.update`. Projects now store manager and assignees as real FKs.
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
- [x] Project code generation `PREFIX-NN` (verified `MFG-03` at the time — **since Stage 12 the prefix is
  one letter and the number is typed, only suggested**: `M-03`), task display code `T-%05d`
- [x] Dark mode + accent toggle persisted in `localStorage` keys `pts-theme` / `pts-accent` (verified in a real browser in Stage 7 — see below; the old inline `THEME_JS` was dead code and never ran)
- [x] Per-user pins, per-user todo ownership and list ordering
- [x] `pts.port` / `PTS_PORT` (8080), `pts.host` / `PTS_HOST` (0.0.0.0), LAN-address startup banner
- [x] Logo served from `/static/assets/mms-logo.png`, with a 301 from the legacy `/static/mms-logo.png`
- [x] Read-only Forecasts and Lessons pages with seed content (now DB-backed) — **both superseded**: Stage 43
  rebuilt Forecasts as the monthly money grid on `project_forecasts` (V8) and Stage 44 rebuilt Lesson learnt as the
  dated, per-person sheet on the widened `lessons` table (V9)
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
server-side (`ProjectService.DEFAULT_STATUS`), so it holds even if the select is bypassed.

**CSP constraints shaped the modal.** The open state is server-rendered (`style="display:block"`, allowed
by `style-src-attr 'unsafe-inline'`), so a rejected submit comes back with the popup already open; the
behaviour is `/static/js/project-modal.js` (`script-src 'self'`, driven by `data-project-modal-open`,
`data-modal-close`, and per-option `data-suggest`/`data-letter`). A category change replaces the offered
number only while it is still untouched (`data-autofilled`), so a hand-typed number survives switching
categories.

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
click-then-pick is a taste call the user can reverse by dropping the `&& !canManage`.

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
- **Percentages, not pixels,** because this project has no `@media` queries at all — 100% of the card is
  the only responsive mechanism it has, and `overflow-wrap: anywhere` on the cells makes a narrow window
  wrap a long client name instead of overflowing the panel.
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

## Stage 17 — project search bar with status and manager sort pickers (2026-10-13, live)

"Add search bar for projects and beside it the user able to sort the projects by status and by project
managers." Four scope questions, four answers, and one of them overrode my recommendation: the user wants
**two separate pickers** — a dedicated Status sort and a dedicated Manager sort sitting beside the search
box — not the single "Sort by" dropdown I proposed. Status sorts in **working order** (Not started →
Ongoing → Blocked → On hold → Completed), which is not `PROJECT_STATUSES`; that list is the *picker* order
for creating a project, so `Project.kt` now also exports `PROJECT_STATUS_ORDER` and `statusRank()`, with an
unrecognised status ranking last rather than at zero. Search is scoped **within the current tab**, and the
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
revenue, and the kanban's missing minimum column width on narrow windows.

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
`?edit=<somebody else's card>` renders an ordinary board. `updateAssigned` takes the four fields a
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
so a colleague's card is not merely inert, it has no click target at all and no tab stop.

**Losing the status dropdown costs nothing, and the arrows stay.** A manager has two other ways to move a
card: drag it between columns, or change **Status** in the editor they just opened — a column is a field of
the form, not a separate decision. A non-manager's Status field is greyed (Stage 23's permission split), so
their cards keep the `←`/`→` buttons, which post `/app/tasks/status` from `kanban.js` and reload as they
always have. That is also why a board swap is still off the table: `kanban.js` binds `dragstart`/`dragover`
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
the board's `kmove-btn` arrows so status is changeable from either place.

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
`.step-row` / `.step-mark` CSS went with it rather than staying as an untestable style. `viewerEmpNo` is never read
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
write the plan. The add line is pinned as **a field and no button**, with exactly one writable field in its form —
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

## Stage 46 — Settings is the Team sheet the picture shows (2026-10-18, live, no migration)

**The ask.** *"make settigns like this"* with `settings-pts.png`: a Settings page whose visible section is
**Team** — an "Add a new employee account…" card with a dark-green `+ Add employee`, a toolbar (search "Search
name, email, employee #…", **All positions**, **All roles**, "22 of 22 shown" pushed to the right edge), then a
flat roster — **NAME / EMPLOYEE # / POSITION / DEPARTMENT / ROLE / EMAIL / OPEN TASKS**, each header a sort
control, each row carrying avatars, a role pill and a red ✎ beside a grey 🗑, and the reader's own row wearing a
green `YOU` pill instead of a pressable bin. Four scope answers fixed the shape before anything was written:
**Settings only, the Team page stays as it is**; the personal card and **Change Password** stay below Team in that
order, and the workload drill-down the old table printed is replaced by the OPEN TASKS column; `+ Add employee`
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
`POST /app/settings/password` is the route it has always been. The eight-column grid is one shared `table.team-sheet`
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

## Stage 47 — Other tasks is a page, and Assigned tasks keeps only its own board (2026-10-18, live, no migration)

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

## Stage 48 — A card is filed in one of a project's six sub-categories, or it is on Other tasks (2026-10-19, live, V10)

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
a hand-built POST cannot delete one of the six out from under the cards filed in it.

**A card that names neither world is refused, and the picker that used to offer it says so.** *— No project —* is out
of the Project list, and `resolve` answers an empty `projectName` with *"Choose which project this card is on."* The
one blank that can still print is `NO_PROJECT_YET` (*— No project yet —*), which is not an option anybody can choose:
it is what the field says when the row arrived before this rule and has nothing to say, and the save that receives it
refuses. A card's **owner** in that state gets a different sentence — *"This card is on no project yet — ask
management to choose one."* — because the field that would fix it is one they may not write; `updateAssigned` prints
it from the row, not from the form.

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
the throwaway: a disabled select posts nothing, so the crossing would land cards in no column. "Let management delete
one of the six from the board" is answered before it is asked — `LaneError.STANDARD`, checked before the count rule —
and "delete the extra lanes so every board is exactly six" is the one thing the reader explicitly refused. "Give
anybody the `⋮` so they can rename their own column" takes in a shared column the work of everybody else.

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

**The sidebar** (`DashboardCommon.kt`). `navItems(employeeNumber)` is left whole — all ten items — because the
notification bell resolves a stored item's section back to a path *through that list* (see
`mn.murray.pts.notificationBell`); trimming the list would silently send a Viewer's own backlog-card notification to
`/app/projects`. The filter lives one layer up: `MANAGEMENT_ONLY_NAV = setOf("other-tasks", "access-control",
"settings")`, and `navItemsFor(employeeNumber)` returns the whole list for management and drops those three ids for
everyone else. The sidebar render now iterates `navItemsFor`, so the menu is what moves and the resolver's list never
changes.

**Access control** (`Application.kt` `GET /app/access-control`). A fresh `if (!canManage) return@get
call.respondRedirect("/app")` sits right after `canManage` is computed, before the roster is read. The POST already
checked `canManage`; only the GET page was open.

**Settings** (`Settings.kt` `settingsContent`, `Application.kt` `respondSettings`). `settingsContent` grows a
required `canManage: Boolean` argument (passed from the session in `respondSettings`). When it is false the whole
Team block — the `Team` heading, the flash, the `+ Add employee` card, the toolbar, `#team-board` and the roster
table — is not drawn, and neither is the employee popup (`#employeeModal`) nor its `employee-modal.js` script (a
landing point for a control that no longer exists). What still prints for a Viewer is their own `accountCard`
(*Your account*) and the **Change Password** form — the personal path that must stay open, and the one `requireSession`
routes must-change-password accounts to via `MUST_CHANGE_ALLOWLIST = {"/app/settings", "/app/settings/password"}`. So
the page is reachable by every role; only its management half is withheld.

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
it is open to every role; hiding the roster is right, hiding the password box is not. "Let a Viewer still create a
backlog card since Stage 42 made the box universal" is the exception this stage retires: writing a *new* card onto the
shared board is one of the Other-tasks functions management keeps, so the add line, the create popup and the save-route
column branch all check the role now. "Lock a page by hiding its link only" is presentation, not the right — the link,
the GET and every POST behind it are gated, because a hidden link still leaves the URL.

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

**If this is restated.** "Revert the project page to lane columns" is the change this stage retires at the user's
request. "Rip out `laneKanban` and the `lanes` param since nothing renders lanes any more" over-reaches: the popup's
Sub-category picker, `lanesOf`, compulsory filing and the lane tests all still read that capability, and removing the
renderer would break them for a display change. "Stop filing cards into sub-categories because the board no longer
shows them" would drop a card's lane and unravel Stage 48 — the field is set, just not the column. Whether the
Sub-category *picker* should also leave the project page's popup is a follow-up the reader has not asked for, so it
stays.

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

## Stage 55 — The Assigned tasks page also shows the reader's own Other tasks (2026-10-19, live, no migration)

The reader's ask: *"when user click on assigned tasks there should be other tasks shown also, because that person can
have assigned tasks outside of the project… If they don't have assigned tasks just show them you don't have other tasks
assigned."* Stage 47 had moved the whole backlog to its own page and Stage 51 took that page's sidebar link away from
Viewers, so a reader who owned backlog cards but could not reach the Other tasks page could no longer see them at all.
Stage 55 puts a **personal slice** of that board back under the assigned one — the reader's *own* Other-task cards
(`kind = "OTHER"`), not the shared board — and, when they hold none, prints `You don't have any other tasks assigned.`
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
anywhere posts `?edit=<id>` to `/app/assigned-tasks`, whose `editableBy` loads either `kind`, and the edit form's
`back = assigned` only steers the redirect, never the row's kind.

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
colleague's never does. Column counts assert `kcol-head` rather than `data-status="` because a card's move-button arrows
also carry `data-status="` and inflate that token. `SidebarTaskCountTest` adds
`the pill counts the reader's own other tasks alongside their project cards` (own project card + own backlog card = 2; a
further reader with one backlog card = 1; a reader with neither = 0). 8080 restarted on PID **9084** with `/` 200;
`javap` on the packaged jar shows `assignedFor(String, boolean)` followed by `assignedOtherFor(String)`.

**If this is restated.** "Merge the two boards into one" would lose the separate empty message the reader asked for.
"Show management the whole backlog here" over-reaches — this is the reader's *own* cards, and management's wide read of
all backlog lives on the Other tasks page, not here. "Bring the flat backlog list back" is the pre-Stage-47 shape this
replaces with a status board; the shared column structure the reader already knows is the point.

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
`tasks.isNotEmpty()`, so a Viewer who can only read others' cards gets no dead form. The empty-line wording moved from
*"nothing assigned to you here yet"* to *"nothing assigned to this project yet"* to match the shared board. `assignedFor`
is **not** changed and keeps its `canManage` branch — only this page stopped using its personal filter; the Calendar,
the general Assigned tasks page and the Team sheet's OPEN TASKS drill-down still scope a non-manager to their own cards
through it. **`taskCard` already printed the holder's avatar unconditionally**, so "who is doing" needed no new code —
widening the list is what surfaced it.

**Tests.** **448 / 30 classes / 0 failures** — the same count Stage 55 shipped, two project-page cases **re-pointed**
(not added or removed). `a viewer's project page shows their own cards and no figures about the rest` → `a viewer's
project page shows every card of the project but edits only their own`: the colleague's cards and their holder (Naran)
are now asserted **present**, while a `data-task-card` count asserts exactly **one** opener (the Viewer's own card).
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

## Stage 58 — A backlog card drags between its bucket's status columns (2026-10-20, live, no migration)

**The ask.** *"since the cards shown in progress we should be able to move cards in status like not started to in
progress"* — on the Other tasks board, the cards sit in status columns since Stage 57 but could only be moved by the
owner's `←`/`→` arrows or by management's popup Status field; a mouse user had no way to drag one from, say, Not
started to In progress. **Stage 58** makes the backlog card a drag source on its own status row.

**The layout, not the rights.** Stage 57 already gave the backlog columns `data-status`, and `kanban.js` binds its
status-drop listeners to *every* `.kcol[data-status]` globally and only starts a drag on a `.kcard[draggable="true"]` —
so the one missing half was the drag source. `otherCard` now sets `draggable="true"` on the card for
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
moves by dragging or the popup, the owner by dragging *or* the arrows. And the drag must mirror the route exactly — a
`Viewer` who is not the assignee gets no `draggable` on a colleague's card; widening the flag past `canManage || mine`
would show a grab-handle the server refuses to move.

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
category only; it adds, renames or removes nothing on a board that already exists, and never re-files a card. The panel
(`bucketDefaultsPanel`) prints the six as the always-on floor (`.lane-chip`), a `data-autosubmit` category picker carrying
`?lanes=`, the category's current extras with a per-lane `buckets/delete` form, and a `buckets/add` form; both routes carry
`?lanes=` back so the picker stays on the category just edited.

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

**If this is restated.** "Add a new project category from Settings" is out — the nine types are code-defined; a catalogue
edit there would desync the board from the create form, so the panel is read-only by design. "Wire EmailJS / a
browser-sent mail" was declined in favour of the existing SMTP mailer. "Send the test to a colleague / another address"
is refused — the recipient is the caller's own address and it is admin-gated. "Let Bucket defaults edit the six, or push a
new default onto a live board" is refused: the six are the filing floor and are not editable, and a catalogue row shapes
only the *next* project.

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
