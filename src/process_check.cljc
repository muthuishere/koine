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

;; ------------------------------------------------------- timeout and kill
;;
;; An agent's `bash` tool is the most dangerous thing it has, and until now it
;; had no off switch: `sh` ran to completion and `close!` politely WAITED on a
;; child that might never leave. Asked for by the toolnexus port, 2026-07-31.
;;
;; The two asks are ONE mechanism. Killing a child closes its stdout, so a
;; `read-line!` parked on a hung peer hits EOF and returns nil — which is the
;; portable answer to "interrupt a blocked read", a thing neither host offers
;; directly.

(def timeout? (host/supports? :process/timeout))

;; sleeps 30s; the deadline is 300ms. If the timeout does not work this check
;; does not fail, it HANGS for 30 seconds — same shape as the deadlock case.
(def timed-out (when timeout? (proc/sh ["sh" "-c" "sleep 30"] {:timeout-ms 300})))
;; the same option on a command that finishes well inside it must be invisible
(def in-time   (when timeout? (proc/sh ["sh" "-c" "printf 'quick\n'"] {:timeout-ms 10000})))

;; kill! as the way out of a blocked read: the child never answers, so
;; read-line! is parked until the kill closes its stdout under it.
(def killed
  (when (and spawn? (host/supports? :process/kill))
    (let [c (proc/spawn ["sh" "-c" "sleep 30"])
          ;; park a reader on a child that will never write
          r (future (proc/read-line! c))]
      (ktime/sleep! 100)
      (let [alive-before (proc/alive? c)
            ret          (proc/kill! c)
            ;; the parked reader must come back — EOF, not a value
            line         (deref r 5000 :still-blocked)]
        {:alive-before alive-before :ret ret :line line
         :alive-after (loop [n 0]
                        (if (or (> n 50) (not (proc/alive? c)))
                          (proc/alive? c)
                          (do (ktime/sleep! 20) (recur (inc n)))))}))))

;; ---------------------------------------------- a child that exits BY ITSELF
;;
;; The case this file was missing, and the gap is instructive: every `alive?`
;; assertion here used to sit either mid-conversation (child up, both hosts
;; agree) or side-by-side with `kill!` (which is what SETS the exit on cljgo, so
;; both hosts agree again). Every tested case was one where the two
;; implementations coincide, so the check was green while `alive?` genuinely
;; diverged — jvm false, cljgo true — for a peer that exited on its own.
;;
;; That is the shape to watch for: a check written around the MECHANISM instead
;; of the QUESTION. The question is "is the child still running", and nobody had
;; ever asked it of a child nobody had stopped. Reported by the toolnexus port.
(def self-exit
  (when spawn?
    (let [c (proc/spawn ["sh" "-c" "printf 'bye\n'; exit 0"])
          line (proc/read-line! c)
          eof  (proc/read-line! c)]
      ;; the child is gone; poll to a deadline, since reaping is asynchronous on
      ;; both hosts and asserting instantly would be racing, not testing
      {:line line
       :eof  eof
       :alive (loop [n 0]
                (if (or (> n 50) (not (proc/alive? c)))
                  (proc/alive? c)
                  (do (ktime/sleep! 20) (recur (inc n)))))
       ;; and close! must still hand back the code the child chose
       :exit (proc/close! c)})))

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
   ["eof-nil"       (:r2 at-eof)                  nil]

   ;; --- timeout: the result is DATA, and it is the SAME data on both hosts ---
   ["timeout-flag"  (:timed-out? timed-out)       (when timeout? true)]
   ;; nil, not 137 (JVM) and not -1 (cljgo). A killed process chose no exit
   ;; code, and koine will not invent agreement between two host inventions.
   ["timeout-exit-nil" (:exit timed-out)          nil]
   ["timeout-no-throw" (map? timed-out)           (boolean timeout?)]
   ;; the flag is ALWAYS present, so a caller can test it unconditionally
   ["normal-not-timed-out" (:timed-out? echoed)   false]
   ["timeout-unused"   (:timed-out? in-time)      (when timeout? false)]
   ["timeout-unused-out" (:out in-time)           (when timeout? "quick\n")]
   ["timeout-unused-exit" (:exit in-time)         (when timeout? 0)]

   ;; --- kill!: the off switch, and the way out of a parked read ---
   ["kill-alive-before" (:alive-before killed)    (when killed true)]
   ["kill-returns-nil"  (:ret killed)             nil]
   ["kill-then-dead"    (:alive-after killed)     (when killed false)]
   ;; the point of the whole exercise: a reader blocked on a hung peer comes
   ;; back at EOF instead of hanging the caller forever.
   ["kill-frees-reader" (:line killed)            nil]

   ;; --- a child nobody stopped: BOTH hosts must say it is gone ---
   ["self-exit-line"  (:line self-exit)           (when spawn? "bye")]
   ["self-exit-eof"   (:eof self-exit)            nil]
   ;; the divergence itself: false on the JVM, true on cljgo, until the reaper
   ["self-exit-dead"  (:alive self-exit)          (when spawn? false)]
   ;; reaping must not eat the exit code close! is supposed to return
   ["self-exit-code"  (:exit self-exit)           (when spawn? 0)]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass"
                (when-not spawn? " (spawn SKIPPED: unsupported on this host)"))))
