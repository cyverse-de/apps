(ns apps.clients.notifications
  (:require [cemerick.url :as curl]
            [cheshire.core :as cheshire]
            [clj-http.client :as http]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [apps.clients.notifications.app-sharing :as asn]
            [apps.clients.notifications.job-sharing :as jsn]
            [apps.clients.notifications.tool-sharing :as tool-notifications]
            [apps.persistence.jobs :as jp]
            [apps.persistence.tool-requests :as tp]
            [apps.util.config :as config])
  (:use [pandect.algo.sha256 :only [sha256]]
        [cemerick.url :only [url]]))

(def ^:private emailable-job-statuses
  #{jp/completed-status
    jp/failed-status
    jp/impending-cancellation-status})

(defn notificationagent-url
  [& components]
  (str (apply curl/url (config/notification-agent-base) components)))

(defn format-timestamp
  "Formats a timestamp in a standard format."
  [timestamp]
  (if-not (or (string/blank? timestamp) (= "0" timestamp))
    (tf/unparse (:date-time tf/formatters) (tc/from-long (Long/parseLong timestamp)))
    ""))

(defn- send-notification
  "Sends a notification to a user."
  [m]
  (http/post (notificationagent-url "notification")
             {:content-type :json
              :body (cheshire/encode m)}))

(defn- guarded-send-notification
  "Sends a notification to a user, logging an error if an exception occurs."
  [m]
  (try
    (send-notification m)
    (catch Exception e
      (log/error e "unable to send notification:" (cheshire/encode m)))))

(defn- send-email?
  [job-info]
  (boolean (and (:notify job-info false)
                (emailable-job-statuses (:status job-info)))))


(defn- interapps-url
  "Returns the externally accessible URL to the interactive app as a URL from
   cemerick.url.

   Example usage:
       (str (interapps-url (url (config/interapps-base)) job-info))
     Returns:
       https://abb9730df.cyverse.run"
  [{host :host :as base} {user-id :user_id analysis-id :uuid}]
  (assoc base :host (str "a" (-> (str user-id analysis-id) sha256 (subs 0 8)) "." host)))

(defn- format-job-status-update
  "Formats a job status update notification to send to the notification agent."
  [username email-address {job-name :name :as job-info} message]
  {:type           "analysis"
   :user           username
   :subject        (str job-name " status changed.")
   :message        message
   :email          (send-email? job-info)
   :email_template "analysis_status_change"
   :payload        (assoc job-info
                     :analysisname          (:name job-info)
                     :analysisdescription   (:description job-info)
                     :analysisstatus        (:status job-info)
                     :analysisstartdate     (format-timestamp (:startdate job-info))
                     :email_address         email-address
                     :action                "job_status_change"
                     :user                  username)})

(defn send-job-status-update
  "Sends notification of an Agave or DE job status update to the user."
  ([username email-address job-info message]
   (try
     (send-notification (format-job-status-update username email-address job-info message))
     (catch Exception e
       (log/warn e "unable to send job status update notification for" (:id job-info)))))
  ([username email-address {job-name :name :as job-info}]
   (send-job-status-update username email-address job-info (str job-name " " (string/lower-case (:status job-info)))))
  ([{username :shortUsername email-address :email} job-info]
   (send-job-status-update username email-address job-info)))

(defn- format-tool-request-notification
  [tool-req user-details]
  (let [{:keys [comments]} (last (:history tool-req))]
    {:type           "tool_request"
     :user           (:shortUsername user-details)
     :subject        (str "Tool Request " (:name tool-req) " Submitted")
     :email          true
     :email_template "tool_request_submitted"
     :payload        (assoc tool-req
                       :email_address (:email user-details)
                       :toolname      (:name tool-req)
                       :comments      comments)}))

(defn send-tool-request-notification
  "Sends notification of a successful tool request submission to the user."
  [tool-req user-details]
  (try
    (send-notification (format-tool-request-notification tool-req user-details))
    (catch Exception e
      (log/warn e "unable to send tool request submission notification for" tool-req))))

(defn- format-tool-request-update-notification
  [tool-req user-details]
  (let [{:keys [status comments]} (last (:history tool-req))]
    {:type           "tool_request"
     :user           (:shortUsername user-details)
     :subject        (str "Tool Request " (:name tool-req) " Status Changed to " status)
     :email          true
     :email_template (tp/email-template-for status)
     :payload        (assoc tool-req
                       :email_address (:email user-details)
                       :toolname      (:name tool-req)
                       :comments      comments
                       :status        status)}))

(defn send-tool-request-update-notification
  "Sends notification of a tool request status change to the user."
  [tool-req user-details]
  (try
    (send-notification (format-tool-request-update-notification tool-req user-details))
    (catch Exception e
      (log/warn e "unable to send tool request update notification for" tool-req))))

(defn send-app-sharing-notifications
  "Sends app sharing notifications."
  [sharer sharee responses]
  (->> (asn/format-sharing-notifications sharer sharee responses)
       (map guarded-send-notification)
       dorun))

(defn send-app-unsharing-notifications
  "Sends app unsharing notifications."
  [sharer sharee responses]
  (->> (asn/format-unsharing-notifications sharer sharee responses)
       (map guarded-send-notification)
       dorun))

(defn send-analysis-sharing-notifications
  [sharer sharee responses]
  (->> (jsn/format-sharing-notifications sharer sharee responses)
       (map guarded-send-notification)
       dorun))

(defn send-analysis-unsharing-notifications
  [sharer sharee responses]
  (->> (jsn/format-unsharing-notifications sharer sharee responses)
       (map guarded-send-notification)
       dorun))

(defn send-tool-sharing-notifications
  [sharer sharee responses]
  (->> (tool-notifications/format-sharing-notifications sharer sharee responses)
       (map guarded-send-notification)
       dorun))

(defn send-tool-unsharing-notifications
  [sharer sharee responses]
  (->> (tool-notifications/format-unsharing-notifications sharer sharee responses)
       (map guarded-send-notification)
       dorun))
