(ns apps.tools-test
  (:require
   [apps.persistence.entities :refer [tools]]
   [apps.test-fixtures :refer [run-integration-tests with-test-db with-test-user]]
   [apps.tools :refer [admin-update-tool get-tool]]
   [apps.user :refer [current-user]]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [korma.core :refer [select where]]))

(use-fixtures :each with-test-db with-test-user run-integration-tests)

(deftest test-get-tool
  (let [tool-map (first (select tools (where {:name "notreal"})))]
    (testing "(get-tool) returns the right tool"
      (let [tool-id        (:id tool-map)
            user           (:shortUsername current-user)
            retrieved-tool (get-tool user tool-id false)]
        (is (= tool-id (:id retrieved-tool)))
        (is (contains? retrieved-tool :restricted))
        (is (contains? retrieved-tool :time_limit_seconds))
        (is (= (:restricted retrieved-tool) false))
        (is (= (:time_limit_seconds retrieved-tool) 0))))))

(deftest test-update-tool
  (let [tool-map (first (select tools (where {:name "notreal"})))]
    (testing "(update-tool) actually updates the tool"
      (let [tool-id      (:id tool-map)
            user         (:shortUsername current-user)
            updated-tool (admin-update-tool user false (-> tool-map
                                                           (assoc :restricted true)
                                                           (assoc :time_limit_seconds 10)))]
        (is (= tool-id (:id updated-tool)))
        (is (contains? updated-tool :restricted))
        (is (contains? updated-tool :time_limit_seconds))
        (is (:restricted updated-tool))
        (is (= (:time_limit_seconds updated-tool) 10))
        (admin-update-tool user false tool-map)))))
