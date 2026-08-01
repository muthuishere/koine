;; s01 — does koine.json/write-str scale, and does key ORDERING dominate it?
;;
;; The suspicion, stated before measuring so the numbers can refute it:
;;   1. `sort-by` applies its key-fn PER COMPARISON, not once per element, so
;;      `code-points` may run O(n log n) times instead of O(n).
;;   2. `code-points` uses `nth` on a string. That is O(1) on the JVM (UTF-16
;;      array) but cljgo strings are rune-indexed, where `nth` may be O(n) —
;;      which would make each scan O(len^2) and the whole encode superlinear
;;      on ONE host only.
;;
;; Growth is what matters, not absolute ms: a fixed cost is fine, a rising
;; cost-per-key is not. Sizes chosen to make the shape visible, keys made
;; long enough that per-key scanning cannot hide.
(require 'koine.json 'koine.time 'koine.host)
(alias 'json 'koine.json) (alias 'ktime 'koine.time) (alias 'host 'koine.host)

(defn- payload [n]
  (into {} (map (fn [i] [(str "some-reasonably-long-key-name-" i) i]) (range n))))

(defn- timed [f] (let [t0 (ktime/mono-ms)] (f) (- (ktime/mono-ms) t0)))

(defn- bench [n reps]
  (let [m (payload n)]
    (timed (fn [] (dotimes [_ reps] (json/write-str m))))))

(println (str "host=" (name host/id)))
(println "   n     reps    total-ms    us/key   growth-vs-prev")
(loop [[n & more] [100 400 1600 6400] prev nil]
  (when n
    (let [reps (max 1 (quot 6400 n))
          ms   (bench n reps)
          per  (/ (* 1000.0 ms) (* n reps))
          g    (if prev (format "%.2fx" (/ per prev)) "-")]
      (println (format "%5d %6d %10d %9.2f   %s" n reps ms per g))
      (recur more per))))
