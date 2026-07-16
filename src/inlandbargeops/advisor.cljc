(ns inlandbargeops.advisor
  "InlandBargeAdvisor -- the *contained intelligence node* for the
  ISIC-5022 'Inland freight water transport' (river barges, canal cargo
  vessels, towboat/pushboat operations) INLAND WATERWAY PORT/LOGISTICS
  SCHEDULING coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: cargo/manifest/voyage record logging, dock/lock-scheduling
  and voyage scheduling coordination, barge/tow-vessel-maintenance
  procurement coordination, and cargo-safety/river-worthiness-concern
  flagging. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a committed
  record and NEVER a direct actuation -- every proposal's `:effect` is
  always `:propose`. Every output is censored downstream by
  `inlandbargeops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a direct barge/towboat-navigation/dispatch/
  rerouting action, a direct lock-gate-operation action, a vessel-
  seaworthiness-clearance finalization, a cargo-load-safety-clearance
  finalization, or any action overriding a tow captain's/lockmaster's
  safety judgment -- those are permanently out of scope for this actor,
  not merely un-implemented. `inlandbargeops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into scope it must never touch) and HARD-holds it, regardless of
  confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :vessel-id  str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-shipment-record
  "Draft a cargo/manifest/voyage record log entry. Pure logging of
  observed shipment data (cargo manifest lines, voyage waypoints, hold
  draft/trim readings) -- never a cargo-load-safety or river-worthiness
  decision."
  [_db {:keys [vessel-id patch]}]
  {:op         :log-shipment-record
   :vessel-id  vessel-id
   :summary    (str vessel-id " の貨物/マニフェスト/航海記録を記録: " (pr-str (keys patch)))
   :rationale  "貨物マニフェスト・航海記録・積載状況の観察記録のみ。積付安全や堪航性の判断は含まない。"
   :cites      [vessel-id]
   :effect     :propose
   :value      (merge {:vessel-id vessel-id} patch)
   :confidence 0.93})

(defn- propose-berth-operation
  "Draft a dock/lock-scheduling and voyage scheduling proposal (a
  mooring/lock-passage-window/voyage-slot entry, never a navigation or
  lock-operation action)."
  [_db {:keys [vessel-id patch]}]
  {:op         :schedule-berth-operation
   :vessel-id  vessel-id
   :summary    (str vessel-id " の係留/閘門通過/航海スケジュールを提案: " (pr-str (keys patch)))
   :rationale  "内陸水路の係留バース割当・閘門通過予約・航行スケジュール調整提案のみ。航行の最終判断は曳船船長/閘門長が行う。"
   :cites      [vessel-id]
   :effect     :propose
   :value      (merge {:vessel-id vessel-id} patch)
   :confidence 0.88})

(defn- propose-maintenance-order
  "Draft a barge/tow-vessel-maintenance procurement coordination request
  naming a registered contractor -- never a finalized purchase order; a
  human always confirms procurement."
  [_db {:keys [vessel-id patch]}]
  {:op         :coordinate-maintenance-order
   :vessel-id  vessel-id
   :summary    (str vessel-id " 向けバージ/曳船整備の発注調整を提案: " (pr-str (keys patch)))
   :rationale  "バージ/曳船整備・ドック入渠等の仕入先発注調整提案のみ。確定発注は人間が行う。"
   :cites      [vessel-id]
   :effect     :propose
   :value      (merge {:vessel-id vessel-id} patch)
   :confidence 0.90})

(defn- propose-safety-concern
  "Surface an observed cargo-safety/river-worthiness concern (hazmat
  placarding discrepancy, load-securement anomaly, hull/draft/trim
  observation) for HUMAN triage. This op ALWAYS escalates in
  `inlandbargeops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real.
  Deliberately reports the OBSERVATION only, never a finalization/
  clearance/override action, so the default rationale never trips the
  governor's `scope-excluded-terms` (see that var's docstring)."
  [_db {:keys [vessel-id patch]}]
  {:op         :flag-safety-concern
   :vessel-id  vessel-id
   :summary    (str vessel-id " の安全性懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "積付安全・堪航性・危険物表示等に関する懸念の観察事実の報告。常に人間(曳船船長/閘門長)の確認・対応が必要。"
   :cites      [vessel-id]
   :effect     :propose
   :value      (merge {:vessel-id vessel-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-shipment-record (propose-shipment-record _db request)
                   :schedule-berth-operation (propose-berth-operation _db request)
                   :coordinate-maintenance-order (propose-maintenance-order _db request)
                   :flag-safety-concern (propose-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually overrode the tow captain's safety judgment and directly navigated the barge out of the lock")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :vessel-id (:vessel-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
