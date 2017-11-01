(ns apps.routes.schemas.workspace
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [common-swagger-api.schema :only [describe]]
        [schema.core :only [defschema optional-key]])
  (:import [java.util UUID]))

(def WorkspaceId (describe UUID "The workspace ID."))

(defschema Workspace
  {:id               WorkspaceId
   :user_id          (describe UUID "The user's internal ID.")
   :root_category_id (describe UUID "The ID of the user's root app category.")
   :is_public        (describe Boolean "Indicates whether the workspace is public.")
   :new_workspace    (describe Boolean "Indicates whether the workspace was just created.")})

(defschema WorkspaceListing
  {:workspaces (describe [Workspace] "The list of workspaces.")})

(defschema WorkspaceListingParams
  (assoc SecuredQueryParams
    (optional-key :username)
    (describe [String] "The username to search for. Can be repeated to search for multiple usernames")))
