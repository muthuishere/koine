(ns koine.host
  "Which host am I on, and what can it do?

  koine's rule is that a seam either works or throws a named error — it never
  quietly degrades. That is right, but it leaves a caller who WANTS to degrade
  with no way to ask first, and the obvious workaround is not portable: catching
  the throw needs `Throwable` on the JVM, cljgo and let-go, and `go/error` on
  Glojure, so `try`/`catch` around a capability probe is itself host-specific
  code — exactly what koine exists to prevent.

  So the question gets a first-class answer. `(supports? :process/spawn)` is a
  compile-time constant per host, and code that can do without a capability can
  branch on it:

      (if (host/supports? :process/spawn)
        (talk-to-mcp-server)
        (fall-back-to-http))

  Capabilities are named `:namespace/fn`, matching where they live. An unknown
  key answers false rather than throwing — a caller asking about a capability
  koine has not heard of should take the safe branch, not crash.")

(def id
  "This host, as a keyword: :jvm, :cljgo, :glojure or :let-go."
  #?(:clj   :jvm
     :cljgo :cljgo
     :glj   :glojure
     :lg    :let-go
     :default :unknown))

(def tier
  ;; ASCII only, deliberately: cljgo's AOT emitter truncates its form-preview
  ;; comment at ~90 BYTES rather than runes, so a multi-byte character landing on
  ;; that boundary is cut in half and the generated Go stops being valid UTF-8
  ;; ("emit: 73:92: illegal UTF-8 encoding"). An em dash here made this namespace
  ;; compile-fail while running fine interpreted. Filed as the ADR 0110 addendum;
  ;; until it lands, prose in THIS file avoids non-ASCII near the start of a form.
  "koine's support tier for this host (README):

    :supported    JVM, cljgo - a gap blocks a release
    :nice-to-have Glojure    - implemented where straightforward
    :best-effort  let-go     - kept green, never gates a release"
  #?(:clj   :supported
     :cljgo :supported
     :glj   :nice-to-have
     :lg    :best-effort
     :default :unknown))

(def capabilities
  "Everything this host can actually do, as a set. The three gaps are real and
  measured (2026-07-31), not assumed:

    let-go  has no byte-level file I/O (io/slurp decodes as text, and nothing in
            io/os/unix reads a file into a byte-array), no base64 over bytes
            (io/encode base64s the PRINTED form of a byte-array — silently
            wrong, so koine refuses it), and no streaming child (os/exec hands
            back an *exec.Cmd whose pipes are unreachable from Clojure)."
  (let [all #{:json/read-write :env/get-env :time/clock :time/iso
              :fs/text :fs/bytes :codec/base64-string :codec/base64-bytes
              :process/sh :process/spawn :http/request :stream/sse
              :route/router :server/serve}]
    #?(:clj   all
       :cljgo all
       :glj   all
       ;; `disj`, not clojure.set/difference: Glojure has no clojure.set at all
       ;; ("failed to load /clojure/set: not found in load path"), and this file
       ;; must load on every host — it is the one that says what a host can do.
       :lg    (disj all :fs/bytes :codec/base64-bytes :process/spawn)
       :default #{})))

(defn supports?
  "True when this host implements `capability` (e.g. :process/spawn).
  An unknown capability is false, never an error."
  [capability]
  (contains? capabilities capability))
