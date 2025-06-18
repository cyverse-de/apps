(ns apps.routes.bootstrap
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [apps.service.bootstrap :as svc]
   [apps.user :refer [current-user]]
   [common-swagger-api.schema :refer [defroutes GET]]
   [common-swagger-api.schema.apps.bootstrap :as schema]
   [ring.util.http-response :refer [ok]]))

(defroutes bootstrap
  (GET "/" []
    :query [params SecuredQueryParams]
    :return schema/AppsBootstrapResponse
    :summary "Bootstrap Service"
    :description "Returns information that the Discovery Environment UI uses to initialize a user's session."
    (ok (svc/bootstrap current-user))))
