(ns apps.service.apps.util
  (:use [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.util.config :as config]
            [apps.util.service :as service]
            [apps.clients.data-info :as data-info]
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

(defn app-type-qualifies?
  "Determines if an app type parameter qualifies for an apps client. The app type is really just the
   job type in a different context, so an apps client supports an app type if it supports the job type
   with the same name. One caveat is that the DE apps client has to be able to support mixed pipelines,
   and must always perform a database search regardless of the requested app type. Because of this, the
   DE apps client will not call this function."
  [apps-client {:keys [app-type]}]
  (or (nil? app-type) (supports-job-type? apps-client app-type)))

(defn paths-accessible?
  "Determins if one or more paths in the data store is accessible to a user who is attempting to launch an app
   referencing the paths. The user should have read access for a path to be considered accessible."
  [user paths]
  (let [paths (if (sequential? paths) paths [paths])]
    (try+
     (data-info/get-path-info user :paths paths :validation-behavior "read" :filter-include "path")
     (catch [:status 500] e
       (let [error-code (:error_code (service/parse-json (:body e)))]
         (if (#{"ERR_NOT_READABLE" "ERR_DOES_NOT_EXIST"} error-code)
           false
           (throw+)))))))
