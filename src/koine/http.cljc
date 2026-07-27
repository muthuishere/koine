(ns koine.http
  "Outbound HTTP, portable. One request shape, one response shape, every host."
  (:require [clojure.string :as str])
  #?(:cljgo (:require [cljg.net.http :as gohttp]))
  #?(:lg (:require [http])))

(defn request
  "Perform an HTTP request.

  req: {:method :post :url \"…\" :headers {\"k\" \"v\"} :body \"…\" :timeout-ms 30000}
  ->   {:status 200 :headers {…} :body \"…\"}

  Header values are passed through verbatim and never logged — they routinely
  carry credentials."
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
         (throw (ex-info (str "koine.http/request: " e) {:url url})))
       (doseq [[k v] headers] (.Set (.Header req) (name k) (str v)))
       (let [resp-pair (.Do net:http.DefaultClient req)
             resp      (nth resp-pair 0)]
         (when-let [e (nth resp-pair 1)]
           (throw (ex-info (str "koine.http/request: " e) {:url url})))
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
