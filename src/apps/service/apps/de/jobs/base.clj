(ns apps.service.apps.de.jobs.base
  (:require [apps.metadata.params :as mp]
            [apps.persistence.app-metadata :as ap]
            [apps.service.apps.de.jobs.common :as ca]
            [apps.service.apps.de.jobs.condor]
            [apps.service.apps.de.jobs.protocol]))

(defn- build-job-request-formatter
  [user submission]
  (let [email      (:email user)
        app-id     (:app_id submission)
        app        (ap/get-app app-id)
        version-id (:version_id app)
        io-maps    (ca/load-io-maps version-id)
        params     (mp/load-app-params version-id)
        defaults   (ca/build-default-values-map params)
        params     (group-by :step_id params)]
    (apps.service.apps.de.jobs.condor.JobRequestFormatter.
     user email submission app io-maps defaults params)))

(defn build-submission
  [user submission]
  (.buildSubmission (build-job-request-formatter user submission)))
