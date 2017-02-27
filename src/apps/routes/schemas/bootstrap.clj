(ns apps.routes.schemas.bootstrap
  (:use [common-swagger-api.schema :only [describe]]
        [apps.routes.params :only [SystemId]]
        [apps.routes.schemas.workspace :only [Workspace]]
        [schema.core :only [Any defschema enum optional-key recursive]]))

(defschema SystemIds
  {:de_system_id   (describe SystemId "The internal system ID used by the Discovery Environment.")
   :all_system_ids (describe [SystemId] "The list of system IDs available to the Discovery Environment.")})

(defschema BootstrapResponse
  {:system_ids (describe SystemIds "Information about system IDs available to the Discovery Environment.")
   :workspace  (describe Workspace "Information about the user's Discovery Environment workspace.")})
