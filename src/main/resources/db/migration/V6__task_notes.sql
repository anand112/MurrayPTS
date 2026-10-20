-- ============================================================================
-- Murray PTS — V6 task notes (SQLite)
-- ----------------------------------------------------------------------------
-- A card can carry a running log of short notes: what happened, what is waiting on
-- whom, why a date moved. Management writes the plan (the checklist under the card,
-- V5); the person doing the work adds to this list as they go, which is the difference
-- between the two tables and the reason both exist.
--
-- Why one row per note rather than a TEXT column on `tasks`:
--
--   * **two people can write at once.** One shared blob is last-write-wins, and on a
--     card with a manager and an assignee on it that race is not hypothetical. One row
--     per note makes every add a single INSERT.
--   * **a note knows who wrote it and when.** `author_emp_no` plus `created` is the whole
--     point of a log — "the roller broke down" with nobody and no date attached is a
--     rumour, not a record.
--   * **it dies with the card**, through the same `ON DELETE CASCADE` as `task_checklist_items`,
--     so deleting a task cannot leave orphaned notes behind.
--
-- `author_emp_no` carries **no foreign key**, exactly like `notifications.actor_emp_no`:
-- a note is history, and it should still name the person who wrote it after their account
-- is deleted. The name is resolved for display at read time and falls back to the number
-- when the account is gone.
--
-- `position` is the append counter used by `task_checklist_items`, `todo_items` and
-- `buckets`, so the order does not depend on the clock's resolution; the board reads the
-- newest first, which is `position DESC`, and two notes added in the same second still
-- print in the order they were written.
--
-- `created` is `yyyy-MM-dd` in UTC, the same convention every other date in this schema
-- uses (tasks.due, tasks.started, projects.start_date). A note is a day-level fact: the
-- app never shows a time, so it never stores one.
-- ============================================================================

CREATE TABLE task_notes (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id       INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    body          TEXT    NOT NULL,
    author_emp_no TEXT    NOT NULL,
    position      INTEGER NOT NULL DEFAULT 0,
    created       TEXT    NOT NULL DEFAULT ''
);

-- Every editor render reads one card's notes, newest first.
CREATE INDEX idx_notes_task ON task_notes (task_id, position);
