(ns apps.routes.oauth
  (:require
   [apps.routes.params :as params]
   [apps.service.oauth :as oauth]
   [apps.user :refer [current-user load-user]]
   [common-swagger-api.schema :refer [context defroutes DELETE GET]]
   [common-swagger-api.schema.oauth :as oauth-schema]
   [ring.util.http-response :refer [ok]]))

(defroutes oauth
  (GET "/access-code/:api-name" []
    :path-params [api-name :- oauth-schema/ApiName]
    :query       [params params/OAuthCallbackQueryParams]
    :return      oauth-schema/OAuthCallbackResponse
    :summary     oauth-schema/GetAccessCodeSummary
    :description oauth-schema/GetAccessCodeDescription
    (ok (oauth/get-access-token api-name params)))

  (context "/token-info/:api-name" []
    :path-params [api-name :- oauth-schema/ApiName]

    (GET "/" []
      :query       [params params/SecuredQueryParams]
      :return      oauth-schema/TokenInfo
      :summary     oauth-schema/GetTokenInfoSummary
      :description oauth-schema/GetTokenInfoDescription
      (ok (oauth/get-token-info api-name current-user)))

    (DELETE "/" []
      :query       [params params/SecuredQueryParams]
      :summary     oauth-schema/DeleteTokenInfoSummary
      :description oauth-schema/DeleteTokenInfoDescription
      (oauth/remove-token-info api-name current-user)
      (ok)))

  (GET "/redirect-uris" []
    :query       [params params/SecuredQueryParams]
    :return      oauth-schema/RedirectUrisResponse
    :summary     oauth-schema/GetRedirectUrisSummary
    :description oauth-schema/GetRedirectUrisDescription
    (ok (oauth/get-redirect-uris current-user))))

(defroutes admin-oauth
  (context "/token-info/:api-name" []
    :path-params [api-name :- oauth-schema/ApiName]

    (GET "/" []
      :query       [params params/TokenInfoProxyParams]
      :return      oauth-schema/AdminTokenInfo
      :summary     oauth-schema/AdminGetTokenInfoSummary
      :description oauth-schema/AdminGetTokenInfoDescription
      (ok (oauth/get-admin-token-info api-name (if (:proxy-user params)
                                                 (load-user (:proxy-user params))
                                                 current-user))))

    (DELETE "/" []
      :query       [params params/TokenInfoProxyParams]
      :summary     oauth-schema/AdminDeleteTokenInfoSummary
      :description oauth-schema/AdminDeleteTokenInfoDescription
      (oauth/remove-token-info api-name (if (:proxy-user params)
                                          (load-user (:proxy-user params))
                                          current-user))
      (ok))))
