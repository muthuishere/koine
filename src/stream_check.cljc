;; stream_check.cljc — portability conformance for koine.stream.
;;
;; Unlike conformance.cljc this one needs a live server, because the property
;; under test is TIMING: an implementation that buffers the whole body and then
;; replays the lines produces exactly the same events as one that streams. Only
;; the arrival times tell them apart, so only the arrival times are asserted.
;;
;; Run it (from the repo root):
;;
;;   python3 test/sse_server.py 8791 &
;;   printf 'http://127.0.0.1:8791' > /tmp/koine-stream-base
;;   cd src
;;   timeout 30 clojure -Sdeps '{:paths ["."]}' -M stream_check.cljc
;;   timeout 30 lg stream_check.cljc
;;   GLJ_CLASSPATH=. timeout 30 glj stream_check.cljc
;;   timeout 30 cljgo run stream_check.cljc
;;
;; The base URL comes from a file rather than an env var on purpose: cljgo has
;; no environment access at all, and `slurp` is verified on all four hosts.
(require 'koine.stream 'koine.time 'clojure.string)
(alias 'stream 'koine.stream)
(alias 'ktime 'koine.time)
(alias 'cstr 'clojure.string)

;; --- pure: parse-sse-line. Runs everywhere, including hosts that cannot stream.
(def pure-cases
  [["data"        (stream/parse-sse-line "data: {\"delta\":\"hi\"}") {:event nil :data "{\"delta\":\"hi\"}"}]
   ["data-nospc"  (stream/parse-sse-line "data:x")                   {:event nil :data "x"}]
   ["data-2spc"   (stream/parse-sse-line "data:  x")                 {:event nil :data " x"}]
   ["data-empty"  (stream/parse-sse-line "data:")                    {:event nil :data ""}]
   ["data-done"   (stream/parse-sse-line "data: [DONE]")             {:event nil :data "[DONE]"}]
   ["data-colon"  (stream/parse-sse-line "data: a: b")               {:event nil :data "a: b"}]
   ["data-utf8"   (stream/parse-sse-line "data: café ☃")             {:event nil :data "café ☃"}]
   ["event"       (stream/parse-sse-line "event: delta")             {:event "delta" :data nil}]
   ["crlf"        (stream/parse-sse-line "data: x\r")                {:event nil :data "x"}]
   ["lf"          (stream/parse-sse-line "data: x\n")                {:event nil :data "x"}]
   ["crlf-both"   (stream/parse-sse-line "data: x\r\n")              {:event nil :data "x"}]
   ["blank"       (stream/parse-sse-line "")                         nil]
   ["blank-cr"    (stream/parse-sse-line "\r")                       nil]
   ["comment"     (stream/parse-sse-line ": ping")                   nil]
   ["comment-bare" (stream/parse-sse-line ":")                       nil]
   ["id-dropped"  (stream/parse-sse-line "id: 42")                   nil]
   ["retry-dropped" (stream/parse-sse-line "retry: 100")             nil]
   ["unknown"     (stream/parse-sse-line "foo: bar")                 nil]
   ["prefix"      (stream/parse-sse-line "datax: 1")                 nil]
   ["nofield"     (stream/parse-sse-line "data")                     {:event nil :data ""}]
   ["nil"         (stream/parse-sse-line nil)                        nil]])

;; --- streaming
(def base (cstr/trim (slurp "/tmp/koine-stream-base")))

(defn collect
  "Run one stream, returning {:ok? :status :events [[arrival-ms data] …]} or
  {:ok? false :msg …} on a host with no streaming route."
  [path headers]
  (let [log (atom [])]
    (try
      (let [t0  (ktime/mono-ms)
            res (stream/sse-post (str base path) headers "{}"
                                 (fn [d] (swap! log conj [(- (ktime/mono-ms) t0) d])))]
        {:ok? true :status (:status res) :events @log})
      ;; `Throwable` does not exist on Glojure, so catch Exception. And read the
      ;; message with `ex-message`: `(str e)` prints `#object[*lang.ExceptionInfo]`
      ;; on cljgo, which would hide the very text this check exists to assert.
      (catch Exception e {:ok? false :msg (or (ex-message e) (str e))}))))

(def main   (collect "/sse" {"content-type" "application/json"}))
;; empty headers is its own case: let-go's client panics on `:headers {}` (it
;; is neither nil nor walkable there), so koine has to omit the key.
(def bare   (when (:ok? main) (collect "/sse" {})))
;; 12000 bytes on one line — a rune is guaranteed to straddle a read boundary.
(def wide   (when (:ok? main) (collect "/utf8" {})))

(def want-data
  ["{\"delta\":\"tok0\"}" "{\"delta\":\"tok1\"}" "{\"delta\":\"tok2\"}" "[DONE]"])

(def stream-cases
  (if (:ok? main)
    (let [times (map first (:events main))
          spread (- (last times) (first times))
          big    (first (map second (:events wide)))]
      [["status"      (:status main)                  200]
       ["events"      (vec (map second (:events main))) want-data]
       ;; THE test. 4 events, 150 ms apart => a real stream spreads over
       ;; ~450 ms; a buffering impl delivers them all at once (spread ~0).
       ["incremental" (>= spread 300)                 true]
       ;; and the first datum must land long before the stream ends
       ["early-first" (< (first times) (- (last times) 300)) true]
       ["no-headers"  [(:status bare) (vec (map second (:events bare)))]
        [200 want-data]]
       ["utf8-len"    (count big)                     4000]
       ["utf8-edges"  [(subs big 0 1) (subs big (dec (count big)))] ["☃" "☃"]]
       ["utf8-done"   (vec (map second (:events wide))) [big "[DONE]"]]])
    ;; A host with no streaming route must fail LOUDLY and by name (rule 2).
    [["named-gap" (and (cstr/includes? (:msg main) "koine.stream/sse-post")
                       (cstr/includes? (:msg main) "no implementation for this host"))
      true]]))

(def cases (concat pure-cases stream-cases))

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails]
    (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (if (:ok? main)
    (println "  arrivals(ms):" (pr-str (vec (map first (:events main)))))
    (println "  no streaming route:" (:msg main)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))
