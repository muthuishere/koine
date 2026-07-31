;; mcp_check.cljc — a REAL MCP stdio handshake through koine.process/spawn.
;;
;; This is the workload the whole library was justified by (toolnexus spike S11):
;; a line-delimited JSON-RPC conversation with a long-lived child over its stdin
;; and stdout —
;;
;;   initialize -> notifications/initialized -> tools/list -> tools/call
;;
;; It exercises koine.process/spawn AND koine.json against a third-party server
;; that knows nothing about koine, so a host that fakes streaming fails here even
;; though it passes process_check.
;;
;; Needs npx on PATH and network access on first run (it fetches
;; @modelcontextprotocol/server-everything). SKIPs cleanly without npx, so
;; run-conformance.sh stays green on a box that has no node.
(require 'koine.process 'koine.json 'koine.host)
(alias 'proc 'koine.process)
(alias 'json 'koine.json)
(alias 'host 'koine.host)

;; Two reasons to skip, and they are different: no npx on the box (nothing to
;; talk to), or a host with no streaming child at all (see koine.host).
;; Neither is a failure; both are reported rather than hidden.
(def spawn? (host/supports? :process/spawn))
(def npx? (and spawn? (zero? (:exit (proc/sh ["sh" "-c" "command -v npx >/dev/null"])))))

(defn- rpc!
  "Send one JSON-RPC request and read lines until one carries a matching :id.
  Notifications and log lines the server interleaves are skipped, which is what
  makes this a transport test and not a two-line echo."
  [c id method params]
  (proc/send-line! c (json/write-str (cond-> {:jsonrpc "2.0" :id id :method method}
                                       params (assoc :params params))))
  (loop [guard 0]
    (when (< guard 200)
      (if-let [line (proc/read-line! c)]
        ;; `Throwable` (not a reader-conditional class name): cljgo's catch wants
        ;; a class-name SYMBOL and rejects a keyword, and Throwable is the one
        ;; spelling both hosts accept.
        (let [msg (try (json/read-str line) (catch Throwable _ nil))]
          (if (and (map? msg) (= id (:id msg)))
            msg
            (recur (inc guard))))
        nil))))

(defn- notify! [c method params]
  (proc/send-line! c (json/write-str (cond-> {:jsonrpc "2.0" :method method}
                                       params (assoc :params params)))))

(def result
  (when npx?
    (let [c (proc/spawn ["npx" "-y" "@modelcontextprotocol/server-everything"])]
      (try
        (let [init  (rpc! c 1 "initialize"
                          {:protocolVersion "2024-11-05"
                           :capabilities    {}
                           :clientInfo      {:name "koine-mcp-check" :version "0.1.0"}})
              _     (notify! c "notifications/initialized" nil)
              tools (rpc! c 2 "tools/list" {})
              names (set (map :name (:tools (:result tools))))
              call  (rpc! c 3 "tools/call" {:name "echo" :arguments {:message "koine ☃"}})
              text  (-> call :result :content first :text)]
          {:proto  (-> init :result :protocolVersion)
           :server (-> init :result :serverInfo :name)
           :names  names
           :echo   text})
        (finally (proc/close! c))))))

(def cases
  (if npx?
    [["initialize-proto"  (string? (:proto result))                    true]
     ["initialize-server" (boolean (seq (str (:server result))))       true]
     ["tools-list"        (contains? (:names result) "echo")           true]
     ["tools-several"     (> (count (:names result)) 1)                true]
     ;; the round trip that matters: a request sent AFTER two prior exchanges,
     ;; answered by a child that has stayed alive throughout
     ["tools-call-echo"   (boolean (re-find #"koine ☃" (str (:echo result)))) true]]
    [["skipped"           :skip                                        :skip]]))

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass"
                (cond (not spawn?) " (SKIP: no streaming subprocess on this host)"
                      (not npx?)   " (SKIP: no npx on PATH)"
                      :else        ""))))
