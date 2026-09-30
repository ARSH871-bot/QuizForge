-- V16: A PLAYER role, granted by joining a tournament from its share link.
--
-- Players are members so that attempts, standings and row-level security work
-- unchanged, but they hold no permission beyond playing: they cannot read
-- question content, which carries the answers.

ALTER TABLE membership
    DROP CONSTRAINT ck_membership_role,
    ADD CONSTRAINT ck_membership_role
        CHECK (role IN ('OWNER', 'ADMIN', 'EDITOR', 'VIEWER', 'PLAYER'));
