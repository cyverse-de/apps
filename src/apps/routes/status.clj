(ns apps.routes.status
  (:require
   [apps.util.config :as config]
   [clojure-commons.service :as commons-service]
   [common-swagger-api.schema :refer [defroutes GET StatusParams StatusResponse]]
   [ring.util.http-response :refer [internal-server-error ok]]))

(defroutes status
  (GET "/" [:as {:keys [server-name server-port]}]
    :query [{:keys [expecting]} StatusParams]
    :return StatusResponse
    :summary "Service Information"
    :description "This endpoint provides the name of the service and its version."
    ((if (and expecting (not= expecting (:app-name config/svc-info))) internal-server-error ok)
     (commons-service/get-docs-status config/svc-info server-name server-port config/docs-uri expecting))))
