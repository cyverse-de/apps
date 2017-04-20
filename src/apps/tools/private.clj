(ns apps.tools.private
  (:use [apps.validation :only [verify-tool-name-location validate-tool-not-used]]
        [korma.db :only [transaction]])
  (:require [apps.clients.permissions :as perms-client]
            [apps.containers :as containers]
            [apps.persistence.tools :as persistence]
            [apps.tools :as tools]
            [apps.tools.permissions :as perms]
            [apps.util.config :as cfg]
            [apps.validation :as validation]))

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

(defn- restrict-private-tool-time-limit
  "Restrict the tool's time limit setting."
  [{:keys [time_limit_seconds] :or {time_limit_seconds (cfg/private-tool-time-limit-seconds)}
    :as tool}]
  (assoc tool :time_limit_seconds (restrict-private-tool-setting time_limit_seconds
                                                                 (cfg/private-tool-time-limit-seconds))))

(defn- restrict-private-tool
  "Set restricted flag, time limit, and default type."
  [tool]
  (-> tool
      (assoc :restricted true
             :type       "executable")
      restrict-private-tool-time-limit))

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
      (perms-client/register-private-tool shortUsername tool-id)
      (tools/get-tool shortUsername tool-id))))

(defn update-private-tool
  [user {:keys [container time_limit_seconds] tool-id :id :as tool}]
  (perms/check-tool-permissions user "write" [tool-id])
  (validation/validate-tool-not-public tool-id)
  (let [current-time-limit (:time_limit_seconds (persistence/get-tool tool-id))
        tool (-> tool
                 (dissoc :type :restricted)
                 (assoc :time_limit_seconds (or time_limit_seconds current-time-limit))
                 restrict-private-tool-time-limit)]
    (persistence/update-tool tool)
    (when container
      (containers/set-tool-container tool-id false (restrict-private-tool-container container))))
  (tools/get-tool user tool-id))

(defn delete-private-tool
  "Deletes a private tool if user has `own` permission for the tool.
   If `force-delete` is not truthy, then the tool is validated as not in use by any apps."
  [user tool-id force-delete]
  (persistence/get-tool tool-id)
  (perms/check-tool-permissions user "own" [tool-id])
  (validation/validate-tool-not-public tool-id)
  (when-not force-delete
    (validate-tool-not-used tool-id))
  (tools/delete-tool tool-id))
