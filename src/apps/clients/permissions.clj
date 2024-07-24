(ns apps.clients.permissions
  (:require [apps.clients.iplant-groups :as ipg]
            [apps.util.config :as config]
            [apps.util.service :as service]
            [apps.util.string :refer [render]]
            [clojure.tools.logging :as log]
            [clojure-commons.error-codes :refer [clj-http-error?]]
            [kameleon.uuids :refer [uuidify]]
            [permissions-client.core :as pc]
            [slingshot.slingshot :refer [try+]]))

(defn- client []
  (config/permissions-client))

(defn- rt-app []
  (config/app-resource-type))

(defn- rt-analysis []
  (config/analysis-resource-type))

(defn- rt-tool []
  (config/tool-resource-type))

(defn- get-failure-reason
  "Extracts the failure reason from an error response body."
  [body]
  ((some-fn :message :reason) (service/parse-json body)))

(defn- group-permissions
  "Groups permissions by resource ID. The resource ID must be a UUID."
  [perms]
  (into {} (map (juxt (comp uuidify :name :resource) :permission_level) perms)))

(defn- group-abbreviated-permissions
  "Groups abbreviated permissions by resource ID. The resource ID must be a UUID."
  [perms]
  (into {} (map (juxt (comp uuidify :resource_name) :permission_level) perms)))

(defn extract-error-message
  [body]
  ((some-fn :reason :message) (service/parse-json body)))

(defn- register-private-resource
  [resource-type user resource-id]
  (pc/grant-permission (client) resource-type resource-id "user" user "own"))

(def register-private-app (partial register-private-resource (rt-app)))
(def register-private-analysis (partial register-private-resource (rt-analysis)))
(def register-private-tool (partial register-private-resource (rt-tool)))

(defn- filter-abbreviated-perms-response
  [response filter-fn]
  (group-abbreviated-permissions (filter filter-fn (:permissions response))))

(defn- load-resource-permissions*
  ([resource-type user filter-fn]
   (filter-abbreviated-perms-response
    (pc/get-abbreviated-subject-permissions-for-resource-type (client) "user" user resource-type true)
    filter-fn))
  ([resource-type user min-level filter-fn]
   (filter-abbreviated-perms-response
    (pc/get-abbreviated-subject-permissions-for-resource-type (client) "user" user resource-type true min-level)
    filter-fn)))

(defn- resource-id-filter
  [resource-ids]
  (comp (set resource-ids) uuidify :resource_name))

(defn- load-resource-permissions
  ([resource-type user]
   (load-resource-permissions* resource-type user (constantly true)))
  ([resource-type user resource-ids]
   (if (= (count resource-ids) 1)
     ((comp group-permissions :permissions)
      (pc/get-subject-permissions-for-resource (client) "user" user resource-type (first resource-ids) true))
     (load-resource-permissions* resource-type user (resource-id-filter resource-ids))))
  ([resource-type user resource-ids min-level]
   (if (= (count resource-ids) 1)
     ((comp group-permissions :permissions)
      (pc/get-subject-permissions-for-resource (client) "user" user resource-type (first resource-ids) true min-level))
     (load-resource-permissions* resource-type user min-level (resource-id-filter resource-ids)))))

(def load-app-permissions (partial load-resource-permissions (rt-app)))
(def load-analysis-permissions (partial load-resource-permissions (rt-analysis)))
(def load-tool-permissions (partial load-resource-permissions (rt-tool)))

(defn- get-perm-filter-fn [user full-listing?]
  (if full-listing?
    identity
    (partial remove (comp (partial = user) :subject_id first))))

(defn- format-perms-listing
  [user perms & [{:keys [full-listing] :or {full-listing false}}]]
  (let [filter-perms (get-perm-filter-fn user full-listing)]
    (->> (map (juxt :subject :permission_level) (:permissions perms))
         filter-perms
         (map (fn [[{id :subject_id source-id :subject_source_id} level]]
                {:subject {:id id :source_id source-id} :permission level})))))

(defn- list-resource-permissions
  [resource-type user resource-ids & [params]]
  (into {}
        (for [resource-id resource-ids]
          [(uuidify resource-id)
           (format-perms-listing user (pc/list-resource-permissions (client) resource-type resource-id) params)])))

(def list-app-permissions (partial list-resource-permissions (rt-app)))
(def list-analysis-permissions (partial list-resource-permissions (rt-analysis)))
(def list-tool-permissions (partial list-resource-permissions (rt-tool)))

(defn delete-app-resource
  [app-id]
  (pc/delete-resource (client) app-id (rt-app)))

(defn delete-tool-resource
  [tool-id]
  (pc/delete-resource (client) tool-id (rt-tool)))

(defn- revoke-app-user-permission
  "Revokes a user's permission to access an app, ignoring cases where the user didn't already have access."
  [user app-id]
  (try+
   (pc/revoke-permission (client) (rt-app) app-id "user" user)
   (catch [:status 404] _)))

(defn make-app-public
  [user app-id]
  (revoke-app-user-permission user app-id)
  (pc/grant-permission (client) (rt-app) app-id "group" (ipg/grouper-user-group-id) "read"))

(defn register-public-tool
  [tool-id]
  (pc/grant-permission (client) (rt-tool) tool-id "group" (ipg/grouper-user-group-id) "read"))

(defn make-tool-public
  [tool-id]
  (try+
   (delete-tool-resource tool-id)
   (catch [:status 404] _))
  (register-public-tool tool-id))

(defn- resource-sharing-log-msg
  [action resource-type resource-name subject-type subject-id reason]
  (render "unable to {{action}} {{resource-type}}:{{resource-name}} with {{subject-type}}:{{subject-id}}: {{reason}}"
          {:action        action
           :resource-type resource-type
           :resource-name resource-name
           :subject-type  subject-type
           :subject-id    subject-id
           :reason        reason}))

(defn- share-resource
  ([resource-type resource-name {subject-source-id :source_id subject-id :id} level]
   (share-resource resource-type resource-name (ipg/get-subject-type subject-source-id) subject-id level))
  ([resource-type resource-name subject-type subject-id level]
   (try+
    (pc/grant-permission (client) resource-type resource-name subject-type subject-id level)
    nil
    (catch clj-http-error? {:keys [body]}
      (let [reason (get-failure-reason body)]
        (log/error (resource-sharing-log-msg "share" resource-type resource-name subject-type subject-id reason)))
      (str "the " resource-type " sharing request failed")))))

(defn- unshare-resource
  ([resource-type resource-name {subject-source-id :source_id subject-id :id}]
   (unshare-resource resource-type resource-name (ipg/get-subject-type subject-source-id) subject-id))
  ([resource-type resource-name subject-type subject-id]
   (try+
    (pc/revoke-permission (client) resource-type resource-name subject-type subject-id)
    nil
    (catch clj-http-error? {:keys [body]}
      (let [reason (get-failure-reason body)]
        (log/error (resource-sharing-log-msg "unshare" resource-type resource-name subject-type subject-id reason)))
      (str "the " resource-type " unsharing request failed")))))

(def share-app (partial share-resource (rt-app)))
(def unshare-app (partial unshare-resource (rt-app)))
(def share-analysis (partial share-resource (rt-analysis)))
(def unshare-analysis (partial unshare-resource (rt-analysis)))
(def share-tool (partial share-resource (rt-tool)))
(def unshare-tool (partial unshare-resource (rt-tool)))

(defn- get-public-resource-ids [resource-type]
  (->> (pc/get-abbreviated-subject-permissions-for-resource-type
        (client) "group" (ipg/grouper-user-group-id) resource-type false)
       :permissions
       (map (comp uuidify :resource_name))
       set))

(def get-public-app-ids (partial get-public-resource-ids "app"))
(def get-public-tool-ids (partial get-public-resource-ids "tool"))
