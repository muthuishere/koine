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

(deftest bytes-round-trip-unchanged
  (let [p   (str root "/blob.bin")
        all (byte-array (map (fn [i] (if (> i 127) (- i 256) i)) (range 256)))]
    (fs/write-bytes p all)
    (is (= 256 (alength (fs/read-bytes p))))
    (is (= (vec all) (vec (fs/read-bytes p))))
    (testing "signed elements, matching the JVM's byte[]"
      (is (= -128 (nth (vec (fs/read-bytes p)) 128)))
      (is (= -1 (nth (vec (fs/read-bytes p)) 255))))))

(deftest bytes-carry-what-the-text-route-cannot
  (testing "0x80 alone is never legal UTF-8, so slurp/spit would corrupt it"
    (let [p (str root "/raw.bin")]
      (fs/write-bytes p (byte-array [0 1 2 -128 -1 65]))
      (is (= [0 1 2 -128 -1 65] (vec (fs/read-bytes p))))
      (testing "and the text route demonstrably does corrupt it — the char
      COUNT survives (each bad byte becomes one replacement rune), so only the
      bytes show the damage"
        (is (not= [0 1 2 -128 -1 65]
                  (vec (.getBytes ^String (fs/read-file p) "UTF-8"))))))))

(deftest write-bytes-truncates-and-returns-nil
  (let [p (str root "/t.bin")]
    (fs/write-bytes p (byte-array [1 2 3 4 5]))
    (is (nil? (fs/write-bytes p (byte-array [9]))))
    (testing "the old tail must not survive"
      (is (= [9] (vec (fs/read-bytes p)))))))

(deftest an-empty-byte-file-is-legal
  (let [p (str root "/e.bin")]
    (fs/write-bytes p (byte-array 0))
    (is (= 0 (alength (fs/read-bytes p))))
    (is (fs/exists? p))))

(deftest find-files-matches-a-suffix-not-an-extension
  (testing "the argument is a plain suffix — a whole filename works too"
    (is (= [(str root "/a.txt")] (fs/find-files root "a.txt")))))
