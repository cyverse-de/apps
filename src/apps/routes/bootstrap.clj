(ns apps.routes.bootstrap
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [apps.user :only [current-user]]
        [common-swagger-api.schema]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.bootstrap :as svc]
            [common-swagger-api.schema.apps.bootstrap :as schema]))

(defroutes bootstrap
  (GET "/" []
       :query [params SecuredQueryParams]
       :return schema/AppsBootstrapResponse
       :summary "Bootstrap Service"
       :description "Returns information that the Discovery Environment UI uses to initialize a user's session."
       (ok (svc/bootstrap current-user))))
