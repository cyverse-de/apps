(ns apps.routes.webhooks
  (:use [common-swagger-api.schema]
        [apps.routes.params]
        [apps.routes.schemas.webhooks]
        [apps.webhooks]
        [ring.util.http-response :only [ok]]))

(defroutes webhooks
           (GET "/" []
                :query [{:keys [user]} SecuredQueryParams]
                :return WebhookList
                :summary "List Webhooks"
                :description "Returns all of the webhooks defined for the user."
                (ok (list-webhooks user)))
           (PUT "/" []
                 :query [{:keys [user]} SecuredQueryParams]
                 :body [body WebhookList]
                 :return WebhookList
                 :summary "Add a Webhook"
                 :description "Adds a new webhook to the system."
                (ok (add-webhooks user body)))
           (context "/topics" []
                    (GET "/" []
                         :query [params SecuredQueryParams]
                         :return TopicList
                         :summary "List notification topics"
                         :description "Returns all of the notification topics defined"
                         (ok (list-topics))))
           (context "/types" []
                    (GET "/" []
                         :query [params SecuredQueryParams]
                         :return WebhookTypeList
                         :summary "List webhooks types"
                         :description "Returns all webhook types defined"
                         (ok (list-types)))))
