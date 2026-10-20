-- ============================================================================
-- Murray PTS — V9 the lesson record (SQLite)
-- ----------------------------------------------------------------------------
-- The Lesson learnt page stops being a list of notes and becomes the sheet the company
-- actually runs: one row is one person's lesson about one project, dated, with a discussion
-- state and a place to put it once the discussion has happened.
--
-- Why columns on `lessons` rather than a new table (the choice V8 made for the forecast
-- ledger): the V1 rows are already the same *kind* of fact — a piece of text about a project —
-- and the sheet keeps them. A second table would have made the page's first column a union of
-- two sources, and every write would have had to ask which one a row lived in. So the old rows
-- are widened in place, and the three columns they never had — who, when, task — stay NULL for
-- them, which is what the page reads as a blank cell rather than a guess.
--
-- `employee_number` is the account that logged the lesson, and it is not a field anybody gets
-- to choose: the sheet's employee column is a statement about who wrote it down, so the route
-- takes it from the session. It is nullable with ON DELETE SET NULL because a lesson outlives
-- the person who logged it — deleting an account must not delete company knowledge, and the row
-- then reads as unowned, which management can still tidy up.
--
-- `task_id` is an optional link to the card the lesson came from, SET NULL for the same reason:
-- a project's cards get deleted, and the lesson that came out of one is the most useful thing
-- on the sheet, not a dependent row to clean up after.
--
-- `logged_on` is 'YYYY-MM-DD' — the shape `<input type="date">` writes and reads, and the shape
-- every other date column in this database uses. It is TEXT NOT NULL DEFAULT '' rather than
-- nullable so the sheet's sort has one comparable value per row: an undated legacy row is ''
-- and sorts to the end of a newest-first list without a NULL special case in the repository.
--
-- `discussed` is the row's state on the sheet — the pill — and `archived` is where it lives:
-- an archived lesson leaves the active list and appears under the page's Archived heading,
-- where it stays readable and restorable. They are two columns and not one four-valued status
-- because they answer different questions ("has this been talked about?" and "is this still in
-- the way?"), and a lesson can be discussed *and* archived, or neither.
--
-- `lesson` is the text the cell prints. The V1 rows carried theirs as a `title` over a
-- `summary`, and the sheet has one cell for it, so the backfill below joins the pair into the
-- column the page reads and every row from here on has exactly one text. `title` and `summary`
-- stay in the table — `title` is NOT NULL with no default, so it cannot be left out of an
-- INSERT without rebuilding the table for no benefit — and nothing on the page reads them.
-- ============================================================================

ALTER TABLE lessons ADD COLUMN lesson TEXT NOT NULL DEFAULT '';
ALTER TABLE lessons ADD COLUMN employee_number VARCHAR(32)
    REFERENCES team_members (employee_number) ON DELETE SET NULL;
ALTER TABLE lessons ADD COLUMN task_id INTEGER
    REFERENCES tasks (id) ON DELETE SET NULL;
ALTER TABLE lessons ADD COLUMN logged_on TEXT NOT NULL DEFAULT '';
ALTER TABLE lessons ADD COLUMN discussed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE lessons ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;

-- The old rows' text, in the one column the sheet reads. A row that only ever had a title keeps
-- just its title; the separator and the summary only appear when there was a summary to join.
UPDATE lessons
   SET lesson = trim(title || CASE WHEN summary = '' THEN '' ELSE ' — ' || summary END)
 WHERE lesson = '';

-- The page reads one of the two lists at a time, newest first, and both filters cut a list down.
CREATE INDEX idx_lessons_archived ON lessons (archived, logged_on);
CREATE INDEX idx_lessons_employee ON lessons (employee_number);
CREATE INDEX idx_lessons_task ON lessons (task_id);
