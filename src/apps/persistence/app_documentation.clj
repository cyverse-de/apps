(ns apps.persistence.app-documentation
  (:require [apps.persistence.entities :refer [app_references]]
            [korma.core :as sql]))

(defn get-app-references
  "Retrieves references for the given app ID."
  [app-version-id]
  (sql/select app_references (sql/where {:app_version_id app-version-id})))

(defn get-documentation
  "Retrieves documentation details for the given app ID."
  [app-version-id]
  (first
   (sql/select :app_documentation
               (sql/join [:users :creators]
                         {:creators.id :created_by})
               (sql/join [:users :editors]
                         {:editors.id :modified_by})
               (sql/join [:app_versions :v]
                         {:v.id :app_version_id})
               (sql/fields :v.app_id
                           [:app_version_id :version_id]
                           [:value :documentation]
                           :created_on
                           :modified_on
                           [:creators.username :created_by]
                           [:editors.username :modified_by])
               (sql/where {:app_version_id app-version-id}))))

(defn add-documentation
  "Inserts an App's documentation into the database."
  [creator-id docs app-version-id]
  (sql/insert :app_documentation
              (sql/values {:app_version_id app-version-id
                           :created_by     creator-id
                           :modified_by    creator-id
                           :created_on     (sql/sqlfn :now)
                           :modified_on    (sql/sqlfn :now)
                           :value          docs})))

(defn edit-documentation
  "Updates the given App's documentation, modified_on timestamp, and modified_by ID in the database."
  [editor-id docs app-version-id]
  (sql/update :app_documentation
              (sql/set-fields {:value       docs
                               :modified_by editor-id
                               :modified_on (sql/sqlfn :now)})
              (sql/where {:app_version_id app-version-id})))
