(ns apps.routes.submissions
  (:require
   [apps.routes.params :refer [SecuredQueryParams SubmissionIdPathParam]]
   [apps.service.apps :as apps]
   [apps.user :refer [current-user]]
   [apps.util.coercions :refer [coerce!]]
   [common-swagger-api.schema :refer [context defroutes GET]]
   [common-swagger-api.schema.apps :refer [AppJobView]]
   [ring.util.http-response :refer [ok]]))

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
