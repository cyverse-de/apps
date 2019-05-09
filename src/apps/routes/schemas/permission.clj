(ns apps.routes.schemas.permission
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [schema.core :only [defschema]])
  (:require [common-swagger-api.schema.apps.permission :as perms-schema]))

(defschema PermissionListerQueryParams
  (merge SecuredQueryParams perms-schema/PermissionListerQueryParams))
