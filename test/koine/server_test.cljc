(ns koine.server-test
  "Inbound HTTP over a REAL socket — no in-process shortcut. Every assertion
  here is part of the cross-host contract; the same checks run on the other
  three hosts from src/server_check.cljc."
  (:require [clojure.test :refer [deftest is testing]]
            [koine.http :as http]
            [koine.server :as server]))

(defn- with-server
  "Start on an OS-assigned port, run f with the base url, always stop."
  [handler f]
  (let [h (server/serve handler {:port 0})]
    (try (f (str "http://127.0.0.1:" (server/port h)) h)
         (finally (server/stop! h)))))

(deftest port-zero-binds-a-real-port
  (with-server (fn [_] {:body "ok"})
    (fn [_ h]
      (is (pos? (server/port h)) ":port 0 must report the port the OS assigned"))))

(deftest echoes-the-body-round-trip
  (let [seen (atom nil)]
    (with-server (fn [req]
                   (reset! seen req)
                   {:status 201
                    :headers {"content-type" "text/plain"}
                    :body (str "echo:" (:body req))})
      (fn [url _]
        (let [res (http/post-json (str url "/mcp") {"x-probe" "1"} "{\"a\":1}")]
          (is (= 201 (:status res)))
          (is (= "echo:{\"a\":1}" (:body res)))
          (testing "the request map koine hands the handler"
            (is (= :post (:method @seen)) ":method is a lower-case keyword")
            (is (= "/mcp" (:path @seen)))
            (is (= "{\"a\":1}" (:body @seen)))
            (is (= "1" (get (:headers @seen) "x-probe"))
                "header keys are lower-case strings")
            (is (= "application/json" (get (:headers @seen) "content-type")))))))))

(deftest response-defaults
  (testing "missing :status is 200, missing :body is empty — a bare map is valid"
    (with-server (fn [_] {:body "just a body"})
      (fn [url _]
        (let [res (http/post-json url {} "x")]
          (is (= 200 (:status res)))
          (is (= "just a body" (:body res)))))))
  (testing "a handler returning nil is still a well-formed 200"
    (with-server (fn [_] nil)
      (fn [url _]
        (let [res (http/post-json url {} "x")]
          (is (= 200 (:status res)))
          (is (= "" (:body res))))))))

(deftest any-method-reaches-the-handler
  (with-server (fn [req] {:body (name (:method req))})
    (fn [url _]
      (is (= "get" (:body (http/request {:method :get :url url}))))
      (is (= "post" (:body (http/post-json url {} "")))))))

(deftest stop-closes-the-port-and-is-idempotent
  (let [h    (server/serve (fn [_] {:body "up"}) {:port 0})
        url  (str "http://127.0.0.1:" (server/port h))]
    (is (= "up" (:body (http/post-json url {} ""))))
    (is (nil? (server/stop! h)))
    (is (nil? (server/stop! h)) "stop! must be idempotent — teardown is unconditional")
    ;; koine.http returns a transport failure as DATA now (never throws), so the
    ;; assertion reads the classified error rather than catching — which is also
    ;; the portable spelling, since the catch symbol differs per host.
    (is (= :connect-failed (:error (http/post-json url {} "")))
        "the socket must actually be gone after stop!")))

(deftest handler-exception-becomes-a-500
  (testing "com.sun leaves the exchange hanging on a thrown handler; koine answers"
    (with-server (fn [_] (throw (ex-info "boom" {})))
      (fn [url _]
        (is (= 500 (:status (http/post-json url {} ""))))))))
