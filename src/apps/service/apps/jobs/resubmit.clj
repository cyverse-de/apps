(ns apps.service.apps.jobs.resubmit
  (:require [apps.service.apps.jobs.submissions :as submissions]
            [apps.service.apps.jobs.submissions.async :as async]
            [cheshire.core :as cheshire]
            [clojure.string :as string]
            [clojure-commons.exception-util :as exception-util]
            [kameleon.uuids :as uuids]))

(defn- decode-job-submission
  [{:keys [id submission] :as job}]
  (when-not submission (exception-util/not-found (str "Job submission values could not be found for " id)))
  (update job :submission #(-> % .getValue (cheshire/decode true))))

(defn- format-redo-name
  "Returns a string by appending `-redo-1` to `original-name`.
   If `original-name` already ends with `-redo-###` (where `###` is any digit),
   then returns `original-name-redo-###` where `###` is a number 1 larger than the original trailing digit."
  [original-name]
  (let [regex          #"(.*-redo-)(\d+)$"
        format-matches #(str %1 (-> %2 Integer/parseInt inc))]
    (if (re-matches regex original-name)
      (string/replace original-name regex #(format-matches (%1 1) (%1 2)))
      (str original-name "-redo-1"))))

(defn- format-resubmission-output-dir
  [{:keys [submission] :as job}]
  (if (:create_output_subdir submission true)
    job
    (update-in job [:submission :output_dir] format-redo-name)))

(defn- format-resubmission-name
  [{:keys [parent_id] :as job}]
  (if parent_id
    (-> job
        (update-in [:submission :parent_id] uuids/uuidify)
        (update-in [:submission :name] format-redo-name))
    job))

(defn- format-resubmission
  [job]
  (-> job
      format-resubmission-output-dir
      format-resubmission-name))

(defn resubmit-jobs
  [apps-client user jobs]
  (let [jobs                  (map (comp format-resubmission decode-job-submission) jobs)
        [ht-jobs single-jobs] ((juxt filter remove) :is_batch jobs)]
    (when-not (empty? single-jobs)
      (async/resubmit-jobs apps-client user single-jobs))
    (doseq [ht-job ht-jobs]
      (submissions/submit apps-client user (:submission ht-job)))))
