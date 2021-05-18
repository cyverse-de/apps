(ns apps.service.apps.job-listings
  (:use [cemerick.url :only [url]]
        [kameleon.uuids :only [uuidify]]
        [apps.clients.notifications :only [interapps-url]]
        [apps.util.conversions :only [remove-nil-vals]])
  (:require [apps.clients.permissions :as perms-client]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.jobs.permissions :as job-permissions]
            [apps.service.apps.util :as apps-util]
            [apps.service.util :as util]
            [apps.util.config :as config]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [kameleon.db :as db]))

(defn- job-timestamp
  [timestamp]
  (str (or (db/millis-from-timestamp timestamp) 0)))

(defn- batch-child-status
  [{:keys [status]}]
  (cond (jp/completed? status) :completed
        (jp/running? status)   :running
        :else                  :submitted))

(def ^:private empty-batch-child-status
  {:total     0
   :completed 0
   :running   0
   :submitted 0})

(defn- format-batch-status
  [batch-id]
  (merge empty-batch-child-status
         (->> (jp/list-child-job-statuses batch-id)
              (group-by batch-child-status)
              (map (fn [[status counts]] [status (reduce #(+ %1 (:count %2)) 0 counts)]))
              (into {}))
         {:total (jp/count-child-jobs batch-id)}))

(defn- job-supports-sharing?
  [apps-client perms rep-steps {:keys [parent_id id]}]
  (and (nil? parent_id)
       (= (get perms id) "own")
       (job-permissions/job-steps-support-job-sharing? apps-client (rep-steps id))))

(defn format-base-job
  [{:keys [parent_id id] :as job}]
  (remove-nil-vals
   {:app_description (:app_description job)
    :app_id          (:app_id job)
    :app_name        (:app_name job)
    :description     (:description job)
    :enddate         (job-timestamp (:end_date job))
    :system_id       (:system_id job)
    :id              id
    :name            (:job_name job)
    :resultfolderid  (:result_folder_path job)
    :startdate       (job-timestamp (:start_date job))
    :status          (:status job)
    :username        (:username job)
    :deleted         (:deleted job)
    :notify          (:notify job false)
    :wiki_url        (:app_wiki_url job)
    :parent_id       parent_id
    :batch           (:is_batch job)
    :batch_status    (when (:is_batch job) (format-batch-status id))}))

(defn- interactive-urls
  [{user-id :user_id :as job} rep-steps]
  (let [interactive? (fn [step] (= (:job_type step) jp/interactive-job-type))
        get-url      (fn [step] (str (interapps-url (url (config/interapps-base)) user-id (:external_id step))))]
    (when-not (:is_batch job)
      (seq (map get-url (filter interactive? (rep-steps (:id job))))))))

(defn format-admin-job
  [rep-steps job]
  (remove-nil-vals
   (assoc (format-base-job job)
          :external_ids     (:external_ids job)
          :interactive_urls (interactive-urls job rep-steps))))

;; The `:app_disabled` field is now deprecated and always returns `false`.
(defn format-job
  [apps-client perms rep-steps job]
  (remove-nil-vals
   (assoc (format-base-job job)
          :app_disabled     false
          :can_share        (job-supports-sharing? apps-client perms rep-steps job)
          :interactive_urls (interactive-urls job rep-steps))))

(defn- list-jobs*
  [{:keys [username]} search-params types analysis-ids]
  (jp/list-jobs-of-types username search-params types analysis-ids))

(defn- count-jobs
  [{:keys [username]} {:keys [filter include-hidden]} types analysis-ids]
  (jp/count-jobs-of-types username filter include-hidden types analysis-ids))

(defn- count-job-statuses
  [{:keys [username]} {:keys [filter include-hidden]} types analysis-ids]
  (jp/count-jobs-of-statuses username filter include-hidden types analysis-ids))

(defn list-jobs
  [apps-client user {:keys [sort-field] :as params}]
  (let [perms            (perms-client/load-analysis-permissions (:shortUsername user))
        analysis-ids     (set (keys perms))
        default-sort-dir (if (nil? sort-field) :desc :asc)
        search-params    (util/default-search-params params :startdate default-sort-dir)
        types            (.getJobTypes apps-client)
        jobs             (list-jobs* user search-params types analysis-ids)
        rep-steps        (group-by :job_id (jp/list-representative-job-steps (mapv :id jobs)))
        status-count     (future (count-job-statuses user params types analysis-ids))]
    {:analyses     (mapv (partial format-job apps-client perms rep-steps) jobs)
     :timestamp    (str (System/currentTimeMillis))
     :status-count @status-count
     :total        (count-jobs user params types analysis-ids)}))

(defn list-job-stats
  [apps-client user params]
  (let [perms            (perms-client/load-analysis-permissions (:shortUsername user))
        analysis-ids     (set (keys perms))
        types            (.getJobTypes apps-client)]
    {:status-count (count-job-statuses user params types analysis-ids)}))

(defn admin-list-jobs-with-external-ids [external-ids]
  (let [jobs      (jp/list-jobs-by-external-id external-ids)
        rep-steps (group-by :job_id (jp/list-representative-job-steps (mapv :id jobs)))]
    {:analyses (mapv (partial format-admin-job rep-steps) jobs)}))

(defn list-job
  [apps-client job-id]
  (let [job-info   (jp/get-job-by-id job-id)
        rep-steps  (group-by :job_id (jp/list-representative-job-steps [job-id]))]
    (format-job apps-client nil rep-steps job-info)))

(defn- build-job-step-listing
  [job-id steps format-step]
  {:analysis_id job-id
   :steps       (map format-step steps)
   :timestamp   (str (System/currentTimeMillis))
   :total       (count steps)})

(defn- format-job-step
  [step]
  (remove-nil-vals
   {:step_number     (:step_number step)
    :external_id     (:external_id step)
    :startdate       (job-timestamp (:start_date step))
    :enddate         (job-timestamp (:end_date step))
    :status          (:status step)
    :app_step_number (:app_step_number step)
    :step_type       (:job_type step)}))

(defn list-job-steps
  [job-id]
  (build-job-step-listing job-id (jp/list-job-steps job-id) format-job-step))

(defn- get-job-step-history
  [apps-client step]
  (assoc (format-job-step step)
         :updates (.getJobStepHistory apps-client step)))

(defn get-job-history
  [apps-client job-id]
  (build-job-step-listing job-id (jp/list-job-steps job-id) (partial get-job-step-history apps-client)))
