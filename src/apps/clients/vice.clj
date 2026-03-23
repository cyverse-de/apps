(ns apps.clients.vice
  (:require [cemerick.url :as curl]
            [cheshire.core :as cheshire]
            [clj-http.client :as http]
            [apps.util.config :as cfg]))

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
              :body         (cheshire/encode job)}))

(defn update-permissions
  "Pushes an updated list of allowed users to app-exposer, which routes the
   update to the operator managing the given analysis."
  [analysis-id allowed-users]
  (http/put (app-exposer-url "vice" analysis-id "permissions")
            {:content-type     :json
             :body             (cheshire/encode {:allowedUsers allowed-users})
             :throw-exceptions false
             :as               :json}))
