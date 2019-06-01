(ns apps.routes.schemas.tool
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [common-swagger-api.schema :only [->optional-param describe]]
        [schema.core :only [defschema optional-key]])
  (:require [common-swagger-api.schema.tools :as schema]
            [common-swagger-api.schema.tools.admin :as admin-schema]))

(defschema PrivateToolDeleteParams
  (merge SecuredQueryParams
         schema/PrivateToolDeleteParams))

(defschema ToolUpdateParams
  (merge SecuredQueryParams
         admin-schema/ToolUpdateParams))

(defschema ToolRequestStatusUpdate
  (dissoc schema/ToolRequestStatus :updated_by :status_date))

(defschema ToolRequestListingParams
  (merge SecuredQueryParams
         schema/ToolRequestListingParams))

(defschema ToolRequestStatusCodeListingParams
  (merge SecuredQueryParams
         schema/ToolRequestStatusCodeListingParams))

(defschema ImagePublicAppToolListing
  {:tools (describe [schema/Tool] "Listing of Public App Tools")})
