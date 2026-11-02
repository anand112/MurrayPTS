-- ============================================================================
-- Murray PTS — V21 a role decides access (SQLite)
-- ----------------------------------------------------------------------------
-- Stage 110. Until now an account's rights were resolved, in this order, from `users.is_super_admin`, the
-- `permission_level` of the position it was filed under, and finally its own `users.access` word — three
-- ranks (Viewer < Manager < Admin). The user asked for the picture on Access control instead: **roles and
-- permissions**, thirteen named chips, a member of staff assigned a role, and the role deciding what they
-- may do. So the rights move off the person and onto a role row, and there are thirteen of them rather than
-- three ranks.
--
-- Two tables and one column:
--
--   * `roles`             — the catalogue: Owner, Administrator, Project Manager, Employee, plus any the
--                           sheet adds. `is_default` marks the one a new account is filed under.
--   * `role_permissions`  — the chips a role holds, one row per `Permission.key`, cascading with its role.
--   * `users.role_id`     — the role this account signs in under. It is nullable and SET NULL on delete: a
--                           role someone removes hands its holders back to the default role (the app falls
--                           back when the column is null, see RoleRepository.roleOf) rather than deleting
--                           people along with it.
--
-- The backfill maps every existing account to the role that gives it *today's* rights, resolving exactly the
-- way `Authorization.effectiveRole` did before this migration:
--
--   1. super admin                    -> Owner           (it was always Admin: the top rank)
--   2. a position's permission_level  -> Administrator / Project Manager / Employee  (level wins, even over
--                                        a higher `access` word — that was the v2 locked resolution)
--   3. otherwise, `access`            -> Administrator (Admin) / Project Manager (Manager) / Employee
--   4. anything else                  -> Employee
--
-- So nobody gains or loses a right on the day this runs; the four roles reproduce the three ranks they were
-- resolved from. `positions.permission_level` is deliberately left in place: positions become job titles
-- under the picture ("Role wins; positions become titles"), the column stops being read for authorization,
-- and dropping it would throw away a catalogue value the operator can still see and edit.
--
-- Unknown chip keys in `role_permissions` are tolerated the same way V1 tolerated an unknown
-- `projects.status`: the app names the ones it knows (`Permission.of` answers null for the rest) and a hand
-- edited row that names nothing simply grants nothing.
-- ============================================================================

CREATE TABLE roles (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL UNIQUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_default INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE role_permissions (
    role_id    INTEGER NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission TEXT    NOT NULL,
    PRIMARY KEY (role_id, permission)
);

-- The four the picture shows, in its order. Employee is the default: the role a new account starts in.
INSERT INTO roles (id, name, sort_order, is_default) VALUES
    (1, 'Owner',           1, 0),
    (2, 'Administrator',   2, 0),
    (3, 'Project Manager', 3, 0),
    (4, 'Employee',        4, 1);

-- Owner and Administrator hold all thirteen; Project Manager the nine about projects, boards and money;
-- Employee reads its colleagues' cards and nothing more.
INSERT INTO role_permissions (role_id, permission) VALUES
    (1, 'create_projects'), (1, 'edit_project_details'), (1, 'delete_projects'),
    (1, 'manage_buckets'), (1, 'edit_any_task'), (1, 'assign_tasks'),
    (1, 'view_teammates_tasks'), (1, 'view_forecasts'), (1, 'edit_forecast_amounts'),
    (1, 'manage_employees'), (1, 'manage_project_types'), (1, 'manage_email_settings'),
    (1, 'manage_roles'),

    (2, 'create_projects'), (2, 'edit_project_details'), (2, 'delete_projects'),
    (2, 'manage_buckets'), (2, 'edit_any_task'), (2, 'assign_tasks'),
    (2, 'view_teammates_tasks'), (2, 'view_forecasts'), (2, 'edit_forecast_amounts'),
    (2, 'manage_employees'), (2, 'manage_project_types'), (2, 'manage_email_settings'),
    (2, 'manage_roles'),

    (3, 'create_projects'), (3, 'edit_project_details'), (3, 'delete_projects'),
    (3, 'manage_buckets'), (3, 'edit_any_task'), (3, 'assign_tasks'),
    (3, 'view_teammates_tasks'), (3, 'view_forecasts'), (3, 'edit_forecast_amounts'),

    (4, 'view_teammates_tasks');

ALTER TABLE users ADD COLUMN role_id INTEGER REFERENCES roles (id) ON DELETE SET NULL;
CREATE INDEX idx_users_role ON users (role_id);

-- Yesterday's resolution, written onto every account that already exists.
UPDATE users
SET role_id = CASE
    WHEN is_super_admin = 1 THEN 1
    WHEN position_id IS NOT NULL THEN COALESCE(
        (SELECT CASE lower(trim(p.permission_level))
                    WHEN 'admin'   THEN 2
                    WHEN 'manager' THEN 3
                    ELSE 4
                END
           FROM positions p
          WHERE p.id = users.position_id),
        4
    )
    WHEN lower(trim(access)) = 'admin'   THEN 2
    WHEN lower(trim(access)) = 'manager' THEN 3
    ELSE 4
END;
