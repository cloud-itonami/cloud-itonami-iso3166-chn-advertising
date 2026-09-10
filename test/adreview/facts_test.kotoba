(ns adreview.facts-test
  "The non-fabrication discipline as executable tests: an uncovered
  jurisdiction has NO spec-basis, coverage is reported honestly, and
  the reviewed-category set is CLOSED (a category nobody put in it is
  not silently swept in)."
  (:require [clojure.test :refer [deftest is testing]]
            [adreview.facts :as facts]))

(deftest chn-has-a-cited-spec-basis
  (let [sb (facts/spec-basis "CHN")]
    (is (some? sb))
    (is (= "国家市场监督管理总局 (State Administration for Market Regulation, SAMR)"
           (:owner-authority sb)))
    (testing "every citation carries a real, fetched official URL"
      (is (re-find #"^https://www\.samr\.gov\.cn/" (:provenance sb)))
      (is (re-find #"^https://www\.samr\.gov\.cn/" (:review-provenance sb)))
      (is (re-find #"^https://www\.samr\.gov\.cn/" (:internet-provenance sb))))
    (testing "the retrieval date is recorded, so a stale citation is visible"
      (is (= "2026-07-27" (:retrieved-at sb))))))

(deftest uncovered-jurisdiction-has-no-spec-basis
  (is (nil? (facts/spec-basis "ATL")))
  (is (= [] (facts/evidence-checklist "ATL")))
  (is (nil? (facts/required-evidence-satisfied? "ATL" ["anything"])))
  (is (nil? (facts/endorser-minimum-age "ATL")))
  (is (nil? (facts/review-basis "ATL")))
  (is (nil? (facts/internet-basis "ATL"))))

(deftest coverage-is-reported-honestly
  (let [c (facts/coverage ["CHN" "JPN" "ATL"])]
    (is (= 3 (:requested c)))
    (is (= 1 (:covered c)))
    (is (= ["CHN"] (:covered-jurisdictions c)))
    (is (= ["ATL" "JPN"] (:missing-jurisdictions c)))))

(deftest evidence-checklist-must-be-fully-satisfied
  (let [checklist (facts/evidence-checklist "CHN")]
    (is (= 4 (count checklist)))
    (is (true? (facts/required-evidence-satisfied? "CHN" checklist)))
    (is (false? (facts/required-evidence-satisfied? "CHN" (butlast checklist)))
        "a partial checklist is not satisfied")))

(deftest reviewed-category-set-is-closed
  (testing "the six categories 广告法第四十六条 names"
    (doseq [cat [:medical-service :drug :medical-device :pesticide
                 :veterinary-drug :health-food]]
      (is (true? (facts/requires-ad-review? "CHN" cat)) (str cat))))
  (testing "特殊医学用途配方食品, added by 互联网广告管理办法第七条"
    (is (true? (facts/requires-ad-review? "CHN" :special-medical-food))))
  (testing "anything else is NOT swept in"
    (is (false? (facts/requires-ad-review? "CHN" :general)))
    (is (false? (facts/requires-ad-review? "CHN" :automotive)))
    (is (false? (facts/requires-ad-review? "CHN" nil))))
  (testing "an uncovered jurisdiction never answers yes"
    (is (false? (facts/requires-ad-review? "ATL" :drug)))))

(deftest every-reviewed-category-names-the-source-that-put-it-there
  (let [{:keys [reviewed-categories reviewed-categories-source]} (facts/spec-basis "CHN")]
    (is (= reviewed-categories (set (keys reviewed-categories-source)))
        "no category is in the closed set without a cited article")))

(deftest endorser-minimum-age-comes-from-the-statute
  (is (= 10 (facts/endorser-minimum-age "CHN")) "广告法第三十八条: 不满十周岁"))
