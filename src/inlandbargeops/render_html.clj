(ns inlandbargeops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL InlandBargeAdvisor OperationActor
  (`inlandbargeops.operation/build` -> a compiled langgraph-clj
  StateGraph) over the REAL seeded store (`inlandbargeops.store/seed-db`),
  through the REAL InlandWaterwayFreightGovernor
  (`inlandbargeops.governor/check`) and the REAL rollout phase gate
  (`inlandbargeops.phase/gate`), and renders whatever those produced.
  Nothing on the page is a mock and nothing is hand-typed telemetry:

    - every table row is read back out of the store after the run
      (`store/ledger`, `store/coordination-log`, `store/all-vessel-records`,
      `store/all-contractor-records`),
    - every HARD-hold rule name and every violation detail string is the
      governor's OWN `:violations` entry off the ledger fact -- never a
      literal in this namespace,
    - the phase-gate table is derived from `inlandbargeops.phase/phases`
      and the governor-configuration table from
      `inlandbargeops.governor`'s public vars.

  Subject provenance: every `:vessel-id` / `:contractor-id` driven below
  is either seeded by `inlandbargeops.store/demo-data` (`vessel-1`
  `vessel-2` `vessel-3` `contractor-1` `contractor-2`) or is the
  deliberately-unregistered `vessel-99`, whose whole purpose is to trip
  the governor's `vessel-unverified` HARD check. The seed was confirmed
  by running this repo's own `clojure -M:dev:run` BEFORE writing this
  file.

  Deterministic: no clock, no randomness, no network, no timestamps in
  the page content. Re-running writes a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [inlandbargeops.advisor :as advisor]
            [inlandbargeops.governor :as governor]
            [inlandbargeops.operation :as op]
            [inlandbargeops.phase :as phase]
            [inlandbargeops.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :inland-logistics-coordinator})

(def ^:private scenarios
  "One entry = one coordination request driven through the real compiled
  actor graph. `:approval`, when present, is the human decision handed
  back to the paused graph (`interrupt-before #{:request-approval}`).
  `:actor :direct-actuation` selects the second actor built below, whose
  injected advisor deliberately returns `:effect :commit` -- the only way
  to exercise the governor's `effect-not-propose` HARD check end to end
  without hand-writing a ledger fact."
  [{:tid "t01" :phase 1
    :exercises "Shipment-record logging at phase 1 (assisted-logging): the governor is clean, but phase 1 auto-commits nothing, so the phase gate escalates it to a human, who approves."
    :request {:op :log-shipment-record :vessel-id "vessel-1"
              :patch {:manifest-lines 42 :cargo-weight-tonnes 3200 :hazmat-class "none"}}
    :approval {:status :approved :by "inland-logistics-coordinator-1"}}

   {:tid "t02" :phase 3
    :exercises "The same op at phase 3 (supervised-auto): governor-clean and high-confidence, so it auto-commits with no human in the loop."
    :request {:op :log-shipment-record :vessel-id "vessel-1"
              :patch {:manifest-lines 30 :cargo-weight-tonnes 1800 :hazmat-class "none"}}}

   {:tid "t03" :phase 3
    :exercises "Dock/lock-passage scheduling for a second registered vessel. A schedule proposal, never a navigation or lock-operation action."
    :request {:op :schedule-berth-operation :vessel-id "vessel-2"
              :patch {:berth "dock-4" :lock-window "lock-7 window B" :voyage-leg "river-mile 214 -> 331"}}}

   {:tid "t04" :phase 3
    :exercises "Low-cost maintenance procurement naming a registered AND verified contractor -- under the cost threshold, so it auto-commits."
    :request {:op :coordinate-maintenance-order :vessel-id "vessel-1"
              :patch {:item "routine hull inspection" :estimated-cost 1200.0
                      :contractor-id "contractor-1"}}}

   {:tid "t05" :phase 3
    :exercises "The same op above the maintenance cost threshold. Large-value procurement ALWAYS escalates, even at phase 3 and even with a clean governor. Human approves."
    :request {:op :coordinate-maintenance-order :vessel-id "vessel-2"
              :patch {:item "main engine overhaul" :estimated-cost 42000.0
                      :contractor-id "contractor-1"}}
    :approval {:status :approved :by "inland-logistics-coordinator-1"}}

   {:tid "t06" :phase 3
    :exercises "A cargo-safety / river-worthiness concern. ALWAYS escalates at every phase -- two independent layers agree (the governor's always-escalate set and the phase table's :auto set). The tow captain approves surfacing it."
    :request {:op :flag-safety-concern :vessel-id "vessel-1"
              :patch {:concern "hazmat placard mismatch on hold 3 manifest, possible cargo securement anomaly"
                      :confidence 0.92}}
    :approval {:status :approved :by "tow-captain-1"}}

   {:tid "t07" :phase 3
    :exercises "The same always-escalating op, but the human DECLINES. Not a compliance violation -- a person said no, and the ledger records it as :approval-rejected with basis :approver-rejected."
    :request {:op :flag-safety-concern :vessel-id "vessel-2"
              :patch {:concern "draft/trim reading inconsistent with declared tonnage on the previous leg"
                      :confidence 0.71}}
    :approval {:status :rejected :by "lockmaster-1"}}

   {:tid "t08" :phase 3
    :exercises "A vessel that is not in the registry at all. HARD hold -- no proposal for an unregistered vessel may commit or even escalate."
    :request {:op :log-shipment-record :vessel-id "vessel-99"
              :patch {:manifest-lines 0}}}

   {:tid "t09" :phase 3
    :exercises "A vessel that IS registered but whose carrier-license verification is still in intake. Registration alone is not enough. HARD hold."
    :request {:op :log-shipment-record :vessel-id "vessel-3"
              :patch {:manifest-lines 10}}}

   {:tid "t10" :phase 3
    :exercises "Maintenance procurement naming a registered but UNVERIFIED contractor -- the maintenance-supply-chain counterparty gate, re-derived from the contractor's own record. HARD hold."
    :request {:op :coordinate-maintenance-order :vessel-id "vessel-1"
              :patch {:item "drydock survey" :estimated-cost 3000.0
                      :contractor-id "contractor-2"}}}

   {:tid "t11" :phase 3
    :exercises "Maintenance procurement naming NO contractor at all. A missing contractor-id fails the same check as an unverified one. HARD hold."
    :request {:op :coordinate-maintenance-order :vessel-id "vessel-1"
              :patch {:item "propeller shaft alignment" :estimated-cost 900.0}}}

   {:tid "t12" :phase 3 :actor :direct-actuation
    :exercises "A compromised/confused advisor whose proposal claims :effect :commit instead of :propose -- a claim to actuate outside governance. HARD hold."
    :request {:op :schedule-berth-operation :vessel-id "vessel-1"
              :patch {:berth "dock-2" :lock-window "lock-3 window A"}}}

   {:tid "t13" :phase 3
    :exercises "An advisor that has drifted into permanently-excluded territory (overriding the tow captain's safety judgment, directly navigating the barge). Scope exclusion is scanned on the advisor's own rationale, unconditionally, on every op. HARD hold, permanent."
    :request {:op :log-shipment-record :vessel-id "vessel-1"
              :out-of-scope? true :patch {}}}

   {:tid "t14" :phase 3
    :exercises "An op outside the closed four-op allowlist. Both the op allowlist and the :propose effect rule reject it -- an unauthorised op has no proposal generator, so it also carries no :effect. HARD hold."
    :request {:op :navigate-barge :vessel-id "vessel-1" :patch {}}}

   {:tid "t15" :phase 0
    :exercises "A governor-clean shipment-record log at phase 0 (read-only). The governor passes it; the ROLLOUT PHASE holds it. This is a phase hold, not a compliance violation -- its :basis is empty and it carries :phase-reason :phase-disabled."
    :request {:op :log-shipment-record :vessel-id "vessel-1"
              :patch {:manifest-lines 7}}}])

(defn- direct-actuation-advisor
  "An advisor that drafts the repo's own real proposal and then claims a
  direct actuation. Injected only for scenario t12, exactly as
  `inlandbargeops.sim` does, so the `effect-not-propose` HARD check is
  reached through the real graph rather than by writing a fact."
  []
  (reify advisor/Advisor
    (-advise [_ _store req] (assoc (advisor/infer nil req) :effect :commit))))

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did, plus the ledger
  facts this scenario itself appended."
  [actors db {:keys [tid actor request phase approval] :as scenario}]
  (let [a       (get actors (or actor :default))
        ctx     (assoc coordinator :phase phase)
        before  (count (store/ledger db))
        r1      (g/run* a {:request request :context ctx} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2      (when (and approval paused?)
                  (g/run* a {:approval approval} {:thread-id tid :resume? true}))
        final   (:state (or r2 r1))
        audit   (:audit final [])]
    (assoc scenario
           :verdict     (:verdict final)
           :proposal    (:proposal final)
           :paused?     paused?
           :escalation  (first (filter #(= :approval-requested (:t %)) audit))
           :human       (when r2 (:status approval))
           :approver    (when r2 (:by approval))
           :disposition (:disposition final)
           :facts       (vec (drop before (store/ledger db))))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor (plus the direct-actuation
  variant for t12), drives every scenario in order. Returns
  {:db store :runs [..]}."
  []
  (let [db     (store/seed-db)
        actors {:default           (op/build db)
                :direct-actuation  (op/build db {:advisor (direct-actuation-advisor)})}]
    {:db db :runs (mapv #(drive! actors db %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- codes
  "Render a SEQUENCE in the order the code produced it -- used for
  `:basis`, whose order is the governor's own evaluation order."
  [coll]
  (if (seq coll) (str/join " " (map code coll)) "<span class=\"muted\">—</span>"))

(defn- kw-codes
  "Render a SET. Sorted, because a set has no order and an unsorted
  render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells]
  (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- ledger views -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "Every `:governor-hold` fact on the append-only ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- hard-holds
  "The subset of holds carrying at least one governor violation. A hold
  with an EMPTY `:basis` is the rollout phase gate refusing an op that is
  not yet enabled -- real, but not a compliance violation."
  [db]
  (filterv #(seq (:basis %)) (holds db)))

(defn- phase-holds [db]
  (filterv #(empty? (:basis %)) (holds db)))

(defn- rejections [db]
  (filterv #(= :approval-rejected (:t %)) (ledger-of db)))

;; ----------------------------- sections -----------------------------

(defn- stat [label value]
  (str "<div class=\"stat\"><span class=\"num\">" (esc value) "</span> "
       "<span class=\"muted\">" (esc label) "</span></div>"))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n   (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger after "
               "driving " (count runs) " requests through " (code "inlandbargeops.operation/build") ".")
          (str "<ul>"
               "<li>" (stat "requests driven" (count runs)) "</li>"
               "<li>" (stat "ledger facts" (count led)) "</li>"
               "<li>" (stat "commits" (n :committed)) "</li>"
               "<li>" (stat "governor HARD holds" (count (hard-holds db))) "</li>"
               "<li>" (stat "rollout-phase holds" (count (phase-holds db))) "</li>"
               "<li>" (stat "human rejections" (count (rejections db))) "</li>"
               "<li>" (stat "committed coordination records" (count (store/coordination-log db))) "</li>"
               "</ul>"
               "<p class=\"muted\">Note: <code>:advisor-proposal</code>, "
               "<code>:approval-requested</code> and <code>:approval-granted</code> are emitted to "
               "the graph's in-memory <code>:audit</code> channel only — "
               "<code>inlandbargeops.operation</code> never appends them to the store ledger, so "
               "they are not facts this page counts. An approved request is visible as the "
               "<code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (str/join " " (map code (map :rule (:violations verdict)))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [human approver paused?]}]
  (cond
    (= :approved human) (str "<span class=\"ok\">approved</span> <span class=\"muted\">"
                             (esc approver) "</span>")
    (= :rejected human) (str "<span class=\"critical\">rejected</span> <span class=\"muted\">"
                             (esc approver) "</span>")
    paused?             "<span class=\"warn\">paused, awaiting a human</span>"
    :else               "<span class=\"muted\">not reached</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit   "<span class=\"ok\">commit</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    :hold     "<span class=\"critical\">hold</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row per request actually driven through the compiled StateGraph. The "
             "verdict, the pause and the final disposition are read off the graph's own "
             "result state; the description says which rule the request was written to "
             "exercise.")
        (table ["#" "Phase" "Op" "Vessel" "Governor verdict" "Human" "Disposition" "What it exercises"]
               (for [r runs]
                 (tr (code (:tid r))
                     (esc (:phase r))
                     (code (-> r :request :op))
                     (code (-> r :request :vessel-id))
                     (verdict-cell r)
                     (human-cell r)
                     (disposition-cell r)
                     (esc (:exercises r)))))))

(defn- holds-section [db]
  (let [hs (hard-holds db)]
    (card "Governor HARD holds"
          (str "Each row is a " (code ":governor-hold") " fact on the append-only ledger. The rule "
               "name and the detail text are the InlandWaterwayFreightGovernor's own "
               (code ":violations") " entries — this page holds no rule text of its own. A HARD "
               "hold can never be approved past.")
          (table ["Rule" "Op" "Vessel" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:vessel-id h))
                       (fmt (:confidence h))
                       (esc (:detail v))))))))

(defn- phase-holds-section [db]
  (let [ps (phase-holds db)]
    (when (seq ps)
      (card "Rollout-phase holds"
            (str "A governor-clean proposal the ROLLOUT PHASE refused, because the op is not "
                 "enabled for writing in that phase. Written to the ledger by the same "
                 (code ":hold") " node, but with an empty " (code ":basis") " and a "
                 (code ":phase-reason") " — not a compliance violation.")
            (table ["Op" "Vessel" "Phase" "Phase reason" "Basis" "Confidence"]
                   (for [p ps]
                     (tr (code (:op p)) (code (:vessel-id p)) (fmt (:phase p))
                         (code (:phase-reason p)) (codes (:basis p)) (fmt (:confidence p)))))))))

(defn- rejections-section [db]
  (let [rs (rejections db)]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Vessel" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:vessel-id r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

(defn- phase-section [runs]
  (card "Rollout phase gate"
        (str "Derived from " (code "inlandbargeops.phase/phases") ". A governor HOLD always stays "
             "a HOLD; an op that may write but is not auto-eligible escalates to a human even when "
             "the governor is clean. Phases marked below are the ones this run actually drove.")
        (let [driven (set (map :phase runs))]
          (table ["Phase" "Label" "Driven here" "May write" "May auto-commit when governor-clean"]
                 (for [[ph {:keys [label writes auto]}] (sort-by key phase/phases)]
                   (tr (esc ph)
                       (esc label)
                       (if (contains? driven ph)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"muted\">—</span>")
                       (if (seq writes) (kw-codes writes) "<span class=\"muted\">none</span>")
                       (if (seq auto) (kw-codes auto)
                           "<span class=\"warn\">none — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "inlandbargeops.governor") " and "
             (code "inlandbargeops.phase") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "maintenance cost threshold (always escalates above)"
                    (code governor/maintenance-cost-threshold))
                (tr "allowed ops (closed allowlist)" (kw-codes governor/allowed-ops))
                (tr "always-escalate ops" (kw-codes governor/always-escalate-ops))
                (tr "scope-excluded action phrases"
                    (str (code (count governor/scope-excluded-terms))
                         " <span class=\"muted\">substrings scanned across "
                         "op/summary/rationale/cites/value on every proposal</span>"))
                (tr "default phase" (code phase/default-phase))])))

(def ^:private op-gate-contract
  ;; The ONLY hand-written content on this page. This is a static
  ;; description of the FIXED op-gate contract this repo's README and
  ;; GOVERNANCE describe -- documentation of invariant behaviour, not
  ;; runtime telemetry, so it is legitimately hand-described rather than
  ;; derived from the live run. Everything else on this page is derived
  ;; from the store/ledger the run above actually produced, or from the
  ;; governor/phase public vars.
  [[":log-shipment-record"
    "Cargo/manifest/voyage record logging. Auto-commit eligible at phase 3 when the governor is clean."]
   [":schedule-berth-operation"
    "Dock/lock-passage and voyage scheduling coordination. Auto-commit eligible at phase 3 when the governor is clean. Never navigates or dispatches a vessel, never operates lock gates."]
   [":coordinate-maintenance-order"
    "Barge/tow-vessel maintenance procurement coordination. HARD-gated on independent contractor verification; ALWAYS escalates above the maintenance cost threshold."]
   [":flag-safety-concern"
    "Cargo-safety / river-worthiness concern surfacing. ALWAYS escalates to a human at every phase — never auto-commits, and never finalises a clearance itself."]])

(defn- contract-section []
  (card "Fixed op-gate contract (static)"
        (str "Static documentation of the four-op closed contract, not runtime output — the one "
             "hand-written block on this page. Every proposal is " (code ":effect :propose") "; "
             "a human always performs the real-world act. This actor never navigates or dispatches "
             "a barge or towboat, never operates lock infrastructure, and never finalises or "
             "overrides a vessel-seaworthiness / cargo-load-safety clearance or a tow captain's or "
             "lockmaster's safety judgment.")
        (table ["Op" "Fixed gate"]
               (for [[o desc] op-gate-contract]
                 (tr (code o) (esc desc))))))

(defn- last-fact-for [led vessel-id]
  (last (filter #(= vessel-id (:vessel-id %)) led)))

(defn- vessel-status [led vessel-id]
  (let [f (last-fact-for led vessel-id)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f))
      (str "<span class=\"critical\">rejected by approver</span> " (codes (:basis f)))
      (and (= :governor-hold (:t f)) (seq (:basis f)))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      (= :governor-hold (:t f))
      (str "<span class=\"warn\">phase hold</span> " (code (:phase-reason f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"critical\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- vessels-section [db]
  (let [led (ledger-of db)]
    (card "Vessel / carrier directory"
          (str "The store's own registry (" (code "inlandbargeops.store/all-vessel-records") "). "
               "The governor re-derives " (code ":registered?") " and " (code ":verified?") " from "
               "these records on every request — never from a proposal's own claim. "
               (code "vessel-99") " is deliberately absent from this table: it is the "
               "unregistered vessel driven in t08, and its absence here is exactly why that "
               "request HARD-held.")
          (table ["Vessel" "Name" "Registered?" "Verified?" "Last ledger fact"]
                 (for [v (store/all-vessel-records db)]
                   (tr (code (:vessel-id v)) (esc (:name v))
                       (flag (:registered? v)) (flag (:verified? v))
                       (vessel-status led (:vessel-id v))))))))

(defn- contractors-section [db]
  (card "Maintenance-contractor directory"
        (str "The store's own counterparty registry ("
             (code "inlandbargeops.store/all-contractor-records") "). Checked ONLY for "
             (code ":coordinate-maintenance-order") ", and re-derived the same way — a missing or "
             "unverified contractor is a HARD block on the procurement proposal.")
        (table ["Contractor" "Name" "Registered?" "Verified?"]
               (for [c (store/all-contractor-records db)]
                 (tr (code (:contractor-id c)) (esc (:name c))
                     (flag (:registered? c)) (flag (:verified? c)))))))

(defn- coordination-section [db]
  (let [recs (store/coordination-log db)]
    (card "Committed coordination log"
          (str "Every proposal that actually reached the " (code ":commit") " node and mutated the "
               "SSoT, in commit order. " (code ":approved-by") " is present only where a human "
               "resumed the paused graph.")
          (table ["Op" "Vessel" "Committed value" "Approved by"]
                 (for [r recs]
                   (tr (code (:op r)) (code (:vessel-id r))
                       (code (pr-str (:value r)))
                       (if-let [by (get-in r [:payload :approved-by])]
                         (str "<span class=\"ok\">" (esc by) "</span>")
                         "<span class=\"muted\">— auto-committed</span>")))))))

(defn- ledger-section [db]
  (let [led (ledger-of db)]
    (card "Audit ledger (append-only)"
          (str "The complete immutable decision-fact log this run produced, in append order — "
               (code "inlandbargeops.store/ledger") ", unfiltered.")
          (table ["#" "Fact" "Op" "Vessel" "Disposition" "Basis"]
                 (map-indexed
                  (fn [i f]
                    (tr (esc (inc i))
                        (code (:t f))
                        (code (:op f))
                        (code (:vessel-id f))
                        (code (:disposition f))
                        (codes (:basis f))))
                  led)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-5022 (inlandbargeops)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<h1>Inland freight water transport — operator console</h1>"
       "</header>\n"
       "<p class=\"subtitle\"><span class=\"badge\">ISIC 5022</span> "
       "<span class=\"badge\">inlandbargeops</span> "
       "governor <code>inland-waterway-freight-governor</code> · actor "
       (esc (:actor-id coordinator)) " · role " (esc (:actor-role coordinator))
       "</p>\n<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (phase-holds-section db)
                          (rejections-section db)
                          (contract-section)
                          (phase-section runs)
                          (governor-section)
                          (vessels-section db)
                          (contractors-section db)
                          (coordination-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>inlandbargeops.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>inlandbargeops.operation</code> actor graph over the real "
       "<code>inlandbargeops.store</code> seed. Deterministic — no clock, no randomness, no "
       "network. No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)
        hard (hard-holds db)]
    ;; Build-time invariant: a console that shows no real HARD hold is not
    ;; evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))
                       :requests (count runs)})))
    (when (empty? hard)
      (throw (ex-info "no :governor-hold fact carrying a governor violation — refusing to write a console whose only holds are rollout-phase holds"
                      {:ledger-facts (count (store/ledger db))
                       :holds (count hs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hard) " HARD holds, "
                  (count runs) " requests)"))))
