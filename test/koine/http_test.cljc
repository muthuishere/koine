(ns koine.http-test
  "JVM-side unit suite for koine.http. The cross-host run is src/http_check.cljc.

  Every request goes to a REAL socket served by koine.server on an
  OS-assigned port — no network, no mock. A mocked client would assert the
  mock; the thing under test is a host HTTP stack."
  (:require [clojure.test :refer [deftest is testing]]
            [koine.http :as http]
            [koine.server :as server]))

(defn- with-server
  "Start on an OS-assigned port, run f with the base url, always stop."
  [handler f]
  (let [h (server/serve handler {:port 0})]
    (try (f (str "http://127.0.0.1:" (server/port h)))
         (finally (server/stop! h)))))

(deftest get-is-the-default-method
  (let [seen (atom nil)]
    (with-server (fn [req] (reset! seen req) {:body "ok"})
      (fn [url]
        (let [res (http/request {:url (str url "/mcp")})]
          (is (= 200 (:status res)))
          (is (= "ok" (:body res)))
          (is (= "GET" (clojure.string/upper-case (str (name (:method @seen)))))))))))

(deftest status-is-passed-through-not-thrown
  (testing "a 404 or 500 is a value — errors belong to the caller"
    (with-server (fn [_] {:status 404 :body "nope"})
      (fn [url]
        (let [res (http/request {:url (str url "/mcp")})]
          (is (= 404 (:status res)))
          (is (= "nope" (:body res))))))
    (with-server (fn [_] {:status 500 :body "boom"})
      (fn [url]
        (is (= 500 (:status (http/request {:url (str url "/mcp")}))))))))

(deftest post-sends-the-body
  (let [seen (atom nil)]
    (with-server (fn [req] (reset! seen req) {:body (str "echo:" (:body req))})
      (fn [url]
        (let [res (http/request {:method :post :url (str url "/mcp") :body "{\"a\":1}"})]
          (is (= "echo:{\"a\":1}" (:body res)))
          (is (= "{\"a\":1}" (:body @seen))))))))

(deftest request-headers-reach-the-server
  (let [seen (atom nil)]
    (with-server (fn [req] (reset! seen req) {:body "ok"})
      (fn [url]
        (http/request {:url (str url "/mcp")
                       :headers {"x-probe" "1" "authorization" "Bearer tok"}})
        (let [h (:headers @seen)]
          (is (= "1" (get h "x-probe")))
          (testing "a credential header is passed through verbatim"
            (is (= "Bearer tok" (get h "authorization")))))))))

(deftest response-headers-are-a-flat-string-map
  (with-server (fn [_] {:headers {"content-type" "application/json"
                                  "x-answer" "42"}
                        :body "{}"})
    (fn [url]
      (let [h (:headers (http/request {:url (str url "/mcp")}))]
        (is (map? h))
        (is (every? string? (vals h)))
        (testing "one value per name, not a vector"
          (is (= "42" (get h "x-answer"))))))))

(deftest utf8-survives-both-directions
  (let [seen (atom nil)]
    (with-server (fn [req] (reset! seen req) {:body "réponse ☃"})
      (fn [url]
        (let [res (http/request {:method :post :url (str url "/mcp")
                                 :body "requête ☃"})]
          (is (= "requête ☃" (:body @seen)))
          (is (= "réponse ☃" (:body res))))))))

(deftest post-json-sets-the-content-type
  (let [seen (atom nil)]
    (with-server (fn [req] (reset! seen req) {:body "ok"})
      (fn [url]
        (http/post-json (str url "/mcp") {"x-probe" "1"} "{\"a\":1}")
        (is (= "application/json" (get (:headers @seen) "content-type")))
        (testing "caller headers are merged, not dropped"
          (is (= "1" (get (:headers @seen) "x-probe"))))))))

(deftest post-json-lets-the-caller-override-content-type
  (let [seen (atom nil)]
    (with-server (fn [req] (reset! seen req) {:body "ok"})
      (fn [url]
        (http/post-json (str url "/mcp") {"content-type" "application/json-rpc"} "{}")
        (is (= "application/json-rpc" (get (:headers @seen) "content-type")))))))

(deftest an-empty-response-body-is-empty-string
  (with-server (fn [_] {:status 204 :body ""})
    (fn [url]
      (let [res (http/request {:url (str url "/mcp")})]
        (is (= 204 (:status res)))
        (is (= "" (:body res)))))))

(deftest a-transport-failure-is-data-not-an-exception
  (testing "an unroutable port returns a classified failure, never a fabricated status"
    (let [res (http/request {:url "http://127.0.0.1:1/mcp" :timeout-ms 2000})]
      (is (nil? (:status res)))
      (is (= :connect-failed (:error res)))
      (is (string? (:error-message res)))
      (is (= "http://127.0.0.1:1/mcp" (:url res)))
      (is (http/failed? res)))))

(deftest a-bad-hostname-classifies-as-dns
  (testing "not :connect-failed — the JVM buries UnresolvedAddressException under
  a message-less ConnectException while cljgo says 'no such host'; koine
  normalises both so a retry policy can skip what will never resolve"
    (let [res (http/request {:url "http://nonexistent.invalid/x" :timeout-ms 3000})]
      (is (= :dns (:error res))))))

(deftest a-real-response-is-not-failed
  (with-server (fn [_] {:status 500 :body "boom"})
    (fn [url]
      (let [res (http/request {:url (str url "/mcp")})]
        (testing "a 500 is an ANSWER, not a transport failure"
          (is (= 500 (:status res)))
          (is (not (http/failed? res)))
          (is (nil? (:error res))))))))
