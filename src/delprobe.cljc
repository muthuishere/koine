(require 'koine.fs 'koine.host 'koine.codec)
(alias 'fs 'koine.fs) (alias 'host 'koine.host) (alias 'codec 'koine.codec)
(let [base (fs/temp-dir! "koine-del-")
      _    (fs/mkdirs! (str base "/sub"))
      _    (fs/write-bytes (str base "/sub/f.txt") (codec/decode-bytes "eA=="))
      ;; THE DISCRIMINATOR: delete! on a NON-EMPTY directory.
      r    (try {:returned (fs/delete! (str base "/sub"))}
                (catch Exception e {:threw (or (ex-message e) (str e))}))]
  (println "host:              " (pr-str host/id))
  (println "delete! non-empty: " (pr-str r))
  (println "dir still there?   " (pr-str (fs/exists? (str base "/sub"))))
  (println "file still there?  " (pr-str (fs/exists? (str base "/sub/f.txt")))))
