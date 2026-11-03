# Murray PTS — Target Architecture (v2)

This document describes the restructured layout the app is built on: the package layout, the
SQLite data model, the security model, the behavior the v2 rewrite had to preserve, and how it is
tested. Stages 1–9 are complete and the app is launch-ready (see README → Launch checklist).

**The stage-by-stage record — Stages 6 through 114, what each one asked for, decided, shipped,
verified and superseded — lives in [STAGE-HISTORY.md](STAGE-HISTORY.md)**, which this file was
split in two to create on 2026-11-02. Those sections moved verbatim and stay in order; the index
under [Stage history](#stage-history) links every one of them. Go there for the reasoning behind
a behaviour, stay here for what the app does now, and read README for how to run it. The
"as built" notes in the sections below override the original plan wherever they differ, and the
plan sections that were never taken are still labelled as such.

Note: the package layout below is the *target* from Stage 1. The shipped tree collapsed it
deliberately — the one-file-per-entity `db/tables/`, `web/route/`, `web/view/` and `util/`
packages did not earn their keep for an app this size, so tables live in a single `db/Tables.kt`,
repositories in three grouped files, services in `service/`, and the page views stayed as
top-level `<Page>.kt` files next to `Application.kt`. Run `find src/main -name '*.kt'` for the
truth.
## Goals

1. **Layered architecture**: routes → services → repositories → DB. No route touches SQL directly. No repository renders HTML.
2. **Real persistence**: SQLite via Exposed ORM + Flyway migrations, replacing single-file JSON.
3. **Hardened auth**: bcrypt password hashes, DB-backed session tokens (as built — see Security → Sessions, not the signed-cookie `Sessions` plugin originally assumed), per-route guards through one `call.requireSession()` helper (as built — a helper on `ApplicationCall`, not a Ktor plugin). The first-login password change this goal named was withdrawn in **Stage 99**: the box and the gate are gone and a password is set from the Team sheet.
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
│   ├── js/access-control.js       ← positions add/update/delete via fetch <tr> fragments, and since Stage 110
│   │                                 the role popup's close/focus/address-cleanup (opening is app.js's live swap)
│   └── assets/                    ← mms-logo.png (the in-page wordmark; moved from static/, 301 from
│                                     the old path) and favicon.png (Stage 114 — the logo cropped the
│                                     way murrays.mn crops its own icon: the emblem, the start of the
│                                     MURRAY bar with "MUR", a sliver of MINING SERVICES, transparent,
│                                     256 px, named by both heads; it replaced Stage 112's M and
│                                     Stage 113's emblem tiles)
├── db/migration/V1__init.sql      ← Flyway picks up from classpath
├── logback.xml
└── application.conf               ← optional HOCON for Ktor
```

## Data model (SQLite)

Real foreign keys replace the current string-name joins. **This is a semantic change** — flag if you want to keep name-based joins instead.

| Table | Key columns | Notes |
|---|---|---|
| `users` | `employee_number` PK, `name`, `password_hash`, `access` (the legacy Viewer/Manager/Admin word), `email`, `position_id` FK NULL, `role_id` FK→roles NULL (**V21, Stage 110 — this is what decides access**), `is_super_admin` BOOL, `created_at`, `updated_at` | Replaces `AccessRow`. EMP0000 seeded with `is_super_admin=true`. `access` is still *written*, kept in step with the role through `RoleRepository.legacyAccessWord`, so a hand-read row agrees with what the app grants; nothing reads it for authorization any more. `role_id` is `ON DELETE SET NULL` — a deleted role hands its holders to the default rather than deleting people with it. |
| `roles` | `id` PK AUTOINCREMENT, `name` UNIQUE, `sort_order` INT, `is_default` BOOL | V21, Stage 110: the catalogue the picture shows — Owner, Administrator, Project Manager, Employee seeded in that order with Employee the default, then any role the sheet adds, appended last. `name` is the identity a form carries; nothing keys off it except a chip's tint. See Stage 110. |
| `role_permissions` | `role_id` FK cascade, `permission` TEXT, PK(`role_id`,`permission`) | V21, Stage 110: the chips a role holds, one row per `Permission.key` — 13 / 13 / 9 / 1 for the seeds. Unknown keys are tolerated the way V1 tolerated an unknown status: the app names the ones it knows, so a hand-edited row that names nothing grants nothing. Deleting a role takes its chips with it. |
| `team_members` | `employee_number` PK, `name`, `department`, `position` | 23-person canonical roster. |
| `positions` | `id` PK, `name` UNIQUE, `permission_level` INT, `description` | Catalog only. `permission_level` was read for authorization from Stage 6 until **Stage 110**, when the role took over: the column stays (the operator can still see and edit it, and V21's backfill used it to place each existing account) but nothing reads it to decide a right. A position is the job title printed beside a name. |
| `projects` | `id` PK AUTOINCREMENT, `code` UNIQUE, `name`, `status`, `progress` INT, `manager_emp_no` FK→team_members, `due` TEXT (**the end date** — V3), `type` (one of nine category labels — V3), `client`, `start_date`, `revenue` REAL, `created_at` | Manager stored as FK, not name. `code` leads with the category letter (`S-01`). Client/start/revenue added by V3; `due` kept instead of an `end_date` column because the Calendar and the alerts already key off it. `status` is **free text with no CHECK constraint** — the five
words (`Not started / In progress / On hold / Blocked / Completed`, one vocabulary since **Stage 96**) are enforced at
the form, which is why V19 could rename a status with one `UPDATE`. See Stage 12. |
| `project_assignees` | `project_id` FK, `employee_number` FK, PK(`project_id`,`employee_number`) | Replaces `List<String>`. |
| `tasks` | `id` PK, `title`, `project_id` FK NULL, `due`, `priority`, `assignee_emp_no` FK NULL, `status`, `bucket`, `project_bucket_id` FK NULL (V7), `started`, `kind` (`ASSIGNED`/`OTHER`), `display_code` | Merges assignedTasks + otherTasks (they're the same shape). |
| `buckets` | `id` PK, `name`, `position` INT | Kanban columns for other-tasks — the backlog's buckets. **Never seeded**: V1 creates it empty, `V10` seeds only `project_buckets`, `V12` creates `type_default_buckets` empty and `V16` touches only a project's phases, so a fresh database opens **Other tasks** with no columns; every bucket is named by management through `POST /app/other-buckets`. There are therefore **no default buckets** for project-unrelated task work, and nothing on Settings' **Project bucket settings** card reads or writes this table — the two sets are separate on purpose. Naming a column is management's, confirmed by the user on 2026-10-26 (Stage 51's gate). |
| `project_buckets` | `id` PK, `project_id` FK cascade, `name`, `position` INT | V7, Stage 30: one **project's** lanes — the phases its own cards are filed under. Separate from `buckets` on purpose (see Stage 30). `tasks.project_bucket_id` is `ON DELETE SET NULL`, so removing a lane un-files its cards rather than deleting them. Since **Stage 111** this table is also what a create popup reads *across* projects: `ProjectBucketRepository.allByProject()` groups it by project, `TaskService.lanesByProjectName()` keys that by project **name** (the value the picker posts), and the general board's `+ New task` puts each board into its own option as `data-lanes` — so a project with no rows here is listed with an empty list rather than dropped. |
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
| Authorization | **Since Stage 110: a role decides, and a role holds chips.** `security/Authorization` answers `canDo(employeeNumber, Permission)` and `canAny(employeeNumber, Collection<Permission>)` from `RoleRepository.roleOf` — one row of `roles` read through `users.role_id`, falling back to the default role when that column is null. The three-rank ladder it used to resolve (super-admin → position `permissionLevel` → account `access` → Viewer, through an ordered `security/Role` enum) is **gone**, and that enum is deleted; `positions.permission_level` stays in the schema but is no longer read for authorization. `canManage` (any chip except *View teammates' tasks*) and `canAdmin` (*Manage employees* or *Manage roles & permissions*) survive only as the one boolean each page-level renderer takes — **a route guards the chip its control is about, never these flags**. V21's backfill resolves every existing account in the old order, so each one lands on the role that matches the rank it had; the rights a role grants are the picture's sets, and the one reach that did change — a Project Manager no longer reaches Settings or Access control — is recorded in Stage 110's *What the picture narrows*. |
| CSRF | Stateless session-bound token: `security/Csrf` = base64url(HMAC-SHA256(secret, sessionId)), validated in constant time. Rotates automatically with the session; no extra storage. **Not wired into any route (known gap, deliberately out of launch scope).** POSTs currently rely on the `SameSite=Lax` session cookie as the CSRF defence; `Csrf` is implemented and unit-tested (`CsrfTest`) and the production secret requirement in `AppConfig.validate()` exists precisely so turning it on later needs no config work. |
| First login | **Deleted in Stage 99** — this is the rule as launched. The route gate, its Settings allowlist and the Change Password card are gone; `must_change_password` is recorded but nothing enforces it, and nothing reads the flag to stop a reader. (Stage 102 later touched only the login screen's case guidance.) As launched: `users.must_change_password` is enforced at the route level: `call.requireSession()` (in `Application.kt`) redirects a flagged account to `/app/settings` for every path except the Settings allowlist (`/app/settings`, `/app/settings/password`), so the user can only set a new password. Settings renders the reason inline in the Change Password card. Flagged by `AuthService.ensureSuperAdmin()` when no `PTS_ADMIN_PASSWORD` was configured, by `JsonImporter` for every legacy account that had no explicit password, and by `UserService.addUser`. Saving a password through `AuthService.changePassword` clears the flag and re-issues the session, so the user stays signed in. |
| Headers | `web/plugin/SecurityHeaders` (installed): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`, `Permissions-Policy`, `Content-Security-Policy` (`default-src 'self'; script-src 'self'; style-src 'self' + style-src-attr 'unsafe-inline'` — tightened in Stage 7 once the assets were extracted, see "Stage 7 progress"), `Strict-Transport-Security` in production. |
| Secrets | `.env` (gitignored) + `.env.example`. `AppConfig` reads system property → env → `.env` → default. `AppConfig.validate()` refuses production boot with the default/short session secret. Secret never logged (`summary()` masks it). |
| Rate limit | `security/LoginRateLimiter` — in-memory sliding window on `POST /login`, bounds from `AppConfig.loginMaxAttempts`/`loginWindowSeconds` (default 5/60s). The key is the client IP from `X-Forwarded-For` **only when `PTS_TRUST_PROXY=true`**; otherwise it is the socket peer, because on a directly exposed port a forged header would otherwise be a free way to dodge the limiter. Verified live both ways. |
| Outbound mail (Stage 10) | `PTS_SMTP_*` credentials come from the same env/`.env` chain and are never logged — `Mailer` logs recipient + subject (plus the body, in log-only mode) and `AppConfig.summary()` prints host/port/sender, not the password. `AppConfig.validate()` does not gate on them (log-only is a valid mode), and `Mailer.send` never throws so a bad credential produces a ledger row with the reason, not a crash. Alerts are plain-text, sent over STARTTLS by default (`PTS_SMTP_SECURITY=ssl` for implicit TLS on 465, `none` only for localhost testing). No inbound mail, so nothing here parses untrusted input. |

### Stage 5 status (security layer)

Built, unit-tested and wired: `Role` (the three-rank enum **Stage 110 deleted** — read that stage for what replaced
it), `Authorization`, `SessionRepository`, `LoginRateLimiter`, `Csrf`, `SecurityHeaders` plugin, `AuthService` (bcrypt verify + DB session lifecycle + `ensureSuperAdmin` bootstrap). The **headers plugin, login rate-limiting and super-admin bootstrap are live in `Application.kt` now**. 16 tests pass (role resolution, throttle window/recovery, CSRF forgery rejection, and an end-to-end `AuthServiceTest` against a real SQLite file covering bcrypt login, session resolve/revoke, revoke-on-password-change, and expiry).

**Deferred to Stage 6 (deliberate):** flipping `POST /login` from the legacy plaintext `DemoData` check to `AuthService.authenticate`, switching `currentSession()` from the in-memory `SessionStore` to `SessionRepository`, replacing inline `MANAGE_ROLES` checks with `Authorization`, and rendering/validating CSRF tokens. Reason: those routes still read/write user data through the legacy `JsonStore`. Moving auth onto the DB before the user-management routes move would create a split-brain (a user added or edited via the UI would exist in JSON but not be able to log in). Auth and the user-data persistence flip together in Stage 6 so the app is never internally inconsistent. The full security layer is already implemented and tested — Stage 6 only swaps the call-sites over.


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
- [x] Logo served from `/static/assets/mms-logo.png`, with a 301 from the legacy `/static/mms-logo.png`;
  **since Stage 112** the tab icon is a separate derived `/static/assets/favicon.png` (256 px, opaque) named
  by both heads, and `/favicon.ico` is not served — **since Stage 113 it was the wordmark's ring/crosshair
  emblem** on the bar's green field, and **since Stage 114 it is the logo cropped the way murrays.mn crops
  its own favicon** — the emblem, the bar's start with "MUR", a sliver of MINING SERVICES, on transparency
- [x] Read-only Forecasts and Lessons pages with seed content (now DB-backed) — **both superseded**: Stage 43
  rebuilt Forecasts as the monthly money grid on `project_forecasts` (V8) and Stage 44 rebuilt Lesson learnt as the
  dated, per-person sheet on the widened `lessons` table (V9)
- [x] Team member-popup JSON payload shape unchanged (`/app/team` still emits the `memberDetailData` island, keys = employee number, same fields; verified live)

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

## Stage history

One section per stage, in order, in [STAGE-HISTORY.md](STAGE-HISTORY.md) — from the first
route/service migration to the latest stage, 2026-11-02's tab icon. The middle column is that section's own
heading text; the right column is its heading's parenthesised tail: the date it landed, whether
it is live, which migration it carries, which layers it touched. A stage with no date predates
the convention.

| Stage | Its section records | Shipped |
|---:|---|---|
| [6](STAGE-HISTORY.md#stage-6) | route/service migration | — |
| [7](STAGE-HISTORY.md#stage-7) | frontend asset extraction + CSP | — |
| [8](STAGE-HISTORY.md#stage-8) | config, logging, packaging, seeder, first-login gate | — |
| [9](STAGE-HISTORY.md#stage-9) | launch verification | — |
| [10](STAGE-HISTORY.md#stage-10) | due-date alerts (post-launch feature) | — |
| [11](STAGE-HISTORY.md#stage-11) | data reset, one-PC name, and publication | — |
| [12](STAGE-HISTORY.md#stage-12) | nine project categories and the create-project form | 2026-10-12 |
| [13](STAGE-HISTORY.md#stage-13) | the task board's card menu, and the notification bell | 2026-10-13 |
| [14](STAGE-HISTORY.md#stage-14) | the Team page groups by department, and a card has no buttons left | 2026-10-13 |
| [15](STAGE-HISTORY.md#stage-15) | projects, managers and assigned tasks are management's call | 2026-10-13 |
| [16](STAGE-HISTORY.md#stage-16) | every category panel prints on one column grid | 2026-10-13, live |
| [17](STAGE-HISTORY.md#stage-17) | project search bar with status and manager sort pickers | 2026-10-13, live |
| [18](STAGE-HISTORY.md#stage-18) | the live search refreshes the board in place | 2026-10-13, live |
| [19](STAGE-HISTORY.md#stage-19) | a reader sees their own tasks, and a card carries a checklist | 2026-10-13, live |
| [20](STAGE-HISTORY.md#stage-20) | the board prints both dates | 2026-10-13, live |
| [21](STAGE-HISTORY.md#stage-21) | the project page rolls its board up on top of it | 2026-10-14, live; **superseded by Stage 28** |
| [22](STAGE-HISTORY.md#stage-22) | the card keeps only its tick boxes and its status mover | 2026-10-14, live |
| [23](STAGE-HISTORY.md#stage-23) | a card opens its editor, its plan and its log | 2026-10-14, live |
| [24](STAGE-HISTORY.md#stage-24) | the card itself is the way in | 2026-10-14, live |
| [25](STAGE-HISTORY.md#stage-25) | an empty board invites the reader who may fill it | 2026-10-14, live |
| [26](STAGE-HISTORY.md#stage-26) | the checklists are the measurement | 2026-10-14, live |
| [27](STAGE-HISTORY.md#stage-27) | the menu counts the work still open | 2026-10-14, live |
| [28](STAGE-HISTORY.md#stage-28) | the project page stops reporting its own board | 2026-10-14, live |
| [29](STAGE-HISTORY.md#stage-29) | every field of the detail card reads the same way | 2026-10-14, live |
| [30](STAGE-HISTORY.md#stage-30) | a project's board is cut by its own lanes | 2026-10-15, live |
| [31](STAGE-HISTORY.md#stage-31) | a lane can be taken back off the board | 2026-10-15, live |
| [33](STAGE-HISTORY.md#stage-33) | the create popup gives the lanes back | 2026-10-15, live |
| [34](STAGE-HISTORY.md#stage-34) | each lane offers to start a card in it | 2026-10-15, live |
| [35](STAGE-HISTORY.md#stage-35) | a project's lanes stopped being editable from its page | 2026-10-15, live, no migration |
| [36](STAGE-HISTORY.md#stage-36) | a card stopped changing size because a count changed | 2026-10-15, live, no migration |
| [37](STAGE-HISTORY.md#stage-37) | the board's geometry stopped being a function of its counts | 2026-10-15, live, no migration |
| [38](STAGE-HISTORY.md#stage-38) | the cards were given their own height back | 2026-10-15, live, no migration |
| [39](STAGE-HISTORY.md#stage-39) | the backlog's bucket comes from its own heading | 2026-10-15, live, no migration |
| [40](STAGE-HISTORY.md#stage-40) | the backlog is a board, and its cards carry a status | 2026-10-16, live, no migration |
| [41](STAGE-HISTORY.md#stage-41) | a card prints a number, and the plan is in the popup | 2026-10-16, live, no migration |
| [42](STAGE-HISTORY.md#stage-42) | the backlog gets the card's whole popup, and every account may write one | 2026-10-16, live, no migration |
| [43](STAGE-HISTORY.md#stage-43) | the Forecasts page is a monthly money grid | 2026-10-16, live, V8 migration |
| [44](STAGE-HISTORY.md#stage-44) | the Lesson learnt page is a dated, per-person sheet | 2026-10-16, live, V9 migration |
| [45](STAGE-HISTORY.md#stage-45) | the Forecasts sheet is cut by category, the way the board is | 2026-10-18, live, no migration |
| [46](STAGE-HISTORY.md#stage-46) | Settings is the Team sheet the picture shows | 2026-10-18, live, no migration |
| [47](STAGE-HISTORY.md#stage-47) | Other tasks is a page, and Assigned tasks keeps only its own board | 2026-10-18, live, no migration; **superseded by Stage 97** |
| [48](STAGE-HISTORY.md#stage-48) | A card is filed in one of a project's six sub-categories, or it is on Other tasks | 2026-10-19, live, V10 |
| [49](STAGE-HISTORY.md#stage-49) | Moving a card between columns is the mover's, on both boards | 2026-10-19, live, no migration |
| [50](STAGE-HISTORY.md#stage-50) | the Team sheet's Assign panel can write an Other task | 2026-10-19, live, no migration |
| [51](STAGE-HISTORY.md#stage-51) | Settings, Access control and Other tasks are management's functions | 2026-10-19, live, no migration |
| [52](STAGE-HISTORY.md#stage-52) | A project's board is cut by status, not by sub-category | 2026-10-19, live, no migration |
| [53](STAGE-HISTORY.md#stage-53) | The Assigned tasks board is personal for every role | 2026-10-19, live, no migration |
| [54](STAGE-HISTORY.md#stage-54) | The Assigned tasks pill counts Completed cards too | 2026-10-19, live, no migration |
| [55](STAGE-HISTORY.md#stage-55) | The Assigned tasks page also shows the reader's own Other tasks | 2026-10-19, live, no migration |
| [56](STAGE-HISTORY.md#stage-56) | A project's page shows the whole project board to every reader | 2026-10-20, live, no migration |
| [57](STAGE-HISTORY.md#stage-57) | The Other tasks board is cut by status, one group per bucket | 2026-10-20, live, no migration |
| [58](STAGE-HISTORY.md#stage-58) | A backlog card drags between its bucket's status columns | 2026-10-20, live, no migration |
| [59](STAGE-HISTORY.md#stage-59) | The Add bucket control is a small button in the page head | 2026-10-20, live, no migration |
| [60](STAGE-HISTORY.md#stage-60) | Management edits the project header as a strip, and can delete the project | 2026-10-20, live, V11 |
| [61](STAGE-HISTORY.md#stage-61) | Settings gains three management panels: project types, email notifications, bucket defaults | 2026-10-20, live, V12 |
| [62](STAGE-HISTORY.md#stage-62) | A super-admin master switch gates every outbound email | 2026-10-20, live, V13 |
| [63](STAGE-HISTORY.md#stage-63) | The project categories come off the table, and Settings' three panels become the picture | 2026-10-20, live, V14 |
| [64](STAGE-HISTORY.md#stage-64) | The Calendar is the reader's own month, for every role | 2026-10-21, live, no migration, Kotlin-only |
| [65](STAGE-HISTORY.md#stage-65) | Any month the reader names, swapped in place | 2026-10-21, live, no migration, Kotlin + CSS |
| [66](STAGE-HISTORY.md#stage-66) | The month holds both boards, and every group of work wears its own colour | 2026-10-21, live, no migration, Kotlin + CSS |
| [67](STAGE-HISTORY.md#stage-67) | The Team row counts a person's cards, from both boards | 2026-10-21, live, no migration, Kotlin |
| [68](STAGE-HISTORY.md#stage-68) | Every reader sees the workload figures, and a Projects column joins the row | 2026-10-21, live, no migration, Kotlin + CSS |
| [69](STAGE-HISTORY.md#stage-69) | A card moved elsewhere never leaves the Team sheet printing the past | 2026-10-22, live, no migration, Kotlin + JS |
| [70](STAGE-HISTORY.md#stage-70) | Finishing a card ticks its whole plan, and reopening it puts the plan back | 2026-10-22, live, V15 |
| [71](STAGE-HISTORY.md#stage-71) | A Viewer's project header is the same one line management's is | 2026-10-22, live, no migration, Kotlin + CSS |
| [72](STAGE-HISTORY.md#stage-72) | Management can delete the card from inside the card | 2026-10-22, live, no migration, Kotlin + CSS |
| [73](STAGE-HISTORY.md#stage-73) | A project's buckets are chosen when it is made, and on its own page afterwards | 2026-10-23, live, no migration, Kotlin + CSS + JS |
| [74](STAGE-HISTORY.md#stage-74) | An empty project's board *is* its buckets, and each column can be re-shaped from the board | 2026-10-23, live, no migration, Kotlin + CSS + JS |
| [75](STAGE-HISTORY.md#stage-75) | Every project is Engineering / Procurement / Production, editable, and its board is always its buckets | 2026-10-23, live, V16, Kotlin + CSS + JS |
| [76](STAGE-HISTORY.md#stage-76) | A project's card prints the stage it has reached | 2026-10-26, live, no migration, Kotlin + CSS |
| [77](STAGE-HISTORY.md#stage-77) | A bucket belongs to its project; widening it to the category is a separate, management-only act | 2026-10-26, live, no migration, Kotlin + CSS |
| [78](STAGE-HISTORY.md#stage-78) | A project's card is its title, its plan line, and a foot line saying how far and who | 2026-10-27, live, no migration, Kotlin + CSS |
| [79](STAGE-HISTORY.md#stage-79) | A project's buckets are its columns, and the page carries no list of them | 2026-10-27, live, no migration, Kotlin + CSS |
| [80](STAGE-HISTORY.md#stage-80) | A bucket wears a colour somebody chose, and a column's finished work folds away | 2026-10-27, live, V17, Kotlin + CSS + JS |
| [81](STAGE-HISTORY.md#stage-81) | A bucket leaves with its work, and the work waits in the project's Archive | 2026-10-27, live, V18, Kotlin + SQL + CSS + JS |
| [82](STAGE-HISTORY.md#stage-82) | The three opening phases stand at the top of the bucket list | 2026-10-27, live, Kotlin + CSS |
| [83](STAGE-HISTORY.md#stage-83) | Creating a bucket asks for a name, and the app paints the colour | 2026-10-28, live, Kotlin |
| [84](STAGE-HISTORY.md#stage-84) | A card's popup deletes again, and the Archive belongs to the bucket | 2026-10-28, live, Kotlin |
| [85](STAGE-HISTORY.md#stage-85) | The navigation drawer is a little narrower | 2026-10-28, live, CSS |
| [86](STAGE-HISTORY.md#stage-86) | A card opens for every reader, and the write stays with its own | 2026-10-28, live, Kotlin |
| [87](STAGE-HISTORY.md#stage-87) | A card's editor no longer asks where the card already is | 2026-10-28, live, Kotlin |
| [88](STAGE-HISTORY.md#stage-88) | A card says which project it is for, and the reader can narrow their backlog by bucket | 2026-10-28, live, Kotlin + CSS |
| [89](STAGE-HISTORY.md#stage-89) | An assignee plans their own card, and the bucket picker is one card wide | 2026-10-28, live, Kotlin + CSS |
| [90](STAGE-HISTORY.md#stage-90) | An empty Other-tasks board still shows its picker, and the reader can write the first card | 2026-10-28, live, Kotlin |
| [91](STAGE-HISTORY.md#stage-91) | The new-project number opens empty, for the reader to type | 2026-10-28, live, Kotlin + JS |
| [92](STAGE-HISTORY.md#stage-92) | The number opens with the category's letter, and a new project no longer chooses its buckets | 2026-10-28, live, Kotlin + JS + CSS |
| [93](STAGE-HISTORY.md#stage-93) | The shared backlog gets the Assigned page's Bucket picker | 2026-10-28, live, no migration, Kotlin + JS |
| [94](STAGE-HISTORY.md#stage-94) | The backlog board stays draggable after its own picker swaps it | 2026-10-29, live, JS only, no migration |
| [95](STAGE-HISTORY.md#stage-95) | A backlog card wears the same stage tint as every other card | 2026-10-29, live, Kotlin + CSS, no migration |
| [96](STAGE-HISTORY.md#stage-96) | One vocabulary for a stage: the project picker reads as the progression | 2026-10-29, live, Kotlin + SQL **V19** |
| [97](STAGE-HISTORY.md#stage-97) | The Bucket picker is the Other-tasks board's only view control | 2026-10-29, live, no migration, Kotlin |
| [98](STAGE-HISTORY.md#stage-98) | A bucket added on Settings is a column on every board of its category | 2026-10-29, live, no migration, Kotlin |
| [99](STAGE-HISTORY.md#stage-99) | Settings loses its password box, and the first-login gate goes with it | 2026-10-29, live, no migration, Kotlin |
| [100](STAGE-HISTORY.md#stage-100) | A new board is exactly its category's Settings list | 2026-10-29, live, no migration, Kotlin |
| [101](STAGE-HISTORY.md#stage-101) | Newly added checklist steps never tick themselves | 2026-10-30, live, no migration, Kotlin |
| [102](STAGE-HISTORY.md#stage-102) | Login accepts a username in any letter case, and the screen says so | 2026-10-30, live, no migration, Kotlin |
| [103](STAGE-HISTORY.md#stage-103) | A status board's card is moved by the drag alone; the prev/next arrows are gone | 2026-10-30, live, no migration, Kotlin |
| [104](STAGE-HISTORY.md#stage-104) | The create popup names a bucket again, and a card's code walks to its project | 2026-10-30, live, no migration, Kotlin + JS + CSS |
| [105](STAGE-HISTORY.md#stage-105) | A project card lands on the drop, and the write follows it | 2026-10-30, live, no migration, JS only |
| [106](STAGE-HISTORY.md#stage-106) | The project header loses the Archive and copy-link icons, and the panel goes with its opener | 2026-10-30, live, no migration |
| [107](STAGE-HISTORY.md#stage-107) | The Assigned tasks projects board gets its own Project picker | 2026-10-30, live, no migration |
| [108](STAGE-HISTORY.md#stage-108) | Revenue is written and shown in tugrik | 2026-10-30, live, no migration, Kotlin |
| [109](STAGE-HISTORY.md#stage-109) | A project's revenue is in its own currency | 2026-10-30, live, migration V20, Kotlin + CSS + JS |
| [110](STAGE-HISTORY.md#stage-110) | Access control gets roles and permissions, and the role decides access | 2026-11-02, live, migration V21, Kotlin + CSS + JS |
| [111](STAGE-HISTORY.md#stage-111) | The Assigned page's `+ New task` asks which project, and its columns come from that project | 2026-11-02, live, no migration, Kotlin + JS |
| [112](STAGE-HISTORY.md#stage-112) | The browser tab carries the wordmark's M on its green bar | 2026-11-02, live, no migration, Kotlin + asset |
| [113](STAGE-HISTORY.md#stage-113) | The browser tab wears the wordmark's emblem, not its M | 2026-11-02, live, no migration, asset only |
| [114](STAGE-HISTORY.md#stage-114) | The tab wears the logo the way murrays.mn's tab does | 2026-11-03, live, no migration, asset only |

**Read the newest few before changing anything they touch.** A section is never trimmed when a
later stage supersedes it — the superseding stage says so in its own heading and explains what no
longer holds — so an older section can still be the only place a decision is argued. When a stage
lands, `STAGE-HISTORY.md` gets its section and this index gets its row.
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
