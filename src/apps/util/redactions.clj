(ns apps.util.redactions
  "Functions for stripping sensitive values from data before it's logged or persisted."
  (:require [clojure.string :as string]))

(defn- config-key
  "Builds the job submission config key for a parameter the same way the job parameter listing does:
   \"<step-id>_<param-id>\", or just the parameter ID if there's no step ID."
  [{step-id :step_id param-id :id}]
  (keyword (if (string/blank? (str step-id))
             (str param-id)
             (str step-id "_" param-id))))

(defn sensitive-config-keys
  "Returns the set of job submission config keys for the sensitive parameters in params. Each parameter needs
   :step_id, :id and :is_sensitive."
  [params]
  (into #{} (comp (filter :is_sensitive) (map config-key)) params))

(defn- dissoc-config-keys
  [config sensitive-keys]
  (apply dissoc config (mapcat (juxt identity name) sensitive-keys)))

(defn redact-sensitive-config
  "Removes the values of sensitive parameters from a raw job submission's :config and :job_config maps. The keys are
   removed rather than replaced with a placeholder, so that nothing reading the submission back (e.g. relaunch) can
   mistake a placeholder for a value."
  [submission sensitive-keys]
  (if (empty? sensitive-keys)
    submission
    (reduce (fn [submission k]
              (if (map? (get submission k))
                (update submission k dissoc-config-keys sensitive-keys)
                submission))
            submission
            [:config :job_config])))

(defn redact-sensitive-environment
  "Strips :sensitive_environment from every step of a built job submission. Submissions without :steps are returned
   unchanged."
  [submission]
  (if (:steps submission)
    (update submission :steps (partial mapv #(dissoc % :sensitive_environment)))
    submission))

(defn redact-submission
  "Removes sensitive values from a job submission, raw (:config, :job_config) or built (:steps), before it's logged or
   persisted. Unredacted values are only sent to the job launch. sensitive-keys is the set returned by
   sensitive-config-keys for the submission's app version."
  [submission sensitive-keys]
  (-> submission
      (redact-sensitive-config sensitive-keys)
      redact-sensitive-environment))
