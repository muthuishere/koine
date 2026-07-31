(ns koine.process
  "Subprocesses, portable.

  `sh` runs a command to completion; `spawn` keeps a long-lived child with piped
  stdin/stdout, which is what an MCP stdio transport needs and what `sh` cannot
  express."
  (:require [clojure.string :as cstr])
  #?(:cljgo (:require [cljg.io :as cio])))

;; cljgo: cljg.process (streaming spawn) and cljg.stream (the pipe handles) must
;; be interned before `spawn` is reachable. A top-level reader conditional with
;; no branch for the other hosts reads as nothing there, so no other dialect
;; pays for this (same pattern as koine.time).
#?(:cljgo (require '[cljg.process] '[cljg.stream]))

;; ------------------------------------------- helpers for the Go-hosted tiers
;;
;; :dir and :env are applied by wrapping the command in `sh -c` on hosts that
;; cannot set them on the child directly, so the quoting helper below is shared
;; rather than written twice.

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
  "Run `command` (a vector) to completion. Returns {:out :err :exit}.
  Never throws on a non-zero exit — that is a normal result.
  opts: :in (string on stdin) :dir :env"
  ([command] (sh command {}))
  ([command {:keys [in dir env] :as opts}]
   #?(:clj
      (let [pb (ProcessBuilder. ^java.util.List (vec (map str command)))
            _  (when dir (.directory pb (java.io.File. ^String dir)))
            _  (when env (let [m (.environment pb)]
                           (doseq [[k v] env] (.put m (str k) (str v)))))
            p  (.start pb)]
        (when in
          (with-open [os (.getOutputStream p)]
            (.write os (.getBytes ^String in "UTF-8"))))
        (let [out (slurp (.getInputStream p))
              err (slurp (.getErrorStream p))]
          {:out out :err err :exit (.waitFor p)}))
      :cljgo
      (let [r (cio/exec (vec (map str command)) (cond-> {}
                                                  in  (assoc :in in)
                                                  dir (assoc :dir dir)
                                                  env (assoc :env env)))]
        {:out (:out r) :err (:err r) :exit (:exit r)})

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
  "Close the child's stdin, wait for it to exit, and return the exit code."
  [child] ((:close! child)))

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
  (future
    (loop []
      (when-let [line (read-line-fn)]
        (swap! sink ring-conj line)
        (recur))))
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
         :close!       (fn [] (cljg.stream/close (:in p))
                         (or @exited (reset! exited ((:wait p)))))})


      :default
      (throw (ex-info "koine.process/spawn: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))
