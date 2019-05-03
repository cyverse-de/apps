(ns apps.routes.analyses
  (:use [apps.routes.params
         :only [AnalysisListingParams
                FilterParams
                SecuredQueryParams
                SecuredQueryParamsEmailRequired]]
        [apps.routes.schemas.analysis]
        [apps.routes.schemas.analysis.listing]
        [apps.routes.schemas.app]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps :only [AppJobView]]
        [ring.util.http-response :only [ok]])
  (:require [apps.json :as json]
            [apps.routes.schemas.permission :as perms]
            [apps.service.apps :as apps]
            [apps.util.coercions :as coercions]
            [common-swagger-api.schema.analyses :as schema]
            [common-swagger-api.schema.analyses.listing :as listing-schema]
            [common-swagger-api.schema.apps.permission :as perms-schema]))

(defroutes analyses
  (GET "/" []
        :query   [{:keys [filter] :as params} AnalysisListingParams]
        :return  listing-schema/AnalysisList
        :summary "List Analyses"
        :description "This service allows users to list analyses that they've previously submitted
        for execution."
        ;; JSON query params are not currently supported by compojure-api,
        ;; so we have to decode the String filter param and validate it here.
        (ok (coerce! listing-schema/AnalysisList
                 (apps/list-jobs current-user
                   (coercions/coerce!
                     (assoc AnalysisListingParams listing-schema/OptionalKeyFilter [FilterParams])
                     (assoc params :filter (json/from-json filter)))))))

  (POST "/" []
         :query   [params SecuredQueryParamsEmailRequired]
         :body    [body schema/AnalysisSubmission]
         :return  schema/AnalysisResponse
         :summary "Submit an Analysis"
         :description   "This service allows users to submit analyses for execution. The `config`
         element in the analysis submission is a map from parameter IDs as they appear in
         the response from the `/apps/:app-id` endpoint to the desired values for those
         parameters."
         (ok (coerce! schema/AnalysisResponse
                  (apps/submit-job current-user body))))

  (POST "/permission-lister" []
         :query [params perms/PermissionListerQueryParams]
         :body [body (describe perms-schema/AnalysisIdList "The analysis permission listing request.")]
         :return perms-schema/AnalysisPermissionListing
         :summary "List App Permissions"
         :description "This endpoint allows the caller to list the permissions for one or more analyses.
         The authenticated user must have read permission on every analysis in the request body for this
         endpoint to succeed."
         (ok (apps/list-job-permissions current-user (:analyses body) params)))

  (POST "/sharing" []
         :query [params SecuredQueryParams]
         :body [body (describe perms-schema/AnalysisSharingRequest "The analysis sharing request.")]
         :return perms-schema/AnalysisSharingResponse
         :summary "Add Analysis Permissions"
         :description "This endpoint allows the caller to share multiple analyses with multiple users. The
         authenticated user must have ownership permission to every analysis in the request body for this
         endpoint to fully succeed. Note: this is a potentially slow operation and the response is returned
         synchronously. The DE UI handles this by allowing the user to continue working while the request is
         being processed. When calling this endpoint, please be sure that the response timeout is long
         enough. Using a response timeout that is too short will result in an exception on the client side.
         On the server side, the result of the sharing operation when a connection is lost is undefined. It
         may be worthwhile to repeat failed or timed out calls to this endpoint."
         (ok (apps/share-jobs current-user (:sharing body))))

  (POST "/unsharing" []
         :query [params SecuredQueryParams]
         :body [body (describe perms-schema/AnalysisUnsharingRequest "The analysis unsharing request.")]
         :return perms-schema/AnalysisUnsharingResponse
         :summary "Revoke Analysis Permissions"
         :description "This endpoint allows the caller to revoke permission to access one or more analyses from
         one or more users. The authenticate user must have ownership permission to every analysis in the request
         body for this endoint to fully succeed. Note: like analysis sharing, this is a potentially slow
         operation."
         (ok (apps/unshare-jobs current-user (:unsharing body))))

  (PATCH "/:analysis-id" []
          :path-params [analysis-id :- schema/AnalysisIdPathParam]
          :query       [params SecuredQueryParams]
          :body        [body listing-schema/AnalysisUpdate]
          :return      listing-schema/AnalysisUpdateResponse
          :summary     "Update an Analysis"
          :description       "This service allows an analysis name or description to be updated."
          (ok (coerce! listing-schema/AnalysisUpdateResponse
                   (apps/update-job current-user analysis-id body))))

  (DELETE "/:analysis-id" []
           :path-params [analysis-id :- schema/AnalysisIdPathParam]
           :query       [params SecuredQueryParams]
           :summary     "Delete an Analysis"
           :description       "This service marks an analysis as deleted in the DE database."
           (ok (apps/delete-job current-user analysis-id)))

  (GET "/:analysis-id/history" []
         :path-params [analysis-id :- schema/AnalysisIdPathParam]
         :query       [params SecuredQueryParams]
         :return      listing-schema/AnalysisHistory
         :summary     "Get the Status Update History of an Analysis"
         :description "This endpoint returns a status update history for each step in an analysis."
         (ok (apps/get-job-history current-user analysis-id)))

  (POST "/shredder" []
         :query   [params SecuredQueryParams]
         :body    [body schema/AnalysisShredderRequest]
         :summary "Delete Multiple Analyses"
         :description   "This service allows the caller to mark one or more analyses as deleted
         in the apps database."
         (ok (apps/delete-jobs current-user body)))

  (GET "/:analysis-id/parameters" []
        :path-params [analysis-id :- schema/AnalysisIdPathParam]
        :query       [params SecuredQueryParams]
        :return      schema/AnalysisParameters
        :summary     "Display the parameters used in an analysis."
        :description       "This service returns a list of parameter values used in a previously
        executed analysis."
        (ok (coerce! schema/AnalysisParameters
                 (apps/get-parameter-values current-user analysis-id))))

  (GET "/:analysis-id/relaunch-info" []
        :path-params [analysis-id :- schema/AnalysisIdPathParam]
        :query       [params SecuredQueryParams]
        :return      AppJobView
        :summary     "Obtain information to relaunch analysis."
        :description       "This service allows the Discovery Environment user interface to obtain an
        app description that can be used to relaunch a previously submitted job, possibly with
        modified parameter values."
        (ok (coerce! AppJobView
                 (apps/get-job-relaunch-info current-user analysis-id))))

  (POST "/:analysis-id/stop" []
         :path-params [analysis-id :- schema/AnalysisIdPathParam]
         :query       [params StopAnalysisRequest]
         :return      schema/StopAnalysisResponse
         :summary     "Stop a running analysis."
         :description       "This service allows DE users to stop running analyses."
         (ok (coerce! schema/StopAnalysisResponse
                  (apps/stop-job current-user analysis-id params))))

  (GET "/:analysis-id/steps" []
        :path-params [analysis-id :- schema/AnalysisIdPathParam]
        :query       [params SecuredQueryParams]
        :return      listing-schema/AnalysisStepList
        :summary     "Display the steps of an analysis."
        :description "This service returns a list of steps in an analysis."
        (ok (apps/list-job-steps current-user analysis-id))))
