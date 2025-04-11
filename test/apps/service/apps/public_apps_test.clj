(ns apps.service.apps.public-apps-test
  (:require
   [apps.constants :refer [de-system-id]]
   [apps.service.apps :as apps]
   [apps.service.apps.de.listings :refer [my-public-apps-id trash-category-id]]
   [apps.service.apps.test-fixtures :as atf]
   [apps.service.apps.test-utils :refer [get-user permanently-delete-app]]
   [apps.test-fixtures :as tf]
   [apps.util.config :as config]
   [apps.validation :as v]
   [clojure.test :refer [deftest is use-fixtures]]
   [korma.core :as sql]
   [permissions-client.core :as pc])
  (:import
   (clojure.lang ExceptionInfo)))

(use-fixtures :once tf/run-integration-tests tf/with-test-db tf/with-config atf/with-workspaces)
(use-fixtures :each atf/with-public-apps atf/with-test-app atf/with-test-tool)

(defn- list-apps
  [user category-id]
  (:apps (apps/list-apps-in-category user de-system-id category-id {})))

(defn- find-app
  [{app-id :id} apps]
  (filter (comp (partial = app-id) :id) apps))

;; FIXME the Beta category is obsolete
(deftest test-marked-as-public
  (let [user        (get-user :testde1)
        app         (atf/create-test-app user "To be published")
        _           (sql/delete :app_documentation (sql/where {:app_id (:id app)}))
        _           (apps/make-app-public user de-system-id app)
        listed      (first (:apps (apps/search-apps user {:search (:name app)})))
        beta-id     (:id (atf/get-beta-category user))
        apps        (list-apps user beta-id)
        public-apps (list-apps user my-public-apps-id)]
    (is (not (nil? listed)))
    (is (:is_public listed))
    (is (empty? (find-app app apps)))
    (is (seq (find-app app public-apps)))
    (permanently-delete-app user de-system-id (:id app) true)))

(deftest test-publishable
  (let [user (get-user :testde1)
        app  (atf/create-test-app user "To be published")]
    (sql/delete :app_documentation (sql/where {:app_id (:id app)}))
    (apps/make-app-public user de-system-id app)
    (pc/grant-permission (config/permissions-client) "app" (:id app) "user" (:shortUsername user) "own")
    (let [publishable? (:publishable (apps/app-publishable? user de-system-id (:id app)))]
      (is (not (nil? publishable?)))
      (is (not publishable?)))
    (permanently-delete-app user de-system-id (:id app) true)))

(deftest validate-tool-not-public
  (let [user (get-user :testde1)
        app  (atf/create-test-tool-app user "To be published")]
    (is (nil? (v/validate-tool-not-used-in-public-apps atf/test-tool-id)))
    (sql/delete :app_documentation (sql/where {:app_id (:id app)}))
    (apps/make-app-public user de-system-id app)
    (is (thrown-with-msg? ExceptionInfo #"in use by public apps" (v/validate-tool-not-used-in-public-apps atf/test-tool-id)))
    (permanently-delete-app user de-system-id (:id app) true)))

(deftest validate-app-trash
  (let [user (get-user :testde1)
        app (atf/create-test-app user "To be published")]
    (sql/delete :app_documentation (sql/where {:app_id (:id app)}))
    (apps/make-app-public user de-system-id app)
    (is (empty? (find-app app (list-apps user trash-category-id))))
    (apps/admin-delete-app user de-system-id (:id app))
    (is (seq (find-app app (list-apps user trash-category-id))))
    (permanently-delete-app user de-system-id (:id app) true)))
