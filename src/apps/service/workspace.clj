(ns apps.service.workspace
  (:require
   [apps.clients.iplant-groups :as ipg]
   [apps.persistence.workspace :as wp]
   [apps.user :refer [append-username-suffix]]))

(defn- format-workspace
  ([workspace]
   (format-workspace workspace false))
  ([workspace new-workspace?]
   (assoc workspace :new_workspace new-workspace?)))

(defn get-workspace
  [{short-username :shortUsername :keys [username]}]
  (ipg/add-de-user short-username)
  (if-let [workspace (wp/get-workspace username)]
    (format-workspace workspace false)
    (format-workspace (wp/create-workspace username) true)))

(defn list-workspaces
  "Lists workspaces matching the provided parameters."
  [{usernames :username}]
  {:workspaces (map format-workspace (wp/list-workspaces (map append-username-suffix usernames)))})

(defn delete-workspaces
  "Deletes workspaces matching the provided parameters."
  [{usernames :username}]
  (wp/delete-workspaces (map append-username-suffix usernames)))
