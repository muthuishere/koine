(require 'koine.http 'koine.server 'koine.json)
(alias 'h 'koine.http) (alias 'srv 'koine.server) (alias 'json 'koine.json)
;; serve a tiny echo, then call it with koine.http — proves outbound on this host
(let [handler (fn [req] {:status 201
                         :headers {"x-seen" (or (get (:headers req) "x-token") "none")}
                         :body (json/write-str {:got (json/read-str (:body req))})})
      port (+ 19000 (rand-int 900))
      hdl (srv/serve handler {:port port})
      base (str "http://127.0.0.1:" (srv/port hdl))
      r (h/post-json (str base "/x") {"x-token" "abc"} (json/write-str {:hello "world"}))
      body (json/read-str (:body r))
      ;; TRANSPORT FAILURES ARE DATA, and the classification must match across
      ;; hosts — a retry policy that branches on :timeout vs :dns is only
      ;; portable if both hosts name the same failure the same way. The JVM and
      ;; cljgo disagreed natively (a bad hostname is a message-less
      ;; ConnectException on the JVM and "no such host" on cljgo); koine
      ;; normalises, and this is what holds it to that.
      refused (h/request {:url "http://127.0.0.1:1/x" :timeout-ms 2000})
      bad-dns (h/request {:url "http://nonexistent.invalid/x" :timeout-ms 3000})
      cases [["status 201" (:status r) 201]
             ["echoed body" (get-in body [:got :hello]) "world"]
             ["a real response is not failed" (h/failed? r) false]
             ["refused -> data, not a throw" (:status refused) nil]
             ["refused -> :connect-failed" (:error refused) :connect-failed]
             ["refused -> failed?" (h/failed? refused) true]
             ["refused carries the url" (:url refused) "http://127.0.0.1:1/x"]
             ["refused carries a message" (string? (:error-message refused)) true]
             ["bad hostname -> :dns" (:error bad-dns) :dns]]
      fails (remove (fn [[_ g w]] (= g w)) cases)]
  (doseq [[l g w] fails] (println "  FAIL" l "got" (pr-str g) "want" (pr-str w)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass"))
  (try (srv/stop! hdl) (catch #?(:glj go/any :default Exception) _ nil)))
