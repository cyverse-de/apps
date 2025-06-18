(ns apps.service.apps.jobs.permissions
  (:require
   [apps.clients.permissions :as perms-client]
   [apps.persistence.jobs :as jp]
   [apps.service.apps.jobs.util :as ju]
   [apps.util.db :refer [transaction]]
   [clojure-commons.exception-util :as cxu]
   [clojure.string :as string]))

(defn job-steps-support-job-sharing?
  [apps-client job-steps]
  (every? #(.supportsJobSharing apps-client %) job-steps))

(defn job-supports-job-sharing?
  [apps-client job-id]
  (job-steps-support-job-sharing? apps-client (jp/list-representative-job-steps [job-id])))

(defn- validate-job-sharing-support
  [apps-client job-ids]
  (doseq [job-id job-ids]
    (when-not (job-supports-job-sharing? apps-client job-id)
      (cxu/bad-request (str "analysis sharing not supported for " job-id)))))

(defn- verify-not-subjobs
  [jobs]
  (when-let [subjob-ids (seq (map :id (filter :parent_id jobs)))]
    (cxu/bad-request "analysis sharing not supported for members of a batch job" :jobs subjob-ids)))

(defn validate-job-permissions
  [{short-username :shortUsername} required-level job-ids]
  (let [perms (perms-client/load-analysis-permissions short-username job-ids required-level)]
    (when-let [forbidden-ids (seq (remove (set (keys perms)) job-ids))]
      (cxu/forbidden (str "insufficient privileges for analyses: " (string/join ", " forbidden-ids))))))

(defn- format-job-permissions
  [_short-username perms {:keys [id job_name]}]
  {:id          id
   :name        job_name
   :permissions (perms id)})

(defn list-job-permissions
  [apps-client {username :shortUsername :as user} job-ids params]
  (ju/validate-job-existence job-ids)
  (transaction
   (let [jobs (jp/list-jobs-by-id job-ids)]
     (verify-not-subjobs jobs)
     (validate-job-permissions user "read" job-ids)
     (validate-job-sharing-support apps-client job-ids)
     (let [perms (perms-client/list-analysis-permissions username job-ids params)]
       {:analyses (mapv (partial format-job-permissions username perms) jobs)}))))
