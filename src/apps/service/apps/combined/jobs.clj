(ns apps.service.apps.combined.jobs
  (:require
   [apps.constants :refer [de-system-id]]
   [apps.persistence.app-metadata :as ap]
   [apps.persistence.jobs :as jp]
   [apps.service.apps.combined.util :as cu]
   [apps.util.service :as service]
   [cheshire.core :as cheshire]
   [clojure-commons.file-utils :as ft]
   [clojure.tools.logging :as log]
   [kameleon.db :as db]
   [kameleon.uuids :as uuids]
   [slingshot.slingshot :refer [throw+ try+]]))

(defn- app-step-partitioner
  "Partitions app steps into units of execution. Each external app step and each interactive app step has to run by
  itself. Consecutive DE app steps can be combined into a single step."
  [{system-id :system_id step-number :app_step_number}]
  (if (= system-id de-system-id)
    system-id
    (str system-id "/" step-number)))

(defn- load-job-steps
  "Loads the app steps from the database, grouping consecutive DE steps into a single step."
  [app-version-id]
  (->> (map (fn [n step] (assoc step :app_step_number n))
            (iterate inc 1)
            (ap/load-app-steps app-version-id))
       (partition-by app-step-partitioner)
       (map first)
       (map (fn [n step] (assoc step :step_number n))
            (iterate inc 1))))

(defn- validate-job-steps
  "Verifies that at least one step is associated with a job submission."
  [app-version-id steps]
  (when (empty? steps)
    (throw+ {:type  :clojure-commons.exception/illegal-argument
             :error (str "app version " app-version-id " has no steps")}))
  steps)

(defn- build-job-save-info
  [user result-folder-path job-id system-id version-id app-info submission]
  {:id                 job-id
   :job_name           (:name submission)
   :job_description    (:description submission)
   :system_id          system-id
   :app_id             (:id app-info)
   :app_version_id     version-id
   :app_name           (:name app-info)
   :app_description    (:description app-info)
   :app_wiki_url       (:wiki_url app-info)
   :result_folder_path result-folder-path
   :start_date         (db/now)
   :status             "Submitted"
   :username           (:username user)
   :notify             (:notify submission false)
   :parent_id          (:parent_id submission)})

(defn- build-job-step-save-info
  [job-id job-step]
  {:job_id          job-id
   :step_number     (:step_number job-step)
   :status          jp/pending-status
   :job_type        (:job_type job-step)
   :app_step_number (:app_step_number job-step)})

(defn build-job-step-list
  [job-id app-version-id]
  (->> (load-job-steps app-version-id)
       (validate-job-steps app-version-id)
       (map (partial build-job-step-save-info job-id))))

(defn- prepare-common-job-step-submission
  [job-info job-step {:keys [config] :as submission}]
  (assoc submission
         :config               (:job_config submission config)
         :create_output_subdir false
         :output_dir           (:result_folder_path job-info)
         :starting_step        (:app_step_number job-step)
         :step_number          (:step_number job-step)))

(def prepare-de-job-step-submission prepare-common-job-step-submission)

(defn- get-current-app-step
  [{app-version-id :app_version_id} {app-step-number :app_step_number}]
  (nth (ap/load-app-steps app-version-id) (dec app-step-number)))

(defn- prepare-tapis-job-step-submission
  [job-info job-step submission]
  (let [app-step   (get-current-app-step job-info job-step)
        submission (prepare-common-job-step-submission job-info job-step submission)]
    (assoc submission
           :app_id      (:external_app_id app-step)
           :paramPrefix (:step_id app-step))))

(defn- prepare-job-step-submission
  [job-info job-step submission]
  (if (cu/is-de-job-step? job-step)
    (prepare-de-job-step-submission job-info job-step submission)
    (prepare-tapis-job-step-submission job-info job-step submission)))

(defn- record-step-submission
  [job-id step-number external-id]
  (->>  {:external_id external-id
         :status      jp/submitted-status
         :start_date  (db/now)}
        (jp/update-job-step-number job-id step-number)))

(defn- submit-job-step
  [client {:keys [id] :as job-info} job-step submission]
  (try+
   (->> (prepare-job-step-submission job-info job-step submission)
        (.submitJobStep client id)
        (record-step-submission id (:step_number job-step)))
   (catch Object _
     (log/error (:throwable (:throwable &throw-context) "job step submission failed"))
     (when-not (boolean (:parent_id submission)) (throw+)))))

(defn submit
  [user clients {system-id :system_id app-id :app_id version-id :app_version_id :as submission}]
  (let [job-id      (uuids/uuid)
        version-id  (or version-id (ap/get-app-latest-version app-id))
        job-steps   (build-job-step-list job-id version-id)
        app-info    (service/assert-found (ap/load-app-info app-id) "app" app-id)
        job-info    (build-job-save-info user (ft/build-result-folder-path submission)
                                         job-id system-id version-id app-info submission)
        job-step    (first job-steps)]
    (jp/save-multistep-job job-info job-steps submission)
    (submit-job-step (cu/apps-client-for-job-step clients job-step) job-info job-step submission)
    job-id))

(defn- pipeline-status-changed?
  [step-number max-step-number status]
  (or (and (= step-number 1)
           (jp/not-completed? status))
      (and (= step-number max-step-number)
           (jp/completed? status))
      (= status jp/failed-status)))

(defn- handle-pipeline-status-change
  [job-id {:keys [step_number]} max-step-number status end-date]
  (when (pipeline-status-changed? step_number max-step-number status)
    (jp/update-job job-id status end-date)))

(defn- ready-for-next-step?
  [step-number max-step-number status]
  (and (not= step-number max-step-number)
       (= status jp/completed-status)))

(defn- load-mapped-inputs
  [app-steps {app-step-number :app_step_number}]
  (->> (dec app-step-number)
       (nth app-steps)
       (:step_id)
       (ap/load-target-step-mappings)))

(defn- build-config-output-id
  [io-map]
  (keyword (str (:source_id io-map) "_" (or (:output_id io-map) (:external_output_id io-map)))))

(defn- build-config-input-id
  [io-map]
  (keyword (str (:target_id io-map) "_" (or (:input_id io-map) (:external_input_id io-map)))))

(defn- find-source-step
  [app-steps source-id]
  (first (filter (comp (partial = source-id) :step_id) app-steps)))

(defn- get-default-output-name
  [combined-client {source-id :source_id :as io-map} app-steps]
  (->> (find-source-step app-steps source-id)
       (.getDefaultOutputName combined-client io-map)))

(defn- get-input-path
  [combined-client {:keys [result_folder_path]} config app-steps io-map]
  (ft/path-join
   result_folder_path
   (->> (if-let [prop-value (get config (build-config-output-id io-map))]
          prop-value
          (get-default-output-name combined-client io-map app-steps)))))

(defn- add-mapped-inputs-to-config
  [combined-client job submission app-steps io-mappings]
  (update-in submission [:config]
             (fn [config]
               (reduce (fn [config io-map]
                         (assoc config
                                (build-config-input-id io-map)
                                (get-input-path combined-client job config app-steps io-map)))
                       config io-mappings))))

(defn- add-mapped-inputs
  [combined-client job next-step submission]
  (let [app-steps (ap/load-app-steps (:app_version_id job))]
    (->> (load-mapped-inputs app-steps next-step)
         (add-mapped-inputs-to-config combined-client job submission app-steps))))

(defn- submit-next-step
  [combined-client clients job {:keys [job_id step_number]}]
  (let [next-step (jp/get-job-step-number job_id (inc step_number))]
    (->> (cheshire/decode (.getValue (:submission job)) true)
         (add-mapped-inputs combined-client job next-step)
         (submit-job-step (cu/apps-client-for-job-step clients next-step) job next-step))))

(defn- handle-next-step-submission
  [combined-client clients job {:keys [step_number] :as job-step} max-step-number status]
  (when (ready-for-next-step? step_number max-step-number status)
    (submit-next-step combined-client clients job job-step)))

(defn- update-pipeline-status
  [combined-client clients max-step-number job-step {job-id :id :as job} status end-date]
  (let [status   (.translateJobStatus combined-client (:job_type job-step) status)
        end-date (when (jp/completed? status) end-date)]
    (when (jp/status-follows? status (:status job-step))
      (jp/update-job-step job-id (:external_id job-step) status end-date)
      (handle-pipeline-status-change job-id job-step max-step-number status end-date)
      (handle-next-step-submission combined-client clients job job-step max-step-number status))))

(defn update-job-status
  [combined-client clients job-step job status end-date]
  (let [max-step-number (jp/get-max-step-number (:id job))]
    (if (= max-step-number 1)
      (.updateJobStatus (cu/apps-client-for-job-step clients job-step) job-step job status end-date)
      (update-pipeline-status combined-client clients max-step-number job-step job status
                              end-date))))
