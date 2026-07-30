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
(require 'koine.fs 'koine.codec 'koine.process)
(alias 'fs 'koine.fs)
(alias 'codec 'koine.codec)
(alias 'proc 'koine.process)

(def root "/tmp/koine-bytes-check")
(proc/sh ["rm" "-rf" root])
(proc/sh ["mkdir" "-p" root])

(def bin (str root "/blob.bin"))

;; every byte 0-255 exactly once, written as the signed values the JVM uses
(def all-bytes (byte-array (map (fn [i] (if (> i 127) (- i 256) i)) (range 256))))

(fs/write-bytes bin all-bytes)
(def read-back (fs/read-bytes bin))

;; a payload that is NOT valid UTF-8, so the text route provably cannot carry it
(def raw (byte-array [0 1 2 -128 -1 65]))
(def raw-path (str root "/raw.bin"))
(fs/write-bytes raw-path raw)

(def b64-str  (codec/encode "hello ☃"))
(def b64-bin  (codec/encode raw))

(def cases
  [["roundtrip-len"    (alength read-back)                         256]
   ["roundtrip-bytes"  (vec read-back)                             (vec all-bytes)]
   ["signed-0x80"      (nth (vec read-back) 128)                   -128]
   ["signed-0xff"      (nth (vec read-back) 255)                   -1]
   ["zero-byte-kept"   (nth (vec read-back) 0)                     0]

   ["raw-roundtrip"    (vec (fs/read-bytes raw-path))              [0 1 2 -128 -1 65]]
   ["overwrite"        (do (fs/write-bytes raw-path (byte-array [9]))
                           (vec (fs/read-bytes raw-path)))         [9]]
   ["empty-file"       (do (fs/write-bytes (str root "/e.bin") (byte-array 0))
                           (alength (fs/read-bytes (str root "/e.bin")))) 0]

   ;; base64 — standard alphabet, padded (RFC 4648 §4), NOT url-safe
   ["b64-ascii"        (codec/encode "hello")                      "aGVsbG8="]
   ["b64-utf8"         b64-str                                     "aGVsbG8g4piD"]
   ["b64-roundtrip"    (codec/decode b64-str)                      "hello ☃"]
   ["b64-empty"        (codec/encode "")                           ""]
   ["b64-padding"      (codec/encode "a")                          "YQ=="]
   ["b64-binary"       b64-bin                                     "AAECgP9B"]
   ["b64-binary-rt"    (vec (codec/decode-bytes b64-bin))          [0 1 2 -128 -1 65]]
   ["b64-not-urlsafe"  (codec/encode (byte-array [-5 -1 -66]))     "+/++"]

   ;; the two seams compose: file -> bytes -> base64 -> bytes -> file
   ["compose"          (do (fs/write-bytes (str root "/c.bin")
                                           (codec/decode-bytes (codec/encode all-bytes)))
                           (vec (fs/read-bytes (str root "/c.bin")))) (vec all-bytes)]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))

(proc/sh ["rm" "-rf" root])
