(ns apps.service.apps.de.limits
  (:require [apps.clients.analyses :as analyses]
            [apps.persistence.job-limits :as job-limits]))

(def ^:private err-permission-needed "ERR_PERMISSION_NEEDED")
(def ^:private err-forbidden "ERR_FORBIDDEN")
(def ^:private err-limit-reached "ERR_LIMIT_REACHED")

(defn- load-concurrent-vice-analysis-limit-check
  "Loads the limit check for the number of concurrently running VICE analyses."
  [user]
  (let [limit-info (analyses/get-concurrent-job-limit (:shortUsername user))
        job-count  (job-limits/count-concurrent-vice-jobs (:username user))]
    (fn []
      (let [{max-jobs :concurrent_jobs using-default? :is_default} limit-info]
        (cond
          (and (<= max-jobs 0) using-default?)
          [err-permission-needed]

          (<= max-jobs 0)
          [err-forbidden]

          (<= max-jobs job-count)
          [err-limit-reached]

          :else
          [])))))

(defn load-limit-checks
  [user]
  {:interactive (load-concurrent-vice-analysis-limit-check user)})
