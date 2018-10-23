(ns apps.tools.private
  (:use [apps.constants :only [executable-tool-type]]
        [apps.validation :only [verify-tool-name-version validate-tool-not-used]]
        [korma.db :only [transaction]]
        [slingshot.slingshot :only [throw+]])
  (:require [apps.clients.permissions :as perms-client]
            [apps.containers :as containers]
            [apps.persistence.tools :as persistence]
            [apps.tools :as tools]
            [apps.tools.permissions :as perms]
            [apps.util.config :as cfg]
            [apps.validation :as validation]))

(defn- validate-image-not-deprecated
  [image-info]
  (let [image (containers/find-matching-image image-info)]
    (when (:deprecated image)
    (throw+ {:type  :clojure-commons.exception/bad-request-field
             :error "Image is deprecated and should not be used in new tools."
             :image image}))))

(defn- restrict-private-tool-setting
  [setting max]
  (if (or (< setting 1) (< max setting))
    max
    setting))

(defn- restrict-private-tool-container
  "Restrict the networking, CPU shares, and memory limits for the tool's container."
  [{:keys [pids_limit memory_limit max_cpu_cores] :or {pids_limit    (cfg/private-tool-pids-limit)
                                                       memory_limit  (cfg/private-tool-memory-limit)
                                                       max_cpu_cores (cfg/private-tool-max-cpu-cores)}
    :as container}]
  (assoc container :network_mode "none"
                   :max_cpu_cores (restrict-private-tool-setting max_cpu_cores (cfg/tool-max-cpu-cores))
                   :pids_limit    (restrict-private-tool-setting pids_limit   (cfg/private-tool-pids-limit))
                   :memory_limit  (restrict-private-tool-setting memory_limit (cfg/tool-memory-limit))))

(defn- set-private-tool-defaults
  "Set the default pid/memory/cpu restrictions for a private tool, if they're unset"
  [{:keys [pids_limit memory_limit max_cpu_cores] :as container}]
  (assoc container :pids_limit    (or pids_limit (cfg/private-tool-pids-limit))
                   :memory_limit  (or memory_limit (cfg/private-tool-memory-limit))
                   :max_cpu_cores (or max_cpu_cores (cfg/private-tool-max-cpu-cores))))

(defn- restrict-private-tool-time-limit
  "Restrict the tool's time limit setting."
  [{:keys [time_limit_seconds] :or {time_limit_seconds (cfg/private-tool-time-limit-seconds)}
    :as tool}]
  (assoc tool :time_limit_seconds (restrict-private-tool-setting time_limit_seconds
                                                                 (cfg/private-tool-time-limit-seconds))))

(defn- restrict-private-tool
  "Set restricted flag, time limit, and default type."
  [{:keys [type] :as tool}]
  (-> tool
      (assoc
        :restricted true
        :type       (or type executable-tool-type))
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
  (validate-image-not-deprecated (:image container))
  (verify-tool-name-version tool)
  (transaction
    (let [tool-id (-> tool
                      restrict-private-tool
                      (assoc :implementation (ensure-default-implementation user implementation))
                      persistence/add-tool)]
      (containers/add-tool-container tool-id (restrict-private-tool-container (set-private-tool-defaults container)))
      (perms-client/register-private-tool shortUsername tool-id)
      (tools/get-tool shortUsername tool-id))))

(defn update-private-tool
  [user {:keys [container time_limit_seconds] tool-id :id :as tool}]
  (perms/check-tool-permissions user "write" [tool-id])
  (validation/validate-tool-not-public tool-id)
  (when container
    (validate-image-not-deprecated (:image container)))
  (transaction
    (let [current-tool       (persistence/get-tool tool-id)
          current-time-limit (:time_limit_seconds current-tool)
          tool               (-> tool
                                 (dissoc :type :restricted)
                                 (assoc :time_limit_seconds (or time_limit_seconds current-time-limit))
                                 restrict-private-tool-time-limit)]
      (tools/verify-tool-name-version-for-update current-tool tool)
      (persistence/update-tool tool)
      (when container
        (containers/set-tool-container tool-id false (restrict-private-tool-container container)))))
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
