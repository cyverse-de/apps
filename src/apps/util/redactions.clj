(ns apps.util.redactions
  "Functions for stripping sensitive values from data before it's logged or persisted.")

(defn redact-sensitive-environment
  "Strips :sensitive_environment from every step of a job submission. Should be called on anything that writes the
   submission to disk (logs, DB, etc.); unredacted values are only sent to the job launch. Submissions without
   :steps are returned unchanged."
  [submission]
  (if (:steps submission)
    (update submission :steps (partial mapv #(dissoc % :sensitive_environment)))
    submission))
