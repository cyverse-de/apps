(ns apps.service.apps.combined.util
  (:use [apps.service.util :only [sort-apps apply-offset apply-limit uuid?]]
        [apps.service.apps.util :only [supports-job-type?]]
        [slingshot.slingshot :only [throw+]])
  (:require [apps.persistence.app-metadata :as ap]
            [apps.persistence.jobs :as jp]
            [clojure-commons.exception-util :as cxu]))

(defn apply-default-search-params
  [params]
  (assoc params
         :sort-field (or (:sort-field params) "name")
         :sort-dir   (or (:sort-dir params) "ASC")))

(defn combine-app-listings
  "Expects results to be a list of maps in a format like {:total int, :apps []}"
  [params results]
  (let [params      (apply-default-search-params params)
        maybe-deref (fn [r] (if (future? r) @r r))
        results     (remove nil? (map maybe-deref results))]
    (-> {:total (apply + (map :total results))
         :apps  (mapcat :apps results)}
        (sort-apps params)
        (apply-offset params)
        (apply-limit params))))

(defn get-apps-client
  ([clients]
   (or (first (filter #(.supportsSystemId % jp/de-client-name) clients))
       (cxu/internal-system-error "default system ID not found")))
  ([clients system-id]
   (or (first (filter #(.supportsSystemId % system-id) clients))
       (cxu/bad-request (str "unrecognized system ID " system-id)))))

(defn apps-client-for-job
  [{app-id :app_id app-version-id :app_version_id system-id :system_id} clients]
  (when (or (not= system-id jp/de-client-name)
            (zero? (ap/count-external-steps (or app-version-id
                                                ; Use the latest version ID if not submitted by client.
                                                (ap/get-app-latest-version app-id)))))
    (get-apps-client clients system-id)))

(defn is-de-job-step?
  [job-step]
  (= (:job_type job-step) jp/de-job-type))

(defn apps-client-for-job-step
  [clients {job-type :job_type :as step}]
  (or (first (filter #(supports-job-type? % job-type) clients))
      (cxu/internal-system-error (str "unsupported job type, " job-type ", found in job step"))))

;; The same field is used for the job type in app steps and job steps.
(def apps-client-for-app-step apps-client-for-job-step)
