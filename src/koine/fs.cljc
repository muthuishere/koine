(ns koine.fs
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
     :default (throw (ex-info "koine.fs/exists?: no implementation for this host" {:path path}))))

(defn directory? [path]
  #?(:clj   (.isDirectory (java.io.File. ^String (str path)))
     :cljgo (cio/directory? (str path))
     :default (throw (ex-info "koine.fs/directory?: no implementation for this host" {:path path}))))

(defn list-tree
  "Every path under `root`, recursively, as strings — files and directories.
  Order is unspecified per host; callers that need determinism must sort."
  [root]
  #?(:clj   (map str (file-seq (java.io.File. ^String (str root))))
     :cljgo (map str (file-seq (str root)))
     :default (throw (ex-info "koine.fs/list-tree: no implementation for this host" {:root root}))))

(defn find-files
  "Every file under `root` whose path ends with `suffix`, SORTED.

  Sorted because skill discovery must be deterministic across hosts — the
  underlying traversal order is not guaranteed to match."
  [root suffix]
  (->> (list-tree root)
       (filter #(str/ends-with? % suffix))
       sort
       vec))

(defn read-file
  "The whole file at `path` as a string (UTF-8)."
  [path]
  (slurp path))

(defn write-file
  "Write string `s` to `path`, replacing any existing file. Returns nil."
  [path s]
  (spit path s))

;; --------------------------------------------------------------- binary
;;
;; `slurp`/`spit` are TEXT. They decode as UTF-8, so a byte that is not valid
;; UTF-8 comes back as the replacement rune and the round trip is lossy — on
;; BOTH hosts, identically (a 12-byte binary file reads back as 13 characters).
;; That is not a divergence to normalise; it is why a byte seam has to exist
;; separately. Unblocked by cljgo ADR 0110.

(defn read-bytes
  "The whole file at `path` as a byte array. Use this, never `read-file`, for
  anything that is not text — the text route is lossy for non-UTF-8 bytes."
  [path]
  #?(:clj   (java.nio.file.Files/readAllBytes
             (.toPath (java.io.File. ^String (str path))))
     :cljgo (cio/read-bytes (str path))
     :default (throw (ex-info "koine.fs/read-bytes: no implementation for this host; add a branch in koine/fs.cljc"
                              {:path path}))))

(defn write-bytes
  "Write byte array `bs` to `path`, replacing any existing file. Returns nil."
  [path bs]
  #?(:clj   (do (java.nio.file.Files/write
                 (.toPath (java.io.File. ^String (str path)))
                 ^bytes bs
                 ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption
                                                            [java.nio.file.StandardOpenOption/CREATE
                                                             java.nio.file.StandardOpenOption/TRUNCATE_EXISTING
                                                             java.nio.file.StandardOpenOption/WRITE]))
                 nil)
     :cljgo (do (cio/write-bytes (str path) bs) nil)
     :default (throw (ex-info "koine.fs/write-bytes: no implementation for this host; add a branch in koine/fs.cljc"
                              {:path path}))))
