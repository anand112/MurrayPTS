-- ============================================================================
-- Murray PTS — V16 the three phases, again, and this time editable (SQLite)
-- ----------------------------------------------------------------------------
-- V10 took a project's board off three phases and onto the six families the company plans work under.
-- That turned out to describe *what the company sells* when what a board has to organise is *how far a
-- piece of work has got*: every project is engineered, then bought, then built. So from here a new board
-- opens with Engineering, Procurement and Production again — the same three V7 seeded — and the ask is
-- explicit that the resemblance stops there: those three are an **opening set, not a floor**. The moment
-- the project exists each one is an ordinary column the manager may retitle or empty and remove, exactly
-- like a lane somebody added for itself, and the board stays its bucket columns whether it holds one card
-- or none (Stage 75).
--
-- What this migration does:
--
--   * takes an empty sub-category of the six off the board, which is the only case in which a lane can go
--     without work going with it;
--   * leaves one that holds cards standing, under its own name, as an ordinary lane — it is now third in
--     line after the three rather than first among six, and the manager can empty it and remove it
--     themselves;
--   * gives every existing project any of the three it does not already have, so the ask's *seed all of
--     them* holds for the twenty projects already open, not only for the next one;
--   * numbers the three 0..2 on every board and puts whatever else the project carries on after them, so
--     the three read as the first three columns everywhere and a lane the project added for itself is
--     still there, in the order it was added.
--
-- What it deliberately does not do:
--
--   * delete a lane that holds a card, whether it is one of the six or one the project added.
--     `tasks.project_bucket_id` answers `ON DELETE SET NULL`, so dropping a column would silently unfile
--     everything in it. Work that is filed under a name the company no longer plans by is a visible thing
--     somebody can read, move and then empty; a card with no column is neither.
--   * refile a card into one of the three. Moving somebody's work into a phase is a guess about that work,
--     and Stage 75 makes the phase a card's status — a thing its owner sets — rather than something a
--     migration can infer from which old family it sat in.
--   * touch `type_default_buckets`. Settings' catalogue never held one of the six — the service refused a
--     default whose name matched a standard lane — so the retired names cannot be sitting there being
--     offered to new projects. It now refuses one of the *three* for the same reason: an offer the board
--     already opens with is not an extra.
--   * touch `backlog_buckets`. The Other tasks columns are a different set belonging to different work.
--
-- The name comparison is case-sensitive and exact on purpose, the same way V10's is: a lane a manager
-- renamed to "engineering" is unusual enough that folding it into the new default silently would be a
-- guess, and the INSERT below only skips a lane whose name is already on that board character for
-- character. Such a lane simply stands on after the three, which is where a name the project chose for
-- itself belongs.
-- ============================================================================

-- 1. A sub-category of the six with nothing filed in it goes. It is no longer part of any board's opening
--    set, and the three arrive below, so leaving it would print a column named for a family the project
--    does not file in. Only an empty one: see the header.
DELETE FROM project_buckets
WHERE name IN ('Fabrication/Manufacturing', 'Repair/Maintenance', 'Site projects', 'Labor supply',
               'Goods sales', 'Other')
  AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.project_bucket_id = project_buckets.id);

-- 2. The three are offered by name by the picker and resolved by name, so a board holding two rows of one
--    of these names would offer one column twice and file a card in whichever row the lookup happened to
--    return. The service refuses a duplicate name on a live board (TaskService.addLane), so this only
--    cleans what older data can carry — a V7 'Engineering' that survived V10 because it held work, next to
--    a later empty one — by dropping the later empty copies and keeping the first. It never touches a lane
--    with cards in it.
DELETE FROM project_buckets
WHERE name IN ('Engineering', 'Procurement', 'Production')
  AND id > (SELECT MIN(pb.id) FROM project_buckets pb
            WHERE pb.project_id = project_buckets.project_id AND pb.name = project_buckets.name)
  AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.project_bucket_id = project_buckets.id);

-- 3. Give every project the three it does not already carry. The position written here is a placeholder in
--    the list's own order; step 4 is what numbers the board, and it reads the three by name.
INSERT INTO project_buckets (project_id, name, position)
SELECT p.id, lane.name, lane.rn
FROM projects p
CROSS JOIN (            SELECT 'Engineering' AS name, 0 AS rn
             UNION ALL  SELECT 'Procurement',   1
             UNION ALL  SELECT 'Production',    2) lane
WHERE NOT EXISTS (
    SELECT 1 FROM project_buckets pb
    WHERE pb.project_id = p.id AND pb.name = lane.name
)
ORDER BY p.id, lane.rn;

-- 4a. The three take positions 0..2 on every board, in the order the picker lists them. Numbering by name
--     rather than appending means a project that already had 'Procurement' from V7 keeps its column — and
--     every card filed in it — *and* its place at the head of the row.
UPDATE project_buckets
SET position = CASE name
                   WHEN 'Engineering' THEN 0
                   WHEN 'Procurement' THEN 1
                   ELSE 2
               END
WHERE name IN ('Engineering', 'Procurement', 'Production');

-- 4b. Everything else stands on after the three, in the order it was added — which includes a retired
--     sub-category of the six that still holds work: it is just a lane now, and it keeps its name because
--     that is what its cards are filed under. Counting lower ids rather than lower positions is
--     deliberate, for the reason V10 gives: `id` is never rewritten by this migration, so a row's place
--     cannot shift under the statement computing it, and ids are handed out in creation order.
UPDATE project_buckets
SET position = 3 + (
    SELECT COUNT(*) FROM project_buckets other
    WHERE other.project_id = project_buckets.project_id
      AND other.name NOT IN ('Engineering', 'Procurement', 'Production')
      AND other.id < project_buckets.id
)
WHERE name NOT IN ('Engineering', 'Procurement', 'Production');
