(ns apps.routes.admin.apps
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [apps.routes.schemas.app :only [AdminAppSearchParams]]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps.admin.categories
         :only [AppCategorizationDocs
                AppCategorizationRequest
                AppCategorizationSummary]]
        [common-swagger-api.schema.integration-data
         :only [IntegrationData
                IntegrationDataIdPathParam]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.apps :as apps]
            [common-swagger-api.routes]                     ;; for :description-file
            [common-swagger-api.schema.apps :as apps-schema]
            [common-swagger-api.schema.apps.admin.apps :as schema]))

(defroutes admin-apps
  (GET "/" []
       :query [params AdminAppSearchParams]
       :summary apps-schema/AppListingSummary
       :return schema/AdminAppListing
       :description schema/AppListingDocs
       (ok (coerce! schema/AdminAppListing
                    (apps/admin-search-apps current-user params))))

  (POST "/" []
        :query [params SecuredQueryParams]
        :body [body AppCategorizationRequest]
        :summary AppCategorizationSummary
        :description AppCategorizationDocs
        (ok (apps/categorize-apps current-user body)))

  (POST "/shredder" []
        :query [params SecuredQueryParams]
        :body [body apps-schema/AppDeletionRequest]
        :summary schema/AppShredderSummary
        :description schema/AppShredderDocs
        (ok (apps/permanently-delete-apps current-user body)))

  (context "/:system-id/:app-id" []
    :path-params [system-id :- apps-schema/SystemId
                  app-id :- apps-schema/StringAppIdParam]

    (DELETE "/" []
            :query [params SecuredQueryParams]
            :summary apps-schema/AppDeleteSummary
            :description schema/AppDeleteDocs
            (ok (apps/admin-delete-app current-user system-id app-id)))

    (PATCH "/" []
           :query [params SecuredQueryParams]
           :body [body schema/AdminAppPatchRequest]
           :return schema/AdminAppDetails
           :summary schema/AdminAppPatchSummary
           :description-file "docs/apps/admin/app-label-update.md"
           (ok (coerce! schema/AdminAppDetails
                        (apps/admin-update-app current-user system-id (assoc body :id app-id)))))

    (GET "/details" []
         :query [params SecuredQueryParams]
         :return schema/AdminAppDetails
         :summary apps-schema/AppDetailsSummary
         :description schema/AppDetailsDocs
         (ok (coerce! schema/AdminAppDetails
                      (apps/admin-get-app-details current-user system-id app-id))))

    (PATCH "/documentation" []
           :query [params SecuredQueryParams]
           :body [body apps-schema/AppDocumentationRequest]
           :return apps-schema/AppDocumentation
           :summary apps-schema/AppDocumentationUpdateSummary
           :description schema/AppDocumentationUpdateDocs
           (ok (coerce! apps-schema/AppDocumentation
                        (apps/admin-edit-app-docs current-user system-id app-id body))))

    (POST "/documentation" []
          :query [params SecuredQueryParams]
          :body [body apps-schema/AppDocumentationRequest]
          :return apps-schema/AppDocumentation
          :summary apps-schema/AppDocumentationAddSummary
          :description schema/AppDocumentationAddDocs
          (ok (coerce! apps-schema/AppDocumentation
                       (apps/admin-add-app-docs current-user system-id app-id body))))

    (PUT "/integration-data/:integration-data-id" []
         :path-params [integration-data-id :- IntegrationDataIdPathParam]
         :query [params SecuredQueryParams]
         :return IntegrationData
         :summary schema/AppIntegrationDataUpdateSummary
         :description schema/AppIntegrationDataUpdateDocs
         (ok (apps/update-app-integration-data current-user system-id app-id integration-data-id)))))
