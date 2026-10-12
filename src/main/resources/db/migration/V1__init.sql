-- ============================================================================
-- Murray PTS — V1 baseline schema (SQLite)
-- ----------------------------------------------------------------------------
-- Managed by Flyway (see db/Migrations.kt). Do NOT edit an applied migration;
-- add V2__*.sql instead. Tables are created in foreign-key-safe order.
--
-- Conventions:
--   * employee_number (TEXT, 'EMP0000') is the natural key for people, shared by
--     team_members, users, and every *_emp_no reference.
--   * Timestamps are ISO-8601 TEXT (UTC) to match the legacy string dates and
--     keep SQLite tooling simple.
--   * SQLite enforces FKs only when `PRAGMA foreign_keys = ON`; DatabaseFactory
--     sets that per connection (Hikari connectionInitSql).
-- ============================================================================

-- Catalog of positions. permission_level drives authorization (Stage 5).
CREATE TABLE positions (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT    NOT NULL UNIQUE,
    permission_level TEXT    NOT NULL DEFAULT 'Viewer',  -- Viewer | Manager | Admin
    description      TEXT    NOT NULL DEFAULT ''
);

-- Canonical staff roster (23 people). Every person referenced anywhere lives here.
CREATE TABLE team_members (
    employee_number TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    department      TEXT NOT NULL DEFAULT '',
    position        TEXT NOT NULL DEFAULT ''
);

-- Login accounts. 1:1 with a team member by employee_number.
CREATE TABLE users (
    employee_number      TEXT PRIMARY KEY REFERENCES team_members (employee_number) ON DELETE CASCADE,
    name                 TEXT    NOT NULL,
    password_hash        TEXT    NOT NULL DEFAULT '',   -- bcrypt; empty = legacy, must set
    access               TEXT    NOT NULL DEFAULT 'Viewer', -- account-level override: Viewer|Manager|Admin
    email                TEXT    NOT NULL DEFAULT '',
    position_id          INTEGER REFERENCES positions (id) ON DELETE SET NULL,
    is_super_admin       INTEGER NOT NULL DEFAULT 0,    -- 1 = EMP0000-style, undeletable
    must_change_password INTEGER NOT NULL DEFAULT 0,
    created_at           TEXT    NOT NULL,
    updated_at           TEXT    NOT NULL
);
CREATE INDEX idx_users_position ON users (position_id);

-- Projects. manager_emp_no is a real FK now (legacy stored the manager's NAME).
CREATE TABLE projects (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    code           TEXT    NOT NULL UNIQUE,
    name           TEXT    NOT NULL,
    status         TEXT    NOT NULL DEFAULT 'Not started',
    progress       INTEGER NOT NULL DEFAULT 0,
    manager_emp_no TEXT    REFERENCES team_members (employee_number) ON DELETE SET NULL,
    due            TEXT    NOT NULL DEFAULT '',
    type           TEXT    NOT NULL DEFAULT '',
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL
);
CREATE INDEX idx_projects_manager ON projects (manager_emp_no);
CREATE INDEX idx_projects_type ON projects (type);

-- Project ↔ assignee many-to-many (legacy: List<String> of employee numbers).
CREATE TABLE project_assignees (
    project_id      INTEGER NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    employee_number TEXT    NOT NULL REFERENCES team_members (employee_number) ON DELETE CASCADE,
    PRIMARY KEY (project_id, employee_number)
);

-- Kanban columns for the other-tasks board (legacy: buckets: List<String>).
CREATE TABLE buckets (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    name     TEXT    NOT NULL,
    position INTEGER NOT NULL DEFAULT 0
);

-- Tasks. One shape backs both boards; `kind` discriminates.
-- project_id / bucket_id are nullable FKs (legacy matched by NAME).
CREATE TABLE tasks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    kind            TEXT    NOT NULL DEFAULT 'ASSIGNED',  -- ASSIGNED | OTHER
    title           TEXT    NOT NULL,
    project_id      INTEGER REFERENCES projects (id) ON DELETE SET NULL,
    due             TEXT    NOT NULL DEFAULT '',
    priority        TEXT    NOT NULL DEFAULT 'Medium',
    assignee_emp_no TEXT    REFERENCES team_members (employee_number) ON DELETE SET NULL,
    status          TEXT    NOT NULL DEFAULT 'Not started',
    bucket_id       INTEGER REFERENCES buckets (id) ON DELETE SET NULL,
    started         TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX idx_tasks_kind ON tasks (kind);
CREATE INDEX idx_tasks_project ON tasks (project_id);
CREATE INDEX idx_tasks_assignee ON tasks (assignee_emp_no);
CREATE INDEX idx_tasks_bucket ON tasks (bucket_id);

-- Resource forecasts (read-only seed content). project_id nullable: a forecast may
-- reference a project name that no longer exists.
CREATE TABLE forecasts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER REFERENCES projects (id) ON DELETE SET NULL,
    period     TEXT NOT NULL DEFAULT '',
    hours      TEXT NOT NULL DEFAULT '',
    note       TEXT NOT NULL DEFAULT ''
);

-- Lessons learned (read-only seed content).
CREATE TABLE lessons (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT NOT NULL,
    project_id INTEGER REFERENCES projects (id) ON DELETE SET NULL,
    summary    TEXT NOT NULL DEFAULT ''
);

-- Personal to-do lists. `position` captures drag-to-reorder (legacy: array order).
CREATE TABLE todo_lists (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL,
    done           INTEGER NOT NULL DEFAULT 0,
    owner_emp_no   TEXT    NOT NULL REFERENCES team_members (employee_number) ON DELETE CASCADE,
    due            TEXT    NOT NULL DEFAULT '',
    position       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_todo_lists_owner ON todo_lists (owner_emp_no);

CREATE TABLE todo_items (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    title        TEXT    NOT NULL,
    done         INTEGER NOT NULL DEFAULT 0,
    list_id      INTEGER NOT NULL REFERENCES todo_lists (id) ON DELETE CASCADE,
    owner_emp_no TEXT    NOT NULL REFERENCES team_members (employee_number) ON DELETE CASCADE,
    due          TEXT    NOT NULL DEFAULT '',
    position     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_todo_items_list ON todo_items (list_id);
CREATE INDEX idx_todo_items_owner ON todo_items (owner_emp_no);

-- Per-user pinned projects (legacy: pins: Map<empNo, List<projectId>>).
CREATE TABLE pins (
    employee_number TEXT    NOT NULL REFERENCES team_members (employee_number) ON DELETE CASCADE,
    project_id      INTEGER NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    position        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (employee_number, project_id)
);

-- Persistent sessions: lets the server survive restarts and admins revoke access.
-- The cookie itself is signed by Ktor Sessions; this row is the audit/revocation copy.
CREATE TABLE sessions (
    id              TEXT PRIMARY KEY,
    employee_number TEXT NOT NULL REFERENCES users (employee_number) ON DELETE CASCADE,
    created_at      TEXT NOT NULL,
    expires_at      TEXT NOT NULL,
    user_agent      TEXT NOT NULL DEFAULT '',
    ip              TEXT NOT NULL DEFAULT ''
);
CREATE INDEX idx_sessions_emp ON sessions (employee_number);
CREATE INDEX idx_sessions_expires ON sessions (expires_at);
