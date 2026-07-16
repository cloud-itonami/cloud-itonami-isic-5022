(ns inlandbargeops.governor
  "InlandWaterwayFreightGovernor -- the independent compliance layer that
  earns the InlandBargeAdvisor the right to commit. The advisor has no
  notion of whether a barge/tow vessel is actually registered and
  carrier-license-verified, whether a named maintenance-order contractor
  is itself a registered/verified counterparty, whether its own proposed
  `:effect` secretly claims a direct actuation instead of a mere
  proposal, or whether it has silently drifted into a permanently
  out-of-scope decision area, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- INLAND WATERWAY
  PORT/LOGISTICS SCHEDULING ONLY (cargo/manifest/voyage record logging,
  dock/lock-scheduling and voyage scheduling coordination, barge/tow-
  vessel-maintenance procurement coordination, cargo-safety/river-
  worthiness-concern flagging). It NEVER performs or authorizes:
    - directly navigating, dispatching, or rerouting a barge or towboat
    - directly operating lock gates or other inland-waterway control
      infrastructure
    - directly finalizing a vessel-seaworthiness clearance or a
      cargo-load-safety clearance
    - overriding a tow captain's or lockmaster's safety judgment, or
      bypassing an inland waterway safety protocol

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Vessel unverified          -- the target vessel/carrier
                                     registration record must exist AND
                                     be independently confirmed
                                     `:registered?`/`:verified?` in the
                                     store before ANY proposal for it may
                                     commit or even escalate. Never trusts
                                     a proposal's own claim about the
                                     vessel -- re-derived from the
                                     vessel's own record, the same
                                     'ground truth, not self-report'
                                     discipline every sibling actor's
                                     governor uses.
    2. Contractor unverified      -- for `:coordinate-maintenance-order`
                                     ONLY, the proposal's own drafted
                                     `:value` must name a `:contractor-id`
                                     that resolves to an independently
                                     `:registered?`/`:verified?`
                                     maintenance-contractor record. A
                                     missing contractor-id, or one that
                                     resolves to an unregistered or
                                     unverified contractor, is a HARD
                                     block -- a maintenance-supply-chain
                                     counterparty-verification gate.
    3. Effect not :propose        -- every proposal's `:effect` MUST be
                                     `:propose`. Any other effect value
                                     is, by construction, a claim to
                                     directly actuate/commit outside
                                     governance -- HARD block, not merely
                                     low-confidence.
    4. Scope exclusion            -- ANY proposal (regardless of op)
                                     whose op, summary, rationale, cites
                                     or draft value touches directly
                                     finalizing a vessel-seaworthiness
                                     clearance, finalizing a cargo-load-
                                     safety clearance, overriding a tow
                                     captain's/lockmaster's safety
                                     judgment, bypassing an inland
                                     waterway safety protocol, or
                                     directly navigating/dispatching a
                                     barge or operating lock gates is a
                                     HARD, PERMANENT block -- this actor's
                                     charter excludes that territory
                                     structurally, not as a rollout
                                     milestone. Evaluated UNCONDITIONALLY
                                     on every proposal. An op outside the
                                     closed four-op allowlist is the SAME
                                     failure mode (an advisor proposing
                                     something it was never authorized to
                                     propose) and is folded into this same
                                     check. `:flag-safety-concern` itself
                                     is never excluded by this check --
                                     surfacing a cargo-load-safety/
                                     river-worthiness/hazmat concern for a
                                     human is exactly this actor's job;
                                     only FINALIZING/overriding/directly-
                                     acting-on that concern (issuing a
                                     clearance, overriding a safety
                                     judgment, bypassing a protocol,
                                     directly navigating the barge or
                                     operating lock infrastructure) is
                                     excluded (see `scope-excluded-terms`
                                     below -- phrased as the
                                     finalization/execution ACTION,
                                     never a bare noun like 'seaworthy',
                                     'river-worthy', 'cargo load safety'
                                     or 'hazmat', so the default mock
                                     advisor's own `:flag-safety-concern`
                                     rationale never self-trips this
                                     check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-safety-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `inlandbargeops.phase` independently agrees:
      `:flag-safety-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.
    - A `:coordinate-maintenance-order` whose drafted `:value` names an
      `:estimated-cost` above `maintenance-cost-threshold` -- a
      large-value barge/tow-vessel-maintenance procurement proposal
      always needs a human sign-off, even when the governor and phase
      would otherwise allow auto-commit."
  (:require [clojure.string :as str]
            [inlandbargeops.store :as store]))

(def confidence-floor 0.6)

(def maintenance-cost-threshold
  "Example single-vessel maintenance procurement threshold
  (USD-equivalent units, domain-illustrative -- not a universal
  cross-domain constant). A `:coordinate-maintenance-order` proposal
  citing an `:estimated-cost` above this value ALWAYS escalates to human
  sign-off, regardless of confidence or rollout phase."
  5000.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`). NONE of
  these ops directly navigate a barge/tow, operate lock infrastructure,
  or finalize a vessel-seaworthiness/cargo-load-safety clearance -- this
  actor coordinates inland-waterway port/logistics scheduling only."
  #{:log-shipment-record :schedule-berth-operation
    :coordinate-maintenance-order :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  vessel-seaworthiness clearance, finalizing a cargo-load-safety
  clearance, overriding a tow captain's/lockmaster's safety judgment,
  bypassing an inland waterway safety protocol, or directly navigating/
  dispatching a barge or towboat or operating lock gates, rather than
  merely coordinating dock/lock/voyage scheduling around it. Scanned
  across the proposal's op/summary/rationale/cites/value, never trusting
  the advisor's own framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'finalize the seaworthiness clearance', 'override the tow
  captain's safety judgment'), never a bare noun like 'seaworthy',
  'river-worthy', 'cargo load safety' or 'hazmat' -- a bare noun would
  accidentally match inside this actor's own legitimate
  `:flag-safety-concern` default proposal text (whose whole job is to
  talk about cargo-load-safety/river-worthiness/hazmat concerns) and
  self-block the happy path. See
  `inlandbargeops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["finalize the seaworthiness clearance" "finalized the seaworthiness clearance" "finalizing the seaworthiness clearance"
   "finalize the river-worthiness clearance" "finalized the river-worthiness clearance" "finalizing the river-worthiness clearance"
   "issue the seaworthiness clearance" "issued the seaworthiness clearance" "issuing the seaworthiness clearance"
   "issue the river-worthiness clearance" "issued the river-worthiness clearance" "issuing the river-worthiness clearance"
   "clear the vessel as seaworthy" "cleared the vessel as seaworthy" "clearing the vessel as seaworthy"
   "clear the barge as river-worthy" "cleared the barge as river-worthy" "clearing the barge as river-worthy"
   "certify the vessel as seaworthy" "certified the vessel as seaworthy" "certifying the vessel as seaworthy"
   "certify the barge as river-worthy" "certified the barge as river-worthy" "certifying the barge as river-worthy"
   "finalize the cargo-load-safety clearance" "finalized the cargo-load-safety clearance" "finalizing the cargo-load-safety clearance"
   "approve the cargo load as safe" "approved the cargo load as safe" "approving the cargo load as safe"
   "clear the cargo load as safe" "cleared the cargo load as safe" "clearing the cargo load as safe"
   "override the tow captain's safety judgment" "overrode the tow captain's safety judgment" "overriding the tow captain's safety judgment"
   "override the captain's safety judgment" "overrode the captain's safety judgment" "overriding the captain's safety judgment"
   "override the lockmaster's safety judgment" "overrode the lockmaster's safety judgment" "overriding the lockmaster's safety judgment"
   "override the master's safety determination" "overrode the master's safety determination"
   "bypass the inland waterway safety protocol" "bypassed the inland waterway safety protocol" "bypassing the inland waterway safety protocol"
   "bypass inland waterway safety protocols" "bypassed inland waterway safety protocols" "bypassing inland waterway safety protocols"
   "bypass lock operating procedures" "bypassed lock operating procedures" "bypassing lock operating procedures"
   "directly navigate the barge" "directly navigated the barge" "directly navigating the barge"
   "directly navigate the tow" "directly navigated the tow" "directly navigating the tow"
   "directly dispatch the barge" "directly dispatched the barge" "directly dispatching the barge"
   "directly reroute the barge" "directly rerouted the barge" "directly rerouting the barge"
   "directly operate the lock gates" "directly operated the lock gates" "directly operating the lock gates"
   "take direct control of the helm" "took direct control of the helm" "taking direct control of the helm"
   "耐空性確認を確定" "耐空性証明を発行した" "耐空性証明を発行する"
   "堪航性確認を確定" "堪航性証明を発行した" "堪航性証明を発行する"
   "貨物積付安全確認を確定" "貨物積付の安全性を確定承認"
   "曳船船長の安全判断を覆した" "水先案内人の安全判断を覆した" "閘門長の安全判断を覆した"
   "内陸水路安全プロトコルを回避" "内陸水路安全規則を迂回" "閘門操作手順を回避"
   "バージを直接操船した" "バージを直接運航させた" "曳船列を直接操船した" "閘門ゲートを直接操作した"])

;; ----------------------------- checks -----------------------------

(defn- vessel-unverified-violations
  "The target vessel/carrier registration record must exist AND be
  independently `:registered?`/`:verified?` in the store -- never trust
  the proposal's own `:vessel-id` claim without a store lookup."
  [{:keys [vessel-id]} st]
  (let [v (store/vessel-record st vessel-id)]
    (when-not (and v (:registered? v) (:verified? v))
      [{:rule :vessel-unverified
        :detail (str vessel-id " は未登録または未検証の船舶/船社(バージ/曳船) -- いかなる提案も進められない")}])))

(defn- contractor-unverified-violations
  "For `:coordinate-maintenance-order` ONLY, the proposal's own drafted
  `:value` must name a `:contractor-id` that resolves to an independently
  `:registered?`/`:verified?` maintenance-contractor record. A missing
  contractor-id, or one that resolves to an unregistered/unverified
  contractor, is a HARD block -- never trust the proposal's own
  contractor claim without a store lookup, the SAME 'ground truth, not
  self-report' discipline as `vessel-unverified-violations`, reapplied
  to the maintenance-supply-chain counterparty."
  [proposal st]
  (when (= :coordinate-maintenance-order (:op proposal))
    (let [contractor-id (get-in proposal [:value :contractor-id])
          c (and contractor-id (store/contractor-record st contractor-id))]
      (when-not (and c (:registered? c) (:verified? c))
        [{:rule :contractor-unverified
          :detail (str (or contractor-id "(contractor-id missing)")
                        " は未登録または未検証の整備業者 -- 整備発注調整提案を進められない")}]))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches directly finalizing a vessel-seaworthiness
  clearance, a cargo-load-safety clearance, overriding a tow captain's/
  lockmaster's safety judgment, bypassing an inland waterway safety
  protocol, or directly navigating/dispatching a barge or towboat or
  operating lock infrastructure, regardless of confidence or how clean
  every other check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "堪航性/耐空性確認・貨物積付安全確認の確定、曳船船長/閘門長の安全判断の覆し、内陸水路安全プロトコルの回避、バージ/曳船の直接操船や閘門の直接操作など航行/安全確定行為は永久に禁止"}])))

(defn- high-cost-maintenance-order?
  "A `:coordinate-maintenance-order` proposal citing an `:estimated-cost`
  above `maintenance-cost-threshold` -- always needs human sign-off (SOFT
  escalate, not a hard block: the order itself is in scope, only its
  size requires a human)."
  [proposal]
  (and (= :coordinate-maintenance-order (:op proposal))
       (some-> proposal :value :estimated-cost (> maintenance-cost-threshold))))

(defn check
  "Censors an InlandBargeAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [vessel-id (or (:vessel-id proposal) (:vessel-id request))
        hard (into []
                   (concat (vessel-unverified-violations {:vessel-id vessel-id} store)
                           (contractor-unverified-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-maintenance-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :vessel-id  (:vessel-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
