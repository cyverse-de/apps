(ns apps.routes.apps.categories
  (:require
   [apps.constants :refer [de-system-id]]
   [apps.routes.params :refer [SecuredQueryParams]]
   [apps.routes.schemas.app :refer [AppListingPagingParams]]
   [apps.routes.schemas.app.category
    :refer [CategoryListingParams
            OntologyAppListingPagingParams
            OntologyHierarchyFilterParams]]
   [apps.service.apps :as apps]
   [apps.service.apps.de.listings :as listings]
   [apps.user :refer [current-user]]
   [apps.util.coercions :refer [coerce!]]
   [apps.util.service :as service]
   [common-swagger-api.schema :refer [context defroutes GET undocumented]]
   [common-swagger-api.schema.apps
    :refer [AppCategoryIdPathParam
            AppListing
            SystemId]]
   [common-swagger-api.schema.apps.categories :as schema]
   [common-swagger-api.schema.ontologies
    :refer [OntologyClassIRIParam
            OntologyHierarchy
            OntologyHierarchyList]]
   [compojure.route :as route]
   [ring.util.http-response :refer [ok]]))

(defroutes app-categories
  (GET "/" []
    :query [params CategoryListingParams]
    :return schema/AppCategoryListing
    :summary schema/AppCategoryListingSummary
    :description schema/AppCategoryListingDocs
    (ok (apps/get-app-categories current-user params)))

  (GET "/featured" []
    :query [params AppListingPagingParams]
    :return schema/AppCategoryAppListing
    :summary schema/FeaturedAppListingSummary
    :description schema/FeaturedAppListingDocs
    (ok (coerce! schema/AppCategoryAppListing
                 (apps/list-apps-in-category current-user de-system-id listings/featured-apps-id params))))

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
