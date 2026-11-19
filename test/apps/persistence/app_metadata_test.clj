(ns apps.persistence.app-metadata-test
  (:require
   [apps.persistence.app-metadata :as amp]
   [clojure.test :refer [deftest is testing]]
   [korma.core :as sql]))

;; These tests only render SQL (korma's sql-only), so they don't need a database.

(defn- update-sql
  [parameter]
  (with-redefs [amp/get-parameter-type-id (constantly "type-id")]
    (sql/sql-only (amp/update-app-parameter (merge {:id "p1" :type "EnvironmentVariable" :name "SECRET"} parameter)))))

(deftest test-update-app-parameter-is-sensitive
  (testing "an update with is_sensitive true or false sets the column"
    (is (re-find #"\"is_sensitive\" = \?" (update-sql {:is_sensitive true})))
    (is (re-find #"\"is_sensitive\" = \?" (update-sql {:is_sensitive false}))))
  (testing "an update without is_sensitive leaves the column alone"
    (is (not (re-find #"is_sensitive" (update-sql {}))))))

(deftest test-filter-valid-app-parameter-values-is-sensitive
  (testing "is_sensitive survives the insert/update whitelist with its value"
    (is (true? (:is_sensitive (#'amp/filter-valid-app-parameter-values {:is_sensitive true}))))
    (is (false? (:is_sensitive (#'amp/filter-valid-app-parameter-values {:is_sensitive false}))))
    (is (not (contains? (#'amp/filter-valid-app-parameter-values {:name "x"}) :is_sensitive)))))
