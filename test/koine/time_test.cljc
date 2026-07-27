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
