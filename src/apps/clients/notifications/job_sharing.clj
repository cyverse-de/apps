(ns apps.clients.notifications.job-sharing
  (:require [apps.clients.notifications.common-sharing :as cs]
            [clojure.string :as string]
            [medley.core :refer [remove-vals]]))

(def notification-type "analysis")
(def singular "analysis")
(def plural "analyses")

(defn- format-analysis
  [category-keyword response]
  (remove-vals nil? (assoc (select-keys response [:analysis_id :analysis_name])
                           :category_id (str (category-keyword response)))))

(defn- format-payload
  [category-keyword action responses]
  {:action   action
   :analyses (map (partial format-analysis category-keyword) responses)})

(defn- format-notification
  [category-keyword recipient formats action sharer sharee responses]
  (when (seq responses)
    (let [response-desc  (string/join ", " (map :analysis_name responses))
          response-count (count responses)]
      {:type    notification-type
       :user    recipient
       :subject (cs/format-subject formats singular plural action sharer sharee response-desc response-count)
       :message (cs/format-message formats singular plural action sharer sharee response-desc response-count)
       :payload (format-payload category-keyword action responses)})))

(defn- format-sharer-notification
  [formats action sharer sharee responses]
  (format-notification :sharer_category sharer formats action sharer sharee responses))

(defn- format-sharee-notification
  [formats action sharer sharee responses]
  (format-notification :sharee_category sharee formats action sharer sharee responses))

(defn- format-sharing-notifications*
  "Formats sharing notifications for analyses."
  [sharer sharee responses]
  (let [responses (group-by :success responses)]
    (remove nil?
            [(format-sharer-notification cs/sharer-success-formats cs/share-action sharer sharee (responses true))
             (format-sharee-notification cs/sharee-success-formats cs/share-action sharer sharee (responses true))
             (format-sharer-notification cs/failure-formats cs/share-action sharer sharee (responses false))])))

(defn format-sharing-notifications
  "Formats sharing notifications for analyses."
  [sharer sharee responses]
  (cs/notifications-for-sharee format-sharing-notifications* sharer sharee responses))

(defn format-general-sharing-failure-notification
  "Formats a notification indicating that a sharing request failed unexpectedly. This notification means that
  there's a bug in the analysis sharing code."
  [sharer async-task-id]
  {:type    notification-type
   :user    sharer
   :subject "Analysis sharing request failed unexpectedly."
   :message "Analysis sharing request failed unexpectedly. Please contact support."
   :payload {:action cs/share-action :asyncTaskID async-task-id}})

(defn- format-unsharing-notifications*
  "Formats unsharing notifications for analyses."
  [sharer sharee responses]
  (let [responses (group-by :success responses)]
    (remove nil?
            [(format-sharer-notification cs/sharer-success-formats cs/unshare-action sharer sharee (responses true))
             (format-sharer-notification cs/failure-formats cs/unshare-action sharer sharee (responses false))])))

(defn format-unsharing-notifications
  "Formats unsharing notifications for analyses."
  [sharer sharee responses]
  (cs/notifications-for-sharee format-unsharing-notifications* sharer sharee responses))

(defn format-general-unsharing-failure-notification
  "Formats a notification indicating that an unsharing request failed unexpectedly. This notification means that
  there's a bug in the analysis unsharing code."
  [sharer async-task-id]
  {:type    notification-type
   :user    sharer
   :subject "Analysis unsharing request failed unexpectedly."
   :message "Analysis unsharing request failed unexpectedly. Please contact support."
   :payload {:action cs/unshare-action :asyncTaskID async-task-id}})
