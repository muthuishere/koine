(ns koine.host
  "Which host am I on, and what can it do?

  koine's rule is that a seam either works or throws a named error — it never
  quietly degrades. That is right, but it leaves a caller who WANTS to degrade
  with no way to ask first, and the obvious workaround is not portable: catching
  the throw is not the same shape on every host, so a `try`/`catch` capability
  probe is itself host-specific code — exactly what koine exists to prevent.

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
  "This host, as a keyword: :jvm or :cljgo."
  #?(:clj   :jvm
     :cljgo :cljgo
     :default :unknown))

(def capabilities
  ;; ASCII only, deliberately: cljgo's AOT emitter truncates its form-preview
  ;; comment at ~90 BYTES rather than runes, so a multi-byte character landing on
  ;; that boundary is cut in half and the generated Go stops being valid UTF-8
  ;; ("emit: 73:92: illegal UTF-8 encoding"). An em dash here made this namespace
  ;; compile-fail while running fine interpreted. Fixed upstream in cljgo, but
  ;; prose in THIS file still avoids non-ASCII near the start of a form.
  "Everything this host can do, as a set.

  Both hosts implement everything today, so this is currently a constant - and
  it is kept anyway, because it is the honest way to ADD a host: a new runtime
  declares what it has, and callers that already branch on `supports?` degrade
  without a line of change."
  #?(:clj   #{:json/read-write :env/get-env :time/clock :time/iso
              :fs/text :fs/bytes :codec/base64-string :codec/base64-bytes
              :process/sh :process/spawn :process/stderr-capture
              :process/timeout :process/kill :process/exit-code :process/await
              :fs/mutate :fs/real-path
              :http/request :http/timeout
              :stream/sse :route/router :server/serve}
     :cljgo #{:json/read-write :env/get-env :time/clock :time/iso
              :fs/text :fs/bytes :codec/base64-string :codec/base64-bytes
              :process/sh :process/spawn :process/stderr-capture
              :process/timeout :process/kill :process/exit-code :process/await
              :fs/mutate :fs/real-path
              :http/request :http/timeout
              :stream/sse :route/router :server/serve}
     :default #{}))

(defn supports?
  "True when this host implements `capability` (e.g. :process/spawn).
  An unknown capability is false, never an error."
  [capability]
  (contains? capabilities capability))
