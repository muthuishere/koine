(ns cljhost.json
  "JSON for dual-host Clojure.

  ENCODE is pure portable Clojure — deliberately NOT delegated to a host
  library. Measured on 2026-07-27, Go's `encoding/json` and JVM
  `clojure.data.json` disagree on 4 of 6 basic payloads (key order, HTML
  escaping, float formatting, unicode escaping). Controlling those three
  choices IS the whole encoder, so we own it and both hosts agree by
  construction.

  DECODE is delegated to the host library: parsing is unambiguous, so there is
  nothing to normalise and no reason to hand-roll it."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str])
  #?(:cljgo (:require [cljg.net.http] [clojure.walk])))

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
    (map? x)        (str "{" (str/join "," (map pair (sort-by (comp key->str first) x))) "}")
    (or (sequential? x) (set? x))
    (str "[" (str/join "," (map write-str x)) "]")
    :else           (str "\"" (esc (str x)) "\"")))

;; ------------------------------------------------------------------ decode

(defn read-str
  "Parse a JSON string into Clojure data. Object keys become keywords unless
  `:key-fn` is supplied (pass `str` to keep them as strings).

  Delegated to the host's own parser — parsing has no formatting choices to
  disagree about."
  ([s] (read-str s {}))
  ([s {:keys [key-fn] :or {key-fn keyword}}]
   #?(:clj
      (let [read (requiring-resolve 'clojure.data.json/read-str)]
        (read s :key-fn key-fn))
      :cljgo
      ;; cljgo's JSON decoder is a PRIVATE builtin (-json-decode) and cannot be
      ;; called directly — verified 2026-07-27. cljg.net.http/json-body is the
      ;; public route to it: it decodes {:body s} from inside the namespace
      ;; where the builtin is visible, and keywordizes keys.
      (let [decoded (cljg.net.http/json-body {:body s})]
        (if (= key-fn keyword) decoded (clojure.walk/stringify-keys decoded)))
      :default
      (throw (ex-info "cljhost.json: no decoder for this host; add a branch in cljhost/json.cljc"
                      {:host :unknown})))))
