(ns adreview.phase
  "Phase 0->3 staged rollout for the China advertising-review actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- campaign intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds regime assessment writes, still
                                 approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:campaign/intake` (no consumer-facing
                                 risk yet) may auto-commit.
                                 `:review/submit`/`:campaign/publish`
                                 NEVER auto-commit, at any phase.

  `:review/submit`/`:campaign/publish` are deliberately ABSENT from
  every phase's `:auto` set, including phase 3 -- a permanent
  structural fact, not a rollout milestone still to come. Submitting a
  package to a 广告审查机关 and putting a real advertisement in front of
  Chinese consumers are the two real-world acts this actor performs;
  both are always a human advertising operator's call.
  `adreview.governor`'s `:actuation/submit-review`/
  `:actuation/publish-campaign` high-stakes gate enforces the same
  invariant independently -- two layers, not one, agree on this."
  (:require [clojure.set :as set]))

(def read-ops  #{})
(def write-ops #{:campaign/intake :regime/assess :review/submit :campaign/publish})

;; NOTE the invariant: `:review/submit`/`:campaign/publish` are members
;; of `write-ops` (governor-gated like any write) but are NEVER members
;; of any phase's `:auto` set below. Do not add them there.
(def never-auto
  "The ops no phase may ever auto-commit. `phases` is checked against
  this at load time, so adding one to an `:auto` set fails loudly
  instead of silently weakening the actuation invariant."
  #{:review/submit :campaign/publish})

(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                    :auto #{}}
   1 {:label "assisted-intake" :writes #{:campaign/intake}                    :auto #{}}
   2 {:label "assisted-assess" :writes #{:campaign/intake :regime/assess}     :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:campaign/intake}}})

(def default-phase 3)

;; Load-time assertion of the actuation invariant. A future edit that
;; drops `:campaign/publish` into phase 3's `:auto` set does not get to
;; be a quiet one-word diff.
(let [leaked (->> (vals phases)
                  (mapcat (comp seq :auto))
                  (filter never-auto)
                  set)]
  (when (seq leaked)
    (throw (ex-info "adreview.phase: actuation op placed in an :auto set"
                    {:leaked leaked}))))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean."
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
  "Map an Ad-Review Compliance Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))

(defn auto-eligible-ops
  "The ops this phase may auto-commit -- always disjoint from
  `never-auto`."
  [phase]
  (set/difference (:auto (get phases phase (get phases default-phase))) never-auto))
