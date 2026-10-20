-- ============================================================================
-- Murray PTS — V8 the forecast ledger (SQLite)
-- ----------------------------------------------------------------------------
-- The Forecasts page stops being a list of planning notes and becomes the money grid the
-- company actually keeps: one line per project per month, carrying what that project plans
-- to raise in the month, what it actually raised, and how management reads that state.
--
-- Why a new table instead of columns on `projects`:
--
--   * a project's revenue is one number and a forecast is a *series* — the same project has
--     an October line and a November line, and the page's "invoiced in previous months" is
--     the sum of the lines before the one being read. A column on `projects` could hold only
--     the latest month and would make that sum impossible to compute honestly.
--   * the grid has to keep its history: correcting August in September is the whole point of
--     a monthly ledger, and a row that is overwritten cannot be corrected.
--
-- Why not the V1 `forecasts` table:
--
--   `forecasts` is the legacy import's home for *resource* planning — a period string, an
--   hours string and a note, keyed to nothing and typed as free text. Its rows survive the
--   migration untouched and the importer still writes them; the page simply stops reading
--   them, because a money grid built on a free-text `hours` column would have to parse a
--   string like "12,400 hrs" to do arithmetic. Two shapes, two tables, one name each.
--
-- `month` is 'YYYY-MM' — the month the line *belongs* to, the one its money is counted in.
-- It is the same shape `<input type="month">` writes and reads, so a stored key needs no
-- conversion to be edited in the grid. It is compared as TEXT, which is correct for a
-- zero-padded fixed-width key and is what lets "everything before October" be a
-- `month < ?` rather than a date calculation spread across the repository.
--
-- `invoiced_month` is a separate, descriptive column, and it is the one design point here
-- worth stating plainly: the grid's month cell names **when the invoice is raised**, and it
-- does not move the money. October's line stays October's line whatever that cell says, so
-- "invoiced in previous months" stays a sum of earlier lines and cannot be quietly rewritten
-- by an unrelated keystroke in an unrelated column. Blank means "this line's own month",
-- which is what a row reads until somebody says otherwise.
--
-- `invoice_status` is a *stated* label, not a computed one: it is what management says of
-- the invoice, and it is what the row's colour follows. The amounts beside it stay the
-- app's figures; deriving the label from them would silently repaint a row the moment
-- somebody typed a number, and a partially invoiced project whose invoice is simply late is
-- a real state the money alone cannot express.
--
-- ON DELETE CASCADE: a forecast line is a fact *about* a project. When the project goes, its
-- ledger goes with it — an orphan line would have no revenue to be measured against.
--
-- The unique index is what makes the grid's save an upsert rather than a search-then-write,
-- and it is the reason one project cannot hold two lines for the same month.
-- ============================================================================

CREATE TABLE project_forecasts (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id      INTEGER NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    month           TEXT    NOT NULL,
    forecast_amount REAL    NOT NULL DEFAULT 0,
    invoiced_amount REAL    NOT NULL DEFAULT 0,
    invoiced_month  TEXT    NOT NULL DEFAULT '',
    invoice_status  TEXT    NOT NULL DEFAULT 'Not invoiced'
);

-- One line per project per month, and the page reads a whole month at a time.
CREATE UNIQUE INDEX ux_project_forecasts ON project_forecasts (project_id, month);
CREATE INDEX idx_project_forecasts_month ON project_forecasts (month);
