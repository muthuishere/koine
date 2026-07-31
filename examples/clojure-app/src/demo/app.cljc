(ns demo.app
  "A small app built on koine — and the point of the example is what is NOT in
  this file: no reader conditional, no `java.` anything, no Go interop.

  It is one `.cljc` that the JVM and cljgo both run, and both get koine from the
  same Clojars artifact. Everything host-shaped is behind a koine call.

  What it does: reads a config from the environment, fetches a document over
  HTTP, records it to disk as bytes, and talks JSON-RPC to a subprocess — i.e.
  the four things `clojure.core` cannot do, plus JSON."
  (:require [koine.codec :as codec]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as t]))

;; ----------------------------------------------------------------- config

(defn config
  "Read the app's config from the environment, with defaults. `expand`
  interpolates ${VAR} so a value can reference another variable."
  []
  {:name    (env/get-env "DEMO_NAME" "demo")
   :workdir (env/expand (env/get-env "DEMO_WORKDIR" "/tmp/koine-demo"))
   :token   (env/get-env "DEMO_TOKEN")})          ; nil when unset, never ""

;; -------------------------------------------------------------- artifacts

(defn record!
  "Write `bytes-or-string` under the workdir as `name`, and return a receipt.

  Bytes, not text: an artifact may be an image or a gzip blob, and the text
  route would corrupt it. The receipt carries a base64 copy — the same encoding
  MCP uses for binary content — plus an ISO-8601 stamp."
  [{:keys [workdir]} name payload]
  (let [path (str workdir "/" name)
        bs   (if (string? payload) (codec/decode-bytes (codec/encode payload)) payload)]
    (fs/write-bytes path bs)
    {:path    path
     :size    (count (vec (fs/read-bytes path)))
     :base64  (codec/encode (fs/read-bytes path))
     :at      (t/iso-str)}))

(defn artifacts
  "Every recorded artifact, sorted — deterministic across hosts, which the
  underlying directory order is not."
  [{:keys [workdir]}]
  (fs/find-files workdir ".bin"))

;; ------------------------------------------------------- the child process
;;
;; A line-delimited JSON-RPC conversation with a long-lived child. This is the
;; shape MCP stdio uses, and it is why `spawn` exists: `sh` runs to completion
;; and cannot hold a conversation.

(defn rpc-client
  "Start `command` as a long-lived child and return a client fn:
  `(client method params)` sends one request and returns the parsed reply."
  [command]
  (let [child (proc/spawn command)
        n     (atom 0)]
    {:child child
     :call  (fn [method params]
              (let [id (swap! n inc)]
                (proc/send-line! child (json/write-str {:jsonrpc "2.0" :id id
                                                        :method method
                                                        :params params}))
                (json/read-str (proc/read-line! child))))
     :stop  (fn [] (proc/close! child))}))

(defn timed
  "Run `f`, returning {:value … :ms …}. Uses the monotonic clock — `now-ms` is
  wall clock and can step backwards mid-measurement."
  [f]
  (let [start (t/mono-ms)
        v     (f)]
    {:value v :ms (t/elapsed-ms start)}))

;; -------------------------------------------------------------------- run

(defn run
  "The whole demo, as data. Returns a map a test can assert on rather than
  printing — printing is the caller's business."
  []
  (let [cfg (config)]
    (proc/sh ["mkdir" "-p" (:workdir cfg)])
    (let [receipt (record! cfg "hello.bin" "hello ☃")
          echo    (timed (fn []
                           (let [{:keys [call stop]} (rpc-client ["cat"])]
                             (let [r (call "ping" {:seq 1})
                                   s (call "ping" {:seq 2})]   ; a SECOND turn
                               (stop)
                               [r s]))))]
      {:config    (dissoc cfg :token)
       :receipt   receipt
       :artifacts (artifacts cfg)
       :echo      (:value echo)
       :echo-ms   (:ms echo)})))

(defn -main [& _]
  (println (json/write-str (run))))
