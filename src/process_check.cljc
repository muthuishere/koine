;; process_check.cljc — portability conformance for koine.process.
;;
;; `spawn` is the reason this namespace exists: an MCP stdio transport is a
;; long-lived child spoken to line by line, and a run-to-completion `sh` cannot
;; express it. So the property under test is CONVERSATION — several round trips
;; against a child that is still running between them. A host that fakes spawn
;; by buffering passes a single round trip and deadlocks on the second.
;;
;; Only POSIX tools every host box has are used (cat, sh, pwd, env), so there is
;; nothing to install. `cat` is the ideal spawn peer: it echoes each line as it
;; arrives and exits 0 on EOF.
(require 'koine.process 'koine.host 'koine.time)
(alias 'ktime 'koine.time)
(alias 'proc 'koine.process)
(alias 'host 'koine.host)

;; --- sh: run to completion --------------------------------------------------
(def echoed  (proc/sh ["sh" "-c" "printf 'hi\\n'"]))
(def failed  (proc/sh ["sh" "-c" "printf 'oops\\n' >&2; exit 3"]))
(def fed     (proc/sh ["cat"] {:in "fed-in\n"}))
(def in-dir  (proc/sh ["pwd"] {:dir "/tmp"}))
(def with-env (proc/sh ["sh" "-c" "printf '%s' \"$KOINE_CHECK_VAR\""]
                       {:env {"KOINE_CHECK_VAR" "envd"}}))

;; --- spawn: a live conversation ---------------------------------------------
(def spawn? (host/supports? :process/spawn))

(def convo
  (when spawn?
   (let [c   (proc/spawn ["cat"])
        _   (proc/send-line! c "one")
        r1  (proc/read-line! c)
        a1  (proc/alive? c)
        _   (proc/send-line! c "two")
        r2  (proc/read-line! c)
        _   (proc/send-line! c "café ☃")
        r3  (proc/read-line! c)
        ex  (proc/close! c)]
    {:r1 r1 :r2 r2 :r3 r3 :alive-mid a1 :exit ex})))

;; EOF: after the child's output is exhausted, read-line! is nil, not a hang
;; and not "".
(def at-eof
  (when spawn?
   (let [c  (proc/spawn ["sh" "-c" "printf 'only\\n'"])
        r1 (proc/read-line! c)
        r2 (proc/read-line! c)]
    (proc/close! c)
    {:r1 r1 :r2 r2})))

;; --- stderr: drained ALWAYS, or the child deadlocks -------------------------
;;
;; The child writes ~256 KiB to stderr — four times a typical 64 KiB pipe buffer
;; — and only THEN answers on stdout. If stderr is not being drained, the child
;; blocks on write and this hangs forever: the exact symptom is a peer that
;; handshakes and then goes silent. Before the drain landed, this test did not
;; fail, it HUNG, which is why it is worth having.
(def noisy
  (when spawn?
    (let [c (proc/spawn ["sh" "-c" "i=0; while [ $i -lt 4000 ]; do printf 'noise line %d padding padding padding padding padding\n' $i >&2; i=$((i+1)); done; read x; printf 'answered:%s\n' \"$x\""])]
      (proc/send-line! c "ping")
      (let [line (proc/read-line! c)
            _    (proc/close! c)
            ;; stderr is drained on a BACKGROUND reader, so it is eventually
            ;; consistent: after close! the child is gone but the drainer may
            ;; still be finishing the tail. Poll to a deadline rather than
            ;; asserting on a race — and note that this is the contract,
            ;; documented on stderr-lines, not a flaw being papered over.
            errs (loop [n 0]
                   (let [e (proc/stderr-lines c)]
                     (if (or (> n 50)
                             (and (seq e) (re-find #"noise line" (str (last e)))))
                       e
                       (do (ktime/sleep! 20) (recur (inc n))))))]
        {:line line :err-count (count errs) :first (first errs) :last (last errs)
         :errs-type errs}))))

(def cases
  [["sh-out"        (:out echoed)                 "hi\n"]
   ["sh-exit-0"     (:exit echoed)                0]
   ["sh-nonzero"    (:exit failed)                3]
   ["sh-err"        (:err failed)                 "oops\n"]
   ["sh-no-throw"   (map? failed)                 true]
   ["sh-stdin"      (:out fed)                    "fed-in\n"]
   ["sh-dir"        (boolean (re-find #"tmp" (:out in-dir))) true]
   ["sh-env"        (:out with-env)               "envd"]

   ;; `koine.host/supports?` gates this rather than a host-specific catch, so a
   ;; host without a live-child route skips instead of failing.
   ["spawn-rt1"     (:r1 convo)                   (when spawn? "one")]
   ["spawn-rt2"     (:r2 convo)                   (when spawn? "two")]      ; the buffering tell
   ["spawn-utf8"    (:r3 convo)                   (when spawn? "café ☃")]
   ["spawn-alive"   (:alive-mid convo)            (when spawn? true)]
   ["spawn-exit"    (:exit convo)                 (when spawn? 0)]
   ["spawn-no-nl"   (if spawn? (nil? (re-find #"\n" (str (:r1 convo)))) true) true]

   ;; the deadlock test: the child answers only AFTER 4000 stderr lines
   ["stderr-no-deadlock" (:line noisy)          (when spawn? "answered:ping")]
   ;; Capture is a SEPARATE capability from the drain: a host can consume stderr
   ;; (so the deadlock above cannot happen) and still not surface the lines.
   ;; Asked, not assumed.
   ["stderr-captured"    (boolean (seq (:first noisy)))
                         (boolean (and spawn? (host/supports? :process/stderr-capture)))]
   ["stderr-bounded"     (if spawn? (<= (:err-count noisy) 200) true) true]
   ["stderr-is-a-vector" (vector? (:errs-type noisy))  (boolean spawn?)]

   ["eof-line"      (:r1 at-eof)                  (when spawn? "only")]
   ["eof-nil"       (:r2 at-eof)                  nil]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass"
                (when-not spawn? " (spawn SKIPPED: unsupported on this host)"))))
