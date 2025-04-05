(ns apps.clients.notifications.tool-sharing
  (:require [apps.clients.notifications.common-sharing :as cs]
            [clojure.string :as string]
            [medley.core :refer [remove-vals]]))

(def notification-type "tools")
(def singular "tool")
(def plural "tools")

(defn- format-tool
  [response]
  (remove-vals nil? (select-keys response [:tool_id :tool_name])))

(defn- format-payload
  [action responses]
  {:action action
   :tools  (map format-tool responses)})

(defn- format-notification
  [recipient formats action sharer sharee responses]
  (when (seq responses)
    (let [response-desc  (string/join ", " (map :tool_name responses))
          response-count (count responses)]
      {:type    notification-type
       :user    recipient
       :subject (cs/format-subject formats singular plural action sharer sharee response-desc response-count)
       :message (cs/format-message formats singular plural action sharer sharee response-desc response-count)
       :payload (format-payload action responses)})))

(defn- format-sharer-notification
  [formats action sharer sharee responses]
  (format-notification sharer formats action sharer sharee responses))

(defn- format-sharee-notification
  [formats action sharer sharee responses]
  (format-notification sharee formats action sharer sharee responses))

(defn- format-sharing-notifications*
  "Formats sharing notifications for tools."
  [sharer sharee responses]
  (let [responses (group-by :success responses)]
    (remove nil?
            [(format-sharer-notification cs/sharer-success-formats cs/share-action sharer sharee (responses true))
             (format-sharee-notification cs/sharee-success-formats cs/share-action sharer sharee (responses true))
             (format-sharer-notification cs/failure-formats cs/share-action sharer sharee (responses false))])))

(defn format-sharing-notifications
  "Formats sharing notifications for tools."
  [sharer sharee responses]
  (cs/notifications-for-sharee format-sharing-notifications* sharer sharee responses))

(defn format-unsharing-notifications*
  "Formats unsharing notifications for tools."
  [sharer sharee responses]
  (let [responses (group-by :success responses)]
    (remove nil?
            [(format-sharer-notification cs/sharer-success-formats cs/unshare-action sharer sharee (responses true))
             (format-sharer-notification cs/failure-formats cs/unshare-action sharer sharee (responses false))])))

(defn format-unsharing-notifications
  "Formats unsharing notifications for tools."
  [sharer sharee responses]
  (cs/notifications-for-sharee format-unsharing-notifications* sharer sharee responses))
