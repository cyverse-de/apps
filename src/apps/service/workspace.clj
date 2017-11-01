(ns apps.service.workspace
  (:use [korma.db :only [transaction]])
  (:require [apps.clients.iplant-groups :as ipg]
            [apps.persistence.workspace :as wp]))

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
  {:workspaces (map format-workspace (wp/list-workspaces usernames))})
