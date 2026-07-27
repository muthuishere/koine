(ns cljhost.env
  "Environment variables, portable.

  Values read here are frequently secrets (API keys expanded into MCP headers).
  Nothing in this namespace logs, prints or caches a value."
  (:require [clojure.string :as str]))

(defn- blank->nil
  "Go's os.Getenv returns \"\" for an unset variable where the JVM returns null.
  An empty string is TRUTHY in Clojure, so without this the `default` argument
  silently never fires on Go-hosted dialects."
  [v]
  (when (and v (not= "" (str v))) (str v)))

(defn get-env
  "The value of environment variable `name`, or nil / `default` when unset."
  ([name] (get-env name nil))
  ([name default]
   (or (blank->nil
        #?(:clj   (System/getenv (str name))
          ;; let-go ships Java-shaped shims, so System/getenv works there too.
          :lg    (System/getenv (str name))
          ;; Glojure exposes Go's stdlib directly; `/` in package names is
          ;; munged to `:`, so os.Getenv is reached as-is.
          :glj   (os.Getenv (str name))
          ;; GAP (verified 2026-07-27, cljgo 0.1.0-dev, both the installed and
          ;; the repo binary): cljgo has no environment-variable route. cljg.os
          ;; is cron/service only, there is no System/getenv shim, and
          ;; require-go reaches only the seed registry — strings/strconv/math/fmt
          ;; (pkg/eval/host.go:15) — so `(require-go '[os])` fails in BOTH
          ;; interpreted and AOT mode. Needs getenv in cljgo's cljg.os.
          :cljgo (throw (ex-info "cljhost.env/get-env: cljgo has no environment-variable access yet (cljg.os has no getenv; require-go cannot reach Go's os package). Tracked as a cljgo work item in toolnexus ADR 0009."
                                 {:name (str name) :host :cljgo}))
          :default (throw (ex-info "cljhost.env/get-env: no implementation for this host; add a branch in cljhost/env.cljc"
                                   {:name (str name)}))))
       default)))

(defn expand
  "Replace every ${VAR} in `s` with its environment value. An unset variable
  expands to the empty string, matching the other toolnexus ports."
  [s]
  (str/replace (str s) #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}"
               (fn [[_ v]] (or (get-env v) ""))))
