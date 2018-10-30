(ns apps.service.apps.jobs
  (:use [korma.db :only [transaction]]
        [slingshot.slingshot :only [try+]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [kameleon.db :as db]
            [apps.clients.notifications :as cn]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.job-listings :as listings]
            [apps.service.apps.jobs.params :as job-params]
            [apps.service.apps.jobs.permissions :as job-permissions]
            [apps.service.apps.jobs.sharing :as job-sharing]
            [apps.service.apps.jobs.submissions :as submissions]
            [apps.service.apps.jobs.util :as ju]
            [apps.util.service :as service]))

(defn validate-job-status-update-step-count
  "Validates the number of steps for an external ID provided in a set of job status updates. If there are too many or
  too few matching job steps then all of the job status updates will be marked as propagated and an exception will be
  thrown."
  [external-id updates job-steps]
  (when (empty? job-steps)
    (jp/mark-job-status-updates-propagated (mapv :id updates))
    (service/not-found "job step" external-id))
  (when (> (count job-steps) 1)
    (jp/mark-job-status-updates-propagated (mapv :id updates))
    (service/not-unique "job step" external-id)))

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
        app-tables (.loadAppTables apps-client [job])
        rep-steps  (jp/list-representative-job-steps [job-id])
        rep-steps  (group-by (some-fn :parent_id :job_id) rep-steps)]
    (assoc (listings/format-job apps-client nil app-tables rep-steps job) :user_id user-id)))

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
  [batch end-date]
  (let [new-status (determine-batch-status batch)]
    (when-not (= (:status batch) new-status)
      (jp/update-job (:id batch) {:status new-status :end_date end-date})
      (jp/update-job-steps (:id batch) new-status end-date))))

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
    (send-job-status-update apps-client (or batch job) job-step)))

(defn update-job-status
  [apps-client job-step job batch status end-date]
  (cond
    (jp/completed? (:status job))      (log-spurious-job-update job)
    (jp/completed? (:status job-step)) (log-spurious-job-step-update job job-step)
    :else                              (update-job-status* apps-client job-step job batch status end-date)))

(defn- find-incomplete-job-steps
  [job-id]
  (remove (comp jp/completed? :status) (jp/list-job-steps job-id)))

(defn- sync-incomplete-job-status
  [apps-client {:keys [id] :as job} step]
  (if-let [step-status (.getJobStepStatus apps-client step)]
    (let [step     (lock-job-step id (:external_id step))
          job      (lock-job id)
          batch    (when-let [parent-id (:parent_id job)] (lock-job parent-id))
          status   (:status step-status)
          end-date (:enddate step-status)]
      (update-job-status apps-client step job batch status end-date))
    (let [step  (lock-job-step id (:external_id step))
          job   (lock-job id)
          batch (when-let [parent-id (:parent_id job)] (lock-job parent-id))]
      (update-job-status apps-client step job batch jp/failed-status (db/now-str)))))

(defn- determine-job-status
  "Determines the status of a job for synchronization in the case when all job steps are
   marked as being in one of the completed statuses but the job itself is not."
  [job-id]
  (let [statuses (map :status (jp/list-job-steps job-id))
        status   (first (filter (partial not= jp/completed-status) statuses))]
    (cond (nil? status)                 jp/completed-status
          (= jp/canceled-status status) status
          (= jp/failed-status status)   status
          :else                         jp/failed-status)))

(defn- sync-complete-job-status
  [{:keys [id]}]
  (let [{:keys [status]} (jp/lock-job id)]
    (when-not (jp/completed? status)
      (jp/update-job id {:status (determine-job-status id) :end_date (db/now)}))))

(defn sync-job-status
  [apps-client {:keys [id] :as job}]
  (if-let [step (first (find-incomplete-job-steps id))]
    (sync-incomplete-job-status apps-client job step)
    (sync-complete-job-status job)))

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

(defn delete-job
  [user job-id]
  (validate-jobs-for-user user [job-id] "write")
  (jp/delete-jobs [job-id]))

(defn delete-jobs
  [user job-ids]
  (validate-jobs-for-user user job-ids "write")
  (jp/delete-jobs job-ids))

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
  (job-params/get-job-relaunch-info apps-client (jp/get-job-by-id job-id)))

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

(defn stop-job
  [apps-client user job-id status-to-set]
  (validate-jobs-for-user user [job-id] "write")
  (let [{:keys [status] :as job} (jp/get-job-by-id job-id)
        running-children (jp/list-running-child-jobs job-id)]
    (when (and (jp/completed? status) (empty? running-children))
      (service/bad-request (str "job, " job-id ", is already completed or canceled")))

    ;; A parent job should be stopped right away, to prevent further child jobs from submitting.
    (stop-single-job apps-client status-to-set job true)
    (if (not (empty? running-children))
      (async-stop-batch-jobs apps-client status-to-set job-id))))

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

(defn share-jobs
  [apps-client user sharing-requests]
  (job-sharing/share-jobs apps-client user sharing-requests))

(defn unshare-jobs
  [apps-client user unsharing-requests]
  (job-sharing/unshare-jobs apps-client user unsharing-requests))
