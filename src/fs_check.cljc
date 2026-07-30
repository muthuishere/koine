;; fs_check.cljc — portability conformance for koine.fs.
;;
;; The whole point of this namespace is the `file-seq` trap: the name resolves on
;; both hosts but takes a java.io.File on the JVM and a string path on cljgo, so
;; a caller that "portably" calls file-seq gets a host-specific crash. list-tree
;; and find-files are the seam that hides it, and the property under test is that
;; both hosts return the SAME sorted set of paths for the same tree.
;;
;; The tree is built with koine.fs itself under a fixed directory below /tmp —
;; no temp-dir shim is assumed, and the directory is created with mkdir -p via
;; koine.process (portable), then removed at the end.
(require 'koine.fs 'koine.process 'clojure.string)
(alias 'fs 'koine.fs)
(alias 'proc 'koine.process)
(alias 'cstr 'clojure.string)

(def root "/tmp/koine-fs-check")

(proc/sh ["rm" "-rf" root])
(proc/sh ["mkdir" "-p" (str root "/sub/deep")])

(fs/write-file (str root "/a.txt")           "alpha")
(fs/write-file (str root "/b.skill.md")      "bee")
(fs/write-file (str root "/sub/c.txt")       "cee")
(fs/write-file (str root "/sub/deep/d.skill.md") "dee ☃")

(def tree (set (fs/list-tree root)))
(def txts (fs/find-files root ".txt"))
(def skills (fs/find-files root ".skill.md"))

(def cases
  [["exists-file"   (fs/exists? (str root "/a.txt"))            true]
   ["exists-dir"    (fs/exists? root)                           true]
   ["exists-missing" (fs/exists? (str root "/nope"))            false]
   ["dir-yes"       (fs/directory? root)                        true]
   ["dir-no"        (fs/directory? (str root "/a.txt"))         false]

   ["roundtrip"     (fs/read-file (str root "/a.txt"))          "alpha"]
   ["roundtrip-utf8" (fs/read-file (str root "/sub/deep/d.skill.md")) "dee ☃"]
   ["overwrite"     (do (fs/write-file (str root "/a.txt") "alpha2")
                        (fs/read-file (str root "/a.txt")))     "alpha2"]

   ;; list-tree: every file AND directory, root included, as strings
   ["tree-has-root" (contains? tree root)                       true]
   ["tree-has-file" (contains? tree (str root "/a.txt"))        true]
   ["tree-has-nested" (contains? tree (str root "/sub/deep/d.skill.md")) true]
   ["tree-has-dir"  (contains? tree (str root "/sub/deep"))     true]
   ["tree-count"    (count tree)                                7]
   ["tree-strings"  (every? string? tree)                       true]

   ;; find-files: sorted, suffix-filtered, files only
   ["find-txt"      txts        [(str root "/a.txt") (str root "/sub/c.txt")]]
   ;; plain string sort, so "b.skill.md" precedes "sub/deep/…" — depth plays no
   ;; part in the order, only the byte sequence. Both hosts agree on this.
   ["find-skill"    skills      [(str root "/b.skill.md")
                                 (str root "/sub/deep/d.skill.md")]]
   ["find-sorted"   (= skills (vec (sort skills)))              true]
   ["find-none"     (fs/find-files root ".nope")                []]
   ["find-vector"   (vector? txts)                              true]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))

(proc/sh ["rm" "-rf" root])
