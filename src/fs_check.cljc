;; fs_check.cljc — portability conformance for koine.fs.
;;
;; The whole point of this namespace is the `file-seq` trap: the name resolves on
;; both hosts but takes a java.io.File on the JVM and a string path on cljgo, so
;; a caller that "portably" calls file-seq gets a host-specific crash. list-tree
;; and find-files are the seam that hides it, and the property under test is that
;; both hosts return the SAME sorted set of paths for the same tree.
;;
;; The tree is built with koine.fs itself, under a directory koine.fs created.
;; It used to shell out to `mkdir -p` and `rm -rf` — portable across HOSTS but
;; not across OPERATING SYSTEMS, and a process spawn for a syscall. That is the
;; workaround koine now removes, so the check must not keep it either.
(require 'koine.fs 'koine.process 'koine.host 'clojure.string)
(alias 'host 'koine.host)
(alias 'fs 'koine.fs)
(alias 'proc 'koine.process)
(alias 'cstr 'clojure.string)

(def root (str (fs/temp-dir! "koine-fs-check") "/tree"))

(fs/mkdirs! (str root "/sub/deep"))

(fs/write-file (str root "/a.txt")           "alpha")
(fs/write-file (str root "/b.skill.md")      "bee")
(fs/write-file (str root "/sub/c.txt")       "cee")
(fs/write-file (str root "/sub/deep/d.skill.md") "dee ☃")

(def tree (set (fs/list-tree root)))

;; --- mutation fixtures, built BEFORE the case table so the table stays data ---
(def mk (fs/mkdirs! (str root "/x/y/z")))
;; captured HERE, not in the case table: the tree is deleted below, so an
;; assertion deferred to the table would be reading the state after the delete.
(def mkdirs-deep? (fs/directory? (str root "/x/y/z")))
(def mkdirs-again? (do (fs/mkdirs! (str root "/x/y/z"))
                       (fs/directory? (str root "/x/y/z"))))
(fs/write-file (str root "/x/y/z/buried.txt") "deep")

(fs/write-file (str root "/gone.txt") "bye")
(def del-ret (fs/delete! (str root "/gone.txt")))
;; deleting what is already gone must NOT throw — that is the contract, and it
;; is the difference between `rm -f` and `rm`.
(def deleted-twice (try (fs/delete! (str root "/gone.txt")) :ok (catch Throwable _ :threw)))

(fs/delete-tree! (str root "/x"))
(def tree-twice (try (fs/delete-tree! (str root "/x")) :ok (catch Throwable _ :threw)))

(def tmp-a (fs/temp-dir! "koine-check"))
(def tmp-b (fs/temp-dir! "koine-check"))

;; --- states this check never used to enter ---
;;
;; Every case above sits in a state where a call SUCCEEDS. That is how `alive?`
;; diverged across hosts for months in koine.process: the tested states were the
;; ones where both implementations coincide. So these ask what happens when the
;; answer is no.
;;
;; mkdirs! over an existing FILE was a genuine divergence — cljgo threw, the JVM
;; returned false from `.mkdirs` and koine discarded it, so the call reported
;; success and created nothing.
(def mkdirs-over-file
  (try (fs/mkdirs! (str root "/a.txt")) :no-throw (catch Throwable _ :threw)))
;; A symlink is read THROUGH on both hosts: a broken one does not exist, a good
;; one does, and a link to a directory is a directory.
;;
;; This lives in its OWN directory, deliberately. Built inside `root` it made
;; find-files return every file twice — because `list-tree` FOLLOWS a directory
;; symlink, identically on both hosts. That is worth knowing on its own: it is
;; why a walk over a tree containing `ln -s ../.. loop` never terminates, and
;; why koine cannot offer a cycle guard until it can canonicalise a path
;; (cljgo #172 — `cljg.io/absolute` is filepath.Abs and does not resolve links).
(def link-root (fs/temp-dir! "koine-links"))
(def link-probe
  (do
    (fs/mkdirs! (str link-root "/d"))
    (fs/write-file (str link-root "/t.txt") "t")
    (proc/sh ["sh" "-c" (str "cd " link-root
                             " && ln -s t.txt goodlink"
                             " && ln -s nope.txt brokenlink"
                             " && ln -s d dirlink")])
    {:good   (fs/exists? (str link-root "/goodlink"))
     :broken (fs/exists? (str link-root "/brokenlink"))
     :dir    (fs/directory? (str link-root "/dirlink"))}))
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
   ["find-vector"   (vector? txts)                              true]

   ;; ------------------------------------------------------------- mutation
   ;; mkdirs! is mkdir -p: every missing parent, and idempotent.
   ["mkdirs-deep"   mkdirs-deep?                                true]
   ["mkdirs-returns" (string? mk)                               true]
   ["mkdirs-again"  mkdirs-again?                               true]   ; a second call is not an error

   ;; delete! is a statement about the END STATE — absent afterwards, and
   ;; deleting something already gone is fine. Both hosts agree because koine
   ;; says so, not because they happened to.
   ["delete-file"   (fs/exists? (str root "/gone.txt"))         false]
   ["delete-absent-ok" deleted-twice                            :ok]
   ["delete-returns-nil" del-ret                                nil]

   ;; delete-tree! removes a NON-EMPTY tree; delete! alone cannot.
   ["tree-gone"     (fs/exists? (str root "/x"))                false]
   ["tree-siblings-survive" (fs/exists? (str root "/a.txt"))    true]
   ["tree-absent-ok" tree-twice                                 :ok]

   ;; temp-dir! hands back a FRESH directory each call — a caller that assumes
   ;; otherwise silently shares state between two unrelated pieces of work.
   ["temp-exists"   (fs/directory? tmp-a)                       true]
   ["temp-distinct" (= tmp-a tmp-b)                             false]
   ["temp-empty"    (count (fs/list-tree tmp-a))                1]    ; the dir itself

   ;; ------------------------------------------------ the unhappy states
   ;; you asked for a directory and there is a file there: both hosts must say so
   ["mkdirs-over-file" mkdirs-over-file                         :threw]
   ;; symlinks are followed, identically, on both hosts
   ["link-good"     (:good link-probe)                          true]
   ["link-broken"   (:broken link-probe)                        false]
   ["link-to-dir"   (:dir link-probe)                           true]])

(let [fails (remove (fn [[_ got want]] (= got want)) cases)]
  (doseq [[l got want] fails] (println "  FAIL" l "got" (pr-str got) "want" (pr-str want)))
  (println (str (- (count cases) (count fails)) "/" (count cases) " pass")))

;; cleanup through the seam itself — no shell, no OS assumption
(fs/delete-tree! root)
(fs/delete-tree! tmp-a)
(fs/delete-tree! tmp-b)
(fs/delete-tree! link-root)
