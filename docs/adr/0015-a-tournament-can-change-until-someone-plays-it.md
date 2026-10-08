# 15. A tournament can change until someone plays it

Date: 2026-10-08

## Status

Accepted.

## Context

Amending or deleting a tournament was refused once it opened. The reason given
was about players: an open tournament "has players who started under its
current rules", and a closed one "has results those rules explain".

The rule tested the clock, though, not the players. That was harmless while
tournaments were scheduled ahead through the API. The web app does not schedule
ahead: a tournament opens the moment it is created, so the organiser can share
its link at once. Every tournament made there was therefore open from its first
second, and could never be corrected or removed, even by an organiser who
spotted a wrong answer before sharing the link with anyone.

The delete rule already counted attempts as well, but only behind the open
check, so the count could never be reached through the API. Its own test said
so.

Two smaller defects surfaced while changing this:

- The draft names the bank a tournament draws from, and the contract says the
  whole draft is applied. The bank was validated, and then ignored: the
  question count was checked against the new bank while play kept drawing from
  the old one.
- "Nobody has played" is a check followed by an action. An attempt still being
  started is invisible to the count, so without serialising the two, an
  organiser deleting at that moment would cascade the new attempt away, and an
  amendment would change the rules under a player who had just started.

## Decision

**A tournament can be amended or deleted until anyone plays it**: one attempt,
in any state, ends that. Whether it has opened no longer matters. An attempt
that ran out of time still counts; it was played, and it settled the board.

It stays one rule for every field, the name included, because a per-field
matrix is what nobody remembers correctly.

An amendment applies the whole draft, including the bank.

The check and the first attempt are serialised by row locks on the tournament:
starting an attempt takes a shared lock, so players never wait for each other;
an amendment or delete takes an exclusive lock before it counts attempts. Either
the attempt commits first and the change is refused, or the change commits
first and the attempt plays under it.

## Consequences

- An organiser can fix or remove a tournament whose link they have shared,
  until someone uses the link. The web app offers Edit and Delete only while
  nobody has played, and says plainly why they are gone after that.
- A closed tournament nobody played can be given a new window, which reopens
  it.
- Organisers who try their own tournament before sharing it have played it.
  Their attempt is a record like any other, so the tournament is fixed from
  then on. Nothing distinguishes a test play from a real one, and inventing a
  distinction would let a real result be deleted by calling it a test.
- `FirstAttemptRaceTest` holds an attempt open, uncommitted, while a delete and
  an amendment run. Both tests fail without the locks: the delete removed the
  tournament under the starting player, and the amendment changed the rules
  under them.
- Changes to `/v1` are to behaviour and descriptions only. Requests the old
  rule refused are now accepted; nothing that was accepted is now refused.
