(ns apps.persistence.jobs-test
  (:require
   [apps.persistence.jobs :as jp]
   [cheshire.core :as cheshire]
   [clojure.test :refer [deftest is testing]]))

;; None of these tests touch the database: every query save-job and redact-submission would run is redefined.

(def ^:private app-id "11111111-1111-1111-1111-111111111111")
(def ^:private version-id "22222222-2222-2222-2222-222222222222")

(def ^:private raw-submission
  {:system_id      "de"
   :app_id         app-id
   :app_version_id version-id
   :name           "job"
   :config         {:step1_secret "hunter2" :step1_plain "visible"}})

(defn- with-sensitive-params
  [sensitive-params f]
  (with-redefs [apps.persistence.app-metadata/get-sensitive-parameters (fn [_] sensitive-params)
                apps.persistence.app-listing/get-app-listing           (fn [_] {:version_id version-id})]
    (f)))

(deftest test-save-job-redacts-raw-submission
  (testing "save-job never stores a sensitive config value from the raw submission"
    (let [saved (atom nil)]
      (with-sensitive-params [{:step_id "step1" :id "secret" :is_sensitive true}]
        #(with-redefs [jp/job-type-id-from-system-id         (constantly 1)
                       apps.persistence.users/get-user-id    (constantly 1)
                       jp/save-job-with-submission           (fn [_ submission] (reset! saved submission))]
           (jp/save-job {:system_id "de" :username "test" :app_id app-id :app_version_id version-id}
                        raw-submission)))
      (let [stored (cheshire/decode @saved true)]
        (is (not (contains? (:config stored) :step1_secret)) "The sensitive value must not be persisted")
        (is (not (re-find #"hunter2" @saved)))
        (is (= "visible" (get-in stored [:config :step1_plain])))))))

(deftest test-redact-submission-uses-latest-version-without-version-id
  (testing "the latest app version's params are used when the submission has no app_version_id"
    (let [looked-up (atom nil)]
      (with-redefs [apps.persistence.app-metadata/get-sensitive-parameters
                    (fn [v] (reset! looked-up v) [{:step_id "step1" :id "secret" :is_sensitive true}])
                    apps.persistence.app-listing/get-app-listing (fn [_] {:version_id version-id})]
        (is (= {:step1_plain "visible"}
               (:config (jp/redact-submission (dissoc raw-submission :app_version_id))))))
      (is (= version-id @looked-up)))))

(deftest test-redact-submission-non-de-apps
  (testing "submissions for apps outside the DE are left alone and don't hit the DE's app tables"
    (with-redefs [apps.persistence.app-metadata/get-sensitive-parameters
                  (fn [_] (throw (AssertionError. "should not be called")))]
      (let [submission (assoc raw-submission :system_id "tapis" :app_id "some-tapis-app-1.0")]
        (is (= submission (jp/redact-submission submission)))))))
