(ns koine.env-test
  "JVM-side unit suite for koine.env. The cross-host run is src/env_check.cljc.

  No test here sets an environment variable: the JVM has no portable setenv, and
  a suite that shells out to arrange one is testing the shell. HOME/PATH are
  taken as given, and everything interesting is about the UNSET case, which is
  where the Go-vs-JVM \"\"-vs-nil divergence lives."
  (:require [clojure.test :refer [deftest is testing]]
            [koine.env :as env]))

(def ^:private unset "KOINE_DEFINITELY_UNSET_XYZ")

(deftest reads-a-variable-that-is-set
  (is (string? (env/get-env "HOME")))
  (is (seq (env/get-env "HOME"))))

(deftest unset-is-nil-not-empty-string
  (testing "nil, so `or`/`when-let` behave — \"\" would be truthy"
    (is (nil? (env/get-env unset)))))

(deftest default-fires-only-when-unset
  (is (= "fallback" (env/get-env unset "fallback")))
  (is (= (env/get-env "HOME") (env/get-env "HOME" "fallback"))))

(deftest default-is-returned-verbatim
  (testing "any value, not just strings — the default is the caller's"
    (is (= {:a 1} (env/get-env unset {:a 1})))
    (is (= 0 (env/get-env unset 0)))))

(deftest name-may-be-a-symbol
  (testing "get-env stringifies its argument with `str`"
    (is (= (env/get-env "HOME") (env/get-env 'HOME))))
  (testing "a KEYWORD is not a shorthand — (str :HOME) is \":HOME\", which no
  process has. Asserted so nobody 'fixes' get-env into guessing what the caller
  meant; the variable name is a string."
    (is (nil? (env/get-env :HOME)))))

(deftest expand-substitutes-set-variables
  (let [home (env/get-env "HOME")]
    (is (= home (env/expand "${HOME}")))
    (is (= (str "a" home "b") (env/expand (str "a${HOME}b"))))
    (is (= (str home ":" home) (env/expand "${HOME}:${HOME}")))))

(deftest expand-turns-an-unset-variable-into-empty-string
  (is (= "[]" (env/expand (str "[${" unset "}]"))))
  (is (= "" (env/expand (str "${" unset "}")))))

(deftest expand-leaves-non-variables-alone
  (testing "a ${…} whose body is not a legal name is not eaten"
    (is (= "${not-a-name}" (env/expand "${not-a-name}")))
    (is (= "${}" (env/expand "${}")))
    (is (= "${1ABC}" (env/expand "${1ABC}")))
    (is (= "${UNCLOSED" (env/expand "${UNCLOSED"))))
  (testing "a bare $ or brace is literal"
    (is (= "cost: $5" (env/expand "cost: $5")))
    (is (= "{HOME}" (env/expand "{HOME}")))
    (is (= "$HOME" (env/expand "$HOME")))))

(deftest expand-handles-edges
  (is (= "" (env/expand "")))
  (is (= "" (env/expand nil)))
  (is (= "no vars" (env/expand "no vars")))
  (testing "underscore and digits are legal in a name, after the first char"
    (is (= "" (env/expand "${KOINE_UNSET_9}")))))

(deftest expand-does-not-rescan-substituted-text
  (testing "an env value that itself looks like ${X} is not expanded again"
    ;; PATH is set on every box and cannot contain a ${…} that resolves, so the
    ;; observable property is simply that expand is a single left-to-right pass.
    (let [p (env/get-env "PATH")]
      (is (= p (env/expand "${PATH}"))))))
