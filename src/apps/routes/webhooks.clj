(ns apps.routes.webhooks
  (:use [common-swagger-api.schema]
        [apps.routes.params]
        [apps.routes.schemas.webhooks]
        [apps.webhooks]
        [ring.util.http-response :only [ok]]
        [korma.db :only [transaction]])
  (:import (org.postgresql.util GT)))

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
                 (transaction (ok (add-webhooks user body))))
           ;;(DELETE "/:id" []
           ;;        :path-params [id :- WebhookIdParam]
           ;;        :query [{:keys [user]} SecuredQueryParams]
           ;;        :summary "Delete Webhook"
           ;;        :description "Deletes a webhook from the system."
           ;;        (ok (delete-webhook id user)))
           ;;(PUT "/:id" []
           ;;         :path-params [id :- WebhookIdParam]
           ;;         :query [{:keys [user]} SecuredQueryParams]
           ;;         :body [body Webhook]
           ;;         :return Webhook
           ;;         :summary "Update Webhook Information"
           ;;         :description "Updates webhook url and topic subscriptions"
           ;;          (ok (update-webhook id user body)))
           ;;(GET "/:id/subscriptions" []
           ;;     :path-params [id :- WebhookIdParam]
           ;;     :query [{:keys [user]} SecuredQueryParams]
           ;;     :return TopicList
           ;;     :summary "List user subscriptions to topics for given webhook"
           ;;     :description "Returns user subscriptions to topics for given webhook"
           ;;     (ok (get-subscriptions id user)))
           (context "/topics" []
                    (GET "/" []
                         :query [params SecuredQueryParams]
                         :return TopicList
                         :summary "List notification topics"
                         :description "Returns all of the notification topics defined"
                         (ok (list-topics)))
                    ))









