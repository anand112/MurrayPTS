-- ============================================================================
-- Murray PTS — V3 project categories and commercial fields (SQLite)
-- ----------------------------------------------------------------------------
-- Two related changes to the `projects` table:
--
--   1. The project set moves from four free-text types to nine letter-coded
--      categories (domain/Project.kt: PROJECT_TYPES + TYPE_PREFIX):
--        M Fabrication/Manufacturing      D Distributor product sales
--        R Repair/Maintenance/other svc.  E Engineering
--        S Site projects                  F Internal works
--        L Labor supply                   G Goods sales
--                                         O Other
--      `type` keeps storing the human label (so the UI needs no join); the letter
--      is derived, and it is also the first token of the project code (S-01, R-12).
--
--   2. The create-project form asks for commercial detail that the old board never
--      had: client, start date and revenue. `due` is NOT replaced — it IS the end
--      date. Calendar.kt, AlertRepository.openProjects() and DueDateAlertService all
--      key off `projects.due`, so renaming the column would move the alerting
--      contract for no benefit. Read `due` as "the date the project is due to finish",
--      which is exactly the field the form labels "End date".
--
-- SQLite's ALTER TABLE cannot add a NOT NULL column without a constant default, hence
-- the DEFAULTs; blank/0 means "not filled in", which is what the legacy rows get too.
-- The UPDATEs below re-label rows written under the four-type scheme. The three
-- unchanged labels (Site projects, plus any row already using a new label) are left
-- alone, and unknown values survive as-is — they land in the "Other" bucket of the
-- board rather than being silently rewritten.
-- ============================================================================

ALTER TABLE projects ADD COLUMN client     TEXT NOT NULL DEFAULT '';
ALTER TABLE projects ADD COLUMN start_date TEXT NOT NULL DEFAULT '';
ALTER TABLE projects ADD COLUMN revenue    REAL NOT NULL DEFAULT 0;

UPDATE projects SET type = 'Fabrication/Manufacturing'          WHERE type = 'Manufacturing';
UPDATE projects SET type = 'Repair/Maintenance/other services'  WHERE type = 'Repair/ Maintenance/ Other Services';
UPDATE projects SET type = 'Labor supply'                       WHERE type = 'Labour supply';

-- Codes follow their category: the old three-letter prefixes collapse to the letter.
-- SIT/MFG/RMS/LAB numbered independently, so the new codes stay unique.
UPDATE projects SET code = 'S-'  || substr(code, 5) WHERE code LIKE 'SIT-%';
UPDATE projects SET code = 'M-'  || substr(code, 5) WHERE code LIKE 'MFG-%';
UPDATE projects SET code = 'R-'  || substr(code, 5) WHERE code LIKE 'RMS-%';
UPDATE projects SET code = 'L-'  || substr(code, 5) WHERE code LIKE 'LAB-%';

CREATE INDEX idx_projects_client ON projects (client);
