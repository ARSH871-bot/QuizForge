-- V14: Fix rate-limit buckets for accounts that belong to multiple workspaces.
--
-- V13 keyed buckets only by subject_id. Under RLS, a session account whose
-- first bucket was created in workspace A could not see that row while scoped
-- to workspace B. The limiter then allowed every request in B without updating
-- a bucket. The workspace is part of the budget identity, so it must be part of
-- the key too.

ALTER TABLE rate_limit_bucket
    DROP CONSTRAINT rate_limit_bucket_pkey,
    ADD PRIMARY KEY (subject_id, workspace_id);
