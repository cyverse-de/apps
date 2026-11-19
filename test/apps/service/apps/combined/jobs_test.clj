(ns apps.service.apps.combined.jobs-test
  (:require
   [apps.persistence.jobs :as jp]
   [apps.service.apps.combined.jobs :as cj]
   [cheshire.core :as cheshire]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]])
  (:import [org.postgresql.util PGobject]))

;; A pipeline of a DE step followed by a Tapis step and another DE step, i.e. three units of execution.
(def ^:private app-steps
  [{:step_id "s1" :job_type "DE"}
   {:step_id "s2" :job_type "Tapis" :external_app_id "ext"}
   {:step_id "s3" :job_type "DE"}])

(def ^:private job-steps
  [{:step_number 1 :app_step_number 1}
   {:step_number 2 :app_step_number 2}
   {:step_number 3 :app_step_number 3}])

(defn- with-sensitive-params
  [sensitive-params f]
  (with-redefs [apps.persistence.app-metadata/load-app-steps           (constantly app-steps)
                apps.persistence.app-metadata/get-sensitive-parameters (constantly sensitive-params)]
    (f)))

(defn- validation-error
  [sensitive-params]
  (with-sensitive-params sensitive-params
    #(try+
      (#'cj/validate-sensitive-params "version" job-steps)
      nil
      (catch map? e e))))

(deftest test-validate-sensitive-params
  (testing "sensitive params in a later unit of execution are refused at submission time"
    (is (= :clojure-commons.exception/bad-request
           (:type (validation-error [{:step_id "s3" :id "secret" :is_sensitive true}])))))
  (testing "sensitive params in the first unit of execution are allowed"
    (is (nil? (validation-error [{:step_id "s1" :id "secret" :is_sensitive true}]))))
  (testing "a single-unit job is always allowed"
    (with-sensitive-params [{:step_id "s1" :id "secret" :is_sensitive true}]
      #(is (nil? (#'cj/validate-sensitive-params "version" (take 1 job-steps)))))))

(defn- json-pgobject
  [v]
  (doto (PGobject.)
    (.setType "json")
    (.setValue (cheshire/encode v))))

(deftest test-submit-next-step-fails-step-with-sensitive-params
  (testing "a later pipeline step that needs sensitive values is failed rather than launched without them"
    (let [updates   (atom [])
          submitted (atom false)
          job       {:id "job" :app_version_id "version" :submission (json-pgobject {:config {}})}]
      (with-sensitive-params [{:step_id "s3" :id "secret" :is_sensitive true}]
        #(with-redefs [jp/get-job-step-number   (fn [_ n] (nth job-steps (dec n)))
                       jp/update-job-step-number (fn [_ n m] (swap! updates conj [:step n (:status m)]))
                       jp/update-job            (fn [_ status _] (swap! updates conj [:job status]))
                       cj/submit-job-step       (fn [& _] (reset! submitted true))]
           (#'cj/submit-next-step nil nil job {:job_id "job" :step_number 2})))
      (is (false? @submitted) "The step must not be submitted")
      (is (= [[:step 3 jp/failed-status] [:job jp/failed-status]] @updates)))))
