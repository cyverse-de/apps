(ns apps.routes.webhooks
  (:use [common-swagger-api.schema]
        [apps.routes.params]
        [apps.webhooks]
        [ring.util.http-response :only [ok]])
  (:require [common-swagger-api.schema.webhooks :as schema]))

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
