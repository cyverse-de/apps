(ns apps.service.apps.jobs.submissions.submit
  (:require
   [apps.clients.permissions :as perms-client]))

(defn submit-and-register-private-job
  "Submits a job and registers it as a private analysis. The permissions
   registration happens before submission to JEX/VICE so that the database
   transaction inside .submitJob is committed before the HTTP call to the
   execution service, which needs the job row to be visible."
  [apps-client user submission]
  (let [job-info (.submitJob apps-client submission)]
    (perms-client/register-private-analysis (:shortUsername user) (:id job-info))
    job-info))
