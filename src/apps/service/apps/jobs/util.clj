(ns apps.service.apps.jobs.util
  (:use [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.data-info :as data-info]
            [apps.persistence.jobs :as jp]
            [apps.util.service :as service]
            [clojure.tools.logging :as log]
            [clojure-commons.error-codes :as ce]
            [clojure-commons.file-utils :as ft]))

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

(defn create-output-dir
  [user submission]
  (let [output-dir (ft/build-result-folder-path submission)]
    (try+
     (data-info/get-path-info user :paths [output-dir] :filter-include "path")
     ; FIXME Update this when data-info's exception handling is updated
     (catch [:status 500] {:keys [body]}
       ;; The caught error can't be rethrown since we parse the body to examine its error code.
       ;; So we must throw the parsed body, but also clear out the `cause` in our `throw+` call,
       ;; since transactions wrapping these functions will try to only rethrow this caught error.
       (let [error (service/parse-json body)]
         (if (= (:error_code error) ce/ERR_DOES_NOT_EXIST)
           (data-info/create-directory user output-dir)
           (throw+ error nil)))))
    output-dir))
