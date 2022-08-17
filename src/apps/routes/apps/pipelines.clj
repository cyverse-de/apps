(ns apps.routes.apps.pipelines
  (:use [common-swagger-api.schema]
        [common-swagger-api.schema.apps :only [AppIdParam AppVersionIdParam]]
        [common-swagger-api.schema.apps.pipeline]
        [apps.routes.params :only [SecuredQueryParamsRequired SecuredQueryParamsEmailRequired]]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.apps :as apps]))

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

                      (GET "/ui" []
                           :query [params SecuredQueryParamsEmailRequired]
                           :return Pipeline
                           :summary PipelineVersionEditingViewSummary
                           :description PipelineVersionEditingViewDocs
                           (ok (coerce! Pipeline
                                        (apps/edit-pipeline current-user app-id version-id))))))))
