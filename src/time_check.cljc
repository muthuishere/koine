(require 'koine.time)
(alias 'kt 'koine.time)

;; Durations are inherently timing-sensitive, so every case is a predicate on
;; a measured value with a generous tolerance — never an equality on a clock.
(def t0 (kt/now-ms))
(def m0 (kt/mono-ms))
(def slept (let [s (kt/mono-ms)] (kt/sleep! 120) (- (kt/mono-ms) s)))
(def m1 (kt/mono-ms))
(def t1 (kt/now-ms))
(def el (kt/elapsed-ms m0))

(def cases
  [["now-ms plausible epoch" (and (> t0 1700000000000) (< t0 4000000000000)) true]
   ["now-ms is integral"     (= t0 (long t0))                                true]
   ["now-ms advances"        (>= t1 t0)                                      true]
   ["mono-ms integral"       (= m0 (long m0))                                true]
   ["mono-ms non-decreasing" (>= m1 m0)                                      true]
   ["mono-ms advanced >=100" (>= (- m1 m0) 100)                              true]
   ["sleep! actually slept"  (>= slept 115)                                  true]
   ["sleep! not absurd"      (< slept 3000)                                  true]
   ["sleep! returns nil"     (kt/sleep! 1)                                   nil]
   ["sleep! 0 is a no-op"    (let [s (kt/mono-ms)] (kt/sleep! 0) (< (- (kt/mono-ms) s) 50)) true]
   ["sleep! negative safe"   (let [s (kt/mono-ms)] (kt/sleep! -5) (< (- (kt/mono-ms) s) 50)) true]
   ["elapsed-ms >= sleep"    (>= el 100)                                     true]
   ["elapsed-ms tracks mono" (< (- (kt/elapsed-ms m0) (- m1 m0)) 200)        true]
   ["wall clock also moved"  (>= (- t1 t0) 100)                              true]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))
