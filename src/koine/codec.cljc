(ns koine.codec
  "Base64, portable.

  Here rather than in `koine.json` because it is a host seam, not a pure
  transform: the JVM has `java.util.Base64` and cljgo has `cljg.security`, and
  hand-rolling it in `clojure.core` would be slower and no more portable than
  either.

  Standard base64 with padding (RFC 4648 §4) — NOT the URL-safe alphabet.
  That is the alphabet MCP `image` / `blob` content blocks use, which is what
  this exists for.

  Strings are treated as UTF-8 both ways. `encode` also accepts a byte array
  (from `koine.fs/read-bytes`), which is the case that matters for binary
  content; `decode` returns a string and `decode-bytes` the raw bytes.

  Unblocked on cljgo by ADR 0110."
  #?(:cljgo (:require [cljg.security :as sec])))

(defn encode
  "Base64-encode `x` — a string (UTF-8) or a byte array. Returns a string."
  [x]
  #?(:clj   (.encodeToString (java.util.Base64/getEncoder)
                             (if (string? x) (.getBytes ^String x "UTF-8") ^bytes x))
     :cljgo (sec/base64-encode x)
     :default (throw (ex-info "koine.codec/encode: no implementation for this host; add a branch in koine/codec.cljc"
                              {}))))

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
  #?(:clj   (String. (decode-bytes s) "UTF-8")
     :cljgo (sec/base64-decode (str s))
     :default (throw (ex-info "koine.codec/decode: no implementation for this host; add a branch in koine/codec.cljc"
                              {}))))
