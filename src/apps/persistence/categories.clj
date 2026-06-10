(ns apps.persistence.categories
  (:require [apps.persistence.users :refer [get-user-id]]
            [apps.util.cache :as cache]
            [korma.core :as sql]))

(defn add-hierarchy-version
  [username version]
  (sql/insert :app_hierarchy_version
              (sql/values {:version version
                           :applied_by (get-user-id username)})))

(defn- fetch-active-hierarchy-version
  []
  ((comp :version first)
   (sql/select :app_hierarchy_version
               (sql/fields :version)
               (sql/order :applied :DESC)
               (sql/limit 1))))

(def ^:private hierarchy-version-cache
  "TTL cache for the active hierarchy version. TTL is 60 seconds — short enough
   to pick up admin changes promptly, long enough to avoid repeated DB hits
   during request processing."
  (cache/ttl-cache fetch-active-hierarchy-version 60000))

(defn invalidate-hierarchy-version-cache
  "Clears the cached hierarchy version. Call after setting a new version."
  []
  ((:invalidate hierarchy-version-cache)))

(def get-active-hierarchy-version
  "Returns the active hierarchy version, served from a 60-second TTL cache."
  (:lookup hierarchy-version-cache))
