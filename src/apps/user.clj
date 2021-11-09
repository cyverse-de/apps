(ns apps.user
  (:use [apps.util.config :only [uid-domain]]
        [slingshot.slingshot :only [throw+]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [apps.clients.iplant-groups :as ipg]))

(def
  ^{:doc "The authenticated user or nil if the service is unsecured."
    :dynamic true}
  current-user nil)

(defn append-username-suffix [username]
  (let [suffix (str "@" (uid-domain))]
    (if (string/ends-with? username suffix)
      username
      (str username suffix))))

(defn user-from-attributes
  [user-attributes]
  (log/debug user-attributes)
  (let [uid (user-attributes :user)]
    (if (empty? uid)
      (throw+ {:type :clojure-commons.exception/not-authorized
               :error "Invalid user credentials provided."
               :user (select-keys user-attributes [:username :shortUsername :first-name :last-name :email])}))
    (-> (select-keys user-attributes [:password :email :first-name :last-name])
        (assoc :username (append-username-suffix uid)
               :shortUsername uid))))

(defmacro with-user
  "Performs a task with the given user information bound to current-user."
  [[user] & body]
  `(binding [current-user (user-from-attributes ~user)]
     (do ~@body)))

(defn store-current-user
  "Creates a function that takes a request, binds current-user to a new instance
   of org.iplantc.authn.user.User that is built from the user attributes found
   in the given params map, then passes request to the given handler."
  [handler & [opts]]
  (fn [request]
    (with-user [(:params request)] (handler request))))

(defn load-user-as-user
  "Loads information for the user with the given username, as another username."
  [username act-as-username]
  (let [short-username        (string/replace username #"@.*" "")
        short-act-as-username (string/replace act-as-username #"@.*" "")
        user-info             (ipg/lookup-subject short-act-as-username short-username)]
    {:username      (append-username-suffix short-username)
     :password      nil
     :email         (:email user-info)
     :shortUsername short-username
     :first-name    (:first-name user-info)
     :last-name     (:last-name user-info)}))

(defn load-user
  "Loads information for the user with the given username."
  [username]
  (load-user-as-user username (:shortUsername current-user)))

(def ^:private anonymous-username "anonymous")

(defn anonymous?
  "Returns true if the user is anonymous (that is, if no user is authenticated)."
  ([]
   (anonymous? current-user))
  ([{username :shortUsername :or {username anonymous-username}}]
   (= anonymous-username username)))
