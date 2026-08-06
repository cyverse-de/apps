(ns apps.service.groups-test
  (:require [apps.clients.groups :as ipg]
            [apps.routes.schemas.groups :as schema]
            [apps.service.groups :as groups]
            [clojure.test :refer [deftest is testing]]
            [schema.core :as s]))

;; The groups service answers a membership replacement with per-subject results
;; for the changes it performed. A member that was already present and stays
;; present appears in neither list, and a removal reports success=true, so the
;; results cannot be read as the new membership.
(def ^:private update-response
  {:results [{:subject_id   "added-user"
              :success      true
              :source_id    "ldap"
              :subject_name "Added User"}
             {:subject_id "removed-user"
              :success    true
              :source_id  "ldap"}
             {:subject_id "imaginary-user"
              :success    false
              :error      "user not found"}]})

;; What the member listing reports after the replacement: the kept member never
;; appeared in the results above, and the removed member must not resurface.
(def ^:private membership-after
  {:members [{:id "kept-user" :name "Kept User" :source_id "ldap"}
             {:id "added-user" :name "Added User" :source_id "ldap"}]})

(deftest update-workshop-group-members-test
  (testing "members come from re-reading the group, failures from the results"
    (with-redefs [ipg/update-workshop-group-members (constantly update-response)
                  ipg/get-workshop-group-members    (constantly membership-after)]
      (let [response (groups/update-workshop-group-members ["kept-user" "added-user" "imaginary-user"])]
        (is (= {:members  [{:id "kept-user" :name "Kept User" :source_id "ldap"}
                           {:id "added-user" :name "Added User" :source_id "ldap"}]
                :failures ["imaginary-user"]}
               response))
        (is (= response (s/validate schema/GroupMembersUpdateResponse response)))))))
