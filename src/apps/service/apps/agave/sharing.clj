(ns apps.service.apps.agave.sharing
  (:use [slingshot.slingshot :only [try+]])
  (:require [apps.clients.iplant-groups :as ipg]
            [apps.persistence.jobs :as jp]
            [apps.service.apps.permissions :as app-permissions]
            [clojure-commons.error-codes :as ce :refer [clj-http-error?]]
            [clojure.tools.logging :as log]))

(defn- try-share-app-with-subject
  [agave sharee app-id level success-fn failure-fn]
  (if-not (ipg/user-source? (:source_id sharee))
    (failure-fn "Sharing HPC apps with a group is not supported")
    (try+
     (.shareAppWithUser agave (:id sharee) app-id level)
     (success-fn)
     (catch [:error_code ce/ERR_UNAVAILABLE] {:keys [reason]}
       (log/error (:throwable &throw-context) "Agave app listing timed out")
       (failure-fn reason))
     (catch clj-http-error? _
       (log/error (:throwable &throw-context) "HTTP error returned by Agave")
       (failure-fn (.getMessage (:throwable &throw-context))))
     (catch Object _
       (log/error (:throwable &throw-context) "Uncaught error returned by Agave")
       (failure-fn (.getMessage (:throwable &throw-context)))))))

(defn share-app-with-subject
  [agave app-names sharee app-id level]
  (let [category-id (:id (.hpcAppGroup agave))]
    (try-share-app-with-subject
     agave sharee app-id level
     #(app-permissions/app-sharing-success app-names jp/agave-client-name app-id level category-id category-id)
     (partial
      app-permissions/app-sharing-failure app-names jp/agave-client-name app-id level category-id category-id))))

(defn unshare-app-with-subject
  [agave app-names sharee app-id]
  (let [category-id (:id (.hpcAppGroup agave))]
    (try-share-app-with-subject
     agave sharee app-id nil
     #(app-permissions/app-unsharing-success app-names jp/agave-client-name app-id category-id)
     (partial
      app-permissions/app-unsharing-failure app-names jp/agave-client-name app-id category-id))))
