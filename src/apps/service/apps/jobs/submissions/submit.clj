(ns apps.service.apps.jobs.submissions.submit
  (:require
   [apps.clients.permissions :as perms-client]
   [apps.util.db :refer [transaction]]))

(defn submit-and-register-private-job
  [apps-client user submission]
  (transaction
   (let [job-info (.submitJob apps-client submission)]
     (perms-client/register-private-analysis (:shortUsername user) (:id job-info))
     job-info)))
