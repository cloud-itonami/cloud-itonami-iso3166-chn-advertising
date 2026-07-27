# Business Model: China Advertising Pre-Publication Review & Internet-Advertising Conformance Service

## Classification

- Repository: `cloud-itonami-iso3166-chn-advertising`
- ISO 3166: `CHN` (People's Republic of China)
- ISIC Rev.5: `7310` (Advertising) — China-jurisdiction member
- Activity: pre-publication advertising review (广告审查) navigation and
  internet-advertising conformance for an operator placing advertisements
  in the Chinese market
- Social impact: [:consumer-protection :advertising-transparency
  :cross-border-friction-reduction]

## Customer

- a foreign brand, agency or SME advertising into China for the first
  time, who has just completed market entry (typically via
  `cloud-itonami-iso3166-chn`) and now needs to place advertising
- a Chinese advertising operator (广告经营者/广告发布者) that must run the
  广告承接登记・审核・档案管理 regime 互联网广告管理办法第十四条 requires,
  and wants an auditable record rather than a spreadsheet
- an in-house marketing team at a multinational whose category
  (药品/医疗器械/保健食品/特殊医学用途配方食品) puts every creative behind a
  pre-publication review, and who keeps discovering 批准文号 expiries late
- a `cloud-itonami-isic-7310` operator that needs the China jurisdiction
  specifically, with its review gate modelled rather than assumed away

## Offer

- **reviewed-category triage**: is this product category actually behind
  广告法第四十六条 / 互联网广告管理办法第七条's pre-publication review, and if
  so which 广告审查机关 (省级市场监督管理部门 or 药品监督管理部门,
  暂行办法第四条) receives it
- **广告批准文号 lifecycle tracking**: the approval number on file, its
  validity window (暂行办法第十八条 ties it to the shortest validity among
  the product's own registration documents; two years when those set none),
  and a hard block on publishing past expiry
- **internet-advertising conformance checklist**: identifiability and
  竞价排名 marking (第九条), one-click close for pop-ups (第十条),
  acceptance-registration/review/archive recordkeeping (第十四条)
- **endorser (广告代言人) eligibility screening** against 广告法第三十八条
- **media-budget ceiling verification** against the client's own recorded
  authorization
- **compliance-audit export package** — the append-only ledger of every
  decision, hold and approval, for the client's own records or an
  enforcement inquiry

## Revenue

- per-campaign compliance-review fee (triage + checklist completion)
- recurring 批准文号 expiry-monitoring subscription (the renewal deadline is
  the recurring, forgettable event this service exists to catch)
- regulatory-change monitoring subscription
- compliance-audit export package

## Trust Controls

- **no campaign in a reviewed category may be proposed for publication
  without an independently verified, unexpired 广告批准文号.** The governor
  re-derives this from the store, never from the advisor's own claim, and
  it is a HARD hold a human approver cannot override.
- **un-checkable is never treated as compliant.** An unrecorded budget, or a
  validity/publish date pair that is not both ISO-8601, produces its own
  HARD hold (`media-budget-uncheckable` / `ad-review-validity-unknown`)
  rather than falling through as "within limits" / "still valid".
- any actual publication or review-package submission requires Ad-Review
  Compliance Governor clearance and always escalates to human sign-off —
  `:campaign/publish` is never automated at any phase, and that exclusion
  is asserted at load time.
- a false or fabricated regulatory-requirement claim is a HARD hold that
  cannot be overridden by human approval alone — it must be corrected
  against a cited official source first.
- this service does **not** provide legal advice; characterization and
  filing on the client's behalf beyond checklist/draft assistance routes
  to Chinese-licensed counsel or a registered agent.
- this actor never mints a 广告批准文号 — only a 广告审查机关 issues one.
- every requirement cites the official regulation and an official URL that
  was actually fetched on the recorded `:retrieved-at`, never invented.

## Boundary with adjacent actors (read before forking)

- **`cloud-itonami-iso3166-chn`**: public-procurement *market entry* — CCGP
  registration, USCC, domestic-entity posture. A prior, different
  regulatory phase. This blueprint assumes the operator can already trade
  in China and handles what it may *say* to Chinese consumers.
- **`cloud-itonami-isic-7310`**: the jurisdiction-agnostic advertising
  actor, whose `advertising.facts` catalog seeds JPN/USA/GBR/DEU. That
  actor models campaign intake, media planning and misleading-claim
  screening generically; it has no notion of a pre-publication review
  gate or a 批准文号 at all. The two compose: an operator running 7310
  across several markets forks this repo for the China leg.
- **`cloud-itonami-iso3166-chn-market-research`**: the sibling 7320
  vertical — 涉外调查 permits and survey approval. Research *before* the
  campaign; this blueprint governs the campaign itself.
- **`adserver`** (cloud-itonami): our own first-party ad network and
  auction. That is ad *serving* infrastructure we operate; this is
  compliance tooling we sell. They are not the same product and neither
  depends on the other.
- **`com-etzhayyim-ooyake`**: read-only civic-wayfinding mirror of
  government structure, non-commercial, barred from acting as or for the
  government. This blueprint is commercial and never claims to be an
  official channel — and specifically never claims to be a 广告审查机关.
