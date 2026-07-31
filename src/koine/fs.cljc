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

;; ------------------------------------------------------------- mutation
;;
;; Creating and removing paths used to mean shelling out to `mkdir -p` and
;; `rm -f`. That is portable across HOSTS and not across OPERATING SYSTEMS,
;; which quietly undoes the point of the library — and it pays a process spawn
;; for a syscall. Asked for by the toolnexus port, 2026-07-31.

(defn mkdirs!
  "Create directory `path` and every missing parent (`mkdir -p`). Returns
  `path`. Not an error if it already exists."
  [path]
  #?(:clj   (do (.mkdirs (java.io.File. ^String (str path))) (str path))
     :cljgo (do (cio/mkdirs (str path)) (str path))
     :default (throw (ex-info "koine.fs/mkdirs!: no implementation for this host; add a branch in koine/fs.cljc"
                              {:path path}))))

(defn delete!
  "Delete the file or EMPTY directory at `path`. Returns nil. Not an error if
  it is already absent — deleting is a statement about the end state, and both
  hosts agree only if koine says so."
  [path]
  #?(:clj   (do (java.nio.file.Files/deleteIfExists
                 (.toPath (java.io.File. ^String (str path))))
                nil)
     :cljgo (do (cio/delete! (str path)) nil)
     :default (throw (ex-info "koine.fs/delete!: no implementation for this host; add a branch in koine/fs.cljc"
                              {:path path}))))

(defn temp-dir!
  "Create a fresh temporary directory and return its path.

  Each call returns a NEW directory — the caller owns it and should
  `delete-tree!` it. `prefix` only shapes the name; never rely on the exact
  path, which differs per host and per OS."
  ([] (temp-dir! "koine"))
  ([prefix]
   #?(:clj   (str (java.nio.file.Files/createTempDirectory
                   ^String (str prefix)
                   ^"[Ljava.nio.file.attribute.FileAttribute;"
                   (into-array java.nio.file.attribute.FileAttribute [])))
      :cljgo (str (cio/temp-dir (str prefix)))
      :default (throw (ex-info "koine.fs/temp-dir!: no implementation for this host; add a branch in koine/fs.cljc"
                               {:prefix prefix})))))

(defn delete-tree!
  "Recursively delete `path` and everything under it (`rm -rf`). Returns nil.
  Not an error if it is already absent.

  No reader conditional: both hosts have a native recursive delete, and using
  neither is the better trade. Deepest-first over `list-tree` is the same
  traversal koine already guarantees, so the ORDER of removal is koine's and
  cannot differ per host — and a bug here deletes the wrong thing, which is the
  last place to want two implementations."
  [path]
  (when (exists? path)
    (doseq [p (reverse (sort (list-tree path)))]
      (delete! p)))
  nil)

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
