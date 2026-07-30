(ns koine.time-test
  "Timing is measured, not asserted exactly: every case here is a bound with a
  generous tolerance so a loaded machine never produces a false failure. The
  cross-host run is src/time_check.cljc — this suite only covers the JVM."
  (:require [clojure.test :refer [deftest is testing]]
            [koine.time :as t]))

(deftest now-ms-is-a-plausible-epoch-millis
  (let [n (t/now-ms)]
    (is (integer? n))
    (is (= n (long n)))
    (testing "after 2023 and before 2096 — catches seconds/nanos unit slips"
      (is (> n 1700000000000))
      (is (< n 4000000000000)))))

(deftest now-ms-does-not-run-backwards-over-a-sleep
  (let [a (t/now-ms)
        _ (t/sleep! 50)
        b (t/now-ms)]
    (is (>= b a))
    (is (>= (- b a) 40))))

(deftest mono-ms-never-goes-backwards
  (let [readings (repeatedly 200 t/mono-ms)]
    (is (= readings (sort readings)))))

(deftest mono-ms-measures-a-sleep
  (let [a (t/mono-ms)
        _ (t/sleep! 120)
        d (- (t/mono-ms) a)]
    (is (>= d 115) "sleep! must actually block")
    (is (< d 3000) "and must not block wildly longer than asked")))

(deftest sleep-returns-nil
  (is (nil? (t/sleep! 1))))

(deftest sleep-with-non-positive-ms-is-a-no-op
  (testing "Thread/sleep throws on a negative argument; koine normalises to Go's return-at-once"
    (let [a (t/mono-ms)]
      (is (nil? (t/sleep! 0)))
      (is (nil? (t/sleep! -5)))
      (is (< (- (t/mono-ms) a) 50)))))

(deftest elapsed-ms-is-mono-ms-minus-start
  (let [start (t/mono-ms)]
    (t/sleep! 60)
    (let [e (t/elapsed-ms start)]
      (is (>= e 55))
      (is (< e 3000))
      (testing "agrees with an explicit subtraction"
        (is (< (Math/abs (long (- (t/elapsed-ms start) (- (t/mono-ms) start)))) 50))))))

;; ------------------------------------------------------------ the wire format

(deftest iso-str-matches-instant-toString
  (testing "exact strings — the cross-host contract is byte-for-byte"
    (is (= "1970-01-01T00:00:00Z" (t/iso-str 0)))
    (is (= "2026-07-30T10:20:30Z" (t/iso-str 1785406830000))))
  (testing "millisecond rule: absent on a whole second, exactly 3 digits otherwise"
    (is (= "1970-01-01T00:00:01.500Z" (t/iso-str 1500)))
    (is (= "1970-01-01T00:00:01.100Z" (t/iso-str 1100)))
    (is (= "1970-01-01T00:00:02Z" (t/iso-str 2000)))))

(deftest iso-str-defaults-to-now
  (let [before (t/now-ms)
        s      (t/iso-str)
        after  (t/now-ms)
        parsed (t/parse-iso s)]
    (is (<= (- before 1000) parsed (+ after 1000)))))

(deftest parse-iso-returns-epoch-millis
  (is (= 0 (t/parse-iso "1970-01-01T00:00:00Z")))
  (is (= 1500 (t/parse-iso "1970-01-01T00:00:01.500Z")))
  (is (= 1785406830000 (t/parse-iso "2026-07-30T10:20:30Z")))
  (testing "returns a long, not a double or a host instant"
    (is (integer? (t/parse-iso "2026-07-30T10:20:30Z")))))

(deftest parse-iso-honours-an-offset-rather-than-dropping-it
  (is (= (t/parse-iso "2026-07-30T06:30:00Z")
         (t/parse-iso "2026-07-30T12:00:00+05:30")))
  (is (= (t/parse-iso "2026-07-30T12:00:00Z")
         (t/parse-iso "2026-07-30T07:00:00-05:00"))))

(deftest iso-round-trips-both-directions
  (doseq [ms [0 1 999 1000 1500 1785406830000 1785406830123]]
    (is (= ms (t/parse-iso (t/iso-str ms))) (str "ms round trip: " ms)))
  (doseq [s ["1970-01-01T00:00:00Z" "2026-07-30T10:20:30Z" "2026-07-30T10:20:30.123Z"]]
    (is (= s (t/iso-str (t/parse-iso s))) (str "string round trip: " s))))

(deftest parse-iso-rejects-a-non-instant
  (testing "throws rather than returning a wrong number"
    (is (thrown? Exception (t/parse-iso "not a date")))
    (is (thrown? Exception (t/parse-iso "2026-07-30")))
    (is (thrown? Exception (t/parse-iso "")))))
