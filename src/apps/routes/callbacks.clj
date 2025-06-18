(ns apps.routes.callbacks
  (:require
   [apps.routes.schemas.callback :refer [DeJobStatusUpdate]]
   [apps.service.callbacks :as callbacks]
   [common-swagger-api.schema :refer [defroutes describe POST]]
   [common-swagger-api.schema.analyses :refer [AnalysisIdPathParam]]
   [common-swagger-api.schema.callbacks :refer [TapisJobStatusUpdate]]
   [ring.util.http-response :refer [ok]]))

(defroutes callbacks
  (POST "/de-job" []
    :body [body (describe DeJobStatusUpdate "The updated job status information.")]
    :summary "Update the status of of a DE analysis."
    :description "The jex-events service calls this endpoint when the status of a DE analysis changes"
    (ok (callbacks/update-de-job-status body)))

  (POST "/tapis-job/:job-id" []
    :path-params [job-id :- AnalysisIdPathParam]
    :body [body (describe TapisJobStatusUpdate "The updated job status information.")]
    :summary "Update the status of an Tapis analysis."
    :description "The DE registers this endpoint as a callback when it submts jobs to Tapis."
    (ok (callbacks/update-tapis-job-status job-id body))))
