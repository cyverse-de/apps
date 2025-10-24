(ns apps.service.apps.jobs.submissions.submit
  (:require
   [apps.clients.permissions :as perms-client]
   [apps.util.db :refer [transaction]]))

(defn submit-and-register-private-job
  [apps-client user params submission]
  (transaction
   (let [submission-with-params (assoc submission :params params)
         job-info (.submitJob apps-client submission-with-params)]
     (perms-client/register-private-analysis (:shortUsername user) (:id job-info))
     job-info)))
