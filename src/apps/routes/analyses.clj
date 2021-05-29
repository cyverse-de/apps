(ns apps.routes.analyses
  (:use [apps.routes.params
         :only [AnalysisListingParams
                AnalysisStatParams
                FilterParams
                SecuredQueryParams
                SecuredQueryParamsEmailRequired]]
        [apps.routes.schemas.analysis]
        [apps.routes.schemas.analysis.listing]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [common-swagger-api.routes]                         ;; for :description-file
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps :only [AppJobView]]
        [ring.util.http-response :only [ok bad-request]])
  (:require [apps.json :as json]
            [apps.routes.schemas.permission :as perms]
            [apps.service.apps :as apps]
            [apps.util.coercions :as coercions]
            [common-swagger-api.schema.analyses :as schema]
            [common-swagger-api.schema.analyses.listing :as listing-schema]
            [common-swagger-api.schema.apps.permission :as perms-schema]))

(defroutes analyses
  (GET "/" []
    :query [{:keys [filter] :as params} AnalysisListingParams]
    :return listing-schema/AnalysisList
    :summary listing-schema/AnalysesListingSummary
    :description listing-schema/AnalysesListingDocs
    ;; JSON query params are not currently supported by compojure-api,
    ;; so we have to decode the String filter param and validate it here.
    (ok (coerce! listing-schema/AnalysisList
                 (apps/list-jobs current-user
                                 (coercions/coerce!
                                  (assoc AnalysisListingParams listing-schema/OptionalKeyFilter [FilterParams])
                                  (assoc params :filter (json/from-json filter)))))))
  (GET "/stats" []
      :query [{:keys [filter] :as params} AnalysisStatParams]
      :return schema/AnalysisStats
      :summary schema/AnalysisStatSummary
      :description schema/AnalysisStatDescription
      ;; JSON query params are not currently supported by compojure-api,
      ;; so we have to decode the String filter param and validate it here.
      (ok (coerce! schema/AnalysisStats
                   (apps/list-job-stats current-user
                                   (coercions/coerce!
                                     (assoc AnalysisStatParams listing-schema/OptionalKeyFilter [FilterParams])
                                     (assoc params :filter (json/from-json filter)))))))

  (POST "/" []
    :query [params SecuredQueryParamsEmailRequired]
    :middleware [schema/coerce-analysis-submission-requirements]
    :body [body schema/AnalysisSubmission]
    :return schema/AnalysisResponse
    :summary listing-schema/AnalysisSubmitSummary
    :description listing-schema/AnalysisSubmitDocs
    (ok (coerce! schema/AnalysisResponse
                 (apps/submit-job current-user body))))

  (POST "/permission-lister" []
    :query [params perms/PermissionListerQueryParams]
    :body [body perms-schema/AnalysisIdList]
    :return perms-schema/AnalysisPermissionListing
    :summary perms-schema/AnalysisPermissionListingSummary
    :description perms-schema/AnalysisPermissionListingDocs
    (ok (apps/list-job-permissions current-user (:analyses body) params)))

  (POST "/relauncher" []
    :query [params SecuredQueryParams]
    :body [{:keys [analyses]} schema/AnalysesRelauncherRequest]
    :summary schema/AnalysesRelauncherSummary
    :description-file "docs/analyses/relauncher.md"
    (ok (apps/relaunch-jobs current-user analyses)))

  (POST "/sharing" []
    :query [params SecuredQueryParams]
    :body [body perms-schema/AnalysisSharingRequest]
    :return perms-schema/AnalysisSharingResponse
    :summary perms-schema/AnalysisSharingSummary
    :description perms-schema/AnalysisSharingDocs
    (let [[passed? response-body] (apps/validate-job-sharing-request-body current-user body)]
      (if passed?
        (ok (assoc response-body :asyncTaskID (apps/share-jobs current-user body)))
        (bad-request response-body))))

  (POST "/unsharing" []
    :query [params SecuredQueryParams]
    :body [body perms-schema/AnalysisUnsharingRequest]
    :return perms-schema/AnalysisUnsharingResponse
    :summary perms-schema/AnalysisUnsharingSummary
    :description perms-schema/AnalysisUnsharingDocs
    (let [[passed? response-body] (apps/validate-job-unsharing-request-body current-user body)]
      (if passed?
        (ok (assoc response-body :asyncTaskID (apps/unshare-jobs current-user body)))
        (bad-request response-body))))

  (POST "/shredder" []
    :query [params SecuredQueryParams]
    :body [body schema/AnalysisShredderRequest]
    :summary listing-schema/AnalysesDeleteSummary
    :description listing-schema/AnalysesDeleteDocs
    (ok (apps/delete-jobs current-user body)))

  (PATCH "/:analysis-id" []
    :path-params [analysis-id :- schema/AnalysisIdPathParam]
    :query [params SecuredQueryParams]
    :body [body listing-schema/AnalysisUpdate]
    :return listing-schema/AnalysisUpdateResponse
    :summary listing-schema/AnalysisUpdateSummary
    :description listing-schema/AnalysisUpdateDocs
    (ok (coerce! listing-schema/AnalysisUpdateResponse
                 (apps/update-job current-user analysis-id body))))

  (DELETE "/:analysis-id" []
    :path-params [analysis-id :- schema/AnalysisIdPathParam]
    :query [params SecuredQueryParams]
    :summary listing-schema/AnalysisDeleteSummary
    :description listing-schema/AnalysisDeleteDocs
    (ok (apps/delete-job current-user analysis-id)))

  (GET "/:analysis-id/history" []
    :path-params [analysis-id :- schema/AnalysisIdPathParam]
    :query [params SecuredQueryParams]
    :return listing-schema/AnalysisHistory
    :summary listing-schema/AnalysisHistorySummary
    :description listing-schema/AnalysisHistoryDocs
    (ok (apps/get-job-history current-user analysis-id)))

  (GET "/:analysis-id/parameters" []
    :path-params [analysis-id :- schema/AnalysisIdPathParam]
    :query [params SecuredQueryParams]
    :return schema/AnalysisParameters
    :summary schema/AnalysisParametersSummary
    :description schema/AnalysisParametersDocs
    (ok (coerce! schema/AnalysisParameters
                 (apps/get-parameter-values current-user analysis-id))))

  (GET "/:analysis-id/relaunch-info" []
    :path-params [analysis-id :- schema/AnalysisIdPathParam]
    :query [params SecuredQueryParams]
    :return AppJobView
    :summary schema/AnalysisRelaunchSummary
    :description schema/AnalysisRelaunchDocs
    (ok (coerce! AppJobView
                 (apps/get-job-relaunch-info current-user analysis-id))))

  (GET "/:analysis-id/steps" []
    :path-params [analysis-id :- schema/AnalysisIdPathParam]
    :query [params SecuredQueryParams]
    :return listing-schema/AnalysisStepList
    :summary listing-schema/AnalysisStepsSummary
    :description listing-schema/AnalysisStepsDocs
    (ok (apps/list-job-steps current-user analysis-id)))

  (POST "/:analysis-id/stop" []
    :path-params [analysis-id :- schema/AnalysisIdPathParam]
    :query [params StopAnalysisRequest]
    :return schema/StopAnalysisResponse
    :summary schema/AnalysisStopSummary
    :description schema/AnalysisStopDocs
    (ok (coerce! schema/StopAnalysisResponse
                 (apps/stop-job current-user analysis-id params)))))
