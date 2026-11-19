(ns apps.service.apps.de.edit-test
  (:require
   [apps.service.apps.de.edit :as edit]
   [clojure.test :refer [deftest is testing]]
   [kameleon.uuids :refer [uuid]]
   [korma.core :as sql]))

;; korma's dry-run prints the SQL each query would run, including the nested `with` queries, without a database.

(deftest test-get-app-details-selects-is-sensitive
  (testing "the app editor's parameter query selects is_sensitive, so /ui and copies keep the flag"
    (let [out (with-out-str (sql/dry-run (#'edit/get-app-details (uuid) (uuid))))]
      (is (re-find #"omit_if_blank" out) "Sanity check: the parameters query should have been rendered")
      (is (re-find #"is_sensitive" out)))))
