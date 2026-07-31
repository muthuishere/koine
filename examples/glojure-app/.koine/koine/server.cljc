(ns koine.server
  "Inbound HTTP, portable. One path, one handler fn, every host.

  Deliberately minimal. The use case is a JSON-RPC endpoint (toolnexus
  SPEC §7B/§7C): a request comes in on ONE path, a string body goes in, a
  string body comes out. There is no routing table, no middleware, no
  static files, no websockets, no TLS — every one of those multiplies the
  surface that has to agree across four hosts, and none of them is needed.

      (def h (server/serve (fn [req] {:status 200 :body (:body req)})
                           {:port 0}))
      (server/port h)   ; => 54321
      (server/stop! h)  ; => nil, idempotent

  handler : request-map -> response-map
    request  {:method :post :path \"/\" :headers {\"content-type\" \"…\"} :body \"…\"}
    response {:status 200 :headers {…} :body \"…\"}   ; :status defaults to 200,
                                                      ; :headers to {}, :body to \"\"

  Normalised across hosts: `:method` is always a lower-case keyword,
  `:headers` keys are always lower-case strings with a single string value
  (first value wins on repeats), `:body` is always a string — never nil.

  Host support (measured 2026-07-27, see the porting notes at the bottom):

  | host    | serve | :port 0 | stop! | :host |
  |---------|-------|---------|-------|-------|
  | jvm     | yes   | yes     | yes   | yes   |
  | cljgo   | yes   | yes     | yes   | no    |
  | glojure | yes   | NO      | yes   | yes   |
  | let-go  | yes   | NO      | NO    | yes   |

  Where a host cannot do something it throws a named, actionable error at
  the point of use rather than pretending."
  (:require [clojure.string :as str])
  #?(:cljgo (:require [bri.web.http :as bri])))

;; ---------------------------------------------------------------- shared
;; Everything below the seam is plain clojure.core so the four host
;; branches only ever contain the socket work, never the shaping.

(defn- lower [s] (str/lower-case (str s)))

(defn- header-name [k] (if (keyword? k) (name k) (str k)))

(defn- normalize-response
  "Fill in the response defaults ONCE, here, so no host branch has to.
  A handler that returns nil or a partial map still produces a valid
  response, and every host sees the same three fully populated keys."
  [res]
  {:status  (or (:status res) 200)
   :headers (or (:headers res) {})
   :body    (str (or (:body res) ""))})

(defn- sleep-ms
  "Best-effort readiness pause. Only the two hosts whose serve call is
  blocking (let-go, Glojure — both go into a `future`) need it; the JVM
  and cljgo have bound the socket before `serve` returns."
  [n]
  #?(:clj   (Thread/sleep (long n))
     :lg    (sleep n)
     :glj   (time.Sleep (* n 1000000))
     :default nil))

(defn- handle*
  "The handle is a plain map — no deftype, no record. Records and host
  types are the two things guaranteed to differ across four runtimes; a
  map with a closure in it is identical everywhere."
  [bound-port host path stop-fn]
  {:port bound-port :host host :path path
   :stop-fn stop-fn :stopped (atom false)})

;; ------------------------------------------------------------------ api

(defn serve
  "Start an HTTP server that sends every request on `path` to `handler`.

  opts: {:port 8080          ; 0 = pick a free port, where the host allows it
         :host \"127.0.0.1\"
         :path \"/\"}          ; catch-all; see the caveat below

  Returns an opaque handle for `port` and `stop!`.

  The default `:path` of \"/\" is a prefix/catch-all on all four hosts and is
  the only value that behaves identically everywhere — let-go's `http/serve`
  takes no pattern at all, so a non-\"/\" path is NOT enforced there. The
  handler always receives `:path`, so filter in the handler if it matters."
  [handler {:keys [port host path] :or {port 0 host "127.0.0.1" path "/"}}]
  #?(:clj
     (let [srv  (com.sun.net.httpserver.HttpServer/create
                  (java.net.InetSocketAddress. ^String host (int port)) 0)
           pool (java.util.concurrent.Executors/newCachedThreadPool)]
       (.createContext
         srv ^String path
         (reify com.sun.net.httpserver.HttpHandler
           (handle [_ ex]
             (try
               (let [req  {:method  (keyword (lower (.getRequestMethod ex)))
                           :path    (.getPath (.getRequestURI ex))
                           :headers (reduce (fn [m [k v]] (assoc m (lower k) (first v)))
                                            {} (.getRequestHeaders ex))
                           :body    (slurp (.getRequestBody ex) :encoding "UTF-8")}
                     res  (normalize-response (handler req))
                     body (.getBytes ^String (:body res) "UTF-8")]
                 (doseq [[k v] (:headers res)]
                   (.add (.getResponseHeaders ex) (header-name k) (str v)))
                 ;; com.sun leaves the exchange hanging on a thrown handler,
                 ;; so unlike the three Go hosts the JVM must answer itself.
                 (.sendResponseHeaders ex (int (:status res)) (alength body))
                 (with-open [os (.getResponseBody ex)] (.write os body)))
               (catch Exception e
                 (try
                   (let [b (.getBytes (str "koine.server: handler error: " (.getMessage e))
                                      "UTF-8")]
                     (.sendResponseHeaders ex 500 (alength b))
                     (with-open [os (.getResponseBody ex)] (.write os b)))
                   (catch Exception _ nil)))
               (finally (.close ex))))))
       (.setExecutor srv pool)
       (.start srv)
       (handle* (.getPort (.getAddress srv)) host path
                (fn [] (.stop srv 0) (.shutdownNow pool) nil)))

     :cljgo
     ;; bri.web.http is cljgo's own Ring-shaped server over Go's net/http.
     ;; :block? false is the seam it already exposes for tests — it returns
     ;; {:port :stop} with the real bound port, which is exactly this API.
     ;; :middleware [] and :ops false strip bri's default stack (logging,
     ;; CORS, /healthz …) so koine's behaviour is the handler's, not bri's.
     (let [wrapped (fn [req]
                     (normalize-response
                       (handler {:method  (:request-method req)
                                 :path    (or (:uri req) path)
                                 :headers (or (:headers req) {})
                                 :body    (str (or (:body req) ""))})))
           s       (bri/serve [[path wrapped]]
                              {:port port :block? false :ops false :middleware []})]
       (handle* (:port s) host path (fn [] ((:stop s)) nil)))

     :glj
     ;; Glojure coerces an IFn to a Go func when the target parameter is a
     ;; func type (pkg/lang/apply.go:287), so a plain Clojure fn IS a
     ;; http.HandlerFunc. That is the whole trick; everything else is
     ;; ordinary Go interop.
     (let [_   (when (zero? port)
                 (throw (ex-info (str "koine.server/serve: :port 0 is not supported on Glojure — "
                                      "its default package set has no plain `net`, so no net.Listener "
                                      "can be created and the port picked by the OS cannot be read "
                                      "back; pass an explicit :port")
                                 {:host host})))
           mux (net:http.NewServeMux)
           srv (new net:http.Server)]
       (.HandleFunc
         mux path
         (fn [w r]
           ;; io.ReadAll is multi-return, which Glojure surfaces as [val err].
           (let [body (.String (bytes.NewBuffer (first (io.ReadAll (.Body r)))))
                 hs   (reduce (fn [m e]
                                ;; http.Header is map[string][]string
                                (assoc m (lower (first e)) (str (first (second e)))))
                              {} (seq (.Header r)))
                 res  (normalize-response
                        (handler {:method  (keyword (lower (.Method r)))
                                  :path    (.Path (.URL r))
                                  :headers hs
                                  :body    body}))]
             (doseq [[k v] (:headers res)]
               (.Set (.Header w) (header-name k) (str v)))
             (.WriteHeader w (:status res))
             (io.WriteString w (:body res)))))
       (set! (.Addr srv) (str host ":" port))
       (set! (.Handler srv) mux)
       (future (.ListenAndServe srv))
       (sleep-ms 200)
       (handle* port host path (fn [] (.Close srv) nil)))

     :lg
     ;; let-go ships http/serve, but it is http.ListenAndServe under the
     ;; hood (pkg/rt/http.go:177) — it blocks and hands back no server
     ;; value. So: run it in a future, and be honest that neither the
     ;; bound port nor a shutdown is reachable.
     (let [_ (when (zero? port)
               (throw (ex-info (str "koine.server/serve: :port 0 is not supported on let-go — "
                                    "http/serve wraps Go's http.ListenAndServe (let-go "
                                    "pkg/rt/http.go:177) and returns no server value, so the port "
                                    "picked by the OS cannot be read back; pass an explicit :port")
                               {:host host})))]
       (future
         (http/serve (fn [req]
                       (let [res (normalize-response
                                   (handler {:method  (:request-method req)
                                             :path    (:path req)
                                             :headers (or (:headers req) {})
                                             :body    (str (or (:body req) ""))}))]
                         ;; let-go's Handler PANICS on an empty-but-present
                         ;; :headers map — it checks `headers != NIL` and then
                         ;; walks the seq assuming entries exist
                         ;; (pkg/rt/http.go:123-137). Absent is the safe shape,
                         ;; and normalize-response always supplies the key.
                         (if (seq (:headers res)) res (dissoc res :headers))))
                     (str host ":" port)))
       (sleep-ms 200)
       (handle* port host path
                (fn []
                  (throw (ex-info (str "koine.server/stop!: not supported on let-go — http/serve "
                                       "wraps http.ListenAndServe (let-go pkg/rt/http.go:177) and "
                                       "exposes no server value to shut down; the server lives "
                                       "until the process exits")
                                  {:port port})))))

     :default
     (throw (ex-info "koine.server/serve: no implementation for this host; add a branch in koine/server.cljc"
                     {:port port :host host}))))

(defn port
  "The port the server is actually bound to. The reason `:port 0` is worth
  having: on the JVM and cljgo this is the OS-assigned port."
  [handle]
  (:port handle))

(defn stop!
  "Shut the server down. Returns nil. Idempotent — the second call is a
  no-op, so teardown can be unconditional.

  Throws on let-go, every time, because there is nothing to stop there;
  the flag is only set once the underlying shutdown actually succeeded, so
  an unsupported host stays loudly unsupported rather than going quiet
  after the first attempt."
  [handle]
  (let [flag (:stopped handle)]
    (when-not (deref flag)
      ((:stop-fn handle))
      (swap! flag (fn [_] true))))
  nil)
