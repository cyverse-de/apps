(ns apps.routes.params
  (:use [common-swagger-api.schema
         :only [->optional-param
                describe
                NonBlankString
                PagingParams
                SortFieldDocs
                SortFieldOptionalKey
                StandardUserQueryParams]]
        [common-swagger-api.schema.common :only [IncludeHiddenParams]])
  (:require [common-swagger-api.schema.analyses.listing :as analyses-schema]
            [common-swagger-api.schema.apps.elements :as elements-schema]
            [common-swagger-api.schema.tools :as tools-schema]
            [schema.core :as s])
  (:import [java.util UUID]))

(def IntegrationDataIdPathParam (describe UUID "A UUID that is used to identify the integration data record"))

(def ApiName (describe String "The name of the external API"))

(def SubmissionIdPathParam (describe UUID "The Submission UUID"))

(s/defschema SecuredQueryParamsRequired
  (merge StandardUserQueryParams
    {:email      (describe NonBlankString "The user's email address")
     :first-name (describe NonBlankString "The user's first name")
     :last-name  (describe NonBlankString "The user's last name")}))

(s/defschema SecuredQueryParamsEmailRequired
  (-> SecuredQueryParamsRequired
      (->optional-param :first-name)
      (->optional-param :last-name)))

(s/defschema SecuredQueryParams
  (-> SecuredQueryParamsEmailRequired
    (->optional-param :email)))

(s/defschema TokenInfoProxyParams
  (merge SecuredQueryParams
         {(s/optional-key :proxy-user)
          (describe NonBlankString "The name of the proxy user for admin service calls.")}))

(s/defschema OAuthCallbackQueryParams
  (merge SecuredQueryParams
         {:code  (describe NonBlankString "The authorization code used to obtain the access token.")
          :state (describe NonBlankString "The authorization state information.")}))

(s/defschema AppElementToolListingParams
  (merge SecuredQueryParams IncludeHiddenParams))

(s/defschema FilterParams
  {:field
   (describe String "The name of the field on which the filter is based.")

   :value
   (describe (s/maybe String)
     "The search value. If `field` is `name` or `app_name`, then `value` can be contained anywhere,
      case-insensitive, in the corresponding field.")})

(s/defschema AnalysisListingParams
  (merge SecuredQueryParams analyses-schema/AnalysisListingParams))

(s/defschema ToolSearchParams
  (merge SecuredQueryParams tools-schema/ToolSearchParams))

(s/defschema AppParameterTypeParams
  (merge SecuredQueryParams elements-schema/AppParameterTypeParams))

(s/defschema IntegrationDataSortFields
  (s/enum :email :name :username))

(s/defschema IntegrationDataSearchParams
  (merge SecuredQueryParams
         PagingParams
         {(s/optional-key :search)
          (describe String "Searches for entries with matching names or email addresses.")

          SortFieldOptionalKey
          (describe IntegrationDataSortFields SortFieldDocs)}))
