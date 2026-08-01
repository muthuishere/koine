(require 'koine.http 'koine.server 'koine.json 'koine.time 'koine.process)
(alias 'h 'koine.http) (alias 'srv 'koine.server) (alias 'json 'koine.json)
(alias 'ktime 'koine.time) (alias 'proc 'koine.process)
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

      ;; :timeout-ms MUST BOUND THE CALL, and this asserts the CLOCK, not just
      ;; the classification.
      ;;
      ;; Everything above passes :timeout-ms only as a safety bound on a request
      ;; expected to fail some other way — so nothing here ever checked that a
      ;; deadline FIRES. It did not, on cljgo, from 0.1.0 until 2026-08-01:
      ;; koine passed cljgo the key `:timeout-ms` where its API takes
      ;; `:timeout`, cljgo ignored the unknown key silently, and a request with
      ;; a 150 ms budget ran the full 1500 ms and returned a plausible 200.
      ;;
      ;; Note what a value-only assertion would have done here: with the wrong
      ;; key there is no error to classify, so a case checking `:error` would
      ;; have looked like a missing feature rather than a broken one. The
      ;; elapsed bound is what makes this a test. Sixth time koine has been
      ;; caught asserting the shape of a result and never its timing —
      ;; `koine.host` even DECLARES :http/timeout, a claim nothing verified.
      ;;
      ;; Reported by the toolnexus port, which probed four key spellings against
      ;; a deliberately slow server instead of filing "the timeout is broken".
      slow-handler (fn [_] (ktime/sleep! 1500) {:status 200 :body "too late"})
      slow-hdl  (srv/serve slow-handler {:port 0})
      slow-url  (str "http://127.0.0.1:" (srv/port slow-hdl) "/slow")
      t0        (ktime/mono-ms)
      timed-out (h/request {:url slow-url :timeout-ms 150})
      elapsed   (- (ktime/mono-ms) t0)
      _         (srv/stop! slow-hdl)
      cases [["status 201" (:status r) 201]
             ["echoed body" (get-in body [:got :hello]) "world"]
             ["a real response is not failed" (h/failed? r) false]
             ["refused -> data, not a throw" (:status refused) nil]
             ["refused -> :connect-failed" (:error refused) :connect-failed]
             ["refused -> failed?" (h/failed? refused) true]
             ["refused carries the url" (:url refused) "http://127.0.0.1:1/x"]
             ["refused carries a message" (string? (:error-message refused)) true]
             ["bad hostname -> :dns" (:error bad-dns) :dns]

             ;; the deadline actually fires, and lands as the SAME data on both hosts
             ["slow server -> :timeout" (:error timed-out) :timeout]
             ["slow server -> failed?"  (h/failed? timed-out) true]
             ["slow server -> no status" (:status timed-out) nil]
             ;; the one that catches a silently-ignored option: the budget was
             ;; 150 ms against a 1500 ms server, so anything near 1500 means the
             ;; deadline was never applied, whatever the result map says
             ["timeout bounds the CALL" (< elapsed 1200) true]]
      fails (remove (fn [[_ g w]] (= g w)) cases)]
  (doseq [[l g w] fails] (println "  FAIL" l "got" (pr-str g) "want" (pr-str w)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass"))
  (try (srv/stop! hdl) (catch #?(:glj go/any :default Exception) _ nil)))
