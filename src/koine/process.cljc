(ns koine.process
  "Subprocesses, portable.

  `sh` (run-to-completion) works on every host. `spawn` (a long-lived child
  with piped stdin/stdout) is the one MCP stdio transports need, and is the
  known gap on cljgo — see the cljgo work item in toolnexus ADR 0009."
  #?(:cljgo (:require [cljg.io :as cio])))

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

(defprotocol Child
  "A running child process with open pipes."
  (send-line! [this s] "Write s + newline to the child's stdin and flush.")
  (read-line! [this]   "Block for one line from the child's stdout. nil at EOF.")
  (alive? [this]       "True while the child is running.")
  (close! [this]       "Close stdin, wait for exit, return the exit code."))

(defn spawn
  "Start `command` (a vector) as a LONG-LIVED child with piped stdin/stdout and
  return a Child. This is what a line-delimited JSON-RPC transport (MCP stdio)
  requires; `sh` cannot express it.

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
                  (java.io.InputStreamReader. (.getInputStream p) "UTF-8"))]
        (reify Child
          (send-line! [_ s] (.write out (str s "\n")) (.flush out) nil)
          (read-line! [_]   (.readLine in))
          (alive?     [_]   (.isAlive p))
          (close!     [_]   (.close out) (.waitFor p))))

      :cljgo
      ;; GAP (measured 2026-07-27): cljg.io/exec is run-to-completion — it takes
      ;; :in as a string and returns after the child exits, so it cannot hold a
      ;; conversation. `(require-go '[os/exec :as ex])` binds nothing in
      ;; interpreted mode either. Needs a streaming primitive in cljgo's cljg.io.
      (throw (ex-info (str "koine.process/spawn: cljgo has no streaming subprocess yet. "
                           "cljg.io/exec is run-to-completion. Tracked as cljgo work item 1 "
                           "in toolnexus ADR 0009.")
                      {:command command :host :cljgo}))

      :default
      (throw (ex-info "koine.process/spawn: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))
