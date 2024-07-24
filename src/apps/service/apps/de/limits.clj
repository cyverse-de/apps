(ns apps.service.apps.de.limits
  (:require [apps.clients.analyses :as analyses]
            [apps.clients.requests :as requests]
            [apps.persistence.job-limits :as job-limits]
            [clojure-commons.core :refer [remove-nil-values]]))

(def ^:private err-permission-needed "ERR_PERMISSION_NEEDED")
(def ^:private err-forbidden "ERR_FORBIDDEN")
(def ^:private err-limit-reached "ERR_LIMIT_REACHED")

(defn- get-limit-check-result-formatter
  "Formats a single result for a limit check."
  [limit-check-id & [additional-info]]
  (fn [reason-codes]
    (-> {:limitCheckID   limit-check-id
         :additionalInfo additional-info
         :reasonCodes    reason-codes}
        remove-nil-values)))

(defn- load-concurrent-vice-analysis-limit-check-results
  "Loads the limit check for the number of concurrently running VICE analyses."
  [user]
  (let [limit-info      (future (analyses/get-concurrent-job-limit (:shortUsername user)))
        job-count       (job-limits/count-concurrent-vice-jobs (:username user))
        requests-list   (requests/list-vice-requests (:shortUsername user))
        pending-request (not (empty? (:requests requests-list)))
        max-jobs        (:concurrent_jobs @limit-info)
        using-default?  (:is_default @limit-info)
        additional-info {:runningJobs job-count :maxJobs max-jobs :usingDefaultSetting using-default? :pendingRequest pending-request}
        format-result   (get-limit-check-result-formatter "CONCURRENT_VICE_ANALYSES" additional-info)]
    (cond
      (and (<= max-jobs 0) using-default?)
      [(format-result [err-permission-needed])]

      (<= max-jobs 0)
      [(format-result [err-forbidden])]

      (<= max-jobs job-count)
      [(format-result [err-limit-reached])]

      :else
      [])))

(defn load-limit-check-results
  "Loads a user's limit check results for all applicable app types. The results are in the format of a map from a
  keywordized job type to a future containing a set of error codes indicating which limit check errors occurred. An
  empty or missing limit check error set for a job type means that apps of that type can be executed."
  [user]
  {:interactive (future (load-concurrent-vice-analysis-limit-check-results user))})

(defn format-app-limit-check-results
  "Formats limit check results for an app. Note that each app may have multiple job types associated with it, and each
   job type needs to be checked. The return value is a map indicating whether or not the user can run the app and
   listing which limit check errors occured."
  [limit-check-results {job-types :job_types}]
  (let [results (mapcat (comp deref (fnil identity (ref nil)) limit-check-results keyword) job-types)]
    {:canRun  (empty? results)
     :results (vec results)}))
