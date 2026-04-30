(ns apps.clients.vice
  (:require
   [apps.util.config :as cfg]
   [cemerick.url :as curl]
   [clj-http.client :as http]))

(defn- app-exposer-url
  [& components]
  (str (apply curl/url (cfg/app-exposer-base-url) components)))

(defn stop-job
  [uuid]
  (http/post (app-exposer-url "vice" uuid "save-and-exit")
             {:throw-exceptions false
              :as               :json}))

(defn submit-job
  [job]
  (http/post (app-exposer-url "vice" "launch")
             {:content-type :json
              :form-params  job}))

(defn update-permissions
  "Pushes an updated list of allowed users to app-exposer, which routes the update to the operator managing the
   analysis."
  [analysis-id allowed-users]
  (http/put (app-exposer-url "vice" analysis-id "permissions")
            {:content-type     :json
             :form-params      {:allowedUsers allowed-users}
             :throw-exceptions :false
             :as               :json}))
