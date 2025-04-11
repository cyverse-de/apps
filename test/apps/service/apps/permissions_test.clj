(ns apps.service.apps.permissions-test
  (:require
   [apps.clients.iplant-groups :as ipg]
   [apps.clients.permissions :as perms-client]
   [apps.constants :refer [de-system-id]]
   [apps.service.apps :as apps]
   [apps.service.apps.de.listings :refer [shared-with-me-id]]
   [apps.service.apps.test-fixtures :as atf]
   [apps.service.apps.test-utils :refer [delete-app get-user permanently-delete-app]]
   [apps.test-fixtures :as tf]
   [apps.util.config :as config]
   [clojure.test :refer [deftest is use-fixtures]]
   [kameleon.uuids :refer [uuid uuidify]]
   [korma.core :as sql]
   [permissions-client.core :as pc])
  (:import
   (clojure.lang ExceptionInfo)))

(use-fixtures :once tf/run-integration-tests tf/with-test-db tf/with-config atf/with-workspaces)
(use-fixtures :each atf/with-public-apps atf/with-test-app)

(defn list-app-permissions [user & app-ids]
  (apps/list-app-permissions user (mapv #(hash-map :system_id de-system-id :app_id %) app-ids) {}))

(deftest test-app-search
  (let [{username :shortUsername :as user} (get-user :testde1)]
    (is (= 1 (:total (apps/search-apps user {:search (:name atf/test-app)}))))
    (is (= 1 (count (:apps (apps/search-apps user {:search (:name atf/test-app)})))))
    (perms-client/unshare-app (:id atf/test-app) "user" username)
    (is (= 0 (:total (apps/search-apps user {:search (:name atf/test-app)}))))
    (is (= 0 (count (:apps (apps/search-apps user {:search (:name atf/test-app)})))))
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" username "own")
    (is (= 1 (:total (apps/search-apps user {:search (:name atf/test-app)}))))
    (is (= 1 (count (:apps (apps/search-apps user {:search (:name atf/test-app)})))))))

(deftest test-app-category-listing-counts
  (let [{username :shortUsername :as user} (get-user :testde1)
        dev-category-id                    (:id (atf/get-dev-category user))
        beta-category-id                   (:id (atf/get-beta-category user))
        group-id                           (ipg/grouper-user-group-id)]
    (is (= 1 (:total (apps/list-apps-in-category user de-system-id dev-category-id {}))))
    (is (= (count atf/beta-apps) (:total (apps/list-apps-in-category user de-system-id beta-category-id {}))))
    (perms-client/unshare-app (:id atf/test-app) "user" username)
    (is (= 0 (:total (apps/list-apps-in-category user de-system-id dev-category-id {}))))
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" username "own")
    (is (= 1 (:total (apps/list-apps-in-category user de-system-id dev-category-id {}))))
    (is (= (count atf/beta-apps) (:total (apps/list-apps-in-category user de-system-id beta-category-id {}))))
    (pc/revoke-permission (config/permissions-client) "app" (:id (first atf/beta-apps)) "group" group-id)
    (is (= 1 (:total (apps/list-apps-in-category user de-system-id dev-category-id {}))))
    (is (= (dec (count atf/beta-apps)) (:total (apps/list-apps-in-category user de-system-id beta-category-id {}))))))

(deftest test-app-hierarchy-counts
  (let [{username :shortUsername :as user} (get-user :testde1)
        group-id                           (ipg/grouper-user-group-id)]
    (is (= 1 (:total (atf/get-dev-category user))))
    (is (= (count atf/beta-apps) (:total (atf/get-beta-category user))))
    (perms-client/unshare-app (:id atf/test-app) "user" username)
    (is (= 0 (:total (atf/get-dev-category user))))
    (is (= (count atf/beta-apps) (:total (atf/get-beta-category user))))
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" username "own")
    (is (= 1 (:total (atf/get-dev-category user))))
    (is (= (count atf/beta-apps) (:total (atf/get-beta-category user))))
    (pc/revoke-permission (config/permissions-client) "app" (:id (first atf/beta-apps)) "group" group-id)
    (is (= 1 (:total (atf/get-dev-category user))))
    (is (= (dec (count atf/beta-apps)) (:total (atf/get-beta-category user))))))

;; FIXME the Beta category is obsolete
(deftest test-admin-app-hierarchy-counts
  (let [user     (get-user :testde1)
        group-id (ipg/grouper-user-group-id)]
    (is (= (count atf/beta-apps) (:total (atf/get-admin-beta-category user))))
    (pc/revoke-permission (config/permissions-client) "app" (:id (first atf/beta-apps)) "group" group-id)
    (is (= (dec (count atf/beta-apps)) (:total (atf/get-admin-beta-category user))))))

(defn find-app [listing app-id]
  (first (filter (comp (partial = app-id) :id) (:apps listing))))

;; FIXME the Beta category is obsolete
(deftest test-app-category-listing
  (let [user             (get-user :testde1)
        beta-category-id (:id (atf/get-beta-category user))
        group-id         (ipg/grouper-user-group-id)
        app-id           (:id (first atf/beta-apps))]
    (is (find-app (apps/list-apps-in-category user de-system-id beta-category-id {}) app-id))
    (pc/revoke-permission (config/permissions-client) "app" (:id (first atf/beta-apps)) "group" group-id)
    (is (nil? (find-app (apps/list-apps-in-category user de-system-id beta-category-id {}) app-id)))))

(deftest check-initial-ownership-permission
  (let [user            (get-user :testde1)
        dev-category-id (:id (atf/get-dev-category user))]
    (is (= "own" (:permission (first (:apps (apps/list-apps-in-category user de-system-id dev-category-id {}))))))))

(defn check-delete-apps
  ([user]
   (check-delete-apps user atf/test-app))
  ([user app]
   (delete-app user de-system-id (:id app))
   true))

(defn check-delete-app
  ([user]
   (check-delete-app user atf/test-app))
  ([user app]
   (apps/delete-app user de-system-id (:id app))
   true))

(defn check-relabel-app [user]
  (apps/relabel-app user de-system-id atf/test-app)
  true)

(defn check-update-app [user]
  (apps/update-app user de-system-id atf/test-app)
  true)

(defn check-app-ui [user]
  (apps/get-app-ui user de-system-id (:id atf/test-app))
  true)

(defn check-copy-app [user]
  (let [app-id (:id (apps/copy-app user de-system-id (:id atf/test-app)))]
    (permanently-delete-app user de-system-id app-id))
  true)

(defn check-edit-app-docs [user]
  (apps/owner-edit-app-docs user de-system-id (:id atf/test-app) {:documentation ""})
  true)

(defn check-get-app-details [user]
  (apps/get-app-details user de-system-id (:id atf/test-app))
  true)

(defn check-get-app-docs [user]
  (apps/get-app-docs user de-system-id (:id atf/test-app))
  true)

(defn check-favorite [user]
  (apps/add-app-favorite user de-system-id (:id atf/test-app))
  true)

(defn check-remove-favorite [user]
  (apps/remove-app-favorite user de-system-id (:id atf/test-app))
  true)

(defn check-publishable [user]
  (apps/app-publishable? user de-system-id (:id atf/test-app))
  true)

(defn check-rating [user]
  (apps/rate-app user de-system-id (:id atf/test-app) {:rating 5 :comment_id 27})
  true)

(defn check-unrating [user]
  (apps/delete-app-rating user de-system-id (:id atf/test-app))
  true)

(defn check-tasks [user]
  (apps/get-app-task-listing user de-system-id (:id atf/test-app))
  true)

(defn check-tools [user]
  (apps/get-app-tool-listing user de-system-id (:id atf/test-app))
  true)

(deftest test-permission-restrictions-none
  (let [user (get-user :testde2)]
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-delete-apps user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-delete-app user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-relabel-app user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-update-app user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-app-ui user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-copy-app user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-edit-app-docs user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-get-app-details user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-get-app-docs user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-favorite user)))
    (is (check-remove-favorite user))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-publishable user)))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-rating user)))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-unrating user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-tasks user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-tools user)))))

(deftest test-permission-restrictions-read
  (let [{username :shortUsername :as user} (get-user :testde2)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" username "read")
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-delete-apps user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-delete-app user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-relabel-app user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-update-app user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-app-ui user)))
    (is (check-copy-app user))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-edit-app-docs user)))
    (is (check-get-app-details user))
    (is (check-get-app-docs user))
    (is (check-favorite user))
    (is (check-remove-favorite user))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-publishable user)))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-rating user)))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-unrating user)))
    (is (check-tasks user))
    (is (check-tools user))))

(deftest test-permission-restrictions-write
  (let [{username :shortUsername :as user} (get-user :testde2)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" username "write")
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-delete-apps user)))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-delete-app user)))
    (is (check-relabel-app user))
    (is (check-update-app user))
    (is (check-app-ui user))
    (is (check-copy-app user))
    (is (check-edit-app-docs user))
    (is (check-get-app-details user))
    (is (check-get-app-docs user))
    (is (check-favorite user))
    (is (check-remove-favorite user))
    (is (thrown-with-msg? ExceptionInfo #"privileges" (check-publishable user)))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-rating user)))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-unrating user)))
    (is (check-tasks user))
    (is (check-tools user))))

(deftest test-permission-restrictions-own
  (let [{username :shortUsername :as user} (get-user :testde2)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" username "own")
    (is (check-relabel-app user))
    (is (check-update-app user))
    (is (check-app-ui user))
    (is (check-copy-app user))
    (is (check-edit-app-docs user))
    (is (check-get-app-details user))
    (is (check-get-app-docs user))
    (is (check-favorite user))
    (is (check-remove-favorite user))
    (is (check-publishable user))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-rating user)))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-unrating user)))
    (is (check-tasks user))
    (is (check-tools user))
    (let [app (atf/create-test-app user "Shreddable")]
      (is (check-delete-apps user app))
      (permanently-delete-app user de-system-id (:id app)))
    (let [app (atf/create-test-app user "Deletable")]
      (is (check-delete-apps user app))
      (permanently-delete-app user de-system-id (:id app)))))

(deftest test-public-app-ratings
  (let [user (get-user :testde1)]
    (sql/delete :app_documentation (sql/where {:app_id (:id atf/test-app)}))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-rating user)))
    (is (thrown-with-msg? ExceptionInfo #"private app" (check-unrating user)))
    (apps/make-app-public user de-system-id atf/test-app)
    (is (check-rating user))
    (is (check-unrating user))))

(defn has-permission? [rt rn st sn level]
  (let [client (config/permissions-client)]
    (seq (:permissions (pc/get-subject-permissions-for-resource client st sn rt rn false level)))))

(deftest test-public-app-permissions
  (let [{username :shortUsername :as user} (get-user :testde1)]
    (sql/delete :app_documentation (sql/where {:app_id (:id atf/test-app)}))
    (is (has-permission? "app" (:id atf/test-app) "user" username "own"))
    (is (not (has-permission? "app" (:id atf/test-app) "group" (ipg/grouper-user-group-id) "read")))
    (apps/make-app-public user de-system-id atf/test-app)
    (is (not (has-permission? "app" (:id atf/test-app) "user" username "own")))
    (is (has-permission? "app" (:id atf/test-app) "group" (ipg/grouper-user-group-id) "read"))))

(defn share-app [sharer sharee app-id level]
  (apps/share-apps sharer false [{:user (:shortUsername sharee)
                                  :apps [{:system_id  de-system-id
                                          :app_id     app-id
                                          :permission level}]}]))

(deftest test-sharing
  (let [testde1                                       (get-user :testde1)
        {testde2-username :shortUsername :as testde2} (get-user :testde2)
        {[user-response :as responses] :sharing}      (share-app testde1 testde2 (:id atf/test-app) "own")]
    (is (= 1 (count responses)))
    (is (= testde2-username (:user user-response)))
    (is (= 1 (count (:apps user-response))))
    (is (= (:id atf/test-app) (uuidify (-> user-response :apps first :app_id))))
    (is (= (:name atf/test-app) (-> user-response :apps first :app_name)))
    (is (= "own" (-> user-response :apps first :permission)))
    (is (true? (-> user-response :apps first :success)))
    (is (has-permission? "app" (:id atf/test-app) "user" testde2-username "own"))))

(deftest test-sharing-no-privs
  (let [{testde2-username :shortUsername :as testde2} (get-user :testde2)
        {testde3-username :shortUsername :as testde3} (get-user :testde3)
        {[user-response :as responses] :sharing}      (share-app testde2 testde3 (:id atf/test-app) "own")]
    (is (= 1 (count responses)))
    (is (= testde3-username (:user user-response)))
    (is (= 1 (count (:apps user-response))))
    (is (= (:id atf/test-app) (uuidify (-> user-response :apps first :app_id))))
    (is (= (:name atf/test-app) (-> user-response :apps first :app_name)))
    (is (= "own" (-> user-response :apps first :permission)))
    (is (false? (-> user-response :apps first :success)))
    (is (re-find #"insufficient privileges" (-> user-response :apps first :error :reason)))
    (is (not (has-permission? "app" (:id atf/test-app) "user" testde2-username "own")))))

(deftest test-sharing-write-privs
  (let [{testde2-username :shortUsername :as testde2} (get-user :testde2)
        {testde3-username :shortUsername :as testde3} (get-user :testde3)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" testde2-username "write")
    (let [{[user-response :as responses] :sharing} (share-app testde2 testde3 (:id atf/test-app) "own")]
      (is (= 1 (count responses)))
      (is (= testde3-username (:user user-response)))
      (is (= 1 (count (:apps user-response))))
      (is (= (:id atf/test-app) (uuidify (-> user-response :apps first :app_id))))
      (is (= (:name atf/test-app) (-> user-response :apps first :app_name)))
      (is (= "own" (-> user-response :apps first :permission)))
      (is (false? (-> user-response :apps first :success)))
      (is (re-find #"insufficient privileges" (-> user-response :apps first :error :reason)))
      (is (not (has-permission? "app" (:id atf/test-app) "user" testde2-username "own"))))))

(deftest test-sharing-non-existent-app
  (let [testde1                                       (get-user :testde1)
        {testde2-username :shortUsername :as testde2} (get-user :testde2)
        {[user-response :as responses] :sharing}      (share-app testde1 testde2 (uuid) "own")]
    (is (= 1 (count responses)))
    (is (= testde2-username (:user user-response)))
    (is (= 1 (count (:apps user-response))))
    (is (= "own" (-> user-response :apps first :permission)))
    (is (false? (-> user-response :apps first :success)))
    (is (re-find #"does not exist" (-> user-response :apps first :error :reason)))))

(deftest test-permission-listings
  (let [testde1                           (get-user :testde1)
        {testde2-username :shortUsername} (get-user :testde2)]
    (is (empty? (-> (list-app-permissions testde1 (:id atf/test-app)) :apps first :permissions)))
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" testde2-username "write")
    (let [perms (-> (list-app-permissions testde1 (:id atf/test-app)) :apps first :permissions)]
      (is (= 1 (count perms)))
      (is (= testde2-username (-> perms first :user)))
      (is (= "write" (-> perms first :permission))))
    (pc/revoke-permission (config/permissions-client) "app" (:id atf/test-app) "user" testde2-username)))

(deftest test-permission-listings-no-privs
  (let [user (get-user :testde2)]
    (is (thrown-with-msg? ExceptionInfo #"insufficient privileges" (list-app-permissions user (:id atf/test-app))))))

(deftest test-permission-listings-read-privs
  (let [{testde1-username :shortUsername}             (get-user :testde1)
        {testde2-username :shortUsername :as testde2} (get-user :testde2)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" testde2-username "read")
    (let [perms (-> (list-app-permissions testde2 (:id atf/test-app)) :apps first :permissions)]
      (is (= 1 (count perms)))
      (is (= testde1-username (-> perms first :user)))
      (is (= "own" (-> perms first :permission))))))

(deftest test-unsharing
  (let [testde1                           (get-user :testde1)
        {testde2-username :shortUsername} (get-user :testde2)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" testde2-username "read")
    (let [requests  [{:user testde2-username :apps [{:system_id de-system-id :app_id (:id atf/test-app)}]}]
          responses (:unsharing (apps/unshare-apps testde1 false requests))
          user-resp (first responses)
          app-resp  (first (:apps user-resp))]
      (is (= 1 (count responses)))
      (is (= testde2-username (:user user-resp)))
      (is (= 1 (count (:apps user-resp))))
      (is (= (:id atf/test-app) (uuidify (:app_id app-resp))))
      (is (true? (:success app-resp))))))

(deftest test-unsharing-read-privs
  (let [{testde2-username :shortUsername :as testde2} (get-user :testde2)
        {testde3-username :shortUsername}             (get-user :testde3)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" testde2-username "read")
    (let [requests  [{:user testde3-username :apps [{:system_id de-system-id :app_id (:id atf/test-app)}]}]
          responses (:unsharing (apps/unshare-apps testde2 false requests))
          user-resp (first responses)
          app-resp  (first (:apps user-resp))]
      (is (= 1 (count responses)))
      (is (= testde3-username (:user user-resp)))
      (is (= 1 (count (:apps user-resp))))
      (is (= (:id atf/test-app) (uuidify (:app_id app-resp))))
      (is (false? (:success app-resp)))
      (is (re-find #"insufficient privileges" (-> app-resp :error :reason))))))

(deftest test-unsharing-write-privs
  (let [{testde2-username :shortUsername :as testde2} (get-user :testde2)
        {testde3-username :shortUsername}             (get-user :testde3)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" testde2-username "write")
    (let [requests  [{:user testde3-username :apps [{:system_id de-system-id :app_id (:id atf/test-app)}]}]
          responses (:unsharing (apps/unshare-apps testde2 false requests))
          user-resp (first responses)
          app-resp  (first (:apps user-resp))]
      (is (= 1 (count responses)))
      (is (= testde3-username (:user user-resp)))
      (is (= 1 (count (:apps user-resp))))
      (is (= (:id atf/test-app) (uuidify (:app_id app-resp))))
      (is (false? (:success app-resp)))
      (is (re-find #"insufficient privileges" (-> app-resp :error :reason))))))

(deftest test-unsharing-non-existent-app
  (let [testde1                           (get-user :testde1)
        {testde2-username :shortUsername} (get-user :testde2)
        requests  [{:user testde2-username :apps [{:system_id de-system-id :app_id (uuid)}]}]
        responses (:unsharing (apps/unshare-apps testde1 false requests))
        user-resp (first responses)
        app-resp  (first (:apps user-resp))]
    (is (= 1 (count responses)))
    (is (= testde2-username (:user user-resp)))
    (is (= 1 (count (:apps user-resp))))
    (is (false? (:success app-resp)))
    (is (re-find #"does not exist" (-> app-resp :error :reason)))))

(deftest test-deleted-app-resource-removal
  (let [user (get-user :testde1)
        app  (atf/create-test-app user "To be deleted")]
    (is (seq (:resources (pc/list-resources (config/permissions-client) {:resource_name (:id app)}))))
    (permanently-delete-app user de-system-id (:id app))
    (is (empty? (:resources (pc/list-resources (config/permissions-client) {:resource_name (:id app)}))))))

(defn- favorite? [user app-id]
  (let [faves-id (:id (atf/get-category user "Favorite Apps"))]
    (->> (:apps (apps/list-apps-in-category user de-system-id faves-id {}))
         (filter (comp (partial = app-id) :id))
         seq)))

(deftest test-shared-favorites
  (let [{username :shortUsername :as user} (get-user :testde2)]
    (pc/grant-permission (config/permissions-client) "app" (:id atf/test-app) "user" username "read")
    (apps/add-app-favorite user de-system-id (:id atf/test-app))
    (is (favorite? user (:id atf/test-app)))
    (pc/revoke-permission (config/permissions-client) "app" (:id atf/test-app) "user" username)
    (is (not (favorite? user (:id atf/test-app))))))

(deftest test-public-app-labels-update
  (let [user (get-user :testde1)]
    (sql/delete :app_documentation (sql/where {:app_id (:id atf/test-app)}))
    (apps/make-app-public user de-system-id atf/test-app)
    (is (check-edit-app-docs user))))

(deftest test-create-pipeline
  (let [{username :shortUsername :as user} (get-user :testde1)
        pipeline                           (atf/create-pipeline user)]
    (is (has-permission? "app" (:id pipeline) "user" username "own"))
    (permanently-delete-app user de-system-id (:id pipeline))))

(deftest test-shared-listing
  (let [testde1       (get-user :testde1)
        testde2       (get-user :testde2)
        app           (atf/create-test-app testde1 "To be shared")
        shared-apps   (fn [user] (apps/list-apps-in-category user de-system-id shared-with-me-id {}))
        contains-app? (fn [app-id apps] (seq (filter (comp (partial = app-id) :id) apps)))
        old-listing   (shared-apps testde2)]
    (is (not (contains-app? (:id app) (:apps old-listing))))
    (pc/grant-permission (config/permissions-client) "app" (:id app) "user" (:shortUsername testde2) "read")
    (let [new-listing (shared-apps testde2)]
      (is (= (inc (:total old-listing)) (:total new-listing)))
      (is (contains-app? (:id app) (:apps new-listing))))
    (permanently-delete-app testde1 de-system-id (:id app))))
