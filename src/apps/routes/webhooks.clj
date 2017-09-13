(ns apps.routes.webhooks
  (:use [common-swagger-api.schema]
        [apps.routes.params]
        [apps.routes.schemas.webhooks]
        [apps.webhooks]
        [ring.util.http-response :only [ok]]
        [korma.db :only [transaction]]))

(defroutes webhooks
           (GET "/" []
                :query [params SecuredQueryParams]
                :return WebhookList
                :summary "List Webhooks"
                :description "Returns all of the webhooks defined for the user."
                (ok (list-webhooks params)))
           (POST "/" []
                 :query [{:keys [user]} SecuredQueryParams]
                 :body [body Webhook]
                 :return Webhook
                 :summary "Add a Webhook"
                 :description "Adds a new webhook to the system."
                 (transaction (let [id (:id (add-webhook user body))]
                                (ok (get-webhook id user)))))
           (DELETE "/:id" []
                   :path-params [id :- WebhookIdParam]
                   :query [{:keys [user]} SecuredQueryParams]
                   :summary "Delete Webhook"
                   :description "Deletes a webhook from the system."
                   (ok (delete-webhook id user)))
           #_(PATCH "/:id" []
                    :path-params [id :- WebhookIdParam]
                    :query [{:keys [user]} WebhookUpdateParams]
                    :body [body WebhookUpdateParams]
                    :return Webhook
                    :summary "Update Webhook Info"
                    :description "Updates webhook url"))
