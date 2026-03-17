(ns apps.service.apps.jobs.params-test
  (:require
   [apps.service.apps.jobs.params :as params]
   [clojure.test :as t :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; update-resource-reqs — step matching and key renaming
;; ---------------------------------------------------------------------------

(deftest test-update-resource-reqs-renames-all-gpu-keys
  (testing "All resource requirement keys including GPU fields are correctly renamed"
    (let [requested-reqs [{:step_number      0
                           :max_cpu_cores    4
                           :min_cpu_cores    2
                           :min_gpus         1
                           :max_gpus         3
                           :min_memory_limit 2147483648
                           :min_disk_space   1073741824
                           :gpu_models       ["A16" "A40"]}]
          step-reqs      {:step_number 0}
          result         (#'params/update-resource-reqs requested-reqs step-reqs)]
      (is (= 4 (:default_max_cpu_cores result))
          "max_cpu_cores should be renamed to default_max_cpu_cores")
      (is (= 2 (:default_cpu_cores result))
          "min_cpu_cores should be renamed to default_cpu_cores")
      (is (= 1 (:default_gpus result))
          "min_gpus should be renamed to default_gpus")
      (is (= 3 (:default_max_gpus result))
          "max_gpus should be renamed to default_max_gpus")
      (is (= 2147483648 (:default_memory result))
          "min_memory_limit should be renamed to default_memory")
      (is (= 1073741824 (:default_disk_space result))
          "min_disk_space should be renamed to default_disk_space")
      (is (= ["A16" "A40"] (:default_gpu_models result))
          "gpu_models should be renamed to default_gpu_models"))))

(deftest test-update-resource-reqs-wrong-step-returns-nil
  (testing "Returns nil when no requested requirement matches the step number"
    (let [requested-reqs [{:step_number 0 :max_cpu_cores 4}]
          step-reqs      {:step_number 1}
          result         (#'params/update-resource-reqs requested-reqs step-reqs)]
      (is (nil? result)
          "Should return nil when step numbers don't match"))))

(deftest test-update-resource-reqs-missing-gpu-models-not-included
  (testing "No default_gpu_models key when gpu_models is absent from the request"
    (let [requested-reqs [{:step_number 0 :max_cpu_cores 4 :min_gpus 1}]
          step-reqs      {:step_number 0}
          result         (#'params/update-resource-reqs requested-reqs step-reqs)]
      (is (not (contains? result :default_gpu_models))
          "Should not contain default_gpu_models when gpu_models was not in request")
      (is (= 1 (:default_gpus result))
          "Other GPU fields should still be renamed"))))

(deftest test-update-resource-reqs-merges-with-step-reqs
  (testing "Existing step-reqs fields are preserved alongside renamed request fields"
    (let [requested-reqs [{:step_number 0 :max_gpus 2 :gpu_models ["A16"]}]
          step-reqs      {:step_number 0 :memory_limit 4294967296 :max_cpu_cores 8}
          result         (#'params/update-resource-reqs requested-reqs step-reqs)]
      (is (= 4294967296 (:memory_limit result))
          "Existing memory_limit from step-reqs should be preserved")
      (is (= 8 (:max_cpu_cores result))
          "Existing max_cpu_cores from step-reqs should be preserved")
      (is (= 2 (:default_max_gpus result))
          "Renamed max_gpus from request should be present")
      (is (= ["A16"] (:default_gpu_models result))
          "Renamed gpu_models from request should be present"))))
