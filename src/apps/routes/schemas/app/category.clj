(ns apps.routes.schemas.app.category
  (:require
   [apps.routes.params :refer [SecuredQueryParams SecuredQueryParamsEmailRequired]]
   [apps.routes.schemas.app :refer [AdminAppListingValidSortFields AppListingPagingParams]]
   [common-swagger-api.schema :refer [->optional-param SortFieldDocs SortFieldOptionalKey describe]]
   [common-swagger-api.schema.apps.categories :as categories-schema]
   [schema.core :refer [defschema enum optional-key]])
  (:import
   (java.util UUID)))

(defschema CategoryListingParams
  (merge SecuredQueryParamsEmailRequired
         categories-schema/CategoryListingParams))

(defschema AdminCategorySearchParams
  (assoc SecuredQueryParams
         (optional-key :name)
         (describe [String] "Category names to search for.")))

(defschema AdminAppListingPagingParams
  (assoc AppListingPagingParams
         SortFieldOptionalKey
         (describe (apply enum AdminAppListingValidSortFields) SortFieldDocs)))

(defschema AppCategoryInfo
  (merge categories-schema/AppCategoryBase
         {:owner
          (describe String "The name of the category owner or 'public' if the category is public.")}))

(defschema AppCategorySearchResults
  {:categories (describe [AppCategoryInfo] "The list of matching app categories.")})

(defschema AppCategoryRequest
  {:name      categories-schema/AppCategoryNameParam
   :system_id (describe String "The system ID of the App Category's parent Category.")
   :parent_id (describe UUID "The UUID of the App Category's parent Category.")})

(defschema AppCategoryPatchRequest
  (-> AppCategoryRequest
      (->optional-param :name)
      (->optional-param :system_id)
      (->optional-param :parent_id)))
