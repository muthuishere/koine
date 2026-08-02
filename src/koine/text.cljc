(ns koine.text
  "Code points, ordering and UTF-8 length — portable, and identical on every host.

  **Why this is in koine at all**, given that koine is the floor and anything
  expressible in `clojure.core` stays there: because `clojure.core` gives
  DIFFERENT ANSWERS on the two hosts. That is the definition of a seam, and it
  is prime directive 5 — a capability that can produce differing output across
  hosts gets normalised here once.

  Measured on Clojure 1.12.5 and cljgo v0.9.0:

  | expression | JVM | cljgo |
  |---|---|---|
  | `(sort [\"\\uD83D\\uDE00\" \"\\uE000\"])` | private-use first | emoji first |
  | `(count \"\\uD83D\\uDE00\")` | 2 — UTF-16 code units | 1 — one rune |
  | `(compare \"\\uD83D\\uDE00\" \"\\uE000\")` | negative | positive |

  A JVM string is a sequence of UTF-16 code units, so a supplementary character
  is a SURROGATE PAIR whose lead unit is below U+FFFD. A cljgo string is a
  sequence of runes, ordered by UTF-8 bytes, which puts the same character
  ABOVE. The two agree across the entire BMP and diverge above it — which is why
  this survives every test until one emoji appears in real data.

  koine met this the expensive way: 0.7.2 shipped byte-differing JSON because
  keys were sorted with `sort`, and 0.7.3 shipped a WORSE bug fixing it, because
  `compare` on vectors is length-first. Both fixes lived privately inside
  `koine.json`. This namespace is that code made public, unchanged in behaviour,
  because a consumer needing stable cross-host ordering should reach for the
  seam rather than rediscover it from a byte-exact output that silently differed
  — which is exactly how the toolnexus port found it, across nine sites.

  Everything here is pure `clojure.core`: no host call, no interop, no reader
  conditional. It is portable because it never asks the host anything.

  NAMED `compare-strings`, not `compare`. Shadowing `clojure.core/compare` would
  force every consumer into a `:refer-clojure :exclude`, and cljgo warns on the
  shadow even WITH the exclusion declared where the JVM is silent — so the
  shadow is a portability cost with no benefit. `shadow_check` exists to keep
  koine off `clojure.core`'s names.")

(defn code-points
  "`s` as a vector of code points.

  On the JVM this folds surrogate pairs back into single code points; on a
  rune-indexed host each character already IS one, and the fold never triggers.
  Either way the vector is the same for the same text — that agreement is the
  whole point.

  An UNPAIRED surrogate is passed through as its own value rather than being
  replaced or dropped, so this never silently loses input it cannot interpret."
  [s]
  (let [s (str s)
        n (count s)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [c (int (nth s i))]
          (if (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) n))
            (let [lo (int (nth s (inc i)))]
              (if (and (>= lo 0xDC00) (<= lo 0xDFFF))
                (recur (+ i 2)
                       (conj acc (+ 0x10000
                                    (* 0x400 (- c 0xD800))
                                    (- lo 0xDC00))))
                (recur (inc i) (conj acc c))))
            (recur (inc i) (conj acc c))))))))

(defn compare-code-points
  "Lexicographic order over two code-point VECTORS: element by element, and a
  prefix sorts before its extension.

  Do not reach for `clojure.core/compare` here. It compares vectors by COUNT
  first, so `[97 114 116]` sorts after `[99 111]` and \"artifacts\" lands after
  \"config\". koine 0.7.2 shipped exactly that while fixing the surrogate bug,
  and every conformance case added alongside it used EQUAL-LENGTH keys, so none
  could see it. It was wrong on both hosts identically — which is worse, because
  a cross-host differential gate is structurally blind to a bug both hosts
  share."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (= i n)
        (compare (count a) (count b))
        (let [x (nth a i) y (nth b i)]
          (if (= x y)
            (recur (inc i))
            (compare x y)))))))

(defn compare-strings
  "Compare two strings BY CODE POINT — negative, zero or positive, like
  `clojure.core/compare`, but with the same sign on every host.

  This is the drop-in `clojure.core/compare` is not: the two hosts return
  OPPOSITE SIGNS for a supplementary character against a private-use one.
  Code-point order is also UTF-8 byte order, so it is the choice that keeps
  serialised output byte-identical."
  [a b]
  (compare-code-points (code-points a) (code-points b)))

(defn sort-strings
  "Sort `strings` by code point. Stable-ordered identically on every host.

  Decorate-sort-undecorate, deliberately: `sort-by` applies its key-fn once per
  COMPARISON rather than once per element, so scanning inside the comparator
  made koine's JSON encoder O(n log n) scans instead of O(n). Measured at 6400
  keys, that was 1436 µs/key on cljgo against 417 after this change — 3.1x. The
  JVM hid it almost entirely because `nth` on a UTF-16 string is O(1) there and
  cljgo's strings are rune-indexed. One host paid, and only at size."
  [strings]
  (->> strings
       (map (fn [s] [(code-points s) s]))
       (sort-by (fn [d] (nth d 0)) compare-code-points)
       (map (fn [d] (nth d 1)))
       vec))

(defn utf8-length
  "How many BYTES `s` occupies when encoded as UTF-8.

  Not `count`, which answers in UTF-16 code units on the JVM and runes on cljgo
  — two different wrong answers for the question a wire protocol, a
  `content-length` header or a byte budget is actually asking. Computed from the
  code points by the UTF-8 rules (RFC 3629), so it needs no host encoder and
  agrees everywhere.

  A lone surrogate is counted as 3 bytes, matching its own encoded width, rather
  than being silently treated as a replacement character."
  [s]
  (reduce (fn [acc cp]
            (+ acc (cond (< cp 0x80)    1
                         (< cp 0x800)   2
                         (< cp 0x10000) 3
                         :else          4)))
          0
          (code-points s)))
