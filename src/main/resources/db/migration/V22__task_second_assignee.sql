-- ============================================================================
-- Murray PTS — V22 a project card may name a second person (SQLite)
-- ----------------------------------------------------------------------------
-- Stage 122. The user asked that *the project tasks can be assigned to 2 people*. Until now a card held one
-- `assignee_emp_no`: the person whose day it books, whose popup opens writable, whose name the card prints and
-- whose inbox hears about it.
--
-- This column is the **second** name on the same card, and nothing else moves. It is nullable, so every row
-- written before this migration keeps exactly the one owner it had, and the two are deliberately columns of
-- `tasks` rather than a `task_assignees` join table: the app's rules are written around *the holder* (the
-- three date/priority fields, the ✓ Done button, the checklist and the log), and a second person is another
-- holder of the same card rather than a new kind of row. Two is the number the user asked for; the column says
-- so where a join table would promise N.
--
-- It references the roster for the same reason the first one does — a name that is not an employee is not a
-- holder — and SET NULL on delete, so removing an account leaves the card with its other person rather than
-- removing the work with it.
--
-- The second name is **project-only**. A card on the Other tasks board is filed under a column and belongs to
-- one person at a time (see TaskService.resolve); the service clears this column when a card crosses over.
-- ============================================================================

ALTER TABLE tasks ADD COLUMN assignee2_emp_no VARCHAR(32)
    REFERENCES team_members (employee_number) ON DELETE SET NULL;
