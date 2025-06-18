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
