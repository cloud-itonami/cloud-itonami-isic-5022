(ns inlandbargeops.store
  "SSoT for the ISIC-5022 'Inland freight water transport' (river barges,
  canal cargo vessels, towboat/pushboat operations, INLAND WATERWAY
  PORT/LOGISTICS SCHEDULING coordination) actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates INLAND WATERWAY PORT/LOGISTICS SCHEDULING ONLY:
  cargo/manifest/voyage record logging, dock/lock-scheduling and voyage
  scheduling coordination, barge/tow-vessel-maintenance procurement
  coordination with registered contractors, and cargo-safety/river-
  worthiness-concern flagging. It NEVER navigates a barge or towboat,
  NEVER dispatches or reroutes one, NEVER directly operates lock gates
  or other inland-waterway control infrastructure, and NEVER finalizes/
  overrides a vessel-seaworthiness clearance, a cargo-load-safety
  clearance, or a tow captain's/lockmaster's safety judgment -- see
  `inlandbargeops.governor`'s `scope-exclusion-violations`, a HARD,
  permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `vessels` directory keyed by `:vessel-id` STRING (the
  barge/tow-vessel/carrier registration record) and a `contractors`
  directory keyed by `:contractor-id` STRING (never keywords --
  consistent keying from the start, avoiding the silent-miss bug that
  has plagued earlier sibling actors).

  A registered/verified vessel/carrier record (barge/vessel registration
  + carrier license) must exist before ANY proposal targeting that
  vessel may ever commit or escalate --
  `inlandbargeops.governor`'s `vessel-unverified-violations` re-derives
  this from the vessel's own `:registered?`/`:verified?` fields, never
  from proposal self-report. A `:coordinate-maintenance-order` proposal
  additionally names a registered maintenance contractor via its own
  `:contractor-id`; the SAME 'ground truth, not self-report' discipline
  applies via `contractor-unverified-violations`.

  The ledger stays append-only: which vessel a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by whom
  is always a query over an immutable log.")

(defprotocol Store
  (vessel-record [s vessel-id] "Registered barge/tow-vessel/carrier
    record, or nil. Vessel map: {:vessel-id .. :name .. :registered?
    bool :verified? bool}.")
  (all-vessel-records [s])
  (contractor-record [s contractor-id] "Registered maintenance-contractor
    record, or nil. Contractor map: {:contractor-id .. :name ..
    :registered? bool :verified? bool}.")
  (all-contractor-records [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-vessel-records [s vessels] "replace/seed the vessel directory (map vessel-id->vessel)")
  (with-contractor-records [s contractors] "replace/seed the contractor directory (map contractor-id->contractor)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained vessel/contractor directory covering both the
  happy path and the governor's own hard checks, so the actor + tests
  run offline."
  []
  {:vessels
   {"vessel-1" {:vessel-id "vessel-1" :name "MV Riverbend Trader"
                :registered? true :verified? true}
    "vessel-2" {:vessel-id "vessel-2" :name "MV Canal Wayfarer"
                :registered? true :verified? true}
    "vessel-3" {:vessel-id "vessel-3" :name "MV Pending Survey (in intake)"
                :registered? true :verified? false}}
   :contractors
   {"contractor-1" {:contractor-id "contractor-1" :name "Northbank Barge & Towboat Maintenance"
                     :registered? true :verified? true}
    "contractor-2" {:contractor-id "contractor-2" :name "Unverified Drydock Broker Co."
                     :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (vessel-record [_ vessel-id] (get-in @a [:vessels vessel-id]))
  (all-vessel-records [_] (sort-by :vessel-id (vals (:vessels @a))))
  (contractor-record [_ contractor-id] (get-in @a [:contractors contractor-id]))
  (all-contractor-records [_] (sort-by :contractor-id (vals (:contractors @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-vessel-records [s vessels] (when (seq vessels) (swap! a assoc :vessels vessels)) s)
  (with-contractor-records [s contractors] (when (seq contractors) (swap! a assoc :contractors contractors)) s))

(defn seed-db
  "A MemStore seeded with the demo vessel/contractor directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with explicit `vessels`/`contractors` maps
  (vessel-id/contractor-id string -> record map) -- the primary
  test/dev entry point. Either may be empty (an unregistered-everywhere
  vessel)."
  ([vessels] (mem-store vessels {}))
  ([vessels contractors]
   (->MemStore (atom {:vessels (or vessels {}) :contractors (or contractors {})
                       :ledger [] :coordination-log []}))))
