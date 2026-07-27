(ns adreview.store
  "SSoT for the China advertising-review actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior cloud-itonami actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store.

  Both implement the same protocol and pass the same contract
  (test/adreview/store_contract_test.clj).

  The primary entity is a `campaign` (广告发布案件). The 广告审查
  submission and the publication apply SEQUENTIALLY to the SAME
  campaign record (review package first, publication later), guarded by
  dedicated `:reviewed?`/`:published?` booleans -- never a `:status`
  value.

  The ledger stays append-only on every backend."
  (:require [adreview.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (campaign [s id])
  (all-campaigns [s])
  (assessment-of [s campaign-id] "committed regime assessment, or nil")
  (ledger [s])
  (review-history [s] "the append-only 广告审查 submission history")
  (publication-history [s] "the append-only publication history")
  (next-review-sequence [s jurisdiction])
  (next-publication-sequence [s jurisdiction])
  (campaign-already-reviewed? [s campaign-id])
  (campaign-already-published? [s campaign-id])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-campaigns [s campaigns] "replace/seed the campaign directory"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained campaign set covering both actuation
  lifecycles (review submission, publication) plus every one of the
  governor's own China-specific checks."
  []
  {:campaigns
   {;; clean, reviewed category with a valid 广告批准文号
    "cmp-1" {:id "cmp-1" :advertiser "华东健康食品有限公司" :channel "CCTV-1"
             :category :health-food :jurisdiction "CHN"
             :proposed-media-spend 1800000 :authorized-budget 2000000
             :ad-approval-number "国食健广审(视)第2026030001号"
             :ad-approval-valid-until "2027-03-01" :publish-date "2026-08-01"
             :internet-ad? false :popup? false
             :labelled-as-ad? true :one-click-close? false
             :has-endorser? false
             :reviewed? false :published? false :status :intake}
    ;; no spec-basis: a jurisdiction this repo does not cover
    "cmp-2" {:id "cmp-2" :advertiser "Atlantis Media Ltd" :channel "web"
             :category :health-food :jurisdiction "ATL"
             :proposed-media-spend 100000 :authorized-budget 200000
             :ad-approval-number "N/A"
             :ad-approval-valid-until "2027-01-01" :publish-date "2026-08-01"
             :internet-ad? true :popup? false
             :labelled-as-ad? true :one-click-close? false
             :has-endorser? false
             :reviewed? false :published? false :status :intake}
    ;; FLAGSHIP: reviewed category, NO 广告批准文号 on file
    "cmp-3" {:id "cmp-3" :advertiser "南方医疗器械股份有限公司" :channel "微信朋友圈"
             :category :medical-device :jurisdiction "CHN"
             :proposed-media-spend 900000 :authorized-budget 1000000
             :ad-approval-number ""
             :ad-approval-valid-until "2027-01-01" :publish-date "2026-08-01"
             :internet-ad? true :popup? false
             :labelled-as-ad? true :one-click-close? false
             :has-endorser? false
             :reviewed? false :published? false :status :intake}
    ;; FLAGSHIP: reviewed category, 批准文号 present but EXPIRED at publish date
    "cmp-4" {:id "cmp-4" :advertiser "北方制药集团" :channel "省级卫视"
             :category :drug :jurisdiction "CHN"
             :proposed-media-spend 500000 :authorized-budget 800000
             :ad-approval-number "国药广审(视)第2024010007号"
             :ad-approval-valid-until "2026-06-30" :publish-date "2026-08-01"
             :internet-ad? false :popup? false
             :labelled-as-ad? true :one-click-close? false
             :has-endorser? false
             :reviewed? false :published? false :status :intake}
    ;; internet pop-up without a one-click close (办法第十条)
    "cmp-5" {:id "cmp-5" :advertiser "上海快消品牌" :channel "移动端弹窗"
             :category :general :jurisdiction "CHN"
             :proposed-media-spend 300000 :authorized-budget 400000
             :ad-approval-number nil
             :ad-approval-valid-until nil :publish-date "2026-08-01"
             :internet-ad? true :popup? true
             :labelled-as-ad? true :one-click-close? false
             :has-endorser? false
             :reviewed? false :published? false :status :intake}
    ;; internet ad not marked as 广告 (办法第九条 / 广告法第十四条)
    "cmp-6" {:id "cmp-6" :advertiser "杭州电商平台" :channel "竞价排名"
             :category :general :jurisdiction "CHN"
             :proposed-media-spend 250000 :authorized-budget 400000
             :ad-approval-number nil
             :ad-approval-valid-until nil :publish-date "2026-08-01"
             :internet-ad? true :popup? false
             :labelled-as-ad? false :one-click-close? false
             :has-endorser? false
             :reviewed? false :published? false :status :intake}
    ;; endorser who never used the product (广告法第三十八条)
    "cmp-7" {:id "cmp-7" :advertiser "广州日化" :channel "短视频"
             :category :general :jurisdiction "CHN"
             :proposed-media-spend 200000 :authorized-budget 400000
             :ad-approval-number nil
             :ad-approval-valid-until nil :publish-date "2026-08-01"
             :internet-ad? true :popup? false
             :labelled-as-ad? true :one-click-close? false
             :has-endorser? true :endorser-used-product? false :endorser-age 32
             :reviewed? false :published? false :status :intake}
    ;; media spend over the client-authorized budget
    "cmp-8" {:id "cmp-8" :advertiser "深圳消费电子" :channel "开屏广告"
             :category :general :jurisdiction "CHN"
             :proposed-media-spend 900000 :authorized-budget 400000
             :ad-approval-number nil
             :ad-approval-valid-until nil :publish-date "2026-08-01"
             :internet-ad? true :popup? false
             :labelled-as-ad? true :one-click-close? false
             :has-endorser? false
             :reviewed? false :published? false :status :intake}
    ;; budget not recorded at all -- un-checkable is NOT within limits
    "cmp-9" {:id "cmp-9" :advertiser "成都食品" :channel "户外"
             :category :general :jurisdiction "CHN"
             :proposed-media-spend 350000 :authorized-budget nil
             :ad-approval-number nil
             :ad-approval-valid-until nil :publish-date "2026-08-01"
             :internet-ad? false :popup? false
             :labelled-as-ad? true :one-click-close? false
             :has-endorser? false
             :reviewed? false :published? false :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- submit-review!
  [s campaign-id]
  (let [c (campaign s campaign-id)
        seq-n (next-review-sequence s (:jurisdiction c))
        result (registry/register-review-submission campaign-id (:jurisdiction c) seq-n)]
    {:result result
     :campaign-patch {:reviewed? true
                      :review-number (get result "review_number")}}))

(defn- publish-campaign!
  [s campaign-id]
  (let [c (campaign s campaign-id)
        seq-n (next-publication-sequence s (:jurisdiction c))
        result (registry/register-publication campaign-id (:jurisdiction c) seq-n (:channel c))]
    {:result result
     :campaign-patch {:published? true
                      :publication-number (get result "publication_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (campaign [_ id] (get-in @a [:campaigns id]))
  (all-campaigns [_] (sort-by :id (vals (:campaigns @a))))
  (assessment-of [_ campaign-id] (get-in @a [:assessments campaign-id]))
  (ledger [_] (:ledger @a))
  (review-history [_] (:review-records @a))
  (publication-history [_] (:publication-records @a))
  (next-review-sequence [_ jurisdiction] (get-in @a [:review-sequences jurisdiction] 0))
  (next-publication-sequence [_ jurisdiction] (get-in @a [:publication-sequences jurisdiction] 0))
  (campaign-already-reviewed? [_ campaign-id] (boolean (get-in @a [:campaigns campaign-id :reviewed?])))
  (campaign-already-published? [_ campaign-id] (boolean (get-in @a [:campaigns campaign-id :published?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :campaign/upsert
      (swap! a update-in [:campaigns (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :campaign/mark-reviewed
      (let [campaign-id (first path)
            {:keys [result campaign-patch]} (submit-review! s campaign-id)
            jurisdiction (:jurisdiction (campaign s campaign-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:review-sequences jurisdiction] (fnil inc 0))
                       (update-in [:campaigns campaign-id] merge campaign-patch)
                       (update :review-records registry/append result))))
        result)

      :campaign/mark-published
      (let [campaign-id (first path)
            {:keys [result campaign-patch]} (publish-campaign! s campaign-id)
            jurisdiction (:jurisdiction (campaign s campaign-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:publication-sequences jurisdiction] (fnil inc 0))
                       (update-in [:campaigns campaign-id] merge campaign-patch)
                       (update :publication-records registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-campaigns [s campaigns] (when (seq campaigns) (swap! a assoc :campaigns campaigns)) s))

(defn seed-db
  "A MemStore seeded with the demo campaign set."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :review-sequences {} :review-records []
                           :publication-sequences {} :publication-records []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  {:campaign/id                        {:db/unique :db.unique/identity}
   :assessment/campaign-id             {:db/unique :db.unique/identity}
   :ledger/seq                         {:db/unique :db.unique/identity}
   :review-record/seq                  {:db/unique :db.unique/identity}
   :publication-record/seq             {:db/unique :db.unique/identity}
   :review-sequence/jurisdiction       {:db/unique :db.unique/identity}
   :publication-sequence/jurisdiction  {:db/unique :db.unique/identity}})

(defn- campaign->tx
  [{:keys [id advertiser channel category jurisdiction
           proposed-media-spend authorized-budget
           ad-approval-number ad-approval-valid-until publish-date
           internet-ad? popup? labelled-as-ad? one-click-close?
           has-endorser? endorser-used-product? endorser-age
           reviewed? published? status review-number publication-number]}]
  (cond-> {:campaign/id id}
    advertiser                     (assoc :campaign/advertiser advertiser)
    channel                        (assoc :campaign/channel channel)
    category                       (assoc :campaign/category category)
    jurisdiction                   (assoc :campaign/jurisdiction jurisdiction)
    proposed-media-spend           (assoc :campaign/proposed-media-spend proposed-media-spend)
    authorized-budget              (assoc :campaign/authorized-budget authorized-budget)
    ad-approval-number             (assoc :campaign/ad-approval-number ad-approval-number)
    ad-approval-valid-until        (assoc :campaign/ad-approval-valid-until ad-approval-valid-until)
    publish-date                   (assoc :campaign/publish-date publish-date)
    (some? internet-ad?)           (assoc :campaign/internet-ad? internet-ad?)
    (some? popup?)                 (assoc :campaign/popup? popup?)
    (some? labelled-as-ad?)        (assoc :campaign/labelled-as-ad? labelled-as-ad?)
    (some? one-click-close?)       (assoc :campaign/one-click-close? one-click-close?)
    (some? has-endorser?)          (assoc :campaign/has-endorser? has-endorser?)
    (some? endorser-used-product?) (assoc :campaign/endorser-used-product? endorser-used-product?)
    endorser-age                   (assoc :campaign/endorser-age endorser-age)
    (some? reviewed?)              (assoc :campaign/reviewed? reviewed?)
    (some? published?)             (assoc :campaign/published? published?)
    status                         (assoc :campaign/status status)
    review-number                  (assoc :campaign/review-number review-number)
    publication-number             (assoc :campaign/publication-number publication-number)))

(def ^:private campaign-pull
  [:campaign/id :campaign/advertiser :campaign/channel :campaign/category
   :campaign/jurisdiction :campaign/proposed-media-spend :campaign/authorized-budget
   :campaign/ad-approval-number :campaign/ad-approval-valid-until :campaign/publish-date
   :campaign/internet-ad? :campaign/popup? :campaign/labelled-as-ad? :campaign/one-click-close?
   :campaign/has-endorser? :campaign/endorser-used-product? :campaign/endorser-age
   :campaign/reviewed? :campaign/published? :campaign/status
   :campaign/review-number :campaign/publication-number])

(defn- pull->campaign [m]
  (when (:campaign/id m)
    ;; NOTE: `:authorized-budget` and the two dates are deliberately NOT
    ;; coerced with `boolean`/a default -- an unrecorded budget must stay
    ;; nil so `media-spend-exceeds-authorized-budget-checkable?` can see
    ;; that it is un-checkable rather than read a fabricated 0.
    {:id (:campaign/id m) :advertiser (:campaign/advertiser m) :channel (:campaign/channel m)
     :category (:campaign/category m) :jurisdiction (:campaign/jurisdiction m)
     :proposed-media-spend (:campaign/proposed-media-spend m)
     :authorized-budget (:campaign/authorized-budget m)
     :ad-approval-number (:campaign/ad-approval-number m)
     :ad-approval-valid-until (:campaign/ad-approval-valid-until m)
     :publish-date (:campaign/publish-date m)
     :internet-ad? (boolean (:campaign/internet-ad? m))
     :popup? (boolean (:campaign/popup? m))
     :labelled-as-ad? (boolean (:campaign/labelled-as-ad? m))
     :one-click-close? (boolean (:campaign/one-click-close? m))
     :has-endorser? (boolean (:campaign/has-endorser? m))
     :endorser-used-product? (boolean (:campaign/endorser-used-product? m))
     :endorser-age (:campaign/endorser-age m)
     :reviewed? (boolean (:campaign/reviewed? m)) :published? (boolean (:campaign/published? m))
     :status (:campaign/status m)
     :review-number (:campaign/review-number m)
     :publication-number (:campaign/publication-number m)}))

(defrecord DatomicStore [conn]
  Store
  (campaign [_ id]
    (pull->campaign (d/pull (d/db conn) campaign-pull [:campaign/id id])))
  (all-campaigns [_]
    (->> (d/q '[:find [?id ...] :where [?e :campaign/id ?id]] (d/db conn))
         (map #(pull->campaign (d/pull (d/db conn) campaign-pull [:campaign/id %])))
         (sort-by :id)))
  (assessment-of [_ campaign-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?cid
                    :where [?a :assessment/campaign-id ?cid] [?a :assessment/payload ?p]]
                  (d/db conn) campaign-id)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (review-history [_] (ls/read-stream conn :review-record/seq :review-record/record))
  (publication-history [_] (ls/read-stream conn :publication-record/seq :publication-record/record))
  (next-review-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
               :where [?e :review-sequence/jurisdiction ?j] [?e :review-sequence/next ?n]]
             (d/db conn) jurisdiction)
        0))
  (next-publication-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
               :where [?e :publication-sequence/jurisdiction ?j] [?e :publication-sequence/next ?n]]
             (d/db conn) jurisdiction)
        0))
  (campaign-already-reviewed? [s campaign-id]
    (boolean (:reviewed? (campaign s campaign-id))))
  (campaign-already-published? [s campaign-id]
    (boolean (:published? (campaign s campaign-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :campaign/upsert
      (d/transact! conn [(campaign->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/campaign-id (first path) :assessment/payload (ls/enc payload)}])

      :campaign/mark-reviewed
      (let [campaign-id (first path)
            {:keys [result campaign-patch]} (submit-review! s campaign-id)
            jurisdiction (:jurisdiction (campaign s campaign-id))
            next-n (inc (next-review-sequence s jurisdiction))]
        (d/transact! conn
                     [(campaign->tx (assoc campaign-patch :id campaign-id))
                      {:review-sequence/jurisdiction jurisdiction :review-sequence/next next-n}
                      {:review-record/seq (count (review-history s))
                       :review-record/record (ls/enc (get result "record"))}])
        result)

      :campaign/mark-published
      (let [campaign-id (first path)
            {:keys [result campaign-patch]} (publish-campaign! s campaign-id)
            jurisdiction (:jurisdiction (campaign s campaign-id))
            next-n (inc (next-publication-sequence s jurisdiction))]
        (d/transact! conn
                     [(campaign->tx (assoc campaign-patch :id campaign-id))
                      {:publication-sequence/jurisdiction jurisdiction :publication-sequence/next next-n}
                      {:publication-record/seq (count (publication-history s))
                       :publication-record/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact)
    fact)
  (with-campaigns [s campaigns]
    (when (seq campaigns) (d/transact! conn (mapv campaign->tx (vals campaigns)))) s))

(defn datomic-store
  ([] (datomic-store {}))
  ([{:keys [campaigns]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-campaigns s campaigns))))

(defn datomic-seed-db
  []
  (datomic-store (demo-data)))
