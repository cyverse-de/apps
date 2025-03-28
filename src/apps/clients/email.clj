(ns apps.clients.email
  (:require [apps.util.config :as config]
            [cemerick.url :as curl]
            [cheshire.core :as json]
            [clj-http.client :as client]))

(defn send-email
  "Sends an e-mail message via the iPlant e-mail service."
  [& {:keys [to from-addr from-name subject template values]}]
  (client/post
   (config/iplant-email-base-url)
   {:content-type :json
    :form-params  {:to        to
                   :from-addr from-addr
                   :from-name from-name
                   :subject   subject
                   :template  template
                   :values    values}}))

(defn send-app-deletion-notification
  "Sends an app deletion email message to the app integrator."
  [integrator-name integrator-email app-name system-id app-id]
  (let [app-link (str (curl/url (config/ui-base-url) "apps" system-id app-id "launch"))
        template-values {:name     integrator-name
                         :app_name app-name
                         :app_link app-link}]
    (send-email
     :to        integrator-email
     :from-addr (config/app-deletion-notification-src-addr)
     :subject   (format (config/app-deletion-notification-subject) app-name)
     :template  "app_deletion_notification"
     :values    template-values)))

(defn send-app-publication-request-email
  "Sends an app publication request email message to DE administrators."
  [user app-name publication-request-id untrusted-tools]
  (send-email
   :to        (config/app-publication-request-email-dest-addr)
   :from-addr (config/app-publication-request-email-src-addr)
   :subject   (config/app-publication-request-email-subject)
   :template  "app_publication_request"
   :values    {:username                user
               :appname                 app-name
               :environment             (config/env-name)
               :apppublicationrequestid publication-request-id
               :privatetoollist         (json/encode untrusted-tools {:pretty true})}))
