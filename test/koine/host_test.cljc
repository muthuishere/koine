(ns koine.host-test
  "JVM-side unit suite for koine.host. The cross-host answer is what matters and
  that is asserted from every host by the conformance checks, which use
  `supports?` to decide whether to run their byte and spawn cases at all."
  (:require [clojure.test :refer [deftest is testing]]
            [koine.host :as host]))

(deftest identifies-this-host
  (is (= :jvm host/id))
  (is (= :supported host/tier)))

(deftest the-jvm-supports-everything
  (doseq [c [:json/read-write :env/get-env :time/clock :time/iso :fs/text
             :fs/bytes :codec/base64-string :codec/base64-bytes
             :process/sh :process/spawn :http/request :stream/sse
             :route/router :server/serve]]
    (is (host/supports? c) (str "JVM must support " c))))

(deftest an-unknown-capability-is-false-not-an-error
  (testing "a caller asking about something koine has not heard of takes the
  safe branch rather than crashing"
    (is (false? (host/supports? :nonsense/capability)))
    (is (false? (host/supports? nil)))))

(deftest capabilities-is-a-set-of-keywords
  (is (set? host/capabilities))
  (is (every? keyword? host/capabilities))
  (testing "every capability is namespaced, matching where it lives"
    (is (every? namespace host/capabilities))))
