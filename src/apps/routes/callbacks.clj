(ns apps.routes.callbacks
  (:use [apps.routes.schemas.callback]
        [common-swagger-api.schema]
        [common-swagger-api.schema.analyses :only [AnalysisIdPathParam]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.callbacks :as callbacks]))

(defroutes callbacks
  (POST "/de-job" []
    :body [body (describe DeJobStatusUpdate "The updated job status information.")]
    :summary "Update the status of of a DE analysis."
    :description "The jex-events service calls this endpoint when the status of a DE analysis
         changes"
    (ok (callbacks/update-de-job-status body)))

  (POST "/agave-job/:job-id" []
    :path-params [job-id :- AnalysisIdPathParam]
    :body [body (describe AgaveJobStatusUpdate "The updated job status information.")]
    :query [params AgaveJobStatusUpdateParams]
    :summary "Update the status of an Agave analysis."
    :description "The DE registers this endpoint as a callback when it submts jobs to Agave."
    (ok (callbacks/update-agave-job-status job-id (:lastUpdated body) params))))
