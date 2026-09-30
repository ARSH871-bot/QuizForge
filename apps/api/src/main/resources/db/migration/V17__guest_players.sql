-- V17: Guest players.
--
-- A guest is an account with a name and nothing else: no email, no password,
-- so it can never sign in again and exists only through the session it was
-- issued. Being a real account is what lets attempts, standings, row-level
-- security and attempt limits apply to guests unchanged.

ALTER TABLE account
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD COLUMN guest BOOLEAN NOT NULL DEFAULT false,
    -- Exactly one shape or the other. A guest with an email could be signed
    -- into; an account without one could never be recovered.
    ADD CONSTRAINT ck_account_guest_shape CHECK (
        (guest AND email IS NULL AND password_hash IS NULL)
        OR (NOT guest AND email IS NOT NULL AND password_hash IS NOT NULL)
    );

-- Organisers opt in per tournament. Existing tournaments keep requiring an
-- account, which is what their players were told.
ALTER TABLE tournament
    ADD COLUMN allow_guests BOOLEAN NOT NULL DEFAULT false;
