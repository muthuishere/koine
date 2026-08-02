;; text_check.cljc — portability conformance for koine.text.
;;
;; The property under test is AGREEMENT ABOVE THE BMP. Every case here passes
;; trivially for ASCII on both hosts, which is exactly why this class survived
;; two koine releases and nine sites in the toolnexus port: a JVM string is
;; UTF-16 code units, a cljgo string is runes, and they agree across the whole
;; BMP and diverge above it.
;;
;;   cd src
;;   clojure -Sdeps '{:paths ["."]}' -M text_check.cljc
;;   cljgo run text_check.cljc
(require 'koine.text 'koine.host)
(alias 'text 'koine.text)
(alias 'host 'koine.host)

(def emoji "😀")   ; U+1F600, supplementary — a surrogate PAIR on the JVM
(def pua   "")         ; U+E000, private use — inside the BMP, ABOVE U+D800

(def cases
  [;; --- code-points: one vector for the same text, whatever the host stores
   ["emoji is ONE code point"        (text/code-points emoji)      [0x1F600]]
   ["pua is one code point"          (text/code-points pua)        [0xE000]]
   ["ascii"                          (text/code-points "abc")      [97 98 99]]
   ["mixed"                          (text/code-points (str "a" emoji "b"))
    [97 0x1F600 98]]
   ["empty"                          (text/code-points "")         []]
   ;; a lone lead surrogate is PASSED THROUGH, not dropped and not replaced
   ["lone surrogate survives"        (count (text/code-points "\uD83D")) 1]

   ;; --- compare: the SIGN must match on both hosts, and it is the sign that
   ;; differed. U+1F600 > U+E000 by code point; the JVM's raw compare says the
   ;; opposite because the emoji's LEAD SURROGATE (U+D83D) is below U+E000.
   ["emoji AFTER pua by code point"  (pos? (text/compare-strings emoji pua))    true]
   ["pua BEFORE emoji"               (neg? (text/compare-strings pua emoji))    true]
   ["equal is zero"                  (text/compare-strings emoji emoji)         0]
   ["ascii ordering"                 (neg? (text/compare-strings "a" "b"))      true]

   ;; --- the 0.7.2 regression: a PREFIX must sort before its extension, and
   ;; `clojure.core/compare` on vectors orders by COUNT first, which put
   ;; "artifacts" after "config". Differing lengths are the discriminator; every
   ;; case added with that bug used equal-length keys.
   ["prefix before extension"        (neg? (text/compare-strings "art" "artifacts")) true]
   ["artifacts before config"        (neg? (text/compare-strings "artifacts" "config")) true]
   ["longer-but-smaller sorts first" (text/sort-strings ["config" "artifacts"])
    ["artifacts" "config"]]

   ;; --- sort-strings: full order, spanning the BMP boundary
   ["sort by code point"             (text/sort-strings [emoji "a" pua "B"])
    ["B" "a" pua emoji]]
   ["sort is idempotent"             (text/sort-strings (text/sort-strings [emoji "a" pua]))
    ["a" pua emoji]]
   ["sort empty"                     (text/sort-strings [])              []]

   ;; --- utf8-length: what a wire protocol actually asks. `count` answers in
   ;; UTF-16 units on the JVM and runes on cljgo — two different wrong answers.
   ["ascii bytes"                    (text/utf8-length "abc")            3]
   ["2-byte (e-acute)"               (text/utf8-length "é")              2]
   ["3-byte (snowman)"               (text/utf8-length "☃")         3]
   ["4-byte (emoji)"                 (text/utf8-length emoji)            4]
   ["mixed bytes"                    (text/utf8-length (str "héllo" emoji)) 10]
   ["empty is zero"                  (text/utf8-length "")               0]
   ;; and the discriminator against `count`: on ONE host these coincide
   ["utf8-length differs from count for the emoji"
    (= (text/utf8-length emoji) (count emoji))                           false]])

(let [fails (remove (fn [[_ g w]] (= g w)) cases)]
  (doseq [[l g w] fails] (println "  FAIL" l "got" (pr-str g) "want" (pr-str w)))
  (println "host" (pr-str host/id))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))
