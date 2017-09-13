(ns apps.webhooks
  (:use [korma.core :exclude [update]]
        [korma.db :only [transaction]]
        [apps.persistence.users :only [get-user-id]])
  (:require [clojure.tools.logging :as log]
            [clojure-commons.exception-util :as cxu]))
(defn list-webhooks [{:keys [user]}]
  (transaction
   {:webhooks (select [:webhooks :w]
                      (join [:users :u] {:w.user_id :u.id})
                      (join [:webhooks_type :wt] {:w.type_id :wt.id})
                      (fields [:w.id :id] [:wt.type :type] [:w.url :url])
                      (where {(sqlfn regexp_replace :u.username "@.*" "")
                              user}))}))

(defn delete-webhook [id user]
  (log/warn user "deleting webhook" id)
  (delete :webhooks (where {:id id}))
  nil)

(defn get-webhook [id user]
  (first (select [:webhooks :w]
                 (join [:users :u] {:w.user_id :u.id})
                 (join [:webhooks_type :wt] {:w.type_id :wt.id})
                 (fields [:w.id :id] [:wt.type :type] [:w.url :url])
                 (where {:w.id
                         id}))))

(defn get-type-id [type]
  (if-let [type_id (:id (first (select :webhooks_type (fields :id) (where {:type type}))))]
    type_id
    (cxu/bad-request (str "Unable to find webhook type: " type))))

(defn add-webhook [user {:keys [url type]}]
  (let [user_id (get-user-id user)
        type_id (get-type-id type)]
    (println "type_id: " type_id)
    (insert :webhooks (values {:user_id user_id
                               :url url
                               :type_id type_id}))))
