(ns apps.routes.apps.communities
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [apps.service.apps.communities :as communities]
   [apps.user :refer [current-user]]
   [apps.util.service :as service]
   [common-swagger-api.routes :refer [get-endpoint-delegate-block]]
   [common-swagger-api.schema :refer [defroutes DELETE POST undocumented]]
   [common-swagger-api.schema.apps :refer [AppIdParam]]
   [common-swagger-api.schema.apps.communities :as schema]
   [common-swagger-api.schema.metadata :refer [AvuList]]
   [compojure.route :as route]
   [ring.util.http-response :refer [ok]]))

(defroutes app-community-tags

  (POST "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body schema/AppCommunityListRequest]
    :return AvuList
    :summary schema/AppCommunityAddSummary
    :description schema/AppCommunityAddDocs
    (ok (communities/add-app-to-communities current-user app-id body false)))

  (DELETE "/:community-id" []
    :path-params [app-id :- AppIdParam community-id :- schema/CommunityIdPathParam]
    :query [params SecuredQueryParams]
    :summary schema/AppCommunityDeleteSummary
    :description schema/AppCommunityDeleteDocs
    (ok (communities/remove-app-from-community current-user app-id community-id false)))

  (DELETE "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body schema/AppCommunityListRequest]
    :summary schema/AppCommunityMetadataDeleteSummary
    :description schema/AppCommunityMetadataDeleteDocs
    (ok (communities/remove-app-from-communities current-user app-id body false)))

  (undocumented (route/not-found (service/unrecognized-path-response))))

(defroutes admin-app-community-tags

  (POST "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body schema/AppCommunityListRequest]
    :return AvuList
    :summary schema/AppCommunityAddSummary
    :description (str
                  schema/AppCommunityAddDocs
                  " An administrator does not have to be a community admin, but the"
                  " communities must exist."
                  (get-endpoint-delegate-block
                   "metadata"
                   "POST /avus/{target-type}/{target-id}")
                  "Where `{target-type}` is `app`.")
    (ok (communities/add-app-to-communities current-user app-id body true)))

  (DELETE "/:community-id" []
    :path-params [app-id :- AppIdParam community-id :- schema/CommunityIdPathParam]
    :query [params SecuredQueryParams]
    :summary schema/AppCommunityDeleteSummary
    :description schema/AppCommunityDeleteDocs
    (ok (communities/remove-app-from-community current-user app-id community-id true)))

  (DELETE "/" []
    :path-params [app-id :- AppIdParam]
    :query [params SecuredQueryParams]
    :body [body schema/AppCommunityListRequest]
    :summary schema/AppCommunityMetadataDeleteSummary
    :description (str
                  "Removes the given Community AVUs associated with an app."
                  (get-endpoint-delegate-block
                   "metadata"
                   "POST /avus/deleter"))
    (ok (communities/remove-app-from-communities current-user app-id body true)))

  (undocumented (route/not-found (service/unrecognized-path-response))))
