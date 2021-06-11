(ns apps.service.apps-client
  (:require [apps.persistence.oauth :as op]
            [apps.protocols]
            [apps.service.apps.agave]
            [apps.service.apps.combined]
            [apps.service.apps.de]
            [apps.service.oauth :refer [authorization-uri has-access-token]]
            [apps.user :as user]
            [apps.util.config :as config]
            [clojure-commons.exception-util :as cxu]
            [mescal.de :as agave]
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

(defn- get-agave-client
  [state-info username]
  (let [server-info (config/agave-oauth-settings)]
    (agave/de-agave-client-v2
     (config/agave-base-url)
     (config/agave-storage-system)
     (partial get-access-token (config/agave-oauth-settings) state-info username)
     (config/agave-jobs-enabled)
     :timeout  (config/agave-read-timeout)
     :page-len (config/agave-page-length))))

(defn- get-agave-apps-client
  [state-info {:keys [username] :as user}]
  (apps.service.apps.agave.AgaveApps.
   (get-agave-client state-info username)
   (partial has-access-token (config/agave-oauth-settings) username)
   user))

(defn- get-apps-client-list
  [user state-info]
  (vector (apps.service.apps.de.DeApps. user)
          (when (and user (config/agave-enabled))
            (get-agave-apps-client state-info user))))

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
