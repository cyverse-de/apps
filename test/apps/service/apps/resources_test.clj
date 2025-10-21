(ns apps.service.apps.resources-test
  (:require
   [apps.service.apps.de.jobs.resources :as r]
   [clojure.test :as t :refer [deftest]]))

(def gib (* 1024 1024 1024))

(defn reqs
  [min-mem max-mem min-cpu max-cpu & [min-gpu max-gpu]]
  {:min_memory_limit (when min-mem (* min-mem gib))
   :memory_limit     (when max-mem (* max-mem gib))
   :min_cpu_cores    min-cpu
   :max_cpu_cores    max-cpu
   :min_gpus         min-gpu
   :max_gpus         max-gpu})

(def test-cases
  [{:container    (reqs 2 256 2 256)
    :requirements (reqs 4 32 4 16)
    :expected     (reqs 4 32 4 16)
    :desc         "all values specified and within range"}
   {:container    (reqs 2 256 2 256)
    :requirements (reqs nil 32 4 16)
    :expected     (reqs 2 32 4 16)
    :desc         "unspecified minimum memory setting"}
   {:container    (reqs 2 256 2 256)
    :requirements (reqs 4 nil 4 16)
    :expected     (reqs 4 4 4 16)
    :desc         "unspecified maximum memory setting"}
   {:container    (reqs 2 256 2 256)
    :requirements (reqs 4 32 nil 16)
    :expected     (reqs 4 32 2 16)
    :desc         "unspecified min cpu setting"}
   {:container    (reqs 2 256 2 256)
    :requirements (reqs 4 32 4 nil)
    :expected     (reqs 4 32 4 4)
    :desc         "unspecified max cpu setting"}
   {:container    (reqs nil 256 2 256)
    :requirements (reqs 4 32 4 16)
    :expected     (reqs 4 32 4 16)
    :desc         "unspecified min memory setting in container"}
   {:container    (reqs 2 nil 2 256)
    :requirements (reqs 4 32 4 16)
    :expected     (reqs 4 16 4 16)
    :desc         "unspecified max memory setting in container, request higher than overall default"}
   {:container    (reqs 2 256 nil 256)
    :requirements (reqs 4 32 4 16)
    :expected     (reqs 4 32 4 16)
    :desc         "unspecified min cpu setting in container"}
   {:container    (reqs 2 256 2 nil)
    :requirements (reqs 4 32 4 16)
    :expected     (reqs 4 32 4 4)
    :desc         "unspecified max cpu setting in container, request higher than overall default"}
   {:container    (reqs 2 256 2 256)
    :requirements (reqs 1 32 1 16)
    :expected     (reqs 2 32 2 16)
    :desc         "minimum requests less than container minimums"}
   {:container    (reqs 2 256 2 256)
    :requirements (reqs 4 512 4 512)
    :expected     (reqs 4 256 4 256)
    :desc         "maximum requests greater than container maximums"}
   {:container    (reqs 2 256 2 256)
    :requirements (reqs 4 nil 4 nil)
    :expected     (reqs 4 4 4 4)
    :desc         "maximum requests not specified"}
   {:container    (reqs 2 256 2 256)
    :requirements (reqs nil nil nil nil)
    :expected     (reqs 2 2 2 2)
    :desc         "no requests specified"}
   {:container    (reqs nil nil nil nil)
    :requirements (reqs 32 32 32 32)
    :expected     (reqs 16 16 4 4)
    :desc         "no container settings specified"}
   {:container    (reqs nil 256 nil 256)
    :requirements (reqs nil nil nil nil)
    :expected     (reqs nil nil nil nil)
    :desc         "no requests specified, and no container minimums specified"}])

(def gpu-test-cases
  [{:container    (reqs 2 256 2 256 1 4)
    :requirements (reqs 4 32 4 16 2 3)
    :expected     (reqs 4 32 4 16 2 3)
    :desc         "GPU: all values specified and within range"}
   {:container    (reqs 2 256 2 256 1 4)
    :requirements (reqs 4 32 4 16 nil 3)
    :expected     (reqs 4 32 4 16 1 3)
    :desc         "GPU: unspecified minimum GPU setting"}
   {:container    (reqs 2 256 2 256 1 4)
    :requirements (reqs 4 32 4 16 2 nil)
    :expected     (reqs 4 32 4 16 2 2)
    :desc         "GPU: unspecified maximum GPU setting"}
   {:container    (reqs 2 256 2 256 1 4)
    :requirements (reqs 4 32 4 16 0 2)
    :expected     (reqs 4 32 4 16 1 2)
    :desc         "GPU: minimum request less than container minimum"}
   {:container    (reqs 2 256 2 256 1 4)
    :requirements (reqs 4 32 4 16 2 5)
    :expected     (reqs 4 32 4 16 2 4)
    :desc         "GPU: maximum request greater than container maximum"}
   {:container    (reqs 2 256 2 256 1 4)
    :requirements (reqs 4 32 4 16 nil nil)
    :expected     (reqs 4 32 4 16 1 1)
    :desc         "GPU: no GPU requests specified"}
   {:container    (reqs 2 256 2 256 nil nil)
    :requirements (reqs 4 32 4 16 2 2)
    :expected     (reqs 4 32 4 16 0 0)
    :desc         "GPU: no container GPU settings specified"}])

(deftest test-resource-requests
  (doseq [{:keys [container requirements expected desc]} test-cases]
    (t/testing desc
      (t/is (= (:min_memory_limit expected) (r/get-required-memory container requirements)))
      (t/is (= (:memory_limit expected) (r/get-max-memory container requirements)))
      (t/is (= (:min_cpu_cores expected) (r/get-required-cpus container requirements)))
      (t/is (= (:max_cpu_cores expected) (r/get-max-cpus container requirements))))))

(deftest test-gpu-resource-requests
  (doseq [{:keys [container requirements expected desc]} gpu-test-cases]
    (t/testing desc
      (t/is (= (:min_gpus expected) (r/get-required-gpus container requirements)))
      (t/is (= (:max_gpus expected) (r/get-max-gpus container requirements))))))
