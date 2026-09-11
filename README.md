# cloud-itonami-iso3166-chn-advertising

Open ISO 3166 Blueprint for **CHN**: People's Republic of China — advertising
vertical — **`:implemented`**.

Independent **advertising pre-publication review (广告审查) and
internet-advertising conformance** service for an advertising operator,
agency or brand placing advertisements in the Chinese market.

A thematic (`advertising`) sibling of
[`cloud-itonami-iso3166-chn`](https://github.com/cloud-itonami/cloud-itonami-iso3166-chn)
(public-procurement market entry), and the China-jurisdiction counterpart of
the jurisdiction-agnostic
[`cloud-itonami-isic-7310`](https://github.com/cloud-itonami/cloud-itonami-isic-7310)
advertising actor. Same family, different regulatory domain — see
*Boundary with adjacent actors* in `docs/business-model.md`.

## Implementation (R0)

| Piece | Location |
|---|---|
| Actor | `src/adreview/*` |
| Governor | `:ad-review-compliance-governor` |
| Flagship HARD | `ad-review-approval-missing` |
| Tests | `kbb -M:dev:test` — 45 tests, 189 assertions |
| Demo | `kbb -M:dev:run` |
| Lint | `kbb -M:lint` |

## What the governor actually checks

Every one of these is a **HARD** hold: a human approver cannot override it.

| Rule | Grounded in |
|---|---|
| `no-spec-basis` | the jurisdiction is not in `adreview.facts/catalog` at all |
| `evidence-incomplete` | the jurisdiction's own evidence checklist is unsatisfied |
| **`ad-review-approval-missing`** | 广告法第四十六条 · 暂行办法第九条 — reviewed category, no 广告批准文号 on file |
| **`ad-review-approval-elapsed`** | 暂行办法第十八条 — 批准文号 expired before the campaign's own publish date |
| **`ad-review-validity-unknown`** | dates unrecorded/unorderable → expiry cannot be evaluated, and *un-checkable is not "still valid"* |
| `internet-ad-unlabelled` | 广告法第十四条 · 互联网广告管理办法第九条 |
| `popup-no-one-click-close` | 互联网广告管理办法第十条 |
| `endorser-did-not-use-product` | 广告法第三十八条 |
| `endorser-under-minimum-age` | 广告法第三十八条 (不满十周岁) |
| `media-spend-exceeds-authorized-budget` | the campaign's own client-authorized budget |
| `media-budget-uncheckable` | either figure unrecorded — again, *un-checkable is not within limits* |
| `already-reviewed` / `already-published` | double-actuation guards |

The **reviewed-category set is CLOSED**: six categories come from
广告法第四十六条 (医疗・药品・医疗器械・农药・兽药・保健食品) and
特殊医学用途配方食品 is added by 互联网广告管理办法第七条. Each records which
source put it there (`:reviewed-categories-source`). A category nobody put in
the set is never swept in, and an uncovered jurisdiction never answers "yes".

## Actuation

`:review/submit` (handing a package to a 广告审查机关) and `:campaign/publish`
(putting a real advertisement in front of Chinese consumers) are the two
real-world acts this actor performs. **Neither auto-commits at any phase**,
including phase 3. Two independent layers enforce this:

- `adreview.governor` treats both as `high-stakes` → always escalate; and
- `adreview.phase` excludes them from every phase's `:auto` set — asserted at
  **load time**, so a future one-word diff that adds `:campaign/publish` to
  phase 3 throws instead of silently weakening the invariant.

This actor never mints a 广告批准文号. Only a 广告审查机关 issues one;
`adreview.registry/approval-number-present?` checks that the operator recorded
one, it does not create one.

## What this is NOT

- **Not a 广告审查机关**, and not the 国家市场监督管理总局. Commercial
  compliance tooling only — it never claims to be an official channel and
  never issues an approval.
- **Not legal advice.** Characterization and filing beyond checklist/draft
  assistance routes to Chinese-licensed counsel or a registered agent.
- Not an ad-buying or media-planning system. It builds the compliance
  **record** an operator keeps; the media buy itself is out of scope.

## Sources

Every citation in `adreview.facts` was fetched and read on
`:retrieved-at` (2026-07-27):

- 中华人民共和国广告法 — <https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/fgs/art/2023/art_5474cf75173c45d6a0379730fb4e8d97.html>
- 互联网广告管理办法（国家市场监督管理总局令第72号，2023-05-01 施行）— <https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/fgs/art/2023/art_d93a579afd45413e8576e4623fab348f.html>
- 药品、医疗器械、保健食品、特殊医学用途配方食品广告审查管理暂行办法（令第21号，2020-03-01 施行）— <https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/fgs/art/2023/art_4ca076ac0eeb4a9e9204e0cee26e359b.html>

An item not in `adreview.facts/catalog` has no spec-basis — never fabricate
one.

## License

AGPL-3.0-or-later.
