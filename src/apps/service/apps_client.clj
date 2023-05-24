(ns apps.service.apps-client
  (:require [apps.persistence.oauth :as op]
            [apps.protocols]
            [apps.service.apps.tapis]
            [apps.service.apps.combined]
            [apps.service.apps.de]
            [apps.service.oauth :refer [authorization-uri has-access-token]]
            [apps.user :as user]
            [apps.util.config :as config]
            [mescal.de :as tapis]
            [slingshot.slingshot :refer [throw+]]))

(defn- authorization-redirect
  [server-info username state-info]
  (throw+ {:type     :clojure-commons.exception/temporary-redirect
           :location (authorization-uri server-info username state-info)}))

(defn- get-access-token
  [{:keys [api-name] :as server-info} state-info username]
  (if-let [token-info (op/get-access-token api-name username)]
    (assoc (merge server-info token-info)
           :token-callback  (partial op/store-access-token api-name username)
           :reauth-callback (partial authorization-redirect server-info username state-info))
    (authorization-redirect server-info username state-info)))

(defn- get-tapis-client
  [state-info username]
  (let [server-info (config/tapis-oauth-settings)]
    (tapis/de-tapis-client-v3
      (config/tapis-base-url)
      (config/tapis-storage-system)
      (partial get-access-token server-info state-info username)
      (config/tapis-jobs-enabled)
      :timeout  (config/tapis-read-timeout)
      :page-len (config/tapis-page-length))))

(defn- get-tapis-apps-client
  [state-info {:keys [username] :as user}]
  (apps.service.apps.tapis.TapisApps.
   (get-tapis-client state-info username)
   (partial has-access-token (config/tapis-oauth-settings) username)
   user))

(defn- get-apps-client-list
  [user state-info]
  (vector (apps.service.apps.de.DeApps. user)
          (when (and user (config/tapis-enabled))
            (get-tapis-apps-client state-info user))))

(defn get-apps-client
  ([user]
   (get-apps-client user ""))
  ([user state-info]
   (apps.service.apps.combined.CombinedApps.
    (remove nil? (get-apps-client-list user state-info))
    user)))

(defn get-apps-client-for-username
  ([username]
   (get-apps-client-for-username username ""))
  ([username state-info]
   (get-apps-client (user/load-user-as-user username username) state-info)))
