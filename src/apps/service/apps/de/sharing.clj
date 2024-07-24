(ns apps.service.apps.de.sharing
  (:require [apps.clients.permissions :as perms-client]
            [apps.persistence.app-metadata :as amp]
            [apps.persistence.app-listing :as app-listing]
            [apps.service.apps.de.listings :as listings]
            [apps.service.apps.de.permissions :as perms]
            [apps.tools.permissions :as tool-perms]
            [apps.tools.sharing :as tool-sharing]
            [apps.util.string :refer [render]]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+]]))

(def app-sharing-formats
  {:not-found    "app ID {{app-id}} does not exist"
   :load-failure "unable to load permissions for {{app-id}}: {{detail}}"
   :not-allowed  "insufficient privileges for app ID {{app-id}}"})

(defn- app-sharing-msg
  ([reason-code app-id]
   (app-sharing-msg reason-code app-id nil))
  ([reason-code app-id detail]
   (render (app-sharing-formats reason-code)
           {:app-id app-id
            :detail (or detail "unexpected error")})))

(defn- share-tool-for-app
  [user sharee {tool-id :id}]
  (when-not (tool-perms/has-tool-permission sharee tool-id "read")
    (tool-sharing/share-tool-with-subject user sharee tool-id "read")))

(defn- share-app-tools
  [user sharee {app-id :id}]
  (try+
   (doseq [tool (amp/get-app-tools app-id)]
     (share-tool-for-app user sharee tool))
   (catch Object _
     (log/error (:throwable &throw-context) "unable to share tools for app" app-id)
     (.getMessage (:throwable &throw-context)))))

(defn- share-app
  [user {app-id :id :as app} sharee level]
  (or (share-app-tools user sharee app)
      (perms-client/share-app app-id sharee level)))

(defn share-app-with-subject
  [admin? {username :shortUsername :as user} sharee app-id level success-fn failure-fn]
  (try+
   (if-let [app (app-listing/get-app-listing app-id)]
     (let [sharer-category (listings/get-category-id-for-app user app-id)
           sharee-category listings/shared-with-me-id]
       (if-not (or admin? (perms/has-app-permission username app-id "own"))
         (failure-fn sharer-category sharee-category (app-sharing-msg :not-allowed app-id))
         (if-let [failure-reason (share-app user app sharee level)]
           (failure-fn sharer-category sharee-category failure-reason)
           (success-fn sharer-category sharee-category))))
     (failure-fn nil nil (app-sharing-msg :not-found app-id)))
   (catch [:type :apps.service.apps.de.permissions/permission-load-failure] {:keys [reason]}
     (failure-fn nil nil (app-sharing-msg :load-failure app-id reason)))))

(defn unshare-app-with-subject
  [admin? {username :shortUsername :as user} sharee app-id success-fn failure-fn]
  (try+
   (if-not (amp/app-exists? app-id)
     (failure-fn nil (app-sharing-msg :not-found app-id))
     (let [sharer-category (listings/get-category-id-for-app user app-id)]
       (if-not (or admin? (perms/has-app-permission username app-id "own"))
         (failure-fn sharer-category (app-sharing-msg :not-allowed app-id))
         (if-let [failure-reason (perms-client/unshare-app app-id sharee)]
           (failure-fn sharer-category failure-reason)
           (success-fn sharer-category)))))
   (catch [:type :apps.service.apps.de.permissions/permission-load-failure] {:keys [reason]}
     (failure-fn nil (app-sharing-msg :load-failure app-id reason)))))
