(ns apps.routes.schemas.workspace
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [common-swagger-api.schema :only [describe ->optional-param]]
        [common-swagger-api.schema.apps.workspace :only [Workspace]]
        [schema.core :only [defschema optional-key]]))

(defschema WorkspaceListing
  {:workspaces (describe [Workspace] "The list of workspaces.")})

(defschema WorkspaceDeletionParams
  (assoc SecuredQueryParams
         :username
         (describe [String] "The username to search for. Can be repeated to search for multiple usernames")))

(defschema WorkspaceListingParams
  (->optional-param WorkspaceDeletionParams :username))
