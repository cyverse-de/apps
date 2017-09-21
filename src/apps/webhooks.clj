(ns apps.webhooks
  (:use [korma.core :exclude [update]]
        [korma.db :only [transaction]]
        [apps.persistence.users :only [get-user-id]])
  (:require [clojure.tools.logging :as log]
            [clojure-commons.exception-util :as cxu]
            [apps.util.config :as config]
            [korma.core :as sql]))

(defn- get-webhook-topics [webhook-id]
  (map :topic (select [:webhooks_topic :wt]
                      (join [:webhooks_subscription :ws] {:wt.id :ws.topic_id})
                      (fields [:wt.topic])
                      (where {:ws.webhook_id webhook-id}))))

(defn- format-webhook [{:keys [id] :as webhook}]
  (assoc webhook :topics (get-webhook-topics id)))

(defn list-webhooks [user]
  (transaction
    (let [webhooks (select [:webhooks :w]
                           (join [:users :u] {:w.user_id :u.id})
                           (join [:webhooks_type :wt] {:w.type_id :wt.id})
                           (fields [:w.id :id] [:wt.type :type] [:w.url :url])
                           (where {(sqlfn regexp_replace :u.username "@.*" "")
                                   user}))]
      {:webhooks (map format-webhook webhooks)})))

(defn delete-webhook [id user]
  (log/warn user "deleting webhook and subscriptions" id)
  (transaction (let [user_id (get-user-id user)]
                 (delete :webhooks (where {:id id}))
                 (delete :webhooks_subscription (where {:id id})))) nil)


(defn get-webhook [id user]
  (let [webhook (first (select [:webhooks :w]
                               (join [:users :u] {:w.user_id :u.id})
                               (join [:webhooks_type :wt] {:w.type_id :wt.id})
                               (fields [:w.id :id] [:wt.type :type] [:w.url :url])
                               (where {:w.id
                                       id})))]
    (format-webhook webhook)))

(defn get-type-id [type]
  (if-let [type_id (:id (first (select :webhooks_type (fields :id) (where {:type type}))))]
    type_id
    (cxu/bad-request (str "Unable to find webhook type: " type))))

(defn- add-topic-subscription [webhook_id topic_name]
  (let [topic_id (:id (first (select :webhooks_topic (fields :id) (where {:topic topic_name}))))]
    (when topic_id
      (insert :webhooks_subscription (values {:webhook_id (log/spy webhook_id)
                                              :topic_id   topic_id})))))
(defn add-webhook [user {:keys [url type topics]}]
  (let [user_id (get-user-id (str user "@" (config/uid-domain)))
        type_id (get-type-id type)
        id (:id (insert :webhooks (values {:user_id user_id
                                           :url     url
                                           :type_id type_id})))]
    (println "type_id: " type_id)
    (doseq [topic_name topics]
      (add-topic-subscription id topic_name))
    (get-webhook id user)))

(defn list-topics []
  {:topics (select [:webhooks_topic :wt])})

(defn get-subscriptions [id user]
  (let [user_id (get-user-id (str user "@" (config/uid-domain)))]
    (log/warn id)
    (log/warn user_id)
    {:topics (select [:webhooks_topic :wt]
                     (join [:webhooks_subscription :ws] {:wt.id :ws.topic_id})
                     (fields [:wt.id :id] [:wt.topic])
                     (where {:ws.webhook_id id}))}))

(defn update-webhook [id user {:keys [url type topics]}]
  (transaction
    (let [type_id (get-type-id type)]
      (sql/update :webhooks
                  (set-fields {:url     url
                               :type_id type_id})
                  (where {:id id})))
    (delete :webhooks_subscription (where {:webhook_id id}))
    (doseq [topic_name topics]
      (add-topic-subscription id topic_name))
    (get-webhook id user)))


