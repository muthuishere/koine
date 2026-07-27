(ns cljhost.env
  "Environment variables, portable.

  Values read here are frequently secrets (API keys expanded into MCP headers).
  Nothing in this namespace logs, prints or caches a value."
  (:require [clojure.string :as str]))

(defn get-env
  "The value of environment variable `name`, or nil / `default` when unset."
  ([name] (get-env name nil))
  ([name default]
   (or #?(:clj   (System/getenv (str name))
          :cljgo (cljg.os/getenv (str name))
          :default (throw (ex-info "cljhost.env/get-env: no implementation for this host"
                                   {:name (str name)})))
       default)))

(defn expand
  "Replace every ${VAR} in `s` with its environment value. An unset variable
  expands to the empty string, matching the other toolnexus ports."
  [s]
  (str/replace (str s) #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}"
               (fn [[_ v]] (or (get-env v) ""))))
