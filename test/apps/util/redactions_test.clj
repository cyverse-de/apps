(ns apps.util.redactions-test
  (:require
   [apps.util.redactions :as redactions]
   [clojure.test :refer [deftest is testing]]))

(deftest test-redact-sensitive-environment-strips-field
  (testing "redact-sensitive-environment removes :sensitive_environment from every step"
    (let [submission {:steps [{:environment {"A" "1"} :sensitive_environment {"B" "2"}}
                              {:environment {} :sensitive_environment {"C" "3"}}]}
          result     (redactions/redact-sensitive-environment submission)]
      (is (every? #(not (contains? % :sensitive_environment)) (:steps result))
          "No step should retain :sensitive_environment")
      (is (= [{"A" "1"} {}] (map :environment (:steps result)))
          "Regular :environment maps must be left untouched"))))

(deftest test-redact-sensitive-environment-handles-missing-field
  (testing "redact-sensitive-environment is a no-op when steps have no sensitive_environment"
    (let [submission {:steps [{:environment {"A" "1"}}]}]
      (is (= submission (redactions/redact-sensitive-environment submission))))))

(deftest test-redact-sensitive-environment-no-steps-key
  (testing "redact-sensitive-environment is a no-op when submission has no :steps (e.g. job-step saves)"
    (let [submission {:app_id "abc"}]
      (is (= submission (redactions/redact-sensitive-environment submission))))))
