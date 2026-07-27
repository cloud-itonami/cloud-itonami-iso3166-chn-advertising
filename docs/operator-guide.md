# Operator guide

## Run it

```bash
clojure -M:dev:test    # 45 tests, 189 assertions
clojure -M:dev:run     # the demo: one clean campaign + every HARD-hold scenario
clojure -M:lint        # clj-kondo, errors fail CI
```

Inside the monorepo the `:local/root` deps resolve as-is. A standalone fork
should replace them with git coordinates (see `deps.edn`).

## The operation lifecycle

```
:campaign/intake   -> normalize the campaign record          (auto at phase 3)
:regime/assess     -> which regime applies, which checklist   (always human)
:review/submit     -> prepare the 广告审查 package             (always human)
:campaign/publish  -> publish the advertisement               (always human)
```

Each is one graph run. The advisor proposes, the governor censors, the phase
gate decides, and exactly one ledger fact is written whether the outcome is
a commit or a hold.

## Adding a jurisdiction

Add one map to `adreview.facts/catalog`:

1. Fetch the jurisdiction's **official** advertising statute and any
   pre-publication review regulation. Read them.
2. Record `:legal-basis`, `:provenance` (the URL you actually fetched) and
   `:retrieved-at` (the date you fetched it).
3. Set `:reviewed-categories` to the **closed** set that jurisdiction puts
   behind a review, and give every member an entry in
   `:reviewed-categories-source` naming the article that put it there. The
   `facts_test` asserts these two agree — a category with no cited article
   fails the build.
4. Set `:endorser-minimum-age` only if the statute states one. Leave it out
   rather than guessing; the governor skips the age check when it is absent
   and never substitutes a default.

Do **not** copy China's categories to another jurisdiction. `coverage`
reports missing jurisdictions honestly; an uncovered jurisdiction reaching
the publish gate is held on `no-spec-basis`, which is the correct outcome.

## Recording a campaign

Fields the governor reads directly from the store (it never takes the
advisor's word for any of them):

| Field | Meaning |
|---|---|
| `:category` | must be one of the closed set to trigger the review gate |
| `:ad-approval-number` | the 广告批准文号 as issued. Blank/whitespace = not on file |
| `:ad-approval-valid-until` | ISO-8601 `YYYY-MM-DD`. Anything else → `ad-review-validity-unknown` |
| `:publish-date` | ISO-8601 `YYYY-MM-DD` |
| `:internet-ad?` / `:popup?` | gate the 互联网广告管理办法 checks |
| `:labelled-as-ad?` / `:one-click-close?` | the conformance facts themselves |
| `:has-endorser?` / `:endorser-used-product?` / `:endorser-age` | 广告法第三十八条 |
| `:proposed-media-spend` / `:authorized-budget` | both required, or the ceiling is un-checkable |

**Leave a field nil rather than filling it with a placeholder.** A nil
budget produces `media-budget-uncheckable` (a hold you can see and fix); a
placeholder `0` produces a silent pass. The `DatomicStore` round-trip
deliberately preserves nil for exactly this reason, and a test asserts it.

## Rollout phases

Start at phase 1 and move up as the operator's confidence grows. Phase 3
auto-commits only `:campaign/intake`. `:review/submit` and
`:campaign/publish` are excluded from every phase's `:auto` set, and
`adreview.phase` throws at load time if that is ever edited away.
