(ns apps.service.apps.de.jobs.common-test
  (:require
   [apps.service.apps.de.jobs.common :as common]
   [clojure.test :as t :refer [deftest is testing]]))

(deftest test-reconcile-container-requirements-includes-gpus
  (testing "Container requirements reconciliation includes GPU fields"
    (let [container    {:min_memory_limit 1073741824
                        :memory_limit     2147483648
                        :min_cpu_cores    1
                        :max_cpu_cores    4
                        :min_gpus         0
                        :max_gpus         2
                        :min_disk_space   1073741824}
          requirements {:min_memory_limit 2147483648
                        :memory_limit     4294967296
                        :min_cpu_cores    2
                        :max_cpu_cores    8
                        :min_gpus         1
                        :max_gpus         2
                        :min_disk_space   2147483648}
          result       (#'common/reconcile-container-requirements container requirements)]
      (is (contains? result :min_gpus) "Result should include min_gpus")
      (is (contains? result :max_gpus) "Result should include max_gpus")
      (is (= 1 (:min_gpus result)) "Should use requested min_gpus when within container limits")
      (is (= 2 (:max_gpus result)) "Should use requested max_gpus when within container limits"))))

(deftest test-reconcile-container-requirements-caps-gpus
  (testing "Container requirements reconciliation caps GPU requests at container maximums"
    (let [container    {:min_gpus 0
                        :max_gpus 2}
          requirements {:min_gpus 1
                        :max_gpus 4}
          result       (#'common/reconcile-container-requirements container requirements)]
      (is (= 1 (:min_gpus result)) "Should use requested min_gpus")
      (is (= 2 (:max_gpus result)) "Should cap max_gpus at container maximum"))))

(deftest test-reconcile-container-requirements-applies-gpu-defaults
  (testing "Container requirements reconciliation applies GPU defaults when container has no GPU settings"
    (let [container    {:min_cpu_cores 1
                        :max_cpu_cores 4}
          requirements {:min_gpus 1
                        :max_gpus 2}
          result       (#'common/reconcile-container-requirements container requirements)]
      ;; When container has no GPU settings (nil), and requirements request GPUs,
      ;; the system should cap at the default limit (0)
      (is (= 0 (:min_gpus result)) "Should default to 0 when container has no GPU support")
      (is (= 0 (:max_gpus result)) "Should default to 0 when container has no GPU support"))))

(deftest test-reconcile-container-requirements-allows-zero-gpus
  (testing "Container requirements reconciliation allows explicit zero GPU requests"
    (let [container    {:min_gpus 0
                        :max_gpus 4}
          requirements {:min_gpus 0
                        :max_gpus 0}
          result       (#'common/reconcile-container-requirements container requirements)]
      (is (= 0 (:min_gpus result)) "Should allow explicit zero GPU request")
      (is (= 0 (:max_gpus result)) "Should allow explicit zero GPU request"))))

(deftest test-reconcile-container-requirements-removes-nil-gpus
  (testing "Container requirements reconciliation removes nil GPU values"
    (let [container    {:min_cpu_cores 1}
          requirements {:min_cpu_cores 2}
          result       (#'common/reconcile-container-requirements container requirements)]
      ;; When neither container nor requirements specify GPUs, they shouldn't appear in result
      (is (not (contains? result :min_gpus))
          "Should not include min_gpus when not specified")
      (is (not (contains? result :max_gpus))
          "Should not include max_gpus when not specified"))))

;; ---------------------------------------------------------------------------
;; reconcile-gpu-models — direct tests of the GPU model intersection logic
;; ---------------------------------------------------------------------------

(deftest test-reconcile-gpu-models-user-subset
  (testing "User's valid subset of tool models is used"
    (let [container    {:gpu_models ["A16" "A40" "A100"]}
          requirements {:gpu_models ["A16" "A40"]}
          result       (#'common/reconcile-gpu-models container requirements)]
      (is (= (set result) #{"A16" "A40"})
          "Should return exactly the user's valid selections"))))

(deftest test-reconcile-gpu-models-invalid-dropped
  (testing "Invalid user selections are silently dropped"
    (let [container    {:gpu_models ["A16" "A40"]}
          requirements {:gpu_models ["A16" "BOGUS"]}
          result       (#'common/reconcile-gpu-models container requirements)]
      (is (= (set result) #{"A16"})
          "Should keep only models that appear in the tool's allowed list"))))

(deftest test-reconcile-gpu-models-all-invalid-falls-back
  (testing "Falls back to full tool list when all user selections are invalid"
    (let [container    {:gpu_models ["A16" "A40"]}
          requirements {:gpu_models ["BOGUS"]}
          result       (#'common/reconcile-gpu-models container requirements)]
      (is (= (set result) #{"A16" "A40"})
          "Should fall back to full tool list when intersection is empty"))))

(deftest test-reconcile-gpu-models-empty-user-falls-back
  (testing "Falls back to full tool list when user sends empty gpu_models"
    (let [container    {:gpu_models ["A16" "A40"]}
          requirements {:gpu_models []}
          result       (#'common/reconcile-gpu-models container requirements)]
      (is (= (set result) #{"A16" "A40"})
          "Should fall back to full tool list when user list is empty"))))

(deftest test-reconcile-gpu-models-nil-user-falls-back
  (testing "Falls back to full tool list when user gpu_models is nil"
    (let [container    {:gpu_models ["A16" "A40"]}
          requirements {}
          result       (#'common/reconcile-gpu-models container requirements)]
      (is (= (set result) #{"A16" "A40"})
          "Should fall back to full tool list when user list is nil"))))

(deftest test-reconcile-gpu-models-tool-no-models-returns-nil
  (testing "Returns nil when tool has no GPU models"
    (let [result-empty (#'common/reconcile-gpu-models {:gpu_models []} {:gpu_models ["A16"]})
          result-nil   (#'common/reconcile-gpu-models {} {:gpu_models ["A16"]})]
      (is (nil? result-empty)
          "Should return nil when tool's gpu_models is empty")
      (is (nil? result-nil)
          "Should return nil when tool has no gpu_models key"))))

;; ---------------------------------------------------------------------------
;; reconcile-container-requirements — GPU model field in end-to-end result
;; ---------------------------------------------------------------------------

(deftest test-reconcile-container-requirements-gpu-models-intersection
  (testing "Container requirements result includes gpu_models as the user/tool intersection"
    (let [container    {:min_gpus 0 :max_gpus 4
                        :gpu_models ["A16" "A40" "A100"]}
          requirements {:min_gpus 1 :max_gpus 2
                        :gpu_models ["A16" "A100"]}
          result       (#'common/reconcile-container-requirements container requirements)]
      (is (= (set (:gpu_models result)) #{"A16" "A100"})
          "Should contain the intersection of user and tool GPU models"))))

(deftest test-reconcile-container-requirements-gpu-models-fallback
  (testing "Container requirements falls back to full tool model list when user list is empty"
    (let [container    {:min_gpus 0 :max_gpus 4
                        :gpu_models ["A16" "A40"]}
          requirements {:min_gpus 1 :max_gpus 2
                        :gpu_models []}
          result       (#'common/reconcile-container-requirements container requirements)]
      (is (= (set (:gpu_models result)) #{"A16" "A40"})
          "Should fall back to full tool list when user sends empty gpu_models"))))

(deftest test-reconcile-container-requirements-gpu-models-absent-when-tool-has-none
  (testing "gpu_models key is absent from result when tool has no GPU models"
    (let [container    {:min_gpus 0 :max_gpus 2}
          requirements {:min_gpus 1 :max_gpus 2
                        :gpu_models ["A16"]}
          result       (#'common/reconcile-container-requirements container requirements)]
      (is (not (contains? result :gpu_models))
          "gpu_models key must be absent (not nil or empty) when tool has no models"))))

;; ---------------------------------------------------------------------------
;; build-environment / build-sensitive-environment — sensitive param split
;; ---------------------------------------------------------------------------

(defn- env-param
  [name value & {:keys [sensitive? omit-if-blank? step-id id]
                 :or   {step-id "step1"}}]
  {:type          "EnvironmentVariable"
   :step_id       step-id
   :id            (or id name)
   :name          name
   :default_value value
   :is_sensitive  sensitive?
   :omit_if_blank omit-if-blank?})

(deftest test-build-environment-excludes-sensitive-params
  (testing "build-environment only returns non-sensitive EnvironmentVariable params"
    (let [params         [(env-param "PLAIN_VAR" "plain-value")
                           (env-param "SECRET_VAR" "super-secret" :sensitive? true)]
          default-values (common/build-default-values-map params)
          result         (common/build-environment {} default-values params)]
      (is (= {"PLAIN_VAR" "plain-value"} result)
          "Should include only the non-sensitive param")
      (is (not (contains? result "SECRET_VAR"))
          "Sensitive param must never appear in the regular environment map"))))

(deftest test-build-sensitive-environment-includes-only-sensitive-params
  (testing "build-sensitive-environment only returns sensitive EnvironmentVariable params"
    (let [params         [(env-param "PLAIN_VAR" "plain-value")
                           (env-param "SECRET_VAR" "super-secret" :sensitive? true)]
          default-values (common/build-default-values-map params)
          result         (common/build-sensitive-environment {} default-values params)]
      (is (= {"SECRET_VAR" "super-secret"} result)
          "Should include only the sensitive param")
      (is (not (contains? result "PLAIN_VAR"))
          "Non-sensitive param must not appear in the sensitive environment map"))))

(deftest test-build-environment-ignores-non-environment-params
  (testing "Both builders ignore non-EnvironmentVariable params regardless of is_sensitive"
    (let [params [{:type "Text" :step_id "step1" :id "NOT_ENV" :name "NOT_ENV"
                   :default_value "x" :is_sensitive true}]
          default-values (common/build-default-values-map params)]
      (is (= {} (common/build-environment {} default-values params)))
      (is (= {} (common/build-sensitive-environment {} default-values params))))))

(deftest test-build-environment-handles-no-sensitive-params
  (testing "build-sensitive-environment returns an empty map when nothing is marked sensitive"
    (let [params         [(env-param "PLAIN_VAR" "plain-value")]
          default-values (common/build-default-values-map params)]
      (is (= {} (common/build-sensitive-environment {} default-values params))))))

;; ---------------------------------------------------------------------------
;; redact-sensitive-environment — persistence/logging redaction helper
;; ---------------------------------------------------------------------------

(deftest test-redact-sensitive-environment-strips-field
  (testing "redact-sensitive-environment removes :sensitive_environment from every step"
    (let [submission {:steps [{:environment {"A" "1"} :sensitive_environment {"B" "2"}}
                               {:environment {} :sensitive_environment {"C" "3"}}]}
          result     (common/redact-sensitive-environment submission)]
      (is (every? #(not (contains? % :sensitive_environment)) (:steps result))
          "No step should retain :sensitive_environment")
      (is (= [{"A" "1"} {}] (map :environment (:steps result)))
          "Regular :environment maps must be left untouched"))))

(deftest test-redact-sensitive-environment-handles-missing-field
  (testing "redact-sensitive-environment is a no-op when steps have no sensitive_environment"
    (let [submission {:steps [{:environment {"A" "1"}}]}
          result     (common/redact-sensitive-environment submission)]
      (is (= submission result)))))
