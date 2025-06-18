(ns apps.routes.workspaces
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [apps.service.workspace :as workspace]
   [apps.user :refer [current-user]]
   [common-swagger-api.schema :refer [defroutes GET]]
   [common-swagger-api.schema.apps.workspace :refer [Workspace]]
   [ring.util.http-response :refer [ok]]))

;; Obsolete?
(defroutes workspaces
  (GET "/" []
    :query [params SecuredQueryParams]
    :return Workspace
    :summary "Obtain user workspace information."
    :description "This endpoint returns information about the workspace belonging to the
        authenticated user."
    (ok (workspace/get-workspace current-user))))
