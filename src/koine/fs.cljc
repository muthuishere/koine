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
     ;; Go's os.Stat multi-returns [FileInfo error]; a non-nil error is the
     ;; "does not exist" answer, not a failure to handle.
     :glj   (nil? (nth (os.Stat (str path)) 1))
     ;; let-go's os/stat returns a FileStat map, or nil when there is nothing
     ;; there. `boolean` so this is a real true/false on every host.
     :lg    (boolean (os/stat (str path)))
     :default (throw (ex-info "koine.fs/exists?: no implementation for this host" {:path path}))))

(defn directory? [path]
  #?(:clj   (.isDirectory (java.io.File. ^String (str path)))
     :cljgo (cio/directory? (str path))
     :glj   (let [[info err] [(nth (os.Stat (str path)) 0) (nth (os.Stat (str path)) 1)]]
              (if err false (.IsDir info)))
     :lg    (boolean (:dir? (os/stat (str path))))
     :default (throw (ex-info "koine.fs/directory?: no implementation for this host" {:path path}))))

(defn list-tree
  "Every path under `root`, recursively, as strings — files and directories.
  Order is unspecified per host; callers that need determinism must sort."
  [root]
  #?(:clj   (map str (file-seq (java.io.File. ^String (str root))))
     :cljgo (map str (file-seq (str root)))
     ;; Go's filepath.WalkDir hands each entry to a callback; collecting into an
     ;; atom is the shape that works without a lazy-seq bridge into Go.
     :glj   (let [acc (atom [])]
              (path:filepath.WalkDir (str root)
                                     (fn [p d err] (when-not err (swap! acc conj (str p))) nil))
              @acc)
     ;; let-go's os/ls is ONE level and returns bare names, so the recursion and
     ;; the path joining are done here.
     :lg    (letfn [(walk [dir]
                      (cons dir
                            (mapcat (fn [name]
                                      (let [p (str dir "/" name)]
                                        (if (:dir? (os/stat p)) (walk p) [p])))
                                    (os/ls dir))))]
              (if (exists? (str root)) (walk (str root)) []))
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
  #?(:glj (let [r (os.ReadFile (str path))]
            (when-let [e (nth r 1)]
              (throw (ex-info (str "koine.fs/read-file: " e) {:path path})))
            (.String (bytes.NewBuffer (nth r 0))))
     :default (slurp path)))

(defn write-file
  "Write string `s` to `path`, replacing any existing file. Returns nil."
  [path s]
  #?(:glj (let [e (os.WriteFile (str path) (.Bytes (bytes.NewBufferString (str s))) 420)]
            (when e (throw (ex-info (str "koine.fs/write-file: " e) {:path path})))
            nil)
     ;; `slurp`/`spit` are clojure.core and portable — EXCEPT on Glojure, where
     ;; `spit` resolves but is unbound ("cannot call nil", measured 2026-07-31).
     ;; os.ReadFile/WriteFile are the working route there.
     :default (spit path s)))

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
     ;; os.ReadFile multi-returns [bytes error]. The values are then normalised
     ;; to SIGNED, because Go's byte is unsigned and the JVM's is not: without
     ;; this, 0x80 reads back as 128 here and -128 there, and a checksum or an
     ;; equality test that passes on one host fails on the other. koine's byte
     ;; contract is the JVM's — see bytes_check.
     :glj   (let [r  (os.ReadFile (str path))
                  _  (when-let [e (nth r 1)]
                       (throw (ex-info (str "koine.fs/read-bytes: " e) {:path path})))
                  bs (nth r 0)]
              (mapv (fn [i] (let [v (long (nth bs i))] (if (> v 127) (- v 256) v)))
                    (range (go/len bs))))
     ;; GAP (measured 2026-07-31): let-go has no byte-level file I/O. `io/slurp`
     ;; decodes as text, `io/encode`/`decode` are string-only, and nothing in the
     ;; io/os/unix namespaces reads a file into a byte-array. Throws rather than
     ;; handing back silently-corrupted text — let-go is tier 3 (see PORTING.md).
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
     ;; Accepts either a Go byte slice or a Clojure seq of byte values, signed
     ;; or unsigned — `bit-and 255` makes -128 and 128 the same byte, which is
     ;; what lets a vector read on the JVM be written unchanged here.
     :glj   (let [buf (bytes.NewBufferString "")]
              (doseq [v (if (sequential? bs)
                          bs
                          (map (fn [i] (nth bs i)) (range (go/len bs))))]
                (.WriteByte buf (bit-and (long v) 255)))
              (let [e (os.WriteFile (str path) (.Bytes buf) 420)]   ; 420 = 0644
                (when e (throw (ex-info (str "koine.fs/write-bytes: " e) {:path path})))
                nil))
     :default (throw (ex-info "koine.fs/write-bytes: no implementation for this host; add a branch in koine/fs.cljc"
                              {:path path}))))
