(ns cljhost.json-test
  "The encoder's output is a CONTRACT: these expected strings must be produced
  byte-for-byte on every host. Run this suite under each host; any difference
  is a portability bug, not a test to relax."
  (:require [clojure.test :refer [deftest is testing]]
            [cljhost.json :as json]))

(deftest encode-scalars
  (is (= "null" (json/write-str nil)))
  (is (= "true" (json/write-str true)))
  (is (= "false" (json/write-str false)))
  (is (= "42" (json/write-str 42)))
  (is (= "\"hi\"" (json/write-str "hi")))
  (is (= "\"kw\"" (json/write-str :kw)))
  (is (= "\"ns/kw\"" (json/write-str :ns/kw))))

(deftest encode-floats-keep-their-fraction
  (testing "a float must never collapse to an integer — that changes the JSON type"
    (is (= "1.0" (json/write-str 1.0)))
    (is (= "100.0" (json/write-str 100.0)))
    (is (= "1.5" (json/write-str 1.5)))
    (is (= "[1,2.0,3.5]" (json/write-str [1 2.0 3.5])))))

(deftest encode-object-keys-are-sorted
  (testing "sorted, because Clojure map order is unspecified above 8 entries"
    (is (= "{\"a\":2,\"b\":1,\"c\":3}" (json/write-str {"b" 1 "a" 2 "c" 3})))
    (is (= "{\"a\":1,\"b\":2}" (json/write-str {:b 2 :a 1})))
    (is (= (json/write-str (into {} (map #(vector (str "k" %) %) (range 20))))
           (json/write-str (into {} (map #(vector (str "k" %) %) (reverse (range 20)))))))))

(deftest encode-escaping
  (testing "the seven JSON escapes plus control chars"
    (is (= "\"a\\\"b\"" (json/write-str "a\"b")))
    (is (= "\"a\\\\b\"" (json/write-str "a\\b")))
    (is (= "\"a\\nb\"" (json/write-str "a\nb")))
    (is (= "\"a\\tb\"" (json/write-str "a\tb")))
    (is (= "\"\\u0000\"" (json/write-str (str (char 0))))))
  (testing "HTML chars are NOT escaped — that is a Go default, not JSON"
    (is (= "\"a<b>c&d\"" (json/write-str "a<b>c&d"))))
  (testing "non-ASCII is emitted literally as UTF-8"
    (is (= "\"café ☃\"" (json/write-str "café ☃")))))

(deftest encode-collections
  (is (= "[]" (json/write-str [])))
  (is (= "{}" (json/write-str {})))
  (is (= "[1,\"x\",null,true]" (json/write-str [1 "x" nil true])))
  (is (= "{\"a\":{\"b\":[1,2]}}" (json/write-str {:a {:b [1 2]}}))))

(deftest decode-basics
  (is (= {:a 1} (json/read-str "{\"a\":1}")))
  (is (= {"a" 1} (json/read-str "{\"a\":1}" {:key-fn str})))
  (is (= [1 2 3] (json/read-str "[1,2,3]")))
  (is (= nil (json/read-str "null")))
  (is (= "café ☃" (json/read-str "\"café ☃\"")))
  (is (= "café ☃" (json/read-str "\"caf\\u00e9 \\u2603\""))
      "escaped and literal unicode must decode identically"))

(deftest round-trip
  (doseq [x [{} [] {:a 1 :b [1 2 {:c "x"}]} "" "a\nb\tc" {:n 1.5} [true false nil]]]
    (is (= x (json/read-str (json/write-str x)))
        (str "round-trip failed for " (pr-str x)))))
