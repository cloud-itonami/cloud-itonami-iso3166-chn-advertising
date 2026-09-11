(ns adreview.adreviewllm
  "AdReview-LLM client -- the *contained intelligence node* for the
  Chinese advertising-review compliance actor.

  It normalizes campaign intake, drafts a per-jurisdiction regulatory
  checklist, drafts the 广告审查 submission action, and drafts the
  publication action. CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields it cited), never a
  committed record and never a real advertisement. Every output is
  censored downstream by `adreview.governor` before anything touches the
  SSoT, and `:review/submit`/`:campaign/publish` proposals NEVER
  auto-commit at any phase -- see README Actuation.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end."
  (:require [adreview.facts :as facts]
            [adreview.store :as store]))

(defn- normalize-intake
  [_db {:keys [patch]}]
  {:summary    (str "广告案件記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :campaign/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-regime
  "Per-jurisdiction advertising-regime checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a
  checklist for a jurisdiction with NO official spec-basis."
  [db {:keys [subject no-spec?]}]
  (let [c (store/campaign db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction c))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式 spec-basis が見つかりません")
       :rationale  "adreview.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      (let [reviewed? (facts/requires-ad-review? iso3 (:category c))]
        {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                          (count (:required-evidence sb)) " 件"
                          (if reviewed?
                            (str " + 类别 " (name (or (:category c) :unknown))
                                 " は发布前广告审查の対象")
                            " (当該类别は发布前审查の対象外)")
                          " を提案")
         :rationale  (str "公式ソース: " (:provenance sb)
                          " / 法的根拠: " (:legal-basis sb)
                          (when reviewed?
                            (str " / 审查根拠: " (:review-legal-basis sb)
                                 " (" (:review-provenance sb) ")")))
         :cites      (cond-> [(:legal-basis sb) (:provenance sb)]
                       reviewed? (conj (:review-legal-basis sb) (:review-provenance sb))
                       (true? (:internet-ad? c)) (conj (:internet-legal-basis sb)
                                                       (:internet-provenance sb)))
         :effect     :assessment/set
         :value      {:jurisdiction iso3
                      :checklist (:required-evidence sb)
                      :spec-basis (:provenance sb)
                      :legal-basis (:legal-basis sb)
                      :requires-ad-review? reviewed?
                      :review-authority (:review-authority sb)}
         :stake      nil
         :confidence 0.9}))))

(defn- propose-review-submit
  "Draft the actual 广告审查 SUBMISSION action. ALWAYS `:stake
  :actuation/submit-review`."
  [db {:keys [subject]}]
  (let [c (store/campaign db subject)]
    {:summary    (str subject " 向け广告审查提出提案"
                      (when c (str " (advertiser=" (:advertiser c) ")")))
     :rationale  (if c
                   (str "jurisdiction=" (:jurisdiction c)
                        " category=" (name (or (:category c) :unknown))
                        " channel=" (:channel c))
                   "campaign が見つかりません")
     :cites      (if c [subject] [])
     :effect     :campaign/mark-reviewed
     :value      {:campaign-id subject}
     :stake      :actuation/submit-review
     :confidence (if c 0.9 0.3)}))

(defn- propose-publish
  "Draft the actual PUBLICATION action. ALWAYS `:stake
  :actuation/publish-campaign` -- a real advertisement in front of
  Chinese consumers."
  [db {:keys [subject]}]
  (let [c (store/campaign db subject)
        needs-review? (and c (facts/requires-ad-review? (:jurisdiction c) (:category c)))]
    {:summary    (str subject " 向け发布提案"
                      (when c (str " (channel=" (:channel c) ")")))
     :rationale  (if c
                   (str "requires-ad-review?=" (boolean needs-review?)
                        " ad-approval-number=" (pr-str (:ad-approval-number c))
                        " valid-until=" (pr-str (:ad-approval-valid-until c))
                        " internet-ad?=" (:internet-ad? c)
                        " labelled-as-ad?=" (:labelled-as-ad? c))
                   "campaign が見つかりません")
     :cites      (if c [subject] [])
     :effect     :campaign/mark-published
     :value      {:campaign-id subject}
     :stake      :actuation/publish-campaign
     ;; The advisor's own confidence is a HINT, never the gate: the
     ;; governor re-derives every one of these conditions from the
     ;; store independently. It is lowered here only so the audit
     ;; trail shows the advisor itself was unsure.
     :confidence (if (and c
                          (or (not needs-review?)
                              (and (seq (str (:ad-approval-number c)))
                                   (not= "" (:ad-approval-number c)))))
                   0.9 0.3)}))

(defprotocol Advisor
  (-advise [this db request] "Return a proposal map for `request`."))

(defrecord MockAdvisor []
  Advisor
  (-advise [_ db {:keys [op] :as request}]
    (case op
      :campaign/intake  (normalize-intake db request)
      :regime/assess    (assess-regime db request)
      :review/submit    (propose-review-submit db request)
      :campaign/publish (propose-publish db request)
      {:summary "unknown op" :rationale "unsupported" :cites []
       :effect :noop :value {} :stake nil :confidence 0.0})))

(defn mock-advisor [] (->MockAdvisor))

(defn trace [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :subject (:subject request)
   :summary (:summary proposal)
   :confidence (:confidence proposal)
   :stake (:stake proposal)})
