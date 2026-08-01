(ns koine.json
  "JSON for dual-host Clojure.

  ENCODE is pure portable Clojure — deliberately NOT delegated to a host
  library. Measured on 2026-07-27, Go's `encoding/json` and JVM
  `clojure.data.json` disagree on 4 of 6 basic payloads (key order, HTML
  escaping, float formatting, unicode escaping). Controlling those three
  choices IS the whole encoder, so we own it and both hosts agree by
  construction.

  DECODE is also pure portable Clojure. Delegating it looked free (parsing has
  no formatting choices to disagree about) until you count the cost: one parser
  per host to keep in agreement, and cljgo's is a private builtin that is not
  reachable at all."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------ encode
;; Three choices, each made once, so the two hosts cannot drift:
;;   1. object keys are SORTED   — Clojure map iteration order is unspecified
;;      for maps over 8 entries and differs between host implementations;
;;      sorting is the only order that is stable everywhere. (Go sorts too.)
;;   2. non-ASCII is emitted LITERALLY as UTF-8, never \uXXXX — valid JSON,
;;      smaller payloads, and matches Go.
;;   3. only the seven JSON control escapes plus <0x20 are escaped. HTML
;;      characters (< > &) are NOT escaped — that is a Go-specific default,
;;      not a JSON requirement.

(def ^:private escapes
  {\" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"
   \formfeed "\\f" \backspace "\\b"})

(defn- esc-char [c]
  (or (escapes c)
      (if (< (int c) 0x20)
        (format "\\u%04x" (int c))
        c)))

(defn- esc [s]
  (str/join (map esc-char (str s))))

(defn- key->str [k]
  (cond
    (keyword? k) (if-let [n (namespace k)] (str n "/" (name k)) (name k))
    (string? k)  k
    :else        (str k)))

;; ------------------------------------------------------- key ORDER is ours too
;;
;; Sorting is not enough: the two hosts do not agree on what sorted MEANS.
;; `sort` on strings compares UTF-16 code units on the JVM and UTF-8 bytes on Go.
;; Those agree across the whole BMP and diverge above it, because a supplementary
;; character is a SURROGATE PAIR on the JVM — and a lead surrogate (0xD800-0xDBFF)
;; is numerically BELOW U+FFFD, while the same character's UTF-8 bytes (F0 …) are
;; ABOVE U+FFFD's (EF …).
;;
;; Measured 2026-08-01, {"�" 1, "😀" 2}:
;;   jvm    {"😀":2,"�":1}
;;   cljgo  {"�":1,"😀":2}
;;
;; Byte-identical output is koine's whole reason to exist — consumers depend on
;; it for provider prompt caching, where a one-byte difference costs the cache
;; hit. Any emoji in a key was enough to break it.
;;
;; So koine picks the order rather than inheriting it: by CODE POINT, which is
;; also exactly UTF-8 byte order (UTF-8 is order-preserving), so it agrees with
;; Go and with every other language that sorts JSON keys by their encoded bytes.
;;
;; The scan is pure clojure.core and needs no host call. On the JVM `nth` yields
;; UTF-16 units, so a surrogate pair is recombined here; on cljgo `nth` already
;; yields whole runes, so the surrogate branch is never taken and the characters
;; pass straight through. One implementation, correct on both for different
;; reasons.

(defn- cp-compare
  "Lexicographic order over two code-point vectors: element by element, and a
  prefix sorts before its extension.

  This exists because `compare` on vectors is NOT lexicographic — clojure.core
  compares vectors by COUNT first, so `[97 114 116]` sorts AFTER `[99 111]` and
  \"artifacts\" landed after \"config\". koine 0.7.2 shipped exactly that bug
  while fixing the surrogate one, and every conformance case added with it used
  EQUAL-LENGTH keys, so none could see it. Ordinary ASCII keys of different
  lengths were mis-ordered on both hosts — consistently, which is worse, because
  the hosts agreed and the cross-host check stayed green."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (= i n)
        (compare (count a) (count b))
        (let [x (nth a i) y (nth b i)]
          (if (= x y) (recur (inc i)) (compare x y)))))))

(defn- code-points
  "`s` as a vector of code points. Portable: no host call, no interop."
  [s]
  (let [n (count s)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [c (int (nth s i))]
          (if (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) n))
            (let [lo (int (nth s (inc i)))]
              (if (and (>= lo 0xDC00) (<= lo 0xDFFF))
                (recur (+ i 2)
                       (conj acc (+ 0x10000
                                    (* 0x400 (- c 0xD800))
                                    (- lo 0xDC00))))
                (recur (inc i) (conj acc c))))
            (recur (inc i) (conj acc c))))))))

(declare write-str)

(defn- pair [[k v]]
  (str "\"" (esc (key->str k)) "\":" (write-str v)))

(defn write-str
  "Encode Clojure data as a JSON string. Object keys are sorted; non-ASCII is
  emitted literally. Deterministic and identical on every host."
  [x]
  (cond
    (nil? x)        "null"
    (true? x)       "true"
    (false? x)      "false"
    (string? x)     (str "\"" (esc x) "\"")
    (keyword? x)    (str "\"" (esc (key->str x)) "\"")
    (symbol? x)     (str "\"" (esc (str x)) "\"")
    (integer? x)    (str x)
    (number? x)     (let [s (str x)]
                      ;; a float always keeps its fraction: 1.0 stays "1.0",
                      ;; never "1" (which would change the JSON type).
                      (if (re-find #"[.eE]" s) s (str s ".0")))
    ;; sorted by CODE POINT with an explicit LEXICOGRAPHIC comparator — not the
    ;; host's string order (differs above the BMP) and not `compare` on the
    ;; vectors (length-first). Both traps are documented above.
    (map? x)        (str "{" (str/join "," (map pair (sort-by (comp code-points key->str first) cp-compare x))) "}")
    (or (sequential? x) (set? x))
    (str "[" (str/join "," (map write-str x)) "]")
    :else           (str "\"" (esc (str x)) "\"")))

;; ------------------------------------------------------------------ decode
;; Also pure clojure.core — NOT delegated to a host parser.
;;
;; Delegation was the original plan (parsing has no formatting choices to
;; disagree about, so it looked free). With both hosts it stops being free:
;; it would mean one parser per host to keep in agreement, and cljgo's decoder
;; is a private builtin that is not reachable at all. One core-only parser is
;; both smaller and more portable.
;;
;; State is [value index] over a string. No reader, no stream, no mutation.

(declare parse-value)

;; `parse-err`, not `err`: cljgo's clojure.core has an `err` var, so the shorter
;; name printed a "already refers to #'clojure.core/err … being replaced" warning
;; on every load there. A portability library must not warn on a supported host.
(defn- parse-err [i msg]
  (throw (ex-info (str "json: " msg " at index " i) {:index i})))

(defn- ws? [c] (or (= c \space) (= c \tab) (= c \newline) (= c \return)))

(defn- skip-ws [s i]
  (loop [i i] (if (and (< i (count s)) (ws? (nth s i))) (recur (inc i)) i)))

(defn- parse-lit [s i lit v]
  (if (and (<= (+ i (count lit)) (count s)) (= lit (subs s i (+ i (count lit)))))
    [v (+ i (count lit))]
    (parse-err i (str "expected " lit))))

(def ^:private unesc
  {\" \" \\ \\ \/ \/ \n \newline \r \return \t \tab \b \backspace \f \formfeed})

(def ^:private hex-digits "0123456789abcdef")

(defn- hex->int [s]
  (reduce (fn [acc c]
            (let [d (str/index-of hex-digits (str/lower-case (str c)))]
              (when-not d (parse-err 0 "bad \\u escape"))
              (+ (* 16 acc) d)))
          0 s))

(defn- parse-string [s i]
  (when (not= \" (nth s i)) (parse-err i "expected string"))
  (loop [i (inc i) acc []]
    (when (>= i (count s)) (parse-err i "unterminated string"))
    (let [c (nth s i)]
      (cond
        (= c \") [(apply str acc) (inc i)]
        (= c \\) (let [e (nth s (inc i))]
                   (if (= e \u)
                     (recur (+ i 6) (conj acc (char (hex->int (subs s (+ i 2) (+ i 6))))))
                     (recur (+ i 2) (conj acc (or (unesc e) (parse-err i "bad escape"))))))
        :else    (recur (inc i) (conj acc c))))))

(defn- parse-number [s i]
  (let [m (re-find #"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?" (subs s i))]
    (when-not m (parse-err i "bad number"))
    ;; the token is regex-validated, so read-string only ever sees a number.
    ;; read-string is clojure.core and behaves identically on every host.
    [(read-string m) (+ i (count m))]))

(defn- parse-seq
  "Shared comma-separated body for arrays and objects."
  [s i close read-item empty-acc]
  (loop [i (skip-ws s (inc i)) acc empty-acc]
    (when (>= i (count s)) (parse-err i "unterminated collection"))
    (if (= close (nth s i))
      [acc (inc i)]
      (let [[acc i'] (read-item s i acc)
            i'       (skip-ws s i')]
        (cond
          (= \, (nth s i'))  (recur (skip-ws s (inc i')) acc)
          (= close (nth s i')) [acc (inc i')]
          :else (parse-err i' (str "expected , or " close)))))))

(defn- read-elem [key-fn]
  (fn [s i acc]
    (let [[v i'] (parse-value s i key-fn)] [(conj acc v) i'])))

(defn- read-entry [key-fn]
  (fn [s i acc]
    (let [[k i'] (parse-string s i)
          i'     (skip-ws s i')
          _      (when (not= \: (nth s i')) (parse-err i' "expected :"))
          [v i'] (parse-value s (skip-ws s (inc i')) key-fn)]
      [(assoc acc (key-fn k) v) i'])))

;; key-fn is threaded as a plain parameter rather than held in a dynamic var,
;; and is APPLIED rather than compared. Two portability findings forced both:
;; `^:dynamic` is not honoured everywhere, and comparing two functions is not
;; portable either. Threading a parameter works anywhere and is less code.

(defn- parse-value [s i key-fn]
  (let [i (skip-ws s i)]
    (when (>= i (count s)) (parse-err i "unexpected end of input"))
    (let [c (nth s i)]
      (cond
        (= c \{) (parse-seq s i \} (read-entry key-fn) {})
        (= c \[) (parse-seq s i \] (read-elem key-fn) [])
        (= c \") (parse-string s i)
        (= c \t) (parse-lit s i "true" true)
        (= c \f) (parse-lit s i "false" false)
        (= c \n) (parse-lit s i "null" nil)
        :else    (parse-number s i)))))

(defn read-str
  "Parse a JSON string into Clojure data. Object keys become keywords unless
  `:key-fn` is supplied (pass `str` to keep them as strings)."
  ([s] (read-str s {}))
  ([s {:keys [key-fn] :or {key-fn keyword}}]
   (let [[v i] (parse-value s 0 key-fn)
         i     (skip-ws s i)]
     (when (< i (count s)) (parse-err i "trailing content"))
     v)))
