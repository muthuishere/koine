(ns koine.stream-test
  "Tests for koine.stream.

  Two halves, and they are not the same kind of test:

  * `parse-sse-line` is pure `clojure.core`, so it is tested exhaustively here
    and the assertions are byte-level. This is the part that must be identical
    on every host.
  * `sse-post` is a seam, and the only property worth asserting about it is
    that delivery is INCREMENTAL. Asserting \"received 4 events\" would pass
    against an implementation that buffers the whole body and replays it —
    which is exactly the failure this namespace exists to prevent. So the
    streaming test asserts ARRIVAL TIMES.

  `clojure.test` only runs on the JVM. The cross-host proof is
  `src/stream_check.cljc` (see its header for how to run it on all four)."
  (:require [clojure.test :refer [deftest is testing]]
            [koine.stream :as stream]))

;; ----------------------------------------------------------------- pure

(deftest parse-sse-line-data
  (testing "a data field, with the one framing space eaten"
    (is (= {:event nil :data "{\"delta\":\"hi\"}"}
           (stream/parse-sse-line "data: {\"delta\":\"hi\"}"))))
  (testing "the space after the colon is optional"
    (is (= {:event nil :data "x"} (stream/parse-sse-line "data:x"))))
  (testing "only ONE space is framing; the rest is data"
    (is (= {:event nil :data " x"} (stream/parse-sse-line "data:  x")))
    (is (= {:event nil :data "\tx"} (stream/parse-sse-line "data:\tx"))))
  (testing "an empty value is a value, not a blank line"
    (is (= {:event nil :data ""} (stream/parse-sse-line "data:")))
    (is (= {:event nil :data ""} (stream/parse-sse-line "data: "))))
  (testing "a colon-less line is a field with an empty value"
    (is (= {:event nil :data ""} (stream/parse-sse-line "data"))))
  (testing "later colons belong to the data — JSON depends on this"
    (is (= {:event nil :data "a: b"} (stream/parse-sse-line "data: a: b")))
    (is (= {:event nil :data "{\"a\":1,\"b\":\"c:d\"}"}
           (stream/parse-sse-line "data: {\"a\":1,\"b\":\"c:d\"}"))))
  (testing "the OpenAI sentinel is ordinary data — framing does not interpret it"
    (is (= {:event nil :data "[DONE]"} (stream/parse-sse-line "data: [DONE]"))))
  (testing "non-ASCII survives verbatim"
    (is (= {:event nil :data "café ☃"} (stream/parse-sse-line "data: café ☃")))))

(deftest parse-sse-line-event
  (is (= {:event "delta" :data nil} (stream/parse-sse-line "event: delta")))
  (is (= {:event "message" :data nil} (stream/parse-sse-line "event:message")))
  (is (= {:event "" :data nil} (stream/parse-sse-line "event:"))))

(deftest parse-sse-line-terminators
  (testing "accepts a line with or without its terminator"
    (is (= {:event nil :data "x"} (stream/parse-sse-line "data: x")))
    (is (= {:event nil :data "x"} (stream/parse-sse-line "data: x\n")))
    (is (= {:event nil :data "x"} (stream/parse-sse-line "data: x\r")))
    (is (= {:event nil :data "x"} (stream/parse-sse-line "data: x\r\n"))))
  (testing "only ONE terminator is stripped — a trailing blank is data"
    (is (= {:event nil :data "x\n"} (stream/parse-sse-line "data: x\n\n")))))

(deftest parse-sse-line-nil-cases
  (testing "blank lines are event boundaries, not values"
    (is (nil? (stream/parse-sse-line "")))
    (is (nil? (stream/parse-sse-line "\n")))
    (is (nil? (stream/parse-sse-line "\r\n"))))
  (testing "comments / keep-alives"
    (is (nil? (stream/parse-sse-line ":")))
    (is (nil? (stream/parse-sse-line ": ping")))
    (is (nil? (stream/parse-sse-line ":ok"))))
  (testing "fields the {:event :data} shape cannot carry"
    (is (nil? (stream/parse-sse-line "id: 42")))
    (is (nil? (stream/parse-sse-line "retry: 1000")))
    (is (nil? (stream/parse-sse-line "foo: bar"))))
  (testing "field names are exact — no prefix or case slop"
    (is (nil? (stream/parse-sse-line "datax: 1")))
    (is (nil? (stream/parse-sse-line "Data: 1")))
    (is (nil? (stream/parse-sse-line " data: 1"))))
  (testing "a non-string is not a line"
    (is (nil? (stream/parse-sse-line nil)))))

;; ------------------------------------------------------------ streaming
;; JVM only: `clojure.test` does not exist on the other three hosts, and the
;; server below is `com.sun.net.httpserver`. Cross-host coverage lives in
;; src/stream_check.cljc.

#?(:clj
   (do
     (def ^:private gap-ms 150)

     (defn- with-sse-server
       "Start an SSE server on an ephemeral port that streams whatever `emit`
       writes, call (f url), and always stop it. `emit` is handed a `write!`
       that flushes, so a test controls exactly when bytes leave the server."
       [emit f]
       (let [server (com.sun.net.httpserver.HttpServer/create
                     (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
         (.createContext
          server "/sse"
          (reify com.sun.net.httpserver.HttpHandler
            (handle [_ exchange]
              (.readAllBytes (.getRequestBody exchange))
              (.add (.getResponseHeaders exchange) "content-type" "text/event-stream")
              ;; 0 = chunked, unknown length: the only way to hold it open
              (.sendResponseHeaders exchange 200 0)
              (let [out (.getResponseBody exchange)]
                (emit (fn [^String s]
                        (.write out (.getBytes s "UTF-8"))
                        (.flush out)))
                (.close out))
              (.close exchange))))
         (.start server)
         (try
           (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/sse"))
           (finally (.stop server 0)))))

     (defn- emit-token-stream
       "3 delta events `gap-ms` apart, then [DONE]. The comment and `event:`
       lines are here so a client that mistakes either for data is caught."
       [w]
       (w ": ping\n\n")
       (dotimes [i 3]
         (Thread/sleep gap-ms)
         (w (str "event: delta\ndata: {\"delta\":\"tok" i "\"}\n\n")))
       (Thread/sleep gap-ms)
       (w "data: [DONE]\n\n"))

     (deftest sse-post-delivers-incrementally
       (with-sse-server emit-token-stream
         (fn [url]
           (let [t0  (System/nanoTime)
                 ms  #(quot (- (System/nanoTime) t0) 1000000)
                 log (atom [])
                 res (stream/sse-post url {"content-type" "application/json"} "{}"
                                      (fn [d] (swap! log conj [(ms) d])))
                 evs @log
                 arrivals (mapv first evs)]
             (testing "the stream terminates with a status"
               (is (= 200 (:status res))))
             (testing "every data line is delivered, [DONE] included, in order"
               (is (= ["{\"delta\":\"tok0\"}" "{\"delta\":\"tok1\"}"
                       "{\"delta\":\"tok2\"}" "[DONE]"]
                      (mapv second evs))))
             (testing "comment and event lines are not delivered as data"
               (is (= 4 (count evs))))
             ;; THE assertion. A buffering implementation produces the exact
             ;; same four events above and fails only here: it would deliver
             ;; them all at once, so the spread would be ~0 instead of ~450 ms.
             (testing "delivery is incremental, not a replay after buffering"
               (let [spread (- (last arrivals) (first arrivals))]
                 (is (>= spread (* 2 gap-ms))
                     (str "events arrived together (spread " spread
                          " ms) — the body was buffered, not streamed: "
                          (pr-str arrivals)))
                 (is (< (first arrivals) (- (last arrivals) (* 2 gap-ms)))
                     (str "the first event did not arrive early: "
                          (pr-str arrivals)))))))))

     (deftest sse-post-surfaces-nothing-for-a-comment-only-stream
       ;; a keep-alive-only stream must return cleanly, not hang or emit nil
       (with-sse-server
         (fn [w] (w ": a\n\n: b\n\n"))
         (fn [url]
           (let [log (atom [])
                 res (stream/sse-post url {} "" (fn [d] (swap! log conj d)))]
             (is (= 200 (:status res)))
             (is (= [] @log))))))

     (deftest sse-post-survives-a-rune-split-across-reads
       ;; 4000 snowmen = 12000 bytes on ONE data line, so a multi-byte rune is
       ;; guaranteed to straddle a read-buffer boundary. A client that decodes
       ;; each raw chunk to text before splitting lines mangles this into
       ;; replacement characters; splitting on the 0x0A *byte* and decoding
       ;; whole lines does not. (0x0A cannot occur inside a UTF-8 sequence.)
       (let [payload (apply str (repeat 4000 "☃"))]
         (with-sse-server
           (fn [w] (w (str "data: " payload "\n\ndata: [DONE]\n\n")))
           (fn [url]
             (let [log (atom [])
                   res (stream/sse-post url {} "" (fn [d] (swap! log conj d)))]
               (is (= 200 (:status res)))
               (is (= [payload "[DONE]"] @log)))))))

     (deftest sse-post-emits-a-final-line-with-no-terminator
       ;; a server that dies mid-frame still gave us a complete data line
       (with-sse-server
         (fn [w] (w "data: a\n\ndata: b"))
         (fn [url]
           (let [log (atom [])]
             (stream/sse-post url {} "" (fn [d] (swap! log conj d)))
             (is (= ["a" "b"] @log))))))))
