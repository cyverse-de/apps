(ns apps.service.apps.jobs.submissions.submit
  (:require
   [apps.clients.permissions :as perms-client]))

(defn submit-and-register-private-job
  "Submits a job for execution and calls the `permissions` service to record permissions."
  [apps-client user submission]
  (let [job-info (.submitJob apps-client submission)]
    (perms-client/register-private-analysis (:shortUsername user) (:id job-info))
    job-info))
