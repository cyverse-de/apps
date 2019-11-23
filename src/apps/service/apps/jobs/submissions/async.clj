(ns apps.service.apps.jobs.submissions.async
  (:use [clojure-commons.core :only [remove-nil-values]]
        [korma.db :only [transaction]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-commons.exception-util :as exception-util]
            [clojure-commons.file-utils :as ft]
            [apps.clients.notifications :as notifications]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.job-listings :as job-listings]
            [apps.service.apps.jobs.submissions.submit :as submit]
            [apps.service.apps.jobs.util :as util]
            [apps.util.config :as config]))

(defn- max-batch-paths-exceeded
  [max-paths first-list-path first-list-count]
  (exception-util/illegal-argument
   (str "The HT Analysis Path List exceeds the maximum of "
        max-paths
        " allowed paths.")
   :path       first-list-path
   :path-count first-list-count))

(defn- validate-path-lists
  [path-lists]
  (let [[first-list-path first-list] (first path-lists)
        first-list-count             (count first-list)]
    (when (> first-list-count (config/ht-path-list-max-paths))
      (max-batch-paths-exceeded (config/ht-path-list-max-paths) first-list-path first-list-count))
    (when-not (every? (comp (partial = first-list-count) count second) path-lists)
      (exception-util/illegal-argument "All HT Analysis Path Lists must have the same number of paths."))
    path-lists))

(defn- map-slice
  [m n]
  (->> (map (fn [[k v]] (vector k (nth v n))) m)
       (into {})
       (remove-nil-values)))

(defn- map-slices
  [m]
  (let [max-count (apply max (map (comp count val) m))]
    (mapv (partial map-slice m) (range max-count))))

(defn- substitute-param-values
  [path-map config]
  (->> (map (fn [[k v]] (vector k (get path-map v v))) config)
       (into {})))

(defn- format-submission-in-batch
  [{:keys [config] :as submission} job-number path-map]
  (let [job-suffix (str "analysis-" (inc job-number))
        job-config (substitute-param-values path-map (:job_config submission config))
        config     (substitute-param-values path-map config)]
    (assoc submission
           :job_config job-config
           :config     config
           :group      (config/jex-batch-group-name)
           :name       (str (:name submission) "-" job-suffix)
           :output_dir (ft/path-join (:output_dir submission) job-suffix))))

(defn- submit-job-in-batch
  [apps-client user submission job-number path-map]
  (try+
   (->> (format-submission-in-batch submission job-number path-map)
        (submit/submit-and-register-private-job apps-client user)
        :status)
   (catch Object _
     (log/error (:throwable &throw-context)
                "batch job submission failed.")
     jp/failed-status)))

(defn- batch-job-reducer
  [apps-client user {:keys [parent-id parent-status total submission batch-status] :as results} path-map]
  (transaction
    ;; re-check for completed parent status before each submission
   (let [parent-status (if (jp/completed? parent-status)
                         parent-status
                         (jp/get-job-status parent-id))
         job-status    (if (jp/completed? parent-status)
                         jp/canceled-status
                         (submit-job-in-batch apps-client user submission total path-map))]
     (assoc results
            :parent-status parent-status
            :total         (inc total)
            :batch-status  (update batch-status job-status (fnil inc 0))))))

(defn- preprocess-batch-submission
  [submission output-dir parent-id]
  (assoc submission
         :output_dir           output-dir
         :parent_id            parent-id
         :create_output_subdir false))

(defn- submit-batch-jobs-thread
  [apps-client {username :shortUsername email-address :email :as user} ht-paths submission output-dir parent-id]
  (try+
   (log/info "batch job submissions starting...")
   (let [path-lists    (validate-path-lists (util/get-path-list-contents-map user ht-paths))
         path-maps     (map-slices path-lists)
         submission    (preprocess-batch-submission submission output-dir parent-id)
         job-stats     (reduce (partial batch-job-reducer apps-client user)
                               {:parent-id     parent-id
                                :parent-status jp/submitted-status
                                :total         0
                                :submission    submission
                                :batch-status  {jp/submitted-status 0}}
                               path-maps)
         total-jobs    (:total job-stats)
         success-count (get-in job-stats [:batch-status jp/submitted-status])
         parent-job    (job-listings/list-job apps-client parent-id)
         message       (format "%s %d analyses of %d %s"
                               (:name parent-job)
                               success-count
                               total-jobs
                               (string/lower-case jp/submitted-status))]
     (if (> success-count 0)
       (notifications/send-job-status-update username email-address parent-job message)
       (throw+ "all batch sub-job submissions failed.")))
   (log/info "batch job submissions complete.")
   (catch Object _
     (log/error (:throwable &throw-context) "batch job submission failed.")
     (jp/update-job parent-id jp/failed-status nil)
     (notifications/send-job-status-update user (job-listings/list-job apps-client parent-id)))))

(defn submit-batch-jobs
  "Asynchronously submits sub-jobs for a parent batch job,
   where `ht-paths` is a list of paths of the HT Path List input files,
   `submission` is the original parent submission map,
   `output-dir` is the path to the output folder created for the parent job,
   and `parent-id` is the parent job's ID to use for each sub-job's `parent_id` field.
   A notification will be sent to the user on success or on failure to submit all sub-jobs."
  [apps-client user ht-paths submission output-dir parent-id]
  (let [^Runnable target #(submit-batch-jobs-thread apps-client user ht-paths submission output-dir parent-id)]
    (.start (Thread. target (str "batch_submit_" parent-id)))))

(defn- resubmit-job-reducer
  [apps-client
   user
   {:keys [total batch-status] :as results}
   {:keys [submission]}]
  (let [job-status (->> submission
                        (submit/submit-and-register-private-job apps-client user)
                        :status)]
    (assoc results
           :total         (inc total)
           :batch-status  (update batch-status job-status (fnil inc 0)))))

(defn- resubmit-jobs-thread
  [apps-client {username :shortUsername email-address :email :as user} jobs]
  (try+
   (let [resubmit-stats (reduce (partial resubmit-job-reducer apps-client user)
                                {:total        0
                                 :batch-status {jp/submitted-status 0}}
                                jobs)
         total-jobs     (:total resubmit-stats)
         success-count  (get-in resubmit-stats [:batch-status jp/submitted-status])
         message        (format "%d of %d analyses successfully relaunched" success-count total-jobs)]
     (if (> success-count 0)
       (notifications/send-job-status-update username email-address (first jobs) message)
       (throw+ "all relaunch submissions failed.")))
   (catch Object _
     (log/error (:throwable &throw-context) "relaunch job submissions failed.")
     (notifications/send-job-status-update user (first jobs)))))

(defn resubmit-jobs
  [apps-client user jobs]
  (let [^Runnable target #(resubmit-jobs-thread apps-client user jobs)]
    (.start (Thread. target (str "resubmit_" (:username user) "_" (-> jobs first :id))))))
