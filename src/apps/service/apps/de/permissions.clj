(ns apps.service.apps.de.permissions
  (:require
   [apps.clients.permissions :as perms-client]
   [apps.constants :refer [de-system-id]]
   [apps.persistence.app-metadata :as amp]
   [apps.service.apps.util :as apps-util]
   [clojure-commons.error-codes :refer [clj-http-error?]]
   [clojure-commons.exception-util :as cxu]
   [clojure.string :as string]
   [slingshot.slingshot :refer [throw+ try+]]))

(defn check-app-permissions
  [user required-level app-ids]
  (let [accessible-app-ids (set (keys (perms-client/load-app-permissions user app-ids required-level)))]
    (when-let [forbidden-apps (seq (remove accessible-app-ids app-ids))]
      (cxu/forbidden (str "insufficient privileges for apps: " (string/join ", " forbidden-apps))))))

(defn- format-app-permissions
  [app-names [app-id app-perms]]
  {:system_id   de-system-id
   :app_id      (str app-id)
   :name        (apps-util/get-app-name app-names de-system-id app-id)
   :permissions app-perms})

(defn- build-app-name-table
  [app-ids]
  (->> (amp/get-app-names app-ids)
       (map (juxt (fn [{:keys [id]}] (apps-util/qualified-app-id de-system-id id)) :name))
       (into {})))

(defn list-app-permissions
  [{user :shortUsername} app-ids params]
  (check-app-permissions user "read" app-ids)
  (map (partial format-app-permissions (build-app-name-table app-ids))
       (perms-client/list-app-permissions user app-ids params)))

(defn has-app-permission
  [user app-id required-level]
  (try+
   (seq (perms-client/load-app-permissions user [app-id] required-level))
   (catch clj-http-error? {:keys [body]}
     (throw+ {:type   ::permission-load-failure
              :reason (perms-client/extract-error-message body)}))))
