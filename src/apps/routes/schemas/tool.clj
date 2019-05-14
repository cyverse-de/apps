(ns apps.routes.schemas.tool
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [clojure-commons.error-codes]
        [common-swagger-api.schema
         :only [->optional-param
                describe
                ErrorResponse
                PagingParams]]
        [schema.core :only [defschema enum optional-key]])
  (:require [common-swagger-api.schema.tools :as schema])
  (:import [java.util UUID]))

(defschema ToolIdsList
  {:tool_ids (describe [UUID] "A List of Tool IDs")})

(defschema PrivateToolDeleteParams
  (merge SecuredQueryParams
         schema/PrivateToolDeleteParams))

(defschema ToolUpdateParams
  (merge SecuredQueryParams
    {(optional-key :overwrite-public)
     (describe Boolean "Flag to force container settings updates of public tools.")}))

(defschema ToolsImportRequest
  {:tools (describe [schema/ToolImportRequest] "zero or more Tool definitions")})

(defschema ToolUpdateRequest
  (-> schema/ToolImportRequest
      (->optional-param :name)
      (->optional-param :version)
      (->optional-param :type)
      (->optional-param :implementation)
      (->optional-param :container)))

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
