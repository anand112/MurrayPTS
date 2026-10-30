-- ============================================================================
-- Murray PTS — V18 an archived card (SQLite)
-- ----------------------------------------------------------------------------
-- Until now a bucket that came off the board had exactly two answers, and both were bad: `removeLane` let the
-- lane go and its cards fell to `ON DELETE SET NULL`, which un-filed other people's work into an Unsorted column
-- nobody chose, and the picker a reader can actually reach (`saveLanes`) refused an occupied lane outright —
-- "still has cards in it, move them to another bucket first". The prototype this stage is copied from
-- (`murray_pts.html`) answers a third way: **the column and the work in it go to Archive together**, and a person
-- who opens the archive can put a card back or delete it for good. That is the rule this migration makes possible.
--
-- It is implemented as a **flag on the card**, not as rows copied into an archive table, and that choice is the
-- whole reason this migration is three columns instead of a table. A PTS card owns its plan (`task_checklist_items`,
-- V5), its log (`task_notes`, V6) and its lesson rows by foreign key; moving a card would mean either deleting
-- and re-inserting it — which cascades those children away and gives them new ids — or duplicating three child
-- tables behind an archive indirection. Flagging it instead leaves every child row exactly where it was, which is
-- what makes the promise the interface can print "restored as it left": a restored card still carries the same
-- steps with the same ticks, the same notes by the same authors, and the same id, so nothing on the Lessons or
-- alerts side has to learn that a card was ever gone.
--
-- The three columns:
--
--   * **archived** is the flag, and the only thing a read has to know. Existing rows get 0, so every card on
--     every board today reads as not archived and no board changes shape on upgrade.
--   * **archived_lane_id** remembers *which column* the card was filed in, as a **plain integer with no foreign
--     key**. It cannot be a reference: on the path that matters most — a bucket removed from the board — the lane
--     row is deleted, and a referencing column would be SET NULL by the same statement that archives the card,
--     which is precisely the memory being removed. So the value is a snapshot, and restore reads it as a hint:
--     if that lane still exists on the project the card goes back into it; if it does not, the card lands in the
--     project's first column rather than being filed under nothing.
--   * **archived_lane** is that lane's *name*, for the archive panel's "was in X" line, which has to be readable
--     after the lane row is gone. A name is a copy and can therefore be stale, but it is only ever used to tell a
--     story about the past, never to file anything — restore goes by the id, and the board groups by its own
--     lanes. **archived_on** is the day, in the same `yyyy-MM-dd` the rest of the app prints.
--
-- What this migration deliberately does not do:
--
--   * add an index. A board reads its cards through `Tasks.selectAll().where { kind eq ... }` and the archived
--     set is small by construction — it is work somebody took off a board — so a partial index would be a thing
--     to maintain for no measurable read.
--   * touch `buckets`, the Other tasks board's columns. Archive is a project-board idea: a backlog card belongs to
--     a column of status-shaped work that has no lanes to fold away, and the popup keeps offering management the
--     hard delete there. V17's header says the same thing about colour, for the same reason.
--   * rewrite the card's `project_id` or `status`. An archived card still belongs to its project — that is what
--     the per-project archive panel lists — and it comes back with the status it had, because *where a card got
--     to* is not the thing being hidden when it is archived.
--
-- `ADD COLUMN` with a default is the whole of the DDL for the flag; the two snapshot columns are plain nullable
-- text and integer, so no existing row needs touching and no table copy happens. The writers that set them are
-- [mn.murray.pts.service.TaskService.archiveCard] and [mn.murray.pts.service.TaskService.archiveLane], and the
-- reads that hide them are [mn.murray.pts.repository.TaskRepository.findByKind],
-- [mn.murray.pts.repository.ProjectBucketRepository.cardCounts] and [mn.murray.pts.repository.AlertRepository.openTasks].
-- ============================================================================

ALTER TABLE tasks ADD COLUMN archived BOOLEAN NOT NULL DEFAULT 0;

ALTER TABLE tasks ADD COLUMN archived_lane_id INTEGER;

ALTER TABLE tasks ADD COLUMN archived_lane TEXT;

ALTER TABLE tasks ADD COLUMN archived_on TEXT;
