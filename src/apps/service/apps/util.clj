(ns apps.service.apps.util
  (:require [apps.util.config :as config]
            [clojure.string :as string]
            [clojure-commons.exception-util :as cxu]))

(defn supports-job-type?
  [apps-client job-type]
  (contains? (set (.getJobTypes apps-client)) job-type))

(defn qualified-app-id
  [system-id app-id]
  {:system_id system-id
   :app_id    (str app-id)})

(defn to-qualified-app-id
  [{system-id :system_id app-id :id}]
  (qualified-app-id system-id app-id))

(defn get-app-name
  [app-names system-id app-id]
  (let [app-name (app-names (qualified-app-id system-id app-id))]
    (if (string/blank? app-name)
      (str system-id "/" app-id)
      app-name)))

(defn validate-system-id
  [supported-system-ids system-id]
  (when-not (supported-system-ids system-id)
    (cxu/bad-request "Unsupported system ID."
                     :system-id            system-id
                     :supported-system-ids supported-system-ids)))

(defn validate-system-ids
  [supported-system-ids system-ids]
  (when-let [unsupported-system-ids (seq (remove supported-system-ids system-ids))]
    (cxu/bad-request "Unsupported system IDs."
                     :system-ids           unsupported-system-ids
                     :supported-system-ids supported-system-ids)))

(defn reject-mixed-system-ids
  [original-system-id system-id]
  (when-not (= original-system-id system-id)
    (cxu/bad-request "System IDs may not be mixed for this request."
                     :original-system-id   original-system-id
                     :associated-system-id system-id)))
