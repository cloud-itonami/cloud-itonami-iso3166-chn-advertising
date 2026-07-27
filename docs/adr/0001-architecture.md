# ADR-0001: Architecture — Ad-Review Compliance Governor in front of a contained advisor

**Status**: accepted
**Date**: 2026-07-27
**Supersedes**: nothing. Mirrors the superproject ADR
`2607277000-cloud-itonami-chn-marketing-vertical` (com-junkawasaki/root).

## Context

`cloud-itonami-iso3166-chn` covers public-procurement market entry for
China. It says nothing about what an operator may then *advertise*, and
the jurisdiction-agnostic `cloud-itonami-isic-7310` advertising actor
models campaign intake and misleading-claim screening but has no concept
of a pre-publication review gate — which is the single largest structural
difference between advertising in China and in the four jurisdictions
7310 seeds (JPN/USA/GBR/DEU).

Under 广告法第四十六条, advertisements for 医疗・药品・医疗器械・农药・兽药・
保健食品 may not be published at all until a 广告审查机关 has reviewed the
creative and issued a 广告批准文号; 互联网广告管理办法第七条 adds
特殊医学用途配方食品. The approval carries an expiry
(暂行办法第十八条) that is easy to miss, and publishing past it is
publishing without approval.

## Decision

Fork the `marketentry` actor shape from `cloud-itonami-iso3166-chn` into
an `adreview` actor in a new thematic sibling repo, rather than adding a
"CHN" row to `cloud-itonami-isic-7310`'s catalog.

A catalog row can carry citations. It cannot carry a **gate** — a
conditional, category-dependent HARD check with its own validity window,
its own un-checkable state, and its own audit record. Modelling the review
gate as data in 7310 would have meant either weakening it to advisory text
or bending 7310's generic shape around one jurisdiction.

Structure (identical seams to every sibling actor, so each is a swap not a
rewrite):

- `adreview.facts` — the cited, closed spec-basis catalog. One
  jurisdiction (CHN). Coverage reported honestly.
- `adreview.adreviewllm` — the contained advisor. Returns proposals; never
  a committed record, never a real advertisement.
- `adreview.governor` — the independent Ad-Review Compliance Governor.
  Eight HARD checks re-derived from the store, never from the advisor's
  claim.
- `adreview.phase` — 0→3 rollout, with the actuation exclusion asserted at
  load time.
- `adreview.registry` / `adreview.store` — pure records; MemStore ≡
  DatomicStore under one contract test.
- `adreview.operation` — the langgraph StateGraph wiring them, with
  `interrupt-before #{:request-approval}`.

## Consequences

- The flagship HARD check `ad-review-approval-missing` (plus
  `-elapsed` / `-validity-unknown`) is genuinely new to this fleet: no
  sibling actor models a pre-publication approval with an expiry.
- **Un-checkable is a distinct outcome, not a pass.** Both the media-budget
  ceiling and the approval expiry expose a `checkable?` predicate, and the
  governor holds when the answer is "cannot tell". This follows the
  correction already made in `cloud-itonami-isic-7310`'s registry, where a
  `(and (number? ...) ...)` guard let un-recorded entities pass a limit
  check silently.
- Dates are compared as ISO-8601 `YYYY-MM-DD` strings under lexicographic
  ordering — portable across JVM and JS with no date library and no
  timezone to get wrong. Anything not matching that shape is refused by
  `iso-date?` rather than silently mis-ordered.
- The load-time assertion in `adreview.phase` means the actuation
  invariant cannot be weakened by a quiet one-word diff.
- 45 tests / 189 assertions green; `clojure -M:lint` clean.

## Alternatives considered

- **Add "CHN" to `cloud-itonami-isic-7310`'s catalog.** Rejected as the
  *primary* vehicle for the reasons above — but it remains worth doing as a
  separate, additive change so the generic actor stops reporting China as
  uncovered. That is a follow-up on 7310, not a blocker here.
- **One combined `cloud-itonami-iso3166-chn-marketing` repo** covering both
  advertising and market research. Rejected: the two are governed by
  different authorities (SAMR vs 国家统计局), different statutes, and
  different governors, and the fleet's convention is one actor per
  regulatory regime.
