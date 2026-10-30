-- ============================================================================
-- Murray PTS — V15 what a step looked like before the card was finished (SQLite)
-- ----------------------------------------------------------------------------
-- A card moved into **Completed** now ticks every step of its own plan, and moved back out of
-- **Completed** its steps return to where they stood before the finish ticked them. That needs one
-- fact the V5 table never kept: the state a step was in *before* the completion changed it. This is
-- that fact, one column per step.
--
-- Why a column on the step and not a snapshot per card (a row in a history table, or a JSON blob on
-- `tasks` — the shape V8 chose for the forecast ledger):
--
--   * **it describes one row, so it travels with that row.** A step added after the card was
--     finished has no earlier state to remember and gets NULL; a step taken off a finished card
--     takes its memory with it, which is exactly what `ON DELETE CASCADE` already does to the step.
--     A card-level snapshot would have to be rebuilt on every one of those writes, and getting it
--     out of step with the plan is the failure this table was designed to avoid (see V5's argument
--     for one row per step rather than a TEXT column on `tasks`).
--   * **a tick stays one UPDATE.** Restoring a finished card's plan is `SET done = pre_completed`
--     over its own rows — no read-modify-write of a shared blob, so two people working two cards
--     cannot overwrite each other, and two people working one card are still racing on the same row
--     they already race on today.
--
-- NULL means *nothing remembered*, which is why the column is nullable rather than DEFAULT 0: 0 is a
-- statement — "this step was unticked when the card was finished" — and a step of a card that has
-- never been through a completion has never made one. Restoring from NULL would untick work somebody
-- did before this rule existed, which is the opposite of what the column is for. Stored as INTEGER
-- 0/1 like every other boolean here, mirrored by Exposed's nullable `bool`.
--
-- The UPDATE below is not decoration. Cards already sitting in **Completed** were finished under the
-- old rule, where a plan could still have gaps in it; from this build those boxes are shown as done
-- and are no longer clickable, so leaving them half-ticked would put a card in a state its own rule
-- says it cannot be in. The backfill therefore runs one completion over exactly those rows — same
-- write the app would have made — and their previous state lands in `pre_completed`, so a reader who
-- drags one of those old cards back out of Completed gets the plan they left, not a wall of ticks.
-- Cards in any other column are untouched: their steps keep their own state and no memory.
-- ============================================================================

ALTER TABLE task_checklist_items ADD COLUMN pre_completed INTEGER;

-- One completion over the cards that are already finished: remember where each step stood, then tick it.
-- SQLite reads the pre-update value of `done` on the right-hand side, so both halves of this are one pass.
UPDATE task_checklist_items
   SET pre_completed = done, done = 1
 WHERE task_id IN (SELECT id FROM tasks WHERE status = 'Completed');
