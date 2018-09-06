(ns apps.service.callbacks
  (:require [apps.persistence.jobs :as jp]
            [apps.service.apps :as apps]
            [apps.util.service :as service]
            [clojure.string :as string]
            [clojure.tools.logging :as log]))

(defn update-de-job-status
  [{:keys [uuid]}]
  (service/assert-valid uuid "no job UUID provided")
  (log/info (str "received a status update for DE job " uuid))
  (apps/update-job-status uuid))

(defn update-agave-job-status
  [job-id last-updated {:keys [status external-id end-time]}]
  (service/assert-valid job-id "no job UUID provided")
  (service/assert-valid status "no status provided")
  (service/assert-valid external-id "no external job ID provided")
  (log/info (str "received a status update for Agave job " external-id ": status = " status))
  (apps/update-job-status job-id external-id status (first (remove string/blank? [end-time last-updated]))))
