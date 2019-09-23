(ns apps.routes.apps.pipelines
  (:use [common-swagger-api.schema]
        [common-swagger-api.schema.apps :only [AppIdParam]]
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
                   (apps/edit-pipeline current-user app-id))))))
