;; Inbound-HTTP conformance. Start a server, talk to it over a real socket,
;; stop it, prove the socket is gone. Run on every host: same file, same
;; assertions, and every host that cannot do a step says so by name.
(require 'koine.server)
(alias 'server 'koine.server)
(require '[clojure.string :as strs])

;; koine.http (outbound) only has :clj and :cljgo branches today, so the two
;; Go-hosted dialects use their own client here. That is a check-harness
;; detail, not a koine.server one.
#?(:clj (require '[koine.http :as kh]) :cljgo (require '[koine.http :as kh]))

(defn post!
  "POST a string body, return {:status :body}."
  [url body]
  #?(:clj   (select-keys (kh/post-json url {} body) [:status :body])
     :cljgo (select-keys (kh/post-json url {} body) [:status :body])
     :lg    (let [r (http/post url body {:content-type "application/json"})]
              {:status (:status r) :body (:body r)})
     :glj   (let [resp (first (net:http.Post url "application/json"
                                             (strings.NewReader body)))]
              {:status (.StatusCode resp)
               :body   (.String (bytes.NewBuffer (first (io.ReadAll (.Body resp)))))})
     :default (throw (ex-info "server_check/post!: no client for this host" {:url url}))))

;; :port 0 needs a way to read the OS-assigned port back; two hosts have none.
(def wanted-port #?(:clj 0 :cljgo 0 :default (+ 18000 (rand-int 4000))))

(def seen (atom nil))

(defn echo [req]
  (swap! seen (fn [_] (select-keys req [:method :path])))
  (if (= "bare" (:body req))
    ;; No :status, no :headers. Both defaults must fill in, and the
    ;; resulting EMPTY headers map must not reach the host verbatim —
    ;; let-go's handler panics on one (pkg/rt/http.go:123-137).
    {:body "bare-ok"}
    {:status 201
     :headers {"content-type" "text/plain"}
     :body (str "echo:" (:body req) ":" (get (:headers req) "x-probe" "-"))}))

(def results (atom []))
(defn check [label got want]
  (swap! results (fn [v] (conj v [label got want]))))

(def h (server/serve echo {:port wanted-port :path "/"}))
(def p (server/port h))
(check "port>0" (> p 0) true)
(when (zero? wanted-port) (check "port-0-assigned" (not= p 0) true))

(def r (post! (str "http://127.0.0.1:" p "/mcp") "{\"a\":1}"))
(check "status"  (:status r) 201)
(check "body"    (:body r)   "echo:{\"a\":1}:-")
(check "method"  (:method (deref seen)) :post)
(check "path"    (:path (deref seen))   "/mcp")

(def bare (post! (str "http://127.0.0.1:" p "/mcp") "bare"))
(check "default-status" (:status bare) 200)
(check "default-body"   (:body bare)   "bare-ok")

;; stop! is called TWICE on purpose — it must be idempotent.
;; A host that cannot stop must throw a NAMED error, not go quiet. That is a
;; declared capability gap, not a failure, so it is scored separately: only an
;; UNNAMED error counts as a broken stop!.
(def stop-outcome
  (try (do (server/stop! h) (server/stop! h) :stopped)
       #?(:glj (catch go/any e (str e))
          :default (catch Exception e (str e)))))

(def stop-unsupported?
  (and (string? stop-outcome)
       (some? (strs/index-of stop-outcome "koine.server/stop!: not supported"))))

(when-not stop-unsupported?
  (check "stop!" stop-outcome :stopped))

(when (= :stopped stop-outcome)
  (def after
    (try (:status (post! (str "http://127.0.0.1:" p "/mcp") "x"))
         #?(:glj (catch go/any _ :refused)
            :default (catch Exception _ :refused))))
  (check "closed" (not= after 201) true))

(let [rs    (deref results)
      fails (remove (fn [[_ got want]] (= got want)) rs)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (when stop-unsupported?
    (println "  n/a  stop! + closed — declared gap on this host, named error thrown"))
  (println (str (- (count rs) (count fails)) "/" (count rs) " pass")))
