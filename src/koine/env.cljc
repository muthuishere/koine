(ns koine.env
  "Environment variables, portable.

  Values read here are frequently secrets (API keys expanded into MCP headers).
  Nothing in this namespace logs, prints or caches a value.")

;; cljgo needs cljg.system interned before `getenv` is reachable. A top-level
;; reader conditional with no branch for the other hosts reads as nothing there,
;; so no other dialect pays for this (same pattern as koine.time).
#?(:cljgo (require '[cljg.system]))

(defn- blank->nil
  "Go's os.Getenv returns \"\" for an unset variable where the JVM returns null.
  An empty string is TRUTHY in Clojure, so without this the `default` argument
  silently never fires on Go-hosted dialects."
  [v]
  (when (and v (not= "" (str v))) (str v)))

(defn get-env
  "The value of environment variable `name`, or nil / `default` when unset."
  ([name] (get-env name nil))
  ([name default]
   (or (blank->nil
        #?(:clj   (System/getenv (str name))
          ;; CLOSED 2026-07-30: cljgo grew `cljg.system/getenv` (a Go os.Getenv
          ;; shim), so this no longer needs `require-go '[os]` — which would
          ;; only work AOT and would put a host value on cljgo's nil-substituting
          ;; build-discovery path. cljg.system/getenv already returns nil (not
          ;; "") for an unset variable; blank->nil above is kept anyway, since
          ;; it also normalises a variable set to the empty string.
          :cljgo (cljg.system/getenv (str name))
          :default (throw (ex-info "koine.env/get-env: no implementation for this host; add a branch in koine/env.cljc"
                                   {:name (str name)}))))
       default)))

(defn- var-name
  "The legal environment-variable name starting at index `i` of `s`
  ([A-Za-z_][A-Za-z0-9_]*), or nil. Char classes are tested with explicit
  ranges rather than a regex: `Character/isLetterOrDigit` is Java, and regex
  behaviour is the least portable thing on these hosts."
  [s i]
  (let [n     (count s)
        head? (fn [c] (or (and (>= (int c) (int \A)) (<= (int c) (int \Z)))
                          (and (>= (int c) (int \a)) (<= (int c) (int \z)))
                          (= c \_)))
        rest? (fn [c] (or (head? c)
                          (and (>= (int c) (int \0)) (<= (int c) (int \9)))))]
    (when (and (< i n) (head? (nth s i)))
      (loop [j (inc i)]
        (if (and (< j n) (rest? (nth s j)))
          (recur (inc j))
          (subs s i j))))))

(defn expand
  "Replace every ${VAR} in `s` with its environment value. An unset variable
  expands to the empty string, matching the other toolnexus ports. A `${…}`
  whose contents are not a legal variable name is left verbatim.

  Hand-rolled rather than `str/replace` with a function replacement: cljgo's
  `clojure.string/replace` only accepts a string replacement and throws
  `replace expects a String, got: #object[fn]` on the function arity (measured
  2026-07-30). A scan over the string is plain `clojure.core`, so it is
  identical on every host — rule 3."
  [s]
  (let [s (str s)
        n (count s)]
    (loop [i 0, acc []]
      (if (>= i n)
        (apply str acc)
        (let [c (nth s i)]
          (if (and (= c \$) (< (inc i) n) (= (nth s (inc i)) \{))
            (let [v (var-name s (+ i 2))]
              (if (and v (< (+ i 2 (count v)) n) (= (nth s (+ i 2 (count v))) \}))
                (recur (+ i 3 (count v)) (conj acc (or (get-env v) "")))
                (recur (inc i) (conj acc c))))
            (recur (inc i) (conj acc c))))))))
