(ns koine.codec
  "Base64, portable.

  Standard base64 with padding (RFC 4648 §4) — NOT the URL-safe alphabet. That
  is what MCP `image` / `blob` content blocks use, which is what this exists for.

  Strings are treated as UTF-8 both ways. `encode` also accepts a byte array
  (from `koine.fs/read-bytes`), which is the case that matters for binary
  content; `decode` returns a string and `decode-bytes` the raw bytes.

  The TRANSFORM is pure `clojure.core` (see `b64-encode-vals` below) — an
  alphabet substitution is arithmetic, not a host capability. Only two things
  are host-shaped and they are small: turning a string into UTF-8 byte values
  and back. Where a host already ships base64 over exactly the types we have
  (the JVM and cljgo), that route is used instead: it is faster, and it is one
  less thing to be subtly wrong about."
  (:require [clojure.string :as cstr])
  #?(:cljgo (:require [cljg.security :as sec])))

;; ------------------------------------------------------------------ pure

(def ^:private alphabet
  "RFC 4648 §4. A vector, so index -> char is O(1) on every host."
  (vec "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"))

(def ^:private inverse
  "char -> 6-bit value. `=` is padding and never reaches here."
  (into {} (map-indexed (fn [i c] [c i]) alphabet)))

(defn b64-encode-vals
  "Base64 of a seq of UNSIGNED byte values (0-255). Pure; identical everywhere."
  [vals]
  (let [v (vec vals)
        n (count v)]
    (loop [i 0, out []]
      (if (>= i n)
        (apply str out)
        (let [b0 (nth v i)
              b1 (if (< (+ i 1) n) (nth v (+ i 1)) 0)
              b2 (if (< (+ i 2) n) (nth v (+ i 2)) 0)
              trip (+ (* b0 65536) (* b1 256) b2)
              c0 (nth alphabet (bit-and (bit-shift-right trip 18) 63))
              c1 (nth alphabet (bit-and (bit-shift-right trip 12) 63))
              c2 (if (< (+ i 1) n) (nth alphabet (bit-and (bit-shift-right trip 6) 63)) \=)
              c3 (if (< (+ i 2) n) (nth alphabet (bit-and trip 63)) \=)]
          (recur (+ i 3) (conj out c0 c1 c2 c3)))))))

(defn b64-decode-vals
  "Base64 string -> a vector of UNSIGNED byte values. Pure."
  [s]
  (let [s (cstr/replace (str s) "=" "")
        n (count s)]
    (loop [i 0, out []]
      (if (>= i n)
        out
        (let [take-n (min 4 (- n i))
              val-at (fn [k]
                       (if (< k take-n)
                         (or (get inverse (nth s (+ i k)))
                             (throw (ex-info (str "koine.codec: not base64: " (pr-str s)) {:input s})))
                         0))
              quad (+ (* (val-at 0) 262144) (* (val-at 1) 4096) (* (val-at 2) 64) (val-at 3))
              b0 (bit-and (bit-shift-right quad 16) 255)
              b1 (bit-and (bit-shift-right quad 8) 255)
              b2 (bit-and quad 255)]
          (recur (+ i 4)
                 (cond-> (conj out b0)
                   (>= take-n 3) (conj b1)
                   (>= take-n 4) (conj b2))))))))

;; --------------------------------------------------------------- the seam
;;
;; Only string <-> UTF-8 bytes is host-shaped; the transform above is pure, so
;; a host without a base64 library still gets a correct implementation.

(defn- ->vals
  "A byte array / Go byte slice / seq of byte values as UNSIGNED values.
  `bit-and 255` folds the JVM's signed bytes and Go's unsigned ones together."
  [bs]
  (map (fn [b] (bit-and (long b) 255)) (seq bs)))

(defn encode
  "Base64-encode `x` — a string (UTF-8) or a byte array. Returns a string."
  [x]
  #?(:clj   (.encodeToString (java.util.Base64/getEncoder)
                             (if (string? x) (.getBytes ^String x "UTF-8") ^bytes x))
     :cljgo (sec/base64-encode x)
     :default (throw (ex-info "koine.codec/encode: no implementation for this host; add a branch in koine/codec.cljc" {}))))

(defn decode-bytes
  "Decode base64 string `s` to a byte array."
  [s]
  #?(:clj   (.decode (java.util.Base64/getDecoder) ^String (str s))
     :cljgo (sec/base64-decode-bytes (str s))
     :default (throw (ex-info "koine.codec/decode-bytes: no implementation for this host; add a branch in koine/codec.cljc"
                              {}))))

(defn decode
  "Decode base64 string `s` to a UTF-8 string. For binary payloads use
  `decode-bytes` — a byte that is not valid UTF-8 does not survive this."
  [s]
  #?(:clj   (String. ^bytes (decode-bytes s) "UTF-8")
     :cljgo (sec/base64-decode (str s))
     :default (throw (ex-info "koine.codec/decode: no implementation for this host; add a branch in koine/codec.cljc"
                              {}))))
