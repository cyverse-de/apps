(ns apps.persistence.docker-registries
  (:use [apps.persistence.entities :only [docker-registries]]
        [apps.util.db :only [transaction]]
        [korma.core :exclude [update]])
  (:require [korma.core :as sql]
            [clojure.string :as string]))

(defn get-registries
  []
  (select docker-registries))

(defn get-registry
  [name]
  (first (select docker-registries
                 (where {:name name}))))

(defn get-registry-from-image
  [image-name]
  (let [parts (string/split image-name #"/")]
    (loop [n (count parts)]
      (when-not (= n 0)
        (if-let [reg (get-registry (string/join "/" (take n parts)))]
          reg
          (recur (- n 1)))))))

(defn add-registry
  [name username password]
  (insert docker-registries
          (values {:name name
                   :username username
                   :password password})))

(defn update-registry
  [name username password]
  (sql/update docker-registries (set-fields {:username username :password password}) (where {:name name})))

(defn add-or-update-registry
  [name username password]
  (if (get-registry name)
    (update-registry name username password)
    (add-registry name username password)))

(defn delete-registry
  [name]
  (delete docker-registries (where {:name name})))
