(ns inlandbargeops.store-contract-test
  "Contract tests for `inlandbargeops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [inlandbargeops.store :as store]))

(deftest mem-store-vessel-lookup
  (testing "MemStore can store and retrieve vessels by ID (string keys)"
    (let [vessels {"v1" {:vessel-id "v1" :name "MV Alice's Trader" :registered? true :verified? true}}
          s (store/mem-store vessels)]
      (is (some? (store/vessel-record s "v1")))
      (is (nil? (store/vessel-record s "v99"))))))

(deftest mem-store-all-vessel-records
  (testing "MemStore returns all vessels in sorted order"
    (let [vessels {"v2" {:vessel-id "v2" :name "MV Bob's Voyager"}
                   "v1" {:vessel-id "v1" :name "MV Alice's Trader"}
                   "v3" {:vessel-id "v3" :name "MV Carol's Carrier"}}
          s (store/mem-store vessels)
          all-v (store/all-vessel-records s)]
      (is (= 3 (count all-v)))
      (is (= "v1" (:vessel-id (first all-v))))
      (is (= "v3" (:vessel-id (last all-v)))))))

(deftest mem-store-contractor-lookup
  (testing "MemStore can store and retrieve contractors by ID (string keys)"
    (let [contractors {"c1" {:contractor-id "c1" :name "Acme Barge & Towboat Maintenance" :registered? true :verified? true}}
          s (store/mem-store {} contractors)]
      (is (some? (store/contractor-record s "c1")))
      (is (nil? (store/contractor-record s "c99"))))))

(deftest mem-store-all-contractor-records
  (testing "MemStore returns all contractors in sorted order"
    (let [contractors {"c2" {:contractor-id "c2" :name "Beta Marine Supply"}
                        "c1" {:contractor-id "c1" :name "Acme Barge & Towboat Maintenance"}}
          s (store/mem-store {} contractors)
          all-c (store/all-contractor-records s)]
      (is (= 2 (count all-c)))
      (is (= "c1" (:contractor-id (first all-c)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-shipment-record :vessel-id "v1" :value {:manifest-lines 42}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-vessel-records
  (testing "MemStore with-vessel-records replaces the vessel directory"
    (let [s (store/mem-store {})
          new-vessels {"v1" {:vessel-id "v1" :name "MV Alice's Trader"}}]
      (is (= 0 (count (store/all-vessel-records s))))
      (store/with-vessel-records s new-vessels)
      (is (= 1 (count (store/all-vessel-records s)))))))

(deftest mem-store-with-contractor-records
  (testing "MemStore with-contractor-records replaces the contractor directory"
    (let [s (store/mem-store {})
          new-contractors {"c1" {:contractor-id "c1" :name "Acme Barge & Towboat Maintenance"}}]
      (is (= 0 (count (store/all-contractor-records s))))
      (store/with-contractor-records s new-contractors)
      (is (= 1 (count (store/all-contractor-records s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo vessels and contractors"
    (let [s (store/seed-db)]
      (is (> (count (store/all-vessel-records s)) 0))
      (is (some? (store/vessel-record s "vessel-1")))
      (is (some? (store/vessel-record s "vessel-2")))
      (is (some? (store/vessel-record s "vessel-3")))
      (is (> (count (store/all-contractor-records s)) 0))
      (is (some? (store/contractor-record s "contractor-1")))
      (is (some? (store/contractor-record s "contractor-2"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for vessel-id/contractor-id"
    (let [demo (store/demo-data)
          vessels (:vessels demo)
          contractors (:contractors demo)]
      (doseq [[k v] vessels]
        (is (string? k) "vessel keys must be strings")
        (is (string? (:vessel-id v)) "vessel-id must be string")
        (is (= k (:vessel-id v)) "key must match vessel-id"))
      (doseq [[k v] contractors]
        (is (string? k) "contractor keys must be strings")
        (is (string? (:contractor-id v)) "contractor-id must be string")
        (is (= k (:contractor-id v)) "key must match contractor-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
