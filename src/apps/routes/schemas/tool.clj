(ns apps.routes.schemas.tool
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [common-swagger-api.schema :refer [describe]]
   [common-swagger-api.schema.tools :as schema]
   [common-swagger-api.schema.tools.admin :as admin-schema]
   [schema.core :refer [defschema]]))

(defschema PrivateToolDeleteParams
  (merge SecuredQueryParams
         schema/PrivateToolDeleteParams))

(defschema ToolDetailsParams
  (merge SecuredQueryParams
         schema/ToolDetailsParams))

(defschema ToolUpdateParams
  (merge SecuredQueryParams
         admin-schema/ToolUpdateParams))

(defschema ToolRequestListingParams
  (merge SecuredQueryParams
         schema/ToolRequestListingParams))

(defschema ToolRequestStatusCodeListingParams
  (merge SecuredQueryParams
         schema/ToolRequestStatusCodeListingParams))

(defschema ImagePublicAppToolListing
  {:tools (describe [schema/Tool] "Listing of Public App Tools")})
