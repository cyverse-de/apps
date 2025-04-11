(ns apps.service.apps.groups-test
  (:require
   [apps.service.apps.test-fixtures :as atf]
   [apps.service.groups :as groups]
   [apps.test-fixtures :as tf]
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once tf/run-integration-tests tf/with-test-db tf/with-config atf/with-workspaces)
(use-fixtures :each atf/with-public-apps atf/with-test-app atf/with-test-tool)

(defn contains-member? [members member-id]
  (first (filter (comp (partial = member-id) :id) members)))

;; We should be able to list information about the workshop group.
(deftest test-workshop-group
  (let [group (groups/get-workshop-group)]
    (is (re-find #"workshop-users$" (:name group)))
    (is (= "role" (:type group)))))

;; We should be able to list the workshop group members.
(deftest test-workshop-group-member-listing
  (is (sequential? (:members (groups/get-workshop-group-members)))))

;; We should be able to update the list of workshop group members.
(deftest test-workshop-group-member-update
  (let [{:keys [members failures]} (groups/update-workshop-group-members ["testde1", "testde2", "testde3"])]
    (is (= (count members) 3))
    (is (= (count failures) 0)))
  (let [members (:members (groups/get-workshop-group-members))]
    (is (= (count members) 3))
    (is (contains-member? members "testde1"))
    (is (contains-member? members "testde2"))
    (is (contains-member? members "testde3"))))

;; Failed user lookups should still update the users group.
(deftest test-workshop-group-update-failed-subject-lookup
  (let [{:keys [members failures]} (groups/update-workshop-group-members ["testde1" "testde2", "imaginary-user"])]
    (is (= (count members) 2))
    (is (contains-member? members "testde1"))
    (is (contains-member? members "testde2"))
    (is (= (count failures) 1))
    (is ((set failures) "imaginary-user")))
  (let [members (:members (groups/get-workshop-group-members))]
    (is (= (count members) 2))
    (is (contains-member? members "testde1"))
    (is (contains-member? members "testde2")))
  (let [{:keys [members failures]} (groups/update-workshop-group-members [])]
    (is (zero? (count members)))
    (is (zero? (count failures))))
  (let [members (:members (groups/get-workshop-group-members))]
    (is (zero? (count members)))))
