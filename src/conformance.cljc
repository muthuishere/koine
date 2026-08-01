(require 'koine.json)
(alias 'json 'koine.json)
(def cases
  [["sorted"   (json/write-str {"b" 1 "a" 2 "c" 3})       "{\"a\":2,\"b\":1,\"c\":3}"]
   ["float"    (json/write-str {:f 1.0 :h 100.0})         "{\"f\":1.0,\"h\":100.0}"]
   ["html"     (json/write-str "a<b>c&d")                 "\"a<b>c&d\""]
   ["unicode"  (json/write-str "café ☃")                  "\"café ☃\""]
   ["escapes"  (json/write-str "a\tb\nc")                 "\"a\\tb\\nc\""]
   ["nested"   (json/write-str {:a {:b [1 2.0 nil true]}}) "{\"a\":{\"b\":[1,2.0,null,true]}}"]
   ["decode"   (json/read-str "{\"a\":1,\"b\":[1,2.5,null,true]}") {:a 1 :b [1 2.5 nil true]}]
   ["dec-uni"  (json/read-str "\"caf\\u00e9\"")           "café"]
   ["rt"       (json/read-str (json/write-str {:x [1 "y" nil]})) {:x [1 "y" nil]}]

   ;; ---- key ORDER above the BMP: the case that made "sorted" meaningless ----
   ;;
   ;; Every case above lives inside the BMP, where UTF-16 code-unit order and
   ;; UTF-8 byte order happen to agree — so they could never tell the hosts
   ;; apart. Cross the BMP boundary and they disagree: on the JVM a
   ;; supplementary character is a surrogate pair whose lead unit (0xD800-0xDBFF)
   ;; is BELOW U+FFFD, while its UTF-8 bytes (F0 …) are ABOVE U+FFFD's (EF …).
   ;;
   ;; Measured before the fix: jvm {"😀":2,"�":1} / cljgo {"�":1,"😀":2}.
   ;; Byte-identity is koine's reason to exist, and one emoji in a key broke it.
   ;;
   ;; The expected value is DERIVED, not snapshotted: koine's rule is code-point
   ;; order, U+FFFD is 65533 and U+1F600 is 128512, so U+FFFD comes first. That
   ;; matters — an expectation copied from koine's own output would enshrine
   ;; whatever koine currently does, including a bug. (Point taken from the
   ;; toolnexus port, which had a payload consistent across three runtimes and
   ;; wrong on all of them.)
   ["order-supplementary" (json/write-str {"�" 1 "😀" 2})
    "{\"�\":1,\"😀\":2}"]
   ;; and the ASCII/supplementary pair, same rule: "a" is 97, so it leads
   ["order-ascii-vs-supp" (json/write-str {"😀" 2 "a" 1})
    "{\"a\":1,\"😀\":2}"]
   ;; a supplementary character as a VALUE must still round-trip unharmed
   ["supp-roundtrip" (json/read-str (json/write-str {:k "😀"}))  {:k "😀"}]])
(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))
