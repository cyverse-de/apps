(ns apps.routes.apps.pipelines
  (:require
   [apps.routes.params :refer [SecuredQueryParamsEmailRequired SecuredQueryParamsRequired]]
   [apps.service.apps :as apps]
   [apps.user :refer [current-user]]
   [apps.util.coercions :refer [coerce!]]
   [common-swagger-api.schema :refer [context defroutes GET POST PUT]]
   [common-swagger-api.schema.apps :refer [AppIdParam AppVersionIdParam]]
   [common-swagger-api.schema.apps.pipeline
    :refer [Pipeline
            PipelineCopyDocs
            PipelineCopySummary
            PipelineCreateDocs
            PipelineCreateRequest
            PipelineCreateSummary
            PipelineEditingViewDocs
            PipelineEditingViewSummary
            PipelineUpdateDocs
            PipelineUpdateRequest
            PipelineUpdateSummary
            PipelineVersionCopyDocs
            PipelineVersionCopySummary
            PipelineVersionCreateDocs
            PipelineVersionCreateSummary
            PipelineVersionEditingViewDocs
            PipelineVersionEditingViewSummary
            PipelineVersionRequest
            PipelineVersionUpdateDocs
            PipelineVersionUpdateSummary]]
   [ring.util.http-response :refer [ok]]))

(defroutes pipelines
  (POST "/" []
    :query [params SecuredQueryParamsRequired]
    :body [body PipelineCreateRequest]
    :return Pipeline
    :summary PipelineCreateSummary
    :description PipelineCreateDocs
    (ok (coerce! Pipeline
                 (apps/add-pipeline current-user body))))

  (context "/:app-id" []
    :path-params [app-id :- AppIdParam]

    (PUT "/" []
      :query [params SecuredQueryParamsEmailRequired]
      :body [body PipelineUpdateRequest]
      :return Pipeline
      :summary PipelineUpdateSummary
      :description PipelineUpdateDocs
      (ok (coerce! Pipeline
                   (apps/update-pipeline current-user (assoc body :id app-id)))))

    (POST "/copy" []
      :query [params SecuredQueryParamsRequired]
      :return Pipeline
      :summary PipelineCopySummary
      :description PipelineCopyDocs
      (ok (coerce! Pipeline
                   (apps/copy-pipeline current-user app-id))))

    (GET "/ui" []
      :query [params SecuredQueryParamsEmailRequired]
      :return Pipeline
      :summary PipelineEditingViewSummary
      :description PipelineEditingViewDocs
      (ok (coerce! Pipeline
                   (apps/edit-pipeline current-user app-id))))

    (context "/versions" []

             (POST "/" []
                   :query [params SecuredQueryParamsRequired]
                   :body [body PipelineVersionRequest]
                   :return Pipeline
                   :summary PipelineVersionCreateSummary
                   :description PipelineVersionCreateDocs
                   (ok (coerce! Pipeline
                                (apps/add-pipeline-version current-user
                                                           (assoc body :id app-id)
                                                           false))))

             (context "/:version-id" []
                      :path-params [version-id :- AppVersionIdParam]

                      (PUT "/" []
                           :query [params SecuredQueryParamsEmailRequired]
                           :body [body PipelineUpdateRequest]
                           :return Pipeline
                           :summary PipelineVersionUpdateSummary
                           :description PipelineVersionUpdateDocs
                           (ok (coerce! Pipeline
                                        (apps/update-pipeline current-user (assoc body
                                                                             :id app-id
                                                                             :version_id version-id)))))

                      (POST "/copy" []
                            :query [params SecuredQueryParamsRequired]
                            :return Pipeline
                            :summary PipelineVersionCopySummary
                            :description PipelineVersionCopyDocs
                            (ok (coerce! Pipeline
                                         (apps/copy-pipeline-version current-user app-id version-id))))

                      (GET "/ui" []
                           :query [params SecuredQueryParamsEmailRequired]
                           :return Pipeline
                           :summary PipelineVersionEditingViewSummary
                           :description PipelineVersionEditingViewDocs
                           (ok (coerce! Pipeline
                                        (apps/edit-pipeline current-user app-id version-id))))))))
