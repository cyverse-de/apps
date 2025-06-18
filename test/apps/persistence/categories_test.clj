(ns apps.persistence.categories-test
  (:require
   [apps.persistence.categories :refer [add-hierarchy-version get-active-hierarchy-version]]
   [apps.test-fixtures :refer [run-integration-tests with-test-db]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [korma.core :as sql]))

(defn clean-up-hierarchy-versions [f]
  (f)
  (sql/delete :app_hierarchy_version))

(use-fixtures :once with-test-db run-integration-tests)
(use-fixtures :each clean-up-hierarchy-versions)

(deftest hierarchy-version-test
  (testing "Test setting and fetching app category hierarchy versions."
    (is (nil? (get-active-hierarchy-version)))

    (add-hierarchy-version "test-user" "v1")
    (is (= "v1" (get-active-hierarchy-version)))

    (add-hierarchy-version "test-user" "v2")
    (is (= "v2" (get-active-hierarchy-version)))

    (add-hierarchy-version "test-user" "v1")
    (is (= "v1" (get-active-hierarchy-version)))

    (add-hierarchy-version "test-user" "foo")
    (is (= "foo" (get-active-hierarchy-version)))))
