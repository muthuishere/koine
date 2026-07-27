;; koine.route on every host. Routing, static files, reverse proxy, logging.
;;
;; Reader conditionals are allowed HERE (a check file may know its host);
;; koine/route.cljc itself must contain none, which is the point of the
;; namespace and is asserted by grep in route_test.cljc.
;;
;; Two host facts shape this file:
;;   - Glojure and let-go cannot bind :port 0, so ports are explicit.
;;   - koine.fs/exists? is implemented for the JVM and cljgo only, so on
;;     let-go and Glojure the host stat call is injected into `static` via
;;     its :exists?/:directory? overrides. That is the whole gap: every
;;     other line of koine.route is the same code on all four hosts.
#?(:lg (require '[os]))

(require 'koine.route 'koine.http 'koine.server 'koine.time)
(alias 'r 'koine.route) (alias 'h 'koine.http)
(alias 'srv 'koine.server) (alias 'time 'koine.time)

(def fs-opts
  #?(:clj   {}                                    ; koine.fs works here
     :cljgo {}                                    ; and here
     :lg    {:exists?    (fn [p] (some? (os/stat p)))
             :directory? (fn [p] (boolean (:dir? (os/stat p))))}
     :glj   {:exists?    (fn [p] (nil? (nth (os.Stat p) 1)))
             :directory? (fn [p] (let [s (os.Stat p)]
                                   (and (nil? (nth s 1)) (.IsDir (nth s 0)))))}
     :default {}))

(def exists?*
  (or (:exists? fs-opts)
      (fn [p] (koine.fs/exists? p))))

;; The static root is koine's own source dir — a directory that exists on
;; every host with no fixture to create. run-conformance.sh runs from src/,
;; a JVM test run from the repo root.
(def static-root (if (exists?* "koine/route.cljc") "koine" "src/koine"))

(defn- GET [path] {:method :get :path path :headers {} :body ""})

;; ------------------------------------------------------------------ pure
(def app
  (r/router {[:get "/health"]  (fn [_] {:status 200 :body "ok"})
             [:post "/health"] (fn [_] {:status 200 :body "posted"})
             "/api/*"          (fn [req] {:status 200 :body (str "api:" (:path-info req))})
             "/api/deep/*"     (fn [req] {:status 200 :body (str "deep:" (:path-info req))})
             "/"               (fn [_] {:status 200 :body "root"})}))

(def logged (atom []))
(def log-app (r/wrap-log (fn [_] {:status 204 :body ""})
                         (fn [e] (swap! logged conj e))))
(def _log-run (log-app (GET "/logme")))
(def log-entry (first (deref logged)))

;; ---------------------------------------------------------------- static
(def files (r/static static-root fs-opts))

;; ----------------------------------------------------------------- proxy
(def up-port   (+ 19200 (rand-int 300)))
(def edge-port (+ 19600 (rand-int 300)))

(def upstream
  (srv/serve (fn [req] {:status 200
                        :headers {"content-type" "text/plain"}
                        :body (str "upstream:" (:path req) ":" (:body req))})
             {:port up-port}))

(def edge
  (srv/serve (r/routes->handler
               {"/api/*"    (r/proxy (str "http://127.0.0.1:" up-port))
                "/static/*" files
                "/hi"       (fn [_] {:status 200 :body "hi"})}
               {:middleware [(fn [hh] (r/wrap-log hh (fn [e] (swap! logged conj e))))]})
             {:port edge-port}))

(def base (str "http://127.0.0.1:" edge-port))

(def proxied  (h/request {:method :post :url (str base "/api/v1/x") :body "payload"}))
(def served   (h/request {:method :get  :url (str base "/static/route.cljc")}))
;; NOTE: traversal is asserted IN-PROCESS only. Over the wire it would test
;; the host's mux, not koine.route — Go's ServeMux cleans dot segments and
;; 301s before a handler ever sees them, so the four hosts legitimately
;; disagree on what reaches us. What koine.route owns is: given a path with
;; `..` in it, refuse. That is asserted directly.
(def missing  (h/request {:method :get  :url (str base "/static/nope.txt")}))
(def unrouted (h/request {:method :get  :url (str base "/nothing/here")}))

(def cases
  [;; --- router
   ["router exact hit"        (:body (app (GET "/health"))) "ok"]
   ["router method-specific"  (:body (app (assoc (GET "/health") :method :post))) "posted"]
   ["router miss -> 404"      (:status (app (GET "/nope"))) 404]
   ["router wildcard"         (:body (app (GET "/api/users"))) "api:/users"]
   ["router bare mount point" (:body (app (GET "/api"))) "api:/"]
   ["longest prefix wins"     (:body (app (GET "/api/deep/x"))) "deep:/x"]
   ["exact beats wildcard"    (:body (app (GET "/"))) "root"]
   ["routers nest on :path-info, :path is never rewritten"
    (:body ((r/router {"/a/*" (r/router {"/b" (fn [q] {:body (str (:path q) "|" (:path-info q))})})})
            (GET "/a/b")))
    "/a/b|/b"]
   ["custom not-found"        (:status ((r/router {} {:not-found (fn [_] {:status 418})})
                                        (GET "/x")))
    418]
   ;; --- safe-path? (the security line)
   ["reject .."               (r/safe-path? "/a/../../etc/passwd") false]
   ["reject encoded .."       (r/safe-path? "/%2e%2e/etc") true]   ; raw form is safe...
   ["reject decoded .."       (r/safe-path? "/../etc") false]      ; ...it is decoded first
   ["reject backslash"        (r/safe-path? "/a\\..\\b") false]
   ["reject unrooted"         (r/safe-path? "a/b") false]
   ["reject //"               (r/safe-path? "//evil.com/x") false]
   ["reject drive letter"     (r/safe-path? "/C:/win") false]
   ["accept ordinary"         (r/safe-path? "/a/b.css") true]
   ;; --- content types
   ["ct html"  (r/content-type "/a.html") "text/html; charset=utf-8"]
   ["ct css"   (r/content-type "/a.css")  "text/css; charset=utf-8"]
   ["ct js"    (r/content-type "/a.js")   "text/javascript; charset=utf-8"]
   ["ct json"  (r/content-type "/a.json") "application/json"]
   ["ct txt"   (r/content-type "/a.txt")  "text/plain; charset=utf-8"]
   ["ct png"   (r/content-type "/a.png")  "image/png"]
   ["ct svg"   (r/content-type "/a.svg")  "image/svg+xml"]
   ["ct none"  (r/content-type "/a")      "application/octet-stream"]
   ;; --- static, in-process
   ["static serves a real file"
    (> (count (:body (files (GET "/route.cljc")))) 500) true]
   ["static 200"        (:status (files (GET "/route.cljc"))) 200]
   ["static 404"        (:status (files (GET "/definitely-absent.txt"))) 404]
   ["static dir w/o index -> 404" (:status (files (GET "/"))) 404]
   ["static rejects traversal"    (:status (files (GET "/../../PORTING.md"))) 403]
   ["static rejects encoded traversal"
    (:status (files (GET "/%2e%2e/%2e%2e/PORTING.md"))) 403]
   ;; --- wrap-log
   ["log fired"        (some? log-entry) true]
   ["log method"       (:method log-entry) :get]
   ["log path"         (:path log-entry) "/logme"]
   ["log status"       (:status log-entry) 204]
   ["log ms is a number and >= 0" (>= (:ms log-entry) 0) true]
   ;; --- over the wire: proxy round-trip between TWO koine servers
   ["proxy status"     (:status proxied) 200]
   ["proxy strips the mount prefix and forwards the body"
    (:body proxied) "upstream:/v1/x:payload"]
   ["static over http"  (:status served) 200]
   ["static body over http"
    (> (count (:body served)) 500) true]
   ["missing over http -> 404"   (:status missing) 404]
   ["unrouted over http -> 404"  (:status unrouted) 404]
   ["middleware logged the wire requests" (> (count (deref logged)) 1) true]])

(let [fails (remove (fn [[_ g w]] (= g w)) cases)]
  (doseq [[l g w] fails] (println "  FAIL" l "got" (pr-str g) "want" (pr-str w)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))

;; let-go cannot stop a server (http/serve wraps ListenAndServe); the process
;; exit does it.
(doseq [hd [edge upstream]]
  (try (srv/stop! hd) (catch #?(:glj go/any :default Exception) _ nil)))
