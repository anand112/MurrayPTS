-- ============================================================================
-- Murray PTS — V10 the shared six sub-categories (SQLite)
-- ----------------------------------------------------------------------------
-- A project's board stops being three phases somebody guessed at and becomes the six the company
-- plans work under: Fabrication/Manufacturing, Repair/Maintenance, Site projects, Labor supply,
-- Goods sales, Other — the same families the Projects board cuts itself by. V7 seeded Engineering,
-- Procurement and Production per project and let each manager edit that set; from here the six are
-- the *floor* every board carries, and a card on a project is filed in one of them rather than left
-- unfiled.
--
-- What this migration does:
--
--   * gives every existing project any of the six it does not already have;
--   * takes an original default off the board when nothing stands in it, which is the only case in
--     which a lane can go without work going with it;
--   * numbers the six 0..5 on every board and puts whatever else the project carries on after them,
--     so the six read as the first six columns everywhere and a lane a manager added for itself is
--     still there, in the order it was added.
--
-- What it deliberately does not do:
--
--   * delete a lane that holds a card, whether it is one of the three originals or one the project
--     added. `tasks.project_bucket_id` answers `ON DELETE SET NULL`, so dropping a column unfiles
--     everything in it; a lane named for a phase the company no longer uses, with work in it, is a
--     visible thing a manager can empty and then remove, whereas unfiled cards are not.
--   * file the cards that are currently unfiled. `project_bucket_id` stays NULL where it was NULL: the
--     migration cannot know which sub-category a piece of work belongs to, and filing it wrongly is
--     worse than leaving it in the Unsorted column where somebody can see it and drag it out. The
--     service refuses the same omission for a *new* card from here on.
--
-- The name comparison is case-sensitive and exact on purpose: a project whose manager renamed a lane
-- to "engineering" is unusual enough that folding it silently would be a guess, and the INSERT below
-- only skips a lane whose name is already on that board character for character. Such a lane simply
-- stands on after the six, which is where a name the company does not use belongs.
-- ============================================================================

-- 1. An original default with nothing filed in it goes. It is not one of the six, and the six arrive
--    below, so leaving it would print a seventh column named for a phase nobody plans under. Only an
--    empty one: see the header.
DELETE FROM project_buckets
WHERE name IN ('Engineering', 'Procurement', 'Production')
  AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.project_bucket_id = project_buckets.id);

-- 2. The six are offered by name by the popup and resolved by name, so a board that held two rows of
--    one of these names would offer one column twice and file a card in whichever row the lookup
--    happened to return. The service refuses a duplicate name on a live board (TaskService.addLane),
--    so this only cleans what older data can carry; it drops the later empty copies and keeps the
--    first, and it never touches a lane with cards in it.
DELETE FROM project_buckets
WHERE name IN ('Fabrication/Manufacturing', 'Repair/Maintenance', 'Site projects', 'Labor supply',
               'Goods sales', 'Other')
  AND id > (SELECT MIN(pb.id) FROM project_buckets pb
            WHERE pb.project_id = project_buckets.project_id AND pb.name = project_buckets.name)
  AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.project_bucket_id = project_buckets.id);

-- 3. Give every project the six it does not already carry. The position written here is a placeholder
--    in the list's own order; step 4 is what numbers the board, and it reads the six by name.
INSERT INTO project_buckets (project_id, name, position)
SELECT p.id, lane.name, lane.rn
FROM projects p
CROSS JOIN (            SELECT 'Fabrication/Manufacturing' AS name, 0 AS rn
             UNION ALL  SELECT 'Repair/Maintenance',        1
             UNION ALL  SELECT 'Site projects',             2
             UNION ALL  SELECT 'Labor supply',              3
             UNION ALL  SELECT 'Goods sales',               4
             UNION ALL  SELECT 'Other',                     5) lane
WHERE NOT EXISTS (
    SELECT 1 FROM project_buckets pb
    WHERE pb.project_id = p.id AND pb.name = lane.name
)
ORDER BY p.id, lane.rn;

-- 4a. The six take positions 0..5 on every board, in the order the picker lists them. Numbering by
--     name rather than appending means a project that already had 'Site projects' from its manager
--     keeps its column *and* its place in the row.
UPDATE project_buckets
SET position = CASE name
                   WHEN 'Fabrication/Manufacturing' THEN 0
                   WHEN 'Repair/Maintenance' THEN 1
                   WHEN 'Site projects' THEN 2
                   WHEN 'Labor supply' THEN 3
                   WHEN 'Goods sales' THEN 4
                   ELSE 5
               END
WHERE name IN ('Fabrication/Manufacturing', 'Repair/Maintenance', 'Site projects', 'Labor supply',
               'Goods sales', 'Other');

-- 4b. Everything else stands on after the six, in the order it was added. Counting lower ids rather
--     than lower positions is deliberate: `id` is never rewritten by this migration, so a row's place
--     cannot shift under the statement that is computing it, and ids are handed out in creation order
--     — which is the order ProjectBucketRepository.append lanes were made in.
UPDATE project_buckets
SET position = 6 + (
    SELECT COUNT(*) FROM project_buckets other
    WHERE other.project_id = project_buckets.project_id
      AND other.name NOT IN ('Fabrication/Manufacturing', 'Repair/Maintenance', 'Site projects',
                             'Labor supply', 'Goods sales', 'Other')
      AND other.id < project_buckets.id
)
WHERE name NOT IN ('Fabrication/Manufacturing', 'Repair/Maintenance', 'Site projects', 'Labor supply',
                   'Goods sales', 'Other');
