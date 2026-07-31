(ns koine.http
  "Outbound HTTP, portable. One request shape, one response shape, every host."
  (:require [clojure.string :as str])
  #?(:cljgo (:require [cljg.net.http :as gohttp]))
  #?(:lg (:require [http])))


(declare -request*)

;; ------------------------------------------------------------ failure shape
;;
;; A transport failure is a RESULT, not an exception — the same call
;; koine.process/sh already makes about a non-zero exit. A client loop's retry
;; policy has to branch on WHY a request failed (retry a timeout, do not retry a
;; bad hostname), and the host-native answers cannot support that portably:
;;
;;   JVM    java.net.ConnectException / java.net.http.HttpTimeoutException — real
;;          distinct classes, but NAMING one is Java interop, so portable code
;;          cannot dispatch on them.
;;   cljgo  all three are *fmt.wrapError; only ex-message tells them apart.
;;   glj    catching at all needs `go/error` rather than `Throwable`, so even the
;;          try/catch around the probe would be host-specific.
;;
;; So koine classifies once, here, and hands back data. Measured 2026-07-31 on
;; JVM and cljgo against a refused port, a hung server and a bad hostname.

(defn- classify
  "A transport error message -> :timeout | :dns | :connect-failed | :transport.

  Substring matching, deliberately: the host error TYPES cannot be named
  portably (that is the whole problem), so the wording each host actually emits
  is the only signal available. Unrecognised text is :transport — never a guess."
  [msg]
  (let [m    (str/lower-case (str msg))
        has? (fn [& ss] (boolean (some (fn [x] (str/includes? m x)) ss)))]
    (cond
      (has? "timed out" "timeout" "deadline exceeded")       :timeout
      (has? "no such host" "unknown host" "name resolution"
            "nodename nor servname" "no address")            :dns
      (has? "connection refused" "connect:" "unreachable"
            "connection reset" "broken pipe" "econnrefused") :connect-failed
      :else                                                  :transport)))

(defn- host-kind
  "The failure kind read from the host EXCEPTION rather than its text, where the
  host has real types. Only the JVM does — and its message is useless precisely
  where it matters: a bad hostname surfaces as `java.net.ConnectException` with
  no text at all, which would classify as :transport while cljgo (whose message
  says \"no such host\") says :dns. Walking the cause chain fixes the one
  divergence measured between the two supported hosts.

  Java interop is legal here: this is inside a :clj branch, which is the whole
  point of the seam."
  [e]
  #?(:clj (let [names (loop [t e, acc []]                  ; the whole cause chain
                        (if (or (nil? t) (> (count acc) 12))
                          acc
                          (recur (.getCause t) (conj acc (.getName (class t))))))
                any?  (fn [n] (boolean (some (fn [x] (= x n)) names)))]
            (cond
              (any? "java.net.UnknownHostException")             :dns
              ;; java.net.http wraps a bad hostname as ConnectException with a
              ;; NIL message, and the real cause is UnresolvedAddressException
              ;; three links down — so without this the JVM says :connect-failed
              ;; where cljgo says :dns. Measured 2026-07-31.
              (any? "java.nio.channels.UnresolvedAddressException") :dns
              (any? "java.net.http.HttpConnectTimeoutException") :timeout
              (any? "java.net.http.HttpTimeoutException")        :timeout
              (any? "java.net.SocketTimeoutException")           :timeout
              (any? "java.net.ConnectException")                 :connect-failed
              (any? "java.net.NoRouteToHostException")           :connect-failed
              :else nil))
     :default nil))

(defn failed?
  "True when `res` is a TRANSPORT failure rather than an HTTP response.

  A 404 or a 500 is not a failure — it is an answer, and `:status` carries it."
  [res]
  (some? (:error res)))

(defn request
  "Perform an HTTP request.

  req: {:method :post :url \"…\" :headers {\"k\" \"v\"} :body \"…\" :timeout-ms 30000}
  ->   {:status 200 :headers {…} :body \"…\"}

  NEVER throws on a transport failure. It returns one:

  ->   {:status nil :error :timeout | :dns | :connect-failed | :transport
        :error-message \"…\" :url \"…\"}

  A retry policy has to branch on which failure it was, and no portable `catch`
  can tell them apart — see the comment above. `failed?` is the predicate; a
  non-2xx status is NOT a failure and arrives with `:status` set as usual.

  Header values are passed through verbatim and never logged — they routinely
  carry credentials."
  [req]
  (try
    (-request* req)
    (catch #?(:glj go/error :default Throwable) e
      ;; `ex-message` is not portable: on Glojure it dies with "no such field or
      ;; method on *runtime.RTEvalError: getMessage", because what is thrown
      ;; there is a Go error, not an ExceptionInfo. `str` is the only spelling
      ;; every host answers.
      ;; `ex-message` is not portable: on Glojure it dies with "no such field or
      ;; method on *runtime.RTEvalError: getMessage", because what is thrown
      ;; there is a Go error rather than an ExceptionInfo. `.Error()` is how that
      ;; host yields text — `(str e)` gives "#object[*url.Error]", which would
      ;; classify everything as :transport.
      (let [msg #?(:glj (try (.Error e) (catch go/error _ (str e)))
                   :default (or (ex-message e) (str e)))]
        {:status        nil
         :error         (or (host-kind e) (classify msg))
         :error-message msg
         :url           (:url req)}))))

(defn- -request*
  "The host call. Throws on a transport failure; `request` turns that into data."
  [{:keys [method url headers body timeout-ms]
    :or   {method :get timeout-ms 30000}}]
  #?(:clj
     (let [client  (-> (java.net.http.HttpClient/newBuilder)
                       (.connectTimeout (java.time.Duration/ofMillis timeout-ms))
                       .build)
           builder (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
           _       (doseq [[k v] headers] (.header builder (name k) (str v)))
           pub     (if body
                     (java.net.http.HttpRequest$BodyPublishers/ofString body)
                     (java.net.http.HttpRequest$BodyPublishers/noBody))
           req     (-> builder
                       (.method (str/upper-case (name method)) pub)
                       (.timeout (java.time.Duration/ofMillis timeout-ms))
                       .build)
           res     (.send client req (java.net.http.HttpResponse$BodyHandlers/ofString))]
       {:status  (.statusCode res)
        :body    (.body res)
        :headers (into {} (map (fn [[k v]] [k (first v)]) (.map (.headers res))))})

     :cljgo
     (let [r (gohttp/request (cond-> {:method method :url url :timeout-ms timeout-ms}
                               headers (assoc :headers headers)
                               body    (assoc :body body)))]
       {:status (:status r) :body (:body r) :headers (:headers r)})

     :lg
     ;; let-go's http/request takes the same shape koine does. NOTE: an
     ;; empty-but-present :headers {} panics inside the host (nil-pointer at
     ;; pkg/rt/http.go:134 — {} is neither NIL nor walkable there), so the key
     ;; is omitted entirely rather than passed empty.
     (let [r (http/request (cond-> {:method method :url url}
                             (seq headers) (assoc :headers headers)
                             body          (assoc :body body)))]
       {:status (:status r) :body (:body r) :headers (or (:headers r) {})})

     :glj
     ;; Go's net/http directly. NewRequest wants an io.Reader body, and Go
     ;; multi-returns arrive as a [value error] vector.
     (let [rdr  (strings.NewReader (or body ""))
           pair (net:http.NewRequest (str/upper-case (name method)) url rdr)
           req  (nth pair 0)]
       (when-let [e (nth pair 1)]
         (throw (ex-info (.Error e) {:url url})))
       (doseq [[k v] headers] (.Set (.Header req) (name k) (str v)))
       (let [resp-pair (.Do net:http.DefaultClient req)
             resp      (nth resp-pair 0)]
         (when-let [e (nth resp-pair 1)]
           ;; `.Error()`, not `(str e)`: a Go error stringifies to
           ;; "#object[*url.Error]" on Glojure, which classifies as :transport
           ;; and tells the caller nothing. .Error() is the real text.
           (throw (ex-info (.Error e) {:url url})))
         (let [buf (bytes.NewBuffer (go/make (go/slice-of go/byte) 0))]
           (io.Copy buf (.Body resp))
           (.Close (.Body resp))
           {:status  (.StatusCode resp)
            :body    (.String buf)
            :headers {}})))

     :default
     (throw (ex-info "koine.http/request: no implementation for this host; add a branch in koine/http.cljc"
                     {:url url}))))

(defn post-json
  "POST a pre-encoded JSON string. Convenience over `request`."
  [url headers json-body]
  (request {:method :post :url url :body json-body
            :headers (merge {"content-type" "application/json"} headers)}))
