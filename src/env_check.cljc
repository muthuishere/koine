;; env_check.cljc — portability conformance for koine.env.
;;
;; Named `env_check` (not `envcheck`): run-conformance.sh globs `*_check.cljc`,
;; so the old spelling was never picked up by the suite.
;;
;; HOME is the only variable assumed to be set — it is present on every host
;; this runs on and needs no wrapper script. The interesting cases are the
;; UNSET ones, because Go returns "" where the JVM returns nil and "" is truthy
;; in Clojure: a host that skips koine.env's blank->nil normalisation passes
;; every "set" case and fails every default.
(require 'koine.env)
(alias 'env 'koine.env)

(def home (env/get-env "HOME"))

(def cases
  [["set-nonblank"   (boolean (seq (str home)))                 true]
   ["set-is-string"  (string? home)                             true]
   ;; the Go-vs-JVM trap: unset MUST be nil, never ""
   ["unset-nil"      (env/get-env "KOINE_DEFINITELY_UNSET_XYZ") nil]
   ["unset-default"  (env/get-env "KOINE_DEFINITELY_UNSET_XYZ" "fallback") "fallback"]
   ["set-ignores-default" (env/get-env "HOME" "fallback")       home]
   ;; expand: set vars interpolate, unset ones become ""
   ["expand-set"     (env/expand "[${HOME}]")                   (str "[" home "]")]
   ["expand-unset"   (env/expand "[${KOINE_DEFINITELY_UNSET_XYZ}]") "[]"]
   ["expand-both"    (env/expand "${KOINE_DEFINITELY_UNSET_XYZ}${HOME}") (str home)]
   ["expand-twice"   (env/expand "${HOME}:${HOME}")             (str home ":" home)]
   ["expand-none"    (env/expand "no vars here")                "no vars here"]
   ;; ${…} that is not a legal variable name is left alone, not eaten
   ["expand-nonname" (env/expand "${not-a-name}")               "${not-a-name}"]
   ["expand-bare$"   (env/expand "cost: $5")                    "cost: $5"]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))
