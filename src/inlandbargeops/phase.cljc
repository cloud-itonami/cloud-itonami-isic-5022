(ns inlandbargeops.phase
  "Phase 0->3 staged rollout for the ISIC-5022 'Inland freight water
  transport' (river barge/canal cargo vessel/towboat operations) INLAND
  WATERWAY PORT/LOGISTICS SCHEDULING coordination actor.

    Phase 0  read-only              -- no writes, still governor-gated.
    Phase 1  assisted-logging       -- shipment-record logging allowed,
                                       every write needs human approval.
    Phase 2  assisted-coordination  -- adds dock/lock-scheduling and
                                       voyage scheduling plus maintenance-
                                       order proposals, still
                                       approval-gated.
    Phase 3  supervised auto        -- governor-clean, high-confidence
                                       `:log-shipment-record`/
                                       `:schedule-berth-operation`/
                                       `:coordinate-maintenance-order`
                                       may auto-commit. `:flag-safety-
                                       concern` NEVER auto-commits, at
                                       any phase, and a high-cost
                                       `:coordinate-maintenance-order`
                                       still escalates even at phase 3
                                       (the governor's own
                                       `high-stakes?` keeps it out of
                                       `:commit`).

  `:flag-safety-concern` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Flagging a cargo-safety/river-
  worthiness concern always needs a human (tow captain/lockmaster) to
  actually look at it. `inlandbargeops.governor`'s own
  `always-escalate-ops` enforces the same invariant independently -- two
  layers, not one, agree on this."
  (:require [inlandbargeops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-safety-concern` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"              :writes #{}                                                              :auto #{}}
   1 {:label "assisted-logging"       :writes #{:log-shipment-record}                                          :auto #{}}
   2 {:label "assisted-coordination"  :writes #{:log-shipment-record :schedule-berth-operation
                                                 :coordinate-maintenance-order}                                :auto #{}}
   3 {:label "supervised-auto"        :writes write-ops
      :auto #{:log-shipment-record :schedule-berth-operation :coordinate-maintenance-order}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean.
  - `:flag-safety-concern` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't). A high-cost `:coordinate-maintenance-order` never
    reaches this function with `:commit` in the first place -- the
    governor's own `high-stakes?` already turned it into `:escalate`
    upstream in `verdict->disposition`."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an InlandWaterwayFreightGovernor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
