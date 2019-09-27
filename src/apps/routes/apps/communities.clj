(ns apps.routes.apps.communities
  (:use [common-swagger-api.routes]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps
         :only [AppCategoryMetadataAddRequest
                AppCategoryMetadataDeleteRequest
                AppIdParam]]
        [common-swagger-api.schema.metadata :only [AvuList]]
        [apps.routes.params :only [SecuredQueryParams]]
        [apps.user :only [current-user]]
        [ring.util.http-response :only [ok]])
  (:require [apps.service.apps.communities :as communities]
            [apps.util.service :as service]
            [common-swagger-api.schema.apps.communities :as schema]
            [compojure.route :as route]))

(defroutes app-community-tags

  (POST "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body AppCategoryMetadataAddRequest]
    :return AvuList
    :summary schema/AppCommunityMetadataAddSummary
    :description schema/AppCommunityMetadataAddDocs
    (ok (communities/add-app-to-communities current-user app-id body false)))

  (DELETE "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body AppCategoryMetadataDeleteRequest]
    :summary schema/AppCommunityMetadataDeleteSummary
    :description schema/AppCommunityMetadataDeleteDocs
    (ok (communities/remove-app-from-communities current-user app-id body false)))

  (undocumented (route/not-found (service/unrecognized-path-response))))

(defroutes admin-app-community-tags

  (POST "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body AppCategoryMetadataAddRequest]
    :return AvuList
    :summary "Add/Update Community Metadata AVUs"
    :description (str
                  "Adds or updates Community Metadata AVUs on the app."
                  (get-endpoint-delegate-block
                   "metadata"
                   "POST /avus/{target-type}/{target-id}")
                  "Where `{target-type}` is `app`."
                  " Please see the metadata service documentation for request information.")
    (ok (communities/add-app-to-communities current-user app-id body true)))

  (DELETE "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body AppCategoryMetadataDeleteRequest]
    :summary "Remove Community Metadata AVUs"
    :description (str
                  "Removes the given Community AVUs associated with an app."
                  (get-endpoint-delegate-block
                   "metadata"
                   "POST /avus/deleter"))
    (ok (communities/remove-app-from-communities current-user app-id body true)))

  (undocumented (route/not-found (service/unrecognized-path-response))))
