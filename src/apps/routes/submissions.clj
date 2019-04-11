(ns apps.routes.submissions
  (:use [common-swagger-api.schema]
        [common-swagger-api.schema.apps :only [AppJobView]]
        [apps.routes.params :only [SecuredQueryParams SubmissionIdPathParam]]
        [apps.user :only [current-user]]
        [apps.util.coercions :only [coerce!]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.apps :as apps]))

(defroutes submissions
  (context "/:submission-id" []
    :path-params [submission-id :- SubmissionIdPathParam]

    (GET "/launch-info" []
      :query       [params SecuredQueryParams]
      :return      AppJobView
      :summary     "Obtain information to launch a saved analysis submission."
      :description "Job submissions in the DE can be stored in the DE database for later reference. This endpoint
      retrieves the JSON for the app associated with the submission, and populates it with the parameter values in the
      submission."
      (ok (coerce! AppJobView (apps/get-submission-launch-info current-user submission-id))))))
