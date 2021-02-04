(ns apps.clients.analyses
  (:require [apps.util.config :as config]
            [cemerick.url :as curl]
            [clj-http.client :as http]))

(defn- analyses-url
  [& components]
  (str (apply curl/url (config/analyses-base) components)))

(defn get-concurrent-job-limit
  [username]
  (:body (http/get (analyses-url "settings" "concurrent-job-limits" username)
                   {:as :json})))
