(ns apps.clients.requests
  (:require [cemerick.url :as curl]
            [clj-http.client :as http]
            [apps.util.config :as cfg]))

(defn- requests-url
  [& components]
  (str (apply curl/url (cfg/requests-base-url) components)))

(defn list-vice-requests
  [username]
  (:body
    (http/get (requests-url "requests")
              {:query-params {:request-type     "vice"
                              :requesting-user  username}
               :as           :json})))
