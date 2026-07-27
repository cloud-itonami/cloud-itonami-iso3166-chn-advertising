(ns adreview.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  Trust Controls implemented faithfully. The single invariant under
  test:

    AdReview-LLM never publishes an advertisement the Ad-Review
    Compliance Governor would reject, `:review/submit`/
    `:campaign/publish` NEVER auto-commit at any phase,
    `:campaign/intake` MAY auto-commit when clean, and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [adreview.store :as store]
            [adreview.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :advertising-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess! [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :regime/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- review! [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-review") {:op :review/submit :subject subject} operator)
  (approve! actor (str tid-prefix "-review")))

(defn- ready! [actor tid-prefix subject]
  (assess! actor tid-prefix subject)
  (review! actor tid-prefix subject))

(defn- last-basis [db] (-> (store/ledger db) last :basis))

;; ----------------------------- happy path -----------------------------

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                     {:op :campaign/intake :subject "cmp-1"
                      :patch {:id "cmp-1" :advertiser "华东健康食品有限公司"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "华东健康食品有限公司" (:advertiser (store/campaign db "cmp-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest regime-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :regime/assess :subject "cmp-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "cmp-1")))))))

(deftest clean-campaign-publishes-only-after-human-approval
  (testing "a fully compliant reviewed-category campaign still escalates, then commits"
    (let [[db actor] (fresh)
          _ (ready! actor "t3" "cmp-1")
          res (exec-op actor "t3-pub" {:op :campaign/publish :subject "cmp-1"} operator)]
      (is (= :interrupted (:status res)) "publication is never auto-committed")
      (let [r2 (approve! actor "t3-pub")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/publication-history db))))
        (is (true? (:published? (store/campaign db "cmp-1"))))))))

;; ----------------------------- spec-basis / evidence -----------------------------

(deftest fabricated-jurisdiction-is-held
  (testing "a regime/assess proposal with no official spec-basis -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :regime/assess :subject "cmp-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "cmp-1")) "no assessment written"))))

(deftest publish-without-assessment-is-held
  (testing "campaign/publish before any regime assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :campaign/publish :subject "cmp-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (last-basis db))))))

;; ----------------------------- FLAGSHIP: 广告审查 -----------------------------

(deftest ad-review-approval-missing-is-held-and-unoverridable
  (testing "reviewed category with no 广告批准文号 on file -> HARD hold (flagship check)"
    (let [[db actor] (fresh)
          _ (ready! actor "t6" "cmp-3")
          res (exec-op actor "t6-pub" {:op :campaign/publish :subject "cmp-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)) "a human approver is never even offered the choice")
      (is (some #{:ad-review-approval-missing} (last-basis db)))
      (is (empty? (store/publication-history db))))))

(deftest ad-review-approval-elapsed-is-held
  (testing "reviewed category whose 广告批准文号 expired before the publish date -> HARD hold"
    (let [[db actor] (fresh)
          _ (ready! actor "t7" "cmp-4")
          res (exec-op actor "t7-pub" {:op :campaign/publish :subject "cmp-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:ad-review-approval-elapsed} (last-basis db)))
      (is (empty? (store/publication-history db))))))

(deftest ad-review-validity-unknown-is-not-treated-as-valid
  (testing "reviewed category with an unrecorded validity date -> HARD hold, not a silent pass"
    (let [[db actor] (fresh)
          ;; cmp-1 is otherwise clean; blank out only the validity date.
          _ (swap! (:a db) assoc-in [:campaigns "cmp-1" :ad-approval-valid-until] nil)
          _ (ready! actor "t8" "cmp-1")
          res (exec-op actor "t8-pub" {:op :campaign/publish :subject "cmp-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:ad-review-validity-unknown} (last-basis db)))
      (is (empty? (store/publication-history db))))))

(deftest non-reviewed-category-does-not-need-an-approval-number
  (testing "a general-category campaign publishes without any 广告批准文号"
    (let [[db actor] (fresh)
          ;; cmp-8 is :general but over budget; use cmp-5 with the pop-up fixed.
          _ (swap! (:a db) assoc-in [:campaigns "cmp-5" :one-click-close?] true)
          _ (ready! actor "t9" "cmp-5")
          res (exec-op actor "t9-pub" {:op :campaign/publish :subject "cmp-5"} operator)]
      (is (= :interrupted (:status res)) "escalates on actuation, not held on a review gate")
      (is (= :commit (get-in (approve! actor "t9-pub") [:state :disposition])))
      (is (= 1 (count (store/publication-history db)))))))

;; ----------------------------- internet advertising -----------------------------

(deftest popup-without-one-click-close-is-held
  (let [[db actor] (fresh)
        _ (ready! actor "t10" "cmp-5")
        res (exec-op actor "t10-pub" {:op :campaign/publish :subject "cmp-5"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:popup-no-one-click-close} (last-basis db)))))

(deftest internet-ad-not-labelled-is-held
  (let [[db actor] (fresh)
        _ (ready! actor "t11" "cmp-6")
        res (exec-op actor "t11-pub" {:op :campaign/publish :subject "cmp-6"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:internet-ad-unlabelled} (last-basis db)))))

;; ----------------------------- endorser -----------------------------

(deftest endorser-who-never-used-the-product-is-held
  (let [[db actor] (fresh)
        _ (ready! actor "t12" "cmp-7")
        res (exec-op actor "t12-pub" {:op :campaign/publish :subject "cmp-7"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:endorser-did-not-use-product} (last-basis db)))))

(deftest endorser-under-minimum-age-is-held
  (let [[db actor] (fresh)
        _ (swap! (:a db) update-in [:campaigns "cmp-7"]
                 merge {:endorser-used-product? true :endorser-age 8})
        _ (ready! actor "t13" "cmp-7")
        res (exec-op actor "t13-pub" {:op :campaign/publish :subject "cmp-7"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:endorser-under-minimum-age} (last-basis db)))))

;; ----------------------------- media budget -----------------------------

(deftest media-spend-over-authorized-budget-is-held
  (let [[db actor] (fresh)
        _ (ready! actor "t14" "cmp-8")
        res (exec-op actor "t14-pub" {:op :campaign/publish :subject "cmp-8"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:media-spend-exceeds-authorized-budget} (last-basis db)))))

(deftest unrecorded-budget-is-held-not-silently-within-limits
  (testing "an un-checkable ceiling is its own HARD violation"
    (let [[db actor] (fresh)
          _ (ready! actor "t15" "cmp-9")
          res (exec-op actor "t15-pub" {:op :campaign/publish :subject "cmp-9"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:media-budget-uncheckable} (last-basis db))))))

;; ----------------------------- double-actuation -----------------------------

(deftest double-review-is-held
  (let [[db actor] (fresh)
        _ (ready! actor "t16" "cmp-1")
        res (exec-op actor "t16-again" {:op :review/submit :subject "cmp-1"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:already-reviewed} (last-basis db)))
    (is (= 1 (count (store/review-history db))))))

(deftest double-publish-is-held
  (let [[db actor] (fresh)
        _ (ready! actor "t17" "cmp-1")
        _ (exec-op actor "t17-pub" {:op :campaign/publish :subject "cmp-1"} operator)
        _ (approve! actor "t17-pub")
        res (exec-op actor "t17-again" {:op :campaign/publish :subject "cmp-1"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:already-published} (last-basis db)))
    (is (= 1 (count (store/publication-history db))))))

;; ----------------------------- ledger discipline -----------------------------

(deftest every-decision-leaves-exactly-one-ledger-fact
  (let [[db actor] (fresh)
        before (count (store/ledger db))
        _ (exec-op actor "t18" {:op :campaign/publish :subject "cmp-1"} operator)]
    (is (= (inc before) (count (store/ledger db)))
        "one hold decision -> exactly one ledger fact")))
