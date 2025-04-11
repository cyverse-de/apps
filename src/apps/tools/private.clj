(ns apps.tools.private
  (:require
   [apps.clients.permissions :as perms-client]
   [apps.constants :refer [executable-tool-type]]
   [apps.containers :as containers]
   [apps.persistence.tools :as persistence]
   [apps.tools :as tools]
   [apps.tools.permissions :as perms]
   [apps.util.config :as cfg]
   [apps.util.db :refer [transaction]]
   [apps.validation :refer [validate-tool-not-public validate-tool-not-used verify-tool-name-version]]
   [slingshot.slingshot :refer [throw+]]))

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
  [type {:keys [pids_limit memory_limit] :or {pids_limit   (cfg/private-tool-pids-limit)
                                              memory_limit (cfg/private-tool-memory-limit)}
         :as   container}]
  (assoc container
         :network_mode (if (= type "interactive") "bridge" "none")
         :pids_limit   (restrict-private-tool-setting pids_limit   (cfg/private-tool-pids-limit))
         :memory_limit (restrict-private-tool-setting memory_limit (cfg/private-tool-memory-limit))))

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
   {:keys [type container implementation] :as tool}]
  (validate-image-not-deprecated (:image container))
  (verify-tool-name-version tool)
  (transaction
   (let [tool-id (-> tool
                     restrict-private-tool
                     (assoc :implementation (ensure-default-implementation user implementation))
                     persistence/add-tool)]
     (containers/add-tool-container tool-id (restrict-private-tool-container type container))
     (perms-client/register-private-tool shortUsername tool-id)
     (tools/get-tool shortUsername tool-id false))))

(defn update-private-tool
  [user {:keys [type container time_limit_seconds] tool-id :id :as tool}]
  (perms/check-tool-permissions user "write" [tool-id])
  (validate-tool-not-public tool-id)
  (when container
    (validate-image-not-deprecated (:image container)))
  (transaction
   (let [current-tool       (persistence/get-tool tool-id)
         current-time-limit (:time_limit_seconds current-tool)
         tool               (-> tool
                                (dissoc :restricted)
                                (assoc :time_limit_seconds (or time_limit_seconds current-time-limit))
                                restrict-private-tool-time-limit)]
     (tools/verify-tool-name-version-for-update current-tool tool)
     (persistence/update-tool tool)
     (when container
       (containers/set-tool-container tool-id false (restrict-private-tool-container type container)))))
  (tools/get-tool user tool-id false))

(defn delete-private-tool
  "Deletes a private tool if user has `own` permission for the tool.
   If `force-delete` is not truthy, then the tool is validated as not in use by any apps."
  [user tool-id force-delete]
  (persistence/get-tool tool-id)
  (perms/check-tool-permissions user "own" [tool-id])
  (validate-tool-not-public tool-id)
  (when-not force-delete
    (validate-tool-not-used tool-id))
  (tools/delete-tool tool-id))
