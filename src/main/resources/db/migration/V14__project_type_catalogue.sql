-- ============================================================================
-- Murray PTS — V14 the project category catalogue (SQLite)
-- ----------------------------------------------------------------------------
-- Since V3 the company's categories have been nine strings compiled into domain/Project.kt. That made
-- the letter a category's project numbers lead with (`S-01` for Site projects), its chip colour, its
-- Forecasts panel and its Settings lane defaults all agree without a lookup — and it also meant the only
-- way to add a category was to change code. Management asked for the list itself: the Settings page now
-- carries a *Project types* panel with an add row and a pencil and a trash on every line.
--
-- This table is that list. One row is one category: `code` is the single upper-case letter its project
-- numbers lead with, `name` is the label stored on `projects.type`, and `position` is the order the
-- boards group in — the Projects panels, the Forecasts tabs and the create popup's picker all read top
-- to bottom, so the order is a fact about the business rather than an accident of a source file.
--
-- `code` is the PRIMARY KEY, which is what makes a project number unambiguous: two categories cannot
-- share a letter, so no two projects can be handed the same number. `name` is deliberately NOT unique at
-- the index level — the service compares it trimmed and case-insensitively, exactly like the duplicate
-- lane names on a board, and an index could only catch the exact spelling.
--
-- What this migration deliberately does not do:
--
--   * add a foreign key from `projects.type`. The column holds a label, and rows written before a
--     category existed, or imported from the legacy JSON, may hold a label this table has never seen.
--     A key would fail the import and force a back-fill of invented categories; instead the view layer
--     prints an unknown label with its raw text and no letter, which is what it does today.
--   * rewrite anything. `projects.type`, `type_default_buckets.type` and every stored project code stay
--     exactly as they are, because the seed below spells the nine categories the same way code used to.
--
-- The seed is the nine categories from SHIPPED_PROJECT_TYPES, in the same order and with the same
-- letters, so a database that has just been migrated behaves precisely as it did a minute earlier.
-- Unlike V12 and V13, which start empty so nothing changes until somebody chooses, this one has to be
-- populated: an empty catalogue would leave the create-project form with no category to file under.
-- ============================================================================

CREATE TABLE project_types (
    code     TEXT    NOT NULL PRIMARY KEY,
    name     TEXT    NOT NULL,
    position INTEGER NOT NULL DEFAULT 0
);

INSERT INTO project_types (code, name, position) VALUES
    ('M', 'Fabrication/Manufacturing',       0),
    ('R', 'Repair/Maintenance/other services', 1),
    ('S', 'Site projects',                   2),
    ('L', 'Labor supply',                    3),
    ('D', 'Distributor product sales',       4),
    ('E', 'Engineering',                     5),
    ('F', 'Internal works',                  6),
    ('G', 'Goods sales',                     7),
    ('O', 'Other',                           8);

-- The boards ask for the whole catalogue in print order; nine rows means this index is about keeping the
-- ORDER BY honest rather than about speed.
CREATE INDEX idx_project_types_position ON project_types (position);
