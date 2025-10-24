(ns apps.clients.jex
  (:require [cemerick.url :as curl]
            [cheshire.core :as cheshire]
            [clj-http.client :as http]
            [apps.util.config :as cfg]))

(defn- jex-url
  [& components]
  (str (apply curl/url (cfg/jex-base-url) components)))

(defn stop-job
  [uuid]
  (http/delete (jex-url "stop" uuid)
               {:throw-exceptions false
                :as               :json}))

(defn submit-job
  [job params]
  (let [query-params (when (:disable-resource-tracking params)
                       {:query-params {"disable-resource-tracking" "true"}})]
    (http/post (jex-url)
               (merge {:body         (cheshire/encode job)
                       :content-type :json}
                      query-params))))
