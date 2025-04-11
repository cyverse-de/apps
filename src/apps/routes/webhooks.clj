(ns apps.routes.webhooks
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [apps.webhooks :refer [add-webhooks list-topics list-types list-webhooks]]
   [common-swagger-api.schema :refer [context defroutes GET PUT]]
   [common-swagger-api.schema.webhooks :as schema]
   [ring.util.http-response :refer [ok]]))

(defroutes webhooks
  (GET "/" []
    :query [{:keys [user]} SecuredQueryParams]
    :return schema/WebhookList
    :summary schema/GetWebhooksSummary
    :description schema/GetWebhooksDesc
    (ok (list-webhooks user)))

  (PUT "/" []
    :query [{:keys [user]} SecuredQueryParams]
    :body [body schema/WebhookList]
    :return schema/WebhookList
    :summary schema/PutWebhooksSummary
    :description schema/PutWebhooksDesc
    (ok (add-webhooks user body)))

  (context "/topics" []
    (GET "/" []
      :query [params SecuredQueryParams]
      :return schema/TopicList
      :summary schema/GetWebhooksTopicSummary
      :description schema/GetWebhooksTopicDesc
      (ok (list-topics))))

  (context "/types" []
    (GET "/" []
      :query [params SecuredQueryParams]
      :return schema/WebhookTypeList
      :summary schema/GetWebhookTypesSummary
      :description schema/GetWebhookTypesDesc
      (ok (list-types)))))
