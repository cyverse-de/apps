(ns apps.service.apps.jobs.submissions
  (:use [clojure-commons.core :only [remove-nil-values]]
        [slingshot.slingshot :only [try+ throw+]]
        [kameleon.uuids :only [uuid]]
        [korma.db :only [transaction]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-commons.error-codes :as ce]
            [clojure-commons.file-utils :as ft]
            [kameleon.db :as db]
            [apps.clients.data-info :as data-info]
            [apps.clients.notifications :as notifications]
            [apps.clients.permissions :as perms-client]
            [apps.persistence.app-metadata :as ap]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.job-listings :as job-listings]
            [apps.service.apps.jobs.submissions.async :as async]
            [apps.service.apps.jobs.submissions.submit :as submit]
            [apps.util.config :as config]
            [apps.util.service :as service]
            [apps.service.apps.jobs.util :as util]))

(defn- get-app-params
  [app type-set]
  (->> (:groups app)
       (mapcat :parameters)
       (filter (comp type-set :type))
       (map (juxt (comp keyword :id) identity))
       (into {})))

(defn- get-file-stats
  "Gets information for the provided paths. Filters to only path, infoType, and file size."
  [user paths]
  (try+
   (data-info/get-path-info user :paths paths :filter-include "infoType,path,file-size")
   (catch Object _
     (log/error (:throwable &throw-context)
                "job submission failed: Could not lookup info types of inputs.")
     (throw+))))

(defn- load-input-path-stats
  [user input-paths-by-id]
  (->> (flatten (vals input-paths-by-id))
       (remove string/blank?)
       (get-file-stats user)
       :paths
       (map val)))

(defn- filter-stats-by-info-type
  [info-type path-stats]
  (filter (comp (partial = info-type) :infoType) path-stats))

(defn- param-value-contains-paths?
  [paths [_ v]]
  (if (sequential? v)
    (some (set paths) v)
    ((set paths) v)))

(defn- extract-path-list-params
  [path-list-stats input-paths-by-id input-params-by-id]
  (let [paths (set (map :path path-list-stats))]
    (->> input-paths-by-id
         (filter (partial param-value-contains-paths? paths))
         (map key)
         (select-keys input-params-by-id)
         vals)))

(defn- validate-multi-input-params
  [multi-input-params]
  (when-not (every? (comp (partial = ap/param-multi-input-type) :type) multi-input-params)
    (throw+ {:type  :clojure-commons.exception/illegal-argument
             :error "Multi-Input Path List files are only supported in multi-file inputs."})))

(defn- multi-input-max-paths-exceeded
  [max-paths path path-count]
  (throw+
    {:type       :clojure-commons.exception/illegal-argument
     :error      (format "Multi-Input Path List exceeds the maximum of %d allowed paths." max-paths)
     :path       path
     :path-count path-count}))

(defn- max-path-list-size-exceeded
  [list-type max-size path actual-size]
  (throw+
    {:type      :clojure-commons.exception/illegal-argument
     :error     (format "%s file exceeds maximum size of %d bytes." list-type max-size)
     :path      path
     :file-size actual-size}))

(defn- validate-path-list-stats
  [list-type max-paths path-list-stats]
  ;; Ensure the file size is within a reasonable limit, based on the iRODS max path length:
  ;; the iRODS max path length + 1 (for newlines) * the path limit + 1 (for some file header overhead).
  (let [path-list-max-size (* (+ 1 (config/irods-path-max-len))
                              (+ 1 max-paths))]
    (doseq [{path :path actual-size :file-size} path-list-stats]
      (when (> actual-size path-list-max-size)
        (max-path-list-size-exceeded list-type path-list-max-size path actual-size)))))

(defn- validate-ht-params
  [ht-params]
  (when (some (comp (partial = ap/param-multi-input-type) :type) ht-params)
    (throw+ {:type  :clojure-commons.exception/illegal-argument
             :error "HT Analysis Path List files are not supported in multi-file inputs."})))

(defn- validate-multi-input-path-lists
  [path-lists]
  (doseq [[path list] path-lists]
    (let [list-count (count list)
          max-paths  (config/multi-input-path-list-max-paths)]
      (when (> list-count max-paths)
        (multi-input-max-paths-exceeded max-paths path list-count))))
  path-lists)

(defn- save-batch*
  [user app submission output-dir]
  (:id (jp/save-job {:job_name           (:name submission)
                     :job_description    (:description submission)
                     :system_id          (:system_id submission)
                     :app_id             (:app_id submission)
                     :app_name           (:name app)
                     :app_description    (:description app)
                     :result_folder_path output-dir
                     :start_date         (db/now)
                     :status             jp/submitted-status
                     :username           (:username user)
                     :notify             (:notify submission)}
                    submission)))

(defn- save-batch-step
  [batch-id job-type]
  (jp/save-job-step {:job_id          batch-id
                     :step_number     1
                     :status          jp/submitted-status
                     :app_step_number 1
                     :job_type        job-type}))

(defn- save-batch
  [user job-types app submission output-dir]
  (transaction
    (let [batch-id (save-batch* user app submission output-dir)]
      (save-batch-step batch-id (first job-types))
      (perms-client/register-private-analysis (:shortUsername user) batch-id)
      batch-id)))

(defn- pre-submit-batch-validation
  [input-params-by-id input-paths-by-id path-list-stats]
  (validate-path-list-stats "HT Analysis Path List"
                            (config/ht-path-list-max-paths)
                            path-list-stats)
  (validate-ht-params (extract-path-list-params path-list-stats
                                                input-paths-by-id
                                                input-params-by-id)))

(defn- submit-batch-job
  [apps-client user input-params-by-id input-paths-by-id path-list-stats job-types app submission]
  (pre-submit-batch-validation input-params-by-id input-paths-by-id path-list-stats)

  (let [ht-paths   (set (map :path path-list-stats))
        output-dir (util/create-output-dir user submission)
        batch-id   (save-batch user job-types app submission output-dir)]
    (async/submit-batch-jobs apps-client user ht-paths submission output-dir batch-id)
    (job-listings/list-job apps-client batch-id)))

(defn- substitute-multi-input-param-values
  [path-list-map config multi-input-param-id]
  (assoc config multi-input-param-id (->> (get config multi-input-param-id) ;; get current multi-input path values
                                          (map #(get path-list-map % %)) ;; expand path-list for each matched value
                                          flatten)))

(defn- pre-expand-multi-input-validation
  [path-list-stats input-paths-by-id input-params-by-id]
  (validate-path-list-stats "Multi-Input Path List"
                            (config/multi-input-path-list-max-paths)
                            path-list-stats)
  (validate-multi-input-params (extract-path-list-params path-list-stats
                                                         input-paths-by-id
                                                         input-params-by-id)))

(defn- config->expand-multi-input-path-lists
  [config user input-params-by-id input-paths-by-id path-list-stats]
  (pre-expand-multi-input-validation path-list-stats input-paths-by-id input-params-by-id)

  (let [path-list-paths       (set (map :path path-list-stats))
        path-list-map         (validate-multi-input-path-lists (util/get-path-list-contents-map user path-list-paths))
        multi-input-param-ids (->> input-params-by-id
                                   (filter (comp (partial = ap/param-multi-input-type) :type second))
                                   (map first)
                                   set)]
    (reduce (partial substitute-multi-input-param-values path-list-map)
            config
            multi-input-param-ids)))

(defn- process-job-config
  [config user input-params-by-id input-paths-by-id path-stats]
  (if-let [multi-input-path-list-stats (->> path-stats
                                            (filter-stats-by-info-type (config/multi-input-path-list-info-type))
                                            seq)]
    (config->expand-multi-input-path-lists config
                                           user
                                           input-params-by-id
                                           input-paths-by-id
                                           multi-input-path-list-stats)
    config))

(defn- pre-process-submission
  [{:keys [config] :as submission} user input-params-by-id input-paths-by-id path-stats]
  (assoc submission :job_config (process-job-config config user input-params-by-id input-paths-by-id path-stats)))

(defn submit
  [apps-client user {app-id :app_id system-id :system_id :as submission}]
  (let [[job-types app]    (.getAppSubmissionInfo apps-client system-id app-id)
        input-params-by-id (get-app-params app ap/param-ds-input-types)
        input-paths-by-id  (select-keys (:config submission) (keys input-params-by-id))
        path-stats         (load-input-path-stats user input-paths-by-id)
        ht-path-list-stats (filter-stats-by-info-type (config/ht-path-list-info-type) path-stats)
        submission         (pre-process-submission submission user input-params-by-id input-paths-by-id path-stats)]
    (if (empty? ht-path-list-stats)
      (submit/submit-and-register-private-job apps-client user submission)
      (submit-batch-job apps-client user input-params-by-id input-paths-by-id
                        ht-path-list-stats job-types app submission))))
