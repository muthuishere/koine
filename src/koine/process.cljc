(ns koine.process
  "Subprocesses, portable.

  `sh` (run-to-completion) works on every host. `spawn` (a long-lived child
  with piped stdin/stdout) is the one MCP stdio transports need, and is the
  known gap on cljgo — see the cljgo work item in toolnexus ADR 0009."
  #?(:cljgo (:require [cljg.io :as cio])))

;; cljgo: cljg.process (streaming spawn) and cljg.stream (the pipe handles) must
;; be interned before `spawn` is reachable. A top-level reader conditional with
;; no branch for the other hosts reads as nothing there, so no other dialect
;; pays for this (same pattern as koine.time).
#?(:cljgo (require '[cljg.process] '[cljg.stream]))

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
      ;; CLOSED 2026-07-30: cljgo grew `cljg.process/spawn`, a Clojure-shaped
      ;; wrapper over os/exec's StdinPipe/StdoutPipe that hands back live
      ;; cljg.stream handles — {:in :out :err :wait :kill}. Deliberately NOT
      ;; `require-go '[os/exec]`: raw interop only links AOT, and a host-returned
      ;; value there rides cljgo's nil-substituting build-discovery pass
      ;; (`(.StdinPipe cmd)` dies at BUILD time on nil). This route is portable
      ;; Clojure, so it behaves identically under `cljgo run` and `cljgo build`.
      ;;
      ;; `alive?` has no direct shim, so it is tracked here: :wait blocks and
      ;; yields the exit code, and once it has returned the child is done.
      (let [p      (cljg.process/spawn (vec (map str command))
                                       (cond-> {}
                                         dir (assoc :dir dir)
                                         env (assoc :env env)))
            exited (atom nil)
            wait!  (fn [] (or @exited (reset! exited ((:wait p)))))]
        (reify Child
          (send-line! [_ s] (cljg.stream/write-line (:in p) (str s)) nil)
          (read-line! [_]   (cljg.stream/read-line (:out p)))
          (alive?     [_]   (nil? @exited))
          (close!     [_]   (cljg.stream/close (:in p)) (wait!))))

      :default
      (throw (ex-info "koine.process/spawn: no implementation for this host; add a branch in koine/process.cljc"
                      {:command command})))))
