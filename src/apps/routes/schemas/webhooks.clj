(ns apps.routes.schemas.webhooks
  (:use [common-swagger-api.schema :only [describe NonBlankString]]
        [schema.core :only [defschema optional-key]])
  (:import [java.util UUID]))

(def WebhookIdParam (describe UUID "A UUID that is used to identify the Webhook"))

(defschema Webhook
           {(optional-key :id) WebhookIdParam
            :type (describe NonBlankString "Type of webhook subscription")
            :url (describe NonBlankString "Url to post the notification")})
(defschema WebhookList
           {:webhooks (describe [Webhook] "A List of webhooks")})
