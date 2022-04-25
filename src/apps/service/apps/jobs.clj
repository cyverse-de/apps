(ns apps.service.apps.jobs
  (:use [apps.util.db :only [transaction]]
        [slingshot.slingshot :only [try+]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-commons.exception-util :as cxu]
            [kameleon.db :as db]
            [apps.clients.notifications :as cn]
            [apps.persistence.jobs :as jp]
            [apps.persistence.submissions :as sp]
            [apps.service.apps.job-listings :as listings]
            [apps.service.apps.jobs.params :as job-params]
            [apps.service.apps.jobs.permissions :as job-permissions]
            [apps.service.apps.jobs.sharing :as job-sharing]
            [apps.service.apps.jobs.resubmit :as resubmit]
            [apps.service.apps.jobs.submissions :as submissions]
            [apps.service.apps.jobs.util :as ju]
            [apps.util.service :as service]))

(defn validate-job-status-update-step-count
  "Validates the number of steps for an external ID provided in a set of job status updates. If there are too many or
  too few matching job steps then all of the job status updates will be marked as propagated and an exception will be
  thrown. This function should not be called from within a transaction."
  [external-id]
  (let [job-steps (jp/get-job-steps-by-external-id external-id)]
    (when (empty? job-steps)
      (jp/mark-job-status-updates-for-external-id-completed external-id)
      (service/not-found "job step" external-id))
    (when (> (count job-steps) 1)
      (jp/mark-job-status-updates-for-external-id-completed external-id)
      (service/not-unique "job step" external-id))))

(defn lock-job-step
  [job-id external-id]
  (service/assert-found (jp/lock-job-step job-id external-id) "job step"
                        (str job-id "/" external-id)))

(defn lock-job
  [job-id]
  (service/assert-found (jp/lock-job job-id) "job" job-id))

(defn- format-job-for-status-update
  [apps-client {job-id :id user-id :user_id}]
  (let [job        (jp/get-job-by-id job-id)
        rep-steps  (jp/list-representative-job-steps [job-id])
        rep-steps  (group-by (some-fn :parent_id :job_id) rep-steps)]
    (assoc (listings/format-job apps-client nil rep-steps job) :user_id user-id)))

(defn- send-job-status-update
  [apps-client {prev-status :status :as original-job} {step-type :job_type :as job-step}]
  (let [{curr-status :status :as job} (format-job-for-status-update apps-client original-job)]
    (when-not (= prev-status curr-status)
      (if (= step-type jp/interactive-job-type)
        (cn/send-interactive-job-status-update (.getUser apps-client) job job-step)
        (cn/send-job-status-update (.getUser apps-client) job)))))

(defn- determine-batch-status
  [{:keys [id]}]
  (let [children (jp/list-child-jobs id)]
    (cond (every? (comp jp/completed? :status) children) jp/completed-status
          (some (comp jp/running? :status) children)     jp/running-status
          :else                                          jp/submitted-status)))

(defn- update-batch-status
  [{current-status :status :as batch} end-date]
  (when-not (jp/completed? current-status)
    (let [new-status (determine-batch-status batch)]
      (when-not (= current-status new-status)
        (jp/update-job (:id batch) {:status new-status :end_date end-date})
        (jp/update-job-steps (:id batch) new-status end-date)))))

(defn- log-spurious-job-update
  [{:keys [id]}]
  (log/warn (str "received a job status update for completed or canceled job, " id)))

(defn- log-spurious-job-step-update
  [{job-id :id} {external-id :external_id}]
  (log/warn (str "received a job status update for completed or canceled step, " external-id "/" job-id)))

(defn- update-job-status*
  [apps-client job-step job batch status end-date]
  (let [end-date (db/timestamp-from-str end-date)]
    (.updateJobStatus apps-client job-step job status end-date)
    (when batch (update-batch-status batch end-date))
    (send-job-status-update apps-client (or batch job) job-step)
    (first (jp/get-job-steps-by-external-id (:external_id job-step)))))

(defn update-job-status
  [apps-client job-step job batch status end-date]
  (cond
    (jp/completed? (:status job))      (do (log-spurious-job-update job) job-step)
    (jp/completed? (:status job-step)) (do (log-spurious-job-step-update job job-step) job-step)
    :else                              (update-job-status* apps-client job-step job batch status end-date)))

(defn- find-incomplete-job-steps
  [job-id]
  (remove (comp jp/completed? :status) (jp/list-job-steps job-id)))

(defn- validate-jobs-for-user
  [user job-ids required-permission]
  (ju/validate-job-existence job-ids)
  (job-permissions/validate-job-permissions user required-permission job-ids))

(defn update-job
  [user job-id body]
  (validate-jobs-for-user user [job-id] "write")
  (jp/update-job job-id body)
  (->> (jp/get-job-by-id job-id)
       ((juxt :id :job_name :description))
       (zipmap [:id :name :description])))

(defn get-parameter-values
  [apps-client user job-id]
  (validate-jobs-for-user user [job-id] "read")
  (let [job (jp/get-job-by-id job-id)]
    {:app_id     (:app_id job)
     :system_id  (:system_id job)
     :parameters (job-params/get-parameter-values apps-client job)}))

(defn get-job-relaunch-info
  [apps-client user job-id]
  (validate-jobs-for-user user [job-id] "read")
  (job-params/get-job-relaunch-info apps-client user (jp/get-job-by-id job-id)))

(defn get-submission-launch-info
  [apps-client user submission-id]
  (if-let [submission (sp/get-submission-by-id submission-id)]
    (job-params/get-submission-launch-info apps-client user submission)
    (cxu/not-found "submission information not found")))

(defn relaunch-jobs
  [apps-client user job-ids]
  (validate-jobs-for-user user job-ids "read")
  (resubmit/resubmit-jobs apps-client user (map jp/get-job-by-id job-ids))
  nil)

(defn- stop-job-steps
  "Stops an individual step in a job."
  [apps-client status-to-set {:keys [id] :as job} steps notify?]
  (.stopJobStep apps-client (first steps))
  (jp/cancel-job-step-numbers id (mapv :step_number steps))
  (jp/add-job-status-update (:external_id (first steps)) "Job being stopped" status-to-set)
  (when notify?
    (send-job-status-update apps-client job (first steps))))

(defn- stop-single-job
  [apps-client status-to-set {job-id :id :as job} notify?]
  (jp/update-job job-id status-to-set (db/now))
  (try+
   (stop-job-steps apps-client status-to-set job (find-incomplete-job-steps job-id) notify?)
   (catch Throwable t
     (log/warn t "unable to cancel the most recent step of job, " job-id))
   (catch Object _
     (log/warn "unable to cancel the most recent step of job, " job-id))))

(defn stop-child-job
  [apps-client status-to-set {job-id :id}]
  (transaction
   (let [{:keys [status] :as job} (jp/get-job-by-id job-id)]
     (when (jp/not-completed? status)
       (stop-single-job apps-client status-to-set job false)
       status-to-set))))

(defn- stop-parent-job
  [apps-client parent-id sub-job-count]
  (transaction
   (let [user       (.getUser apps-client)
         parent-job (format-job-for-status-update apps-client (lock-job parent-id))
         message    (format "%s %d analyses %s"
                            (:name parent-job)
                            sub-job-count
                            (string/lower-case jp/canceled-status))]
     (cn/send-job-status-update (:shortUsername user) (:email user) parent-job message)
     (log/info "batch job cancellation complete:" sub-job-count "stopped."))))

(defn- stop-batch-jobs-thread
  [apps-client status-to-set parent-id]
  (try+
   (log/info "batch job cancellation starting...")
    ;; Re-list running children after the parent has been cancelled, to catch any new submissions.
   (let [children         (jp/list-running-child-jobs parent-id)
         stopped-sub-jobs (->> children
                               (map (partial stop-child-job apps-client status-to-set))
                               (remove nil?))]
     (stop-parent-job apps-client parent-id (count stopped-sub-jobs)))
   (catch Object _
     (log/error (:throwable &throw-context)
                "unable to cancel batch jobs," parent-id))))

(defn- async-stop-batch-jobs
  [apps-client status-to-set parent-id]
  (let [^Runnable target #(stop-batch-jobs-thread apps-client status-to-set parent-id)]
    (.start (Thread. target (str "batch_stop_" parent-id)))))

(defn- stop-job*
  [apps-client user job-id status-to-set]
  (let [{:keys [status] :as job} (jp/get-job-by-id job-id)
        running-children (jp/list-running-child-jobs job-id)]
    (when-not (and (jp/completed? status) (empty? running-children))

      ;; A parent job should be stopped right away, to prevent further child jobs from submitting.
      (stop-single-job apps-client status-to-set job true)
      (if (not (empty? running-children))
        (async-stop-batch-jobs apps-client status-to-set job-id)))))

(defn stop-job
  [apps-client user job-id status-to-set]
  (validate-jobs-for-user user [job-id] "write")
  (stop-job* apps-client user job-id status-to-set))

(defn delete-jobs
  [apps-client user job-ids]
  (validate-jobs-for-user user job-ids "write")
  (doseq [job-id job-ids]
    (stop-job* apps-client user job-id jp/canceled-status))
  (jp/delete-jobs job-ids))

(defn delete-job
  [apps-client user job-id]
  (delete-jobs apps-client user [job-id]))

(defn get-job-history
  [apps-client user job-id]
  (validate-jobs-for-user user [job-id] "read")
  (listings/get-job-history apps-client job-id))

(defn list-job-steps
  [user job-id]
  (validate-jobs-for-user user [job-id] "read")
  (listings/list-job-steps job-id))

(defn submit
  [apps-client user submission]
  (submissions/submit apps-client user submission))

(defn list-job-permissions
  [apps-client user job-ids params]
  (job-permissions/list-job-permissions apps-client user job-ids params))

(defn validate-job-sharing-request-body
  [apps-client user sharing-requests]
  (job-sharing/validate-job-sharing-request-body apps-client user sharing-requests))

(defn share-jobs
  [apps-client user sharing-requests]
  (job-sharing/share-jobs apps-client user sharing-requests))

(defn validate-job-unsharing-request-body
  [apps-client user unsharing-requests]
  (job-sharing/validate-job-unsharing-request-body apps-client user unsharing-requests))

(defn unshare-jobs
  [apps-client user unsharing-requests]
  (job-sharing/unshare-jobs apps-client user unsharing-requests))
