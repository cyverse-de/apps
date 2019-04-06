(ns apps.routes.apps.communities
  (:use [common-swagger-api.routes]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps :only [AppIdParam]]
        [apps.routes.params :only [SecuredQueryParams]]
        [common-swagger-api.schema.apps :only [AppCategoryMetadata]]
        [apps.user :only [current-user]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.apps.communities :as communities]
            [apps.util.service :as service]
            [compojure.route :as route]))

(defroutes app-community-tags

           (POST "/" []
                 :path-params [app-id :- AppIdParam]
                 :query [params SecuredQueryParams]
                 :body [body (describe AppCategoryMetadata "Community metadata to add to the App.")]
                 :summary "Add/Update Community Metadata AVUs"
                 :description (str
                                "Adds or updates Community Metadata AVUs on the app."
                                " The authenticated user must be a community admin for every Community AVU in the request,"
                                " in order to add or edit this metadata."
                                (get-endpoint-delegate-block
                                  "iplant-groups"
                                  "GET /groups/{group-name}/privileges")
                                (get-endpoint-delegate-block
                                  "metadata"
                                  "POST /avus/{target-type}/{target-id}")
                                "Where `{target-type}` is `app`."
                                " Please see the metadata service documentation for request and response information.")
                 (communities/add-app-to-communities current-user app-id body false))

           (DELETE "/" []
                   :path-params [app-id :- AppIdParam]
                   :query [params SecuredQueryParams]
                   :body [body (describe AppCategoryMetadata "Community metadata to remove from the App.")]
                   :summary "Remove Community Metadata AVUs"
                   :description (str
                                  "Removes the given Community AVUs associated with an app."
                                  " The authenticated user must be a community admin for every Community AVU in the request,"
                                  " in order to remove those AVUs."
                                  (get-endpoint-delegate-block
                                    "iplant-groups"
                                    "GET /groups/{group-name}/privileges")
                                  (get-endpoint-delegate-block
                                    "metadata"
                                    "POST /avus/deleter"))
                   (ok (communities/remove-app-from-communities current-user app-id body false)))

           (undocumented (route/not-found (service/unrecognized-path-response))))

(defroutes admin-app-community-tags

           (POST "/" []
                 :path-params [app-id :- AppIdParam]
                 :query [params SecuredQueryParams]
                 :body [body (describe AppCategoryMetadata "Community metadata to add to the App.")]
                 :summary "Add/Update Community Metadata AVUs"
                 :description (str
                                "Adds or updates Community Metadata AVUs on the app."
                                (get-endpoint-delegate-block
                                  "metadata"
                                  "POST /avus/{target-type}/{target-id}")
                                "Where `{target-type}` is `app`."
                                " Please see the metadata service documentation for request and response information.")
                 (communities/add-app-to-communities current-user app-id body true))

           (DELETE "/" []
                   :path-params [app-id :- AppIdParam]
                   :query [params SecuredQueryParams]
                   :body [body (describe AppCategoryMetadata "Community metadata to remove from the App.")]
                   :summary "Remove Community Metadata AVUs"
                   :description (str
                                  "Removes the given Community AVUs associated with an app."
                                  (get-endpoint-delegate-block
                                    "metadata"
                                    "POST /avus/deleter"))
                   (ok (communities/remove-app-from-communities current-user app-id body true)))

           (undocumented (route/not-found (service/unrecognized-path-response))))
