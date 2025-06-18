(ns apps.service.apps.tapis.jobs
  (:require
   [apps.persistence.jobs :as jp]
   [apps.util.config :as config]
   [apps.util.conversions :refer [remove-nil-vals]]
   [apps.util.json :as json-util]
   [cemerick.url :as curl]
   [clojure-commons.file-utils :as ft]
   [kameleon.db :as db]
   [kameleon.uuids :as uuids]
   [slingshot.slingshot :refer [throw+ try+]]))

(defn- build-callback-url
  [id]
  (str (curl/url (config/tapis-callback-base) (str id))))

(defn- format-submission
  [_ job-id result-folder-path {:keys [config] :as submission}]
  (-> submission
      (dissoc :starting_step :step_number :job_config)
      (assoc :config               (:job_config submission config)
             :callbackUrl          (build-callback-url job-id)
             :job_id               job-id
             :step_number          (:step_number submission 1)
             :output_dir           result-folder-path
             :create_output_subdir false)))

(defn- prepare-submission
  [tapis job-id submission]
  (->> (format-submission tapis
                          job-id
                          (ft/build-result-folder-path submission)
                          submission)
       (.prepareJobSubmission tapis)))

(defn- determine-start-time
  [job]
  (or (db/timestamp-from-str (str (:startdate job)))
      (db/now)))

(defn- send-submission*
  [tapis user submission job]
  (try+
   (let [job-info (.sendJobSubmission tapis job)]
     (assoc job-info
            :name      (:name submission)
            :notify    (:notify submission false)
            :startdate (determine-start-time job)
            :username  (:username user)))
   (catch Object _
     (when-not (:parent_id submission)
       (throw+)))))

(defn- store-tapis-job
  [user job-id job submission]
  (jp/save-job {:id                 job-id
                :job_name           (:name job)
                :job_description    (:description submission)
                :system_id          (:system_id submission)
                :app_id             (:app_id job)
                :app_name           (:app_name job)
                :app_description    (:app_details job)
                :app_wiki_url       (:wiki_url job)
                :result_folder_path (:resultfolderid job)
                :start_date         (:startdate job)
                :username           (:username user)
                :status             (:status job)
                :notify             (:notify job)
                :parent_id          (:parent_id submission)}
               submission))

(defn- store-job-step
  [job-id job]
  (jp/save-job-step {:job_id          job-id
                     :step_number     1
                     :external_id     (:id job)
                     :start_date      (:startdate job)
                     :status          (:status job)
                     :job_type        jp/tapis-job-type
                     :app_step_number 1}))

(defn- format-job-submission-response
  [job-id submission job]
  (remove-nil-vals
   {:app_description (:app_description job)
    :app_disabled    false
    :app_id          (:app_id job)
    :app_name        (:app_name job)
    :batch           false
    :description     (:description job)
    :enddate         (:enddate job)
    :system_id       jp/tapis-client-name
    :id              job-id
    :name            (:name job)
    :notify          (:notify job)
    :resultfolderid  (:resultfolderid job)
    :startdate       (str (.getTime (:startdate job)))
    :status          (:status job)
    :username        (:username job)
    :wiki_url        (:wiki_url job)
    :parent_id       (:parent_id submission)}))

(defn- handle-successful-submission
  [user job-id job submission]
  (store-tapis-job user job-id job submission)
  (store-job-step job-id job)
  (format-job-submission-response job-id submission job))

(defn- handle-failed-submission
  [user job-id job submission]
  (let [job (assoc job :status jp/failed-status)]
    (store-tapis-job user job-id job submission)
    (store-job-step job-id job)
    (format-job-submission-response job-id submission job)))

(defn- send-submission
  [tapis user job-id submission job]
  (if-let [submitted-job (send-submission* tapis user submission job)]
    (handle-successful-submission user job-id submitted-job submission)
    (handle-failed-submission user job-id job submission)))

(defn submit
  [tapis user submission]
  (let [job-id (or (:job_id submission) (uuids/uuid))]
    (->> (prepare-submission tapis job-id submission)
         (json-util/log-json "job")
         (send-submission tapis user job-id submission))))

(defn submit-step
  [tapis job-id submission]
  (->> (prepare-submission tapis job-id submission)
       (json-util/log-json "job step")
       (.sendJobSubmission tapis)
       (:id)))

(defn- translate-job-status
  [tapis status]
  (if-not (jp/valid-status? status)
    (.translateJobStatus tapis status)
    status))

(defn update-job-status
  [tapis {:keys [external_id] :as job-step} {job-id :id} status end-date]
  (let [status   (translate-job-status tapis status)
        end-date (when (jp/completed? status) end-date)]
    (when (and status (jp/status-follows? status (:status job-step)))
      (jp/update-job-step job-id external_id status end-date)
      (jp/update-job job-id status end-date))))

(defn get-default-output-name
  [tapis {external-output-id :external_output_id} {external-app-id :external_app_id}]
  (.getDefaultOutputName tapis external-app-id external-output-id))

(defn get-job-step-status
  [tapis {:keys [external_id]}]
  (try+
   (select-keys (.listJob tapis external_id) [:status :enddate])
   (catch [:status 404] _ nil)))
