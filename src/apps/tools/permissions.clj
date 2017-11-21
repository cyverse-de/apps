(ns apps.tools.permissions
  (:use [clojure-commons.error-codes :only [clj-http-error?]]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [apps.clients.permissions :as permissions]
            [apps.persistence.tools :as tools-db]
            [clojure-commons.exception-util :as exception-util]
            [clojure.string :as string]))

(defn- list-non-existent-tool-ids
  [tool-id-set]
  (->> (tools-db/get-tools-by-id tool-id-set)
       (map :id)
       (set)
       (clojure.set/difference tool-id-set)))

(defn- validate-tools-existence
  [tool-ids]
  (let [missing-ids (list-non-existent-tool-ids (set tool-ids))]
    (when-not (empty? missing-ids)
      (exception-util/not-found (string/join " " ["tools" tool-ids "not found"])))))

(defn check-tool-permissions
  [user required-level tool-ids]
  (let [tool-ids            (set tool-ids)
        accessible-tool-ids (set (keys (permissions/load-tool-permissions user tool-ids required-level)))]
    (when-let [forbidden-tools (seq (clojure.set/difference tool-ids accessible-tool-ids))]
      (exception-util/forbidden (str "insufficient privileges for tools: " (string/join ", " forbidden-tools))))))

(defn has-tool-permission
  [user tool-id required-level]
  (try+
    (seq (permissions/load-tool-permissions user [tool-id] required-level))
    (catch clj-http-error? {:keys [body]}
      (throw+ {:type   ::permission-load-failure
               :reason (permissions/extract-error-message body)}))))

(defn- format-tool-permissions
  [perms {:keys [id name]}]
  {:id          id
   :name        name
   :permissions (perms id)})

(defn list-tool-permissions
  [{user :shortUsername} tool-ids params]
  (validate-tools-existence tool-ids)
  (check-tool-permissions user "read" tool-ids)
  (let [perms (permissions/list-tool-permissions user tool-ids params)
        tools (tools-db/get-tools-by-id tool-ids)]
    {:tools (mapv (partial format-tool-permissions perms) tools)}))
