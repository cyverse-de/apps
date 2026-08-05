(ns apps.service.groups-test
  (:require [apps.clients.groups :as ipg]
            [apps.routes.schemas.groups :as schema]
            [apps.service.groups :as groups]
            [clojure.test :refer [deftest is testing]]
            [schema.core :as s]))

;; The groups service answers a membership replacement with per-subject results.
(def ^:private update-response
  {:results [{:subject_id   "testde1"
              :success      true
              :source_id    "ldap"
              :subject_name "Test DE User 1"}
             {:subject_id "imaginary-user"
              :success    false
              :error      "user not found"}]})

(deftest update-workshop-group-members-test
  (testing "the per-subject results map onto the documented response shape"
    (with-redefs [ipg/update-workshop-group-members (constantly update-response)]
      (let [response (groups/update-workshop-group-members ["testde1" "imaginary-user"])]
        (is (= {:members  [{:id "testde1" :name "Test DE User 1" :source_id "ldap"}]
                :failures ["imaginary-user"]}
               response))
        (is (= response (s/validate schema/GroupMembersUpdateResponse response)))))))
