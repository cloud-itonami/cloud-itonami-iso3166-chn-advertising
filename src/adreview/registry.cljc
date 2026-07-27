(ns adreview.registry
  "Pure-function 广告审查 submission-draft + campaign-publication record
  construction -- an append-only advertising book-of-record draft.

  Like every sibling actor's registry, there is no single international
  reference-number standard for an advertising review submission --
  each 广告审查机关 assigns its own 广告批准文号 format. This namespace
  does NOT invent one; it builds a jurisdiction-scoped sequence number
  for the operator's OWN book of record and validates the record's
  required fields, the same honest, non-fabricating discipline
  `adreview.facts` uses. A real 广告批准文号 only ever arrives from the
  review authority -- `approval-number-present?` checks that the
  operator recorded one, it does NOT mint one.

  `media-spend-exceeds-authorized-budget?` is a reapplication of this
  fleet's MAXIMUM-ceiling check family (established by
  `facility.registry/occupancy-exceeds-capacity?`, and carried into
  advertising by `cloud-itonami-isic-7310`'s own registry), comparing a
  campaign's proposed media spend against its own recorded
  client-authorized budget.

  `approval-elapsed?` is the analogous MINIMUM-validity check for the
  批准文号's own expiry, whose length 暂行办法第十八条 ties to the
  product's registration documents (two years when those set none).
  Dates are compared as ISO-8601 `YYYY-MM-DD` strings, which order
  lexicographically -- portable across JVM and JS with no date library
  and no timezone to get wrong.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real review portal or ad platform. It builds the RECORD
  an advertising operator would keep, not the act of publishing the
  advertisement itself (that is `adreview.operation`'s
  `:campaign/publish`, always human-gated -- see README Actuation)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the advertising operator's own act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ----------------------------- ceiling / validity -----------------------------

(defn media-spend-exceeds-authorized-budget?
  "Does `campaign`'s own `:proposed-media-spend` exceed its own recorded
  `:authorized-budget`? Pure ground-truth check against the campaign's
  own permanent fields."
  [{:keys [proposed-media-spend authorized-budget]}]
  (and (number? proposed-media-spend) (number? authorized-budget)
       (> proposed-media-spend authorized-budget)))

(defn media-spend-exceeds-authorized-budget-checkable?
  "Are both sides of `media-spend-exceeds-authorized-budget?` actually
  recorded?

  That predicate answers only `over` / `not over`, and its
  `(and (number? ...) (number? ...) ...)` guard makes every un-recorded
  case fall through as `not over` -- a campaign missing either figure
  would pass the limit check silently. Callers must ask this first:
  un-checkable is not within limits."
  [{:keys [proposed-media-spend authorized-budget]}]
  (boolean (and (number? proposed-media-spend) (number? authorized-budget))))

(def ^:private iso-date-re #"^\d{4}-\d{2}-\d{2}$")

(defn iso-date?
  "Is `s` an ISO-8601 `YYYY-MM-DD` date string? Only these compare
  correctly under lexicographic ordering, so anything else is refused
  rather than silently mis-ordered."
  [s]
  (boolean (and (string? s) (re-matches iso-date-re s))))

(defn approval-number-present?
  "Did the operator actually record a 广告批准文号 for this campaign?
  A blank or whitespace-only string is not a number on file."
  [{:keys [ad-approval-number]}]
  (boolean (and (string? ad-approval-number)
                (not (str/blank? ad-approval-number)))))

(defn approval-elapsed?
  "Has the recorded 广告批准文号 expired as of `publish-date`?
  Returns true when the approval's own `:ad-approval-valid-until` is
  strictly before the campaign's own `:publish-date`."
  [{:keys [ad-approval-valid-until publish-date]}]
  (and (iso-date? ad-approval-valid-until) (iso-date? publish-date)
       (neg? (compare ad-approval-valid-until publish-date))))

(defn approval-elapsed-checkable?
  "Are both dates `approval-elapsed?` needs actually recorded, and in a
  form that orders correctly? Same discipline as the ceiling check:
  un-checkable is not 'still valid'."
  [{:keys [ad-approval-valid-until publish-date]}]
  (boolean (and (iso-date? ad-approval-valid-until) (iso-date? publish-date))))

;; ----------------------------- records -----------------------------

(defn register-review-submission
  "Validate + construct the 广告审查 SUBMISSION DRAFT -- the operator's
  own act of preparing a review package for the 广告审查机关. Pure
  function -- does not touch any real review portal, and never mints a
  广告批准文号 (only the authority issues one)."
  [campaign-id jurisdiction sequence]
  (when-not (and campaign-id (not= campaign-id ""))
    (throw (ex-info "review-submission: campaign_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "review-submission: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "review-submission: sequence must be >= 0" {})))
  (let [review-number (str (str/upper-case jurisdiction) "-RVW-" (zero-pad sequence 6))
        record {"record_id" review-number
                "kind" "ad-review-submission-draft"
                "campaign_id" campaign-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "review_number" review-number
     "certificate" (unsigned-certificate "AdReviewSubmission" review-number review-number)}))

(defn register-publication
  "Validate + construct the CAMPAIGN-PUBLICATION record -- the
  operator's own act of actually publishing the advertisement (always
  human-gated upstream). The 4-arity records WHICH media channel it ran
  on; the publication NUMBER stays jurisdiction-scoped, because the
  sequence is the operator's own book-of-record counter and re-scoping
  it per channel would silently renumber every publication already
  recorded."
  ([campaign-id jurisdiction sequence]
   (register-publication campaign-id jurisdiction sequence nil))
  ([campaign-id jurisdiction sequence channel]
   (when-not (and campaign-id (not= campaign-id ""))
     (throw (ex-info "publication: campaign_id required" {})))
   (when-not (and jurisdiction (not= jurisdiction ""))
     (throw (ex-info "publication: jurisdiction required" {})))
   (when (< sequence 0)
     (throw (ex-info "publication: sequence must be >= 0" {})))
   (let [publication-number (str (str/upper-case jurisdiction) "-PUB-" (zero-pad sequence 6))
         record (cond-> {"record_id" publication-number
                         "kind" "campaign-publication"
                         "campaign_id" campaign-id
                         "jurisdiction" jurisdiction
                         "immutable" true}
                  channel (assoc "channel" channel))]
     {"record" record "publication_number" publication-number
      "certificate" (unsigned-certificate "CampaignPublication" publication-number publication-number)})))

(defn append [history result]
  (conj (vec history) (get result "record")))
