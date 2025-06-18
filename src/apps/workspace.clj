(ns apps.workspace
  (:require
   [apps.persistence.users :refer [get-existing-user-id]]
   [apps.persistence.workspace :refer [fetch-workspace-by-user-id]]
   [apps.user :refer [current-user]]
   [slingshot.slingshot :refer [throw+]]))

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
