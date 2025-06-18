(ns apps.persistence.workspace
  (:require [apps.persistence.app-groups :as app-groups]
            [apps.persistence.entities :refer [workspace]]
            [apps.persistence.users :as users]
            [apps.util.config :as config]
            [apps.util.db :refer [transaction]]
            [korma.core :as sql]))

(defn- workspace-base-query
  []
  (-> (sql/select* [:workspace :w])
      (sql/join [:users :u] {:w.user_id :u.id})
      (sql/fields :w.id :w.user_id :w.root_category_id :w.is_public)))

(defn- add-usernames-filter
  [query usernames]
  (when-not (empty? usernames)
    (sql/where query {:u.username [:in usernames]})))

(defn get-workspace
  [username]
  (-> (workspace-base-query)
      (add-usernames-filter [username])
      sql/select
      first))

(defn- create-root-app-category
  [workspace-id]
  (app-groups/create-app-group workspace-id {:name (config/workspace-root-app-category)}))

(defn- create-default-workspace-subcategories
  [workspace-id root-category-id]
  (->> (config/get-default-app-categories)
       (map (comp :id (partial app-groups/create-app-group workspace-id) (partial hash-map :name)))
       (map-indexed (partial app-groups/add-subgroup root-category-id))
       (dorun)))

(defn- set-workspace-root-app-group
  "Sets the given root-app-group ID in the given workspace, and returns a map of
   the workspace with its new group ID."
  [workspace_id root_app_group_id]
  (sql/update workspace
              (sql/set-fields {:root_category_id root_app_group_id})
              (sql/where {:id workspace_id})))

(defn- add-root-app-category
  [{workspace-id :id}]
  (let [{root-category-id :id} (create-root-app-category workspace-id)]
    (create-default-workspace-subcategories workspace-id root-category-id)
    (set-workspace-root-app-group workspace-id root-category-id)))

(defn fetch-workspace-by-user-id
  "Gets the workspace for the given user_id."
  [user_id]
  (first (sql/select workspace (sql/where {:user_id user_id}))))

(defn- create-workspace*
  "Creates a workspace database entry for the given user ID."
  [user_id]
  (sql/insert workspace (sql/values {:user_id user_id})))

(defn create-workspace
  [username]
  (transaction
   (-> (users/get-user-id username)
       (create-workspace*)
       (add-root-app-category)))
  (get-workspace username))

(defn list-workspaces
  "Lists workspaces, optionally filtered by username."
  [usernames]
  (-> (workspace-base-query)
      (add-usernames-filter usernames)
      sql/select))

(defn- user-id-subselect
  [usernames]
  (sql/subselect :users (sql/fields :id) (sql/where {:username [:in usernames]})))

(defn delete-workspaces
  "Deletes the workspaces for selected usernames."
  [usernames]
  (when-not (empty? usernames)
    (sql/delete :workspace (sql/where {:user_id [:in (user-id-subselect usernames)]}))))
