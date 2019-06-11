(ns apps.routes.admin
  (:use [common-swagger-api.routes]
        [common-swagger-api.schema]
        [common-swagger-api.schema.analyses.listing :only [ExternalId]]
        [common-swagger-api.schema.apps
         :only [AppCategoryIdPathParam
                SystemId]]
        [common-swagger-api.schema.apps.categories
         :only [AppCategoryListing
                AppCategoryAppListing
                AppCommunityGroupNameParam]]
        [common-swagger-api.schema.ontologies
         :only [OntologyClassIRIParam
                OntologyHierarchy
                OntologyVersionParam]]
        [apps.routes.params :only [SecuredQueryParams]]
        [apps.routes.schemas.analysis.listing]
        [apps.routes.schemas.app]
        [apps.routes.schemas.app.category]
        [apps.routes.schemas.workspace]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.apps :as apps]
            [apps.service.apps.de.admin :as admin]
            [apps.service.apps.de.listings :as listings]
            [apps.service.workspace :as workspace]
            [common-swagger-api.schema.apps.admin.apps :as schema]))

(defroutes admin-analyses
  (context "/by-external-id" []

    (POST "/" []
      :query [params SecuredQueryParams]
      :body [body ExternalIdList]
      :return AdminAnalysisList
      :summary "Look Up Analyses by External ID"
      :description "This endpoint is used to retrieve information about analyses with a given set of external
      identifiers."
      (ok (apps/admin-list-jobs-with-external-ids current-user (:external_ids body))))

    (GET "/:external-id" []
      :path-params [external-id :- ExternalId]
      :query [params SecuredQueryParams]
      :return AdminAnalysisList
      :summary "Look Up an Analysis by External ID"
      :description "This endpoint is used to retrieve information about an analysis with a given external identifier."
      (ok (apps/admin-list-jobs-with-external-ids current-user [external-id])))))

(defroutes admin-categories
  (GET "/" []
    :query [params SecuredQueryParams]
    :return AppCategoryListing
    :summary "List App Categories"
    :description "This service is used by DE admins to obtain a list of public app categories along
    with the 'Trash' virtual category."
    (ok (apps/get-admin-app-categories current-user params)))

  (GET "/search" []
    :query [params AdminCategorySearchParams]
    :return (describe AppCategorySearchResults "The matching app category information.")
    :summary "Search for App Categories"
    :description "This service allows the caller to search for categories by name. The name search is exact for the
    time being."
    (ok (apps/search-admin-app-categories current-user params)))

  (context "/:system-id" []
    :path-params [system-id :- SystemId]

    (POST "/" []
      :query [params SecuredQueryParams]
      :body [body (describe AppCategoryRequest "The details of the App Category to add.")]
      :return AppCategoryAppListing
      :summary "Add an App Category"
      :description "This endpoint adds an App Category under the given parent App Category, as long as
      that parent Category doesn't already have a subcategory with the given name and it doesn't
      directly contain its own Apps."
      (ok (apps/admin-add-category current-user system-id body)))

    (DELETE "/:category-id" []
      :path-params [category-id :- AppCategoryIdPathParam]
      :query [params SecuredQueryParams]
      :summary "Delete an App Category"
      :description "This service physically removes an App Category from the database, along with all
      of its child Categories, as long as none of them contain any Apps."
      (ok (apps/admin-delete-category current-user system-id category-id)))

    (PATCH "/:category-id" []
      :path-params [category-id :- AppCategoryIdPathParam]
      :query [params SecuredQueryParams]
      :body [body (describe AppCategoryPatchRequest "Details of the App Category to update.")]
      :summary "Update an App Category"
      :description "This service renames or moves an App Category to a new parent Category, depending
      on the fields included in the request."
      (ok (apps/admin-update-category current-user system-id (assoc body :id category-id))))))

(defroutes admin-communities

  (GET "/:community-id/apps" []
       :path-params [community-id :- AppCommunityGroupNameParam]
       :query [params AdminAppListingPagingParams]
       :return schema/AdminAppListing
       :summary "List Apps in a Community"
       :description (str "Lists all of the apps under an App Community that are visible to an admin."
                         (get-endpoint-delegate-block
                           "metadata"
                           "POST /avus/filter-targets"))
       (ok (coerce! schema/AdminAppListing (apps/admin-list-apps-in-community current-user community-id params)))))

(defroutes admin-ontologies

  (GET "/" []
    :query [params SecuredQueryParams]
    :return ActiveOntologyDetailsList
    :summary "List Ontology Details"
    :description (str
"Lists Ontology details saved in the metadata service."
(get-endpoint-delegate-block
  "metadata"
  "GET /ontologies"))
    (ok (admin/list-ontologies current-user)))

  (DELETE "/:ontology-version" []
    :path-params [ontology-version :- OntologyVersionParam]
    :query [params SecuredQueryParams]
    :summary "Delete an Ontology"
    :description (str
"Marks an Ontology as deleted in the metadata service.
 Returns `ERR_ILLEGAL_ARGUMENT` when attempting to delete the active `ontology-version`."
(get-endpoint-delegate-block
  "metadata"
  "DELETE /admin/ontologies/{ontology-version}"))
    (admin/delete-ontology current-user ontology-version))

  (POST "/:ontology-version" []
    :path-params [ontology-version :- OntologyVersionParam]
    :query [params SecuredQueryParams]
    :return AppCategoryOntologyVersionDetails
    :summary "Set Active Ontology Version"
    :description
    "Sets the active `ontology-version` to use in non-admin endpoints when querying the ontology
    endpoints of the metadata service."
    (ok (admin/set-category-ontology-version current-user ontology-version)))

  (GET "/:ontology-version/:root-iri" []
       :path-params [ontology-version :- OntologyVersionParam
                     root-iri :- OntologyClassIRIParam]
       :query [{:keys [attr] :as params} OntologyHierarchyFilterParams]
       :return OntologyHierarchy
       :summary "Get App Category Hierarchy"
       :description (str
"Gets the list of app categories that are visible to the user for the given `ontology-version`,
 rooted at the given `root-iri`."
(get-endpoint-delegate-block
  "metadata"
  "POST /ontologies/{ontology-version}/{root-iri}/filter")
"Please see the metadata service documentation for response information.")
    (ok (listings/get-app-hierarchy current-user ontology-version root-iri attr)))

  (GET "/:ontology-version/:root-iri/apps" []
    :path-params [ontology-version :- OntologyVersionParam
                  root-iri :- OntologyClassIRIParam]
    :query [{:keys [attr] :as params} AdminOntologyAppListingPagingParams]
    :return schema/AdminAppListing
    :summary "List Apps in a Category"
    :description (str
"Lists all of the apps under an app category hierarchy, for the given `ontology-version`,
 that are visible to the user."
(get-endpoint-delegate-block
  "metadata"
  "POST /ontologies/{ontology-version}/{root-iri}/filter-targets"))
    (ok (coerce! schema/AdminAppListing
                 (apps/admin-list-apps-under-hierarchy current-user ontology-version root-iri attr params))))

  (GET "/:ontology-version/:root-iri/unclassified" [root-iri]
    :path-params [ontology-version :- OntologyVersionParam
                  root-iri :- OntologyClassIRIParam]
    :query [{:keys [attr] :as params} AdminOntologyAppListingPagingParams]
    :return schema/AdminAppListing
    :summary "List Unclassified Apps"
    :description (str
"Lists all of the apps that are visible to the user that are not under the given `root-iri`, or any of
 its subcategories, for the given `ontology-version`."
(get-endpoint-delegate-block
  "metadata"
  "POST /ontologies/{ontology-version}/{root-iri}/filter-unclassified"))
    (ok (coerce! schema/AdminAppListing
                 (listings/get-unclassified-app-listing current-user ontology-version root-iri attr params true)))))

(defroutes admin-workspaces
  (GET "/" []
    :query [params WorkspaceListingParams]
    :return WorkspaceListing
    :summary "List Workspaces"
    :description "This endpoint allows an administrator to list workspaces in the DE."
    (ok (workspace/list-workspaces params)))

  (DELETE "/" []
    :query [params WorkspaceDeletionParams]
    :summary "Delete Workspaces"
    :description "This endpoint allows an administrator to delete workspaces in the DE."
    (workspace/delete-workspaces params)
    (ok)))
