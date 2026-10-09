# Murray PTS — Target Architecture (v2)

This document describes the restructured layout that Stages 2–9 will build.
Nothing here is implemented yet — review and approve before code changes land.

## Goals

1. **Layered architecture**: routes → services → repositories → DB. No route touches SQL directly. No repository renders HTML.
2. **Real persistence**: SQLite via Exposed ORM + Flyway migrations, replacing single-file JSON.
3. **Hardened auth**: bcrypt password hashes, Ktor Sessions plugin with signed persistent cookies, per-route role guards via a Ktor plugin (not inline `if`s).
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
├── static/
│   ├── css/app.css                ← extracted from APP_CSS
│   ├── css/login.css              ← extracted from PAGE_CSS
│   ├── js/theme.js                ← existing
│   ├── js/kanban.js               ← existing (deduped against AssignedTasks inline)
│   ├── js/todo.js                 ← extracted from Todo.kt inline
│   ├── js/team-popup.js           ← extracted from Team.kt inline
│   ├── js/positions.js            ← extracted from AccessControl.kt inline
│   └── assets/mms-logo.png        ← moved from static/
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

Migrations live in `src/main/resources/db/migration/V1__init.sql`. Flyway runs at startup.

## Security

| Concern | Approach |
|---|---|
| Passwords | jBCrypt cost 12 (`security/PasswordHasher`). On first-run import, existing plaintext passwords are hashed and the JSON is renamed `.migrated`. `AuthService.changePassword` rehashes and revokes all of the user's sessions. |
| Sessions | **Opaque-token model (as built), not the signed-cookie `Sessions` plugin.** `PTS_SID` carries a random UUID; the authoritative record lives in the `sessions` table (`repository/SessionRepository`): employee_number FK, created_at, expires_at, user_agent, ip. Cookie stays `httpOnly`, name **preserved as `PTS_SID`**, 12h expiry from `AppConfig.sessionTtlHours`. DB rows survive restart and allow admin revocation (`revokeAllFor`). Expiry is enforced at read (`findValid`) and swept by `purgeExpired`. Chosen over the Ktor `Sessions` signed-cookie plugin because it keeps revocation and audit server-side and matches the legacy token-in-cookie shape. |
| Authorization | `security/Authorization` (RoleGuard). Effective role resolves **super-admin → Admin, else the linked position's `permissionLevel`, else the account `access`, else Viewer** (`security/Role` ordered enum). This is the v2 change: `permissionLevel` is now enforced. `canManage`/`canAdmin`/`isManagerOrAdmin` replace the ~30 inline `if (access in MANAGE_ROLES)` checks (route call-sites migrated in Stage 6). |
| CSRF | Stateless session-bound token: `security/Csrf` = base64url(HMAC-SHA256(secret, sessionId)), validated in constant time. Rotates automatically with the session; no extra storage. **Wiring into forms/JS deferred to Stage 6/7** (the token must be rendered into every POST form and attached by the fetch-based kanban/reorder handlers). |
| Headers | `web/plugin/SecurityHeaders` (installed): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`, `Permissions-Policy`, `Content-Security-Policy` (default-src 'self'; script/style-src allow 'unsafe-inline' **during transition**, tightened in Stage 7 once assets are extracted), `Strict-Transport-Security` in production. |
| Secrets | `.env` (gitignored) + `.env.example`. `AppConfig` reads system property → env → `.env` → default. `AppConfig.validate()` refuses production boot with the default/short session secret. Secret never logged (`summary()` masks it). |
| Rate limit | `security/LoginRateLimiter` — in-memory sliding window on `POST /login`, bounds from `AppConfig.loginMaxAttempts`/`loginWindowSeconds` (default 5/60s) keyed by client IP (`X-Forwarded-For` first hop, else socket peer). Verified live: 6th attempt returns "Too many failed attempts". |

### Stage 5 status (security layer)

Built, unit-tested and wired: `Role`, `Authorization`, `SessionRepository`, `LoginRateLimiter`, `Csrf`, `SecurityHeaders` plugin, `AuthService` (bcrypt verify + DB session lifecycle + `ensureSuperAdmin` bootstrap). The **headers plugin, login rate-limiting and super-admin bootstrap are live in `Application.kt` now**. 16 tests pass (role resolution, throttle window/recovery, CSRF forgery rejection, and an end-to-end `AuthServiceTest` against a real SQLite file covering bcrypt login, session resolve/revoke, revoke-on-password-change, and expiry).

**Deferred to Stage 6 (deliberate):** flipping `POST /login` from the legacy plaintext `DemoData` check to `AuthService.authenticate`, switching `currentSession()` from the in-memory `SessionStore` to `SessionRepository`, replacing inline `MANAGE_ROLES` checks with `Authorization`, and rendering/validating CSRF tokens. Reason: those routes still read/write user data through the legacy `JsonStore`. Moving auth onto the DB before the user-management routes move would create a split-brain (a user added or edited via the UI would exist in JSON but not be able to log in). Auth and the user-data persistence flip together in Stage 6 so the app is never internally inconsistent. The full security layer is already implemented and tested — Stage 6 only swaps the call-sites over.


## Behavior preservation checklist

Every item below must still work after Stage 9:

- [ ] `PTS_SID` cookie name
- [ ] Login at `/`, `POST /login`, `GET /logout`, `/app` → `/app/projects`
- [ ] All 35 route paths exactly as today
- [ ] `POST /app/tasks/status` form-encoded `id`+`status`, Referrer redirect
- [ ] `POST /app/todo/lists/reorder` with `order=csv`
- [ ] Positions endpoints return HTML `<tr>` fragments when `X-Requested-With: fetch`, 204 on delete
- [ ] `?type=` filter on projects, `?pw=PasswordStatus&who=emp` flash banner
- [ ] First-run seed data, EMP0000 super-admin always ensured, `MIN_PASSWORD_LENGTH=4`
- [ ] Password defaults to employee number for legacy users (only during import; new users must set one)
- [ ] Auto-email `first.last@murray.mn`
- [ ] Project code generation `PREFIX-NN`, task display code `T-%05d`
- [ ] Dark mode + accent toggle persisted in `localStorage` keys `pts-theme` / `pts-accent`
- [ ] Per-user pins, per-user todo ownership and list ordering
- [ ] `pts.port` / `PTS_PORT` (8080), `pts.host` / `PTS_HOST` (0.0.0.0), LAN-address startup banner
- [ ] `/static/mms-logo.png` (moved to `/static/assets/mms-logo.png` with a redirect from the old path)
- [ ] Read-only Forecasts and Lessons pages with seed content
- [ ] Team member-popup JSON payload shape

## Frontend approach (Stage 7 decision point)

Today: CSS/JS are **inlined as Kotlin string constants** (`APP_CSS`, `PAGE_CSS`, `THEME_JS`, plus per-page inline scripts). The extracted files in `src/main/resources/` are dead code.

Two options:

- **A. Serve extracted files** (cleaner, smaller HTML, browser-cacheable). Risk: the extracted `.css`/`.js` may have drifted from the inline versions; Stage 7 must diff them and treat inline as source of truth.
- **B. Keep inline, delete dead files**. Zero behavior risk, but HTML stays large and CSP `unsafe-inline` is permanent.

**Recommendation: A**, with a diff step in Stage 7. Flag if you prefer B.

## Migration plan (JSON → SQLite)

`JsonImporter` runs once at startup if `users` table is empty AND `data/pts-data.json` exists:

1. Read JSON via existing `PtsData` kotlinx-serialization classes (kept in `db/migration/legacy/`).
2. Replay the 11 `migrate()` steps in-memory so the importer sees normalized data.
3. Insert in FK-safe order: positions → team_members → users (hash plaintext passwords) → projects → project_assignees → tasks → buckets → forecasts → lessons → todo_lists → todo_items → pins.
4. Rename `data/pts-data.json` → `data/pts-data.json.migrated-<timestamp>`.
5. Log a summary: counts per table, any skipped rows.

If the JSON is corrupt, the importer logs and falls back to `DemoData.seed()` (same as today) rather than silently overwriting.

## Testing strategy (Stage 9)

- `JsonImporterTest` — imports `pts-data.json.example`, asserts row counts and EMP0000 exists with hashed password.
- `ProjectRepositoryTest` — CRUD + code generation against an in-memory SQLite.
- `UserServiceTest` — bcrypt hash/verify, role checks, email defaulting.
- `AuthFlowTest` — Ktor `testApplication`: login with correct/wrong password, session cookie issued, `/app/projects` redirects when unauthenticated.
- `TaskStatusRouteTest` — kanban drag-drop endpoint preserves Referrer redirect and permission rules.

## Out of scope (flag if you want any of these)

- HTTPS termination (assume reverse proxy)
- Multi-tenancy
- Audit log beyond `sessions` table
- Email/password reset flow
- API versioning or JSON API for a future SPA
- Replacing kotlinx.html with a template engine
- Changing the domain semantics (string-name joins → FKs is the one exception, called out above)

## Open questions before Stage 2

1. **Frontend option A or B?** (recommend A)
2. **FK-based joins OK?** They change rename behavior — today renaming a project silently breaks task links; with FKs, renames are safe but legacy JSON imports need a name→id lookup pass.
3. **Keep `DemoData.seed()` on empty DB?** Today a missing/corrupt file silently seeds demo data. With SQLite I'd prefer to seed only on explicit `PTS_SEED_DEMO=true` and otherwise show an empty state. Flag if you want the old behavior preserved.
4. **Positions enforcement**: today `Position.permissionLevel` is stored but never enforced — `users.access` is the effective role. Keep as-is, or wire positions into authorization?
