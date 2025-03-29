(ns apps.persistence.app-groups
  (:require [apps.persistence.entities :as entities]
            [apps.util.db :refer [sql-array transaction]]
            [korma.core :as sql]))

;; Declarations for special symbols used by Korma.
(declare max)

(defn get-app-group-hierarchy
  "Gets the app group hierarchy rooted at the node with the given identifier."
  [root-id {:keys [tapis-enabled app-ids] :or {tapis-enabled "false"}}]
  (if (seq app-ids)
    (sql/select (sql/sqlfn :app_category_hierarchy root-id (Boolean/parseBoolean tapis-enabled) (sql-array "uuid" app-ids)))
    (sql/select (sql/sqlfn :app_category_hierarchy root-id (Boolean/parseBoolean tapis-enabled)))))

(defn get-visible-workspaces
  "Gets the list of workspaces that are visible to the user with the given workspace
   identifier."
  [workspace-id]
  (sql/select entities/workspace (sql/where (or {:is_public true} {:id workspace-id}))))

(defn get-app-category
  "Retrieves an App category by its ID."
  [app_group_id]
  (first (sql/select entities/app_category_listing
                     (sql/fields :id :name :is_public)
                     (sql/where {:id app_group_id}))))

(defn search-app-groups
  "Searches for app category information by name."
  [names]
  (-> (sql/select* [:app_categories :c])
      (sql/join [:workspace :w] {:c.workspace_id :w.id})
      (sql/join [:users :u] {:w.user_id :u.id})
      (sql/fields :c.id :c.name [(sql/sqlfn :regexp_replace :u.username "@.*" "") :owner])
      (sql/where {:c.name [:in names]})
      (sql/select)))

(defn get-app-subcategory-id
  "Gets the ID of the child category at the given index under the parent category with the given ID."
  [parent-category-id child-index]
  ((comp :id first)
   (sql/select
    :app_category_group
    (sql/fields [:child_category_id :id])
    (sql/where {:parent_category_id parent-category-id
                :child_index child-index}))))

(defn create-app-group
  "Creates a database entry for an app group, with an UUID and the given
   workspace_id and name, and returns a map of the group with its id."
  [workspace-id category]
  (let [insert-values (-> category
                          (select-keys [:id :name :description])
                          (assoc :workspace_id workspace-id))]
    (sql/insert entities/app_categories (sql/values insert-values))))

(defn add-subgroup
  "Adds a subgroup to a parent group, which should be listed at the given index
   position of the parent's subgroups."
  ([parent-group-id subgroup-id]
   (transaction
    (let [index (:index (first (sql/select :app_category_group
                                           (sql/aggregate (max :child_index) :index)
                                           (sql/where {:parent_category_id parent-group-id}))))]
      (add-subgroup parent-group-id (if (not (nil? index)) (inc index) 0) subgroup-id))))
  ([parent-group-id index subgroup-id]
   (sql/insert :app_category_group
               (sql/values {:parent_category_id parent-group-id
                            :child_category_id subgroup-id
                            :child_index index}))))

(defn delete-app-category
  "Deletes an App Category and all of its subcategories from the database. Delete will cascade to
  the app_category_group table and app categorizations in app_category_app table."
  [category-id]
  (sql/delete entities/app_categories
              (sql/where {:id [:in (sql/subselect (sql/sqlfn :app_category_hierarchy_ids category-id))]})))

(defn update-app-category
  "Updates an app category's name in the database."
  [category-id name]
  (sql/update entities/app_categories (sql/set-fields {:name name}) (sql/where {:id category-id})))

(defn decategorize-category
  "Removes a subcategory from all parent categories in the database."
  [category-id]
  (sql/delete :app_category_group (sql/where {:child_category_id category-id})))

(defn decategorize-app
  "Removes an app from all categories in the database."
  [app-id]
  (sql/delete :app_category_app (sql/where {:app_id app-id})))

(defn category-contains-subcategory?
  "Checks if the app category with the given ID contains a subcategory with the given name."
  [category-id name]
  (seq (sql/select entities/app_categories
                   (sql/join [:app_category_group :acg]
                             {:acg.child_category_id :id})
                   (sql/where {:acg.parent_category_id category-id
                               :name name}))))

(defn category-ancestor-of-subcategory?
  "Checks if the app category ID is a parent or ancestor of the subcategory ID in the
   app_category_group table."
  [category-id subcategory-id]
  (seq (sql/select :app_category_group
                   (sql/where {:child_category_id [:in (sql/subselect (sql/sqlfn :app_category_hierarchy_ids category-id))]})
                   (sql/where {:child_category_id subcategory-id}))))

(defn category-contains-apps?
  "Checks if the app category with the given ID directly contains any apps."
  [category-id]
  (seq (sql/select :app_category_app (sql/where {:app_category_id category-id}))))

(defn category-hierarchy-contains-apps?
  "Checks if the app category with the given ID or any of its subcategories contain any apps."
  [category-id]
  (seq (sql/select :app_category_app
                   (sql/where {:app_category_id
                               [:in (sql/subselect (sql/sqlfn :app_category_hierarchy_ids category-id))]}))))

(defn app-in-category?
  "Determines whether or not an app is in an app category."
  [app-id category-id]
  (seq (first (sql/select :app_category_app
                          (sql/where {:app_category_id category-id
                                      :app_id          app-id})))))

(defn add-app-to-category
  "Adds an app to an app category."
  [app-id category-id]
  (when-not (app-in-category? app-id category-id)
    (sql/insert :app_category_app
                (sql/values {:app_category_id category-id
                             :app_id          app-id}))))

(defn remove-app-from-category
  "Removes an app from an app category."
  [app-id category-id]
  (sql/delete :app_category_app
              (sql/where {:app_category_id category-id
                          :app_id          app-id})))

(defn get-groups-for-app
  "Retrieves a listing of all groups the app with the given ID is listed under."
  [app-id]
  (sql/select entities/app_category_listing
              (sql/fields :id
                          :name)
              (sql/join :app_category_app
                        (= :app_category_app.app_category_id
                           :app_category_listing.id))
              (sql/where {:app_category_app.app_id app-id
                          :is_public true})))

(defn get-suggested-groups-for-app
  "Retrieves a listing of all groups the integrator recommneds for the app."
  [app-id]
  (sql/select :suggested_groups
              (sql/fields :app_categories.id
                          :app_categories.name)
              (sql/join :app_categories
                        (= :app_categories.id
                           :suggested_groups.app_category_id))
              (sql/where {:suggested_groups.app_id app-id})))
