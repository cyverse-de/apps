(ns apps.workspace
  (:require
   [apps.persistence.users :refer [get-existing-user-id]]
   [apps.persistence.workspace :refer [fetch-workspace-by-user-id]]
   [apps.user :refer [current-user]]
   [apps.util.cache :as cache]
   [slingshot.slingshot :refer [throw+]]))

(def ^:private workspace-cache
  "Cache of username -> workspace. Only non-nil results are stored.
   Workspaces are immutable once created, so caching indefinitely is safe."
  (cache/cache-by-key
   (fn [username]
     (fetch-workspace-by-user-id (get-existing-user-id username)))))

(def ^:private lookup-workspace
  "Cached workspace lookup by username."
  (:lookup workspace-cache))

(defn get-workspace
  "Gets a workspace database entry for the given username or the current user."
  ([]
   (get-workspace (:username current-user)))
  ([username]
   (if-let [workspace (lookup-workspace username)]
     workspace
     (throw+ {:type     :clojure-commons.exception/not-found
              :message  "Workspace for user not found."
              :username username}))))

(defn get-optional-workspace
  "Gets a workspace database entry for the given username if a username is provided."
  [username]
  (when username (get-workspace username)))
