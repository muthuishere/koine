(ns koine.time
  "Wall-clock time, monotonic elapsed time and sleeping, portable.

  toolnexus SPEC §8 stamps a `ms` duration on every llm/tool/run metric event
  and its retry policy sleeps between attempts, so durations are load-bearing:
  measure them with `mono-ms`/`elapsed-ms` (never with `now-ms`, which the
  operating system can move backwards) and stamp events with `now-ms`."
  (:require [clojure.string :as cstr]))

;; cljgo needs cljg.date / cljg.system interned before `now`, `nano-time` and
;; `sleep` are reachable. A top-level reader conditional with no branch for the
;; other hosts reads as nothing there (verified on both hosts), so no other
;; dialect pays for this.
#?(:cljgo (require '[cljg.date] '[cljg.system]))

(defn now-ms
  "Wall-clock time in milliseconds since the Unix epoch, as a long.

  Suitable for timestamps. NOT suitable for measuring a duration — use
  `mono-ms`."
  []
  (long
   #?(:clj   (System/currentTimeMillis)
      ;; cljgo: `cljg.date/now` is the public epoch-millis fn (a
      ;; time.Now().UnixMilli() shim). cljg.os/now is the older cron-facing
      ;; spelling of the same thing; cljg.date is where the clock lives now.
      :cljgo (cljg.date/now)
      :default (throw (ex-info "koine.time/now-ms: no implementation for this host; add a branch in koine/time.cljc" {})))))

(defn mono-ms
  "A monotonic elapsed-time counter in milliseconds, as a long.

  Only DIFFERENCES between two readings are meaningful — the origin differs
  per host. Guaranteed never to go backwards.

  All three hosts have a TRUE monotonic source: System/nanoTime on the JVM,
  and `cljg.date/nano-time` on cljgo. No host needs the wall-clock fallback koine
  used to carry."
  []
  (long
   #?(:clj   (quot (System/nanoTime) 1000000)
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

;; ------------------------------------------------------------ the wire format
;;
;; PURE. There is no reader conditional below this line, and that is deliberate:
;; the calendar is arithmetic, not a host capability. Rule 3 — if it is
;; expressible in `clojure.core`, it is not a seam. The earlier version branched
;; on `java.time.Instant` and `cljg.date`, which meant two implementations to
;; keep byte-identical. One pure implementation serves every host and cannot
;; drift.
;;
;; The civil-from-days algorithm is Howard Hinnant's, which is exact for the
;; proleptic Gregorian calendar over the whole range a 64-bit millisecond count
;; can express — no lookup tables, no leap-second fudge (Unix time has none).

;; `Math/floor` is Java on the JVM and a different spelling elsewhere, so floor
;; division is done with `quot` and a correction — integer arithmetic only.
(defn- floor-div [a b]
  (let [q (quot a b)]
    (if (and (neg? (bit-xor (long a) (long b))) (not= a (* q b))) (dec q) q)))

(defn- floor-mod [a b] (- a (* b (floor-div a b))))

(defn- civil-from-days
  "Days since 1970-01-01 -> [year month day] (proleptic Gregorian)."
  [z]
  (let [z    (+ z 719468)
        era  (floor-div z 146097)
        doe  (- z (* era 146097))                                  ; [0, 146096]
        yoe  (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y    (+ yoe (* era 400))
        doy  (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp   (quot (+ (* 5 doy) 2) 153)
        d    (+ (- doy (quot (+ (* 153 mp) 2) 5)) 1)
        m    (+ mp (if (< mp 10) 3 -9))]
    [(if (<= m 2) (inc y) y) m d]))

(defn- days-from-civil
  "[year month day] -> days since 1970-01-01. The exact inverse."
  [y m d]
  (let [y   (if (<= m 2) (dec y) y)
        era (floor-div y 400)
        yoe (- y (* era 400))
        mp  (if (> m 2) (- m 3) (+ m 9))
        doy (+ (quot (+ (* 153 mp) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn- pad
  "`n` as a decimal string, left-padded with zeros to `width`. `format` is not
  used: its %02d works on both hosts, but building the string by hand keeps
  this function free of any host formatting behaviour at all."
  [n width]
  (let [s (str n)]
    (if (>= (count s) width) s (str (apply str (repeat (- width (count s)) "0")) s))))

(defn- fmt-iso
  "Epoch millis -> ISO-8601 UTC. Millisecond precision, matching
  `java.time.Instant/toString`: the fractional part is omitted on a whole second
  and is exactly three digits otherwise."
  [millis]
  (let [days      (floor-div millis 86400000)
        ms-of-day (floor-mod millis 86400000)
        [y m d]   (civil-from-days days)
        secs      (quot ms-of-day 1000)
        frac      (rem ms-of-day 1000)]
    (str (pad y 4) "-" (pad m 2) "-" (pad d 2) "T"
         (pad (quot secs 3600) 2) ":" (pad (quot (rem secs 3600) 60) 2) ":"
         (pad (rem secs 60) 2)
         (when (pos? frac) (str "." (pad frac 3)))
         "Z")))

(defn- digits?
  [s] (and (seq s) (every? (fn [c] (and (>= (int c) (int \0)) (<= (int c) (int \9)))) s)))

(defn- to-int
  "Parse a run of ASCII digits. `parse-long` is 1.11+ on the JVM and absent on
  some hosts; this is arithmetic that works everywhere."
  [s]
  (reduce (fn [acc c] (+ (* 10 acc) (- (int c) (int \0)))) 0 (seq s)))

(defn- scan-iso
  "ISO-8601 / RFC 3339 -> epoch millis. Accepts `Z`, `+HH:MM` / `-HH:MM` (with or
  without the colon), an optional fractional part of any length (truncated to
  milliseconds, not rounded — same as java.time), and a space in place of `T`."
  [s]
  (let [bad! (fn [] (throw (ex-info (str "koine.time/parse-iso: not an ISO-8601 instant: " (pr-str s))
                                    {:input s})))
        n    (count s)]
    (when (< n 20) (bad!))
    (let [date (subs s 0 10)
          sep  (nth s 10)
          rest (subs s 11)]
      (when-not (and (= \- (nth date 4)) (= \- (nth date 7))
                     (digits? (subs date 0 4)) (digits? (subs date 5 7)) (digits? (subs date 8 10))
                     (or (= \T sep) (= \t sep) (= \space sep)))
        (bad!))
      (let [;; split the offset off the tail
            [body offset]
            (cond
              (or (= \Z (nth rest (dec (count rest)))) (= \z (nth rest (dec (count rest)))))
              [(subs rest 0 (dec (count rest))) 0]

              :else
              (let [i (max (or (cstr/last-index-of rest "+") -1)
                           (or (cstr/last-index-of rest "-") -1))]
                (when (neg? i) (bad!))
                (let [sign (if (= \+ (nth rest i)) 1 -1)
                      off  (subs rest (inc i))
                      off  (if (= 5 (count off)) (str (subs off 0 2) (subs off 3)) off)]
                  (when-not (and (= 4 (count off)) (digits? off)) (bad!))
                  [(subs rest 0 i)
                   (* sign (+ (* 3600000 (to-int (subs off 0 2)))
                              (* 60000 (to-int (subs off 2)))))])))
            [hms frac] (let [i (or (cstr/index-of body ".") -1)]
                         (if (neg? i) [body 0]
                             [(subs body 0 i)
                              (let [f (subs body (inc i))]
                                (when-not (digits? f) (bad!))
                                (to-int (subs (str f "000") 0 3)))]))]
        (when-not (and (= 8 (count hms)) (= \: (nth hms 2)) (= \: (nth hms 5))
                       (digits? (subs hms 0 2)) (digits? (subs hms 3 5)) (digits? (subs hms 6 8)))
          (bad!))
        (let [y (to-int (subs date 0 4)) mo (to-int (subs date 5 7)) d (to-int (subs date 8 10))
              h (to-int (subs hms 0 2)) mi (to-int (subs hms 3 5)) sec (to-int (subs hms 6 8))]
          (when-not (and (<= 1 mo 12) (<= 1 d 31) (<= 0 h 23) (<= 0 mi 59) (<= 0 sec 60)) (bad!))
          (- (+ (* 86400000 (days-from-civil y mo d))
                (* 3600000 h) (* 60000 mi) (* 1000 sec) frac)
             offset))))))

;;
;; Epoch millis is the internal currency (it is what `now-ms` returns and what
;; every host can hold as a long); ISO-8601 is what protocols actually carry.
;; Only these two conversions are portable, and deliberately nothing else: a
;; pattern-based `format` is NOT here, because Go's layout strings ("2006-01-02")
;; and java.time's patterns ("yyyy-MM-dd") are different languages and koine
;; will not pretend one is the other. Unblocked on cljgo by ADR 0110.

(defn iso-str
  "An instant — epoch milliseconds, defaulting to now — as an ISO-8601 / RFC 3339
  UTC string.

  Millisecond precision, matching `java.time.Instant/toString`: no fractional
  part on a whole second, exactly three digits otherwise. `(iso-str 0)` is
  \"1970-01-01T00:00:00Z\" and `(iso-str 1500)` is \"1970-01-01T00:00:01.500Z\"
  on every host."
  ([] (iso-str (now-ms)))
  ([millis] (fmt-iso (long millis))))

(defn parse-iso
  "An ISO-8601 / RFC 3339 timestamp as epoch milliseconds, as a long.

  The fractional part is optional and an offset is honoured rather than
  dropped, so \"…T12:00:00+05:30\" and \"…T06:30:00Z\" parse equal. Throws,
  naming the input, when the string is not an instant."
  [s]
  (long (scan-iso (str s))))
