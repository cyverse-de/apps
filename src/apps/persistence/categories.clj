(ns apps.persistence.categories
  (:use [apps.persistence.users :only [get-user-id]]
        [apps.util.db :only [transaction]]
        [korma.core :exclude [update]]))

(defn add-hierarchy-version
  [username version]
  (insert :app_hierarchy_version
          (values {:version version
                   :applied_by (get-user-id username)})))

(defn get-active-hierarchy-version
  []
  ((comp :version first)
   (select :app_hierarchy_version
           (fields :version)
           (order :applied :DESC)
           (limit 1))))
