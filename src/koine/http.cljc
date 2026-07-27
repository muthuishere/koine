(ns koine.http
  "Outbound HTTP, portable. One request shape, one response shape, every host."
  (:require [clojure.string :as str])
  #?(:cljgo (:require [cljg.net.http :as gohttp])))

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

     :default
     (throw (ex-info "koine.http/request: no implementation for this host; add a branch in koine/http.cljc"
                     {:url url}))))

(defn post-json
  "POST a pre-encoded JSON string. Convenience over `request`."
  [url headers json-body]
  (request {:method :post :url url :body json-body
            :headers (merge {"content-type" "application/json"} headers)}))
