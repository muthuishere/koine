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
  | let-go  | yes      | `(http/request {… :as :stream})` + `io/line-seq`   |
  | Glojure | yes      | `net/http` + chunked `Body.Read` into `bytes.Buffer` |
  | cljgo   | **no**   | `cljg.net.http` is `io.ReadAll` — no reader exposed |

  Do NOT reach for `BodyHandlers/ofLines` on the JVM. It looks like the clean
  route and it is not — see the comment on the `:clj` branch."
  (:require [clojure.string :as str]))

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
  their line reader keeps the terminator (Glojure's chunked reader does; the
  JVM's `readLine` and let-go's `io/line-seq` do not), so normalise here rather
  than in three places."
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
  `on-event` is APPLIED, never compared — `(= f g)` throws on Glojure."
  [on-event line]
  (when-let [d (:data (parse-sse-line line))]
    (on-event d))
  nil)

;; ------------------------------------------------------------------- seam

(defn sse-post
  "POST `body` to `url` and invoke `(on-event data-string)` once per SSE
  `data:` line, AS IT ARRIVES. Returns `{:status n}` when the stream ends.

  `[DONE]` is delivered like any other datum — the SSE framing has no idea it
  is a sentinel, and deciding that is the caller's job, not the transport's.

  Blocks until the server closes the stream. Header values are passed through
  verbatim and never logged; they routinely carry credentials.

  Throws on a host with no incremental route. It does not quietly buffer."
  [url headers body on-event]
  #?(:lg
     ;; let-go's own client already has the seam: `:as :stream` swaps the
     ;; buffered `io.ReadAll` body for a boxed reader, and `io/line-seq` over it
     ;; is a genuine lazy seq backed by `bufio.Reader.ReadString` (pkg/rt/
     ;; ions.go `makeLineSeq`) — one host read per realised element.
     ;; `:headers` is omitted rather than passed as `{}`: let-go's client tests
     ;; the key for nil and then walks the seq, and an EMPTY map is neither nil
     ;; nor walkable there — it panics with a nil-pointer dereference inside the
     ;; host. A caller with no headers is normal, so absorb it here.
     (let [r (http/request (cond-> {:method :post
                                    :url    url
                                    :body   (or body "")
                                    :as     :stream}
                             (seq headers) (assoc :headers headers)))
           rdr (:body r)]
       (doseq [line (io/line-seq rdr)]
         (emit! on-event line))
       (io/close rdr)
       {:status (:status r)})

     :glj
     ;; Go's `net/http` is in Glojure's default package map, but `bufio` is NOT
     ;; (see `cmd/gen-import-interop/main.go`), so there is no ReadString to
     ;; borrow — the chunking is hand-rolled.
     ;;
     ;; Bytes, not a string, are what accumulate between reads. A 4 KiB read can
     ;; land mid-rune, and decoding the partial chunk would corrupt any
     ;; non-ASCII token. `bytes.Buffer` holds the undecoded tail and
     ;; `ReadBytes(10)` hands back only whole lines; 0x0A can never occur inside
     ;; a multi-byte UTF-8 sequence, so splitting on the byte is safe.
     (let [rr  (net:http.NewRequest "POST" url (strings.NewReader (or body "")))
           req (nth rr 0)]
       (when (nth rr 1)
         (throw (ex-info (str "koine.stream/sse-post: bad request: " (nth rr 1))
                         {:url url})))
       (doseq [[k v] headers]
         (.Set (.Header req) (name k) (str v)))
       (let [dd (.Do net:http.DefaultClient req)]
         (when (nth dd 1)
           (throw (ex-info (str "koine.stream/sse-post: " (nth dd 1)) {:url url})))
         (let [resp (nth dd 0)
               rbody (.Body resp)
               chunk (go/make (go/slice-of go/byte) 4096)
               pend  (bytes.NewBufferString "")
               ;; drain every COMPLETE line; the partial tail goes back in
               drain! (fn []
                        (loop []
                          (let [lr   (.ReadBytes pend 10)
                                bs   (nth lr 0)
                                done (nth lr 1)]
                            (if done
                              ;; no delimiter: that was the tail, not a line
                              (when (pos? (go/len bs)) (.Write pend bs))
                              (do (emit! on-event (.String (bytes.NewBuffer bs)))
                                  (recur))))))]
           (loop []
             (let [r (.Read rbody chunk)
                   n (nth r 0)
                   e (nth r 1)]
               (when (pos? n)
                 (.Write pend (go/slice chunk 0 n))
                 (drain!))
               (if e
                 ;; EOF: a last line with no terminator still counts
                 (let [tail (.String pend)]
                   (when (not= "" tail) (emit! on-event tail))
                   (.Close rbody)
                   {:status (.StatusCode resp)})
                 (recur)))))))

     :clj
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
           rdr     (java.io.BufferedReader.
                    (java.io.InputStreamReader. (.body res) "UTF-8"))]
       (try
         (loop []
           (when-let [line (.readLine rdr)]
             (emit! on-event line)
             (recur)))
         (finally (.close rdr)))
       {:status (.statusCode res)})

     :default
     ;; cljgo lands here. `cljg.net.http`'s only shim is `-http-do`, which ends
     ;; in `io.ReadAll(resp.Body)` (pkg/bri/net_http.go) — the reader is closed
     ;; before Clojure ever sees the response, and there is no second entry
     ;; point. `(require-go '[net/http])` is accepted but interns nothing, so
     ;; the raw package is unreachable too. `cljg.io/sh` shelling out to `curl`
     ;; is also run-to-completion. There is no honest streaming route.
     (throw (ex-info (str "koine.stream/sse-post: no implementation for this host; "
                          "add a branch in koine/stream.cljc")
                     {:url url}))))
