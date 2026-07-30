(ns koine.fs-test
  "JVM-side unit suite for koine.fs. The cross-host run is src/fs_check.cljc.

  A fixed tree under /tmp is built and torn down per test run — no temp-dir shim
  is assumed, since koine.fs deliberately does not expose one."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [koine.fs :as fs]
            [koine.process :as proc]))

(def ^:private root "/tmp/koine-fs-test")

(defn- with-tree [f]
  (proc/sh ["rm" "-rf" root])
  (proc/sh ["mkdir" "-p" (str root "/sub/deep")])
  (fs/write-file (str root "/a.txt") "alpha")
  (fs/write-file (str root "/b.skill.md") "bee")
  (fs/write-file (str root "/sub/c.txt") "cee")
  (fs/write-file (str root "/sub/deep/d.skill.md") "dee ☃")
  (try (f) (finally (proc/sh ["rm" "-rf" root]))))

(use-fixtures :each with-tree)

(deftest exists?-distinguishes-present-from-absent
  (is (fs/exists? root))
  (is (fs/exists? (str root "/a.txt")))
  (is (not (fs/exists? (str root "/nope.txt"))))
  (testing "returns a real boolean, not a truthy object"
    (is (true? (fs/exists? root)))
    (is (false? (fs/exists? (str root "/nope.txt"))))))

(deftest directory?-distinguishes-dir-from-file
  (is (true? (fs/directory? root)))
  (is (true? (fs/directory? (str root "/sub/deep"))))
  (is (false? (fs/directory? (str root "/a.txt"))))
  (testing "a path that does not exist is not a directory"
    (is (false? (fs/directory? (str root "/nope"))))))

(deftest read-and-write-round-trip
  (is (= "alpha" (fs/read-file (str root "/a.txt"))))
  (testing "utf-8 survives"
    (is (= "dee ☃" (fs/read-file (str root "/sub/deep/d.skill.md")))))
  (testing "write overwrites rather than appends"
    (fs/write-file (str root "/a.txt") "replaced")
    (is (= "replaced" (fs/read-file (str root "/a.txt")))))
  (testing "an empty file is legal"
    (fs/write-file (str root "/empty.txt") "")
    (is (= "" (fs/read-file (str root "/empty.txt"))))))

(deftest list-tree-returns-strings-for-files-and-dirs
  (let [tree (set (fs/list-tree root))]
    (is (every? string? tree))
    (testing "the root itself is included"
      (is (contains? tree root)))
    (is (contains? tree (str root "/a.txt")))
    (is (contains? tree (str root "/sub")))
    (is (contains? tree (str root "/sub/deep")))
    (is (contains? tree (str root "/sub/deep/d.skill.md")))
    (testing "exactly the 7 entries the fixture creates"
      (is (= 7 (count tree))))))

(deftest find-files-filters-by-suffix-and-sorts
  (is (= [(str root "/a.txt") (str root "/sub/c.txt")]
         (fs/find-files root ".txt")))
  (testing "plain string order — depth plays no part"
    (is (= [(str root "/b.skill.md") (str root "/sub/deep/d.skill.md")]
           (fs/find-files root ".skill.md"))))
  (testing "sorted, because skill discovery must be deterministic"
    (let [found (fs/find-files root ".md")]
      (is (= found (vec (sort found))))))
  (testing "no match is an empty vector, not nil"
    (is (= [] (fs/find-files root ".nope")))
    (is (vector? (fs/find-files root ".nope")))))

(deftest find-files-matches-a-suffix-not-an-extension
  (testing "the argument is a plain suffix — a whole filename works too"
    (is (= [(str root "/a.txt")] (fs/find-files root "a.txt")))))
