(ns apps.service.apps.de.jobs
  (:require
   [apps.clients.jex :as jex]
   [apps.clients.vice :as vice]
   [apps.constants :as c]
   [apps.persistence.app-metadata :as ap]
   [apps.persistence.jobs :as jp]
   [apps.service.apps.de.jobs.base :as jb]
   [apps.service.apps.de.jobs.io-tickets :as io-tickets]
   [apps.util.config :as cfg]
   [apps.util.conversions :refer [remove-nil-vals]]
   [apps.util.db :refer [transaction]]
   [apps.util.json :as json-util]
   [cheshire.core :as cheshire]
   [clojure-commons.file-utils :refer [build-result-folder-path]]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [kameleon.db :as db]
   [korma.core :refer [sqlfn]]
   [slingshot.slingshot :refer [throw+ try+]]))

(defn- record-submission-failure
  "Records a job submission failure with end date and status history.
   Catches and logs any errors to prevent failure recording from
   interfering with the caller."
  [job-id external-id error-message]
  (try
    (let [end-date (db/now)]
      (jp/update-job job-id jp/failed-status end-date)
      (jp/update-job-step job-id external-id jp/failed-status end-date)
      (jp/add-job-status-update external-id
                                (or error-message "Job submission failed")
                                jp/failed-status))
    (catch Exception e
      (log/error e "failed to record submission failure for job" job-id))))

(defn- extract-submission-error
  "Extracts error message and status code from HTTP exception.
   Tries to parse JSON body and extract 'message' field.
   Returns map with :message and :status-code."
  [e]
  (let [body (:body e)
        status-code (:status e)
        reason-phrase (:reason-phrase e)
        message (or (when (string? body)
                      (try
                        (:message (cheshire/parse-string body true))
                        (catch Exception _ nil)))
                    (first (remove str/blank? [body reason-phrase]))
                    "job submission failed")]
    {:message     message
     :status-code (or status-code 500)}))

(defn- do-jex-submission
  "Submits the job to JEX or VICE. On success, returns the job unchanged.
   On failure, records the failure and returns the job with status set to Failed
   and error details added."
  [{job-id :id external-id :uuid :as job}]
  (try+
   (if (and (cfg/vice-k8s-enabled)
            (= (:execution_target job) "interapps"))
     (vice/submit-job job)
     (jex/submit-job job))
   job
   (catch Object e
     (let [{:keys [message status-code]} (extract-submission-error e)]
       (log/error (:throwable &throw-context) (str "job submission failed: " message))
       (record-submission-failure job-id external-id message)
       (assoc job
              :status jp/failed-status
              :error-reason message
              :error-status-code status-code)))))

(defn- store-submitted-job
  "Saves information about a job in the database."
  [user job submission status]
  (-> (select-keys job [:app_id :app_version_id :app_name :app_description :notify])
      (assoc :job_name           (:name job)
             :job_description    (:description job)
             :system_id          (:system_id submission)
             :app_wiki_url       (:wiki_url job)
             :result_folder_path (:output_dir job)
             :start_date         (sqlfn :now)
             :username           (:username user)
             :status             status
             :parent_id          (:parent_id submission))
      (jp/save-job submission)))

(defn- job-type-for-step
  "Determines the job type to use for a single job step."
  [{{tool-type :type} :component}]
  (condp = tool-type
    c/interactive-tool-type jp/interactive-job-type
    c/osg-tool-type         jp/osg-job-type
    jp/de-job-type))

(defn- store-job-step
  "Saves a single job step in the database."
  [job-id job status]
  (jp/save-job-step {:job_id          job-id
                     :step_number     1
                     :external_id     (:uuid job)
                     :start_date      (sqlfn :now)
                     :status          status
                     :job_type        (job-type-for-step (-> job :steps first))
                     :app_step_number 1}))

(defn- save-job-submission
  "Saves a DE job and its job-step in the database. Returns the job map
   with :id and :status merged in."
  ([user job submission]
   (save-job-submission user job submission jp/submitted-status))
  ([user {ticket-map :ticket_map :as job} submission status]
   (transaction
    (try+
     (let [job-id (:id (store-submitted-job user job submission status))]
       (store-job-step job-id job status)
       (jp/record-tickets job-id ticket-map)
       (assoc job :id (str job-id) :status status))
     (catch Object _
       (io-tickets/delete-tickets user ticket-map)
       (throw+))))))

(defn- format-job-submission-response
  [user jex-submission batch? {job-id :id status :status error-reason :error-reason error-status-code :error-status-code}]
  (remove-nil-vals
   {:app_description   (:app_description jex-submission)
    :app_disabled      false
    :app_id            (:app_id jex-submission)
    :app_version_id    (:app_version_id jex-submission)
    :app_name          (:app_name jex-submission)
    :batch             batch?
    :description       (:description jex-submission)
    :system_id         "de"
    :id                (str job-id)
    :name              (:name jex-submission)
    :notify            (:notify jex-submission)
    :resultfolderid    (:output_dir jex-submission)
    :startdate         (str (.getTime (db/now)))
    :status            status
    :error             error-reason
    :error_status_code error-status-code
    :username          (:username user)
    :wiki_url          (:wiki_url jex-submission)}))

(defn- submit-job
  "Submits a job to JEX/VICE. The do-jex-submission function handles errors
   internally and returns the job with updated status if submission failed."
  [user {parent-id :parent_id :as submission} job]
  (as-> (save-job-submission user job submission) saved-job
    (do-jex-submission saved-job)
    (format-job-submission-response user job (boolean parent-id) saved-job)))

(defn- prep-submission
  [{:keys [config] :as submission}]
  (assoc submission
         :config               (:job_config submission config)
         :output_dir           (build-result-folder-path submission)
         :create_output_subdir false))

(defn- build-submission
  [user submission]
  (remove-nil-vals (jb/build-submission user submission)))

(defn submit
  [user submission]
  (->> (prep-submission submission)
       (build-submission user)
       (json-util/log-json "job")
       (submit-job user submission)))

(defn submit-step
  [user job-id submission]
  (let [{ticket-map :ticket_map :as job-step} (build-submission user submission)]
    (json-util/log-json "job step" job-step)
    (try+
     (do-jex-submission job-step)
     (jp/record-tickets job-id ticket-map)
     (catch Object _
       (io-tickets/delete-tickets user ticket-map)
       (jp/mark-tickets-deleted ticket-map)
       (throw+)))
    (:uuid job-step)))

(defn- delete-job-tickets
  [user job-id]
  (let [ticket-map (jp/load-job-ticket-map job-id)]
    (io-tickets/delete-tickets user ticket-map)
    (jp/mark-tickets-deleted ticket-map)))

(defn update-job-status
  [user {:keys [external_id] :as job-step} {job-id :id } status end-date]
  (let [end-date (when (jp/completed? status) end-date)]
    (when (jp/status-follows? status (:status job-step))
      (jp/update-job-step job-id external_id status end-date)
      (jp/update-job job-id status end-date)
      (when end-date (delete-job-tickets user job-id)))))

(defn get-default-output-name
  [{output-id :output_id} {task-id :task_id}]
  (ap/get-default-output-name task-id output-id))

(defn get-job-step-status
  [{:keys [external_id]}]
  (when-let [step (jp/get-job-state external_id)]
    {:status  (:status step)
     :enddate (:completion_date step)}))

(defn get-job-step-history
  [{:keys [external_id]}]
  (for [update (jp/get-job-status-updates external_id)]
    {:status    (:status update)
     :message   (:message update)
     :timestamp (str (:sent_on update))}))
