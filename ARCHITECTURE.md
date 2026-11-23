# Murray PTS — Target Architecture (v2)

This document describes the restructured layout the app is built on: the package layout, the
SQLite data model, the security model, the behavior the v2 rewrite had to preserve, and how it is
tested. Stages 1–9 are complete and the app is launch-ready (see README → Launch checklist).

**The stage-by-stage record — Stages 6 through 133, what each one asked for, decided, shipped,
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
3. **Hardened auth**: bcrypt password hashes, DB-backed session tokens (as built — see Security → Sessions, not the signed-cookie `Sessions` plugin originally assumed), per-route guards through one `call.requireSession()` helper (as built — a helper on `ApplicationCall`, not a Ktor plugin). The first-login password change this goal named was withdrawn in **Stage 99**, and **Stage 123** restored only its self-service half: the gate is still gone, every account changes its own password under Settings › *Your account*, and only the super admin sets somebody else's.
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
│   ├── js/login.js                ← the login password box's Show/Hide button (Stage 121; login page only)
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
| `team_members` | `employee_number` PK, `name`, `department`, `position` | 23-person canonical roster. Since **Stage 117** a person's `department` is chosen from nine settings in the employee popup by an owner or administrator (a role holding every chip); older free-text words stay until somebody changes them. |
| `positions` | `id` PK, `name` UNIQUE, `permission_level` INT, `description` | Catalog only. `permission_level` was read for authorization from Stage 6 until **Stage 110**, when the role took over: the column stays (the operator can still see and edit it, and V21's backfill used it to place each existing account) but nothing reads it to decide a right. A position is the job title printed beside a name. |
| `projects` | `id` PK AUTOINCREMENT, `code` UNIQUE, `name`, `status`, `progress` INT, `manager_emp_no` FK→team_members, `due` TEXT (**the end date** — V3), `type` (one of nine category labels — V3), `client`, `start_date`, `revenue` REAL, `created_at` | Manager stored as FK, not name. `code` leads with the category letter (`S-01`). Client/start/revenue added by V3; `due` kept instead of an `end_date` column because the Calendar and the alerts already key off it. `status` is **free text with no CHECK constraint** — the five
words (`Not started / In progress / On hold / Blocked / Completed`, one vocabulary since **Stage 96**) are enforced at
the form, which is why V19 could rename a status with one `UPDATE`. See Stage 12. |
| `project_assignees` | `project_id` FK, `employee_number` FK, PK(`project_id`,`employee_number`) | Replaces `List<String>`. |
| `tasks` | `id` PK, `title`, `project_id` FK NULL, `due`, `priority`, `assignee_emp_no` FK NULL, `assignee2_emp_no` FK NULL (V22, Stage 122), `status`, `bucket`, `project_bucket_id` FK NULL (V7), `started`, `kind` (`ASSIGNED`/`OTHER`), `display_code` | Merges assignedTasks + otherTasks (they're the same shape). `assignee2_emp_no` is the **second** person on a project card — nullable, `ON DELETE SET NULL`, blank on a backlog card, and read through `TaskItem.assignees` so both people are holders everywhere; see Stage 122. |
| `buckets` | `id` PK, `name`, `position` INT | Kanban columns for other-tasks — the backlog's buckets. **Never seeded**: V1 creates it empty, `V10` seeds only `project_buckets`, `V12` creates `type_default_buckets` empty and `V16` touches only a project's phases, so a fresh database opens **Other tasks** with no columns; every bucket is named by management through `POST /app/other-buckets`. There are therefore **no default buckets** for project-unrelated task work, and nothing on Settings' **Project bucket settings** card reads or writes this table — the two sets are separate on purpose. Naming a column is management's, confirmed by the user on 2026-10-26 (Stage 51's gate). |
| `project_buckets` | `id` PK, `project_id` FK cascade, `name`, `position` INT | V7, Stage 30: one **project's** lanes — the phases its own cards are filed under. Separate from `buckets` on purpose (see Stage 30). `tasks.project_bucket_id` is `ON DELETE SET NULL`, so removing a lane un-files its cards rather than deleting them. Since **Stage 111** this table is also what a create popup reads *across* projects: `ProjectBucketRepository.allByProject()` groups it by project, `TaskService.lanesByProjectName()` keys that by project **name** (the value the picker posts), and the general board's `+ New task` puts each board into its own option as `data-lanes` — so a project with no rows here is listed with an empty list rather than dropped. |
| `forecasts` | `id` PK, `project_id` FK, `period`, `hours`, `note` | V1's legacy row set: hours per project per period, kept for the importer. Since Stage 43 **nothing reads it** — the Forecasts page is `project_forecasts` now, and the sidebar pill counts projects instead of these rows. |
| `lessons` | `id` PK, `title`, `project_id` FK NULL, `summary`, `lesson`, `employee_number` FK NULL, `task_id` FK NULL, `logged_on` (`yyyy-MM-dd`, `''` = undated), `discussed` BOOL, `archived` BOOL (all six since V9) | V1's row set widened in place by V9, Stage 44: the Lesson learnt sheet's own record — the one text the page prints, who logged it, which card it came from, the day, and its states. **Since Stage 116 the archive is read-only data**: the control, its route and its writes are gone, and `board()` hides `archived = 1` rows; the column stays so rows put away before then are not lost. `title`/`summary` stay (`title` is `NOT NULL`, no default) and the page prints `lesson`; the repository falls back to the old pair only if `lesson` came through blank; the backfill joins each old pair into `lesson` once, guarded by `WHERE lesson = ''`. All three FKs are `ON DELETE SET NULL`, so a lesson outlives the account, the card and the project it names — an unowned row is management's to tidy. See Stage 44. |
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
| Passwords | jBCrypt cost 12 (`security/PasswordHasher`). On first-run import, existing plaintext passwords are hashed and the JSON is renamed `.migrated`. `AuthService.changePassword` rehashes and revokes all of the user's sessions. Since **Stage 123** every account changes its **own** password on Settings › *Your account* (`POST /app/settings/password` → `UserService.changeOwnPassword`: current, new and confirm, refused as `EMPTY`/`WRONG_CURRENT`/`MISMATCH`/`TOO_SHORT` under the field each belongs to), and the route re-issues one session so the browser that made the change stays signed in. Setting **another** account's password is the super admin's alone (`UserService.SUPER_ADMIN`, EMP0000) — not a chip, so an all-chip Owner or Administrator does not get it — on Access control's Password column, its add card, and the Settings employee popup. A stored hash is never readable, so "see somebody's password" can only mean the control to set a new one. |
| Sessions | **Opaque-token model (as built), not the signed-cookie `Sessions` plugin.** `PTS_SID` carries a random UUID; the authoritative record lives in the `sessions` table (`repository/SessionRepository`): employee_number FK, created_at, expires_at, user_agent, ip. Cookie stays `httpOnly`, name **preserved as `PTS_SID`**, 12h expiry from `AppConfig.sessionTtlHours` — **absolute, stamped at login** (`create` writes `expires_at`; nothing slides it on activity). DB rows survive restart and allow admin revocation (`revokeAllFor`). Expiry is enforced at read (`findValid`) and swept by `purgeExpired`. Chosen over the Ktor `Sessions` signed-cookie plugin because it keeps revocation and audit server-side and matches the legacy token-in-cookie shape. |
| Authorization | **Since Stage 110: a role decides, and a role holds chips.** `security/Authorization` answers `canDo(employeeNumber, Permission)` and `canAny(employeeNumber, Collection<Permission>)` from `RoleRepository.roleOf` — one row of `roles` read through `users.role_id`, falling back to the default role when that column is null. The three-rank ladder it used to resolve (super-admin → position `permissionLevel` → account `access` → Viewer, through an ordered `security/Role` enum) is **gone**, and that enum is deleted; `positions.permission_level` stays in the schema but is no longer read for authorization. `canManage` (any chip except *View teammates' tasks*) and `canAdmin` (*Manage employees* or *Manage roles & permissions*) survive only as the one boolean each page-level renderer takes — **a route guards the chip its control is about, never these flags**. V21's backfill resolves every existing account in the old order, so each one lands on the role that matches the rank it had; the rights a role grants are the picture's sets, and the one reach that did change — a Project Manager no longer reaches Access control — is recorded in Stage 110's *What the picture narrows*; **Stage 123** reopened Settings to every account, so Access control is now the only one of the two that still turns a Project Manager away. **Stage 117** adds one predicate beside `canDo`/`canAny`: `isOwnerOrAdministrator` is true when the role holds **every** chip, and it alone is the department right on the Settings popup — both seeded all-chip roles have it, a renamed one keeps it, and a twelve-chip role does not. **Stage 123** removes `settings` from `NAV_PERMISSIONS`: every account's drawer prints the link and `GET /app/settings` turns nobody away, while the Team sheet and the three panels stay on their chips, so a Project Manager or an Employee sees *Your account* alone. The other-password controls are the super admin's, not a permission — Owner and Administrator hold all thirteen chips and still do not get them. |
| CSRF | Stateless session-bound token: `security/Csrf` = base64url(HMAC-SHA256(secret, sessionId)), validated in constant time. Rotates automatically with the session; no extra storage. **Not wired into any route (known gap, deliberately out of launch scope).** POSTs currently rely on the `SameSite=Lax` session cookie as the CSRF defence; `Csrf` is implemented and unit-tested (`CsrfTest`) and the production secret requirement in `AppConfig.validate()` exists precisely so turning it on later needs no config work. |
| First login | **Deleted in Stage 99** — this is the rule as launched. The route gate, its Settings allowlist and the Change Password card are gone; `must_change_password` is recorded but nothing enforces it, and nothing reads the flag to stop a reader. **Stage 123** brings the *self-service* form back on every account's *Your account* card (`POST /app/settings/password`); the gate, the allowlist and the forced change do not return. (Stage 102 later touched only the login screen's case guidance.) As launched: `users.must_change_password` is enforced at the route level: `call.requireSession()` (in `Application.kt`) redirects a flagged account to `/app/settings` for every path except the Settings allowlist (`/app/settings`, `/app/settings/password`), so the user can only set a new password. Settings renders the reason inline in the Change Password card. Flagged by `AuthService.ensureSuperAdmin()` when no `PTS_ADMIN_PASSWORD` was configured, by `JsonImporter` for every legacy account that had no explicit password, and by `UserService.addUser`. Saving a password through `AuthService.changePassword` clears the flag and re-issues the session, so the user stays signed in. |
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
- [x] Login at `/`, `POST /login`, `GET /logout`, `/app` → Dashboard, `/app/projects` → Projects
- [x] All 35 route paths exactly as today
- [x] `POST /app/tasks/status` form-encoded `id`+`status`, Referrer redirect
- [x] `POST /app/todo/lists/reorder` with `order=csv`
- [x] Positions endpoints return HTML `<tr>` fragments when `X-Requested-With: fetch`, 204 on delete
- [x] `?type=` filter on projects, `?pw=PasswordStatus` flash banner (Stage 123's own-password change; the old form's `who=emp` key went with it)
- [x] First-run seed data — now gated behind `PTS_SEED_DEMO` (opt-in seeder wired in Stage 8: `db/migration/DemoSeeder`), EMP0000 super-admin always ensured ✓, `MIN_PASSWORD_LENGTH=4` ✓
- [x] Password defaults to employee number for legacy users (only during import; new users must set one)
- [x] Auto-email `firstname.surname@murrays.mn` (Stage 125: first and last word of the name, middle words dropped) —
  **and since Stage 126 the accounts already stored read the same way**, recomputed once at boot through the same
  function and marked in `app_settings`, not by a SQL copy of the rule
- [x] Project code generation `PREFIX-NN` (verified `MFG-03` at the time — **since Stage 12 the prefix is
  one letter and the number is typed, only suggested**: `M-03`), task display code `T-%05d`
- [x] Dark mode + accent toggle persisted in `localStorage` keys `pts-theme` / `pts-accent` (verified in a real browser in Stage 7 — see below; the old inline `THEME_JS` was dead code and never ran). **Since Stage 129** the theme also
  chooses the drawer closer's drawing — stroked on light, solid on dark — which is a stylesheet reading of the
  `data-theme` attribute `theme.js` already sets, not a second render and not a server-side guess. **Since Stage 130**
  a third key, `pts-sidebar`, holds whether this browser has folded the desktop sidebar away: same per-browser
  storage, same absence-is-the-default shape, and still nothing on the server knows how a reader's window is arranged
- [x] Per-user pins, per-user todo ownership and list ordering — **since Stage 127 every signed-in account may
  pin for itself** (the route's *Edit project details* gate is gone, and the row written is still the caller's
  own, keyed by `session.employeeNumber`), and the sidebar's **Pinned projects** rows lead with the project's
  **ID** — `projects.code`, the same value the Projects sheet prints under its `ID` header
- [x] `pts.port` / `PTS_PORT` (8080), `pts.host` / `PTS_HOST` (0.0.0.0), LAN-address startup banner
- [x] Logo served from `/static/assets/mms-logo.png`, with a 301 from the legacy `/static/mms-logo.png`;
  **since Stage 112** the tab icon is a separate derived `/static/assets/favicon.png` (256 px, opaque) named
  by both heads, and `/favicon.ico` is not served — **since Stage 113 it was the wordmark's ring/crosshair
  emblem** on the bar's green field, and **since Stage 114 it is the logo cropped the way murrays.mn crops
  its own favicon** — the emblem, the bar's start with "MUR", a sliver of MINING SERVICES, on transparency.
  **Since Stage 115 every page's tab is titled MPTS** — `title("MPTS")` in both `appPage` and `loginPage`, so the
  tab does not change as the reader moves between functions; the page's own name is the on-screen heading
  (`appPage`'s `title` parameter drives that heading, not the tab) and the long system name survives only as the
  sign-in page's visible heading
- [x] Read-only Forecasts and Lessons pages with seed content (now DB-backed) — **both superseded**: Stage 43
  rebuilt Forecasts as the monthly money grid on `project_forecasts` (V8) and Stage 44 rebuilt Lesson learnt as the
  dated, per-person sheet on the widened `lessons` table (V9)
- [x] Team member-popup JSON payload shape unchanged (`/app/team` still emits the `memberDetailData` island, keys = employee number, same fields; verified live)

### Assigned tasks: the empty panel can name a shared column (Stage 124)

`AssignedTasks.kt` supplies `taskKanban`'s `emptyOffer`/`emptyHint`: the empty projects-board panel remains
visible on *Edit any task*, but with *Manage buckets* its offer is `+ New bucket`; without that chip it keeps
`+ New task`. The page head's task opener is unchanged. The new GET onto `/app/assigned-tasks` carries
`newbucket=1`, the current `bucket`/`project` filters and `data-live-target="#bucketModal"`. It reuses
`OtherTasks.kt`'s `bucketModal` add half and `bucket-modal.js`, posting `/app/other-buckets` with
`back=assigned-tasks`, both filter ids, `data-live-target="#assignedOther"` and `data-live-extra="#bucketModal"`.
The POST rechecks *Manage buckets*; denial returns Assigned, refusal answers 200 with the typed name, field error
and both filters, and success answers 200 selecting the new bucket in the lower personal picker, preserving the
project filter and closing the popup. `#assignedOther` holds that picker, own cards or empty line, and `+ Add task`.
`Application.kt`'s `respondAssignedTasks` centralizes GET/create/refusal rendering, including task-save refusals.
The new bucket is shared Other-tasks work, not a project lane or a status change. `bucket-modal.js` clears
`newbucket` on close but preserves Assigned's `bucket` filter; on Other tasks, `bucket` is a popup key and is cleared.
No migration (schema **22**) or CSS change; JavaScript changed, so **Ctrl+F5** once. Verified: **726 tests / 49 classes**,
**25 throwaway HTTP checks** and **90 JavaScript harness checks**, all passing. Live on 8080 since 2026-11-06,
PID **16344**; HTTP and synthetic-DOM verification, not a signed-in visual sign-off.

### Company email: one rule, and one pass over the rows written before it (Stages 125–126)

`domain/User.kt`'s `autoEmail(name)` is the only generator: the name's first and last word, lowercased, joined by one
dot, at `murrays.mn` (a one-word name stands alone, a middle word is dropped, a name with no word yields nothing).
Every create path calls it — `UserService.addUser`, `UserService.updateEmail` on a blank edit, `AuthService.ensureSuperAdmin`,
`EmployeeService`, `Data.kt`, `AccessRow.displayEmail`, `LegacyNormalizer`/`JsonImporter` — and a typed address at create
time is kept, trimmed and lowercased. Stage 126 added `db/migration/UserEmailBackfill.applyOnce()`, called from
`Application.bootstrapDatabase()` after the import, the seed and the bootstrap account: it recomputes each stored address
through the same function and writes the ones that differ, guarded by the `app_settings` key `email_rule_version` so it
runs once per database and a later-typed address stays typed. No schema change (Flyway stays **v22**); the cost is that
the one pass also rewrites addresses an administrator had typed.

### Pins: who may pin, and what the panel prints (Stage 127)

Pinning is one reader's own list, so `POST /app/projects/{id}/pin` needs only a session: it reads the project id from the
path and the employee number from `session.employeeNumber`, calls `ProjectService.togglePin`, and redirects to the
Referrer. It never reads an employee number from the body, so there is nothing to forge, and it never touches another
account's rows. `pins` is `(employee_number, project_id, position)` with `position` in pin order, which is what
`PinRepository.pinnedProjectIds` sorts by.

The control is drawn **once per page** and says the same thing wherever it appears: **☆ Pin for me** / **★ Pinned**. The
editable head strip prints it, gated on `canEdit` alone — not on the `canEdit || canDelete` that decides whether the head
exists at all — because an account that may delete but not edit is also served the read-only card, and that card carries
the pin. The Projects list keeps its short **☆ Pin** column; the sidebar's pinned row ends in a × that posts the same
route.

Each sidebar row is the project's **ID**, then its name as the row's only link, then the ×. `app.css` gives the ID
`flex: 0 0 auto` and the name `flex: 1; min-width: 0` with `text-overflow: ellipsis`, so a long name clips while the
number that identifies the project stays whole — the same guarantee the Projects sheet's **ID** column makes. That ID is
`projects.code` (`S-01`), not `projects.id`. No schema change (Flyway stays **v22**); CSS changed, so **Ctrl+F5** once.
Verified: **742 tests / 51 classes**, plus **49 throwaway HTTP checks** on a fresh database with two chip-less Employee
accounts; live since 2026-11-09, PID **25068**.

### The drawer's two controls: the hamburger that opens it and the button that shuts it (Stage 128)

The shell draws one sidebar — `DashboardCommon.kt`'s `aside("sidebar")`, named `#appSidebar` — and two disclosures that
belong to it. `#navToggle`, in the page head beside the title, is the opener: three bars, `aria-controls`,
`aria-expanded`, and an `aria-label` that `app.js` swaps with the state. `#navClose` is the closer, printed **inside**
the drawer after the logo link and the caption and before `nav`: a `type="button"` with `aria-controls="appSidebar"`
and `aria-label="Close navigation"`, holding the panel-and-chevron glyph and no word. It carries no `aria-expanded` —
the drawer's one state has one owner, the opener. **Stage 129** put two drawings of that glyph in the box, an outlined
one and a solid one, and left everything above them alone — see the next section.

Its place in the drawer's order is load-bearing rather than cosmetic: `app.js` opens by focusing
`navDrawer.querySelector('a, button')`, and that first answer has to stay the logo link, not a door.

When the closer shows, and what it closes, is the stylesheet's decision. `.nav-close` shares the default
`display: none` line with the hamburger, the backdrop and the reopen tab, so nobody is handed a control before a script
has said what it does. Below 900px the rule that shows it is `html.js body.nav-open .nav-close`, inside the one
`@media (max-width: 900px)` block — three conditions, each one a reader for whom the button would close nothing:
scripting (`html.js`), an actually-open panel (`body.nav-open`), and a sidebar that is really a drawer (the
breakpoint). There it is the hamburger's own 40px box, `position: fixed` at `top: 12px; right: 12px`, `z-index: 61` so
it sits above the panel it belongs to, and fixed so a long menu keeps it while its rows scroll. **Stage 130** gave the
same button a second meaning above the breakpoint, where it folds the column instead — see
*Folding the desktop sidebar, and the tab that brings it back* below. What this stage decided, and what still holds
verbatim: the drawer's closer is printed inside the drawer, after the logo link, with no `aria-expanded` and one
function to close.

Closing stays one function. `closeNav()` is what the hamburger's close branch, the backdrop, Escape and the new button
all call: `nav-open` off, the opener's label back to *Open navigation*, focus returned to `#navToggle` so nobody is
left on a control that just slid off-screen. No new route, no data written, no schema change (Flyway stays **v22**);
Kotlin, CSS and JS all changed, so **Ctrl+F5** once. Verified at the time by **745 tests / 51 classes**, **166 throwaway
HTTP checks** across all ten authenticated pages plus the login page, and **19 synthetic-DOM JS checks**; the shell as
it now stands — with **Stage 129**'s redraw, **Stage 130**'s desktop fold and **Stage 131**'s placement of the
way back, with **Stage 132**'s scale, ink and ring, **Stage 133**'s table of times and **Stage 134**'s phone
close-out written over all of it — is at **779 tests / 54 classes**. The last deploy is Stage 134's follow-up
(2026-11-16, PID **12248**, jar **2010630** bytes), live on 8080 with `data/pts.db*` copied in place to
`data/pts.pre-stage134b-20261116-091223.db*` before the JVM came back up. The stage's first deploy (2026-11-13,
PID 15388, jar 2009972, backup `pts.pre-stage134-20261113-112344.db*`) shipped a fold that reached only one of the
project page's two copies of the row; the follow-up is what corrected it.

### The drawer closer's two drawings, and the theme that picks between them (Stage 129)

`button#navClose` holds two `<svg>` elements and shows exactly one. `DashboardCommon.kt`'s `icon(path, classes)`
draws the outlined half — the house box, `fill="none" stroke="currentColor"`, a rounded panel with its divider at
`M9 3v18` and a chevron pointing at it — and `iconFilled(path, classes)` draws the solid half in the same 24×24 box
with `fill="currentColor" stroke="none"`. The `classes` argument is optional and every other icon in the shell leaves
it empty, so their markup is byte-identical to what it was before this stage.

The solid path is three subpaths under `fill-rule="evenodd"`: the panel block, then the divider strip, then the
chevron, the last two subtracting from the first. That is what makes them holes rather than paint — what shows through
is the button's own background, including the `--input-bg` it takes on hover — and it is why the glyph needs neither a
theme variable nor a hover rule of its own.

`app.css` decides which drawing a reader sees, in four whole-line rules that sit **after** the `@media
(max-width: 900px)` block: `.nav-close .glyph-outline { display: block; }` with the solid one hidden, and the two
`html[data-theme="dark"] …` rules swapping them. Placing them outside the breakpoint is the point — a theme is not a
width, and a rule inside the block would make a theme toggle look broken until the window was resized. The sizing rule
`.nav-close svg { width: 20px; height: 20px; }` deliberately carries no `display`, so nothing can paint both drawings
by accident.

The swap costs no work and no reload. `theme.js` reads and writes `localStorage.pts-theme` and sets `data-theme` on
`document.documentElement`; it never touches markup, so the CSS hook fires on the attribute change in the same frame,
and the server never learns — or needs — which theme a reader is on. No route, no schema change (Flyway stays **v22**);
Kotlin and CSS both changed, so **Ctrl+F5** once. Verified at the time: **746 tests / 51 classes** (the existing closer test now
reads both drawings, and *the closer's glyph follows the theme, not the width* pins the four rules and asserts the
breakpoint names no drawing), **243 throwaway HTTP checks** over ten authenticated pages, the login page, the served
stylesheet, script and theme script, and a rendered fixture — `scratch/stage129-glyph-fixture.html` through headless
Chrome into `scratch/stage129-glyph.png` — that puts both drawings on light and dark cards side by side.

**Stage 130** later gave this button a second job above the breakpoint, so read the two rules here as the drawer's half
of them: the corner geometry moved from `position: fixed` at 12px to `position: absolute` inside the sidebar at 10px on
a wide page, the `display: none` that used to be the button's whole desktop story is now one answer inside the
breakpoint rather than the default, and the sizing rule this section moved out of the block sits above it for both
widths. The two drawings, the four theme rules after the block, and the fact that no drawing is chosen by a width are
untouched. The shell as it ships is in the next section.

### Folding the desktop sidebar, and the tab that brings it back (Stage 130)

The sidebar takes 240px of the row on a wide page, and a reader working a full-width sheet wants that width back. The
control is the corner button Stage 128 already printed — `DashboardCommon.kt`'s `navCloseButton()`, one id, one class,
two meanings — and the way back is `sidebarTab()`, a `button#sidebarTab` printed in `appPage` **after**
`<aside id="appSidebar">` and still inside `.app`. It has to be a sibling: the button that hides the column lives in
that column, so once it has hidden it there is nothing left inside to press.

`app.css` does the rest with three rules above the Stage 119 block and three replies inside it. Above:
`html.js .nav-close` is a 34px box `position: absolute` in the top-right of the sidebar (`.sidebar` is
`position: sticky`, which is what makes the sidebar its containing block); `html.js body.sidebar-hidden .sidebar {
display: none; }` takes the column out of the flex row so `main` (`flex: 1`) fills the window in one step — not a
width animating to zero, and not a `visibility` fold that keeps the gutter; `html.js body.sidebar-hidden .sidebar-tab`
is a 22×72px strip `position: fixed` at `top: 10px; left: 0` with its left border off and its right corners
rounded — the `10px` is the closer's own offset, and it is **[Stage 131](STAGE-HISTORY.md#stage-131)**: the tab shipped
centred at `top: 50%` with a `translateY(-50%)`, and the reader asked for it back at the top of the edge.
Inside the single `@media (max-width: 900px)` block each of those three is answered by the same selector written later
— the sidebar back to `display: flex`, the desktop closer box stood down before the drawer's own rule shows it, the tab
hidden — so a phone page is Stage 119's shell whatever this browser remembered from a wider window, and there is still
exactly one `@media` rule in the file (*the count became two in **[Stage 134](STAGE-HISTORY.md#stage-134)** — a 520px
block for the project page's field row; the fold's rules are still answered inside the 900px block*). Both controls sit inside `html.js`, and the default `display: none` line carries
four names now: the hamburger, the backdrop, the closer and the tab.

`app.js` owns the state, in `localStorage.pts-sidebar` — the third per-browser key beside `pts-theme` and `pts-accent`,
read once immediately after the document is marked `js` and before any control is wired, so a remembered fold never
paints a sidebar for a frame and then removes it. One class, `body.sidebar-hidden`, is the state; one `toggleSidebar()`
is the press, handed to both buttons; and its first line is `if (!isWide()) return;`, which is what leaves Stage 128's
drawer path alone — below 900px the closer's other listener, `closeNav()`, is the only one that does anything.
`nameSidebarControls()` keeps the two accessible names honest on load and on every `(min-width: 901px)` change: the
closer is *Hide sidebar* on a wide page and *Close navigation* on a narrow one, and the tab — the control that is always
there to be asked at desktop width — carries the `aria-expanded`, which the closer has never had. Every storage touch
is inside a `try`, so a browser that blocks `localStorage` loses the memory and keeps the button. Nothing folds by
itself: no width, page or visit collapses the column except a press.

No route, no table, no schema change (Flyway stays **v22**); Kotlin, CSS and JS all changed, so **Ctrl+F5** once.
Verified at the time: **749 tests / 51 classes** (`ShellRenderTest` 11 → 14 — the tab's markup and placement, the fold and its
breakpoint replies with the one-`@media` count (*[Stage 134](STAGE-HISTORY.md#stage-134) rewrote that pin to name both
widths — 900px and 520px*), and the one-key/read-before-paint rule in the script, plus two existing
cases rewritten because they had become untrue), **248 throwaway HTTP checks** over ten authenticated pages, the login
page, the served stylesheet, script and jar, **41 synthetic-DOM JS checks**, and **43 live read-back checks**. That the
corner chip sits well over the logo and caption, and that the tab reads as part of the window edge, is the reader's to
confirm on a signed-in wide page — every check here is markup, cascade and behaviour, not a picture. Live at the
time: 2026-11-09, PID **11348**, jar **1974696** bytes. The one property this section states as Stage 131 shipped it
rather than as Stage 130 wrote it is the tab's `top`; the shell as it now stands is in the next section.

### The way back is placed by the top of the edge, not its middle (Stage 131)

Stage 130 centred the reopen tab on the window's left edge — `top: 50%` with `transform: translateY(-50%)` — and the
reader asked for it where the button that hid the column is. `html.js body.sidebar-hidden .sidebar-tab` now reads
`position: fixed; top: 10px; left: 0; z-index: 50`: the `10px` is the offset `html.js .nav-close` is given inside the
sidebar, and the `transform` is gone rather than replaced, because a rule that still carries one has placed the box by
its middle again under a different name. `ShellRenderTest` pins both halves — the number must be the closer's number,
compared by capture rather than written twice, and the words `top: 50%`, `translateY` and `transform` must not appear in
the tab's rule at all.

Nothing else about the control moved, and the rest of this shell is Stage 130's exactly as the section above describes
it: the same 22×72px strip with its left border off and its right corners rounded, printed by `sidebarTab()` as a
sibling after the `<aside>` with one stroke glyph, the same single `toggleSidebar()` and `pts-sidebar` read before the
first paint, the same three desktop rules above the one breakpoint and their three identical replies inside it. CSS and
one test case changed; no Kotlin, no route, no table, no schema (Flyway stays **v22**), and no second breakpoint — the
fold has one width to be true at and the tab now has one height to be read at. (*[Stage 134](STAGE-HISTORY.md#stage-134)
added a second breakpoint — 520px, for the project page's field row; the fold still has one width to be true at.*)

**Verified: 749 tests / 51 classes / 0 failures** (the same 14 `ShellRenderTest` cases, with the fold's geometry
assertions rewritten rather than a fifteenth added), **252 throwaway HTTP checks**, **41 synthetic-DOM JS checks**
re-run against an untouched script, and **48 live read-back checks** — those last reading only the log bytes the new
boot wrote, from an offset recorded before the restart. Live since 2026-11-11, PID **3140**, jar **1974818** bytes.
**`app.css` changed, so Ctrl+F5 once.** That the tab now reads as the closer's answer, at the top of the edge rather
than as a mark in the middle of it, is the reader's to confirm on a signed-in wide page.

### A named scale, an accent ink, and one focus ring (Stage 132)

There is now one place a size lives. `base.css` — the file every page loads, and the only file the login page loads —
opens with a table: `--sp-1`…`--sp-8` (4→32px) for spacing, `--r-xs`…`--r-xl` plus `--r-pill`/`--r-round` for corners,
`--fs-1`…`--fs-15` (9px→22px) for type, and two face stacks, `--font-ui` (Inter first, `"Segoe UI"` immediately behind
it, **no font file shipped**) and `--font-mono`. `app.css` asks that table for its values in 402 places. The ramp is per
family, so 14px of *padding* is not the type step called `--fs-11` and stays a number; the 170 geometry declarations
between the steps are left literal on purpose, which keeps the app's distance from a clean grid countable and makes
narrowing the ramp a design decision rather than a rename. `DesignTokensTest` fails a declaration that writes out a
pixel its own family already names, and `ShellRenderTest`/`TaskBoardRenderTest` pin a shape as a token *plus* the step it
stands for, so retyping the table cannot keep a passing test that claims a size.

Colour is now split by job. `--accent-strong` fills a box that has white words on it; `--accent-ink` is what an accent
hue's *own* words are drawn in. They were the same value, which is how a dark page came to be asked for dark green on
darker green at 1.45:1; each of the three hues now lifts its ink for the dark theme
(`#33500b`→`#85a25d`, `#1a4f8a`→`#689dd8`, `#8a5a06`→`#c0903c`) and no rule paints text or an outline with the fill.
Every one of the six accent/theme blocks also declares `--focus-ring: var(--accent-ink)`, and that repetition is
load-bearing: a custom property resolves against the element's own cascade, so a single `:root` declaration loses to
`base.css`'s `html[data-theme="dark"]` rule and every dark page would ring in green whatever hue it wore. `--muted` and
`--note` — captions and placeholder text — were measured with the same WCAG arithmetic and darkened to 4.86:1 and 4.87:1.

`base.css` ends with one universal `:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }`. A
click draws nothing; a keyboard draws the page's own accent. It is universal and class-free, so a control that already
drew its own ring — a kanban card's opener, a bucket swatch, the login toggle — keeps the one it has, and everything
else reachable by Tab gains one. It is in `base.css` because the login page loads `base.css` alone, and for the same
reason the ring token has a literal value there.

Nothing else moved: no Kotlin, no route, no table, no schema (Flyway stays **v22**), no second `@media`
(*[Stage 134](STAGE-HISTORY.md#stage-134) later added one — 520px, for the project page's field row*), and the `app.js`
that drives the fold of Stages 119–131 is byte-identical to what Stage 131 shipped. **Verified: 757 tests / 52 classes**
(eight new `DesignTokensTest` cases, five rewritten CSS pins), **92 throwaway HTTP checks**, and a headless-Chrome
before/after pass over 56 element states × 23 properties that finds **no geometry difference at all** — the only two
changed lines are the two `font-family` declarations. Live since 2026-11-11, PID **21024**, jar **1976964** bytes;
**both stylesheets changed, so Ctrl+F5 once, on the login page as well as the app.** That the Inter-first stack reads
well where Inter is actually installed is the reader's to see: this machine does not have it, so nothing here could
measure it.

### A table of times, and the five moments that now take one (Stage 133)

There is now one place a length of time lives. `base.css` — the file every page loads — carries `--dur-1: 120ms`
and `--dur-2: 200ms` beside two curves, `--ease-std` for a state answering a pointer and `--ease-out` for
something arriving. Neither duration was chosen: before this stage `app.css` held exactly five motion
declarations and no animation at all, and they ran `120ms` and `.2s`, so the table names the two lengths the
stylesheet already had. `MotionTest` fails any rule that writes a time out instead of asking for one.

Five kinds of motion, ten declarations. **A control under a pointer** is one rule with a 28-name selector list
(*29 since [Stage 134](STAGE-HISTORY.md#stage-134): `.mtab` joined it*) —
nav items, buttons, cards, filter tabs, row links, a team-table row's cells — travelling `background-color`,
`border-color`, `color`, `box-shadow` and `transform`; the list is a list of controls that already change colour
under a pointer, and the test reads it back against the `:hover` rules that exist, so a name added without one
fails rather than silently doing nothing. `button.submit` is not in it because it is styled in `base.css`, where
its own transition and the app's single press rule live. **A popup arriving**: `.modal` fades while its scrim
goes soft under `backdrop-filter: blur(3px)` and `.modal-content` rises; `.mpop`, a panel beside a row rather than
an overlay, only fades. **A banner a save earned**: `.flash` slides down. **A card in the hand**:
`.kcard.kcard-drag`, dimmed to `.85`, tilted `.4deg`, lifted 2px, with a darker shadow for the dark theme. **A
page that has just changed**: `.content` fades in once, on load.

Two of those decisions are structural and worth keeping. Entrances are `animation`, never `transition`, because
the seven scripts that open a popup do it by setting `display: 'block'` — a node arriving from `display: none`
has no previous state for a transition to travel from, and an animation runs on exactly the beat those scripts
are on. Closes therefore stay instant, since an exit animation would mean every one of them waiting on an
`animationend`. And the view fade animates **opacity alone**: a transform would make `.content` the containing
block of every fixed descendant for the length of the fade, so the first fixed element anybody puts in there would
slide on every page load. "Not on every render" is honoured by omission — `[data-live-region]` keeps the dimming
Stage 39 gave a refresh and is given no animation, because a board re-rendering inside a page is not a view
change.

The drag lift is the one motion that needed JavaScript. `kanban.js` puts `.kcard-drag` on the card **before** it
calls `dataTransfer.setData(...)`, because the browser paints the drag image from that node the instant the
`dragstart` handler returns: a lift added afterwards stays behind in the column and the thing in the hand is an
unlifted card. `dragend` takes it off, falling back to the remembered card (`cardOf(e.target) || dragging`) since
the event fires on whatever node the drag started from — in practice the card's title text, sometimes a column.

`base.css` ends with the app's only `!important`: `@media (prefers-reduced-motion: reduce) { *, *::before,
*::after { animation-duration: .01ms !important; animation-iteration-count: 1 !important; transition-duration:
.01ms !important; } }`. Three `!important` where the file had none, because a setting a reader asked for has to
outrank every rule that follows, and it is the last rule in the file so it outranks by source order too. The
durations are `.01ms` rather than `0s` so a `transitionend` or `animationend` still fires and no script that ever
waits on one can hang; the iteration count is there because a short duration does not stop a looping animation,
it only makes each loop short. It is in `base.css` because the login page links that file alone.

Nothing else moved: no Kotlin, no route, no table, no schema (Flyway stays **v22**), no second `@media` in
`app.css` (*[Stage 134](STAGE-HISTORY.md#stage-134) added the 520px field-row block and moved `MotionTest`'s pin to
name both widths*), and the six rules this stage added are the only ones — 768 → 774, with 789 shared rule instances
compared declaration by declaration against the pre-stage stylesheets and every non-motion value equal.
**Verified: 765 tests / 53 classes** (eight new `MotionTest` cases), a `var()`-expanding geometry diff, a
synthetic-DOM harness that runs the shipped `kanban.js` and its pre-stage copy side by side, and a headless-Chrome
pass over a 50-element fixture at seven widths from 1200px to 360px that finds every rect and computed style
equal except the intended lift and blur. Live since 2026-11-11, PID **16420**, jar **1980015** bytes; **both
stylesheets and `kanban.js` changed, so Ctrl+F5 once.** Whether 120ms and 200ms are the right *feel*, and whether
reduced motion does what the block says on a machine whose setting is actually on, are the reader's to see — this
machine's `matchMedia('(prefers-reduced-motion: reduce)')` is `false`, so what was proven is the declarations and
that they win.

### A phone gets its own row of tabs, a search page, a board it can swipe, and a KPI row that folds (Stage 134)

The shell now has a second navigation, printed on every authenticated page and shown only below 900px:
`nav#mobileTabs`, a fixed bar at the foot of the page — four links (*Home* → `/app`, the Dashboard; *My tasks* →
`/app/assigned-tasks`, *To-do* → `/app/todo`, *Search* → `/app/search`) and one button (*Menu*). The links come from
`MOBILE_TABS` through `mobileTabsFor`, which filters them by the same `NAV_PERMISSIONS` the sidebar reads — a no-op
for today's four, kept for the fifth tab anybody adds later. The active tab is named by the page's own `section`,
the same word the sidebar's `.nav-item active` is chosen by, and wears `aria-current="page"`. *Menu* is
`button#mtabMenu` with `aria-controls="appSidebar"`: it has no destination, it opens the Stage 119 drawer, and
`app.js` wires it into the one `setNav` / `openNav(from)` / `closeNav` the hamburger uses — `openedBy` returns focus
to whichever control opened the drawer, and both controls' `aria-expanded` is kept in step by that one function.
The bar sits at `z-index: 54`, under the scrim's 55, so an open drawer still has exactly one close path (Stage
128's). `env(safe-area-inset-bottom)` is padding on the bar and on `.main`'s `padding-bottom: calc(76px + …)`.
The 76px is *clearance*, not the bar's height: measured at 375px the bar is 65px — 6px of its own padding twice, a
1px top border and a 51.6px tab (a 20px glyph, a 3px gap, a 12.6px caption line at `--fs-4` over 1.2, and `--sp-2`
above and below it) — and the 11px left over is room, so the fixed bar never sits on the last row of a sheet and
that row is never flush against it. A phone without a
gesture strip gets `env()` resolving to 0. The active tab's word is `--accent-ink` on `--accent-soft` (Stage 132's
ink, never the fill), and `.mtab` rides Stage 133's one pointer rule — its 28 names became 29.

The *Search* tab lands on a page of its own, `GET /app/search?q=…` — deliberately not a `sectionRoute`, because that
helper never sees the query and here the query *is* the page. A plain GET form means the words are in the address, a
shared link opens the same results, and a scriptless browser gets the same page (nothing focuses the box for them;
`autofocus` is printed only when the query is non-blank). `searchGroups(query, employeeNumber)` looks across six
groups — projects, project cards, Other-tasks cards, lessons, the reader's own to-do, people — with `SEARCH_MIN = 2`
and titles cut at 90 characters on a word boundary. The reader's own rights are the only filter: to-do rows are
`owner == employeeNumber` and nobody else's; archived cards are not results; the Other-tasks group is gated by
`SEARCH_PERMISSIONS`, which reads `NAV_PERMISSIONS["other-tasks"]` rather than naming chips a second time — the gate
is about whose *board* those rows belong to, not about a door that would refuse: `/app/other-tasks` answers every
signed-in reader, an ordinary account on their own slice (Stage 51), while these rows are the shared backlog that
only the sidebar's chips open; lessons are read through `LessonService.board().active`, the sheet's own reading.
Every result links to the page the row already lives on — `/app/projects/{id}`, `?edit={id}` popups,
`/app/other-tasks`, `/app/lessons`, `/app/todo`, `/app/team`. One sentence prints in all three states (under the
two-letter floor, which includes the empty box; no answer; an answer with its count), because a search page silent
on an empty result is indistinguishable from one that broke — and a page that says *nothing matches* for a word it
never looked for is telling the reader a falsehood rather than keeping quiet. Results are the house markup — `.card-panel` groups of two-line
`.row-link`s — a list rather than a table, so it wraps at any width without Stage 120's scroll.

Below 900px the boards are a swipe: `.kanban` and `.kanban-lanes` become a flex row (`grid-template-columns: none`
neutralises the desktop tracks), `overflow-x: auto` with `scroll-snap-type: x mandatory`, each column and the dashed
add-a-column box on `flex: 0 0 88%` with `scroll-snap-align: start`. 88% rather than 100% because the sliver of the
next column is the only affordance a swipeable row has. This replaces Stage 119's two-up wrap; both boards keep
their own gaps, which flex honours exactly as grid did.

The project page's field row — the checklist's "KPI row", the reader's naming — takes two fields to a row at
900px and one below 520px (`flex: 1 1 100%`), and **both copies of it fold**: management's editable `.edit-strip`
(ten cells, `flex: 1 1 calc(50% - 9px)`, half its 18px column gap) and the read-only `edit-strip detail-strip` every
other reader gets (nine cells, `calc(50% - 7px)`, half its 14px gap). The editable rule comes first in each block,
so the read-only one — equal in specificity, later in source — is what decides the 7px on the element carrying both
classes. Every weight selector is answered rather than a bare `.ef`, because `.detail-strip .ef.cat-ef` is (0,3,0)
and would outrank a (0,2,0) rule however late it sat: five selectors for the editable copy, nine for the read-only
one, each count pinned against the desktop rules it answers. Naming only `.detail-strip` was this stage's first
pass, and a browser measurement caught it — at 375px management's ten cells came out 7–20px apiece, because
`flex: 1.3 1 0` is a *zero* basis, so every cell fits one line at any width and simply divides it, and the controls
inside them pushed the document 19px past its own width. The read-only copy was never at risk: its values
ellipsize, where an input has an intrinsic size it will not give up. The 520px block is `app.css`'s second `@media`
and the last rule in the file: both queries are `max-width`, so the narrower wins by source order at 480px.
`MotionTest` pins both widths by name rather than a count. The calendar's month grid — `table.calendar-grid`,
which carries no `grid` class — joins Stage 120's scroll rule as `.card-panel:has(table.calendar-grid)`; the floors
themselves are untouched.

**Verified: 779 tests / 54 classes / 0 failures** — `SearchRenderTest` new (nine cases), `ShellRenderTest` 14 → 19,
`MotionTest`'s width pin rewritten. Five full runs: the first four printed every test PASSED and then died flushing
Gradle's binary results, so the console count was the evidence; the fifth (2026-11-16) finished `BUILD SUCCESSFUL`
and left 54 XML files whose own totals agree — 779 tests, 0 failures, 0 errors, 0 skipped. **Live** since
2026-11-16 09:12 (PID **12248**, jar **2,010,630** bytes, up from the stage's first deploy at 2,009,972 and Stage
133's 1,980,015). No migration (Flyway stays **v22**). Both stylesheets, `app.js`, `kanban.js` and the Kotlin
changed, so **Ctrl+F5 once**.

The phone's geometry was measured in a browser as well as parsed: both copies of the field row folding at
375/520/700/900 with the document's `scrollWidth` equal to its `clientWidth` at every one, the desktop at 1280
unmoved, both boards snapping, the drawer opening and closing, and the bar 65px tall under 76px of clearance.
Whether the bar reads right at the thumb, the swipe's settle and the search page's groups on real data is still the
reader's to see.

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
route/service migration to the latest stage, 2026-11-13's phone close-out — a bottom row of tabs, a search page, a
board the thumb swipes and a KPI row that folds (live, no migration,
Kotlin + CSS + JS + test). The middle column is that section's own
heading text; the right column is its heading's parenthesised tail: its date and status, whether
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
| [115](STAGE-HISTORY.md#stage-115) | The tab is titled MPTS | 2026-11-03, live, no migration, Kotlin + test |
| [116](STAGE-HISTORY.md#stage-116) | Lesson learnt drops its archive, and the assignment notice is verified end to end | 2026-11-03, live, no migration, Kotlin + CSS + test |
| [117](STAGE-HISTORY.md#stage-117) | A new employee's popup files them under a department, and only an owner or administrator may choose it | 2026-11-03, live, no migration, Kotlin + test |
| [118](STAGE-HISTORY.md#stage-118) | Access control's People sheet files a person under a department, and only the all-chip pair may move it | 2026-11-03, live, no migration, Kotlin + test |
| [119](STAGE-HISTORY.md#stage-119) | The shell folds to a drawer below 900px, and a card board keeps two columns | 2026-11-03, live, no migration, Kotlin + CSS + JS + test |
| [120](STAGE-HISTORY.md#stage-120) | A dense sheet keeps a readable width and scrolls instead of breaking words into letters | 2026-11-03, live, no migration, CSS + test |
| [121](STAGE-HISTORY.md#stage-121) | The login password box gets a Show/Hide button | 2026-11-03, live, no migration, Kotlin + CSS + JS + test |
| [122](STAGE-HISTORY.md#stage-122) | A project card can be assigned to two people | 2026-11-04, live, migration V22, Kotlin + test |
| [123](STAGE-HISTORY.md#stage-123) | Settings opens to every account, your own password changes there, and only the super admin sets anyone else's | 2026-11-05, live, no migration, Kotlin + test |
| [124](STAGE-HISTORY.md#stage-124) | The empty Assigned panel offers a new bucket, the shared Other-tasks column | 2026-11-06, live, no migration, Kotlin + JS + test |
| [125](STAGE-HISTORY.md#stage-125) | A blank email becomes firstname.surname@murrays.mn | 2026-11-09, live, no migration, Kotlin + test |
| [126](STAGE-HISTORY.md#stage-126) | The accounts already stored get the same address, once, at boot | 2026-11-09, live, no migration, Kotlin + test |
| [127](STAGE-HISTORY.md#stage-127) | Every account pins projects for itself, and a pinned row names the project by its ID | 2026-11-09, live, no migration, Kotlin + CSS + test |
| [128](STAGE-HISTORY.md#stage-128) | The drawer carries its own close button, and it appears only while the drawer is open | 2026-11-09, live, no migration, Kotlin + CSS + JS + test |
| [129](STAGE-HISTORY.md#stage-129) | The drawer's closer carries two drawings of its glyph, and the theme picks one | 2026-11-09, live, no migration, Kotlin + CSS + test |
| [130](STAGE-HISTORY.md#stage-130) | A desktop page folds the sidebar away, and a tab on the left edge brings it back | 2026-11-09, live, no migration, Kotlin + CSS + JS + test |
| [131](STAGE-HISTORY.md#stage-131) | The way back from that fold stays at the top of the edge, level with the button that hid the panel | 2026-11-11, live, no migration, CSS + test |
| [132](STAGE-HISTORY.md#stage-132) | A named scale, an accent ink that is not the fill, and one focus ring for every control | 2026-11-11, live, no migration, CSS + test |
| [133](STAGE-HISTORY.md#stage-133) | A table of times, and the five moments on the page that now take one | 2026-11-11, live, no migration, CSS + JS + test |
| [134](STAGE-HISTORY.md#stage-134) | A phone gets its own row of tabs, a search page, a board it can swipe, and a KPI row that folds | 2026-11-13, live, no migration, Kotlin + CSS + JS + test |

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
  uses email, and a reset still means the super admin setting somebody else's password — Stage 123's
  own-password form is a change, not a reset)
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
