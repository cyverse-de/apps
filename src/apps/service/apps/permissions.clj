(ns apps.service.apps.permissions
  (:use [medley.core :only [map-kv]])
  (:require [apps.clients.notifications :as cn]
            [apps.service.apps.util :as apps-util]
            [clojure-commons.error-codes :as ce]))

(defn- load-app-names
  [apps-client requests]
  (->> (mapcat :apps requests)
       (map (fn [req] (select-keys req [:system_id :app_id])))
       set
       (#(.loadAppTables apps-client %))
       (apply merge)
       (map-kv (fn [k v] [k (:name v)]))))

(defn process-app-sharing-requests
  [apps-client app-sharing-requests]
  (let [app-names (load-app-names apps-client app-sharing-requests)]
    (for [{sharee :subject subject-app-sharing-requests :apps} app-sharing-requests]
      {:subject sharee
       :apps    (.shareAppsWithSubject apps-client app-names sharee subject-app-sharing-requests)})))

(defn- share-apps-with-subject
  [apps-client app-names sharee subject-app-sharing-requests]
  (for [{system-id :system_id app-id :app_id level :permission} subject-app-sharing-requests]
    (.shareAppWithSubject apps-client app-names sharee system-id app-id level)))

(defn process-subject-app-sharing-requests
  [apps-client app-names sharee subject-app-sharing-requests]
  (let [responses (share-apps-with-subject apps-client app-names sharee subject-app-sharing-requests)]
    (cn/send-app-sharing-notifications (:shortUsername (.getUser apps-client)) sharee responses)
    (mapv #(dissoc % :sharer_category :sharee_category) responses)))

(defn app-sharing-success
  [app-names system-id app-id level sharer-category sharee-category]
  {:system_id       system-id
   :app_id          (str app-id)
   :app_name        (apps-util/get-app-name app-names system-id app-id)
   :sharer_category sharer-category
   :sharee_category sharee-category
   :permission      level
   :success         true})

(defn app-sharing-failure
  [app-names system-id app-id level sharer-category sharee-category reason]
  {:system_id       system-id
   :app_id          (str app-id)
   :app_name        (apps-util/get-app-name app-names system-id app-id)
   :sharer_category sharer-category
   :sharee_category sharee-category
   :permission      level
   :success         false
   :error           {:error_code ce/ERR_BAD_REQUEST
                     :reason     reason}})

(defn process-app-unsharing-requests
  [apps-client app-unsharing-requests]
  (let [app-names (load-app-names apps-client app-unsharing-requests)]
    (for [{sharee :subject app-ids :apps} app-unsharing-requests]
      {:subject sharee
       :apps    (.unshareAppsWithSubject apps-client app-names sharee app-ids)})))

(defn- unshare-apps-with-subject
  [apps-client app-names sharee subject-app-unsharing-requests]
  (for [{system-id :system_id app-id :app_id} subject-app-unsharing-requests]
    (.unshareAppWithSubject apps-client app-names sharee system-id app-id)))

(defn process-subject-app-unsharing-requests
  [apps-client app-names sharee app-ids]
  (let [responses (unshare-apps-with-subject apps-client app-names sharee app-ids)]
    (cn/send-app-unsharing-notifications (:shortUsername (.getUser apps-client)) sharee responses)
    (mapv #(dissoc % :sharer_category) responses)))

(defn app-unsharing-success
  [app-names system-id app-id sharer-category]
  {:system_id       system-id
   :app_id          (str app-id)
   :app_name        (apps-util/get-app-name app-names system-id app-id)
   :sharer_category sharer-category
   :success         true})

(defn app-unsharing-failure
  [app-names system-id app-id sharer-category reason]
  {:system_id       system-id
   :app_id          (str app-id)
   :app_name        (apps-util/get-app-name app-names system-id app-id)
   :sharer_category sharer-category
   :success         false
   :error           {:error_code ce/ERR_BAD_REQUEST
                     :reason     reason}})
