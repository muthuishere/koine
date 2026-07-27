(ns cljhost.fs
  "Filesystem, portable.

  Note `file-seq` is a trap: it resolves on BOTH hosts but takes different
  argument types — a java.io.File on the JVM, a string path on cljgo. A name
  that resolves everywhere is not thereby portable, which is why directory
  traversal lives behind this seam."
  (:require [clojure.string :as str])
  #?(:cljgo (:require [cljg.io :as cio])))

(defn exists? [path]
  #?(:clj   (.exists (java.io.File. ^String (str path)))
     :cljgo (cio/exists? (str path))
     :default (throw (ex-info "cljhost.fs/exists?: no implementation for this host" {:path path}))))

(defn directory? [path]
  #?(:clj   (.isDirectory (java.io.File. ^String (str path)))
     :cljgo (cio/directory? (str path))
     :default (throw (ex-info "cljhost.fs/directory?: no implementation for this host" {:path path}))))

(defn list-tree
  "Every path under `root`, recursively, as strings — files and directories.
  Order is unspecified per host; callers that need determinism must sort."
  [root]
  #?(:clj   (map str (file-seq (java.io.File. ^String (str root))))
     :cljgo (map str (file-seq (str root)))
     :default (throw (ex-info "cljhost.fs/list-tree: no implementation for this host" {:root root}))))

(defn find-files
  "Every file under `root` whose path ends with `suffix`, SORTED.

  Sorted because skill discovery must be deterministic across hosts — the
  underlying traversal order is not guaranteed to match."
  [root suffix]
  (->> (list-tree root)
       (filter #(str/ends-with? % suffix))
       sort
       vec))

(defn read-file  [path] (slurp path))          ; clojure.core, portable
(defn write-file [path s] (spit path s))       ; clojure.core, portable
