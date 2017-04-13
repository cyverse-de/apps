(ns apps.tools.private
  (:use [apps.validation :only [verify-tool-name-location validate-tool-not-used]]
        [korma.db :only [transaction]])
  (:require [apps.clients.permissions :as permissions]
            [apps.containers :as containers]
            [apps.persistence.tools :as persistence]
            [apps.tools :as tools]
            [apps.util.config :as cfg]))

(defn- restrict-private-tool-setting
  [setting max]
  (if (or (< setting 1) (< max setting))
    max
    setting))

(defn- restrict-private-tool-container
  "Restrict the networking, CPU shares, and memory limits for the tool's container."
  [{:keys [cpu_shares memory_limit] :or {cpu_shares   (cfg/private-tool-cpu-shares)
                                         memory_limit (cfg/private-tool-memory-limit)}
    :as container}]
  (assoc container :network_mode "none"
                   :cpu_shares   (restrict-private-tool-setting cpu_shares   (cfg/private-tool-cpu-shares))
                   :memory_limit (restrict-private-tool-setting memory_limit (cfg/private-tool-memory-limit))))

(defn- restrict-private-tool
  "Set restricted flag, time limit, and default type."
  [{:keys [time_limit_seconds] :or {time_limit_seconds (cfg/private-tool-time-limit-seconds)}
    :as tool}]
  (assoc tool :type               "executable"
              :restricted         true
              :time_limit_seconds (restrict-private-tool-setting
                                    time_limit_seconds (cfg/private-tool-time-limit-seconds))))

(defn- ensure-default-implementation
  "If implementation details were not given, then some defaults are populated with info about the current user."
  [{:keys [email first-name last-name]} implementation]
  (or implementation
      {:test              {:input_files [] :output_files []}
       :implementor       (str first-name " " last-name)
       :implementor_email email}))

(defn add-private-tool
  "Adds a private tool to the database, returning the tool details added."
  [{:keys [shortUsername] :as user}
   {:keys [container implementation] :as tool}]
  (verify-tool-name-location tool)
  (transaction
    (let [tool-id (-> tool
                      restrict-private-tool
                      (assoc :implementation (ensure-default-implementation user implementation))
                      persistence/add-tool)]
      (containers/add-tool-container tool-id (restrict-private-tool-container container))
      (permissions/register-private-tool shortUsername tool-id)
      (tools/get-tool shortUsername tool-id))))
