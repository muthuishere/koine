(ns koine.server
  "Inbound HTTP, portable. One path, one handler fn, every host.

  Deliberately minimal. The use case is a JSON-RPC endpoint (toolnexus
  SPEC §7B/§7C): a request comes in on ONE path, a string body goes in, a
  string body comes out. There is no routing table, no middleware, no
  static files, no websockets, no TLS — every one of those multiplies the
  surface that has to agree across both hosts, and none of them is needed.

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

  Where a host cannot do something it throws a named, actionable error at
  the point of use rather than pretending."
  (:require [clojure.string :as str])
  #?(:cljgo (:require [bri.web.http :as bri])))

;; ---------------------------------------------------------------- shared
;; Everything below the seam is plain clojure.core so the two host
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
  blocking (those that go into a `future`) need it; the JVM
  and cljgo have bound the socket before `serve` returns."
  [n]
  #?(:clj   (Thread/sleep (long n))
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

  The default `:path` of \"/\" is a prefix/catch-all on both hosts and is
  the only value that behaves identically everywhere — cljgo's `serve`
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
  no-op, so teardown can be unconditional."
  [handle]
  (let [flag (:stopped handle)]
    (when-not (deref flag)
      ((:stop-fn handle))
      (swap! flag (fn [_] true))))
  nil)
