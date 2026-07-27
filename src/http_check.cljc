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
      cases [["status 201" (:status r) 201]
             ["echoed body" (get-in body [:got :hello]) "world"]]
      fails (remove (fn [[_ g w]] (= g w)) cases)]
  (doseq [[l g w] fails] (println "  FAIL" l "got" (pr-str g) "want" (pr-str w)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass"))
  (try (srv/stop! hdl) (catch #?(:glj go/any :default Exception) _ nil)))
