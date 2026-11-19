(ns apps.service.apps.jobs.resubmit-test
  (:require
   [apps.persistence.jobs :as jp]
   [apps.service.apps.jobs.resubmit :as resubmit]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]))

(deftest test-resubmit-refuses-jobs-with-sensitive-params
  (testing "jobs whose app has sensitive params can't be resubmitted from their stored submission"
    (let [submitted (atom false)
          error     (with-redefs [jp/sensitive-config-keys        (fn [{:keys [id]}] (if (= id "j2") #{:s1_secret} #{}))
                                  apps.service.apps.jobs.submissions.async/resubmit-jobs
                                  (fn [& _] (reset! submitted true))]
                      (try+
                       (resubmit/resubmit-jobs nil {:username "test"} [{:id "j1"} {:id "j2"}])
                       nil
                       (catch map? e e)))]
      (is (= :clojure-commons.exception/bad-request (:type error)))
      (is (= ["j2"] (:job-ids error)))
      (is (false? @submitted) "Nothing in the request should be resubmitted"))))
