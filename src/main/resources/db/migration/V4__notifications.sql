-- ============================================================================
-- Murray PTS — V4 in-app notifications (SQLite)
-- ----------------------------------------------------------------------------
-- The bell in the top-right corner of every page reads this table. It is an inbox,
-- not a queue: a row is written when something happens to you (a manager assigns a
-- task or a project, a due-date reminder goes out) and stays until it is trimmed.
--
-- Two rules the columns exist to enforce:
--
--   * **once per day per item per person.** `dedupe_day` is the yyyy-MM-dd the row
--     belongs to, in the app clock (UTC — the launchers set -Duser.timezone), exactly
--     like due_date_alerts.alert_date. A manager who edits the same card six times
--     this afternoon produces one notification, not six, and a restart mid-afternoon
--     cannot produce two.
--   * **it survives being clicked.** `read_at` is '' while unread, so the badge count
--     is a plain string compare, and an item can be marked read from any page without
--     a second table.
--
-- `link` is always a path the server wrote (/app/projects/12, /app/assigned-tasks) and
-- the open route refuses anything that does not start with /app/, so a hand-edited row
-- cannot turn the bell into an open redirect.
--
-- recipient_emp_no cascades with the roster: deleting an account deletes its inbox.
-- actor_emp_no deliberately has no foreign key — the notification is history, and it
-- should still say who assigned it after that person's account is gone.
-- ============================================================================

CREATE TABLE notifications (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    recipient_emp_no TEXT    NOT NULL
                     REFERENCES team_members (employee_number) ON DELETE CASCADE,
    kind             TEXT    NOT NULL,                    -- TASK_ASSIGNED | PROJECT_MANAGER | DUE_DATE
    item_kind        TEXT    NOT NULL DEFAULT '',         -- TASK | PROJECT, for the icon and the target
    item_id          INTEGER NOT NULL DEFAULT 0,          -- tasks.id / projects.id
    dedupe_day       TEXT    NOT NULL,                    -- yyyy-MM-dd this notification belongs to
    title            TEXT    NOT NULL,                    -- the one line the dropdown shows
    body             TEXT    NOT NULL DEFAULT '',         -- the detail line, and the email body
    link             TEXT    NOT NULL DEFAULT '',         -- in-app path, server-written
    actor_emp_no     TEXT    NOT NULL DEFAULT '',         -- who caused it, '' for the scheduler
    mail_status      TEXT    NOT NULL DEFAULT '',         -- sent | logged | failed | no-address | ''
    created_at       TEXT    NOT NULL,                    -- ISO-8601 UTC instant
    read_at          TEXT    NOT NULL DEFAULT '',         -- '' = unread
    UNIQUE (recipient_emp_no, kind, item_kind, item_id, dedupe_day)
);

-- The dropdown reads one person's newest rows and the badge counts the unread ones.
CREATE INDEX idx_notifications_recipient ON notifications (recipient_emp_no, id);
CREATE INDEX idx_notifications_unread    ON notifications (recipient_emp_no, read_at);
