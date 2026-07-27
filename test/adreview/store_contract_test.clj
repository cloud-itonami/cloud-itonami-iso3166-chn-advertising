(ns adreview.store-contract-test
  "MemStore ≡ DatomicStore parity for the Store protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [adreview.store :as store]))

(defn- exercise [s]
  (store/commit-record! s {:effect :campaign/upsert
                           :value {:id "cmp-x" :advertiser "X 广告主" :channel "CCTV-1"
                                   :category :health-food :jurisdiction "CHN"
                                   :proposed-media-spend 100 :authorized-budget 200
                                   :ad-approval-number "国食健广审(视)第2026030001号"
                                   :ad-approval-valid-until "2027-03-01"
                                   :publish-date "2026-08-01"
                                   :internet-ad? false :popup? false
                                   :labelled-as-ad? true :one-click-close? false
                                   :has-endorser? false
                                   :reviewed? false :published? false :status :intake}})
  (store/commit-record! s {:effect :assessment/set
                           :path ["cmp-x"]
                           :payload {:jurisdiction "CHN" :checklist ["a"] :spec-basis "x"}})
  (store/commit-record! s {:effect :campaign/mark-reviewed :path ["cmp-x"]})
  (store/commit-record! s {:effect :campaign/mark-published :path ["cmp-x"]})
  (store/append-ledger! s {:t :committed :op :test})
  {:campaign (store/campaign s "cmp-x")
   :assessment (store/assessment-of s "cmp-x")
   :reviews (store/review-history s)
   :publications (store/publication-history s)
   :ledger (store/ledger s)
   :reviewed? (store/campaign-already-reviewed? s "cmp-x")
   :published? (store/campaign-already-published? s "cmp-x")})

(defn- empty-mem []
  (store/->MemStore (atom {:campaigns {} :assessments {} :ledger []
                           :review-sequences {} :review-records []
                           :publication-sequences {} :publication-records []})))

(deftest mem-and-datomic-parity
  (let [m (exercise (empty-mem))
        d (exercise (store/datomic-store {}))]
    (is (= (:advertiser (:campaign m)) (:advertiser (:campaign d))))
    (is (= (:category (:campaign m)) (:category (:campaign d))))
    (is (true? (:reviewed? m)))
    (is (true? (:reviewed? d)))
    (is (true? (:published? m)))
    (is (true? (:published? d)))
    (is (= 1 (count (:reviews m))))
    (is (= 1 (count (:reviews d))))
    (is (= 1 (count (:publications m))))
    (is (= 1 (count (:publications d))))
    (is (= 1 (count (:ledger m))))
    (is (= 1 (count (:ledger d))))
    (is (= (:assessment m) (:assessment d)))
    (testing "the publication record carries the campaign's channel on both backends"
      (is (= "CCTV-1" (get (first (:publications m)) "channel")))
      (is (= "CCTV-1" (get (first (:publications d)) "channel"))))))

(deftest an-unrecorded-budget-survives-the-datomic-round-trip-as-nil
  (testing "a nil budget must NOT come back as 0 -- that would silently pass the ceiling check"
    (let [s (store/datomic-store {})]
      (store/commit-record! s {:effect :campaign/upsert
                               :value {:id "cmp-nobudget" :advertiser "Y" :jurisdiction "CHN"
                                       :category :general :proposed-media-spend 350000
                                       :authorized-budget nil :status :intake}})
      (is (nil? (:authorized-budget (store/campaign s "cmp-nobudget")))))))

(deftest sequences-are-per-jurisdiction-and-monotonic
  (let [s (empty-mem)]
    (doseq [id ["a" "b"]]
      (store/commit-record! s {:effect :campaign/upsert
                               :value {:id id :jurisdiction "CHN" :category :general
                                       :channel "web" :status :intake}})
      (store/commit-record! s {:effect :campaign/mark-published :path [id]}))
    (is (= ["CHN-PUB-000000" "CHN-PUB-000001"]
           (mapv #(get % "record_id") (store/publication-history s))))))
