(ns apps.service.apps.jobs.params-test
  (:require
   [apps.protocols]
   [apps.service.apps.jobs.params :as params]
   [cheshire.core :as cheshire]
   [clojure.test :as t :refer [deftest is testing]])
  (:import [org.postgresql.util PGobject]))

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

;; ---------------------------------------------------------------------------
;; Sensitive parameters on relaunch and in parameter value listings
;; ---------------------------------------------------------------------------

(deftest test-update-prop-does-not-prefill-sensitive-props
  (testing "update-prop never pre-fills a sensitive property from a saved submission's config"
    (let [config {:step1_secret "hunter2" :step1_plain "visible"}
          prop   {:id "step1_secret" :type "EnvironmentVariable" :is_sensitive true :required true}
          result (#'params/update-prop {:username "test"} config prop)]
      (is (= prop result) "The sensitive property must come back unchanged")
      (is (not (contains? result :value)))
      (is (not (contains? result :defaultValue)))))
  (testing "update-prop still pre-fills non-sensitive properties"
    (let [result (#'params/update-prop {:username "test"} {:step1_plain "visible"}
                                       {:id "step1_plain" :type "EnvironmentVariable"})]
      (is (= "visible" (:value result)))
      (is (= "visible" (:defaultValue result))))))

(defn- json-pgobject
  [v]
  (doto (PGobject.)
    (.setType "json")
    (.setValue (cheshire/encode v))))

(deftest test-get-parameter-values-omits-sensitive-params
  (testing "get-parameter-values leaves sensitive params out, even if an old submission stored their values"
    (let [param-defs  [{:id "secret" :step_id "step1" :type "EnvironmentVariable" :label "Secret"
                        :is_sensitive true}
                       {:id "plain" :step_id "step1" :type "EnvironmentVariable" :label "Plain"}]
          apps-client (reify apps.protocols.Apps
                        (getParamDefinitions [_ _ _ _] param-defs))
          job         {:system_id  "de"
                       :app_id     "app"
                       :submission (json-pgobject {:config {:step1_secret "hunter2" :step1_plain "visible"}})}
          result      (params/get-parameter-values apps-client job)]
      (is (= ["step1_plain"] (map :full_param_id result)))
      (is (not-any? #(= "hunter2" (get-in % [:param_value :value])) result)))))
