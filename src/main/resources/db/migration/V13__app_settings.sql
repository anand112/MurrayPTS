-- ============================================================================
-- Murray PTS — V13 a place for one app-wide switch (SQLite)
-- ----------------------------------------------------------------------------
-- The app's outbound mail has always been governed by keys in data/.env
-- (PTS_SMTP_HOST / PTS_SMTP_DRY_RUN / the password). Those are deployment
-- secrets: an operator edits the file and restarts, and a password must never
-- live in a web form or a database. But one decision does not belong to the
-- operator's file — *whether this installation is allowed to send email to
-- people at all*. That is an in-app choice a super admin makes from Settings,
-- and it has to survive a restart, so it needs a row, not an env key.
--
-- This is a tiny generic key/value store rather than a `mail_send_enabled`
-- boolean column, because the same "a setting the app owns, not the operator"
-- need will recur (a from-display-name, a support address, a quiet-hours flag)
-- and a second migration per switch is heavier than one table read by one
-- repository. The store holds text; how a key is interpreted is the service's
-- business, not the schema's.
--
-- What this migration deliberately does not do:
--
--   * seed any row. A switch with no stored value reads as OFF — see
--     MailSwitch.enabled(), which treats an absent key as false. Seeding
--     nothing means the app behaves exactly as it did before this table
--     existed (mail is log-only) until a super admin turns the switch on from
--     Settings. The system does not start emailing people on its own because a
--     migration ran; it starts because an admin said so.
--   * store a secret. The mail password stays in data/.env where the launch
--     checklists expect it. This table is for non-sensitive switches only.
--
-- `key` is the primary key so an upsert has one row per setting; `value` is
-- free text (a "true"/"false" for now), NOT NULL with no default so a write is
-- always an explicit choice and a reader never has to guess what an empty cell
-- meant.
-- ============================================================================

CREATE TABLE app_settings (
    key   TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);
