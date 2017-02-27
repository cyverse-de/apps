(ns apps.routes.bootstrap
  (:use [common-swagger-api.routes]
        [common-swagger-api.schema]
        [apps.routes.params :only [SecuredQueryParams]]
        [apps.routes.schemas.bootstrap :only [BootstrapResponse]]
        [apps.user :only [current-user]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.bootstrap :as svc]))

(defroutes bootstrap
  (GET "/" []
    :query [params SecuredQueryParams]
    :return BootstrapResponse
    :summary "Bootstrap Service"
    :description "Returns information that the Discovery Environment UI uses to initialize a user's session."
    (ok (svc/bootstrap current-user))))
