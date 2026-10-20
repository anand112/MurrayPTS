-- ============================================================================
-- Murray PTS — V7 per-project task buckets (SQLite)
-- ----------------------------------------------------------------------------
-- A project's board stops being a status progression and becomes the lanes the work falls
-- into: every new project starts with **Engineering**, **Procurement** and **Production**,
-- and whoever runs the project adds lanes of their own. The status columns stay where they
-- always were — on the Assigned tasks board, where a person reads "what is on me now" across
-- every project — so this table is a second grouping, not a replacement for `tasks.status`.
--
-- Why the lanes belong to a project rather than to the company:
--
--   * the ask is about a project being *created* with its lanes, which is a fact about that
--     project. A shared list would show a Maintenance job a Procurement column it never has.
--   * a project may rename and extend its own set without changing anybody else's board.
--
-- Why a new table next to `buckets` instead of a `project_id` column on it:
--
--   `buckets` is the Other tasks board's column list, and `tasks.bucket_id` points at it for
--   `kind = 'OTHER'` rows. Folding project lanes into the same table would mean every read of
--   the shared list — and the legacy importer's name→id map, which assumes names are unique —
--   had to remember to filter. One column per board is the cheaper promise to keep: an
--   assigned card's lane is `project_bucket_id`, an other-task card's column is `bucket_id`,
--   and neither reader can mistake the other's rows for its own.
--
-- `position` is the append counter the checklist, notes, to-do items and buckets already use,
-- so lane order survives two people adding one in the same second.
--
-- `tasks.project_bucket_id` is nullable on purpose: a card nobody has filed, or one whose lane
-- was removed, is not lost — the project board gives it an **Unsorted** column so it stays
-- visible and draggable rather than vanishing with the lane it was pointing at. ON DELETE SET
-- NULL is what makes removing a lane a one-statement operation.
--
-- The INSERT below gives every project that already exists the three default lanes, in that
-- order. Existing cards keep `project_bucket_id` NULL and read as Unsorted — the migration
-- cannot guess which phase a piece of work belongs to, and filing it wrongly would be worse
-- than leaving it where somebody can see it and drag it out.
-- ============================================================================

CREATE TABLE project_buckets (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,
    position   INTEGER NOT NULL DEFAULT 0
);

-- A board reads its own project's lanes, in lane order.
CREATE INDEX idx_project_buckets ON project_buckets (project_id, position);

ALTER TABLE tasks ADD COLUMN project_bucket_id INTEGER
    REFERENCES project_buckets (id) ON DELETE SET NULL;

INSERT INTO project_buckets (project_id, name, position)
SELECT p.id, lane.name, lane.position
FROM projects p
CROSS JOIN (            SELECT 'Engineering' AS name, 0 AS position
             UNION ALL  SELECT 'Procurement',         1
             UNION ALL  SELECT 'Production',          2) lane
ORDER BY p.id, lane.position;
