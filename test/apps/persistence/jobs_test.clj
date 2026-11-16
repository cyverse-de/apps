(ns apps.persistence.jobs-test
  (:require
   [clojure.test :as t :refer [deftest is testing]]))

;; This namespace intentionally avoids requiring apps.persistence.jobs at the
;; top level: that namespace pulls in korma/db config that expects a live
;; connection pool. We resolve the private fn by var instead, which is safe
;; because #'redact-sensitive-environment is pure and touches no DB state.

(def ^:private redact-sensitive-environment
  (requiring-resolve 'apps.persistence.jobs/redact-sensitive-environment))

(deftest test-redact-sensitive-environment-strips-field-before-persistence
  (testing "sensitive_environment is stripped from every step before encoding for storage"
    (let [submission {:steps [{:environment {"A" "1"} :sensitive_environment {"B" "2"}}]}
          result     (redact-sensitive-environment submission)]
      (is (every? #(not (contains? % :sensitive_environment)) (:steps result))
          "Persisted submission must never retain sensitive_environment")
      (is (= {"A" "1"} (-> result :steps first :environment))
          "Non-sensitive environment must be preserved"))))

(deftest test-redact-sensitive-environment-no-steps-key
  (testing "redact-sensitive-environment is a no-op when submission has no :steps (e.g. job-step saves)"
    (let [submission {:app_id "abc"}]
      (is (= submission (redact-sensitive-environment submission))))))
