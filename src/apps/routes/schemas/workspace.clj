(ns apps.routes.schemas.workspace
  (:require [apps.routes.params :refer [SecuredQueryParams]]
            [common-swagger-api.schema :refer [describe ->optional-param]]
            [common-swagger-api.schema.apps.workspace :refer [Workspace]]
            [schema.core :refer [defschema]]))

(defschema WorkspaceListing
  {:workspaces (describe [Workspace] "The list of workspaces.")})

(defschema WorkspaceDeletionParams
  (assoc SecuredQueryParams
         :username
         (describe [String] "The username to search for. Can be repeated to search for multiple usernames")))

(defschema WorkspaceListingParams
  (->optional-param WorkspaceDeletionParams :username))
