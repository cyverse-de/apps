(ns apps.webhooks
  (:require
   [apps.persistence.users :refer [get-user-id]]
   [apps.user :refer [append-username-suffix]]
   [apps.util.db :refer [transaction]]
   [clojure-commons.exception-util :as cxu]
   [korma.core :refer [delete fields insert join select sqlfn values where]]))

(defn- get-webhook-topics [webhook-id]
  (map :topic (select [:webhooks_topic :wt]
                      (join [:webhooks_subscription :ws] {:wt.id :ws.topic_id})
                      (fields [:wt.topic])
                      (where {:ws.webhook_id webhook-id}))))

(defn- get-webhook-type [type]
  (first (select :webhooks_type (where {:type type}))))

(defn- format-webhook [{:keys [id type] :as webhook}]
  (assoc webhook
         :topics (get-webhook-topics id)
         :type (get-webhook-type type)))

(defn get-webhooks [user]
  (transaction
   (let [webhooks (select [:webhooks :w]
                          (join [:users :u] {:w.user_id :u.id})
                          (join [:webhooks_type :wt] {:w.type_id :wt.id})
                          (fields [:w.id :id] [:wt.type :type] [:w.url :url])
                          (where {(sqlfn :regexp_replace :u.username "@.*" "")
                                  user}))]
     (map format-webhook webhooks))))

(defn list-webhooks [user]
  {:webhooks (get-webhooks user)})

(defn get-type-id [{:keys [type]}]
  (if-let [type_id (:id (first (select :webhooks_type (fields :id) (where {:type type}))))]
    type_id
    (cxu/bad-request (str "Unable to find webhook type: " type))))

(defn- add-topic-subscription [webhook_id topic_name]
  (let [topic_id (:id (first (select :webhooks_topic (fields :id) (where {:topic topic_name}))))]
    (when topic_id
      (insert :webhooks_subscription (values {:webhook_id webhook_id
                                              :topic_id   topic_id})))))

(defn add-webhook [user_id url type topics]
  (let [type_id (get-type-id type)
        id (:id (insert :webhooks (values {:user_id user_id
                                           :url     url
                                           :type_id type_id})))]
    (doseq [topic_name topics]
      (add-topic-subscription id topic_name))))

(defn add-webhooks [user {:keys [webhooks]}]
  (let [user_id (get-user-id (append-username-suffix user))]
    (transaction
     (delete :webhooks (where {:user_id user_id}))
     (doseq [{:keys [url type topics]} webhooks]
       (add-webhook user_id url type topics))))
  (list-webhooks user))

(defn list-topics []
  {:topics (select :webhooks_topic)})

(defn list-types []
  {:webhooktypes (select :webhooks_type)})
