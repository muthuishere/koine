;; main.cljc — the let-go entry point: run the app, then the shared suite.
;;
;; `run-tests` is called with NO argument: let-go's clojure.test rejects the
;; (run-tests 'some.ns) arity with "name expected Named" — it runs the tests
;; registered in the current namespace instead. Requiring the suite above is
;; what registers them.
(require 'demo.app 'demo.app-test 'clojure.test)

(println (demo.app/-main))
(let [summary (clojure.test/run-tests)]
  (println summary)
  ;; `(:fail summary)` can be nil here — let-go's run-tests returns a summary
  ;; without the counters when it is called with no namespace argument — and
  ;; (pos? nil) throws "cannot compare nil and Int" rather than answering false.
  ;; `or 0` keeps the exit-code check honest on every host.
  (when (or (pos? (or (:fail summary) 0)) (pos? (or (:error summary) 0)))
    (System/exit 1)))
