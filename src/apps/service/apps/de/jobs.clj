(ns apps.service.apps.de.jobs
  (:use [apps.persistence.users :only [get-user-id]]
        [apps.util.conversions :only [remove-nil-vals]]
        [clojure-commons.file-utils :only [build-result-folder-path]]
        [korma.core :only [sqlfn]]
        [korma.db :only [transaction]]
        [medley.core :only [dissoc-in]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [cheshire.core :as cheshire]
            [clojure.tools.logging :as log]
            [kameleon.db :as db]
            [apps.clients.jex :as jex]
            [apps.persistence.app-metadata :as ap]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.de.jobs.base :as jb]
            [apps.util.json :as json-util]))

(defn- pre-process-jex-step
  "Removes the input array of a fAPI step's config."
  [{{step-type :type} :component :as step}]
  (if (= step-type "fAPI")
    (dissoc-in step [:config :input])
    step))

(defn- pre-process-jex-submission
  "Finalizes the job for submission to the JEX."
  [job]
  (update-in job [:steps] (partial map pre-process-jex-step)))

(defn- do-jex-submission
  [job]
  (try+
   (jex/submit-job (pre-process-jex-submission job))
   (catch Object _
     (log/error (:throwable &throw-context) "job submission failed")
     (throw+ {:type  :clojure-commons.exception/request-failed
              :error "job submission failed"}))))

(defn- store-submitted-job
  "Saves information about a job in the database."
  [user job submission status]
  (-> (select-keys job [:app_id :app_name :app_description :notify])
      (assoc :job_name           (:name job)
             :job_description    (:description job)
             :system_id          (:system_id submission)
             :app_wiki_url       (:wiki_url job)
             :result_folder_path (:output_dir job)
             :start_date         (sqlfn now)
             :username           (:username user)
             :status             status
             :parent_id          (:parent_id submission))
      (jp/save-job (cheshire/encode submission))))

(defn- store-job-step
  "Saves a single job step in the database."
  [job-id job status]
  (jp/save-job-step {:job_id          job-id
                     :step_number     1
                     :external_id     (:uuid job)
                     :start_date      (sqlfn now)
                     :status          status
                     :job_type        jp/de-job-type
                     :app_step_number 1}))

(defn- save-job-submission
  "Saves a DE job and its job-step in the database."
  ([user job submission]
     (save-job-submission user job submission "Submitted"))
  ([user job submission status]
     (transaction
      (let [job-id (:id (store-submitted-job user job submission status))]
        (store-job-step job-id job status)
        job-id))))

(defn- format-job-submission-response
  [user jex-submission batch? job-id]
  (remove-nil-vals
   {:app_description (:app_description jex-submission)
    :app_disabled    false
    :app_id          (:app_id jex-submission)
    :app_name        (:app_name jex-submission)
    :batch           batch?
    :description     (:description jex-submission)
    :system_id       "de"
    :id              (str job-id)
    :name            (:name jex-submission)
    :notify          (:notify jex-submission)
    :resultfolderid  (:output_dir jex-submission)
    :startdate       (str (.getTime (db/now)))
    :status          jp/submitted-status
    :username        (:username user)
    :wiki_url        (:wiki_url jex-submission)}))

(defn- submit-job-in-batch
  [user submission job]
  (->> (try+
        (do-jex-submission job)
        (save-job-submission user job submission)
        (catch Object _
          (save-job-submission user job submission "Failed")))
       (format-job-submission-response user job true)))

(defn- submit-standalone-job
  [user submission job]
  (do-jex-submission job)
  (->> (save-job-submission user job submission)
       (format-job-submission-response user job false)))

(defn- submit-job
  [user submission job]
  (if (:parent_id submission)
    (submit-job-in-batch user submission job)
    (submit-standalone-job user submission job)))

(defn- prep-submission
  [submission]
  (assoc submission
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
  [user submission]
  (let [job-step (build-submission user submission)]
    (json-util/log-json "job step" job-step)
    (do-jex-submission job-step)
    (:uuid job-step)))

(defn update-job-status
  [{:keys [external_id] :as job-step} {job-id :id :as job} status end-date]
  (when (jp/status-follows? status (:status job-step))
    (jp/update-job-step job-id external_id status end-date)
    (jp/update-job job-id status end-date)))

(defn get-default-output-name
  [{output-id :output_id :as io-map} {task-id :task_id :as source-step}]
  (ap/get-default-output-name task-id output-id))

(defn get-job-step-status
  [{:keys [external_id]}]
  (when-let [step (jp/get-job-state external_id)]
    {:status  (:status step)
     :enddate (:completion_date step)}))

(defn prepare-step
  [user submission]
  (build-submission user submission))
