(ns adreview.registry-test
  "The pure record layer: it never mints a 广告批准文号, its ceiling and
  validity checks distinguish 'within limits' from 'un-checkable', and
  its dates order correctly without a date library."
  (:require [clojure.test :refer [deftest is testing]]
            [adreview.registry :as registry]))

;; ----------------------------- ceiling -----------------------------

(deftest media-spend-ceiling
  (is (true?  (registry/media-spend-exceeds-authorized-budget?
               {:proposed-media-spend 500 :authorized-budget 400})))
  (is (false? (registry/media-spend-exceeds-authorized-budget?
               {:proposed-media-spend 400 :authorized-budget 400}))
      "equal to the ceiling is within it"))

(deftest an-unrecorded-budget-is-uncheckable-not-within-limits
  (testing "the predicate alone reads as 'not over' -- which is why callers must ask checkable? first"
    (is (false? (registry/media-spend-exceeds-authorized-budget?
                 {:proposed-media-spend 500 :authorized-budget nil}))))
  (is (false? (registry/media-spend-exceeds-authorized-budget-checkable?
               {:proposed-media-spend 500 :authorized-budget nil})))
  (is (false? (registry/media-spend-exceeds-authorized-budget-checkable?
               {:proposed-media-spend nil :authorized-budget 400})))
  (is (true?  (registry/media-spend-exceeds-authorized-budget-checkable?
               {:proposed-media-spend 500 :authorized-budget 400}))))

;; ----------------------------- 广告批准文号 -----------------------------

(deftest approval-number-presence
  (is (true?  (registry/approval-number-present? {:ad-approval-number "国食健广审(视)第2026030001号"})))
  (is (false? (registry/approval-number-present? {:ad-approval-number ""})))
  (is (false? (registry/approval-number-present? {:ad-approval-number "   "}))
      "whitespace is not a number on file")
  (is (false? (registry/approval-number-present? {:ad-approval-number nil})))
  (is (false? (registry/approval-number-present? {}))))

(deftest approval-expiry-orders-correctly
  (is (true?  (registry/approval-elapsed? {:ad-approval-valid-until "2026-06-30"
                                           :publish-date "2026-08-01"})))
  (is (false? (registry/approval-elapsed? {:ad-approval-valid-until "2027-03-01"
                                           :publish-date "2026-08-01"})))
  (testing "valid through the last day -- expiry is strictly before the publish date"
    (is (false? (registry/approval-elapsed? {:ad-approval-valid-until "2026-08-01"
                                             :publish-date "2026-08-01"}))))
  (testing "year boundaries order correctly under string comparison"
    (is (true? (registry/approval-elapsed? {:ad-approval-valid-until "2025-12-31"
                                            :publish-date "2026-01-01"})))))

(deftest a-non-iso-date-is-uncheckable-not-still-valid
  (doseq [bad ["2026/08/01" "1 Aug 2026" "2026-8-1" "" nil 20260801]]
    (is (false? (registry/approval-elapsed-checkable?
                 {:ad-approval-valid-until bad :publish-date "2026-08-01"}))
        (str "valid-until " (pr-str bad)))
    (is (false? (registry/approval-elapsed? {:ad-approval-valid-until bad
                                             :publish-date "2026-08-01"}))
        "and the predicate alone reads as 'not elapsed' -- hence the separate checkable? gate"))
  (is (true? (registry/approval-elapsed-checkable?
              {:ad-approval-valid-until "2026-06-30" :publish-date "2026-08-01"}))))

(deftest iso-date-recogniser
  (is (true?  (registry/iso-date? "2026-07-27")))
  (is (false? (registry/iso-date? "2026-07-27T00:00:00Z")))
  (is (false? (registry/iso-date? 20260727))))

;; ----------------------------- records -----------------------------

(deftest review-submission-record-is-an-unsigned-draft
  (let [r (registry/register-review-submission "cmp-1" "CHN" 0)]
    (is (= "CHN-RVW-000000" (get r "review_number")))
    (is (= "ad-review-submission-draft" (get-in r ["record" "kind"])))
    (is (true? (get-in r ["record" "immutable"])))
    (testing "the actor never claims a registry issued this"
      (is (nil? (get-in r ["certificate" "proof"])))
      (is (false? (get-in r ["certificate" "issued_by_registry"])))
      (is (= "draft-unsigned" (get-in r ["certificate" "status"]))))
    (testing "it does NOT mint a 广告批准文号 -- only the review authority issues one"
      (is (nil? (get-in r ["record" "ad_approval_number"]))))))

(deftest publication-record-carries-the-channel
  (let [r (registry/register-publication "cmp-1" "CHN" 7 "CCTV-1")]
    (is (= "CHN-PUB-000007" (get r "publication_number")))
    (is (= "CCTV-1" (get-in r ["record" "channel"])))
    (testing "the 3-arity omits the channel field rather than inventing one"
      (is (nil? (get-in (registry/register-publication "cmp-1" "CHN" 7) ["record" "channel"]))))))

(deftest records-validate-their-required-fields
  (doseq [f [registry/register-review-submission registry/register-publication]]
    (is (thrown? clojure.lang.ExceptionInfo (f "" "CHN" 0)))
    (is (thrown? clojure.lang.ExceptionInfo (f "cmp-1" "" 0)))
    (is (thrown? clojure.lang.ExceptionInfo (f "cmp-1" "CHN" -1)))))

(deftest append-is-append-only
  (let [r1 (registry/register-review-submission "cmp-1" "CHN" 0)
        r2 (registry/register-review-submission "cmp-2" "CHN" 1)
        h (-> [] (registry/append r1) (registry/append r2))]
    (is (= 2 (count h)))
    (is (= "CHN-RVW-000000" (get (first h) "record_id")))
    (is (= "CHN-RVW-000001" (get (second h) "record_id")))))
