(ns inlandbargeops.governor-test
  "Pure unit tests of `inlandbargeops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [inlandbargeops.advisor :as adv]
            [inlandbargeops.governor :as gov]
            [inlandbargeops.store :as store]))

(def vessel-1 {:vessel-id "vessel-1" :name "MV Riverbend Trader" :registered? true :verified? true})
(def vessel-3 {:vessel-id "vessel-3" :name "MV Pending Survey" :registered? true :verified? false})
(def contractor-1 {:contractor-id "contractor-1" :name "Northbank Barge & Towboat Maintenance" :registered? true :verified? true})
(def contractor-2 {:contractor-id "contractor-2" :name "Unverified Drydock Broker Co." :registered? true :verified? false})

(defn- clean-proposal [op vessel-id]
  {:op op :vessel-id vessel-id :summary "s" :rationale "routine inland-waterway logistics coordination"
   :cites [vessel-id] :effect :propose :value {} :confidence 0.85})

(defn- clean-maintenance-order [vessel-id contractor-id cost]
  (assoc (clean-proposal :coordinate-maintenance-order vessel-id)
         :value {:vessel-id vessel-id :contractor-id contractor-id :estimated-cost cost}))

(deftest vessel-unregistered-is-hard
  (testing "no vessel record at all -> HARD hold"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          verdict (gov/check {} nil (clean-proposal :log-shipment-record "unknown-vessel") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:vessel-unverified} (map :rule (:violations verdict)))))))

(deftest vessel-unverified-is-hard
  (testing "vessel registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"vessel-3" vessel-3})
          verdict (gov/check {} nil (clean-proposal :log-shipment-record "vessel-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:vessel-unverified} (map :rule (:violations verdict)))))))

(deftest contractor-missing-on-maintenance-order-is-hard
  (testing "maintenance-order proposal with no :contractor-id at all -> HARD hold"
    (let [s (store/mem-store {"vessel-1" vessel-1} {"contractor-1" contractor-1})
          verdict (gov/check {} nil (clean-maintenance-order "vessel-1" nil 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:contractor-unverified} (map :rule (:violations verdict)))))))

(deftest contractor-unregistered-on-maintenance-order-is-hard
  (testing "maintenance-order proposal naming an unknown contractor -> HARD hold"
    (let [s (store/mem-store {"vessel-1" vessel-1} {"contractor-1" contractor-1})
          verdict (gov/check {} nil (clean-maintenance-order "vessel-1" "unknown-contractor" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:contractor-unverified} (map :rule (:violations verdict)))))))

(deftest contractor-unverified-on-maintenance-order-is-hard
  (testing "maintenance-order proposal naming a registered-but-unverified contractor -> HARD hold"
    (let [s (store/mem-store {"vessel-1" vessel-1} {"contractor-1" contractor-1 "contractor-2" contractor-2})
          verdict (gov/check {} nil (clean-maintenance-order "vessel-1" "contractor-2" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:contractor-unverified} (map :rule (:violations verdict)))))))

(deftest contractor-verified-on-maintenance-order-is-not-hard-on-contractor-check
  (testing "maintenance-order proposal naming a verified contractor never trips :contractor-unverified"
    (let [s (store/mem-store {"vessel-1" vessel-1} {"contractor-1" contractor-1})
          verdict (gov/check {} nil (clean-maintenance-order "vessel-1" "contractor-1" 100.0) s)]
      (is (empty? (filter #(= :contractor-unverified (:rule %)) (:violations verdict)))))))

(deftest contractor-check-is-scoped-to-maintenance-order-only
  (testing "non-maintenance-order ops never trip :contractor-unverified, even with no contractors registered at all"
    (let [s (store/mem-store {"vessel-1" vessel-1})]
      (doseq [op [:log-shipment-record :schedule-berth-operation :flag-safety-concern]]
        (let [verdict (gov/check {} nil (clean-proposal op "vessel-1") s)]
          (is (empty? (filter #(= :contractor-unverified (:rule %)) (:violations verdict)))
              (str "op " op " must never trip :contractor-unverified")))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-berth-operation "vessel-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          verdict (gov/check {} nil (clean-proposal :finalize-seaworthiness-clearance "vessel-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest seaworthiness-clearance-finalization-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches directly finalizing the vessel-seaworthiness clearance is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          poisoned (assoc (clean-proposal :log-shipment-record "vessel-1")
                          :rationale "finalized the seaworthiness clearance ahead of the scheduled survey"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest river-worthiness-clearance-finalization-content-is-hard
  (testing "a proposal touching finalizing the river-worthiness clearance is HARD-blocked, same as seaworthiness"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          poisoned (assoc (clean-proposal :log-shipment-record "vessel-1")
                          :rationale "issued the river-worthiness clearance before the scheduled inspection"
                          :confidence 0.95)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest cargo-load-safety-clearance-finalization-content-is-hard
  (testing "a proposal touching finalizing the cargo-load-safety clearance is HARD-blocked, same as seaworthiness"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          poisoned (assoc (clean-proposal :log-shipment-record "vessel-1")
                          :rationale "approved the cargo load as safe before the survey report was on file"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest override-tow-captain-judgment-content-is-hard
  (testing "a proposal touching overriding the tow captain's safety judgment is HARD-blocked"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          poisoned (assoc (clean-proposal :schedule-berth-operation "vessel-1")
                          :summary "the coordinator overrode the tow captain's safety judgment to keep the lock window")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest override-lockmaster-judgment-content-is-hard
  (testing "a proposal touching overriding the lockmaster's safety judgment is HARD-blocked"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          poisoned (assoc (clean-proposal :schedule-berth-operation "vessel-1")
                          :summary "the coordinator overrode the lockmaster's safety judgment to force an early lock passage")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest direct-navigation-content-is-hard
  (testing "a proposal touching directly navigating the barge is HARD-blocked"
    (let [s (store/mem-store {"vessel-1" vessel-1} {"contractor-1" contractor-1})
          poisoned (assoc (clean-maintenance-order "vessel-1" "contractor-1" 100.0)
                          :summary "directly navigated the barge out of the dock ahead of clearance")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest direct-lock-gate-operation-content-is-hard
  (testing "a proposal touching directly operating lock gates is HARD-blocked"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          poisoned (assoc (clean-proposal :schedule-berth-operation "vessel-1")
                          :summary "directly operated the lock gates to let the tow pass ahead of schedule")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest bypass-inland-waterway-safety-protocol-content-is-hard
  (testing "a proposal touching bypassing an inland waterway safety protocol is HARD-blocked"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          poisoned (assoc (clean-proposal :log-shipment-record "vessel-1")
                          :rationale "bypassed the inland waterway safety protocol to save time at the dock"
                          :confidence 0.9)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-safety-concern-is-not-scope-excluded
  (testing "flagging observed cargo-load-safety/river-worthiness/hazmat concerns as a SAFETY CONCERN (not a clearance finalization) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          concern (assoc (clean-proposal :flag-safety-concern "vessel-1")
                         :value {:concern "hazmat placard mismatch on hold 3 manifest, possible cargo securement anomaly, river-worthiness observation pending survey"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (cargo-load-safety/river-worthiness/hazmat) is exactly what this op exists to surface"))))

(deftest safety-concern-always-escalates-clean
  (testing ":flag-safety-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"vessel-1" vessel-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-safety-concern "vessel-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-maintenance-order-always-escalates
  (testing "a :coordinate-maintenance-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"vessel-1" vessel-1} {"contractor-1" contractor-1})
          expensive (assoc (clean-maintenance-order "vessel-1" "contractor-1" 42000.0) :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-maintenance-order-does-not-force-escalate
  (testing "a :coordinate-maintenance-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"vessel-1" vessel-1} {"contractor-1" contractor-1})
          cheap (assoc (clean-maintenance-order "vessel-1" "contractor-1" 1200.0) :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------- self-trip regression -----------------------------
;;
;; A known bug class in this actor fleet: the governor's own
;; scope-exclusion term list is sometimes phrased as a bare noun (e.g.
;; "seaworthiness", "river-worthiness" or "cargo load safety"), which
;; then accidentally matches inside the mock advisor's own DEFAULT
;; rationale/disclaimer text for a legitimate, allowed proposal --
;; causing the actor to self-block its own happy path. This is a
;; dedicated regression test: every op the default mock advisor can
;; generate, with default (non-`out-of-scope?`) request patches, must
;; NEVER trip `:scope-excluded` or `:op-not-allowed`.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals for every allowed op never trip the governor's scope-exclusion check"
    (let [s (store/mem-store {"vessel-1" vessel-1} {"contractor-1" contractor-1})]
      (doseq [op [:log-shipment-record :schedule-berth-operation :coordinate-maintenance-order
                  :flag-safety-concern]]
        (let [patch (if (= op :coordinate-maintenance-order)
                      {:item "routine hull inspection" :estimated-cost 1200.0 :contractor-id "contractor-1"}
                      {})
              proposal (adv/infer nil {:op op :vessel-id "vessel-1" :patch patch})
              verdict (gov/check {:vessel-id "vessel-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:summary :rationale]))))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must always be inside the closed op allowlist")))))))
