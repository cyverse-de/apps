(ns apps.routes.apps.versions
  (:require [apps.routes.params :refer [SecuredQueryParams
                                        SecuredQueryParamsEmailRequired
                                        SecuredQueryParamsRequired]]
            [apps.service.apps :as apps]
            [apps.user :refer [current-user]]
            [apps.util.coercions :refer [coerce!]]
            [common-swagger-api.routes :refer :all]
            [common-swagger-api.schema :refer :all]
            [common-swagger-api.schema.apps :as schema]
            [common-swagger-api.schema.integration-data :as integration-schema]
            [ring.util.http-response :refer [ok]]))

(defroutes app-versions
           (context "/:system-id/:app-id/versions" []
                    :path-params [system-id :- schema/SystemId
                                  app-id    :- schema/StringAppIdParam]

                    (POST "/" []
                          :query [params SecuredQueryParamsRequired]
                          :body [app schema/AppVersionRequest]
                          :return schema/App
                          :summary schema/AppVersionCreateSummary
                          :description schema/AppVersionCreateDocs
                          (ok (apps/add-app-version current-user
                                                    system-id
                                                    (assoc app :id app-id)
                                                    false)))

                    (context "/:version-id" []
                             :path-params [version-id :- schema/AppVersionIdParam]

                             (DELETE "/" []
                                     :query [params SecuredQueryParams]
                                     :summary schema/AppVersionDeleteSummary
                                     :description schema/AppVersionDeleteDocs
                                     (ok (apps/delete-app-version current-user
                                                                  system-id
                                                                  app-id
                                                                  version-id)))

                             (GET "/" []
                                  :query [params SecuredQueryParams]
                                  :return schema/AppJobView
                                  :summary schema/AppJobViewSummary
                                  :description schema/AppJobViewDocs
                                  (ok (coerce! schema/AppJobView
                                               (apps/get-app-version-job-view current-user
                                                                              system-id
                                                                              app-id
                                                                              version-id))))

                             (PATCH "/" []
                                    :query [params SecuredQueryParamsEmailRequired]
                                    :body [body schema/AppLabelUpdateRequest]
                                    :return schema/App
                                    :summary schema/AppLabelUpdateSummary
                                    :description-file "docs/apps/app-label-update.md"
                                    (ok (apps/relabel-app current-user
                                                          system-id
                                                          (assoc body :id app-id
                                                                      :version_id version-id))))

                             (PUT "/" []
                                  :query [params SecuredQueryParamsEmailRequired]
                                  :body [body schema/AppUpdateRequest]
                                  :return schema/App
                                  :summary schema/AppUpdateSummary
                                  :description schema/AppUpdateDocs
                                  (ok (apps/update-app current-user
                                                       system-id
                                                       (assoc body :id app-id
                                                                   :version_id version-id))))

                             (POST "/copy" []
                                   :query [params SecuredQueryParamsRequired]
                                   :return schema/App
                                   :summary schema/AppCopySummary
                                   :description schema/AppCopyDocs
                                   (ok (apps/copy-app-version current-user system-id app-id version-id)))

                             (GET "/details" []
                                  :query [params SecuredQueryParams]
                                  :return schema/AppDetails
                                  :summary schema/AppDetailsSummary
                                  :description schema/AppDetailsDocs
                                  (ok (coerce! schema/AppDetails
                                               (apps/get-app-version-details current-user
                                                                             system-id
                                                                             app-id
                                                                             version-id))))

                             (GET "/documentation" []
                                  :query [params SecuredQueryParams]
                                  :return schema/AppDocumentation
                                  :summary schema/AppDocumentationSummary
                                  :description schema/AppDocumentationDocs
                                  (ok (coerce! schema/AppDocumentation
                                               (apps/get-app-version-docs current-user
                                                                          system-id
                                                                          app-id
                                                                          version-id))))

                             (PATCH "/documentation" []
                                    :query [params SecuredQueryParamsEmailRequired]
                                    :body [body schema/AppDocumentationRequest]
                                    :return schema/AppDocumentation
                                    :summary schema/AppDocumentationUpdateSummary
                                    :description schema/AppDocumentationUpdateDocs
                                    (ok (coerce! schema/AppDocumentation
                                                 (apps/owner-edit-app-version-docs current-user
                                                                                   system-id
                                                                                   app-id
                                                                                   version-id
                                                                                   body))))

                             (POST "/documentation" []
                                   :query [params SecuredQueryParamsEmailRequired]
                                   :body [body schema/AppDocumentationRequest]
                                   :return schema/AppDocumentation
                                   :summary schema/AppDocumentationAddSummary
                                   :description schema/AppDocumentationAddDocs
                                   (ok (coerce! schema/AppDocumentation
                                                (apps/owner-add-app-version-docs current-user
                                                                                 system-id
                                                                                 app-id
                                                                                 version-id
                                                                                 body))))

                             (GET "/integration-data" []
                                  :query [params SecuredQueryParams]
                                  :return integration-schema/IntegrationData
                                  :summary schema/AppIntegrationDataSummary
                                  :description schema/AppIntegrationDataDocs
                                  (ok (apps/get-app-version-integration-data current-user
                                                                             system-id
                                                                             app-id
                                                                             version-id)))

                             (GET "/ui" []
                                  :query [params SecuredQueryParamsEmailRequired]
                                  :return schema/App
                                  :summary schema/AppEditingViewSummary
                                  :description schema/AppEditingViewDocs
                                  (ok (apps/get-app-ui current-user system-id app-id version-id))))))
