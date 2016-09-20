(ns apps.service.apps.integration-data-test
  (:use [apps.service.apps.test-utils :only [users get-user de-system-id permanently-delete-app]]
        [clojure.test]
        [kameleon.uuids :only [uuid]]
        [medley.core :only [remove-vals]])
  (:require [apps.persistence.app-metadata :as amp]
            [apps.service.apps :as apps]
            [apps.service.apps.test-fixtures :as atf]
            [apps.service.integration-data :as ids]
            [apps.test-fixtures :as tf]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [korma.core :as sql])
  (:import [clojure.lang ExceptionInfo]
           [java.util.regex Pattern]))

(use-fixtures :once tf/run-integration-tests tf/with-test-db tf/with-config atf/with-workspaces)
(use-fixtures :each atf/with-public-apps atf/with-test-app atf/with-test-tool)

(defn- get-integration-data [{:keys [username]}]
  (first (sql/select :integration_data
                     (sql/where {:user_id (sql/subselect :users
                                                         (sql/fields :id)
                                                         (sql/where {:username username}))}))))

(defn- get-integration-data-for-tool [tool-id]
  (first (sql/select :integration_data
                     (sql/where {:id (sql/subselect :tools
                                                    (sql/fields :integration_data_id)
                                                    (sql/where {:id tool-id}))}))))

(defn- get-integration-data-for-app [app-id]
  (first (sql/select :integration_data
                     (sql/where {:id (sql/subselect :apps
                                                    (sql/fields :integration_data_id)
                                                    (sql/where {:id app-id}))}))))

(defn- user-to-integration-data-name [{:keys [first-name last-name]}]
  (str first-name " " last-name))

;; The user ID should be associated with the integration data after the user creates an
;; app.
(deftest test-app-integration-data
  (let [user             atf/test-app-user
        integration-data (get-integration-data user)]
    (is (not (nil? integration-data)))
    (is (not (nil? (:user_id integration-data))))
    (is (= (:integrator_email integration-data) (:email user)))
    (is (= (:integrator_name integration-data) (user-to-integration-data-name user)))))

;; In this case, we expect the user ID associated with the integration data to be nil
;; because the user didn't have integration data before and the tools endpoint has no
;; way to get the username of the integrator.
(deftest test-tool-integration-data
  (let [user             atf/test-tool-user
        integration-data (get-integration-data-for-tool atf/test-tool-id)]
    (is (not (nil? integration-data)))
    (is (nil? (:user_id integration-data)))
    (is (= (:integrator_email integration-data) (:email user)))
    (is (= (:integrator_name integration-data) (user-to-integration-data-name user)))))

;; In this case, the user ID should be associated with the integration data because an
;; app has already been created by the user.
(deftest test-tool-integration-data-after-app-integration
  (let [tool-id          (first (:tool_ids (atf/create-tool atf/test-app-user "YATT")))
        integration-data (get-integration-data-for-tool tool-id)]
    (atf/delete-tool tool-id)
    (is (not (nil? integration-data)))
    (is (not (nil? (:user_id integration-data))))
    (is (= (:integrator_email integration-data) (:email atf/test-app-user)))
    (is (= (:integrator_name integration-data) (user-to-integration-data-name atf/test-app-user)))))

;; In the case of an app being copied, the integration data should have a user ID associated with it.
(deftest test-tool-integration-data-after-app-copy
  (let [user             (get-user :testde9)
        app-id           (:id (apps/copy-app user (:id (first atf/public-apps))))
        integration-data (get-integration-data-for-app app-id)]
    (is (not (nil? integration-data)))
    (is (not (nil? (:user_id integration-data))))
    (is (= (:integrator_email integration-data) (:email user)))
    (is (= (:integrator_name integration-data) (user-to-integration-data-name user)))
    (permanently-delete-app user de-system-id app-id)))

;; Integration data should automatically be updated if the email address changes.
(deftest test-integration-data-email-address-change
  (let [user (get-user :testde1)
        id1  (amp/get-integration-data (assoc user :email "foo@example.org"))
        id2  (amp/get-integration-data user)]
    (is (not (nil? id1)))
    (is (not (nil? (:user_id id1))))
    (is (= (:integrator_email id1) "foo@example.org"))
    (is (= (:integrator_name id1) (user-to-integration-data-name user)))
    (is (not (nil? id2)))
    (is (not (nil? (:user_id id2))))
    (is (= (:integrator_email id2) (:email user)))
    (is (= (:integrator_name id2) (user-to-integration-data-name user)))
    (is (= (:user_id id1) (:user_id id2)))))

;; Integration data should automatically be updated if the name changes.
(deftest test-integration-data-name-change
  (let [real (get-user :testde1)
        fake (assoc real :first-name "foo" :last-name "bar")
        idf  (amp/get-integration-data fake)
        idr  (amp/get-integration-data real)]
    (is (not (nil? idf)))
    (is (not (nil? (:user_id idf))))
    (is (= (:integrator_email idf) (:email real)))
    (is (= (:integrator_name idf) (user-to-integration-data-name fake)))
    (is (not (nil? idr)))
    (is (not (nil? (:user_id idr))))
    (is (= (:integrator_email idr) (:email real)))
    (is (= (:integrator_name idr) (user-to-integration-data-name real)))
    (is (= (:user_id idf) (:user_id idr)))))

;; Integration data should not be updated if there's a conflict.
(deftest test-integration-data-change-conflict
  (let [u1  (get-user :testde1)
        u2  (get-user :testde2)
        uc  (assoc u1 :username (:username u2))
        id1 (amp/get-integration-data u1)
        id2 (amp/get-integration-data u2)
        idc (amp/get-integration-data uc)]
    (is (not (nil? id1)))
    (is (not (nil? id2)))
    (is (not (nil? idc)))
    (is (= id2 idc))
    (is (= id1 (get-integration-data u1)))))

(defn- list-integration-data [params]
  (ids/list-integration-data (get-user :testde1) params))

;; We should be able to list integration data.
(deftest test-integration-data-listing
  (let [results (list-integration-data {})]
    (is (not (nil? results)))
    (is (not (nil? (:integration_data results))))
    (is (not (nil? (:total results))))
    (is (= (count (:integration_data results)) (:total results)))))

;; We should be able to limit the number of results.
(deftest test-integration-data-listing-limit
  (let [results (list-integration-data {:limit 1})]
    (is (not (nil? results)))
    (is (not (nil? (:integration_data results))))
    (is (not (nil? (:total results))))
    (is (= (count (:integration_data results)) 1))
    (is (not= (:total results) 1))))

;; We should be able to offset the results.
(deftest test-integration-data-listing-offset
  (let [ids (:integration_data (list-integration-data {:limit 2}))
        id1 (first (:integration_data (list-integration-data {:limit 1})))
        id2 (first (:integration_data (list-integration-data {:limit 1 :offset 1})))]
    (is (= id1 (first ids)))
    (is (= id2 (second ids)))))

;; We should be able to sort results by name.
(deftest test-integration-data-sort-by-name
  (let [asc  (:integration_data (list-integration-data {:sort-field :name :sort-dir "ASC"}))
        desc (:integration_data (list-integration-data {:sort-field :name :sort-dir "DESC"}))]
    (is (not= (first asc) (first desc)))
    (is (= (:name (first asc)) (:name (last desc))))
    (is (= (:name (last asc)) (:name (first desc))))))

;; We should be able to sort results by email address.
(deftest test-integration-data-sort-by-email
  (let [asc  (:integration_data (list-integration-data {:sort-field :email :sort-dir "ASC"}))
        desc (:integration_data (list-integration-data {:sort-field :email :sort-dir "DESC"}))]
    (is (not= (first asc) (first desc)))
    (is (= (:email (first asc)) (:email (last desc))))
    (is (= (:email (last asc)) (:email (first desc))))))

;; We should be able to sort results by username.
(deftest test-integration-data-sort-by-username
  (let [asc  (:integration_data (list-integration-data {:sort-field :username :sort-dir "ASC"}))
        desc (:integration_data (list-integration-data {:sort-field :username :sort-dir "DESC"}))]
    (is (not= (first asc) (first desc)))
    (is (= (:username (first asc)) (:username (last desc))))
    (is (= (:username (last asc)) (:username (first desc))))))

(defn- handle-wildcard-character [c]
  (condp = c
    \* ".*"
    \? ".?"
    (Pattern/quote (str c))))

(defn- search-string-to-regex [search-string]
  (re-pattern (str "(?i)" (apply str (map handle-wildcard-character search-string)))))

(defn- integration-data-entry-matches? [search-string entry]
  (let [regex (search-string-to-regex search-string)]
    (or (re-find regex (:email entry))
        (re-find regex (:name entry)))))

(defn- check-integration-data-search [search-string]
  (testing (str "filtered search for " search-string)
    (let [unfiltered (set (:integration_data (list-integration-data {})))
          filtered   (set (:integration_data (list-integration-data {:search search-string})))
          matches?   (partial integration-data-entry-matches? search-string)]
      (is (seq unfiltered))
      (is (every? matches? filtered))
      (is (= filtered (set (filter matches? unfiltered)))))))

;; We should be able to filter results by username and email address.
(deftest test-integration-data-search
  (check-integration-data-search "testde1")
  (check-integration-data-search "test*9")
  (check-integration-data-search "testde?0"))

(defn- add-integration-data [username email name]
  (ids/add-integration-data (get-user :testde1) (remove-vals nil? {:username username :email email :name name})))

(defn- delete-integration-data [{:keys [id]}]
  (when id (sql/delete :integration_data (sql/where {:id id}))))

;; We should be able to add a new integration data record.
(deftest test-integration-data-insertion
  (let [integration-data (add-integration-data "foo" "foo@example.org" "Foo Bar")]
    (is (not (nil? (:id integration-data))))
    (is (= "foo" (:username integration-data)))
    (is (= "foo@example.org" (:email integration-data)))
    (is (= "Foo Bar" (:name integration-data)))
    (delete-integration-data integration-data)))

;; We should not be able to add multiple integration data records for the same username.
(deftest test-integration-data-insertion-duplicate-username
  (let [integration-data (add-integration-data "foo" "foo@example.org" "Foo Bar")]
    (is (thrown-with-msg? ExceptionInfo #"already has an integration data record"
                          (add-integration-data "foo" "bar@example.org" "Bar Baz")))
    (delete-integration-data integration-data)))

;; We should not be able to add multiple integraiton data records for the same email address.
(deftest test-integration-data-insertion-duplicate-email
  (let [integration-data (add-integration-data "foo" "foo@example.org" "Foo Bar")]
    (is (thrown-with-msg? ExceptionInfo #"already has an integration data record"
                          (add-integration-data "bar" "foo@example.org" "Bar Baz")))
    (delete-integration-data integration-data)))

(defn- update-integration-data [{:keys [id]} email name]
  (ids/update-integration-data (get-user :testde1) id {:email email :name name}))

;; We should be able to edit an existing integration data record.
(deftest test-integration-data-udpate
  (let [integration-data (add-integration-data "foo" "foo@example.org" "Foo Bar")
        updated          (update-integration-data integration-data "bar@example.org" "Bar Baz")
        fetched          (amp/get-integration-data-by-id (:id integration-data))]
    (is (not (nil? updated)))
    (is (= "bar@example.org" (:email updated)))
    (is (= "Bar Baz" (:name updated)))
    (is (not (nil? fetched)))
    (is (= "bar@example.org" (:integrator_email fetched)))
    (is (= "Bar Baz" (:integrator_name fetched)))
    (delete-integration-data integration-data)))

;; An attempt to edit a non-existent integration data record should fail.
(deftest test-integration-data-update-non-existent
  (is (thrown-with-msg? ExceptionInfo #"does not exist"
                        (update-integration-data {:id (uuid)} "foo@example.org" "Foo Bar"))))

;; We should be able to edit just the name of an integration data record.
(deftest test-integration-data-update-name-only
  (let [integration-data (add-integration-data "foo" "foo@example.org" "Foo Bar")
        updated          (update-integration-data integration-data "foo@example.org" "Bar Baz")
        fetched          (amp/get-integration-data-by-id (:id integration-data))]
    (is (not (nil? updated)))
    (is (= "foo@example.org" (:email updated)))
    (is (= "Bar Baz" (:name updated)))
    (is (not (nil? fetched)))
    (is (= "foo@example.org" (:integrator_email fetched)))
    (is (= "Bar Baz" (:integrator_name fetched)))
    (delete-integration-data integration-data)))

;; We should be able to edit just the email address of an integration data record.
(deftest test-integration-data-update-email-only
  (let [integration-data (add-integration-data "foo" "foo@example.org" "Foo Bar")
        updated          (update-integration-data integration-data "bar@example.org" "Foo Bar")
        fetched          (amp/get-integration-data-by-id (:id integration-data))]
    (is (not (nil? updated)))
    (is (= "bar@example.org" (:email updated)))
    (is (= "Foo Bar" (:name updated)))
    (is (not (nil? fetched)))
    (is (= "bar@example.org" (:integrator_email fetched)))
    (is (= "Foo Bar" (:integrator_name fetched)))
    (delete-integration-data integration-data)))

;; We should not be able to change the email address to one that already has an integration data record.
(deftest test-integration-data-duplicate-email
  (let [foo (add-integration-data "foo" "foo@example.org" "Foo Bar")
        bar (add-integration-data "bar" "bar@example.org" "Bar baz")]
    (is (thrown-with-msg? ExceptionInfo #"already has an integration data record"
                          (update-integration-data foo (:email bar) (:name foo))))
    (delete-integration-data foo)
    (delete-integration-data bar)))

;; We should be able to get information about an integration data record.
(deftest test-integration-data-get
  (let [foo     (add-integration-data "foo" "foo@example.org" "Foo Bar")
        fetched (ids/get-integration-data (get-user :testde1) (:id foo))]
    (is (= foo fetched))
    (delete-integration-data foo)))

;; We should get an error message if the integration data record doesn't exist.
(deftest test-integration-data-get-non-existent
  (is (thrown-with-msg? ExceptionInfo #"does not exist"
                        (ids/get-integration-data (get-user :testde1) (uuid)))))

;; We should be able to delete an integration data record.
(deftest test-integration-data-deletion
  (let [integration-data (add-integration-data "foo" "foo@example.org" "Foo Bar")]
    (ids/delete-integration-data (get-user :testde1) (:id integration-data))
    (is (nil? (amp/get-integration-data-by-id (:id integration-data))))))

;; We should not be able to delete an integration data record associated with an app.
(deftest test-integration-data-deletion-app
  (let [integration-data (get-integration-data-for-app (:id atf/test-app))]
    (is (not (nil? integration-data)))
    (is (thrown-with-msg? ExceptionInfo #"used by one or more apps"
                          (ids/delete-integration-data (get-user :testde1) (:id integration-data))))))

;; We should not be able to delete an integration data record associated with a tool.
(deftest test-integration-data-deletion-tool
  (let [integration-data (get-integration-data-for-tool atf/test-tool-id)]
    (is (not (nil? integration-data)))
    (is (thrown-with-msg? ExceptionInfo #"used by one or more tools"
                          (ids/delete-integration-data (get-user :testde1) (:id integration-data))))))

;; We should get an error message if we try to delete a non-existent integration data record.
(deftest test-integration-data-deletion-non-existent
  (is (thrown-with-msg? ExceptionInfo #"does not exist"
                        (ids/delete-integration-data (get-user :testde1) (uuid)))))

;; We should be able to get an integration data record for an app.
(deftest test-app-integration-data-retrieval
  (let [actual   (apps/get-app-integration-data (get-user :testde1) de-system-id (:id atf/test-app))
        expected (get-integration-data-for-app (:id atf/test-app))]
    (is (= (:integrator_email expected) (:email actual)))
    (is (= (:integrator_name expected) (:name actual)))))

;; Attempting to get the integration data for an unknown app should fail.
(deftest test-app-integration-data-retrieval-not-found
  (is (thrown-with-msg? ExceptionInfo #"no integration data found for app"
                        (apps/get-app-integration-data (get-user :testde1) de-system-id (uuid)))))

;; We should be able to get an integration data record for a tool.
(deftest test-tool-integration-data-retrieval
  (let [actual   (apps/get-tool-integration-data (get-user :testde1) atf/test-tool-id)
        expected (get-integration-data-for-tool atf/test-tool-id)]
    (is (= (:integrator_email expected) (:email actual)))
    (is (= (:integrator_name expected) (:name actual)))))

;; Attempting to get the integration data for an unknown tool should fail.
(deftest test-tool-integration-data-retrieval-not-found
  (is (thrown-with-msg? ExceptionInfo #"no integration data found for tool"
                        (apps/get-tool-integration-data (get-user :testde1) (uuid)))))

;; We should be able to change the integration data record associated with an app.
(deftest test-app-integration-data-update
  (let [user     (get-user :testde1)
        app-id   (:id atf/test-app)
        original (apps/get-app-integration-data user de-system-id app-id)
        new      (add-integration-data "foo" "foo@example.org" "Foo Bar")]
    (is (not= original new))
    (apps/update-app-integration-data user app-id (:id new))
    (is (= new (apps/get-app-integration-data user de-system-id app-id)))
    (apps/update-app-integration-data user app-id (:id original))
    (is (= original (apps/get-app-integration-data user de-system-id app-id)))
    (delete-integration-data new)))

;; Attempting to set the integration data record for a non-existent app should fail.
(deftest test-app-integration-data-update-missing-app
  (let [new (add-integration-data "foo" "foo@example.org" "Foo Bar")]
    (is (thrown-with-msg? ExceptionInfo #"could not be found"
                          (apps/update-app-integration-data (get-user :testde1) (uuid) (:id new))))
    (delete-integration-data new)))

;; Attempting to assign a non-existent integration data record to an app should fail.
(deftest test-app-integration-data-update-missing-integration-data
  (is (thrown-with-msg? ExceptionInfo #"does not exist"
                        (apps/update-app-integration-data (get-user :testde1) (:id atf/test-app) (uuid)))))

;; We should be able to change the integration data record associated with a tool.
(deftest test-tool-integration-data-update
  (let [user     (get-user :testde1)
        original (apps/get-tool-integration-data user atf/test-tool-id)
        new      (add-integration-data "foo" "foo@example.org" "Foo Bar")]
    (is (not= original new))
    (apps/update-tool-integration-data user atf/test-tool-id (:id new))
    (is (= new (apps/get-tool-integration-data user atf/test-tool-id)))
    (apps/update-tool-integration-data user atf/test-tool-id (:id original))
    (is (= original (apps/get-tool-integration-data user atf/test-tool-id)))
    (delete-integration-data new)))

;; Attempting to set the integration data record for a non-existent tool should fail.
(deftest test-tool-integration-data-update-missing-tool
  (let [new (add-integration-data "foo" "foo@example.org" "Foo Bar")]
    (is (thrown-with-msg? ExceptionInfo #"could not be found"
                          (apps/update-tool-integration-data (get-user :testde1) (uuid) (:id new))))
    (delete-integration-data new)))

;; Attempting to assign a non-existent integration data record to a tool should fail.
(deftest test-tool-integration-data-missing-integration-data
  (is (thrown-with-msg? ExceptionInfo #"does not exist"
                        (apps/update-tool-integration-data (get-user :testde1) atf/test-tool-id (uuid)))))
