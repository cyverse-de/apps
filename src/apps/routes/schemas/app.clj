(ns apps.routes.schemas.app
  (:use [apps.routes.params :only [SecuredQueryParams SecuredQueryParamsEmailRequired]]
        [common-swagger-api.schema
         :only [->optional-param
                optional-key->keyword
                describe
                PagingParams
                SortFieldDocs
                SortFieldOptionalKey]]
        [common-swagger-api.schema.apps
         :only [AppBase
                AppDeletedParam
                AppDetails
                AppDisabledParam
                AppDocUrlParam
                AppFilterParams
                AppListing
                AppListingDetail
                AppListingJobStats
                AppListingJobStatsDocs
                AppListingValidSortFields
                AppGroup
                AppReferencesParam
                AppSearchValidSortFields
                GroupListDocs
                OptionalGroupsKey]]
        [schema.core :only [defschema enum optional-key]])
  (:require [clojure.set :as sets]
            [common-swagger-api.schema.apps :as app-schema])
  (:import [java.util Date]))

(defschema AdminAppListingJobStats
  (merge AppListingJobStats
         {:job_count
          (describe Long "The number of times this app has run")

          :job_count_failed
          (describe Long "The number of times this app has run to `Failed` status")

          (optional-key :last_used)
          (describe Date "The start date this app was last run")}))

(defschema AdminAppListingDetail
  (merge AppListingDetail
         {(optional-key :job_stats)
          (describe AdminAppListingJobStats AppListingJobStatsDocs)}))

(defschema AdminAppListing
  (merge AppListing
         {:apps (describe [AdminAppListingDetail] "A listing of App details")}))

(def AdminAppListingJobStatsKeys (->> AdminAppListingJobStats
                                      keys
                                      (map optional-key->keyword)
                                      set))

(def AdminAppListingValidSortFields
  (sets/union AppListingValidSortFields AdminAppListingJobStatsKeys))

(defschema AppListingPagingParams
  (merge SecuredQueryParamsEmailRequired
         PagingParams
         AppFilterParams
         {SortFieldOptionalKey
          (describe (apply enum AppListingValidSortFields) SortFieldDocs)}))

(def AdminAppSearchValidSortFields
  (sets/union AppSearchValidSortFields AdminAppListingJobStatsKeys))

(defschema AppSearchParams
  (merge SecuredQueryParams app-schema/AppSearchParams))

(def AppSubsets (enum :public :private :all))

(defschema AdminAppSearchParams
  (merge AppSearchParams
         {SortFieldOptionalKey
          (describe (apply enum AdminAppSearchValidSortFields) SortFieldDocs)

          (optional-key :app-subset)
          (describe AppSubsets "The subset of apps to search." :default :public)}))

(defschema AdminAppDetails
  (merge AppDetails
         {(optional-key :job_stats)
          (describe AdminAppListingJobStats AppListingJobStatsDocs)}))

(defschema AdminAppPatchRequest
  (-> AppBase
    (->optional-param :id)
    (->optional-param :name)
    (->optional-param :description)
    (assoc (optional-key :wiki_url)   AppDocUrlParam
           (optional-key :references) AppReferencesParam
           (optional-key :deleted)    AppDeletedParam
           (optional-key :disabled)   AppDisabledParam
           OptionalGroupsKey          (describe [AppGroup] GroupListDocs))))
