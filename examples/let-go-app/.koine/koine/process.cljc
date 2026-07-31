(ns koine.process
  "Subprocesses, portable.

  `sh` (run-to-completion) works on every host. `spawn` (a long-lived child
  with piped stdin/stdout) is the one MCP stdio transports need, and is the
  known gap on cljgo — see the cljgo work item in toolnexus ADR 0009."
  (:require [clojure.string :as cstr])
  #?(:cljgo (:require [cljg.io :as cio])))

;; cljgo: cljg.process (streaming spawn) and cljg.stream (the pipe handles) must
;; be interned before `spawn` is reachable. A top-level reader conditional with
;; no branch for the other hosts reads as nothing there, so no other dialect
;; pays for this (same pattern as koine.time).
#?(:cljgo (require '[cljg.process] '[cljg.stream]))

;; ------------------------------------------- helpers for the Go-hosted tiers
;;
;; Glojure and let-go both reach subprocesses through Go's os/exec, and neither
;; lets koine set the child's Dir or Env directly — Glojure rejects struct-field
;; assignment, and let-go's os/sh takes only an argv. Both therefore get :dir and
;; :env by wrapping the command in `sh -c`, which is why the quoting below is
;; shared rather than written twice.

(defn- shq
  "Single-quote `s` for POSIX sh: wrap it, and end/escape/reopen each embedded
  quote. Nothing inside survives as a metacharacter, so a path or an env value
  containing a space, a `$` or a `;` cannot become a second command."
  [s]
  (str "'" (cstr/replace (str s) "'" "'\\''") "'"))

(defn- wrap-cmd
  "The argv for `sh -c`, applying `dir` and `env` as shell built-ins. Returns
  nil when neither is needed, so the common case execs the binary directly."
  [command dir env in-file]
  (when (or dir (seq env) in-file)
    (let [parts (concat (when dir [(str "cd " (shq dir) " &&")])
                        (map (fn [[k v]] (str (name k) "=" (shq v))) env)
                        (map shq command)
                        (when in-file [(str "< " (shq in-file))]))]
      ["sh" "-c" (cstr/join " " parts)])))

#?(:glj
   (do
     (defn- glj-argv [command dir env]
       (or (wrap-cmd (map str command) dir env nil) (map str command)))

     (defn- drain-reader
       "Read a Go io.Reader to EOF and return it as a string. `bufio` is not in
       Glojure's default package map (see koine.stream), so the loop is manual:
       fixed-size Read into a slice, appended to a bytes.Buffer, until Read
       reports an error — which at EOF is io.EOF, not a failure."
       [rdr]
       (let [chunk (go/make (go/slice-of go/byte) 4096)
             buf   (bytes.NewBufferString "")]
         (loop []
           (let [r (.Read rdr chunk)
                 n (nth r 0)
                 e (nth r 1)]
             (when (pos? n) (.Write buf (go/slice chunk 0 n)))
             (if e (.String buf) (recur))))))))

#?(:lg
   (defn- lg-argv
     "argv for let-go's os/sh. `in` is written to a temp file and redirected,
     because os/sh's own :in option does not reach the child."
     [command in dir env]
     (if-let [wrapped (wrap-cmd (map str command) dir env
                                (when in
                                  (let [f (str (os/temp-dir) "/koine-stdin-" (System/currentTimeMillis))]
                                    (spit f in)
                                    f)))]
       wrapped
       (map str command))))

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
      :glj
      ;; Go's os/exec, driven through PIPES rather than struct fields: Glojure
      ;; rejects `(set! (.-Stdout c) …)` and `(set! (.-Dir c) …)` outright
      ;; ("RTEvalError" — struct field assignment is not supported), so :dir and
      ;; :env are applied by wrapping the command in `sh -c` instead, and the
      ;; three streams are read off StdinPipe/StdoutPipe/StderrPipe, which do
      ;; work. Same reason koine.stream hand-rolls its chunking on this host.
      (let [c      (apply os:exec.Command (glj-argv command dir env))
            stdin  (nth (.StdinPipe c) 0)
            stdout (nth (.StdoutPipe c) 0)
            stderr (nth (.StderrPipe c) 0)]
        (.Start c)
        (when in
          (.Write stdin (.Bytes (bytes.NewBufferString (str in)))))
        (.Close stdin)                       ; EOF, or the child waits forever
        (let [out (drain-reader stdout)
              err (drain-reader stderr)]
          (.Wait c)
          {:out out :err err :exit (.ExitCode (.ProcessState c))}))

      :lg
      ;; let-go's os/sh returns #os/ShellResult{:exit :out :err} directly. Its
      ;; :in option is accepted and then IGNORED (measured 2026-07-31: the child
      ;; sees empty stdin), and os/with-stdin wants an *exec.Cmd rather than the
      ;; argv os/sh takes — so :in, :dir and :env all ride an `sh -c` wrapper.
      (let [res (apply os/sh (lg-argv command in dir env))]
        {:out (:out res) :err (:err res) :exit (:exit res)})

      :default
      (throw (ex-info "koine.process/sh: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))


;; ------------------------------------------------------------------- Child
;;
;; A child is a PLAIN MAP of closures — {:send-line! :read-line! :alive?
;; :close!} — and the four fns below just apply them. It was a `defprotocol` +
;; `reify` until 2026-07-31, which cost the whole capability on Glojure: that
;; host has `defprotocol` but NOT `reify`, `deftype`, `defrecord` or
;; `extend-type` (all four answer RTEvalError), so a protocol there can be
;; declared and never implemented. Its os/exec pipes work perfectly.
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
;; on every host that has `spawn` (JVM, cljgo, Glojure — verified 2026-07-31).

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

      :glj
      ;; Go's os/exec pipes. The line buffering is hand-rolled because `bufio` is
      ;; not in Glojure's default package map (see koine.stream): read 4 KiB at a
      ;; time into a pending buffer and hand back one complete line per call,
      ;; keeping the tail. Splitting on the BYTE 0x0A is safe — it cannot occur
      ;; inside a multi-byte UTF-8 sequence — whereas decoding a partial chunk to
      ;; a string first would corrupt a non-ASCII line straddling two reads.
      (let [c      (apply os:exec.Command (glj-argv command dir env))
            stdin  (nth (.StdinPipe c) 0)
            stdout (nth (.StdoutPipe c) 0)
            stderr (nth (.StderrPipe c) 0)
            sink   (atom [])
            pend   (bytes.NewBufferString "")
            chunk  (go/make (go/slice-of go/byte) 4096)
            done   (atom nil)
            buffered-line!
            (fn []
              (let [lr   (.ReadBytes pend 10)
                    bs   (nth lr 0)
                    miss (nth lr 1)]
                (if-not miss
                  (.String (bytes.NewBuffer (go/slice bs 0 (dec (go/len bs)))))
                  (do (when (pos? (go/len bs)) (.Write pend bs))
                      nil))))]
        (.Start c)
        ;; Drain stderr on its own reader — same 4 KiB chunking as stdout, since
        ;; `bufio` is unreachable here too. Without this the child blocks once
        ;; the pipe buffer fills, exactly as on the other hosts.
        (let [echunk (go/make (go/slice-of go/byte) 4096)
              epend  (bytes.NewBufferString "")]
          (drain-into! sink
                       (fn []
                         (loop []
                           (let [lr   (.ReadBytes epend 10)
                                 bs   (nth lr 0)
                                 miss (nth lr 1)]
                             (if-not miss
                               (.String (bytes.NewBuffer (go/slice bs 0 (dec (go/len bs)))))
                               (do (when (pos? (go/len bs)) (.Write epend bs))
                                   (let [r (.Read stderr echunk)
                                         n (nth r 0)
                                         e (nth r 1)]
                                     (when (pos? n) (.Write epend (go/slice echunk 0 n)))
                                     (cond
                                       (pos? n) (recur)
                                       e        (let [tail (.String epend)]
                                                  (when (not= "" tail) tail))
                                       :else    (recur))))))))))
        {:send-line! (fn [s]
                       (.Write stdin (.Bytes (bytes.NewBufferString (str s "\n"))))
                       nil)
         :read-line! (fn []
                       (loop []
                         (if-let [line (buffered-line!)]
                           line
                           (let [r (.Read stdout chunk)
                                 n (nth r 0)
                                 e (nth r 1)]
                             (when (pos? n) (.Write pend (go/slice chunk 0 n)))
                             (cond
                               (pos? n) (recur)
                               ;; EOF: a final line with no terminator counts
                               e        (let [tail (.String pend)]
                                          (when (not= "" tail) tail))
                               :else    (recur))))))
         :alive?     (fn [] (nil? @done))
         :close!     (fn []
                       (.Close stdin)
                       (.Wait c)
                       (or @done (reset! done (.ExitCode (.ProcessState c)))))})

      :default
      ;; let-go lands here. Its os/exec returns an *exec.Cmd, but nothing in the
      ;; io/os/unix namespaces reaches that Cmd's stdin/stdout pipes from
      ;; Clojure, and os/sh is run-to-completion — so there is no honest
      ;; streaming route. Throws rather than faking one (rule 2); let-go is
      ;; tier 3 and never gates a release (PORTING.md).
      (throw (ex-info "koine.process/spawn: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))
