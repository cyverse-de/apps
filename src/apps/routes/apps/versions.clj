(ns apps.routes.apps.versions
  (:require [apps.routes.params :refer [SecuredQueryParams
                                        SecuredQueryParamsEmailRequired
                                        SecuredQueryParamsRequired]]
            [apps.service.apps :as apps]
            [apps.user :refer [current-user]]
            [apps.util.coercions :refer [coerce!]]
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
                                                    false)))

                    (context "/:version-id" []
                             :path-params [version-id :- schema/AppVersionIdParam]

                             (DELETE "/" []
                                     :query [params SecuredQueryParams]
                                     :summary schema/AppVersionDeleteSummary
                                     :description schema/AppVersionDeleteDocs
                                     (ok (apps/delete-app-version current-user
                                                                  system-id
                                                                  app-id
                                                                  version-id)))

                             (GET "/" []
                                  :query [params SecuredQueryParams]
                                  :return schema/AppJobView
                                  :summary schema/AppJobViewSummary
                                  :description schema/AppJobViewDocs
                                  (ok (coerce! schema/AppJobView
                                               (apps/get-app-version-job-view current-user
                                                                              system-id
                                                                              app-id
                                                                              version-id))))

                             (GET "/ui" []
                                  :query [params SecuredQueryParamsEmailRequired]
                                  :return schema/App
                                  :summary schema/AppEditingViewSummary
                                  :description schema/AppEditingViewDocs
                                  (ok (apps/get-app-ui current-user system-id app-id version-id))))))
