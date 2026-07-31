(ns koine.process-test
  "JVM-side unit suite for koine.process. The cross-host runs are
  src/process_check.cljc (sh + spawn) and src/mcp_check.cljc (a real MCP stdio
  handshake).

  Only POSIX tools present on any dev box are used — sh, cat, pwd — so nothing
  needs installing. `cat` is the spawn peer throughout: it echoes each line as it
  arrives and exits 0 on EOF, which is exactly the MCP stdio shape."
  (:require [clojure.test :refer [deftest is testing]]
            [koine.process :as proc]))

;; --------------------------------------------------------------------- sh

(deftest sh-captures-stdout-and-exit
  (let [r (proc/sh ["sh" "-c" "printf 'hi\\n'"])]
    (is (= "hi\n" (:out r)))
    (is (= "" (:err r)))
    (is (= 0 (:exit r)))))

(deftest sh-does-not-throw-on-a-non-zero-exit
  (testing "a failing command is a normal result, not an exception"
    (let [r (proc/sh ["sh" "-c" "printf 'boom\\n' >&2; exit 7"])]
      (is (= 7 (:exit r)))
      (is (= "boom\n" (:err r)))
      (is (= "" (:out r))))))

(deftest sh-writes-stdin
  (is (= "fed\n" (:out (proc/sh ["cat"] {:in "fed\n"})))))

(deftest sh-honours-dir
  (is (re-find #"tmp" (:out (proc/sh ["pwd"] {:dir "/tmp"})))))

(deftest sh-honours-env
  (let [r (proc/sh ["sh" "-c" "printf '%s' \"$KOINE_TEST_ONE\""]
                   {:env {"KOINE_TEST_ONE" "one"}})]
    (is (= "one" (:out r)))))

(deftest sh-stringifies-command-elements
  (testing "numbers and keywords in the vector are coerced, not rejected"
    (is (= "2\n" (:out (proc/sh ["echo" 2]))))))

(deftest sh-handles-utf8
  (is (= "café ☃\n" (:out (proc/sh ["sh" "-c" "printf 'café ☃\\n'"])))))

;; ------------------------------------------------------------------ spawn

(deftest spawn-holds-a-multi-turn-conversation
  (testing "the property a run-to-completion sh cannot express"
    (let [c (proc/spawn ["cat"])]
      (try
        (proc/send-line! c "one")
        (is (= "one" (proc/read-line! c)))
        (is (proc/alive? c))
        (proc/send-line! c "two")
        (is (= "two" (proc/read-line! c)))
        (proc/send-line! c "three")
        (is (= "three" (proc/read-line! c)))
        (finally (is (= 0 (proc/close! c))))))))

(deftest spawn-strips-the-line-terminator
  (let [c (proc/spawn ["cat"])]
    (try
      (proc/send-line! c "x")
      (let [line (proc/read-line! c)]
        (is (= "x" line))
        (is (not (re-find #"[\r\n]" line))))
      (finally (proc/close! c)))))

(deftest spawn-passes-utf8-both-ways
  (let [c (proc/spawn ["cat"])]
    (try
      (proc/send-line! c "café ☃ 日本")
      (is (= "café ☃ 日本" (proc/read-line! c)))
      (finally (proc/close! c)))))

(deftest spawn-read-line-is-nil-at-eof
  (testing "nil, not \"\" and not a hang"
    (let [c (proc/spawn ["sh" "-c" "printf 'only\\n'"])]
      (try
        (is (= "only" (proc/read-line! c)))
        (is (nil? (proc/read-line! c)))
        (finally (proc/close! c))))))

(deftest spawn-close-returns-the-exit-code
  (let [c (proc/spawn ["sh" "-c" "exit 5"])]
    (is (= 5 (proc/close! c)))
    (is (not (proc/alive? c)))))

(deftest spawn-honours-dir-and-env
  (let [c (proc/spawn ["sh" "-c" "pwd; printf '%s\\n' \"$KOINE_TEST_TWO\""]
                      {:dir "/tmp" :env {"KOINE_TEST_TWO" "two"}})]
    (try
      (is (re-find #"tmp" (proc/read-line! c)))
      (is (= "two" (proc/read-line! c)))
      (finally (proc/close! c)))))

(deftest spawn-survives-a-large-line
  (testing "a 64 KiB payload — one JSON-RPC message can be big"
    (let [big (apply str (repeat 65536 "x"))
          c   (proc/spawn ["cat"])]
      (try
        (proc/send-line! c big)
        (is (= big (proc/read-line! c)))
        (finally (proc/close! c))))))

(deftest spawn-send-line-returns-nil
  (testing "documented return: nil, so it is never mistaken for a result"
    (let [c (proc/spawn ["cat"])]
      (try
        (is (nil? (proc/send-line! c "x")))
        (finally (proc/close! c))))))

;; ------------------------------------------------------------------ stderr

(deftest stderr-is-drained-so-a-noisy-child-cannot-deadlock
  (testing "the child writes ~256 KiB to stderr — four times a typical 64 KiB
  pipe buffer — BEFORE answering on stdout. With stderr undrained it blocks on
  write and this test hangs rather than fails, which is precisely the production
  symptom: a peer that handshakes and then goes silent."
    (let [c (proc/spawn ["sh" "-c" "i=0; while [ $i -lt 4000 ]; do printf 'noise %d padding padding padding padding\\n' $i >&2; i=$((i+1)); done; read x; printf 'answered:%s\\n' \"$x\""])]
      (try
        (proc/send-line! c "ping")
        (is (= "answered:ping" (proc/read-line! c)))
        (finally (proc/close! c))))))

(deftest stderr-lines-are-bounded-and-available
  (let [c (proc/spawn ["sh" "-c" "i=0; while [ $i -lt 500 ]; do printf 'line %d\\n' $i >&2; i=$((i+1)); done; read x; printf 'ok\\n'"])]
    (try
      (proc/send-line! c "go")
      (proc/read-line! c)
      (proc/close! c)
      ;; drained on a background reader, so poll to a deadline rather than race
      (let [lines (loop [n 0]
                    (let [l (proc/stderr-lines c)]
                      (if (or (> n 50) (seq l)) l (do (Thread/sleep 20) (recur (inc n))))))]
        (is (vector? lines))
        (is (seq lines) "stderr must be captured, not just consumed")
        (testing "bounded — a chatty server must not grow the caller's heap"
          (is (<= (count lines) 200)))
        (testing "it is the TAIL that is kept, which is what explains a crash"
          (is (re-find #"line 4\d\d" (str (last lines))))))
      (finally (proc/close! c)))))

(deftest stderr-lines-is-empty-not-nil-for-a-quiet-child
  (let [c (proc/spawn ["cat"])]
    (try
      (is (= [] (proc/stderr-lines c)))
      (finally (proc/close! c)))))
