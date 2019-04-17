(ns apps.routes.apps.categories
  (:use [common-swagger-api.routes]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps
         :only [AppCategoryIdPathParam
                AppListing
                SystemId]]
        [common-swagger-api.schema.ontologies
         :only [OntologyClassIRIParam
                OntologyHierarchy
                OntologyHierarchyList]]
        [apps.routes.params :only [SecuredQueryParams]]
        [apps.routes.schemas.app :only [AppListingPagingParams]]
        [apps.routes.schemas.app.category
         :only [CategoryListingParams
                OntologyAppListingPagingParams
                OntologyHierarchyFilterParams]]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.apps :as apps]
            [apps.service.apps.de.listings :as listings]
            [apps.util.service :as service]
            [common-swagger-api.schema.apps.categories :as schema]
            [compojure.route :as route]))

(defroutes app-categories
  (GET "/" []
        :query [params CategoryListingParams]
        :return schema/AppCategoryListing
        :summary schema/AppCategoryListingSummary
        :description schema/AppCategoryListingDocs
        (ok (apps/get-app-categories current-user params)))

  (GET "/:system-id/:category-id" []
        :path-params [system-id :- SystemId
                      category-id :- AppCategoryIdPathParam]
        :query [params AppListingPagingParams]
        :return schema/AppCategoryAppListing
        :summary schema/AppCategoryAppListingSummary
        :description schema/AppCategoryAppListingDocs
        (ok (coerce! schema/AppCategoryAppListing
                 (apps/list-apps-in-category current-user system-id category-id params))))

  (undocumented (route/not-found (service/unrecognized-path-response))))

(defroutes app-hierarchies

  (GET "/" []
       :query [params SecuredQueryParams]
       :return OntologyHierarchyList
       :summary schema/AppHierarchiesListingSummary
       :description schema/AppHierarchiesListingDocs
       (ok (listings/list-hierarchies current-user)))

  (context "/:root-iri" []
    :path-params [root-iri :- OntologyClassIRIParam]

    (GET "/" []
         :query [{:keys [attr]} OntologyHierarchyFilterParams]
         :return OntologyHierarchy
         :summary schema/AppCategoryHierarchyListingSummary
         :description schema/AppCategoryHierarchyListingDocs
         (ok (listings/get-app-hierarchy current-user root-iri attr)))

    (GET "/apps" []
         :query [{:keys [attr] :as params} OntologyAppListingPagingParams]
         :return AppListing
         :summary schema/AppCategoryAppListingSummary
         :description schema/AppHierarchyAppListingDocs
         (ok (coerce! AppListing (apps/list-apps-under-hierarchy current-user root-iri attr params))))

    (GET "/unclassified" []
         :query [{:keys [attr] :as params} OntologyAppListingPagingParams]
         :return AppListing
         :summary schema/AppHierarchyUnclassifiedListingSummary
         :description schema/AppHierarchyUnclassifiedListingDocs
         (ok (coerce! AppListing (listings/get-unclassified-app-listing current-user root-iri attr params)))))

  (undocumented (route/not-found (service/unrecognized-path-response))))

(defroutes app-communities

  (GET "/:community-id/apps" []
       :path-params [community-id :- schema/AppCommunityGroupNameParam]
       :query [params AppListingPagingParams]
       :return AppListing
       :summary schema/AppCommunityAppListingSummary
       :description schema/AppCommunityAppListingDocs
       (ok (coerce! AppListing (apps/list-apps-in-community current-user community-id params))))

  (undocumented (route/not-found (service/unrecognized-path-response))))
