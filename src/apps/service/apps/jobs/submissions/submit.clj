(ns apps.service.apps.jobs.submissions.submit
  (:use [korma.db :only [transaction]])
  (:require [apps.clients.permissions :as perms-client]))

(defn submit-and-register-private-job
  [apps-client user submission]
  (transaction
    (let [job-info (.submitJob apps-client submission)]
      (perms-client/register-private-analysis (:shortUsername user) (:id job-info))
      job-info)))
