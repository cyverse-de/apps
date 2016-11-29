(ns apps.workspace
  (:use [apps.persistence.users :only [get-existing-user-id]]
        [apps.persistence.workspace :only [fetch-workspace-by-user-id]]
        [korma.core :exclude [update]]
        [apps.user :only [current-user]]
        [slingshot.slingshot :only [throw+]]))

(defn get-workspace
  "Gets a workspace database entry for the given username or the current user."
  ([]
     (get-workspace (:username current-user)))
  ([username]
     (if-let [workspace (fetch-workspace-by-user-id (get-existing-user-id username))]
       workspace
       (throw+ {:type     :clojure-commons.exception/not-found
                :message  "Workspace for user not found."
                :username username}))))

(defn get-optional-workspace
  "Gets a workspace database entry for the given username if a username is provided."
  [username]
  (when username (get-workspace username)))
