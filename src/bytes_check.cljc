;; bytes_check.cljc — portability conformance for the BINARY seams:
;; koine.fs/read-bytes + write-bytes and koine.codec (base64).
;;
;; The property under test is that bytes survive a round trip UNCHANGED,
;; including the ones text cannot carry: 0x00, 0xFF, a lone 0x80 (never legal
;; UTF-8), and a byte sequence that decodes to the replacement rune. If a host
;; routes bytes through a string anywhere, these cases corrupt and the equal
;; assertions fail.
;;
;; Element SIGN is asserted explicitly. The JVM's byte[] is signed, so 0x80 is
;; -128 and 0xFF is -1; a host returning 128 and 255 would compose wrongly with
;; every JVM consumer while looking fine in isolation.
(require 'koine.fs 'koine.codec 'koine.process 'koine.host)
(alias 'host 'koine.host)
(alias 'fs 'koine.fs)
(alias 'codec 'koine.codec)
(alias 'proc 'koine.process)

;; Byte I/O and byte base64 are gated on the capability rather than assumed, so
;; a host without them skips instead of failing for a documented gap.
(def bytes? (host/supports? :fs/bytes))
(def b64-bytes? (host/supports? :codec/base64-bytes))

(def root "/tmp/koine-bytes-check")
(proc/sh ["rm" "-rf" root])
(proc/sh ["mkdir" "-p" root])

(def bin (str root "/blob.bin"))

;; every byte 0-255 exactly once, written as the signed values the JVM uses
(def all-bytes (byte-array (map (fn [i] (if (> i 127) (- i 256) i)) (range 256))))

(when bytes? (fs/write-bytes bin all-bytes))
(def read-back (when bytes? (fs/read-bytes bin)))

;; a payload that is NOT valid UTF-8, so the text route provably cannot carry it
(def raw (byte-array [0 1 2 -128 -1 65]))
(def raw-path (str root "/raw.bin"))
(when bytes? (fs/write-bytes raw-path raw))

(def b64-str  (codec/encode "hello ☃"))
(def b64-bin  (when b64-bytes? (codec/encode raw)))

(def cases
  [["roundtrip-len"    (when bytes? (count (vec read-back)))            (when bytes? 256)]
   ["roundtrip-bytes"  (when bytes? (vec read-back))                (when bytes? (vec all-bytes))]
   ["signed-0x80"      (when bytes? (nth (vec read-back) 128))      (when bytes? -128)]
   ["signed-0xff"      (when bytes? (nth (vec read-back) 255))      (when bytes? -1)]
   ["zero-byte-kept"   (when bytes? (nth (vec read-back) 0))        (when bytes? 0)]

   ["raw-roundtrip"    (when bytes? (vec (fs/read-bytes raw-path))) (when bytes? [0 1 2 -128 -1 65])]
   ["overwrite"        (when bytes? (do (fs/write-bytes raw-path (byte-array [9]))
                                        (vec (fs/read-bytes raw-path))))  (when bytes? [9])]
   ["empty-file"       (when bytes? (do (fs/write-bytes (str root "/e.bin") (byte-array 0))
                                        (count (vec (fs/read-bytes (str root "/e.bin")))))) (when bytes? 0)]

   ;; base64 — standard alphabet, padded (RFC 4648 §4), NOT url-safe
   ["b64-ascii"        (codec/encode "hello")                      "aGVsbG8="]
   ["b64-utf8"         b64-str                                     "aGVsbG8g4piD"]
   ["b64-roundtrip"    (codec/decode b64-str)                      "hello ☃"]
   ["b64-empty"        (codec/encode "")                           ""]
   ["b64-padding"      (codec/encode "a")                          "YQ=="]
   ["b64-binary"       b64-bin                                     (when b64-bytes? "AAECgP9B")]
   ["b64-binary-rt"    (when b64-bytes? (vec (codec/decode-bytes b64-bin)))
                                                                   (when b64-bytes? [0 1 2 -128 -1 65])]
   ["b64-not-urlsafe"  (when b64-bytes? (codec/encode (byte-array [-5 -1 -66])))
                                                                   (when b64-bytes? "+/++")]

   ;; the two seams compose: file -> bytes -> base64 -> bytes -> file
   ["compose"          (when (and bytes? b64-bytes?)
                         (do (fs/write-bytes (str root "/c.bin")
                                             (codec/decode-bytes (codec/encode all-bytes)))
                             (vec (fs/read-bytes (str root "/c.bin")))))
                                                                   (when (and bytes? b64-bytes?) (vec all-bytes))]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass"
                (when-not bytes? " (byte I/O SKIPPED: unsupported on this host)"))))

(proc/sh ["rm" "-rf" root])
