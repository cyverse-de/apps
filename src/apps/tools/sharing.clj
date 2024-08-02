(ns apps.tools.sharing
  (:require [apps.clients.notifications :as cn]
            [apps.clients.permissions :as perms-client]
            [apps.persistence.tools :as tools-db]
            [apps.tools.permissions :as perms]
            [clojure-commons.error-codes :as error-codes]
            [clojure-commons.template :refer [render]]
            [slingshot.slingshot :refer [try+]]))

(defn- get-tool-name
  [tool-id {tool-name :name}]
  (or tool-name (str "tool ID " tool-id)))

(def tool-sharing-formats
  {:not-found    "tool ID {{tool-id}} does not exist"
   :load-failure "unable to load permissions for {{tool-id}}: {{detail}}"
   :not-allowed  "insufficient privileges for tool ID {{tool-id}}"})

(defn- tool-sharing-msg
  ([reason-code tool-id]
   (tool-sharing-msg reason-code tool-id nil))
  ([reason-code tool-id detail]
   (render (tool-sharing-formats reason-code)
           {:tool-id tool-id
            :detail  (or detail "unexpected error")})))

(defn- tool-sharing-success
  [tool-id tool level]
  {:tool_id    (str tool-id)
   :tool_name  (get-tool-name tool-id tool)
   :permission level
   :success    true})

(defn- tool-sharing-failure
  [tool-id tool level reason]
  {:tool_id    (str tool-id)
   :tool_name  (get-tool-name tool-id tool)
   :permission level
   :success    false
   :error      {:error_code error-codes/ERR_BAD_REQUEST
                :reason     reason}})

(defn tool-unsharing-success
  [tool-id tool]
  {:tool_id   (str tool-id)
   :tool_name (get-tool-name tool-id tool)
   :success   true})

(defn tool-unsharing-failure
  [tool-id tool reason]
  {:tool_id   (str tool-id)
   :tool_name (get-tool-name tool-id tool)
   :success   false
   :error     {:error_code error-codes/ERR_BAD_REQUEST
               :reason     reason}})

(defn share-tool-with-subject
  [{username :shortUsername} sharee tool-id level]
  (if-let [tool (first (tools-db/get-tools-by-id [tool-id]))]
    (let [share-failure (partial tool-sharing-failure tool-id tool level)]
      (try+
       (if-not (perms/has-tool-permission username tool-id "own")
         (share-failure (tool-sharing-msg :not-allowed tool-id))
         (if-let [failure-reason (perms-client/share-tool tool-id sharee level)]
           (share-failure failure-reason)
           (tool-sharing-success tool-id tool level)))
       (catch [:type :apps.tools.permissions/permission-load-failure] {:keys [reason]}
         (share-failure (tool-sharing-msg :load-failure tool-id reason)))))
    (tool-sharing-failure tool-id nil level (tool-sharing-msg :not-found tool-id))))

(defn unshare-tool-with-subject
  [{username :shortUsername} sharee tool-id]
  (if-let [tool (first (tools-db/get-tools-by-id [tool-id]))]
    (let [share-failure (partial tool-unsharing-failure tool-id tool)]
      (try+
       (if-not (perms/has-tool-permission username tool-id "own")
         (share-failure (tool-sharing-msg :not-allowed tool-id))
         (if-let [failure-reason (perms-client/unshare-tool tool-id sharee)]
           (share-failure failure-reason)
           (tool-unsharing-success tool-id tool)))
       (catch [:type :apps.tools.permissions/permission-load-failure] {:keys [reason]}
         (share-failure (tool-sharing-msg :load-failure tool-id reason)))))
    (tool-unsharing-failure tool-id nil (tool-sharing-msg :not-found tool-id))))

(defn- share-tools-with-subject
  [sharer {sharee :subject :keys [tools]}]
  (let [responses (for [{:keys [tool_id permission]} tools]
                    (share-tool-with-subject sharer sharee tool_id permission))]
    (cn/send-tool-sharing-notifications (:shortUsername sharer) sharee responses)
    {:subject sharee
     :tools   responses}))

(defn share-tools
  [user sharing-requests]
  {:sharing (mapv (partial share-tools-with-subject user) sharing-requests)})

(defn- unshare-tools-with-subject
  [sharer {sharee :subject :keys [tools]}]
  (let [responses (mapv (partial unshare-tool-with-subject sharer sharee) tools)]
    (cn/send-tool-unsharing-notifications (:shortUsername sharer) sharee responses)
    {:subject sharee
     :tools   responses}))

(defn unshare-tools
  [user unsharing-requests]
  {:unsharing (mapv (partial unshare-tools-with-subject user) unsharing-requests)})
