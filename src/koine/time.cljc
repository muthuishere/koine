(ns koine.time
  "Wall-clock time, monotonic elapsed time and sleeping, portable.

  toolnexus SPEC §8 stamps a `ms` duration on every llm/tool/run metric event
  and its retry policy sleeps between attempts, so durations are load-bearing:
  measure them with `mono-ms`/`elapsed-ms` (never with `now-ms`, which the
  operating system can move backwards) and stamp events with `now-ms`.")

;; cljgo needs cljg.date / cljg.system interned before `now`, `nano-time` and
;; `sleep` are reachable. A top-level reader conditional with no branch for the
;; other hosts reads as nothing there (verified on all four hosts), so no other
;; dialect pays for this.
#?(:cljgo (require '[cljg.date] '[cljg.system]))

(def ^:private mono-floor
  "Highest value `mono-ms` has ever returned, on hosts where the underlying
  clock is wall-clock-derived and can therefore step backwards (let-go,
  cljgo). Clamping here is what makes the 'never goes backwards' guarantee
  true everywhere rather than only on the JVM and Glojure."
  (atom 0))

(defn- clamp-mono
  "Return `v`, or the previous high-water mark when the clock stepped back."
  [v]
  (swap! mono-floor (fn [floor] (if (> v floor) v floor))))

(def ^:private glj-anchor
  "Glojure only: the `time.Time` this process anchors elapsed time on. Go's
  Time carries a monotonic reading, so `time.Since` on it is a true monotonic
  stopwatch. Seeded atomically on first use — `swap!` returns the winning
  value, so concurrent first calls share one anchor and cannot reset it."
  (atom nil))

(defn now-ms
  "Wall-clock time in milliseconds since the Unix epoch, as a long.

  Suitable for timestamps. NOT suitable for measuring a duration — use
  `mono-ms`."
  []
  (long
   #?(:clj   (System/currentTimeMillis)
      ;; let-go ships Java-shaped shims; System/currentTimeMillis is
      ;; time.Now().UnixMilli() under the hood (pkg/rt/system.go:116).
      :lg    (System/currentTimeMillis)
      ;; Glojure exposes Go's stdlib directly, `/` munged to `:`; Go methods
      ;; are ordinary interop.
      :glj   (.UnixMilli (time.Now))
      ;; cljgo: `cljg.date/now` is the public epoch-millis fn (a
      ;; time.Now().UnixMilli() shim). cljg.os/now is the older cron-facing
      ;; spelling of the same thing; cljg.date is where the clock lives now.
      :cljgo (cljg.date/now)
      :default (throw (ex-info "koine.time/now-ms: no implementation for this host; add a branch in koine/time.cljc" {})))))

(defn mono-ms
  "A monotonic elapsed-time counter in milliseconds, as a long.

  Only DIFFERENCES between two readings are meaningful — the origin differs
  per host. Guaranteed never to go backwards.

  JVM (System/nanoTime), Glojure (Go's monotonic clock, via time.Since on a
  process anchor) and cljgo (`cljg.date/nano-time`, monotonic nanos since
  process start) have a true monotonic source. let-go does NOT: its
  System/nanoTime is `time.Now().UnixNano()`, i.e. wall clock with no monotonic
  reading (pkg/rt/system.go:121). There it FALLS BACK to wall clock, clamped to
  a high-water mark so a backwards clock step shows as zero elapsed rather than
  a negative duration."
  []
  (long
   #?(:clj   (quot (System/nanoTime) 1000000)
      :lg    (clamp-mono (System/currentTimeMillis))   ; fallback: wall clock
      :glj   (.Milliseconds (time.Since (swap! glj-anchor (fn [a] (or a (time.Now))))))
      ;; cljgo: a real monotonic reading (Go's monotonic clock) since 2026-07-30
      ;; — no clamp needed, and unlike -nano-time this one is public.
      :cljgo (quot (cljg.date/nano-time) 1000000)
      :default (throw (ex-info "koine.time/mono-ms: no implementation for this host; add a branch in koine/time.cljc" {})))))

(defn sleep!
  "Block the current thread for `ms` milliseconds. Returns nil.

  A non-positive `ms` returns immediately (the JVM's Thread/sleep throws on a
  negative argument; the Go-hosted dialects return at once — koine normalises
  to the latter)."
  [ms]
  (let [ms (long ms)]
    (when (pos? ms)
      #?(:clj   (Thread/sleep ms)
         ;; let-go has no Thread class; `sleep` is a core fn taking ms.
         :lg    (sleep ms)
         ;; Go's time.Sleep takes a Duration, which is int64 NANOseconds.
         :glj   (time.Sleep (* ms 1000000))
         ;; cljgo has no Thread class, but `cljg.system/sleep` is the public
         ;; time.Sleep shim (ms). It replaces the old parking-on-a-core.async
         ;; `timeout` channel trick, which worked but spun up a channel to sleep.
         :cljgo (cljg.system/sleep ms)
         :default (throw (ex-info "koine.time/sleep!: no implementation for this host; add a branch in koine/time.cljc" {})))))
  nil)

(defn elapsed-ms
  "Milliseconds elapsed since `start`, which must be a `mono-ms` reading."
  [start]
  (- (mono-ms) (long start)))
