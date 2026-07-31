(ns koine.process
  "Subprocesses, portable.

  `sh` runs a command to completion; `spawn` keeps a long-lived child with piped
  stdin/stdout, which is what an MCP stdio transport needs and what `sh` cannot
  express.

  Both have an OFF SWITCH: `sh` takes `:timeout-ms`, and a spawned child takes
  `kill!`. A subprocess is the most dangerous thing most programs do, and one
  that cannot be stopped is a program that cannot be stopped."
  (:require [clojure.string :as cstr])
  #?(:cljgo (:require [cljg.io :as cio])))

;; cljgo: cljg.process (streaming spawn) and cljg.stream (the pipe handles) must
;; be interned before `spawn` is reachable. A top-level reader conditional with
;; no branch for the other hosts reads as nothing there, so no other dialect
;; pays for this (same pattern as koine.time).
#?(:cljgo (require '[cljg.process] '[cljg.stream]))

;; ------------------------------------------------- helpers for the Go host
;;
;; :dir and :env are applied by wrapping the command in `sh -c` on hosts that
;; cannot set them on the child directly, so the quoting helper below is shared
;; rather than written twice.

(defn- run-async!
  "Run `f` on a background thread that must NOT keep the program alive.

  NOT `future` on the JVM. Clojure's future pool threads are non-daemon with a
  60-second keep-alive, so a program that called ONE koine.process fn sat there
  for a full minute after printing its answer — no output, no CPU, nothing to
  see. `(shutdown-agents)` fixes it, but a library cannot demand that of a
  consumer, and a library must never decide when the host program may exit.
  Measured 2026-07-31: 60.5s for a `sh [\"true\"]`.

  On cljgo the equivalent is a goroutine, which never holds up exit."
  [f]
  #?(:clj   (doto (Thread. ^Runnable f) (.setDaemon true) (.start))
     :cljgo (future (f))
     :default (throw (ex-info "koine.process: no async primitive for this host; add a branch in koine/process.cljc" {}))))

(defn- shq
  "Single-quote `s` for POSIX sh: wrap it, and end/escape/reopen each embedded
  quote. Nothing inside survives as a metacharacter, so a path or an env value
  containing a space, a `$` or a `;` cannot become a second command."
  [s]
  (str "'" (cstr/replace (str s) "'" "'\\''") "'"))

(defn- wrap-cmd
  "The argv for `sh -c`, applying `dir` and `env` as shell built-ins. Returns
  nil when neither is needed, so the common case execs the binary directly."
  [command dir env]
  (when (or dir (seq env))
    (let [parts (concat (when dir [(str "cd " (shq dir) " &&")])
                        (map (fn [[k v]] (str (name k) "=" (shq v))) env)
                        (map shq command))]
      ["sh" "-c" (cstr/join " " parts)])))

(defn sh
  "Run `command` (a vector) to completion. Returns
  {:out :err :exit :timed-out?}. Never throws on a non-zero exit — that is a
  normal result.

  opts: :in (string on stdin) :dir :env :timeout-ms

  `:timeout-ms` force-kills the command after n milliseconds. A killed command
  reports `:timed-out? true` and `:exit nil` — NOT a number. There is no exit
  code when a process is killed: the JVM would report 137 and Go -1, both of
  them inventions, and a caller doing `(zero? (:exit r))` must not silently read
  a kill as a clean run. `:timed-out?` is always present, so it is safe to test
  on every result. `:out` / `:err` carry whatever the command managed to write
  before it died, which is best-effort and NOT guaranteed to match across hosts.

  Without `:timeout-ms` the command runs to completion, however long that is.
  Asked for by the toolnexus port: an agent's `bash` tool needs an off switch."
  ([command] (sh command {}))
  ([command {:keys [in dir env timeout-ms]}]
   #?(:clj
      (let [pb (ProcessBuilder. ^java.util.List (vec (map str command)))
            _  (when dir (.directory pb (java.io.File. ^String dir)))
            _  (when env (let [m (.environment pb)]
                           (doseq [[k v] env] (.put m (str k) (str v)))))
            p  (.start pb)]
        (when in
          (with-open [os (.getOutputStream p)]
            (.write os (.getBytes ^String in "UTF-8"))))
        ;; Both pipes are drained CONCURRENTLY with the wait. Reading them in
        ;; sequence deadlocks the moment a command fills one buffer while we
        ;; block on the other — the same pipe-buffer trap `spawn` documents for
        ;; stderr, and with a timeout it would also make the deadline unreachable.
        (let [out-p (promise)
              err-p (promise)
              _     (run-async! (fn [] (deliver out-p (slurp (.getInputStream p)))))
              _     (run-async! (fn [] (deliver err-p (slurp (.getErrorStream p)))))
              done? (if timeout-ms
                      (.waitFor p (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
                      (do (.waitFor p) true))]
          (if done?
            {:out @out-p :err @err-p :exit (.exitValue p) :timed-out? false}
            (do (.destroyForcibly p)
                (.waitFor p)
                ;; the readers end at EOF once the child is gone, but a
                ;; DEADLINE is still required: a grandchild holding the pipe
                ;; open outlives its parent, and this must not become the
                ;; second thing that hangs forever.
                {:out (deref out-p 2000 "") :err (deref err-p 2000 "")
                 :exit nil :timed-out? true}))))

      :cljgo
      (let [r (cio/exec (vec (map str command)) (cond-> {}
                                                  in         (assoc :in in)
                                                  dir        (assoc :dir dir)
                                                  env        (assoc :env env)
                                                  timeout-ms (assoc :timeout-ms timeout-ms)))]
        ;; cljgo reports a kill as :exit -1; the JVM would say 137. Neither is
        ;; an exit code the command chose, so koine returns nil on both.
        (if (:timed-out? r)
          {:out (:out r) :err (:err r) :exit nil :timed-out? true}
          {:out (:out r) :err (:err r) :exit (:exit r) :timed-out? false}))

      :default
      (throw (ex-info "koine.process/sh: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))


;; ------------------------------------------------------------------- Child
;;
;; A child is a PLAIN MAP of closures — {:send-line! :read-line! :alive?
;; :close!} — and the four fns below just apply them. It was a `defprotocol` +
;; `reify` until 2026-07-31, and a host turned up with `defprotocol` but no
;; `reify`/`deftype`/`defrecord`/`extend-type` at all, so the protocol could be
;; declared there and never implemented.
;;
;; A map of closures is the portable object: it needs nothing but `fn` and
;; `get`. koine.server's handle already works this way for the same reason, and
;; so does cljgo's own cljg.process/spawn. The public API is unchanged —
;; `(send-line! child "x")` reads and behaves exactly as before.

(defn send-line!
  "Write `s` + newline to the child's stdin and flush. Returns nil."
  [child s] ((:send-line! child) s))

(defn read-line!
  "Block for one line from the child's stdout, WITHOUT the terminator.
  nil at EOF."
  [child] ((:read-line! child)))

(defn alive?
  "True while the child is running."
  [child] ((:alive? child)))

(defn close!
  "Close the child's stdin, wait for it to exit, and return the exit code.

  This is the POLITE shutdown and it WAITS: a child that ignores its stdin
  closing will hang here forever. When you need a guarantee rather than a
  request, use `kill!`."
  [child] ((:close! child)))

(defn kill!
  "Force-terminate the child. Returns nil — always, on both hosts.

  Returns nil rather than an exit code on purpose: a killed process did not
  choose one. The JVM would report 137 and Go -1, and koine does not invent
  agreement between two host-specific numbers. Ask `alive?` if you need to know
  it is gone.

  This is also the way OUT of a blocked `read-line!`. A reader parked on a hung
  peer cannot be interrupted portably, but killing the child closes its stdout,
  so the parked `read-line!` hits EOF and returns nil. One mechanism ends a
  runaway command and frees a stuck transport — asked for by the toolnexus port
  as two separate things, 2026-07-31."
  [child] ((:kill! child)))

;; ------------------------------------------------------------------ stderr
;;
;; A child's stderr MUST be drained, whether or not anyone reads it. It is not a
;; convenience: an undrained pipe fills its OS buffer (typically 64 KiB) and then
;; the CHILD BLOCKS ON WRITE — forever. The symptom is a peer that completes its
;; handshake and then goes silent, with nothing in any log to explain it, which
;; is indistinguishable from a hung peer and is one of the worst things to debug.
;; Verbose MCP servers hit it. Reported by the toolnexus port, 2026-07-31.
;;
;; So a background reader drains it always, into a BOUNDED ring — a chatty
;; server must not grow the caller's heap — and `stderr-lines` hands back what is
;; there. `future` is the concurrency primitive because it is the one that exists
;; on every host that has `spawn` (JVM and cljgo, verified 2026-07-31).

(def ^:private stderr-keep
  "How many trailing stderr lines a child retains. Enough to explain a crash,
  small enough that a server logging in a loop cannot exhaust memory."
  200)

(defn- ring-conj
  "Append to a bounded vector, dropping from the front. Pure."
  [v line]
  (let [v (conj (or v []) line)]
    (if (> (count v) stderr-keep)
      (subvec v (- (count v) stderr-keep))
      v)))

(defn- drain-into!
  "Read `read-line-fn` to EOF on a background thread, appending each line to the
  `sink` atom's ring. Returns nil immediately. Never throws into the caller: the
  child dying mid-read is the normal way this ends."
  [sink read-line-fn]
  (run-async!
   (fn []
     (loop []
       (when-let [line (read-line-fn)]
         (swap! sink ring-conj line)
         (recur)))))
  nil)

(defn stderr-lines
  "The child's most recent stderr lines (up to 200), oldest first, as a vector.

  Always available — stderr is drained from the moment the child starts, so this
  is a snapshot of a buffer that is being filled for you, not a read that could
  block. Returns [] when the child has written nothing."
  [child]
  (if-let [f (:stderr-lines child)] (f) []))

(defn spawn
  "Start `command` (a vector) as a LONG-LIVED child with piped stdin/stdout and
  return a child handle — a map of closures, see above. This is what a
  line-delimited JSON-RPC transport (MCP stdio) requires; `sh` cannot express it.

  The child's STDERR is drained from the moment it starts — see the comment
  above; not draining it deadlocks the child once the pipe buffer fills. Read it
  with `stderr-lines`.

  opts: :dir :env"
  ([command] (spawn command {}))
  ([command {:keys [dir env]}]
   #?(:clj
      (let [pb  (ProcessBuilder. ^java.util.List (vec (map str command)))
            _   (when dir (.directory pb (java.io.File. ^String dir)))
            _   (when env (let [m (.environment pb)]
                            (doseq [[k v] env] (.put m (str k) (str v)))))
            p   (.start pb)
            out (java.io.OutputStreamWriter. (.getOutputStream p) "UTF-8")
            in  (java.io.BufferedReader.
                  (java.io.InputStreamReader. (.getInputStream p) "UTF-8"))
            err (java.io.BufferedReader.
                  (java.io.InputStreamReader. (.getErrorStream p) "UTF-8"))
            sink (atom [])]
        (drain-into! sink (fn [] (.readLine err)))
        {:send-line!   (fn [s] (.write out (str s "\n")) (.flush out) nil)
         :read-line!   (fn [] (.readLine in))
         :alive?       (fn [] (.isAlive p))
         :stderr-lines (fn [] @sink)
         :kill!        (fn [] (.destroyForcibly p) (.waitFor p) nil)
         :close!       (fn [] (.close out) (.waitFor p))})

      :cljgo
      ;; cljg.process/spawn hands back live cljg.stream handles — {:in :out :err
      ;; :wait :kill}. Deliberately NOT `require-go '[os/exec]`: raw interop only
      ;; links AOT, and a host-returned value there rides cljgo's
      ;; nil-substituting build pass, where (.StdinPipe cmd) dies at BUILD time.
      (let [p      (cljg.process/spawn (vec (map str command))
                                       (cond-> {}
                                         dir (assoc :dir dir)
                                         env (assoc :env env)))
            exited (atom nil)
            sink   (atom [])]
        (drain-into! sink (fn [] (cljg.stream/read-line (:err p))))
        {:send-line!   (fn [s] (cljg.stream/write-line (:in p) (str s)) nil)
         :read-line!   (fn [] (cljg.stream/read-line (:out p)))
         :alive?       (fn [] (nil? @exited))
         :stderr-lines (fn [] @sink)
         :kill!        (fn [] ((:kill p))
                         (or @exited (reset! exited ((:wait p))))
                         nil)
         :close!       (fn [] (cljg.stream/close (:in p))
                         (or @exited (reset! exited ((:wait p)))))})


      :default
      (throw (ex-info "koine.process/spawn: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))
