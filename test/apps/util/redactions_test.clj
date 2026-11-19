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

;; ---------------------------------------------------------------------------
;; sensitive-config-keys
;; ---------------------------------------------------------------------------

(deftest test-sensitive-config-keys
  (testing "sensitive-config-keys builds <step-id>_<param-id> keys for sensitive params only"
    (is (= #{:s1_p2 :s2_p3}
           (redactions/sensitive-config-keys [{:step_id "s1" :id "p1" :is_sensitive false}
                                              {:step_id "s1" :id "p2" :is_sensitive true}
                                              {:step_id "s2" :id "p3" :is_sensitive true}
                                              {:step_id "s2" :id "p4"}]))))
  (testing "a param without a step ID uses its bare ID, matching the job parameter listing"
    (is (= #{:p1} (redactions/sensitive-config-keys [{:id "p1" :is_sensitive true}]))))
  (testing "UUIDs are formatted the same way they are in submission config keys"
    (let [step-id  (java.util.UUID/fromString "11111111-1111-1111-1111-111111111111")
          param-id (java.util.UUID/fromString "22222222-2222-2222-2222-222222222222")]
      (is (= #{:11111111-1111-1111-1111-111111111111_22222222-2222-2222-2222-222222222222}
             (redactions/sensitive-config-keys [{:step_id step-id :id param-id :is_sensitive true}]))))))

;; ---------------------------------------------------------------------------
;; redact-sensitive-config / redact-submission
;; ---------------------------------------------------------------------------

(def ^:private raw-submission
  {:app_id     "abc"
   :name       "job"
   :config     {:s1_secret "hunter2" :s1_plain "visible" :s1_input "/iplant/home/u/in.txt"}
   :job_config {:s1_secret "hunter2" :s1_plain "visible"}})

(deftest test-redact-sensitive-config-removes-sensitive-keys
  (testing "the sensitive key is removed from :config and :job_config, other keys are untouched"
    (let [result (redactions/redact-sensitive-config raw-submission #{:s1_secret})]
      (is (not (contains? (:config result) :s1_secret)))
      (is (not (contains? (:job_config result) :s1_secret)))
      (is (= {:s1_plain "visible" :s1_input "/iplant/home/u/in.txt"} (:config result)))
      (is (= {:s1_plain "visible"} (:job_config result)))
      (is (= (dissoc raw-submission :config :job_config) (dissoc result :config :job_config))
          "Keys outside the config maps must be left alone")))
  (testing "string keys are removed too (e.g. a submission that hasn't been keywordized)"
    (is (= {:config {"s1_plain" "visible"}}
           (redactions/redact-sensitive-config {:config {"s1_secret" "hunter2" "s1_plain" "visible"}}
                                               #{:s1_secret})))))

(deftest test-redact-sensitive-config-no-sensitive-params
  (testing "a submission with no sensitive params is unchanged"
    (is (= raw-submission (redactions/redact-sensitive-config raw-submission #{})))
    (is (= raw-submission (redactions/redact-sensitive-config raw-submission nil)))))

(deftest test-redact-sensitive-config-no-config
  (testing "a submission without config maps is unchanged, and doesn't gain them"
    (is (= {:app_id "abc"} (redactions/redact-sensitive-config {:app_id "abc"} #{:s1_secret})))))

(deftest test-redact-submission-raw-submission
  (testing "a raw submission without :steps still has its sensitive config values removed"
    (let [result (redactions/redact-submission raw-submission #{:s1_secret})]
      (is (not (contains? (:config result) :s1_secret))
          "Persisting a raw submission must never keep a sensitive value")
      (is (not (contains? result :steps)) "A raw submission must not gain :steps"))))

(deftest test-redact-submission-steps-and-config
  (testing "a submission with both :steps and :config has both redacted"
    (let [submission (assoc raw-submission
                            :steps [{:environment {"PLAIN" "visible"} :sensitive_environment {"SECRET" "hunter2"}}])
          result     (redactions/redact-submission submission #{:s1_secret})]
      (is (not (contains? (:config result) :s1_secret)))
      (is (not (contains? (:job_config result) :s1_secret)))
      (is (= [{:environment {"PLAIN" "visible"}}] (:steps result))))))
