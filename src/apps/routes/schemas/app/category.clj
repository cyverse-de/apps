(ns apps.routes.schemas.app.category
  (:use [common-swagger-api.schema :only [->optional-param
                                          describe
                                          NonBlankString
                                          PagingParams
                                          SortFieldDocs
                                          SortFieldOptionalKey]]
        [common-swagger-api.schema.apps :only [AppListingDetail
                                               SystemId]]
        [common-swagger-api.schema.ontologies]
        [apps.routes.params]
        [apps.routes.schemas.app :only [AdminAppListingValidSortFields
                                        AppListingPagingParams]]
        [schema.core :only [defschema optional-key recursive enum]])
  (:import [java.util Date UUID]))

(def AppCategoryNameParam (describe String "The App Category's name"))
(def AppCommunityGroupNameParam (describe String "The full group name of the App Community"))

(defschema CategoryListingParams
  (merge SecuredQueryParamsEmailRequired
    {(optional-key :public)
     (describe Boolean
       "If set to 'true', then only app categories that are in a workspace that is marked as
        public in the database are returned. If set to 'false', then only app categories that
        are in the user's workspace are returned. If not set, then both public and the user's
        private categories are returned.")}))

(defschema AdminCategorySearchParams
  (assoc SecuredQueryParams
    (optional-key :name)
    (describe [String] "Category names to search for.")))

(defschema AdminAppListingPagingParams
  (assoc AppListingPagingParams
    SortFieldOptionalKey
    (describe (apply enum AdminAppListingValidSortFields) SortFieldDocs)))

(defschema AppCategoryId
  {:system_id
   SystemId

   :id
   AppCategoryIdPathParam})

(defschema AppCategoryBase
  (merge AppCategoryId
         {:name
          AppCategoryNameParam}))

(defschema AppCategoryInfo
  (merge AppCategoryBase
         {:owner
          (describe String "The name of the category owner or 'public' if the category is public.")}))

(defschema AppCategorySearchResults
  {:categories (describe [AppCategoryInfo] "The list of matching app categories.")})

(defschema AppCategory
  (merge AppCategoryBase
         {:total
          (describe Long "The number of Apps under this Category and all of its children")

          :is_public
          (describe Boolean
                    (str "Whether this App Category is viewable to all users or private to only the user that owns its"
                         " Workspace"))

          (optional-key :categories)
          (describe [(recursive #'AppCategory)]
                    "A listing of child App Categories under this App Category")}))

(defschema AppCategoryListing
  {:categories (describe [AppCategory] "A listing of App Categories visisble to the requesting user")})

(defschema AppCategoryIdList
  {:category_ids (describe [AppCategoryId] "A List of App Category identifiers")})

(defschema AppCategoryAppListing
  (merge (dissoc AppCategory :categories)
         {:apps (describe [AppListingDetail] "A listing of Apps under this Category")}))

(defschema AppCategorization
  (merge AppCategoryIdList
         {:system_id SystemId
          :app_id    (describe String "The ID of the App to be Categorized")}))

(defschema AppCategorizationRequest
  {:categories (describe [AppCategorization] "Apps and the Categories they should be listed under")})

(defschema AppCategoryRequest
  {:name      AppCategoryNameParam
   :system_id (describe String "The system ID of the App Category's parent Category.")
   :parent_id (describe UUID "The UUID of the App Category's parent Category.")})

(defschema AppCategoryPatchRequest
  (-> AppCategoryRequest
      (->optional-param :name)
      (->optional-param :system_id)
      (->optional-param :parent_id)))

(defschema AppCategoryOntologyVersionDetails
  {:version    (describe String "The unique version of the Ontology")
   :applied_by (describe NonBlankString "The user that set this version as active")
   :applied    (describe Date "The date this version was set as active")})

(defschema ActiveOntologyDetails
  (merge OntologyDetails
         {:active (describe Boolean
                            "Marks this Ontology version as the active version used when querying
                             metadata service ontology endpoints")}))

(defschema ActiveOntologyDetailsList
  {:ontologies (describe [ActiveOntologyDetails] "List of available Ontologies")})

(defschema OntologyHierarchyFilterParams
  (merge SecuredQueryParams
         {:attr (describe String "The metadata attribute that stores class IRIs under the given root IRI")}))

(defschema OntologyAppListingPagingParams
  (merge AppListingPagingParams
         {:attr (describe String "The metadata attribute that stores the given class IRI")}))

(defschema AdminOntologyAppListingPagingParams
  (assoc OntologyAppListingPagingParams
    SortFieldOptionalKey
    (describe (apply enum AdminAppListingValidSortFields) SortFieldDocs)))
