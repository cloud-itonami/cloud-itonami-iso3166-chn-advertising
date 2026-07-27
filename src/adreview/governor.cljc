(ns adreview.governor
  "Ad-Review Compliance Governor -- the independent compliance layer
  that earns the AdReview-LLM the right to commit. The LLM has no
  notion of which product categories 广告法第四十六条 puts behind a
  pre-publication review, whether a 广告批准文号 is actually on file and
  still valid on the publish date, whether an internet placement is
  marked as 广告 and closable in one click, whether an endorser
  actually used the product, or when a draft stops being a draft and
  becomes a real advertisement in front of Chinese consumers -- so this
  MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  `:itonami.blueprint/governor` is `:ad-review-compliance-governor`.

  This blueprint's own text (docs/business-model.md Trust Controls:
  'no campaign in a reviewed category may be proposed for publication
  without an independently verified, unexpired 广告批准文号'; 'a
  fabricated regulatory-requirement claim is a HARD hold') names
  exactly the checks below.

  Eight checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them. The confidence/actuation gate is SOFT:
  it asks a human to look, and the human may approve -- but see
  `adreview.phase`: for `:stake :actuation/submit-review`/
  `:actuation/publish-campaign` NO phase ever allows auto-commit
  either. Two independent layers agree that actuation is always a human
  call.

    1. Spec-basis                   -- did the proposal cite an OFFICIAL
                                       source (`adreview.facts`), or
                                       invent one?
    2. Evidence incomplete          -- for `:review/submit`/
                                       `:campaign/publish`, has the
                                       jurisdiction actually been
                                       assessed with a full evidence
                                       checklist on file?
    3. Ad-review approval missing   -- FLAGSHIP. For `:campaign/publish`,
                                       when the campaign's own product
                                       category is inside the
                                       jurisdiction's CLOSED reviewed
                                       set (广告法第四十六条 +
                                       互联网广告管理办法第七条),
                                       INDEPENDENTLY verify a 广告批准文号
                                       is recorded AND has not elapsed
                                       as of the campaign's own
                                       publish date (暂行办法第九条/
                                       第十八条). An unrecorded or
                                       unorderable date pair is
                                       un-checkable, and un-checkable is
                                       NOT 'still valid'.
    4. Internet-ad conformance      -- for `:campaign/publish`, when the
                                       campaign is an internet ad,
                                       verify it is marked as 广告
                                       (办法第九条), and when it is a
                                       pop-up, that a one-click close is
                                       recorded (办法第十条).
    5. Endorser ineligible          -- for `:campaign/publish`, when the
                                       campaign declares an endorser,
                                       verify the endorser actually used
                                       the product and meets the
                                       jurisdiction's own minimum
                                       endorser age (广告法第三十八条).
    6. Media spend over budget      -- for `:campaign/publish`,
                                       INDEPENDENTLY compare the
                                       campaign's own proposed media
                                       spend against its own recorded
                                       client-authorized budget --
                                       and hold when the comparison is
                                       un-checkable at all.
    7/8. Double-actuation guards    -- refuse to submit the same review
                                       package twice, or publish the
                                       same campaign twice, off
                                       dedicated `:reviewed?`/
                                       `:published?` facts (never a
                                       `:status` value).
    9. Confidence floor / actuation
       gate                         -- LLM confidence below threshold,
                                       OR the op is a REAL act
                                       -> escalate (SOFT)."
  (:require [adreview.facts :as facts]
            [adreview.registry :as registry]
            [adreview.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Submitting a real review package to a 广告审查机关 and publishing a
  real advertisement are the two real-world actuation events this
  actor performs."
  #{:actuation/submit-review :actuation/publish-campaign})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's advertising-review requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:regime/assess :review/submit :campaign/publish} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式 spec-basis の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:review/submit`/`:campaign/publish`, the jurisdiction's required
  evidence must actually be satisfied."
  [{:keys [op subject]} st]
  (when (contains? #{:review/submit :campaign/publish} op)
    (let [c (store/campaign st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction c) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(客户委托/广告内容审核/广告承接登记/媒介投放授权)が充足していない状態での提案"}]))))

(defn- ad-review-approval-violations
  "FLAGSHIP. For `:campaign/publish`, when the campaign's own product
  category is inside the jurisdiction's CLOSED reviewed set, the
  campaign must carry a 广告批准文号 that is BOTH recorded and unexpired
  at its own publish date.

  Three distinct HARD failures, reported separately so the ledger says
  which one actually happened:

    :ad-review-approval-missing  -- no 批准文号 on file at all
    :ad-review-approval-elapsed  -- on file but expired by publish date
    :ad-review-validity-unknown  -- the validity/publish dates are not
                                    both recorded as ISO-8601 dates, so
                                    expiry cannot be evaluated. This is
                                    NOT treated as 'still valid' -- the
                                    same discipline the fleet's ceiling
                                    checks use for an unrecorded limit."
  [{:keys [op subject]} st]
  (when (= op :campaign/publish)
    (let [c (store/campaign st subject)]
      (when (facts/requires-ad-review? (:jurisdiction c) (:category c))
        (cond
          (not (registry/approval-number-present? c))
          [{:rule :ad-review-approval-missing
            :detail (str subject " は审查対象类别(" (name (or (:category c) :unknown))
                         ")だが广告批准文号が未記録 -- 発布提案は進められない"
                         " [广告法第四十六条 / 暂行办法第九条]")}]

          (not (registry/approval-elapsed-checkable? c))
          [{:rule :ad-review-validity-unknown
            :detail (str subject " の广告批准文号有効期(" (pr-str (:ad-approval-valid-until c))
                         ")または発布日(" (pr-str (:publish-date c))
                         ")が ISO-8601 で記録されておらず失効判定不能 -- 判定不能は有効ではない"
                         " [暂行办法第十八条]")}]

          (registry/approval-elapsed? c)
          [{:rule :ad-review-approval-elapsed
            :detail (str subject " の广告批准文号は " (:ad-approval-valid-until c)
                         " に失効しており発布日 " (:publish-date c) " には無効"
                         " [暂行办法第十八条]")}]

          :else nil)))))

(defn- internet-ad-violations
  "For `:campaign/publish`, an internet advertisement must be
  identifiable as 广告 (办法第九条), and a pop-up placement must offer a
  one-click close (办法第十条). Both are CONDITIONAL on the campaign's
  own `:internet-ad?`/`:popup?` ground truth -- a broadcast or print
  campaign is not silently failed by an internet-only rule."
  [{:keys [op subject]} st]
  (when (= op :campaign/publish)
    (let [c (store/campaign st subject)]
      (when (true? (:internet-ad? c))
        (seq
         (cond-> []
           (not (true? (:labelled-as-ad? c)))
           (conj {:rule :internet-ad-unlabelled
                  :detail (str subject " は互联网广告だが\"广告\"の显著标明が未確認"
                               " [广告法第十四条 / 互联网广告管理办法第九条]")})

           (and (true? (:popup? c)) (not (true? (:one-click-close? c))))
           (conj {:rule :popup-no-one-click-close
                  :detail (str subject " は弹窗广告だが一键关闭が未確認"
                               " [互联网广告管理办法第十条]")})))))))

(defn- endorser-violations
  "For `:campaign/publish`, when the campaign declares an endorser
  (广告代言人), INDEPENDENTLY verify the endorser actually used the
  product and is at least the jurisdiction's own minimum endorser age.
  CONDITIONAL on the campaign's own `:has-endorser?` ground truth."
  [{:keys [op subject]} st]
  (when (= op :campaign/publish)
    (let [c (store/campaign st subject)
          min-age (facts/endorser-minimum-age (:jurisdiction c))]
      (when (true? (:has-endorser? c))
        (seq
         (cond-> []
           (not (true? (:endorser-used-product? c)))
           (conj {:rule :endorser-did-not-use-product
                  :detail (str subject " の广告代言人は当該商品の使用が未確認"
                               " [广告法第三十八条]")})

           (and (number? min-age)
                (or (not (number? (:endorser-age c)))
                    (< (:endorser-age c) min-age)))
           (conj {:rule :endorser-under-minimum-age
                  :detail (str subject " の广告代言人年齢(" (pr-str (:endorser-age c))
                               ")が法定下限 " min-age " 歳を満たさない、または未記録"
                               " [广告法第三十八条]")})))))))

(defn- media-spend-violations
  "For `:campaign/publish`, INDEPENDENTLY compare the campaign's own
  proposed media spend against its own recorded client-authorized
  budget. An un-checkable comparison (either figure unrecorded) is its
  own HARD violation -- it must not fall through as 'not over'."
  [{:keys [op subject]} st]
  (when (= op :campaign/publish)
    (let [c (store/campaign st subject)]
      (cond
        (not (registry/media-spend-exceeds-authorized-budget-checkable? c))
        [{:rule :media-budget-uncheckable
          :detail (str subject " は投放予定額(" (pr-str (:proposed-media-spend c))
                       ")または客户授权予算(" (pr-str (:authorized-budget c))
                       ")が未記録で上限判定不能 -- 判定不能は上限内ではない")}]

        (registry/media-spend-exceeds-authorized-budget? c)
        [{:rule :media-spend-exceeds-authorized-budget
          :detail (str subject " の投放予定額(" (:proposed-media-spend c)
                       ")が客户授权予算(" (:authorized-budget c) ")を超過")}]

        :else nil))))

(defn- already-reviewed-violations
  "For `:review/submit`, refuses to submit the SAME campaign's review
  package twice."
  [{:keys [op subject]} st]
  (when (= op :review/submit)
    (when (store/campaign-already-reviewed? st subject)
      [{:rule :already-reviewed
        :detail (str subject " は既に广告审查提出済み")}])))

(defn- already-published-violations
  "For `:campaign/publish`, refuses to publish the SAME campaign twice."
  [{:keys [op subject]} st]
  (when (= op :campaign/publish)
    (when (store/campaign-already-published? st subject)
      [{:rule :already-published
        :detail (str subject " は既に発布済み")}])))

(defn check
  "Censors an AdReview-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (ad-review-approval-violations request st)
                           (internet-ad-violations request st)
                           (endorser-violations request st)
                           (media-spend-violations request st)
                           (already-reviewed-violations request st)
                           (already-published-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
