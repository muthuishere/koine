(ns koine.codec-test
  "JVM-side unit suite for koine.codec. The cross-host run is
  src/bytes_check.cljc, which also checks that fs bytes and base64 compose."
  (:require [clojure.test :refer [deftest is testing]]
            [koine.codec :as codec]))

(deftest encodes-ascii-with-padding
  (is (= "aGVsbG8=" (codec/encode "hello")))
  (testing "padding is emitted — RFC 4648 §4, not the unpadded variant"
    (is (= "YQ==" (codec/encode "a")))
    (is (= "YWI=" (codec/encode "ab")))
    (is (= "YWJj" (codec/encode "abc")))))

(deftest encodes-utf8-as-bytes-not-code-points
  (is (= "aGVsbG8g4piD" (codec/encode "hello ☃")))
  (is (= "hello ☃" (codec/decode (codec/encode "hello ☃")))))

(deftest empty-input-round-trips
  (is (= "" (codec/encode "")))
  (is (= "" (codec/decode ""))))

(deftest uses-the-standard-alphabet-not-url-safe
  (testing "+ and / must appear, never - and _"
    (let [s (codec/encode (byte-array [-5 -1 -66]))]
      (is (= "+/++" s))
      (is (not (re-find #"[-_]" s))))))

(deftest encodes-a-byte-array
  (is (= "AAECgP9B" (codec/encode (byte-array [0 1 2 -128 -1 65])))))

(deftest decode-bytes-carries-what-a-string-cannot
  (testing "0x80 and 0xFF are not legal UTF-8 — only the byte route survives"
    (let [bs (codec/decode-bytes "AAECgP9B")]
      (is (= [0 1 2 -128 -1 65] (vec bs)))
      (testing "elements are SIGNED, like the JVM's byte[]"
        (is (= -128 (nth (vec bs) 3)))
        (is (= -1 (nth (vec bs) 4)))))))

(deftest round-trips-every-byte-value
  (let [all (byte-array (map (fn [i] (if (> i 127) (- i 256) i)) (range 256)))]
    (is (= (vec all) (vec (codec/decode-bytes (codec/encode all)))))))

(deftest decode-is-the-inverse-of-encode-for-text
  (doseq [s ["" "a" "ab" "abc" "hello world" "café ☃ 日本" "line\nbreak\ttab"]]
    (is (= s (codec/decode (codec/encode s))) (str "round trip: " (pr-str s)))))
