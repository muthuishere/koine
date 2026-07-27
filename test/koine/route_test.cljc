(ns koine.route-test
  "koine.route on the JVM. The same assertions run on the other three hosts
  from src/route_check.cljc — this file adds the things only the JVM can do
  cheaply (port 0, a throwing handler, hop-by-hop header inspection) and the
  one structural assertion that is the whole point of the namespace: it
  contains no reader conditionals."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.http :as http]
            [koine.route :as route]
            [koine.server :as server]))

(defn- GET
  ([path] (GET path :get))
  ([path method] {:method method :path path :headers {} :body ""}))

;; ------------------------------------------------------- the prime constraint

(deftest route-cljc-has-zero-reader-conditionals
  (testing "koine.route is composed ABOVE the seam; a #? here would mean a
            combinator had grown host knowledge, which is the one thing this
            namespace exists to avoid"
    (let [src (slurp "src/koine/route.cljc")]
      (is (not (str/includes? src "#?"))
          "src/koine/route.cljc must contain no reader conditionals")
      (is (not (str/includes? src "#?("))))))

;; ------------------------------------------------------------------- router

(def app
  (route/router {[:get "/health"]  (fn [_] {:body "ok"})
                 [:post "/health"] (fn [_] {:body "posted"})
                 "/api/*"          (fn [req] {:body (str "api:" (:path-info req))})
                 "/api/deep/*"     (fn [req] {:body (str "deep:" (:path-info req))})
                 "/"               (fn [_] {:body "root"})}))

(deftest router-matching
  (testing "exact"
    (is (= "ok" (:body (app (GET "/health")))))
    (is (= "root" (:body (app (GET "/")))) "an exact route beats a wildcard"))
  (testing "method"
    (is (= "posted" (:body (app (GET "/health" :post)))))
    (is (= "ok" (:body (app (GET "/health" :GET)))) "methods normalise to lower-case"))
  (testing "wildcard"
    (is (= "api:/users" (:body (app (GET "/api/users")))))
    (is (= "api:/" (:body (app (GET "/api")))) "a mount answers its own bare name")
    (is (= "deep:/x" (:body (app (GET "/api/deep/x")))) "longest prefix wins"))
  (testing "miss"
    (is (= 404 (:status (app (GET "/nope")))))
    (is (= 418 (:status ((route/router {} {:not-found (fn [_] {:status 418})})
                         (GET "/x"))))
        ":not-found replaces the default 404")))

(deftest router-sets-path-info-and-nests
  (let [inner (route/router {"/b" (fn [req] {:body (str (:path req) "|" (:path-info req))})})
        outer (route/router {"/a/*" inner})]
    (is (= "/a/b|/b" (:body (outer (GET "/a/b"))))
        ":path is never rewritten; :path-info carries the remainder inward")
    (is (= 404 (:status (outer (GET "/a/zzz")))))))

(deftest routes->handler-applies-middleware-outermost-first
  (let [order (atom [])
        mw    (fn [tag] (fn [h] (fn [req] (swap! order conj tag) (h req))))
        app   (route/routes->handler {"/x" (fn [_] {:body "x"})}
                                     {:middleware [(mw :outer) (mw :inner)]})]
    (is (= "x" (:body (app (GET "/x")))))
    (is (= [:outer :inner] @order))))

;; ------------------------------------------------------------------- static

(def files (route/static "src/koine"))

(deftest static-serves-and-guesses-content-type
  (let [res (files (GET "/route.cljc"))]
    (is (= 200 (:status res)))
    (is (str/includes? (:body res) "ns koine.route"))
    (is (= "application/octet-stream" (get (:headers res) "content-type"))))
  (testing "content-type by extension"
    (is (= "text/html; charset=utf-8" (route/content-type "/a/b.html")))
    (is (= "text/css; charset=utf-8" (route/content-type "b.CSS")) "extension is case-insensitive")
    (is (= "text/javascript; charset=utf-8" (route/content-type "/b.js")))
    (is (= "application/json" (route/content-type "/b.json")))
    (is (= "text/plain; charset=utf-8" (route/content-type "/b.txt")))
    (is (= "image/png" (route/content-type "/b.png")))
    (is (= "image/svg+xml" (route/content-type "/b.svg")))
    (is (= "application/octet-stream" (route/content-type "/no-extension")))
    (is (= "application/octet-stream" (route/content-type "/a.dir/file")))))

(deftest static-404s
  (is (= 404 (:status (files (GET "/definitely-absent.txt")))))
  (is (= 404 (:status (files (GET "/"))))
      "a directory with no index is a 404, never a slurp of the directory"))

(deftest static-rejects-path-traversal
  (testing "THE security assertion: no request may escape the served root"
    (doseq [p ["/../../PORTING.md"
               "/koine/../../PORTING.md"
               "/%2e%2e/%2e%2e/PORTING.md"
               "/..%2f..%2fPORTING.md"
               "/a\\..\\..\\PORTING.md"]]
      (is (= 403 (:status (files (GET p)))) (str "must refuse " p))))
  (testing "and the predicate underneath it"
    (is (false? (route/safe-path? "/a/../b")))
    (is (false? (route/safe-path? "/..")))
    (is (false? (route/safe-path? "//evil.com/x")) "protocol-relative / UNC")
    (is (false? (route/safe-path? "/C:/windows")) "drive letter")
    (is (false? (route/safe-path? "a/b")) "must be rooted")
    (is (false? (route/safe-path? (str "/a" (char 0) "/b"))) "NUL byte")
    (is (true? (route/safe-path? "/a/b.css")))
    (is (true? (route/safe-path? "/a/..b/c.css")) "'..b' is a normal name, not traversal")))

(deftest static-serves-index-for-a-directory
  (testing "an injected fs proves the index branch without writing to disk"
    (let [tree {"pub" :dir "pub/index.html" "<h1>hi</h1>"}
          h    (route/static "pub" {:exists?    (fn [p] (contains? tree p))
                                    :directory? (fn [p] (= :dir (get tree p)))
                                    :read       (fn [p] (get tree p))})]
      (is (= "<h1>hi</h1>" (:body (h (GET "/")))))
      (is (= "text/html; charset=utf-8" (get (:headers (h (GET "/"))) "content-type"))))))

;; -------------------------------------------------------------------- proxy

(defn- with-servers
  "Stand up an upstream and an edge that proxies to it."
  [upstream-handler edge-routes f]
  (let [up   (server/serve upstream-handler {:port 0})
        base (str "http://127.0.0.1:" (server/port up))
        edge (server/serve (route/router (edge-routes base)) {:port 0})]
    (try (f (str "http://127.0.0.1:" (server/port edge)) base)
         (finally (server/stop! edge) (server/stop! up)))))

(deftest proxy-round-trip
  (let [seen (atom nil)]
    (with-servers
      (fn [req] (reset! seen req)
        {:status 201 :headers {"content-type" "text/plain"} :body (str "up:" (:path req) ":" (:body req))})
      (fn [base] {"/api/*" (route/proxy base)})
      (fn [edge _]
        (let [res (http/request {:method :post :url (str edge "/api/v1/x") :body "payload"})]
          (is (= 201 (:status res)) "the upstream status is relayed verbatim")
          (is (= "up:/v1/x:payload" (:body res))
              "the mount prefix is stripped and the body forwarded")
          (is (= :post (:method @seen)) "the method is preserved"))))))

(deftest proxy-strips-hop-by-hop-headers
  (let [seen (atom nil)]
    (with-servers
      (fn [req] (reset! seen req) {:body "ok"})
      (fn [base] {"/*" (route/proxy base {:headers {"x-forwarded-host" "edge"}})})
      (fn [edge _]
        ;; "connection"/"upgrade"/"host" cannot even be SENT by the JVM
        ;; client (restricted header names), so the inbound case is exercised
        ;; with "trailer", which is equally hop-by-hop and not restricted.
        (http/request {:method :get :url (str edge "/x")
                       :headers {"trailer" "x-checksum"
                                 "x-keep" "yes"}})
        (is (nil? (get (:headers @seen) "trailer")) "hop-by-hop is not relayed")
        ;; NOTE: `connection` may still be PRESENT upstream — the JVM client
        ;; writes its own ("Upgrade, HTTP2-Settings") on the outbound hop.
        ;; That is the transport doing its job; what matters is that ours was
        ;; not relayed, which is what the `trailer` assertion shows.
        (is (= "yes" (get (:headers @seen) "x-keep")) "end-to-end headers survive")
        (is (= "edge" (get (:headers @seen) "x-forwarded-host")) ":headers are merged in")
        (is (not (contains? (:headers @seen) "transfer-encoding")))))))

;; ----------------------------------------------------------------- wrap-log

(deftest wrap-log-reports-every-request
  (let [seen (atom [])
        h    (route/wrap-log (fn [_] {:status 204 :body ""}) (fn [e] (swap! seen conj e)))]
    (is (= 204 (:status (h (GET "/a")))))
    (let [e (first @seen)]
      (is (= :get (:method e)))
      (is (= "/a" (:path e)))
      (is (= 204 (:status e)))
      (is (number? (:ms e)))
      (is (>= (:ms e) 0) ":ms comes from the monotonic clock, so never negative"))))

(deftest wrap-log-defaults-and-exceptions
  (testing "a handler returning a bare body logs 200, matching koine.server's default"
    (let [seen (atom nil)
          h    (route/wrap-log (fn [_] {:body "x"}) (fn [e] (reset! seen e)))]
      (h (GET "/b"))
      (is (= 200 (:status @seen)))))
  (testing "a throwing handler is still logged, as 500, and still throws"
    (let [seen (atom nil)
          h    (route/wrap-log (fn [_] (throw (ex-info "boom" {}))) (fn [e] (reset! seen e)))]
      (is (thrown? Exception (h (GET "/c"))))
      (is (= 500 (:status @seen)))
      (is (= "/c" (:path @seen))))))

;; ------------------------------------------------------- everything together

(deftest nginx-shaped-app-over-a-real-socket
  (let [log (atom [])]
    (with-servers
      (fn [req] {:body (str "up" (:path req))})
      (fn [base] {"/api/*"    (route/proxy base)
                  "/assets/*" (route/static "src")
                  [:get "/health"] (fn [_] {:body "ok"})})
      (fn [edge _]
        (is (= "ok" (:body (http/request {:url (str edge "/health")}))))
        (is (= "up/v1" (:body (http/request {:url (str edge "/api/v1")}))))
        (is (str/includes? (:body (http/request {:url (str edge "/assets/koine/route.cljc")}))
                           "ns koine.route"))
        (is (= 404 (:status (http/request {:url (str edge "/elsewhere")}))))))
    (is (empty? @log))))
