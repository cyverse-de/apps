(ns apps.persistence.categories
  (:require [apps.persistence.users :refer [get-user-id]]
            [korma.core :as sql]))

(defn add-hierarchy-version
  [username version]
  (sql/insert :app_hierarchy_version
              (sql/values {:version version
                           :applied_by (get-user-id username)})))

(defn get-active-hierarchy-version
  []
  ((comp :version first)
   (sql/select :app_hierarchy_version
               (sql/fields :version)
               (sql/order :applied :DESC)
               (sql/limit 1))))
