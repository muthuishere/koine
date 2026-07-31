(require 'koine.codec) (alias 'c 'koine.codec)
(def cases [["ascii" (c/encode "hello") "aGVsbG8="]
            ["utf8" (c/encode "hello ☃") "aGVsbG8g4piD"]
            ["pad1" (c/encode "a") "YQ=="]
            ["pad2" (c/encode "ab") "YWI="]
            ["none" (c/encode "abc") "YWJj"]
            ["empty" (c/encode "") ""]
            ["dec" (c/decode "aGVsbG8g4piD") "hello ☃"]
            ["dec-empty" (c/decode "") ""]
            ["rt" (c/decode (c/encode "café ☃ 日本")) "café ☃ 日本"]
            ["pure-enc" (c/b64-encode-vals [0 1 2 128 255 65]) "AAECgP9B"]
            ["pure-dec" (c/b64-decode-vals "AAECgP9B") [0 1 2 128 255 65]]])
(let [f (remove (fn [[_ g w]] (= g w)) cases)]
  (doseq [[l g w] f] (println " FAIL" l (pr-str g) "want" (pr-str w)))
  (println (str (- (count cases) (count f)) "/" (count cases) " pass")))
