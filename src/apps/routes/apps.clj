(ns apps.routes.apps
  (:use [common-swagger-api.routes]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps
         :only [App
                AppCopyDocs
                AppCopySummary
                AppCreateDocs
                AppCreateRequest
                AppCreateSummary
                AppDeleteDocs
                AppDeleteSummary
                AppDeletionRequest
                AppDetails
                AppDetailsSummary
                AppDocumentation
                AppDocumentationAddDocs
                AppDocumentationAddSummary
                AppDocumentationDocs
                AppDocumentationRequest
                AppDocumentationSummary
                AppDocumentationUpdateDocs
                AppDocumentationUpdateSummary
                AppEditingViewDocs
                AppEditingViewSummary
                AppFavoriteAddDocs
                AppFavoriteAddSummary
                AppFavoriteDeleteDocs
                AppFavoriteDeleteSummary
                AppIntegrationDataDocs
                AppIntegrationDataSummary
                AppJobView
                AppJobViewDocs
                AppJobViewSummary
                AppLabelUpdateRequest
                AppLabelUpdateSummary
                AppListing
                AppListingSummary
                AppPreviewDocs
                AppPreviewRequest
                AppPreviewSummary
                AppPublishableDocs
                AppPublishableResponse
                AppPublishableSummary
                AppRatingDeleteDocs
                AppRatingDeleteSummary
                AppRatingDocs
                AppRatingSummary
                AppsShredderDocs
                AppsShredderSummary
                AppTaskListing
                AppTaskListingDocs
                AppTaskListingSummary
                AppToolListing
                AppToolListingDocs
                AppToolListingSummary
                AppUpdateRequest
                AppUpdateSummary
                PublishAppDocs
                PublishAppRequest
                PublishAppSummary
                StringAppIdParam
                SystemId]]
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
        [ring.util.http-response :only [ok]])
  (:require [apps.routes.schemas.permission :as perms]
            [apps.service.apps :as apps]))

(defroutes apps
  (GET "/" []
    :query [params AppSearchParams]
    :summary AppListingSummary
    :return AppListing
    :description-file "docs/apps/apps-listing.md"
    (ok (coerce! AppListing
                 (apps/search-apps current-user params))))

  (POST "/shredder" []
    :query [params SecuredQueryParams]
    :body [body AppDeletionRequest]
    :summary AppsShredderSummary
    :description AppsShredderDocs
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
    :path-params [system-id :- SystemId]

    (POST "/" []
          :query [params SecuredQueryParamsRequired]
          :body [body AppCreateRequest]
          :return App
          :summary AppCreateSummary
          :description AppCreateDocs
          (ok (apps/add-app current-user system-id body)))

    (POST "/arg-preview" []
          :query [params SecuredQueryParams]
          :body [body AppPreviewRequest]
          :summary AppPreviewSummary
          :description AppPreviewDocs
          (ok (apps/preview-command-line current-user system-id body)))

    (context "/:app-id" []
      :path-params [app-id :- StringAppIdParam]

      (GET "/" []
           :query [params SecuredQueryParams]
           :summary AppJobViewSummary
           :return AppJobView
           :description AppJobViewDocs
           (ok (coerce! AppJobView
                        (apps/get-app-job-view current-user system-id app-id))))

      (DELETE "/" []
              :query [params SecuredQueryParams]
              :summary AppDeleteSummary
              :description AppDeleteDocs
              (ok (apps/delete-app current-user system-id app-id)))

      (PATCH "/" []
             :query [params SecuredQueryParamsEmailRequired]
             :body [body AppLabelUpdateRequest]
             :return App
             :summary AppLabelUpdateSummary
             :description-file "docs/apps/app-label-update.md"
             (ok (apps/relabel-app current-user system-id (assoc body :id app-id))))

      (PUT "/" []
           :query [params SecuredQueryParamsEmailRequired]
           :body [body AppUpdateRequest]
           :return App
           :summary AppUpdateSummary
           :description-file "docs/apps/app-update.md"
           (ok (apps/update-app current-user system-id (assoc body :id app-id))))

      (POST "/copy" []
            :query [params SecuredQueryParamsRequired]
            :return App
            :summary AppCopySummary
            :description AppCopyDocs
            (ok (apps/copy-app current-user system-id app-id)))

      (GET "/details" []
           :query [params SecuredQueryParams]
           :return AppDetails
           :summary AppDetailsSummary
           :description-file "docs/apps/app-details.md"
           (ok (coerce! AppDetails
                        (apps/get-app-details current-user system-id app-id))))

      (GET "/documentation" []
           :query [params SecuredQueryParams]
           :return AppDocumentation
           :summary AppDocumentationSummary
           :description AppDocumentationDocs
           (ok (coerce! AppDocumentation
                        (apps/get-app-docs current-user system-id app-id))))

      (PATCH "/documentation" []
             :query [params SecuredQueryParamsEmailRequired]
             :body [body AppDocumentationRequest]
             :return AppDocumentation
             :summary AppDocumentationUpdateSummary
             :description AppDocumentationUpdateDocs
             (ok (coerce! AppDocumentation
                          (apps/owner-edit-app-docs current-user system-id app-id body))))

      (POST "/documentation" []
            :query [params SecuredQueryParamsEmailRequired]
            :body [body AppDocumentationRequest]
            :return AppDocumentation
            :summary AppDocumentationAddSummary
            :description AppDocumentationAddDocs
            (ok (coerce! AppDocumentation
                         (apps/owner-add-app-docs current-user system-id app-id body))))

      (DELETE "/favorite" []
              :query [params SecuredQueryParams]
              :summary AppFavoriteDeleteSummary
              :description AppFavoriteDeleteDocs
              (ok (apps/remove-app-favorite current-user system-id app-id)))

      (PUT "/favorite" []
           :query [params SecuredQueryParams]
           :summary AppFavoriteAddSummary
           :description AppFavoriteAddDocs
           (ok (apps/add-app-favorite current-user system-id app-id)))

      (GET "/integration-data" []
           :query [params SecuredQueryParams]
           :return IntegrationData
           :summary AppIntegrationDataSummary
           :description AppIntegrationDataDocs
           (ok (apps/get-app-integration-data current-user system-id app-id)))

      (GET "/is-publishable" []
           :query [params SecuredQueryParams]
           :return AppPublishableResponse
           :summary AppPublishableSummary
           :description AppPublishableDocs
           (ok (apps/app-publishable? current-user system-id app-id)))

      (POST "/publish" []
            :query [params SecuredQueryParamsEmailRequired]
            :body [body PublishAppRequest]
            :summary PublishAppSummary
            :description PublishAppDocs
            (ok (apps/make-app-public current-user system-id (assoc body :id app-id))))

      (DELETE "/rating" []
              :query [params SecuredQueryParams]
              :return RatingResponse
              :summary AppRatingDeleteSummary
              :description AppRatingDeleteDocs
              (ok (apps/delete-app-rating current-user system-id app-id)))

      (POST "/rating" []
            :query [params SecuredQueryParams]
            :body [body RatingRequest]
            :return RatingResponse
            :summary AppRatingSummary
            :description AppRatingDocs
            (ok (apps/rate-app current-user system-id app-id body)))

      (GET "/tasks" []
           :query [params SecuredQueryParams]
           :return AppTaskListing
           :summary AppTaskListingSummary
           :description AppTaskListingDocs
           (ok (coerce! AppTaskListing
                        (apps/get-app-task-listing current-user system-id app-id))))

      (GET "/tools" []
           :query [params SecuredQueryParams]
           :return AppToolListing
           :summary AppToolListingSummary
           :description AppToolListingDocs
           (ok (coerce! AppToolListing
                        (apps/get-app-tool-listing current-user system-id app-id))))

      (GET "/ui" []
           :query [params SecuredQueryParamsEmailRequired]
           :return App
           :summary AppEditingViewSummary
           :description AppEditingViewDocs
           (ok (apps/get-app-ui current-user system-id app-id))))))
