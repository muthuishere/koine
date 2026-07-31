;; shadow_check.cljc — no koine name may ACCIDENTALLY shadow a clojure.core var.
;;
;; The hosts do not agree on what clojure.core contains: cljgo's has `ok`, `err`
;; and `close!`; the JVM's does not. So a name that is free on one host shadows
;; on the other, and the punishment is not uniform — `koine.json/err` merely
;; WARNED on every cljgo load, while `koine.route/proxy` had its entire
;; namespace REJECTED and forced the breaking 0.3.0 rename to `forward`.
;;
;; Runs on EVERY host, which is the point: a JVM-only lint cannot catch any of
;; this, because none of these names is special on the JVM. Private names count
;; too — `err` was private.
;;
;; A shadow is allowed only if it is DECLARED: listed below with a reason, and
;; carrying a matching `:refer-clojure :exclude` in its namespace. Anything else
;; fails. Recommended by the toolnexus port, 2026-07-31; it found `close!` on
;; its first run.
(require 'koine.json 'koine.env 'koine.time 'koine.fs 'koine.codec
         'koine.process 'koine.http 'koine.stream 'koine.route 'koine.server
         'koine.host)

(def nss '[koine.json koine.env koine.time koine.fs koine.codec
           koine.process koine.http koine.stream koine.route koine.server
           koine.host])

(def declared
  "Deliberate shadows: the name is right, the collision is host-specific, and
  the namespace declares `:refer-clojure :exclude` for it."
  #{"koine.process/close!"})   ; in cljgo's core, not the JVM's

(defn- shadows [ns-sym]
  (->> (ns-interns (find-ns ns-sym))
       keys
       (filter (fn [n] (some? (ns-resolve 'clojure.core n))))
       (map (fn [n] (str ns-sym "/" n)))
       (remove declared)
       sort))

(def found (vec (mapcat shadows nss)))

(def cases
  [["no-accidental-core-shadows" found []]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got _] fails]
    (println "  FAIL" l)
    (println "    these koine names also exist in clojure.core on THIS host:")
    (doseq [n got] (println "     " n))
    (println "    rename, or declare it: :refer-clojure :exclude + an entry in `declared`"))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))
