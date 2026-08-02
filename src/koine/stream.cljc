(ns koine.stream
  "Streaming HTTP responses — Server-Sent Events, portable.

  `koine.http/request` buffers: it hands you the whole body once the server is
  done. That is wrong for an LLM, where the point of `stream` mode is that
  tokens surface *as they are produced*. This namespace is the incremental
  counterpart, kept in its own file because the host seams are completely
  different from the buffered ones (a buffered client and a streaming client
  share a URL and nothing else).

  The contract is INCREMENTAL DELIVERY, not \"returns the same lines\". A host
  that can only buffer does **not** get a slow-but-correct fallback here — it
  throws (rule 2). A fake stream passes every test and then fails in front of a
  user, which is strictly worse than an honest error.

  Measured on 2026-07-27 (150 ms between server events, arrival times relative
  to the first byte):

  | host    | streams? | route                                             |
  |---------|----------|---------------------------------------------------|
  | JVM     | yes      | `HttpResponse$BodyHandlers/ofInputStream`          |
  | cljgo   | yes      | `(cljg.net.http/request {… :as :stream})` + `cljg.stream/read-line` |

  The cljgo row was `no` until 2026-07-30 — `cljg.net.http` buffered through
  `io.ReadAll` and exposed no reader. The `:as :stream` shim closed it.

  Do NOT reach for `BodyHandlers/ofLines` on the JVM. It looks like the clean
  route and it is not — see the comment on the `:clj` branch."
  (:require [clojure.string :as str]
            [koine.http :as khttp]))

(declare -sse-post*)

;; cljgo: the streaming client + the stream handles must be interned before
;; `sse-post`'s cljgo branch is reachable. A top-level reader conditional with no
;; branch for the other hosts reads as nothing there (same pattern as koine.time).
#?(:cljgo (require '[cljg.net.http] '[cljg.stream]))

;; ---------------------------------------------------------------- pure part
;;
;; Line parsing is plain `clojure.core` — no reader conditional, no host code.
;; This is the part every host must agree on byte for byte, so it is the part
;; that must not vary per host. Rule 3: if it is expressible in core, it is not
;; a seam.
;;
;; Field grammar (WHATWG `text/event-stream`), and only the parts a caller can
;; observe through this API:
;;   ""            -> nil   (dispatch boundary; the caller does not need it)
;;   ":anything"   -> nil   (comment / keep-alive)
;;   "field: v"    -> the field, with ONE optional space after the colon eaten
;;   "field"       -> the field with value "" (a colon-less line is legal)
;; `data` and `event` are surfaced; `id` / `retry` / unknown fields are not
;; representable in the {:event :data} shape and return nil.

(defn- strip-eol
  "Drop one trailing LF and then one trailing CR. Hosts disagree about whether
  their line reader keeps the terminator, so normalise here rather than at each
  seam."
  [s]
  (let [s (if (str/ends-with? s "\n") (subs s 0 (dec (count s))) s)]
    (if (str/ends-with? s "\r") (subs s 0 (dec (count s))) s)))

(defn parse-sse-line
  "Parse one raw SSE line.

  -> {:event \"delta\" :data nil}   for `event: delta`
  -> {:event nil :data \"{…}\"}     for `data: {…}`
  -> nil                            for blank lines, comments (`: ping`), and
                                    fields this shape cannot carry (`id`,
                                    `retry`, unknown).

  A trailing CR and/or LF is stripped, so it accepts a line either with or
  without its terminator. Pure; identical on every host."
  [line]
  (when (string? line)
    (let [line (strip-eol line)]
      (cond
        (= "" line)                 nil
        (str/starts-with? line ":") nil
        :else
        (let [i     (str/index-of line ":")
              field (if i (subs line 0 i) line)
              raw   (if i (subs line (inc i)) "")
              ;; exactly one leading space is part of the framing, not the data
              value (if (str/starts-with? raw " ") (subs raw 1) raw)]
          (cond
            (= "data" field)  {:event nil   :data value}
            (= "event" field) {:event value :data nil}
            :else             nil))))))

(defn- emit!
  "Feed one raw line to the caller's callback if it carries data.
  `on-event` is APPLIED, never compared: comparing functions is not portable."
  [on-event line]
  (when-let [d (:data (parse-sse-line line))]
    (on-event d))
  nil)

;; ------------------------------------------------------------------- seam

(defn sse-post
  "POST `body` to `url` and invoke `(on-event data-string)` once per SSE
  `data:` line, AS IT ARRIVES. Returns `{:status n :headers {…}}` when the
  stream ends.

  `[DONE]` is delivered like any other datum — the SSE framing has no idea it
  is a sentinel, and deciding that is the caller's job, not the transport's.

  Blocks until the server closes the stream. Header values are passed through
  verbatim and never logged; they routinely carry credentials.

  Throws on a host with no incremental route. It does not quietly buffer.

  With `opts` `{:on-open f}`, `f` is applied ONCE to `{:status n :headers {…}}`
  as soon as the response head is available — before the first event, while the
  stream is still open. Response header names are lowercased on every host (see
  `koine.http/normalize-headers`); `koine.http/header` reads one case-insensitively.

  Why on-open rather than the returned map: a caller may need something from the
  head in order to answer *during* the stream. MCP streamable-HTTP is the case
  that asked for it — the server issues `Mcp-Session-Id` in the response
  headers, and a server→client reverse request arriving as an SSE event must be
  answered by a SEPARATE POST carrying that id, all before the first stream
  closes. Headers returned when the stream ends arrive strictly too late, and
  the buffered `koine.http/request` never streams at all — so without this a
  consumer had to choose between learning the session id and receiving events
  incrementally. Asked for by the toolnexus MCP port, 2026-08-02.

  `on-open` is APPLIED, never compared — comparing functions is not portable."
  ([url headers body on-event]
   (sse-post url headers body on-event nil))
  ([url headers body on-event opts]
   (-sse-post* url headers body on-event opts)))

(defn- -sse-post* [url headers body on-event opts]
  (let [on-open (:on-open opts)
        open!   (fn [status hdrs]
                  (let [head {:status status :headers (khttp/normalize-headers hdrs)}]
                    (when on-open (on-open head))
                    head))]
   #?(:clj
     ;; `BodyHandlers/ofLines` is the obvious choice and it is a TRAP: measured
     ;; against a server emitting 4 events 150 ms apart, every line surfaced at
     ;; ~605 ms — the whole body first, then a replay. Same with `sendAsync`.
     ;; `ofInputStream` is the only handler here that is actually incremental
     ;; (lines observed at 4 / 155 / 310 / 465 / 619 ms).
     ;;
     ;; No `.timeout` on the request: that is a deadline for the WHOLE exchange
     ;; and a stream is meant to stay open. Only the connect phase is bounded.
     (let [client  (-> (java.net.http.HttpClient/newBuilder)
                       (.connectTimeout (java.time.Duration/ofMillis 30000))
                       .build)
           builder (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
           _       (doseq [[k v] headers] (.header builder (name k) (str v)))
           req     (-> builder
                       (.method "POST" (java.net.http.HttpRequest$BodyPublishers/ofString
                                        (or body "")))
                       .build)
           res     (.send client req (java.net.http.HttpResponse$BodyHandlers/ofInputStream))
           ;; `.send` with ofInputStream returns as soon as the HEAD is in — the
           ;; body is still arriving — so this fires before the first event, not
           ;; after the stream ends. That timing is the whole point; it is
           ;; asserted by the clock in stream_check, not assumed.
           head    (open! (.statusCode res)
                          (into {} (map (fn [[k v]] [k (first v)]) (.map (.headers res)))))
           rdr     (java.io.BufferedReader.
                    (java.io.InputStreamReader. (.body res) "UTF-8"))]
       (try
         (loop []
           (when-let [line (.readLine rdr)]
             (emit! on-event line)
             (recur)))
         (finally (.close rdr)))
       head)

     :cljgo
     ;; CLOSED 2026-07-30: `cljg.net.http` grew a second shim, `-http-stream`,
     ;; reached with `:as :stream` — the response :body is then a live
     ;; cljg.stream readable over the OPEN Go resp.Body instead of the
     ;; io.ReadAll string, and the caller closes it. `cljg.stream/read-line`
     ;; strips the terminator and returns nil at EOF, which is exactly the
     ;; JVM `.readLine` contract, so the loop below is the :clj one verbatim.
     ;;
     ;; Deliberately NOT `require-go '[net/http]` + `bufio`: raw interop only
     ;; links AOT, and `(.-Body resp)` rides cljgo's nil-substituting build
     ;; pass. This route is portable Clojure — identical under run and build.
     ;;
     ;; No :timeout override: cljg.net.http defaults to 30 s, which for a
     ;; STREAM is a deadline on the whole exchange, not just the connect phase.
     ;; A stream is meant to stay open, so it is raised well past any single
     ;; LLM response rather than left at the buffered-request default.
     (let [r   (cljg.net.http/request (cond-> {:method  :post
                                               :url     url
                                               :body    (or body "")
                                               :as      :stream
                                               :timeout 86400000}
                                       (seq headers) (assoc :headers headers)))
           ;; `:as :stream` returns once the head is read, over an OPEN
           ;; resp.Body — same guarantee as the JVM branch above.
           head (open! (:status r) (:headers r))
           rdr  (:body r)]
       (try
         (loop []
           (when-let [line (cljg.stream/read-line rdr)]
             (emit! on-event line)
             (recur)))
         (finally (cljg.stream/close rdr)))
       head)

     :default
     (throw (ex-info (str "koine.stream/sse-post: no implementation for this host; "
                          "add a branch in koine/stream.cljc")
                     {:url url})))))
