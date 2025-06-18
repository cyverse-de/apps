(ns apps.util.conversions
  (:require
   [clojure.string :as string]
   [medley.core :refer [remove-vals]])
  (:import
   (com.google.common.primitives Doubles Ints)
   (java.sql Timestamp)))

(defn to-long
  "Converts a string to a long integer."
  [s]
  (try
    (Long/parseLong s)
    (catch Exception e
      (throw (IllegalArgumentException. e)))))

(defn date->long
  "Converts a Date object to a Long representation of its timestamp."
  ([date]
   (date->long date nil))
  ([date default]
   (if (nil? date) default (.getTime date))))

(defn long->timestamp
  "Converts a long value, which may contain an empty string, into an instance
  of java.sql.Timestamp."
  [ms]
  (let [ms (str ms)]
    (when-not (string/blank? ms) (Timestamp. (to-long ms)))))

(defn date->timestamp
  "Converts a date value into an instance of java.sql.Timestamp."
  [date]
  (when-not (nil? date) (Timestamp. (.getTime date))))

(def remove-nil-vals (partial remove-vals nil?))

(def remove-empty-vals
  (partial remove-vals #(and (sequential? %1) (empty? %1))))

(defn convert-rule-argument
  [arg type]
  (let [arg (string/trim arg)]
    (condp = type
      "Integer" (Ints/tryParse arg)
      "Double"  (Doubles/tryParse arg)
      arg)))
