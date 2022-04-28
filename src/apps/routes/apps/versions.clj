(ns apps.routes.apps.versions
  (:require [apps.routes.params :refer [SecuredQueryParamsRequired]]
            [apps.service.apps :as apps]
            [apps.user :refer [current-user]]
            [common-swagger-api.routes :refer :all]
            [common-swagger-api.schema :refer :all]
            [common-swagger-api.schema.apps :as schema]
            [ring.util.http-response :refer [ok]]))

(defroutes app-versions
           (context "/:system-id/:app-id/versions" []
                    :path-params [system-id :- schema/SystemId
                                  app-id    :- schema/StringAppIdParam]

                    (POST "/" []
                          :query [params SecuredQueryParamsRequired]
                          :body [app schema/AppVersionRequest]
                          :return schema/App
                          :summary schema/AppVersionCreateSummary
                          :description schema/AppVersionCreateDocs
                          (ok (apps/add-app-version current-user
                                                    system-id
                                                    (assoc app :id app-id)
                                                    false)))))
