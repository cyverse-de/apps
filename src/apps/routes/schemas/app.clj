(ns apps.routes.schemas.app
  (:require
   [apps.routes.params :refer [SecuredQueryParams SecuredQueryParamsEmailRequired]]
   [clojure.set :as sets]
   [common-swagger-api.schema.apps :as app-schema]
   [common-swagger-api.schema.apps.admin.apps :as admin-schema]
   [schema.core :refer [defschema]]))

(def AdminAppListingValidSortFields
  (sets/union app-schema/AppListingValidSortFields
              admin-schema/AdminAppListingJobStatsKeys))

(defschema AppListingPagingParams
  (merge SecuredQueryParamsEmailRequired
         app-schema/AppListingPagingParams))

(defschema AppSearchParams
  (merge SecuredQueryParams
         app-schema/AppSearchParams))

(defschema AdminAppSearchParams
  (merge SecuredQueryParams
         admin-schema/AdminAppSearchParams))

(defschema AppPublicationRequestSearchParams
  (merge SecuredQueryParams
         admin-schema/AppPublicationRequestSearchParams))
