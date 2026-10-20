-- ============================================================================
-- Murray PTS — V2 due-date alert ledger (SQLite)
-- ----------------------------------------------------------------------------
-- Backs the daily "this is not finished and the due date is arriving" email
-- (service/DueDateAlertService). The table is a ledger, not a queue: one row per
-- (what, who, day) written when a sweep tries to deliver, so
--
--   * nobody receives the same alert twice on the same day, however many times the
--     process restarts or an operator presses "run now";
--   * the attempt is auditable afterwards, including the failures — `delivered = 0`
--     with an `error` is exactly the case an operator needs to see.
--
-- alert_date is a calendar day in the app's clock (UTC, because the launchers set
-- -Duser.timezone=UTC), not a timestamp, which is what makes the UNIQUE key the
-- "once per day" rule.
-- ============================================================================

CREATE TABLE due_date_alerts (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    kind             TEXT    NOT NULL,                    -- PROJECT | TASK
    item_id          INTEGER NOT NULL,                    -- projects.id / tasks.id
    recipient_emp_no TEXT    NOT NULL
                     REFERENCES team_members (employee_number) ON DELETE CASCADE,
    alert_date       TEXT    NOT NULL,                    -- yyyy-MM-dd, the day this alert belongs to
    subject          TEXT    NOT NULL DEFAULT '',
    body             TEXT    NOT NULL DEFAULT '',         -- rendered message, kept for the audit trail
    due              TEXT    NOT NULL DEFAULT '',         -- the due date that triggered it
    delivered        INTEGER NOT NULL DEFAULT 0,          -- 1 = SMTP accepted, 0 = log only / failed
    channel          TEXT    NOT NULL DEFAULT 'email',    -- email | log
    error            TEXT    NOT NULL DEFAULT '',         -- delivery failure reason
    sent_at          TEXT    NOT NULL,                    -- ISO-8601 UTC instant of the attempt
    UNIQUE (kind, item_id, recipient_emp_no, alert_date)
);

CREATE INDEX idx_due_alerts_recipient ON due_date_alerts (recipient_emp_no, alert_date);
