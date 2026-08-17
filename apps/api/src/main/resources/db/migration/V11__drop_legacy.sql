-- V11: Drops the legacy coursework schema.
--
-- The replacement is complete: a player starts a tournament, answers question
-- by question, submits, and appears on a leaderboard entirely through
-- authenticated /v1 endpoints. The owner confirmed these tables hold no data
-- worth keeping before this migration was written.
--
-- Dropped children first, then parents, so no foreign key blocks the order.

DROP TABLE IF EXISTS question_options;
DROP TABLE IF EXISTS legacy_question;
DROP TABLE IF EXISTS score;
DROP TABLE IF EXISTS participation;
DROP TABLE IF EXISTS quiz_likes;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS quiz;

-- The legacy user table. Accounts live in "account" (V2) and are unrelated to
-- this; nothing in the current schema references it.
DROP TABLE IF EXISTS users;
