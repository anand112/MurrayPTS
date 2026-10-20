-- ============================================================================
-- Murray PTS — V12 the extra lanes a category adds (SQLite)
-- ----------------------------------------------------------------------------
-- Every project board starts with the same six sub-categories (V10 put them on every board and
-- ProjectBucketRepository.seedDefaults writes them for every new one), and those six are the floor a
-- card is filed against — the general boards read a card as *on a project* precisely because it stands
-- in one of them. What the six cannot answer is the shape of the work: a Fabrication job lives on
-- shutdown windows and commissioning it never shares with a Labor supply contract, and today a manager
-- retypes those lanes onto every project the company opens.
--
-- This table moves that repetition to the catalog: management records, per project **category**, the
-- lanes that category's projects should start with *besides* the six, and ProjectService.create appends
-- them to the board right after seedDefaults. A new project arrives with its category's own columns and
-- the card-filing rule is untouched, because the six are still written first and this table cannot hold
-- one of them — the service refuses a name that matches a standard lane.
--
-- What this migration deliberately does not do:
--
--   * seed any row. The point of the table is that the company decides; a pre-filled set of "extra"
--     lanes would put columns on boards nobody asked for, and unlike the six they have no rule behind
--     them. The table starts empty, which means every category behaves exactly as it does today until
--     somebody adds a default on Settings.
--   * touch project_buckets or the cards on it. Existing boards keep their lanes: changing a default
--     changes the *next* project of that category, not the last twenty. There is no back-link from a
--     lane to the default that created it, and that is right — a manager who renames or empties a lane
--     on a live board has said something about that project, not about the catalog.
--
-- `type` stores a category LABEL exactly as PROJECT_TYPES spells it (`Site projects`, not `S`): the
-- letter is derived, and a label the catalog does not know is a row that can never be shown or used,
-- so the service validates it against PROJECT_TYPES on the way in. `position` is the append counter
-- the lanes, checklist steps and notes already use — the six hold 0..5 on a fresh board, so the extras
-- number from 6 and the board prints them after the floor in the order management added them.
--
-- A duplicate name within one category is refused by the service rather than by a unique index, for the
-- same reason TaskService.addLane refuses a duplicate on a live board: the comparison is made after a
-- trim and ignoring case, and an index could only enforce the exact spelling, so a board that already
-- carries "commissioning" would be handed "Commissioning" by the index and print one column twice.
-- ============================================================================

CREATE TABLE type_default_buckets (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    type     TEXT    NOT NULL,
    name     TEXT    NOT NULL,
    position INTEGER NOT NULL DEFAULT 0
);

-- The panel reads one category's lanes, in the order it prints them.
CREATE INDEX idx_type_default_buckets ON type_default_buckets (type, position);
