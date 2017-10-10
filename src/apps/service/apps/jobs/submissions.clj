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
            [apps.util.service :as service]))

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

(defn- load-path-list-stats
  [user input-paths-by-id]
  (->> (flatten (vals input-paths-by-id))
       (remove string/blank?)
       (get-file-stats user)
       (:paths)
       (map val)
       (filter (comp (partial = (config/path-list-info-type)) :infoType))))

(defn- param-value-contains-paths?
  [paths [_ v]]
  (if (sequential? v)
    (some (set paths) v)
    ((set paths) v)))

(defn- extract-ht-param-ids
  [path-list-stats input-paths-by-id]
  (let [ht-paths (set (map :path path-list-stats))]
    (map key (filter (partial param-value-contains-paths? ht-paths) input-paths-by-id))))

(defn- max-path-list-size-exceeded
  [max-size path actual-size]
  (throw+
    {:type      :clojure-commons.exception/illegal-argument
     :error     (str "HT Analysis Path List file exceeds maximum size of " max-size " bytes.")
     :path      path
     :file-size actual-size}))

(defn- validate-path-list-stats
  [{path :path actual-size :file-size}]
  (when (> actual-size (config/path-list-max-size))
    (max-path-list-size-exceeded (config/path-list-max-size) path actual-size)))

(defn- validate-ht-params
  [ht-params]
  (when (some (comp (partial = ap/param-multi-input-type) :type) ht-params)
    (throw+ {:type  :clojure-commons.exception/illegal-argument
             :error "HT Analysis Path List files are not supported in multi-file inputs."})))

(defn- get-batch-output-dir
  [user submission]
  (let [output-dir (ft/build-result-folder-path submission)]
    (try+
     (data-info/get-path-info user :paths [output-dir] :filter-include "path")
     ; FIXME Update this when data-info's exception handling is updated
     (catch [:status 500] {:keys [body]}
       ;; The caught error can't be rethrown since we parse the body to examine its error code.
       ;; So we must throw the parsed body, but also clear out the `cause` in our `throw+` call,
       ;; since the transaction wrapping these functions will try to only rethrow this caught error.
       (let [error (service/parse-json body)]
         (if (= (:error_code error) ce/ERR_DOES_NOT_EXIST)
           (data-info/create-directory user output-dir)
           (throw+ error nil)))))
    output-dir))

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
  (doseq [path-stat path-list-stats]
    (validate-path-list-stats path-stat))

  (->> (extract-ht-param-ids path-list-stats input-paths-by-id)
       (select-keys input-params-by-id)
       vals
       validate-ht-params))

(defn- submit-batch-job
  [apps-client user input-params-by-id input-paths-by-id path-list-stats job-types app submission]
  (pre-submit-batch-validation input-params-by-id input-paths-by-id path-list-stats)

  (let [ht-paths   (set (map :path path-list-stats))
        output-dir (get-batch-output-dir user submission)
        batch-id   (save-batch user job-types app submission output-dir)]
    (async/submit-batch-jobs apps-client user ht-paths submission output-dir batch-id)
    (job-listings/list-job apps-client batch-id)))

(defn submit
  [apps-client user {app-id :app_id system-id :system_id :as submission}]
  (let [[job-types app]    (.getAppSubmissionInfo apps-client system-id app-id)
        input-params-by-id (get-app-params app ap/param-ds-input-types)
        input-paths-by-id  (select-keys (:config submission) (keys input-params-by-id))]
    (if-let [path-list-stats (seq (load-path-list-stats user input-paths-by-id))]
      (submit-batch-job apps-client user input-params-by-id input-paths-by-id
                        path-list-stats job-types app submission)
      (submit/submit-and-register-private-job apps-client user submission))))
