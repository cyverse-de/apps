(ns apps.persistence.app-documentation
  (:use [apps.persistence.entities :only [app_references]]
        [korma.core :exclude [update]])
  (:require [korma.core :as sql]))

(defn get-app-references
  "Retrieves references for the given app ID."
  [app-version-id]
  (select app_references (where {:app_version_id app-version-id})))

(defn get-documentation
  "Retrieves documentation details for the given app ID."
  [app-version-id]
  (first
   (select :app_documentation
           (join [:users :creators]
                 {:creators.id :created_by})
           (join [:users :editors]
                 {:editors.id :modified_by})
           (fields :app_id
                   [:app_version_id :version_id]
                   [:value :documentation]
                   :created_on
                   :modified_on
                   [:creators.username :created_by]
                   [:editors.username :modified_by])
           (where {:app_version_id app-version-id}))))

(defn add-documentation
  "Inserts an App's documentation into the database."
  [creator-id docs app-id]
  (insert :app_documentation
          (values {:app_id      app-id
                   :created_by  creator-id
                   :modified_by creator-id
                   :created_on  (sqlfn now)
                   :modified_on (sqlfn now)
                   :value       docs})))

(defn edit-documentation
  "Updates the given App's documentation, modified_on timestamp, and modified_by ID in the database."
  [editor-id docs app-id]
  (sql/update :app_documentation
              (set-fields {:value       docs
                           :modified_by editor-id
                           :modified_on (sqlfn now)})
              (where {:app_id app-id})))
