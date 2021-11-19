(ns apps.persistence.app-groups
  (:use [apps.persistence.entities]
        [apps.util.db :only [sql-array transaction]]
        [korma.core :exclude [update]]
        [slingshot.slingshot :only [throw+]])
  (:require [kameleon.uuids :refer [uuid]]
            [korma.core :as sql]))

(defn get-app-group-hierarchy
  "Gets the app group hierarchy rooted at the node with the given identifier."
  [root-id {:keys [agave-enabled app-ids] :or {agave-enabled "false"}}]
  (if (seq app-ids)
    (select (sqlfn :app_category_hierarchy root-id (Boolean/parseBoolean agave-enabled) (sql-array "uuid" app-ids)))
    (select (sqlfn :app_category_hierarchy root-id (Boolean/parseBoolean agave-enabled)))))

(defn get-visible-workspaces
  "Gets the list of workspaces that are visible to the user with the given workspace
   identifier."
  [workspace-id]
  (select workspace (where (or {:is_public true} {:id workspace-id}))))

(defn get-app-category
  "Retrieves an App category by its ID."
  [app_group_id]
  (first (select app_category_listing
                 (fields :id :name :is_public)
                 (where {:id app_group_id}))))

(defn search-app-groups
  "Searches for app category information by name."
  [names]
  (-> (select* [:app_categories :c])
      (join [:workspace :w] {:c.workspace_id :w.id})
      (join [:users :u] {:w.user_id :u.id})
      (fields :c.id :c.name [(sqlfn regexp_replace :u.username "@.*" "") :owner])
      (where {:c.name [in names]})
      (select)))

(defn get-app-subcategory-id
  "Gets the ID of the child category at the given index under the parent category with the given ID."
  [parent-category-id child-index]
  ((comp :id first)
   (select
    :app_category_group
    (fields [:child_category_id :id])
    (where {:parent_category_id parent-category-id
            :child_index child-index}))))

(defn create-app-group
  "Creates a database entry for an app group, with an UUID and the given
   workspace_id and name, and returns a map of the group with its id."
  [workspace-id category]
  (let [insert-values (-> category
                          (select-keys [:id :name :description])
                          (assoc :workspace_id workspace-id))]
    (insert app_categories (values insert-values))))

(defn add-subgroup
  "Adds a subgroup to a parent group, which should be listed at the given index
   position of the parent's subgroups."
  ([parent-group-id subgroup-id]
   (transaction
    (let [index (:index (first (select :app_category_group
                                       (aggregate (max :child_index) :index)
                                       (where {:parent_category_id parent-group-id}))))]
      (add-subgroup parent-group-id (if (not (nil? index)) (inc index) 0) subgroup-id))))
  ([parent-group-id index subgroup-id]
   (insert :app_category_group
           (values {:parent_category_id parent-group-id
                    :child_category_id subgroup-id
                    :child_index index}))))

(defn delete-app-category
  "Deletes an App Category and all of its subcategories from the database. Delete will cascade to
  the app_category_group table and app categorizations in app_category_app table."
  [category-id]
  (delete app_categories
          (where {:id [in (subselect (sqlfn :app_category_hierarchy_ids category-id))]})))

(defn update-app-category
  "Updates an app category's name in the database."
  [category-id name]
  (sql/update app_categories (set-fields {:name name}) (where {:id category-id})))

(defn decategorize-category
  "Removes a subcategory from all parent categories in the database."
  [category-id]
  (delete :app_category_group (where {:child_category_id category-id})))

(defn decategorize-app
  "Removes an app from all categories in the database."
  [app-id]
  (delete :app_category_app (where {:app_id app-id})))

(defn get-app-by-id
  "Searches for an existing app by id."
  [id]
  (first (select apps
                 (where {:id id}))))

(defn category-contains-subcategory?
  "Checks if the app category with the given ID contains a subcategory with the given name."
  [category-id name]
  (seq (select app_categories
               (join [:app_category_group :acg]
                     {:acg.child_category_id :id})
               (where {:acg.parent_category_id category-id
                       :name name}))))

(defn category-ancestor-of-subcategory?
  "Checks if the app category ID is a parent or ancestor of the subcategory ID in the
   app_category_group table."
  [category-id subcategory-id]
  (seq (select :app_category_group
               (where {:child_category_id [in (subselect (sqlfn :app_category_hierarchy_ids category-id))]})
               (where {:child_category_id subcategory-id}))))

(defn category-contains-apps?
  "Checks if the app category with the given ID directly contains any apps."
  [category-id]
  (seq (select :app_category_app (where {:app_category_id category-id}))))

(defn category-hierarchy-contains-apps?
  "Checks if the app category with the given ID or any of its subcategories contain any apps."
  [category-id]
  (seq (select :app_category_app
               (where {:app_category_id
                       [in (subselect (sqlfn :app_category_hierarchy_ids category-id))]}))))

(defn app-in-category?
  "Determines whether or not an app is in an app category."
  [app-id category-id]
  (not (empty? (first (select :app_category_app
                              (where {:app_category_id category-id
                                      :app_id          app-id}))))))

(defn add-app-to-category
  "Adds an app to an app category."
  [app-id category-id]
  (when-not (app-in-category? app-id category-id)
    (insert :app_category_app
            (values {:app_category_id category-id
                     :app_id          app-id}))))

(defn remove-app-from-category
  "Removes an app from an app category."
  [app-id category-id]
  (delete :app_category_app
          (where {:app_category_id category-id
                  :app_id          app-id})))

(defn get-groups-for-app
  "Retrieves a listing of all groups the app with the given ID is listed under."
  [app-id]
  (select app_category_listing
          (fields :id
                  :name)
          (join :app_category_app
                (= :app_category_app.app_category_id
                   :app_category_listing.id))
          (where {:app_category_app.app_id app-id
                  :is_public true})))

(defn get-suggested-groups-for-app
  "Retrieves a listing of all groups the integrator recommneds for the app."
  [app-id]
  (select :suggested_groups
          (fields :app_categories.id
                  :app_categories.name)
          (join :app_categories
                (= :app_categories.id
                   :suggested_groups.app_category_id))
          (where {:suggested_groups.app_id app-id})))
