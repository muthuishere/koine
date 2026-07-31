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
(require 'koine.process 'koine.host)
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

(def cases
  [["sh-out"        (:out echoed)                 "hi\n"]
   ["sh-exit-0"     (:exit echoed)                0]
   ["sh-nonzero"    (:exit failed)                3]
   ["sh-err"        (:err failed)                 "oops\n"]
   ["sh-no-throw"   (map? failed)                 true]
   ["sh-stdin"      (:out fed)                    "fed-in\n"]
   ["sh-dir"        (boolean (re-find #"tmp" (:out in-dir))) true]
   ["sh-env"        (:out with-env)               "envd"]

   ;; spawn is not on every host — let-go has no route to a live child's pipes.
   ;; `koine.host/supports?` answers that portably, so the check SKIPS rather
   ;; than failing or (worse) needing a host-specific catch to probe with.
   ["spawn-rt1"     (:r1 convo)                   (when spawn? "one")]
   ["spawn-rt2"     (:r2 convo)                   (when spawn? "two")]      ; the buffering tell
   ["spawn-utf8"    (:r3 convo)                   (when spawn? "café ☃")]
   ["spawn-alive"   (:alive-mid convo)            (when spawn? true)]
   ["spawn-exit"    (:exit convo)                 (when spawn? 0)]
   ["spawn-no-nl"   (if spawn? (nil? (re-find #"\n" (str (:r1 convo)))) true) true]

   ["eof-line"      (:r1 at-eof)                  (when spawn? "only")]
   ["eof-nil"       (:r2 at-eof)                  nil]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass"
                (when-not spawn? " (spawn SKIPPED: unsupported on this host)"))))
