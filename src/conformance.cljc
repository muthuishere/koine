(require 'cljhost.json)
(alias 'json 'cljhost.json)
(def cases
  [["sorted"   (json/write-str {"b" 1 "a" 2 "c" 3})       "{\"a\":2,\"b\":1,\"c\":3}"]
   ["float"    (json/write-str {:f 1.0 :h 100.0})         "{\"f\":1.0,\"h\":100.0}"]
   ["html"     (json/write-str "a<b>c&d")                 "\"a<b>c&d\""]
   ["unicode"  (json/write-str "café ☃")                  "\"café ☃\""]
   ["escapes"  (json/write-str "a\tb\nc")                 "\"a\\tb\\nc\""]
   ["nested"   (json/write-str {:a {:b [1 2.0 nil true]}}) "{\"a\":{\"b\":[1,2.0,null,true]}}"]
   ["decode"   (json/read-str "{\"a\":1,\"b\":[1,2.5,null,true]}") {:a 1 :b [1 2.5 nil true]}]
   ["dec-uni"  (json/read-str "\"caf\\u00e9\"")           "café"]
   ["rt"       (json/read-str (json/write-str {:x [1 "y" nil]})) {:x [1 "y" nil]}]])
(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))
