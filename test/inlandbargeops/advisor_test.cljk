(ns inlandbargeops.advisor-test
  "Unit tests of `inlandbargeops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [inlandbargeops.advisor :as adv]
            [inlandbargeops.store :as store]))

(def db (store/seed-db))

(deftest propose-shipment-record-shape
  (testing "shipment-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-shipment-record
                           :vessel-id "vessel-1"
                           :patch {:manifest-lines 42 :cargo-weight-tonnes 3200 :hazmat-class "none"}})]
      (is (= :log-shipment-record (:op p)))
      (is (= "vessel-1" (:vessel-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :vessel-id)))))

(deftest propose-berth-operation-shape
  (testing "dock/lock-scheduling proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-berth-operation
                           :vessel-id "vessel-2"
                           :patch {:berth "dock-4" :lock-window "lock-7 2026-07-20T06:00:00Z"}})]
      (is (= :schedule-berth-operation (:op p)))
      (is (= "vessel-2" (:vessel-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-maintenance-order-shape
  (testing "maintenance-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-maintenance-order
                           :vessel-id "vessel-1"
                           :patch {:item "routine hull inspection" :estimated-cost 1200.0
                                   :contractor-id "contractor-1"}})]
      (is (= :coordinate-maintenance-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p)))
      (is (= "contractor-1" (get-in p [:value :contractor-id]))))))

(deftest propose-safety-concern-shape
  (testing "safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-safety-concern
                           :vessel-id "vessel-1"
                           :patch {:concern "hazmat placard mismatch on hold 3 manifest"}})]
      (is (= :flag-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-shipment-record :schedule-berth-operation :coordinate-maintenance-order
                :flag-safety-concern]]
      (let [p (adv/infer db {:op op :vessel-id "vessel-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-shipment-record :schedule-berth-operation :coordinate-maintenance-order
                :flag-safety-concern]]
      (let [p (adv/infer db {:op op :vessel-id "vessel-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
