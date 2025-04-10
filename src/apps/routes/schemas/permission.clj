(ns apps.routes.schemas.permission
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [common-swagger-api.schema.apps.permission :as perms-schema]
   [schema.core :refer [defschema]]))

(defschema PermissionListerQueryParams
  (merge SecuredQueryParams perms-schema/PermissionListerQueryParams))
