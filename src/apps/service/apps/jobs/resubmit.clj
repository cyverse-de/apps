(ns apps.service.apps.jobs.resubmit
  (:use [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.data-info :as data-info]
            [apps.service.apps.jobs.submissions :as submissions]
            [apps.service.apps.jobs.submissions.async :as async]
            [cheshire.core :as cheshire]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-commons.exception-util :as exception-util]
            [kameleon.uuids :as uuids]))

(defn- get-paths-exist
  [user paths]
  (try+
   (data-info/get-paths-exist user paths)
   (catch Object _
     (log/error (:throwable &throw-context)
                "job resubmission failed: Could not lookup output folder existence.")
     (throw+))))

(defn- filter-existing-output-paths
  [user output-paths]
  (as-> output-paths p
    (remove string/blank? p)
    (get-paths-exist user p)
    (:paths p)
    (group-by val p)
    (get p true)
    (map first p)))

(defn- find-existing-output-dirs
  [user jobs]
  (->> jobs
       (map :submission)
       (remove #(:create_output_subdir % true))
       (map :output_dir)
       (filter-existing-output-paths user)))

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
        [ht-jobs single-jobs] ((juxt filter remove) :is_batch jobs)
        existing-output-dirs  (find-existing-output-dirs user jobs)]
    (when-not (empty? existing-output-dirs)
      (exception-util/exists "One or more output folders already exist." :paths existing-output-dirs))

    (when-not (empty? single-jobs)
      (async/resubmit-jobs apps-client user single-jobs))
    (doseq [ht-job ht-jobs]
      (submissions/submit apps-client user (:submission ht-job)))))
