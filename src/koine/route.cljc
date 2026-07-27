(ns koine.route
  "Routing, static files and reverse proxying — as pure handler combinators
  over `koine.server`.

  THE DESIGN CONSTRAINT: this file contains **zero reader conditionals**, on
  purpose. Everything here is `handler -> handler` or `data -> handler`,
  composed ABOVE the seam:

    routing  is map lookup                (plain clojure.core)
    static   is `koine.fs` + `slurp`      (already portable)
    proxying is `koine.http/request`      (already portable)
    timing   is `koine.time/mono-ms`      (already portable)

  So the namespace inherits four-host support for free and adds no host code.
  If a change here seems to need a reader conditional, the design is wrong —
  the branch belongs in the seam namespace (`koine.server`, `koine.http`,
  `koine.fs`), not in a combinator. (There is a test asserting this file has
  none; it greps the source.)

      (def app
        (route/routes->handler
          {[:get \"/health\"] (fn [_] {:body \"ok\"})
           \"/assets/*\"       (route/static \"public\")
           \"/api/*\"          (route/proxy \"http://127.0.0.1:9000\")}
          {:middleware [(fn [h] (route/wrap-log h println))]}))

      (server/serve app {:port 8080})

  Handlers speak exactly the `koine.server` request/response maps — request
  `{:method :path :headers :body}`, response `{:status :headers :body}` — so
  any handler here is a valid `serve` handler and any `serve` handler is a
  valid route target. Nothing new to learn, nothing new to port.

  NESTING. A wildcard match adds two keys to the request map:

    :path-info    the part of the path AFTER the matched prefix (\"/a.css\")
    :route-prefix the prefix that matched (\"/assets/\")

  `router`, `static` and `proxy` all read `:path-info` in preference to
  `:path`, which is what makes them nest: a router inside a router matches on
  the remainder, and `static`/`proxy` mounted under `/assets/*` see
  `/a.css`, not `/assets/a.css`. `:path` is never rewritten, so logging and
  the handler's own view of the original URL stay intact.

  KNOWN LIMITS, stated rather than faked:
  - Bodies are strings on every host (that is `koine.server`'s contract), so
    `static` serves TEXT faithfully and binary files only as far as the
    host's `slurp` decoding allows. Content types for binary extensions are
    still emitted, because getting the header right costs nothing.
  - `koine.server` hands over the path only, never the query string, so
    `proxy` forwards the path and drops any query.
  - `koine.fs/exists?`/`directory?` are implemented for the JVM and cljgo
    only. `static` therefore takes `:exists?`/`:directory?`/`:read`
    overrides: the DEFAULTS are the `koine.fs` fns, and a host where those
    are unimplemented can supply its own without this file growing a branch.
    (The fix belongs in `koine/fs.cljc`, not here.)"
  (:refer-clojure :exclude [proxy])
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.http :as http]
            [koine.time :as time]))

;; ------------------------------------------------------------------ shared

(defn- text-response [status body]
  {:status status
   :headers {"content-type" "text/plain; charset=utf-8"}
   :body body})

(def ^:private not-found-res  (text-response 404 "Not Found"))
(def ^:private forbidden-res  (text-response 403 "Forbidden"))

(defn effective-path
  "The path a mounted handler should act on: the remainder left by an
  enclosing wildcard route, or the request path when there is no enclosing
  route. This one function is the whole nesting story."
  [req]
  (or (:path-info req) (:path req) "/"))

(defn- lower-str [x] (str/lower-case (str x)))

;; --------------------------------------------------------------- 1. router

(defn- norm-method
  "Methods are compared as lower-case keywords. nil, :any and \"*\" all mean
  'any method' — a bare path key in a routes map is method-agnostic."
  [m]
  (cond
    (nil? m) :any
    (= m "*") :any
    (= m :*) :any
    (keyword? m) (let [k (keyword (lower-str (name m)))] (if (= k :*) :any k))
    :else (keyword (lower-str m))))

(defn- compile-route
  "One routes-map entry -> a match record. Key is either a path string or a
  [method path] pair. A trailing `*` makes it a prefix route."
  [k handler]
  (let [[m p] (if (string? k) [nil k] [(first k) (second k)])
        p     (str p)
        wild? (str/ends-with? p "*")
        prefix (if wild? (subs p 0 (dec (count p))) p)]
    {:method  (norm-method m)
     :wild?   wild?
     :pattern p
     :prefix  prefix
     :handler handler}))

(defn- match-path
  "nil when `e` does not match `path`; otherwise the remainder to expose as
  :path-info. Longest-prefix wins is decided by the caller via :rank.

  An EXACT match yields the whole path, not the empty string: an exact route
  consumes no prefix, so a handler mounted there (or a nested router) must
  still see the path it matched."
  [e path]
  (let [pre (:prefix e)]
    (if (:wild? e)
      (cond
        (str/starts-with? path pre) (subs path (count pre))
        ;; \"/api/*\" also answers the bare \"/api\" — otherwise every mount
        ;; point would 404 on its own name, which surprises everyone once.
        (and (str/ends-with? pre "/")
             (= path (subs pre 0 (dec (count pre))))) ""
        :else nil)
      (when (= path (:pattern e)) path))))

(defn- rank
  "Sort key. Exact beats wildcard; then longer prefix beats shorter; then a
  method-specific route beats a method-agnostic one."
  [e]
  [(if (:wild? e) 0 1)
   (count (:prefix e))
   (if (= :any (:method e)) 0 1)])

(defn router
  "Turn a routes map into a handler.

  routes: {[method path] handler
           path          handler}   ; path-only key = any method

  `path` may end in `*` for a prefix route (\"/api/*\"). Longest prefix wins,
  an exact path always beats a wildcard, and a method-specific route beats a
  method-agnostic one at the same path. Nothing matched -> 404.

  opts: {:not-found handler}"
  ([routes] (router routes {}))
  ([routes {:keys [not-found]}]
   (let [entries (vec (map (fn [[k h]] (compile-route k h)) routes))
         miss    (or not-found (fn [_] not-found-res))]
     (fn [req]
       (let [path   (effective-path req)
             method (norm-method (:method req))
             hits   (filter (fn [e]
                              (and (or (= :any (:method e)) (= method (:method e)))
                                   (some? (match-path e path))))
                            entries)
             best   (last (sort-by rank hits))]
         (if (nil? best)
           (miss req)
           ((:handler best)
            (assoc req
                   :route-prefix (if (:wild? best) (:prefix best) "")
                   :path-info    (let [r (match-path best path)]
                                   (if (str/starts-with? r "/") r (str "/" r)))))))))))

(defn routes->handler
  "`router` plus a middleware stack, so a whole app is one expression.

  opts: {:not-found handler
         :middleware [f g]}   ; each f is handler -> handler; FIRST is outermost

  `(routes->handler routes {:middleware [a b]})` == `(a (b (router routes)))`."
  ([routes] (routes->handler routes {}))
  ([routes {:keys [not-found middleware]}]
   (reduce (fn [h mw] (mw h))
           (router routes {:not-found not-found})
           (reverse (vec (or middleware []))))))

;; --------------------------------------------------------------- 2. static

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "htm"  "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "mjs"  "text/javascript; charset=utf-8"
   "json" "application/json"
   "txt"  "text/plain; charset=utf-8"
   "md"   "text/markdown; charset=utf-8"
   "xml"  "application/xml"
   "csv"  "text/csv; charset=utf-8"
   "svg"  "image/svg+xml"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "ico"  "image/x-icon"
   "wasm" "application/wasm"})

(defn- extension [path]
  (let [slash (or (str/last-index-of path "/") -1)
        dot   (str/last-index-of path ".")]
    (if (and dot (> dot (inc slash)))
      (str/lower-case (subs path (inc dot)))
      "")))

(defn content-type
  "Content type guessed from a path's extension. Unknown -> octet-stream."
  [path]
  (or (get content-types (extension path)) "application/octet-stream"))

(def ^:private hex-digits "0123456789abcdef")

(defn- hex-val [c] (str/index-of hex-digits (str/lower-case (str c))))

(defn- percent-decode
  "Decode %XX escapes, ASCII only. Decoding matters for SECURITY, not for
  looks: `/%2e%2e/etc` must be rejected by the same test that rejects
  `/../etc`. Non-ASCII escapes are left verbatim rather than mangled into a
  lone char — the four hosts disagree on whether the server layer decoded
  the path already (Go's `URL.Path` is decoded, the JVM's `getRequestURI`
  is not), and an undecoded byte sequence at worst 404s, while a mangled one
  could smuggle."
  [s]
  (let [n (count s)]
    (loop [i 0 acc []]
      (if (>= i n)
        (apply str acc)
        (let [c (nth s i)]
          (if (and (= c \%) (< (+ i 2) n))
            (let [h (hex-val (nth s (inc i)))
                  l (hex-val (nth s (+ i 2)))
                  v (when (and h l) (+ (* 16 h) l))]
              (if (and v (< v 0x80))
                (recur (+ i 3) (conj acc (char v)))
                (recur (inc i) (conj acc c))))
            (recur (inc i) (conj acc c))))))))

(defn safe-path?
  "THE security line of this namespace. True only for a relative, rooted,
  traversal-free path.

  Rejects, after percent-decoding: any `..` segment, a backslash (a Windows
  separator that would slip past a `/`-only segment test), a leading `//`
  (protocol-relative / UNC), a drive letter, an embedded NUL, and anything
  not starting at `/`. Deliberately a WHITELIST of shape rather than a
  blacklist of strings."
  [rel]
  (and (string? rel)
       (str/starts-with? rel "/")
       (not (str/starts-with? rel "//"))
       (not (str/includes? rel "\\"))
       (not (str/includes? rel (str (char 0))))
       (nil? (re-find #"^/[A-Za-z]:" rel))
       (every? (fn [seg] (not= seg "..")) (str/split rel #"/"))))

(defn- strip-trailing-slash [s]
  (let [s (str s)]
    (if (and (> (count s) 1) (str/ends-with? s "/"))
      (subs s 0 (dec (count s)))
      s)))

(defn static
  "Serve files under `dir` as a handler.

  Resolves `(effective-path req)` — so mounting it at \"/assets/*\" serves
  `dir/a.css` for `/assets/a.css`. Path traversal is rejected with 403, a
  missing file is 404, a directory serves `:index` if present.

  opts: {:index      \"index.html\"
         :exists?    fn        ; default koine.fs/exists?
         :directory? fn        ; default koine.fs/directory?
         :read       fn}       ; default slurp

  The three fn overrides exist because `koine.fs` is currently JVM+cljgo
  only; they let a host without those branches serve files without this
  file growing a reader conditional. Everything else here is pure string
  work and is identical on every host."
  ([dir] (static dir {}))
  ([dir {:keys [index exists? directory? read]
         :or   {index "index.html"}}]
   (let [root    (strip-trailing-slash dir)
         exists? (or exists? fs/exists?)
         dir?    (or directory? fs/directory?)
         rd      (or read slurp)]
     (fn [req]
       (let [rel (percent-decode (effective-path req))]
         (if-not (safe-path? rel)
           forbidden-res
           ;; the trailing slash is stripped BEFORE the stat calls: a real
           ;; filesystem shrugs at "pub/", an injected one is entitled not to,
           ;; and "/dir" and "/dir/" must resolve to the same file either way.
           (let [base (strip-trailing-slash (str root rel))
                 file (if (and (exists? base) (dir? base))
                        (str base "/" index)
                        base)]
             (if (and (exists? file) (not (dir? file)))
               {:status  200
                :headers {"content-type" (content-type file)}
                :body    (rd file)}
               not-found-res))))))))

;; ---------------------------------------------------------------- 3. proxy

(def ^:private hop-by-hop
  "Headers that describe THIS connection and must not be relayed to the next
  one (RFC 9110 §7.6.1), plus `host` and `content-length`, which the
  outbound client must set for itself."
  #{"connection" "keep-alive" "proxy-authenticate" "proxy-authorization"
    "te" "trailer" "transfer-encoding" "upgrade" "host" "content-length"})

(defn- end-to-end
  "Drop hop-by-hop headers and lower-case the rest, so the same map shape
  comes out on every host (Go canonicalises `Content-Type`, let-go and bri
  lower-case it)."
  [headers]
  (reduce (fn [m [k v]]
            (let [k (lower-str k)]
              (if (contains? hop-by-hop k) m (assoc m k (str v)))))
          {} headers))

(defn proxy
  "Forward the request to `target-base` and return the upstream response.

  Mounted under a wildcard route it forwards the remainder:
  `{\"/api/*\" (proxy \"http://up:9000\")}` sends `/api/v1/x` to
  `http://up:9000/v1/x`. Hop-by-hop headers are stripped in BOTH directions.

  opts: {:timeout-ms 30000
         :headers {\"x-forwarded-host\" \"…\"}}  ; merged in, wins over inbound"
  ([target-base] (proxy target-base {}))
  ([target-base {:keys [timeout-ms headers] :or {timeout-ms 30000}}]
   (let [base (strip-trailing-slash target-base)]
     (fn [req]
       (let [hs   (merge (end-to-end (:headers req)) (end-to-end headers))
             body (:body req)
             ;; :headers and :body are OMITTED rather than passed empty —
             ;; let-go's client nil-pointers on an empty-but-present
             ;; :headers {} (koine.http absorbs it, but not sending a key we
             ;; have nothing to say with is the cheaper contract).
             base-req {:method (or (:method req) :get)
                       :url (str base (effective-path req))
                       :timeout-ms timeout-ms}
             out  (merge base-req
                         (when (seq hs) {:headers hs})
                         (when (seq body) {:body body}))
             res  (http/request out)]
         {:status  (or (:status res) 502)
          :headers (end-to-end (:headers res))
          :body    (or (:body res) "")})))))

;; ------------------------------------------------------------- 4. wrap-log

(defn wrap-log
  "Call `(f {:method :path :status :ms})` after every request, then return
  the response unchanged.

  `:ms` comes from `koine.time/mono-ms`, never wall clock — a duration
  measured against a clock the OS can move backwards is a lie. The call is
  in a `finally`, so a handler that throws is still logged (as 500) and the
  exception still propagates; `finally` needs no catch clause and therefore
  no reader conditional, which is exactly why it is used here."
  [handler f]
  (fn [req]
    (let [start (time/mono-ms)
          out   (atom {:status 500})]
      (try
        (let [res (handler req)]
          (swap! out (fn [_] (or res {})))
          res)
        (finally
          (f {:method (:method req)
              :path   (:path req)
              :status (or (:status (deref out)) 200)
              :ms     (time/elapsed-ms start)}))))))
