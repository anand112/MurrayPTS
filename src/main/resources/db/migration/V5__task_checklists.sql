-- ============================================================================
-- Murray PTS — V5 task checklists (SQLite)
-- ----------------------------------------------------------------------------
-- A task card can carry a list of small, tickable steps: management writes the list,
-- the person the card is assigned to works down it. The task itself keeps its one
-- status column — a checklist is not a second workflow, and finishing three of five
-- boxes never moves the card between kanban columns.
--
-- Why this is its own table and not a TEXT column on `tasks`:
--
--   * **one row per step, so a tick is one UPDATE.** A JSON or newline-separated blob
--     would make "tick box 3" a read-modify-write of the whole list, which is exactly
--     the kind of last-write-wins race two people on one card can hit.
--   * **it dies with the card.** `task_id` cascades, so deleting a task — or a whole
--     project, through tasks.project_id's own SET NULL chain — cannot leave orphaned
--     steps behind, the same way `tasks` and `todo_items` already behave.
--   * **the order is the author's, not the alphabet's.** `position` is the append counter,
--     like `todo_items.position` and `buckets.position`: management adds a step and it
--     lands at the bottom of the list and stays there.
--
-- `done` is INTEGER 0/1, which is how every boolean in this schema is stored (Exposed
-- `bool`). `label` rather than `text` because the latter is a type keyword.
--
-- No foreign key on who ticked a box: the app does not record it. The card has exactly
-- one person allowed to tick it besides management, and their name is already on the
-- card — a second copy of that fact would only be able to disagree with the first.
-- ============================================================================

CREATE TABLE task_checklist_items (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id  INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    label    TEXT    NOT NULL,
    done     INTEGER NOT NULL DEFAULT 0,
    position INTEGER NOT NULL DEFAULT 0
);

-- Every board render reads the items of a whole page of cards at once, ordered.
CREATE INDEX idx_checklist_task ON task_checklist_items (task_id, position);
