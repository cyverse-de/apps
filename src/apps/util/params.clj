(ns apps.util.params
  (:require
   [apps.util.conversions :as conversions]
   [clojure.string :refer [lower-case]]))

(defn- blank?
  "Returns true if the argument is nil or a blank string."
  [s]
  (cond (nil? s)          true
        (not (string? s)) false
        :else             (clojure.string/blank? s)))

(defn optional-string
  "Extracts an optional string argument from a map."
  ([ks m]
   (optional-string ks m nil))
  ([ks m d]
   (let [v (first (remove blank? (map m ks)))]
     (if-not (nil? v) v d))))

(defn optional-long
  "Extracts an optional long argument from a map."
  ([ks m]
   (optional-long ks m nil))
  ([ks m d]
   (let [v (first (remove blank? (map m ks)))]
     (if-not (nil? v)
       (if (string? v) (conversions/to-long v) v)
       d))))

(defn as-keyword
  "Converts a string to a lower-case keyword."
  [s]
  (keyword (lower-case s)))

(defn optional-keyword
  "Extracts an optional keyword argument from a map."
  ([ks m]
   (optional-keyword ks m nil))
  ([ks m d]
   (let [v (first (remove blank? (map m ks)))]
     (if (nil? v) d (as-keyword v)))))

(defn optional-vector
  "Extracts an optional vector argument from a map."
  ([ks m]
   (optional-vector ks m nil))
  ([ks m d]
   (let [v (first (remove blank? (map m ks)))]
     (cond (vector? v) v
           (nil? v)    d
           :else       [v]))))
