(ns apps.service.apps.jobs.util
  (:use [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.data-info :as data-info]
            [apps.persistence.jobs :as jp]
            [apps.util.service :as service]
            [clojure.tools.logging :as log]))

(defn validate-job-existence
  [job-ids]
  (let [missing-ids (jp/list-non-existent-job-ids (set job-ids))]
    (when-not (empty? missing-ids)
      (service/not-found "jobs" job-ids))))

(defn get-path-list-contents
  [user path]
  (try+
    (when (seq path) (data-info/get-path-list-contents user path))
    (catch Object _
      (log/error (:throwable &throw-context)
                 "job submission failed: Could not get file contents of Path List input"
                 path)
      (throw+))))

(defn get-path-list-contents-map
  [user paths]
  (into {} (map (juxt identity (partial get-path-list-contents user)) paths)))
