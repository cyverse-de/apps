(ns apps.service.callbacks
  (:require
   [apps.service.apps :as apps]
   [apps.util.service :as service]
   [clojure.string :as string]
   [clojure.tools.logging :as log]))

(defn update-de-job-status
  [{:keys [uuid]}]
  (service/assert-valid uuid "no job UUID provided")
  (log/info (str "received a status update for DE job " uuid))
  (apps/update-job-status uuid))

(defn update-tapis-job-status
  [job-id {{:keys [timestamp type data]} :event}]
  (service/assert-valid job-id "no job UUID provided")
  (service/assert-valid type "no status provided")
  (let [{:keys [jobUuid]} (service/parse-json data)
        ; The `event.type` will be a string like "jobs.JOB_NEW_STATUS.FINISHED"
        status (string/replace type #".*\." "")]
    (service/assert-valid jobUuid "no external job ID provided")
    (log/info (str "received a status update for Tapis job " jobUuid ": type = " type ", status = " status))
    (apps/update-job-status job-id jobUuid status timestamp)))
