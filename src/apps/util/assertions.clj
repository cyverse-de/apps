(ns apps.util.assertions
  "Assertions for apps services."
  (:require [clojure-commons.exception-util :as cxu]))

(defmacro assert-not-nil
  "Throws an exception if the result of a group of expressions is nil.

   Parameters:
     id-field - the name of the field to use when storing the ID.
     id       - the identifier to store in the ID field."
  [[id-field id] & body]
  `(let [res# (do ~@body)]
     (if (nil? res#)
       (cxu/not-found (str "The item with the following ID could not be found: " ~id)
                      ~id-field (str ~id))
       res#)))

(defn assert-app-version
  "Throws a not-found exception if the given app is emtpy.

   Parameters:
     app-id     - the app identifier to include in the exception message.
     version-id - the app version identifier to include in the exception message."
  [[app-id version-id] app]
  (when-not app
    (cxu/not-found "Version ID not found for given App ID."
                   :app_id     app-id
                   :version_id version-id))
  app)
