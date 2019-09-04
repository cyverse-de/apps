(ns apps.routes.apps
  (:use [common-swagger-api.routes]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps.permission
         :only [AppPermissionListing
                AppPermissionListingDocs
                AppPermissionListingRequest
                AppPermissionListingSummary
                AppSharingDocs
                AppSharingRequest
                AppSharingResponse
                AppSharingSummary
                AppUnsharingDocs
                AppUnsharingRequest
                AppUnsharingResponse
                AppUnsharingSummary]]
        [common-swagger-api.schema.apps.rating]
        [common-swagger-api.schema.integration-data :only [IntegrationData]]
        [apps.routes.params
         :only [SecuredQueryParams
                SecuredQueryParamsEmailRequired
                SecuredQueryParamsRequired]]
        [apps.routes.schemas.app :only [AppSearchParams]]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [ring.util.http-response :only [accepted ok]])
  (:require [apps.routes.schemas.permission :as perms]
            [apps.service.apps :as apps]
            [common-swagger-api.schema.apps :as schema]))

(defroutes apps
  (GET "/" []
       :query [params AppSearchParams]
       :summary schema/AppListingSummary
       :return schema/AppListing
       :description schema/AppListingDocs
       (ok (coerce! schema/AppListing
                    (apps/search-apps current-user params))))

  (POST "/shredder" []
        :query [params SecuredQueryParams]
        :body [body schema/AppDeletionRequest]
        :summary schema/AppsShredderSummary
        :description schema/AppsShredderDocs
        (ok (apps/delete-apps current-user body)))

  (POST "/permission-lister" []
        :query [params perms/PermissionListerQueryParams]
        :body [body AppPermissionListingRequest]
        :return AppPermissionListing
        :summary AppPermissionListingSummary
        :description AppPermissionListingDocs
        (ok (apps/list-app-permissions current-user (:apps body) params)))

  (POST "/sharing" []
        :query [params SecuredQueryParams]
        :body [{:keys [sharing]} AppSharingRequest]
        :return AppSharingResponse
        :summary AppSharingSummary
        :description AppSharingDocs
        (ok (apps/share-apps current-user sharing)))

  (POST "/unsharing" []
        :query [params SecuredQueryParams]
        :body [{:keys [unsharing]} AppUnsharingRequest]
        :return AppUnsharingResponse
        :summary AppUnsharingSummary
        :description AppUnsharingDocs
        (ok (apps/unshare-apps current-user unsharing)))

  (context "/:system-id" []
    :path-params [system-id :- schema/SystemId]

    (POST "/" []
          :query [params SecuredQueryParamsRequired]
          :body [body schema/AppCreateRequest]
          :return schema/App
          :summary schema/AppCreateSummary
          :description schema/AppCreateDocs
          (ok (apps/add-app current-user system-id body)))

    (POST "/arg-preview" []
          :query [params SecuredQueryParams]
          :body [body schema/AppPreviewRequest]
          :summary schema/AppPreviewSummary
          :description schema/AppPreviewDocs
          (ok (apps/preview-command-line current-user system-id body)))

    (context "/:app-id" []
      :path-params [app-id :- schema/StringAppIdParam]

      (GET "/" []
           :query [params SecuredQueryParams]
           :summary schema/AppJobViewSummary
           :return schema/AppJobView
           :description schema/AppJobViewDocs
           (ok (coerce! schema/AppJobView
                        (apps/get-app-job-view current-user system-id app-id))))

      (DELETE "/" []
              :query [params SecuredQueryParams]
              :summary schema/AppDeleteSummary
              :description schema/AppDeleteDocs
              (ok (apps/delete-app current-user system-id app-id)))

      (PATCH "/" []
             :query [params SecuredQueryParamsEmailRequired]
             :body [body schema/AppLabelUpdateRequest]
             :return schema/App
             :summary schema/AppLabelUpdateSummary
             :description-file "docs/apps/app-label-update.md"
             (ok (apps/relabel-app current-user system-id (assoc body :id app-id))))

      (PUT "/" []
           :query [params SecuredQueryParamsEmailRequired]
           :body [body schema/AppUpdateRequest]
           :return schema/App
           :summary schema/AppUpdateSummary
           :description schema/AppUpdateDocs
           (ok (apps/update-app current-user system-id (assoc body :id app-id))))

      (POST "/copy" []
            :query [params SecuredQueryParamsRequired]
            :return schema/App
            :summary schema/AppCopySummary
            :description schema/AppCopyDocs
            (ok (apps/copy-app current-user system-id app-id)))

      (GET "/details" []
           :query [params SecuredQueryParams]
           :return schema/AppDetails
           :summary schema/AppDetailsSummary
           :description schema/AppDetailsDocs
           (ok (coerce! schema/AppDetails
                        (apps/get-app-details current-user system-id app-id))))

      (GET "/documentation" []
           :query [params SecuredQueryParams]
           :return schema/AppDocumentation
           :summary schema/AppDocumentationSummary
           :description schema/AppDocumentationDocs
           (ok (coerce! schema/AppDocumentation
                        (apps/get-app-docs current-user system-id app-id))))

      (PATCH "/documentation" []
             :query [params SecuredQueryParamsEmailRequired]
             :body [body schema/AppDocumentationRequest]
             :return schema/AppDocumentation
             :summary schema/AppDocumentationUpdateSummary
             :description schema/AppDocumentationUpdateDocs
             (ok (coerce! schema/AppDocumentation
                          (apps/owner-edit-app-docs current-user system-id app-id body))))

      (POST "/documentation" []
            :query [params SecuredQueryParamsEmailRequired]
            :body [body schema/AppDocumentationRequest]
            :return schema/AppDocumentation
            :summary schema/AppDocumentationAddSummary
            :description schema/AppDocumentationAddDocs
            (ok (coerce! schema/AppDocumentation
                         (apps/owner-add-app-docs current-user system-id app-id body))))

      (DELETE "/favorite" []
              :query [params SecuredQueryParams]
              :summary schema/AppFavoriteDeleteSummary
              :description schema/AppFavoriteDeleteDocs
              (ok (apps/remove-app-favorite current-user system-id app-id)))

      (PUT "/favorite" []
           :query [params SecuredQueryParams]
           :summary schema/AppFavoriteAddSummary
           :description schema/AppFavoriteAddDocs
           (ok (apps/add-app-favorite current-user system-id app-id)))

      (GET "/integration-data" []
           :query [params SecuredQueryParams]
           :return IntegrationData
           :summary schema/AppIntegrationDataSummary
           :description schema/AppIntegrationDataDocs
           (ok (apps/get-app-integration-data current-user system-id app-id)))

      (GET "/is-publishable" []
           :query [params SecuredQueryParams]
           :return schema/AppPublishableResponse
           :summary schema/AppPublishableSummary
           :description schema/AppPublishableDocs
           (ok (apps/app-publishable? current-user system-id app-id)))

      (POST "/publish" []
            :query [params SecuredQueryParamsEmailRequired]
            :body [body schema/PublishAppRequest]
            :summary schema/PublishAppSummary
            :description schema/PublishAppDocs
            (apps/validate-app-publishable current-user system-id app-id)
            (let [body (assoc body :id app-id)]
              (if (apps/uses-tools-in-untrusted-registries? current-user system-id app-id)
                (accepted (apps/create-publication-request current-user system-id body))
                (ok (apps/make-app-public current-user system-id body)))))

      (DELETE "/rating" []
              :query [params SecuredQueryParams]
              :return RatingResponse
              :summary schema/AppRatingDeleteSummary
              :description schema/AppRatingDeleteDocs
              (ok (apps/delete-app-rating current-user system-id app-id)))

      (POST "/rating" []
            :query [params SecuredQueryParams]
            :body [body RatingRequest]
            :return RatingResponse
            :summary schema/AppRatingSummary
            :description schema/AppRatingDocs
            (ok (apps/rate-app current-user system-id app-id body)))

      (GET "/tasks" []
           :query [params SecuredQueryParams]
           :return schema/AppTaskListing
           :summary schema/AppTaskListingSummary
           :description schema/AppTaskListingDocs
           (ok (coerce! schema/AppTaskListing
                        (apps/get-app-task-listing current-user system-id app-id))))

      (GET "/tools" []
           :query [params SecuredQueryParams]
           :return schema/AppToolListing
           :summary schema/AppToolListingSummary
           :description schema/AppToolListingDocs
           (ok (coerce! schema/AppToolListing
                        (apps/get-app-tool-listing current-user system-id app-id))))

      (GET "/ui" []
           :query [params SecuredQueryParamsEmailRequired]
           :return schema/App
           :summary schema/AppEditingViewSummary
           :description schema/AppEditingViewDocs
           (ok (apps/get-app-ui current-user system-id app-id))))))
