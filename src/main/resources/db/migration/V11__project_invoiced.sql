-- ============================================================================
-- Murray PTS — V11 the amount invoiced (SQLite)
-- ----------------------------------------------------------------------------
-- The project detail page now lets management edit the commercial picture in place, and one number that
-- picture needs was never stored: how much of the contract has actually been *billed*. `revenue` is what the
-- job was quoted at; what has been invoiced against it is a separate fact, it moves a piece at a time as
-- invoices go out, and only a person knows where it stands. So it gets its own column and management enters
-- it by hand — it is deliberately NOT derived from revenue or from anything else on the row, because a
-- derived figure would be a guess about billing that the system cannot make.
--
-- Like every commercial field before it (V3 added client, start_date and revenue the same way), SQLite's
-- ALTER TABLE cannot add a NOT NULL column without a constant default, so existing projects start at 0 —
-- "nothing invoiced yet", which is the honest reading of a project whose billing was never recorded.
-- ============================================================================

ALTER TABLE projects ADD COLUMN invoiced REAL NOT NULL DEFAULT 0;
