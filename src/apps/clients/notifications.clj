(ns apps.clients.notifications
  (:require [apps.clients.iplant-groups :as groups-client]
            [apps.clients.notifications.app-sharing :as asn]
            [apps.clients.notifications.job-sharing :as jsn]
            [apps.clients.notifications.tool-sharing :as tool-notifications]
            [apps.persistence.app-metadata :as amp]
            [apps.persistence.jobs :as jp]
            [apps.persistence.tool-requests :as tp]
            [apps.service.util :refer [valid-uuid?]]
            [apps.util.config :as config]
            [cemerick.url :as curl]
            [cheshire.core :as cheshire]
            [clj-http.client :as http]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [pandect.algo.sha256 :refer [sha256]]))

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

(defn interapps-url
  "Returns the externally accessible URL to the interactive app as a URL from
   cemerick.url.

   Example usage:
       (str (interapps-url (curl/url (config/interapps-base)) username external-id))
     Returns:
       https://abb9730df.cyverse.run"
  [{host :host :as base} username external-id]
  (assoc base :host (str "a" (-> (str username external-id) sha256 (subs 0 8)) "." host)))

(defn- get-notification-type
  "Determines the notification type to use for an app ID. In general, we only want one type of notification
   per app, but because the notification type is determined by the tool, it's possible to have more than one
   notification type per app. Currently, tools that use different notification types will never appear in the
   same app, so this function arbitrarily takes the first notification type associated with the app for now.
   Also, apps that do not run in the DE will always have a notification type of `analysis`."
  [app-id app-version-id]
  (or (when (valid-uuid? app-id) (first (amp/get-app-notification-types app-version-id)))
      "analysis"))

(defn- format-job-status-update
  "Formats a job status update notification to send to the notification agent."
  [username email-address {job-name :name app-id :app_id app-version-id :app_version_id :as job-info} message]
  {:type           (get-notification-type app-id app-version-id)
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
                          :analysisresultsfolder (:resultfolderid job-info)
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

(defn send-interactive-job-status-update
  "Sends notification of an interactive job status update to the user."
  ([username email-address {status :status user-id :user_id :as job-info} {external-id :external_id}]
   (if-not (jp/completed? status)
     (let [access-url (str (interapps-url (curl/url (config/interapps-base)) user-id external-id))]
       (send-job-status-update username email-address (assoc job-info :access_url access-url)))
     (send-job-status-update username email-address job-info)))
  ([{username :shortUsername email-address :email} job-info job-step-info]
   (send-interactive-job-status-update username email-address job-info job-step-info)))

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

(defn send-general-analysis-sharing-failure-notification
  [sharer async-task-id]
  (guarded-send-notification (jsn/format-general-sharing-failure-notification sharer async-task-id)))

(defn send-analysis-unsharing-notifications
  [sharer sharee responses]
  (->> (jsn/format-unsharing-notifications sharer sharee responses)
       (map guarded-send-notification)
       dorun))

(defn send-general-analysis-unsharing-failure-notification
  [sharer async-task-id]
  (guarded-send-notification (jsn/format-general-unsharing-failure-notification sharer async-task-id)))

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

(defn- format-community-admin-notification
  [integrator-name app-name {:keys [id email]} community-names]
  {:type           "apps"
   :user           id
   :subject        (str "Community Add Request for app: " app-name)
   :email          true
   :email_template "app_community_request"
   :payload        {:email_address  email
                    :app_name       app-name
                    :app_integrator integrator-name
                    :community_list (string/join "\n" community-names)}})

(defn send-community-admin-notification
  [username integrator-name app-name admin community-names]
  (guarded-send-notification (format-community-admin-notification integrator-name
                                                                  app-name
                                                                  (groups-client/lookup-subject username admin)
                                                                  community-names)))

(defn- format-app-published-notification
  [app-name app-id {:keys [id email]}]
  {:type           "apps"
   :user           id
   :subject        (str "App " app-name " published")
   :email          true
   :email_template "app_publication_completion"
   :payload        {:email_address email
                    :appname       app-name
                    :appid         app-id}})

(defn send-app-published-notification
  [username app-name {app-id :app_id requestor-username :requestor}]
  (guarded-send-notification
   (format-app-published-notification app-name
                                      app-id
                                      (groups-client/lookup-subject username requestor-username))))
