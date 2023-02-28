(ns apps.service.apps.jobs.sharing
  (:use [apps.service.apps-client :only [get-apps-client-for-username]]
        [clojure-commons.core :only [remove-nil-values]]
        [clostache.parser :only [render]]
        [kameleon.uuids :only [uuidify]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.async-tasks :as async-tasks]
            [apps.clients.data-info :as data-info]
            [apps.clients.iplant-groups :as ipg]
            [apps.clients.permissions :as perms-client]
            [apps.clients.notifications :as cn]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.jobs.params :as job-params]
            [apps.service.apps.jobs.permissions :as job-permissions]
            [apps.util.service :as service]
            [otel.otel :as otel]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-commons.error-codes :as ce]))

(def default-failure-reason "unexpected error")

(defn- validation-passed?
  "Takes a list of validation responses and returns a Boolean flag indicating whether or not the validation passed."
  [validation-responses]
  (every? :ok (mapcat :analyses validation-responses)))

(defn- get-job-name
  [job-id {job-name :job_name}]
  (or job-name (str "analysis ID " job-id)))

(def job-sharing-formats
  {:not-found     "analysis ID {{analysis-id}} does not exist"
   :load-failure  "unable to load permissions for {{analysis-id}}: {{detail}}"
   :not-allowed   "insufficient privileges for analysis ID {{analysis-id}}"
   :is-subjob     "analysis sharing not supported for individual jobs within an HT batch"
   :not-supported "analysis sharing is not supported for jobs of this type"
   :is-group      "sharing an analysis with a group is not supported at this time"})

(defn- job-sharing-success
  [job-id job level input-share-errs output-share-err-msg app-share-err-msg]
  (remove-nil-values
   {:analysis_id   job-id
    :analysis_name (get-job-name job-id job)
    :permission    level
    :input_errors  input-share-errs
    :outputs_error output-share-err-msg
    :app_error     app-share-err-msg
    :success       true}))

(defn- job-sharing-failure
  [job-id job level & [reason]]
  {:analysis_id   job-id
   :analysis_name (get-job-name job-id job)
   :permission    level
   :success       false
   :error         {:error_code ce/ERR_BAD_REQUEST
                   :reason     (or reason default-failure-reason)}})

(defn- job-unsharing-success
  [job-id job input-unshare-errs output-unshare-err-msg]
  (remove-nil-values
   {:analysis_id   job-id
    :analysis_name (get-job-name job-id job)
    :input_errors  input-unshare-errs
    :outputs_error output-unshare-err-msg
    :success       true}))

(defn- job-unsharing-failure
  [job-id job & [reason]]
  {:analysis_id   job-id
   :analysis_name (get-job-name job-id job)
   :success       false
   :error         {:error_code ce/ERR_BAD_REQUEST
                   :reason     (or reason default-failure-reason)}})

(defn- job-sharing-msg
  ([reason-code job-id]
   (job-sharing-msg reason-code job-id nil))
  ([reason-code job-id detail]
   (render (job-sharing-formats reason-code)
           {:analysis-id job-id
            :detail (or detail default-failure-reason)})))

(defn- job-sharing-error
  [failure-reason]
  (throw+
   {:type           ::job-sharing-failure
    :failure-reason failure-reason}))

(defn- has-analysis-permission
  [user job-id required-level]
  (seq (perms-client/load-analysis-permissions user [job-id] required-level)))

(defn- verify-accessible
  [sharer job-id]
  (when-not (has-analysis-permission (:shortUsername sharer) job-id "own")
    (job-sharing-error (job-sharing-msg :not-allowed job-id))))

(defn- verify-not-subjob
  [{:keys [id parent_id]}]
  (when parent_id
    (job-sharing-error (job-sharing-msg :is-subjob id))))

(defn- verify-support
  [apps-client job-id]
  (when-not (job-permissions/job-supports-job-sharing? apps-client job-id)
    (job-sharing-error (job-sharing-msg :not-supported job-id))))

(defn- share-app-for-job
  [apps-client sharer sharee job-id {system-id :system_id app-id :app_id}]
  (otel/with-span [s ["share-app-for-job"]]
    (when-not (.hasAppPermission apps-client sharee system-id app-id "read")
      (let [response (.shareAppWithSubject apps-client false {} sharee system-id app-id "read")]
        (when-not (:success response)
          (get-in response [:error :reason] "unable to share app"))))))

(defn- get-user-from-subject
  [subject]
  (condp = (:source_id subject)
    "ldap"  (:id subject)
    "g:gsa" (str "@grouper-" (:id subject))
    nil))

(defn- share-output-folder
  [sharer sharee {:keys [result_folder_path]}]
  (let [sharee (get-user-from-subject sharee)]
    (try+
      (data-info/share-path sharer result_folder_path sharee "read")
      nil
      (catch ce/clj-http-error? {:keys [body]}
        (str "unable to share result folder: " (:error_code (service/parse-json body)))))))

(defn- share-input-file
  [sharer sharee path]
  (let [sharee (get-user-from-subject sharee)]
    (try+
      (data-info/share-path sharer path sharee "read")
      nil
      (catch ce/clj-http-error? {:keys [body]}
        (str "unable to share input file, " path ": " (:error_code (service/parse-json body)))))))

(defn- process-child-jobs
  [f job-id]
  (otel/with-span [s ["process-child-jobs"]]
    (doall (remove nil? (mapcat f (jp/list-child-jobs job-id))))))

(defn- list-job-inputs
  [apps-client {system-id :system_id app-id :app_id app-version-id :app_version_id :as job}]
  (->> (mapv keyword (.getAppInputIds apps-client system-id app-id app-version-id))
       (select-keys (job-params/get-job-config job))
       vals
       flatten
       (remove string/blank?)))

(defn- process-job-inputs
  [f apps-client job]
  (otel/with-span [s ["process-job-inputs"]]
    (doall (remove nil? (map f (list-job-inputs apps-client job))))))

(defn- share-analysis
  [job-id sharee level]
  (if-let [share-error (perms-client/share-analysis job-id sharee level)]
    (job-sharing-error share-error)))

(defn- share-child-job
  [apps-client sharer sharee level job]
  (share-analysis (:id job) sharee level)
  (process-job-inputs (partial share-input-file sharer sharee) apps-client job))

(defn- share-job
  [update-fn apps-client sharer sharee {job-id :analysis_id level :permission}]
  (otel/with-span [s ["share-job"]]
    (let [job-id (uuidify job-id)
          job    (jp/get-job-by-id job-id)]
      (try+
       (share-analysis job-id sharee level)

       (let [child-input-share-errs (process-child-jobs (partial share-child-job apps-client sharer sharee level) job-id)
             input-share-errs       (process-job-inputs (partial share-input-file sharer sharee) apps-client job)
             output-share-err-msg   (share-output-folder sharer sharee job)
             app-share-err-msg      (share-app-for-job apps-client sharer sharee job-id job)]
         (update-fn (format "shared job ID %s with %s" job-id sharee))
         (job-sharing-success job-id
                              job
                              level
                              (concat input-share-errs child-input-share-errs)
                              output-share-err-msg
                              app-share-err-msg))
       (catch [:type ::job-sharing-failure] {:keys [failure-reason]}
         (update-fn (format "failed to share job ID %s with %s: %s" job-id sharee failure-reason))
         (job-sharing-failure job-id job level failure-reason))
       (catch Object _
         (update-fn (format "failed to share job ID %s with %s: %s" job-id sharee (str (:throwable &throw-context))))
         (job-sharing-failure job-id job level))))))

(defn- share-jobs-with-user
  [update-fn apps-client sharer {sharee :subject :keys [analyses]}]
  (otel/with-span [s ["share-jobs-with-user"]]
    (let [responses (mapv (partial share-job update-fn apps-client sharer sharee) analyses)]
      (cn/send-analysis-sharing-notifications (:shortUsername sharer) sharee responses)
      {:subject  sharee
       :analyses responses})))

(defn- share-jobs-thread
  [async-task-id]
  (let [{:keys [username data]} (async-tasks/get-by-id async-task-id)]
    (try+
     (async-tasks/add-status async-task-id {:status "running"})
     (let [apps-client (get-apps-client-for-username username)
           user        (.getUser apps-client)]
       (mapv (partial share-jobs-with-user (async-tasks/update-fn async-task-id "running") apps-client user)
             (:sharing data))
       (async-tasks/add-completed-status async-task-id {:status "completed"}))
     (catch Object _
       (log/error (:throwable &throw-context) "failed processing async task" async-task-id)
       (async-tasks/add-completed-status async-task-id {:status "failed"})
       (cn/send-general-analysis-sharing-failure-notification username async-task-id)))))

(defn share-jobs
  [apps-client {username :shortUsername} sharing-requests]
  (otel/with-span [s ["share-jobs"]]
    (-> (async-tasks/new-task "analysis-sharing" username sharing-requests)
        (async-tasks/run-async-thread share-jobs-thread "analysis-sharing")
        (string/replace #".*/tasks/" "")
        uuidify)))

(defn- job-sharing-validation-response
  [job-id job level & [failure-reason]]
  (remove-nil-values
   {:analysis_id   job-id
    :analysis_name (get-job-name job-id job)
    :permission    level
    :ok            (nil? failure-reason)
    :error         (when failure-reason
                     {:error_code ce/ERR_BAD_REQUEST
                      :reason     failure-reason})}))

(defn- validate-job-sharing-request
  [apps-client sharer sharee {job-id :analysis_id level :permission}]
  (if-let [job (jp/get-job-by-id job-id)]
    (try+
     (verify-not-subjob job)
     (verify-accessible sharer job-id)
     (verify-support apps-client job-id)
     (job-sharing-validation-response job-id job level)
     (catch [:type ::job-sharing-failure] {:keys [failure-reason]}
       (job-sharing-validation-response job-id job level failure-reason)))
    (job-sharing-validation-response job-id nil level (job-sharing-msg :not-found job-id))))

(defn- validate-subject-job-sharing-requests
  [apps-client sharer {sharee :subject :keys [analyses]}]
  {:subject  sharee
   :analyses (mapv (partial validate-job-sharing-request apps-client sharer sharee) analyses)})

(defn validate-job-sharing-request-body
  "Performs cursory validations on a job sharing request body, returning a flag indicating whether or not the
  validation passed along with validation responses for each sharing request."
  [apps-client user request-body]
  ((juxt (comp validation-passed? :sharing) identity)
   (update request-body :sharing (partial mapv (partial validate-subject-job-sharing-requests apps-client user)))))

(defn- unshare-output-folder
  [sharer sharee {:keys [result_folder_path]}]
  (let [sharee (get-user-from-subject sharee)]
    (try+
      (data-info/unshare-path sharer result_folder_path sharee)
      nil
      (catch ce/clj-http-error? {:keys [body]}
        (str "unable to unshare result folder: " (:error_code (service/parse-json body)))))))

(defn- unshare-input-file
  [sharer sharee path]
  (let [sharee (get-user-from-subject sharee)]
    (try+
      (data-info/unshare-path sharer path sharee)
      nil
      (catch ce/clj-http-error? {:keys [body]}
        (str "unable to unshare input file, " path ": " (:error_code (service/parse-json body)))))))

(defn- unshare-analysis
  [job-id sharee]
  (if-let [unshare-error (perms-client/unshare-analysis job-id sharee)]
    (job-sharing-error unshare-error)))

(defn- unshare-child-job
  [apps-client sharer sharee job]
  (unshare-analysis (:id job) sharee)
  (process-job-inputs (partial unshare-input-file sharer sharee) apps-client job))

(defn- unshare-job
  [update-fn apps-client sharer sharee job-id]
  (let [job-id (uuidify job-id)
        job    (jp/get-job-by-id job-id)]
    (try+
     (unshare-analysis job-id sharee)

     (let [child-input-unshare-errs (process-child-jobs (partial unshare-child-job apps-client sharer sharee) job-id)
           input-unshare-errs       (process-job-inputs (partial unshare-input-file sharer sharee) apps-client job)
           output-unshare-err-msg   (unshare-output-folder sharer sharee job)]
       (update-fn (format "unshared job ID %s with %s" job-id sharee))
       (job-unsharing-success job-id
                              job
                              (concat input-unshare-errs child-input-unshare-errs)
                              output-unshare-err-msg))
     (catch [:type ::job-sharing-failure] {:keys [failure-reason]}
       (update-fn (format "failed to unshare job ID %s with %s: %s" job-id sharee failure-reason))
       (job-unsharing-failure job-id job failure-reason))
     (catch Object _
       (update-fn (format "failed to unshare job ID %s with %s: %s" job-id sharee (str (:throwable &throw-context))))
       (job-unsharing-failure job-id job)))))

(defn- unshare-jobs-with-user
  [update-fn apps-client sharer {sharee :subject :keys [analyses]}]
  (let [responses (mapv (partial unshare-job update-fn apps-client sharer sharee) analyses)]
    (cn/send-analysis-unsharing-notifications (:shortUsername sharer) sharee responses)
    {:subject  sharee
     :analyses responses}))

(defn- unshare-jobs-thread
  [async-task-id]
  (let [{:keys [username data]} (async-tasks/get-by-id async-task-id)]
    (try+
     (async-tasks/add-status async-task-id {:status "running"})
     (let [apps-client (get-apps-client-for-username username)
           user        (.getUser apps-client)]
       (mapv (partial unshare-jobs-with-user (async-tasks/update-fn async-task-id "running") apps-client user)
             (:unsharing data))
       (async-tasks/add-completed-status async-task-id {:status "completed"}))
     (catch Object _
       (log/error (:throwable &throw-context) "failed processing async task" async-task-id)
       (async-tasks/add-completed-status async-task-id {:status "failed"})
       (cn/send-general-analysis-unsharing-failure-notification username async-task-id)))))

(defn unshare-jobs
  [apps-client {username :shortUsername} sharing-requests]
  (otel/with-span [s ["share-jobs"]]
    (-> (async-tasks/new-task "analysis-unsharing" username sharing-requests)
        (async-tasks/run-async-thread unshare-jobs-thread "analysis-unsharing")
        (string/replace #".*/tasks/" "")
        uuidify)))

(defn- job-unsharing-validation-response
  [job-id job & [failure-reason]]
  (remove-nil-values
   {:analysis_id   job-id
    :analysis_name (get-job-name job-id job)
    :ok            (nil? failure-reason)
    :error         (when failure-reason
                     {:error_code ce/ERR_BAD_REQUEST
                      :reason     failure-reason})}))

(defn- validate-job-unsharing-request
  [apps-client sharer sharee job-id]
  (if-let [job (jp/get-job-by-id job-id)]
    (try+
     (verify-not-subjob job)
     (verify-accessible sharer job-id)
     (verify-support apps-client job-id)
     (job-unsharing-validation-response job-id job)
     (catch [:type ::job-sharing-failure] {:keys [failure-reason]}
       (job-unsharing-validation-response job-id job failure-reason)))
    (job-unsharing-validation-response job-id nil (job-sharing-msg :not-found job-id))))

(defn- validate-subject-job-unsharing-requests
  [apps-client sharer {sharee :subject :keys [analyses]}]
  {:subject sharee
   :analyses (mapv (partial validate-job-unsharing-request apps-client sharer sharee) analyses)})

(defn validate-job-unsharing-request-body
  "Performs cursory validations on a job unsharing request body, returning a flag indicating whether or not the
  validation passed along with validation responses for each unsharing request."
  [apps-client user request-body]
  ((juxt (comp validation-passed? :unsharing) identity)
   (update request-body :unsharing (partial mapv (partial validate-subject-job-unsharing-requests apps-client user)))))
