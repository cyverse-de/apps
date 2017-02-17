(ns apps.service.apps.test-utils
  (:use [apps.user :only [user-from-attributes]])
  (:require [apps.constants :as c]
            [apps.service.apps :as apps]))

(def fake-system-id "notreal")
(def hpc-system-id c/hpc-system-id)
(def de-system-id c/de-system-id)

(defn create-user [i]
  (let [username (str "testde" i)]
    {:user       username
     :first-name username
     :last-name  username
     :email      (str username "@mail.org")}))

(defn create-user-map []
  (->> (take 10 (iterate inc 1))
       (mapv (comp (juxt (comp keyword :user) identity) create-user))
       (into {})))

(def users (create-user-map))

(defn get-user [k]
  (user-from-attributes (users k)))

(defn app-deletion-request [system-id app-ids]
  (let [app-deletion-request (fn [app-id] {:system_id system-id :app_id app-id})]
    {:app_ids (mapv app-deletion-request app-ids)}))

(defn delete-app [user system-id app-id]
  (apps/delete-apps user (app-deletion-request system-id [app-id])))

(defn permanently-delete-app
  ([user system-id app-id]
   (apps/permanently-delete-apps user (app-deletion-request system-id [app-id])))
  ([user system-id app-id root-deletion-request?]
   (->> (assoc (app-deletion-request system-id [app-id]) :root_deletion_request root-deletion-request?)
        (apps/permanently-delete-apps user))))
