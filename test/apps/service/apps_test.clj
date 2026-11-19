(ns apps.service.apps-test
  (:require
   [apps.persistence.jobs :as jp]
   [apps.service.apps :as apps]
   [clojure.test :refer [deftest is testing]]))

(def ^:private submission
  {:name "job" :config {:step1_secret "hunter2" :step1_plain "visible"} :job_config {:step1_secret "hunter2"}})

(deftest test-redact-submission-for-log
  (testing "the raw submission is redacted before it's logged"
    (with-redefs [jp/redact-submission (fn [s] (update s :config dissoc :step1_secret))]
      (is (= {:step1_plain "visible"} (:config (#'apps/redact-submission-for-log submission))))))
  (testing "if the sensitive params can't be looked up, no parameter values are logged at all"
    (with-redefs [jp/redact-submission (fn [_] (throw (Exception. "no such app")))]
      (is (= {:name "job"} (#'apps/redact-submission-for-log submission))))))
