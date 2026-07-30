(ns build
  "Release build for koine — a SOURCE-ONLY artifact.

  There is deliberately no `compile-clj` step. koine's whole premise is that
  consumers read the `.cljc` and their own host resolves the reader conditionals;
  AOT-compiling it here would bake JVM class files into a library whose reason to
  exist is that it also loads on cljgo, Glojure and let-go.

  Usage:
    clojure -T:build jar                 ; target/koine-<v>.jar + pom
    clojure -T:build install             ; into ~/.m2, for local consumers
    clojure -T:build deploy              ; to Clojars (needs the env vars below)

  Deploy credentials come from the ENVIRONMENT only — never a file in this repo:
    CLOJARS_USERNAME  the Clojars account name
    CLOJARS_PASSWORD  a Clojars DEPLOY TOKEN (not the account password)"
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

;; net.clojars.muthuishere, not io.github.muthuishere: Clojars pre-verifies
;; net.clojars.<user> for every account, while io.github.<user> needs a one-time
;; GitHub verification the group has not been through (a 403 "Group
;; 'io.github.muthuishere' doesn't exist" on deploy, 2026-07-30).
(def lib 'net.clojars.muthuishere/koine)
(def version "0.3.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- pom-template [_]
  [[:description
    "One tiny seam over the host, so the same .cljc runs on Clojure (JVM) and cljgo."]
   [:url "https://github.com/muthuishere/koine"]
   [:licenses
    [:license
     [:name "MIT"]
     [:url "https://opensource.org/license/mit"]]]
   [:scm
    [:url "https://github.com/muthuishere/koine"]
    [:connection "scm:git:https://github.com/muthuishere/koine.git"]
    [:developerConnection "scm:git:ssh://git@github.com/muthuishere/koine.git"]
    [:tag (or (b/git-process {:git-args "rev-parse HEAD"}) version)]]
   [:developers
    [:developer [:name "Muthukumaran Navaneethakrishnan"]]]])

(defn clean [_] (b/delete {:path "target"}))

(defn jar
  "Build the source jar + pom. `src` only — the *_check.cljc conformance
  programs live in src/ too, and they are runnable scripts, not library code, so
  they are excluded from the jar."
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (b/create-basis {:project "deps.edn"})
                :src-dirs  ["src"]
                :pom-data  (pom-template nil)})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir
               :ignores    [#".*_check\.cljc" #"conformance\.cljc"]})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "wrote" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis     (b/create-basis {:project "deps.edn"})
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "installed" lib version "to ~/.m2"))

(defn deploy
  "Push to Clojars. Reads CLOJARS_USERNAME / CLOJARS_PASSWORD from the
  environment; the token value never appears in this file or in the output."
  [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
