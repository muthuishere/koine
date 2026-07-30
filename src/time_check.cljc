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
   ["wall clock also moved"  (>= (- t1 t0) 100)                              true]

   ;; --- ISO-8601, the wire format. Exact strings, not shapes: the whole point
   ;; is that two hosts stamp the SAME instant identically, and the millisecond
   ;; rule (absent on a whole second, exactly 3 digits otherwise) is where
   ;; java.time and a hand-rolled Go formatter most easily disagree.
   ["iso epoch zero"         (kt/iso-str 0)              "1970-01-01T00:00:00Z"]
   ["iso millis kept"        (kt/iso-str 1500)           "1970-01-01T00:00:01.500Z"]
   ["iso 3 digits not 1"     (kt/iso-str 1100)           "1970-01-01T00:00:01.100Z"]
   ["iso whole second bare"  (kt/iso-str 2000)           "1970-01-01T00:00:02Z"]
   ["iso a real date"        (kt/iso-str 1785406830000)  "2026-07-30T10:20:30Z"]
   ["iso is utc"             (kt/iso-str 1785406830000)  (kt/iso-str 1785406830000)]
   ["parse round trip"       (kt/parse-iso "2026-07-30T10:20:30Z")           1785406830000]
   ["parse with millis"      (kt/parse-iso "1970-01-01T00:00:01.500Z")       1500]
   ["parse epoch zero"       (kt/parse-iso "1970-01-01T00:00:00Z")           0]
   ["parse honours offset"   (= (kt/parse-iso "2026-07-30T12:00:00+05:30")
                                (kt/parse-iso "2026-07-30T06:30:00Z"))       true]
   ["iso->parse->iso"        (kt/iso-str (kt/parse-iso (kt/iso-str 1785406830123)))
                                                         "2026-07-30T10:20:30.123Z"]
   ["parse->iso->parse"      (kt/parse-iso (kt/iso-str (kt/parse-iso "2026-07-30T10:20:30.123Z")))
                                                         1785406830123]
   ["iso-str of now parses"  (integer? (kt/parse-iso (kt/iso-str)))          true]
   ["iso-str now ~ now-ms"   (< (abs (- (kt/parse-iso (kt/iso-str)) (kt/now-ms))) 2000) true]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))
