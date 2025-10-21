(ns apps.service.apps.de.jobs.common-test
  (:require
   [apps.service.apps.de.jobs.common :as common]
   [apps.service.apps.de.jobs.resources :as resources]
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
      (is (or (not (contains? result :min_gpus)) (nil? (:min_gpus result)))
          "Should not include min_gpus or should be nil when not specified")
      (is (or (not (contains? result :max_gpus)) (nil? (:max_gpus result)))
          "Should not include max_gpus or should be nil when not specified"))))
