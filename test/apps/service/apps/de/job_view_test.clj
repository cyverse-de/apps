(ns apps.service.apps.de.job-view-test
  (:require
   [apps.service.apps.de.job-view :as job-view]
   [clojure.test :as t :refer [deftest is testing]]))

(deftest test-format-step-resource-requirements-includes-gpu-defaults
  (testing "Step resource requirements formatting includes GPU defaults for interactive apps"
    (let [requirements {:min_memory_limit 1073741824
                        :memory_limit     2147483648
                        :min_cpu_cores    1
                        :max_cpu_cores    4
                        :min_gpus         0
                        :max_gpus         1}
          step-number  1
          result       (#'job-view/format-step-resource-requirements requirements step-number true)]
      (is (contains? result :max_gpus) "Result should include max_gpus default")
      (is (number? (:max_gpus result)) "max_gpus should be a number")
      (is (= 1 (:step_number result)) "Should include step number"))))

(deftest test-format-step-resource-requirements-merges-gpu-defaults
  (testing "Step resource requirements merges defaults with existing GPU values"
    (let [requirements {:max_gpus 2}
          step-number  1
          result       (#'job-view/format-step-resource-requirements requirements step-number true)]
      (is (= 2 (:max_gpus result)) "Should preserve existing max_gpus value")
      (is (contains? result :max_cpu_cores) "Should add default CPU cores")
      (is (contains? result :memory_limit) "Should add default memory limit"))))

(deftest test-format-step-resource-requirements-no-gpu-defaults-when-not-requested
  (testing "Step resource requirements does not add GPU defaults when add-defaults is false"
    (let [requirements {:memory_limit 2147483648}
          step-number  1
          result       (#'job-view/format-step-resource-requirements requirements step-number false)]
      (is (= 1 (:step_number result)) "Should include step number")
      (is (= 2147483648 (:memory_limit result)) "Should preserve existing values")
      (is (not (and (contains? result :max_gpus) 
                    (some? (:max_gpus result))
                    (not (contains? requirements :max_gpus))))
          "Should not add max_gpus default when add-defaults is false"))))

;; ---------------------------------------------------------------------------
;; GPU model preservation and non-injection
;; ---------------------------------------------------------------------------

(deftest test-format-step-resource-requirements-preserves-gpu-models
  (testing "Existing gpu_models in requirements are preserved by format-step-resource-requirements"
    (let [requirements {:gpu_models ["A16" "A40"]
                        :max_gpus   2}
          step-number  1
          result       (#'job-view/format-step-resource-requirements requirements step-number true)]
      (is (= ["A16" "A40"] (:gpu_models result))
          "Should preserve the tool's gpu_models list when present"))))

(deftest test-format-step-resource-requirements-no-gpu-models-when-not-interactive
  (testing "gpu_models not injected when add-defaults is false, even if absent"
    (let [requirements {:max_gpus 2}
          step-number  1
          result       (#'job-view/format-step-resource-requirements requirements step-number false)]
      (is (not (contains? result :gpu_models))
          "Should not inject gpu_models when add-defaults is false"))))
