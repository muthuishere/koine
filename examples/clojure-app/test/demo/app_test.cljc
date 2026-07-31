(ns demo.app-test
  "ONE test file, run unchanged by BOTH hosts:

    cd examples/jvm  && clojure -M:test     ; Clojure 1.12 on the JVM
    cd examples/app  && cljgo test          ; cljgo

  Both resolve koine from the same Clojars artifact. If a koine seam ever
  diverges between the hosts, one of these fails and the other does not — which
  is the only kind of proof that means anything for a portability library."
  (:require [clojure.string :as cstr]
            [clojure.test :refer [deftest is testing]]
            [demo.app :as app]
            [koine.codec :as codec]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as t]))

(def cfg {:name "test" :workdir "/tmp/koine-demo-test"})

(defn- fresh! []
  (proc/sh ["rm" "-rf" (:workdir cfg)])
  (proc/sh ["mkdir" "-p" (:workdir cfg)]))

;; --------------------------------------------------------------- env

(deftest config-defaults-when-unset
  (let [c (app/config)]
    (testing "a default fires for an unset variable — the Go \"\" vs nil trap"
      (is (string? (:name c)))
      (is (seq (:workdir c))))
    (testing "an unset variable with no default is nil, never \"\""
      (is (or (nil? (:token c)) (string? (:token c)))))))

;; ------------------------------------------------------------- bytes

(deftest record-writes-real-bytes
  (fresh!)
  (let [r (app/record! cfg "hello.bin" "hello ☃")]
    (is (= (str (:workdir cfg) "/hello.bin") (:path r)))
    (testing "utf-8 is 9 bytes here, not 7 characters"
      (is (= 9 (:size r))))
    (is (= "aGVsbG8g4piD" (:base64 r)))
    (testing "the stamp is ISO-8601 and parses back"
      (is (integer? (t/parse-iso (:at r)))))))

(deftest bytes-survive-that-text-cannot-carry
  (fresh!)
  (let [raw (byte-array [0 1 2 -128 -1 65])
        r   (app/record! cfg "raw.bin" raw)]
    (is (= [0 1 2 -128 -1 65] (vec (fs/read-bytes (:path r)))))
    (testing "signed elements — identical on both hosts"
      (is (= -128 (nth (vec (fs/read-bytes (:path r))) 3))))
    (testing "base64 round trip returns the same bytes"
      (is (= [0 1 2 -128 -1 65] (vec (codec/decode-bytes (:base64 r))))))))

(deftest artifacts-are-sorted-and-filtered
  (fresh!)
  (app/record! cfg "b.bin" "b")
  (app/record! cfg "a.bin" "a")
  (fs/write-file (str (:workdir cfg) "/notes.txt") "ignored")
  (let [found (app/artifacts cfg)]
    (is (= [(str (:workdir cfg) "/a.bin") (str (:workdir cfg) "/b.bin")] found))
    (testing "sorted, so discovery is deterministic across hosts"
      (is (= found (vec (sort found)))))))

;; ----------------------------------------------------------- subprocess

(deftest rpc-client-holds-a-multi-turn-conversation
  (testing "the property a run-to-completion `sh` cannot express"
    (let [{:keys [call stop]} (app/rpc-client ["cat"])
          a (call "ping" {:seq 1})
          b (call "ping" {:seq 2})]
      (is (= 1 (:id a)))
      (is (= 2 (:id b)))                       ; the child was still alive
      (is (= "ping" (:method a)))
      (is (= {:seq 2} (:params b)))
      (is (= 0 (stop))))))

(deftest json-survives-the-round-trip-through-the-child
  (let [{:keys [call stop]} (app/rpc-client ["cat"])
        payload {:text "café ☃" :nested {:xs [1 2.5 nil true]}}
        back    (call "echo" payload)]
    (stop)
    (is (= payload (:params back)))
    (testing "2.5 stays a float and nil stays null"
      (is (= [1 2.5 nil true] (get-in back [:params :nested :xs]))))))

;; ----------------------------------------------------------------- time

(deftest timed-measures-with-the-monotonic-clock
  (let [r (app/timed (fn [] (t/sleep! 60) :done))]
    (is (= :done (:value r)))
    (is (>= (:ms r) 50))
    (is (< (:ms r) 5000))))

;; ------------------------------------------------------------------ run

(deftest run-produces-the-whole-shape
  (let [out (app/run)]
    (is (contains? (:config out) :name))
    (testing "the token is never in the output — it is a secret"
      (is (not (contains? (:config out) :token))))
    (is (= 9 (get-in out [:receipt :size])))
    (is (seq (:artifacts out)))
    (is (= 2 (count (:echo out))))
    (is (integer? (:echo-ms out)))))

(deftest run-output-is-json-encodable
  (testing "sorted keys, so both hosts emit byte-identical JSON"
    (let [s (json/write-str (app/run))]
      (is (string? s))
      (is (= (:receipt (json/read-str s)) (:receipt (json/read-str s))))
      (testing "keys are sorted — \"artifacts\" precedes \"config\". Note
      `clojure.string/index-of`, NOT `.indexOf`: Java interop in a test file is
      exactly the thing this example exists to avoid."
        (is (< (cstr/index-of s "\"artifacts\"") (cstr/index-of s "\"config\"")))))))
